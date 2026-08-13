package com.pocketrealm.client

import android.view.KeyEvent
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The injection surface the contract talks to. The default production
 * implementation wraps a real winlator [XServer]; tests supply a recording fake.
 * Keeping this narrow lets the contract's state-machine logic (generation
 * gating, ordering, release semantics) be unit-tested on the host JVM without
 * instantiating Android-bound XServer machinery.
 */
fun interface InputSink {
    fun inject(event: SinkEvent)
}

/**
 * Schedules the next step of a serialized IME key pulse without blocking the
 * Android UI thread. Production uses the display View's main-loop scheduler;
 * host tests may use the immediate default or a deterministic fake.
 */
fun interface ImePulseScheduler {
    fun postDelayed(delayMillis: Long, action: () -> Unit)
}

/** Pointer button codes the sink understands (mirrors winlator's set). */
enum class SinkButton { LEFT, MIDDLE, RIGHT, SCROLL_UP, SCROLL_DOWN, SCROLL_CLICK_LEFT, SCROLL_CLICK_RIGHT }

/** A single primitive injection event emitted by the contract to the sink. */
sealed class SinkEvent {
    data class PointerMove(val x: Int, val y: Int) : SinkEvent()
    data class PointerMoveDelta(val dx: Int, val dy: Int) : SinkEvent()
    data class PointerButton(val button: SinkButton, val pressed: Boolean) : SinkEvent()
    data class Key(
        val event: KeyEvent,
        val logicalKeyCode: Int = event.keyCode,
        val pressed: Boolean = event.action == KeyEvent.ACTION_DOWN,
    ) : SinkEvent()
}

/** Production sink: forwards each [SinkEvent] to the existing XServer primitives. */
class XServerInputSink(private val xServer: XServer) : InputSink {
    override fun inject(event: SinkEvent) {
        when (event) {
            is SinkEvent.PointerMove -> xServer.injectPointerMove(event.x, event.y)
            is SinkEvent.PointerMoveDelta -> xServer.injectPointerMoveDelta(event.dx, event.dy)
            is SinkEvent.PointerButton -> {
                val b = event.button.toX()
                if (event.pressed) xServer.injectPointerButtonPress(b) else xServer.injectPointerButtonRelease(b)
            }
            is SinkEvent.Key -> xServer.keyboard.onKeyEvent(event.event)
        }
    }

    private fun SinkButton.toX(): Pointer.Button = when (this) {
        SinkButton.LEFT -> Pointer.Button.BUTTON_LEFT
        SinkButton.MIDDLE -> Pointer.Button.BUTTON_MIDDLE
        SinkButton.RIGHT -> Pointer.Button.BUTTON_RIGHT
        SinkButton.SCROLL_UP -> Pointer.Button.BUTTON_SCROLL_UP
        SinkButton.SCROLL_DOWN -> Pointer.Button.BUTTON_SCROLL_DOWN
        SinkButton.SCROLL_CLICK_LEFT -> Pointer.Button.BUTTON_SCROLL_CLICK_LEFT
        SinkButton.SCROLL_CLICK_RIGHT -> Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT
    }
}

/**
 * O14 G4 mobile input UX — versioned logical input contract v1.
 *
 * Sits between Android input events (translated by [ClientInputBridge]) and the
 * existing in-process winlator [XServer] injection methods. It owns all pressed
 * state, stamps every event with the active display/client generation, rejects
 * stale input, preserves per-source DOWN-before-UP ordering, and provides one
 * deterministic [releaseAll] exit path that releases every genuinely held input
 * in a fixed order.
 *
 * Boundary: pure UI-process state, like the prior [ClientInputBridge] sets. It
 * only calls XServer primitives that already exist (`injectPointerMove`,
 * `injectPointerMoveDelta`, `injectPointerButtonPress/Release`,
 * `keyboard.onKeyEvent`). No AIDL, no native change, no Wine change. ADR-014's
 * "UI process owns input translation" invariant is preserved.
 *
 * Generation ownership: each [ClientDisplayHost] instance is one display/client
 * generation. A fresh host (surface recreate or client relaunch) constructs a
 * fresh contract with a new [generation]; events whose generation differs from
 * the active one are rejected before injection, so a stale touch from a recycled
 * surface cannot reach the new WoW window. This hardens the O12 renderer-restart
 * fix without inventing a second lifecycle authority.
 *
 * IME composition, gamepad axes/buttons, pointer capture, and profile storage
 * all enter through this same generation/session boundary; no input path gets a
 * second lifecycle authority.
 */
