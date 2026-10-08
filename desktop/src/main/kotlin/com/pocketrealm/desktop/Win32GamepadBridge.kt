package com.pocketrealm.desktop

import com.pocketrealm.client.ControlPointer
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.client.FaceLayer
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.Rp6Control
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.Vk
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions

/**
 * DESK-4 (portable subset): the Win32 gamepad reader — the desktop twin of
 * the Android controller lane. An XInput poller translates the physical pad
 * through the versioned [InputProfile] (the same map the controls screen
 * edits) and injects the mapped outputs into whatever window is foreground
 * — keyboard keys via the [Win32AutoLogin] SendInput seam, mouse
 * buttons/wheel via SendInput mouse events — ONLY while the WoW.exe client
 * owns the foreground (the desktop client is a native window; the Android
 * lane injected through the embedded X server instead).
 *
 * Output dispatch is faithful where the mechanism exists natively:
 *  - actions carrying a virtual-key code inject that key (hold semantics
 *    while the physical control is held; the stick/dpad use the profile's
 *    dead zones),
 *  - AUTO_RUN keeps the Android dispatch discipline: NUM_LOCK stays the
 *    catalog identity but the app injects the app-owned F9 edge (a real
 *    NumLock tap would toggle the OS numpad mode),
 *  - TARGET_PULSE taps the stock unbound F6 slot (the launch-time binding
 *    reservation the profile KDoc describes), USE_LOOT_CLICK clicks the
 *    right mouse button at the pointer,
 *  - face layers L1/L2 switch the app-level layer over the four face
 *    buttons exactly like the Android profile semantics,
 *  - actions with no native desktop target (CAMERA_LOCK toggle, the
 *    NEARBY_USE realm-assisted lane, the add-on-owned CONSOLE_RADIAL and
 *    MOVE_UI edges) are skipped with a one-time honest note — the nearby
 *    lane needs the client acceptance patch desktop never applied, and the
 *    radial/mover edges belong to the project add-on's frames.
 *
 * All failures degrade honestly: no pad, no client window, a refused
 * foreground check — the poller idles; it never throws into the app.
 */
