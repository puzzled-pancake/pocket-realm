package com.pocketrealm.ui

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Single-file archive for moving realm characters between installs: one
 * stopped-state database snapshot (the engine's own hash-verified format),
 * the paired user-account credentials, and a small manifest. Everything is
 * plain ZIP so exports stay inspectable and host-testable.
 */
internal object RealmDataArchive {
    const val KIND = "pocket-realm-characters"
    private const val META_ENTRY = "realm-archive.json"
    private const val SNAPSHOT_PREFIX = "snapshot/"
    private const val ACCOUNT_ENTRY = "account.json"
    private const val BUFFER = 1 shl 16

    data class ArchiveInfo(
        val kind: String,
        val snapshotId: String,
        val createdAtMs: Long,
        val appVersionName: String,
        val abi: String,
    )

    fun meta(snapshotId: String, createdAtMs: Long, appVersionName: String, abi: String): JSONObject =
        JSONObject().put("schema", 1).put("kind", KIND).put("snapshotId", snapshotId)
            .put("createdAtMs", createdAtMs).put("appVersionName", appVersionName).put("abi", abi)

    fun writeArchive(
        output: OutputStream,
        snapshotDir: File,
        accountFile: File?,
        meta: JSONObject,
    ) {
        require(snapshotDir.isDirectory) { "snapshot directory missing" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(META_ENTRY))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            snapshotDir.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(snapshotDir).invariantSeparatorsPath }
                .forEach { file ->
                    val relative = file.relativeTo(snapshotDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(SNAPSHOT_PREFIX + relative))
                    file.inputStream().use { input -> input.copyTo(zip, BUFFER) }
                    zip.closeEntry()
                }
            accountFile?.takeIf { it.isFile }?.let { account ->
                zip.putNextEntry(ZipEntry(ACCOUNT_ENTRY))
                account.inputStream().use { input -> input.copyTo(zip, BUFFER) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    /** Reads only the archive manifest, without extracting anything. */
    fun inspect(input: InputStream): ArchiveInfo {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == META_ENTRY) {
                    val meta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    check(meta.optInt("schema") == 1 && meta.optString("kind") == KIND) {
                        "not a Pocket Realm characters archive"
                    }
                    return ArchiveInfo(
                        kind = meta.optString("kind"),
                        snapshotId = meta.optString("snapshotId"),
                        createdAtMs = meta.optLong("createdAtMs"),
                        appVersionName = meta.optString("appVersionName"),
                        abi = meta.optString("abi"),
                    )
                }
            }
        }
        throw IllegalArgumentException("archive manifest entry missing")
    }

    /**
     * Extracts the snapshot into [snapshotRoot] (an empty, not-yet-published
     * directory) and returns the account entry bytes when present. Verifies the
     * snapshot manifest digest before anything is published.
     */
    fun extractSnapshot(input: InputStream, snapshotRoot: File): Pair<ArchiveInfo, ByteArray?> {
        require(snapshotRoot.isDirectory && snapshotRoot.listFiles().isNullOrEmpty()) {
            "extraction target must be empty"
        }
        var info: ArchiveInfo? = null
        var account: ByteArray? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name == META_ENTRY -> {
                        val meta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        check(meta.optInt("schema") == 1 && meta.optString("kind") == KIND) {
                            "not a Pocket Realm characters archive"
                        }
                        info = ArchiveInfo(
                            kind = meta.optString("kind"),
                            snapshotId = meta.optString("snapshotId"),
                            createdAtMs = meta.optLong("createdAtMs"),
                            appVersionName = meta.optString("appVersionName"),
                            abi = meta.optString("abi"),
                        )
                    }
                    name == ACCOUNT_ENTRY -> account = zip.readBytes()
                    name.startsWith(SNAPSHOT_PREFIX) -> {
                        val relative = name.removePrefix(SNAPSHOT_PREFIX)
                        check(safeRelative(relative)) { "unsafe archive path: $relative" }
                        val target = File(snapshotRoot, relative)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output, BUFFER) }
                    }
                    else -> throw IllegalArgumentException("unexpected archive entry: $name")
                }
            }
        }
        val parsed = checkNotNull(info) { "archive manifest entry missing" }
        verifySnapshotDigest(snapshotRoot)
        return parsed to account
    }

    /** Recomputes the snapshot manifest digest the way DatabaseSnapshotStore publishes it. */
    private fun verifySnapshotDigest(snapshotRoot: File) {
        val manifest = File(snapshotRoot, "manifest.json")
        val digestFile = File(snapshotRoot, "manifest.sha256")
        check(manifest.isFile && digestFile.isFile) { "snapshot manifest missing from archive" }
        check(sha256(manifest) == digestFile.readText().trim()) { "snapshot manifest digest mismatch" }
    }

    private fun safeRelative(relative: String): Boolean =
        relative.isNotEmpty() && !relative.startsWith("/") && relative.split('/').none { it == ".." || it.isEmpty() }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
