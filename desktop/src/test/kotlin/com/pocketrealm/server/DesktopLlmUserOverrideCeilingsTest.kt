package com.pocketrealm.server

import com.pocketrealm.llm.LlmModelRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pin: the user-override generation-timeout ceiling is a platform
 * TWIN constant — LlmRuntimePolicy is deliberately not a shared source
 * (the twins differ in their lane wiring: the desktop sidecar block vs
 * the Android :llm-process/NPU members), so nothing structural ties the
 * two MAX_GENERATION_TIMEOUT_SEC values together and they drifted
 * (desktop 300 vs Android 360 — desktop clamped below the embedded tier's
 * own 360 s wall). This test IS the tie: it reads the Android twin's
 * source from the checkout and asserts the ceilings are equal, so neither
 * platform's editor can move one alone. Deliberately separate from
 * [DesktopLlmEmbeddedConfTest], whose MEASURED_* tier pins are the
 * LVF-bots-llm-014 contract and stay untouched.
 */
class DesktopLlmUserOverrideCeilingsTest {

    @Test
    fun userOverrideCeilingMatchesTheAndroidTwin() {
        val twinSource = androidTwinSource().readText()
        val match = Regex("""const\s+val\s+MAX_GENERATION_TIMEOUT_SEC\s*=\s*(\d+)""")
            .find(twinSource)
            ?: error(
                "the Android twin no longer declares MAX_GENERATION_TIMEOUT_SEC " +
                    "as a const val — update this pin's reader to the new shape",
            )
        val androidCeiling = match.groupValues[1].toInt()
        assertEquals(
            "the user-override ceiling must match the Android twin " +
                "(desktop ${LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC} vs " +
                "android $androidCeiling): the twins are separately editable " +
                "sources, this equality is the cross-platform settings-parity " +
                "contract (GAP-8)",
            androidCeiling,
            LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC,
        )
        // The sizing law that picks the unified value (mirrors the Android
        // LlmGenerationTimeoutWallTest): the ceiling must enclose the tier
        // default the settings screens present — "Tier default" resolves to
        // the embedded cold-start wall, so a ceiling under it would clamp an
        // override of exactly the tier's own value BELOW the wall the tier
        // ships.
        assertTrue(
            "the ceiling ${LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC} must enclose the " +
                "embedded tier wall ${LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC}",
            LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC >=
                LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC,
        )
    }

    /**
     * Locates the Android twin's source from the test working dir, walking
     * up to the checkout — the same dev-run idiom as ServerRuntimeFiles'
     * resolveBundledAsset walk-up (generateSequence from user.dir over the
     * ancestors). Packaged runs have no checkout; this is a dev-checkout
     * unit test, same as every other source-pinned desktop test.
     */
    private fun androidTwinSource(): File {
        val relative = "android/app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt"
        val candidate = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(WALK_UP_LIMIT)
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?: error(
                "cannot locate the Android twin ($relative) by walking up from " +
                    "${System.getProperty("user.dir")} ($WALK_UP_LIMIT levels) — the test " +
                    "must run from the dev checkout",
            )
        return candidate
    }

    private companion object {
        /** Mirrors ServerRuntimeFiles.MAX_ASSET_WALK_UP (checkout sits well within 6). */
        private const val WALK_UP_LIMIT = 6
    }
}
