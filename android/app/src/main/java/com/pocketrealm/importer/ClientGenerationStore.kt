package com.pocketrealm.importer

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.client.ClientRuntimeContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Immutable generation publication with an atomic active-pointer switch. */
class ClientGenerationStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "client").apply { mkdirs() }
    private val generations = File(root, "generations").apply { mkdirs() }
    private val activePointer = File(root, "active.json")
    private val previousPointer = File(root, "previous.json")

    fun staging(importId: String): File = safeGeneration(".staging-$importId")
    fun generation(importId: String): File = safeGeneration(importId)

    fun prepare(importId: String): File {
        generations.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".staging-") && it.name != ".staging-$importId" }
            .forEach { check(it.deleteRecursively()) { "cannot retire abandoned client staging generation" } }
        val directory = staging(importId)
        directory.mkdirs()
        check(directory.isDirectory) { "cannot create managed-client staging generation" }
        return directory
    }

    /** Recover the narrow crash window after generation rename but before journal/pointer commit. */
    fun recoverPublished(importId: String): PublishedGeneration? {
        val final = generation(importId)
        val manifest = File(final, "client-manifest.json")
        if (!final.isDirectory || !manifest.isFile) return null
        val digest = validateManifest(final, manifest, importId)
        activate(importId, digest)
        return PublishedGeneration(importId, final, digest)
    }

    fun resolve(staging: File, relativePath: String): File {
        val target = File(staging, relativePath).canonicalFile
        val rootPath = staging.canonicalFile.toPath()
        check(target.toPath().startsWith(rootPath) && target != staging.canonicalFile) {
            "normalized import path escaped staging"
        }
        return target
    }

    fun publish(
        importId: String,
        identity: JSONObject,
        sourceFingerprint: String,
        journalEntries: List<ImportJournal.JournalEntry>,
        durationMs: Long,
        afterRenameBeforeActivate: () -> Unit = {},
    ): PublishedGeneration {
        val staging = staging(importId)
        val final = generation(importId)
        recoverPublished(importId)?.let { return it }
        check(staging.isDirectory) { "managed-client staging generation is absent" }
        writeManagedConfiguration(staging)

        val files = staging.walkTopDown().filter { it.isFile && it.name != "client-manifest.json" }
            .map { file ->
                val relative = file.relativeTo(staging).invariantSeparatorsPath
                JSONObject().put("path", relative).put("size", file.length()).put("sha256", sha256(file))
            }.sortedBy { it.getString("path").lowercase() }.toList()
        val manifest = JSONObject()
            .put("schema", 2).put("complete", true)
            .put("clientId", ClientRuntimeContract.WOW_5875_ID)
            .put("identity", identity)
            .put("executable", "WoW.exe").put("directLaunch", true)
            .put("sourceRuntimeDependency", false).put("sourceFingerprint", sourceFingerprint)
            .put("managedRoot", "no_backup/client/generations/$importId")
            .put("safeMode", JSONObject()
                .put("renderer", "wined3d").put("resolution", "1280x720")
                .put("fpsCap", 30).put("audio", "off").put("realmEndpoint", "127.0.0.1")
                .put("addons", "preserved-disabled-until-reviewed"))
            .put("appOwnedFiles", JSONArray(listOf("realmlist.wtf", "WTF/Config.wtf")))
            .put("journal", JSONObject()
                .put("schema", ImportJournal.SCHEMA)
                .put("verified", journalEntries.count { it.state == ImportFileState.VERIFIED })
                .put("skipped", journalEntries.count { it.state == ImportFileState.SKIPPED })
                .put("maxAttempt", journalEntries.maxOfOrNull { it.attempt } ?: 0))
            .put("files", JSONArray(files)).put("durationMs", durationMs)
            .put("publishedAtMs", System.currentTimeMillis())
        val manifestFile = File(staging, "client-manifest.json")
        atomicWrite(manifestFile, manifest.toString(2).toByteArray(Charsets.UTF_8))
        val digest = sha256(manifestFile)
        check(!final.exists()) { "managed-client generation collision" }
        Os.rename(staging.absolutePath, final.absolutePath)
        fsyncDirectory(generations)
        afterRenameBeforeActivate()
        activate(importId, digest)
        return PublishedGeneration(importId, final, digest)
    }

    fun activeGeneration(): String? = readPointer(activePointer)?.optString("generation")?.takeIf(UUID::matches)

    private fun activate(generation: String, manifestSha256: String) {
        val current = readPointer(activePointer)
        val priorGeneration = current?.getString("generation")
        if (priorGeneration == generation && current?.optString("manifestSha256") == manifestSha256) return
        if (priorGeneration != null && priorGeneration != generation) {
            atomicWrite(previousPointer, checkNotNull(activePointer.takeIf { it.isFile }).readBytes())
        }
        val pointer = JSONObject().put("schema", 1).put("generation", generation)
            .put("clientId", ClientRuntimeContract.WOW_5875_ID)
            .put("manifestSha256", manifestSha256).put("activatedAtMs", System.currentTimeMillis())
        atomicWrite(activePointer, pointer.toString().toByteArray(Charsets.UTF_8))
        val keep = setOfNotNull(generation, priorGeneration)
        generations.listFiles().orEmpty().filter {
            it.isDirectory && !it.name.startsWith(".staging-") && it.name !in keep
        }.forEach { check(it.deleteRecursively()) { "cannot retire old client generation" } }
        fsyncDirectory(generations)
    }

    private fun writeManagedConfiguration(staging: File) {
        atomicWrite(File(staging, "realmlist.wtf"), "set realmlist 127.0.0.1\r\n".toByteArray())
        atomicWrite(File(staging, "WTF/Config.wtf"), SAFE_CONFIG.replace("\\r\\n", "\r\n").toByteArray())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        Os.rename(temp.absolutePath, target.absolutePath)
        fsyncDirectory(checkNotNull(target.parentFile))
    }

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun readPointer(file: File): JSONObject? = if (!file.isFile) null else runCatching {
        JSONObject(file.readText()).also {
            check(it.getInt("schema") == 1 && UUID.matches(it.getString("generation")))
        }
    }.getOrNull()

    private fun validateManifest(root: File, manifestFile: File, importId: String): String {
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 2 && manifest.getBoolean("complete"))
        check(manifest.getString("clientId") == ClientRuntimeContract.WOW_5875_ID)
        check(manifest.getString("managedRoot").endsWith("/$importId"))
        for (index in 0 until manifest.getJSONArray("files").length()) {
            val record = manifest.getJSONArray("files").getJSONObject(index)
            val file = resolve(root, record.getString("path"))
            check(file.isFile && file.length() == record.getLong("size") &&
                sha256(file) == record.getString("sha256")) { "published generation failed integrity validation" }
        }
        return sha256(manifestFile)
    }

    private fun safeGeneration(name: String): File {
        require(name.matches(Regex("(?:\\.staging-)?[0-9a-f-]{36}"))) { "invalid generation identity" }
        val value = File(generations, name).absoluteFile
        check(value.parentFile == generations.absoluteFile) { "generation escaped root" }
        return value
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class PublishedGeneration(val id: String, val root: File, val manifestSha256: String)

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val SAFE_CONFIG = """SET readTOS "1"\r
SET readEULA "1"\r
SET readScanning "1"\r
SET movie "0"\r
SET gxResolution "1280x720"\r
SET gxWindow "1"\r
SET gxMaximize "0"\r
SET gxVSync "0"\r
SET gxMultisample "1"\r
SET gxMultisampleQuality "0.000000"\r
SET maxFPS "30"\r
SET Sound_EnableAllSound "0"\r
SET Sound_EnableMusic "0"\r
SET Sound_EnableSFX "0"\r
SET Sound_EnableAmbience "0"\r
SET ffxGlow "0"\r
SET ffxDeath "0"\r
SET farclip "177"\r
"""
    }
}
