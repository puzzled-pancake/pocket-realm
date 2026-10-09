package com.pocketrealm.ui

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.supervisor.AdvertisedAddress
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeLaunchSpec

/**
 * Design-tunnel §4/§5/§6: the tunnel-host lane's PURE logic — the invite
 * artifact text, the tunnel-kind guidance copy, and the mode-aware join
 * validation — kept out of the composables so the contract is unit-pinned
 * without a UI. SHARED between the desktop app and the Android app
 * (desktop/shared-sources.json): both platforms render the same invite,
 * apply the same join grammar, and show the same mode-aware failure copy.
 *
 * The invite hard rule (design-lan §4.2, superseding design-tunnel's
 * round-1 §6; design-remote-accounts §4.1 demands the same split): the
 * artifact is ADDRESS BLOCK ONLY — a working username+password never
 * travels inside a pasted invite, because the paste lands in an
 * uncontrolled channel's permanent archive. The credential is its own
 * delivery step (guest accounts are handed over in person per design-lan
 * D5a; the remote verifier flow is design-remote-accounts' lane — the
 * friend's app derives (s, v) locally and only the verifier crosses).
 */
object TunnelInvite {

    /** The invited player's client-requirement line, resolved from the
     *  flavor registry (D9: `qualified().single()` loud-fails the day a
     *  second flavor qualifies, forcing the invite to carry its lane) —
     *  never a hard-coded vanilla identity in multi-flavor surface copy. */
    private fun clientRequirementLine(): String {
        // R3-2 (android-core review round 2): the last live
        // `qualified().single()` in the slice — List.single() throws
        // NoSuchElementException inside the Compose model at a
        // multi-qualified flip (a crash, not an actionable error), and it
        // ignores the holder's selection entirely. Resolve through the same
        // fail-loud machine every sibling site uses: the holder's persisted
        // pick in :ui, sole-qualified degrades to it, ambiguous refuses
        // loud with the machine's message.
        val flavor = ActiveFlavor.resolve(
            "the tunnel invite renders the qualified lane's client requirement",
        )
        val version = flavor.versionResource.substringBeforeLast('.')
        return "2) Client: WoW $version build ${flavor.build} exactly " +
            "(the realm rejects other builds)."
    }


    /** Which tunnel the advertised address names — drives the invite's
     * step-1 line and the card's guidance copy. */
    enum class TunnelKind(val displayName: String) {
        TAILSCALE("Tailscale"),
        ZERO_TIER("ZeroTier"),
        /** The documented advanced recipe (§4.3): advertised loopback, each
         * friend runs `cloudflared access tcp` locally. Copy-only, no UI lane. */
        CLOUDFLARED("cloudflared"),
        OTHER("tunnel"),
    }

    /**
     * Classify an advertised address: CGNAT 100.64/10 reads as Tailscale
     * (the design's recommended default, §4.1); loopback reads as the
     * cloudflared recipe (§4.3); private RFC1918 as ZeroTier's default
     * managed range (§4.2). Purely presentational — any numeric address
     * works with any tunnel; the copy is the best guess.
     */
    fun tunnelKindFor(advertised: AdvertisedAddress): TunnelKind = when {
        advertised.isCgnatTunnel -> TunnelKind.TAILSCALE
        advertised.isLoopback -> TunnelKind.CLOUDFLARED
        else -> TunnelKind.ZERO_TIER
    }

