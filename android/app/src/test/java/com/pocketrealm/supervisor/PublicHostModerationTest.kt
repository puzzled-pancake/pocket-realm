package com.pocketrealm.supervisor

import com.pocketrealm.discovery.iroh.JoinHello
import com.pocketrealm.discovery.iroh.JoinPeer
import com.pocketrealm.discovery.iroh.SignupProtocol
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The public host's ban store, sign-up limits and ban verbs. */
class PublicHostModerationTest {

    @get:Rule val temp = TemporaryFolder()

    private var nowMs = 1_000_000_000L
    private val file get() = temp.root.resolve(PublicHostModerationStore.FILE_NAME)
    private fun store() = PublicHostModerationStore(file) { nowMs }

    private val phone = JoinPeer("a1".repeat(32), "b1".repeat(32))
    private val laptop = JoinPeer("a2".repeat(32), "b2".repeat(32))

    /** The unlocked-game admission (today's behavior minus the password). */
    private fun PublicHostModerationStore.admitWelcome(peer: JoinPeer): SignupProtocol.Reply = admit(
        peer,
        JoinHello.Request(JoinHello.SCHEMA, peer.deviceToken),
        locked = false,
        expectedProof = null,
        playerLimit = null,
        onlinePlayers = 0,
    )

    @Test
    fun anUnknownDeviceIsWelcomeAndABannedDeviceOrNodeIsRefused() {
        val store = store()
        assertTrue(store.admitWelcome(phone).ok)
        store.recordLogin("Wanderer", phone)
        assertEquals(listOf(phone), store.banDevicesOf("wanderer", "griefing"))
        assertEquals(JoinHello.BANNED, store.admitWelcome(phone).code)
        // The same node with a fresh device token is still refused, and so
        // is the same device behind a fresh node.
        assertFalse(store.admitWelcome(JoinPeer("ff".repeat(32), phone.nodeHex)).ok)
        assertFalse(store.admitWelcome(JoinPeer(phone.deviceToken, "ee".repeat(32))).ok)
        assertTrue(store.admitWelcome(laptop).ok)
    }

