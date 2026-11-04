package com.pocketrealm.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Design-timeframe: the ANDROID world-start wiring contract — every
 * world start applies the stage overlay AFTER the conf write and BEFORE the
 * native boot, and an overlay failure carries the loud start-failed-overlay
 * lifecycle record. WorldRuntimeService needs a live Android context (the
 * device lane drives the binder), so the contract is pinned as a
 * source-contract test over the shipped service source — the same pattern
 * the repo's other host-untestable glue uses.
 */
class TimeframeOverlayWiringTest {

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
            File("app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
            File("android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
            File("../android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("WorldRuntimeService.kt not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }

    @Test
    fun everyWorldStartLegAppliesTheOverlayBeforeTheNativeBoot() {
        val text = serviceSource()
        // The shared helper exists and routes through the shared decision
        // plan (identical statements to the desktop lane).
        assertTrue("applyStageOverlayOrRefuse" in text)
        assertTrue("TimeframeOverlayApplier.prepareForStart" in text)
        assertTrue("TimeframeStages.confValue" in text)
        // All THREE world-start legs apply it (the binder's start legs).
        assertEquals(
            "all three world-start legs must apply the overlay",
            3,
            Regex("applyStageOverlayOrRefuse\\(").findAll(text).count() - 1,
        )
        // Ordering in the two lease-guarded legs: the conf is produced
        // first, then the overlay, then the native start consumes it.
        listOf("start-normal", "start-bot-profile").forEach { leg ->
            val marker = text.indexOf("startWithNormalData {")
            assertNotEquals(-1, marker)
        }
        val startNormalBlock = text.substring(
            text.indexOf("start-normal"),
            text.indexOf("start-bot-profile"),
        )
        assertTrue(
            "start-normal: conf write precedes the overlay",
            startNormalBlock.indexOf("worldConfigNormal") < startNormalBlock.indexOf("applyStageOverlayOrRefuse("),
        )
        assertTrue(
            "start-normal: the overlay precedes the native boot (the lambda returns the conf)",
            startNormalBlock.indexOf("applyStageOverlayOrRefuse(") < startNormalBlock.lastIndexOf("config"),
        )
        // The simple (o09-baseline) leg: conf, overlay, startNative in order.
        val startAtBlock = text.substring(
            text.indexOf("override fun startAt("),
            text.indexOf("override fun startNormal()"),
        )
        assertTrue(
            "startAt: conf write precedes the overlay",
            startAtBlock.indexOf("files.worldConfig(") < startAtBlock.indexOf("applyStageOverlayOrRefuse("),
        )
        assertTrue(
            "startAt: the overlay precedes the native boot",
            startAtBlock.indexOf("applyStageOverlayOrRefuse(") < startAtBlock.indexOf("WorldNative.startNative("),
        )
    }

    @Test
    fun everyWorldStartLegResolvesTheStageOnceForConfAndOverlay() {
        // One settings read per start: each leg loads ONE snapshot and feeds
        // BOTH the conf write (settingsSnapshot = ...) and the overlay — two
        // independent loads could straddle a settings write and boot a world
        // whose conf names a stage the DB/ledger were never brought to.
        val text = serviceSource()
        assertEquals(
            "each world-start leg passes its snapshot to the conf write",
            3,
            Regex("settingsSnapshot = settingsSnapshot").findAll(text).count(),
        )
        listOf(
            "applyStageOverlayOrRefuse(settingsSnapshot)",
        ).forEach { marker ->
            assertEquals(
                "each world-start leg feeds the same snapshot to the overlay: $marker",
                3,
                Regex(Regex.escape(marker)).findAll(text).count(),
            )
        }
    }

    @Test
    fun anOverlayFailureRefusesTheStartLoudlyWithTheLifecycleRecord() {
        val text = serviceSource()
        val helper = text.substring(text.indexOf("private fun applyStageOverlayOrRefuse"))
        assertTrue("start-failed-overlay" in helper)
        assertTrue("world start refused: timeframe overlay failed" in helper)
        // The helper consumes the CALLER's snapshot — the same one the conf
        // write consumed — and resolves it to the concrete conf stage (never
        // a raw stored id, never a second settings read); the datadir is the
        // shared control-plane's.
        assertTrue(
            "the overlay's desired stage is the caller's one snapshot",
            "private fun applyStageOverlayOrRefuse(settingsSnapshot: Settings.Snapshot)" in helper,
        )
        assertTrue("TimeframeStages.confValue" in helper)
        assertTrue("DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME" in helper)
    }
}
