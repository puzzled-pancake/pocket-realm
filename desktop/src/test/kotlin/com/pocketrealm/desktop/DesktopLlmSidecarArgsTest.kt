package com.pocketrealm.desktop

import com.pocketrealm.llm.LlmModelRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DESK-9 engine-knob contract on the sidecar argv (pinned without
 * spawning a real multi-GB llama-server): decode threads ride -t, offload
 * layers ride -ngl only above the CPU-only desktop default, the context
 * follows the SELECTED model's tier, and the Android runtimeConfig
 * extras (--jinja, --load-mode none) are verbatim.
 */
class DesktopLlmSidecarArgsTest {

    private val binary = File("E:/fake/llama-server.exe")
    private val model = File("E:/staged/gemma4-E2B-TUNED-q4_0.gguf")
    private val tier = LlmModelRegistry.TUNED_E2B.tierProfile

    @Test
    fun cpuOnlyLaunchOmitsNglAndCarriesTheAndroidExtras() {
        val argv = DesktopLlmModelManager(
            DesktopStorageRoots(File("E:/fake/roots")),
        ).sidecarArgs(binary, model, tier, port = 54321, decodeThreads = 3, offloadLayers = 0)
        val expected = listOf(
            binary.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", "54321",
            "-c", "12288", // the SELECTED model's tier context
            "-t", "3",
            "--jinja", "--load-mode", "none", "--no-webui",
        )
        assertEquals(expected, argv)
        assertFalse("CPU-only must emit no -ngl", "-ngl" in argv)
    }

    @Test
    fun offloadAndThreadKnobsRideTheLaunch() {
        val argv = DesktopLlmModelManager(
            DesktopStorageRoots(File("E:/fake/roots-2")),
        ).sidecarArgs(binary, model, tier, port = 54321, decodeThreads = 6, offloadLayers = 16)
        assertTrue("-ngl must ride when offload > 0: $argv", "-ngl" in argv)
        assertEquals("16", argv[argv.indexOf("-ngl") + 1])
        assertEquals("6", argv[argv.indexOf("-t") + 1])
    }

    @Test
    fun knobsNormalizeBeforeTheyRide() {
        val argv = DesktopLlmModelManager(
            DesktopStorageRoots(File("E:/fake/roots-3")),
        ).sidecarArgs(binary, model, tier, port = 54321, decodeThreads = 999, offloadLayers = -3)
        assertEquals("8", argv[argv.indexOf("-t") + 1]) // MAX_THREADS clamp
        assertFalse("a non-positive offload must emit no -ngl", "-ngl" in argv)
    }

    /**
     * LVF-bots-llm-009 companion: the sidecar's merged stdout/stderr must
     * land in a bounded, launch-truncated sink under the llm root
     * (sidecar-stdout.log), not an unread ProcessBuilder pipe — an unread
     * Windows anonymous pipe both blocks the child's log writers and loses
     * every diagnostic word (the exit-code line included) exactly when the
     * lane dies. The path is pinned here; the drain thread itself is wired
     * in DesktopLlmModelManager.launch before the health probe.
     */
    @Test
    fun sidecarOutputSinkRidesTheLlmRoot() {
        val manager = DesktopLlmModelManager(DesktopStorageRoots(File("E:/fake/roots-4")))
        val sink = manager.sidecarOutputFile()
        assertEquals(
            File("E:/fake/roots-4/runtime/llm/sidecar-stdout.log").path,
            sink.path,
        )
        assertEquals("sidecar-stdout.log", sink.name)
        assertEquals(
            "sidecar-stdout.log",
            DesktopLlmModelManager.SIDECAR_OUTPUT_LOG_NAME,
        )
    }
}
