package com.pocketrealm.importer

/**
 * Magic-byte format sniffing for importable client archives plus the
 * pre-staging verdict logic. Pure JVM: the SAF plumbing (read a header,
 * optionally probe entry names over a seekable channel) lives in the caller,
 * so the decision table is unit-testable on its own.
 */
enum class ArchiveFormat { ZIP, SEVEN_ZIP, RAR4, RAR5, ISO, UNKNOWN }

object ArchiveFormatSniffer {
    /** Read the first [HEADER_BYTES] of the stream plus the ISO probe at 0x8001. */
    const val HEADER_BYTES = 16
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b)
    private val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
    private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)

    fun sniff(header: ByteArray): ArchiveFormat = when {
        header.size >= 4 && header.copyOfRange(0, 4).let { it[0] == ZIP_MAGIC[0] && it[1] == ZIP_MAGIC[1] &&
            (it[2] == 0x03.toByte() || it[2] == 0x05.toByte() || it[2] == 0x07.toByte()) } -> ArchiveFormat.ZIP
        header.size >= 6 && header.copyOfRange(0, 6).contentEquals(SEVEN_ZIP_MAGIC) -> ArchiveFormat.SEVEN_ZIP
        header.size >= 7 && header.copyOfRange(0, 6).contentEquals(RAR_MAGIC) &&
            header[6] == 0x00.toByte() -> ArchiveFormat.RAR4
        header.size >= 8 && header.copyOfRange(0, 6).contentEquals(RAR_MAGIC) &&
            header[7] == 0x00.toByte() -> ArchiveFormat.RAR5
        else -> ArchiveFormat.UNKNOWN
    }

    /** True when bytes at ISO primary-volume-descriptor offset say `CD001`. */
    fun isIso(probe: ByteArray): Boolean =
        probe.size >= 5 && String(probe, 0, 5, Charsets.US_ASCII) == "CD001"
}

/**
 * The pre-staging decision over what is cheaply knowable before copying a
 * multi-GB archive into app storage: the sniffed format, and — only when the
 * caller could enumerate entry names without extracting (seekable local
 * channel) — the raw name list. Rejections use pinned VAL wording.
 */
object ArchiveQuickCheck {
    sealed interface Verdict {
        /** Format accepted; deep detection happens after staging. */
        data class Accept(val format: ArchiveFormat) : Verdict
        /**
         * setup.exe + setup-N.bin payload: accepted into the Inno installer
         * lane, which extracts the archive to a scratch directory and parses
         * the installer headers. Payloads that turn out not to be supportable
         * Inno installers are rejected there with the VAL-12 wording.
         */
        data class InstallerPayload(val format: ArchiveFormat) : Verdict
        data class Reject(val failure: String) : Verdict
    }

    const val VAL11_ISO = "VAL-11: disc images are not supported — the 1.12.1 ISOs ship older " +
        "installers (1.0.1 / 1.10.0); Pocket Realm needs the extracted 1.12.1.5875 client, not an installer"
    const val VAL12_INSTALLER = "VAL-12: this is the Blizzard setup installer (setup.exe + setup-*.bin), " +
        "not a client — select an archive that directly contains WoW.exe and Data"
    const val VAL12_NOT_INNO = "VAL-12: this archive carries a Windows installer payload Pocket Realm " +
        "cannot use (only original Inno Setup-based 1.12.1 installers are extractable on device) — " +
        "select an archive that directly contains WoW.exe and Data"
    const val VAL13_UNSUPPORTED = "VAL-13: unsupported or unrecognized archive — expected .zip, .7z or .rar"

    fun evaluate(format: ArchiveFormat, entryNames: List<String>?): Verdict = when (format) {
        ArchiveFormat.ISO -> Verdict.Reject(VAL11_ISO)
        ArchiveFormat.UNKNOWN -> Verdict.Reject(VAL13_UNSUPPORTED)
        else -> {
            val names = entryNames ?: return Verdict.Accept(format)
            if (looksLikeInstaller(names)) Verdict.InstallerPayload(format)
            else if (names.none { it.substringAfterLast('/').substringAfterLast('\\').equals("WoW.exe", true) }) {
                // A launcher-only or wrong-content archive: fail fast before the staging copy.
                Verdict.Reject(
                    "VAL-01: no WoW.exe anywhere in the archive — " +
                        "choose the client archive itself, not a launcher, downloader or installer",
                )
            } else Verdict.Accept(format)
        }
    }

    /** setup.exe plus its setup-N.bin payloads, at any single directory level. */
    fun looksLikeInstaller(names: List<String>): Boolean {
        val normalized = names.map { it.replace('\\', '/') }
        val dirs = normalized.map { it.substringBeforeLast('/', "") }.toSet() + ""
        return dirs.any { dir ->
            val files = normalized.filter { it.substringBeforeLast('/', "") == dir }
                .map { it.substringAfterLast('/') }.map { it.lowercase() }
            "setup.exe" in files && files.any { it.startsWith("setup-") && it.endsWith(".bin") }
        }
    }
}
