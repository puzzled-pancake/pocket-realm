package com.pocketrealm.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase E: the user-driver crash guard state machine, its registry
 * persistence, and the diagnostics session record — all pure/JVM-side.
 */
class UserVulkanCrashGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun driver(streak: Int = 0, quarantined: Boolean = false) = UserVulkanDriver(
        id = "user-turnip-26-3",
        label = "Turnip 26.3",
        libraryFileName = "driver.so",
        sha256 = "a".repeat(64),
        vulkanApiVersion = "1.3.290",
        addedAt = 1L,
        earlyCrashStreak = streak,
        quarantined = quarantined,
        quarantineReason = if (quarantined) UserVulkanCrashGuard.QUARANTINE_REASON else null,
    )

    @Test
    fun twoConsecutiveEarlyDeathsQuarantineWithTheExactReason() {
        val afterFirst = UserVulkanCrashGuard.onSessionOutcome(driver(), earlyDeath = true)
        assertEquals(1, afterFirst.earlyCrashStreak)
        assertFalse(afterFirst.quarantined)
        val afterSecond = UserVulkanCrashGuard.onSessionOutcome(afterFirst, earlyDeath = true)
        assertTrue(afterSecond.quarantined)
        assertEquals(
            "quarantined after 2 early crashes",
            afterSecond.quarantineReason,
        )
        assertEquals(2, afterSecond.earlyCrashStreak)
    }

    @Test
    fun aCleanOrSurvivingSessionResetsTheStreak() {
        val afterCrash = UserVulkanCrashGuard.onSessionOutcome(driver(), earlyDeath = true)
        val afterOk = UserVulkanCrashGuard.onSessionOutcome(afterCrash, earlyDeath = false)
        assertEquals(0, afterOk.earlyCrashStreak)
        assertFalse(afterOk.quarantined)
        // Starting clean stays clean.
        assertEquals(driver(), UserVulkanCrashGuard.onSessionOutcome(driver(), false))
    }

    @Test
    fun quarantineIsStickyAndIdempotent() {
        val quarantined = UserVulkanCrashGuard.onSessionOutcome(
            UserVulkanCrashGuard.onSessionOutcome(driver(), true), true,
        )
        assertEquals(quarantined, UserVulkanCrashGuard.onSessionOutcome(quarantined, true))
        assertEquals(quarantined, UserVulkanCrashGuard.onSessionOutcome(quarantined, false))
    }

    @Test
    fun earlyDeathClassificationBoundaries() {
        val threshold = UserVulkanCrashGuard.EARLY_DEATH_UPTIME_MS
        assertTrue(UserVulkanCrashGuard.isEarlyDeath(failed = true, forced = false, uptimeMs = 0))
        assertTrue(
            UserVulkanCrashGuard.isEarlyDeath(failed = true, forced = false, uptimeMs = threshold - 1),
        )
        assertFalse(
            UserVulkanCrashGuard.isEarlyDeath(failed = true, forced = false, uptimeMs = threshold),
        )
        // A clean exit at any uptime and a forced stop are never crashes.
        assertFalse(UserVulkanCrashGuard.isEarlyDeath(failed = false, forced = false, uptimeMs = 1))
        assertFalse(UserVulkanCrashGuard.isEarlyDeath(failed = true, forced = true, uptimeMs = 1))
    }

    @Test
    fun forcedStopInsideTheWindowIsStreakNeutral() {
        // hang-then-kill must not wipe an ongoing crash streak (plan §9:
        // only a clean exit or surviving past the window resets it).
        val afterCrash = UserVulkanCrashGuard.onSessionOutcome(driver(), earlyDeath = true)
        val neutral = UserVulkanCrashGuard.onSessionOutcome(
            afterCrash, failed = true, forced = true, uptimeMs = 5_000,
        )
        assertEquals(afterCrash, neutral)
        val afterSecondCrash = UserVulkanCrashGuard.onSessionOutcome(
            neutral, failed = true, forced = false, uptimeMs = 5_000,
        )
        assertTrue(afterSecondCrash.quarantined)
        assertEquals("quarantined after 2 early crashes", afterSecondCrash.quarantineReason)
        // A forced stop past the window behaves like a normal non-early end
        // (the session survived the early window): streak resets.
        val survived = UserVulkanCrashGuard.onSessionOutcome(
            afterCrash, failed = true, forced = true, uptimeMs = 60_000,
        )
        assertEquals(0, survived.earlyCrashStreak)
    }

    @Test
    fun quarantinedDriversAreUnselectableThroughTheLaunchSeam() {
        val root = temp.newFolder("drivers")
        val registry = UserVulkanDriverRegistry(root)
        val dir = File(root, "turnip-26-3")
        dir.mkdirs()
        File(dir, UserVulkanDriver.LIBRARY_FILE_NAME).writeBytes(ByteArray(64))
        File(dir, UserVulkanDriver.ICD_FILE_NAME).writeText(
            """{"ICD":{"library_path":"driver.so"}}""",
        )
        File(root, "registry.json").writeText(
            org.json.JSONObject()
                .put("schema", 1)
                .put(
                    "drivers",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("id", "user-turnip-26-3")
                            .put("label", "Turnip 26.3")
                            .put("libraryFileName", "driver.so")
                            .put("sha256", "a".repeat(64))
                            .put("addedAt", 1L)
                            .put("earlyCrashStreak", 2)
                            .put("quarantined", true)
                            .put("quarantineReason", UserVulkanCrashGuard.QUARANTINE_REASON),
                    ),
                )
                .toString(),
        )
        // The quarantine survives the registry round-trip and the seam
        // explains it exactly.
        val stored = registry.find("user-turnip-26-3")!!
        val guardOutcome = UserVulkanCrashGuard.onSessionOutcome(stored, earlyDeath = false)
        assertEquals(stored, guardOutcome)
        val failure = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-turnip-26-3", registry, allowUserDrivers = true,
            )
        }.exceptionOrNull()
        assertEquals(
            "Imported driver Turnip 26.3 is quarantined: quarantined after 2 early crashes",
            failure?.message,
        )
    }

    @Test
    fun sessionRecordCarriesEnvNamesNotPathsAndSurvivesRoundTrip() {
        val record = UserVulkanCrashGuard.sessionRecordJson(
            driver(streak = 1),
            renderer = "dxvk",
            vulkanEnvNames = listOf("VK_ICD_FILENAMES", "VK_DRIVER_FILES", "TU_DEBUG"),
            uptimeMs = 4_321,
            earlyDeath = true,
        )
        val json = JSONObject(record)
        assertEquals("user-turnip-26-3", json.getString("driverId"))
        assertEquals("a".repeat(64), json.getString("driverSha256"))
        assertEquals("1.3.290", json.getString("driverVulkanApiVersion"))
        assertEquals(
            listOf("VK_ICD_FILENAMES", "VK_DRIVER_FILES", "TU_DEBUG"),
            json.getJSONArray("vulkanEnv").let { array ->
                (0 until array.length()).map { array.getString(it) }
            },
        )
        assertEquals(4321, json.getLong("uptimeMs"))
        assertTrue(json.getBoolean("earlyDeath"))
        assertEquals(1, json.getInt("earlyCrashStreak"))
        assertFalse(json.getBoolean("quarantined"))
        // The support-bundle verifier rejects absolute paths; the record must
        // never contain them.
        assertFalse(record.contains("/data/"))
        assertFalse(record.contains('\\'.toString()))
        assertNull(json.optJSONObject("quarantineReason"))
    }
}
