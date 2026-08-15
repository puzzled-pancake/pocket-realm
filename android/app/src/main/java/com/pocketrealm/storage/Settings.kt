package com.pocketrealm.storage

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.createMultiProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.ClientDisplayCapabilities
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientDisplaySelection
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.supervisor.RuntimeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File

private val vulkanDriverPreference = stringPreferencesKey("arm_vulkan_driver_package")
private val vulkanSelectionSchemaPreference =
    intPreferencesKey("arm_vulkan_driver_selection_schema")
private val vulkanMigrationNoticePreference =
    intPreferencesKey("arm_vulkan_driver_migration_notice")
private val rendererPreference = stringPreferencesKey("renderer")
private val rendererSelectionSchemaPreference =
    intPreferencesKey("arm_renderer_selection_schema")

internal const val POCKET_SETTINGS_STORE_NAME = "pocket_settings"
internal const val POCKET_SETTINGS_FILE_NAME = "$POCKET_SETTINGS_STORE_NAME.preferences_pb"

internal fun pocketSettingsDataFile(context: Context): File =
    context.applicationContext.preferencesDataStoreFile(POCKET_SETTINGS_STORE_NAME)

internal fun vulkanSelectionMigration(deviceModel: String): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            resolve(currentData).migrated

        override suspend fun migrate(currentData: Preferences): Preferences {
            val resolved = resolve(currentData)
            if (!resolved.migrated) return currentData
            return currentData.toMutablePreferences().apply {
                this[vulkanDriverPreference] = resolved.driverId
                this[vulkanSelectionSchemaPreference] = VulkanDriverCatalog.SELECTION_SCHEMA
                if (resolved.notice != null) {
                    this[vulkanMigrationNoticePreference] = 1
                } else {
                    remove(vulkanMigrationNoticePreference)
                }
            }
        }

        override suspend fun cleanUp() = Unit

        private fun resolve(currentData: Preferences) =
            VulkanDriverCatalog.resolvePersistedSelection(
                requestedId = currentData[vulkanDriverPreference],
                selectionSchema = currentData[vulkanSelectionSchemaPreference] ?: 0,
                deviceModel = deviceModel,
            )
    }

/** Complete-schema migration: partial/older renderer choices must not reactivate. */
internal fun rendererSelectionMigration(): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[rendererSelectionSchemaPreference] !=
                ArmClientRendererCatalog.SELECTION_SCHEMA ||
                ArmClientRendererCatalog.find(currentData[rendererPreference]) == null

        override suspend fun migrate(currentData: Preferences): Preferences {
            if (!shouldMigrate(currentData)) return currentData
            return currentData.toMutablePreferences().apply {
                this[rendererPreference] = ArmClientRenderer.DXVK.id
                this[rendererSelectionSchemaPreference] =
                    ArmClientRendererCatalog.SELECTION_SCHEMA
            }
        }

        override suspend fun cleanUp() = Unit
    }

/** One instance in each Android process, coordinated through the shared settings file. */
private object PocketSettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): DataStore<Preferences> {
        val settingsFile = pocketSettingsDataFile(context).absoluteFile
        val storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            coordinatorProducer = { path, _ ->
                createMultiProcessCoordinator(scope.coroutineContext, path.toFile())
            },
            producePath = { settingsFile.toOkioPath() },
        )
        return MultiProcessDataStoreFactory.create(
            storage = storage,
            migrations = listOf(
                vulkanSelectionMigration(Build.MODEL),
                rendererSelectionMigration(),
            ),
            scope = scope,
        )
    }
}

internal fun pocketSettingsStore(context: Context): DataStore<Preferences> =
    PocketSettingsStore.get(context)

/**
 * Persistent, generation-independent app settings (the wizard/advanced screens
 * write here). Generation-managed settings (runtime tuples, addon profiles,
 * visual overlays) are NOT stored here — they live under [StorageRoots.runtime]
 * as versioned, rollbackable generations per DECISIONS.md #27.
 */
class Settings(private val context: Context) {

    private val store = pocketSettingsStore(context)

