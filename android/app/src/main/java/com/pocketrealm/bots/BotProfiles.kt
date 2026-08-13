package com.pocketrealm.bots

import java.security.MessageDigest

/** A measured bot tier. Values are product policy, not mutable upstream defaults. */
data class BotProfile(
    val id: String,
    val displayName: String,
    val summary: String,
    val userSelectable: Boolean = true,
    val selectedTarget: Int,
    val minimumOnline: Int,
    val maximumOnline: Int,
    val initialTarget: Int = selectedTarget,
    val startupIncreaseStep: Int = selectedTarget,
    val startupRampIntervalMs: Long = 0L,
    val activationBatchSize: Int = minOf(maximumOnline, 64),
    val maximumAltBots: Int,
    val generationBatchSize: Int,
    val generationYieldMs: Long,
    val accountPrefix: String,
    val accountCount: Int,
    val loginBatchSize: Int,
    val maintenanceBatchSize: Int = loginBatchSize,
    val randomBotUpdateIntervalMs: Int = 1_500,
    val iterationsPerTick: Int = 10,
    val loginAtStartup: Boolean = false,
    val loginWithPlayer: Boolean = true,
    val forceActiveWhenNearPlayer: Boolean = false,
    val nearPlayerTeleportMaxAmount: Int = 0,
    val nearPlayerTeleportRadius: Int = 0,
    val teleportMinIntervalSeconds: Int = 7_200,
    val teleportMaxIntervalSeconds: Int = 172_800,
    val syncLevelWithPlayers: Boolean = false,
    val syncLevelMaxAbove: Int = 3,
    val syncLevelNoPlayer: Int = 1,
    val randomBotMaxLevelChance: Float = 0.15f,
    val randomizeMinIntervalSeconds: Int = 7_200,
    val randomizeMaxIntervalSeconds: Int = 86_400,
    val limitCombatActivity: Boolean = false,
    val activeBotPercent: Int = 5,
    val autoDoQuests: Boolean = false,
    val allowBotChat: Boolean = false,
    val allowPlayerInvites: Boolean = true,
    val groupNearby: Boolean = true,
    val wanderWhenIdle: Boolean = true,
    val enableOffSpecStrategies: Boolean = true,
    val admission: BotAdmissionLimits,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")))
        require(minimumOnline in 0..selectedTarget && selectedTarget <= maximumOnline)
        require(initialTarget in minimumOnline..selectedTarget)
        require(startupIncreaseStep in 1..maximumOnline)
        require(startupRampIntervalMs in 0..30 * 60_000L)
        require(activationBatchSize in 1..64)
        // The ordinary mobile tier remains deliberately small. Higher values
        // are reserved for named, explicitly selected stress profiles and are
        // still bounded by the Settings population contract.
        require(maximumOnline <= 1_500)
        require(maximumAltBots in 0..8)
        require(generationBatchSize in 1..10 && generationYieldMs in 0..5_000)
        require(accountPrefix.matches(Regex("[A-Z][A-Z0-9]{2,7}")))
        require(accountCount in 1..200 && loginBatchSize in 1..10)
        require(accountCount * 9 >= maximumOnline) { "account pool cannot supply maximum bot target" }
        require(maintenanceBatchSize in 1..64)
        require(randomBotUpdateIntervalMs in 500..60_000)
        require(iterationsPerTick in 1..20)
        require(nearPlayerTeleportMaxAmount in 0..100)
        require(nearPlayerTeleportRadius in 0..1_000)
        require((nearPlayerTeleportMaxAmount == 0) == (nearPlayerTeleportRadius == 0))
        require(teleportMinIntervalSeconds in 60..172_800)
        require(teleportMaxIntervalSeconds in teleportMinIntervalSeconds..172_800)
        require(syncLevelMaxAbove in 0..10 && syncLevelNoPlayer in 1..60)
        require(randomBotMaxLevelChance in 0f..1f)
        require(randomizeMinIntervalSeconds in 600..1_209_600)
        require(randomizeMaxIntervalSeconds in randomizeMinIntervalSeconds..1_209_600)
        require(activeBotPercent in 1..20)
    }

    /**
     * Emit only reviewed keys. In particular, network command/LLM egress and
     * battleground/arena/guild growth are disabled for the first mobile tier.
     * Auction-house automation remains disabled; the core BUILD_AHBOT target is excluded.
     */
    fun playerbotConfig(): String = """
        AiPlayerbot.Enabled = 1
        AiPlayerbot.RandomBotAutologin = 1
        AiPlayerbot.RandomBotLoginAtStartup = ${if (loginAtStartup) 1 else 0}
        AiPlayerbot.RandomBotLoginWithPlayer = ${if (loginWithPlayer) 1 else 0}
        AiPlayerbot.RandomBotAutoCreate = 1
        PocketRealm.GenerationBatchSize = $generationBatchSize
        PocketRealm.GenerationYieldMs = $generationYieldMs
        PocketRealm.ActivationBatchSize = $activationBatchSize
        AiPlayerbot.MinRandomBots = $minimumOnline
        AiPlayerbot.MaxRandomBots = $maximumOnline
        AiPlayerbot.RandomBotMinLevel = 1
        AiPlayerbot.RandomBotMaxLevel = 60
        AiPlayerbot.RandomBotMaps = 0,1
        AiPlayerbot.RandomBotAccountPrefix = $accountPrefix
        AiPlayerbot.RandomBotAccountCount = $accountCount
        AiPlayerbot.DeleteRandomBotAccounts = 0
        AiPlayerbot.RandomBotRandomPassword = 1
        AiPlayerbot.RandomBotUpdateInterval = $randomBotUpdateIntervalMs
        AiPlayerbot.RandomBotsMaxLoginsPerInterval = $loginBatchSize
        AiPlayerbot.RandomBotsPerInterval = $maintenanceBatchSize
        AiPlayerbot.DisableBotOptimizations = 0
        AiPlayerbot.DisableActivityPriorities = 0
        AiPlayerbot.ForceActiveWhenNearPlayer = ${if (forceActiveWhenNearPlayer) 1 else 0}
        AiPlayerbot.GuildOrderAlwaysActive = 0
        AiPlayerbot.LimitCombatActivity = ${if (limitCombatActivity) 1 else 0}
        AiPlayerbot.botActiveAlone = $activeBotPercent
        AiPlayerbot.DiffWithPlayer = 100
        AiPlayerbot.DiffEmpty = 200
        AiPlayerbot.EnableMinimalMove = 1
        AiPlayerbot.IterationsPerTick = $iterationsPerTick
        AiPlayerbot.ReactDelay = 100
        AiPlayerbot.PassiveDelay = 10000
        AiPlayerbot.RpgDelay = 10000
        AiPlayerbot.EnableRandomTeleports = ${if (nearPlayerTeleportMaxAmount > 0) 1 else 0}
        AiPlayerbot.RandomBotTeleportNearPlayer = ${if (nearPlayerTeleportMaxAmount > 0) 1 else 0}
        AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount = $nearPlayerTeleportMaxAmount
        AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = $nearPlayerTeleportRadius
        AiPlayerbot.RandomBotTeleportTeleportMinInterval = $teleportMinIntervalSeconds
        AiPlayerbot.RandomBotTeleportTeleportMaxInterval = $teleportMaxIntervalSeconds
        AiPlayerbot.SyncLevelWithPlayers = ${if (syncLevelWithPlayers) 1 else 0}
        AiPlayerbot.SyncLevelMaxAbove = $syncLevelMaxAbove
        AiPlayerbot.SyncLevelNoPlayer = $syncLevelNoPlayer
        AiPlayerbot.InstantRandomize = 1
        AiPlayerbot.RandomBotMaxLevelChance = $randomBotMaxLevelChance
        AiPlayerbot.MinRandomBotRandomizeTime = $randomizeMinIntervalSeconds
        AiPlayerbot.MaxRandomRandomizeTime = $randomizeMaxIntervalSeconds
        AiPlayerbot.RandomBotTimedLogout = 1
        AiPlayerbot.RandomBotTimedOffline = 0
        AiPlayerbot.AllowMultiAccountAltBots = 0
        AiPlayerbot.AllowGuildBots = 0
        AiPlayerbot.RandomBotJoinLfg = 0
        AiPlayerbot.RandomBotJoinBG = 0
        AiPlayerbot.RandomBotAutoJoinBG = 0
        AiPlayerbot.PreQuests = 0
        AiPlayerbot.RandomGearUpgradeEnabled = 0
        AiPlayerbot.RandomBotFormGuild = 0
        AiPlayerbot.RandomBotGuildCount = 0
        AiPlayerbot.RandomBotArenaTeamCount = 0
        AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0
        AiPlayerbot.BotCheckAllAuctionListings = 0
        AiPlayerbot.AsyncBotLogin = 0
        AiPlayerbot.PreloadHolders = 0
        AiPlayerbot.AutoDoQuests = ${if (autoDoQuests) 1 else 0}
        AiPlayerbot.RandomBotSayWithoutMaster = ${if (allowBotChat) 1 else 0}
        AiPlayerbot.RandomBotInvitePlayer = ${if (allowPlayerInvites) 1 else 0}
        AiPlayerbot.RandomBotGroupNearby = ${if (groupNearby) 1 else 0}
        AiPlayerbot.RandomBotRaidNearby = 0
        AiPlayerbot.InviteChat = ${if (allowPlayerInvites) 1 else 0}
        AiPlayerbot.EnableOffSpecStrategies = ${if (enableOffSpecStrategies) 1 else 0}
        AiPlayerbot.UseWanderAsDefaultFollowStrategy = ${if (wanderWhenIdle) 1 else 0}
        AiPlayerbot.EnableBroadcasts = 0
        AiPlayerbot.CommandServerPort = 0
        AiPlayerbot.PerfMonEnabled = 0
        AiPlayerbot.LLMEnabled = 0
        AiPlayerbot.ShowProgressBars = 0
    """.trimIndent() + "\n"
}

