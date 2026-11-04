package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The owner-authorized listing additions — uptimeSeconds and signupOpen —
 * admitted tolerantly (older listings without them still show) and
 * bounded/typed like the other numbers. */
class ListingSchemaAdditionsTest {

    private val store = ListingStore { 0L }

    private companion object {
        val STAGE: String = com.pocketrealm.client.TimeframeStages.defaultStageId("wotlk")
    }

    private val base = "{\"name\":\"Northdale\",\"motd\":\"\",\"flavor\":\"wotlk\"," +
        "\"build\":${ClientFlavors.WOTLK.build},\"timeframe\":\"${STAGE}\",\"rates\":null," +
        "\"players\":{\"humans\":1,\"bots\":2},\"join\":null,\"statusPageUrl\":null"

    private fun parse(extra: String): ListingStore.ContentParse = store.parseContent("$base$extra}")

    private fun ok(extra: String): RealmListingContent {
        val parsed = parse(extra)
        if (parsed !is ListingStore.ContentParse.Ok) fail("expected admission for '$extra': $parsed")
        return (parsed as ListingStore.ContentParse.Ok).content
    }

    private fun refused(extra: String) {
        assertTrue("expected refusal for '$extra'", parse(extra) is ListingStore.ContentParse.Refused)
    }

    @Test
    fun aListingFromAnOlderPublisherWithoutTheNewKeysStillShows() {
        val content = ok("")
        assertNull(content.uptimeSeconds)
        assertFalse(content.signupOpen)
    }

    @Test
    fun theNewKeysRoundTripFromThePublisherSerializer() {
        val published = RealmListingContent(
            name = "Northdale", motd = "", flavor = "wotlk", build = ClientFlavors.WOTLK.build,
            timeframeStage = STAGE, rates = null, humans = 1, bots = 2,
            uptimeSeconds = 11_520L, signupOpen = true,
        )
        val parsed = store.parseContent(published.toJsonString())
        assertEquals(ListingStore.ContentParse.Ok(published), parsed)
        assertTrue(published.toJsonString().endsWith(",\"uptimeSeconds\":11520,\"signupOpen\":true}"))
    }

    @Test
    fun eachAdditionIsOptionalOnItsOwn() {
        assertEquals(60L, ok(",\"uptimeSeconds\":60").uptimeSeconds)
        assertTrue(ok(",\"signupOpen\":true").signupOpen)
        assertNull(ok(",\"uptimeSeconds\":null,\"signupOpen\":false").uptimeSeconds)
    }

    @Test
    fun uptimeIsAWholeBoundedNumberNeverCoerced() {
        refused(",\"uptimeSeconds\":\"60\"")
        refused(",\"uptimeSeconds\":1.5")
        refused(",\"uptimeSeconds\":true")
        refused(",\"uptimeSeconds\":-1")
        refused(",\"uptimeSeconds\":${RealmListingContent.UPTIME_SECONDS_CEILING + 1}")
        assertEquals(
            RealmListingContent.UPTIME_SECONDS_CEILING,
            ok(",\"uptimeSeconds\":${RealmListingContent.UPTIME_SECONDS_CEILING}").uptimeSeconds,
        )
    }

    @Test
    fun signupOpenMustBeABoolean() {
        refused(",\"signupOpen\":\"true\"")
        refused(",\"signupOpen\":1")
    }

    @Test
    fun anyOtherExtraKeyIsStillAHostileExtension() {
        refused(",\"addr\":\"x\"")
        refused(",\"uptimeSeconds\":60,\"hostIp\":\"x\"")
        // A base key missing is still refused, whatever the additions.
        assertTrue(
            store.parseContent("{\"name\":\"x\",\"uptimeSeconds\":1}") is ListingStore.ContentParse.Refused,
        )
    }

    @Test
    fun thePublisherRefusesAnOutOfRangeUptime() {
        val content = RealmListingContent(
            name = "Northdale", motd = "", flavor = "wotlk", build = ClientFlavors.WOTLK.build,
            timeframeStage = "", rates = null, humans = 0, bots = 0, uptimeSeconds = -5L,
        )
        try {
            RealmListingEvent.build(content, NostrPrivateKey.generate(), 1_760_000_000L)
            fail("a negative uptime must be refused at publish")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun theStatusPayloadUptimeRidesIntoTheListing() {
        val payload = org.json.JSONObject()
            .put("realm", org.json.JSONObject().put("name", "Northdale").put("flavor", "wotlk"))
            .put("players", org.json.JSONObject().put("source", "native").put("humansOnline", 0).put("botsOnline", 0))
            .put("uptime", org.json.JSONObject().put("seconds", 3_600L))
        assertEquals(3_600L, RealmListingContent.fromStatusPayload(payload)!!.uptimeSeconds)
        payload.remove("uptime")
        assertNull(RealmListingContent.fromStatusPayload(payload)!!.uptimeSeconds)
    }
}
