package com.pocketrealm.bots

/**
 * Single resolution rule for "which bot profile does the app launch with".
 * Priority: explicitly selected saved preset (usr5, if still resolvable) →
 * legacy adv4 advanced tuning → selected built-in profile → legacy target
 * migration. Falls back to the recommended default rather than crashing when
 * a saved preset was deleted.
 */
object BotSelection {

    data class Selection(
        val profile: BotProfile,
        /** Saved preset backing this selection, when one is active. */
        val savedPreset: BotPresetStore.SavedPreset?,
        /** True when the saved preset selection was unresolvable and fell back. */
        val savedPresetMissing: Boolean,
    )

    fun resolve(
        savedPresetId: String?,
        advancedEnabled: Boolean,
        advancedTarget: Int,
        advanced: BotAdvancedSettings,
        profileId: String,
    ): Selection {
        if (savedPresetId != null) {
            val store = BotCustomPresets.store()
            if (store != null) {
                val preset = store.presets.value.firstOrNull { it.id == savedPresetId }
                if (preset != null) {
                    return Selection(
                        profile = preset.configuration.resolve(
                            preset.identity(),
                            preset.name,
                            preset.basePresetId,
                        ),
                        savedPreset = preset,
                        savedPresetMissing = false,
                    )
                }
            }
            val fallback = fallback(advancedEnabled, advancedTarget, advanced, profileId)
            return Selection(fallback, null, savedPresetMissing = true)
        }
        if (advancedEnabled) {
            return Selection(
                BotProfiles.advanced(advancedTarget, advanced),
                savedPreset = null,
                savedPresetMissing = false,
            )
        }
        return Selection(
            fallback(advancedEnabled, advancedTarget, advanced, profileId),
            savedPreset = null,
            savedPresetMissing = false,
        )
    }

    private fun fallback(
        advancedEnabled: Boolean,
        advancedTarget: Int,
        advanced: BotAdvancedSettings,
        profileId: String,
    ): BotProfile {
        if (advancedEnabled) return BotProfiles.advanced(advancedTarget, advanced)
        return BotProfiles.find(profileId)
            ?: BotProfiles.migrateLegacyTarget(advancedTarget)
    }
}
