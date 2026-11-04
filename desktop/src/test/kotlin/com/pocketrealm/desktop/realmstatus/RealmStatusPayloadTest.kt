package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.JoinKind
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.PlayerSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.Rates
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.StatusSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.WorldCounts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `GET /api/status.json` payload contract's serialization pins
 * (design realm-status-browser.md, schema 3): the PUBLIC trim set,
 * the degraded fail-closed nulls, the structural world-down zeros, the
 * two-case advertised-address rule, the join-axis keying, and the
 * MOTD clamp's hostile vectors.
 */
class RealmStatusPayloadTest {

    @Suppress("LongParameterList") // the helper mirrors the input bundle; defaults keep each pin's focus
    private fun inputs(
        visibility: RealmStatusVisibility = RealmStatusVisibility.PUBLIC,
        worldRunning: Boolean = true,
        statusSource: StatusSource = StatusSource.LIVE,
        players: RealmStatusPayload.Players = nativePlayers(),
        advertisedAddress: String? = null,
        joinKind: JoinKind? = null,
        worldStartedAtWallMs: Long? = 1_791_990_000_000L,
        generatedAtWallMs: Long = 1_791_997_200_456L,
        tunnelState: TunnelState = TunnelState.LIVE,
        publicUrl: String? = "https://warm-underscore-example.trycloudflare.com",
        realmName: String? = "Host's Realm",
        description: String = "Chill vanilla levelling realm.",
        rates: Rates = Rates.engineDefault(),
    ) = RealmStatusPayload.Inputs(
        visibility = visibility,
        generatedAtWallMs = generatedAtWallMs,
        realmName = realmName,
        flavor = "vanilla",
        timeframeStageId = "vanilla-1.12.1",
        timeframeTitle = "Patch 1.12.1 — current release content",
        timeframeIsFinal = true,
        description = description,
        worldRunning = worldRunning,
            worldStartedAtWallMs = worldStartedAtWallMs,
            recordSeconds = 7_320L,
            rates = rates,
        players = players,
        worldPrepared = true,
        worldGeneration = "a1b2c3d4",
        worldCounts = WorldCounts(212, 173, 42, 173, 4_198),
        statusSource = statusSource,
        phase = "RUNNING",
        mode = "LAN_HOST",
        clean = false,
        advertisedAddress = advertisedAddress,
        joinKind = joinKind ?: if (visibility == RealmStatusVisibility.PUBLIC) {
            JoinKind.NONE
        } else {
            JoinKind.TUNNEL_INVITE
        },
        publicUrl = publicUrl,
        tunnelState = tunnelState,
        characterNamesInPublic = false,
    )

    private fun nativePlayers(
        source: PlayerSource = PlayerSource.NATIVE,
        crossCheckOk: Boolean = true,
    ) = RealmStatusPayload.Players(
        source = source,
        crossCheckOk = crossCheckOk,
        humansOnline = 3,
        botsOnline = 47,
        totalOnline = 50,
        botTarget = 50,
    )

    @Test
    fun `the public shape trims the topology and fingerprint set to null`() {
        val payload = RealmStatusPayload.build(inputs())
        assertEquals(3, payload.getInt("schema"))
        assertEquals("public", payload.getString("visibility"))
        val supervisor = payload.getJSONObject("supervisor")
        assertTrue("phase trimmed in PUBLIC", supervisor.isNull("phase"))
        assertTrue("mode trimmed in PUBLIC", supervisor.isNull("mode"))
        assertTrue("clean trimmed in PUBLIC", supervisor.isNull("clean"))
        assertTrue(
            "advertisedAddress null in PUBLIC, always (§7's two-case rule)",
            supervisor.isNull("advertisedAddress"),
        )
        assertTrue("generation trimmed in PUBLIC", payload.getJSONObject("world").isNull("generation"))
        assertTrue("botTarget trimmed in PUBLIC", payload.getJSONObject("players").isNull("botTarget"))
        assertTrue(
            "startedAtWallMs trimmed in PUBLIC (fingerprinting)",
            payload.getJSONObject("uptime").isNull("startedAtWallMs"),
        )
        assertEquals("none", payload.getJSONObject("join").getString("kind"))
    }

    @Test
    fun `public timestamps are minute-coarse`() {
        val payload = RealmStatusPayload.build(inputs(generatedAtWallMs = 1_791_997_200_456L))
        assertEquals(
            "generatedAt floored to the minute in PUBLIC",
            1_791_997_200_000L,
            payload.getLong("generatedAtWallMs"),
        )
        assertEquals(
            1_791_997_200_000L % 60_000L,
            payload.getLong("generatedAtWallMs") % 60_000L,
        )
    }

    @Test
    fun `the public liveness pairing rule holds - realmOnline with a live status source`() {
        // its pairing rule: realmOnline true REQUIRES statusSource live;
        // realmOnline is null ONLY when degraded (no false offline claim).
        val payload = RealmStatusPayload.build(inputs())
        assertEquals(true, payload.getJSONObject("supervisor").getBoolean("realmOnline"))
        assertEquals("live", payload.getJSONObject("supervisor").getString("statusSource"))
    }