/**
 * User-facing overrides for a measured profile. Every value is bounded and
 * encoded into the runtime profile identity so the isolated supervisor/world
 * processes and crash journal resolve exactly the same configuration without
 * accepting raw configuration text.
 */
data class BotAdvancedSettings(
    val nearbyBotLimit: Int = 12,
    val nearbyRadius: Int = 250,
    val loginBatchSize: Int = 3,
    val maintenanceBatchSize: Int = 12,
    val updateIntervalMs: Int = 1_500,
    val teleportMinMinutes: Int = 60,
    val teleportMaxMinutes: Int = 240,
    val iterationsPerTick: Int = 10,
    val admissionWorldP99Ms: Int = 250,
    val syncLevelWithPlayers: Boolean = false,
    val limitCombatActivity: Boolean = true,
    val activeBotPercent: Int = 5,
    val autoDoQuests: Boolean = true,
    val allowBotChat: Boolean = false,
    val allowPlayerInvites: Boolean = false,
    val groupNearby: Boolean = true,
    val wanderWhenIdle: Boolean = true,
    val enableOffSpecStrategies: Boolean = true,
) {
    init {
        require(nearbyBotLimit in 0..50)
        require(if (nearbyBotLimit == 0) nearbyRadius == 0 else nearbyRadius in 100..500)
        require(loginBatchSize in 1..10)
        require(maintenanceBatchSize in 1..32)
        require(updateIntervalMs in 1_000..5_000 && updateIntervalMs % 250 == 0)
        require(teleportMinMinutes in 30..2_880 && teleportMinMinutes % 30 == 0)
        require(teleportMaxMinutes in teleportMinMinutes..2_880 && teleportMaxMinutes % 30 == 0)
        require(iterationsPerTick in 1..20)
        require(admissionWorldP99Ms in 100..300 && admissionWorldP99Ms % 25 == 0)
        require(activeBotPercent in 1..20)
    }

    companion object {
        fun fromProfile(profile: BotProfile) = BotAdvancedSettings(
            nearbyBotLimit = profile.nearPlayerTeleportMaxAmount,
            nearbyRadius = profile.nearPlayerTeleportRadius,
            loginBatchSize = profile.loginBatchSize,
            maintenanceBatchSize = profile.maintenanceBatchSize,
            updateIntervalMs = profile.randomBotUpdateIntervalMs,
            teleportMinMinutes = profile.teleportMinIntervalSeconds / 60,
            teleportMaxMinutes = profile.teleportMaxIntervalSeconds / 60,
            iterationsPerTick = profile.iterationsPerTick,
            admissionWorldP99Ms = profile.admission.maxWorldP99Ms,
            syncLevelWithPlayers = profile.syncLevelWithPlayers,
            limitCombatActivity = profile.limitCombatActivity,
            activeBotPercent = profile.activeBotPercent,
            autoDoQuests = profile.autoDoQuests,
            allowBotChat = profile.allowBotChat,
            allowPlayerInvites = profile.allowPlayerInvites,
            groupNearby = profile.groupNearby,
            wanderWhenIdle = profile.wanderWhenIdle,
            enableOffSpecStrategies = profile.enableOffSpecStrategies,
        )
    }
}