    /** Legacy, never-applied preference. It is read only for migration diagnostics. */
    enum class FpsProfile(val hz: Int) { FPS_30(30), FPS_40(40), FPS_60(60) }
    enum class AudioMode { OFF, ON }

    /**
     * Tunable auto-login timing set. Defaults equal the historical companion
     * constants in `SinglePlayerAutoLogin`/`InputContract`, so fresh and existing
     * installs resolve to identical behavior unless the user opts into "Advanced
     * timing". Persisted as flat int keys (Long values narrowed via toInt/toLong).
     */
    data class AutoLoginTimings(
        val pollIntervalMs: Long = 250L,
        val requiredStablePolls: Int = 4,
        val loginUiSettleMs: Long = 8_000L,
        val sessionTimeoutMs: Long = 300_000L,
        val drainPollMs: Long = 50L,
        val inputDrainTimeoutMs: Long = 5_000L,
        val imeKeyDwellMs: Long = 50L,
        val imeKeyGapMs: Long = 10L,
        val fieldSettleMs: Long = 300L,
        val pointerDwellMs: Long = 80L,
    ) {
        fun minimumInputDrainTimeoutMs(): Long =
            3L * (pointerDwellMs + fieldSettleMs) +
                32L * (imeKeyDwellMs + imeKeyGapMs) +
                2L * drainPollMs

        fun normalized(): AutoLoginTimings {
            val ranged = copy(
                pollIntervalMs = pollIntervalMs.coerceIn(100, 1_000),
                requiredStablePolls = requiredStablePolls.coerceIn(1, 12),
                loginUiSettleMs = loginUiSettleMs.coerceIn(1_000, 30_000),
                sessionTimeoutMs = sessionTimeoutMs.coerceIn(60_000, 900_000),
                drainPollMs = drainPollMs.coerceIn(25, 200),
                inputDrainTimeoutMs = inputDrainTimeoutMs.coerceIn(1_000, 30_000),
                imeKeyDwellMs = imeKeyDwellMs.coerceIn(20, 200),
                imeKeyGapMs = imeKeyGapMs.coerceIn(0, 100),
                fieldSettleMs = fieldSettleMs.coerceIn(50, 2_000),
                pointerDwellMs = pointerDwellMs.coerceIn(20, 500),
            )
            return ranged.copy(inputDrainTimeoutMs = ranged.inputDrainTimeoutMs
                .coerceAtLeast(ranged.minimumInputDrainTimeoutMs()))
        }

        fun toControlJson(): String = JSONObject()
            .put("pollIntervalMs", pollIntervalMs)
            .put("requiredStablePolls", requiredStablePolls)
            .put("loginUiSettleMs", loginUiSettleMs)
            .put("sessionTimeoutMs", sessionTimeoutMs)
            .put("drainPollMs", drainPollMs)
            .put("inputDrainTimeoutMs", inputDrainTimeoutMs)
            .put("imeKeyDwellMs", imeKeyDwellMs)
            .put("imeKeyGapMs", imeKeyGapMs)
            .put("fieldSettleMs", fieldSettleMs)
            .put("pointerDwellMs", pointerDwellMs)
            .toString()

        companion object {
            private val CONTROL_KEYS = setOf(
                "pollIntervalMs", "requiredStablePolls", "loginUiSettleMs",
                "sessionTimeoutMs", "drainPollMs", "inputDrainTimeoutMs",
                "imeKeyDwellMs", "imeKeyGapMs", "fieldSettleMs", "pointerDwellMs",
            )

            fun fromControlJson(raw: String): AutoLoginTimings {
                require(raw.toByteArray(Charsets.UTF_8).size <= 4_096) {
                    "auto-login timing payload is too large"
                }
                val value = JSONObject(raw)
                val actual = buildSet { value.keys().forEachRemaining { add(it) } }
                require(actual == CONTROL_KEYS) { "auto-login timing schema mismatch" }
                val requested = AutoLoginTimings(
                    pollIntervalMs = value.getLong("pollIntervalMs"),
                    requiredStablePolls = value.getInt("requiredStablePolls"),
                    loginUiSettleMs = value.getLong("loginUiSettleMs"),
                    sessionTimeoutMs = value.getLong("sessionTimeoutMs"),
                    drainPollMs = value.getLong("drainPollMs"),
                    inputDrainTimeoutMs = value.getLong("inputDrainTimeoutMs"),
                    imeKeyDwellMs = value.getLong("imeKeyDwellMs"),
                    imeKeyGapMs = value.getLong("imeKeyGapMs"),
                    fieldSettleMs = value.getLong("fieldSettleMs"),
                    pointerDwellMs = value.getLong("pointerDwellMs"),
                )
                val normalized = requested.normalized()
                require(normalized == requested) { "auto-login timings are outside supported bounds" }
                return requested
            }
        }
    }

