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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.pocketrealm.server.PreparedDataStore
import kotlin.coroutines.coroutineContext

/** Checkpointed CMaNGOS data generation and immutable normal-mode publication. */
class DataPreparationStore(
    context: Context,
    private val journal: ImportJournal,
) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "content/o11-server").apply { mkdirs() }
    private val generations = File(root, "generations").apply { mkdirs() }
    private val active = File(root, "active.json")
    private val previous = File(root, "previous.json")
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)

    suspend fun prepare(importId: String, clientRoot: File): PublishedData = withContext(Dispatchers.IO) {
        recover(importId)?.let { return@withContext it }
        val stageRoot = generation(".staging-$importId").apply { mkdirs() }
        check(clientRoot.isDirectory && File(clientRoot, "WoW.exe").isFile)
        requireTools()

        finiteStage(importId, DataStage.DBC_MAPS, stageRoot) {
            runTool(importId, DataStage.DBC_MAPS, stageRoot, "libpocket_ad.so",
                listOf("-i", clientRoot.absolutePath, "-o", stageRoot.absolutePath, "-e", "3"))
            requireCount(File(stageRoot, "dbc"), ".dbc", MIN_DBC)
            requireCount(File(stageRoot, "maps"), ".map", MIN_MAP_TILES)
        }
        finiteStage(importId, DataStage.VMAP_EXTRACT, stageRoot) {
            runTool(importId, DataStage.VMAP_EXTRACT, stageRoot, "libpocket_vmap_extractor.so",
                listOf("-s", "-d", File(clientRoot, "Data").absolutePath, "-o", stageRoot.absolutePath))
            requireCount(File(stageRoot, "Buildings"), null, 1)
        }
        repairInterruptedVmapExtraction(importId, clientRoot, stageRoot)
        finiteStage(importId, DataStage.VMAP_ASSEMBLE, stageRoot) {
            File(stageRoot, "vmaps").mkdirs()
            runTool(importId, DataStage.VMAP_ASSEMBLE, stageRoot, "libpocket_vmap_assembler.so",
                listOf(File(stageRoot, "Buildings").absolutePath, File(stageRoot, "vmaps").absolutePath))
            requireCount(File(stageRoot, "vmaps"), ".vmtree", 1)
        }
        prepareMmaps(importId, stageRoot)
        publish(importId, stageRoot)
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
        importId: String, stage: DataStage, root: File, body: suspend () -> Unit,
    ) {
        if (journal.dataStage(importId, stage)?.state == DataStageState.VERIFIED) return
        journal.startDataStage(importId, stage, 1)
        try {
            body()
            journal.checkpointDataStage(importId, stage, 1, 1, directoryBytes(root), "complete", true)
        } catch (error: Throwable) {
            journal.failDataStage(importId, stage, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private suspend fun prepareMmaps(importId: String, root: File) {
        val maps = File(root, "maps").listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.matches(Regex("[0-9]{3}[0-9]{4}\\.map")) }
            .map { it.name.substring(0, 3).toInt() }.distinct().sorted().toList()
        check(maps.isNotEmpty()) { "no map IDs available for mmap generation" }
        val prior = journal.dataStage(importId, DataStage.MMAPS)
        if (prior?.state == DataStageState.VERIFIED) return
        journal.startDataStage(importId, DataStage.MMAPS, maps.size, prior?.checkpoint)
        File(root, "mmaps").mkdirs()
        atomicWrite(File(root, "config.json"), "{}\n".toByteArray())
        atomicWrite(File(root, "offmesh.txt"), ByteArray(0))
        val completed = prior?.processed?.coerceIn(0, maps.size) ?: 0
        try {
            for ((index, mapId) in maps.withIndex()) {
                coroutineContext.ensureActive()
                if (index < completed && File(root, "mmaps/%03d.mmap".format(mapId)).isFile) continue
                runTool(importId, DataStage.MMAPS, root, "libpocket_movemapgen.so", listOf(
                    mapId.toString(), "--silent", "--threads", MMAP_THREADS.toString(),
                    "--configInputPath", File(root, "config.json").absolutePath,
                    "--offMeshInput", File(root, "offmesh.txt").absolutePath,
                    "--workdir", root.absolutePath,
                ), suffix = "%03d".format(mapId))
                check(File(root, "mmaps/%03d.mmap".format(mapId)).isFile) {
                    "mmap generator did not publish map $mapId"
                }
                journal.checkpointDataStage(importId, DataStage.MMAPS, index + 1, maps.size,
                    directoryBytes(File(root, "mmaps")), mapId.toString())
            }
            requireCount(File(root, "mmaps"), ".mmap", maps.size)
            requireCount(File(root, "mmaps"), ".mmtile", 1)
            journal.checkpointDataStage(importId, DataStage.MMAPS, maps.size, maps.size,
                directoryBytes(File(root, "mmaps")), "complete", true)
        } catch (error: Throwable) {
            journal.failDataStage(importId, DataStage.MMAPS, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private suspend fun runTool(
        importId: String, stage: DataStage, workDir: File, library: String,
        arguments: List<String>, suffix: String = "run",
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
            while (!process.waitFor(1, TimeUnit.SECONDS)) coroutineContext.ensureActive()
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

    private fun publish(importId: String, stageRoot: File): PublishedData {
        recover(importId)?.let { return it }
        journal.startDataStage(importId, DataStage.MANIFEST, 1)
        val required = listOf("dbc", "maps", "vmaps", "mmaps")
        required.forEach { check(File(stageRoot, it).isDirectory) { "required data stage absent: $it" } }
        removeIntermediates(stageRoot)
        val files = required.asSequence().flatMap { File(stageRoot, it).walkTopDown().asSequence() }
            .filter { it.isFile }.map { file ->
                JSONObject().put("path", file.relativeTo(stageRoot).invariantSeparatorsPath)
                    .put("size", file.length()).put("sha256", sha256(file))
            }.sortedBy { it.getString("path") }.toList()
        val counts = JSONObject().put("dbc", count(File(stageRoot, "dbc"), ".dbc"))
            .put("maps", count(File(stageRoot, "maps"), ".map"))
            .put("vmapTrees", count(File(stageRoot, "vmaps"), ".vmtree"))
            .put("vmapTiles", count(File(stageRoot, "vmaps"), ".vmtile"))
            .put("mmapMaps", count(File(stageRoot, "mmaps"), ".mmap"))
            .put("mmapTiles", count(File(stageRoot, "mmaps"), ".mmtile"))
        val manifest = JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL").put("clientBuild", 5875).put("cmangosFamily", "classic")
            .put("generator", generatorProvenance())
            .put("sourceClientGeneration", importId).put("counts", counts)
            .put("files", JSONArray(files)).put("publishedAtMs", System.currentTimeMillis())
        val manifestFile = File(stageRoot, "data-manifest.json")
        atomicWrite(manifestFile, manifest.toString(2).toByteArray())
        val digest = sha256(manifestFile)
        val final = generation(importId)
        check(!final.exists()) { "data generation collision" }
        Os.rename(stageRoot.absolutePath, final.absolutePath)
        fsyncDirectory(generations)
        activate(importId, digest)
        journal.checkpointDataStage(importId, DataStage.MANIFEST, 1, 1, directoryBytes(final), "complete", true)
        return PublishedData(importId, final, digest)
    }

    private fun recover(importId: String): PublishedData? {
        val final = generation(importId)
        val manifestFile = File(final, "data-manifest.json")
        if (!final.isDirectory || !manifestFile.isFile) return null
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 1 && manifest.getBoolean("complete") &&
            manifest.getString("mode") == "NORMAL" && manifest.getInt("clientBuild") == 5875)
        for (index in 0 until manifest.getJSONArray("files").length()) {
            val record = manifest.getJSONArray("files").getJSONObject(index)
            val file = safeResolve(final, record.getString("path"))
            check(file.isFile && file.length() == record.getLong("size") && sha256(file) == record.getString("sha256"))
        }
        val digest = sha256(manifestFile)
        activate(importId, digest)
        return PublishedData(importId, final, digest)
    }

    private fun activate(importId: String, digest: String) {
        PreparedDataStore.acquirePublicationLease(root).use {
            val current = if (active.isFile) {
                runCatching { JSONObject(active.readText()) }.getOrNull()
            } else null
            val priorGeneration = current?.optString("generation")?.takeIf { it.matches(UUID) }
            if (priorGeneration == importId && current?.optString("manifestSha256") == digest &&
                current?.optString("mode") == "NORMAL") return
            if (priorGeneration != null && priorGeneration != importId) atomicWrite(previous, active.readBytes())
            atomicWrite(active, JSONObject().put("schema", 1).put("generation", importId)
                .put("manifestSha256", digest).put("mode", "NORMAL")
                .put("activatedAtMs", System.currentTimeMillis()).toString().toByteArray())
            val keep = setOfNotNull(importId, priorGeneration)
            generations.listFiles().orEmpty().filter {
                it.isDirectory && !it.name.startsWith(".staging-") && it.name !in keep
            }.forEach { check(it.deleteRecursively()) { "cannot retire old data generation" } }
            fsyncDirectory(generations)
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
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
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
        // RP6/modern handheld lane: leave two cores for Android/UI while the
        // finite on-device navigation build uses the remaining big/little pool.
        private const val MMAP_THREADS = 6
        private const val CMANGOS_COMMIT = "c096bada9e4ed23ad4ca706c67160a26d7121337"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal fun zeroLengthFiles(directory: File): List<File> =
    if (!directory.isDirectory) emptyList() else directory.walkTopDown()
        .filter { it.isFile && it.length() == 0L }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .toList()
