package com.pocketrealm.ui

import com.pocketrealm.supervisor.AdvertisedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel §6/§4.4.3/§5: the tunnel lane's pure contract — the invite
 * artifact (ADDRESS BLOCK ONLY: the hard rule design-lan §4.2 supersedes
 * round-1's §6 credential-embedding with), the tunnel-kind copy split, and
 * the mode-aware join validation/probe copy. SHARED: this suite runs on
 * BOTH platforms (desktop/shared-sources.json shared_tests), pinning the
 * same invite/grammar contract the desktop run built for the Android port.
 */
class TunnelInviteTest {
    @Test
    fun inviteCarriesTheAdvertisedAddressAndTheBuildPin() {
        val invite = TunnelInvite.inviteText(
            realmName = "Whispering Pines",
            advertised = "100.64.12.34",
            kind = TunnelInvite.TunnelKind.TAILSCALE,
        )
        assertTrue("the advertised address is the block's dial target:\n$invite", "set realmlist 100.64.12.34" in invite)
        assertTrue("the build pin rides the invite:\n$invite", "1.12.1 build 5875" in invite)
        assertTrue("the app join path is named:\n$invite", "paste 100.64.12.34 into the Join pane" in invite)
        assertTrue("the realm name is the header:\n$invite", "Play on Whispering Pines" in invite)
    }

    @Test
    fun inviteCopyNeverNamesTheDesktopGuestsCard() {
        // R1-2 (android-ui-tests review round 1): the invite is rendered by
        // the ANDROID host's LanScreen — a surface family that has a Guest
        // LOGIN card, never the desktop-only "Guests card". The credential
        // handoff line must stay platform-neutral (D50(4) forbid-Guests
        // precedent, mirrored from FriendInviteStatusCopyTest).
        for (kind in listOf(TunnelInvite.TunnelKind.TAILSCALE, TunnelInvite.TunnelKind.CLOUDFLARED)) {
            val invite = TunnelInvite.inviteText(
                realmName = "Whispering Pines",
                advertised = "100.64.12.34",
                kind = kind,
            )
            assertFalse(
                "kind=$kind: the invite must not name the desktop-only Guests card:\n$invite",
                "Guests" in invite,
            )
        }
        val lan = TunnelInvite.lanInviteText(
            realmName = "Whispering Pines",
            lanAddress = "192.168.1.20",
        )
        assertFalse("the LAN invite must not name the Guests card", "Guests" in lan)
    }

    @Test
    fun inviteNeverCarriesACredential() {
        // The §6 hard rule: no working username+password inside the pasted
        // artifact — delivery of the secret is a separate sensitive step.
        val invite = TunnelInvite.inviteText(
            realmName = "Whispering Pines",
            advertised = "100.64.12.34",
            kind = TunnelInvite.TunnelKind.TAILSCALE,
        )
        assertFalse("no password word:\n$invite", "password" in invite.lowercase())
        assertFalse("no account word:\n$invite", "account" in invite.lowercase())
        val withGuest = TunnelInvite.inviteText(
            realmName = "guest451",
            advertised = "100.64.12.34",
            kind = TunnelInvite.TunnelKind.ZERO_TIER,
        )
        assertFalse(
            "an account-name header must not leak a credential either:\n$withGuest",
            "password" in withGuest.lowercase(),
        )
    }

    @Test
    fun tunnelKindSplitsTheGuidanceCopy() {
        assertEquals(
            TunnelInvite.TunnelKind.TAILSCALE,
            TunnelInvite.tunnelKindFor(AdvertisedAddress.parse("100.64.12.34")),
        )
        assertEquals(
            TunnelInvite.TunnelKind.ZERO_TIER,
            TunnelInvite.tunnelKindFor(AdvertisedAddress.parse("10.147.1.5")),
        )
        assertEquals(
            TunnelInvite.TunnelKind.CLOUDFLARED,
            TunnelInvite.tunnelKindFor(AdvertisedAddress.parse("127.0.0.1")),
        )
        val tailscale = TunnelInvite.inviteText(
            "realm", "100.64.12.34", TunnelInvite.TunnelKind.TAILSCALE,
        )
        assertTrue("Tailscale" in tailscale)
        val zeroTier = TunnelInvite.inviteText(
            "realm", "10.147.1.5", TunnelInvite.TunnelKind.ZERO_TIER,
        )
        assertTrue("ZeroTier" in zeroTier)
    }

    @Test
    fun joinFieldValidationUsesTheWidenedV2Grammar() {
        assertNull("a LAN host address joins", TunnelInvite.joinTargetError("192.168.1.2"))
        assertNull("a tunnel address joins (design-tunnel §4.4.3)", TunnelInvite.joinTargetError("100.64.0.9"))
        assertTrue(
            "hostnames stay refused",
            TunnelInvite.joinTargetError("example.com")?.contains("IPv4") == true,
        )
        assertTrue(
            "public IPv4 stays refused",
            TunnelInvite.joinTargetError("8.8.8.8") != null,
        )
        // The cloudflared/loopback lane: the advertised address is each
        // player's OWN forwarder target, and the Join grammar refuses
        // loopback — pin the refusal the copy must not promise around.
        assertTrue(
            "127.0.0.1 is refused by the join grammar (the app route does not exist for this lane)",
            TunnelInvite.joinTargetError("127.0.0.1") != null,
        )
    }

    @Test
    fun theCloudflaredInviteDoesNotPromiseAnAppRouteTheJoinPaneRefuses() {
        val invite = TunnelInvite.inviteText(
            realmName = "Whispering Pines",
            advertised = "127.0.0.1",
            kind = TunnelInvite.TunnelKind.CLOUDFLARED,
        )
        assertFalse(
            "the invite must not send app users to a Join pane that refuses 127.0.0.1:\n$invite",
            "into the Join pane" in invite,
        )
        assertTrue(
            "the client-only path is named instead:\n$invite",
            "realmlist" in invite,
        )
        // The LAN/tunnel kinds keep the app route (their addresses join).
        val tunnelInvite = TunnelInvite.inviteText(
            realmName = "Whispering Pines",
            advertised = "100.64.12.34",
            kind = TunnelInvite.TunnelKind.TAILSCALE,
        )
        assertTrue(
            "the tunnel invite keeps the working app route:\n$tunnelInvite",
            "paste 100.64.12.34 into the Join pane" in tunnelInvite,
        )
    }

    @Test
    fun joinFailureCopyIsModeAware() {
        val lan = TunnelInvite.joinFailureHint(null)
        assertTrue("plain LAN keeps the same-network copy:\n$lan", "same network" in lan)
        val tunnel = TunnelInvite.joinFailureHint("100.64.12.34")
        assertTrue(
            "tunnel mode names the advertised address:\n$tunnel",
            "100.64.12.34:3724" in tunnel,
        )
        assertTrue(
            "tunnel mode says the address resolves inside the tunnel:\n$tunnel",
            "only resolves inside" in tunnel,
        )
        assertTrue(
            "tunnel mode names the tunnel kind:\n$tunnel",
            "Tailscale" in tunnel,
        )
    }
}
