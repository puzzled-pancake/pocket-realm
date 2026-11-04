package com.pocketrealm.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel / / + design-remote-accounts: the ANDROID wiring
 * contract for the tunnel host / join-over-tunnel / invite lanes. The
 * services and screens need a live Android context (the device lane drives
 * the binder), so the cross-file wiring is pinned as a source-contract test
 * over the shipped sources — the same pattern the repo's other
 * host-untestable glue uses (TimeframeOverlayWiringTest).
 *
 * The pure contracts behind this wiring are pinned where they live:
 * RuntimeTopologyTest (endpoint model v2 + InterfaceWatch),
 * ServerRuntimeContractTest (wildcard bind + lockout),
 * AndroidTunnelBackendGuardTest (projection guards + bind resolution),
 * TunnelInviteTest (invite artifact + join grammar, shared with desktop).
 */
class LanTunnelWiringContractTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/$relative"),
            File("app/src/main/java/com/pocketrealm/$relative"),
            File("android/app/src/main/java/com/pocketrealm/$relative"),
            File("../android/app/src/main/java/com/pocketrealm/$relative"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun theJoinPaneTakesTheWidenedTunnelGrammarNotTheBindGrammar() {
        val bridge = mainSource("onlineplay/JoinBridge.kt")
        assertTrue(
            "the join address validates through parseJoinTarget (CGNAT tunnel addresses join)",
            "RealmEndpoint.parseJoinTarget(raw.trim())" in bridge,
        )
        // Design-remote-accounts: the friend-request card reaches the
        // host's PRIVATE LAN listener only, so its host field validates the
        // LAN grammar (a tunnel address green-lit here could only fail at
        // connect time) and carries the LAN-only honesty note.
        val advanced = mainSource("ui/online/DirectJoinAdvanced.kt")
        assertTrue(
            "the friend-request host field validates the LAN grammar",
            "RealmEndpoint.parseLan(hostAddress" in advanced,
        )
        assertTrue(
            "the friend-request card carries the LAN-only honesty note",
            "friend-request-lan-only-note" in advanced,
        )
    }

    @Test
    fun theJoinPaneCarriesTheProbeWithModeAwareFailureCopy() {
        val bridge = mainSource("onlineplay/JoinBridge.kt")
        assertTrue("the reachability probe ships on Android", "LanJoinProbe.probe(" in bridge)
        assertTrue(
            "the LAN failure keeps the humanized firewall copy",
            "RuntimeFailureCopy.humanize(LanJoinProbe.unreachableDetail(" in bridge,
        )
    }

    @Test
    fun theHostPaneShipsTheAdvertisedTunnelAddressLane() {
        val advanced = mainSource("ui/online/HostAdvanced.kt")
        assertTrue(
            "the advertise field exists (the desktop TunnelAdvertisedField twin)",
            "tunnel-advertised-address" in advanced,
        )
        assertTrue(
            "tunnel-adapter quick-picks render from the shared allowlist lane (§4.4.4)",
            "TunnelInterfacePolicy.available()" in advanced,
        )
        assertTrue(
            "the advertised value validates for display through the shared grammar",
            "AdvertisedAddress.parse(tunnelAdvertisedAddress.trim())" in advanced,
        )
        val tab = mainSource("ui/online/HostTab.kt")
        assertTrue(
            "the host start carries the advertised address",
            "snapshot.tunnelAdvertisedAddress.trim()" in tab,
        )
        assertTrue(
            "hosting status shows the live advertisement",
            "advertisedAddress = (realmState as? com.pocketrealm.realm.RealmState.Running)?.advertisedAddress" in tab,
        )
    }

    @Test
    fun theFriendAccessCardRendersTheSharedAddressBlockOnlyInvite() {
        val advanced = mainSource("ui/online/HostAdvanced.kt")
        assertTrue(
            "the invite artifact comes from the SHARED composer",
            "TunnelInvite.inviteText(" in advanced,
        )
        assertTrue(
            "the artifact is copyable",
            "friend-invite-copy" in advanced,
        )
    }

    @Test
    fun theServiceCarriesTheAdvertisedStartAndTheModeAwareWatch() {
        val service = mainSource("service/RealmService.kt")
        assertTrue(
            "ACTION_HOST_LAN builds the spec with the advertised override",
            "advertised = intent.getStringExtra(EXTRA_ADVERTISED_ADDRESS)" in service,
        )
        assertTrue(
            "the join intent guard accepts tunnel addresses (§4.4.3)",
            "RealmEndpoint.parseJoinTarget(address)" in service,
        )
        assertTrue(
            "the interface-loss watch is mode-keyed and bind-aware (a loopback online host never stops)",
            "InterfaceWatch.forSession(snapshot.realmEndpoint.address, snapshot.advertisedAddress)" in service,
        )
        assertTrue(
            "the tunnel loss leg names the tunnel stop reason",
            "TUNNEL_HOST_INTERFACE_LOST" in service,
        )
    }

    @Test
    fun theServerSeamsAcceptTheWildcardBindAndProjectTheAdvertisement() {
        val realmService = mainSource("server/RealmRuntimeService.kt")
        assertTrue(
            "realmd startAt resolves the bind through the shared wildcard-aware resolver",
            "ServerRuntimeContract.resolveBindAddress(bindAddress)" in realmService,
        )
        val worldService = mainSource("server/WorldRuntimeService.kt")
        assertTrue(
            "world start legs resolve the bind the same way",
            worldService.contains("ServerRuntimeContract.resolveBindAddress(bindAddress)"),
        )
        val serverFiles = mainSource("server/ServerRuntimeFiles.kt")
        assertTrue(
            "the conf writers feed the same resolver (no second parse path)",
            serverFiles.contains("ServerRuntimeContract.resolveBindAddress(bindAddress)"),
        )
        val engine = mainSource("database/DatabaseEngine.kt")
        assertTrue(
            "the projection seam takes the ADVERTISED grammar in BOTH provider lanes",
            engine.contains("val advertised = AdvertisedAddress.parse(address)") &&
                occurrences(engine, "AdvertisedAddress.parse(address)") >= 2,
        )
        val backend = mainSource("supervisor/AndroidRuntimeBackend.kt")
        assertTrue(
            "the backend overrides the advertisement projection",
            "override suspend fun projectRealmAdvertisement(" in backend,
        )
        assertTrue(
            "the backend binds the wildcard for tunnel-advertised starts",
            "if (spec.tunnelAdvertising) RealmEndpoint.WILDCARD_BIND else spec.endpoint.address" in backend,
        )
    }

    @Test
    fun theHomeSubtitleDisplaysTheLiveAdvertisement() {
        val home = mainSource("ui/HomeScreen.kt")
        assertTrue(
            "design-tunnel §5: the Home hosting line shows the advertised address",
            "state.advertisedAddress" in home,
        )
    }
}
