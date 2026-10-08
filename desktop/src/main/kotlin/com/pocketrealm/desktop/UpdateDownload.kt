package com.pocketrealm.desktop

import com.pocketrealm.fs.FileDigests
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The desktop updater's resumable, digest-verified download (DESK-14/32/45/
 * 91/104; the ApkDownloader twin). The body is STREAMED to the .part file
 * through a fixed buffer (a release package is hundreds of MB — never
 * buffered in heap); a 206 response APPENDS to the existing partial, any
 * other code restarts from zero. Resume state (.part/.etag sidecars) lives
 * under the settings downloads dir and survives process death. The finalized
 * download is length- AND sha256-verified (shared fs/FileDigests) before it
 * is renamed into place: a failed, short, or tampered download is discarded,
 * never installed.
 */
@Suppress("MagicNumber") // HTTP statuses, socket timeouts, byte units
object UpdateDownload {

    /** The staged package when a complete verified copy is on disk. */
    fun downloadedIfVerified(roots: DesktopStorageRoots, manifest: UpdateCheck.UpdateManifest): File? {
        val target = stagedFile(roots, "")
        if (!target.isFile || target.length() != manifest.size) return null
        return target.takeIf { FileDigests.sha256(it).equals(manifest.sha256, true) }
    }

    /**
     * Reclaims the staged download plus its .part/.etag sidecars (after a
     * committed apply, or for a superseded release). Never called right
     * after a commit — a user cancelling the confirmation must still be able
     * to retry from disk.
     */
    fun clearStagedDownload(roots: DesktopStorageRoots) {
        STAGED_SUFFIXES.forEach { suffix -> stagedFile(roots, suffix).delete() }
    }

    /**
     * Resumable download of [manifest]'s package into the settings downloads
     * dir. A previously verified download is reused as-is — no network, no
     * delete-and-redownload.
     */
    fun download(
        roots: DesktopStorageRoots,
        manifest: UpdateCheck.UpdateManifest,
        onProgress: (Long) -> Unit = {},
    ): File {
        require(manifest.downloadable) {
            "the feed carries no download legs (url/size/sha256); nothing to download"
        }
        // `downloadable` guards the legs, but the compiler still sees the
        // nullable fields; bind them once, non-null, for the whole flow.
        val total = requireNotNull(manifest.size)
        val packageUrl = requireNotNull(manifest.packageUrl)
        val expectedDigest = requireNotNull(manifest.sha256)
        val dir = UpdateCheck.stagingDir(roots)
        // mkdirs before the usableSpace read: a nonexistent path reports 0.
        dir.mkdirs()
        val base = stagedFile(roots, "")
        downloadedIfVerified(roots, manifest)?.let { verified ->
            onProgress(verified.length())
            return verified
        }
        val response = buildResumePoint(roots, total)
        fetchInto(response, packageUrl, onProgress)
        return finalizeVerified(base, response, expectedDigest, onProgress)
    }

    private fun stagedFile(roots: DesktopStorageRoots, suffix: String): File =
        File(UpdateCheck.stagingDir(roots), UpdateCheck.PACKAGE_BASE_NAME + suffix)

    /**
     * Validates the resume point: drops a .part that already overshoots the
     * manifest (corrupt resume state), cleans a fresh attempt's leftovers,
     * and returns the download's write target with its If-Range etag.
     */
    private fun buildResumePoint(roots: DesktopStorageRoots, total: Long): PartialWrite {
        val partial = stagedFile(roots, PART_SUFFIX)
        val etagFile = stagedFile(roots, ETAG_SUFFIX)
        if (partial.isFile && partial.length() > total) partial.delete()
        val start = partial.length()
        val etag = if (start > 0 && etagFile.isFile) {
            etagFile.readText().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (start == 0L) {
            // A complete base and a stale .part cannot coexist — the rename
            // that produces base atomically consumes .part — so this cleanup
            // only fires on genuinely fresh attempts.
            stagedFile(roots, "").delete()
            partial.delete()
        }
        return PartialWrite(partial, etagFile, total, start, etag)
    }

    /** One request/response cycle: Range + If-Range in, streamed .part out. */
    private fun fetchInto(response: PartialWrite, packageUrl: String, onProgress: (Long) -> Unit) {
        val connection = UpdateCheck.openConnection(packageUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        connection.requestMethod = "GET"
        if (response.start > 0) connection.setRequestProperty("Range", "bytes=${response.start}-")
        response.ifRange?.let { connection.setRequestProperty("If-Range", it) }
        try {
            val code = connection.responseCode
            if (code != 200 && code != HTTP_APPLIED_RANGE) throw IOException("HTTP $code")
            // A 206 against a resume point appends; anything else restarts.
            response.append = response.start > 0 && code == HTTP_APPLIED_RANGE
            if (!response.append && response.start > 0) response.file.delete()
            copyCapped(connection.inputStream, response, onProgress)
            connection.getHeaderField(ETAG_HEADER)?.let { response.etagFile.writeText(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Streams one response body into the .part; appends on 206, restarts otherwise. */
    private fun copyCapped(input: InputStream, response: PartialWrite, onProgress: (Long) -> Unit) {
        response.written = if (response.append) response.start else 0L
        FileOutputStream(response.file, response.append).use { output ->
            copyLoop(input, output, response, onProgress)
            // One durability point per attempt: power-loss corruption is
            // caught by the length + sha256 gates anyway; per-chunk fsync
            // would add minutes of flash sync overhead on a large package.
            output.fd.sync()
        }
        if (response.written > response.total) {
            throw IOException("download exceeds the manifest size cap")
        }
    }

    private fun copyLoop(
        input: InputStream,
        output: FileOutputStream,
        response: PartialWrite,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (response.written <= response.total) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            response.written += read
            onProgress(response.written)
        }
    }

    /** Length + sha256 gates, then the atomic rename that consumes the .part. */
    private fun finalizeVerified(
        base: File,
        response: PartialWrite,
        expectedDigest: String,
        onProgress: (Long) -> Unit,
    ): File {
        val partial = response.file
        if (partial.length() != response.total) {
            onProgress(partial.length())
            throw IOException("download incomplete (${partial.length()}/${response.total})")
        }
        if (!FileDigests.sha256(partial).equals(expectedDigest, true)) {
            partial.delete()
            throw IOException("checksum mismatch - download discarded")
        }
        check(!base.isFile || base.delete()) { "cannot replace the staged $base" }
        check(partial.renameTo(base)) { "cannot finalize the staged download" }
        response.etagFile.delete()
        onProgress(base.length())
        return base
    }

    /**
     * The one in-flight partial download: its .part file, etag sidecar, size
     * cap, resume offset, If-Range etag, running byte count, and whether
     * this attempt appends.
     */
    private class PartialWrite(
        val file: File,
        val etagFile: File,
        val total: Long,
        val start: Long,
        val ifRange: String?,
    ) {
        var append: Boolean = false
        var written: Long = 0
    }

    private const val PART_SUFFIX = ".part"
    private const val ETAG_SUFFIX = ".etag"
    private const val ETAG_HEADER = "ETag"
    private const val HTTP_APPLIED_RANGE = 206
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private val STAGED_SUFFIXES = listOf("", PART_SUFFIX, ETAG_SUFFIX)
}
