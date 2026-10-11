package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.server.PreparedDataStore
import com.pocketrealm.server.publicationMinimums
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DESK-44/69/102: checkpointed CMaNGOS data generation and immutable
 * normal-mode publication — the desktop twin of the Android
 * importer/DataPreparationStore. Same stage model (DBC_MAPS → VMAP_EXTRACT →
 * VMAP_ASSEMBLE → MMAPS → MANIFEST) checkpointed through the
 * [ImportJournal] twin's data_stages rows, same zero-byte vmap repair pass,
 * same per-map mmap resume with terrain-backed coverage checks, and the same
 * flavor-scoped activation (per-flavor active pointer + keep-set prune)
 * through the SHARED [PreparedDataStore] — so the world starts from
 * `requireActive()` without the external python tool.
 *
 * Platform substitutions (the register: Context→DesktopStorageRoots,
 * Os.fsync→FileChannel.force, the staged `.so` tools→the staged Windows
 * extractor EXEs):
 *  - the tool set is native/bin/x64_Release/Extractors/{ad,
 *    vmap_extractor, vmap_assembler, MoveMapGen}.exe — the exact binaries
 *    tools/win_prepare_data.py drives, invoked with the same argument
 *    shapes as the Android `.so` lane (ad -i/-o/-e, vmap_extractor
 *    -s/-d/-o, per-map MoveMapGen positional map id + --workdir);
 *  - `android.os.Process.myPid()` → `ProcessHandle.current().pid()`;
 *  - `Os.rename` → Files.move with ATOMIC_MOVE and a plain-replace
 *    fallback; `Os.fsync(dir)` → no-op (Windows cannot open a directory
 *    through FileChannel; NTFS journals the rename — the ClientGenerationStore
 *    twin's documented posture);
 *  - the workspace stages under the foundations package's SCRATCH root
 *    (roots.scratch/data-prep) and publishes into
 *    content/o11-server/generations, the layout win_prepare_data.py and the
 *    shared PreparedDataStore both resolve;
 *  - the child process keeps the inherited environment (Windows CRT needs
 *    SystemRoot — the Android env-clearing would break every extractor) and
 *    the mmap checkpoint reports tile counts without the Android procfs
 *    child-CPU meter (no /proc on Windows; ImportProcessMetrics is the
 *    android-specific trim per the register).
 *
 * Resumability is checkpoint-based per stage like Android, and the per-map
 * mmap checkpoints survive a crash mid-map exactly like the device lane.
 */
internal open class DataPreparationStore(
    private val roots: DesktopStorageRoots,
    private val journal: ImportJournal,
    /** Explicit extractor directory override (tests; dev probes). */
    private val toolsDir: File? = null,
) {
    private val root = File(roots.content, "o11-server").apply { mkdirs() }
    private val generations = File(root, "generations").apply { mkdirs() }

    suspend fun prepare(
        importId: String,
        clientRoot: File,
        onTick: () -> Unit = {},
    ): PublishedData = withContext(Dispatchers.IO) {
        recover(importId, clientRoot, onTick)?.let { return@withContext it }
        // The data_stages checkpoints reference the imports table; the
        // desktop data-prep lane anchors its own row (the Android lane
        // reused the client import's row) before the first stage.
        journal.beginDataPreparationOrResume(importId, "data-prep//$importId")
        val stageRoot = stageDir(importId).apply { mkdirs() }
        check(clientRoot.isDirectory && File(clientRoot, "WoW.exe").isFile) {
            "the selected client generation does not contain WoW.exe"
        }
        requireTools()

        finiteStage(importId, DataStage.DBC_MAPS, stageRoot, onTick) {
            runTool(importId, DataStage.DBC_MAPS, stageRoot, AD_TOOL,
                listOf("-i", clientRoot.absolutePath, "-o", stageRoot.absolutePath, "-e", "3"))
            // R1-1/R6-3: the stage gate enforces the verified flavor's floors,
            // the same row the publication gate and the boot envelope use.
            val minimums = publicationMinimums(verifiedSourceFlavor(clientRoot).flavorId)
            requireCount(File(stageRoot, "dbc"), ".dbc", minimums.dbc)
            requireCount(File(stageRoot, "maps"), ".map", minimums.maps)
        }
        finiteStage(importId, DataStage.VMAP_EXTRACT, stageRoot, onTick) {
            runTool(importId, DataStage.VMAP_EXTRACT, stageRoot, VMAP_EXTRACTOR_TOOL,
                listOf("-s", "-d", File(clientRoot, "Data").absolutePath, "-o", stageRoot.absolutePath))
            requireCount(File(stageRoot, "Buildings"), null, 1)
        }
        repairInterruptedVmapExtraction(importId, clientRoot, stageRoot)
        finiteStage(importId, DataStage.VMAP_ASSEMBLE, stageRoot, onTick) {
            File(stageRoot, "vmaps").mkdirs()
            runTool(importId, DataStage.VMAP_ASSEMBLE, stageRoot, VMAP_ASSEMBLER_TOOL,
                listOf(File(stageRoot, "Buildings").absolutePath, File(stageRoot, "vmaps").absolutePath))
            requireCount(File(stageRoot, "vmaps"), ".vmtree", 1)
        }
        prepareMmaps(importId, stageRoot, clientRoot, onTick)
        publish(importId, stageRoot, clientRoot, onTick)
    }

    /**
     * The upstream extractor resumes by checking only whether an output path
     * exists. If the process is interrupted after fopen("wb") but before the
     * first write, a zero-byte model survives and every later resume skips it.
     * Remove only those impossible generated artifacts and let the extractor's
     * own incremental path recreate them; all valid Buildings remain untouched.
     */
    private suspend fun repairInterruptedVmapExtraction(
        importId: String,
        clientRoot: File,
        stageRoot: File,
    ) {
        val buildings = File(stageRoot, "Buildings")
        val interrupted = zeroLengthFiles(buildings)
        if (interrupted.isEmpty()) return
        val repairRoot = File(stageRoot, ".vmap-repair-${ProcessHandle.current().pid()}")
        check(!repairRoot.exists() || repairRoot.deleteRecursively()) {
            "cannot reset vmap repair workspace"
        }
        check(repairRoot.mkdirs()) { "cannot create vmap repair workspace" }
        try {
            runTool(importId, DataStage.VMAP_EXTRACT, stageRoot, VMAP_EXTRACTOR_TOOL,
                listOf("-s", "-d", File(clientRoot, "Data").absolutePath,
                    "-o", repairRoot.absolutePath), suffix = "repair")
            val repairedBuildings = File(repairRoot, "Buildings")
            interrupted.forEach { target ->
                val relative = target.relativeTo(buildings)
                val replacement = File(repairedBuildings, relative.path)
                check(replacement.isFile && replacement.length() > 0L) {
                    "vmap extraction did not regenerate: ${relative.invariantSeparatorsPath}"
                }
                atomicReplaceFromFile(target, replacement)
            }
        } finally {
            check(!repairRoot.exists() || repairRoot.deleteRecursively()) {
                "cannot remove vmap repair workspace"
            }
        }
        val remaining = zeroLengthFiles(buildings)
        check(remaining.isEmpty()) {
            "vmap extraction repair left zero-byte outputs: ${remaining.take(5).joinToString { it.name }}"
        }
        requireCount(buildings, null, 1)
    }

    private suspend fun finiteStage(
        importId: String,
        stage: DataStage,
        root: File,
        onTick: () -> Unit,
        body: suspend () -> Unit,
    ) {
        if (journal.dataStage(importId, stage)?.state == DataStageState.VERIFIED) return
        // Total 0 = unknown-length work; the UI renders an honest
        // indeterminate bar instead of a frozen "0/1" checkpoint for the
        // minutes these single-shot tools run. Completion records 1/1.
        journal.startDataStage(importId, stage, 0)
        onTick()
        try {
            body()
            journal.checkpointDataStage(importId, stage, 1, 1, directoryBytes(root), "complete", true)
            onTick()
        } catch (error: Throwable) {
            journal.failDataStage(importId, stage, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private suspend fun prepareMmaps(importId: String, root: File, clientRoot: File, onTick: () -> Unit) {
        val mapsDir = File(root, "maps")
        val vmapsDir = File(root, "vmaps")
        val mapIds = derivedMmapMapIds(mapsDir, vmapsDir)
        check(mapIds.isNotEmpty()) { "no map IDs available for mmap generation" }
        // Terrain-backed maps must always publish a navmesh header; vmtree-only
        // dungeon/test maps are best-effort (degenerate geometry legitimately
        // yields nothing) and are skipped with a journaled note instead of
        // failing the whole import.
        val terrainBacked = numericMapPrefixes(mapsDir, ADT_MAP_FILE).toSet()
        val prior = journal.dataStage(importId, DataStage.MMAPS)
        if (prior?.state == DataStageState.VERIFIED) return
        journal.startDataStage(importId, DataStage.MMAPS, mapIds.size, prior?.checkpoint)
        onTick()
        val mmaps = File(root, "mmaps").apply { mkdirs() }
        atomicWrite(File(root, "config.json"), "{}\n".toByteArray())
        atomicWrite(File(root, "offmesh.txt"), ByteArray(0))
        val completed = prior?.processed?.coerceIn(0, mapIds.size) ?: 0
        val skipped = mutableSetOf<Int>()
        try {
            for ((index, mapId) in mapIds.withIndex()) {
                coroutineContext.ensureActive()
                val header = File(mmaps, "%03d.mmap".format(mapId))
                if (index < completed && (header.isFile || mapId !in terrainBacked)) continue
                val output = runTool(importId, DataStage.MMAPS, root, MOVEMAPGEN_TOOL, listOf(
                    mapId.toString(), "--silent", "--threads", mmapThreads().toString(),
                    "--configInputPath", File(root, "config.json").absolutePath,
                    "--offMeshInput", File(root, "offmesh.txt").absolutePath,
                    "--workdir", root.absolutePath,
                ), suffix = "%03d".format(mapId), onTick = {
                    reportMmapProgress(importId, mapId, index, mapIds.size, mmaps)
                    onTick()  // prepareMmaps's notification tick; runTool's label is not a binding
                })
                // MoveMapGen exits 0 even when a per-map build fails; the
                // stdout scan (win_prepare_data.py's discipline) plus the
                // header check below are the only honest signals.
                check(MOVEMAP_FAILURE.findAll(output).none()) {
                    "mmap generator reported per-map failures for map $mapId"
                }
                if (!header.isFile) {
                    check(mapId !in terrainBacked) {
                        "mmap generator did not publish terrain-backed map $mapId"
                    }
                    skipped += mapId
                }
                journal.checkpointDataStage(importId, DataStage.MMAPS, index + 1, mapIds.size,
                    directoryBytes(mmaps), mmapCheckpointText(mapId, skipped))
                onTick()
            }
        requireCount(mmaps, ".mmap", terrainBacked.size)
        requireCount(mmaps, ".mmtile", 1)
        validateMmtileNames(mmaps, verifiedSourceFlavor(clientRoot))
            journal.checkpointDataStage(importId, DataStage.MMAPS, mapIds.size, mapIds.size,
                directoryBytes(mmaps),
                if (skipped.isEmpty()) "complete"
                else "complete; no-navmesh maps skipped: " + skipped.joinToString(",") { "%03d".format(it) },
                true)
        } catch (error: Throwable) {
            journal.failDataStage(importId, DataStage.MMAPS, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    /** One-second in-run progress for the user-visible card: tiles published
     * for the map under construction. (The Android twin also reports the
     * generator child's cumulative CPU through procfs; Windows has no /proc
     * and ImportProcessMetrics is the register's android-specific trim.) */
    private fun reportMmapProgress(importId: String, mapId: Int, index: Int, total: Int, mmaps: File) {
        val prefix = "%03d".format(mapId)
        val tiles = mmaps.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.size ?: 0
        journal.checkpointDataStage(importId, DataStage.MMAPS, index, total,
            directoryBytes(mmaps),
            "map %s (%d/%d) tiles %d".format(prefix, index + 1, total, tiles))
    }

    private fun mmapCheckpointText(mapId: Int, skipped: Set<Int>): String =
        if (mapId in skipped) "%03d skipped (no navmesh data)".format(mapId)
        else "%03d published".format(mapId)

    /**
     * win_prepare_data.py's mmtile name gate: mmtile names are packed digits
     * (%03d map + %02d x + %02d y). A name that does not match (a future
     * go%04u gameobject tile, say) must fail loudly instead of silently
     * inflating the publication counts. W2: wotlk's extractor additionally
     * emits the alt tile form %03u%02i%02i_NN.mmtile (cmangos-wotlk
     * MapBuilder.cpp:1090; its runtime loads both forms via
     * TILE_ALT_FILE_NAME_FORMAT, MoveMap.cpp:28), so the wotlk lane accepts
     * exactly one _NN suffix while the other lanes keep the strict 7-digit
     * contract (an alt tile there is cross-flavor contamination).
     */
    private fun validateMmtileNames(mmaps: File, flavor: ClientFlavor) {
        val pattern = mmtileNamePattern(flavor.flavorId)
        val offenders = mmaps.listFiles { file -> file.isFile && file.name.endsWith(".mmtile") }
            .orEmpty()
            .filter { !pattern.matches(it.nameWithoutExtension) }
        check(offenders.isEmpty()) {
            "unexpected mmtile name (expected ${mmtileExpectedShape(flavor.flavorId)}): " +
                offenders.take(3).joinToString { it.name }
        }
    }

    /**
     * Runs one extractor and returns its captured stdout. The process keeps
     * the inherited environment (the Windows CRT needs SystemRoot); stdout and
     * stderr land in the stage's logs directory keyed by stage, suffix and
     * attempt, so a failed run's evidence survives for support bundles.
     */
    internal open suspend fun runTool(
        importId: String,
        stage: DataStage,
        workDir: File,
        tool: String,
        arguments: List<String>,
        suffix: String = "run",
        onTick: (() -> Unit)? = null,
    ): String {
        val executable = toolFile(tool)
        check(executable.isFile && executable.canExecute()) {
            MISSING_TOOLS_COPY
        }
        val logs = File(workDir, "logs").apply { mkdirs() }
        val attempt = journal.dataStage(importId, stage)?.attempt?.coerceAtLeast(1) ?: 1
        val stdout = File(logs, "${stage.name.lowercase()}-$suffix-a$attempt.stdout.log")
        val stderr = File(logs, "${stage.name.lowercase()}-$suffix-a$attempt.stderr.log")
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(workDir)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .start()
        var output = ""
        try {
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                coroutineContext.ensureActive()
                onTick?.invoke()
            }
        } catch (error: Throwable) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            throw error
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException(diagnostic(stage, process.exitValue(), stdout, stderr))
        }
        // vmap_assembler (and friends) report some failures through stdout,
        // and the generators' per-map WRITE failures go through perror() →
        // stderr (cmangos-wotlk MapBuilder.cpp:853-854/:1095-1096), all while
        // still exiting 0 — win_prepare_data.py's belt-and-braces check scans
        // BOTH streams (R2-r2-2).
        output = stdout.readText() + "\n" + stderr.readText()
        check("exit with errors" !in output) {
            diagnostic(stage, process.exitValue(), stdout, stderr)
        }
        return output
    }

    private fun diagnostic(stage: DataStage, exit: Int, stdout: File, stderr: File): String {
        val tail = stderr.readText().takeLast(DIAGNOSTIC_TAIL).ifBlank {
            stdout.readText().takeLast(DIAGNOSTIC_TAIL)
        }
        return "$stage exited $exit: $tail"
    }

    /**
     * Where an extractor resolves. Order: the explicit [toolsDir] override,
     * then the packaged app dir / dev native library path entries (packageApp
     * stages the EXEs beside the DLLs), then a walk up from the working
     * directory to the checkout's own build tree (dev runs).
     */
    internal open fun toolFile(name: String): File {
        toolsDir?.let { return File(it, name) }
        val javaLibraryPath = System.getProperty("java.library.path") ?: ""
        val fromLibraryPath = javaLibraryPath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(File(it), name) }
        // R1-2 (W2 code-red round 1): the extractors are PER-FLAVOR builds —
        // the repo walk resolves the ACTIVE lane's build root (the mirror of
        // win_prepare_data.py's WIN_BUILD_ROOTS), never vanilla's for every
        // lane.
        val fromRepo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(MAX_REPO_WALK_UP)
            .map { repo ->
                extractorToolsDir(repo, ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE_TOP_LEVEL).flavorId)
                    .resolve(name)
            }
        return (fromLibraryPath + fromRepo.toList()).firstOrNull { it.isFile }
            ?: File(name) // surfaced by requireTools()'s check with the honest copy
    }

    internal open fun requireTools() = TOOL_NAMES.forEach {
        check(toolFile(it).isFile) {
            "A required server-data preparation tool is missing ($it). $MISSING_TOOLS_COPY"
        }
    }

    private fun publish(
        importId: String,
        stageRoot: File,
        clientRoot: File,
        onTick: () -> Unit,
    ): PublishedData {
        recover(importId, clientRoot, onTick)?.let { return it }
        // Unknown-length like the other finite stages — the full
        // data hash walk would otherwise leave a frozen "0/1" bar for its
        // entire duration.
        journal.startDataStage(importId, DataStage.MANIFEST, 0)
        onTick()
        val required = listOf("dbc", "maps", "vmaps", "mmaps")
        required.forEach { check(File(stageRoot, it).isDirectory) { "required data stage absent: $it" } }
        removeIntermediates(stageRoot)
        onTick()
        val files = required.asSequence().flatMap { File(stageRoot, it).walkTopDown().asSequence() }
            .filter { it.isFile }.onEach { onTick() }.map { file ->
                JSONObject().put("path", file.relativeTo(stageRoot).invariantSeparatorsPath)
                    .put("size", file.length()).put("sha256", sha256(file))
            }.sortedBy { it.getString("path") }.toList()
        val counts = JSONObject().put("dbc", count(File(stageRoot, "dbc"), ".dbc"))
            .put("maps", count(File(stageRoot, "maps"), ".map"))
            .put("vmapTrees", count(File(stageRoot, "vmaps"), ".vmtree"))
            .put("vmapTiles", count(File(stageRoot, "vmaps"), ".vmtile"))
            .put("mmapMaps", count(File(stageRoot, "mmaps"), ".mmap"))
            .put("mmapTiles", count(File(stageRoot, "mmaps"), ".mmtile"))
        // GAP-3 (owner census UIUX-3): publication resolves the ACTIVE lane
        // through the flavor machine — with several qualified flavors the
        // manifest identity and the per-flavor active pointer follow the
        // Home switcher's selection instead of `qualified().single()`
        // refusing data preparation wholesale.
        // R2-4 (code-red round 2) + R2r3-1 (desktop-twin round 3, serious):
        // the stamp is the VERIFIED source generation identity, never the
        // switcher's selection — the desktop twin of the android
        // verifiedSourceFlavor contract (D48's mid-prep-switch quarantine,
        // which this file's old comment CLAIMED while line 384 resolved the
        // live machine). The helper REFUSES when the source generation's
        // stamped clientId disagrees with the active lane, so a Home switch
        // during the hours-long prep can never relabel cross-flavor bytes.
        val flavor = verifiedSourceFlavor(clientRoot)
        val manifest = buildDataManifestJson(
            flavor = flavor,
            counts = counts,
            files = files,
            generator = generatorProvenance(flavor),
            importId = importId,
            publishedAtMs = System.currentTimeMillis(),
        )
        val manifestFile = File(stageRoot, "data-manifest.json")
        atomicWrite(manifestFile, manifest.toString(2).toByteArray())
        // Publication minimums validate BEFORE the pointer is touched so a
        // partial generation can never become the boot source (the shared
        // envelope's own gates run again at every boot).
        validatePublicationCounts(counts, flavor)
        val digest = sha256(manifestFile)
        val final = generation(importId)
        check(!final.exists()) { "data generation collision" }
        moveStageIntoGenerations(stageRoot, final)
        activate(importId, digest, flavor)
        journal.checkpointDataStage(importId, DataStage.MANIFEST, 1, 1, directoryBytes(final), "complete", true)
        return PublishedData(importId, final, digest)
    }

    /**
     * The recover re-hash is minutes of work, so it ticks the progress path,
     * and a crash between the generation rename and the MANIFEST checkpoint
     * is cleaned up here instead of leaving a stale RUNNING row.
     */
    private fun recover(
        importId: String,
        clientRoot: File,
        onTick: () -> Unit = {},
    ): PublishedData? {
        val final = generation(importId)
        val manifestFile = File(final, "data-manifest.json")
        if (!final.isDirectory || !manifestFile.isFile) return null
        val manifest = JSONObject(manifestFile.readText())
        // GAP-3 + R2r3-1: recovery resolves the same VERIFIED source
        // identity publication stamped — the resume path carries the same
        // quarantine (a mid-prep switch refused at the resume, too), never
        // a `.single()` crash and never a live-machine relabel.
        val flavor = verifiedSourceFlavor(clientRoot)
        // The shared envelope validator is the one identity gate (schema,
        // complete, mode, clientBuild, cmangosFamily, optional flavor alias).
        PreparedDataStore.validateDataManifestEnvelope(manifest, flavor)
        onTick()
        for (index in 0 until manifest.getJSONArray("files").length()) {
            val record = manifest.getJSONArray("files").getJSONObject(index)
            val file = safeResolve(final, record.getString("path"))
            check(
                file.isFile && file.length() == record.getLong("size") &&
                    sha256(file) == record.getString("sha256"),
            )
            onTick()
        }
        val digest = sha256(manifestFile)
        activate(importId, digest, flavor)
        journal.checkpointDataStage(importId, DataStage.MANIFEST, 1, 1, directoryBytes(final), "complete", true)
        return PublishedData(importId, final, digest)
    }

    /**
     * Flavor-scoped activation (G3-5): the pointer switch and the prune run
     * inside [flavor]'s namespace — the vanilla lane can never point at
     * another lane's data generation, and the keep-set never retires one.
     */
    private fun activate(importId: String, digest: String, flavor: ClientFlavor) {
        PreparedDataStore.acquirePublicationLease(root).use {
            val activePointer = activePointerFor(flavor)
            val previousPointer = previousPointerFor(flavor)
            val current = if (activePointer.isFile) {
                runCatching { JSONObject(activePointer.readText()) }.getOrNull()
            } else null
            val priorGeneration = current?.optString("generation")?.takeIf { it.matches(UUID) }
            if (priorGeneration == importId && current?.optString("manifestSha256") == digest &&
                current?.optString("mode") == "NORMAL"
            ) return
            if (priorGeneration != null && priorGeneration != importId) {
                atomicWrite(previousPointer, activePointer.readBytes())
            }
            atomicWrite(
                activePointer, JSONObject().put("schema", 1).put("generation", importId)
                    .put("manifestSha256", digest).put("mode", "NORMAL")
                    .put("flavor", flavor.flavorId)
                    .put("activatedAtMs", System.currentTimeMillis()).toString().toByteArray(),
            )
            val pruneCandidates = generations.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".staging-") }
            val keep = GenerationPointers.scopedKeepSet(
                activatingGeneration = importId,
                priorGeneration = priorGeneration,
                generations = pruneCandidates,
                activatingFlavorId = flavor.flavorId,
                generationFlavor = GenerationPointers::dataGenerationFlavor,
            )
            pruneCandidates.filter { it.name !in keep }.forEach {
                check(it.deleteRecursively()) { "cannot retire old data generation" }
            }
        }
    }

    /**
     * G3-5 legacy seed (data lane: copy-then-adopt through the atomic
     * writer). On first read, when the canonical per-flavor pointer is absent
     * and the un-namespaced pointer exists, copy it under the canonical name
     * and continue there; the canonical name wins the moment it exists.
     */
    private fun activePointerFor(flavor: ClientFlavor): File =
        adoptLegacyPointer(
            File(root, GenerationPointers.activePointerName(flavor.flavorId)),
            File(root, GenerationPointers.LEGACY_ACTIVE),
        )

    private fun previousPointerFor(flavor: ClientFlavor): File =
        adoptLegacyPointer(
            File(root, GenerationPointers.previousPointerName(flavor.flavorId)),
            File(root, GenerationPointers.LEGACY_PREVIOUS),
        )

    private fun adoptLegacyPointer(canonical: File, legacy: File): File {
        if (!canonical.isFile && legacy.isFile) atomicWrite(canonical, legacy.readBytes())
        return canonical
    }

    private fun moveStageIntoGenerations(stageRoot: File, final: File) {
        try {
            Files.move(stageRoot.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // scratch/ and content/ live under the same %LOCALAPPDATA% root in
            // practice; a redirected scratch falls back to copy-then-delete.
            Files.move(stageRoot.toPath(), final.toPath())
        }
    }

    private fun removeIntermediates(stageRoot: File) {
        for (name in listOf("Buildings", "tmp", "logs")) {
            val value = File(stageRoot, name)
            check(!value.exists() || value.deleteRecursively()) { "cannot remove generated intermediate: $name" }
        }
        for (name in listOf("config.json", "offmesh.txt")) {
            val value = File(stageRoot, name)
            check(!value.exists() || value.delete()) { "cannot remove generated intermediate: $name" }
        }
    }

    private fun generatorProvenance(flavor: ClientFlavor): JSONObject {
        val artifacts = TOOL_NAMES.map { name ->
            val file = toolFile(name)
            JSONObject().put("name", name).put("size", file.length()).put("sha256", if (file.isFile) sha256(file) else "")
        }
        // R1-2 (W2 code-red round 1): the stamp is the VERIFIED flavor's own
        // core commit — a wotlk run through this lane used to stamp vanilla's
        // D36 commit over wotlk-core-built extractors.
        return JSONObject().put("cmangosCommit", cmangosCommitFor(flavor.flavorId))
            .put("artifactSet", JSONArray(artifacts)).put("abi", "windows-x86_64")
    }

    private fun validatePublicationCounts(counts: JSONObject, flavor: ClientFlavor) {
        // R1-1/R6-3 (W2 code-red round 1): the ACTIVE flavor's measured
        // minimums (shared with the boot envelope through publicationMinimums
        // — the golden pin holds the rows to tools/win_prepare_data.py's
        // FLAVOR_MIN_COUNTS), never a vanilla-flat row.
        val minimums = publicationMinimums(flavor.flavorId)
        val failures = buildList {
            add("dbc" to minimums.dbc)
            add("maps" to minimums.maps)
            add("vmapTrees" to minimums.vmapTrees)
            add("mmapMaps" to minimums.mmapMaps)
            add("mmapTiles" to minimums.mmapTiles)
        }.filter { (key, minimum) -> counts.optInt(key) < minimum }
        check(failures.isEmpty()) {
            "prepared data below publication minimums: " +
                failures.joinToString("; ") { (key, minimum) -> "$key=${counts.optInt(key)} < $minimum" }
        }
    }

    private fun stageDir(importId: String): File {
        require(importId.matches(UUID)) { "invalid staging identity" }
        return File(File(roots.scratch, "data-prep"), ".staging-$importId")
    }

    private fun generation(name: String): File {
        require(name.matches(Regex("(?:\\.staging-)?[0-9a-f-]{36}"))) { "invalid generation identity" }
        return File(generations, name).also { check(it.absoluteFile.parentFile == generations.absoluteFile) }
    }

    private fun safeResolve(base: File, relative: String): File = File(base, relative).canonicalFile.also {
        check(it.toPath().startsWith(base.canonicalFile.toPath()) && it != base.canonicalFile)
    }

    private fun count(directory: File, suffix: String?): Int = directory.walkTopDown()
        .count { it.isFile && (suffix == null || it.name.endsWith(suffix, true)) }

    private fun requireCount(directory: File, suffix: String?, minimum: Int) {
        check(directory.isDirectory && count(directory, suffix) >= minimum) {
            "data validation failed: ${directory.name} count=${count(directory, suffix)} minimum=$minimum"
        }
    }

    private fun directoryBytes(directory: File): Long = if (!directory.exists()) 0 else
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${ProcessHandle.current().pid()}.tmp")
        FileChannel.open(
            temp.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("cannot replace ${target.name}: ${failure.message}", failure)
        }
    }

    private fun atomicReplaceFromFile(target: File, source: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${ProcessHandle.current().pid()}.repair")
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(temp.length() == source.length() && temp.length() > 0L)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("cannot replace ${target.name}: ${failure.message}", failure)
        }
    }

    data class PublishedData(val id: String, val root: File, val manifestSha256: String)

    companion object {
        private const val DIAGNOSTIC_TAIL = 2048
        private const val MAX_REPO_WALK_UP = 6

        /** GAP-3: the [ActiveFlavor] lane suffix naming the data-prep gate. */
        private const val SERVER_DATA_PREPARATION_LANE =
            "server data preparation resolves the qualified lane"

        private const val AD_TOOL = "ad.exe"
        private const val VMAP_EXTRACTOR_TOOL = "vmap_extractor.exe"
        private const val VMAP_ASSEMBLER_TOOL = "vmap_assembler.exe"
        private const val MOVEMAPGEN_TOOL = "MoveMapGen.exe"

        private val TOOL_NAMES = listOf(
            AD_TOOL, VMAP_EXTRACTOR_TOOL, VMAP_ASSEMBLER_TOOL, MOVEMAPGEN_TOOL,
        )

        /** The honest refusal when the extractor EXEs are absent (dev checkouts
         * without the native extractors build; packaged installs always stage
         * them beside the DLLs). Mirrors the Android nativeDir copy. */
        private const val MISSING_TOOLS_COPY =
            "This Pocket Realm build does not include the server-data preparation tools. " +
                "Install the complete Pocket Realm build."

        // MoveMapGen prints "[Map NNN] Failed creating navmesh!" on per-map
        // build failures while still exiting 0 (win_prepare_data.py's pin) —
        // and the wotlk generator's alloc failures print the core's own
        // misspelling "FIALED" (cmangos-wotlk MapBuilder.cpp:950/:961, R5-1):
        // the oracle matches both spellings, mirroring the python lane.
        internal val MOVEMAP_FAILURE = Regex("\\[Map\\s+\\d+\\].*(?:Failed|FIALED)", RegexOption.IGNORE_CASE)

        /**
         * Pure data-manifest composition (the [publish] writer body) so the
         * durable format is golden-fixture-testable on the JVM (G3-2). The
         * manifest stays schema 1; when S6 stamps the `flavor` alias key into
         * new manifests, the committed fixture rotates deliberately — never
         * silently, and never through a schema bump.
         */
        // The faithful template signature — one parameter per pinned manifest
        // field; splitting it would obscure the byte-pinned composition.
        @Suppress("LongParameterList")
        internal fun buildDataManifestJson(
            flavor: ClientFlavor,
            counts: JSONObject,
            files: List<JSONObject>,
            generator: JSONObject,
            importId: String,
            publishedAtMs: Long,
        ): JSONObject = JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL").put("clientBuild", flavor.build).put("cmangosFamily", flavor.serverFamily)
            .put("generator", generator)
            .put("sourceClientGeneration", importId).put("counts", counts)
            .put("files", JSONArray(files)).put("publishedAtMs", publishedAtMs)

        /** MoveMapGen's thread pool: everything but one core, minimum one
         * (the Android lane pins 6 to leave the phone UI two cores; a desktop
         * uses its own budget). */
        internal fun mmapThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

        // The extractor provenance pin (port plan S1 item 1): must equal the
        // commit the o11 extractor lockfiles were built from — the o09/o11
        // drivers and schemas/flavor.json vanilla server_core.commit all pin
        // the same commit, and tools/check_repo.py's divergence check fails
        // the build if these ever disagree again (decision D36).
        private const val CMANGOS_COMMIT = "ce83805d48f9c98b2af617096be5347acb8a1f17"

        /** R1-2 (W2 code-red round 1): the per-flavor core provenance stamp.
         * Vanilla stays the D36 four-way-pinned [CMANGOS_COMMIT] (the shared
         * vanilla extractor provenance tools/check_repo.py holds across the
         * o11 lockfiles, the o09 driver, this constant, and flavor.json);
         * tbc/wotlk stamp their OWN server_core commit — a wotlk run through
         * this lane used to stamp vanilla's commit over wotlk-core-built
         * extractors. The golden pin (DesktopDataPreparationStoreTest) holds
         * every row to schemas/flavor.json's server_core.commit. */
        internal val CMANGOS_COMMIT_BY_FLAVOR = mapOf(
            "vanilla" to CMANGOS_COMMIT,
            "tbc" to "ffbf97675dc31f845587778f5bb993b232f1b6a2",
            "wotlk" to "c3a8d86fe4e61c3373eff424eb12cd7e7e8282b5",
        )

        private fun cmangosCommitFor(flavorId: String): String =
            CMANGOS_COMMIT_BY_FLAVOR[flavorId]
                ?: error("flavor \"$flavorId\" carries no registered core provenance stamp")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal fun zeroLengthFiles(directory: File): List<File> =
    if (!directory.isDirectory) emptyList() else directory.walkTopDown()
        .filter { it.isFile && it.length() == 0L }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .toList()


/**
 * The lane string the data-preparation publish/recover legs resolve through
 * ActiveFlavor (top-level so verifiedSourceFlavor shares it; mirrors the
 * android twin).
 */
private const val SERVER_DATA_PREPARATION_LANE_TOP_LEVEL =
    "server data preparation resolves the qualified lane"

/**
 * R2-4 (code-red round 2): the data manifest's flavor is the VERIFIED
 * identity of the source client generation (its client-manifest.json
 * clientId — the identity the import gate stamped), never the Home
 * switcher's current selection. A generation whose verified flavor
 * disagrees with the active lane REFUSES: a mid-prep flavor switch must
 * not relabel cross-flavor bytes. Desktop twin of the android helper.
 */
internal fun verifiedSourceFlavor(clientRoot: File): ClientFlavor {
    val active = ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE_TOP_LEVEL)
    val manifestFile = File(clientRoot, "client-manifest.json")
    if (!manifestFile.isFile) return active
    val clientId = org.json.JSONObject(manifestFile.readText()).optString("clientId")
    val verified = com.pocketrealm.client.ClientFlavors.ALL.firstOrNull { it.clientId == clientId }
    checkNotNull(verified) {
        "source client generation identity \"" + clientId + "\" resolves to no registered " +
            "flavor — the verified clientId must name a registered lane"
    }
    // R2r3-1: compare the flavorId — a registry lane compared by data-class
    // equality against the holder's resolved instance would break on any
    // qualified-flag copy (the TwoQualifiedFlavors fixture's own shape).
    check(verified.flavorId == active.flavorId) {
        "data preparation source generation is \"" + clientId + "\" (" + verified.flavorId + ") " +
            "but the active lane is " + active.flavorId + " — a mid-prep flavor switch " +
            "cannot relabel the bytes; re-run preparation from the " +
            active.flavorId + " lane"
    }
    return verified
}

/** The mmtile name contract shared by every lane: packed digits
 * (%03d map + %02d x + %02d y). */
private val MMTILE_NAME_ANY_LANE = Regex("\\d{7}")

/** wotlk-only alt tile form %03u%02i%02i_NN.mmtile (cmangos-wotlk
 * MapBuilder.cpp:1090; its runtime loads both forms, MoveMap.cpp:28) —
 * accepted on no other lane. */
private val MMTILE_NAME_WOTLK_LANE = Regex("\\d{7}(_\\d{2})?")

/**
 * The active lane's mmtile name contract (win_prepare_data.py's
 * FLAVOR_MMTILE_NAME mirror): packed digits %03d map + %02d x + %02d y on
 * every lane, plus wotlk's measured alt form with exactly one _NN suffix.
 */
internal fun mmtileNamePattern(flavorId: String): Regex =
    if (flavorId == "wotlk") MMTILE_NAME_WOTLK_LANE else MMTILE_NAME_ANY_LANE

/** The lane's accepted mmtile shape, for refusal copy that states the ACTIVE
 * contract (R6-2): wotlk's gate accepts the alt form, so its message must say
 * so rather than the flat "7 digits" the other lanes enforce. */
internal fun mmtileExpectedShape(flavorId: String): String =
    if (flavorId == "wotlk") "7 digits, or 7 digits + _NN" else "7 digits"

/** The flavor's extractor home under [repo] — the mirror of
 * tools/win_prepare_data.py's WIN_BUILD_ROOTS: vanilla keeps the historical
 * unsuffixed root, tbc/wotlk carry the -{flavor} suffix (R1-2: the twin used
 * to resolve vanilla's extractors for every lane, driving vanilla-built tools
 * against a wotlk client). */
internal fun extractorToolsDir(repo: File, flavorId: String): File =
    File(
        repo,
        "native/.build-win-x86_64" + (if (flavorId == "vanilla") "" else "-$flavorId") +
            "/bin/x64_Release/Extractors",
    )

/**
 * Map IDs that can carry a navmesh: terrain-backed maps (extracted .map tiles)
 * plus every vmtree-bearing map, which covers WMO-only dungeons such as Uldaman
 * (070) that have no ADT terrain at all. The generator derives real tiles from
 * the vmap geometry for those.
 */
internal fun derivedMmapMapIds(mapsDir: File, vmapsDir: File): List<Int> =
    (numericMapPrefixes(mapsDir, ADT_MAP_FILE) + numericMapPrefixes(vmapsDir, VMTREE_FILE))
        .distinct().sorted().toList()

private val ADT_MAP_FILE = Regex("[0-9]{3}[0-9]{4}\\.map")
private val VMTREE_FILE = Regex("[0-9]{3}\\.vmtree")

private fun numericMapPrefixes(directory: File, pattern: Regex): Sequence<Int> =
    directory.listFiles().orEmpty().asSequence()
        .filter { it.isFile && it.name.matches(pattern) }
        .mapNotNull { it.name.substring(0, 3).toIntOrNull() }
