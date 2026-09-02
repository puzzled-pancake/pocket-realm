package com.pocketrealm.llm

import android.os.Build
import java.io.File

/**
 * App-side pre-flight for the Hexagon NPU (HTP) path. Answers one question:
 * may the service spawn llama-server with the hexagon backend enabled?
 *
 * Why this must be conservative:
 *  - a failed HTP session open poisons the ggml backend registry (null device)
 *    and takes the whole llama-server process down — CPU mode included — so a
 *    doomed first spawn must never happen;
 *  - an unsatisfiable anon-for-DMA allocation at model load PANICS the kernel
 *    (whole-device reboot, no LMK rescue) — hence the MemAvailable load gate:
 *    refuse below peak + ~0.7 GB margin;
 *  - all signals readable from an app uid are best-effort: SELinux may deny
 *    sysfs reads. The authoritative verdict is the child's own session open,
 *    which the isolated :llm process + crash counter contain.
 *
 * The three failure layers and what detects them:
 *  1. driver (libcdsprpc.so missing)  -> backend skips registration cleanly;
 *  2. session open (DSP down, skel
 *     not in ADSP_LIBRARY_PATH)       -> registry poison -> pre-flight + crash counter;
 *  3. runtime DSP death               -> process abort -> supervisor restart.
 */
object HexagonProbe {

    /** Devices with a known-good HTP arch mapping. QCS8550/SM8550 (RP6) = v73. */
    private val SOC_ARCH = mapOf(
        "8550" to 73,   // Snapdragon 8 Gen 2 / QCS8550 (Retroid Pocket 6)
        "8650" to 75,   // Snapdragon 8 Gen 3 / SM8650 (SOC_MODEL form)
        "8750" to 79,   // Snapdragon 8 Elite
    )

    enum class State { READY, NOT_DETECTED, LIBS_MISSING, SKELS_MISSING, INSUFFICIENT_MEMORY, BLOCKED }

    data class Result(
        val state: State,
        val arch: Int?,            // HTP arch version (73 on the RP6), null = unknown
        val detail: String,        // human-readable reason for the UI
        val requiredMemKb: Long,   // mem gate for the current model (0 if not applicable)
        val memAvailKb: Long,
    ) {
        val ready: Boolean get() = state == State.READY
    }

    /** NativeLibraryDir contents we require for the NPU path. */
    fun backendLibFile(context: android.content.Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libggmlhex.so")

    /** Directory the DSP file service reads skels from (extracted at start). */
    fun dspDir(context: android.content.Context): File =
        File(context.filesDir, "dsp")

    fun expectedArch(): Int? {
        val soc = socModel()
        return SOC_ARCH.entries.firstOrNull { soc.contains(it.key) }?.value
    }

    private fun socModel(): String =
        ((if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "") +
            Build.HARDWARE + " " + Build.DEVICE).uppercase()

    /**
     * Full probe. [modelFile] drives the memory gate; pass null for a
     * hardware-only check (UI display).
     */
    fun probe(context: android.content.Context, modelFile: File? = null): Result {
        // 1. Hardware: FastRPC char device + (best-effort) remoteproc state.
        val fastrpcDev = File("/dev/adsprpc-smd").exists() ||
            File("/dev/adsprpc-smd-secure").exists() ||
            File("/dev/fastrpc-adsp").exists() ||
            firstExisting("/dev", "fastrpc") != null
        val remoteproc = remoteprocRunning()
        if (!fastrpcDev && remoteproc != true) {
            return Result(
                State.NOT_DETECTED, expectedArch(),
                "no FastRPC device and no running hexagon remoteproc on this device",
                0, memAvailableKb(),
            )
        }

        // 2. Backend lib shipped and DSP skels extracted.
        if (!backendLibFile(context).isFile) {
            return Result(
                State.LIBS_MISSING, expectedArch(),
                "libggmlhex.so not present in ${context.applicationInfo.nativeLibraryDir} " +
                    "(CPU-only build installed)",
                0, memAvailableKb(),
            )
        }
        val skel = expectedArch()?.let { "libggml-htp-v$it.so" }
        val dsp = dspDir(context)
        val skels = dsp.listFiles { f -> f.name.startsWith("libggml-htp-v") }?.map { it.name } ?: emptyList()
        if (skels.isEmpty() || (skel != null && skel !in skels)) {
            return Result(
                State.SKELS_MISSING, expectedArch(),
                "DSP skels not extracted to $dsp (expected ${skel ?: "any libggml-htp-v*.so"})",
                0, memAvailableKb(),
            )
        }

        // 3. Memory gate (kernel-panic protection).
        val avail = memAvailableKb()
        val required = if (modelFile != null && modelFile.isFile) requiredMemKb(modelFile.length()) else 0
        if (required > 0 && avail < required) {
            return Result(
                State.INSUFFICIENT_MEMORY, expectedArch(),
                "MemAvailable ${avail / 1024} MB < required ${required / 1024} MB — an " +
                    "unsatisfiable DSP DMA load reboots this device; refusing (free RAM or use CPU mode)",
                required, avail,
            )
        }
        return Result(
            State.READY, expectedArch(),
            buildString {
                append("HTP v").append(expectedArch() ?: "?")
                if (remoteproc == true) append(" · remoteproc running")
                if (required > 0) append(" · mem ${avail / 1024}/${required / 1024} MB")
            },
            required, avail,
        )
    }

    /** Load-gate: observed peak ≈ file × 1.1, plus a 0.7 GB panic margin. */
    fun requiredMemKb(modelBytes: Long): Long = (modelBytes * 11L / 10L) / 1024L + 700L * 1024L

    fun memAvailableKb(): Long = try {
        Regex("MemAvailable:\\s+(\\d+)").find(File("/proc/meminfo").readText())
            ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /** true / false / null (unreadable — SELinux or absent). */
    private fun remoteprocRunning(): Boolean? {
        return try {
            val root = File("/sys/class/remoteproc")
            val dirs = root.listFiles() ?: return null
            var sawAny = false
            for (d in dirs) {
                val name = try { File(d, "name").readText().trim() } catch (_: Exception) { "" }
                val state = try { File(d, "state").readText().trim() } catch (_: Exception) { "" }
                if (name.isEmpty()) continue
                sawAny = true
                if ((name.contains("hexagon") || name.contains("cdsp")) && state == "running") return true
            }
            if (sawAny) false else null
        } catch (_: Exception) {
            null
        }
    }

    private fun firstExisting(dir: String, prefix: String): File? = try {
        File(dir).listFiles { f -> f.name.startsWith(prefix) }?.firstOrNull()
    } catch (_: Exception) {
        null
    }
}
