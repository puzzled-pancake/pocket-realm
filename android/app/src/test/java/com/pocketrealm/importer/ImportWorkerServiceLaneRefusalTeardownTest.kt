package com.pocketrealm.importer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-r3-1 (W4 code-red round 3): the flip's no-pick import refusal is the
 * DESIGNED first-run outcome — and before this wave's fix both service
 * arms returned from it WITHOUT the teardown, leaking one 8h
 * PARTIAL_WAKE_LOCK per attempt and leaving the foreground service up
 * (the cleanup finally sits in the SECOND try, below the construction).
 * The repo has no Robolectric lane, so this pin holds the SOURCE SHAPE:
 * in each arm, the IllegalStateException catch must run the full
 * teardown — wake-lock release, foreground detach, stopSelf — between
 * the refusal notification and its return. A bare `return@launch`
 * directly after the notify is the leak, and this test fails on it.
 */
class ImportWorkerServiceLaneRefusalTeardownTest {
    private fun serviceSource(): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) {
            it.parentFile
        }.firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        return File(
            root,
            "android/app/src/main/java/com/pocketrealm/importer/" +
                "ImportWorkerService.kt",
        ).readText()
    }

    private fun refusalArms(source: String): List<String> =
        Regex("""catch \(lane: IllegalStateException\) \{.*?return@launch""",
            RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .map { it.value }
            .toList()

    @Test
    fun bothImportArmsCarryTheLaneRefusal() {
        val arms = refusalArms(serviceSource())
        assertTrue(
            "the folder and archive arms each carry one no-pick refusal " +
                "catch (found ${arms.size})",
            arms.size == 2,
        )
    }

    @Test
    fun theRefusalPathReleasesTheWakeLockAndStopsTheService() {
        val source = serviceSource()
        val arms = refusalArms(source)
        for ((index, arm) in arms.withIndex()) {
            assertTrue(
                "arm $index: the refusal must release the held wake lock " +
                    "before returning (R4-r3-1: the 8h lock is already " +
                    "acquired when construction refuses)",
                "wakeLock?.let { if (it.isHeld) it.release() }" in arm &&
                    "wakeLock = null" in arm,
            )
            assertTrue(
                "arm $index: the refusal must leave the foreground state " +
                    "and stop the service",
                "ServiceCompat.stopForeground" in arm && "stopSelf(startId)" in arm,
            )
            assertTrue(
                "arm $index: the refusal must settle the started metrics " +
                    "marker (markStarted ran before construction)",
                "ImportProcessMetricsSampler.markStopped(applicationContext)" in arm,
            )
            // the leak shape: a return with no teardown between notify and
            // return
            val afterNotify = arm.substringAfter("return@launch", "")
            assertTrue(
                "arm $index: a bare return@launch directly after the " +
                    "notification is the leak shape this pin exists for",
                afterNotify.isEmpty(),
            )
        }
    }
}