class InputContract(
    private val sink: InputSink,
    private val imeScheduler: ImePulseScheduler = ImePulseScheduler { _, action -> action() },
    private val imeKeyDwellMs: Long = Companion.IME_KEY_DWELL_MS,
    private val imeKeyGapMs: Long = Companion.IME_KEY_GAP_MS,
    private val fieldSettleMs: Long = Companion.AUTO_LOGIN_FIELD_SETTLE_MS,
    private val pointerDwellMs: Long = Companion.IME_POINTER_DWELL_MS,
    private val onCameraLockChanged: (Boolean) -> Unit = {},
) {
    /** Logical pointer buttons the contract understands. */
    enum class PointerButton { LEFT, MIDDLE, RIGHT }

    /** Reason a [releaseAll] was triggered; recorded in the [ReleaseReport]. */
    enum class ReleaseReason {
        FOCUS_LOSS,
        ON_PAUSE,
        GENERATION_REPLACED,
        CLOSE,
        EXPLICIT_RELEASE_INPUT,
        DEVICE_REMOVED,
        IME_OPENED,
    }

    /**
     * Bounded, deterministic report produced by [releaseAll]. Counts are of
     * genuinely held inputs that were actually released; wheel pulses are never
     * counted (they are atomic and never retained).
     */
    data class ReleaseReport(
        val reason: ReleaseReason,
        val sources: List<Int>,
        val keyCount: Int,
        val buttonCount: Int,
        val rejectedStaleEventCount: Long,
    )

    /** Logical axes exposed by Android joystick/gamepad motion events. */
    enum class GamepadAxis {
        LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, HAT_X, HAT_Y, LEFT_TRIGGER, RIGHT_TRIGGER,
    }

    /** Named mappings are selected by the Android-device bridge, never globally. */
    enum class GamepadLayout { GENERIC, PROFILED, RETROID_POCKET_6, DISABLED }

    /** Exact physical/synthetic control that owns one logical held input. */
    private sealed interface ControlOwner {
        data class AndroidKey(val keyCode: Int) : ControlOwner
        data class GamepadButton(val keyCode: Int) : ControlOwner
        data class Axis(val axis: GamepadAxis) : ControlOwner
        data class DirectPointer(val button: PointerButton) : ControlOwner
        data object ImePulse : ControlOwner
        data object CameraLock : ControlOwner
        data object AutomaticCamera : ControlOwner
    }

    /** Per-source pressed state. One entry per Android `deviceId` or synthetic source. */
    private data class SourceState(
        val keyOwners: MutableMap<ControlOwner, Int> = LinkedHashMap(),
        val buttonOwners: MutableMap<ControlOwner, PointerButton> = LinkedHashMap(),
        val axisKeys: MutableMap<GamepadAxis, Int?> = java.util.EnumMap(GamepadAxis::class.java),
        val axisPressed: MutableSet<GamepadAxis> = java.util.EnumSet.noneOf(GamepadAxis::class.java),
        // Joystick motion is sampled more finely than an integer X pointer can
        // represent. Retain each sub-pixel remainder instead of truncating it
        // on every Android sample; otherwise the inner stick travel becomes a
        // series of dead bands followed by visible one-pixel jumps.
        var relativeRemainderX: Float = 0f,
        var relativeRemainderY: Float = 0f,
        var rightStickX: Float = 0f,
        var rightStickY: Float = 0f,
        val cameraToggleOwners: MutableSet<ControlOwner> = LinkedHashSet(),
        var lastSeq: Long = 0L,
    )

    // ---- generation/session ownership -------------------------------------
    @Volatile private var activeGeneration: Long = 0L
    @Volatile private var activeSession: UUID? = null
    @Volatile private var acceptingInput: Boolean = false
    @Volatile private var profile: InputProfile = InputProfile.DEFAULT
    @Volatile private var profileReset: Boolean = false

    private val rejectedStale = AtomicLong(0L)
    private val releasedKeys = AtomicLong(0L)
    private val releasedButtons = AtomicLong(0L)
    private val reportRef = AtomicReference(ReleaseReport(ReleaseReason.EXPLICIT_RELEASE_INPUT, emptyList(), 0, 0, 0L))

    // Single mutex guards all pressed-state mutation + injection so release
    // ordering and per-source sequence assignment are deterministic under
    // concurrent Android dispatch threads.
    private val lock = Any()
    private val sources = LinkedHashMap<Int, SourceState>()
    /** Number of exact owners currently holding each logical X key/button. */
    private val logicalKeyOwnerCounts = sortedMapOf<Int, Int>()
    private val logicalButtonOwnerCounts = java.util.EnumMap<PointerButton, Int>(PointerButton::class.java)
    private var cameraLocked = false
    private var cameraLockOwnerSource: Int? = null
    private var automaticGamepadCameraActive = false
    private var gamepadPointerFrameTimeNanos = 0L
    private data class ImePulse(
        val keyCode: Int?,
        val metaState: Int = 0,
        val gapAfterMs: Long = IME_KEY_GAP_MS,
        val pointerX: Int? = null,
        val pointerY: Int? = null,
    )
    private val imeQueue = java.util.ArrayDeque<ImePulse>()
    private var imePulseEpoch = 0L
    private var imeStepScheduled = false
    private var imeInFlightKey: Int? = null

    /**
     * Attach this contract to a new display/client generation. Any pressed
     * state from a prior generation is released (in [releaseAll] order) before
     * the new generation accepts input, and [rejectedStaleEventCount] is
     * preserved across the swap. Returns the release report for the old state
     * (zero counts if nothing was held).
     *
     * @param sessionId the client session this contract serves (informational;
     *   matches the existing [ClientRuntime] session id; no second authority)
     * @param generation monotonic display/client generation; events with any
     *   other generation are rejected
     * @param newProfile the input profile to activate; if its [InputProfile.aspectIdentity]
     *   does not match [aspectIdentity], [DEFAULT][InputProfile.DEFAULT] is
     *   selected and [profileReset] becomes true
     * @param aspectIdentity active display aspect identity (e.g. `"16:9"`)
     */
    fun attach(
        sessionId: UUID?,
        generation: Long,
        newProfile: InputProfile = InputProfile.DEFAULT,
        aspectIdentity: String = InputProfile.DEFAULT_ASPECT_IDENTITY,
    ): ReleaseReport = synchronized(lock) {
        val report = releaseAllLocked(ReleaseReason.GENERATION_REPLACED)
        activeSession = sessionId
        activeGeneration = generation
        acceptingInput = true
        // A new display generation must not inherit the previous editor's
        // capture/suspension state.
        imeActive = false
        profile = if (newProfile.aspectIdentity == aspectIdentity) {
            profileReset = false
            newProfile
        } else {
            profileReset = true
            InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity)
        }
        report
    }

    /**
     * Permanently detach this contract from its display host. The operation is
     * idempotent and atomically prevents every later event before releasing
     * held state and invalidating delayed IME callbacks.
     */
    fun detach(): ReleaseReport = synchronized(lock) {
        if (!acceptingInput) {
            return ReleaseReport(ReleaseReason.CLOSE, emptyList(), 0, 0, rejectedStale.get())
        }
        acceptingInput = false
        imeActive = false
        activeSession = null
        releaseAllLocked(ReleaseReason.CLOSE)
    }

    /** Currently active generation; 0 before the first [attach]. */
    val generation: Long get() = activeGeneration

    /** Currently active session; null before the first [attach]. */
    val sessionId: UUID? get() = activeSession

    /** True when the last [attach] reset to the default profile due to aspect mismatch. */
    val isProfileReset: Boolean get() = profileReset

    /** Active profile used for dead-zone and captured-camera translation. */
    val activeProfile: InputProfile get() = profile

    /** Replace the profile for the current generation after releasing held input. */
    fun switchProfile(newProfile: InputProfile, aspectIdentity: String, generation: Long): ReleaseReport =
        synchronized(lock) {
            if (!acceptLocked(generation)) {
                return ReleaseReport(ReleaseReason.GENERATION_REPLACED, emptyList(), 0, 0, rejectedStale.get())
            }
            val report = releaseAllLocked(ReleaseReason.EXPLICIT_RELEASE_INPUT)
            profile = if (newProfile.aspectIdentity == aspectIdentity) {
                profileReset = false
                newProfile
            } else {
                profileReset = true
                InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity)
            }
            report
        }

    /** Last [ReleaseReport] produced; convenient for diagnostics/tests. */
    val lastReport: ReleaseReport get() = reportRef.get()

    /** Cumulative count of events rejected for stale-generation or out-of-order. */
    val rejectedStaleEventCount: Long get() = rejectedStale.get()
    /** Cumulative release counts retained for bounded lifecycle diagnostics. */
    val releasedKeyCount: Long get() = releasedKeys.get()
    val releasedButtonCount: Long get() = releasedButtons.get()

    // ---- pointer ----------------------------------------------------------

    /**
     * Absolute pointer motion. Applies the caller's letterbox transform already
     * (the bridge owns the Android-view→X coordinate math, matching today's
     * verified path); the contract only stamps and injects.
     */
    fun pointerAbsolute(src: Int, x: Int, y: Int, generation: Long) {
        synchronized(lock) {
            if (!acceptLocked(generation)) return
            sink.inject(SinkEvent.PointerMove(x, y))
        }
    }

    /** Relative pointer motion (camera-look / captured mouse). */
    fun pointerRelative(src: Int, dx: Int, dy: Int, generation: Long) {
        synchronized(lock) {
            if (!acceptLocked(generation)) return
            sink.inject(SinkEvent.PointerMoveDelta(dx, dy))
        }
    }

    /**
     * Pointer button press/release. Enforces DOWN-before-UP per source and
     * drops an unmatched UP (idempotent cleanup) without synthesizing a phantom
     * DOWN. A duplicate DOWN for an already-held button is a no-op on tracking
     * (the press is still forwarded, matching Android repeat semantics for keys;
     * for buttons we forward only the state transition).
     */
    fun pointerButton(src: Int, button: PointerButton, pressed: Boolean, generation: Long) {
        synchronized(lock) {
            if (!acceptLocked(generation)) return
            pointerButtonLocked(src, ControlOwner.DirectPointer(button), button, pressed)
        }
    }

    /**
     * Hold or release WoW's right mouse button as a persistent camera mode.
     * This is deliberately separate from Android pointer capture: it applies to
     * the RP6 right stick and touch camera region, and one tap replaces a
     * continuously held shoulder/rear button.
     */
    fun setCameraLock(enabled: Boolean, generation: Long, source: Int = CAMERA_LOCK_SOURCE): Boolean =
        synchronized(lock) {
            if (!acceptLocked(generation)) return@synchronized cameraLocked
            setCameraLockLocked(enabled, source)
        }

    fun toggleCameraLock(generation: Long, source: Int = CAMERA_LOCK_SOURCE): Boolean =
        synchronized(lock) {
            if (!acceptLocked(generation)) return@synchronized cameraLocked
            setCameraLockLocked(!cameraLocked, source)
        }

    val isCameraLocked: Boolean get() = synchronized(lock) { cameraLocked }

    /**
     * Wheel pulse: atomic press+release. Never retained as pressed state, never
     * counted in [releaseAll] held-state totals, and recorded in diagnostics
     * only as a transient pulse count.
     *
     * @param vTicks vertical ticks; positive = down, negative = up, 0 = none
     * @param hTicks horizontal ticks; positive = right, negative = left, 0 =
     *   none. Injected via winlator's scroll-click left/right buttons.
     */
    fun wheel(src: Int, vTicks: Int, hTicks: Int, generation: Long) {
        if (vTicks == 0 && hTicks == 0) return
        synchronized(lock) {
            if (!acceptLocked(generation)) return
            // Vertical: repeat per tick to preserve direction granularity.
            repeat(kotlin.math.abs(vTicks)) {
                val b = if (vTicks > 0) SinkButton.SCROLL_DOWN else SinkButton.SCROLL_UP
                sink.inject(SinkEvent.PointerButton(b, pressed = true))
                sink.inject(SinkEvent.PointerButton(b, pressed = false))
            }
            // Horizontal: winlator exposes scroll-click left/right buttons.
            repeat(kotlin.math.abs(hTicks)) {
                val b = if (hTicks > 0) SinkButton.SCROLL_CLICK_RIGHT else SinkButton.SCROLL_CLICK_LEFT
                sink.inject(SinkEvent.PointerButton(b, pressed = true))
                sink.inject(SinkEvent.PointerButton(b, pressed = false))
            }
        }
    }

    // ---- keyboard ---------------------------------------------------------

    /**
     * Keyboard make/break from a real Android [KeyEvent]. The forward path
     * injects the actual event (preserving modifiers, repeat count, and device
     * state exactly as the verified O06/O12 path did); the contract tracks only
     * the keycode per source so [releaseAll] can synthesize an UP later. Enforces
     * DOWN-before-UP per source; an unmatched UP is dropped (idempotent cleanup)
     * without synthesizing a phantom DOWN. A duplicate DOWN for an already-held
     * key is idempotent. Ownership is edge-triggered because the X keyboard
     * retains only one pressed bit per logical key. Returns whether the event
     * was accepted.
     */
    fun key(src: Int, event: KeyEvent, generation: Long): Boolean {
        synchronized(lock) {
            if (!acceptLocked(generation)) return false
            return keyLocked(src, event)
        }
    }

    /**
     * Gamepad button translation. The physical button is converted to a
     * stable logical WoW key before entering the normal per-source key set.
     * This keeps hot-plug release and generation replacement identical to a
     * keyboard source.
     */
    fun gamepadButton(
        src: Int,
        keyCode: Int,
        pressed: Boolean,
        generation: Long,
        layout: GamepadLayout = GamepadLayout.GENERIC,
    ): Boolean {
        return synchronized(lock) {
            if (!acceptLocked(generation)) return@synchronized false
            val binding = gamepadBinding(keyCode, layout, profile) ?: return@synchronized false
            gamepadActionLocked(src, ControlOwner.GamepadButton(keyCode), binding, pressed)
        }
    }

    /**
     * Gamepad axis translation with dead-zone hysteresis. Left-stick axes
     * synthesize WASD transitions; right-stick axes become relative camera
     * deltas. Returning false means the event was stale or intentionally
     * neutral/no-op.
     */
    fun gamepadAxis(
        src: Int,
        axis: GamepadAxis,
        value: Float,
        generation: Long,
        layout: GamepadLayout = GamepadLayout.GENERIC,
    ): Boolean {
        synchronized(lock) {
            if (!acceptLocked(generation)) return false
            if (layout == GamepadLayout.DISABLED) return false
            if (imeActive && axis != GamepadAxis.RIGHT_X && axis != GamepadAxis.RIGHT_Y) {
                return false
            }
            val bounded = value.coerceIn(-1f, 1f)
            val st = sources.getOrPut(src) { SourceState() }
            val deadZone = profile.deadZone
            val profiled = layout == GamepadLayout.RETROID_POCKET_6 || layout == GamepadLayout.PROFILED
            when (axis) {
                GamepadAxis.LEFT_X -> updateAxisKeyLocked(
                    st, axis, bounded, deadZone,
                    if (profiled) {
                        InputProfile.actionFor(profile, Rp6Control.LEFT_STICK_LEFT).keyCode
                    } else KeyEvent.KEYCODE_A,
                    if (profiled) {
                        InputProfile.actionFor(profile, Rp6Control.LEFT_STICK_RIGHT).keyCode
                    } else KeyEvent.KEYCODE_D,
                )
                GamepadAxis.LEFT_Y -> updateAxisKeyLocked(
                    st, axis, bounded, deadZone,
                    if (profiled) {
                        InputProfile.actionFor(profile, Rp6Control.LEFT_STICK_UP).keyCode
                    } else KeyEvent.KEYCODE_W,
                    if (profiled) {
                        InputProfile.actionFor(profile, Rp6Control.LEFT_STICK_DOWN).keyCode
                    } else KeyEvent.KEYCODE_S,
                )
                GamepadAxis.RIGHT_X -> updateRelativeAxisStateLocked(st, axis, bounded, horizontal = true)
                GamepadAxis.RIGHT_Y -> updateRelativeAxisStateLocked(st, axis, bounded, horizontal = false)
                GamepadAxis.HAT_X -> if (profiled) {
                    updateAxisKeyLocked(
                        st, axis, bounded, deadZone,
                        InputProfile.actionFor(profile, Rp6Control.DPAD_LEFT).keyCode,
                        InputProfile.actionFor(profile, Rp6Control.DPAD_RIGHT).keyCode,
                    )
                }
                GamepadAxis.HAT_Y -> if (profiled) {
                    updateAxisKeyLocked(
                        st, axis, bounded, deadZone,
                        InputProfile.actionFor(profile, Rp6Control.DPAD_UP).keyCode,
                        InputProfile.actionFor(profile, Rp6Control.DPAD_DOWN).keyCode,
                    )
                }
                GamepadAxis.LEFT_TRIGGER -> if (profiled) {
                    updateAxisActionLocked(
                        src, st, axis, bounded,
                        profile.leftTriggerOnThreshold, profile.leftTriggerOffThreshold,
                        actionBinding(InputProfile.actionFor(profile, Rp6Control.L2)),
                    )
                }
                GamepadAxis.RIGHT_TRIGGER -> if (profiled) {
                    updateAxisActionLocked(
                        src, st, axis, bounded,
                        profile.rightTriggerOnThreshold, profile.rightTriggerOffThreshold,
                        actionBinding(InputProfile.actionFor(profile, Rp6Control.R2)),
                    )
                }
            }
            return true
        }
    }

    /**
     * Integrate the latest absolute right-stick state once per display frame.
     * Android may batch duplicate joystick positions at a device-dependent
     * report rate; treating each report as displacement makes the camera both
     * too fast and visibly uneven. A frame clock keeps velocity independent of
     * report rate and continues smoothly while a held ABS axis is unchanged.
     */
    fun pumpGamepadPointer(frameTimeNanos: Long, generation: Long): Boolean = synchronized(lock) {
        if (!acceptLocked(generation)) return@synchronized false
        require(frameTimeNanos >= 0L) { "frame time must be non-negative" }
        syncAutomaticGamepadCameraLocked()
        val previous = gamepadPointerFrameTimeNanos
        gamepadPointerFrameTimeNanos = frameTimeNanos
        if (previous == 0L || frameTimeNanos <= previous) return@synchronized false
        val elapsedSeconds = ((frameTimeNanos - previous).coerceAtMost(MAX_GAMEPAD_FRAME_NANOS)) /
            NANOS_PER_SECOND.toFloat()
        var totalX = 0
        var totalY = 0
        for ((_, state) in sources.toSortedMap()) {
            val xAccumulated = state.rightStickX * profile.cameraSensitivity *
                GAMEPAD_CAMERA_PIXELS_PER_SECOND * elapsedSeconds + state.relativeRemainderX
            val yAccumulated = state.rightStickY * profile.cameraSensitivity *
                GAMEPAD_CAMERA_PIXELS_PER_SECOND * elapsedSeconds + state.relativeRemainderY
            val dx = xAccumulated.toInt()
            val dy = yAccumulated.toInt()
            state.relativeRemainderX = xAccumulated - dx
            state.relativeRemainderY = yAccumulated - dy
            totalX += dx
            totalY += dy
        }
        if (totalX == 0 && totalY == 0) return@synchronized false
        sink.inject(SinkEvent.PointerMoveDelta(totalX, totalY))
        true
    }

    // ---- focus / cleanup --------------------------------------------------

    /** Focus lost; releases all held input (deterministic order). */
    fun focusLost(): ReleaseReport = releaseAll(ReleaseReason.FOCUS_LOSS)

    // ---- IME (increment 2) ------------------------------------------------

    /**
     * The IME is opening. This releases held movement/gameplay keys and pointer
     * buttons and emits a bounded [ReleaseReport]. New gameplay-key input stays
     * suppressed while the IME owns text, but cursor motion/click/wheel remains
     * available so a handheld user can navigate the Win32 form without closing
     * Android's keyboard. Previously held keys are NOT restored on [imeClosed].
     *
     * @param generation the active generation; stale = no-op
     * @return the release report for what was held, or a zero report if stale
     */
    fun imeOpened(generation: Long): ReleaseReport = synchronized(lock) {
        if (!acceptLocked(generation)) {
            return ReleaseReport(ReleaseReason.IME_OPENED, emptyList(), 0, 0, rejectedStale.get())
        }
        // IMM may recreate the InputConnection while the keyboard is still
        // visible. Re-opening must not cancel already queued text/backspace.
        if (imeActive) {
            return ReleaseReport(ReleaseReason.IME_OPENED, emptyList(), 0, 0, rejectedStale.get())
        }
        imeActive = true
        releaseAllLocked(ReleaseReason.IME_OPENED)
    }

    /**
     * The IME is closing. Leaves input in a neutral state; does NOT restore
     * previously held keys. Clears the IME-active flag.
     *
     * @param generation the active generation; stale = no-op
     */
    fun imeClosed(generation: Long) = synchronized(lock) {
        if (!acceptLocked(generation)) return@synchronized
        imeActive = false
        cancelImeQueueLocked()
        val (keys, buttons) = removeSourceLocked(IME_SOURCE)
        releasedKeys.addAndGet(keys.toLong())
        releasedButtons.addAndGet(buttons.toLong())
    }

    /** True while the IME is open (gameplay keys released; cursor input remains available). */
    val isImeActive: Boolean get() = imeActive

    @Volatile private var imeActive: Boolean = false

    /**
     * Commit IME text through the contract. Maps the text to keycode+shift
     * sequences via [ImeCharMap], applies the generation gate, and — only if
     * **every** character is supported — injects the complete string in order
     * as DOWN+UP pairs through the same keyboard path as physical keys.
     *
     * **Atomicity (requirement):** if any character is unsupported, the entire
     * commit is rejected: zero characters are queued, all unsupported
     * codepoints are reported in the result, and no keys/modifiers/buttons are
     * left held. Accepted keys are serialized with a real dwell so frame-polled
     * DirectInput consumers cannot miss an instantaneous DOWN+UP pair.
     *
     * Empty commits are no-ops. The maximum commit length is enforced before
     * injection. Generation gating and deterministic ordering are preserved.
     *
     * @return the [ImeCharMap.ImeCommitResult] describing accepted/rejected
     *   characters; empty (all lists) if the generation was stale or the commit
     *   was empty
     */
    fun imeCommit(text: String, generation: Long): ImeCharMap.ImeCommitResult {
        val result = ImeCharMap.map(text)
        return synchronized(lock) {
            if (!acceptLocked(generation)) {
                return@synchronized ImeCharMap.ImeCommitResult(
                    emptyList(), emptyList(), ImeCharMap.Rejection.STALE_GENERATION,
                )
            }
            if (!imeActive) {
                return@synchronized result.copy(rejection = ImeCharMap.Rejection.IME_INACTIVE)
            }
            // Atomicity: if any character is unsupported, reject the entire
            // commit after the lifecycle/session gate and queue nothing.
            if (!result.allAccepted) return@synchronized result
            if (pendingImePulseCountLocked() + result.accepted.size > MAX_PENDING_IME_PULSES) {
                return@synchronized result.copy(rejection = ImeCharMap.Rejection.QUEUE_FULL)
            }
            for (m in result.accepted) {
                val meta = if (m.shift) {
                    KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                } else 0
                imeQueue.addLast(ImePulse(m.keyCode, meta, gapAfterMs = imeKeyGapMs))
            }
            startImeQueueLocked(generation)
            result
        }
    }

    /**
     * Delete [count] characters via serialized Backspace pulses. Delete shares
     * the same FIFO as committed text and editor actions, so it cannot overtake
     * a pending commit.
     */
    fun imeDelete(count: Int, generation: Long): Int {
        if (count !in 0..ImeCharMap.MAX_COMMIT_LENGTH) return 0
        val toDelete = count
        return synchronized(lock) {
            if (!acceptLocked(generation) || !imeActive) return@synchronized 0
            if (pendingImePulseCountLocked() + toDelete > MAX_PENDING_IME_PULSES) {
                return@synchronized 0
            }
            repeat(toDelete) { imeQueue.addLast(ImePulse(KeyEvent.KEYCODE_DEL, 0, gapAfterMs = imeKeyGapMs)) }
            startImeQueueLocked(generation)
            toDelete
        }
    }

    /** Queue one IME-owned key (for example Enter) behind pending text. */
    fun imeKeyTap(keyCode: Int, generation: Long): Boolean {
        return synchronized(lock) {
            if (!acceptLocked(generation) || !imeActive) return@synchronized false
            if (pendingImePulseCountLocked() >= MAX_PENDING_IME_PULSES) return@synchronized false
            imeQueue.addLast(ImePulse(keyCode, 0, gapAfterMs = imeKeyGapMs))
            startImeQueueLocked(generation)
            true
        }
    }

    /** True when no committed-text, delete, or editor-action pulse is pending. */
    val isImeInputIdle: Boolean
        get() = synchronized(lock) {
            !imeStepScheduled && imeInFlightKey == null && imeQueue.isEmpty()
        }

    /**
     * True only when this generation is accepting input and no physical,
     * virtual, pointer, gamepad, or IME-owned state is held or queued. Used by
     * the optional one-shot auto-login gate so it never overrides live input.
     */
    fun isNeutral(generation: Long): Boolean = synchronized(lock) {
        acceptingInput && generation == activeGeneration && !imeActive &&
            noHeldInputLocked() && !imeStepScheduled && imeInFlightKey == null && imeQueue.isEmpty()
    }

    /**
     * Atomically claim a neutral generation and enqueue the complete
     * username-Tab-password-Enter sequence. Mapping/capacity/lifecycle failure
     * injects zero events, so credentials can never be partially accepted.
     */
    fun queueSinglePlayerAutoLogin(
        username: String,
        password: String,
        generation: Long,
        loginWindowX: Int = 0,
        loginWindowY: Int = 0,
        loginWindowWidth: Int = 1920,
        loginWindowHeight: Int = 1080,
    ): Boolean {
        val mappedUsername = ImeCharMap.map(username)
        val mappedPassword = ImeCharMap.map(password)
        if (!mappedUsername.allAccepted || !mappedPassword.allAccepted ||
            mappedUsername.accepted.isEmpty() || mappedPassword.accepted.isEmpty()) return false
        return synchronized(lock) {
            if (!acceptLocked(generation) || imeActive || !noHeldInputLocked() ||
                imeStepScheduled || imeInFlightKey != null || imeQueue.isNotEmpty()) {
                return@synchronized false
            }
            val pulseCount = mappedUsername.accepted.size + mappedPassword.accepted.size + 3
            if (pulseCount > MAX_PENDING_IME_PULSES) return@synchronized false
            imeActive = true
            fun enqueueClick(xFraction: Float, yFraction: Float) {
                imeQueue.addLast(ImePulse(
                    keyCode = null,
                    gapAfterMs = fieldSettleMs,
                    pointerX = loginWindowX + (loginWindowWidth * xFraction).toInt(),
                    pointerY = loginWindowY + (loginWindowHeight * yFraction).toInt(),
                ))
            }
            fun enqueueMapped(result: ImeCharMap.ImeCommitResult) {
                result.accepted.forEach { mapped ->
                    val meta = if (mapped.shift) {
                        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                    } else 0
                    imeQueue.addLast(ImePulse(mapped.keyCode, meta, gapAfterMs = imeKeyGapMs))
                }
            }
            enqueueClick(0.50f, 0.523f)
            enqueueMapped(mappedUsername)
            enqueueClick(0.50f, 0.622f)
            enqueueMapped(mappedPassword)
            enqueueClick(0.50f, 0.702f)
            startImeQueueLocked(generation)
            true
        }
    }

    private fun noHeldInputLocked(): Boolean = sources.values.none { state ->
        state.keyOwners.isNotEmpty() || state.buttonOwners.isNotEmpty() || state.axisPressed.isNotEmpty() ||
            state.axisKeys.values.any { it != null } || state.cameraToggleOwners.isNotEmpty() ||
            state.rightStickX != 0f || state.rightStickY != 0f
    }

    private fun pendingImePulseCountLocked(): Int =
        imeQueue.size + if (imeInFlightKey == null) 0 else 1

    private fun startImeQueueLocked(generation: Long) {
        if (imeStepScheduled || imeQueue.isEmpty()) return
        imeStepScheduled = true
        beginImePulseLocked(imePulseEpoch, generation)
    }

    private fun beginImePulseLocked(epoch: Long, generation: Long) {
        if (epoch != imePulseEpoch || !acceptingInput || generation != activeGeneration || !imeActive) return
        val pulse = if (imeQueue.isEmpty()) null else imeQueue.removeFirst()
        if (pulse == null) {
            imeStepScheduled = false
            return
        }
        val state = sources.getOrPut(IME_SOURCE) { SourceState() }
        val pointerX = pulse.pointerX
        val pointerY = pulse.pointerY
        if (pointerX != null && pointerY != null) {
            sink.inject(SinkEvent.PointerMove(pointerX, pointerY))
            pointerButtonLocked(
                IME_SOURCE, ControlOwner.ImePulse, PointerButton.LEFT, pressed = true,
            )
            imeInFlightKey = IME_POINTER_IN_FLIGHT
            imeScheduler.postDelayed(pointerDwellMs) {
                synchronized(lock) {
                    if (epoch != imePulseEpoch || !acceptingInput ||
                        generation != activeGeneration || !imeActive) return@synchronized
                    val current = sources[IME_SOURCE]
                    pointerButtonLocked(
                        IME_SOURCE, ControlOwner.ImePulse, PointerButton.LEFT, pressed = false,
                    )
                    finishImePulseLocked(pulse, epoch, generation, current)
                }
            }
            return
        }
        val keyCode = requireNotNull(pulse.keyCode)
        val now = android.os.SystemClock.uptimeMillis()
        imeInFlightKey = keyCode
        acquireKeyLocked(
            state,
            ControlOwner.ImePulse,
            keyCode,
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, pulse.metaState),
        )
        imeScheduler.postDelayed(imeKeyDwellMs) {
            synchronized(lock) {
                if (epoch != imePulseEpoch || !acceptingInput || generation != activeGeneration || !imeActive) {
                    return@synchronized
                }
                val current = sources[IME_SOURCE]
                if (current != null) {
                    val releaseAt = android.os.SystemClock.uptimeMillis()
                    releaseKeyLocked(
                        current,
                        ControlOwner.ImePulse,
                        KeyEvent(releaseAt, releaseAt, KeyEvent.ACTION_UP, keyCode, 0, pulse.metaState),
                    )
                }
                finishImePulseLocked(pulse, epoch, generation, current)
            }
        }
    }

    private fun finishImePulseLocked(
        pulse: ImePulse,
        epoch: Long,
        generation: Long,
        current: SourceState?,
    ) {
        if (current != null && current.keyOwners.isEmpty() && current.buttonOwners.isEmpty()) {
            sources.remove(IME_SOURCE)
        }
        imeInFlightKey = null
        if (imeQueue.isEmpty()) {
            imeStepScheduled = false
        } else {
            imeScheduler.postDelayed(pulse.gapAfterMs) {
                synchronized(lock) {
                    if (epoch == imePulseEpoch && acceptingInput &&
                        generation == activeGeneration && imeActive) {
                        beginImePulseLocked(epoch, generation)
                    }
                }
            }
        }
    }

    private fun cancelImeQueueLocked() {
        imePulseEpoch++
        imeQueue.clear()
        imeStepScheduled = false
        imeInFlightKey = null
    }

    /**
     * The single deterministic exit path. Releases every genuinely held input
     * in a fixed order so logs and counts are reproducible:
     *
     * 1. controller-derived axes   (future; none in this increment)
     * 2. held controller buttons   (future; none in this increment)
     * 3. right mouse button
     * 4. middle mouse button
     * 5. left mouse button
     * 6. held keyboard keys in stable keycode order
     *
     * Wheel pulses are atomic and never retained, so they are never released
     * here. Only implemented categories are emitted.
     */
    fun releaseAll(reason: ReleaseReason): ReleaseReport = synchronized(lock) {
        releaseAllLocked(reason)
    }

    /**
     * Release only one device/source (e.g. on hot-plug removal). Must not touch
     * another source's held state.
     */
    fun releaseSource(src: Int, reason: ReleaseReason = ReleaseReason.DEVICE_REMOVED): ReleaseReport =
        synchronized(lock) {
            if (src == IME_SOURCE) cancelImeQueueLocked()
            val ownsCameraLock = cameraLockOwnerSource == src
            if (src !in sources && !ownsCameraLock) {
                return ReleaseReport(reason, emptyList(), 0, 0, rejectedStale.get())
            }
            var keys = 0
            var buttons = 0

            if (ownsCameraLock) {
                // Transfer RIGHT ownership before dropping the explicit lock.
                // Otherwise retiring the lock owner emits an UP/DOWN pair even
                // though another live right stick is already displaced.
                if (hasDisplacedRightStickSourceLocked(excluding = src) &&
                    !automaticGamepadCameraActive
                ) {
                    setAutomaticGamepadCameraLocked(true)
                }
                if (logicalButtonOwnerCounts[PointerButton.RIGHT] == 1) buttons++
                setCameraLockLocked(false, src)
            }

            if (src in sources) {
                val removed = removeSourceLocked(src)
                keys += removed.first
                buttons += removed.second
            }

            // Cleanup may retire an owner that had already activated automatic
            // camera mode, but it must never activate dormant ABS stick state.
            // Activation remains exclusively frame-pump driven (apart from the
            // seamless explicit-lock handoff above).
            if (automaticGamepadCameraActive && !hasDisplacedRightStickSourceLocked()) {
                if (logicalButtonOwnerCounts[PointerButton.RIGHT] == 1) buttons++
                setAutomaticGamepadCameraLocked(false)
            }
            releasedKeys.addAndGet(keys.toLong())
            releasedButtons.addAndGet(buttons.toLong())
            ReleaseReport(reason, listOf(src), keys, buttons, rejectedStale.get())
                .also { reportRef.set(it) }
        }

    /** Remove every exact owner for one source, returning actual final X releases. */
    private fun removeSourceLocked(src: Int): Pair<Int, Int> {
        val state = sources[src] ?: return 0 to 0
        var releasedButtonTransitions = 0
        state.buttonOwners.entries.toList()
            .sortedWith(compareByDescending<Map.Entry<ControlOwner, PointerButton>> { it.value.ordinal })
            .forEach { (owner, button) ->
                if (logicalButtonOwnerCounts[button] == 1) releasedButtonTransitions++
                check(pointerButtonLocked(src, owner, button, pressed = false)) {
                    "held pointer owner could not be released"
                }
            }
        var releasedKeyTransitions = 0
        state.keyOwners.entries.toList().sortedBy { it.value }.forEach { (owner, keyCode) ->
            if (logicalKeyOwnerCounts[keyCode] == 1) releasedKeyTransitions++
            check(releaseKeyLocked(state, owner)) { "held key owner could not be released" }
        }
        sources.remove(src)
        return releasedKeyTransitions to releasedButtonTransitions
    }

    private fun releaseAllLocked(reason: ReleaseReason): ReleaseReport {
        cancelImeQueueLocked()
        gamepadPointerFrameTimeNanos = 0L
        val all = sources.toMap()
        sources.clear()
        val wasCameraLocked = cameraLocked
        cameraLocked = false
        cameraLockOwnerSource = null
        automaticGamepadCameraActive = false
        val buttons = logicalButtonOwnerCounts.keys.toList().sortedWith(BUTTON_RELEASE_ORDER)
        for (button in buttons) sink.inject(SinkEvent.PointerButton(button.toSink(), pressed = false))
        val totalButtons = buttons.size
        val keys = logicalKeyOwnerCounts.keys.toList().sorted()
        for (key in keys) injectAndroidKey(key, pressed = false)
        val totalKeys = keys.size
        logicalButtonOwnerCounts.clear()
        logicalKeyOwnerCounts.clear()
        val report = ReleaseReport(reason, all.keys.toList().sorted(), totalKeys, totalButtons, rejectedStale.get())
        releasedKeys.addAndGet(totalKeys.toLong())
        releasedButtons.addAndGet(totalButtons.toLong())
        if (wasCameraLocked) onCameraLockChanged(false)
        reportRef.set(report)
        return report
    }

    // ---- internal ---------------------------------------------------------

    private fun keyLocked(
        src: Int,
        event: KeyEvent,
        logicalKeyCode: Int = event.keyCode,
        pressedOverride: Boolean? = null,
        owner: ControlOwner = ControlOwner.AndroidKey(event.keyCode),
    ): Boolean {
        val pressed = pressedOverride ?: (event.action == KeyEvent.ACTION_DOWN)
        val state = sources.getOrPut(src) { SourceState() }
        return if (pressed) {
            acquireKeyLocked(state, owner, logicalKeyCode, event)
        } else {
            releaseKeyLocked(state, owner, event)
        }
    }

    /** Emit only the first logical make across every exact physical owner. */
    private fun acquireKeyLocked(
        state: SourceState,
        owner: ControlOwner,
        logicalKeyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val existing = state.keyOwners[owner]
        if (existing != null) return existing == logicalKeyCode
        state.keyOwners[owner] = logicalKeyCode
        val count = logicalKeyOwnerCounts[logicalKeyCode] ?: 0
        logicalKeyOwnerCounts[logicalKeyCode] = count + 1
        if (count == 0) {
            sink.inject(SinkEvent.Key(event, logicalKeyCode = logicalKeyCode, pressed = true))
        }
        return true
    }

    /** Emit only the final logical break after the last exact owner releases. */
    private fun releaseKeyLocked(
        state: SourceState,
        owner: ControlOwner,
        event: KeyEvent? = null,
    ): Boolean {
        val logicalKeyCode = state.keyOwners.remove(owner) ?: return false
        val count = checkNotNull(logicalKeyOwnerCounts[logicalKeyCode]) {
            "logical key owner count is absent"
        }
        check(count > 0) { "logical key owner count underflow" }
        if (count == 1) {
            logicalKeyOwnerCounts.remove(logicalKeyCode)
            if (event != null) {
                sink.inject(SinkEvent.Key(event, logicalKeyCode = logicalKeyCode, pressed = false))
            } else {
                injectAndroidKey(logicalKeyCode, pressed = false)
            }
        } else {
            logicalKeyOwnerCounts[logicalKeyCode] = count - 1
        }
        return true
    }

    /**
     * Generation + ordering gate. Returns false (and increments the stale
     * counter) when the event's generation differs from the active one. Does NOT
     * reject legitimate batched Android events whose `eventTime` is older than a
     * prior batch — the contract does not key ordering on Android's
     * (non-monotonic) eventTime; it preserves per-source DOWN-before-UP via the
     * tracked pressed-state sets alone.
     */
    private fun acceptLocked(generation: Long): Boolean {
        if (!acceptingInput || generation != activeGeneration) {
            rejectedStale.incrementAndGet()
            return false
        }
        return true
    }

    /**
     * Release-only key injection. The forward path uses the real Android
     * [KeyEvent] via [key]; only [releaseAll] / [releaseSource] reach here,
     * where just the keycode is known. This matches the prior `releaseAll`
     * behavior exactly: `KeyEvent(ACTION_UP, keyCode)`.
     */
    private fun injectAndroidKey(keyCode: Int, pressed: Boolean) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        sink.inject(SinkEvent.Key(
            KeyEvent(action, keyCode),
            logicalKeyCode = keyCode,
            pressed = pressed,
        ))
    }

    private fun updateAxisKeyLocked(
        state: SourceState,
        axis: GamepadAxis,
        value: Float,
        deadZone: Float,
        negativeKey: Int?,
        positiveKey: Int?,
    ) {
        val next = when {
            value < -deadZone -> negativeKey
            value > deadZone -> positiveKey
            else -> null
        }
        val previous = state.axisKeys[axis]
        if (previous == next) return
        val owner = ControlOwner.Axis(axis)
        previous?.let {
            releaseKeyLocked(state, owner, KeyEvent(KeyEvent.ACTION_UP, it))
        }
        next?.let {
            acquireKeyLocked(state, owner, it, KeyEvent(KeyEvent.ACTION_DOWN, it))
        }
        state.axisKeys[axis] = next
    }

    private fun updateRelativeAxisStateLocked(
        state: SourceState,
        axis: GamepadAxis,
        value: Float,
        horizontal: Boolean,
    ) {
        var adjusted = applyDeadZone(value, profile.rightStickDeadZone)
        if (horizontal && profile.invertCameraX) adjusted = -adjusted
        if (!horizontal && profile.invertCameraY) adjusted = -adjusted
        if (adjusted == 0f) {
            // Do not let a fraction retained before the stick returned to
            // neutral become a delayed cursor movement on the next gesture.
            if (horizontal) state.relativeRemainderX = 0f else state.relativeRemainderY = 0f
        }
        if (horizontal) {
            state.rightStickX = adjusted
        } else {
            state.rightStickY = adjusted
        }
        // Keep the axis entry present without inventing a key release.
        state.axisKeys.putIfAbsent(axis, null)
    }

    /**
     * A controller right stick is camera input, not an absolute mouse. Hold
     * WoW's right button only while a stick is outside its dead zone so camera
     * look works without a separate lock/hold control. Manual right-button and
     * optional explicit camera-lock sources remain independently reference
     * counted by [pointerButtonLocked].
     */
    private fun syncAutomaticGamepadCameraLocked() {
        val shouldHold = !imeActive && hasDisplacedRightStickSourceLocked()
        if (shouldHold == automaticGamepadCameraActive) return
        setAutomaticGamepadCameraLocked(shouldHold)
    }

    private fun hasDisplacedRightStickSourceLocked(excluding: Int? = null): Boolean =
        sources.any { (source, state) ->
            source >= 0 && source != excluding &&
                (state.rightStickX != 0f || state.rightStickY != 0f)
        }

    private fun setAutomaticGamepadCameraLocked(enabled: Boolean) {
        if (enabled == automaticGamepadCameraActive) return
        automaticGamepadCameraActive = enabled
        pointerButtonLocked(
            AUTO_GAMEPAD_CAMERA_SOURCE,
            ControlOwner.AutomaticCamera,
            PointerButton.RIGHT,
            pressed = enabled,
        )
    }

    private fun pointerButtonLocked(
        src: Int,
        owner: ControlOwner,
        button: PointerButton,
        pressed: Boolean,
    ): Boolean {
        val state = sources.getOrPut(src) { SourceState() }
        if (pressed) {
            val existing = state.buttonOwners[owner]
            if (existing != null) return existing == button
            state.buttonOwners[owner] = button
            val count = logicalButtonOwnerCounts[button] ?: 0
            logicalButtonOwnerCounts[button] = count + 1
            if (count == 0) {
                sink.inject(SinkEvent.PointerButton(button.toSink(), pressed = true))
            }
        } else {
            val logical = state.buttonOwners.remove(owner) ?: return false
            check(logical == button) { "pointer owner changed logical button while held" }
            val count = checkNotNull(logicalButtonOwnerCounts[button]) {
                "logical pointer owner count is absent"
            }
            check(count > 0) { "logical pointer owner count underflow" }
            if (count == 1) {
                logicalButtonOwnerCounts.remove(button)
                sink.inject(SinkEvent.PointerButton(button.toSink(), pressed = false))
            } else {
                logicalButtonOwnerCounts[button] = count - 1
            }
        }
        return true
    }

    private fun setCameraLockLocked(enabled: Boolean, source: Int): Boolean {
        if (cameraLocked == enabled) return cameraLocked
        cameraLocked = enabled
        if (enabled) {
            cameraLockOwnerSource = source
            pointerButtonLocked(
                CAMERA_LOCK_SOURCE, ControlOwner.CameraLock, PointerButton.RIGHT, pressed = true,
            )
        } else {
            cameraLockOwnerSource = null
            pointerButtonLocked(
                CAMERA_LOCK_SOURCE, ControlOwner.CameraLock, PointerButton.RIGHT, pressed = false,
            )
        }
        onCameraLockChanged(cameraLocked)
        return cameraLocked
    }

    private fun gamepadActionLocked(
        src: Int,
        owner: ControlOwner,
        binding: GamepadBinding,
        pressed: Boolean,
    ): Boolean =
        when (binding) {
            is GamepadBinding.Key -> {
                if (imeActive) false else keyLocked(
                    src,
                    KeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, binding.keyCode),
                    logicalKeyCode = binding.keyCode,
                    pressedOverride = pressed,
                    owner = owner,
                )
            }
            is GamepadBinding.Pointer -> pointerButtonLocked(src, owner, binding.button, pressed)
            GamepadBinding.CameraLock -> if (imeActive) false else {
                val state = sources.getOrPut(src) { SourceState() }
                if (pressed) {
                    if (state.cameraToggleOwners.add(owner)) {
                        setCameraLockLocked(!cameraLocked, src)
                    }
                    true
                } else {
                    state.cameraToggleOwners.remove(owner)
                }
            }
        }

    private fun updateAxisActionLocked(
        src: Int,
        state: SourceState,
        axis: GamepadAxis,
        value: Float,
        onThreshold: Float,
        offThreshold: Float,
        binding: GamepadBinding?,
    ) {
        val wasPressed = axis in state.axisPressed
        val pressed = if (wasPressed) value > offThreshold else value >= onThreshold
        if (pressed == wasPressed) return
        if (pressed) state.axisPressed.add(axis) else state.axisPressed.remove(axis)
        binding?.let { gamepadActionLocked(src, ControlOwner.Axis(axis), it, pressed) }
    }

    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        val magnitude = kotlin.math.abs(value)
        if (magnitude <= deadZone) return 0f
        return kotlin.math.sign(value) * ((magnitude - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
    }

    private fun PointerButton.toSink(): SinkButton = when (this) {
        PointerButton.LEFT -> SinkButton.LEFT
        PointerButton.MIDDLE -> SinkButton.MIDDLE
        PointerButton.RIGHT -> SinkButton.RIGHT
    }

    companion object {
        /**
         * Fixed release order: right, middle, left (matches [releaseAll] doc).
         * Enum ordinals are LEFT=0, MIDDLE=1, RIGHT=2, so descending ordinal
         * yields RIGHT, MIDDLE, LEFT.
         */
        private val BUTTON_RELEASE_ORDER: Comparator<PointerButton> =
            compareByDescending<PointerButton> { it.ordinal }

        /** Synthetic source id for IME-committed text injection. */
        internal const val IME_SOURCE: Int = -2
        internal const val IME_KEY_DWELL_MS: Long = 50L
        internal const val IME_KEY_GAP_MS: Long = 10L
        internal const val AUTO_LOGIN_FIELD_SETTLE_MS: Long = 300L
        internal const val IME_POINTER_DWELL_MS: Long = 80L
        private const val IME_POINTER_IN_FLIGHT: Int = -3
        internal const val CAMERA_LOCK_SOURCE: Int = -4
        internal const val AUTO_GAMEPAD_CAMERA_SOURCE: Int = -5
        internal const val MAX_PENDING_IME_PULSES: Int = 256
        private const val GAMEPAD_CAMERA_PIXELS_PER_SECOND = 8f * 60f
        private const val MAX_GAMEPAD_FRAME_NANOS = 50_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        private sealed class GamepadBinding {
            data class Key(val keyCode: Int) : GamepadBinding()
            data class Pointer(val button: PointerButton) : GamepadBinding()
            data object CameraLock : GamepadBinding()
        }

        private fun actionBinding(action: ControllerAction): GamepadBinding? {
            action.keyCode?.let { return GamepadBinding.Key(it) }
            action.pointer?.let { pointer ->
                return GamepadBinding.Pointer(
                    if (pointer == ControlPointer.LEFT) PointerButton.LEFT else PointerButton.RIGHT,
                )
            }
            return if (action.cameraLockToggle) GamepadBinding.CameraLock else null
        }

        private fun gamepadBinding(
            keyCode: Int,
            layout: GamepadLayout,
            profile: InputProfile,
        ): GamepadBinding? {
            if (layout == GamepadLayout.DISABLED) return null
            if (layout == GamepadLayout.RETROID_POCKET_6 || layout == GamepadLayout.PROFILED) {
                val faceLayout = if (
                    layout == GamepadLayout.RETROID_POCKET_6 &&
                    profile.controllerFamily == ControllerFamily.AUTO
                ) FaceButtonLayout.RP6_PRINTED else profile.faceButtonLayout
                val control = faceControlForKeyCode(keyCode, faceLayout) ?: when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> Rp6Control.DPAD_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> Rp6Control.DPAD_LEFT
                    KeyEvent.KEYCODE_DPAD_UP -> Rp6Control.DPAD_UP
                    KeyEvent.KEYCODE_DPAD_RIGHT -> Rp6Control.DPAD_RIGHT
                    KeyEvent.KEYCODE_BUTTON_R1 -> Rp6Control.R1
                    KeyEvent.KEYCODE_BUTTON_L1 -> Rp6Control.L1
                    KeyEvent.KEYCODE_BUTTON_L2 -> Rp6Control.L2
                    KeyEvent.KEYCODE_BUTTON_R2 -> Rp6Control.R2
                    KeyEvent.KEYCODE_BUTTON_START -> Rp6Control.START
                    KeyEvent.KEYCODE_BUTTON_SELECT -> Rp6Control.SELECT
                    KeyEvent.KEYCODE_BUTTON_THUMBL -> Rp6Control.L3
                    KeyEvent.KEYCODE_BUTTON_THUMBR -> Rp6Control.R3
                    KeyEvent.KEYCODE_BUTTON_C -> Rp6Control.REAR_LEFT
                    KeyEvent.KEYCODE_BUTTON_Z -> Rp6Control.REAR_RIGHT
                    else -> null
                } ?: return null
                return actionBinding(InputProfile.actionFor(profile, control))
            }
            return logicalGamepadKey(keyCode)?.let(GamepadBinding::Key)
        }

        internal fun faceControlForKeyCode(keyCode: Int, layout: FaceButtonLayout): Rp6Control? =
            when (layout) {
                FaceButtonLayout.ANDROID_STANDARD -> when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> Rp6Control.FACE_BOTTOM
                    KeyEvent.KEYCODE_BUTTON_X -> Rp6Control.FACE_LEFT
                    KeyEvent.KEYCODE_BUTTON_Y -> Rp6Control.FACE_TOP
                    KeyEvent.KEYCODE_BUTTON_B -> Rp6Control.FACE_RIGHT
                    else -> null
                }
                FaceButtonLayout.RP6_PRINTED -> when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> Rp6Control.FACE_RIGHT
                    KeyEvent.KEYCODE_BUTTON_B -> Rp6Control.FACE_BOTTOM
                    KeyEvent.KEYCODE_BUTTON_Y -> Rp6Control.FACE_LEFT
                    KeyEvent.KEYCODE_BUTTON_X -> Rp6Control.FACE_TOP
                    else -> null
                }
            }

        internal fun logicalGamepadKey(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_SPACE
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_ESCAPE
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_2
            KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_Q
            KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_E
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_ESCAPE
            else -> null
        }
    }
}
