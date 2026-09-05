package com.pocketrealm.storage

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.createMultiProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.ClientDisplayCapabilities
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientDisplaySelection
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.ingame.WowGameSettingsConfig
import com.pocketrealm.llm.ComputeMode
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.server.LlmRuntimePolicy
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.supervisor.RuntimeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
private val allowUserVulkanDriversPreference =
    intPreferencesKey("allow_user_vulkan_drivers")
private val rendererPreference = stringPreferencesKey("renderer")
internal val tweaksPreference = stringPreferencesKey("client_tweaks")
internal val tweaksSchemaPreference = intPreferencesKey("client_tweaks_schema")
internal val setupCompletePreference = intPreferencesKey("setup_complete")
private val rendererSelectionSchemaPreference =
    intPreferencesKey("arm_renderer_selection_schema")

internal const val POCKET_SETTINGS_STORE_NAME = "pocket_settings"
internal const val POCKET_SETTINGS_FILE_NAME = "$POCKET_SETTINGS_STORE_NAME.preferences_pb"

/**
 * client_tweaks schema history:
 *  1 — explicitly versioned user choices (the unversioned era's accidental
 *      enable-everything state was migrated once to pristine Vanilla);
 *  2 — one-time first-boot defaults for widescreen devices (fov, quick-loot,
 *      camera-skip fix, max camera distance). Only never-configured keys
 *      (schema < 1) gain the new defaults; schema-1 explicit choices are
 *      preserved untouched. [Settings.update] must keep writing this
 *      constant or every settings write would regress the stamp and re-run
 *      the migration.
 */
internal const val TWEAKS_SCHEMA_VERSION = 2

/**
 * F3d: enable the recommended tweak set once for never-configured installs
 * when the resolved virtual display is widescreen (both adaptive profiles
 * are 16:9; the fixed-aspect Classic 4:3 profile disables the FOV tweak at
 * selection time).
 * The 4:3 coupling and the user's later toggles always win — this runs at
 * most once, ever.
 */
internal fun clientTweaksDefaultsMigration(context: Context): DataMigration<Preferences> =
    clientTweaksDefaultsMigration {
        runCatching { resolvesWidescreenVirtualDisplay(context) }.getOrDefault(true)
    }

internal fun clientTweaksDefaultsMigration(
    resolvesWidescreen: () -> Boolean,
): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            (currentData[tweaksSchemaPreference] ?: 0) < TWEAKS_SCHEMA_VERSION

        override suspend fun migrate(currentData: Preferences): Preferences {
            val priorSchema = currentData[tweaksSchemaPreference] ?: 0
            val neverConfigured = priorSchema < 1
            val widescreen = resolvesWidescreen()
            return currentData.toMutablePreferences().apply {
                if (neverConfigured && widescreen) {
                    val defaults = ClientTweaksConfig.fromJson(this[tweaksPreference])
                        .copy(
                            fovEnabled = true,
                            quicklootEnabled = true,
                            cameraSkipFixEnabled = true,
                            maxCameraDistanceEnabled = true,
                        )
                    this[tweaksPreference] = defaults.toJson()
                }
                this[tweaksSchemaPreference] = TWEAKS_SCHEMA_VERSION
            }
        }

        override suspend fun cleanUp() = Unit
    }

private const val WIDESCREEN_WIDTH_MULTIPLIER = 9
private const val WIDESCREEN_HEIGHT_MULTIPLIER = 16

internal fun resolvesWidescreenVirtualDisplay(context: Context): Boolean {
    val (width, height) = ClientDisplayCapabilities.physicalLandscapeBounds(context)
    val profile = ClientDisplayProfile.forDevice(Build.SUPPORTED_ABIS.asList(), Build.MODEL)
    val display = profile.resolveFor(width, height)
    return display.width * WIDESCREEN_WIDTH_MULTIPLIER ==
        display.height * WIDESCREEN_HEIGHT_MULTIPLIER
}

/**
 * A persisted user-driver selection only stays effective while the
 * user-driver lane is enabled; disabled lanes resolve to Auto (with the
 * visible Settings notice) instead of a launch-time silent swap.
 */
internal fun resolveEffectiveVulkanSelection(
    persistedDriverId: String,
    allowUserVulkanDrivers: Boolean,
): String =
    if (UserVulkanDriver.isUserId(persistedDriverId) && !allowUserVulkanDrivers) {
        VulkanDriverCatalog.AUTO_ID
    } else persistedDriverId

internal fun pocketSettingsDataFile(context: Context): File =
    context.applicationContext.preferencesDataStoreFile(POCKET_SETTINGS_STORE_NAME)

internal fun vulkanSelectionMigration(
    adrenoGpu: Boolean = ArmRendererAuto.isAdrenoGpu(),
): DataMigration<Preferences> =
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
                adrenoGpu = adrenoGpu,
            )
    }

