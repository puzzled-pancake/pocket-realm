package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostListingPublisherTest {

    private class FakeCarrier : HostListingPublisher.Carrier {
        override val reports = MutableStateFlow<Map<String, RelayPool.RelayReport>>(emptyMap())
        override val acks = MutableSharedFlow<RelayPool.WriteAck>(extraBufferCapacity = 16)
        val published = mutableListOf<NostrEvent>()
        var stopped = false
        override fun publish(event: NostrEvent) {
            published += event
        }
        override fun stop() {
            stopped = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val key = NostrPrivateKey.generate()
    private val descriptor = IrohJoinDescriptor(nodeHex = "cd".repeat(32), relayUrl = "https://relay.example.com/")
    private var nowMs = 1_800_000_000_000L
    private var content: RealmListingContent? = HostListingPublisher.contentFor(
        realmName = "Night Harbor",
        flavorId = ClientFlavors.VANILLA.flavorId,
        timeframeStage = com.pocketrealm.client.TimeframeStages.defaultStageId(ClientFlavors.VANILLA.flavorId),
        worldReady = true,
        realPlayers = 1,
        playerbotsEnabled = true,
        onlinePlayers = 41,
    )
    private var hostResult: Result<IrohJoinDescriptor> = Result.success(descriptor)
    private var hostReady = true
    private var hostEnsures = 0
    private var hostStops = 0
    private val carriers = mutableListOf<FakeCarrier>()

    private val publisher = HostListingPublisher(
        scope = scope,
        seams = HostListingPublisher.Seams(
            clockMs = { nowMs },
            relays = { listOf("wss://relay.example.com") },
            identity = { key },
            content = { content },
            ensureHost = { hostEnsures += 1; hostResult },
            stopHost = { hostStops += 1 },
            hostReady = { hostReady },
            carrierFactory = { FakeCarrier().also { carriers += it } },
            retractDrainMs = 0L,
        ),
    )

    private val on = HostListingPublisher.Gates(online = true, listed = true, hostingWorldUp = true)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun evaluate(gates: HostListingPublisher.Gates) = runBlocking { publisher.evaluate(gates) }

    @Test
    fun nothingIsPublishedOrStartedWhileTheSwitchIsOffOrNoRealmIsHosted() {
        evaluate(HostListingPublisher.Gates(online = false, listed = true, hostingWorldUp = true))
        assertEquals(HostListingPublisher.Phase.OFF, publisher.state.value.phase)
        evaluate(HostListingPublisher.Gates(online = true, listed = true, hostingWorldUp = false))
        assertEquals(HostListingPublisher.Phase.WAITING, publisher.state.value.phase)
        assertEquals(0, hostEnsures)
        assertTrue(carriers.isEmpty())
    }

    @Test
    fun aHostedRealmIsListedWithTheReadyJoinDescriptor() {
        evaluate(on)
        val state = publisher.state.value
        assertEquals(HostListingPublisher.Phase.LIVE, state.phase)
        assertEquals(descriptor, state.descriptor)
        val event = carriers.single().published.single()
        assertEquals(state.listingEventId, event.idHex)
        val body = JSONObject(event.content)
        assertEquals("Night Harbor", body.getString("name"))
        assertEquals(ClientFlavors.VANILLA.build, body.getInt("build"))
        assertEquals(1, body.getJSONObject("players").getInt("humans"))
        assertEquals(40, body.getJSONObject("players").getInt("bots"))
        val join = body.getJSONObject("join")
        assertEquals(descriptor.nodeHex, join.getString("node"))
        assertEquals(descriptor.relayUrl, join.getString("relay"))
        // The browser's own parser admits what the host published.
        assertEquals(descriptor, IrohJoinDescriptor.fromListingJson(join))
        // ...and the browser's admission ladder admits the whole listing.
        assertTrue(ListingStore { 0L }.parseContent(event.content) is ListingStore.ContentParse.Ok)
    }

    @Test
    fun theListingRepublishesOnlyWhenTheHeartbeatIsDue() {
        evaluate(on)
        evaluate(on)
        assertEquals(1, carriers.single().published.size)
        nowMs += RealmListingContract.HEARTBEAT_SECONDS * 1_000L
        evaluate(on)
        assertEquals(2, carriers.single().published.size)
        assertEquals(2, publisher.state.value.beats)
    }

    @Test
    fun aDeadHostEndpointIsReEnsuredAndRepublishedBeforeTheBeat() {
        evaluate(on)
        hostReady = false
        evaluate(on)
        assertEquals(2, hostEnsures)
        assertEquals(2, carriers.single().published.size)
    }

    @Test
    fun aListingWhoseEndpointCannotComeBackIsRetracted() {
        evaluate(on)
        val listing = carriers.single().published.single()
        hostReady = false
        hostResult = Result.failure(IllegalStateException("relay unreachable"))
        evaluate(on)
        val deletion = carriers.single().published.last()
        assertEquals(5, deletion.kind)
        assertTrue(deletion.tags.any { it.size >= 2 && it[0] == "e" && it[1] == listing.idHex })
        assertEquals(HostListingPublisher.Phase.FAILED, publisher.state.value.phase)
        assertNull(publisher.state.value.descriptor)
    }

    @Test
    fun unlistedOnlineHostingKeepsTheEndpointAndRetractsAnyListing() {
        evaluate(on)
        val carrier = carriers.single()
        val listing = carrier.published.single()
        // The host turns "Show in the game list" off: the endpoint STAYS
        // (invite-code joins keep working), the listing retracts, and no
        // new listing publishes.
        evaluate(HostListingPublisher.Gates(online = true, listed = false, hostingWorldUp = true))
        val deletion = carrier.published.last()
        assertEquals(5, deletion.kind)
        assertTrue(deletion.tags.any { it.size >= 2 && it[0] == "e" && it[1] == listing.idHex })
        assertTrue(carrier.stopped)
        assertEquals(HostListingPublisher.Phase.UNLISTED, publisher.state.value.phase)
        assertEquals(descriptor, publisher.state.value.descriptor)
        assertEquals(0, hostStops)
        // Turning it back on publishes again (on a fresh carrier).
        evaluate(HostListingPublisher.Gates(online = true, listed = true, hostingWorldUp = true))
        assertEquals(HostListingPublisher.Phase.LIVE, publisher.state.value.phase)
        assertEquals(2, carriers.size)
        assertEquals(0, hostStops)
    }

    @Test
    fun unlistedHostingStillEnsuresTheEndpoint() {
        evaluate(HostListingPublisher.Gates(online = true, listed = false, hostingWorldUp = true))
        assertEquals(1, hostEnsures)
        assertEquals(HostListingPublisher.Phase.UNLISTED, publisher.state.value.phase)
        assertTrue(carriers.isEmpty())
        assertEquals(0, hostStops)
    }

    @Test
    fun aGateDropRetractsTheListingAndStopsTheEndpoint() {
        evaluate(on)
        val carrier = carriers.single()
        val listing = carrier.published.single()
        evaluate(HostListingPublisher.Gates(online = true, listed = true, hostingWorldUp = false))
        val deletion = carrier.published.last()
        assertEquals(5, deletion.kind)
        assertTrue(deletion.tags.any { it.size >= 2 && it[0] == "e" && it[1] == listing.idHex })
        assertTrue(carrier.stopped)
        assertEquals(1, hostStops)
        assertEquals(HostListingPublisher.Phase.WAITING, publisher.state.value.phase)
        assertNull(publisher.state.value.descriptor)
        // A further drop evaluation neither re-retracts nor re-stops.
        evaluate(HostListingPublisher.Gates(online = false, listed = false, hostingWorldUp = false))
        assertEquals(2, carrier.published.size)
        assertEquals(1, hostStops)
        assertEquals(HostListingPublisher.Phase.OFF, publisher.state.value.phase)
    }

    @Test
    fun shutdownRetractsALiveListing() {
        evaluate(on)
        runBlocking { publisher.shutdown() }
        assertEquals(5, carriers.single().published.last().kind)
        assertEquals(1, hostStops)
    }

    @Test
    fun anEndpointThatCannotComeUpPublishesNothingAndSaysWhy() {
        hostResult = Result.failure(IllegalStateException("relay unreachable"))
        evaluate(on)
        val state = publisher.state.value
        assertEquals(HostListingPublisher.Phase.FAILED, state.phase)
        assertTrue(HostListingPublisher.statusCopy(state).contains("relay unreachable"))
        assertTrue(carriers.isEmpty())
        // The next evaluation retries.
        hostResult = Result.success(descriptor)
        evaluate(on)
        assertEquals(HostListingPublisher.Phase.LIVE, publisher.state.value.phase)
        assertEquals(2, hostEnsures)
    }

    @Test
    fun anUnobservableWorldPausesInsteadOfPublishingGuessedCounts() {
        content = null
        evaluate(on)
        assertEquals(HostListingPublisher.Phase.STARTING, publisher.state.value.phase)
        assertTrue(carriers.isEmpty())
        assertEquals(0, hostEnsures)
    }

    @Test
    fun liveCopyClaimsAListingOnlyOnceARelayAccepted() {
        evaluate(on)
        val sent = HostListingPublisher.statusCopy(publisher.state.value)
        assertTrue(sent, sent.startsWith("Sent"))
        val id = publisher.state.value.listingEventId!!
        runBlocking {
            carriers.single().acks.emit(RelayPool.WriteAck("wss://relay.example.com", id, ok = true, message = ""))
        }
        assertEquals(1, publisher.state.value.acceptedBy)
        assertTrue(HostListingPublisher.statusCopy(publisher.state.value).startsWith("Listed"))
        // An ack for another event never counts.
        runBlocking {
            carriers.single().acks.emit(RelayPool.WriteAck("wss://other.example.com", "00", ok = false, message = ""))
        }
        assertEquals(0, publisher.state.value.rejectedBy)
    }

    @Test
    fun contentSplitsHumansFromBotsAndNeverListsAnUnreadyWorld() {
        val noBots = HostListingPublisher.contentFor(
            realmName = "",
            flavorId = ClientFlavors.TBC.flavorId,
            timeframeStage = "",
            worldReady = true,
            realPlayers = 0,
            playerbotsEnabled = false,
            onlinePlayers = 3,
        )
        assertNotNull(noBots)
        assertEquals(HostListingPublisher.DEFAULT_REALM_NAME, noBots!!.name)
        assertEquals(3, noBots.humans)
        assertEquals(0, noBots.bots)
        assertNull(noBots.join)
        assertNull(
            HostListingPublisher.contentFor("x", ClientFlavors.TBC.flavorId, "", false, 0, false, 0),
        )
    }

    @Test
    fun aNameTheFenceRefusesSurfacesAsAFailureNotAListing() {
        content = null
        val refused = runCatching {
            HostListingPublisher.contentFor(
                realmName = "join me at myhost.example.com",
                flavorId = ClientFlavors.VANILLA.flavorId,
                timeframeStage = "",
                worldReady = true,
                realPlayers = 0,
                playerbotsEnabled = false,
                onlinePlayers = 0,
            )
        }
        assertTrue(refused.isFailure)
        // The refusal speaks the host's words, not the schema's.
        assertTrue(refused.exceptionOrNull()!!.message!!.startsWith("The realm name"))
    }

    @Test
    fun theListingCarriesTheDescriptionPlayerLimitAndAppVersion() {
        val listed = HostListingPublisher.contentFor(
            realmName = "Night Harbor",
            flavorId = ClientFlavors.VANILLA.flavorId,
            timeframeStage = com.pocketrealm.client.TimeframeStages.defaultStageId(ClientFlavors.VANILLA.flavorId),
            worldReady = true,
            realPlayers = 2,
            playerbotsEnabled = true,
            onlinePlayers = 12,
            signupOpen = true,
            description = "  Friendly realm, all welcome  ",
            maxPlayers = 10,
            appVersion = "0.110.0",
        )!!
        assertEquals("Friendly realm, all welcome", listed.motd)
        assertEquals(10, listed.maxPlayers)
        assertEquals("v0.110.0", listed.appVersion)
        assertEquals(2, listed.humans)
        assertEquals(10, listed.bots)
        assertTrue(ListingStore { 0L }.parseContent(listed.toJsonString()) is ListingStore.ContentParse.Ok)
        val badDescription = runCatching {
            HostListingPublisher.contentFor(
                "Night Harbor", ClientFlavors.VANILLA.flavorId, "", true, 0, false, 0,
                description = "join me at myhost.example.com",
            )
        }
        assertTrue(badDescription.exceptionOrNull()!!.message!!.startsWith("The description"))
    }

    @Test
    fun aChangeInPlayersRepublishesAfterTheChangeWindowNotBefore() {
        evaluate(on)
        assertEquals(1, carriers.single().published.size)
        content = content!!.copy(humans = content!!.humans + 1)
        evaluate(on)
        assertEquals("inside the change window nothing goes out", 1, carriers.single().published.size)
        nowMs += RealmListingContract.CHANGE_REPUBLISH_SECONDS * 1_000L
        evaluate(on)
        assertEquals(2, carriers.single().published.size)
        assertEquals(
            content!!.humans,
            JSONObject(carriers.single().published.last().content).getJSONObject("players").getInt("humans"),
        )
        // Unchanged facts wait for the heartbeat.
        nowMs += RealmListingContract.CHANGE_REPUBLISH_SECONDS * 1_000L
        evaluate(on)
        assertEquals(2, carriers.single().published.size)
    }
}
