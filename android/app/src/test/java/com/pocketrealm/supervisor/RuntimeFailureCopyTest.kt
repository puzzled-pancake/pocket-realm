package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-JVM test for the human-copy mapping at the UI decode boundary. */
class RuntimeFailureCopyTest {

    @Test fun `unverified orphan detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        assertFalse(human.contains("UNVERIFIED_ORPHAN"))
        assertFalse(human.contains("WORLD ownership"))
        assertTrue(human.contains("recover"))
    }

    @Test fun `unverified orphan copy names the consented force-stop repair`() {
        val human = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        // Truthful about the deadlock: the world cannot be verified, so
        // automatic recovery will not stop it...
        assertTrue(human.contains("cannot verify"))
        assertTrue(human.contains("recovery will not stop it"))
        // ...and the only way forward is the dedicated consented action.
        assertTrue(human.contains("force-stopped"))
        assertTrue(human.contains("Force stop realm"))
        // The old copy advised "Tap Start to recover", which just re-ran the
        // refusal; the new copy must not claim a plain start can clear it.
        assertFalse(human.contains("Tap Start"))
    }

    @Test fun `db owned by dead session detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize(
            "DATABASE: DB_OWNED_BY_DEAD_SESSION: ended own-session database owner " +
                "could not be released: injected drain failure")
        assertFalse(human.contains("DB_OWNED_BY_DEAD_SESSION"))
        assertFalse(human.contains("DATABASE:"))
        // Truthful about the wedge: the lock belongs to an ended session on
        // this device, so retrying the start cannot clear it...
        assertTrue(human.contains("database"))
        assertTrue(human.contains("ended"))
        assertTrue(human.contains("retrying"))
        // ...and the only way forward is the dedicated consented action.
        assertTrue(human.contains("Force stop realm"))
        assertTrue(human.contains("checked and repaired"))
    }

    @Test fun `db owned by dead session copy keeps the unverified orphan copy distinct`() {
        val stale = RuntimeFailureCopy.humanize("DB_OWNED_BY_DEAD_SESSION: database claim rejected")
        val orphan = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        // The two repair classes never collapse into each other's copy.
        assertTrue(stale.contains("locked"))
        assertFalse(stale.contains("cannot verify"))
        assertTrue(orphan.contains("cannot verify"))
        assertFalse(orphan.contains("locked"))
    }

    @Test fun `timeout cancellation detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize(
            "TimeoutCancellationException: Timed out waiting for 1800000 ms")
        assertFalse(human.contains("TimeoutCancellationException"))
        assertTrue(human.contains("stopped responding"))
        assertTrue(human.contains("recover"))
    }

    @Test fun `component-prefixed timeout detail still maps`() {
        val human = RuntimeFailureCopy.humanize(
            "DATABASE: TimeoutCancellationException: Timed out waiting for 1000 ms")
        assertFalse(human.contains("TimeoutCancellationException"))
    }

    @Test fun `unknown detail passes through unchanged`() {
        assertEquals(
            "world: DB_REVISION ledger drift",
            RuntimeFailureCopy.humanize("world: DB_REVISION ledger drift"),
        )
        assertEquals("", RuntimeFailureCopy.humanize(""))
    }

    // ------------------------------------------------------------------
    // the LAN-aware entries.
    // ------------------------------------------------------------------

    @Test fun `lan host interface loss stop reason maps to the restart copy`() {
        val human = RuntimeFailureCopy.humanize("LAN_HOST_INTERFACE_LOST")
        assertTrue(human.contains("network connection changed"))
        assertTrue(human.contains("world was saved"))
        assertFalse(human.contains("LAN_HOST_INTERFACE_LOST"))
    }

    @Test fun `stale lan host address refusal maps to the pick-an-address copy`() {
        val human = RuntimeFailureCopy.humanize(
            "world start failed: LAN host address is not an active private IPv4 interface",
        )
        // Platform-neutral wording: the copy renders on Android too, where
        // there is no "Settings → LAN play" destination and no PC.
        assertTrue(human.contains("isn't active on this device"))
        assertTrue(human.contains("LAN hosting settings"))
        assertFalse(human.contains("this PC"))
        assertFalse(human.contains("Settings → LAN play"))
        assertFalse(human.contains("active private IPv4 interface"))
    }

    @Test fun `join preflight client refusal maps to the import copy`() {
        val human = RuntimeFailureCopy.humanize(
            "LAN-JOIN-CLIENT-MISSING: no client directory resolves",
        )
        assertTrue(human.contains("Joining doesn't need a local realm"))
        assertTrue(human.contains("open Import"))
        assertFalse(human.contains("LAN-JOIN"))
    }

    @Test fun `probe failure carries the probed address and the firewall remedy`() {
        val human = RuntimeFailureCopy.humanize(
            "LAN-PROBE-UNREACHABLE: no answer from 192.168.1.50 within 2000 ms",
        )
        assertTrue(human.contains("No realm answered at 192.168.1.50:3724"))
        assertTrue(human.contains("Windows Firewall on the host"))
        assertFalse(human.contains("LAN-PROBE"))
    }

    @Test fun `probe failure without an address still renders the remedy`() {
        val human = RuntimeFailureCopy.humanize("LAN-PROBE-UNREACHABLE")
        assertTrue(human.contains("No realm answered at the host's address:3724"))
    }

    @Test fun `non-lan probe target maps to the tunnel address-class copy`() {
        val human = RuntimeFailureCopy.humanize("LAN-PROBE-NOT-LAN: 100.100.0.1")
        assertTrue(human.contains("inside the host's tunnel"))
        assertFalse(human.contains("LAN-PROBE-NOT-LAN"))
    }

    @Test fun `banned account surfaces with the extracted name`() {
        val human = RuntimeFailureCopy.humanize(
            "AUTH_LOGON_FAILED_BANNED: account 'FRIENDONE' is banned",
        )
        assertEquals(
            "FRIENDONE is banned on this realm; they can't log in until you unban them.",
            human,
        )
    }

    @Test fun `banned account without a name in the detail still renders the copy`() {
        val human = RuntimeFailureCopy.humanize("AUTH_LOGON_FAILED_BANNED")
        assertTrue(human.contains("banned on this realm"))
        assertTrue(human.contains("unban"))
    }

    @Test fun `lan entries never shadow the pre-existing mappings`() {
        // The LAN markers' wording must not accidentally contain the older
        // raw markers (the when-chain is substring keyed).
        listOf(
            "LAN_HOST_INTERFACE_LOST",
            "LAN host address is not an active private IPv4 interface",
            "LAN-JOIN-CLIENT-MISSING",
            "LAN-PROBE-UNREACHABLE",
            "LAN-PROBE-NOT-LAN",
            "AUTH_LOGON_FAILED_BANNED",
        ).forEach { marker ->
            assertFalse("marker shadows UNVERIFIED_ORPHAN: $marker", "UNVERIFIED_ORPHAN" in marker)
            assertFalse("marker shadows the timeout marker: $marker", "TimeoutCancellationException" in marker)
        }
    }

    @Test fun `missing staged overlay refusal maps to the re-prepare copy`() {
        // Design-timeframe 's fail-closed family: the shared plan's
        // unstaged refusal, wrapped by the start-leg's loud prefix, must
        // never reach the card as raw engineer detail.
        val human = RuntimeFailureCopy.humanize(
            "world start refused: timeframe overlay failed: timeframe overlay: " +
                "no overlay staged for \"vanilla-1.1\" (staged stages: " +
                "[vanilla-1.2, vanilla-1.12.1])",
        )
        assertTrue(human.contains("Re-prepare the game files"))
        assertFalse(human.contains("no overlay staged for"))
        assertFalse(human.contains("staged stages"))
    }

    @Test fun `overlay statement failure maps to the recover copy`() {
        val human = RuntimeFailureCopy.humanize(
            "world start refused: timeframe overlay failed: timeframe overlay for " +
                "vanilla-1.5: statement (INSERT OR IGNORE ...) failed: database disk " +
                "image is malformed",
        )
        assertTrue(human.contains("Use Recover"))
        assertFalse(human.contains("statement ("))
        assertFalse(human.contains("malformed"))
    }

    @Test fun `unstaged overlay refusal on tbc maps to the default-stage remedy`() {
        // The detail string is built by the
        // PRODUCER (TimeframeOverlayPlan.unstagedRefusal), never hand-typed
        // — the producer->consumer string contract is pinned by round trip,
        // so a wording/format change in the producer breaks HERE instead of
        // silently reverting the non-vanilla lanes to the wrong remedy.
        val human = RuntimeFailureCopy.humanize(
            "world start refused: timeframe overlay failed: " +
                com.pocketrealm.database.TimeframeOverlayPlan.unstagedRefusal("tbc-2.0.1"),
        )
        assertTrue(human.contains("World timeframe"))
        assertTrue(human.contains("current-release"))
        assertFalse(human.contains("Re-prepare the game files"))
        assertFalse(human.contains("no overlay staged for"))
    }

    @Test fun `unstaged overlay refusal on wotlk maps to the default-stage remedy`() {
        val human = RuntimeFailureCopy.humanize(
            "world start refused: timeframe overlay failed: " +
                com.pocketrealm.database.TimeframeOverlayPlan.unstagedRefusal("wotlk-3.0.2"),
        )
        assertTrue(human.contains("World timeframe"))
        assertFalse(human.contains("Re-prepare the game files"))
    }

    @Test fun `unstaged overlay refusal on vanilla keeps the re-prepare remedy`() {
        // the vanilla remedy stays: a re-prepare DOES restage vanilla's
        // overlays, so the original copy is the truthful one there.
        // Round-tripped through the producer like the flavor cases.
        val human = RuntimeFailureCopy.humanize(
            "world start refused: timeframe overlay failed: " +
                com.pocketrealm.database.TimeframeOverlayPlan.unstagedRefusal("vanilla-1.1"),
        )
        assertTrue(human.contains("Re-prepare the game files"))
        assertFalse(human.contains("World timeframe"))
    }
}
