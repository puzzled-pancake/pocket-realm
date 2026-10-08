package com.pocketrealm.storage

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientDisplaySelection
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.client.TimeframeStages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Design-timeframe §4.1: the persisted content-patch stage (the World
 * timeframe knob) rides the `Settings.update()` write-set under the reviewed
 * `timeframe_stage` key. The write persists through the shared
 * [TimeframeStages.resolveStored], so an impossible value can never be
 * re-persisted, and the default stays "" (= the qualified flavor's final
 * patch — existing worlds unaffected).
 */
class TimeframeStageSettingsTest {

    private val display = ClientDisplaySelection.nominal(
        ClientDisplayProfile.BALANCED,
        ClientFrameCap.FPS_30,
    )

    @Test
    fun defaultSnapshotPersistsTheEmptyDefault() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
        assertEquals("", prefs[stringPreferencesKey("timeframe_stage")])
    }

    @Test
    fun aQualifiedLaneStagePersistsVerbatim() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(timeframeStage = "vanilla-1.5"),
            display,
        )
        assertEquals("vanilla-1.5", prefs[stringPreferencesKey("timeframe_stage")])
    }

    @Test
    fun theFinalPatchPersistsVerbatimAndResolvesIdenticallyToTheEmptyDefault() {
        // Storage pins VERBATIM persistence: the write-set passes the id
        // through [TimeframeStages.resolveStored], which keeps a REGISTERED
        // final id as-is. The "" normalization is the UI click's decision
        // (SettingsScreen stores "" when the final patch is selected) — this
        // layer must not special-case it. What makes the two encodings
        // interchangeable is downstream resolution: "" and the explicit
        // final id resolve to the SAME stage id and the SAME conf value.
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(timeframeStage = "vanilla-1.12.1"),
            display,
        )
        assertEquals("vanilla-1.12.1", prefs[stringPreferencesKey("timeframe_stage")])
        // "" stays the default MARKER at the resolve layer while the
        // explicit final id survives verbatim — and both name the SAME
        // concrete conf stage, which is what makes the encodings
        // interchangeable downstream.
        assertEquals("", TimeframeStages.resolveStored(""))
        assertEquals(
            "vanilla-1.12.1",
            TimeframeStages.resolveStored("vanilla-1.12.1"),
        )
        assertEquals(
            "the empty default and the explicit final id name the same conf stage",
            TimeframeStages.confValue(""),
            TimeframeStages.confValue("vanilla-1.12.1"),
        )
    }

    @Test
    fun anImpossibleStageNeverReachesThePersistedEncoding() {
        // Rejection at the write boundary: a stale stage of an unqualified
        // flavor and a never-registered id both degrade to "" — the reader
        // resolves "" to the final patch, so no lane can be silently pinned.
        listOf(
            "tbc-2.4.3", // registered flavor, NOT qualified today
            "vanilla-9.9", // never a stage
            "garbage",
        ).forEach { impossible ->
            val prefs = mutablePreferencesOf()
            prefs.writeSnapshotWrites(Settings.Snapshot(timeframeStage = impossible), display)
            assertEquals(
                "impossible stored stage \"$impossible\" must degrade to \"\"",
                "",
                prefs[stringPreferencesKey("timeframe_stage")],
            )
        }
    }
}
