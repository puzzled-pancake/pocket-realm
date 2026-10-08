package com.pocketrealm.desktop

import com.pocketrealm.desktop.ui.budgetLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Round-6 R6-1 (desktop twin): the Guests card's budget line must LABEL
 * bot sessions, never pad "N players in world" over a bot-inclusive
 * session count (ForegroundPromotionPolicy's own documented caveat —
 * `onlinePlayers` counts bots when the lane is armed).
 */
class DesktopBudgetLineBotLabelTest {

    private fun players(sessions: Long, real: Int, bots: Boolean) =
        DesktopAppModel.LanPlayersOnline(
            activeSessions = sessions, realPlayers = real, playerbotsEnabled = bots,
        )

    @Test
    fun botsEnabledZeroRealPlayersAreLabeled() {
        val line = budgetLine(null, players(7, 0, bots = true))
        assertEquals("Bots: off · No players in world — 7 bot sessions running", line)
        assertFalse("bots must not render as players", "7 players" in line)
    }

    @Test
    fun botsDisabledSessionsStayPlayers() {
        val line = budgetLine(null, players(2, 0, bots = false))
        // The desktop budget line carries no "Connected players:" prefix in
        // either of its player-count arms (sibling realPlayers arm, below);
        // the prefix belongs to the android presence-list twin only.
        assertEquals("Bots: off · 2 players in world", line)
    }

    @Test
    fun realPlayersKeepTheGuestsSplit() {
        val line = budgetLine(null, players(3, 2, bots = true))
        assertEquals("Bots: off · 2 players in world (you + 1 guests)", line)
    }
}
