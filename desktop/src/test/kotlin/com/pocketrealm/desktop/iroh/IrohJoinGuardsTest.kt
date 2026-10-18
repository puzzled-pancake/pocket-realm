package com.pocketrealm.desktop.iroh

import com.pocketrealm.supervisor.RuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-N3: the host+join refusal ladder's pure decision table (the ask's
 * "refuse simultaneous host+join on one instance"). The model verbs call
 * THESE — the truth table cannot drift from the wiring — and this test
 * pins every row.
 */
class IrohJoinGuardsTest {

    private val startable = RuntimePhase.STOPPED

    @Test
    fun `a join is refused while a realm stack is up`() {
        for (phase in listOf(
            RuntimePhase.WORLD_READY,
            RuntimePhase.RUNNING,
            RuntimePhase.PAUSED,
            RuntimePhase.CLIENT_FAILED,
        )) {
            val refusal = IrohJoinGuards.joinRefusal(
                phase = phase,
                joinState = IrohGatewayManager.JoinState.Idle,
            )
            assertEquals(
                "phase $phase must refuse the join",
                IrohJoinGuards.VAL_IROH_JOIN_CLASH,
                refusal?.substringBefore(':'),
            )
        }
    }

    @Test
    fun `a join is allowed exactly when the realm stack is startable`() {
        for (phase in listOf(
            RuntimePhase.STOPPED,
            RuntimePhase.UNCONFIGURED,
            RuntimePhase.ERROR,
        )) {
            assertNull(
                "phase $phase must allow a join",
                IrohJoinGuards.joinRefusal(
                    phase = phase,
                    joinState = IrohGatewayManager.JoinState.Idle,
                ),
            )
        }
    }

    @Test
    fun `a second join refuses while one is starting or ready`() {
        for (state in listOf(
            IrohGatewayManager.JoinState.Starting(anyDescriptor),
            IrohGatewayManager.JoinState.Ready(anyDescriptor),
        )) {
            val refusal = IrohJoinGuards.joinRefusal(phase = startable, joinState = state)
            assertEquals(
                IrohJoinGuards.VAL_IROH_JOIN_CLASH,
                refusal?.substringBefore(':'),
            )
        }
    }

    @Test
    fun `a realm start refuses while a join is starting or ready`() {
        for (state in listOf(
            IrohGatewayManager.JoinState.Starting(anyDescriptor),
            IrohGatewayManager.JoinState.Ready(anyDescriptor),
        )) {
            val refusal = IrohJoinGuards.realmStartRefusal(state)
            assertEquals(
                IrohJoinGuards.VAL_IROH_JOIN_CLASH,
                refusal?.substringBefore(':'),
            )
        }
        assertNull(
            "an idle join lane must not block hosting",
            IrohJoinGuards.realmStartRefusal(IrohGatewayManager.JoinState.Idle),
        )
    }

    @Test
    fun `a finished join never blocks and an error state never blocks`() {
        assertNull(
            IrohJoinGuards.joinRefusal(
                phase = startable,
                joinState = IrohGatewayManager.JoinState.Error("CODE", "detail"),
            ),
        )
        assertNull(
            IrohJoinGuards.realmStartRefusal(
                IrohGatewayManager.JoinState.Error("CODE", "detail"),
            ),
        )
    }

    // --------------------------------------- round 4 (R1-r4-2, R6-4-3)

    @Test
    fun `a listing for another flavor refuses with the flavor row`() {
        // R6-4-3's proposed leg: the mismatch row exists, names the code,
        // and is actionable (names both flavors).
        val refusal = IrohJoinGuards.joinRefusal(
            phase = startable,
            joinState = IrohGatewayManager.JoinState.Idle,
            activeFlavorId = "tbc",
            listingFlavorId = "wotlk",
        )
        assertTrue(
            "a flavor mismatch must refuse with VAL-IROH-JOIN-FLAVOR",
            refusal != null && refusal.startsWith("VAL-IROH-JOIN-FLAVOR"),
        )
        assertTrue("the row names the listing's flavor", refusal!!.contains("wotlk"))
        assertTrue("the row names the active flavor", refusal.contains("tbc"))
    }

