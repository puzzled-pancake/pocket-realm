package com.pocketrealm.importer

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.InputStream

/**
 * the desktop RAR lane — the JVM twin of the Android
 * importer/RarArchiveSource (libarchive JNI in the APK) over junrar's
 * pure-Java RAR4/RAR5 reader. Same ImportSource contract as the shared
 * [ZipArchiveSource]/[SevenZipArchiveSource]: it consumes the
 * [ImportExtractionPolicy.Classified] inventory produced by
 * [ArchiveClientScanner], opens entries by their original archive name
 * ([ImportSourceEntry.key]), and re-verifies per-entry declared sizes during
 * [inventory] so a corrupted staged file fails closed before publish.
 *
 * The VAL-13 policy refusals survive the twin verbatim (they are the reason a
 * partial RAR lane is safe to offer):
 *  - password-protected archives (header- or file-encrypted), either format;
 *  - multi-volume archives (the RAR4 MHD_VOLUME main-header flag, or any
 *    split-before/split-after file header);
 *  - solid RAR4 archives (the MHD_SOLID main-header flag; the RAR5 solid
 *    path stays readable through junrar's own sequential decode).
 *
 * Licensing posture (the reason the Android lane uses libarchive instead):
 * junrar ships under the unrar license — free to use in an extractor, but it
 * forbids using the code to build a RAR-compatible ARCHIVER. This lane only
 * reads, which is the permitted side; the rest of the desktop build stays
 * unrar-license-free because every junrar touchpoint lives in this file.
 */
