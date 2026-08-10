package com.pocketrealm.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.RendererPackageCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "pocket_settings")

/**
 * Persistent, generation-independent app settings (the wizard/advanced screens
 * write here). Generation-managed settings (runtime tuples, addon profiles,
 * visual overlays) are NOT stored here — they live under [StorageRoots.runtime]
 * as versioned, rollbackable generations per DECISIONS.md #27.
 */
class Settings(private val context: Context) {

    private val store = context.settingsStore

    enum class FpsProfile(val hz: Int) { FPS_30(30), FPS_40(40), FPS_60(60) }
    enum class Renderer { DXVK, OPENGL }
    enum class RuntimeProvider { BOX64, FEX }

    data class Snapshot(
        val fpsProfile: FpsProfile = FpsProfile.FPS_40,
        val renderer: Renderer = Renderer.DXVK,
        val provider: RuntimeProvider = RuntimeProvider.BOX64,
        val box64DxvkPackageId: String = RendererPackageCatalog.BOX64_DEFAULT,
        val fexDxvkPackageId: String = RendererPackageCatalog.FEX_DEFAULT,
        val rendererSelectionNotice: String? = null,
        val botPopulationTarget: Int = 160,
        val botAdvancedEnabled: Boolean = false,
        val botAdvanced: BotAdvancedSettings = BotAdvancedSettings(),
        val setupComplete: Boolean = false,
        val lastActiveGeneration: Int = 0,
        val inputSafeMode: Boolean = false,
    ) {
        fun selectedDxvkPackageId(): String = when (provider) {
            RuntimeProvider.BOX64 -> box64DxvkPackageId
            RuntimeProvider.FEX -> fexDxvkPackageId
        }
    }

    val flow: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun update(transform: (Snapshot) -> Snapshot) {
        store.edit { prefs ->
            val current = prefs.toSnapshot()
            val next = transform(current)
            prefs[Keys.FPS] = next.fpsProfile.name
            prefs[Keys.RENDERER] = next.renderer.name
            prefs[Keys.PROVIDER] = next.provider.name
            prefs[Keys.DXVK_BOX64] = next.box64DxvkPackageId
            prefs[Keys.DXVK_FEX] = next.fexDxvkPackageId
            prefs[Keys.BOTS] = next.botPopulationTarget
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
            prefs[Keys.SETUP_DONE] = if (next.setupComplete) 1 else 0
            prefs[Keys.GENERATION] = next.lastActiveGeneration
            prefs[Keys.INPUT_SAFE_MODE] = if (next.inputSafeMode) 1 else 0
        }
    }

    private object Keys {
        val FPS = stringPreferencesKey("fps_profile")
        val RENDERER = stringPreferencesKey("renderer")
        val PROVIDER = stringPreferencesKey("provider")
        val DXVK_BOX64 = stringPreferencesKey("dxvk_box64_package")
        val DXVK_FEX = stringPreferencesKey("dxvk_fex_package")
        val BOTS = intPreferencesKey("bot_population_target")
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
        val SETUP_DONE = intPreferencesKey("setup_complete")
        val GENERATION = intPreferencesKey("last_active_generation")
        val INPUT_SAFE_MODE = intPreferencesKey("input_safe_mode")
    }

    private fun Preferences.toSnapshot(): Snapshot {
        val target = ((((this[Keys.BOTS] ?: 160) + 12) / 25) * 25).coerceIn(25, 700)
        val defaults = BotAdvancedSettings.fromProfile(BotProfiles.forRequestedTarget(target))
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
            )
        }.getOrDefault(defaults).takeIf { it.nearbyBotLimit <= target } ?: defaults
        val provider = runCatching { RuntimeProvider.valueOf(this[Keys.PROVIDER] ?: "") }
            .getOrDefault(RuntimeProvider.BOX64)
        val requestedBox64 = this[Keys.DXVK_BOX64]
        val requestedFex = this[Keys.DXVK_FEX]
        val box64Package = RendererPackageCatalog.normalize(
            ArmTranslationBackend.BOX64, requestedBox64,
        )
        val fexPackage = RendererPackageCatalog.normalize(
            ArmTranslationBackend.FEX, requestedFex,
        )
        val selectedRequested = if (provider == RuntimeProvider.FEX) requestedFex else requestedBox64
        val selectedResolved = if (provider == RuntimeProvider.FEX) fexPackage else box64Package
        val selectionNotice = selectedRequested?.takeIf { it != selectedResolved }?.let {
            "The saved DXVK package is unavailable for ${provider.name.lowercase()}; " +
                "using $selectedResolved."
        }
        return Snapshot(
        fpsProfile = runCatching { FpsProfile.valueOf(this[Keys.FPS] ?: "") }.getOrDefault(FpsProfile.FPS_40),
        renderer = when (val stored = this[Keys.RENDERER]) {
            // Migrate the old label. The actual fallback is now the client's
            // native OpenGL mode, not WineD3D's Direct3D implementation.
            "WINE_D3D" -> Renderer.OPENGL
            else -> runCatching { Renderer.valueOf(stored ?: "") }
                .getOrDefault(Renderer.DXVK)
        },
        provider = provider,
        box64DxvkPackageId = box64Package,
        fexDxvkPackageId = fexPackage,
        rendererSelectionNotice = selectionNotice,
        botPopulationTarget = target,
        botAdvancedEnabled = (this[Keys.BOTS_ADVANCED] ?: 0) == 1,
        botAdvanced = advanced,
        setupComplete = (this[Keys.SETUP_DONE] ?: 0) == 1,
        lastActiveGeneration = this[Keys.GENERATION] ?: 0,
        inputSafeMode = (this[Keys.INPUT_SAFE_MODE] ?: 0) == 1,
        )
    }
}
