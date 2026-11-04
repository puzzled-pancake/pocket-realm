package com.pocketrealm.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial breaker (android-core ): ONE Android world start
 * resolves its timeframe stage through TWO DIFFERENT flavor machines.
 *
 * - The conf leg — `ServerRuntimeFiles.worldConfig` ->
 *   `TimeframeStages.confValueFor(snapshot.timeframeStage,
 *   worldLaneFlavor(...).flavorId)` — resolves the PERSISTED Home pick
 * threaded as a value (the R1-arch-5/:world form).
 * - The overlay leg — `WorldRuntimeService.applyStageOverlayOrRefuse` ->
 *   `TimeframeStages.confValue(snapshot.timeframeStage)` — resolves through
 *   the [ActiveFlavor] HOLDER (`TimeframeStage.kt` confValue delegates to
 *   `ActiveFlavor.resolve()`), whose selection is :ui-process-local memory
 *   that never crosses into :world. That is the exact bare-holder class
 * recorded as fixed for:world, surviving on one call site.
 *
 * Today (vanilla-only) the two machines coincide. At a multi-qualified flip
 * with no holder pick in :world, the overlay leg THROWS while the conf leg
 * succeeds — so the start refuses with
 * "world start refused: timeframe overlay failed: expected exactly one
 * qualified flavor, found N — the active flavor resolves the qualified lane",
 * misattributing a lane-resolution failure to the overlay, on EVERY start of
 * every flavor. The in-code claim "the conf's PocketRealm.TimeframeStage and
 * the overlay's desired stage can never disagree (one stage resolution per
 * start)" is therefore false: they are two resolutions through two machines.
 * These tests pin the split mechanically so the flip cannot re-discover it.
 */
class R2WorldStartLaneSplitTest {

    @After
    fun restore() {
        ActiveFlavor.reset()
    }

    @Test
    fun `the overlay leg resolves through the same persisted machine as the conf leg`() {
        // R2-1/R4-2 fix: the overlay leg's
        // desired stage used to resolve through the bare holder machine and
        // REFUSE at a two-qualified flip while the conf leg succeeded. The
        // fixed call site resolves through the SAME persisted-selection
        // expression the conf leg uses - both legs return the booting lane's
        // final-patch stage.
        TwoQualifiedFlavors.install(selection = null)
        // Conf leg's expression (the persisted pick threaded as a value).
        assertEquals("tbc-2.4.3", TimeframeStages.confValueFor("", "tbc"))
        // Overlay leg's expression after the fix: the SAME persisted machine.
        assertEquals(
            "tbc-2.4.3",
            TimeframeStages.confValueFor(
                "",
                com.pocketrealm.client.ActiveFlavorState("tbc")
                    .resolve(ActiveFlavor.qualifiedFlavors, "lane")
                    .flavorId,
            ),
        )
        // And the WorldRuntimeService call site itself carries the fixed
        // shape (source contract): the overlay's desired stage never routes
        // through the bare holder helper.
        val serviceSource = locateSource(
            "android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt",
        )
        val overlayStart = serviceSource.indexOf("private fun applyStageOverlayOrRefuse(")
        val overlayBody = serviceSource.substring(
            overlayStart,
            serviceSource.indexOf("private fun releaseNormalDataLease()", overlayStart),
        )
        assertTrue(
            "applyStageOverlayOrRefuse must not resolve the desired stage " +
                "through the bare holder helper",
            "TimeframeStages.confValue(" !in overlayBody,
        )
        assertTrue(
            "applyStageOverlayOrRefuse must resolve the desired stage through " +
                "the persisted-selection machine (the conf leg's machine)",
            "TimeframeStages.confValueFor(" in overlayBody &&
                "worldLaneFlavor(" in overlayBody,
        )
    }

    private fun locateSource(repoRelative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { java.io.File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
    }

    @Test
    fun `a persisted pick does not save the overlay leg's machine - only a holder selection does`() {
        TwoQualifiedFlavors.install(selection = null)
        // The holder does not see the persisted pick: its resolve is what
        // throws, and its qualified list is the only thing it knows.
        val failure = assertThrows(IllegalStateException::class.java) {
            ActiveFlavor.resolve()
        }
        assertTrue(failure.message!!.contains("expected exactly one qualified flavor, found 2"))
        // While the persisted-threaded form — the one TimeframeOverlayPlan's
        // installed laneResolver and the conf leg use — resolves fine.
        assertEquals(
            TwoQualifiedFlavors.TBC,
            com.pocketrealm.client.ActiveFlavorState("tbc")
                .resolve(ActiveFlavor.qualifiedFlavors, "lane"),
        )
    }

    @Test
    fun `control - today's single-qualified registry makes both machines coincide`() {
        // W4 flip: three lanes are qualified and the sole-qualified degrade
        // is retired — the holder leg needs a selection to coincide with the
        // persisted leg, so the control pins the vanilla pick it compares
        // against.
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
        for (stored in listOf("", "vanilla-1.9", "vanilla-1.12.1", "tbc-2.0.1", "garbage")) {
            assertEquals(
                "stored=$stored must resolve identically through both machines today",
                TimeframeStages.confValueFor(stored, "vanilla"),
                TimeframeStages.confValue(stored),
            )
        }
    }
}
