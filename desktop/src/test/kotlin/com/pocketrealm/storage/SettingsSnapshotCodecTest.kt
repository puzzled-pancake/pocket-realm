package com.pocketrealm.storage

import com.pocketrealm.bots.BotProfiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DESK-133/134: the desktop Settings twin's shipped defaults and codec
 * contract — the AutoLoginTimings JVM twin clamps like the Android
 * original, the timing surface round-trips through the persisted JSON,
 * and the DESK-134 defaults alignment (default bot profile, auto-login
 * on) never reaches EXISTING settings files (fromJson fallbacks stay
 * byte-stable; defaults apply to fresh installs only).
 */
class SettingsSnapshotCodecTest {

    // ---- DESK-134: shipped defaults ------------------------------------

    @Test
    fun freshInstallsShipTheAndroidProductDefaults() {
        val snapshot = Settings.Snapshot()
        assertEquals(
            "a first boot has bots (the default profile, like Android)",
            BotProfiles.defaultProfile.id,
            snapshot.botProfileId,
        )
        assertTrue(
            "auto-login ships on (inert until an account is provisioned)",
            snapshot.autoLoginOnLaunch,
        )
        assertEquals(Settings.AutoLoginTimings(), snapshot.autoLoginTimings)
    }

    @Test
    fun existingFilesDecodeExactlyAsWritten() {
        val legacy = Settings.Snapshot(botProfileId = "", autoLoginOnLaunch = false)
        val decoded = Settings.Snapshot.fromJson(legacy.toJson())
        assertEquals("", decoded.botProfileId)
        assertFalse(decoded.autoLoginOnLaunch)
    }

    @Test
    fun absentKeysFallBackToTheLegacyValuesNeverTheNewDefaults() {
        val decoded = Settings.Snapshot.fromJson("""{"runtimeMode":"LOCAL"}""")
        assertEquals(
            "the fromJson fallback must stay the pre-DESK-134 value",
            "",
            decoded.botProfileId,
        )
        assertFalse(decoded.autoLoginOnLaunch)
    }

    // ---- DESK-133: the timing surface ----------------------------------

    @Test
    fun inBoundsTimingsRoundTripThroughTheSnapshotCodec() {
        val custom = Settings.AutoLoginTimings(
            pollIntervalMs = 500,
            requiredStablePolls = 6,
            loginUiSettleMs = 12_000,
            imeKeyDwellMs = 80,
        )
        val snapshot = Settings.Snapshot(autoLoginTimings = custom)
        assertEquals(custom, Settings.Snapshot.fromJson(snapshot.toJson()).autoLoginTimings)
    }

    @Test
    fun absentOrEmptyTimingBlocksDecodeToTheDefaults() {
        assertEquals(
            Settings.AutoLoginTimings(),
            Settings.Snapshot.fromJson("""{"botProfileId":""}""").autoLoginTimings,
        )
        assertEquals(
            Settings.AutoLoginTimings(),
            Settings.AutoLoginTimings.decodePersisted(null),
        )
    }

    @Test
    fun outOfBoundsPersistedTimingsNormalizeInsteadOfThrowing() {
        val requested = Settings.AutoLoginTimings(
            pollIntervalMs = 1,
            requiredStablePolls = 999,
            loginUiSettleMs = 1,
            sessionTimeoutMs = 1,
            drainPollMs = 1,
            inputDrainTimeoutMs = 1,
            imeKeyDwellMs = 1,
            imeKeyGapMs = 9_999,
            fieldSettleMs = 1,
            pointerDwellMs = 1,
        )
        val expected = requested.normalized()
        val raw = JSONObject()
            .put("autoLoginTimings", JSONObject(requested.toControlJson()))
            .toString()
        assertEquals(expected, Settings.Snapshot.fromJson(raw).autoLoginTimings)
        assertEquals(expected, Settings.Snapshot.fromJson(raw).effectiveAutoLoginTimings())
    }

    @Test
    fun clampsMirrorTheAndroidTwin() {
        val clamped = Settings.AutoLoginTimings(
            pollIntervalMs = 50,
            requiredStablePolls = 0,
            loginUiSettleMs = 500,
            sessionTimeoutMs = 1_000,
            drainPollMs = 1,
            inputDrainTimeoutMs = 1,
            imeKeyDwellMs = 1,
            imeKeyGapMs = -5,
            fieldSettleMs = 10_000,
            pointerDwellMs = 1,
        ).normalized()
        assertEquals(100L, clamped.pollIntervalMs)
        assertEquals(1, clamped.requiredStablePolls)
        assertEquals(1_000L, clamped.loginUiSettleMs)
        assertEquals(60_000L, clamped.sessionTimeoutMs)
        assertEquals(25L, clamped.drainPollMs)
        assertEquals(20L, clamped.imeKeyDwellMs)
        assertEquals(0L, clamped.imeKeyGapMs)
        assertEquals(2_000L, clamped.fieldSettleMs)
        assertEquals(20L, clamped.pointerDwellMs)
        // The input-drain floor lifts the drain timeout above its band
        // when the dwell/settle legs demand it.
        assertEquals(
            clamped.minimumInputDrainTimeoutMs(),
            clamped.inputDrainTimeoutMs,
        )
        assertTrue(clamped.inputDrainTimeoutMs >= 1_000L)
    }

