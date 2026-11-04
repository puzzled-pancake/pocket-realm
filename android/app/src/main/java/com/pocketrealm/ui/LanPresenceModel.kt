package com.pocketrealm.ui

import org.json.JSONObject

/**
 * Pure presentation model for the LAN screen's joined-mode players-online
 * line (F9, census-backend-tools; design-lan) — the
 * DesktopAppModel.lanPlayersOnline + budgetLine "people" half twin, over
 * the same shared observeWorldPresence contract. The Compose line renders
 * this verbatim; the shapes are pinned by LanPresenceModelTest.
 *
 * Null rule (DesktopAppModel's own contract, DesktopAppModel.kt:1129-1131):
 * while no live world reports, the line renders NOTHING rather than
 * guessing — a joined device runs client-only
 * (RuntimeLaunchSpec.componentPlan), so its supervisor truthfully reports
 * no world; that is a live reading from the shared contract, never a
 * promise that a count is coming.
 */
object LanPresenceModel {

    /**
     * The players-online facts — the DesktopAppModel.LanPlayersOnline
     * twin. [activeSessions] carries the world's online-session count
     * (the android sample's `onlinePlayers`; the desktop twin reads the
     * world telemetry's `activeSessions` — the same session counter lane).
     */
    data class LanPlayersOnline(
        val activeSessions: Long,
        val realPlayers: Int,
        /** Whether the bot lane is armed: with bots on, [activeSessions]
         * counts bot sessions too (ForegroundPromotionPolicy) and must
         * never render as "players" (round-6 R6-1: bots labeled, never
         * padded). */
        val playerbotsEnabled: Boolean = false,
    ) {
        /** The guests beyond the host's own session (never negative). */
        val guests: Int get() = (realPlayers - 1).coerceAtLeast(0)
    }

    /** The poll cadence — the desktop Guests card's 5 s twin. */
    const val POLL_MS: Long = 5_000L

    /**
     * Decode the supervisor's worldPresence payload. Null while the
     * supervisor is unreachable or its world is STOPPED (no live world
     * reports) — the caller renders nothing rather than guessing.
     */
    fun decode(raw: JSONObject?): LanPlayersOnline? {
        if (raw == null) return null
        if (raw.optString("state") == "STOPPED") return null
        return LanPlayersOnline(
            activeSessions = raw.optLong("onlinePlayers", 0L).coerceAtLeast(0L),
            realPlayers = raw.optInt("realPlayers", 0).coerceAtLeast(0),
            playerbotsEnabled = raw.optBoolean("playerbotsEnabled", false),
        )
    }

    /**
     * The joined-mode line — the desktop budgetLine people half
     * (desktop HomeScreen.kt:1507-1511) under the placeholder's own noun:
     * the real-player count with the you-plus-guests split, falling back
     * to the online-session count when the world reports none (the host
     * plays clientless). Null renders nothing.
     */
    fun line(players: LanPlayersOnline?): String? = when {
        players == null -> null
        // bots labeled, never padded. With the bot lane
        // armed, the session count includes bot sessions (the promotion
        // policy's own documented caveat) — render the human count and
        // label the bot sessions, never "N players" over a padded total.
        players.realPlayers > 0 ->
            "Connected players: ${players.realPlayers} players in world (you + ${players.guests} guests)"
        players.playerbotsEnabled && players.activeSessions > 0 ->
            "No players in world — ${players.activeSessions} bot session" +
                (if (players.activeSessions == 1L) "" else "s") + " running"
        players.activeSessions > 0 ->
            "Connected players: ${players.activeSessions} players in world"
        else -> "No players in world"
    }
}