internal class RarArchiveSource(
    private val file: File,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {
    private val archive: Archive = openRarArchive(file)

    /** The header list in stored archive order — inventory order IS archive
     * order (the copy loop is one forward pass of [open] calls). */
    private val headers: List<FileHeader> = archive.fileHeaders

    override fun inventory(): SourceInventory {
        // Stored archive order, matched against the classified inventory by
        // the original entry name (the same contract as the Android lane's
        // libarchive walk).
        val byKey = classified.entries.associateBy { it.key }
        val entries = headers.mapNotNull { header ->
            val name = header.fileName ?: return@mapNotNull null
            val entry = byKey[name] ?: return@mapNotNull null
            val size = header.fullUnpackSize
            if (header.isDirectory != entry.directory || size != entry.size) {
                throw ImportRejected("The RAR changed during import: ${entry.relativePath}")
            }
            entry
        }
        val listedNameSet = headers.asSequence().mapNotNull { it.fileName }.toHashSet()
        val missing = classified.entries.firstOrNull { it.key !in listedNameSet }
        if (missing != null || entries.size != classified.entries.size) {
            throw ImportRejected(
                "The RAR changed during import — an entry is gone: ${missing?.relativePath ?: "its classified entries"}",
            )
        }
        return SourceInventory(entries, classified.fileCount, classified.totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        val header = headers.firstOrNull { it.fileName == entry.key }
            ?: throw ImportRejected("The RAR changed during import: ${entry.relativePath}")
        return try {
            archive.getInputStream(header)
        } catch (error: Exception) {
            throw ImportRejected("An archive entry could not be read: ${entry.relativePath}")
        }
    }

    override fun close() {
        runCatching { archive.close() }
    }
}

/**
 * Opens a staged RAR through junrar and runs the pinned VAL-13 refusals
 * BEFORE any byte is served — the single gate both the deep-detection lane
 * and the installer scratch lane go through. A RAR whose headers never parse
 * (truncated, damaged, password-protected headers this build cannot read)
 * refuses here too, instead of falling through to the generic no-client
 * rejection with a misleading reason. Callers own the returned archive
 * (AutoCloseable).
 */
internal fun openRarArchive(file: File): Archive {
    val archive = try {
        Archive(file)
    } catch (error: RarException) {
        throw ImportRejected(VAL13_RAR_UNREADABLE).initCause(error)
    } catch (error: java.io.IOException) {
        throw ImportRejected(VAL13_RAR_UNREADABLE).initCause(error)
    }
    try {
        applyRarRefusals(file, archive)
    } catch (error: Throwable) {
        runCatching { archive.close() }
        throw error
    }
    return archive
}

private fun applyRarRefusals(file: File, archive: Archive) {
    if (archive.isEncrypted) throw ImportRejected(VAL13_RAR_ENCRYPTED)
    // A RAR4 archive always carries a MAIN_HEAD block and a RAR5 archive a
    // MAIN head, so "no main header AND no entries" means the header walk
    // failed — refuse as unreadable rather than mislabeling the archive.
    val mainHeader = runCatching { archive.mainHeader }.getOrNull()
    val headers = archive.fileHeaders
    if (mainHeader == null && headers.isEmpty()) {
        throw ImportRejected(VAL13_RAR_UNREADABLE)
    }
    if (mainHeader != null) {
        if (mainHeader.isMultiVolume) throw ImportRejected(VAL13_RAR_MULTIVOLUME)
        if (mainHeader.isSolid && isRar4Magic(file)) throw ImportRejected(VAL13_RAR_SOLID_RAR4)
    }
    if (headers.any { it.isSplitBefore || it.isSplitAfter }) {
        throw ImportRejected(VAL13_RAR_MULTIVOLUME)
    }
    if (headers.any { it.isEncrypted }) throw ImportRejected(VAL13_RAR_ENCRYPTED)
}

/**
 * One header walk over a staged RAR producing the raw entry list plus the
 * encrypted flag — the desktop twin of the Android listRarEntries. The pinned
 * VAL-13 refusals (encrypted / multi-volume / solid-RAR4) run first, so both
 * the deep-detection lane and the installer scratch lane refuse before
 * staging a byte. Non-solid archives walk cheaply; solid RAR5 archives pay a
 * header-only pass here (data is decoded lazily per entry).
 */
internal fun listRarEntries(file: File): List<ArchiveClientScanner.RawEntry> {
    openRarArchive(file).use { archive ->
        return archive.fileHeaders.mapNotNull { header ->
            val name = header.fileName ?: return@mapNotNull null
            ArchiveClientScanner.RawEntry(
                name,
                header.isDirectory,
                header.fullUnpackSize,
                header.isEncrypted,
            )
        }.also { entries ->
            if (entries.size > MAX_HEADER_WALK) {
                throw ImportRejected("Too many entries in the RAR")
            }
        }
    }
}

/**
 * Streams a raw entry's bytes from a staged RAR for the detection reader
 * (the WoW.exe identity probe — capped at [maxBytes]). Runs the same
 * [openRarArchive] refusal gate as every other RAR touchpoint.
 */
internal fun readRarEntry(file: File, rawName: String, maxBytes: Int): ByteArray {
    openRarArchive(file).use { archive ->
        val header = archive.fileHeaders.firstOrNull { it.fileName == rawName }
            ?: throw ImportRejected("The RAR changed during import: $rawName")
        if (header.isDirectory) throw ImportRejected("The RAR changed during import: $rawName")
        try {
            archive.getInputStream(header).use { stream ->
                val buffer = ByteArray(maxBytes)
                var filled = 0
                while (filled < maxBytes) {
                    val count = stream.read(buffer, filled, maxBytes - filled)
                    if (count <= 0) break
                    filled += count
                }
                return buffer.copyOf(filled)
            }
        } catch (error: ImportRejected) {
            throw error
        } catch (error: Exception) {
            throw ImportRejected("RAR extraction failed — ${error.message}")
        }
    }
}

/** The sniffed-magic probe: RAR4 is marker+0x00 at byte 6 (the RAR5 marker
 * carries 0x01 there). Mirrors [ArchiveFormatSniffer.sniff]. */
internal fun isRar4Magic(file: File): Boolean = try {
    file.inputStream().use { stream ->
        val marker = ByteArray(MAGIC_PROBE_BYTES)
        var read = 0
        while (read < MAGIC_PROBE_BYTES) {
            val count = stream.read(marker, read, MAGIC_PROBE_BYTES - read)
            if (count < 0) break
            read += count
        }
        read >= 7 && marker[6] == 0x00.toByte()
    }
} catch (_: java.io.IOException) {
    false
}

/** The pinned VAL-13 RAR-refusal copy (shared by the source and both walks). */
internal const val VAL13_RAR_ENCRYPTED =
    "This archive is password-protected — Pocket Realm cannot open encrypted archives; " +
        "re-pack it without a password (or extract on a PC and import the folder)"

internal const val VAL13_RAR_MULTIVOLUME =
    "Multi-volume RAR archives are not supported — import the single-volume .rar " +
        "that contains the complete client"

internal const val VAL13_RAR_SOLID_RAR4 =
    "Solid RAR4 archives are not supported — re-pack the client as .zip or .7z " +
        "(or extract the client folder and import it)"

internal const val VAL13_RAR_UNREADABLE =
    "The RAR archive could not be read — it may be damaged or password-protected; " +
        "re-pack the client as .zip or .7z (or extract the client folder and import it)"

private const val MAGIC_PROBE_BYTES = 8
private const val MAX_HEADER_WALK = 110_000
