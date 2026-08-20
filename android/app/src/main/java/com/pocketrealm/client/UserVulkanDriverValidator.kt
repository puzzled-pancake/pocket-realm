package com.pocketrealm.client

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Offline import gate for user-provided Turnip drivers. Every rejection
 * carries an exact human-readable reason surfaced verbatim in the UI and
 * diagnostics; nothing is silently accepted or substituted.
 */
object UserVulkanDriverValidator {
    const val DEFAULT_MAX_IMPORT_BYTES: Long = 256L * 1024 * 1024
    const val EM_AARCH64 = 183
    const val MIN_PT_LOAD_ALIGN = 0x4000L

    sealed class ElfOutcome {
        data class Accepted(val sha256: String) : ElfOutcome()
        data class Rejected(val reason: String) : ElfOutcome()
    }

    sealed class IcdOutcome {
        data class Accepted(val apiVersion: String?, val warning: String?) : IcdOutcome()
        data class Rejected(val reason: String) : IcdOutcome()
    }

    /** Size cap + ELF64/aarch64 identity + the 16 KB PT_LOAD alignment rule. */
    fun validateElf(
        library: File,
        maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
    ): ElfOutcome {
        if (library.length() > maxImportBytes) {
            return ElfOutcome.Rejected(sizeRejection(library.length(), maxImportBytes))
        }
        // Only the header + program header table are inspected; a bounded
        // prefix keeps a 256 MiB import off the heap.
        val fileSize = library.length()
        val prefix = ByteArray(minOf(fileSize, ELF_PREFIX_BYTES).toInt())
        library.inputStream().use { input ->
            var read = 0
            while (read < prefix.size) {
                val n = input.read(prefix, read, prefix.size - read)
                if (n < 0) break
                read += n
            }
        }
        elfRejection(prefix, fileSize)?.let { return ElfOutcome.Rejected(it) }
        return ElfOutcome.Accepted(sha256Of(library))
    }

    /**
     * Pure ELF64 check. Returns the exact rejection reason, or null when the
     * library is an ELF64 aarch64 image whose PT_LOAD segments all satisfy
     * the 16 KB page-alignment rule (Android 15+ kernels, including the RP6,
     * load with 16 KB pages and SIGBUS otherwise). [fileSize] is the full
     * library length; [bytes] may be a bounded prefix of it.
     */
    fun elfRejection(bytes: ByteArray, fileSize: Long = bytes.size.toLong()): String? {
        if (fileSize < 64 || bytes.size < 64) {
            return "The file is too short to be an ELF64 library ($fileSize bytes)."
        }
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return "The file is not an ELF library (missing ELF magic)."
        }
        if (bytes[4] != 2.toByte()) {
            return "The driver is not a 64-bit ELF library (ELF class ${bytes[4]})."
        }
        if (bytes[5] != 1.toByte()) {
            return "The driver is not a little-endian ELF library."
        }
        val machine = u16(bytes, 18)
        if (machine != EM_AARCH64) {
            return "The driver targets ELF machine $machine, not aarch64 " +
                "(EM_AARCH64/$EM_AARCH64) — Pocket Realm runs on ARM64."
        }
        val phentsize = u16(bytes, 54)
        val phnum = u16(bytes, 56)
        if (phnum == 0) {
            return "The ELF has no program headers; the 16 KB load alignment cannot be verified."
        }
        if (phentsize < 56) {
            return "The ELF program header table is malformed (e_phentsize=$phentsize)."
        }
        val phoff = u64(bytes, 32)
        // Overflow-safe bounds check: the table must lie inside the file.
        if (phoff <= 0 || phnum.toLong() * phentsize > fileSize - phoff) {
            return "The ELF program header table is truncated."
        }
        if (phoff + phnum.toLong() * phentsize > bytes.size) {
            // The table lies past the loaded prefix; it exists on disk but is
            // not inspectable — fail closed rather than skip segments.
            return "The ELF program header table is truncated."
        }
        for (i in 0 until phnum) {
            val header = (phoff + i.toLong() * phentsize).toInt()
            if (u32(bytes, header) == PT_LOAD) {
                val align = u64(bytes, header + 48)
                if (align < MIN_PT_LOAD_ALIGN) {
                    return "This build has a PT_LOAD segment with p_align=0x${align.toString(16)}, " +
                        "but the RP6 kernel uses 16 KB pages and this build will crash on load. " +
                        "Use a build made with `-Wl,-z,max-page-size=0x4000`."
                }
            }
        }
        return null
    }

    /** ICD manifest sanity + the warn-only Vulkan API floor for DXVK 2.4.1. */
    fun validateIcd(json: String): IcdOutcome {
        val document = try {
            JSONObject(json)
        } catch (error: Exception) {
            return IcdOutcome.Rejected(
                "The ICD JSON could not be parsed: ${error.message ?: error.javaClass.simpleName}.",
            )
        }
        val icd = document.optJSONObject("ICD")
            ?: return IcdOutcome.Rejected("The ICD JSON has no ICD object.")
        val libraryPath = icd.optString("library_path")
        if (libraryPath.isBlank()) {
            return IcdOutcome.Rejected("The ICD JSON has no ICD.library_path entry.")
        }
        val apiVersion = icd.optString("api_version").takeIf { it.isNotBlank() }
        val warning = apiVersion?.let(::apiVersionWarning)
        return IcdOutcome.Accepted(apiVersion, warning)
    }

    /**
     * Vulkan API floor is warn-only, never an import rejection: DXVK 2.4.1
     * needs 1.3, while the box64-dxvk-1.10.3 compatibility package pairs
     * with 1.1 (mirrors the catalog's minimum_vulkan_by_renderer).
     */
    fun apiVersionWarning(apiVersion: String): String? {
        val match = Regex("^(\\d+)\\.(\\d+)").find(apiVersion) ?: return null
        val (major, minor) = match.destructured
        // Absurd components stay warn-free rather than throwing NumberFormat.
        val majorInt = major.toIntOrNull() ?: return null
        val minorInt = minor.toIntOrNull() ?: return null
        val meets13 = majorInt > 1 || (majorInt == 1 && minorInt >= 3)
        return if (meets13) null else {
            "This build reports Vulkan $apiVersion, below the Vulkan 1.3 that DXVK 2.4.1 " +
                "requires — pair it with the DXVK 1.10.3 compatibility package or expect " +
                "DXVK to fail to initialize."
        }
    }

    private const val PT_LOAD = 1
    private const val ELF_PREFIX_BYTES = 4L * 1024 * 1024

    /** Single owner of the exact size-cap rejection string (I8). */
    fun sizeRejection(actual: Long, cap: Long): String =
        "The imported driver is $actual bytes (${actual / (1024 * 1024)} MiB); " +
            "the import cap is ${cap / (1024 * 1024)} MiB."

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        }
        return value
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
