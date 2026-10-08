package com.pocketrealm.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F9 (census-backend-tools): the joined-mode players-online presentation
 * wiring — the DesktopAppModel.lanPlayersOnline + budgetLine people-half
 * twin. Pins the decode (supervisor worldPresence payload → the players
 * facts), the live "N players in world" shapes, and the null rule the
 * desktop model documents ("renders nothing rather than guessing").
 */
class LanPresenceModelTest {

    @Test
    fun liveRealPlayerSampleRendersTheYouPlusGuestsSplit() {
        val players = LanPresenceModel.decode(
            JSONObject()
                .put("state", "READY")
                .put("realPlayers", 3)
                .put("playerbotsEnabled", true)
                .put("onlinePlayers", 41),
        )
        assertEquals(
            LanPresenceModel.LanPlayersOnline(activeSessions = 41L, realPlayers = 3, playerbotsEnabled = true),
            players)
        assertEquals(2, players?.guests)
        assertEquals(
            "Connected players: 3 players in world (you + 2 guests)",
            LanPresenceModel.line(players),
        )
    }

    @Test
    fun soloHostNeverRendersNegativeGuests() {
        val players = LanPresenceModel.decode(
            JSONObject().put("state", "READY").put("realPlayers", 1)
                .put("playerbotsEnabled", false).put("onlinePlayers", 1),
        )
        assertEquals(0, players?.guests)
        assertEquals(
            "Connected players: 1 players in world (you + 0 guests)",
            LanPresenceModel.line(players),
        )
    }

    @Test
    fun botsEnabledSessionsAreLabeledNeverPadded() {
        // Round-6 R6-1 (flipped pin): with the bot lane armed the session
        // count includes bot sessions (ForegroundPromotionPolicy's own
        // caveat) — the line must LABEL them, never render "7 players".
        val players = LanPresenceModel.decode(
            JSONObject().put("state", "READY").put("realPlayers", 0)
                .put("playerbotsEnabled", true).put("onlinePlayers", 7),
        )
        val line = LanPresenceModel.line(players)
        assertEquals("No players in world — 7 bot sessions running", line)
        assertFalse("bots must not render as players", line != null && "7 players" in line)
    }

    @Test
    fun botsDisabledZeroPlayersRendersTheHonestEmptyLine() {
        // The bots-disabled fallback keeps the session-count rendering:
        // without the bot lane every session IS a player session.
        val players = LanPresenceModel.decode(
            JSONObject().put("state", "READY").put("realPlayers", 0)
                .put("playerbotsEnabled", false).put("onlinePlayers", 2),
        )
        assertEquals(
            "Connected players: 2 players in world",
            LanPresenceModel.line(players),
        )
    }

    @Test
    fun zeroSessionsWithBotsRendersNoPlayers() {
        val players = LanPresenceModel.decode(
            JSONObject().put("state", "READY").put("realPlayers", 0)
                .put("playerbotsEnabled", true).put("onlinePlayers", 0),
        )
        assertEquals("No players in world", LanPresenceModel.line(players))
    }

    @Test
    fun stoppedWorldDecodesToNullAndRendersNothing() {
        // The joined-device shape: client-only (RuntimeLaunchSpec
        // .componentPlan), so the supervisor samples no local world.
        assertNull(
            LanPresenceModel.decode(
                JSONObject().put("state", "STOPPED").put("realPlayers", 0)
                    .put("playerbotsEnabled", false).put("onlinePlayers", 0),
            ),
        )
        assertNull(LanPresenceModel.line(null))
    }

    @Test
    fun unreachableSupervisorDecodesToNullAndRendersNothing() {
        assertNull(LanPresenceModel.decode(null))
    }

    @Test
    fun decodeCoercesMalformedCountsDownToZero() {
        // Defensive decode: a negative or missing count never renders as a
        // negative player line.
        val players = LanPresenceModel.decode(
            JSONObject().put("state", "READY").put("realPlayers", -2),
        )
        assertEquals(LanPresenceModel.LanPlayersOnline(activeSessions = 0L, realPlayers = 0), players)
        // Round-6 R6-1: zero sessions (bots off) renders the honest empty
        // line — "0 players in world" told the user nothing.
        assertEquals("No players in world", LanPresenceModel.line(players))
    }
}
