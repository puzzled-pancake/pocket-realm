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
            touchCameraSensitivity = 0.55f,
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
        assertEquals(0.35f, migrated.touchCameraSensitivity)
        assertEquals(ControlScheme.CLASSIC_CAMERA, migrated.scheme)
        assertEquals(ControllerAction.DISABLED,
            InputProfile.actionFor(migrated, Rp6Control.REAR_RIGHT))
        assertEquals(ControllerAction.ESCAPE,
            InputProfile.actionFor(migrated, OverlayControl.MENU))
        assertEquals(ControllerAction.TARGET_PULSE,
            InputProfile.actionFor(migrated, Rp6Control.R1))
        assertEquals(ControllerAction.TARGET_PULSE,
            InputProfile.actionFor(migrated, OverlayControl.TARGET))
        assertEquals(ControllerAction.USE_LOOT_CLICK,
            InputProfile.actionFor(migrated, OverlayControl.USE_LOOT))

        val v5Classic = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 5)
            .put("aspectIdentity", "16:9")
            .put("scheme", ControlScheme.CLASSIC_CAMERA.name))
        assertEquals(ControllerAction.DISABLED,
            InputProfile.actionFor(v5Classic, Rp6Control.REAR_RIGHT))

        val invalidBinding = InputProfile.fromJson(org.json.JSONObject()
            .put("version", InputProfile.CURRENT_VERSION)
            .put("aspectIdentity", "16:9")
            .put("rp6Bindings", org.json.JSONObject()
                .put(Rp6Control.R1.name, "NOT_AN_ACTION")
                .put(Rp6Control.L1.name, ControllerAction.JUMP.name)))
        assertEquals(ControllerAction.TARGET_PULSE,
            InputProfile.actionFor(invalidBinding, Rp6Control.R1))
        assertEquals(ControllerAction.JUMP,
            InputProfile.actionFor(invalidBinding, Rp6Control.L1))
        assertEquals(ControllerAction.KEY_1,
            InputProfile.actionFor(invalidBinding, OverlayControl.ACTION_1))
    }

    @Test fun `schema seven retires addon-era defaults but preserves customization`() {
        val legacyMap = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "KEY_9",
            Rp6Control.L1 to "KEY_0",
            Rp6Control.L2 to "SHIFT",
            Rp6Control.R2 to "CTRL",
            Rp6Control.START to "RADIAL_MENU",
            Rp6Control.SELECT to "MAP",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "POINTER_LEFT",
            Rp6Control.REAR_LEFT to "INTERACT",
            Rp6Control.REAR_RIGHT to "POINTER_RIGHT",
        )
        fun decode(bindings: Map<Rp6Control, String>): InputProfile {
            val stored = org.json.JSONObject()
            bindings.forEach { (control, action) -> stored.put(control.name, action) }
            return InputProfile.fromJson(org.json.JSONObject()
                .put("version", 7)
                .put("aspectIdentity", "16:9")
                .put("scheme", ControlScheme.CLASSIC_CAMERA.name)
                .put("rp6Bindings", stored))
        }

        val migrated = decode(legacyMap)
        assertEquals(ControllerAction.USE_LOOT_CLICK, InputProfile.actionFor(migrated, Rp6Control.R2))
        assertEquals(ControllerAction.ESCAPE, InputProfile.actionFor(migrated, Rp6Control.START))
        assertEquals(ControllerAction.AUTO_RUN, InputProfile.actionFor(migrated, Rp6Control.L3))
        assertEquals(ControllerAction.JUMP, InputProfile.actionFor(migrated, Rp6Control.R3))
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(migrated, Rp6Control.REAR_LEFT))
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(migrated, Rp6Control.REAR_RIGHT))

        val custom = decode(legacyMap + (Rp6Control.R1 to ControllerAction.JUMP.name))
        assertEquals(ControlScheme.CLASSIC_CAMERA, custom.scheme)
        assertEquals(ControllerAction.JUMP, InputProfile.actionFor(custom, Rp6Control.R1))
        assertEquals(ControllerAction.CTRL, InputProfile.actionFor(custom, Rp6Control.R2))
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

    @Test fun `retired PocketRealmPad scheme migrates to the built-in stock profile`() {
        val classic = InputProfile.profileForScheme(
            ControlScheme.CLASSIC_CAMERA,
            InputProfile.DEFAULT_ASPECT_IDENTITY,
        )
        val retired = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 8)
            .put("aspectIdentity", InputProfile.DEFAULT_ASPECT_IDENTITY)
            .put("scheme", "POCKET_REALM_PAD_CAMERA"))
        assertEquals(ControllerAction.ESCAPE, InputProfile.actionFor(classic, OverlayControl.MENU))
        assertEquals(ControlScheme.CLASSIC_CAMERA, retired.scheme)
        assertEquals(InputProfile.defaultRp6Bindings(), retired.rp6Bindings)
        assertEquals(InputProfile.defaultOverlayBindings(), retired.overlayBindings)
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

    private fun scheduledConsoleContract(): Triple<InputContract, RecordingSink, ManualImeScheduler> {
        val sink = RecordingSink()
        val scheduler = ManualImeScheduler()
        val contract = InputContract(sink, scheduler)
        contract.attach(null, 1)
        contract.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        return Triple(contract, sink, scheduler)
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

    @Test fun `unlocked gamepad right stick moves free cursor and stale input is rejected`() {
        val (c, sink) = newContract()
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.5f, 1)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_Y, -0.25f, 1)
        c.pumpGamepadPointer(1_000_000_000L, 1)
        c.pumpGamepadPointer(1_016_666_667L, 1)
        assertEquals(listOf(SinkEvent.PointerMoveDelta(3, -1)), sink.events)
        c.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 1f, 99)
        assertEquals(1, sink.events.size)
        assertTrue(c.rejectedStaleEventCount >= 1)
    }

    @Test fun `right stick never presses camera button while unlocked`() {
        val (contract, sink) = newContract()
        contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0.8f, 1)
        contract.pumpGamepadPointer(1_000_000_000L, 1)
        contract.pumpGamepadPointer(1_016_666_667L, 1)
        contract.gamepadAxis(7, InputContract.GamepadAxis.RIGHT_X, 0f, 1)
        contract.pumpGamepadPointer(1_033_333_334L, 1)

        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
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
        assertTrue(justAbove.second.isEmpty())

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
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_BUTTON_THUMBR to KeyEvent.KEYCODE_SPACE,
        )
        expected.forEach { (physical, _) -> assertTrue(c.gamepadButton(22, physical, true, 1, rp6)) }
        assertTrue(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, rp6))
        assertTrue(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, rp6))
        assertTrue(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_R2, true, 1, rp6))
        assertFalse(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_C, true, 1, rp6))
        assertFalse(c.gamepadButton(22, KeyEvent.KEYCODE_BUTTON_Z, true, 1, rp6))

        val keys = sink.events.filterIsInstance<SinkEvent.Key>()
        assertEquals(
            expected.flatMap { (physical, logical) ->
                if (physical == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                    listOf(KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F9)
                } else listOf(logical)
            } + listOf(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_M),
            keys.map { it.logicalKeyCode })
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isNotEmpty())
        val report = c.releaseSource(22)
        assertEquals(expected.size - 1, report.keyCount)
        assertEquals(0, report.buttonCount)
    }

    @Test fun `external Select tap defers then emits one balanced Map press`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED

        assertTrue(c.gamepadButton(31, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout))
        assertTrue("pending Select is contract state even though it emits nothing", !c.isNeutral(1))
        assertTrue(sink.events.isEmpty())

        assertTrue(c.gamepadButton(31, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout))
        assertEquals(
            listOf(KeyEvent.KEYCODE_M to true, KeyEvent.KEYCODE_M to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `console port Select tap retains one balanced radial pulse`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        val layout = InputContract.GamepadLayout.RETROID_POCKET_6

        assertTrue(c.gamepadButton(70, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout))
        assertTrue(sink.events.isEmpty())
        assertTrue(c.gamepadButton(70, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout))

        assertEquals(
            listOf(KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `console port radial tap unlocks camera and centres pointer before F12`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        c.setCameraAimPoint(960, 540, 1)
        c.setCameraLock(true, 1)
        sink.events.clear()

        c.gamepadButton(
            78, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX,
        )
        c.gamepadButton(
            78, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX,
        )

        assertFalse(c.isCameraLocked)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
                SinkEvent.PointerMove(960, 540),
            ),
            sink.events.take(2),
        )
        assertEquals(
            listOf(KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `console port frequent target nearby use and pointer buttons are direct buttons`() {
        fun contract(): Pair<InputContract, RecordingSink> = newContract().also { (c, _) ->
            c.switchProfile(
                InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
                "16:9",
                1,
            )
        }
        val layout = InputContract.GamepadLayout.RETROID_POCKET_6

        run {
            val (c, sink) = contract()
            c.gamepadButton(71, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
            c.gamepadButton(71, KeyEvent.KEYCODE_BUTTON_R1, true, 1, layout)
            c.gamepadButton(71, KeyEvent.KEYCODE_BUTTON_R1, false, 1, layout)
            assertEquals(
                listOf(KeyEvent.KEYCODE_F6 to true, KeyEvent.KEYCODE_F6 to false),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
            )
            assertTrue("consumed Select must not open radial", c.isNeutral(1))
        }
        run {
            val (c, sink) = contract()
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_L1, true, 1, layout)
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_L1, true, 1, layout)
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_L1, false, 1, layout)
            assertEquals(
                listOf(KeyEvent.KEYCODE_F7 to true, KeyEvent.KEYCODE_F7 to false),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
            )
            assertTrue(c.isNeutral(1))
        }
        run {
            val (c, sink) = contract()
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_THUMBL, true, 1, layout)
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_THUMBL, false, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.RIGHT, true),
                    SinkEvent.PointerButton(SinkButton.RIGHT, false),
                ),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            sink.events.clear()
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_THUMBL, true, 1, layout)
            c.gamepadButton(72, KeyEvent.KEYCODE_BUTTON_THUMBL, false, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.RIGHT, true),
                    SinkEvent.PointerButton(SinkButton.RIGHT, false),
                ),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            assertTrue(c.isNeutral(1))
        }
        run {
            val (c, sink) = contract()
            c.gamepadButton(73, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
            c.gamepadButton(73, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.LEFT, true),
                    SinkEvent.PointerButton(SinkButton.LEFT, false),
                ),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            assertTrue(c.isNeutral(1))
        }
    }

    @Test fun `console port Select chords cover last hostile jump pointer and camera`() {
        fun contract(): Pair<InputContract, RecordingSink> = newContract().also { (c, _) ->
            c.switchProfile(
                InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
                "16:9",
                1,
            )
        }
        val layout = InputContract.GamepadLayout.RETROID_POCKET_6

        listOf(
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_SPACE,
        ).forEachIndexed { index, (button, key) ->
            val (c, sink) = contract()
            val source = 80 + index
            c.gamepadButton(source, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(source, button, true, 1, layout)
            c.gamepadButton(source, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(source, button, false, 1, layout)
            assertEquals(
                listOf(key to true, key to false),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
            )
            assertTrue(c.isNeutral(1))
        }
        run {
            val (c, sink) = contract()
            c.gamepadButton(82, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(82, KeyEvent.KEYCODE_BUTTON_R2, true, 1, layout)
            c.gamepadButton(82, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(82, KeyEvent.KEYCODE_BUTTON_R2, false, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.LEFT, true),
                    SinkEvent.PointerButton(SinkButton.LEFT, false),
                ),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            assertTrue(c.isNeutral(1))
        }
        run {
            val (c, sink) = contract()
            c.gamepadButton(83, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(83, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
            c.gamepadButton(83, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(83, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
            assertTrue(c.isCameraLocked)
            assertEquals(
                listOf(SinkEvent.PointerButton(SinkButton.RIGHT, true)),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            assertEquals(1, c.releaseSource(83).buttonCount)
            assertTrue(c.isNeutral(1))
        }
    }

    @Test fun `console port Select Start enters Move UI without changing R3`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        val layout = InputContract.GamepadLayout.RETROID_POCKET_6_XBOX
        c.setCameraAimPoint(960, 540, 1)
        c.setCameraLock(true, 1)
        sink.events.clear()

        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)

        assertFalse(c.isCameraLocked)
        assertTrue(sink.events.none { it is SinkEvent.PointerMove })
        assertEquals(
            listOf(SinkEvent.PointerButton(SinkButton.RIGHT, false)),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertEquals(
            listOf(KeyEvent.KEYCODE_F8 to true, KeyEvent.KEYCODE_F8 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertFalse(sink.events.filterIsInstance<SinkEvent.Key>()
            .any { it.logicalKeyCode == KeyEvent.KEYCODE_ESCAPE ||
                it.logicalKeyCode == KeyEvent.KEYCODE_F12 })
        assertTrue(c.isNeutral(1))

        sink.events.clear()
        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
        c.gamepadButton(84, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.LEFT, true),
                SinkEvent.PointerButton(SinkButton.LEFT, false),
            ),
            sink.events,
        )
    }

    @Test fun `console port Select pointer chord supports analogue R2 and Select L1 aimed use`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        val layout = InputContract.GamepadLayout.RETROID_POCKET_6
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadAxis(75, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, layout)
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadAxis(75, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.19f, 1, layout)

        assertEquals(listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        assertTrue(c.isNeutral(1))

        c.setCameraLock(true, 1)
        sink.events.clear()
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_L1, true, 1, layout)
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadButton(75, KeyEvent.KEYCODE_BUTTON_L1, false, 1, layout)

        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertTrue(c.isCameraLocked)
        assertFalse(sink.events.filterIsInstance<SinkEvent.Key>()
            .any { it.logicalKeyCode == KeyEvent.KEYCODE_F12 })
        assertFalse(c.setCameraLock(false, 1))
        assertEquals(listOf(false, true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        assertTrue(c.isNeutral(1))
    }

    @Test fun `console port pending Select lifecycle release is neutral`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
            "16:9",
            1,
        )
        c.gamepadButton(
            76, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        )

        val report = c.releaseSource(76)

        assertEquals(0, report.keyCount)
        assertEquals(0, report.buttonCount)
        assertTrue(sink.events.isEmpty())
        assertTrue(c.isNeutral(1))
    }

    @Test fun `custom non product Select binding does not enable utility chords`() {
        val (c, sink) = newContract()
        c.switchProfile(
            InputProfile.DEFAULT.copy(
                scheme = ControlScheme.CUSTOM,
                rp6Bindings = InputProfile.defaultRp6Bindings() +
                    (Rp6Control.SELECT to ControllerAction.KEY_P),
            ),
            "16:9",
            1,
        )
        val layout = InputContract.GamepadLayout.PROFILED

        c.gamepadButton(77, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(77, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
        c.gamepadButton(77, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
        c.gamepadButton(77, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)

        assertFalse(c.isCameraLocked)
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_P to true,
                KeyEvent.KEYCODE_SPACE to true,
                KeyEvent.KEYCODE_SPACE to false,
                KeyEvent.KEYCODE_P to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `external Select R3 toggles camera once and consumes Map`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED

        c.gamepadButton(32, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(32, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
        c.gamepadButton(32, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadButton(32, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)

        assertTrue(c.isCameraLocked)
        assertTrue("consumed Select must not open Map", sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())
        assertEquals(
            listOf(SinkEvent.PointerButton(SinkButton.RIGHT, true)),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertEquals(1, c.releaseSource(32).buttonCount)
        assertEquals(listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
    }

    @Test fun `external Select R2 holds left click until R2 release after Select release`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED

        c.gamepadButton(33, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(33, KeyEvent.KEYCODE_BUTTON_R2, true, 1, layout)
        c.gamepadButton(33, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        assertEquals(listOf(true),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        assertTrue(sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())

        c.gamepadButton(33, KeyEvent.KEYCODE_BUTTON_R2, false, 1, layout)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.LEFT, true),
                SinkEvent.PointerButton(SinkButton.LEFT, false),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `external Select R2 chord works for analogue trigger reports`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED

        c.gamepadButton(34, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadAxis(34, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, layout)
        c.gamepadButton(34, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadAxis(34, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.19f, 1, layout)

        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.LEFT, true),
                SinkEvent.PointerButton(SinkButton.LEFT, false),
            ),
            sink.events,
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `external action already down keeps original meaning and cannot become a chord`() {
        val layout = InputContract.GamepadLayout.PROFILED
        run {
            val (c, sink) = newContract()
            c.gamepadButton(35, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
            c.gamepadButton(35, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(35, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout) // repeat
            c.gamepadButton(35, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(35, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
            assertEquals(
                listOf(
                    KeyEvent.KEYCODE_SPACE to true,
                    KeyEvent.KEYCODE_M to true,
                    KeyEvent.KEYCODE_M to false,
                    KeyEvent.KEYCODE_SPACE to false,
                ),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
            )
            assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
        }
        run {
            val (c, sink) = newContract()
            c.gamepadButton(36, KeyEvent.KEYCODE_BUTTON_R2, true, 1, layout)
            c.gamepadButton(36, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(36, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(36, KeyEvent.KEYCODE_BUTTON_R2, false, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.RIGHT, true),
                    SinkEvent.PointerButton(SinkButton.RIGHT, false),
                ),
                sink.events.filterIsInstance<SinkEvent.PointerButton>(),
            )
            assertEquals(listOf(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_M),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode })
        }
    }

    @Test fun `external Select chord state is isolated per controller source`() {
        val (c, sink) = newContract()
        val layout = InputContract.GamepadLayout.PROFILED

        c.gamepadButton(41, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(42, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
        c.gamepadButton(41, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadButton(42, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SPACE to true,
                KeyEvent.KEYCODE_M to true,
                KeyEvent.KEYCODE_M to false,
                KeyEvent.KEYCODE_SPACE to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertFalse(c.isCameraLocked)
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
    }

    @Test fun `external pending chord state clears neutrally across every lifecycle release path`() {
        fun assertExit(name: String, exit: (InputContract) -> Unit) {
            val (c, sink) = newContract()
            val layout = InputContract.GamepadLayout.PROFILED
            c.gamepadButton(50, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(50, KeyEvent.KEYCODE_BUTTON_R2, true, 1, layout)

            exit(c)

            assertEquals("$name must release chord-owned left exactly once", listOf(true, false),
                sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
            assertTrue("$name must not turn consumed Select into Map",
                sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())
        }

        assertExit("hot unplug") { it.releaseSource(50, InputContract.ReleaseReason.DEVICE_REMOVED) }
        assertExit("focus loss") { it.releaseAll(InputContract.ReleaseReason.FOCUS_LOSS) }
        assertExit("IME open") { it.imeOpened(1) }
        assertExit("profile switch") { it.switchProfile(InputProfile.DEFAULT, "16:9", 1) }
        assertExit("generation switch") { it.attach(null, 2, InputProfile.DEFAULT, "16:9") }
    }

    @Test fun `RP6 Select uses safe utility chords without rear buttons`() {
        for (layout in listOf(
            InputContract.GamepadLayout.RETROID_POCKET_6,
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX,
        )) {
            val (c, sink) = newContract()
            c.gamepadButton(60, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            assertTrue(sink.events.isEmpty())
            c.gamepadButton(60, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1, layout)
            c.gamepadButton(60, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadButton(60, KeyEvent.KEYCODE_BUTTON_THUMBR, false, 1, layout)
            assertTrue(c.isCameraLocked)
            assertTrue(sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())
            assertEquals(listOf(true),
                sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
            c.setCameraLock(false, 1)
            assertEquals(listOf(true, false),
                sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        }
    }

    @Test fun `RP6 Select tap emits Map and Select analogue R2 emits left click`() {
        for (layout in listOf(
            InputContract.GamepadLayout.RETROID_POCKET_6,
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX,
        )) {
            val (c, sink) = newContract()
            c.gamepadButton(62, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(62, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            assertEquals(
                listOf(KeyEvent.KEYCODE_M to true, KeyEvent.KEYCODE_M to false),
                sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
            )
            sink.events.clear()
            c.gamepadButton(62, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadAxis(62, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, layout)
            c.gamepadButton(62, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            c.gamepadAxis(62, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.19f, 1, layout)
            assertEquals(
                listOf(
                    SinkEvent.PointerButton(SinkButton.LEFT, true),
                    SinkEvent.PointerButton(SinkButton.LEFT, false),
                ),
                sink.events,
            )
            assertTrue(c.isNeutral(1))
        }
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

    @Test fun `camera toggle selects locked look or unlocked cursor mode`() {
        val (c, sink) = newContract()
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )
        c.pumpGamepadPointer(1_000_000_000L, 1)
        c.pumpGamepadPointer(1_016_666_667L, 1)
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())

        assertTrue(c.toggleCameraLock(1, source = 42))
        assertFalse(c.toggleCameraLock(1, source = 42))
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )

        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )
        c.pumpGamepadPointer(1_033_333_334L, 1)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `unlocked physical mouse click never requests pointer capture`() {
        assertFalse(ClientInputBridge.shouldRequestPointerCapture(
            cameraLocked = false, physicalMouse = true, alreadyCaptured = false, sdkInt = 35,
        ))
        assertTrue(ClientInputBridge.shouldRequestPointerCapture(
            cameraLocked = true, physicalMouse = true, alreadyCaptured = false, sdkInt = 35,
        ))
        assertFalse(ClientInputBridge.shouldRequestPointerCapture(
            cameraLocked = true, physicalMouse = true, alreadyCaptured = true, sdkInt = 35,
        ))
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

    @Test fun `right-stick source release has no camera button edge while unlocked`() {
        val (c, sink) = newContract()
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )
        c.pumpGamepadPointer(1_000_000_000L, 1)

        val report = c.releaseSource(8)

        assertEquals(listOf(8), report.sources)
        assertEquals(0, report.buttonCount)
        assertTrue(sink.events.filterIsInstance<SinkEvent.PointerButton>().isEmpty())
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

    @Test fun `camera-lock retirement frees mouse even when another stick is displaced`() {
        val (c, sink) = newContract()
        assertTrue(c.toggleCameraLock(1, source = 42))
        c.gamepadAxis(
            8, InputContract.GamepadAxis.RIGHT_X, 1f, 1,
            InputContract.GamepadLayout.PROFILED,
        )

        val lockRelease = c.releaseSource(42)

        assertEquals(1, lockRelease.buttonCount)
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )

        val stickRelease = c.releaseSource(8)
        assertEquals(0, stickRelease.buttonCount)
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

        assertTrue(sink.events.filterIsInstance<SinkEvent.Key>().isEmpty())
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
            ),
            sink.events.filterIsInstance<SinkEvent.PointerButton>(),
        )
        assertEquals(0, release.keyCount)
        assertEquals(0, release.buttonCount)
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

    @Test fun `built-in profile emits stock target action and right-click loot`() {
        val sink = RecordingSink()
        val c = InputContract(sink)
        val profile = InputProfile.profileForScheme(
            ControlScheme.CLASSIC_CAMERA,
            InputProfile.DEFAULT_ASPECT_IDENTITY,
        ).copy(controllerFamily = ControllerFamily.RETROID_POCKET_6)
        c.attach(null, 1, profile, InputProfile.DEFAULT_ASPECT_IDENTITY)
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_R1, true, 1, rp6))
        assertTrue(c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_R2, true, 1, rp6))
        assertEquals(
            listOf(KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F6),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed })
        assertFalse(c.isCameraLocked)
    }

    @Test fun `schema ten repairs exact persisted addon layout and preserves a genuine custom map`() {
        fun legacyJson(r1: String = ControllerAction.KEY_9.name): org.json.JSONObject {
            val bindings = org.json.JSONObject()
            val legacy = linkedMapOf(
                Rp6Control.LEFT_STICK_UP to ControllerAction.MOVE_W.name,
                Rp6Control.LEFT_STICK_DOWN to ControllerAction.MOVE_S.name,
                Rp6Control.LEFT_STICK_LEFT to ControllerAction.STRAFE_Q.name,
                Rp6Control.LEFT_STICK_RIGHT to ControllerAction.STRAFE_E.name,
                Rp6Control.FACE_BOTTOM to ControllerAction.KEY_1.name,
                Rp6Control.FACE_LEFT to ControllerAction.KEY_2.name,
                Rp6Control.FACE_TOP to ControllerAction.KEY_3.name,
                Rp6Control.FACE_RIGHT to ControllerAction.KEY_4.name,
                Rp6Control.DPAD_DOWN to ControllerAction.KEY_5.name,
                Rp6Control.DPAD_LEFT to ControllerAction.KEY_6.name,
                Rp6Control.DPAD_UP to ControllerAction.KEY_7.name,
                Rp6Control.DPAD_RIGHT to ControllerAction.KEY_8.name,
                Rp6Control.R1 to r1,
                Rp6Control.L1 to ControllerAction.KEY_0.name,
                Rp6Control.L2 to ControllerAction.SHIFT.name,
                Rp6Control.R2 to ControllerAction.CTRL.name,
                Rp6Control.START to ControllerAction.ESCAPE.name,
                Rp6Control.SELECT to ControllerAction.MAP.name,
                Rp6Control.L3 to ControllerAction.AUTO_RUN.name,
                Rp6Control.R3 to ControllerAction.POINTER_LEFT.name,
                Rp6Control.REAR_LEFT to ControllerAction.INTERACT.name,
                Rp6Control.REAR_RIGHT to ControllerAction.POINTER_RIGHT.name,
            )
            legacy.forEach { (control, action) -> bindings.put(control.name, action) }
            return org.json.JSONObject()
                .put("version", 9)
                .put("aspectIdentity", "16:9")
                .put("scheme", ControlScheme.CUSTOM.name)
                .put("controllerFamily", ControllerFamily.RETROID_POCKET_6.name)
                .put("faceButtonLayout", FaceButtonLayout.RP6_PRINTED.name)
                .put("rp6Bindings", bindings)
        }

        val repaired = InputProfile.fromJson(legacyJson())
        assertEquals(InputProfile.CURRENT_VERSION, repaired.version)
        assertEquals(ControlScheme.CLASSIC_CAMERA, repaired.scheme)
        assertEquals(ControllerFamily.AUTO, repaired.controllerFamily)
        assertEquals(FaceButtonLayout.ANDROID_STANDARD, repaired.faceButtonLayout)
        assertEquals(ControllerAction.USE_LOOT_CLICK, InputProfile.actionFor(repaired, Rp6Control.R2))
        assertEquals(ControllerAction.AUTO_RUN, InputProfile.actionFor(repaired, Rp6Control.L3))
        assertEquals(ControllerAction.JUMP, InputProfile.actionFor(repaired, Rp6Control.R3))
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(repaired, Rp6Control.REAR_LEFT))
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(repaired, Rp6Control.REAR_RIGHT))

        val custom = InputProfile.fromJson(legacyJson(r1 = ControllerAction.JUMP.name))
        assertEquals(ControlScheme.CUSTOM, custom.scheme)
        assertEquals(ControllerFamily.RETROID_POCKET_6, custom.controllerFamily)
        assertEquals(FaceButtonLayout.RP6_PRINTED, custom.faceButtonLayout)
        assertEquals(ControllerAction.JUMP, InputProfile.actionFor(custom, Rp6Control.R1))
        assertEquals(ControllerAction.CTRL, InputProfile.actionFor(custom, Rp6Control.R2))
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
                KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_G),
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
        assertTrue(ClientInputBridge.isRetroidPocketController(
            "Xbox Wireless Controller", "c575e892a6bb353df4b1327e81beedf84b540eb4",
            0x2022, 0x3001,
        ))
        val xboxModeIsRetroid = ClientInputBridge.isRetroidPocketController(
            "Xbox Wireless Controller", "ee6d26f8ce1cc60310155713f3660225d7d89557",
            0x2022, 0x3002,
        )
        assertTrue(xboxModeIsRetroid)
        assertEquals(
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX,
            ClientInputBridge.gamepadLayoutFor(ControllerFamily.AUTO, ControllerDeviceMode.RP6_XBOX),
        )
        assertEquals(
            Rp6Control.FACE_BOTTOM,
            InputContract.faceControlForKeyCode(
                KeyEvent.KEYCODE_BUTTON_A,
                FaceButtonLayout.ANDROID_STANDARD,
            ),
        )
        assertFalse(ClientInputBridge.isRetroidPocketController(
            "Generic Controller", "unrelated", 0x045e, 0x02fd,
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

    @Test fun `v11 target is one balanced pulse per physical press`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.gamepadButton(61, KeyEvent.KEYCODE_BUTTON_R1, true, 1, rp6))
        assertTrue(c.gamepadButton(61, KeyEvent.KEYCODE_BUTTON_R1, true, 1, rp6))
        assertTrue(c.gamepadButton(61, KeyEvent.KEYCODE_BUTTON_R1, false, 1, rp6))
        assertEquals(
            listOf(true, false),
            sink.events.filterIsInstance<SinkEvent.Key>()
                .filter { it.logicalKeyCode == KeyEvent.KEYCODE_F6 }
                .map { it.pressed },
        )
        val before = sink.events.size
        assertFalse(c.gamepadButton(61, KeyEvent.KEYCODE_BUTTON_R1, true, 999, rp6))
        assertEquals(before, sink.events.size)
    }

    @Test fun `v11 locked loot click uses one restore press and source release emits final up`() {
        val (c, sink) = newContract()
        assertTrue(c.setCameraAimPoint(640, 403, 1))
        assertTrue(c.setCameraLock(true, 1, source = 70))
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.gamepadButton(70, KeyEvent.KEYCODE_BUTTON_R2, true, 1, rp6))
        assertTrue(c.gamepadButton(70, KeyEvent.KEYCODE_BUTTON_R2, false, 1, rp6))
        assertEquals(
            listOf(
                SinkEvent.PointerMove(640, 403),
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
            ),
            sink.events,
        )
        assertTrue(c.isCameraLocked)
        assertEquals(1, c.releaseSource(70).buttonCount)
        assertFalse(c.isCameraLocked)
        assertEquals(
            listOf(true, false, true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
    }

    @Test fun `v11 unlocked loot click is one ordinary balanced secondary click`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6

        assertTrue(c.gamepadButton(71, KeyEvent.KEYCODE_BUTTON_R2, true, 1, rp6))
        assertTrue(c.gamepadButton(71, KeyEvent.KEYCODE_BUTTON_R2, false, 1, rp6))

        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.RIGHT, true),
                SinkEvent.PointerButton(SinkButton.RIGHT, false),
            ),
            sink.events,
        )
        assertTrue(c.isNeutral(1))
        assertEquals(0, c.releaseSource(71).buttonCount)
        assertEquals(2, sink.events.size)
    }

    @Test fun `v11 locked analogue R2 samples pulse once and explicit unlock emits final up`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        assertTrue(c.setCameraLock(true, 1, source = 72))

        // Android can report the resting value and then repeat several
        // historical high trigger samples. Only the threshold crossing pulses.
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0f, 1, rp6)
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, rp6)
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.76f, 1, rp6)
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.41f, 1, rp6)
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0.19f, 1, rp6)
        c.gamepadAxis(72, InputContract.GamepadAxis.RIGHT_TRIGGER, 0f, 1, rp6)

        assertEquals(
            listOf(true, false, true),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
        assertTrue(c.isCameraLocked)
        assertFalse(c.setCameraLock(false, 1, source = 72))
        assertEquals(
            listOf(true, false, true, false),
            sink.events.filterIsInstance<SinkEvent.PointerButton>().map { it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `v11 face layers expose actions one through twelve and retain down binding until up`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6_XBOX
        val faces = listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_B,
        )
        fun tap(key: Int) {
            assertTrue(c.gamepadButton(81, key, true, 1, rp6))
            assertTrue(c.gamepadButton(81, key, false, 1, rp6))
        }
        faces.forEach(::tap)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L2, true, 1, rp6)
        faces.forEach(::tap)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L1, true, 1, rp6)
        faces.forEach(::tap) // L1 deterministically wins over L2.
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L1, false, 1, rp6)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L2, false, 1, rp6)
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
            ).flatMap { listOf(it, it) },
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )

        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L2, true, 1, rp6)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_A, true, 1, rp6)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_L2, false, 1, rp6)
        c.gamepadButton(81, KeyEvent.KEYCODE_BUTTON_A, false, 1, rp6)
        assertEquals(
            listOf(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5),
            sink.events.filterIsInstance<SinkEvent.Key>().takeLast(2).map { it.logicalKeyCode },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `v11 overlay mode and action twelve persist while v10 enabled migrates to auto`() {
        val profile = InputProfile.DEFAULT.copy(overlayMode = OverlayMode.FULL)
        assertEquals(profile, InputProfile.fromJson(InputProfile.toJson(profile)))
        val migrated = InputProfile.fromJson(org.json.JSONObject()
            .put("version", 10)
            .put("aspectIdentity", "16:9")
            .put("overlayEnabled", false))
        assertEquals(OverlayMode.OFF, migrated.overlayMode)
        assertEquals(ControllerAction.KEY_EQUALS,
            InputProfile.actionFor(migrated, OverlayControl.ACTION_12))
    }

    @Test fun `RP6 topology classifies retro xbox and unrelated controllers without name guessing`() {
        val sources = android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK
        assertEquals(ControllerDeviceMode.RP6_RETRO,
            ClientInputBridge.controllerDeviceMode("anything", null, 0x2022, 0x3001, sources))
        assertEquals(ControllerDeviceMode.RP6_XBOX,
            ClientInputBridge.controllerDeviceMode("Xbox Wireless Controller",
                "ee6d26f8ce1cc60310155713f3660225d7d89557", 0x2022, 0x3002, sources))
        assertEquals(ControllerDeviceMode.OTHER_CONTROLLER,
            ClientInputBridge.controllerDeviceMode("Xbox Wireless Controller", "other", 1, 2, sources))
        assertEquals(ControllerDeviceMode.NONE,
            ClientInputBridge.controllerDeviceMode("keyboard", "kbd", 1, 2,
                android.view.InputDevice.SOURCE_KEYBOARD))
        assertTrue(ClientInputBridge.shouldSuppressUnexpectedRp6Key(
            ControllerDeviceMode.RP6_XBOX, false, ControllerFamily.AUTO))
        assertFalse(ClientInputBridge.shouldSuppressUnexpectedRp6Key(
            ControllerDeviceMode.RP6_XBOX, false, ControllerFamily.KEYBOARD_MOUSE))
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
                (Rp6Control.R1 to ControllerAction.MAP),
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
            listOf(KeyEvent.KEYCODE_M),
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
        assertFalse(c.gamepadButton(
            9, KeyEvent.KEYCODE_BUTTON_THUMBR, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6,
        ))
        assertFalse(c.gamepadButton(
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

    @Test fun `HAT directions use semantic bindings and pulse once until neutral`() {
        val (c, sink) = newContract()
        val profile = InputProfile.DEFAULT.copy(
            rp6Bindings = InputProfile.defaultRp6Bindings() + mapOf(
                Rp6Control.DPAD_UP to ControllerAction.TARGET_PULSE,
                Rp6Control.DPAD_DOWN to ControllerAction.USE_LOOT_CLICK,
                Rp6Control.DPAD_LEFT to ControllerAction.CAMERA_LOCK,
                Rp6Control.DPAD_RIGHT to ControllerAction.WHEEL_DOWN,
            ),
        )
        c.switchProfile(profile, "16:9", 1)
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_Y, -1f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_Y, -1f, 1, layout)
        assertEquals(listOf(true, false), sink.events.filterIsInstance<SinkEvent.Key>().map { it.pressed })
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_Y, 0f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_Y, 1f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_Y, 0f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_X, -1f, 1, layout)
        assertTrue(c.isCameraLocked)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_X, 0f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_X, 1f, 1, layout)
        c.gamepadAxis(91, InputContract.GamepadAxis.HAT_X, 0f, 1, layout)
        assertTrue(c.isCameraLocked)
        assertTrue(sink.events.contains(SinkEvent.PointerButton(SinkButton.SCROLL_DOWN, true)))
        c.setCameraLock(false, 1)
        assertTrue(c.isNeutral(1))
    }

    @Test fun `AUTO face convention follows live RP6 mode and explicit family honors override`() {
        fun emitted(layout: InputContract.GamepadLayout, profile: InputProfile): Int {
            val sink = RecordingSink()
            val c = InputContract(sink)
            c.attach(null, 1, profile, "16:9")
            c.gamepadButton(1, KeyEvent.KEYCODE_BUTTON_A, true, 1, layout)
            return sink.events.filterIsInstance<SinkEvent.Key>().single().logicalKeyCode
        }
        val staleAuto = InputProfile.DEFAULT.copy(
            controllerFamily = ControllerFamily.AUTO,
            faceButtonLayout = FaceButtonLayout.RP6_PRINTED,
        )
        assertEquals(KeyEvent.KEYCODE_4,
            emitted(InputContract.GamepadLayout.RETROID_POCKET_6, staleAuto))
        assertEquals(KeyEvent.KEYCODE_1,
            emitted(InputContract.GamepadLayout.RETROID_POCKET_6_XBOX, staleAuto))
        assertEquals(KeyEvent.KEYCODE_1,
            emitted(InputContract.GamepadLayout.PROFILED, staleAuto))
        val explicit = staleAuto.copy(
            controllerFamily = ControllerFamily.RETROID_POCKET_6,
            faceButtonLayout = FaceButtonLayout.ANDROID_STANDARD,
        )
        assertEquals(KeyEvent.KEYCODE_1,
            emitted(InputContract.GamepadLayout.RETROID_POCKET_6, explicit))
    }

    @Test fun `mode transition releases old face output before applying new convention`() {
        val (c, sink) = newContract()
        c.gamepadButton(1, KeyEvent.KEYCODE_BUTTON_A, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6)
        c.releaseSource(1)
        c.gamepadButton(2, KeyEvent.KEYCODE_BUTTON_A, true, 1,
            InputContract.GamepadLayout.RETROID_POCKET_6_XBOX)
        assertEquals(
            listOf(KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_1),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode },
        )
    }

    @Test fun `classic scheme resets layer maps while custom preserves them`() {
        val customLayers = InputProfile.defaultLayerFaceBindings().mapValues { (_, value) -> value.toMutableMap() }
            .toMutableMap().also {
                it.getValue(FaceLayer.L2)[Rp6Control.FACE_BOTTOM] = ControllerAction.KEY_Z
            }
        val base = InputProfile.DEFAULT.copy(layerFaceBindings = customLayers)
        assertEquals(ControllerAction.KEY_5, InputProfile.actionFor(
            InputProfile.profileForScheme(ControlScheme.CLASSIC_CAMERA, "16:9", base),
            FaceLayer.L2, Rp6Control.FACE_BOTTOM,
        ))
        assertEquals(ControllerAction.KEY_Z, InputProfile.actionFor(
            InputProfile.profileForScheme(ControlScheme.CUSTOM, "16:9", base),
            FaceLayer.L2, Rp6Control.FACE_BOTTOM,
        ))
    }

    @Test fun `vanilla console port preset matches ConsoleExperience keyboard contract`() {
        val profile = InputProfile.profileForScheme(
            ControlScheme.ANDROID_PORT,
            "16:9",
        )
        val expected = linkedMapOf(
            Rp6Control.FACE_BOTTOM to ControllerAction.KEY_1,
            Rp6Control.FACE_LEFT to ControllerAction.KEY_2,
            Rp6Control.FACE_TOP to ControllerAction.KEY_3,
            Rp6Control.FACE_RIGHT to ControllerAction.KEY_4,
            Rp6Control.DPAD_DOWN to ControllerAction.KEY_5,
            Rp6Control.DPAD_LEFT to ControllerAction.KEY_6,
            Rp6Control.DPAD_UP to ControllerAction.KEY_7,
            Rp6Control.DPAD_RIGHT to ControllerAction.KEY_8,
            Rp6Control.R1 to ControllerAction.TARGET_PULSE,
            Rp6Control.L1 to ControllerAction.NEARBY_USE,
            Rp6Control.L2 to ControllerAction.SHIFT,
            Rp6Control.R2 to ControllerAction.CTRL,
            Rp6Control.START to ControllerAction.ESCAPE,
            Rp6Control.SELECT to ControllerAction.CONSOLE_RADIAL,
            Rp6Control.L3 to ControllerAction.POINTER_RIGHT,
            Rp6Control.REAR_LEFT to ControllerAction.DISABLED,
            Rp6Control.REAR_RIGHT to ControllerAction.DISABLED,
        )
        expected.forEach { (control, action) ->
            assertEquals(control.displayName, action, InputProfile.actionFor(profile, control))
        }
        assertEquals(ControlScheme.ANDROID_PORT, profile.scheme)
        assertEquals(
            ControlScheme.ANDROID_PORT,
            InputProfile.fromJson(InputProfile.toJson(profile)).scheme,
        )
    }

    @Test fun `exact old console preset migrates to direct shoulders while customization is preserved`() {
        fun decode(
            r1: ControllerAction,
            l1: ControllerAction = ControllerAction.KEY_0,
        ): InputProfile {
            val stored = org.json.JSONObject()
            linkedMapOf(
                Rp6Control.LEFT_STICK_UP to ControllerAction.MOVE_W,
                Rp6Control.LEFT_STICK_DOWN to ControllerAction.MOVE_S,
                Rp6Control.LEFT_STICK_LEFT to ControllerAction.STRAFE_Q,
                Rp6Control.LEFT_STICK_RIGHT to ControllerAction.STRAFE_E,
                Rp6Control.FACE_BOTTOM to ControllerAction.KEY_1,
                Rp6Control.FACE_LEFT to ControllerAction.KEY_2,
                Rp6Control.FACE_TOP to ControllerAction.KEY_3,
                Rp6Control.FACE_RIGHT to ControllerAction.KEY_4,
                Rp6Control.DPAD_DOWN to ControllerAction.KEY_5,
                Rp6Control.DPAD_LEFT to ControllerAction.KEY_6,
                Rp6Control.DPAD_UP to ControllerAction.KEY_7,
                Rp6Control.DPAD_RIGHT to ControllerAction.KEY_8,
                Rp6Control.R1 to r1,
                Rp6Control.L1 to l1,
                Rp6Control.L2 to ControllerAction.SHIFT,
                Rp6Control.R2 to ControllerAction.CTRL,
                Rp6Control.START to ControllerAction.ESCAPE,
                Rp6Control.SELECT to ControllerAction.CONSOLE_RADIAL,
                Rp6Control.L3 to ControllerAction.AUTO_RUN,
                Rp6Control.R3 to ControllerAction.POINTER_LEFT,
                Rp6Control.REAR_LEFT to ControllerAction.DISABLED,
                Rp6Control.REAR_RIGHT to ControllerAction.DISABLED,
            ).forEach { (control, action) -> stored.put(control.name, action.name) }
            return InputProfile.fromJson(org.json.JSONObject()
                .put("version", 11)
                .put("aspectIdentity", "16:9")
                .put("scheme", ControlScheme.ANDROID_PORT.name)
                .put("rp6Bindings", stored))
        }

        val migrated = decode(ControllerAction.KEY_9)
        assertEquals(ControllerAction.TARGET_PULSE,
            InputProfile.actionFor(migrated, Rp6Control.R1))
        assertEquals(ControllerAction.NEARBY_USE,
            InputProfile.actionFor(migrated, Rp6Control.L1))

        val migratedDirect = decode(ControllerAction.TARGET_PULSE, ControllerAction.USE_LOOT_CLICK)
        assertEquals(ControllerAction.NEARBY_USE,
            InputProfile.actionFor(migratedDirect, Rp6Control.L1))

        val migratedNearbyUse = decode(ControllerAction.TARGET_PULSE, ControllerAction.NEARBY_USE)
        assertEquals(ControllerAction.NEARBY_USE,
            InputProfile.actionFor(migratedNearbyUse, Rp6Control.L1))
        assertEquals(ControllerAction.POINTER_RIGHT,
            InputProfile.actionFor(migratedNearbyUse, Rp6Control.L3))

        val customized = decode(ControllerAction.KEY_Z)
        assertEquals(ControllerAction.KEY_Z,
            InputProfile.actionFor(customized, Rp6Control.R1))
        assertEquals(ControllerAction.KEY_0,
            InputProfile.actionFor(customized, Rp6Control.L1))
    }

    @Test fun `vanilla console radial action uses one ownership tracked F12 edge`() {
        val (c, sink) = newContract()
        assertTrue(c.virtualAction(4, ControllerAction.CONSOLE_RADIAL, true, 1))
        assertTrue(c.virtualAction(4, ControllerAction.CONSOLE_RADIAL, true, 1))
        assertTrue(c.virtualAction(4, ControllerAction.CONSOLE_RADIAL, false, 1))
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_F12 to true,
                KeyEvent.KEYCODE_F12 to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>()
                .map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `vanilla console radial does not release an F12 key owned elsewhere`() {
        val (c, sink) = newContract()
        assertTrue(c.virtualAction(7, ControllerAction.F12, true, 1))
        assertTrue(c.virtualAction(4, ControllerAction.CONSOLE_RADIAL, true, 1))
        assertTrue(c.virtualAction(4, ControllerAction.CONSOLE_RADIAL, false, 1))
        assertTrue(c.virtualAction(7, ControllerAction.F12, false, 1))
        assertEquals(
            listOf(KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `move ui action fires one F8 edge from the touch overlay`() {
        val (c, sink) = newContract()
        assertTrue(c.virtualAction(4, ControllerAction.MOVE_UI, true, 1))
        assertTrue(c.virtualAction(4, ControllerAction.MOVE_UI, false, 1))
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_F8 to true,
                KeyEvent.KEYCODE_F8 to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>()
                .map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `controller actions cover qualified runtime keys exactly once except unusable TAB`() {
        assertEquals(25, ControllerAction.TARGET.ordinal)
        assertEquals(33, ControllerAction.CAMERA_LOCK.ordinal)
        assertTrue(ControllerAction.TARGET_PULSE.ordinal > ControllerAction.CAMERA_LOCK.ordinal)
        val expected = buildSet {
            addAll(listOf(
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_LEFT_BRACKET,
                KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_GRAVE,
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_BACKSLASH,
                KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_NUMPAD_ADD,
                KeyEvent.KEYCODE_NUMPAD_DOT,
            ))
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
            addAll(KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)
            addAll(KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
        }
        val outputs = ControllerAction.values().mapNotNull { it.keyCode }
        assertEquals(outputs.toSet().size, outputs.size)
        assertEquals(expected, outputs.toSet())
        assertEquals(ControllerAction.TARGET,
            ControllerAction.values().single { it.keyCode == KeyEvent.KEYCODE_F6 })
        // MOVE_UI carries no direct key; it must stay appended after the
        // pulse actions so ordinal-derived touch source ids stay stable.
        assertTrue(ControllerAction.MOVE_UI.ordinal > ControllerAction.NEARBY_USE.ordinal)
        assertEquals(ControllerAction.MOVE_UI, ControllerAction.values().last())
    }

    @Test fun `middle mouse and wheel actions are balanced discrete or held outputs`() {
        val (c, sink) = newContract()
        c.virtualAction(1, ControllerAction.POINTER_MIDDLE, true, 1)
        c.virtualAction(1, ControllerAction.POINTER_MIDDLE, false, 1)
        c.virtualAction(2, ControllerAction.WHEEL_UP, true, 1)
        c.virtualAction(2, ControllerAction.WHEEL_UP, true, 1)
        c.virtualAction(2, ControllerAction.WHEEL_UP, false, 1)
        assertEquals(
            listOf(
                SinkEvent.PointerButton(SinkButton.MIDDLE, true),
                SinkEvent.PointerButton(SinkButton.MIDDLE, false),
                SinkEvent.PointerButton(SinkButton.SCROLL_UP, true),
                SinkEvent.PointerButton(SinkButton.SCROLL_UP, false),
            ),
            sink.events,
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `keyboard mouse family consumes controller devices while external keyboard remains eligible`() {
        assertTrue(ClientInputBridge.shouldIgnoreControllerDevice(
            ControllerDeviceMode.RP6_XBOX, ControllerFamily.KEYBOARD_MOUSE))
        assertTrue(ClientInputBridge.shouldIgnoreControllerDevice(
            ControllerDeviceMode.OTHER_CONTROLLER, ControllerFamily.KEYBOARD_MOUSE))
        assertFalse(ClientInputBridge.shouldIgnoreControllerDevice(
            ControllerDeviceMode.NONE, ControllerFamily.KEYBOARD_MOUSE))
        assertFalse(ClientInputBridge.shouldSuppressUnexpectedRp6Key(
            ControllerDeviceMode.RP6_XBOX, false, ControllerFamily.KEYBOARD_MOUSE))
    }

    @Test fun `exact early v11 default migrates while one customized D-pad binding is preserved`() {
        fun decode(up: ControllerAction): InputProfile {
            val stored = org.json.JSONObject()
            val early = InputProfile.defaultRp6Bindings().toMutableMap().apply {
                this[Rp6Control.DPAD_DOWN] = ControllerAction.KEY_5
                this[Rp6Control.DPAD_LEFT] = ControllerAction.KEY_6
                this[Rp6Control.DPAD_UP] = up
                this[Rp6Control.DPAD_RIGHT] = ControllerAction.KEY_8
                this[Rp6Control.REAR_LEFT] = ControllerAction.CAMERA_LOCK
                this[Rp6Control.REAR_RIGHT] = ControllerAction.POINTER_LEFT
            }
            early.forEach { (control, action) -> stored.put(control.name, action.name) }
            return InputProfile.fromJson(org.json.JSONObject()
                .put("version", 11)
                .put("aspectIdentity", "16:9")
                .put("scheme", ControlScheme.CLASSIC_CAMERA.name)
                .put("rp6Bindings", stored))
        }
        val migrated = decode(ControllerAction.KEY_7)
        assertEquals(ControllerAction.KEY_G, InputProfile.actionFor(migrated, Rp6Control.DPAD_UP))
        assertEquals(ControllerAction.F1, InputProfile.actionFor(migrated, Rp6Control.DPAD_DOWN))
        val custom = decode(ControllerAction.KEY_Z)
        assertEquals(ControllerAction.KEY_Z, InputProfile.actionFor(custom, Rp6Control.DPAD_UP))
        assertEquals(ControllerAction.KEY_5, InputProfile.actionFor(custom, Rp6Control.DPAD_DOWN))
    }

    @Test fun `Select held before Start keeps immediate Move UI chord semantics`() {
        val (c, sink, scheduler) = scheduledConsoleContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(201, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(201, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(201, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)
        c.gamepadButton(201, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        assertTrue(scheduler.delays.isEmpty())
        assertEquals(
            listOf(KeyEvent.KEYCODE_F8 to true, KeyEvent.KEYCODE_F8 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `Select release then Start inside grace consumes radial and Escape`() {
        val (c, sink, scheduler) = scheduledConsoleContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(202, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(202, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        assertEquals(listOf(InputContract.EXTERNAL_SELECT_CHORD_GRACE_MS), scheduler.delays)
        assertTrue(sink.events.isEmpty())
        c.gamepadButton(202, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(202, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)
        scheduler.drain() // canceled Select callback must be inert.
        assertEquals(
            listOf(KeyEvent.KEYCODE_F8 to true, KeyEvent.KEYCODE_F8 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `Select grace expiry emits radial once and next Start is default Escape`() {
        val (c, sink, scheduler) = scheduledConsoleContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(203, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(203, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        scheduler.runNext()
        c.gamepadButton(203, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(203, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false,
                KeyEvent.KEYCODE_ESCAPE to true, KeyEvent.KEYCODE_ESCAPE to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `pending Select callback is canceled by every lifecycle boundary`() {
        val layout = InputContract.GamepadLayout.PROFILED
        val cases = listOf<Pair<String, (InputContract) -> Unit>>(
            "release all" to { it.releaseAll(InputContract.ReleaseReason.ON_PAUSE) },
            "device removal" to { it.releaseSource(204) },
            "generation replacement" to { it.attach(null, 2) },
            "profile switch" to {
                it.switchProfile(InputProfile.DEFAULT, "16:9", 1)
            },
            "IME open" to { it.imeOpened(1) },
        )
        cases.forEach { (name, boundary) ->
            val (c, sink, scheduler) = scheduledConsoleContract()
            c.gamepadButton(204, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
            c.gamepadButton(204, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
            boundary(c)
            scheduler.drain()
            assertTrue("$name emitted a stale Select tap", sink.events.isEmpty())
        }
    }

    @Test fun `pending Select grace is isolated by controller source`() {
        val (c, sink, scheduler) = scheduledConsoleContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(205, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(205, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        // Another controller cannot consume source 205's pending modifier.
        c.gamepadButton(206, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(206, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)
        c.gamepadButton(205, KeyEvent.KEYCODE_BUTTON_START, true, 1, layout)
        c.gamepadButton(205, KeyEvent.KEYCODE_BUTTON_START, false, 1, layout)
        scheduler.drain()
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_ESCAPE to true, KeyEvent.KEYCODE_ESCAPE to false,
                KeyEvent.KEYCODE_F8 to true, KeyEvent.KEYCODE_F8 to false,
            ),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `Select repeat and re-press replace pending tap without duplicate or stuck owner`() {
        val (c, sink, scheduler) = scheduledConsoleContract()
        val layout = InputContract.GamepadLayout.PROFILED
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout)
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout) // repeat
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout) // cancels first
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, true, 1, layout) // repeat
        c.gamepadButton(207, KeyEvent.KEYCODE_BUTTON_SELECT, false, 1, layout)
        assertEquals(
            listOf(
                InputContract.EXTERNAL_SELECT_CHORD_GRACE_MS,
                InputContract.EXTERNAL_SELECT_CHORD_GRACE_MS,
            ),
            scheduler.delays,
        )
        scheduler.drain()
        assertEquals(
            listOf(KeyEvent.KEYCODE_F12 to true, KeyEvent.KEYCODE_F12 to false),
            sink.events.filterIsInstance<SinkEvent.Key>().map { it.logicalKeyCode to it.pressed },
        )
        assertTrue(c.isNeutral(1))
    }

    @Test fun `exact v11 rear-button default migrates but custom rear mapping is preserved`() {
        fun decode(rearLeft: ControllerAction): InputProfile {
            val stored = org.json.JSONObject()
            InputProfile.defaultRp6Bindings().toMutableMap().apply {
                this[Rp6Control.REAR_LEFT] = rearLeft
                this[Rp6Control.REAR_RIGHT] = ControllerAction.POINTER_LEFT
            }.forEach { (control, action) -> stored.put(control.name, action.name) }
            return InputProfile.fromJson(org.json.JSONObject()
                .put("version", 11)
                .put("aspectIdentity", "16:9")
                .put("scheme", ControlScheme.CLASSIC_CAMERA.name)
                .put("rp6Bindings", stored))
        }

        val migrated = decode(ControllerAction.CAMERA_LOCK)
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(migrated, Rp6Control.REAR_LEFT))
        assertEquals(ControllerAction.DISABLED, InputProfile.actionFor(migrated, Rp6Control.REAR_RIGHT))

        val custom = decode(ControllerAction.JUMP)
        assertEquals(ControllerAction.JUMP, InputProfile.actionFor(custom, Rp6Control.REAR_LEFT))
        assertEquals(ControllerAction.POINTER_LEFT, InputProfile.actionFor(custom, Rp6Control.REAR_RIGHT))
    }
}
