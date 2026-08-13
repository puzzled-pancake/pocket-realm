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
}
