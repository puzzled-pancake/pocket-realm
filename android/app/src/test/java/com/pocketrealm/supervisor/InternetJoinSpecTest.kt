package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetJoinSpecTest {
    @Test fun internetJoinIsClientOnlyAgainstTheLoopbackGateway() {
        val spec = RuntimeLaunchSpec.internetJoin("integrated")
        assertEquals(RuntimeMode.LAN_JOIN, spec.mode)
        assertTrue(spec.endpoint.isLoopback)
        assertEquals(listOf(RuntimeComponent.CLIENT), spec.componentPlan())
        assertTrue(spec.requiresClient)
    }

    @Test fun internetJoinSurvivesTheLaunchSpecRoundTrip() {
        val spec = RuntimeLaunchSpec.internetJoin("integrated")
        assertEquals(spec, RuntimeLaunchSpec.fromJson(spec.toJson().toString()))
    }

    @Test fun theUserJoinGrammarStillRefusesLoopback() {
        // Only the in-app gateway may point a join at 127.0.0.1; typed
        // join addresses keep the private/link-local/CGNAT grammar.
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.lanJoin("integrated", RealmEndpoint.LOOPBACK_ADDRESS)
        }
    }

    @Test fun aLoopbackJoinAndOnlineHostJournalBothDecode() {
        val fixture = javaClass.getResourceAsStream("/fixtures/g3/journal-current-release.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val join = org.json.JSONObject(fixture).put("runtimeMode", "LAN_JOIN")
        val decoded = RuntimeSnapshotJournalCodec.decode(join)
        assertEquals(RuntimeMode.LAN_JOIN, decoded.runtimeMode)
        assertTrue(decoded.realmEndpoint.isLoopback)
        // A loopback LAN_HOST journal is the ONLINE host — the gateway
        // carries the players — and must decode (its restart depends on it).
        val host = org.json.JSONObject(fixture).put("runtimeMode", "LAN_HOST")
        val onlineHost = RuntimeSnapshotJournalCodec.decode(host)
        assertEquals(RuntimeMode.LAN_HOST, onlineHost.runtimeMode)
        assertTrue(onlineHost.realmEndpoint.isLoopback)
    }

    @Test fun localModeStillRequiresLoopbackAndTheOnlineHostTakesIt() {
        assertTrue(RuntimeLaunchSpec.local("integrated").endpoint.isLoopback)
        // The online host: the one LAN_HOST form allowed on loopback, and
        // it never claims LAN exposure.
        val online = RuntimeLaunchSpec.lanHost("integrated", RealmEndpoint.LOOPBACK_ADDRESS)
        assertTrue(online.endpoint.isLoopback)
        assertTrue(!online.allowLanPlayers)
    }
}
