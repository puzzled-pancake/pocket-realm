package com.pocketrealm.bots

/**
 * Complete user-editable bot configuration for named custom presets
 * (landscape UI brief, sections 20-32). Every field maps to a knob the
 * reviewed playerbot configuration generator actually emits — no pretend
 * settings. Values are validated by [BotProfile] construction rules plus
 * [BotPopulationPolicy] for custom population shapes beyond the built-in
 * window.
 */
data class BotCustomConfiguration(
    // Population
    val selectedTarget: Int,
    val minimumOnline: Int,
    val maximumOnline: Int,
    val initialTarget: Int,
    val startupIncreaseStep: Int,
    val startupRampIntervalMs: Long,
    val activationBatchSize: Int,
    // Accounts, generation and batching
    val maximumAltBots: Int = 2,
    val generationBatchSize: Int = 5,
    val generationYieldMs: Long = 250,
    val accountPrefix: String = "PRCUS",
    val accountCount: Int,
    val loginBatchSize: Int = 3,
    val maintenanceBatchSize: Int = 12,
    // AI scheduling
    val randomBotUpdateIntervalMs: Int = 2_000,
    val iterationsPerTick: Int = 10,
    val loginAtStartup: Boolean = false,
    val loginWithPlayer: Boolean = true,
    // Nearby activity / locality
    val forceActiveWhenNearPlayer: Boolean = true,
    val nearPlayerTeleportMaxAmount: Int = 12,
    val nearPlayerTeleportRadius: Int = 250,
    // Teleporting
    val teleportMinIntervalSeconds: Int = 3_600,
    val teleportMaxIntervalSeconds: Int = 14_400,
    // Leveling
    val syncLevelWithPlayers: Boolean = false,
    val syncLevelMaxAbove: Int = 3,
    val syncLevelNoPlayer: Int = 1,
    val randomBotMaxLevelChance: Float = 0.15f,
    val randomizeMinIntervalSeconds: Int = 7_200,
    val randomizeMaxIntervalSeconds: Int = 86_400,
    // Behaviour
    val limitCombatActivity: Boolean = true,
    val activeBotPercent: Int = 8,
    val autoDoQuests: Boolean = true,
    val allowBotChat: Boolean = false,
    val allowPlayerInvites: Boolean = false,
    val groupNearby: Boolean = true,
    val wanderWhenIdle: Boolean = true,
    val enableOffSpecStrategies: Boolean = true,
    // Adaptation / performance floors
    val admission: BotAdmissionLimits = BotAdmissionLimits(
        maxWorldP99Ms = 250,
        minFreeMemoryMiB = 2_048,
        minFreeStorageMiB = 2_048,
        performanceWarmupMs = 4 * 60_000L,
        reduceStep = 25,
        increaseStep = 25,
        healthyRampMs = 5 * 60_000L,
        changeCooldownMs = 10_000L,
    ),
) {
    init {
        BotPopulationPolicy.validatePopulation(
            minimumOnline = minimumOnline,
            initialTarget = initialTarget,
            target = selectedTarget,
            maximumOnline = maximumOnline,
        )
    }

    /** Population-independent estimate of normally-active bots (§17 display). */
    fun estimatedActiveBots(): Int = (selectedTarget * activeBotPercent + 50) / 100

    /** Rebuild with a new target, keeping the shape valid and re-sizing the account pool. */
    fun withTarget(target: Int): BotCustomConfiguration {
        BotPopulationPolicy.validateTarget(target)
        return copy(
            selectedTarget = target,
            minimumOnline = minimumOnline.coerceAtMost(target),
            // The configured maximum tracks the target; the Advanced
            // population section can raise it deliberately afterwards.
            maximumOnline = target,
            initialTarget = initialTarget.coerceIn(
                minimumOnline.coerceAtMost(target), target,
            ),
            startupIncreaseStep = startupIncreaseStep.coerceIn(1, target),
            activationBatchSize = activationBatchSize.coerceIn(1, 64),
            nearPlayerTeleportMaxAmount = nearPlayerTeleportMaxAmount.coerceAtMost(target),
            accountCount = BotPopulationPolicy.allocatedAccounts(target),
        )
    }

    fun resolve(identity: String, name: String, basePresetId: String? = null): BotProfile =
        BotProfile(
            id = identity,
            displayName = name,
            summary = "Custom preset",
            userSelectable = false,
            selectedTarget = selectedTarget,
            minimumOnline = minimumOnline,
            maximumOnline = maximumOnline,
            initialTarget = initialTarget,
            startupIncreaseStep = startupIncreaseStep,
            startupRampIntervalMs = startupRampIntervalMs,
            activationBatchSize = activationBatchSize,
            maximumAltBots = maximumAltBots,
            generationBatchSize = generationBatchSize,
            generationYieldMs = generationYieldMs,
            accountPrefix = accountPrefix,
            accountCount = accountCount,
            loginBatchSize = loginBatchSize,
            maintenanceBatchSize = maintenanceBatchSize,
            randomBotUpdateIntervalMs = randomBotUpdateIntervalMs,
            iterationsPerTick = iterationsPerTick,
            loginAtStartup = loginAtStartup,
            loginWithPlayer = loginWithPlayer,
            forceActiveWhenNearPlayer = forceActiveWhenNearPlayer,
            nearPlayerTeleportMaxAmount = nearPlayerTeleportMaxAmount,
            nearPlayerTeleportRadius = nearPlayerTeleportRadius,
            teleportMinIntervalSeconds = teleportMinIntervalSeconds,
            teleportMaxIntervalSeconds = teleportMaxIntervalSeconds,
            syncLevelWithPlayers = syncLevelWithPlayers,
            syncLevelMaxAbove = syncLevelMaxAbove,
            syncLevelNoPlayer = syncLevelNoPlayer,
            randomBotMaxLevelChance = randomBotMaxLevelChance,
            randomizeMinIntervalSeconds = randomizeMinIntervalSeconds,
            randomizeMaxIntervalSeconds = randomizeMaxIntervalSeconds,
            limitCombatActivity = limitCombatActivity,
            activeBotPercent = activeBotPercent,
            autoDoQuests = autoDoQuests,
            allowBotChat = allowBotChat,
            allowPlayerInvites = allowPlayerInvites,
            groupNearby = groupNearby,
            wanderWhenIdle = wanderWhenIdle,
            enableOffSpecStrategies = enableOffSpecStrategies,
            admission = admission,
        ).also { require(basePresetId == null || basePresetId.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) }

    companion object {
        fun fromProfile(profile: BotProfile): BotCustomConfiguration = BotCustomConfiguration(
            selectedTarget = profile.selectedTarget,
            minimumOnline = profile.minimumOnline,
            maximumOnline = profile.maximumOnline,
            initialTarget = profile.initialTarget,
            startupIncreaseStep = profile.startupIncreaseStep,
            startupRampIntervalMs = profile.startupRampIntervalMs,
            activationBatchSize = profile.activationBatchSize,
            maximumAltBots = profile.maximumAltBots,
            generationBatchSize = profile.generationBatchSize,
            generationYieldMs = profile.generationYieldMs,
            accountPrefix = profile.accountPrefix,
            accountCount = profile.accountCount,
            loginBatchSize = profile.loginBatchSize,
            maintenanceBatchSize = profile.maintenanceBatchSize,
            randomBotUpdateIntervalMs = profile.randomBotUpdateIntervalMs,
            iterationsPerTick = profile.iterationsPerTick,
            loginAtStartup = profile.loginAtStartup,
            loginWithPlayer = profile.loginWithPlayer,
            forceActiveWhenNearPlayer = profile.forceActiveWhenNearPlayer,
            nearPlayerTeleportMaxAmount = profile.nearPlayerTeleportMaxAmount,
            nearPlayerTeleportRadius = profile.nearPlayerTeleportRadius,
            teleportMinIntervalSeconds = profile.teleportMinIntervalSeconds,
            teleportMaxIntervalSeconds = profile.teleportMaxIntervalSeconds,
            syncLevelWithPlayers = profile.syncLevelWithPlayers,
            syncLevelMaxAbove = profile.syncLevelMaxAbove,
            syncLevelNoPlayer = profile.syncLevelNoPlayer,
            randomBotMaxLevelChance = profile.randomBotMaxLevelChance,
            randomizeMinIntervalSeconds = profile.randomizeMinIntervalSeconds,
            randomizeMaxIntervalSeconds = profile.randomizeMaxIntervalSeconds,
            limitCombatActivity = profile.limitCombatActivity,
            activeBotPercent = profile.activeBotPercent,
            autoDoQuests = profile.autoDoQuests,
            allowBotChat = profile.allowBotChat,
            allowPlayerInvites = profile.allowPlayerInvites,
            groupNearby = profile.groupNearby,
            wanderWhenIdle = profile.wanderWhenIdle,
            enableOffSpecStrategies = profile.enableOffSpecStrategies,
            admission = profile.admission,
        )

        /** Start a new custom preset from a built-in base (duplicate-friendly). */
        fun fromBasePreset(base: BotProfile): BotCustomConfiguration = fromProfile(base)
    }
}

