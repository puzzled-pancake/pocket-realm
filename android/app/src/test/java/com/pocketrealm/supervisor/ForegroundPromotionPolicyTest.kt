package com.pocketrealm.supervisor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM truth table for the foreground-promotion predicate, including
 * the "naive readings never fire or never demote" cases: onlinePlayers
 * counts bots, realPlayers is bot-profile-gated, and demotion carries
 * asymmetric hysteresis (promote immediate, demote after three empty
 * samples).
 */
class ForegroundPromotionPolicyTest {

    private fun promote(
        state: ComponentLifecycle = ComponentLifecycle.READY,
        realPlayers: Int = 0,
        playerbotsEnabled: Boolean = true,
        onlinePlayers: Int = 0,
        currentlyPromoted: Boolean = false,
    ) = ForegroundPromotionPolicy.shouldPromote(
        state, realPlayers, playerbotsEnabled, onlinePlayers, currentlyPromoted)

    private fun demote(
        state: ComponentLifecycle = ComponentLifecycle.READY,
        realPlayers: Int = 0,
        playerbotsEnabled: Boolean = true,
        onlinePlayers: Int = 0,
        consecutiveEmptySamples: Int = 0,
        currentlyPromoted: Boolean = true,
    ) = ForegroundPromotionPolicy.shouldDemote(
        state, realPlayers, playerbotsEnabled, onlinePlayers,
        consecutiveEmptySamples, currentlyPromoted)

    @Test fun `real player promotes immediately regardless of bots`() {
        assertTrue(promote(realPlayers = 1, playerbotsEnabled = true, onlinePlayers = 50))
        assertTrue(promote(realPlayers = 3, playerbotsEnabled = false, onlinePlayers = 3))
    }

    @Test fun `bot-only online count never promotes`() {
        assertFalse(promote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 320))
        assertFalse(promote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 1))
    }

    @Test fun `botless realm promotes on any online session`() {
        assertTrue(promote(realPlayers = 0, playerbotsEnabled = false, onlinePlayers = 1))
        assertFalse(promote(realPlayers = 0, playerbotsEnabled = false, onlinePlayers = 0))
    }

    @Test fun `only the live world state promotes`() {
        assertFalse(promote(state = ComponentLifecycle.STARTING, realPlayers = 1))
        assertFalse(promote(state = ComponentLifecycle.STOPPING, realPlayers = 1))
        assertFalse(promote(state = ComponentLifecycle.STOPPED, realPlayers = 1))
        assertFalse(promote(state = ComponentLifecycle.FAILED, realPlayers = 1))
        assertFalse(promote(state = ComponentLifecycle.UNKNOWN, realPlayers = 1))
    }

    @Test fun `already promoted never re-fires the promote edge`() {
        assertFalse(promote(realPlayers = 1, currentlyPromoted = true))
    }

    @Test fun `demotion waits for three consecutive empty samples`() {
        assertTrue(demote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 50,
            consecutiveEmptySamples = 3))
        assertFalse(demote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 50,
            consecutiveEmptySamples = 1))
        assertFalse(demote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 50,
            consecutiveEmptySamples = 2))
    }

    @Test fun `player present never demotes regardless of sample count`() {
        assertFalse(demote(realPlayers = 1, playerbotsEnabled = true, onlinePlayers = 50,
            consecutiveEmptySamples = 10))
        assertFalse(demote(realPlayers = 0, playerbotsEnabled = false, onlinePlayers = 2,
            consecutiveEmptySamples = 10))
    }

    @Test fun `not promoted never demotes`() {
        assertFalse(demote(realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 50,
            consecutiveEmptySamples = 3, currentlyPromoted = false))
    }

    @Test fun `dead or stopped world demotes after the hysteresis window`() {
        assertTrue(demote(state = ComponentLifecycle.STOPPED,
            consecutiveEmptySamples = 3))
        assertTrue(demote(state = ComponentLifecycle.FAILED,
            consecutiveEmptySamples = 3))
        assertFalse(demote(state = ComponentLifecycle.STOPPED,
            consecutiveEmptySamples = 2))
    }
}
