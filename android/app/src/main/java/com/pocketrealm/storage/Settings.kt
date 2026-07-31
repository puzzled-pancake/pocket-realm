package com.pocketrealm.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    enum class Renderer { DXVK, WINE_D3D }
    enum class RuntimeProvider { BOX64, FEX }

    data class Snapshot(
        val fpsProfile: FpsProfile = FpsProfile.FPS_40,
        val renderer: Renderer = Renderer.DXVK,
        val provider: RuntimeProvider = RuntimeProvider.BOX64,
        val botPopulationTarget: Int = 400,
        val setupComplete: Boolean = false,
        val lastActiveGeneration: Int = 0,
    )

    val flow: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun update(transform: (Snapshot) -> Snapshot) {
        store.edit { prefs ->
            val current = prefs.toSnapshot()
            val next = transform(current)
            prefs[Keys.FPS] = next.fpsProfile.name
            prefs[Keys.RENDERER] = next.renderer.name
            prefs[Keys.PROVIDER] = next.provider.name
            prefs[Keys.BOTS] = next.botPopulationTarget
            prefs[Keys.SETUP_DONE] = if (next.setupComplete) 1 else 0
            prefs[Keys.GENERATION] = next.lastActiveGeneration
        }
    }

    private object Keys {
        val FPS = stringPreferencesKey("fps_profile")
        val RENDERER = stringPreferencesKey("renderer")
        val PROVIDER = stringPreferencesKey("provider")
        val BOTS = intPreferencesKey("bot_population_target")
        val SETUP_DONE = intPreferencesKey("setup_complete")
        val GENERATION = intPreferencesKey("last_active_generation")
    }

    private fun Preferences.toSnapshot() = Snapshot(
        fpsProfile = runCatching { FpsProfile.valueOf(this[Keys.FPS] ?: "") }.getOrDefault(FpsProfile.FPS_40),
        renderer = runCatching { Renderer.valueOf(this[Keys.RENDERER] ?: "") }.getOrDefault(Renderer.DXVK),
        provider = runCatching { RuntimeProvider.valueOf(this[Keys.PROVIDER] ?: "") }.getOrDefault(RuntimeProvider.BOX64),
        botPopulationTarget = this[Keys.BOTS] ?: 400,
        setupComplete = (this[Keys.SETUP_DONE] ?: 0) == 1,
        lastActiveGeneration = this[Keys.GENERATION] ?: 0,
    )
}
