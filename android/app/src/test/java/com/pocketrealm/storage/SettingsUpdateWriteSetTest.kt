package com.pocketrealm.storage

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientDisplaySelection
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.llm.ComputeMode
import com.pocketrealm.llm.LlmModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trip guard for the `Settings.update()` write-set hazard: `update()`
 * rewrites EVERY persisted key from the transformed snapshot, so a snapshot
 * field that falls out of the write-set — or a written encoding the reader
 * cannot restore — silently loses user data on the next unrelated settings
 * write. The write half is the exact internal entry `update()` calls after
 * the context-bound display normalization; the llm* pair is read back through
 * the production reader, and the journal keys are asserted to stay outside
 * the write-set (the mutateGameSettings counter contract).
 */
class SettingsUpdateWriteSetTest {

    private val display = ClientDisplaySelection.nominal(
        ClientDisplayProfile.BALANCED,
        ClientFrameCap.FPS_30,
    )

    @Test
    fun llmSettingsRoundTripThroughTheWriteSetAndNormalize() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(
                llmEnabled = true,
                llmComputeMode = ComputeMode.NPU,
                llmCoresMask = 0L,
                llmThreads = 64,
                llmOffloadLayers = 10_000,
                llmExternalMode = true,
                llmExternalUrl = "  https://api.openai.com  ",
                llmExternalApiKey = " sk-test ",
                llmExternalModel = " gpt-4o-mini ",
                llmBanter = false,
                llmAmbience = true,
                llmModelId = "gemma4-E2B-TUNED-q4_0",
                llmAdvanced = true,
                llmMaxNewTokens = 9_000,
                llmGenerationTimeout = 7,
                llmPromptPackJson = "  {\"custom\": true}  ",
            ),
            display,
        )
        // The production reader must restore exactly the normalized intent.
        assertEquals(
            LlmSnapshotFields(
                enabled = true,
                computeMode = ComputeMode.NPU,
                coresMask = 0x38L,
                threads = 8,
                offloadLayers = 128,
                externalMode = true,
                // external strings persist VERBATIM (no trim in the write
                // set - a trim would fight the text field mid-typing);
                // normalization happens at conf-emission time
                externalUrl = "  https://api.openai.com  ",
                externalApiKey = " sk-test ",
                externalModel = " gpt-4o-mini ",
                banter = false,
                ambience = true,
                modelId = "gemma4-E2B-TUNED-q4_0",
                advanced = true,
                // override clamps: 9000 -> ceiling 600, 7 -> floor 15
                maxNewTokens = 600,
                generationTimeout = 15,
                // pack JSON persists verbatim (mid-typing rule); resolution
                // happens at read/stage time via LlmPromptPack.resolve
                promptPackJson = "  {\"custom\": true}  ",
            ),
            prefs.readLlmSnapshotFields(),
        )
        // ...under the reviewed persisted names and raw encodings.
        assertEquals(1, prefs[intPreferencesKey("llm_enabled")])
        assertEquals("NPU", prefs[stringPreferencesKey("llm_compute_mode")])
        assertEquals(0x38L, prefs[longPreferencesKey("llm_cores_mask")])
        assertEquals(8, prefs[intPreferencesKey("llm_threads")])
        assertEquals(128, prefs[intPreferencesKey("llm_offload_layers")])
        assertEquals(1, prefs[intPreferencesKey("llm_external_mode")])
        assertEquals("  https://api.openai.com  ", prefs[stringPreferencesKey("llm_external_url")])
        assertEquals(" sk-test ", prefs[stringPreferencesKey("llm_external_api_key")])
        assertEquals(" gpt-4o-mini ", prefs[stringPreferencesKey("llm_external_model")])
        assertEquals(0, prefs[intPreferencesKey("llm_cloud_chatter")])
        assertEquals(0, prefs[intPreferencesKey("llm_banter")])
        assertEquals(1, prefs[intPreferencesKey("llm_ambience")])
        assertEquals(
            "gemma4-E2B-TUNED-q4_0",
            prefs[stringPreferencesKey("llm_model_id")],
        )
    }

    @Test
    fun llmAdvancedOverrideEncodingsClampAndPersistUnderReviewedNames() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(llmAdvanced = true, llmMaxNewTokens = 9_000, llmGenerationTimeout = 7),
            display,
        )
        assertEquals(1, prefs[intPreferencesKey("llm_advanced")])
        // 9000 clamps to the reply-length ceiling, 7 to the timeout floor
        assertEquals(600, prefs[intPreferencesKey("llm_max_new_tokens")])
        assertEquals(15, prefs[intPreferencesKey("llm_generation_timeout")])
        // a zero override is the "follow the model profile" sentinel and persists as 0
        val defaults = mutablePreferencesOf()
        defaults.writeSnapshotWrites(Settings.Snapshot(llmAdvanced = true), display)
        assertEquals(0, defaults[intPreferencesKey("llm_max_new_tokens")])
        assertEquals(0, defaults[intPreferencesKey("llm_generation_timeout")])
    }

    @Test
    fun defaultSnapshotPersistsTheReviewedLlmOffContract() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
        val llm = prefs.readLlmSnapshotFields()
        assertFalse(llm.enabled)
        assertEquals(ComputeMode.AUTO, llm.computeMode)
        assertEquals(0x38L, llm.coresMask)
        assertEquals(3, llm.threads)
        assertEquals(99, llm.offloadLayers)
        assertFalse(llm.externalMode)
        assertEquals("", llm.externalUrl)
        assertEquals("", llm.externalApiKey)
        assertEquals("", llm.externalModel)
        // banter is free authored content: default ON
        assertTrue(llm.banter)
        // ambience costs generation: default OFF (the silence doctrine)
        assertFalse(llm.ambience)
        assertEquals(LlmModelRegistry.DEFAULT_MODEL_ID, llm.modelId)
        // advanced disclosure OFF; overrides follow the model/tier profiles
        assertFalse(llm.advanced)
        assertEquals(0, llm.maxNewTokens)
        assertEquals(0, llm.generationTimeout)
        // no custom pack: empty resolves to the default at stage time
        assertEquals("", llm.promptPackJson)
        assertEquals("", prefs[stringPreferencesKey("llm_prompt_pack")])
        assertEquals(0, prefs[intPreferencesKey("llm_enabled")])
    }

    @Test
    fun worldDebugLogsEncodesUnderTheReviewedZeroOneToggleNames() {
        // B2: verbose world logging is an advanced opt-in; default OFF keeps
        // the world.conf LogFileLevel at errors-only (1). The conf is
        // written at world start, so the toggle applies on the next realm
        // start by construction - nothing else may key off this field.
        val off = mutablePreferencesOf()
        off.writeSnapshotWrites(Settings.Snapshot(), display)
        assertEquals(0, off[intPreferencesKey("world_debug_logs")])
        val on = mutablePreferencesOf()
        on.writeSnapshotWrites(Settings.Snapshot(worldDebugLogs = true), display)
        assertEquals(1, on[intPreferencesKey("world_debug_logs")])
    }

    @Test
    fun corruptPackJsonFailsOpenToDefaultAtResolveTime() {
        // the write-set never validates pack JSON (mid-typing rule); the
        // reader resolves: corrupt/empty input restores the default pack
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(llmPromptPackJson = "{corrupt"),
            display,
        )
        assertEquals("{corrupt", prefs[stringPreferencesKey("llm_prompt_pack")])
        val resolved = com.pocketrealm.llm.LlmPromptPack.resolve(
            prefs.readLlmSnapshotFields().promptPackJson,
        )
        assertEquals(
            com.pocketrealm.llm.LlmPromptPack().blocks.map { it.id },
            resolved.blocks.map { it.id },
        )
    }

    @Test
    fun writeSetNeverTouchesTheJournalOrUnrelatedKeysAndScrubsRetiredOnes() {
        val revision = longPreferencesKey("game_settings_revision")
        val directEdits = stringPreferencesKey("game_settings_direct_edit_revisions")
        val unrelated = stringPreferencesKey("account_name")
        val retiredFps = stringPreferencesKey("fps_profile")
        val retiredProvider = stringPreferencesKey("provider")
        val retiredFex = stringPreferencesKey("dxvk_fex_package")
        val retiredNotice = intPreferencesKey("arm_vulkan_driver_migration_notice")
        val prefs = mutablePreferencesOf(
            revision to 41L,
            directEdits to """{"MasterSoundEnable":40}""",
            unrelated to "kept-account",
            retiredFps to "40",
            retiredProvider to "FEX",
            retiredFex to "legacy-fex",
            retiredNotice to 1,
        )
        prefs.writeSnapshotWrites(Settings.Snapshot(llmEnabled = true), display)
        // The queue journal lives outside update()'s write-set by contract:
        // no settings rewrite may regress the revision counter or the
        // direct-edit journal (see mutateGameSettings / its KDoc).
        assertEquals(41L, prefs[revision])
        assertEquals("""{"MasterSoundEnable":40}""", prefs[directEdits])
        assertEquals("kept-account", prefs[unrelated])
        // Retired keys are scrubbed by every write, not resurrected.
        assertNull(prefs[retiredFps])
        assertNull(prefs[retiredProvider])
        assertNull(prefs[retiredFex])
        assertNull(prefs[retiredNotice])
    }

    @Test
    fun clientUiScaleRoundTripsThroughTheWriteSet() {
        val uiScale = floatPreferencesKey("client_ui_scale")
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(clientUiScale = 1.25f), display)
        assertEquals(1.25f, prefs[uiScale])
        // Unmanaged (null) removes the key rather than writing it — the same
        // contract as bot_saved_preset_id, which keeps the default write-set
        // assertion in this class truthful.
        val seeded = mutablePreferencesOf(uiScale to 1.25f)
        seeded.writeSnapshotWrites(Settings.Snapshot(), display)
        assertNull(seeded[uiScale])
    }

    @Test
    fun clientUiScaleBoundsAndPerProfileClamp() {
        // Fail-fast at the update() boundary (displaySelection is what
        // update() calls before writing).
        try {
            Settings.Snapshot(clientUiScale = 2.5f).displaySelection()
            fail("2.5f must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            Settings.Snapshot(clientUiScale = 0.25f).displaySelection()
            fail("0.25f must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        // Launch-effective values: per-profile clamp keeps stock ~512-unit
        // frames on-screen; unmanaged stays null (never clamped).
        assertNull(Settings.Snapshot().effectiveClientUiScale(720))
        assertEquals(1.40625f, Settings.Snapshot(clientUiScale = 2f).effectiveClientUiScale(720))
        assertEquals(1.875f, Settings.Snapshot(clientUiScale = 2f).effectiveClientUiScale(960))
        assertEquals(2f, Settings.Snapshot(clientUiScale = 2f).effectiveClientUiScale(1080))
        assertEquals(0.85f, Settings.Snapshot(clientUiScale = 0.85f).effectiveClientUiScale(720))
    }

    @Test
    fun writeSetCoversExactlyTheReviewedPersistedKeys() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(llmEnabled = true), display)
        // bot_saved_preset_id is deliberately absent: the default snapshot has
        // no saved preset, and null removes the key rather than writing null.
        assertEquals(
            setOf(
                "client_display_profile", "client_frame_cap", "client_display_schema",
                "dxvk_box64_package",
                "arm_vulkan_driver_package", "allow_user_vulkan_drivers",
                "arm_vulkan_driver_selection_schema",
                "renderer", "arm_renderer_selection_schema",
                "bot_population_target", "bot_profile_id", "bot_presets_imported",
                "bot_advanced_enabled",
                "bot_nearby_limit", "bot_nearby_radius", "bot_login_batch",
                "bot_maintenance_batch", "bot_update_interval_ms",
                "bot_teleport_min_minutes", "bot_teleport_max_minutes",
                "bot_iterations_per_tick", "bot_world_p99_ms", "bot_sync_level",
                "bot_limit_combat", "bot_active_percent", "bot_auto_quest",
                "bot_allow_chat", "bot_allow_invites", "bot_group_nearby",
                "bot_wander", "bot_off_spec",
                "setup_complete", "last_active_generation", "input_safe_mode",
                "auto_login_on_launch", "auto_login_advanced",
                "al_poll_interval_ms", "al_stable_polls", "al_login_ui_settle_ms",
                "al_session_timeout_ms", "al_drain_poll_ms",
                "al_input_drain_timeout_ms", "al_ime_key_dwell_ms",
                "al_ime_key_gap_ms", "al_field_settle_ms", "al_pointer_dwell_ms",
                "client_tweaks", "client_tweaks_schema",
                "game_settings_queue", "game_settings_queue_schema",
                "audio_mode", "nearby_interact_trigger_guard_ms",
                "world_debug_logs",
                "runtime_mode", "allow_lan_players",
                "llm_enabled", "llm_compute_mode", "llm_cores_mask",
                "llm_threads", "llm_offload_layers",
                "llm_external_mode", "llm_external_url", "llm_external_api_key",
                "llm_external_model", "llm_cloud_chatter", "llm_banter", "llm_ambience", "llm_model_id",
                "llm_advanced", "llm_max_new_tokens", "llm_generation_timeout",
                "llm_prompt_pack",
            ),
            prefs.asMap().keys.map { it.name }.toSet(),
        )
        // Schema stamps and opt-in defaults that must ride along every write.
        assertEquals(1, prefs[intPreferencesKey("client_display_schema")])
        assertEquals(2, prefs[intPreferencesKey("client_tweaks_schema")])
        assertEquals(1, prefs[intPreferencesKey("game_settings_queue_schema")])
        assertEquals(1, prefs[intPreferencesKey("auto_login_on_launch")])
        assertEquals("ON", prefs[stringPreferencesKey("audio_mode")])
        assertEquals("LOCAL", prefs[stringPreferencesKey("runtime_mode")])
        assertEquals(0, prefs[intPreferencesKey("allow_lan_players")])
    }
}
