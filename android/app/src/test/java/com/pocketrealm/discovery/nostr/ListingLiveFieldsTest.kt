package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The public listing's player limit and host app version, and the
 * change-driven republish rule both publishers share. */
class ListingLiveFieldsTest {

    private val store = ListingStore { 0L }
    private val stage = com.pocketrealm.client.TimeframeStages.defaultStageId("wotlk")

    private fun content(maxPlayers: Int? = null, appVersion: String? = null, humans: Int = 1) =
        RealmListingContent(
            name = "Northdale", motd = "Casual, all welcome", flavor = "wotlk", build = ClientFlavors.WOTLK.build,
            timeframeStage = stage, rates = null, humans = humans, bots = 40,
            uptimeSeconds = 60L, signupOpen = true, maxPlayers = maxPlayers, appVersion = appVersion,
        )

    @Test
    fun theNewFieldsRoundTripThroughTheBrowserLadder() {
        val published = content(maxPlayers = 10, appVersion = "v0.110.0")
        val json = published.toJsonString()
        assertTrue(json.endsWith(",\"maxPlayers\":10,\"appVersion\":\"v0.110.0\"}"))
        assertEquals(ListingStore.ContentParse.Ok(published), store.parseContent(json))
    }

    @Test
    fun aListingWithoutThemKeepsItsExactOlderBytes() {
        val json = content().toJsonString()
        assertFalse(json.contains("maxPlayers"))
        assertFalse(json.contains("appVersion"))
        assertTrue(json.endsWith(",\"signupOpen\":true}"))
        val parsed = store.parseContent(json) as ListingStore.ContentParse.Ok
        assertNull(parsed.content.maxPlayers)
        assertNull(parsed.content.appVersion)
    }

    @Test
    fun badShapesAreRefusedNeverCoerced() {
        val base = content().toJsonString().removeSuffix("}")
        for (extra in listOf(
            ",\"maxPlayers\":0", ",\"maxPlayers\":-3", ",\"maxPlayers\":\"10\"", ",\"maxPlayers\":1.5",
            ",\"appVersion\":\"0.110.0\"", ",\"appVersion\":\"v10.0.0.1.2\"", ",\"appVersion\":7",
            ",\"appVersion\":\"vhttp\"",
        )) {
            assertTrue("expected refusal for $extra", store.parseContent("$base$extra}") is ListingStore.ContentParse.Refused)
        }
        assertTrue(store.parseContent("$base,\"maxPlayers\":null,\"appVersion\":null}") is ListingStore.ContentParse.Ok)
    }

    @Test
    fun theSigningBoundaryRefusesBadValuesToo() {
        val key = NostrPrivateKey.generate()
        assertTrue(runCatching { RealmListingEvent.build(content(maxPlayers = 0), key, 1L) }.isFailure)
        assertTrue(runCatching { RealmListingEvent.build(content(appVersion = "1.2.3"), key, 1L) }.isFailure)
        val event = RealmListingEvent.build(content(maxPlayers = 10, appVersion = "v1.2.3"), key, 1L)
        assertTrue(RealmListingEvent.verifyListing(event).ok)
    }

    @Test
    fun theAppVersionTagKeepsVersionsClearOfTheAddressFence() {
        assertEquals("v0.110.0", RealmListingContent.appVersionTag("0.110.0"))
        assertEquals("v0.110.0", RealmListingContent.appVersionTag("v0.110.0"))
        assertNull(RealmListingContent.appVersionTag("0.110.0-debug"))
        assertNull(RealmListingContent.appVersionTag(null))
        // The tag passes the free-text fence the bare dotted number fails.
        PrivacyGuard.refuseIfBanned("v0.110.0", "app")
        assertTrue(runCatching { PrivacyGuard.refuseIfBanned("0.110.0", "app") }.isFailure)
    }

    @Test
    fun aChangedListingRepublishesEarlyButUptimeAloneNeverDoes() {
        val live = content(maxPlayers = 10)
        assertFalse(RealmListingContract.factsChanged(live, live.copy(uptimeSeconds = 999L)))
        assertTrue(RealmListingContract.factsChanged(live, live.copy(humans = 2)))
        assertTrue(RealmListingContract.factsChanged(live, live.copy(signupOpen = false)))
        assertTrue(RealmListingContract.factsChanged(null, live))
        assertFalse(RealmListingContract.factsChanged(null, null))
        val window = RealmListingContract.CHANGE_REPUBLISH_SECONDS * 1_000L
        assertFalse(RealmListingContract.changeWindowOpen(null, window))
        assertFalse(RealmListingContract.changeWindowOpen(0L, window - 1))
        assertTrue(RealmListingContract.changeWindowOpen(0L, window))
        assertTrue(RealmListingContract.CHANGE_REPUBLISH_SECONDS < RealmListingContract.HEARTBEAT_SECONDS)
    }
}
