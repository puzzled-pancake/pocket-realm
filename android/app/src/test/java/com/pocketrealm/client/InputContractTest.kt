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
}