    data class Snapshot(
        val displayProfileId: String = ClientDisplayProfile.BALANCED.id,
        val clientFrameCap: Int = ClientFrameCap.FPS_30.fps,
        val armRendererId: String = ArmClientRendererCatalog.DEFAULT_ID,
        val box64DxvkPackageId: String = RendererPackageCatalog.BOX64_DEFAULT,
        val armVulkanDriverId: String = VulkanDriverCatalog.RELEASE_DEFAULT,
        val rendererSelectionNotice: String? = null,
        val displaySelectionNotice: String? = null,
        val botProfileId: String = BotProfiles.BALANCED_100.id,
        val botPopulationTarget: Int = 100,
        val botAdvancedEnabled: Boolean = false,
        val botAdvanced: BotAdvancedSettings = BotAdvancedSettings(),
        val setupComplete: Boolean = false,
        val lastActiveGeneration: Int = 0,
        val inputSafeMode: Boolean = false,
        val autoLoginOnLaunch: Boolean = true,
        val autoLoginAdvanced: Boolean = false,
        val autoLoginTimings: AutoLoginTimings = AutoLoginTimings(),
        val tweaks: ClientTweaksConfig = ClientTweaksConfig(),
        val audioMode: AudioMode = AudioMode.ON,
        val nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        /** Missing legacy values migrate to LOCAL; LAN hosting remains opt-in. */
        val runtimeMode: RuntimeMode = RuntimeMode.LOCAL,
        val allowLanPlayers: Boolean = false,
    ) {
        fun selectedArmRenderer(): ArmClientRenderer =
            ArmClientRendererCatalog.requireSelection(armRendererId)

        fun selectedDxvkPackageId(): String = box64DxvkPackageId

        fun selectedVulkanDriverId(): String = armVulkanDriverId

        fun displaySelection(): ClientDisplaySelection = ClientDisplaySelection.nominal(
            ClientDisplayProfile.requireId(displayProfileId),
            ClientFrameCap.requireFps(clientFrameCap),
        )

        fun effectiveAutoLoginTimings(): AutoLoginTimings =
            if (autoLoginAdvanced) autoLoginTimings.normalized() else AutoLoginTimings()
    }

