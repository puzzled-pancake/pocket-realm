package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTopologyTest {
    @Test fun canonicalPrivateAndLinkLocalIpv4AreAcceptedWithFixedPorts() {
        listOf("10.0.0.1", "172.16.1.2", "172.31.255.254", "192.168.50.4", "169.254.3.9")
            .forEach { raw ->
                val endpoint = RealmEndpoint.parseLan(raw)
                assertEquals(raw, endpoint.address)
                assertEquals(3724, endpoint.realmPort)
                assertEquals(8085, endpoint.worldPort)
            }
    }

    @Test fun ipv6HostnamesUrisControlsNonCanonicalAndPublicIpv4AreRejected() {
        listOf(
            "::1", "fe80::1", "realm.local", "http://192.168.1.4", "192.168.001.4",
            "192.168.1.4:3724", "192.168.1.4\n", "8.8.8.8", "100.64.0.1", "127.0.0.1",
            "0.0.0.0", "255.255.255.255",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { RealmEndpoint.parseLan(raw) }
        }
    }

    @Test fun componentPlanIsDerivedOnlyFromTopology() {
        assertEquals(
            listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD),
            RuntimeLaunchSpec.local("profile").componentPlan(),
        )
        assertEquals(
            listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD, RuntimeComponent.CLIENT),
            RuntimeLaunchSpec.local("profile", includeClient = true).componentPlan(),
        )
        assertEquals(
            listOf(RuntimeComponent.CLIENT),
            RuntimeLaunchSpec.lanJoin("profile", "192.168.1.2").componentPlan(),
        )
        assertEquals(
            listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD),
            RuntimeLaunchSpec.lanHost("profile", "10.0.0.4").componentPlan(),
        )
    }

    @Test fun clientRequirementsAreExplicitAndServerOnlyDefaultsStayIndependent() {
        assertFalse(RuntimeLaunchSpec.local("profile").requiresClient)
        assertTrue(RuntimeLaunchSpec.local("profile", includeClient = true).requiresClient)
        assertFalse(RuntimeLaunchSpec.lanHost("profile", "10.0.0.4").requiresClient)
        assertTrue(RuntimeLaunchSpec.lanHost(
            "profile", "10.0.0.4", includeClient = true,
        ).requiresClient)
        assertTrue(RuntimeLaunchSpec.lanJoin("profile", "10.0.0.4").requiresClient)
    }

    @Test fun launchSpecSchemaRejectsCallerSelectedPortsAndUnknownFields() {
        val valid = RuntimeLaunchSpec.lanJoin("profile", "192.168.1.2").toJson()
        assertEquals(RuntimeMode.LAN_JOIN, RuntimeLaunchSpec.fromJson(valid).mode)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.fromJson(valid.toString().replace("\"realmPort\":3724", "\"realmPort\":9999"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.fromJson(valid.put("password", "secret"))
        }
    }

    @Test fun lanHostRequiresExplicitAuthorizationBit() {
        assertTrue(RuntimeLaunchSpec.lanHost("profile", "192.168.1.20").allowLanPlayers)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec(
                RuntimeMode.LAN_HOST,
                "profile",
                RealmEndpoint.parseLan("192.168.1.20"),
                includeClient = true,
                allowLanPlayers = false,
            )
        }
    }

    @Test fun newSettingsTopologyDefaultsRemainLocalAndLanHostingOff() {
        val defaults = com.pocketrealm.storage.Settings.Snapshot()
        assertEquals(RuntimeMode.LOCAL, defaults.runtimeMode)
        assertFalse(defaults.allowLanPlayers)
        assertTrue(defaults.runtimeMode == RuntimeMode.LOCAL)
    }

    // design-tunnel: endpoint model v2 (advertised address) ----

    @Test fun advertisedAddressesAcceptLoopbackPrivateLinkLocalAndCgnat() {
        listOf(
            "127.0.0.1", "10.0.0.1", "172.16.1.2", "192.168.50.4", "169.254.3.9",
            "100.64.0.1", "100.100.100.100", "100.127.255.254",
        ).forEach { raw ->
            assertEquals(raw, AdvertisedAddress.parse(raw).value)
        }
    }

    @Test fun advertisedAddressesRejectHostnamesPublicIpv4AndNonCanonicalForms() {
        listOf(
            "realm.local", "my-realm.ts.net", "http://192.168.1.4", "8.8.8.8",
            "1.2.3.4", "100.63.255.255", "100.128.0.1", "192.168.001.4",
            "100.64.0.1:3724", "100.64.0.1 ", "::1", "", "0.0.0.0",
            "255.255.255.255",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { AdvertisedAddress.parse(raw) }
        }
    }

    @Test fun cgnatFlagCoversExactlyTheSharedRange() {
        assertTrue(AdvertisedAddress.parse("100.64.0.1").isCgnatTunnel)
        assertTrue(AdvertisedAddress.parse("100.127.255.254").isCgnatTunnel)
        assertFalse(AdvertisedAddress.parse("10.0.0.1").isCgnatTunnel)
        assertFalse(AdvertisedAddress.parse("192.168.1.50").isCgnatTunnel)
        assertTrue(AdvertisedAddress.parse("127.0.0.1").isLoopback)
        assertFalse(AdvertisedAddress.parse("100.64.0.1").isLoopback)
    }

    @Test fun launchSpecSchema2RoundTripsTheAdvertisedAddress() {
        val spec = RuntimeLaunchSpec.lanHost("profile", "192.168.1.20", advertised = "100.64.0.5")
        assertTrue(spec.tunnelAdvertising)
        assertEquals("100.64.0.5", spec.advertisedOrDefault)
        assertEquals("100.64.0.5", spec.advertisedAddress?.value)
        val round = RuntimeLaunchSpec.fromJson(spec.toJson())
        assertEquals(RuntimeMode.LAN_HOST, round.mode)
        assertEquals("192.168.1.20", round.endpoint.address)
        assertEquals("100.64.0.5", round.advertisedAddress?.value)
        assertTrue(round.tunnelAdvertising)
    }

    @Test fun launchSpecWithoutAnAdvertisementStaysByteIdenticalLocalAndLan() {
        val local = RuntimeLaunchSpec.local("profile")
        assertFalse("advertisedAddress" in local.toJson().toString())
        assertEquals(local.endpoint.address, local.advertisedOrDefault)
        assertFalse(local.tunnelAdvertising)
        assertEquals(
            RuntimeLaunchSpec.local("profile"),
            RuntimeLaunchSpec.fromJson(local.toJson()),
        )
        val lan = RuntimeLaunchSpec.lanHost("profile", "192.168.1.20")
        assertFalse("advertisedAddress" in lan.toJson().toString())
        assertEquals("192.168.1.20", lan.advertisedOrDefault)
        assertFalse(lan.tunnelAdvertising)
        assertEquals(lan, RuntimeLaunchSpec.fromJson(lan.toJson()))
    }

    @Test fun tunnelAdvertisingRequiresAnAdvertisementThatDiffersFromTheBind() {
        assertFalse(
            RuntimeLaunchSpec.lanHost("profile", "192.168.1.20", advertised = "192.168.1.20")
                .tunnelAdvertising,
        )
        assertTrue(
            RuntimeLaunchSpec.lanHost("profile", "192.168.1.20", advertised = "10.147.1.5")
                .tunnelAdvertising,
        )
    }

    @Test fun advertisingAnOverrideIsHostOnly() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.local("profile").copy(
                advertisedAddress = AdvertisedAddress.parse("100.64.0.5"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.lanJoin("profile", "192.168.1.2").copy(
                advertisedAddress = AdvertisedAddress.parse("100.64.0.5"),
            )
        }
        val poisonedLocal = RuntimeLaunchSpec.local("profile")
            .toJson().put("advertisedAddress", "100.64.0.5")
        assertThrows(IllegalArgumentException::class.java) { RuntimeLaunchSpec.fromJson(poisonedLocal) }
        val poisonedJoin = RuntimeLaunchSpec.lanJoin("profile", "192.168.1.2")
            .toJson().put("advertisedAddress", "100.64.0.5")
        assertThrows(IllegalArgumentException::class.java) { RuntimeLaunchSpec.fromJson(poisonedJoin) }
    }

    @Test fun launchSpecSchemaGateRejectsSchema1Json() {
        val schema1 = RuntimeLaunchSpec.lanHost("profile", "192.168.1.20").toJson().put("schema", 1)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLaunchSpec.fromJson(schema1)
        }
    }

    @Test fun malformedAdvertisementJsonRefusesTheSpec() {
        val spec = RuntimeLaunchSpec.lanHost("profile", "192.168.1.20", advertised = "100.64.0.5")
        val bad = spec.toJson().put("advertisedAddress", "my-realm.ts.net")
        assertThrows(IllegalArgumentException::class.java) { RuntimeLaunchSpec.fromJson(bad) }
    }

    @Test fun joinTargetsAcceptCgnatTunnelAddressesAndRoundTripThroughSchema2() {
        assertEquals(
            "100.64.0.9",
            RuntimeLaunchSpec.lanJoin("profile", "100.64.0.9").endpoint.address,
        )
        val round = RuntimeLaunchSpec.fromJson(
            RuntimeLaunchSpec.lanJoin("profile", "100.64.0.9").toJson(),
        )
        assertEquals("100.64.0.9", round.endpoint.address)
        assertEquals("100.64.0.9", round.advertisedOrDefault)
    }

    @Test fun joinTargetParsingStillRefusesPublicHostnamesAndLoopback() {
        listOf("8.8.8.8", "example.com", "127.0.0.1", "::1", "192.168.1.4:3724")
            .forEach { raw ->
                assertThrows(raw, IllegalArgumentException::class.java) {
                    RealmEndpoint.parseJoinTarget(raw)
                }
            }
        // The BIND path keeps its exact v1 grammar: CGNAT can never bind.
        listOf("100.64.0.9", "0.0.0.0").forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { RealmEndpoint.parseLan(raw) }
        }
    }

    @Test fun tunnelAdapterAllowlistMatchesFriendlyAndDriverNamePrefixesOnly() {
        assertTrue(TunnelInterfacePolicy.isTunnelAdapter("eth5", "Tailscale"))
        assertTrue(TunnelInterfacePolicy.isTunnelAdapter("Ethernet 2", "tailscale tunnel"))
        assertTrue(TunnelInterfacePolicy.isTunnelAdapter("zerotier6", "ZeroTier One [8056c2e21c]"))
        assertFalse(TunnelInterfacePolicy.isTunnelAdapter("eth0", "Realtek Ethernet"))
        assertFalse(TunnelInterfacePolicy.isTunnelAdapter("wlan0", "Wi-Fi"))
        assertFalse(
            "prefix, not substring",
            TunnelInterfacePolicy.isTunnelAdapter("tafx0", "Some Tailscale-ish VPN"),
        )
    }

    // design-tunnel: the mode-aware interface-loss watch --------

    @Test fun interfaceWatchKeysTheLossLegOffTheSessionsAdvertisement() {
        assertEquals(
            "no advertisement = plain LAN hosting, the D2 leg",
            InterfaceWatch.Lan,
            InterfaceWatch.forSession("192.168.1.20", null),
        )
        assertEquals(
            InterfaceWatch.Lan,
            InterfaceWatch.forSession("192.168.1.20", ""),
        )
        assertEquals(
            "a tunnel advertisement watches the TUNNEL adapter (physical-LAN churn never kills it)",
            InterfaceWatch.Tunnel(AdvertisedAddress.parse("100.64.12.34")),
            InterfaceWatch.forSession("192.168.1.20", "100.64.12.34"),
        )
        assertEquals(
            "a ZeroTier RFC1918 advertisement is a tunnel watch too",
            InterfaceWatch.Tunnel(AdvertisedAddress.parse("10.147.1.5")),
            InterfaceWatch.forSession("192.168.1.20", "10.147.1.5"),
        )
        assertEquals(
            "loopback advertisement = the cloudflared recipe: no interface to lose",
            InterfaceWatch.None,
            InterfaceWatch.forSession("192.168.1.20", "127.0.0.1"),
        )
    }

    @Test fun interfaceWatchDegradesAMalformedAdvertisementToNoWatch() {
        // The launch gate already refuses a malformed advertisement; the
        // monitor treats one as no-watch rather than inventing a stop.
        assertEquals(InterfaceWatch.None, InterfaceWatch.forSession("192.168.1.20", "my-realm.ts.net"))
        assertEquals(InterfaceWatch.None, InterfaceWatch.forSession("192.168.1.20", "8.8.8.8"))
    }
}