/** Complete-schema migration: partial/older renderer choices must not reactivate. */
internal fun rendererSelectionMigration(): DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            if (currentData[rendererSelectionSchemaPreference] !=
                ArmClientRendererCatalog.SELECTION_SCHEMA
            ) return true
            val stored = currentData[rendererPreference]
            return !ArmClientRendererCatalog.isAutoSelection(stored) &&
                ArmClientRendererCatalog.find(stored) == null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            if (!shouldMigrate(currentData)) return currentData
            val stored = currentData[rendererPreference]
            val resolved = if (ArmClientRendererCatalog.isAutoSelection(stored)) {
                ArmClientRendererCatalog.AUTO_ID
            } else {
                ArmClientRendererCatalog.resolvePersisted(
                    stored, currentData[rendererSelectionSchemaPreference] ?: 0,
                )
            }
            return currentData.toMutablePreferences().apply {
                this[rendererPreference] = resolved
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
                vulkanSelectionMigration(),
                rendererSelectionMigration(),
                clientTweaksDefaultsMigration(context),
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
 * as versioned, rollbackable generations.
 */
class Settings(private val context: Context) {

    private val store = pocketSettingsStore(context)

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
        /**
         * App-managed WoW UI scale, or null when the client owns the
         * `useUiScale`/`uiScale` CVars (stock behavior). Managed values are
         * clamped per display profile by [effectiveClientUiScale].
         */
        val clientUiScale: Float? = null,
        val armRendererId: String = ArmClientRendererCatalog.DEFAULT_ID,
        val box64DxvkPackageId: String = RendererPackageCatalog.BOX64_DEFAULT,
        val armVulkanDriverId: String = VulkanDriverCatalog.AUTO_ID,
        /** Opt-in lane for user-imported Vulkan drivers; default off. */
        val allowUserVulkanDrivers: Boolean = false,
        val rendererSelectionNotice: String? = null,
        val displaySelectionNotice: String? = null,
        val botProfileId: String = BotProfiles.defaultProfile.id,
        val botPopulationTarget: Int = BotProfiles.defaultProfile.selectedTarget,
        val botSavedPresetId: String? = null,
        val botPresetsImported: Boolean = false,
        val botAdvancedEnabled: Boolean = false,
        val botAdvanced: BotAdvancedSettings = BotAdvancedSettings.fromProfile(BotProfiles.defaultProfile),
        val setupComplete: Boolean = false,
        val lastActiveGeneration: Int = 0,
        val inputSafeMode: Boolean = false,
        val autoLoginOnLaunch: Boolean = true,
        val autoLoginAdvanced: Boolean = false,
        val autoLoginTimings: AutoLoginTimings = AutoLoginTimings(),
        val tweaks: ClientTweaksConfig = ClientTweaksConfig(),
        /**
         * Pending in-game settings edits (queue only; the revision counter
         * and direct-edit journal live in their own keys and are written
         * exclusively through [mutateGameSettings] /
         * [journalGameSettingsDirectEdit], never through [update]).
         */
        val gameSettings: WowGameSettingsConfig = WowGameSettingsConfig(),
        val gameSettingsRevision: Long = 0L,
        val gameSettingsDirectEditRevisions: Map<String, Long> = emptyMap(),
        val audioMode: AudioMode = AudioMode.ON,
        val nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        /**
         * B2: verbose world-server logging (mangosd LogFileLevel = 3) staged
         * into world.conf. Default OFF keeps the world log at errors-only
         * (level 1, matching realmd): the vendored level 3 flooded world.log
         * with movement and battleground churn (79.7 MB over a 30-minute
         * soak) that has never diagnosed a field issue. Applies on the next
         * realm start, like every world.conf knob - the conf is written at
         * world start.
         */
        val worldDebugLogs: Boolean = false,
        /** Missing legacy values migrate to LOCAL; LAN hosting remains opt-in. */
        val runtimeMode: RuntimeMode = RuntimeMode.LOCAL,
        val allowLanPlayers: Boolean = false,
        /**
         * Playerbot LLM runtime (:llm process, llama-server + optional Hexagon
         * NPU hybrid). Default OFF — the reviewed base conf keeps
         * AiPlayerbot.LLMEnabled = 0 until the user opts in from the LLM
         * submenu. Toggles apply on the next realm start (the conf is written
         * at world start).
         */
        val llmEnabled: Boolean = false,
        val llmComputeMode: ComputeMode = LlmRuntimePolicy.DEFAULT_COMPUTE_MODE,
        val llmCoresMask: Long = LlmRuntimePolicy.DEFAULT_CORES_MASK,
        val llmThreads: Int = LlmRuntimePolicy.DEFAULT_THREADS,
        val llmOffloadLayers: Int = LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS,
        /**
         * External endpoint mode: when on, the realm conf points bot chat at
         * any OpenAI-compatible /v1/chat/completions endpoint ([llmExternalUrl],
         * optional [llmExternalApiKey] sent as a Bearer header, [llmExternalModel]
         * in the request body) and the embedded :llm runtime is never started.
         * The URL/model/key are fail-closed: an invalid endpoint suppresses
         * the whole conf block, exactly like a missing local model file.
         */
        val llmExternalMode: Boolean = false,
        val llmExternalUrl: String = "",
        val llmExternalApiKey: String = "",
        val llmExternalModel: String = "",
        /**
         * WS-A Cloud conversation toggle (AiPlayerbot.LLMCloudChatter):
         * masters every cloud-lane widening natively as the conjunction
         * key AND external tier — the device lane never widens. Ships
         * OFF (the upgrade cohort keeps today's external behavior);
         * turning it on is a spend decision (see the LLM screen
         * disclosure) and applies on the next realm start.
         */
        val llmCloudChatter: Boolean = false,
        /**
         * Authored banter layer (rare kill quips, tier greetings, idle/mood
         * lines). Free — no model call behind any of it — and heavily
         * rate-limited in the native layer; default ON because it only ever
         * matters while the LLM feature itself is enabled.
         */
        val llmBanter: Boolean = true,
        /**
         * World chatter: the LLM-voiced ambient layers — party
         * banter, proximity murmur, rare general-chat set pieces — every
         * line event-gated (silence is the default; no fact-bank row, no
         * line). The collapsed power state (off / low-battery dim / normal)
         * is staged to the native layer at world start by
         * [com.pocketrealm.server.ChatterPowerMonitor] and re-staged on
         * battery events while the realm runs.
         * Default OFF: ambient generation costs battery, and the user opts
         * in. The external endpoint fields double as the cloud-composer
         * configuration when set (the composer is a cloud-class job).
         */
        val llmAmbience: Boolean = false,
        /**
         * Selected registry model ([LlmModelRegistry]); drives the staged-file
         * gate, the runtime's model path, and the request-body sampling
         * profile. Unknown persisted ids resolve to the registry default at
         * read time; applies on the next realm start like every llm* toggle.
         */
        val llmModelId: String = LlmModelRegistry.DEFAULT_MODEL_ID,
        /**
         * Verbose-tier disclosure for the LLM submenu (the AI-settings
         * "advanced" gate, like [autoLoginAdvanced]). Off, the submenu shows
         * the simple tier only (speech switch, source, banter, world chatter,
         * model); on, it also shows the accelerator, generation, and
         * connection cards. Purely a UI gate — no knob changes value until
         * the user touches it.
         */
        val llmAdvanced: Boolean = false,
        /**
         * Advanced-tier reply-length override in tokens, or 0 = follow the
         * selected model's sampling profile
         * (emitted as AiPlayerbot.LLMMaxNewTokens). Clamped through
         * [LlmRuntimePolicy.normalizeMaxNewTokensOverride] at read and write.
         */
        val llmMaxNewTokens: Int = 0,
        /**
         * Advanced-tier generation-timeout override in seconds, or 0 = follow
         * the model's tier profile (emitted as
         * AiPlayerbot.LLMGenerationTimeout). Clamped through
         * [LlmRuntimePolicy.normalizeGenerationTimeoutOverride].
         */
        val llmGenerationTimeout: Int = 0,
        /**
         * Player-edited prompt pack JSON (Phase 2 Advanced prompt manager):
         * empty = the trained default pack. Persisted verbatim (never
         * trimmed — a trim would fight the editor mid-typing); resolved via
         * [com.pocketrealm.llm.LlmPromptPack.resolve] at read/stage time,
         * so a corrupt edit fails open to the default, never to silence.
         * Applies on the next realm start like every llm* setting.
         */
        val llmPromptPackJson: String = "",
    ) {
        /** Persisted selection; [ArmClientRendererCatalog.AUTO_ID] is allowed. */
        fun selectedArmRendererId(): String = armRendererId

        fun isAutoRenderer(): Boolean =
            ArmClientRendererCatalog.isAutoSelection(armRendererId)

        fun effectiveRenderer(): ArmClientRenderer =
            if (isAutoRenderer()) ArmRendererAuto.resolve()
            else ArmClientRendererCatalog.requireSelection(armRendererId)

        fun selectedDxvkPackageId(): String = box64DxvkPackageId

        fun selectedVulkanDriverId(): String = armVulkanDriverId

        fun effectiveVulkanDriverId(): String =
            ArmRendererAuto.resolveVulkanDriverId(armVulkanDriverId) ?: armVulkanDriverId

        fun displaySelection(): ClientDisplaySelection {
            clientUiScale?.let { require(it in 0.5f..2.0f) { "unsupported client UI scale: $it" } }
            return ClientDisplaySelection.nominal(
                ClientDisplayProfile.requireId(displayProfileId),
                ClientFrameCap.requireFps(clientFrameCap),
            )
        }

        /**
         * The scale actually enforced this launch: the managed value clamped
         * so stock ~512-unit-tall frames stay on-screen (virtualHeight/512,
         * capped at 2.0). Null stays null — unmanaged is never clamped.
         */
        fun effectiveClientUiScale(virtualHeight: Int): Float? =
            clientUiScale?.coerceIn(0.5f, 2f)?.coerceAtMost(virtualHeight / 512f)

        fun effectiveAutoLoginTimings(): AutoLoginTimings =
            if (autoLoginAdvanced) autoLoginTimings.normalized() else AutoLoginTimings()
    }

    val flow: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    /**
     * One-shot blocking read for non-suspend contexts — the :world conf
     * generation at realm start (ServerRuntimeFiles) reads the LLM toggle
     * this way. The multi-process coordinator makes the read consistent with
     * writes from :supervisor/:ui; never call this on a hot path.
     */
    fun blockingSnapshot(): Snapshot = kotlinx.coroutines.runBlocking { flow.first() }

    suspend fun update(transform: (Snapshot) -> Snapshot) {
        store.edit { prefs ->
            val current = prefs.toSnapshot()
            val next = transform(current)
            // No-op writes (every keystroke that normalizes back to the
            // current snapshot) skip the display resolve and the full
            // write-set: they hold the DataStore mutex for nothing.
            if (next == current) return@edit
            val requestedDisplay = next.displaySelection()
            val display = ClientDisplayCapabilities.requireSelection(
                context,
                requestedDisplay.profile.id,
                requestedDisplay.frameCap.fps,
            )
            prefs.writeSnapshotWrites(next, display)
        }
    }

    /**
     * One atomic queue mutation: bump the global revision counter and hand
     * the transformed queue and the post-bump revision to [transform], which
     * stamps each staged entry. The counter and the queue commit together —
     * a rejected transform (e.g. payload over cap) rolls both back. The
     * counter lives in its own preferences key, so no JSON parse failure or
     * `update()` rewrite can ever regress it.
     */
    suspend fun mutateGameSettings(
        transform: (WowGameSettingsConfig, Long) -> WowGameSettingsConfig,
    ): Long {
        var bumped = 0L
        store.edit { prefs ->
            val current = if ((prefs[Keys.GAME_SETTINGS_SCHEMA] ?: 0) >= 1) {
                WowGameSettingsConfig.fromJson(prefs[Keys.GAME_SETTINGS])
            } else WowGameSettingsConfig()
            val revision = (prefs[Keys.GAME_SETTINGS_REVISION] ?: 0L) + 1L
            val next = transform(current, revision)
            val json = next.toJson()
            require(json.toByteArray(Charsets.UTF_8).size <= WowGameSettingsConfig.MAX_JSON_BYTES) {
                "the in-game settings queue is full — discard some pending changes first"
            }
            prefs[Keys.GAME_SETTINGS_REVISION] = revision
            prefs[Keys.GAME_SETTINGS] = json
            prefs[Keys.GAME_SETTINGS_SCHEMA] = 1
            bumped = revision
        }
        return bumped
    }

    /**
     * Journal one direct file edit: bump the counter (every direct edit
     * bumps it, not only queue-superseding ones) and record the post-bump
     * revision for the edited key. The master-sound transition rule in
     * `ManagedConfigPolicy` depends on this journal to tell a user-chosen
     * master-off apart from a stale enforced zero.
     */
    suspend fun journalGameSettingsDirectEdit(key: String): Long {
        var bumped = 0L
        store.edit { prefs ->
            val revision = (prefs[Keys.GAME_SETTINGS_REVISION] ?: 0L) + 1L
            val edits = parseGameSettingsDirectEdits(prefs[Keys.GAME_SETTINGS_DIRECT_EDITS])
                .toMutableMap()
            edits[key] = revision
            prefs[Keys.GAME_SETTINGS_REVISION] = revision
            prefs[Keys.GAME_SETTINGS_DIRECT_EDITS] =
                JSONObject(edits.toMap()).toString()
            bumped = revision
        }
        return bumped
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
        val clientUiScale = this[Keys.UI_SCALE]?.takeIf { it in 0.5f..2.0f }
        // Legacy flat target key still stores the adv4-era population. New
        // installs resolve to the recommended default (Alive Realm 320).
        val storedTarget = this[Keys.BOTS]
        val legacyTarget = storedTarget?.let { ((((it + 12) / 25) * 25).coerceIn(25, 700)) }
            ?: BotProfiles.defaultProfile.selectedTarget
        val storedProfile = this[Keys.BOT_PROFILE_ID]?.let(BotProfiles::find)
        val selectedProfile = when {
            storedProfile?.userSelectable == true -> storedProfile
            // Legacy ladder ids remain resolvable; migration keeps their id.
            storedProfile != null -> BotProfiles.migrateLegacyTarget(storedProfile.selectedTarget)
            storedTarget != null -> BotProfiles.migrateLegacyTarget(legacyTarget)
            else -> BotProfiles.defaultProfile
        }
        val savedPresetId = this[Keys.BOT_SAVED_PRESET]?.takeIf {
            it.matches(Regex("[0-9a-f]{32}"))
        }
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
        val adrenoGpu = ArmRendererAuto.isAdrenoGpu()
        val persistedVulkan = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = requestedVulkanDriver,
            selectionSchema = this[Keys.VULKAN_SELECTION_SCHEMA] ?: 0,
            adrenoGpu = adrenoGpu,
        )
        val allowUserVulkanDrivers =
            (this[Keys.ALLOW_USER_VULKAN_DRIVERS] ?: 0) == 1
        val vulkanDriver = resolveEffectiveVulkanSelection(
            persistedVulkan.driverId, allowUserVulkanDrivers,
        )
        val vulkanAvailability = VulkanDriverCatalog.availability(vulkanDriver, adrenoGpu)
        val rendererSchema = this[Keys.RENDERER_SCHEMA] ?: 0
        val persistedRendererId = ArmClientRendererCatalog.resolvePersisted(
            this[Keys.RENDERER], rendererSchema,
        )
        // Non-ARM64 builds cannot package the GL bridges; they keep DXVK.
        val rendererId = persistedRendererId.takeIf {
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" ||
                it == ArmClientRendererCatalog.AUTO_ID ||
                it == ArmClientRenderer.DXVK.id
        } ?: ArmClientRenderer.DXVK.id
        val removedProvider = this[Keys.PROVIDER]?.takeIf { it != "BOX64" }
        val removedRenderer = this[Keys.RENDERER]?.takeIf {
            rendererSchema < 2 &&
                !it.equals("DXVK", ignoreCase = true)
        }
        val packageChanged = requestedBox64?.takeIf { it != box64Package }
        val selectionNotice = when {
            removedProvider != null || removedRenderer != null ->
                "A removed client runtime selection was migrated to Box64 + DXVK ($box64Package)."
            packageChanged != null ->
                "The saved DXVK package is unavailable; using $box64Package."
            rendererId == ArmClientRenderer.DXVK.id && !allowUserVulkanDrivers &&
                UserVulkanDriver.isUserId(persistedVulkan.driverId) ->
                "Imported Vulkan drivers are turned off; the driver selection was reset to Auto."
            rendererId == ArmClientRenderer.DXVK.id &&
                vulkanDriver != VulkanDriverCatalog.AUTO_ID &&
                !UserVulkanDriver.isUserId(vulkanDriver) &&
                VulkanDriverCatalog.find(vulkanDriver) == null ->
                "The saved Vulkan driver is unknown. Choose an available packaged driver before launch."
            // User-lane ids are registry-backed, not catalog-backed: their
            // availability/quarantine state is gated at launch with its own
            // exact reasons, never the catalog's unknown-package notice.
            rendererId == ArmClientRenderer.DXVK.id &&
                !UserVulkanDriver.isUserId(vulkanDriver) &&
                !vulkanAvailability.available ->
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
        val llm = readLlmSnapshotFields()
        return Snapshot(
        displayProfileId = displayProfile.id,
        clientFrameCap = frameCap.fps,
        clientUiScale = clientUiScale,
        armRendererId = rendererId,
        box64DxvkPackageId = box64Package,
        armVulkanDriverId = vulkanDriver,
        allowUserVulkanDrivers = allowUserVulkanDrivers,
        rendererSelectionNotice = selectionNotice,
        displaySelectionNotice = displaySelectionNotice,
        botProfileId = selectedProfile.id,
        botPopulationTarget = target,
        botSavedPresetId = savedPresetId,
        botPresetsImported = (this[Keys.BOT_PRESETS_IMPORTED] ?: 0) == 1,
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
        gameSettings = if ((this[Keys.GAME_SETTINGS_SCHEMA] ?: 0) >= 1) {
            WowGameSettingsConfig.fromJson(this[Keys.GAME_SETTINGS])
        } else WowGameSettingsConfig(),
        gameSettingsRevision = this[Keys.GAME_SETTINGS_REVISION] ?: 0L,
        gameSettingsDirectEditRevisions =
            parseGameSettingsDirectEdits(this[Keys.GAME_SETTINGS_DIRECT_EDITS]),
        audioMode = runCatching { AudioMode.valueOf(this[Keys.AUDIO_MODE] ?: "") }
            .getOrDefault(AudioMode.ON),
        nearbyInteractTriggerGuardMs = NearbyInteractPolicy.normalizeTriggerGuardMs(
            this[Keys.NEARBY_INTERACT_TRIGGER_GUARD_MS]
                ?: NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        ),
        worldDebugLogs = (this[Keys.WORLD_DEBUG_LOGS] ?: 0) == 1,
        runtimeMode = runCatching { RuntimeMode.valueOf(this[Keys.RUNTIME_MODE] ?: "") }
            .getOrDefault(RuntimeMode.LOCAL),
        allowLanPlayers = (this[Keys.ALLOW_LAN_PLAYERS] ?: 0) == 1,
        llmEnabled = llm.enabled,
        llmComputeMode = llm.computeMode,
        llmCoresMask = llm.coresMask,
        llmThreads = llm.threads,
        llmOffloadLayers = llm.offloadLayers,
        llmExternalMode = llm.externalMode,
        llmExternalUrl = llm.externalUrl,
        llmExternalApiKey = llm.externalApiKey,
        llmExternalModel = llm.externalModel,
        llmCloudChatter = llm.cloudChatter,
        llmBanter = llm.banter,
        llmAmbience = llm.ambience,
        llmModelId = llm.modelId,
        llmAdvanced = llm.advanced,
        llmMaxNewTokens = llm.maxNewTokens,
        llmGenerationTimeout = llm.generationTimeout,
        llmPromptPackJson = llm.promptPackJson,
        )
    }

    private fun parseGameSettingsDirectEdits(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val out = linkedMapOf<String, Long>()
            root.keys().forEachRemaining { key -> out[key] = root.getLong(key) }
            out
        }.getOrDefault(emptyMap())
    }
}