class Win32GamepadBridge(
    /** The live profile (re-read every tick so edits apply immediately). */
    private val profile: () -> InputProfile,
    /** The client pid probe (cheap; the bridge re-probes once a second). */
    private val clientPid: () -> Int?,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val xinput: XInput? = loadXInput()
    private val user32 = User32.INSTANCE

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running: Boolean = false

    /** Serializes the claim-and-spawn in [start] against [stop] (and
     * against itself): the two live callers — the post-launch auto-start on
     * an IO dispatcher and the Controls screen's toggle on the UI thread —
     * can otherwise interleave the unlocked check-then-act and spawn TWO
     * poller threads, double-injecting the mapped keys. */
    private val lifecycleLock = Any()

    /** Whether an XInput DLL was loadable (a machine without a pad or the
     * system XInput still has xinput1_4 on Windows 8+; absent → the bridge
     * is honestly inert). */
    fun gamepadSupported(): Boolean = xinput != null

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            if (xinput == null) return
            running = true
            worker = Thread({ loop() }, "pocketrealm-gamepad").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Whether the poller is live (the controls screen's toggle state). */
    val injecting: Boolean get() = running

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            worker?.interrupt()
            worker = null
        }
        releaseAllHeld()
    }

    // ------------------------------------------------------------ poll loop

    private fun loop() {
        val state = XInput.State()
        val previous = BooleanArray(Rp6Control.entries.size)
        val held = HashMap<Rp6Control, HeldKey>()
        var layer = FaceLayer.L2
        var lastPidProbe = 0L
        var cachedPid: Int? = null
        val notedOnce = HashSet<ControllerAction>()
        while (running) {
            val profileNow = profile()
            val now = System.currentTimeMillis()
            if (now - lastPidProbe > PID_PROBE_MILLIS) {
                cachedPid = clientPid()
                lastPidProbe = now
            }
            val foreground = cachedPid != null && foregroundBelongsTo(cachedPid)
            if (!foreground) {
                // Nothing is owed to a window the player is not playing;
                // a held key that survives a focus loss would be a stuck key.
                releaseAll(held)
                previous.fill(false)
                if (pollMillis > 0) {
                    try {
                        Thread.sleep(pollMillis * IDLE_BACKOFF)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                continue
            }
            val read = xinput!!.XInputGetState(0, state)
            if (read != ERROR_SUCCESS) {
                releaseAll(held)
                previous.fill(false)
                sleep(pollMillis)
                continue
            }
            val gamepad = state.Gamepad
            val pressed = BooleanArray(Rp6Control.entries.size)
            // Buttons word.
            markIf(pressed, Rp6Control.FACE_BOTTOM, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_A) != 0)
            markIf(pressed, Rp6Control.FACE_RIGHT, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_B) != 0)
            markIf(pressed, Rp6Control.FACE_LEFT, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_X) != 0)
            markIf(pressed, Rp6Control.FACE_TOP, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_Y) != 0)
            markIf(pressed, Rp6Control.DPAD_UP, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_DPAD_UP) != 0)
            markIf(pressed, Rp6Control.DPAD_DOWN, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_DPAD_DOWN) != 0)
            markIf(pressed, Rp6Control.DPAD_LEFT, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_DPAD_LEFT) != 0)
            markIf(pressed, Rp6Control.DPAD_RIGHT, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_DPAD_RIGHT) != 0)
            markIf(pressed, Rp6Control.L1, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_LEFT_SHOULDER) != 0)
            markIf(pressed, Rp6Control.R1, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_RIGHT_SHOULDER) != 0)
            markIf(pressed, Rp6Control.START, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_START) != 0)
            markIf(pressed, Rp6Control.SELECT, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_BACK) != 0)
            markIf(pressed, Rp6Control.L3, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_LEFT_THUMB) != 0)
            markIf(pressed, Rp6Control.R3, (gamepad.wButtons.toInt() and XINPUT_GAMEPAD_RIGHT_THUMB) != 0)
            // Analogue triggers with the profile's hysteresis (they are axes
            // on XInput, not buttons — the Android lane read them the same way).
            markIf(pressed, Rp6Control.L2, triggerHeld(gamepad.bLeftTrigger, previous[Rp6Control.L2.ordinal], profileNow.leftTriggerOnThreshold, profileNow.leftTriggerOffThreshold))
            markIf(pressed, Rp6Control.R2, triggerHeld(gamepad.bRightTrigger, previous[Rp6Control.R2.ordinal], profileNow.rightTriggerOnThreshold, profileNow.rightTriggerOffThreshold))
            // Left stick axes through the profile dead zone (right stick is
            // the camera on the desktop: the native client already gets the
            // physical mouse for looking, so it stays unmapped).
            val deadZone = profileNow.deadZone
            val lx = axisNormalized(gamepad.sThumbLX)
            val ly = axisNormalized(gamepad.sThumbLY)
            markIf(pressed, Rp6Control.LEFT_STICK_UP, ly > deadZone)
            markIf(pressed, Rp6Control.LEFT_STICK_DOWN, ly < -deadZone)
            markIf(pressed, Rp6Control.LEFT_STICK_LEFT, lx < -deadZone)
            markIf(pressed, Rp6Control.LEFT_STICK_RIGHT, lx > deadZone)
            // REAR_LEFT/REAR_RIGHT have no XInput equivalent (controller-
            // specific paddles): permanently unpressed, honestly.
            pressed[Rp6Control.REAR_LEFT.ordinal] = false
            pressed[Rp6Control.REAR_RIGHT.ordinal] = false

            // Edges and holds.
            for (control in Rp6Control.entries) {
                val action = layerAction(profileNow, layer, control)
                val down = pressed[control.ordinal]
                val wasDown = previous[control.ordinal]
                when {
                    action.keyCode != null -> {
                        val vk = dispatchKey(action.keyCode)
                        holdKey(held, control, vk, down, wasDown)
                    }
                    action.pointer == ControlPointer.LEFT ->
                        holdMouse(held, control, MOUSE_LEFT_DOWN, MOUSE_LEFT_UP, down, wasDown)
                    action.pointer == ControlPointer.RIGHT ->
                        holdMouse(held, control, MOUSE_RIGHT_DOWN, MOUSE_RIGHT_UP, down, wasDown)
                    action.wheelTicks != null && down && !wasDown ->
                        injectWheel(action.wheelTicks * WHEEL_DELTA)
                    action == ControllerAction.LAYER_L1 && down && !wasDown -> layer = FaceLayer.L1
                    action == ControllerAction.LAYER_L2 && down && !wasDown -> layer = FaceLayer.L2
                    action == ControllerAction.AUTO_RUN && down && !wasDown -> tapKey(Vk.VK_F9)
                    action == ControllerAction.TARGET_PULSE && down && !wasDown -> tapKey(Vk.VK_F6)
                    action == ControllerAction.USE_LOOT_CLICK && down && !wasDown ->
                        clickMouse(MOUSE_RIGHT_DOWN, MOUSE_RIGHT_UP)
                    !notedOnce.contains(action) && down -> {
                        // Actions with no native desktop target: one honest
                        // note each, then silence (never a hot loop of logs).
                        notedOnce.add(action)
                        DesktopLog.i(
                            TAG,
                            "gamepad: ${control.name} -> ${action.name} has no native desktop " +
                                "target (app/addon-owned edge); not injected",
                        )
                    }
                }
            }
            // Drop holds whose mapping changed under them (profile edit).
            held.keys.toList().forEach { control ->
                val action = layerAction(profileNow, layer, control)
                val currentVk = action.keyCode?.let(::dispatchKey)
                val currentMouse = when (action.pointer) {
                    ControlPointer.LEFT -> MOUSE_LEFT_DOWN
                    ControlPointer.RIGHT -> MOUSE_RIGHT_DOWN
                    else -> null
                }
                val heldNow = held[control]
                if (heldNow != null && heldNow.vk != currentVk && heldNow.mouseDownFlag == null && currentVk != null) {
                    releaseHeld(held, control)
                } else if (heldNow != null && heldNow.mouseDownFlag != null &&
                    heldNow.mouseDownFlag != currentMouse
                ) {
                    releaseHeld(held, control)
                }
            }
            System.arraycopy(pressed, 0, previous, 0, pressed.size)
            sleep(pollMillis)
        }
        releaseAllHeld()
    }

    // Layer resolution mirrors InputProfile.actionFor(profile, layer, control):
    // an explicit layer-face binding wins, else the stock layer default.
    private fun layerAction(profile: InputProfile, layer: FaceLayer, control: Rp6Control): ControllerAction =
        InputProfile.actionFor(profile, layer, control)
            ?: InputProfile.actionFor(profile, control)

    private fun dispatchKey(identity: Int): Int =
        if (identity == Vk.VK_NUMLOCK) Vk.VK_F9 else identity

    private fun triggerHeld(raw: Byte, wasHeld: Boolean, onThreshold: Float, offThreshold: Float): Boolean {
        val value = (raw.toInt() and 0xFF) / 255f
        return if (wasHeld) value >= offThreshold else value >= onThreshold
    }

    private fun axisNormalized(raw: Short): Float {
        // XInput's stick range is -32768..32767; normalize the negative side
        // to -1..0 and the positive to 0..1 (the documented asymmetry).
        return if (raw < 0) raw / 32768f else raw / 32767f
    }

    private fun markIf(target: BooleanArray, control: Rp6Control, condition: Boolean) {
        if (condition) target[control.ordinal] = true
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------- foreground gate

    private fun foregroundBelongsTo(pid: Int?): Boolean {
        if (pid == null || pid <= 0) return false
        val foreground = user32.GetForegroundWindow() ?: return false
        val owner = IntByReference()
        user32.GetWindowThreadProcessId(foreground, owner)
        return owner.value == pid
    }

    // ------------------------------------------------------------- injection

    private class HeldKey(val vk: Int?, val mouseDownFlag: Int?)

    private fun holdKey(held: MutableMap<Rp6Control, HeldKey>, control: Rp6Control, vk: Int, down: Boolean, wasDown: Boolean) {
        if (down && !wasDown) {
            sendKey(vk, up = false)
            held[control] = HeldKey(vk, null)
        } else if (!down && wasDown) {
            sendKey(vk, up = true)
            held.remove(control)
        }
    }

    private fun holdMouse(held: MutableMap<Rp6Control, HeldKey>, control: Rp6Control, downFlag: Int, upFlag: Int, down: Boolean, wasDown: Boolean) {
        if (down && !wasDown) {
            sendMouse(downFlag)
            held[control] = HeldKey(null, downFlag)
        } else if (!down && wasDown) {
            sendMouse(upFlag)
            held.remove(control)
        }
    }

    private fun releaseHeld(held: MutableMap<Rp6Control, HeldKey>, control: Rp6Control) {
        val heldKey = held.remove(control) ?: return
        if (heldKey.vk != null) sendKey(heldKey.vk, up = true)
        if (heldKey.mouseDownFlag != null) sendMouse(heldKey.mouseDownFlag)
    }

    private fun releaseAll(held: MutableMap<Rp6Control, HeldKey>) {
        held.keys.toList().forEach { releaseHeld(held, it) }
    }

    private fun releaseAllHeld() {
        // A fresh map shares nothing with the poller; the poller itself
        // releases through its own map on every exit path. This standalone
        // release covers the stop() path racing a poll.
        // (SendInput KEYUP for a key that is not down is a harmless no-op.)
        injectedKeys.forEach { sendKey(it, up = true) }
    }

    /** Keys this bridge may currently hold down (upper bound = every
     * injectable key action), released defensively on stop(). */
    private val injectedKeys: IntArray = buildList {
        ControllerAction.entries.forEach { action ->
            action.keyCode?.let { vk -> add(dispatchKey(vk)) }
        }
    }.toIntArray()

    private fun tapKey(vk: Int) {
        if (!sendKey(vk, up = false)) return
        sleep(KEY_TAP_MILLIS)
        sendKey(vk, up = true)
    }

    private fun clickMouse(downFlag: Int, upFlag: Int) {
        sendMouse(downFlag)
        sleep(KEY_TAP_MILLIS)
        sendMouse(upFlag)
    }

    private fun sendKey(vk: Int, up: Boolean): Boolean {
        val keyboard = WinUser.KEYBDINPUT()
        keyboard.wVk = WinDef.WORD(vk.toLong())
        keyboard.dwFlags = WinDef.DWORD(if (up) WinUser.KEYBDINPUT.KEYEVENTF_KEYUP.toLong() else 0L)
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki = keyboard
        return user32.SendInput(WinDef.DWORD(1), arrayOf(input), input.size()).toInt() == 1
    }

    private fun sendMouse(flag: Int) {
        val mouse = WinUser.MOUSEINPUT()
        mouse.dwFlags = WinDef.DWORD(flag.toLong())
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.input.setType("mi")
        input.input.mi = mouse
        user32.SendInput(WinDef.DWORD(1), arrayOf(input), input.size())
    }

    private fun injectWheel(delta: Int) {
        val mouse = WinUser.MOUSEINPUT()
        mouse.dwFlags = WinDef.DWORD(MOUSE_WHEEL.toLong())
        mouse.mouseData = WinDef.DWORD(delta.toLong())
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.input.setType("mi")
        input.input.mi = mouse
        user32.SendInput(WinDef.DWORD(1), arrayOf(input), input.size())
    }

    // -------------------------------------------------------------- Win32/JNA

    private fun loadXInput(): XInput? =
        listOf("xinput1_4", "xinput9_1_0").firstNotNullOfOrNull { name ->
            runCatching { Native.load(name, XInput::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }

    @Suppress("FunctionNaming", "MagicNumber") // Win32 naming and pad constants
    private interface XInput : com.sun.jna.Library {
        fun XInputGetState(userIndex: Int, state: State): Int

        class Gamepad : Structure() {
            // JNA maps PUBLIC fields only — a bare Kotlin `var` is a private
            // backing field and Structure construction dies with
            // "declared field names ([])" (the same discipline as
            // DesktopChatterPowerMonitor.SYSTEM_POWER_STATUS). The structure
            // is thread-confined to the poller, so volatile is not owed.
            @JvmField
            var wButtons: Short = 0

            @JvmField
            var bLeftTrigger: Byte = 0

            @JvmField
            var bRightTrigger: Byte = 0

            @JvmField
            var sThumbLX: Short = 0

            @JvmField
            var sThumbLY: Short = 0

            @JvmField
            var sThumbRX: Short = 0

            @JvmField
            var sThumbRY: Short = 0

            override fun getFieldOrder(): List<String> = listOf(
                "wButtons", "bLeftTrigger", "bRightTrigger",
                "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY",
            )
        }

        class State : Structure() {
            @JvmField
            var dwPacketNumber: Int = 0

            @JvmField
            var Gamepad: Gamepad = Gamepad()

            override fun getFieldOrder(): List<String> = listOf("dwPacketNumber", "Gamepad")
        }
    }

    private companion object {
        const val TAG = "GamepadBridge"
        const val POLL_MILLIS = 16L
        const val IDLE_BACKOFF = 4L
        const val PID_PROBE_MILLIS = 1_000L
        const val KEY_TAP_MILLIS = 25L
        const val WHEEL_DELTA = 120
        const val ERROR_SUCCESS = 0

        const val XINPUT_GAMEPAD_DPAD_UP = 0x0001
        const val XINPUT_GAMEPAD_DPAD_DOWN = 0x0002
        const val XINPUT_GAMEPAD_DPAD_LEFT = 0x0004
        const val XINPUT_GAMEPAD_DPAD_RIGHT = 0x0008
        const val XINPUT_GAMEPAD_START = 0x0010
        const val XINPUT_GAMEPAD_BACK = 0x0020
        const val XINPUT_GAMEPAD_LEFT_THUMB = 0x0040
        const val XINPUT_GAMEPAD_RIGHT_THUMB = 0x0080
        const val XINPUT_GAMEPAD_LEFT_SHOULDER = 0x0100
        const val XINPUT_GAMEPAD_RIGHT_SHOULDER = 0x0200
        const val XINPUT_GAMEPAD_A = 0x1000
        const val XINPUT_GAMEPAD_B = 0x2000
        const val XINPUT_GAMEPAD_X = 0x4000
        const val XINPUT_GAMEPAD_Y = 0x8000

        // WinUser.h MOUSEEVENTF_* flags (local: JNA's WinUser carries them
        // under a different shape and the values are frozen by WinUser.h).
        const val MOUSE_LEFT_DOWN = 0x0002
        const val MOUSE_LEFT_UP = 0x0004
        const val MOUSE_RIGHT_DOWN = 0x0008
        const val MOUSE_RIGHT_UP = 0x0010
        const val MOUSE_WHEEL = 0x0800
    }
}
