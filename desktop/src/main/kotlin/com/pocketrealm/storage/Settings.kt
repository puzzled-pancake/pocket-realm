package com.pocketrealm.storage

import com.pocketrealm.supervisor.RuntimeMode

/**
 * Desktop twin of the Android app's storage/Settings. Carries the
 * Windows-relevant subset: the Wine-era display/renderer/Vulkan fields have
 * no meaning here (the WoW client runs natively), and the LLM lane is
 * EXTERNAL-ONLY (no embedded model manager, no NPU/decode-core fields —
 * the desktop conf emission always takes the external-endpoint branch).
 * Defaults match the Android product's topology invariants — LOCAL mode,
 * LAN off, bots unselected until the user applies a profile in Bots.
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
        val worldDebugLogs: Boolean = false,
        val clientDir: String = "",
        val llmEnabled: Boolean = false,
        val llmExternalUrl: String = "",
        val llmExternalModel: String = "",
        val llmExternalApiKey: String = "",
        val llmCloudChatter: Boolean = false,
        val llmBanter: Boolean = true,
        val llmAmbience: Boolean = false,
        val llmMaxNewTokens: Int = 0,
        val llmGenerationTimeout: Int = 0,
    ) {
        companion object {
            fun fromJson(text: String): Snapshot {
                val value = org.json.JSONObject(text)
                return Snapshot(
                    // Degrade per field (the Android twin's posture): one
                    // unknown enum value must not throw and reset the
                    // WHOLE snapshot to defaults on the next load.
                    runtimeMode = runCatching {
                        RuntimeMode.valueOf(value.optString("runtimeMode", RuntimeMode.LOCAL.name))
                    }.getOrDefault(RuntimeMode.LOCAL),
                    allowLanPlayers = value.optBoolean("allowLanPlayers", false),
                    setupComplete = value.optBoolean("setupComplete", false),
                    botProfileId = value.optString("botProfileId", ""),
                    botPopulationTarget = value.optInt("botPopulationTarget", 0),
                    botSavedPresetId = if (value.isNull("botSavedPresetId")) null
                    else value.optString("botSavedPresetId").ifEmpty { null },
                    autoLoginOnLaunch = value.optBoolean("autoLoginOnLaunch", false),
                    lastActiveGeneration = value.optInt("lastActiveGeneration", 0),
                    worldDebugLogs = value.optBoolean("worldDebugLogs", false),
                    clientDir = value.optString("clientDir", ""),
                    llmEnabled = value.optBoolean("llmEnabled", false),
                    llmExternalUrl = value.optString("llmExternalUrl", ""),
                    llmExternalModel = value.optString("llmExternalModel", ""),
                    llmExternalApiKey = value.optString("llmExternalApiKey", ""),
                    llmCloudChatter = value.optBoolean("llmCloudChatter", false),
                    llmBanter = value.optBoolean("llmBanter", true),
                    llmAmbience = value.optBoolean("llmAmbience", false),
                    llmMaxNewTokens = value.optInt("llmMaxNewTokens", 0),
                    llmGenerationTimeout = value.optInt("llmGenerationTimeout", 0),
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
            .put("worldDebugLogs", worldDebugLogs)
            .put("clientDir", clientDir)
            .put("llmEnabled", llmEnabled)
            .put("llmExternalUrl", llmExternalUrl)
            .put("llmExternalModel", llmExternalModel)
            .put("llmExternalApiKey", llmExternalApiKey)
            .put("llmCloudChatter", llmCloudChatter)
            .put("llmBanter", llmBanter)
            .put("llmAmbience", llmAmbience)
            .put("llmMaxNewTokens", llmMaxNewTokens)
            .put("llmGenerationTimeout", llmGenerationTimeout)
            .toString()
    }
}