private object Keys {
    val FPS = stringPreferencesKey("fps_profile")
    val DISPLAY_PROFILE = stringPreferencesKey("client_display_profile")
    val FRAME_CAP = intPreferencesKey("client_frame_cap")
    val UI_SCALE = floatPreferencesKey("client_ui_scale")
    val DISPLAY_SCHEMA = intPreferencesKey("client_display_schema")
    val RENDERER = rendererPreference
    val RENDERER_SCHEMA = rendererSelectionSchemaPreference
    val PROVIDER = stringPreferencesKey("provider")
    val DXVK_BOX64 = stringPreferencesKey("dxvk_box64_package")
    val VULKAN_DRIVER = vulkanDriverPreference
    val VULKAN_SELECTION_SCHEMA = vulkanSelectionSchemaPreference
    val VULKAN_MIGRATION_NOTICE = vulkanMigrationNoticePreference
    val ALLOW_USER_VULKAN_DRIVERS = allowUserVulkanDriversPreference
    val DXVK_FEX = stringPreferencesKey("dxvk_fex_package")
    val BOTS = intPreferencesKey("bot_population_target")
    val BOT_PROFILE_ID = stringPreferencesKey("bot_profile_id")
    val BOT_SAVED_PRESET = stringPreferencesKey("bot_saved_preset_id")
    val BOT_PRESETS_IMPORTED = intPreferencesKey("bot_presets_imported")
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
    val SETUP_DONE = setupCompletePreference
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
    val TWEAKS = tweaksPreference
    val TWEAKS_SCHEMA = tweaksSchemaPreference
    val GAME_SETTINGS = stringPreferencesKey("game_settings_queue")
    val GAME_SETTINGS_SCHEMA = intPreferencesKey("game_settings_queue_schema")
    val GAME_SETTINGS_REVISION = longPreferencesKey("game_settings_revision")
    val GAME_SETTINGS_DIRECT_EDITS = stringPreferencesKey("game_settings_direct_edit_revisions")
    val AUDIO_MODE = stringPreferencesKey("audio_mode")
    val NEARBY_INTERACT_TRIGGER_GUARD_MS =
        intPreferencesKey("nearby_interact_trigger_guard_ms")
    val WORLD_DEBUG_LOGS = intPreferencesKey("world_debug_logs")
    val RUNTIME_MODE = stringPreferencesKey("runtime_mode")
    val ALLOW_LAN_PLAYERS = intPreferencesKey("allow_lan_players")
    val LLM_ENABLED = intPreferencesKey("llm_enabled")
    val LLM_COMPUTE_MODE = stringPreferencesKey("llm_compute_mode")
    val LLM_CORES_MASK = longPreferencesKey("llm_cores_mask")
    val LLM_THREADS = intPreferencesKey("llm_threads")
    val LLM_OFFLOAD_LAYERS = intPreferencesKey("llm_offload_layers")
    val LLM_EXTERNAL_MODE = intPreferencesKey("llm_external_mode")
    val LLM_EXTERNAL_URL = stringPreferencesKey("llm_external_url")
    val LLM_EXTERNAL_API_KEY = stringPreferencesKey("llm_external_api_key")
    val LLM_EXTERNAL_MODEL = stringPreferencesKey("llm_external_model")
    internal val LLM_CLOUD_CHATTER = intPreferencesKey("llm_cloud_chatter")
    val LLM_BANTER = intPreferencesKey("llm_banter")
    val LLM_AMBIENCE = intPreferencesKey("llm_ambience")
    val LLM_MODEL_ID = stringPreferencesKey("llm_model_id")
    val LLM_ADVANCED = intPreferencesKey("llm_advanced")
    val LLM_MAX_NEW_TOKENS = intPreferencesKey("llm_max_new_tokens")
    val LLM_GENERATION_TIMEOUT = intPreferencesKey("llm_generation_timeout")
    val LLM_PROMPT_PACK = stringPreferencesKey("llm_prompt_pack")
}

