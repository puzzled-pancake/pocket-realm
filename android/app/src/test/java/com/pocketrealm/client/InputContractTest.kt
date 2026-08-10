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
            overlayOpacity = 0.6f,
            overlayEnabled = false,
            overlayScale = 1.25f,
            cameraRegionWidth = 0.5f,
            invertCameraY = true,
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
        assertEquals(ControllerAction.KEY_9,
            InputProfile.actionFor(migrated, Rp6Control.R1))

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

    @Test fun `RP6 gamepad maps physical controls and releases every held output`() {
        val (c, sink) = newContract()
        val rp6 = InputContract.GamepadLayout.RETROID_POCKET_6
        val expected = listOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_4,
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
