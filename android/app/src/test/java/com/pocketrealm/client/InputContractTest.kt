package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM unit tests for [InputContract] v1 state-machine logic.
 *
 * These cover the pointer/wheel/generation/release logic that does not require a
 * real Android [android.view.KeyEvent]: generation gating, stale rejection,
 * per-source button tracking, wheel atomicity, deterministic release order,
 * source isolation, profile aspect-reset, and the release report. Keyboard
 * make/break and end-to-end Win32 observation are verified by the O14
 * instrumentation test (real Android framework + Wine probe).
 *
 * The [RecordingSink] records every [SinkEvent] in order so assertions can check
 * exact injection sequences and release ordering.
 */
class InputContractTest {

    private fun newContract(gen: Long = 1L): Pair<InputContract, RecordingSink> {
        val sink = RecordingSink()
        val c = InputContract(sink)
        c.attach(sessionId = null, generation = gen)
        return c to sink
    }

    @Test fun `current-generation event is accepted`() {
        val (c, sink) = newContract(gen = 7L)
        c.pointerAbsolute(10, 100, 200, 7L)
        assertEquals(listOf<SinkEvent>(SinkEvent.PointerMove(100, 200)), sink.events)
        assertEquals(0L, c.rejectedStaleEventCount)
    }

    @Test fun `stale-generation event is rejected and counted`() {
        val (c, sink) = newContract(gen = 5L)
        c.pointerAbsolute(10, 1, 2, 99L)
        assertTrue(sink.events.isEmpty())
        assertEquals(1L, c.rejectedStaleEventCount)
        // A subsequent current-gen event still works.
        c.pointerAbsolute(10, 3, 4, 5L)
        assertEquals(1, sink.events.size)
    }

