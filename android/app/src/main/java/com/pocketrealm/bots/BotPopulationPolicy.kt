package com.pocketrealm.bots

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import kotlin.math.ceil

/**
 * Bot population bounds derived from the pinned CMaNGOS/Playerbots sources
 * (native/playerbots @ 1abeac6).
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
 * magnitude above any realistic handheld population.
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

    /**
     * G3-10: the per-flavor population values. The VANILLA row IS today's
     * constants — [BotPopulationPolicyVanillaRowTest] pins the equality, so a
     * per-flavor table (H3R2-1) can only ever add rows, never retune the
     * classic lane in place.
     */
    data class PopulationRow(
        val charactersPerBotAccount: Int,
        val minCustomTarget: Int,
        val maxSupportedTarget: Int,
        val maxBuiltInTarget: Int,
        val maxAccounts: Int,
    )

    val VANILLA_ROW: PopulationRow = PopulationRow(
        charactersPerBotAccount = CHARACTERS_PER_BOT_ACCOUNT,
        minCustomTarget = MIN_CUSTOM_TARGET,
        maxSupportedTarget = MAX_SUPPORTED_TARGET,
        maxBuiltInTarget = MAX_BUILT_IN_TARGET,
        maxAccounts = MAX_ACCOUNTS,
    )

    /**
     * H3R2-1 (census lanes item 16): the TBC row mirrors
     * `schemas/flavor.json` `bot_policy` — `characters_per_account: 9`
     * (MANGOSBOT_ONE takes the pinned factory's non-TWO path,
     * `maxAllowed = 9 - count`). Target/account bounds are the shared
     * PocketRealm device bounds (see [MAX_SUPPORTED_TARGET]), not engine
     * facts, so they match the vanilla row until a measured reason diverges.
     */
    val TBC_ROW: PopulationRow = PopulationRow(
        charactersPerBotAccount = CHARACTERS_PER_BOT_ACCOUNT,
        minCustomTarget = MIN_CUSTOM_TARGET,
        maxSupportedTarget = MAX_SUPPORTED_TARGET,
        maxBuiltInTarget = MAX_BUILT_IN_TARGET,
        maxAccounts = MAX_ACCOUNTS,
    )

    /**
     * H3R2-1 (census lanes item 16): the WotLK row. The engine's
     * MANGOSBOT_TWO path allows **10** bots per account
     * (RandomPlayerbotFactory.cpp: `maxAllowed = 10 - count`), but the
     * manifest pins the headroom-conservative `characters_per_account: 9`
     * ("player character occupies one slot; client UI cap is 10 per
     * realm") — this row mirrors the manifest value, deliberately under
     * the engine ceiling.
     */
    val WOTLK_ROW: PopulationRow = PopulationRow(
        charactersPerBotAccount = CHARACTERS_PER_BOT_ACCOUNT,
        minCustomTarget = MIN_CUSTOM_TARGET,
        maxSupportedTarget = MAX_SUPPORTED_TARGET,
        maxBuiltInTarget = MAX_BUILT_IN_TARGET,
        maxAccounts = MAX_ACCOUNTS,
    )

    /** The population policy for [flavor]; every declared flavor has a row,
     *  unknown ids still fail loud (add-only: a new flavor adds a row here,
     *  it never retunes an existing one — G3-10). */
    fun forFlavor(flavor: ClientFlavor): PopulationRow = when (flavor.flavorId) {
        ClientFlavors.VANILLA.flavorId -> VANILLA_ROW
        ClientFlavors.TBC.flavorId -> TBC_ROW
        ClientFlavors.WOTLK.flavorId -> WOTLK_ROW
        else -> error("bot population policy for flavor \"${flavor.flavorId}\" is not defined")
    }

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
