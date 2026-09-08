package com.pocketrealm.storage

import com.pocketrealm.supervisor.RuntimeMode

/**
 * Desktop twin of the Android app's storage/Settings. Carries only the
 * Windows-relevant subset: the Wine-era display/renderer/Vulkan fields have
 * no meaning here (the WoW client runs natively). Defaults match the Android
 * product's topology invariants — LOCAL mode, LAN off — which the shared
 * RuntimeTopologyTest pins on both platforms.
 */
object Settings {
    data class Snapshot(
        val runtimeMode: RuntimeMode = RuntimeMode.LOCAL,
        val allowLanPlayers: Boolean = false,
        val setupComplete: Boolean = false,
        val botProfileId: String = "",
        val botPopulationTarget: Int = 0,
        val botSavedPresetId: String? = null,
        val autoLoginOnLaunch: Boolean = false,
        val lastActiveGeneration: Int = 0,
    ) {
        companion object {
            fun fromJson(text: String): Snapshot {
                val value = org.json.JSONObject(text)
                return Snapshot(
                    runtimeMode = RuntimeMode.valueOf(value.optString("runtimeMode", RuntimeMode.LOCAL.name)),
                    allowLanPlayers = value.optBoolean("allowLanPlayers", false),
                    setupComplete = value.optBoolean("setupComplete", false),
                    botProfileId = value.optString("botProfileId", ""),
                    botPopulationTarget = value.optInt("botPopulationTarget", 0),
                    botSavedPresetId = if (value.isNull("botSavedPresetId")) null
                        else value.optString("botSavedPresetId").ifEmpty { null },
                    autoLoginOnLaunch = value.optBoolean("autoLoginOnLaunch", false),
                    lastActiveGeneration = value.optInt("lastActiveGeneration", 0),
                )
            }
        }

        fun toJson(): String = org.json.JSONObject()
            .put("runtimeMode", runtimeMode.name)
            .put("allowLanPlayers", allowLanPlayers)
            .put("setupComplete", setupComplete)
            .put("botProfileId", botProfileId)
            .put("botPopulationTarget", botPopulationTarget)
            .put("botSavedPresetId", botSavedPresetId)
            .put("autoLoginOnLaunch", autoLoginOnLaunch)
            .put("lastActiveGeneration", lastActiveGeneration)
            .toString()
    }
}