    val flow: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun update(transform: (Snapshot) -> Snapshot) {
        store.edit { prefs ->
            val current = prefs.toSnapshot()
            val next = transform(current)
            val requestedDisplay = next.displaySelection()
            val display = ClientDisplayCapabilities.requireSelection(
                context,
                requestedDisplay.profile.id,
                requestedDisplay.frameCap.fps,
            )
            prefs[Keys.DISPLAY_PROFILE] = display.profile.id
            prefs[Keys.FRAME_CAP] = display.frameCap.fps
            prefs[Keys.DISPLAY_SCHEMA] = 1
            // fps_profile was present before display selection was connected
            // to Wine. Do not reinterpret its FPS_40 default as user intent.
            prefs.remove(Keys.FPS)
            prefs[Keys.DXVK_BOX64] = next.box64DxvkPackageId
            prefs[Keys.VULKAN_DRIVER] = next.armVulkanDriverId
            prefs[Keys.VULKAN_SELECTION_SCHEMA] = VulkanDriverCatalog.SELECTION_SCHEMA
            prefs.remove(Keys.VULKAN_MIGRATION_NOTICE)
            prefs[Keys.RENDERER] = next.selectedArmRenderer().id
            prefs[Keys.RENDERER_SCHEMA] = ArmClientRendererCatalog.SELECTION_SCHEMA
            // Removed provider choices are not written back. Any settings write
            // completes their migration after reads have resolved Box64.
            prefs.remove(Keys.PROVIDER)
            prefs.remove(Keys.DXVK_FEX)
            prefs[Keys.BOTS] = next.botPopulationTarget
            prefs[Keys.BOT_PROFILE_ID] = next.botProfileId
            prefs[Keys.BOTS_ADVANCED] = if (next.botAdvancedEnabled) 1 else 0
            prefs[Keys.BOTS_NEARBY] = next.botAdvanced.nearbyBotLimit
            prefs[Keys.BOTS_RADIUS] = next.botAdvanced.nearbyRadius
            prefs[Keys.BOTS_LOGIN_BATCH] = next.botAdvanced.loginBatchSize
            prefs[Keys.BOTS_MAINTENANCE_BATCH] = next.botAdvanced.maintenanceBatchSize
            prefs[Keys.BOTS_UPDATE_MS] = next.botAdvanced.updateIntervalMs
            prefs[Keys.BOTS_TELEPORT_MIN] = next.botAdvanced.teleportMinMinutes
            prefs[Keys.BOTS_TELEPORT_MAX] = next.botAdvanced.teleportMaxMinutes
            prefs[Keys.BOTS_ITERATIONS] = next.botAdvanced.iterationsPerTick
            prefs[Keys.BOTS_P99] = next.botAdvanced.admissionWorldP99Ms
            prefs[Keys.BOTS_SYNC_LEVEL] = if (next.botAdvanced.syncLevelWithPlayers) 1 else 0
            prefs[Keys.BOTS_LIMIT_COMBAT] = if (next.botAdvanced.limitCombatActivity) 1 else 0
            prefs[Keys.BOTS_ACTIVE_PERCENT] = next.botAdvanced.activeBotPercent
            prefs[Keys.BOTS_AUTO_QUEST] = if (next.botAdvanced.autoDoQuests) 1 else 0
            prefs[Keys.BOTS_CHAT] = if (next.botAdvanced.allowBotChat) 1 else 0
            prefs[Keys.BOTS_INVITES] = if (next.botAdvanced.allowPlayerInvites) 1 else 0
            prefs[Keys.BOTS_GROUP_NEARBY] = if (next.botAdvanced.groupNearby) 1 else 0
            prefs[Keys.BOTS_WANDER] = if (next.botAdvanced.wanderWhenIdle) 1 else 0
            prefs[Keys.BOTS_OFF_SPEC] = if (next.botAdvanced.enableOffSpecStrategies) 1 else 0
            prefs[Keys.SETUP_DONE] = if (next.setupComplete) 1 else 0
            prefs[Keys.GENERATION] = next.lastActiveGeneration
            prefs[Keys.INPUT_SAFE_MODE] = if (next.inputSafeMode) 1 else 0
            prefs[Keys.AUTO_LOGIN_ON_LAUNCH] = if (next.autoLoginOnLaunch) 1 else 0
            prefs[Keys.AUTO_LOGIN_ADVANCED] = if (next.autoLoginAdvanced) 1 else 0
            val timings = next.autoLoginTimings.normalized()
            prefs[Keys.AL_POLL_INTERVAL] = timings.pollIntervalMs.toInt()
            prefs[Keys.AL_STABLE_POLLS] = timings.requiredStablePolls
            prefs[Keys.AL_LOGIN_SETTLE] = timings.loginUiSettleMs.toInt()
            prefs[Keys.AL_SESSION_TIMEOUT] = timings.sessionTimeoutMs.toInt()
            prefs[Keys.AL_DRAIN_POLL] = timings.drainPollMs.toInt()
            prefs[Keys.AL_INPUT_DRAIN_TIMEOUT] = timings.inputDrainTimeoutMs.toInt()
            prefs[Keys.AL_IME_DWELL] = timings.imeKeyDwellMs.toInt()
            prefs[Keys.AL_IME_GAP] = timings.imeKeyGapMs.toInt()
            prefs[Keys.AL_FIELD_SETTLE] = timings.fieldSettleMs.toInt()
            prefs[Keys.AL_POINTER_DWELL] = timings.pointerDwellMs.toInt()
            prefs[Keys.TWEAKS] = next.tweaks.toJson()
            prefs[Keys.TWEAKS_SCHEMA] = 1
            prefs[Keys.AUDIO_MODE] = next.audioMode.name
            prefs[Keys.NEARBY_INTERACT_TRIGGER_GUARD_MS] =
                NearbyInteractPolicy.normalizeTriggerGuardMs(next.nearbyInteractTriggerGuardMs)
            prefs[Keys.RUNTIME_MODE] = next.runtimeMode.name
            prefs[Keys.ALLOW_LAN_PLAYERS] = if (next.allowLanPlayers) 1 else 0
        }
    }

