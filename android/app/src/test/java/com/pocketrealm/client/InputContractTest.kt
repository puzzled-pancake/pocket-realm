package com.pocketrealm.client

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun newImeContract(gen: Long = 1L): Pair<InputContract, RecordingSink> =
        newContract(gen).also { (contract, _) -> contract.imeOpened(gen) }

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

    @Test fun `neutral ignores empty source bookkeeping but rejects held state`() {
        val (c, _) = newContract()
        assertTrue(c.isNeutral(1))

        // Both operations retain harmless source bookkeeping internally.
        c.pointerButton(9, InputContract.PointerButton.RIGHT, pressed = false, generation = 1)
        c.gamepadAxis(10, InputContract.GamepadAxis.LEFT_X, 0f, generation = 1)
        assertTrue(c.isNeutral(1))

        c.pointerButton(9, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)
        assertFalse(c.isNeutral(1))
        c.pointerButton(9, InputContract.PointerButton.RIGHT, pressed = false, generation = 1)
        assertTrue(c.isNeutral(1))
        assertFalse(c.isNeutral(2))
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
        val tuned = InputProfile(
            version = InputProfile.CURRENT_VERSION,
            deadZone = 0.2f,
            aspectIdentity = "16:9",
            cameraSensitivity = 1.75f,
            rightStickDeadZone = 0.08f,
            overlayOpacity = 0.6f,
            overlayEnabled = false,
            overlayScale = 1.25f,
            cameraRegionWidth = 0.5f,
            invertCameraY = true,
            leftTriggerOnThreshold = 0.45f,
            leftTriggerOffThreshold = 0.24f,
            rightTriggerOnThreshold = 0.55f,
            rightTriggerOffThreshold = 0.31f,
            scheme = ControlScheme.CUSTOM,
            controllerFamily = ControllerFamily.PLAYSTATION,
            faceButtonLayout = FaceButtonLayout.ANDROID_STANDARD,
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.JUMP),
            overlayBindings = InputProfile.defaultOverlayBindings() +
                (OverlayControl.ACTION_1 to ControllerAction.INTERACT),
        )
        val roundTrip = InputProfile.fromJson(InputProfile.toJson(tuned))
        assertEquals(tuned, roundTrip)
        val migrated = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 1)
            .put("deadZone", 0.18)
            .put("aspectIdentity", "16:9"))
        assertEquals(InputProfile.CURRENT_VERSION, migrated.version)
        assertEquals(1.0f, migrated.cameraSensitivity)
        assertEquals(0.85f, migrated.overlayOpacity)
        assertEquals(ControlScheme.CLASSIC_CAMERA, migrated.scheme)
        assertEquals(ControllerAction.POINTER_RIGHT,
            InputProfile.actionFor(migrated, Rp6Control.REAR_RIGHT))
        assertEquals(ControllerAction.ESCAPE,
            InputProfile.actionFor(migrated, OverlayControl.MENU))
        assertEquals(ControllerAction.KEY_9,
            InputProfile.actionFor(migrated, Rp6Control.R1))

        val v5Bindings = org.json.JSONObject().also { stored ->
            InputProfile.classicRp6Bindings(cameraLock = true).forEach { (control, action) ->
                stored.put(control.name, action.name)
            }
        }
        val v5Classic = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 5)
            .put("aspectIdentity", "16:9")
            .put("scheme", ControlScheme.CLASSIC_CAMERA.name)
            .put("rp6Bindings", v5Bindings))
        assertEquals(ControllerAction.POINTER_RIGHT,
            InputProfile.actionFor(v5Classic, Rp6Control.REAR_RIGHT))

        val invalidBinding = InputProfile.fromJson(org.json.JSONObject()
            .put("version", InputProfile.CURRENT_VERSION)
            .put("aspectIdentity", "16:9")
            .put("rp6Bindings", org.json.JSONObject()
                .put(Rp6Control.R1.name, "NOT_AN_ACTION")
                .put(Rp6Control.L1.name, ControllerAction.JUMP.name)))
        assertEquals(ControllerAction.KEY_9,
            InputProfile.actionFor(invalidBinding, Rp6Control.R1))
        assertEquals(ControllerAction.JUMP,
            InputProfile.actionFor(invalidBinding, Rp6Control.L1))
        assertEquals(ControllerAction.KEY_1,
            InputProfile.actionFor(invalidBinding, OverlayControl.ACTION_1))
    }

    @Test fun `schema seven migrates only the exact former right stick default`() {
        fun decode(version: Int, deadZone: Double?): InputProfile {
            val json = org.json.JSONObject()
                .put("version", version)
                .put("aspectIdentity", "16:9")
            deadZone?.let { json.put("rightStickDeadZone", it) }
            return InputProfile.fromJson(json)
        }

        assertEquals(0.05f, decode(6, 0.12).rightStickDeadZone)
        assertEquals(0.05f, decode(6, null).rightStickDeadZone)
        assertEquals(0.20f, decode(6, 0.20).rightStickDeadZone)
        assertEquals(0.05f, decode(7, null).rightStickDeadZone)
        assertEquals(0.12f, decode(7, 0.12).rightStickDeadZone)
    }

    @Test fun `classic overlay menu is escape while PocketRealmPad menu is F7`() {
        val classic = InputProfile.profileForScheme(
            ControlScheme.CLASSIC_CAMERA,
            InputProfile.DEFAULT_ASPECT_IDENTITY,
        )
        val pad = InputProfile.profileForScheme(
            ControlScheme.POCKET_REALM_PAD_CAMERA,
            InputProfile.DEFAULT_ASPECT_IDENTITY,
        )
        assertEquals(ControllerAction.ESCAPE, InputProfile.actionFor(classic, OverlayControl.MENU))
        assertEquals(ControllerAction.RADIAL_MENU, InputProfile.actionFor(pad, OverlayControl.MENU))
    }

    @Test fun `digital dpad is suppressed only for the matching hat axis`() {
        assertTrue(ClientInputBridge.shouldSuppressDigitalDpad(
            KeyEvent.KEYCODE_DPAD_LEFT, hasHatX = true, hasHatY = false,
        ))
        assertTrue(ClientInputBridge.shouldSuppressDigitalDpad(
            KeyEvent.KEYCODE_DPAD_RIGHT, hasHatX = true, hasHatY = false,
        ))
        assertFalse(ClientInputBridge.shouldSuppressDigitalDpad(
            KeyEvent.KEYCODE_DPAD_UP, hasHatX = true, hasHatY = false,
        ))
        assertTrue(ClientInputBridge.shouldSuppressDigitalDpad(
            KeyEvent.KEYCODE_DPAD_DOWN, hasHatX = false, hasHatY = true,
        ))
        assertFalse(ClientInputBridge.shouldSuppressDigitalDpad(
            KeyEvent.KEYCODE_DPAD_DOWN, hasHatX = false, hasHatY = false,
        ))
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

        fun keyEventCount(): Int = events.count { it is SinkEvent.Key }
    }

    private class BlockingReleaseSink : InputSink {
        val events = java.util.Collections.synchronizedList(mutableListOf<SinkEvent>())
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        @Volatile var blockNextRelease = false

        override fun inject(event: SinkEvent) {
            events += event
            if (blockNextRelease && event is SinkEvent.Key && !event.pressed) {
                blockNextRelease = false
                releaseEntered.countDown()
                check(allowRelease.await(2, TimeUnit.SECONDS)) { "release test timed out" }
            }
        }
    }

    private class ManualImeScheduler : ImePulseScheduler {
        private val pending = java.util.ArrayDeque<() -> Unit>()
        val delays = mutableListOf<Long>()

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            delays += delayMillis
            pending.addLast(action)
        }

        fun runNext() = checkNotNull(pending.pollFirst()) { "no scheduled IME step" }.invoke()

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
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
        c.pumpGamepadPointer(1_000_000_000L, 1)
        c.pumpGamepadPointer(1_016_666_667L, 1)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerMoveDelta(3, -1),
            ),
            sink.events,
        )
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 1f, 99)
        assertEquals(2, sink.events.size)
        assertTrue(c.rejectedStaleEventCount >= 1)
    }

    @Test fun `right stick owns camera only while displaced and never needs a lock button`() {
        val (contract, sink) = newContract()
        contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.8f, 1)
        contract.pumpGamepadPointer(1_000_000_000L, 1)
        contract.pumpGamepadPointer(1_016_666_667L, 1)
        contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0f, 1)
        contract.pumpGamepadPointer(1_033_333_334L, 1)

        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertTrue(sink.events.any { it is SinkEvent.PointerMoveDelta && it.dx > 0 })
    }

    @Test fun `IME suppresses movement only until it is explicitly closed`() {
        val (contract, sink) = newContract()
        contract.imeOpened(1)
        contract.gamepadAxis(7, InputContract.GamepadAxis.LEFT_Y, -1f, 1)
        assertTrue(sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())
        contract.imeClosed(1)
        contract.gamepadAxis(7, InputContract.GamepadAxis.LEFT_Y, -1f, 1)
        assertEquals(
            KeyEvent.KEYCODE_W,
            sink.events.filterIsInstance<SinkEvent.Key>().single().logicalKeyCode,
        )
    }

    @Test fun `small right stick samples accumulate smoothly and neutral clears the fraction`() {
        val (c, sink) = newContract()
        // Exercise fractional accumulation independently from the v7 default
        // change; migration and near-center behavior are covered separately.
        c.switchProfile(InputProfile.DEFAULT.copy(rightStickDeadZone = 0.12f), "16:9", 1)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        var frame = 1_000_000_000L
        c.pumpGamepadPointer(frame, 1)
        repeat(3) { frame += 16_666_667L; c.pumpGamepadPointer(frame, 1) }
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty())
        frame += 16_666_667L
        c.pumpGamepadPointer(frame, 1)
        assertEquals(
            listOf(SinkEvent.PointerMoveDelta(1, 0)),
            sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>(),
        )

        sink.events.clear()
        repeat(3) { frame += 16_666_667L; c.pumpGamepadPointer(frame, 1) }
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0f, 1)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        frame += 16_666_667L
        c.pumpGamepadPointer(frame, 1)
        assertTrue(
            "neutral must discard an unfinished sub-pixel gesture",
            sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty(),
        )

        c.releaseSource(7)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        repeat(3) { frame += 16_666_667L; c.pumpGamepadPointer(frame, 1) }
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty())
        c.releaseSource(7)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        frame += 16_666_667L
        c.pumpGamepadPointer(frame, 1)
        assertTrue(
            "source release must discard an unfinished fraction",
            sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty(),
        )

        c.releaseSource(7)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        repeat(3) { frame += 16_666_667L; c.pumpGamepadPointer(frame, 1) }
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty())
        c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.15f, 1)
        frame += 16_666_667L
        c.pumpGamepadPointer(frame, 1)
        assertTrue(
            "global release must discard an unfinished fraction",
            sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().isEmpty(),
        )
    }

    @Test fun `v7 right stick default accepts live near-center values without accepting noise`() {
        fun movementFor(raw: Float, frames: Int): Pair<Int, List<SinkEvent.PointerButton>> {
            val (contract, sink) = newContract()
            contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, raw, 1)
            var frame = 1_000_000_000L
            contract.pumpGamepadPointer(frame, 1)
            repeat(frames) {
                frame += 16_666_667L
                contract.pumpGamepadPointer(frame, 1)
            }
            return sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().sumOf { it.dx } to
                sink.events.filterIsInstance<SinkEvent.PointerButton>()
        }

        val below = movementFor(0.049f, 180)
        assertEquals(0, below.first)
        assertTrue(below.second.isEmpty())

        val justAbove = movementFor(0.051f, 180)
        assertTrue("a value immediately above the v7 dead zone must eventually move", justAbove.first > 0)
        assertEquals(SinkEvent.PointerButton(SinkButton.RIGHT, true), justAbove.second.single())

        val liveSample = movementFor(0.094f, 12)
        assertTrue("the observed RP6 0.094 sample must produce smooth camera motion", liveSample.first > 0)
    }

    @Test fun `right stick velocity is independent of duplicate report rate and long frames are bounded`() {
        fun distance(reportCount: Int): Int {
            val (contract, sink) = newContract()
            repeat(reportCount) {
                contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.75f, 1)
            }
            var frame = 1_000_000_000L
            contract.pumpGamepadPointer(frame, 1)
            repeat(60) {
                frame += 16_666_667L
                contract.pumpGamepadPointer(frame, 1)
            }
            return sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().sumOf { it.dx }
        }
        assertEquals(distance(60), distance(120))
        assertEquals(distance(60), distance(166))

        val (contract, sink) = newContract()
        contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 1f, 1)
        contract.pumpGamepadPointer(1_000_000_000L, 1)
        contract.pumpGamepadPointer(2_000_000_000L, 1)
        val jumped = sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().sumOf { it.dx }
        assertTrue("one stalled frame must not create a catch-up jump", jumped <= 24)
    }

    @Test fun `right stick distance is independent of display pump cadence`() {
        fun distance(framesPerSecond: Int): Int {
            val (contract, sink) = newContract()
            contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.75f, 1)
            var frame = 1_000_000_000L
            contract.pumpGamepadPointer(frame, 1)
            val step = 1_000_000_000L / framesPerSecond
            repeat(framesPerSecond) {
                frame += step
                contract.pumpGamepadPointer(frame, 1)
            }
            return sink.events.filterIsInstance<SinkEvent.PointerMoveDelta>().sumOf { it.dx }
        }

        val at60Hz = distance(60)
        val at120Hz = distance(120)
        assertTrue("60 Hz and 120 Hz pumps must differ by at most one pixel",
            kotlin.math.abs(at60Hz - at120Hz) <= 1)
    }

    @Test fun `combined RP6 device sources classify keyboard-tagged buttons as gamepad`() {
        assertTrue(ClientInputBridge.isGamepadSource(
            android.view.InputDevice.SOURCE_KEYBOARD,
            android.view.InputDevice.SOURCE_KEYBOARD or android.view.InputDevice.SOURCE_GAMEPAD or
                android.view.InputDevice.SOURCE_JOYSTICK,
            KeyEvent.KEYCODE_BUTTON_A,
        ))
        val combined = android.view.InputDevice.SOURCE_KEYBOARD or
            android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK
        for (ordinaryKey in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE)) {
            assertFalse(ClientInputBridge.isGamepadSource(
                android.view.InputDevice.SOURCE_KEYBOARD, combined, ordinaryKey,
            ))
        }
        assertFalse(ClientInputBridge.isGamepadSource(
            android.view.InputDevice.SOURCE_KEYBOARD,
            android.view.InputDevice.SOURCE_KEYBOARD,
            KeyEvent.KEYCODE_BUTTON_A,
        ))
    }

    @Test fun `relative pointer injection clamps at both surface edges`() {
        assertEquals(0, com.winlator.xserver.XServer.clampInjectedPointerCoordinate(2, -50, 1920))
        assertEquals(1919, com.winlator.xserver.XServer.clampInjectedPointerCoordinate(1918, 50, 1920))
        assertEquals(101, com.winlator.xserver.XServer.clampInjectedPointerCoordinate(100, 1, 1920))
        assertEquals(0, com.winlator.xserver.XServer.clampInjectedPointerCoordinate(100, 1, 0))
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

    @Test fun `logical key remains down until axis and other-source button owners both release`() {
        val (c, sink) = newContract()
        val profile = InputProfile.DEFAULT.copy(
            scheme = ControlScheme.CUSTOM,
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.MOVE_W),
        )
        c.switchProfile(profile, "16:9", 1)
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadAxis(8, InputContract.GamepadAxis.LEFT_Y, -1f, 1, layout)
        c.gamepadButton(44, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        c.gamepadButton(44, KeyEvent.KEYCODE_BUTTON_R1, false, 1, layout)
        assertEquals(listOf(true), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
        c.gamepadAxis(8, InputContract.GamepadAxis.LEFT_Y, 0f, 1, layout)
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
    }

    @Test fun `same-source physical button and axis have independent logical key ownership`() {
        val (c, sink) = newContract()
        val profile = InputProfile.DEFAULT.copy(
            scheme = ControlScheme.CUSTOM,
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.MOVE_W),
        )
        c.switchProfile(profile, "16:9", 1)
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadAxis(8, InputContract.GamepadAxis.LEFT_Y, -1f, 1, layout)
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_R1, false, 1, layout)
        assertEquals(listOf(true), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
        c.gamepadAxis(8, InputContract.GamepadAxis.LEFT_Y, 0f, 1, layout)
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
    }

    @Test fun `same-source physical buttons sharing a pointer output release on final owner`() {
        val (c, sink) = newContract()
        val profile = InputProfile.DEFAULT.copy(
            scheme = ControlScheme.CUSTOM,
            rp6Bindings = InputProfile.defaultRp6Bindings() + mapOf(
                Rp6Control.R1 to ControllerAction.POINTER_RIGHT,
                Rp6Control.L1 to ControllerAction.POINTER_RIGHT,
            ),
        )
        c.switchProfile(profile, "16:9", 1)
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_L1, true, 1, layout)
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_R1, false, 1, layout)
        assertEquals(listOf(true), sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        c.gamepadButton(8, KeyEvent.KEYCODE_BUTTON_L1, false, 1, layout)
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
    }

    @Test fun `source release and repeat down preserve final logical key edge`() {
        val (c, sink) = newContract()
        val profile = InputProfile.DEFAULT.copy(
            scheme = ControlScheme.CUSTOM,
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.MOVE_W),
        )
        c.switchProfile(profile, "16:9", 1)
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(1, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        c.gamepadButton(1, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
        assertEquals(listOf(true), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
        assertEquals(0, c.releaseSource(1).keyCount)
        assertEquals(listOf(true), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
        assertEquals(1, c.releaseSource(2).keyCount)
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
    }

    @Test fun `global lifecycle exits release a shared logical key exactly once`() {
        val exits = listOf<Pair<String, (InputContract) -> InputContract.ReleaseReport>>(
            "releaseAll" to { it.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT) },
            "focusLost" to { it.focusLost() },
            "imeOpened" to { it.imeOpened(1) },
            "profileSwitch" to { it.switchProfile(InputProfile.DEFAULT, "16:9", 1) },
        )
        for ((name, exit) in exits) {
            val (c, sink) = newContract()
            val profile = InputProfile.DEFAULT.copy(
                scheme = ControlScheme.CUSTOM,
                rp6Bindings = InputProfile.defaultRp6Bindings() +
                    (Rp6Control.R1 to ControllerAction.MOVE_W),
            )
            c.switchProfile(profile, "16:9", 1)
            val layout = InputContract.GamepadLayout.PROFILED
            c.gamepadButton(1, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
            c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)

            val report = exit(c)
            assertEquals("$name final release count", 1, report.keyCount)
            assertEquals(
                "$name must emit only the global make and final break",
                listOf(true, false),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed },
            )
        }
    }

    @Test fun `RP6 gamepad maps physical controls and releases every held output`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        val expected = listOf(
            // Automatic RP6 detection follows the printed/Nintendo positions:
            // A right, B bottom, Y left, X top.
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_F7,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_BUTTON_C to KeyEvent.KEYCODE_I,
        )
        expected.forEach { (physical, _) -> assertTrue(c.gamepadButton(22, physical, true, 1, rp6)) }
        assertTrue(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, rp6))
        assertTrue(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_Z, true, 1, rp6))

        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(expected.map { it.second }, keys.map { it.logicalKeyCode })
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.LEFT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        val report = c.releaseSource(22)
        assertEquals(expected.size, report.keyCount)
        assertEquals(2, report.buttonCount)
    }

    @Test fun `camera lock holds right mouse while stick moves without duplicate button transitions`() {
        val (c, sink) = newContract()
        assertTrue(c.toggleCameraLock(1, source = 42))
        assertTrue(c.isCameraLocked)
        c.gamepadAxis(42, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED)
        c.pumpGamepadPointer(1_000_000_000L, 1)
        c.pumpGamepadPointer(1_016_666_667L, 1)
        c.pointerButton(9, InputContract.PointerButton.RIGHT, true, 1)
        assertFalse(c.toggleCameraLock(1, source = 42))
        assertFalse(c.isCameraLocked)
        c.pointerButton(9, InputContract.PointerButton.RIGHT, false, 1)
        c.gamepadAxis(42, InputContract.GamepadAxis.RIGHT_X, 0f, 1,
            InputContract.GamepadLayout.PROFILED)
        c.pumpGamepadPointer(1_033_333_334L, 1)

        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertTrue(sink.events.any { it is SinkEvent.PointerMoveDelta && it.dx > 0 })
    }

    @Test fun `camera-lock-only source release reports its final right-button edge`() {
        val (c, sink) = newContract()
        assertTrue(c.toggleCameraLock(1, source = 42))

        val report = c.releaseSource(42)

        assertEquals(listOf(42), report.sources)
        assertEquals(1, report.buttonCount)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `right-stick source release reports automatic camera final edge`() {
        val (c, sink) = newContract()
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )
        c.pumpGamepadPointer(1_000_000_000L, 1)

        val report = c.releaseSource(8)

        assertEquals(listOf(8), report.sources)
        assertEquals(1, report.buttonCount)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `shared right-button source release counts only the final logical edge`() {
        val (c, sink) = newContract()
        assertTrue(c.toggleCameraLock(1, source = 42))
        c.pointerButton(9, InputContract.PointerButton.RIGHT, pressed = true, generation = 1)

        assertEquals(0, c.releaseSource(42).buttonCount)
        assertEquals(1, c.releaseSource(9).buttonCount)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `camera-lock retirement hands right button to another displaced stick without churn`() {
        val (c, sink) = newContract()
        assertTrue(c.toggleCameraLock(1, source = 42))
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )

        val lockRelease = c.releaseSource(42)

        assertEquals(0, lockRelease.buttonCount)
        assertEquals(
            listOf(true),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )

        val stickRelease = c.releaseSource(8)
        assertEquals(1, stickRelease.buttonCount)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `retiring an unpumped right stick does not create pointer edges`() {
        val (c, sink) = newContract()
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )

        val report = c.releaseSource(8)

        assertEquals(0, report.buttonCount)
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
    }

    @Test fun `retiring an unrelated source does not activate an unpumped right stick`() {
        val (c, sink) = newContract()
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )
        assertTrue(c.gamepadButton(9, KeyEvent.KEYCODE_BUTTON_A, true, 1))

        c.releaseSource(9)

        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
        assertEquals(0, c.releaseSource(8).buttonCount)
    }

    @Test fun `profiled analogue triggers use independent hysteresis and release safely`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadAxis(77, InputContract.GamepadAxis.LEFT_TRIGGER, 0.29f, 1, layout)
        c.gamepadAxis(77, InputContract.GamepadAxis.LEFT_TRIGGER, 0.31f, 1, layout)
        c.gamepadAxis(77, InputContract.GamepadAxis.LEFT_TRIGGER, 0.25f, 1, layout)
        c.gamepadAxis(77, InputContract.GamepadAxis.LEFT_TRIGGER, 0.19f, 1, layout)
        c.gamepadAxis(77, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, layout)
        val release = c.releaseSource(77)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
        assertEquals(1, release.keyCount)
    }

    @Test fun `face layouts map physical positions and disabled gamepads inject nothing`() {
        assertEquals(
            Rp6Control.FACE_BOTTOM,
            InputContract.faceControlForKeyCode(KeyEvent.KEYCODE_BUTTON_A, FaceButtonLayout.ANDROID_STANDARD),
        )
        assertEquals(
            Rp6Control.FACE_RIGHT,
            InputContract.faceControlForKeyCode(KeyEvent.KEYCODE_BUTTON_A, FaceButtonLayout.RP6_PRINTED),
        )
        val (c, sink) = newContract()
        assertFalse(c.gamepadButton(
            3, KeyEvent.KEYCODE_BUTTON_A, true, 1, InputContract.GamepadLayout.DISABLED,
        ))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `controller families select profiled or disabled gamepad routing`() {
        assertEquals(
            InputContract.GamepadLayout.RETROID_POCKET_6,
            ClientInputBridge.gamepadLayoutFor(ControllerFamily.AUTO, true),
        )
        assertEquals(
            InputContract.GamepadLayout.PROFILED,
            ClientInputBridge.gamepadLayoutFor(ControllerFamily.AUTO, false),
        )
        for (family in listOf(
            ControllerFamily.XBOX,
            ControllerFamily.PLAYSTATION,
            ControllerFamily.GENERIC,
        )) {
            assertEquals(
                InputContract.GamepadLayout.PROFILED,
                ClientInputBridge.gamepadLayoutFor(family, false),
            )
        }
        for (family in listOf(ControllerFamily.KEYBOARD_MOUSE, ControllerFamily.TOUCH_ONLY)) {
            assertEquals(
                InputContract.GamepadLayout.DISABLED,
                ClientInputBridge.gamepadLayoutFor(family, true),
            )
        }
    }

    @Test fun `PocketRealmPad profile emits addon semantic keys and camera variant`() {
        val sink = RecordingSink()
        val c = InputContract(sink)
        val profile = InputProfile.profileForScheme(
            ControlScheme.POCKET_REALM_PAD_CAMERA,
            InputProfile.DEFAULT_ASPECT_IDENTITY,
        ).copy(controllerFamily = ControllerFamily.RETROID_POCKET_6)
        c.attach(null, 1, profile, InputProfile.DEFAULT_ASPECT_IDENTITY)
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_R1, true, 1, rp6))
        assertTrue(c.gamepadButton(2, KeyEvent.KEYCODE_DPAD_UP, true, 1, rp6))
        assertTrue(c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_Z, true, 1, rp6))
        assertEquals(
            listOf(KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_DPAD_UP),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
        assertTrue(c.isCameraLocked)
        assertTrue(sink.events.contains(SinkEvent.PointerButton(SinkButton.RIGHT, true)))
    }

    @Test fun `RP6 left stick strafes and hat produces utility numbers`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        c.gamepadAxis(3, InputContract.GamepadAxis.LEFT_X, -1f, 1, rp6)
        c.gamepadAxis(3, InputContract.GamepadAxis.LEFT_X, 1f, 1, rp6)
        c.gamepadAxis(3, InputContract.GamepadAxis.HAT_Y, -1f, 1, rp6)
        c.gamepadAxis(3, InputContract.GamepadAxis.HAT_Y, 0f, 1, rp6)
        assertEquals(
            listOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E,
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_7),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
        assertEquals(1, c.releaseSource(3).keyCount) // E remains held.
    }

    @Test fun `RP6 remap is balanced and profile switch releases held output`() {
        val (c, sink) = newContract()
        val remapped = InputProfile.DEFAULT.copy(
            rp6Bindings = InputProfile.defaultRp6Bindings() + mapOf(
                Rp6Control.R1 to ControllerAction.JUMP,
                Rp6Control.R3 to ControllerAction.POINTER_RIGHT,
                Rp6Control.LEFT_STICK_LEFT to ControllerAction.MOVE_A,
            ),
        )
        c.switchProfile(remapped, "16:9", 1)
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.gamepadButton(31, KeyEvent.KEYCODE_BUTTON_R1, true, 1, rp6))
        assertTrue(c.gamepadButton(31, KeyEvent.KEYCODE_BUTTON_R1, false, 1, rp6))
        assertTrue(c.gamepadButton(31, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, rp6))
        assertTrue(c.gamepadAxis(31, InputContract.GamepadAxis.LEFT_X, -1f, 1, rp6))

        assertEquals(
            listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
        assertEquals(
            listOf(SinkEvent.PointerButton(SinkButton.RIGHT, true)),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )

        val report = c.switchProfile(InputProfile.DEFAULT, "16:9", 1)
        assertEquals(1, report.keyCount)
        assertEquals(1, report.buttonCount)
        assertTrue(c.isNeutral(1))
    }

    @Test fun `RP6 identity gate and Android system passthrough are strict`() {
        assertTrue(ClientInputBridge.isRetroidPocketController(
            "Retroid Pocket Controller", null, 0x2022, 0x3001,
        ))
        assertFalse(ClientInputBridge.isRetroidPocketController(
            "Generic Controller", "dc75afea56e3c3a269b97967aa26b8c93c0bd3fb", 0x2022, 0x3001,
        ))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_HOME))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_BACK))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_MUTE))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_POWER))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_SLEEP))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_WAKEUP))
        // Local Android stubs do not retain KeyEvent instance fields, so the
        // pure-JVM boundary checks exercise the explicit fallback allowlist.
        // O14 exercises isAndroidSystemEvent with real framework KeyEvents.
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_MENU))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_CAMERA))
        assertFalse(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_A))
        assertFalse(ClientInputBridge.isAndroidSystemKey(KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test fun `profile switch and gamepad dispatch share one neutral boundary`() {
        val sink = BlockingReleaseSink()
        val contract = InputContract(sink)
        contract.attach(sessionId = null, generation = 1)
        val oldProfile = InputProfile.DEFAULT.copy(
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.JUMP),
        )
        val newProfile = InputProfile.DEFAULT.copy(
            rp6Bindings = InputProfile.defaultRp6Bindings() +
                (Rp6Control.R1 to ControllerAction.RADIAL_MENU),
        )
        contract.switchProfile(oldProfile, "16:9", 1)
        assertTrue(contract.gamepadButton(
            41, KeyEvent.KEYCODE_BUTTON_R1, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))

        sink.blockNextRelease = true
        val switching = Thread { contract.switchProfile(newProfile, "16:9", 1) }
        switching.start()
        assertTrue(
            "profile switch did not reach its release boundary",
            sink.releaseEntered.await(2, TimeUnit.SECONDS),
        )

        val dispatching = Thread {
            contract.gamepadButton(
                42, KeyEvent.KEYCODE_BUTTON_R1, true, 1,
                InputContract.GamepadLayout.RETROID_POCKET_6,
            )
        }
        dispatching.start()
        sink.allowRelease.countDown()
        switching.join(2_000)
        dispatching.join(2_000)
        assertFalse(switching.isAlive)
        assertFalse(dispatching.isAlive)

        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        val releaseBoundary = keys.indexOfFirst {
            it.logicalKeyCode == KeyEvent.KEYCODE_SPACE && !it.pressed
        }
        assertTrue(releaseBoundary >= 0)
        assertEquals(
            listOf(KeyEvent.KEYCODE_F7),
            keys.drop(releaseBoundary + 1).filter { it.pressed }.map { it.logicalKeyCode },
        )
        contract.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        assertTrue(contract.isNeutral(1))
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

    @Test fun `IME keeps cursor navigation active while gameplay controls stay suppressed`() {
        val (c, sink) = newContract()
        c.imeOpened(1)
        c.pointerAbsolute(0, 10, 20, 1)
        c.pointerRelative(0, 3, -2, 1)
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        c.pointerButton(0, InputContract.PointerButton.LEFT, pressed = false, generation = 1)
        c.wheel(0, 1, 1, 1)
        assertTrue(c.gamepadAxis(
            9, InputContract.GamepadAxis.RIGHT_X, 0.5f, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        c.pumpGamepadPointer(1_000_000_000L, 1)
        c.pumpGamepadPointer(1_016_666_667L, 1)
        assertTrue(c.gamepadButton(
            9, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        assertTrue(c.gamepadButton(
            9, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        val beforeGameplay = sink.events.size
        assertFalse(c.gamepadAxis(
            9, InputContract.GamepadAxis.LEFT_X, 1f, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        assertFalse(c.gamepadButton(
            9, KeyEvent.KEYCODE_BUTTON_A, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        assertEquals("IME must suppress gameplay keys but not the cursor", beforeGameplay, sink.events.size)
        assertTrue(sink.events.any { it is SinkEvent.PointerMove })
        assertTrue(sink.events.any { it is SinkEvent.PointerMoveDelta })
        assertTrue(sink.events.any { it is SinkEvent.PointerButton })
    }

    @Test fun `recreated input connection does not cancel queued IME input`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)
        assertTrue(c.imeCommit("a", 1).allAccepted)
        c.imeOpened(1)
        scheduler.drain()
        assertEquals(
            listOf(KeyEvent.KEYCODE_A to true, KeyEvent.KEYCODE_A to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
    }

    @Test fun `IME commit injects accepted characters in order`() {
        val (c, sink) = newImeContract()
        val result = c.imeCommit("ab", 1)
        assertTrue(result.allAccepted)
        assertEquals(2, result.accepted.size)
        // Each character = DOWN + UP = 2 Key events; total 4.
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(4, keyEvents.size)
    }

    @Test fun `scheduled IME pulses dwell and preserve inter-key ordering`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        assertTrue(c.imeCommit("ab", 1).allAccepted)
        assertEquals(listOf(InputContract.IME_KEY_DWELL_MS), scheduler.delays)
        assertEquals(1, sink.keyEventCount())

        scheduler.runNext()
        assertEquals(InputContract.IME_KEY_GAP_MS, scheduler.delays.last())
        assertEquals(2, sink.keyEventCount())
        scheduler.runNext()
        assertEquals(InputContract.IME_KEY_DWELL_MS, scheduler.delays.last())
        scheduler.runNext()

        assertEquals(4, sink.keyEventCount())
        assertEquals(
            listOf(
                InputContract.IME_KEY_DWELL_MS,
                InputContract.IME_KEY_GAP_MS,
                InputContract.IME_KEY_DWELL_MS,
            ),
            scheduler.delays,
        )
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `IME text delete and editor action share one FIFO`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        c.imeCommit("a", 1)
        c.imeDelete(1, 1)
        c.imeKeyTap(KeyEvent.KEYCODE_ENTER, 1)
        scheduler.drain()

        assertEquals(6, sink.keyEventCount())
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_A to true,
                KeyEvent.KEYCODE_A to false,
                KeyEvent.KEYCODE_DEL to true,
                KeyEvent.KEYCODE_DEL to false,
                KeyEvent.KEYCODE_ENTER to true,
                KeyEvent.KEYCODE_ENTER to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertEquals(listOf(50L, 10L, 50L, 10L, 50L), scheduler.delays)
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `non-default imeKeyGapMs flows to the inter-key gap`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler, imeKeyGapMs = 37L)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        assertTrue(c.imeCommit("ab", 1).allAccepted)
        scheduler.drain()
        // Dwell stays at the default (50L); only the inter-key gap is overridden.
        assertEquals(listOf(50L, 37L, 50L), scheduler.delays)
    }

    @Test fun `non-default imeKeyDwellMs overrides the make-break dwell`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler, imeKeyDwellMs = 90L)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        assertTrue(c.imeCommit("ab", 1).allAccepted)
        scheduler.drain()
        // dwell(90), gap(10 default), dwell(90)
        assertEquals(listOf(90L, 10L, 90L), scheduler.delays)
    }

    @Test fun `companion timing constants are unchanged for pinned callers`() {
        assertEquals(50L, InputContract.IME_KEY_DWELL_MS)
        assertEquals(10L, InputContract.IME_KEY_GAP_MS)
        assertEquals(300L, InputContract.AUTO_LOGIN_FIELD_SETTLE_MS)
        assertEquals(80L, InputContract.IME_POINTER_DWELL_MS)
    }

    @Test fun `release cancels pending IME pulses without a late event`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        c.imeCommit("aa", 1)
        assertEquals(1, sink.keyEventCount())
        val release = c.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        assertEquals(1, release.keyCount)
        assertEquals(2, sink.keyEventCount())
        scheduler.drain()
        assertEquals(2, sink.events.size)
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `IME close releases a mid-dwell key exactly once`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        c.imeCommit("a", 1)
        c.imeClosed(1)
        assertEquals(
            listOf(KeyEvent.KEYCODE_A to true, KeyEvent.KEYCODE_A to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        scheduler.drain()
        assertEquals(2, sink.keyEventCount())
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `pause during inter-key gap prevents the next down`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        c.imeCommit("ab", 1)
        scheduler.runNext() // A up; gap callback is now pending.
        c.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        scheduler.drain()
        assertEquals(2, sink.keyEventCount())
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `detach is idempotent and rejects retained IME connections`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)
        c.imeCommit("a", 1)

        assertEquals(1, c.detach().keyCount)
        assertEquals(0, c.detach().keyCount)
        val rejected = c.imeCommit("b", 1)
        assertEquals(ImeCharMap.Rejection.STALE_GENERATION, rejected.rejection)
        scheduler.drain()
        assertEquals(2, sink.keyEventCount())
    }

    @Test fun `old callback cannot interfere with a new generation queue`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)
        c.imeCommit("a", 1)

        c.attach(sessionId = null, generation = 2)
        c.imeOpened(2)
        c.imeCommit("b", 2)
        scheduler.drain()

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_A to true,
                KeyEvent.KEYCODE_A to false,
                KeyEvent.KEYCODE_B to true,
                KeyEvent.KEYCODE_B to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isImeInputIdle)
    }

    @Test fun `IME queue backpressure rejects whole operations`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)
        c.imeOpened(1)

        assertTrue(c.imeCommit("a".repeat(InputContract.MAX_PENDING_IME_PULSES), 1).allAccepted)
        assertEquals(ImeCharMap.Rejection.QUEUE_FULL, c.imeCommit("b", 1).rejection)
        assertEquals(0, c.imeDelete(1, 1))
        assertFalse(c.imeKeyTap(KeyEvent.KEYCODE_ENTER, 1))
        assertEquals(1, sink.keyEventCount())
    }

    @Test fun `IME input is rejected outside an active editor session`() {
        val (c, sink) = newContract()
        assertEquals(ImeCharMap.Rejection.IME_INACTIVE, c.imeCommit("a", 1).rejection)
        assertEquals(0, c.imeDelete(1, 1))
        assertFalse(c.imeKeyTap(KeyEvent.KEYCODE_ENTER, 1))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `IME commit rejects unsupported characters without partial injection`() {
        val (c, sink) = newImeContract()
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
        val (c, sink) = newImeContract()
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
        val (c, sink) = newImeContract()
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
        val (c, sink) = newImeContract()
        // 'a' is supported, '中' is not → entire "a中" rejected, no 'a' injected.
        c.imeCommit("a中", 1)
        assertEquals("no partial injection", 0, sink.events.size)
        // A subsequent valid 'a' alone succeeds — proves the contract wasn't corrupted.
        c.imeCommit("a", 1)
        assertEquals("subsequent valid commit injects", 2, sink.events.size)
    }

    @Test fun `IME commit atomicity - final state neutral after rejected`() {
        val (c, sink) = newImeContract()
        c.imeCommit("a中b", 1)
        // No keys/buttons should be held after the rejected commit.
        val report = c.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        assertEquals("no held keys after rejected commit", 0, report.keyCount)
        assertEquals("no held buttons after rejected commit", 0, report.buttonCount)
    }

    @Test fun `stale-generation IME commit is rejected`() {
        val (c, sink) = newImeContract(gen = 1)
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
        val (c, sink) = newImeContract()
        val result = c.imeCommit("", 1)
        assertTrue(result.allAccepted)
        assertEquals(0, result.accepted.size)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `IME delete injects backspace pairs`() {
        val (c, sink) = newImeContract()
        val deleted = c.imeDelete(3, 1)
        assertEquals(3, deleted)
        val keyEvents = sink.events.filterIsInstance<SinkEvent.Key>()
        // 3 deletions * (DOWN + UP) = 6 events.
        assertEquals(6, keyEvents.size)
    }

    @Test fun `IME commit ordering is deterministic with editor action`() {
        val (c, sink) = newImeContract()
        c.imeCommit("b", 1)
        c.imeKeyTap(KeyEvent.KEYCODE_ENTER, 1)
        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(4, keys.size)
    }

    @Test fun `IME commit within max length succeeds`() {
        val (c, _) = newImeContract()
        val text = "a".repeat(ImeCharMap.MAX_COMMIT_LENGTH)
        val result = c.imeCommit(text, 1)
        assertEquals(ImeCharMap.MAX_COMMIT_LENGTH, result.accepted.size)
    }

    @Test fun `single player login sequence is accepted atomically and ordered`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)

        assertTrue(c.queueSinglePlayerAutoLogin("ab", "cd", 1))
        scheduler.drain()
        // Four credential characters are balanced key pairs; field selection
        // and submit use three ordinary balanced pointer clicks.
        assertEquals(8, sink.events.filterIsInstance<SinkEvent.Key>().size)
        assertEquals(6, sink.events.filterIsInstance<SinkEvent.PointerButton>().size)
        assertTrue(c.isImeInputIdle)
        c.imeClosed(1)
        assertTrue(c.isNeutral(1))
    }

    @Test fun `single player login rejection queues zero credential events`() {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val c = InputContract(sink, scheduler)
        c.attach(sessionId = null, generation = 1)

        assertFalse(c.queueSinglePlayerAutoLogin("ab", "c中", 1))
        assertTrue(sink.events.isEmpty())
        assertTrue(c.isNeutral(1))

        c.pointerButton(4, InputContract.PointerButton.LEFT, pressed = true, generation = 1)
        val before = sink.events.size
        assertFalse(c.queueSinglePlayerAutoLogin("ab", "cd", 1))
        assertEquals(before, sink.events.size)
    }
}
