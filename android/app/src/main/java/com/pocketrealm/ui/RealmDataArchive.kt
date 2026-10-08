package com.pocketrealm.ui

import com.pocketrealm.supervisor.UserAccountStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

    /** Hard cap on one in-memory meta/account entry, in bytes — the
     * provisioning frame's 4 KiB discipline
     * ([com.pocketrealm.server.FriendProvisionProtocol.MAX_REQUEST_BYTES]):
     * a user-picked archive is hostile input, so those two entries may
     * never be read into memory whole (snapshot entries stay streamed). */
    private const val MAX_ENTRY_BYTES = 4_096
    private val SNAPSHOT_ID = Regex("[a-zA-Z0-9._-]{1,96}")

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
                    val meta = JSONObject(readCapped(zip).toString(Charsets.UTF_8))
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

    /** The manifest's snapshot id names the EXTRACTION ROOT in both import
     * lanes (android SettingsScreen, desktop DesktopAppModel), so its grammar
     * is the snapshot store's own validId rule — [a-zA-Z0-9._-]{1,96} and no
     * leading dot. Callers must run this BEFORE mkdir-ing the target: an id
     * like "../x" would walk the extraction root out of the snapshots tree,
     * which the per-entry zip-slip gate cannot see because the root itself
     * moved. */
    fun validSnapshotId(id: String): Boolean =
        SNAPSHOT_ID.matches(id) && !id.startsWith('.')

    /**
     * The account entry is hostile bytes until proven a real record: an
     * import lane may write them over the device's saved auto-login
     * account only after they pass the SAME decode the store runs at load
     * ([UserAccountStore.decodeRecord]). Both import lanes (android
     * SettingsScreen, desktop DesktopAppModel) gate on this — an invalid
     * record rejects only the account leg, never the database import.
     */
    fun isValidAccountRecord(bytes: ByteArray): Boolean =
        runCatching { UserAccountStore.decodeRecord(JSONObject(String(bytes, Charsets.UTF_8))) }.isSuccess

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
                        val meta = JSONObject(readCapped(zip).toString(Charsets.UTF_8))
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
                    name == ACCOUNT_ENTRY -> account = readCapped(zip)
                    name.startsWith(SNAPSHOT_PREFIX) -> {
                        val relative = name.removePrefix(SNAPSHOT_PREFIX)
                        check(safeRelative(relative)) { "unsafe archive path: $relative" }
                        val target = File(snapshotRoot, relative).canonicalFile
                        val rootPath = snapshotRoot.canonicalFile.toPath()
                        check(target.toPath().startsWith(rootPath) && target != snapshotRoot.canonicalFile) {
                            "normalized archive path escaped the extraction root"
                        }
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

    /** Zip-slip gate: entry names travel with '/' separators only — a '\'
     * would be a path separator on Windows hosts (the desktop import lane),
     * hiding traversal from the component scan — and every component must be
     * a real name. Legit exports never carry '\': writeArchive stores
     * invariantSeparatorsPath names. */
    private fun safeRelative(relative: String): Boolean =
        '\\' !in relative && relative.isNotEmpty() && !relative.startsWith("/") &&
            relative.split('/').none { it == ".." || it.isEmpty() }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    /** Reads at most [MAX_ENTRY_BYTES] of the current entry (the +1 probe
     * tells "at the cap" from "over it") so a hostile archive cannot size
     * the allocation. Oversize refuses the whole import, like every other
     * codec gate. */
    private fun readCapped(zip: ZipInputStream): ByteArray {
        val buffer = ByteArray(MAX_ENTRY_BYTES + 1)
        var read = 0
        while (read < buffer.size) {
            val n = zip.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        check(read <= MAX_ENTRY_BYTES) { "archive entry exceeds $MAX_ENTRY_BYTES bytes" }
        return buffer.copyOf(read)
    }
}