    private object Keys {
        val FPS = stringPreferencesKey("fps_profile")
        val DISPLAY_PROFILE = stringPreferencesKey("client_display_profile")
        val FRAME_CAP = intPreferencesKey("client_frame_cap")
        val DISPLAY_SCHEMA = intPreferencesKey("client_display_schema")
        val RENDERER = rendererPreference
        val RENDERER_SCHEMA = rendererSelectionSchemaPreference
        val PROVIDER = stringPreferencesKey("provider")
        val DXVK_BOX64 = stringPreferencesKey("dxvk_box64_package")
        val VULKAN_DRIVER = vulkanDriverPreference
        val VULKAN_SELECTION_SCHEMA = vulkanSelectionSchemaPreference
        val VULKAN_MIGRATION_NOTICE = vulkanMigrationNoticePreference
        val DXVK_FEX = stringPreferencesKey("dxvk_fex_package")
        val BOTS = intPreferencesKey("bot_population_target")
        val BOT_PROFILE_ID = stringPreferencesKey("bot_profile_id")
        val BOTS_ADVANCED = intPreferencesKey("bot_advanced_enabled")
        val BOTS_NEARBY = intPreferencesKey("bot_nearby_limit")
        val BOTS_RADIUS = intPreferencesKey("bot_nearby_radius")
        val BOTS_LOGIN_BATCH = intPreferencesKey("bot_login_batch")
        val BOTS_MAINTENANCE_BATCH = intPreferencesKey("bot_maintenance_batch")
        val BOTS_UPDATE_MS = intPreferencesKey("bot_update_interval_ms")
        val BOTS_TELEPORT_MIN = intPreferencesKey("bot_teleport_min_minutes")
        val BOTS_TELEPORT_MAX = intPreferencesKey("bot_teleport_max_minutes")
        val BOTS_ITERATIONS = intPreferencesKey("bot_iterations_per_tick")
        val BOTS_P99 = intPreferencesKey("bot_world_p99_ms")
        val BOTS_SYNC_LEVEL = intPreferencesKey("bot_sync_level")
        val BOTS_LIMIT_COMBAT = intPreferencesKey("bot_limit_combat")
        val BOTS_ACTIVE_PERCENT = intPreferencesKey("bot_active_percent")
        val BOTS_AUTO_QUEST = intPreferencesKey("bot_auto_quest")
        val BOTS_CHAT = intPreferencesKey("bot_allow_chat")
        val BOTS_INVITES = intPreferencesKey("bot_allow_invites")
        val BOTS_GROUP_NEARBY = intPreferencesKey("bot_group_nearby")
        val BOTS_WANDER = intPreferencesKey("bot_wander")
        val BOTS_OFF_SPEC = intPreferencesKey("bot_off_spec")
        val SETUP_DONE = intPreferencesKey("setup_complete")
        val GENERATION = intPreferencesKey("last_active_generation")
        val INPUT_SAFE_MODE = intPreferencesKey("input_safe_mode")
        val AUTO_LOGIN_ON_LAUNCH = intPreferencesKey("auto_login_on_launch")
        val AUTO_LOGIN_ADVANCED = intPreferencesKey("auto_login_advanced")
        val AL_POLL_INTERVAL = intPreferencesKey("al_poll_interval_ms")
        val AL_STABLE_POLLS = intPreferencesKey("al_stable_polls")
        val AL_LOGIN_SETTLE = intPreferencesKey("al_login_ui_settle_ms")
        val AL_SESSION_TIMEOUT = intPreferencesKey("al_session_timeout_ms")
        val AL_DRAIN_POLL = intPreferencesKey("al_drain_poll_ms")
        val AL_INPUT_DRAIN_TIMEOUT = intPreferencesKey("al_input_drain_timeout_ms")
        val AL_IME_DWELL = intPreferencesKey("al_ime_key_dwell_ms")
        val AL_IME_GAP = intPreferencesKey("al_ime_key_gap_ms")
        val AL_FIELD_SETTLE = intPreferencesKey("al_field_settle_ms")
        val AL_POINTER_DWELL = intPreferencesKey("al_pointer_dwell_ms")
        val TWEAKS = stringPreferencesKey("client_tweaks")
        val TWEAKS_SCHEMA = intPreferencesKey("client_tweaks_schema")
        val AUDIO_MODE = stringPreferencesKey("audio_mode")
        val NEARBY_INTERACT_TRIGGER_GUARD_MS =
            intPreferencesKey("nearby_interact_trigger_guard_ms")
        val RUNTIME_MODE = stringPreferencesKey("runtime_mode")
        val ALLOW_LAN_PLAYERS = intPreferencesKey("allow_lan_players")
    }