/** Safe, comprehensible starting points for the independent behaviour toggles. */
enum class BotBehaviorPreset(val label: String, val summary: String) {
    EFFICIENT("Efficient", "Minimal background combat, questing, chat, and grouping."),
    NATURAL("Natural world", "Bots quest, wander, form nearby groups, and use their full specs."),
    SOCIAL("Social", "Natural behaviour plus local chatter and invitations to the player."),
}

fun BotAdvancedSettings.withBehaviorPreset(preset: BotBehaviorPreset): BotAdvancedSettings =
    when (preset) {
        BotBehaviorPreset.EFFICIENT -> copy(
            limitCombatActivity = true,
            activeBotPercent = 3,
            autoDoQuests = false,
            allowBotChat = false,
            allowPlayerInvites = false,
            groupNearby = false,
            wanderWhenIdle = false,
            enableOffSpecStrategies = false,
        )
        BotBehaviorPreset.NATURAL -> copy(
            limitCombatActivity = true,
            activeBotPercent = 5,
            autoDoQuests = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
        )
        BotBehaviorPreset.SOCIAL -> copy(
            limitCombatActivity = false,
            activeBotPercent = 8,
            autoDoQuests = true,
            allowBotChat = true,
            allowPlayerInvites = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
        )
    }

fun BotAdvancedSettings.matchesBehaviorPreset(preset: BotBehaviorPreset): Boolean {
    val expected = withBehaviorPreset(preset)
    return limitCombatActivity == expected.limitCombatActivity &&
        activeBotPercent == expected.activeBotPercent &&
        autoDoQuests == expected.autoDoQuests &&
        allowBotChat == expected.allowBotChat &&
        allowPlayerInvites == expected.allowPlayerInvites &&
        groupNearby == expected.groupNearby &&
        wanderWhenIdle == expected.wanderWhenIdle &&
        enableOffSpecStrategies == expected.enableOffSpecStrategies
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
    private const val ADVANCED_V1 = "advanced-v1"
    private const val ADVANCED_V2 = "advanced-v2"
    private const val ADVANCED_V3 = "adv3"
    private const val ADVANCED_V4 = "adv4"
    private fun advancedPattern(prefix: String) = Regex(
        "^$prefix-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(0|1)-([0-9a-f]{8})$",
    )
    private fun compactAdvancedPattern(prefix: String) = Regex(
        "^$prefix-" + List(13) { "([0-9a-z]+)" }.joinToString("-") + "-([0-9a-f]{8})$",
    )
    /** Report section 13 B1 / SOAK-25 candidate. It is not a default until its soak passes. */
    val LOW_25 = BotProfile(
        id = "mobile-low-b1-25-v1",
        displayName = "Qualification · 25 bots",
        summary = "Small deterministic validation tier.",
        userSelectable = false,
        selectedTarget = 25,
        minimumOnline = 20,
        maximumOnline = 25,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 250,
        accountPrefix = "PRB13",
        accountCount = 3,
        loginBatchSize = 5,
        maintenanceBatchSize = 12,
        loginWithPlayer = false,
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

    /** Report Profile A: low CPU, full behavior near a real player. */
    val LOW_CPU_160 = BotProfile(
        id = "mobile-lowcpu-nearby-b160-v1",
        displayName = "Efficient · 160 bots",
        summary = "Low background activity with lively bots in the player’s active zone.",
        userSelectable = false,
        selectedTarget = 160,
        minimumOnline = 120,
        maximumOnline = 160,
        maximumAltBots = 2,
        generationBatchSize = 10,
        generationYieldMs = 150,
        accountPrefix = "PRL160",
        accountCount = 18,
        loginBatchSize = 3,
        maintenanceBatchSize = 12,
        randomBotUpdateIntervalMs = 1_500,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 12,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 14_400,
        admission = BotAdmissionLimits(
            maxWorldP99Ms = 250,
            minFreeMemoryMiB = 1_024,
            minFreeStorageMiB = 2_048,
            performanceWarmupMs = 3 * 60_000L,
            reduceStep = 25,
            increaseStep = 25,
            healthyRampMs = 5 * 60_000L,
            changeCooldownMs = 10_000L,
        ),
    )

    /** Report Profile B: fresh realm, level-band and active-zone biased. */
    val FRESH_REALM_240 = BotProfile(
        id = "mobile-fresh-levelband-b240-v1",
        displayName = "Fresh realm · 240 bots",
        summary = "Follows the highest local-player progression band and favors active zones.",
        userSelectable = false,
        selectedTarget = 240,
        minimumOnline = 180,
        maximumOnline = 240,
        maximumAltBots = 2,
        generationBatchSize = 10,
        generationYieldMs = 125,
        accountPrefix = "PRF240",
        accountCount = 27,
        loginBatchSize = 4,
        maintenanceBatchSize = 16,
        randomBotUpdateIntervalMs = 1_250,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 18,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 1_800,
        teleportMaxIntervalSeconds = 7_200,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.35f,
        randomizeMinIntervalSeconds = 3_600,
        randomizeMaxIntervalSeconds = 10_800,
        admission = BotAdmissionLimits(
            maxWorldP99Ms = 250,
            minFreeMemoryMiB = 1_536,
            minFreeStorageMiB = 2_048,
            performanceWarmupMs = 4 * 60_000L,
            reduceStep = 25,
            increaseStep = 25,
            healthyRampMs = 5 * 60_000L,
            changeCooldownMs = 10_000L,
        ),
    )

    /**
     * Explicit RP6 lively-world profile. This remains opt-in while retaining
     * the admission controller's authority to shed load safely.
     */
    val LIVELY_700 = BotProfile(
        id = "mobile-lively-b700-v2",
        displayName = "High density · 700 bots",
        summary = "RP6 stress profile with low-CPU scheduling and fresh-realm locality bias.",
        userSelectable = false,
        selectedTarget = 700,
        minimumOnline = 25,
        maximumOnline = 700,
        maximumAltBots = 2,
        generationBatchSize = 10,
        generationYieldMs = 100,
        accountPrefix = "PRS6",
        // Eighty accounts provide 720 bounded characters for a 700 target.
        accountCount = 80,
        loginBatchSize = 4,
        maintenanceBatchSize = 16,
        randomBotUpdateIntervalMs = 1_250,
        forceActiveWhenNearPlayer = true,
        // Profile B density: favor active zones without flooding one point.
        nearPlayerTeleportMaxAmount = 18,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 1_800,
        teleportMaxIntervalSeconds = 7_200,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.35f,
        randomizeMinIntervalSeconds = 3_600,
        randomizeMaxIntervalSeconds = 10_800,
        admission = BotAdmissionLimits(
            maxWorldP99Ms = 250,
            minFreeMemoryMiB = 2_048,
            minFreeStorageMiB = 2_048,
            performanceWarmupMs = 5 * 60_000L,
            reduceStep = 50,
            increaseStep = 25,
            healthyRampMs = 5 * 60_000L,
            changeCooldownMs = 10_000L,
        ),
    )

    val TYPICAL_50 = BotProfile(
        id = "mobile-typical-b50-v1",
        displayName = "Typical · 50 bots",
        summary = "A quiet everyday realm that fills gradually around the player.",
        selectedTarget = 50,
        minimumOnline = 25,
        maximumOnline = 50,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 30_000,
        activationBatchSize = 5,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRT50",
        accountCount = 6,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_000,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 8,
        nearPlayerTeleportRadius = 200,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 14_400,
        limitCombatActivity = true,
        activeBotPercent = 3,
        allowPlayerInvites = false,
        groupNearby = false,
        wanderWhenIdle = false,
        enableOffSpecStrategies = false,
        admission = BotAdmissionLimits(250, 1_024, 2_048, 3 * 60_000L, 25, 25,
            5 * 60_000L, 10_000L),
    )

    val QUIET_25 = BotProfile(
        id = "mobile-quiet-b25-v1",
        displayName = "Quiet · 25 bots",
        summary = "The lightest everyday world for battery life and solo play.",
        selectedTarget = 25,
        minimumOnline = 25,
        maximumOnline = 25,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 30_000,
        activationBatchSize = 5,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRQ25",
        accountCount = 3,
        loginBatchSize = 2,
        maintenanceBatchSize = 5,
        randomBotUpdateIntervalMs = 2_000,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 6,
        nearPlayerTeleportRadius = 200,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 14_400,
        limitCombatActivity = true,
        activeBotPercent = 2,
        allowPlayerInvites = false,
        groupNearby = false,
        wanderWhenIdle = false,
        enableOffSpecStrategies = false,
        admission = BotAdmissionLimits(250, 768, 2_048, 3 * 60_000L, 5, 5,
            5 * 60_000L, 10_000L),
    )

    val BALANCED_100 = BotProfile(
        id = "mobile-balanced-b100-v1",
        displayName = "Balanced · 100 bots",
        summary = "A busier everyday realm with conservative background scheduling.",
        selectedTarget = 100,
        minimumOnline = 25,
        maximumOnline = 100,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 30_000,
        activationBatchSize = 5,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRB100",
        accountCount = 12,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_000,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 12,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 14_400,
        syncLevelWithPlayers = true,
        limitCombatActivity = true,
        activeBotPercent = 5,
        autoDoQuests = true,
        allowPlayerInvites = false,
        admission = BotAdmissionLimits(250, 1_536, 2_048, 3 * 60_000L, 25, 25,
            5 * 60_000L, 10_000L),
    )

    /** High-density launch-day candidate. Experimental until an ARM world soak passes. */
    val LAUNCH_DAY_700 = BotProfile(
        id = "mobile-launchday-b700-v1",
        displayName = "Launch day · 700 bots (experimental)",
        summary = "Builds a crowded world slowly, with automatic load shedding.",
        selectedTarget = 700,
        minimumOnline = 25,
        maximumOnline = 700,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 30_000,
        activationBatchSize = 5,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRL700",
        accountCount = 80,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_000,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 18,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 1_800,
        teleportMaxIntervalSeconds = 7_200,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.35f,
        randomizeMinIntervalSeconds = 3_600,
        randomizeMaxIntervalSeconds = 10_800,
        limitCombatActivity = true,
        activeBotPercent = 3,
        autoDoQuests = true,
        allowPlayerInvites = false,
        enableOffSpecStrategies = false,
        admission = BotAdmissionLimits(250, 2_048, 2_048, 5 * 60_000L, 50, 25,
            5 * 60_000L, 10_000L),
    )

    val POPULATED_250 = BotProfile(
        id = "mobile-populated-b250-v1",
        displayName = "Populated · 250 bots",
        summary = "A fuller leveling world that still ramps in measured steps.",
        selectedTarget = 250,
        minimumOnline = 25,
        maximumOnline = 250,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 30_000,
        activationBatchSize = 5,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRP250",
        accountCount = 28,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_000,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 16,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 9_000,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.25f,
        limitCombatActivity = true,
        activeBotPercent = 5,
        autoDoQuests = true,
        allowPlayerInvites = false,
        admission = BotAdmissionLimits(250, 1_536, 2_048, 4 * 60_000L, 25, 25,
            5 * 60_000L, 10_000L),
    )

    val CROWDED_400 = BotProfile(
        id = "mobile-crowded-b400-v1",
        displayName = "Crowded · 400 bots",
        summary = "A high-population world with a restrained local crowd and gradual startup.",
        selectedTarget = 400,
        minimumOnline = 25,
        maximumOnline = 400,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 45_000,
        activationBatchSize = 4,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRC400",
        accountCount = 45,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_500,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 10,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 14_400,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.25f,
        limitCombatActivity = true,
        activeBotPercent = 3,
        autoDoQuests = true,
        allowPlayerInvites = false,
        enableOffSpecStrategies = false,
        admission = BotAdmissionLimits(250, 1_792, 2_048, 4 * 60_000L, 25, 25,
            5 * 60_000L, 10_000L),
    )

    val BUSY_600 = BotProfile(
        id = "mobile-busy-b600-v1",
        displayName = "Busy · 600 bots",
        summary = "A very busy realm that limits nearby activity and ramps in conservative batches.",
        selectedTarget = 600,
        minimumOnline = 25,
        maximumOnline = 600,
        initialTarget = 25,
        startupIncreaseStep = 25,
        startupRampIntervalMs = 45_000,
        activationBatchSize = 4,
        maximumAltBots = 2,
        generationBatchSize = 5,
        generationYieldMs = 500,
        accountPrefix = "PRB600",
        accountCount = 67,
        loginBatchSize = 2,
        maintenanceBatchSize = 8,
        randomBotUpdateIntervalMs = 2_500,
        forceActiveWhenNearPlayer = true,
        nearPlayerTeleportMaxAmount = 14,
        nearPlayerTeleportRadius = 250,
        teleportMinIntervalSeconds = 3_600,
        teleportMaxIntervalSeconds = 10_800,
        syncLevelWithPlayers = true,
        randomBotMaxLevelChance = 0.30f,
        limitCombatActivity = true,
        activeBotPercent = 3,
        autoDoQuests = true,
        allowPlayerInvites = false,
        enableOffSpecStrategies = false,
        admission = BotAdmissionLimits(250, 2_048, 2_048, 5 * 60_000L, 50, 25,
            5 * 60_000L, 10_000L),
    )

    private val profiles = listOf(
        LOW_25, LOW_CPU_160, FRESH_REALM_240, LIVELY_700,
        QUIET_25, TYPICAL_50, BALANCED_100, POPULATED_250,
        CROWDED_400, BUSY_600, LAUNCH_DAY_700,
    )
        .associateBy(BotProfile::id)

    /** Catalog used when adv2/adv3 identities were minted. Never reorder or extend. */
    private val legacyAdvancedCatalog = listOf(
        QUIET_25, TYPICAL_50, BALANCED_100, POPULATED_250, LAUNCH_DAY_700,
    )

    fun find(id: String): BotProfile? = profiles[id] ?: decodeAdvanced(id)
    fun require(id: String): BotProfile = requireNotNull(find(id)) { "unknown bot profile: $id" }
    fun ids(): Set<String> = profiles.keys
    fun userSelectable(): List<BotProfile> = profiles.values.filter { it.userSelectable }
        .sortedBy { it.selectedTarget }
    fun forRequestedTarget(target: Int): BotProfile = userSelectable().minBy {
        kotlin.math.abs(it.selectedTarget - target)
    }

    fun migrateLegacyTarget(target: Int): BotProfile = when {
        target <= 37 -> QUIET_25
        target <= 75 -> TYPICAL_50
        target <= 175 -> BALANCED_100
        target <= 325 -> POPULATED_250
        target <= 500 -> CROWDED_400
        target <= 650 -> BUSY_600
        else -> LAUNCH_DAY_700
    }

    fun advanced(target: Int, settings: BotAdvancedSettings): BotProfile {
        return advancedFromBase(ADVANCED_V4, target, settings, forRequestedTarget(target))
    }

    private fun advancedFromBase(
        prefix: String,
        target: Int,
        settings: BotAdvancedSettings,
        base: BotProfile,
    ): BotProfile {
        require(target in 25..700 && target % 25 == 0)
        require(settings.nearbyBotLimit <= target)
        val identityPrefix = if (prefix == ADVANCED_V3 || prefix == ADVANCED_V4) {
            listOf(
                prefix,
                encode36(target),
                encode36(settings.nearbyBotLimit),
                encode36(settings.nearbyRadius),
                encode36(settings.loginBatchSize),
                encode36(settings.maintenanceBatchSize),
                encode36(settings.updateIntervalMs / 250),
                encode36(settings.teleportMinMinutes / 30),
                encode36(settings.teleportMaxMinutes / 30),
                encode36(settings.iterationsPerTick),
                encode36(settings.admissionWorldP99Ms / 25),
                if (settings.syncLevelWithPlayers) "1" else "0",
                encode36(settings.activeBotPercent),
                encode36(behaviorFlags(settings)),
            ).joinToString("-")
        } else {
            listOf(
                prefix,
                target,
                settings.nearbyBotLimit,
                settings.nearbyRadius,
                settings.loginBatchSize,
                settings.maintenanceBatchSize,
                settings.updateIntervalMs,
                settings.teleportMinMinutes,
                settings.teleportMaxMinutes,
                settings.iterationsPerTick,
                settings.admissionWorldP99Ms,
                if (settings.syncLevelWithPlayers) 1 else 0,
            ).joinToString("-")
        }
        val pacedStartup = prefix != ADVANCED_V1
        val resolved = base.copy(
            id = "$identityPrefix-00000000",
            displayName = "Custom · $target bots",
            summary = "Bounded low-CPU profile with user-selected locality and scheduling.",
            userSelectable = false,
            selectedTarget = target,
            minimumOnline = minOf(base.minimumOnline, target),
            maximumOnline = target,
            initialTarget = if (pacedStartup) minOf(base.initialTarget, target) else target,
            startupIncreaseStep = if (pacedStartup) minOf(base.startupIncreaseStep, target) else target,
            startupRampIntervalMs = if (pacedStartup) base.startupRampIntervalMs else 0,
            activationBatchSize = if (pacedStartup) base.activationBatchSize else minOf(target, 64),
            accountCount = maxOf(base.accountCount, (target + 8) / 9),
            loginBatchSize = settings.loginBatchSize,
            maintenanceBatchSize = settings.maintenanceBatchSize,
            randomBotUpdateIntervalMs = settings.updateIntervalMs,
            iterationsPerTick = settings.iterationsPerTick,
            forceActiveWhenNearPlayer = settings.nearbyBotLimit > 0,
            nearPlayerTeleportMaxAmount = settings.nearbyBotLimit,
            nearPlayerTeleportRadius = settings.nearbyRadius,
            teleportMinIntervalSeconds = settings.teleportMinMinutes * 60,
            teleportMaxIntervalSeconds = settings.teleportMaxMinutes * 60,
            syncLevelWithPlayers = settings.syncLevelWithPlayers,
            limitCombatActivity = settings.limitCombatActivity,
            activeBotPercent = settings.activeBotPercent,
            autoDoQuests = settings.autoDoQuests,
            allowBotChat = settings.allowBotChat,
            allowPlayerInvites = settings.allowPlayerInvites,
            groupNearby = settings.groupNearby,
            wanderWhenIdle = settings.wanderWhenIdle,
            enableOffSpecStrategies = settings.enableOffSpecStrategies,
            admission = base.admission.copy(maxWorldP99Ms = settings.admissionWorldP99Ms),
        )
        return resolved.copy(id = "$identityPrefix-${advancedDigest(resolved, prefix)}")
    }

    private fun decodeAdvanced(id: String): BotProfile? {
        return decodeCompactAdvanced(id, ADVANCED_V4, ::forRequestedTarget)
            ?: decodeCompactAdvanced(id, ADVANCED_V3, ::legacyAdvancedBase)
            ?: decodeAdvanced(id, ADVANCED_V2) { legacyAdvancedBase(it) }
            ?: decodeAdvanced(id, ADVANCED_V1) { target ->
                listOf(LOW_CPU_160, FRESH_REALM_240, LIVELY_700).minBy {
                    kotlin.math.abs(it.selectedTarget - target)
                }
            }
    }

    private fun decodeAdvanced(
        id: String,
        prefix: String,
        base: (Int) -> BotProfile,
    ): BotProfile? {
        val match = advancedPattern(prefix).matchEntire(id) ?: return null
        return runCatching {
            val values = match.groupValues
            val target = values[1].toInt()
            val baseProfile = base(target)
            advancedFromBase(
                prefix = prefix,
                target = target,
                base = baseProfile,
                settings = BotAdvancedSettings.fromProfile(baseProfile).copy(
                    nearbyBotLimit = values[2].toInt(),
                    nearbyRadius = values[3].toInt(),
                    loginBatchSize = values[4].toInt(),
                    maintenanceBatchSize = values[5].toInt(),
                    updateIntervalMs = values[6].toInt(),
                    teleportMinMinutes = values[7].toInt(),
                    teleportMaxMinutes = values[8].toInt(),
                    iterationsPerTick = values[9].toInt(),
                    admissionWorldP99Ms = values[10].toInt(),
                    syncLevelWithPlayers = values[11] == "1",
                ),
            )
        }.getOrNull()?.takeIf { it.id == id }
    }

    private fun decodeCompactAdvanced(
        id: String,
        prefix: String,
        baseForTarget: (Int) -> BotProfile,
    ): BotProfile? {
        val match = compactAdvancedPattern(prefix).matchEntire(id) ?: return null
        return runCatching {
            val values = match.groupValues
            val target = decode36(values[1])
            val base = baseForTarget(target)
            val sync = decode36(values[11]).also { require(it in 0..1) } == 1
            val flags = decode36(values[13])
            val settings = BotAdvancedSettings.fromProfile(base).copy(
                nearbyBotLimit = decode36(values[2]),
                nearbyRadius = decode36(values[3]),
                loginBatchSize = decode36(values[4]),
                maintenanceBatchSize = decode36(values[5]),
                updateIntervalMs = decode36(values[6]) * 250,
                teleportMinMinutes = decode36(values[7]) * 30,
                teleportMaxMinutes = decode36(values[8]) * 30,
                iterationsPerTick = decode36(values[9]),
                admissionWorldP99Ms = decode36(values[10]) * 25,
                syncLevelWithPlayers = sync,
                activeBotPercent = decode36(values[12]),
                limitCombatActivity = flags and 1 != 0,
                autoDoQuests = flags and 2 != 0,
                allowBotChat = flags and 4 != 0,
                allowPlayerInvites = flags and 8 != 0,
                groupNearby = flags and 16 != 0,
                wanderWhenIdle = flags and 32 != 0,
                enableOffSpecStrategies = flags and 64 != 0,
            )
            advancedFromBase(prefix, target, settings, base)
        }.getOrNull()?.takeIf { it.id == id }
    }

    private fun legacyAdvancedBase(target: Int): BotProfile = legacyAdvancedCatalog.minBy {
        kotlin.math.abs(it.selectedTarget - target)
    }

    private fun advancedDigest(profile: BotProfile, prefix: String): String {
        val canonicalText = when (prefix) {
            ADVANCED_V1 -> legacyIdentity(profile, includePacing = false)
            ADVANCED_V2 -> legacyIdentity(profile, includePacing = true)
            else -> buildString {
                append(prefix).append('|')
                append(profile.selectedTarget).append('|')
                append(profile.minimumOnline).append('|')
                append(profile.maximumOnline).append('|')
                append(profile.initialTarget).append('|')
                append(profile.startupIncreaseStep).append('|')
                append(profile.startupRampIntervalMs).append('|')
                append(profile.activationBatchSize).append('|')
                append(profile.playerbotConfig()).append('|')
                append(profile.admission)
            }
        }
        val canonical = canonicalText.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
            .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun legacyIdentity(profile: BotProfile, includePacing: Boolean): String = buildString {
        append("BotProfile(id=").append(if (includePacing) ADVANCED_V2 else ADVANCED_V1)
        append(", displayName=, summary=, userSelectable=false")
        append(", selectedTarget=").append(profile.selectedTarget)
        append(", minimumOnline=").append(profile.minimumOnline)
        append(", maximumOnline=").append(profile.maximumOnline)
        if (includePacing) {
            append(", initialTarget=").append(profile.initialTarget)
            append(", startupIncreaseStep=").append(profile.startupIncreaseStep)
            append(", startupRampIntervalMs=").append(profile.startupRampIntervalMs)
            append(", activationBatchSize=").append(profile.activationBatchSize)
        }
        append(", maximumAltBots=").append(profile.maximumAltBots)
        append(", generationBatchSize=").append(profile.generationBatchSize)
        append(", generationYieldMs=").append(profile.generationYieldMs)
        append(", accountPrefix=").append(profile.accountPrefix)
        append(", accountCount=").append(profile.accountCount)
        append(", loginBatchSize=").append(profile.loginBatchSize)
        append(", maintenanceBatchSize=").append(profile.maintenanceBatchSize)
        append(", randomBotUpdateIntervalMs=").append(profile.randomBotUpdateIntervalMs)
        append(", iterationsPerTick=").append(profile.iterationsPerTick)
        append(", loginAtStartup=").append(profile.loginAtStartup)
        append(", loginWithPlayer=").append(profile.loginWithPlayer)
        append(", forceActiveWhenNearPlayer=").append(profile.forceActiveWhenNearPlayer)
        append(", nearPlayerTeleportMaxAmount=").append(profile.nearPlayerTeleportMaxAmount)
        append(", nearPlayerTeleportRadius=").append(profile.nearPlayerTeleportRadius)
        append(", teleportMinIntervalSeconds=").append(profile.teleportMinIntervalSeconds)
        append(", teleportMaxIntervalSeconds=").append(profile.teleportMaxIntervalSeconds)
        append(", syncLevelWithPlayers=").append(profile.syncLevelWithPlayers)
        append(", syncLevelMaxAbove=").append(profile.syncLevelMaxAbove)
        append(", syncLevelNoPlayer=").append(profile.syncLevelNoPlayer)
        append(", randomBotMaxLevelChance=").append(profile.randomBotMaxLevelChance)
        append(", randomizeMinIntervalSeconds=").append(profile.randomizeMinIntervalSeconds)
        append(", randomizeMaxIntervalSeconds=").append(profile.randomizeMaxIntervalSeconds)
        append(", admission=").append(profile.admission).append(')')
    }

    private fun behaviorFlags(settings: BotAdvancedSettings): Int =
        (if (settings.limitCombatActivity) 1 else 0) or
            (if (settings.autoDoQuests) 2 else 0) or
            (if (settings.allowBotChat) 4 else 0) or
            (if (settings.allowPlayerInvites) 8 else 0) or
            (if (settings.groupNearby) 16 else 0) or
            (if (settings.wanderWhenIdle) 32 else 0) or
            (if (settings.enableOffSpecStrategies) 64 else 0)

    private fun encode36(value: Int): String {
        require(value >= 0)
        return value.toString(36)
    }

    private fun decode36(value: String): Int = value.toInt(36)
}