/**
 * Activity bundles (brief §14): combinations of AI update interval,
 * iterations per tick and active percentage. Any population may combine with
 * any activity — the editor warns about heavy mixes but never blocks them.
 */
enum class BotActivityPreset(val label: String, val summary: String) {
    SMART("Smart", "Maximum foreground and nearby AI attention."),
    ACTIVE("Active", "Strong foreground plus meaningful world simulation."),
    BALANCED("Balanced", "Balance between population and thinking speed."),
    LIGHT("Light", "Lower AI frequency for battery and cooler devices."),
    CUSTOM("Custom", "User-defined scheduling values.");

    fun applyTo(configuration: BotCustomConfiguration): BotCustomConfiguration = when (this) {
        SMART -> configuration.copy(
            randomBotUpdateIntervalMs = 1_000,
            iterationsPerTick = 20,
            activeBotPercent = 15,
        )
        ACTIVE -> configuration.copy(
            randomBotUpdateIntervalMs = 2_000,
            iterationsPerTick = 15,
            activeBotPercent = 12,
        )
        BALANCED -> configuration.copy(
            randomBotUpdateIntervalMs = 2_500,
            iterationsPerTick = 10,
            activeBotPercent = 8,
        )
        LIGHT -> configuration.copy(
            randomBotUpdateIntervalMs = 2_500,
            iterationsPerTick = 8,
            activeBotPercent = 3,
        )
        CUSTOM -> configuration
    }

