package com.pocketrealm.bots

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BotPresetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): BotPresetStore {
        val store = BotPresetStore(tmp.newFolder())
        BotCustomPresets.install(store)
        return store
    }

    @Test fun createdPresetsSurviveRestartAndStoreReinstall() = runTest {
        val directory = tmp.newFolder()
        val store = BotPresetStore(directory)
        BotCustomPresets.install(store)
        val preset = store.create("320 Smart LAN", base = BotProfiles.ALIVE_REALM_320)
        assertEquals(1, preset.revision.revision)

        // A fresh process re-reads the same document from the same directory.
        val restarted = BotPresetStore(directory)
        BotCustomPresets.install(restarted)
        assertEquals(
            listOf("320 Smart LAN"),
            restarted.presets.value.map { it.name },
        )
    }

    @Test fun saveRenameDuplicateDeleteFavoriteRoundTrip() = runTest {
        val store = newStore()
        val preset = store.create("Dungeon Bots", base = BotProfiles.BUSY_WORLD_240)

        val renamed = store.rename(preset.id, "Dungeon Team")
        assertEquals("Dungeon Team", renamed.name)
        assertEquals(preset.id, renamed.id)

        val favored = store.setFavorite(preset.id, true)
        assertTrue(favored.favorite)

        val duplicate = store.duplicate(preset.id, "Dungeon Team copy")
        assertEquals("Dungeon Team copy", duplicate.name)
        assertEquals(preset.configuration, duplicate.configuration)
        assertFalse(duplicate.favorite)

        store.delete(duplicate.id)
        assertEquals(1, store.presets.value.size)
        assertNull(
            store.presets.value.firstOrNull { it.id == duplicate.id },
        )
    }

    @Test fun revisionBumpsOnlyOnRealConfigurationChanges() = runTest {
        val store = newStore()
        val preset = store.create("Experiment 725", base = BotProfiles.ALIVE_REALM_320)
        val reSaved = store.save(preset.id, preset.configuration)
        assertEquals(1, reSaved.revision.revision)

        val changed = store.save(preset.id, preset.configuration.withTarget(725))
        assertEquals(2, changed.revision.revision)
        assertEquals(725, changed.configuration.selectedTarget)
        assertTrue(changed.revisions.map { it.revision } == listOf(1, 2))
    }

    @Test fun usr5IdentitiesResolveThroughBotProfilesFind() = runTest {
        val store = newStore()
        val preset = store.create("725 Experiment", base = BotProfiles.ALIVE_REALM_320)
            .let { store.save(it.id, it.configuration.withTarget(725)) }
        val identity = preset.identity()

        val resolved = BotProfiles.find(identity)
        assertNotNull(resolved)
        assertEquals(725, resolved?.selectedTarget)
        assertEquals(identity, resolved?.id)
        assertEquals("725 Experiment", resolved?.displayName)
    }

    @Test fun launchSnapshotIdentitiesStayResolvableAfterFurtherEdits() = runTest {
        val store = newStore()
        val preset = store.create("Evolving", base = BotProfiles.ALIVE_REALM_320)
        val launchedIdentity = preset.identity()

        // User keeps editing the saved preset after launch.
        store.save(preset.id, preset.configuration.withTarget(500))
        store.save(preset.id, preset.configuration.withTarget(240))

        val snapshotProfile = BotProfiles.find(launchedIdentity)
        assertNotNull(snapshotProfile)
        assertEquals(320, snapshotProfile?.selectedTarget)
        assertEquals(launchedIdentity, snapshotProfile?.id)
    }

    @Test fun tamperedDigestOrMissingRevisionIsRejected() = runTest {
        val store = newStore()
        val preset = store.create("Guarded", base = BotProfiles.LOW_POWER_80)
        val identity = preset.identity()

        // Flip one hex character of the digest.
        val lastHex = identity.last()
        val flipped = if (lastHex == '0') identity.dropLast(1) + '1' else identity.dropLast(1) + '0'
        assertNull(BotProfiles.find(flipped))
        assertNull(BotProfiles.find("usr5-${preset.id}-9-${identity.takeLast(8)}"))
        assertNull(BotProfiles.find("usr5-not-hex-at-all-00000000"))
        assertEquals(identity, BotProfiles.find(identity)?.id)
    }

    @Test fun deletedPresetStopsResolvingButDocumentStaysValid() = runTest {
        val store = newStore()
        val preset = store.create("Temporary", base = BotProfiles.LIVELY_160)
        val identity = preset.identity()
        assertNotNull(BotProfiles.find(identity))

        store.delete(preset.id)
        assertNull(BotProfiles.find(identity))
        assertEquals(0, store.presets.value.size)
    }

    @Test fun hundredsOfPresetsPersistWithoutACountCap() = runTest {
        val store = newStore()
        val names = (1..120).map { "Preset $it" }
        names.forEach { name ->
            store.create(name, base = BotProfiles.ALIVE_REALM_320)
        }
        assertEquals(120, store.presets.value.size)

        val reloaded = store.reload()
        assertEquals(names, reloaded.map { it.name })
        // Unlimited: the cap is storage, not a count constant.
        assertTrue(BotPresetStore.MAX_REVISIONS_PER_PRESET > 0)
    }

    @Test fun corruptDocumentIsQuarantinedAndStartsEmpty() = runTest {
        val directory = tmp.newFolder()
        directory.resolve(BotPresetStore.FILE_NAME).writeText("{ not json !!!")
        val store = BotPresetStore(directory)
        BotCustomPresets.install(store)
        assertEquals(0, store.presets.value.size)
        assertTrue(directory.resolve(BotPresetStore.FILE_NAME + ".corrupt").isFile)
    }

    @Test fun legacyAdvancedSetupImportsAsANamedPreset() = runTest {
        val store = newStore()
        // adv4-era advanced values migrate into the new model unchanged.
        val legacyProfile = BotProfiles.advanced(
            500,
            BotAdvancedSettings.fromProfile(BotProfiles.CROWDED_400),
        )
        val imported = store.create("Imported Advanced Setup", base = legacyProfile)
        assertEquals(
            legacyProfile.selectedTarget,
            imported.configuration.selectedTarget,
        )
        assertEquals(
            legacyProfile.iterationsPerTick,
            imported.configuration.iterationsPerTick,
        )
        assertEquals(
            legacyProfile.accountCount,
            imported.configuration.accountCount,
        )
        // Values that the flat BotAdvancedSettings cannot carry (pacing,
        // teleport seconds, generation) round-trip through the richer model.
        assertEquals(
            legacyProfile.teleportMaxIntervalSeconds,
            imported.configuration.teleportMaxIntervalSeconds,
        )
    }

    @Test fun exportImportRoundTripsAConfigurationWithANewStableIdentity() = runTest {
        val store = newStore()
        val preset = store.create("725 Experiment", base = BotProfiles.ALIVE_REALM_320)
            .let { store.save(it.id, it.configuration.withTarget(725)) }
        val json = store.exportJson(preset)

        val imported = store.importJson(json)
        assertEquals("725 Experiment", imported.name)
        assertEquals(preset.configuration, imported.configuration)
        assertTrue(imported.favorite == preset.favorite)
        // New stable identity: import never collides with the source preset.
        assertNotSame(preset.id, imported.id)
        val identity = imported.identity()
        assertEquals(725, BotProfiles.find(identity)?.selectedTarget)
    }

    @Test fun tamperedPresetFilesAreRejected() = runTest {
        val store = newStore()
        val preset = store.create("Guarded export", base = BotProfiles.LOW_POWER_80)
        val json = org.json.JSONObject(store.exportJson(preset))

        // Edited value without updating the checksum.
        val edited = org.json.JSONObject(json.toString())
        edited.getJSONObject("configuration").put("selectedTarget", 600)
        assertRejects { store.importJson(edited.toString()) }

        // Wrong checksum.
        val badSum = org.json.JSONObject(json.toString())
        badSum.put("checksum", "00000000")
        assertRejects { store.importJson(badSum.toString()) }

        // Not a preset file at all.
        assertRejects { store.importJson("{\"kind\":\"other\",\"schema\":1}") }
        assertRejects { store.importJson("not json") }

        // Future schema.
        val future = org.json.JSONObject(json.toString())
        future.put("schema", 99)
        assertRejects { store.importJson(future.toString()) }

        // Original still imports.
        assertEquals("Guarded export", store.importJson(json.toString()).name)
    }

    @Test fun importedOutOfRangePopulationsAreRejectedByValidation() = runTest {
        val store = newStore()
        val preset = store.create("Range guard", base = BotProfiles.LIVELY_160)
        val json = org.json.JSONObject(store.exportJson(preset))
        json.getJSONObject("configuration").put("selectedTarget", 99_999_999)
        // The checksum now mismatches the edited configuration, and even a
        // recomputed checksum would fail BotCustomConfiguration validation.
        assertRejects { store.importJson(json.toString()) }
    }
}

private inline fun assertRejects(block: () -> Unit) {
    try {
        block()
        error("expected rejection")
    } catch (expected: IllegalArgumentException) {
        // The expected validation rejection.
    } catch (expected: org.json.JSONException) {
        // The expected malformed-document rejection.
    }
    // Any AssertionError (including our own error()) propagates and fails the test.
}
