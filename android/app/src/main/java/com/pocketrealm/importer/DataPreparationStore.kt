package com.pocketrealm.importer

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.server.PreparedDataStore
import kotlin.coroutines.coroutineContext

/** Checkpointed CMaNGOS data generation and immutable normal-mode publication. */
/** The lane string the data-preparation publish/recover legs resolve 
 * through ActiveFlavor (top-level so verifiedSourceFlavor shares it). */
private const val SERVER_DATA_PREPARATION_LANE =     "server data preparation resolves the qualified lane"


class DataPreparationStore(
    context: Context,
    private val journal: ImportJournal,
) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "content/o11-server").apply { mkdirs() }
    private val generations = File(root, "generations").apply { mkdirs() }
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)

    suspend fun prepare(
        importId: String,
        clientRoot: File,
        onTick: () -> Unit = {},
    ): PublishedData = withContext(Dispatchers.IO) {
        recover(importId, onTick)?.let { return@withContext it }
        val stageRoot = generation(".staging-$importId").apply { mkdirs() }
        check(clientRoot.isDirectory && File(clientRoot, "WoW.exe").isFile)
        requireTools()

        finiteStage(importId, DataStage.DBC_MAPS, stageRoot, onTick) {
            runTool(importId, DataStage.DBC_MAPS, stageRoot, "libpocket_ad.so",
                listOf("-i", clientRoot.absolutePath, "-o", stageRoot.absolutePath, "-e", "3"))
            requireCount(File(stageRoot, "dbc"), ".dbc", MIN_DBC)
            requireCount(File(stageRoot, "maps"), ".map", MIN_MAP_TILES)
        }
        finiteStage(importId, DataStage.VMAP_EXTRACT, stageRoot, onTick) {
            runTool(importId, DataStage.VMAP_EXTRACT, stageRoot, "libpocket_vmap_extractor.so",
                listOf("-s", "-d", File(clientRoot, "Data").absolutePath, "-o", stageRoot.absolutePath))
            requireCount(File(stageRoot, "Buildings"), null, 1)
        }
        repairInterruptedVmapExtraction(importId, clientRoot, stageRoot)
        finiteStage(importId, DataStage.VMAP_ASSEMBLE, stageRoot, onTick) {
            File(stageRoot, "vmaps").mkdirs()
            runTool(importId, DataStage.VMAP_ASSEMBLE, stageRoot, "libpocket_vmap_assembler.so",
                listOf(File(stageRoot, "Buildings").absolutePath, File(stageRoot, "vmaps").absolutePath))
            requireCount(File(stageRoot, "vmaps"), ".vmtree", 1)
        }
        prepareMmaps(importId, stageRoot, onTick)
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
        val repairRoot = File(stageRoot, ".vmap-repair-${android.os.Process.myPid()}")
        check(!repairRoot.exists() || repairRoot.deleteRecursively()) {
            "cannot reset vmap repair workspace"
        }
        check(repairRoot.mkdirs()) { "cannot create vmap repair workspace" }
        try {
            runTool(importId, DataStage.VMAP_EXTRACT, stageRoot, "libpocket_vmap_extractor.so",
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
        importId: String, stage: DataStage, root: File, onTick: () -> Unit, body: suspend () -> Unit,
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

    private suspend fun prepareMmaps(importId: String, root: File, onTick: () -> Unit) {
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
                runTool(importId, DataStage.MMAPS, root, "libpocket_movemapgen.so", listOf(
                    mapId.toString(), "--silent", "--threads", MMAP_THREADS.toString(),
                    "--configInputPath", File(root, "config.json").absolutePath,
                    "--offMeshInput", File(root, "offmesh.txt").absolutePath,
                    "--workdir", root.absolutePath,
                ), suffix = "%03d".format(mapId), onTick = {
                    reportMmapProgress(importId, mapId, index, mapIds.size, mmaps)
                    onTick()  // prepareMmaps's notification tick; runTool's label is not a binding
                })
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

    /**
     * One-second in-run progress for the user-visible page: tiles published for
     * the map under construction plus the generator child's cumulative CPU.
     * The child is forked by a worker thread, so Android's per-app CPU meters
     * attribute it nowhere useful; reading procfs here keeps the display truthful.
     */
    private fun reportMmapProgress(importId: String, mapId: Int, index: Int, total: Int, mmaps: File) {
        val prefix = "%03d".format(mapId)
        val tiles = mmaps.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.size ?: 0
        val cpuSeconds = ImportProcessMetricsSampler.forkedChildPids()
            .filter { pid -> generatorPid(pid) }
            .sumOf { pid -> ImportProcessMetricsSampler.processCpuSeconds(pid) ?: 0.0 }
        journal.checkpointDataStage(importId, DataStage.MMAPS, index, total,
            directoryBytes(mmaps),
            "map %s (%d/%d) tiles %d gen-cpu %.0fs".format(prefix, index + 1, total, tiles, cpuSeconds))
    }

    private fun generatorPid(pid: Int): Boolean = runCatching {
        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).contains("libpocket_movemapgen")
    }.getOrDefault(false)

    private fun mmapCheckpointText(mapId: Int, skipped: Set<Int>): String =
        if (mapId in skipped) "%03d skipped (no navmesh data)".format(mapId)
        else "%03d published".format(mapId)

    private suspend fun runTool(
        importId: String, stage: DataStage, workDir: File, library: String,
        arguments: List<String>, suffix: String = "run", onTick: (() -> Unit)? = null,
    ) {
        val executable = File(nativeDir, library)
        check(executable.isFile && executable.canExecute()) {
            "This app build does not include the server-data preparation tools. Install the complete Pocket Realm build."
        }
        val logs = File(workDir, "logs").apply { mkdirs() }
        val attempt = journal.dataStage(importId, stage)?.attempt ?: 1
        val stdout = File(logs, "${stage.name.lowercase()}-$suffix-a$attempt.stdout.log")
        val stderr = File(logs, "${stage.name.lowercase()}-$suffix-a$attempt.stderr.log")
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(workDir).redirectOutput(stdout).redirectError(stderr).apply {
                environment().clear()
                environment()["HOME"] = workDir.absolutePath
                environment()["TMPDIR"] = File(workDir, "tmp").apply { mkdirs() }.absolutePath
                environment()["PATH"] = "/system/bin"
                environment()["LC_ALL"] = "C"
            }.start()
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
        check(process.exitValue() == 0) {
            val diagnostic = stderr.readText().takeLast(2048)
                .ifBlank { stdout.readText().takeLast(2048) }
            "$stage exited ${process.exitValue()}: $diagnostic"
        }
    }

    private fun publish(importId: String, stageRoot: File, clientRoot: File, onTick: () -> Unit): PublishedData {
        recover(importId, onTick)?.let { return it }
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
        // R2-4 (code-red round 2): the ACTIVE selection alone was the whole
        // identity — a flavor switch during the hours-long prep window
        // published a generation stamped for the WRONG lane and every
        // consume-side envelope gate keyed off the same selection, so the
        // mislabeled bytes booted without rejection. The stamp now comes
        // from the VERIFIED source generation's own client-manifest
        // clientId (the PE-resolved identity ClientGenerationStore pins),
        // and a disagreement with the active lane refuses loudly instead
        // of relabeling the bytes.
        val flavor = verifiedSourceFlavor(clientRoot)
        val manifest = buildDataManifestJson(
            flavor = flavor,
            counts = counts,
            files = files,
            generator = generatorProvenance(),
            importId = importId,
            publishedAtMs = System.currentTimeMillis(),
        )
        val manifestFile = File(stageRoot, "data-manifest.json")
        atomicWrite(manifestFile, manifest.toString(2).toByteArray())
        val digest = sha256(manifestFile)
        val final = generation(importId)
        check(!final.exists()) { "data generation collision" }
        Os.rename(stageRoot.absolutePath, final.absolutePath)
        fsyncDirectory(generations)
        activate(importId, digest, flavor)
        journal.checkpointDataStage(importId, DataStage.MANIFEST, 1, 1, directoryBytes(final), "complete", true)
        return PublishedData(importId, final, digest)
    }

    /**
     * The recover re-hash is minutes of work, so it ticks the notification
     * path, and a crash between the generation rename and the MANIFEST
     * checkpoint is cleaned up here instead of leaving a stale RUNNING row
     * that pins the "Current work" pane and the completion notification.
     */
    /**
     * R2-4: the data manifest's flavor is the VERIFIED identity of the
     * source client generation (its client-manifest.json clientId — the
     * PE-resolved lane the import gate stamped), never the Home switcher's
     * current selection. A generation whose verified flavor disagrees with
     * the active lane REFUSES publication: a mid-prep flavor switch must
     * not relabel cross-flavor bytes (the consume-side envelope gates key
     * off the manifest, so a mislabeled generation would boot unrejected).
     */
    private fun recover(importId: String, onTick: () -> Unit = {}): PublishedData? {
        val final = generation(importId)
        val manifestFile = File(final, "data-manifest.json")
        if (!final.isDirectory || !manifestFile.isFile) return null
        val manifest = JSONObject(manifestFile.readText())
        // recovery resolves the same ACTIVE lane publication did —
        // the envelope identity gate and the re-activation follow the
        // selection, never a `.single()` crash at the second qualification.
        val flavor = ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE)
        check(manifest.getInt("schema") == 1 && manifest.getBoolean("complete") &&
            manifest.getString("mode") == "NORMAL" &&
            manifest.getInt("clientBuild") == flavor.build) {
            "data generation was built for client build ${manifest.optInt("clientBuild")}, " +
                "but the qualified lane is build ${flavor.build}"
        }
        // The write path stamps the qualified flavor's server family; a
        // generation produced for another family must never reactivate here.
        check(manifest.getString("cmangosFamily") == flavor.serverFamily) {
            "data generation was built for the ${manifest.optString("cmangosFamily")} server family, " +
                "but the qualified lane is ${flavor.serverFamily}"
        }
        // the flavor key is an additive alias of the fields above —
        // absent on pre-flavor manifests, refused on disagreement.
        check(manifest.optString("flavor", flavor.flavorId) == flavor.flavorId) {
            "data generation was built for the \"${manifest.optString("flavor")}\" flavor, " +
                "but the qualified lane is ${flavor.flavorId}"
        }
        onTick()
        for (index in 0 until manifest.getJSONArray("files").length()) {
            val record = manifest.getJSONArray("files").getJSONObject(index)
            val file = safeResolve(final, record.getString("path"))
            check(file.isFile && file.length() == record.getLong("size") && sha256(file) == record.getString("sha256"))
            onTick()
        }
        val digest = sha256(manifestFile)
        activate(importId, digest, flavor)
        journal.checkpointDataStage(importId, DataStage.MANIFEST, 1, 1, directoryBytes(final), "complete", true)
        return PublishedData(importId, final, digest)
    }

    /**
     * Flavor-scoped activation (G3-5): the pointer switch and the prune run
     * inside [flavor]'s namespace. The keep-set never contains another
     * flavor's data generation (hours of on-device mmaps work), and the
     * pointer body carries the flavor key so a doctored cross-flavor pointer
     * is detectable by the fail-closed readers.
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
                current?.optString("mode") == "NORMAL") return
            if (priorGeneration != null && priorGeneration != importId) {
                atomicWrite(previousPointer, activePointer.readBytes())
            }
            atomicWrite(activePointer, JSONObject().put("schema", 1).put("generation", importId)
                .put("manifestSha256", digest).put("mode", "NORMAL")
                .put("flavor", flavor.flavorId)
                .put("activatedAtMs", System.currentTimeMillis()).toString().toByteArray())
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
            fsyncDirectory(generations)
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

    private fun removeIntermediates(stageRoot: File) {
        for (name in listOf("Buildings", "tmp", "logs")) {
            val value = File(stageRoot, name)
            check(!value.exists() || value.deleteRecursively()) { "cannot remove generated intermediate: $name" }
        }
        for (name in listOf("config.json", "offmesh.txt")) {
            val value = File(stageRoot, name)
            check(!value.exists() || value.delete()) { "cannot remove generated intermediate: $name" }
        }
        fsyncDirectory(stageRoot)
    }

    private fun generatorProvenance(): JSONObject {
        val artifacts = listOf("libpocket_ad.so", "libpocket_vmap_extractor.so",
            "libpocket_vmap_assembler.so", "libpocket_movemapgen.so").map { name ->
            val file = File(nativeDir, name)
            JSONObject().put("name", name).put("size", file.length()).put("sha256", sha256(file))
        }
        return JSONObject().put("cmangosCommit", CMANGOS_COMMIT)
            .put("artifactSet", JSONArray(artifacts)).put("abi", Build.SUPPORTED_ABIS.firstOrNull())
            .put("api", Build.VERSION.SDK_INT).put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
    }

    private fun requireTools() = listOf("libpocket_ad.so", "libpocket_vmap_extractor.so",
        "libpocket_vmap_assembler.so", "libpocket_movemapgen.so").forEach {
        check(File(nativeDir, it).isFile) {
            "A required server-data preparation tool is missing ($it). Reinstall the complete Pocket Realm build."
        }
    }

    private fun generation(name: String): File {
        require(name.matches(Regex("(?:\\.staging-)?[0-9a-f-]{36}")))
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
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        Os.rename(temp.absolutePath, target.absolutePath)
        fsyncDirectory(checkNotNull(target.parentFile))
    }
    private fun atomicReplaceFromFile(target: File, source: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.repair")
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(temp.length() == source.length() && temp.length() > 0L)
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        Os.rename(temp.absolutePath, target.absolutePath)
        fsyncDirectory(checkNotNull(target.parentFile))
    }
    private fun fsyncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    data class PublishedData(val id: String, val root: File, val manifestSha256: String)
    companion object {
        private const val MIN_DBC = 100
        private const val MIN_MAP_TILES = 100

        /** the [ActiveFlavor] lane suffix naming the data-prep gate. */
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
        // RP6/modern handheld lane: leave two cores for Android/UI while the
        // finite on-device navigation build uses the remaining big/little pool.
        internal const val MMAP_THREADS = 6
        // The extractor provenance pin (port plan S1 item 1): must equal the
        // commit the o11 extractor lockfiles were built from — the o09/o11
        // drivers and schemas/flavor.json vanilla server_core.commit all pin
        // the same commit, and tools/check_repo.py's divergence check fails
        // the build if these ever disagree again (decision D36).
        private const val CMANGOS_COMMIT = "ce83805d48f9c98b2af617096be5347acb8a1f17"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal fun zeroLengthFiles(directory: File): List<File> =
    if (!directory.isDirectory) emptyList() else directory.walkTopDown()
        .filter { it.isFile && it.length() == 0L }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .toList()

internal fun verifiedSourceFlavor(clientRoot: File): ClientFlavor {
    val active = ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE)
    val manifestFile = File(clientRoot, "client-manifest.json")
    if (!manifestFile.isFile) return active
    val clientId = JSONObject(manifestFile.readText()).optString("clientId")
    val verified = ClientFlavors.ALL.firstOrNull { it.clientId == clientId }
    checkNotNull(verified) {
        "source client generation identity \"$clientId\" resolves to no registered " +
            "flavor — the verified clientId must name a registered lane"
    }
    check(verified == active) {
        "data preparation source generation is \"$clientId\" (${verified.flavorId}) " +
            "but the active lane is ${active.flavorId} — a mid-prep flavor switch " +
            "cannot relabel the bytes; re-run preparation from the " +
            "${active.flavorId} lane"
    }
    return verified
}

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