    @Test
    fun `the null-active posture refuses with the pick-first row - pinned`() {
        // R1-r4-2's pinned decision (it was an accidental skip): no Home
        // flavor picked + a flavor-carrying listing → refuse FIRST with
        // the pick-first row — never dial-then-die on "no client".
        val refusal = IrohJoinGuards.joinRefusal(
            phase = startable,
            joinState = IrohGatewayManager.JoinState.Idle,
            activeFlavorId = null,
            listingFlavorId = "wotlk",
        )
        assertTrue(
            "the ambiguous-flavor state must refuse with VAL-IROH-JOIN-FLAVOR",
            refusal != null && refusal.startsWith("VAL-IROH-JOIN-FLAVOR"),
        )
        assertTrue(
            "the row must be the pick-first instruction",
            refusal!!.contains("no realm flavor picked"),
        )
    }

    @Test
    fun `a matching flavor and a flavor-less listing allow the join`() {
        assertNull(
            "an active flavor matching the listing allows the join",
            IrohJoinGuards.joinRefusal(
                phase = startable,
                joinState = IrohGatewayManager.JoinState.Idle,
                activeFlavorId = "wotlk",
                listingFlavorId = "wotlk",
            ),
        )
        assertNull(
            "a listing without a flavor stays on the legacy posture",
            IrohJoinGuards.joinRefusal(
                phase = startable,
                joinState = IrohGatewayManager.JoinState.Idle,
                activeFlavorId = null,
                listingFlavorId = null,
            ),
        )
    }

    @Test
    fun `the shared contract's ports are the game's fixed ports`() {
        // The gateway project restates 3724/8085 (it cannot see the
        // supervisor tree); THIS lane sees both trees — the drift alarm.
        // Round-4 R1-r4-1: EVERY owning spelling is pinned equal — the
        // supervisor's RealmEndpoint AND the server table's
        // ServerRuntimeContract (the spelling GatewayContract.kt's comment
        // names; the old pin covered only RealmEndpoint).
        assertEquals(
            3724,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_REALM,
        )
        assertEquals(
            8085,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_WORLD,
        )
        assertEquals(
            com.pocketrealm.supervisor.RealmEndpoint.REALM_PORT,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_REALM,
        )
        assertEquals(
            com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_WORLD,
        )
        assertEquals(
            com.pocketrealm.server.ServerRuntimeContract.REALM_PORT,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_REALM,
        )
        assertEquals(
            com.pocketrealm.server.ServerRuntimeContract.WORLD_PORT,
            com.pocketrealm.discovery.iroh.GatewayContract.PORT_WORLD,
        )
    }

    @Test
    fun `stopJoin releases the guards - Ready goes Idle, hosting re-allows`() {
        // R4-2/R3-2: the manager's stop leg is the guards' release valve —
        // after a Ready join is stopped, joinState is Idle and both verbs
        // allow their lanes again. (The state injection rides the real
        // manager flow: startJoin on a jar that cannot exist errors; the
        // stop leg is exercised on the Idle lane and the refusal table's
        // Ready row is pinned above.)
        val manager = com.pocketrealm.desktop.iroh.IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = com.pocketrealm.desktop.iroh.IrohGatewayManager
                .DEFAULT_JAVA_21,
        )
        try {
            // The java process SPAWNS (and dies on the missing jar): the
            // watcher lands Error asynchronously — poll within a budget.
            manager.startJoin(
                com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
                    nodeHex = "ab".repeat(32),
                    relayUrl = "https://usw1-1.relay.n0.iroh.link",
                ),
                flavorId = "wotlk",
                relayOnly = true,
            )
            val deadline = System.currentTimeMillis() + 10_000L
            while (
                manager.joinState.value !is
                com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Error &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "the dead join process must land Error",
                manager.joinState.value is
                    com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Error,
            )
            // The stop leg: the state clears to Idle, and the guards
            // release (hosting re-allows).
            manager.stopJoin()
            assertEquals(
                com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Idle,
                manager.joinState.value,
            )
            assertNull(
                com.pocketrealm.desktop.iroh.IrohJoinGuards.realmStartRefusal(
                    manager.joinState.value,
                ),
            )
        } finally {
            manager.close()
        }
    }

    private companion object {
        val anyDescriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = "ab".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
    }
}