/**
 * The persisted llm* pair shared by [Settings.Snapshot] reads and the
 * [writeSnapshotWrites] round trip. Kept as one reader so the write encoding
 * and the restore semantics cannot drift apart (SettingsUpdateWriteSetTest).
 */
internal data class LlmSnapshotFields(
    val enabled: Boolean,
    val computeMode: ComputeMode,
    val coresMask: Long,
    val threads: Int,
    val offloadLayers: Int,
    val externalMode: Boolean,
    val externalUrl: String,
    val externalApiKey: String,
    val externalModel: String,
    val cloudChatter: Boolean = false,
    val banter: Boolean,
    val ambience: Boolean,
    val modelId: String,
    val advanced: Boolean,
    val maxNewTokens: Int,
    val generationTimeout: Int,
    val promptPackJson: String,
)

internal fun Preferences.readLlmSnapshotFields(): LlmSnapshotFields {
    // `null == 1` is false, so a missing key reads as off without an elvis.
    return LlmSnapshotFields(
        enabled = this[Keys.LLM_ENABLED] == 1,
        computeMode = runCatching { ComputeMode.valueOf(this[Keys.LLM_COMPUTE_MODE] ?: "") }
            .getOrDefault(LlmRuntimePolicy.DEFAULT_COMPUTE_MODE),
        coresMask = LlmRuntimePolicy.normalizeCoresMask(
            this[Keys.LLM_CORES_MASK] ?: LlmRuntimePolicy.DEFAULT_CORES_MASK,
        ),
        threads = LlmRuntimePolicy.normalizeThreads(
            this[Keys.LLM_THREADS] ?: LlmRuntimePolicy.DEFAULT_THREADS,
        ),
        offloadLayers = LlmRuntimePolicy.normalizeOffloadLayers(
            this[Keys.LLM_OFFLOAD_LAYERS] ?: LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS,
        ),
        externalMode = this[Keys.LLM_EXTERNAL_MODE] == 1,
        externalUrl = this[Keys.LLM_EXTERNAL_URL] ?: "",
        externalApiKey = this[Keys.LLM_EXTERNAL_API_KEY] ?: "",
        externalModel = this[Keys.LLM_EXTERNAL_MODEL] ?: "",
        cloudChatter = (this[Keys.LLM_CLOUD_CHATTER] ?: 0) == 1,
        banter = (this[Keys.LLM_BANTER] ?: 1) == 1,
        ambience = this[Keys.LLM_AMBIENCE] == 1,
        modelId = this[Keys.LLM_MODEL_ID] ?: LlmModelRegistry.DEFAULT_MODEL_ID,
        advanced = this[Keys.LLM_ADVANCED] == 1,
        maxNewTokens = LlmRuntimePolicy.normalizeMaxNewTokensOverride(
            this[Keys.LLM_MAX_NEW_TOKENS] ?: 0,
        ),
        generationTimeout = LlmRuntimePolicy.normalizeGenerationTimeoutOverride(
            this[Keys.LLM_GENERATION_TIMEOUT] ?: 0,
        ),
        promptPackJson = this[Keys.LLM_PROMPT_PACK] ?: "",
    )
}