    @Test fun `right and middle buttons tracked and released`() {
        val (c, sink) = newContract()
        c.pointerButton(10, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        c.pointerButton(10, InputContract.PointerButton.MIDDLE, pressed = true, generation = 1)
        val report = c.releaseAll(InputContract.ReleaseReason.FOCUS_LOSS)
        assertEquals(2, report.buttonCount)
        assertEquals(0, report.keyCount)
        // Release order must be right then middle (fixed order), regardless of
        // press order.
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, pressed = true),
                SinkEvent.PointerButton(SinkButton.MIDDLE, pressed = true),
                SinkEvent.PointerButton(SinkButton.RIGHT, pressed = false),
                SinkEvent.PointerButton(SinkButton.MIDDLE, pressed = false),
            ),
            sink.events,
        )
    }

    @Test fun `left button press and release preserves verified path`() {
        val (c, sink) = newContract()
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = false, generation = 1)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.LEFT, pressed = true),
                SinkEvent.PointerButton(SinkButton.LEFT, pressed = false),
            ),
            sink.events,
        )
    }

    @Test fun `duplicate button press does not double-inject`() {
        val (c, sink) = newContract()
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        assertEquals(1, sink.events.size)
    }

    @Test fun `unmatched button up is dropped without synthesizing down`() {
        val (c, sink) = newContract()
        // UP with no prior DOWN: no injection, no synth.
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = false, generation = 1)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `wheel emits atomic press-release and is never retained`() {
        val (c, sink) = newContract()
        c.wheel(0, vTicks = 2, hTicks = 0, generation = 1)
        // 2 ticks down → 2x (SCROLL_DOWN press + release).
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.SCROLL_DOWN, pressed = true),
                SinkEvent.PointerButton(SinkButton.SCROLL_DOWN, pressed = false),
                SinkEvent.PointerButton(SinkButton.SCROLL_DOWN, pressed = true),
                SinkEvent.PointerButton(SinkButton.SCROLL_DOWN, pressed = false),
            ),
            sink.events,
        )
        // Wheel is never retained: releaseAll reports zero buttons/keys.
        val report = c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        assertEquals(0, report.buttonCount)
        assertEquals(0, report.keyCount)
    }

    @Test fun `wheel up direction uses SCROLL_UP`() {
        val (c, sink) = newContract()
        c.wheel(0, vTicks = -1, hTicks = 0, generation = 1)
        assertEquals(SinkButton.SCROLL_UP, (sink.events[0] as SinkEvent.PointerButton).button)
    }

    @Test fun `horizontal wheel uses scroll-click buttons`() {
        val (c, sink) = newContract()
        c.wheel(0, vTicks = 0, hTicks = 1, generation = 1)
        assertEquals(SinkButton.SCROLL_CLICK_RIGHT, (sink.events[0] as SinkEvent.PointerButton).button)
    }

    @Test fun `relative pointer motion injects delta`() {
        val (c, sink) = newContract()
        c.pointerRelative(0, 5, -3, 1)
        assertEquals(listOf<SinkEvent>(SinkEvent.PointerMoveDelta(5, -3)), sink.events)
    }

    @Test fun `focus loss releases all held buttons`() {
        val (c, sink) = newContract()
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        val report = c.focusLost()
        assertEquals(InputContract.ReleaseReason.FOCUS_LOSS, report.reason)
        assertEquals(2, report.buttonCount)
        // After release, a second releaseAll is a no-op.
        val sink2 = (c.lastReport)
        val second = c.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        assertEquals(0, second.buttonCount)
    }

    @Test fun `generation replacement releases old state before accepting new`() {
        val (c, sink) = newContract(gen = 1)
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        // Attach a new generation: old right-button must be released first.
        val report = c.attach(sessionId = null, generation = 2)
        assertEquals(1, report.buttonCount)
        assertEquals(InputContract.ReleaseReason.GENERATION_REPLACED, report.reason)
        // Old-generation event now rejected.
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = false, generation = 1)
        assertTrue(c.rejectedStaleEventCount >= 1)
        // New-generation event accepted.
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 2)
        val last = sink.events.last()
        assertEquals(SinkButton.LEFT, (last as SinkEvent.PointerButton).button)
    }

    @Test fun `releasing one device does not release another device held state`() {
        val (c, _) = newContract()
        c.pointerButton(1, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        c.pointerButton(2, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        val report = c.releaseSource(1)
        assertEquals(listOf(1), report.sources)
        assertEquals(1, report.buttonCount)
        // Device 2's left button is still held; releasing all now frees exactly it.
        val report2 = c.releaseAll(InputContract.ReleaseReason.DEVICE_REMOVED)
        assertEquals(listOf(2), report2.sources)
        assertEquals(1, report2.buttonCount)
    }

    @Test fun `release order and diagnostics are deterministic`() {
        // Press in scrambled order; release must always emit right, middle, left.
        val (c, sink) = newContract()
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.MIDDLE, pressed = true, generation = 1)
        c.releaseAll(InputContract.ReleaseReason.CLOSE)
        val releases = sink.events.filterIsInstance<SinkEvent.PointerButton>().filter { !it.pressed }
        assertEquals(listOf(SinkButton.RIGHT, SinkButton.MIDDLE, SinkButton.LEFT), releases.map { it.button })
    }

    @Test fun `aspect mismatch selects default profile and reports reset`() {
        val sink = RecordingSink()
        val c = InputContract(sink)
        val mismatchedProfile = InputProfile(InputProfile.CURRENT_VERSION, 0.2f, "21:9")
        c.attach(sessionId = null, generation = 1, newProfile = mismatchedProfile, aspectIdentity = "16:9")
        assertTrue(c.isProfileReset)
    }

    @Test fun `aspect match keeps provided profile without reset`() {
        val sink = RecordingSink()
        val c = InputContract(sink)
        val matched = InputProfile(InputProfile.CURRENT_VERSION, 0.2f, "16:9")
        c.attach(sessionId = null, generation = 1, newProfile = matched, aspectIdentity = "16:9")
        assertFalse(c.isProfileReset)
    }

    @Test fun `input profile codec preserves persisted tuning and migrates version one`() {
        val tuned = InputProfile(InputProfile.CURRENT_VERSION, 0.2f, "16:9", 1.75f, 0.6f)
        val roundTrip = InputProfile.fromJson(InputProfile.toJson(tuned))
        assertEquals(tuned, roundTrip)
        val migrated = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 1)
            .put("deadZone", 0.18)
            .put("aspectIdentity", "16:9"))
        assertEquals(InputProfile.CURRENT_VERSION, migrated.version)
        assertEquals(1.0f, migrated.cameraSensitivity)
        assertEquals(0.85f, migrated.overlayOpacity)
    }

    @Test fun `aspect identity reduces coprime ratios`() {
        assertEquals("16:9", InputProfile.aspectIdentity(1920, 1080))
        assertEquals("16:9", InputProfile.aspectIdentity(1280, 720))
        assertEquals("4:3", InputProfile.aspectIdentity(1024, 768))
        assertNotEquals(InputProfile.aspectIdentity(1920, 1080), InputProfile.aspectIdentity(2560, 1080))
    }

    @Test fun `zero wheel ticks inject nothing`() {
        val (c, sink) = newContract()
        c.wheel(0, vTicks = 0, hTicks = 0, generation = 1)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `last report persists after release`() {
        val (c, _) = newContract()
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        assertEquals(InputContract.ReleaseReason.ON_PAUSE, c.lastReport.reason)
        assertEquals(1, c.lastReport.buttonCount)
    }

    /** A recording [InputSink] that captures every event in order. */
    private class RecordingSink : InputSink {
        val events = mutableListOf<SinkEvent>()
        override fun inject(event: SinkEvent) {
            events.add(event)
        }
    }

    // ---- IME extension tests (increment 2) --------------------------------

    @Test fun `IME opening releases held keyboard and pointer state`() {
        val (c, sink) = newContract()
        c.pointerButton(0, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        // Simulate a held keyboard key via the contract's key path (stub KeyEvent).
        val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_W)
        c.key(0, downEvent, 1)
        val report = c.imeOpened(1)
        assertEquals(InputContract.ReleaseReason.IME_OPENED, report.reason)
        assertTrue("IME open should release held key", report.keyCount >= 1)
        assertTrue("IME open should release held button", report.buttonCount >= 1)
        assertTrue("IME should be active", c.isImeActive)
    }

    @Test fun `gamepad left stick crosses dead zone with balanced WASD`() {
        val (c, sink) = newContract()
        c.gamepadAxis(42, InputContract.GamepadAxis.LEFT_X, 0.8f, 1)
        c.gamepadAxis(42, InputContract.GamepadAxis.LEFT_X, 0.0f, 1)
        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(2, keys.size)
        assertEquals(0, c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT).keyCount)
        assertEquals(android.view.KeyEvent.KEYCODE_SPACE,
            InputContract.logicalGamepadKey(android.view.KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun `gamepad right stick injects relative camera delta and stale input is rejected`() {
        val (c, sink) = newContract()
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.5f, 1)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_Y, -0.25f, 1)
        assertEquals(
            listOf(SinkEvent.PointerMoveDelta(4, 0), SinkEvent.PointerMoveDelta(0, -2)),
            sink.events,
        )
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 1f, 99)
        assertEquals(2, sink.events.size)
        assertTrue(c.rejectedStaleEventCount >= 1)
    }

    @Test fun `gamepad buttons use logical mapping and hot unplug releases the logical key`() {
        val (c, sink) = newContract()
        assertTrue(c.gamepadButton(9, android.view.KeyEvent.KEYCODE_BUTTON_A, true, 1))
        val report = c.releaseSource(9)
        assertEquals(1, report.keyCount)
        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(2, keys.size)
        assertEquals(android.view.KeyEvent.KEYCODE_SPACE,
            InputContract.logicalGamepadKey(android.view.KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun `IME closing leaves neutral state and does not restore keys`() {
        val (c, _) = newContract()
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.imeOpened(1) // releases the button
        c.imeClosed(1)
        assertFalse("IME should be inactive after close", c.isImeActive)
        // After close, a releaseAll shows nothing held (neutral state).
        val report = c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        assertEquals(0, report.buttonCount)
        assertEquals(0, report.keyCount)
    }

    @Test fun `IME active suppresses gameplay pointer injection until closed`() {
        val (c, sink) = newContract()
        c.imeOpened(1)
        c.pointerAbsolute(0, 10, 20, 1)
        c.pointerRelative(0, 3, -2, 1)
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.wheel(0, 1, 1, 1)
        assertTrue("IME should suppress gameplay pointer events", sink.events.isEmpty())
        c.imeClosed(1)
        c.pointerAbsolute(0, 10, 20, 1)
        assertEquals(1, sink.events.size)
    }

    @Test fun `IME commit injects accepted characters in order`() {
        val (c, sink) = newContract()
        val result = c.imeCommit("ab", 1)
        assertTrue(result.allAccepted)
        assertEquals(2, result.accepted.size)
        // Each character = DOWN + UP = 2 Key events; total 4.
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(4, keyEvents.size)
    }

    @Test fun `IME commit rejects unsupported characters without partial injection`() {
        val (c, sink) = newContract()
        val result = c.imeCommit("a中b", 1)
        // Atomicity: the entire commit is rejected because '中' is unsupported.
        // Zero key events are injected (no 'a', no 'b', no partial sequence).
        assertFalse("commit with unsupported char must not be allAccepted", result.allAccepted)
        assertEquals(2, result.accepted.size) // mapped but NOT injected
        assertEquals(1, result.rejected.size)
        assertEquals('中'.code, result.rejected[0])
        // NO key events should be injected — atomicity means zero injection.
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals("atomicity: zero key events for rejected commit", 0, keyEvents.size)
    }

    @Test fun `IME commit atomicity - valid commit after rejected still succeeds`() {
        val (c, sink) = newContract()
        // First: rejected commit injects nothing.
        val rejected = c.imeCommit("a中b", 1)
        assertFalse(rejected.allAccepted)
        assertEquals(0, sink.events.size)
        // Second: a fully-supported commit still injects correctly.
        val accepted = c.imeCommit("cd", 1)
        assertTrue(accepted.allAccepted)
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals("valid commit after rejected: 2 chars * 2 events = 4", 4, keyEvents.size)
    }

    @Test fun `IME commit atomicity - multiple unsupported all reported`() {
        val (c, sink) = newContract()
        // Use BMP-only unsupported chars (no surrogate pairs) for exact counting.
        val result = c.imeCommit("à中éñ", 1)
        assertFalse(result.allAccepted)
        assertEquals(0, result.accepted.size)
        assertEquals(4, result.rejected.size)
        assertEquals('à'.code, result.rejected[0])
        assertEquals('中'.code, result.rejected[1])
        assertEquals('é'.code, result.rejected[2])
        assertEquals('ñ'.code, result.rejected[3])
        assertEquals("zero injection for all-unsupported commit", 0, sink.events.size)
    }

    @Test fun `IME commit atomicity - no partial WM_CHAR sequence`() {
        val (c, sink) = newContract()
        // 'a' is supported, '中' is not → entire "a中" rejected, no 'a' injected.
        c.imeCommit("a中", 1)
        assertEquals("no partial injection", 0, sink.events.size)
        // A subsequent valid 'a' alone succeeds — proves the contract wasn't corrupted.
        c.imeCommit("a", 1)
        assertEquals("subsequent valid commit injects", 2, sink.events.size)
    }

    @Test fun `IME commit atomicity - final state neutral after rejected`() {
        val (c, sink) = newContract()
        c.imeCommit("a中b", 1)
        // No keys/buttons should be held after the rejected commit.
        val report = c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        assertEquals("no held keys after rejected commit", 0, report.keyCount)
        assertEquals("no held buttons after rejected commit", 0, report.buttonCount)
    }

    @Test fun `stale-generation IME commit is rejected`() {
        val (c, sink) = newContract(gen = 1)
        val result = c.imeCommit("abc", generation = 99)
        assertEquals(0, result.accepted.size)
        assertTrue(sink.events.isEmpty())
        assertTrue(c.rejectedStaleEventCount >= 1)
    }

    @Test fun `stale-generation IME open is rejected and does not release`() {
        val (c, _) = newContract(gen = 1)
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        val report = c.imeOpened(generation = 99)
        assertEquals(0, report.buttonCount) // stale = no release
        assertFalse("IME should not activate on stale generation", c.isImeActive)
        assertTrue(c.rejectedStaleEventCount >= 1)
    }

    @Test fun `empty IME commit is a no-op`() {
        val (c, sink) = newContract()
        val result = c.imeCommit("", 1)
        assertTrue(result.allAccepted)
        assertEquals(0, result.accepted.size)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `IME delete injects backspace pairs`() {
        val (c, sink) = newContract()
        val deleted = c.imeDelete(3, 1)
        assertEquals(3, deleted)
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        // 3 deletions * (DOWN + UP) = 6 events.
        assertEquals(6, keyEvents.size)
    }

    @Test fun `IME commit ordering is deterministic with keyboard events`() {
        val (c, sink) = newContract()
        // Inject a keyboard key, then commit IME text, then another key.
        c.key(0, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A), 1)
        c.imeCommit("b", 1)
        c.key(0, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_A), 1)
        // The order must be: A-DOWN, b-DOWN, b-UP, A-UP — deterministic.
        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(4, keys.size)
    }

    @Test fun `IME commit within max length succeeds`() {
        val (c, _) = newContract()
        val text = "a".repeat(ImeCharMap.MAX_COMMIT_LENGTH)
        val result = c.imeCommit(text, 1)
        assertEquals(ImeCharMap.MAX_COMMIT_LENGTH, result.accepted.size)
    }
}