    @Test
    fun `degraded status publishes no offline claim and nulls the split`() {
        // realmOnline null (never false), uptime 0, and the
        // players block fail-closes to NULLS — never a 0-split. The two
        // degrade axes are independent (the pairing note): the players
        // block carries its OWN source label here.
        val payload = RealmStatusPayload.build(
            inputs(
                statusSource = StatusSource.DEGRADED,
                players = nativePlayers(source = PlayerSource.DEGRADED),
            ),
        )
        val supervisor = payload.getJSONObject("supervisor")
        assertTrue("realmOnline is null when degraded", supervisor.isNull("realmOnline"))
        assertEquals("degraded", supervisor.getString("statusSource"))
        assertEquals(0L, payload.getJSONObject("uptime").getLong("seconds"))
        val players = payload.getJSONObject("players")
        assertTrue(players.isNull("humansOnline"))
        assertTrue(players.isNull("botsOnline"))
        assertTrue(players.isNull("totalOnline"))
        assertEquals("degraded", players.getString("source"))
        assertEquals(false, players.getBoolean("splitAvailable"))
        assertTrue("crossCheckOk is an independent axis", players.getBoolean("crossCheckOk"))
        assertTrue("bots are labeled even when the lane is down", players.getBoolean("botsLabeled"))
    }

    @Test
    fun `the world-down zeros are structural and labeled world-down`() {
        // no world means no players by construction; the
        // label is world-down, never "native" (which claims a lane).
        val payload = RealmStatusPayload.build(
            inputs(
                worldRunning = false,
                worldStartedAtWallMs = null,
                players = RealmStatusPayload.Players(
                    source = PlayerSource.WORLD_DOWN,
                    crossCheckOk = true,
                    humansOnline = 0,
                    botsOnline = 0,
                    totalOnline = 0,
                ),
                tunnelState = TunnelState.LIVE,
            ),
        )
        assertEquals(false, payload.getJSONObject("supervisor").getBoolean("realmOnline"))
        assertEquals(0L, payload.getJSONObject("uptime").getLong("seconds"))
        val players = payload.getJSONObject("players")
        assertEquals("world-down", players.getString("source"))
        assertEquals("the structural zeros are NUMBERS (T10)", 0, players.getInt("humansOnline"))
        assertEquals(0, players.getInt("botsOnline"))
        assertEquals(0, players.getInt("totalOnline"))
        assertFalse("a structural zero split is not a lane-verified split", players.getBoolean("splitAvailable"))
        assertTrue(
            "the world block survives the stop (prepared data persists)",
            payload.getJSONObject("world").getBoolean("prepared"),
        )
        assertEquals(
            "the public link stays live through a realm stop (§1)",
            "live",
            payload.getJSONObject("public").getString("tunnelState"),
        )
    }

    @Test
    fun `a native lane carries the real split with the mandatory labels`() {
        val payload = RealmStatusPayload.build(inputs(visibility = RealmStatusVisibility.FRIENDS))
        val players = payload.getJSONObject("players")
        assertEquals(3, players.getInt("humansOnline"))
        assertEquals(47, players.getInt("botsOnline"))
        assertEquals(50, players.getInt("totalOnline"))
        assertEquals("native", players.getString("source"))
        assertTrue(players.getBoolean("splitAvailable"))
        assertTrue(players.getBoolean("crossCheckOk"))
        assertTrue("botsLabeled is ALWAYS true (convergence §5 Tier 3)", players.getBoolean("botsLabeled"))
        assertEquals(50, players.getInt("botTarget"))
    }

    @Test
    fun `a cross-check failure labels the lane and nulls the split`() {
        val payload = RealmStatusPayload.build(
            inputs(players = nativePlayers(source = PlayerSource.CROSS_CHECK_FAILED, crossCheckOk = false)),
        )
        val players = payload.getJSONObject("players")
        assertEquals("cross-check-failed", players.getString("source"))
        assertTrue(players.isNull("humansOnline"))
        assertTrue(players.isNull("botsOnline"))
        assertTrue(players.isNull("totalOnline"))
        assertFalse(players.getBoolean("crossCheckOk"))
    }

    @Test
    fun `friends carries the topology the public trim removes`() {
        val payload = RealmStatusPayload.build(
            inputs(
                visibility = RealmStatusVisibility.FRIENDS,
                advertisedAddress = "100.64.1.2",
                generatedAtWallMs = 1_791_997_200_456L,
            ),
        )
        assertEquals("friends", payload.getString("visibility"))
        assertEquals("RUNNING", payload.getJSONObject("supervisor").getString("phase"))
        assertEquals("LAN_HOST", payload.getJSONObject("supervisor").getString("mode"))
        assertEquals(false, payload.getJSONObject("supervisor").getBoolean("clean"))
        // its two cases, case (a) the FRIENDS tunnel lane carries the
        // frozen advertisement — grammar-bounded by construction upstream.
        assertEquals("100.64.1.2", payload.getJSONObject("supervisor").getString("advertisedAddress"))
        assertEquals("tunnel-invite", payload.getJSONObject("join").getString("kind"))
        assertEquals(
            "FRIENDS keeps exact timestamps",
            1_791_997_200_456L,
            payload.getLong("generatedAtWallMs"),
        )
        assertEquals(
            1_791_990_000_000L,
            payload.getJSONObject("uptime").getLong("startedAtWallMs"),
        )
        assertEquals("a1b2c3d4", payload.getJSONObject("world").getString("generation"))
    }

