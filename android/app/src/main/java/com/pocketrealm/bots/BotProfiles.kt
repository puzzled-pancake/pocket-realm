package com.pocketrealm.bots

/** A measured bot tier. Values are product policy, not mutable upstream defaults. */
data class BotProfile(
    val id: String,
    val selectedTarget: Int,
    val minimumOnline: Int,
    val maximumOnline: Int,
    val maximumAltBots: Int,
    val generationBatchSize: Int,
    val generationYieldMs: Long,
    val accountPrefix: String,
    val accountCount: Int,
    val loginBatchSize: Int,
    val admission: BotAdmissionLimits,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")))
        require(minimumOnline in 0..selectedTarget && selectedTarget <= maximumOnline)
        require(maximumOnline <= 100)
        require(maximumAltBots in 0..8)
        require(generationBatchSize in 1..10 && generationYieldMs in 0..5_000)
        require(accountPrefix.matches(Regex("[A-Z][A-Z0-9]{2,7}")))
        require(accountCount in 1..20 && loginBatchSize in 1..10)
    }

    /**
     * Emit only reviewed keys. In particular, network command/LLM egress and
     * battleground/arena/guild growth are disabled for the first mobile tier.
     * Auction-house automation remains disabled; the core BUILD_AHBOT target is excluded.
     */
    fun playerbotConfig(): String = """
        AiPlayerbot.Enabled = 1
        AiPlayerbot.RandomBotAutologin = 1
        AiPlayerbot.RandomBotLoginAtStartup = 1
        AiPlayerbot.RandomBotAutoCreate = 1
        PocketRealm.GenerationBatchSize = $generationBatchSize
        PocketRealm.GenerationYieldMs = $generationYieldMs
        AiPlayerbot.MinRandomBots = $minimumOnline
        AiPlayerbot.MaxRandomBots = $maximumOnline
        AiPlayerbot.RandomBotAccountPrefix = $accountPrefix
        AiPlayerbot.RandomBotAccountCount = $accountCount
        AiPlayerbot.DeleteRandomBotAccounts = 0
        AiPlayerbot.RandomBotRandomPassword = 1
        AiPlayerbot.RandomBotUpdateInterval = 1000
        AiPlayerbot.RandomBotsMaxLoginsPerInterval = $loginBatchSize
        AiPlayerbot.RandomBotsPerInterval = $loginBatchSize
        AiPlayerbot.RandomBotTimedLogout = 1
        AiPlayerbot.RandomBotTimedOffline = 0
        AiPlayerbot.AllowMultiAccountAltBots = 0
        AiPlayerbot.AllowGuildBots = 0
        AiPlayerbot.RandomBotJoinLfg = 0
        AiPlayerbot.RandomBotJoinBG = 0
        AiPlayerbot.RandomBotAutoJoinBG = 0
        AiPlayerbot.RandomBotFormGuild = 0
        AiPlayerbot.RandomBotGuildCount = 0
        AiPlayerbot.RandomBotArenaTeamCount = 0
        AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0
        AiPlayerbot.BotCheckAllAuctionListings = 0
        AiPlayerbot.AutoDoQuests = 0
        AiPlayerbot.CommandServerPort = 0
        AiPlayerbot.PerfMonEnabled = 0
        AiPlayerbot.LLMEnabled = 0
        AiPlayerbot.ShowProgressBars = 0
    """.trimIndent() + "\n"
}

data class BotAdmissionLimits(
    val maxWorldP99Ms: Int,
    val minFreeMemoryMiB: Long,
    val minFreeStorageMiB: Long,
    val performanceWarmupMs: Long,
    val reduceStep: Int,
    val increaseStep: Int,
    val healthyRampMs: Long,
    val changeCooldownMs: Long,
) {
    init {
        require(maxWorldP99Ms > 0 && minFreeMemoryMiB > 0 && minFreeStorageMiB > 0)
        require(performanceWarmupMs >= 0)
        require(reduceStep > 0 && increaseStep > 0)
        require(healthyRampMs >= changeCooldownMs && changeCooldownMs >= 10_000)
    }
}

object BotProfiles {
    /** Report section 13 B1 / SOAK-25 candidate. It is not a default until its soak passes. */
    val LOW_25 = BotProfile(
        id = "mobile-low-b1-25-v1",
        selectedTarget = 25,
        minimumOnline = 20,
        maximumOnline = 25,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 250,
        accountPrefix = "PRB13",
        accountCount = 3,
        loginBatchSize = 5,
        admission = BotAdmissionLimits(
            maxWorldP99Ms = 250,
            minFreeMemoryMiB = 768,
            minFreeStorageMiB = 2_048,
            // The native percentile window includes synchronous startup and
            // initial bot-login work. Give that bounded bootstrap traffic time
            // to age out before treating latency as steady-state overload.
            performanceWarmupMs = 3 * 60_000L,
            reduceStep = 5,
            increaseStep = 5,
            healthyRampMs = 5 * 60_000L,
            changeCooldownMs = 10_000L,
        ),
    )

    private val profiles = listOf(LOW_25).associateBy(BotProfile::id)

    fun find(id: String): BotProfile? = profiles[id]
    fun require(id: String): BotProfile = requireNotNull(find(id)) { "unknown bot profile: $id" }
    fun ids(): Set<String> = profiles.keys
}
