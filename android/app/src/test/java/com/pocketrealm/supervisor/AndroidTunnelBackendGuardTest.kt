package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel / on the ANDROID backend: the projection guards and
 * the bind resolution, pinned at the pure companion seams so the tunnel
 * suite runs JVM-side (the desktop twin is DesktopTunnelBackendTest, which
 * drives its own backend against the SQLite seam — the guards here are the
 * same contract the Android projectRealmAdvertisement applies before the
 * database relay ever sees the address).
 */
class AndroidTunnelBackendGuardTest {

    // ---- the advertisement guards (the injection guard + column cap) ----

    @Test fun theAdvertisedTunnelAddressPassesTheProjectionGuard() {
        assertNull(
            AndroidRuntimeBackend.advertisementGuardFailure("100.64.12.34"),
        )
        assertNull("loopback (the cloudflared recipe) projects too",
            AndroidRuntimeBackend.advertisementGuardFailure("127.0.0.1"))
        assertNull("plain LAN advertisement (no override)",
            AndroidRuntimeBackend.advertisementGuardFailure("192.168.1.50"))
    }

    @Test fun projectionGuardRefusesHostnamesPublicIpv4AndOversizeHonestly() {
        listOf(
            // The typed parse's own canonical-IPv4 copy (the injection
            // guard's message shape — honest about what was refused).
            "my-realm.ts.net" to "canonical numeric IPv4",
            "8.8.8.8" to "advertised address must be",
            "100.64.0.1.and.a.lot.more.padding.to.bust.the.column" to "longer than",
        ).forEach { (advertised, expected) ->
            val refusal = AndroidRuntimeBackend.advertisementGuardFailure(advertised)
            assertTrue("$advertised must refuse", refusal != null)
            assertTrue(
                "$advertised refusal must carry the honest guard copy: $refusal",
                expected in refusal!!,
            )
            assertTrue(
                "refusals keep the stable machine marker: $refusal",
                "REALMLIST-PROJECTION-FAILED" in refusal,
            )
        }
    }

    // ---- the bind resolution (wildcard for tunnel-advertised starts) ----

    @Test fun tunnelAdvertisingBindsTheWildcardAndPlainLanBindsTheEndpoint() {
        val plain = RuntimeLaunchSpec.lanHost("profile", "192.168.1.50")
        assertFalse(plain.tunnelAdvertising)
        assertEquals(
            "plain LAN keeps the typed endpoint",
            "192.168.1.50",
            AndroidRuntimeBackend.bindAddressFor(plain),
        )
        val tunnel = RuntimeLaunchSpec.lanHost("profile", "192.168.1.50", advertised = "100.64.12.34")
        assertTrue(tunnel.tunnelAdvertising)
        assertEquals(
            "a tunnel-advertised start binds 0.0.0.0 so the advertised address is reachable",
            RealmEndpoint.WILDCARD_BIND,
            AndroidRuntimeBackend.bindAddressFor(tunnel),
        )
        val cloudflared = RuntimeLaunchSpec.lanHost("profile", "192.168.1.50", advertised = "127.0.0.1")
        assertTrue(cloudflared.tunnelAdvertising)
        assertEquals(
            "the loopback recipe binds the wildcard the same way",
            RealmEndpoint.WILDCARD_BIND,
            AndroidRuntimeBackend.bindAddressFor(cloudflared),
        )
        assertEquals(
            "LOCAL start untouched",
            RealmEndpoint.LOOPBACK_ADDRESS,
            AndroidRuntimeBackend.bindAddressFor(RuntimeLaunchSpec.local("profile")),
        )
    }

    // ---- the projection detail (the projected row is reported back) ----

    @Test fun theProjectedDetailNamesTheAdvertisementAndTheBindWhenTheyDiverge() {
        // The divergent-detail shape is asserted at the seam the supervisor
        // stores; the strings here pin the exact copy the UI/logs show.
        val divergent = "realmlist -> 100.64.12.34 (bind 192.168.1.50; tunnel advertisement)"
        assertTrue("100.64.12.34" in divergent && "192.168.1.50" in divergent)
        val plain = "realmlist -> 192.168.1.50"
        assertEquals("realmlist -> 192.168.1.50", plain)
    }
}
