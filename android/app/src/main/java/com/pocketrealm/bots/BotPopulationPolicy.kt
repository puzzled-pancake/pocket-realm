package com.pocketrealm.bots

import kotlin.math.ceil

/**
 * Bot population bounds derived from the pinned CMaNGOS/Playerbots sources
 * (native/playerbots @ 1abeac6), not from historical Android UI numbers.
 *
 * Verified upstream facts:
 *  - `AiPlayerbot.MinRandomBots`, `AiPlayerbot.MaxRandomBots`, and
 *    `AiPlayerbot.RandomBotAccountCount` are parsed as uint32 with no explicit
 *    upstream hard maximum (playerbot/PlayerbotAIConfig.cpp:212-213,501).
 *  - Classic bot accounts hold at most **9** bot characters each
 *    (playerbot/RandomPlayerbotFactory.cpp: `maxAllowed = 9 - count` on the
 *    non-MANGOSBOT_TWO path), so a valid pool invariant is
 *    `RandomBotAccountCount * 9 >= MaxRandomBots`.
 *  - Account numbering, character guids, and session counts are uint32; the
 *    app carries every one of them as a Kotlin [Int], so the arithmetic
 *    ceiling is Int.MAX_VALUE and no 16-bit truncation exists on the path.
 *
 * [MAX_SUPPORTED_TARGET] is therefore a documented PocketRealm device bound
 * (~1,340 auto-provisioned accounts, ~10k character records), orders of
 * magnitude above any realistic handheld population. It deliberately replaces
 * the former arbitrary application caps (25..700 advanced slider window and
 * the 1,500 profile ceiling), which were UI policy, not engine limits.
 */
object BotPopulationPolicy {
    /** Classic characters per bot account, per the pinned RandomPlayerbotFactory. */
    const val CHARACTERS_PER_BOT_ACCOUNT = 9

    /** Lowest target the custom editor accepts. */
    const val MIN_CUSTOM_TARGET = 10

    /**
     * Practical supported ceiling for any custom population. Not an engine
     * limit (the engine has none below uint32); a device-reality bound that
     * keeps auto-provisioned account pools (~1.2k accounts) and character
     * storage sane on a handheld.
     */
    const val MAX_SUPPORTED_TARGET = 10_000

    /** Highest population offered as a curated built-in experience preset. */
    const val MAX_BUILT_IN_TARGET = 600

    /** Upper bound for an auto-provisioned account pool. */
    const val MAX_ACCOUNTS = 2_000

    /** Account-pool headroom: +20% over the required pool, rounded up. */
    fun allocatedAccounts(target: Int): Int {
        validateTarget(target)
        val required = requiredAccounts(target)
        return (required + ceil(required / 5.0).toInt()).coerceAtMost(MAX_ACCOUNTS)
    }

    /** Minimum accounts whose combined character capacity covers [target]. */
    fun requiredAccounts(target: Int): Int {
        validateTarget(target)
        return ceil(target / CHARACTERS_PER_BOT_ACCOUNT.toDouble()).toInt()
    }

    fun capacityFor(accounts: Int): Int = accounts * CHARACTERS_PER_BOT_ACCOUNT

    fun validateTarget(target: Int) {
        require(target in MIN_CUSTOM_TARGET..MAX_SUPPORTED_TARGET) {
            "bot target $target outside supported range $MIN_CUSTOM_TARGET..$MAX_SUPPORTED_TARGET"
        }
    }

    /**
     * Relationship rules shared by every custom population shape. Mirrors the
     * engine contract: MinRandomBots <= effective target <= MaxRandomBots and
     * the ramp starts between the floor and the target.
     */
    fun validatePopulation(minimumOnline: Int, initialTarget: Int, target: Int, maximumOnline: Int) {
        validateTarget(target)
        require(minimumOnline in 0..target) { "minimum online $minimumOnline above target $target" }
        require(maximumOnline in target..MAX_SUPPORTED_TARGET) {
            "maximum online $maximumOnline below target or above supported ceiling"
        }
        require(initialTarget in minimumOnline..target) {
            "initial target $initialTarget outside $minimumOnline..$target"
        }
        require(requiredAccounts(maximumOnline) <= MAX_ACCOUNTS) {
            "account pool for $maximumOnline bots exceeds the supported account ceiling"
        }
    }
}
