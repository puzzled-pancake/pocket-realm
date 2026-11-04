package com.pocketrealm.storage

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientDisplaySelection
import com.pocketrealm.client.ClientFrameCap
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
 * the context-bound display normalization, and the journal keys are
 * asserted to stay outside the write-set (the mutateGameSettings counter
 * contract).
 */
class SettingsUpdateWriteSetTest {

    private val display = ClientDisplaySelection.nominal(
        ClientDisplayProfile.BALANCED,
        ClientFrameCap.FPS_30,
    )

    @Test
    fun retiredBotLlmKeysAreScrubbedByEveryWrite() {
        // The bot-LLM settings are retired: an upgraded install drops every
        // stored llm_* key on the next settings write - the endpoint API
        // key included - instead of carrying it forever.
        val apiKey = stringPreferencesKey("llm_external_api_key")
        val enabled = intPreferencesKey("llm_enabled")
        val cores = longPreferencesKey("llm_cores_mask")
        val prefs = mutablePreferencesOf(apiKey to "sk-old", enabled to 1, cores to 0x38L)
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
        assertNull(prefs[apiKey])
        assertNull(prefs[enabled])
        assertNull(prefs[cores])
        assertTrue(prefs.asMap().keys.none { it.name.startsWith("llm_") })
    }

    @Test
    fun worldDebugLogsEncodesUnderTheReviewedZeroOneToggleNames() {
        // verbose world logging is an advanced opt-in; default OFF keeps
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
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
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
    fun flavorSelectionRoundTripsVerbatimUnderTheReviewedKeyName() {
        // The Home switcher's pick must persist VERBATIM ("" = no pick): the
        // :supervisor realmlist projection resolves it through the fail-loud
        // ActiveFlavor machine at use, so a stale value refuses the start
        // instead of being normalized into a silent lane downgrade here.
        val key = stringPreferencesKey("selected_flavor_id")
        val picked = mutablePreferencesOf()
        picked.writeSnapshotWrites(Settings.Snapshot(selectedFlavorId = "vanilla"), display)
        assertEquals("vanilla", picked[key])
        assertEquals("vanilla", Settings.Snapshot(selectedFlavorId = "vanilla").selectedFlavorOrNull())
        // No pick is the empty string (resolution falls to the machine's
        // sole-qualified default), and a stale pick stays un-normalized.
        val none = mutablePreferencesOf()
        none.writeSnapshotWrites(Settings.Snapshot(), display)
        assertEquals("", none[key])
        assertNull(Settings.Snapshot().selectedFlavorOrNull())
        val stale = mutablePreferencesOf()
        stale.writeSnapshotWrites(Settings.Snapshot(selectedFlavorId = "tbc"), display)
        assertEquals("tbc", stale[key])
    }

    @Test
    fun publicListingSwitchNameAndIdentitiesRoundTripAndEmptyIdentitiesLeaveNoKey() {
        val nostrKey = stringPreferencesKey("nostr_private_key_hex")
        val irohKey = stringPreferencesKey("iroh_gateway_secret_hex")
        val secret = "0f".repeat(32)
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(
            Settings.Snapshot(
                onlineHosting = true,
                gamePassword = "sesame",
                publicRealmName = "Night Harbor",
                nostrPrivateKeyHex = secret,
                irohGatewaySecretHex = secret,
            ),
            display,
        )
        assertEquals(1, prefs[intPreferencesKey("online_hosting")])
        assertEquals(1, prefs[intPreferencesKey("list_in_browser")])
        assertEquals("sesame", prefs[stringPreferencesKey("game_password")])
        assertEquals("Night Harbor", prefs[stringPreferencesKey("public_realm_name")])
        assertEquals(secret, prefs[nostrKey])
        assertEquals(secret, prefs[irohKey])
        // The identities persist across every later write that carries them,
        // and an unset identity removes the key instead of writing "" — the
        // cleared password and the retired legacy switch key behave the same.
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
        assertNull(prefs[nostrKey])
        assertNull(prefs[irohKey])
        assertNull(prefs[stringPreferencesKey("game_password")])
        assertEquals(0, prefs[intPreferencesKey("online_hosting")])
    }

    @Test
    fun theOldPublishSwitchMigratesToOnlineHostingAndListing() {
        // The old publish switch meant BOTH online hosting and listing;
        // the new keys split the two, and the legacy key feeds the read.
        assertTrue(migratedOnlineHosting(newKey = 1, legacyPublishKey = null))
        assertTrue(migratedOnlineHosting(newKey = null, legacyPublishKey = 1))
        assertTrue(migratedOnlineHosting(newKey = 0, legacyPublishKey = 1))
        assertFalse(migratedOnlineHosting(newKey = 0, legacyPublishKey = 0))
        assertFalse(migratedOnlineHosting(newKey = null, legacyPublishKey = null))
    }

    @Test
    fun signupSwitchAndCapRoundTripWithTheCapBounded() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(signupOpen = true, signupCap = 25), display)
        assertEquals(1, prefs[intPreferencesKey("signup_open")])
        assertEquals(25, prefs[intPreferencesKey("signup_cap")])
        prefs.writeSnapshotWrites(Settings.Snapshot(signupCap = 1_000_000), display)
        assertEquals(0, prefs[intPreferencesKey("signup_open")])
        assertEquals(SIGNUP_CAP_MAX, prefs[intPreferencesKey("signup_cap")])
    }

    @Test
    fun writeSetCoversExactlyTheReviewedPersistedKeys() {
        val prefs = mutablePreferencesOf()
        prefs.writeSnapshotWrites(Settings.Snapshot(), display)
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
                "xp_rate_kill", "xp_rate_quest", "xp_rate_explore", "xp_rate_pet_kill",
                "world_debug_logs",
                "runtime_mode", "allow_lan_players", "friend_access_enabled",
                "tunnel_advertised_address",
                "timeframe_stage",
                "selected_flavor_id",
                "online_hosting", "list_in_browser", "public_realm_name", "public_realm_description",
                "signup_open", "signup_cap",
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