    fun matches(configuration: BotCustomConfiguration): Boolean {
        if (this == CUSTOM) {
            return entries.none { it != CUSTOM && it.matches(configuration) }
        }
        val applied = applyTo(configuration)
        return configuration.randomBotUpdateIntervalMs == applied.randomBotUpdateIntervalMs &&
            configuration.iterationsPerTick == applied.iterationsPerTick &&
            configuration.activeBotPercent == applied.activeBotPercent
    }
}

/**
 * Playstyle bundles (brief §13). Behaviour around humans and groups; LAN
 * Co-op here means bot behaviour near multiple humans, not networking
 * (networking lives in the LAN destination).
 */
enum class BotPlaystylePreset(val label: String, val summary: String) {
    CLASSIC_WORLD("Classic World", "Quests, autonomous groups, wandering; level matching off."),
    SOLO_FRIENDLY("Solo Friendly", "Player-focused and quiet; bots do not form their own groups."),
    LAN_COOP("LAN Co-op", "Group-ready behaviour for several humans in one world."),
    INDEPENDENT("Independent", "Bots roam and quest alone; no bot-only groups form."),
    DUNGEON("Dungeon / Raid", "Group and role ready with level matching to the party."),
    SOCIAL("Social", "Chatty bots that invite the player."),
    CUSTOM("Custom", "User-defined behaviour values.");

    fun applyTo(configuration: BotCustomConfiguration): BotCustomConfiguration = when (this) {
        CLASSIC_WORLD -> configuration.copy(
            autoDoQuests = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            syncLevelWithPlayers = false,
        )
        SOLO_FRIENDLY -> configuration.copy(
            autoDoQuests = true,
            groupNearby = false,
            wanderWhenIdle = true,
            enableOffSpecStrategies = false,
            allowBotChat = false,
            allowPlayerInvites = false,
            syncLevelWithPlayers = false,
        )
        LAN_COOP -> configuration.copy(
            autoDoQuests = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            syncLevelWithPlayers = true,
        )
        // Distinct from CLASSIC_WORLD (verification: the two presets were
        // tuple-identical, so both chips rendered selected at once and the
        // default profile matched both). Independent bots do not form
        // bot-only groups.
        INDEPENDENT -> configuration.copy(
            autoDoQuests = true,
            groupNearby = false,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            syncLevelWithPlayers = false,
        )
        DUNGEON -> configuration.copy(
            autoDoQuests = true,
            groupNearby = true,
            wanderWhenIdle = false,
            enableOffSpecStrategies = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            syncLevelWithPlayers = true,
        )
        SOCIAL -> configuration.copy(
            autoDoQuests = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
            allowBotChat = true,
            allowPlayerInvites = true,
            syncLevelWithPlayers = false,
        )
        CUSTOM -> configuration
    }

    fun matches(configuration: BotCustomConfiguration): Boolean {
        if (this == CUSTOM) {
            return entries.none { it != CUSTOM && it.matches(configuration) }
        }
        val applied = applyTo(configuration)
        return configuration.autoDoQuests == applied.autoDoQuests &&
            configuration.groupNearby == applied.groupNearby &&
            configuration.wanderWhenIdle == applied.wanderWhenIdle &&
            configuration.enableOffSpecStrategies == applied.enableOffSpecStrategies &&
            configuration.allowBotChat == applied.allowBotChat &&
            configuration.allowPlayerInvites == applied.allowPlayerInvites &&
            configuration.syncLevelWithPlayers == applied.syncLevelWithPlayers
    }
}