    private fun Preferences.toSnapshot(): Snapshot {
        val defaultDisplay = ClientDisplaySelection.defaultForDevice(
            Build.SUPPORTED_ABIS.asList(), Build.MODEL,
        )
        val displaySchema = this[Keys.DISPLAY_SCHEMA] ?: 0
        val requestedDisplayProfile = if (displaySchema >= 1) {
            runCatching {
                ClientDisplayProfile.requireId(this[Keys.DISPLAY_PROFILE].orEmpty())
            }.getOrDefault(defaultDisplay.profile)
        } else defaultDisplay.profile
        val normalizedDisplay = runCatching {
            val (width, height) = ClientDisplayCapabilities.physicalLandscapeBounds(context)
            ClientDisplayCapabilities.normalizeProfileForPhysical(
                requestedDisplayProfile, defaultDisplay.profile, width, height,
            )
        }.getOrElse {
            ClientDisplayCapabilities.NormalizedProfile(
                requestedDisplayProfile, changed = false,
            )
        }
        val displayProfile = normalizedDisplay.profile
        val displaySelectionNotice = if (normalizedDisplay.changed) {
            val (width, height) = ClientDisplayCapabilities.physicalLandscapeBounds(context)
            "The saved display resolution does not fit this screen; using " +
                "${displayProfile.resolveFor(width, height).resolution}."
        } else null
        val frameCap = if (displaySchema >= 1) {
            runCatching {
                ClientFrameCap.requireFps(this[Keys.FRAME_CAP] ?: 0)
            }.getOrDefault(defaultDisplay.frameCap)
        } else defaultDisplay.frameCap
        val legacyTarget = ((((this[Keys.BOTS] ?: 100) + 12) / 25) * 25).coerceIn(25, 700)
        val storedProfile = this[Keys.BOT_PROFILE_ID]?.let(BotProfiles::find)
        val selectedProfile = storedProfile?.takeIf { it.userSelectable }
            ?: BotProfiles.migrateLegacyTarget(storedProfile?.selectedTarget ?: legacyTarget)
        val target = if ((this[Keys.BOTS_ADVANCED] ?: 0) == 1) {
            legacyTarget
        } else {
            selectedProfile.selectedTarget
        }
        val defaults = BotAdvancedSettings.fromProfile(selectedProfile)
        val advanced = runCatching {
            BotAdvancedSettings(
                nearbyBotLimit = this[Keys.BOTS_NEARBY] ?: defaults.nearbyBotLimit,
                nearbyRadius = this[Keys.BOTS_RADIUS] ?: defaults.nearbyRadius,
                loginBatchSize = this[Keys.BOTS_LOGIN_BATCH] ?: defaults.loginBatchSize,
                maintenanceBatchSize = this[Keys.BOTS_MAINTENANCE_BATCH]
                    ?: defaults.maintenanceBatchSize,
                updateIntervalMs = this[Keys.BOTS_UPDATE_MS] ?: defaults.updateIntervalMs,
                teleportMinMinutes = this[Keys.BOTS_TELEPORT_MIN]
                    ?: defaults.teleportMinMinutes,
                teleportMaxMinutes = this[Keys.BOTS_TELEPORT_MAX]
                    ?: defaults.teleportMaxMinutes,
                iterationsPerTick = this[Keys.BOTS_ITERATIONS]
                    ?: defaults.iterationsPerTick,
                admissionWorldP99Ms = this[Keys.BOTS_P99]
                    ?: defaults.admissionWorldP99Ms,
                syncLevelWithPlayers = (this[Keys.BOTS_SYNC_LEVEL]
                    ?: if (defaults.syncLevelWithPlayers) 1 else 0) == 1,
                limitCombatActivity = (this[Keys.BOTS_LIMIT_COMBAT]
                    ?: if (defaults.limitCombatActivity) 1 else 0) == 1,
                activeBotPercent = this[Keys.BOTS_ACTIVE_PERCENT] ?: defaults.activeBotPercent,
                autoDoQuests = (this[Keys.BOTS_AUTO_QUEST]
                    ?: if (defaults.autoDoQuests) 1 else 0) == 1,
                allowBotChat = (this[Keys.BOTS_CHAT]
                    ?: if (defaults.allowBotChat) 1 else 0) == 1,
                allowPlayerInvites = (this[Keys.BOTS_INVITES]
                    ?: if (defaults.allowPlayerInvites) 1 else 0) == 1,
                groupNearby = (this[Keys.BOTS_GROUP_NEARBY]
                    ?: if (defaults.groupNearby) 1 else 0) == 1,
                wanderWhenIdle = (this[Keys.BOTS_WANDER]
                    ?: if (defaults.wanderWhenIdle) 1 else 0) == 1,
                enableOffSpecStrategies = (this[Keys.BOTS_OFF_SPEC]
                    ?: if (defaults.enableOffSpecStrategies) 1 else 0) == 1,
            )
        }.getOrDefault(defaults).takeIf { it.nearbyBotLimit <= target } ?: defaults
        val requestedBox64 = this[Keys.DXVK_BOX64]
        val box64Package = RendererPackageCatalog.normalize(
            ArmTranslationBackend.BOX64, requestedBox64,
        )
        val requestedVulkanDriver = this[Keys.VULKAN_DRIVER]
        val persistedVulkan = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = requestedVulkanDriver,
            selectionSchema = this[Keys.VULKAN_SELECTION_SCHEMA] ?: 0,
            deviceModel = Build.MODEL,
        )
        val vulkanDriver = persistedVulkan.driverId
        val vulkanAvailability = VulkanDriverCatalog.availability(vulkanDriver, Build.MODEL)
        val rendererSchema = this[Keys.RENDERER_SCHEMA] ?: 0
        val persistedRenderer = ArmClientRendererCatalog.resolvePersisted(
            this[Keys.RENDERER], rendererSchema,
        )
        val renderer = persistedRenderer.takeIf {
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" || it == ArmClientRenderer.DXVK
        } ?: ArmClientRenderer.DXVK
        val removedProvider = this[Keys.PROVIDER]?.takeIf { it != "BOX64" }
        val removedRenderer = this[Keys.RENDERER]?.takeIf {
            rendererSchema != ArmClientRendererCatalog.SELECTION_SCHEMA &&
                !it.equals("DXVK", ignoreCase = true)
        }
        val packageChanged = requestedBox64?.takeIf { it != box64Package }
        val persistedVulkanNotice = VulkanDriverCatalog.RP6_SYSTEM_MIGRATION_NOTICE.takeIf {
            this[Keys.VULKAN_MIGRATION_NOTICE] == 1
        }
        val selectionNotice = when {
            persistedVulkanNotice != null -> persistedVulkanNotice
            persistedVulkan.migrated -> persistedVulkan.notice
            removedProvider != null || removedRenderer != null ->
                "A removed client runtime selection was migrated to Box64 + DXVK ($box64Package)."
            packageChanged != null ->
                "The saved DXVK package is unavailable; using $box64Package."
            renderer == ArmClientRenderer.DXVK && VulkanDriverCatalog.find(vulkanDriver) == null ->
                "The saved Vulkan driver is unknown. Choose an available packaged driver before launch."
            renderer == ArmClientRenderer.DXVK && !vulkanAvailability.available ->
                vulkanAvailability.reason
            else -> null
        }
        val timings = AutoLoginTimings(
            pollIntervalMs = (this[Keys.AL_POLL_INTERVAL] ?: 250).toLong().coerceIn(100, 1000),
            requiredStablePolls = (this[Keys.AL_STABLE_POLLS] ?: 4).coerceIn(1, 12),
            loginUiSettleMs = (this[Keys.AL_LOGIN_SETTLE] ?: 8_000).toLong().coerceIn(1_000, 30_000),
            sessionTimeoutMs = (this[Keys.AL_SESSION_TIMEOUT] ?: 300_000).toLong().coerceIn(60_000, 900_000),
            drainPollMs = (this[Keys.AL_DRAIN_POLL] ?: 50).toLong().coerceIn(25, 200),
            inputDrainTimeoutMs = (this[Keys.AL_INPUT_DRAIN_TIMEOUT] ?: 5_000).toLong().coerceIn(1_000, 30_000),
            imeKeyDwellMs = (this[Keys.AL_IME_DWELL] ?: 50).toLong().coerceIn(20, 200),
            imeKeyGapMs = (this[Keys.AL_IME_GAP] ?: 10).toLong().coerceIn(0, 100),
            fieldSettleMs = (this[Keys.AL_FIELD_SETTLE] ?: 300).toLong().coerceIn(50, 2000),
            pointerDwellMs = (this[Keys.AL_POINTER_DWELL] ?: 80).toLong().coerceIn(20, 500),
        ).normalized()
        return Snapshot(
        displayProfileId = displayProfile.id,
        clientFrameCap = frameCap.fps,
        armRendererId = renderer.id,
        box64DxvkPackageId = box64Package,
        armVulkanDriverId = vulkanDriver,
        rendererSelectionNotice = selectionNotice,
        displaySelectionNotice = displaySelectionNotice,
        botProfileId = selectedProfile.id,
        botPopulationTarget = target,
        botAdvancedEnabled = (this[Keys.BOTS_ADVANCED] ?: 0) == 1,
        botAdvanced = advanced,
        setupComplete = (this[Keys.SETUP_DONE] ?: 0) == 1,
        lastActiveGeneration = this[Keys.GENERATION] ?: 0,
        inputSafeMode = (this[Keys.INPUT_SAFE_MODE] ?: 0) == 1,
        autoLoginOnLaunch = (this[Keys.AUTO_LOGIN_ON_LAUNCH] ?: 1) == 1,
        autoLoginAdvanced = (this[Keys.AUTO_LOGIN_ADVANCED] ?: 0) == 1,
        autoLoginTimings = timings,
        // The first implementation silently persisted nearly every patch as enabled.
        // Preserve only explicitly versioned user choices; migrate that unversioned
        // configuration once to the pristine Vanilla executable.
        tweaks = if ((this[Keys.TWEAKS_SCHEMA] ?: 0) >= 1) {
            ClientTweaksConfig.fromJson(this[Keys.TWEAKS])
        } else ClientTweaksConfig(),
        audioMode = runCatching { AudioMode.valueOf(this[Keys.AUDIO_MODE] ?: "") }
            .getOrDefault(AudioMode.ON),
        nearbyInteractTriggerGuardMs = NearbyInteractPolicy.normalizeTriggerGuardMs(
            this[Keys.NEARBY_INTERACT_TRIGGER_GUARD_MS]
                ?: NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        ),
        runtimeMode = runCatching { RuntimeMode.valueOf(this[Keys.RUNTIME_MODE] ?: "") }
            .getOrDefault(RuntimeMode.LOCAL),
        allowLanPlayers = (this[Keys.ALLOW_LAN_PLAYERS] ?: 0) == 1,
        )
    }
}
