package com.pocketrealm.supervisor

/**
 * Pure foreground-promotion predicate for :world and :database.
 *
 * Both services sit at bound-service priority while idle; the supervisor
 * promotes them to specialUse FGS the moment a real player is present and
 * demotes them only after a sustained absence. The world has no RUNNING
 * state - `ComponentLifecycle.READY` is the live state.
 *
 * The presence composite exists because the naive readings are both wrong:
 * `onlinePlayers` counts bots (a bot-only world would never demote), and
 * `realPlayers` alone misses a player connecting to a botless realm.
 */
object ForegroundPromotionPolicy {

    /** Asymmetric hysteresis: demotion requires this many consecutive empty samples. */
    const val DEMOTE_EMPTY_SAMPLES = 3

    /**
     * True when actual players are present: any real player, or - on a realm
     * without playerbots - any online session at all (they can only be
     * players).
     */
    fun playersPresent(realPlayers: Int, playerbotsEnabled: Boolean, onlinePlayers: Int): Boolean =
        realPlayers > 0 || (!playerbotsEnabled && onlinePlayers > 0)

    /** Promote IMMEDIATELY: no hysteresis on the promote edge. */
    fun shouldPromote(
        stateCode: ComponentLifecycle,
        realPlayers: Int,
        playerbotsEnabled: Boolean,
        onlinePlayers: Int,
        currentlyPromoted: Boolean,
    ): Boolean = !currentlyPromoted && stateCode == ComponentLifecycle.READY &&
        playersPresent(realPlayers, playerbotsEnabled, onlinePlayers)

    /**
     * The negation of the promote predicate, gated on asymmetric hysteresis:
     * only demote after [DEMOTE_EMPTY_SAMPLES] consecutive empty samples.
     */
    fun shouldDemote(
        stateCode: ComponentLifecycle,
        realPlayers: Int,
        playerbotsEnabled: Boolean,
        onlinePlayers: Int,
        consecutiveEmptySamples: Int,
        currentlyPromoted: Boolean,
    ): Boolean = currentlyPromoted &&
        !(stateCode == ComponentLifecycle.READY &&
            playersPresent(realPlayers, playerbotsEnabled, onlinePlayers)) &&
        consecutiveEmptySamples >= DEMOTE_EMPTY_SAMPLES
}

/** One world presence sample feeding [ForegroundPromotionPolicy]. */
data class WorldPresenceSample(
    val state: ComponentLifecycle,
    val realPlayers: Int,
    val playerbotsEnabled: Boolean,
    val onlinePlayers: Int,
) {
    companion object {
        /** An unobservable or absent world is definitively playerless. */
        val EMPTY = WorldPresenceSample(ComponentLifecycle.STOPPED, 0, playerbotsEnabled = false, 0)
    }
}
