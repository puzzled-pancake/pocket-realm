package com.pocketrealm.supervisor

import com.pocketrealm.server.FriendProvisionProtocol
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shared friend-access verbs (design-remote-accounts §5/§9/§12): the
 * supervisor's provision/ban/gm verbs route to the backend overrides,
 * publish durable journal actions either way, and the [FriendAccessController]
 * approval + moderation flow lands the §12 audit rows.
 */
class FriendAccessHubTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sHex = "aa".repeat(32)
    private val vHex = "bb".repeat(32)

    private class FakeBackend : RuntimeBackend {
        val actions = mutableListOf<String>()
        private val observations = RuntimeComponent.entries.associateWith {
            ComponentObservation(it, ComponentLifecycle.STOPPED, false)
        }.toMutableMap()
        var verifierVerdict = AccountProvisionResult(true, "ACCOUNT_CREATED", accountId = 42L)
        var banOk = true
        var unbanOk = true
        var gmOk = true

        override suspend fun preflight(spec: RuntimeLaunchSpec) = RuntimeActionResult(true, "preflight")
        override suspend fun observe(component: RuntimeComponent) = observations.getValue(component)
        override suspend fun start(
            component: RuntimeComponent,
            owner: ComponentOwner,
            spec: RuntimeLaunchSpec,
        ): ComponentObservation {
            actions += "start:$component"
            val result = ComponentObservation(component, ComponentLifecycle.READY, true, owner, 100, "ready")
            observations[component] = result
            return result
        }
        override suspend fun projectRealmEndpoint(
            databaseOwner: ComponentOwner,
            endpoint: RealmEndpoint,
        ) = RuntimeActionResult(true, "projected")
        override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner) =
            RuntimeActionResult(true, "stopped").also { actions += "stop:$component" }
        override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner) =
            RuntimeActionResult(true, "forced")
        override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner) =
            RuntimeActionResult(false, "nothing to adopt")
        override suspend fun observeWorldPresence() = WorldPresenceSample.EMPTY
        override suspend fun promoteToForeground(component: RuntimeComponent) = RuntimeActionResult(true, "promoted")
        override suspend fun demoteToForeground(component: RuntimeComponent) = RuntimeActionResult(true, "demoted")
        override suspend fun saveWorld(owner: ComponentOwner) = RuntimeActionResult(true, "saved")
        override suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean) =
            RuntimeActionResult(true, "companion")
        override suspend fun provisionAccount(
            owner: ComponentOwner,
            username: String,
            password: String,
            gmLevel: Int,
        ) = AccountProvisionResult(true, "ACCOUNT_CREATED")
        override suspend fun recoverDatabase() = RuntimeActionResult(true, "recovered")

        override suspend fun provisionAccountVerifier(
            username: String,
            sHex: String,
            vHex: String,
            allowExistingAccount: Boolean,
        ): AccountProvisionResult {
            actions += "provision-verifier:$username" +
                (if (allowExistingAccount) ":retry" else "")
            return verifierVerdict
        }
        override suspend fun accountRoster(): RosterRead = RosterRead(
            null,
            listOf(FriendRosterJson.AccountRow(42L, "Dave", 0, 0, banned = false, bot = false, lastLogon = "", online = false)),
        )
        override suspend fun banFriendAccount(username: String, duration: String, reason: String): ModerationResult {
            actions += "ban:$username/$duration"
            return ModerationResult(banOk, if (banOk) "banned" else "refused")
        }
        override suspend fun unbanFriendAccount(username: String): ModerationResult {
            actions += "unban:$username"
            return ModerationResult(unbanOk, if (unbanOk) "unbanned" else "refused")
        }
        override suspend fun setFriendGmLevel(username: String, level: Int): Boolean {
            actions += "gm:$username/$level"
            return gmOk
        }
    }

    private fun supervisor(backend: FakeBackend) = DurableRuntimeSupervisor(
        backend = backend,
        journal = object : SupervisorJournal {
            var last: RuntimeSnapshot? = null
            override fun read() = last
            override fun write(snapshot: RuntimeSnapshot) { last = snapshot }
        },
        tokens = object : RuntimeTokenSource {
            private var next = 0
            override fun sessionId() = "session"
            override fun instanceToken(): String = (++next).toString(16).padStart(2, '0').repeat(32)
        },
        clock = object : RuntimeClock {
            private var value = 1L
            override fun wallMs() = value++
            override fun elapsedMs() = value++
        },
        timeouts = RuntimeTimeouts(1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000),
    )

    @Test
    fun provisionFriendAccountRoutesToTheBackendAndJournalsEitherWay() = runTest {
        val backend = FakeBackend()
        val runtime = supervisor(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)

        val ok = runtime.provisionFriendAccount("Dave", sHex, vHex)
        assertTrue(ok.ok)
        assertEquals(42L, ok.accountId)
        assertEquals("friend-account-provisioned-verifier", runtime.state.value.lastDurableAction)
        assertTrue(backend.actions.contains("provision-verifier:Dave"))

        backend.verifierVerdict = AccountProvisionResult(false, "ACCOUNT_EXISTS")
        val refused = runtime.provisionFriendAccount("Dave", sHex, vHex)
        assertFalse(refused.ok)
        assertEquals("ACCOUNT_EXISTS", refused.code)
        assertEquals("friend-account-provision-failed", runtime.state.value.lastDurableAction)
    }

    @Test
    fun provisionFriendAccountRefusesMalformedVerifiersBeforeTheBackend() = runTest {
        val backend = FakeBackend()
        val runtime = supervisor(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)

        val bad = runtime.provisionFriendAccount("Dave", "ZZ".repeat(32), vHex)
        assertFalse(bad.ok)
        assertEquals("ACCOUNT_INVALID", bad.code)
        assertTrue(
            "the backend must never see a malformed verifier",
            backend.actions.none { it.startsWith("provision-verifier:") },
        )
        assertTrue(
            "uppercase hex is not the wire form",
            !FriendProvisionProtocol.isSrpHex(sHex.uppercase()),
        )
    }

    @Test
    fun moderationVerbsJournalAppliedAndFailed() = runTest {
        val backend = FakeBackend()
        val runtime = supervisor(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)

        val ban = runtime.banFriendAccount("Dave", "1h", "reason")
        assertTrue(ban.ok)
        assertEquals("friend-ban-applied", runtime.state.value.lastDurableAction)

        backend.banOk = false
        assertFalse(runtime.banFriendAccount("Dave", "1h", "reason").ok)
        assertEquals("friend-ban-failed", runtime.state.value.lastDurableAction)

        assertTrue(runtime.unbanFriendAccount("Dave").ok)
        assertEquals("friend-unban-applied", runtime.state.value.lastDurableAction)

        assertTrue(runtime.setFriendGmLevel("Dave", 2))
        assertEquals("friend-gm-applied", runtime.state.value.lastDurableAction)
        assertFalse("gm 4 is outside the 0..3 band", runtime.setFriendGmLevel("Dave", 4))
    }

    @Test
    fun theControllerApprovalFlowLandsProvisionedRowsAndModerationAudit() = runTest {
        val backend = FakeBackend()
        val runtime = supervisor(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        val storeDir = folder.newFolder("friend-access")
        val hub = FriendAccessController(storeDir, backend, runtime)

        val (inviteId, token) = requireNotNull(hub.createInvite("Dave"))
        assertTrue(
            hub.store.submit(
                inviteId, token, "Dave", sHex, vHex, "192.168.1.5",
            ) is FriendInviteStore.SubmitResult.Accepted,
        )
        val verdict = hub.approveInvite(inviteId)
        assertTrue("approve must succeed against the fake backend: $verdict", "Approved:" in verdict)
        assertEquals(FriendInviteStore.State.PROVISIONED, hub.store.rows().single().state)
        assertEquals(42L, hub.store.rows().single().accountId)

        hub.ban("Dave", "1h", "host ban")
        hub.unban("Dave")
        assertTrue(hub.setGmLevel("Dave", 1))
        val actions = hub.auditTail().map { it.optString("action") }
        assertTrue(FriendInviteStore.ACTION_BANNED in actions)
        assertTrue(FriendInviteStore.ACTION_UNBANNED in actions)
        assertTrue(FriendInviteStore.ACTION_GM_CHANGED in actions)
        assertTrue(
            "audit rows never carry s/v",
            hub.auditTail().none { it.toString().contains(sHex) },
        )
        assertEquals(
            "roster reads route through the backend override",
            listOf("Dave"),
            kotlinx.coroutines.runBlocking { hub.roster().accounts.map { it.username } },
        )
        assertTrue(File(storeDir, FriendInviteStore.STORE_FILE_NAME).isFile)
        hub.close()
    }

    @Test
    fun onRuntimeChangedAfterCloseNeverRebindsTheProvisioningSocket() = runTest {
        // The torn-teardown pin (design-remote-accounts §6): close() is
        // terminal. A watcher body already inside onRuntimeChanged when the
        // owner tears down (a coroutine cancel cannot interrupt a body with
        // no suspension points) must not re-bind the provisioning socket
        // after the realm stopped — the socket is a property of a hosting
        // session, never of the app.
        val backend = FakeBackend()
        val runtime = supervisor(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        val hub = FriendAccessController(folder.newFolder("hub-lifecycle"), backend, runtime)

        hub.onRuntimeChanged(
            phase = RuntimePhase.RUNNING,
            mode = RuntimeMode.LAN_HOST,
            friendAccessEnabled = true,
            endpointAddress = "127.0.0.1",
        )
        assertTrue(
            "precondition: the hosting session binds the listener",
            hub.listenerState.value is ProvisioningListener.ListenerState.Listening,
        )

        hub.close()
        assertEquals(ProvisioningListener.ListenerState.Off, hub.listenerState.value)

        // The late watcher emission that races the owner's stop/close.
        hub.onRuntimeChanged(
            phase = RuntimePhase.RUNNING,
            mode = RuntimeMode.LAN_HOST,
            friendAccessEnabled = true,
            endpointAddress = "127.0.0.1",
        )
        assertEquals(
            "close() must be terminal: no late onRuntimeChanged may re-bind the socket",
            ProvisioningListener.ListenerState.Off,
            hub.listenerState.value,
        )
    }
}