/**
 * The complete `Settings.update()` write-set, extracted so the round trip is
 * testable without an Android context (Settings is otherwise the only
 * context-free moment `update()` has: read snapshot, transform, write every
 * key). Every persisted key is rewritten from the transformed snapshot on
 * every update, so a snapshot field that falls out of this set — or a written
 * encoding the reader cannot restore — silently loses user data on the next
 * unrelated settings write. The queue journal keys (`game_settings_revision`,
 * `game_settings_direct_edit_revisions`) are deliberately absent: only
 * [Settings.mutateGameSettings] and [Settings.journalGameSettingsDirectEdit]
 * may touch them, so no settings rewrite can regress the counter.
 */
internal fun MutablePreferences.writeSnapshotWrites(
    next: Settings.Snapshot,
    display: ClientDisplaySelection,
) {
    this[Keys.DISPLAY_PROFILE] = display.profile.id
    this[Keys.FRAME_CAP] = display.frameCap.fps
    if (next.clientUiScale == null) {
        this.remove(Keys.UI_SCALE)
    } else {
        this[Keys.UI_SCALE] = next.clientUiScale
    }
    this[Keys.DISPLAY_SCHEMA] = 1
    // fps_profile was present before display selection was connected
    // to Wine. Do not reinterpret its FPS_40 default as user intent.
    this.remove(Keys.FPS)
    this[Keys.DXVK_BOX64] = next.box64DxvkPackageId
    // Turning the lane off visibly resets a user-driver selection to
    // Auto (I1: never a launch-time silent swap); toSnapshot keeps
    // enforcing the rule for stale persisted values regardless.
    this[Keys.VULKAN_DRIVER] = if (
        !next.allowUserVulkanDrivers &&
        UserVulkanDriver.isUserId(next.armVulkanDriverId)
    ) VulkanDriverCatalog.AUTO_ID else next.armVulkanDriverId
    this[Keys.ALLOW_USER_VULKAN_DRIVERS] =
        if (next.allowUserVulkanDrivers) 1 else 0
    this[Keys.VULKAN_SELECTION_SCHEMA] = VulkanDriverCatalog.SELECTION_SCHEMA
    this.remove(Keys.VULKAN_MIGRATION_NOTICE)
    this[Keys.RENDERER] = next.armRendererId
    this[Keys.RENDERER_SCHEMA] = ArmClientRendererCatalog.SELECTION_SCHEMA
    // Removed provider choices are not written back. Any settings write
    // completes their migration after reads have resolved Box64.
    this.remove(Keys.PROVIDER)
    this.remove(Keys.DXVK_FEX)
    this[Keys.BOTS] = next.botPopulationTarget
    this[Keys.BOT_PROFILE_ID] = next.botProfileId
    if (next.botSavedPresetId == null) {
        this.remove(Keys.BOT_SAVED_PRESET)
    } else {
        this[Keys.BOT_SAVED_PRESET] = next.botSavedPresetId
    }
    this[Keys.BOT_PRESETS_IMPORTED] = if (next.botPresetsImported) 1 else 0
    this[Keys.BOTS_ADVANCED] = if (next.botAdvancedEnabled) 1 else 0
    this[Keys.BOTS_NEARBY] = next.botAdvanced.nearbyBotLimit
    this[Keys.BOTS_RADIUS] = next.botAdvanced.nearbyRadius
    this[Keys.BOTS_LOGIN_BATCH] = next.botAdvanced.loginBatchSize
    this[Keys.BOTS_MAINTENANCE_BATCH] = next.botAdvanced.maintenanceBatchSize
    this[Keys.BOTS_UPDATE_MS] = next.botAdvanced.updateIntervalMs
    this[Keys.BOTS_TELEPORT_MIN] = next.botAdvanced.teleportMinMinutes
    this[Keys.BOTS_TELEPORT_MAX] = next.botAdvanced.teleportMaxMinutes
    this[Keys.BOTS_ITERATIONS] = next.botAdvanced.iterationsPerTick
    this[Keys.BOTS_P99] = next.botAdvanced.admissionWorldP99Ms
    this[Keys.BOTS_SYNC_LEVEL] = if (next.botAdvanced.syncLevelWithPlayers) 1 else 0
    this[Keys.BOTS_LIMIT_COMBAT] = if (next.botAdvanced.limitCombatActivity) 1 else 0
    this[Keys.BOTS_ACTIVE_PERCENT] = next.botAdvanced.activeBotPercent
    this[Keys.BOTS_AUTO_QUEST] = if (next.botAdvanced.autoDoQuests) 1 else 0
    this[Keys.BOTS_CHAT] = if (next.botAdvanced.allowBotChat) 1 else 0
    this[Keys.BOTS_INVITES] = if (next.botAdvanced.allowPlayerInvites) 1 else 0
    this[Keys.BOTS_GROUP_NEARBY] = if (next.botAdvanced.groupNearby) 1 else 0
    this[Keys.BOTS_WANDER] = if (next.botAdvanced.wanderWhenIdle) 1 else 0
    this[Keys.BOTS_OFF_SPEC] = if (next.botAdvanced.enableOffSpecStrategies) 1 else 0
    this[Keys.SETUP_DONE] = if (next.setupComplete) 1 else 0
    this[Keys.GENERATION] = next.lastActiveGeneration
    this[Keys.INPUT_SAFE_MODE] = if (next.inputSafeMode) 1 else 0
    this[Keys.AUTO_LOGIN_ON_LAUNCH] = if (next.autoLoginOnLaunch) 1 else 0
    this[Keys.AUTO_LOGIN_ADVANCED] = if (next.autoLoginAdvanced) 1 else 0
    val timings = next.autoLoginTimings.normalized()
    this[Keys.AL_POLL_INTERVAL] = timings.pollIntervalMs.toInt()
    this[Keys.AL_STABLE_POLLS] = timings.requiredStablePolls
    this[Keys.AL_LOGIN_SETTLE] = timings.loginUiSettleMs.toInt()
    this[Keys.AL_SESSION_TIMEOUT] = timings.sessionTimeoutMs.toInt()
    this[Keys.AL_DRAIN_POLL] = timings.drainPollMs.toInt()
    this[Keys.AL_INPUT_DRAIN_TIMEOUT] = timings.inputDrainTimeoutMs.toInt()
    this[Keys.AL_IME_DWELL] = timings.imeKeyDwellMs.toInt()
    this[Keys.AL_IME_GAP] = timings.imeKeyGapMs.toInt()
    this[Keys.AL_FIELD_SETTLE] = timings.fieldSettleMs.toInt()
    this[Keys.AL_POINTER_DWELL] = timings.pointerDwellMs.toInt()
    this[Keys.TWEAKS] = next.tweaks.toJson()
    this[Keys.TWEAKS_SCHEMA] = TWEAKS_SCHEMA_VERSION
    this[Keys.GAME_SETTINGS] = next.gameSettings.toJson()
    this[Keys.GAME_SETTINGS_SCHEMA] = 1
    this[Keys.AUDIO_MODE] = next.audioMode.name
    this[Keys.NEARBY_INTERACT_TRIGGER_GUARD_MS] =
        NearbyInteractPolicy.normalizeTriggerGuardMs(next.nearbyInteractTriggerGuardMs)
    this[Keys.WORLD_DEBUG_LOGS] = if (next.worldDebugLogs) 1 else 0
    this[Keys.RUNTIME_MODE] = next.runtimeMode.name
    this[Keys.ALLOW_LAN_PLAYERS] = if (next.allowLanPlayers) 1 else 0
    this[Keys.LLM_ENABLED] = if (next.llmEnabled) 1 else 0
    this[Keys.LLM_COMPUTE_MODE] = next.llmComputeMode.name
    this[Keys.LLM_CORES_MASK] = LlmRuntimePolicy.normalizeCoresMask(next.llmCoresMask)
    this[Keys.LLM_THREADS] = LlmRuntimePolicy.normalizeThreads(next.llmThreads)
    this[Keys.LLM_OFFLOAD_LAYERS] =
        LlmRuntimePolicy.normalizeOffloadLayers(next.llmOffloadLayers)
    this[Keys.LLM_EXTERNAL_MODE] = if (next.llmExternalMode) 1 else 0
    // verbatim, NOT trimmed: a trim here round-trips through the flow and
    // deletes a trailing space under the user's cursor mid-typing; the conf
    // emission path normalizes (trims + validates) at read time instead
    this[Keys.LLM_EXTERNAL_URL] = next.llmExternalUrl
    this[Keys.LLM_EXTERNAL_API_KEY] = next.llmExternalApiKey
    this[Keys.LLM_EXTERNAL_MODEL] = next.llmExternalModel
    this[Keys.LLM_CLOUD_CHATTER] = if (next.llmCloudChatter) 1 else 0
    this[Keys.LLM_BANTER] = if (next.llmBanter) 1 else 0
    this[Keys.LLM_AMBIENCE] = if (next.llmAmbience) 1 else 0
    this[Keys.LLM_MODEL_ID] = next.llmModelId
    this[Keys.LLM_ADVANCED] = if (next.llmAdvanced) 1 else 0
    this[Keys.LLM_MAX_NEW_TOKENS] =
        LlmRuntimePolicy.normalizeMaxNewTokensOverride(next.llmMaxNewTokens)
    this[Keys.LLM_GENERATION_TIMEOUT] =
        LlmRuntimePolicy.normalizeGenerationTimeoutOverride(next.llmGenerationTimeout)
    // verbatim prompt-pack JSON (never trimmed — same mid-typing rule as the
    // external URL fields); resolved via LlmPromptPack.resolve at read time
    this[Keys.LLM_PROMPT_PACK] = next.llmPromptPackJson
}