    @Test
    fun bansAndSightingsPersistAcrossInstancesAndUnbanLiftsThem() {
        store().recordLogin("Wanderer", phone)
        store().banDevicesOf("Wanderer", "griefing")
        val reread = store()
        assertTrue(reread.isBanned(phone))
        assertEquals("WANDERER", reread.deviceBans().single().account)
        assertTrue(reread.unbanDevice(phone.deviceToken))
        assertFalse(store().isBanned(phone))
        assertFalse("nothing left to lift", store().unbanDevice(phone.deviceToken))
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndTheNextWriteHealsIt() {
        file.writeText("{not json")
        assertTrue(store().admitWelcome(phone).ok)
        store().recordLogin("Fresh", phone)
        assertEquals(listOf("FRESH"), store().players(emptyList()).map { it.account })
    }

    @Test
    fun signUpNeedsAHelloAndRespectsThePerDeviceLimits() {
        val store = store()
        assertEquals(SignupProtocol.REQUEST_INVALID, store.signupGate(null)?.code)
        assertNull(store.signupGate(phone))
        store.recordSignup("One", phone)
        // Too soon after the last one from this device.
        assertEquals(SignupProtocol.RATE_LIMITED, store.signupGate(phone)?.code)
        assertNull("another device is unaffected", store.signupGate(laptop))
        nowMs += PublicHostModerationStore.MIN_SIGNUP_INTERVAL_MS
        assertNull(store.signupGate(phone))
        store.recordSignup("Two", phone)
        nowMs += PublicHostModerationStore.MIN_SIGNUP_INTERVAL_MS
        store.recordSignup("Three", phone)
        nowMs += PublicHostModerationStore.MIN_SIGNUP_INTERVAL_MS
        val capped = store.signupGate(phone)
        assertEquals(SignupProtocol.RATE_LIMITED, capped?.code)
        assertTrue(capped!!.detail.contains("${PublicHostModerationStore.MAX_ACCOUNTS_PER_DEVICE}"))
        // A banned device cannot sign up at all.
        store.banDevicesOf("One", "spam")
        assertEquals(JoinHello.BANNED, store.signupGate(phone)?.code)
    }

    @Test
    fun theWholeRealmTakesABoundedNumberOfSignUpsPerHour() {
        val store = store()
        repeat(PublicHostModerationStore.MAX_SIGNUPS_PER_HOUR) { index ->
            val peer = JoinPeer("%064x".format(index + 1), "%064x".format(index + 1_000))
            store.recordSignup("Name$index", peer)
        }
        val fresh = JoinPeer("c".repeat(64), "d".repeat(64))
        assertEquals(SignupProtocol.RATE_LIMITED, store.signupGate(fresh)?.code)
        nowMs += 60L * 60L * 1_000L
        assertNull(store.signupGate(fresh))
    }

    @Test
    fun thePlayersListJoinsSignUpsSightingsAndLivePresence() {
        val store = store()
        store.recordSignup("Wanderer", phone)
        nowMs += 1_000
        store.recordLogin("OTHER", laptop)
        store.markConnected(laptop)
        val signedUp = listOf(SignupAccountStore.Entry("Wanderer", 5L))
        val rows = store.players(signedUp)
        assertEquals(listOf("OTHER", "Wanderer"), rows.map { it.account })
        val other = rows.first()
        assertTrue(other.online)
        assertNull(other.signedUpAtMs)
        val wanderer = rows.last()
        assertEquals(5L, wanderer.signedUpAtMs)
        assertEquals(1, wanderer.devices)
        assertFalse(wanderer.online)
        store.markLeft(laptop)
        assertFalse(store.players(signedUp).first { it.account == "OTHER" }.online)
        // The JSON the Android host screen reads carries the same facts.
        val json = JSONObject(store.playersJson(signedUp))
        assertEquals(2, json.getJSONArray("players").length())
        val parsed = PublicPlayersCopy.rowFromJson(json.getJSONArray("players").getJSONObject(0))
        assertEquals(store.players(signedUp).first(), parsed)
    }

    @Test
    fun theModeratorBansTheAccountThroughTheRealmAndTheDeviceThroughTheStore() = runBlocking {
        val store = store()
        store.recordLogin("Wanderer", phone)
        val banned = mutableListOf<Pair<String, String>>()
        val dropped = mutableListOf<Pair<String?, String?>>()
        val moderator = PublicHostModerator(
            store = store,
            banAccount = { name, reason -> banned += name to reason; ModerationResult(true, "ok") },
            unbanAccount = { ModerationResult(true, "ok") },
            disconnect = { device, node -> dropped += device to node },
        )
        val both = moderator.act(PublicHostModerator.Action.BAN_BOTH, "Wanderer")
        assertTrue(both.ok)
        assertEquals(listOf("Wanderer" to PublicHostModerator.DEFAULT_REASON), banned)
        assertEquals(listOf<Pair<String?, String?>>(phone.deviceToken to phone.nodeHex), dropped)
        val row = store.players(emptyList()).single()
        assertTrue(row.accountBanned)
        assertTrue(row.deviceBanned)
        assertTrue(moderator.act(PublicHostModerator.Action.UNBAN_DEVICE, "Wanderer").ok)
        assertTrue(moderator.act(PublicHostModerator.Action.UNBAN_ACCOUNT, "Wanderer").ok)
        val lifted = store.players(emptyList()).single()
        assertFalse(lifted.accountBanned)
        assertFalse(lifted.deviceBanned)
    }

    @Test
    fun aDeviceBanNeedsAKnownDeviceAndAStoppedRealmSaysSoPlainly() = runBlocking {
        val moderator = PublicHostModerator(
            store = store(),
            banAccount = { _, _ -> ModerationResult(false, "world-not-ready") },
            unbanAccount = { ModerationResult(false, "world-not-ready") },
            disconnect = { _, _ -> },
        )
        val device = moderator.act(PublicHostModerator.Action.BAN_DEVICE, "Nobody")
        assertFalse(device.ok)
        assertTrue(device.detail.contains("No device is known"))
        val account = moderator.act(PublicHostModerator.Action.BAN_ACCOUNT, "Nobody")
        assertFalse(account.ok)
        assertEquals("Start the realm first, then try again.", account.detail)
    }

    @Test
    fun theStatusLineIsPlain() {
        val row = PublicHostModerationStore.PlayerRow(
            account = "Wanderer", signedUpAtMs = 1L, lastSeenMs = 0L, devices = 2,
            online = false, accountBanned = true, deviceBanned = false,
        )
        assertEquals(
            "Last seen 3h ago · 2 devices · signed up here · account banned",
            PublicPlayersCopy.statusLine(row, 3L * 60L * 60L * 1_000L),
        )
        assertNotNull(PublicPlayersCopy.statusLine(row.copy(online = true, lastSeenMs = null), 0L))
        assertEquals("under a minute ago", PublicPlayersCopy.ago(-5L))
    }
    // ------------------------------------------------- the password gate

    private val hostNode = "9a".repeat(32)
    private fun proofFor(password: String, joinerNode: String) =
        com.pocketrealm.discovery.iroh.JoinPasscode.proof(
            com.pocketrealm.discovery.iroh.JoinPasscode.keyHex(password), hostNode, joinerNode,
        )

    private fun PublicHostModerationStore.admitLocked(
        peer: JoinPeer,
        password: String,
        presented: String,
        playerLimit: Int? = null,
        onlinePlayers: Int = 0,
    ): SignupProtocol.Reply = admit(
        peer,
        JoinHello.Request(JoinHello.SCHEMA, peer.deviceToken, presented),
        locked = true,
        expectedProof = proofFor(password, peer.nodeHex),
        playerLimit = playerLimit,
        onlinePlayers = onlinePlayers,
    )

    @Test
    fun aLockedGameWelcomesTheRightPasswordAndRefusesAWrongOne() {
        val store = store()
        assertEquals(JoinHello.WELCOME, store.admitLocked(phone, "sesame", proofFor("sesame", phone.nodeHex)).code)
        assertEquals(
            JoinHello.PASSWORD_WRONG,
            store.admitLocked(phone, "sesame", proofFor("open", phone.nodeHex)).code,
        )
    }

    @Test
    fun theSixthWrongTryIsLockedOutAndTheWindowReopens() {
        val store = store()
        val wrong = proofFor("open", phone.nodeHex)
        repeat(5) {
            assertEquals(JoinHello.PASSWORD_WRONG, store.admitLocked(phone, "sesame", wrong).code)
        }
        // The 6th try — even with the RIGHT password — is told to wait.
        assertEquals(
            JoinHello.TRY_LATER,
            store.admitLocked(phone, "sesame", proofFor("sesame", phone.nodeHex)).code,
        )
        // The lockout also bites a fresh device token on the same node.
        assertEquals(
            JoinHello.TRY_LATER,
            store.admitLocked(JoinPeer("ff".repeat(32), phone.nodeHex), "sesame", wrong).code,
        )
        // Ten minutes later the window reopens.
        nowMs += PublicHostModerationStore.PASSWORD_LOCKOUT_MS
        assertEquals(
            JoinHello.PASSWORD_WRONG,
            store.admitLocked(phone, "sesame", wrong).code,
        )
        assertEquals(
            JoinHello.WELCOME,
            store.admitLocked(phone, "sesame", proofFor("sesame", phone.nodeHex)).code,
        )
    }

    @Test
    fun aSchema1JoinerIsAskedForThePasswordOnALockedGameAndWelcomedOnAnOpenOne() {
        val store = store()
        val v1 = JoinHello.Request(JoinHello.SCHEMA_1, phone.deviceToken)
        assertEquals(
            JoinHello.PASSWORD_REQUIRED,
            store.admit(phone, v1, locked = true, expectedProof = proofFor("sesame", phone.nodeHex),
                playerLimit = null, onlinePlayers = 0).code,
        )
        // A schema-2 hello with no proof is asked the same way.
        assertEquals(
            JoinHello.PASSWORD_REQUIRED,
            store.admit(phone, JoinHello.Request(JoinHello.SCHEMA, phone.deviceToken),
                locked = true, expectedProof = proofFor("sesame", phone.nodeHex),
                playerLimit = null, onlinePlayers = 0).code,
        )
        // On an open game the older app is still admitted.
        assertEquals(JoinHello.WELCOME, store.admitWelcome(phone).code)
    }

    @Test
    fun aFullGameRefusesAtThePlayerLimit() {
        val store = store()
        assertEquals(
            JoinHello.FULL,
            store.admit(phone, JoinHello.Request(JoinHello.SCHEMA, phone.deviceToken),
                locked = false, expectedProof = null, playerLimit = 100, onlinePlayers = 100).code,
        )
        // One below the limit is welcome; on a locked game the password
        // is answered first.
        assertEquals(
            JoinHello.WELCOME,
            store.admitLocked(phone, "sesame", proofFor("sesame", phone.nodeHex),
                playerLimit = 100, onlinePlayers = 99).code,
        )
    }

    @Test
    fun aLockedGameWithoutAHostProofStaysLocked() {
        // The host's own node id not known yet: never open the door.
        val store = store()
        assertEquals(
            JoinHello.PASSWORD_REQUIRED,
            store.admit(phone, JoinHello.Request(JoinHello.SCHEMA, phone.deviceToken, "ab".repeat(32)),
                locked = true, expectedProof = null, playerLimit = null, onlinePlayers = 0).code,
        )
    }

}