    @Test
    fun minimumInputDrainFormulaMatchesTheTwin() {
        val t = Settings.AutoLoginTimings()
        assertEquals(
            3L * (t.pointerDwellMs + t.fieldSettleMs) +
                32L * (t.imeKeyDwellMs + t.imeKeyGapMs) +
                2L * t.drainPollMs,
            t.minimumInputDrainTimeoutMs(),
        )
    }

    /** DESK-84: the input safe-mode gate persists and defaults to off. */
    @Test
    fun inputSafeModeRoundTripsThroughTheSnapshotCodec() {
        val on = Settings.Snapshot(inputSafeMode = true)
        assertEquals(true, Settings.Snapshot.fromJson(on.toJson()).inputSafeMode)
        // The persisted key mirrors the Android preference spelling family.
        assertTrue(on.toJson().contains("inputSafeMode"))
        // Fresh installs and legacy files (no key) decode to off.
        assertEquals(false, Settings.Snapshot().inputSafeMode)
        assertEquals(false, Settings.Snapshot.fromJson("{}").inputSafeMode)
    }

    // ---- DESK-124: the launcher-level advanced override -----------------

    @Test
    fun advancedTuningDefaultsToOffAndTheDefaultProfileShape() {
        val snapshot = Settings.Snapshot()
        assertFalse("the advanced override ships off", snapshot.botAdvancedEnabled)
        assertEquals(
            "the advanced values default to the default profile's shape",
            com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
                com.pocketrealm.bots.BotProfiles.defaultProfile,
            ),
            snapshot.botAdvanced,
        )
    }

    @Test
    fun advancedTuningRoundTripsThroughTheSnapshotCodec() {
        val advanced = com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
            com.pocketrealm.bots.BotProfiles.defaultProfile,
        ).copy(
            nearbyBotLimit = 5,
            nearbyRadius = 150,
            loginBatchSize = 4,
            maintenanceBatchSize = 8,
            updateIntervalMs = 2_000,
            teleportMinMinutes = 30,
            teleportMaxMinutes = 60,
            iterationsPerTick = 5,
            admissionWorldP99Ms = 200,
            syncLevelWithPlayers = true,
            limitCombatActivity = false,
            activeBotPercent = 10,
            autoDoQuests = false,
            allowBotChat = true,
            allowPlayerInvites = true,
            groupNearby = false,
            wanderWhenIdle = false,
            enableOffSpecStrategies = false,
        )
        val snapshot = Settings.Snapshot(botAdvancedEnabled = true, botAdvanced = advanced)
        val decoded = Settings.Snapshot.fromJson(snapshot.toJson())
        assertTrue(decoded.botAdvancedEnabled)
        assertEquals(advanced, decoded.botAdvanced)
    }

    @Test
    fun existingFilesWithoutTheAdvancedKeysDecodeOffAndByteStable() {
        val decoded = Settings.Snapshot.fromJson("""{"runtimeMode":"LOCAL"}""")
        assertFalse("an existing file must not flip the override on", decoded.botAdvancedEnabled)
        assertEquals(
            "the absent block decodes to the default profile's shape",
            com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
                com.pocketrealm.bots.BotProfiles.defaultProfile,
            ),
            decoded.botAdvanced,
        )
    }

    @Test
    fun anInvalidAdvancedBlockDegradesInsteadOfResettingTheSnapshot() {
        val decoded = Settings.Snapshot.fromJson(
            """{"botAdvancedEnabled":true,"botAdvanced":{"nearbyBotLimit":999,"nearbyRadius":99999}}""",
        )
        assertTrue("the enabled flag still decodes", decoded.botAdvancedEnabled)
        assertEquals(
            "the invalid block degrades to the default profile's shape",
            com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
                com.pocketrealm.bots.BotProfiles.defaultProfile,
            ),
            decoded.botAdvanced,
        )
    }

    @Test
    fun controlJsonKeepsTheStrictAndroidContract() {
        val raw = Settings.AutoLoginTimings(pollIntervalMs = 300).toControlJson()
        assertEquals(300L, Settings.AutoLoginTimings.fromControlJson(raw).pollIntervalMs)
        try {
            Settings.AutoLoginTimings.fromControlJson(
                Settings.AutoLoginTimings(pollIntervalMs = 1).toControlJson(),
            )
            fail("out-of-bounds control values must be refused")
        } catch (failure: IllegalArgumentException) {
            assertTrue("outside supported bounds" in failure.message!!)
        }
        try {
            Settings.AutoLoginTimings.fromControlJson("""{"pollIntervalMs":250}""")
            fail("a partial key set must be refused")
        } catch (failure: IllegalArgumentException) {
            assertTrue("schema mismatch" in failure.message!!)
        }
    }

    // ---- DESK-5/47/78: the database provider lane -----------------------

    @Test
    fun databaseProviderShipsMariadbAndRoundTripsThroughTheCodec() {
        // The default-flip: MARIADB is the shipped default (the on-device
        // product's default, now the desktop's too) — a fresh install
        // resolves the engine lane, and an explicit SQLITE selection still
        // round-trips (the dev/compat provider stays selectable).
        assertEquals(Settings.DatabaseProvider.MARIADB, Settings.Snapshot().databaseProvider)
        val explicitSqlite = Settings.Snapshot(databaseProvider = Settings.DatabaseProvider.SQLITE)
        assertEquals(
            Settings.DatabaseProvider.SQLITE,
            Settings.Snapshot.fromJson(explicitSqlite.toJson()).databaseProvider,
        )
        assertTrue("the codec persists the provider key", "databaseProvider" in explicitSqlite.toJson())
    }

    @Test
    fun databaseProviderDegradesLenientlyOnAnUnknownValue() {
        // One unknown enum value must not throw and reset the whole
        // snapshot (the audioMode posture); it degrades to the shipped
        // MARIADB default, and so does the absent key (a store that never
        // chose a provider is a fresh init on MariaDB).
        val degraded = Settings.Snapshot.fromJson("""{"databaseProvider":"ORACLE"}""")
        assertEquals(Settings.DatabaseProvider.MARIADB, degraded.databaseProvider)
        assertEquals(Settings.DatabaseProvider.MARIADB, Settings.Snapshot.fromJson("{}").databaseProvider)
    }

    @Test
    fun anExistingFileKeepsItsExplicitSqliteSelection() {
        // The legacy-adoption-by-persisted-selection path: a pre-flip
        // settings file carries an EXPLICIT "SQLITE" (toJson always wrote
        // the key), and it must decode exactly as written — the default
        // flip applies to stores that never chose, never over an explicit
        // choice, so the populated SQLite datadir behind it keeps serving.
        val preFlipFile = Settings.Snapshot(databaseProvider = Settings.DatabaseProvider.SQLITE).toJson()
        assertEquals(
            Settings.DatabaseProvider.SQLITE,
            Settings.Snapshot.fromJson(preFlipFile).databaseProvider,
        )
        assertEquals(
            Settings.DatabaseProvider.SQLITE,
            Settings.Snapshot.fromJson("""{"databaseProvider":"SQLITE"}""").databaseProvider,
        )
    }

    // ---- design-timeframe §4.1: the persisted content-patch stage --------

    @Test
    fun timeframeStageShipsEmptyAndRoundTripsThroughTheCodec() {
        // "" IS the default (the qualified flavor's final patch): existing
        // worlds and fresh installs are unaffected by construction.
        assertEquals("", Settings.Snapshot().timeframeStage)
        assertEquals("", Settings.Snapshot.fromJson("{}").timeframeStage)
        val staged = Settings.Snapshot(timeframeStage = "vanilla-1.5")
        assertEquals("vanilla-1.5", Settings.Snapshot.fromJson(staged.toJson()).timeframeStage)
        // The final patch is a valid selection too.
        val final = Settings.Snapshot(timeframeStage = "vanilla-1.12.1")
        assertEquals(
            "vanilla-1.12.1",
            Settings.Snapshot.fromJson(final.toJson()).timeframeStage,
        )
    }

    @Test
    fun impossibleTimeframeStagesDegradeToTheDefaultNeverThrow() {
        // Rejection at the read layer (the degrade-per-field posture): a
        // stale stage of an unqualified flavor, a never-registered id, and a
        // case-swapped id all degrade to "" — a flavor change can never
        // strand a world silently.
        listOf(
            "tbc-2.4.3",
            "wotlk-3.3.5a",
            "vanilla-9.9",
            "VANILLA-1.5",
            "garbage",
        ).forEach { impossible ->
            val degraded = Settings.Snapshot.fromJson(
                JSONObject().put("timeframeStage", impossible).toString(),
            )
            assertEquals(
                "impossible stored stage \"$impossible\" must degrade to \"\"",
                "",
                degraded.timeframeStage,
            )
        }
        // The write path normalizes too: an impossible value constructed in
        // memory never re-persists.
        assertTrue(
            !Settings.Snapshot(timeframeStage = "tbc-2.4.3").toJson().contains("tbc-2.4.3"),
        )
    }
}