    /**
     * The §6 invite artifact: one text block, one copy button, rendered
     * from live state. Carries the advertised address (never the host's
     * LAN IP while tunnel mode is on, §5) and the pinned client build —
     * and NEVER a credential. [realmName] is the display name (the flavor
     * realm's name is what the friend sees in-game; the app seeds one
     * realm row).
     */
    fun inviteText(
        realmName: String,
        advertised: String,
        kind: TunnelKind,
    ): String {
        val setup = when (kind) {
            TunnelKind.TAILSCALE ->
                "1) One-time setup: install Tailscale and join my tailnet " +
                    "(I'll send the invite separately)."
            TunnelKind.ZERO_TIER ->
                "1) One-time setup: install ZeroTier and join my network " +
                    "(I'll send the network ID and authorize you)."
            TunnelKind.CLOUDFLARED ->
                "1) One-time setup: install cloudflared and run the two " +
                    "`cloudflared access tcp` commands I'll send you."
            TunnelKind.OTHER ->
                "1) One-time setup: join my tunnel network (I'll send the invite)."
        }
        return buildString {
            appendLine("── Play on $realmName " + "─".repeat((58 - realmName.length).coerceAtLeast(3)))
            appendLine(setup)
            appendLine(clientRequirementLine())
            appendLine("3) Put this one line in realmlist.wtf:")
            appendLine("     set realmlist $advertised")
            if (kind == TunnelKind.CLOUDFLARED) {
                // The loopback lane's honest app note: the advertised
                // address is each player's OWN local forwarder target, so
                // the app's Join pane — which takes private/link-local or
                // CGNAT addresses, never 127.0.0.1 — cannot join this lane.
                append(
                    "   This lane plays through the client's realmlist only: the app's Join " +
                        "pane takes LAN or tunnel (CGNAT) addresses, not a 127.0.0.1 forwarder. " +
                        "The host sets your login up on the Guests card.",
                )
            } else {
                append(
                    "   …or, if you use the Pocket Realm app: paste $advertised into the Join pane. " +
                        "(In the app, the request-a-login card only reaches the host over a shared " +
                        "LAN — over this tunnel, the host sets your login up on the Guests card " +
                        "instead.)",
                )
            }
        }
    }

    /**
     * The plain-LAN invite (design-lan's artifact for the shared-network
     * lane): the same address-block-only contract, with NO tunnel setup
     * step — a LAN friend installs nothing and joins nothing, they only
     * need the host's network. Rendered when the host has set NO tunnel
     * advertisement: the advertised field is the tunnel lane's input, so
     * its absence means the LAN lane, and a LAN address must never be
     * dressed up as a tunnel (the invite is the artifact friends follow —
     * promising an install+join that does not exist is false setup copy).
     */
    fun lanInviteText(
        realmName: String,
        lanAddress: String,
    ): String = buildString {
        appendLine("── Play on $realmName " + "─".repeat((58 - realmName.length).coerceAtLeast(3)))
        appendLine("1) No tunnel, no installs: your device just needs to be on the " +
            "host's same local network (same Wi-Fi or router).")
        appendLine(clientRequirementLine())
        appendLine("3) Put this one line in realmlist.wtf:")
        appendLine("     set realmlist $lanAddress")
        append(
            "   …or, if you use the Pocket Realm app: paste $lanAddress into the Join pane. " +
                "(This address only resolves on the host's shared network — over the " +
                "internet the host needs a tunnel lane. On this network the app's " +
                "request-a-login card reaches the host — as long as the host has " +
                "Friend access switched on.)",
        )
    }

    /**
     * The §4.4.3 mode-aware join-field validation: the JOIN grammar is the
     * widened v2 one (private/link-local LAN host, or the host's numeric
     * tunnel address). Returns the parse error message, or null when the
     * draft is a valid join target — the same shape the LAN field's
     * `parseLan` display validation uses, so the field can never disagree
     * with the spec the start builds (the model builds through
     * [RuntimeLaunchSpec.lanJoin], the same grammar).
     */
    fun joinTargetError(raw: String): String? =
        runCatching { RealmEndpoint.parseJoinTarget(raw) }.exceptionOrNull()?.message

    /**
     * The §5 mode-aware probe copy for a failed join: in tunnel mode the
     * realm address only resolves inside the host's tunnel — the D6
     * correction ("both devices are on the same network" is wrong there).
     * [advertised] null = plain LAN join, the design-lan copy.
     */
    fun joinFailureHint(advertised: String?): String = if (advertised == null) {
        "No realm answered — check the host's realm is online and that " +
            "both devices are on the same network."
    } else {
        val kind = runCatching { AdvertisedAddress.parse(advertised) }.getOrNull()
            ?.let(::tunnelKindFor)?.displayName
            ?: "tunnel"
        "No realm answered at $advertised:3724. Check the host's realm is " +
            "online and that you have joined the host's $kind network — " +
            "the realm address only resolves inside it."
    }
}
