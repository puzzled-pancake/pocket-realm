package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.supervisor.RuntimeMode

/**
 * Realm-status browser (design realm-status-browser.md) the page
 * visibility states.
 *
 * OFF is the fail-closed default AND the teardown state — no page server,
 * no tunnel child, nothing listens. FRIENDS binds the page per the game's
 * own hosting lane (wildcard 0.0.0.0 under LAN/tunnel hosting); PUBLIC
 * binds loopback only and adds the page-only cloudflared quick tunnel.
 * The page is served under FRIENDS/PUBLIC only — a served payload's
 * `visibility` field is never "off" because an OFF session has no server.
 */
enum class RealmStatusVisibility { OFF, FRIENDS, PUBLIC }

/**
 * The pure visibility transition verdicts (§4). Refusals leave the
 * visibility at its PRIOR state and carry the honest copy the hosting
 * card renders — they never half-start the tunnel or the server.
 */
sealed class RealmTransitionVerdict {
    /** The transition may proceed; [rebind] is false for the OFF→OFF
     * style no-ops that must not bounce a healthy listener. */
    data class Allowed(val next: RealmStatusVisibility, val rebind: Boolean) :
        RealmTransitionVerdict()

    /** Refused: visibility stays at its prior state; [reason] is the
     * machine-readable code the app-side suite asserts (T3d names
     * CROSS_CHECK_FAILING in the 409 body) and [copy] is the honest,
     * pid-free/path-free refusal text (§3.2). */
    data class Refused(val reason: RefusalReason, val copy: String) :
        RealmTransitionVerdict()
}

/** Why a visibility transition was refused (§4/§5.3/§6.6). */
enum class RefusalReason {
    /** Port 18080 already bound (second app instance, leftover,
     * unrelated app). Copy names the port, never a pid or path. */
    BIND_CONFLICT,

    /** The activeSessions cross-check is failing — PUBLIC enable is
     * refused (§3.3; T3d's 409-analog). */
    CROSS_CHECK_FAILING,

    /** A lost-record orphaned page tunnel is live: the transition that
     * binds 18080 is refused until the host acknowledges or adopts it
     * (§5.3 — FRIENDS included, the resurrect scenario runs through it). */
    ORPHAN_PENDING,

    /** LOCAL/solo sessions are not offered FRIENDS (the page rides
     * the game's own membership surface, which LOCAL does not have). */
    LOCAL_NOT_OFFERED,
}

/**
 * The pure visibility state machine (design realm-status-browser.md):
 *
 *   OFF ──(enable FRIENDS)──► FRIENDS ──(enable PUBLIC)──► PUBLIC-ON
 *    ▲                          │                            │
 *    └──────── (turn off) ──────┴─────────(turn off)────────┘
 *
 * Every gate the spec pins is an INPUT, so the machine stays pure and the
 * pins run without sockets or processes:
 * the bind probe (a taken 18080 refuses with the prior state kept);
 * the players cross-check (PUBLIC must not be ENABLED while it fails);
 * the orphan-sweep gate (while a lost-record match is live, EVERY
 *    transition that binds 18080 is refused — FRIENDS included);
 * the session mode (LOCAL/solo → FRIENDS not offered).
 *
 * Turning OFF is NEVER gated: teardown must always be reachable.
 */
object RealmVisibilityMachine {

    /**
     * §5.3's refusal copy — ONE source for this machine's input-level
     * refusal AND the controller's apply-time fresh-sweep refusal (the
     * sweep that runs before a binding transition gates that same
     * transition, so an orphan that surfaced after the machine's read
     * refuses with the identical copy).
     */
    const val ORPHAN_PENDING_COPY =
        "Orphaned page tunnel detected — a cloudflared process from an " +
            "earlier app run is still serving this page. Stop it or adopt it " +
            "on the hosting card, then try again."

    /**
     * One transition decision. [current] is the served visibility;
     * [target] the switch's wish. [hostingMode] is the supervisor
     * snapshot's runtime mode (the game's own hosting lane — LOCAL offers
     * no FRIENDS seat); [bindAllowed] is the caller's 18080 bind probe;
     * [crossCheckOk] the players cross-check verdict (§3.3); [orphanGateClear]
     * the §5.3 sweep verdict.
     */
    @Suppress("ReturnCount", "LongParameterList", "MaxLineLength") // the truth table is one literal; the gates ARE the inputs
    fun transition(
        current: RealmStatusVisibility,
        target: RealmStatusVisibility,
        hostingMode: RuntimeMode,
        bindAllowed: Boolean,
        crossCheckOk: Boolean,
        orphanGateClear: Boolean,
    ): RealmTransitionVerdict {
        if (target == current) {
            return RealmTransitionVerdict.Allowed(next = current, rebind = false)
        }
        if (target == RealmStatusVisibility.OFF) {
            // Teardown is never gated (OFF is the post-teardown state).
            return RealmTransitionVerdict.Allowed(next = RealmStatusVisibility.OFF, rebind = false)
        }
        if (hostingMode != RuntimeMode.LAN_HOST) {
            return RealmTransitionVerdict.Refused(
                RefusalReason.LOCAL_NOT_OFFERED,
                "The realm-status page follows the game's own hosting lane. " +
                    "Host on your LAN (or a tunnel) to serve it.",
            )
        }
        if (!orphanGateClear) {
            return RealmTransitionVerdict.Refused(
                RefusalReason.ORPHAN_PENDING,
                ORPHAN_PENDING_COPY,
            )
        }
        if (!bindAllowed) {
            return RealmTransitionVerdict.Refused(
                RefusalReason.BIND_CONFLICT,
                "Port 18080 is already in use by another program, so the " +
                    "realm-status page cannot start. Close the other program " +
                    "and try again.",
            )
        }
        if (target == RealmStatusVisibility.PUBLIC && !crossCheckOk) {
            return RealmTransitionVerdict.Refused(
                RefusalReason.CROSS_CHECK_FAILING,
                "The player-count cross-check is failing right now, so the " +
                    "Public showcase cannot be enabled. Player counts must be " +
                    "verifiable before the page goes public.",
            )
        }
        // FRIENDS→PUBLIC rebinds (wildcard → loopback); OFF→FRIENDS and
        // OFF→PUBLIC start the server fresh; FRIENDS stays put otherwise.
        return RealmTransitionVerdict.Allowed(next = target, rebind = true)
    }

    /**
     * The boot semantics (§4, round 4): the persisted value meets the
     * fail-closed default at app launch. A persisted FRIENDS RESTORES —
     * the page server restarts, no tunnel child, no new consent (the
     * reachability is unchanged). A persisted PUBLIC restores ARMED-BUT-OFF:
     * the app never auto-starts cloudflared at launch, and the §3.5 consent
     * flip is required before any tunnel child spawns. OFF stays OFF.
     */
    fun bootOutcome(persisted: RealmStatusVisibility): BootOutcome = when (persisted) {
        RealmStatusVisibility.OFF -> BootOutcome(restored = RealmStatusVisibility.OFF, publicArmed = false)
        RealmStatusVisibility.FRIENDS -> BootOutcome(restored = RealmStatusVisibility.FRIENDS, publicArmed = false)
        RealmStatusVisibility.PUBLIC -> BootOutcome(restored = RealmStatusVisibility.OFF, publicArmed = true)
    }

    /** The launch state plus whether the hosting card must show the
     * "your Public showcase was on — enable again" armed notice (§4). */
    data class BootOutcome(val restored: RealmStatusVisibility, val publicArmed: Boolean)
}