    @Test
    fun `friends plain-lan carries a null advertised address and the lan join kind`() {
        // its two cases, case (b) the plain-LAN lane is null on either
        // DB provider; the LAN address appears only in the PAGE's join
        // section, never in the payload.
        val payload = RealmStatusPayload.build(
            inputs(
                visibility = RealmStatusVisibility.FRIENDS,
                advertisedAddress = null,
                joinKind = JoinKind.LAN_INVITE,
            ),
        )
        assertTrue(payload.getJSONObject("supervisor").isNull("advertisedAddress"))
        assertEquals("lan-invite", payload.getJSONObject("join").getString("kind"))
        assertTrue(
            "the FRIENDS hint is address-free",
            !payload.getJSONObject("join").getString("hint").contains("192.168."),
        )
    }

    @Test
    fun `a dead tunnel never shows a stale url`() {
        // when tunnelState != live, public.url is null.
        val payload = RealmStatusPayload.build(
            inputs(tunnelState = TunnelState.DOWN, publicUrl = "https://warm-underscore-example.trycloudflare.com"),
        )
        assertTrue(payload.getJSONObject("public").isNull("url"))
        assertEquals("down", payload.getJSONObject("public").getString("tunnelState"))
        assertTrue(payload.getJSONObject("public").getBoolean("readOnly"))
    }

    @Test
    fun `the motd clamp strips control characters and caps at 280 - the hostile vector`() {
        // T13d's vector shape: script tag + over-280 length + control chars.
        val hostile = "<script>alert(1)</script>" + "x".repeat(300) + "\u0000\u0007\u001b[31m"
        val clamped = RealmStatusPayload.clampDescription(hostile)
        assertTrue("clamped to the cap", clamped.length <= RealmStatusPayload.MOTD_MAX_LENGTH)
        assertTrue("control characters stripped", clamped.none { it.isISOControl() })
        val payload = RealmStatusPayload.build(
            inputs(description = clamped),
        )
        assertEquals(clamped, payload.getJSONObject("realm").getString("description"))
    }

    @Test
    fun `a blank realm name falls back to the generic my realm`() {
        val payload = RealmStatusPayload.build(inputs(realmName = null))
        assertEquals("my realm", payload.getJSONObject("realm").getString("name"))
    }

    @Test
    fun `the rates block carries the honest source labels`() {
        val running = RealmStatusPayload.build(inputs())
        assertEquals(1.0, running.getJSONObject("rates").getDouble("xpKill"), 0.0)
        assertEquals("engine-default", running.getJSONObject("rates").getString("source"))
        // the down state reports the conf lane as not-running (the
        // controller feeds notRunning(); the builder serializes verbatim).
        val down = RealmStatusPayload.build(
            inputs(
                worldRunning = false,
                rates = Rates.notRunning(),
                players = RealmStatusPayload.Players(
                    source = PlayerSource.WORLD_DOWN,
                    crossCheckOk = true,
                    humansOnline = 0,
                    botsOnline = 0,
                    totalOnline = 0,
                ),
            ),
        )
        assertTrue(down.getJSONObject("rates").isNull("xpKill"))
        assertEquals("not-running", down.getJSONObject("rates").getString("source"))
    }

    @Test
    fun `the url shape gate accepts only trycloudflare https hosts`() {
        assertTrue(
            RealmStatusPayload.isShapeValidPublicUrl("https://warm-underscore-example.trycloudflare.com"),
        )
        assertFalse(RealmStatusPayload.isShapeValidPublicUrl("https://evil.example.com/x"))
        assertFalse(RealmStatusPayload.isShapeValidPublicUrl("http://warm.trycloudflare.com"))
        assertFalse(RealmStatusPayload.isShapeValidPublicUrl("https://Warm_Under.example.trycloudflare.com"))
        assertFalse(RealmStatusPayload.isShapeValidPublicUrl(null))
        assertFalse(RealmStatusPayload.isShapeValidPublicUrl(""))
    }

    @Test
    fun `the roster rides only when the caller provides it`() {
        val withNames = nativePlayers().copy(characterNames = listOf("Aelwyn", "Brann"))
        val payload = RealmStatusPayload.build(inputs(visibility = RealmStatusVisibility.FRIENDS, players = withNames))
        assertEquals(listOf("Aelwyn", "Brann"), names(payload))
        val without = RealmStatusPayload.build(inputs())
        assertTrue(
            "absent by default (§3.4) — no key at all, never a null member",
            !without.getJSONObject("players").has("characterNames"),
        )
    }

    private fun names(payload: JSONObject): List<String> {
        val array = payload.getJSONObject("players").getJSONArray("characterNames")
        return (0 until array.length()).map { array.getString(it) }
    }
}
