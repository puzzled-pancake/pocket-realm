package com.pocketrealm.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel §4/§5/§10 + design-remote-accounts: the ANDROID wiring
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
        val lan = mainSource("ui/LanScreen.kt")
        assertTrue(
            "the Join pane validates through parseJoinTarget (CGNAT tunnel addresses join)",
            "RealmEndpoint.parseJoinTarget(lanAddress)" in lan,
        )
        assertTrue(
            "the LAN bind grammar must not gate the join field",
            !("RealmEndpoint.parseLan(lanAddress)" in lan),
        )
        // Design-remote-accounts §6: the friend-request card reaches the
        // host's PRIVATE LAN listener only, so its host field validates the
        // LAN grammar (a tunnel address green-lit here could only fail at
        // connect time) and carries the desktop twin's honesty note.
        assertTrue(
            "the friend-request host field validates the LAN grammar",
            "RealmEndpoint.parseLan(hostAddress" in lan,
        )
        assertTrue(
            "the friend-request card carries the LAN-only honesty note",
            "friend-request-lan-only-note" in lan,
        )
    }

    @Test
    fun theJoinPaneCarriesTheProbeWithModeAwareFailureCopy() {
        val lan = mainSource("ui/LanScreen.kt")
        assertTrue("the D6 probe ships on Android", "LanJoinProbe.probe(" in lan)
        assertTrue(
            "the tunnel-shaped failure uses the shared mode-aware hint (§5)",
            "TunnelInvite.joinFailureHint(" in lan,
        )
        assertTrue(
            "the LAN failure keeps the humanized firewall copy",
            "RuntimeFailureCopy.humanize(LanJoinProbe.unreachableDetail(" in lan,
        )
    }

    @Test
    fun theHostPaneShipsTheAdvertisedTunnelAddressLane() {
        val lan = mainSource("ui/LanScreen.kt")
        assertTrue(
            "the advertise field exists (the desktop TunnelAdvertisedField twin)",
            "tunnel-advertised-address" in lan,
        )
        assertTrue(
            "tunnel-adapter quick-picks render from the shared allowlist lane (§4.4.4)",
            "TunnelInterfacePolicy.available()" in lan,
        )
        assertTrue(
            "the start validates the stored advertisement before launching",
            "AdvertisedAddress.parse(advertised)" in lan,
        )
        assertTrue(
            "the host start carries the advertised address",
            "RealmService.hostLan(context, selection.profile.id, includeClient = false, advertised = advertised)" in lan,
        )
        assertTrue(
            "hosting status shows the live advertisement",
            "running.advertisedAddress" in lan,
        )
    }

    @Test
    fun theFriendAccessCardRendersTheSharedAddressBlockOnlyInvite() {
        val lan = mainSource("ui/LanScreen.kt")
        assertTrue(
            "the invite artifact comes from the SHARED composer",
            "TunnelInvite.inviteText(" in lan,
        )
        assertTrue(
            "the artifact is copyable",
            "friend-invite-copy" in lan,
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
            "the interface-loss watch is mode-keyed (§10: tunnel churn ≠ LAN churn)",
            "InterfaceWatch.forSession(snapshot.advertisedAddress)" in service,
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
