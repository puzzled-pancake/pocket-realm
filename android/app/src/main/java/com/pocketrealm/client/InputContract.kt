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
    enum class GamepadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y }

    /** Per-source pressed state. One entry per Android `deviceId`. */
    private data class SourceState(
        val keys: MutableSet<Int> = sortedMutableSet(),
        val buttons: MutableSet<PointerButton> = sortedMutableButtonSet(),
        val axisKeys: MutableMap<GamepadAxis, Int?> = java.util.EnumMap(GamepadAxis::class.java),
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
    private data class ImePulse(val keyCode: Int, val metaState: Int)
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
            if (imeActive) return
            sink.inject(SinkEvent.PointerMove(x, y))
        }
    }

    /** Relative pointer motion (camera-look / captured mouse). */
    fun pointerRelative(src: Int, dx: Int, dy: Int, generation: Long) {
        synchronized(lock) {
            if (!acceptLocked(generation)) return
            if (imeActive) return
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
            if (imeActive) return
            val st = sources.getOrPut(src) { SourceState() }
            if (pressed) {
                if (button !in st.buttons) {
                    st.buttons.add(button)
                    sink.inject(SinkEvent.PointerButton(button.toSink(), pressed = true))
                }
            } else {
                if (button in st.buttons) {
                    st.buttons.remove(button)
                    sink.inject(SinkEvent.PointerButton(button.toSink(), pressed = false))
                }
                // Unmatched UP: idempotent cleanup. Do NOT synthesize a DOWN.
            }
        }
    }

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
            if (imeActive) return
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
     * key still forwards the event (Android may repeat) but does not duplicate
     * the tracked entry. Returns the sink's boolean result (the production sink
     * returns `keyboard.onKeyEvent`'s result).
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
    fun gamepadButton(src: Int, keyCode: Int, pressed: Boolean, generation: Long): Boolean {
        val logical = logicalGamepadKey(keyCode) ?: return false
        return synchronized(lock) {
            if (!acceptLocked(generation) || imeActive) return@synchronized false
            keyLocked(src, KeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, logical))
        }
    }

    /**
     * Gamepad axis translation with dead-zone hysteresis. Left-stick axes
     * synthesize WASD transitions; right-stick axes become relative camera
     * deltas. Returning false means the event was stale or intentionally
     * neutral/no-op.
     */
    fun gamepadAxis(src: Int, axis: GamepadAxis, value: Float, generation: Long): Boolean {
        synchronized(lock) {
            if (!acceptLocked(generation)) return false
            if (imeActive) return false
            val bounded = value.coerceIn(-1f, 1f)
            val st = sources.getOrPut(src) { SourceState() }
            val deadZone = profile.deadZone
            when (axis) {
                GamepadAxis.LEFT_X -> updateAxisKeyLocked(st, axis, bounded, deadZone, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
                GamepadAxis.LEFT_Y -> updateAxisKeyLocked(st, axis, bounded, deadZone, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S)
                GamepadAxis.RIGHT_X -> injectRelativeAxisLocked(st, axis, bounded, profile.cameraSensitivity, horizontal = true)
                GamepadAxis.RIGHT_Y -> injectRelativeAxisLocked(st, axis, bounded, profile.cameraSensitivity, horizontal = false)
            }
            return true
        }
    }

    // ---- focus / cleanup --------------------------------------------------

    /** Focus lost; releases all held input (deterministic order). */
    fun focusLost(): ReleaseReport = releaseAll(ReleaseReason.FOCUS_LOSS)

    // ---- IME (increment 2) ------------------------------------------------

    /**
     * The IME is opening. Per report §16.8, this releases held movement/gameplay
     * keys and pointer buttons, suspends camera-capture mode, and emits a bounded
     * [ReleaseReport]. The input is left in a neutral state; previously held
     * keys are NOT restored on [imeClosed].
     *
     * @param generation the active generation; stale = no-op
     * @return the release report for what was held, or a zero report if stale
     */
    fun imeOpened(generation: Long): ReleaseReport = synchronized(lock) {
        if (!acceptLocked(generation)) {
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
        val state = sources.remove(IME_SOURCE)
        val keys = state?.keys?.toList()?.sorted().orEmpty()
        for (key in keys) injectAndroidKey(key, pressed = false)
        releasedKeys.addAndGet(keys.size.toLong())
    }

    /** True while the IME is open (movement keys released, camera suspended). */
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
                imeQueue.addLast(ImePulse(m.keyCode, meta))
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
            repeat(toDelete) { imeQueue.addLast(ImePulse(KeyEvent.KEYCODE_DEL, 0)) }
            startImeQueueLocked(generation)
            toDelete
        }
    }

    /** Queue one IME-owned key (for example Enter) behind pending text. */
    fun imeKeyTap(keyCode: Int, generation: Long): Boolean {
        return synchronized(lock) {
            if (!acceptLocked(generation) || !imeActive) return@synchronized false
            if (pendingImePulseCountLocked() >= MAX_PENDING_IME_PULSES) return@synchronized false
            imeQueue.addLast(ImePulse(keyCode, 0))
            startImeQueueLocked(generation)
            true
        }
    }

    /** True when no committed-text, delete, or editor-action pulse is pending. */
    val isImeInputIdle: Boolean
        get() = synchronized(lock) {
            !imeStepScheduled && imeInFlightKey == null && imeQueue.isEmpty()
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
        val now = android.os.SystemClock.uptimeMillis()
        val state = sources.getOrPut(IME_SOURCE) { SourceState() }
        state.keys.add(pulse.keyCode)
        imeInFlightKey = pulse.keyCode
        sink.inject(SinkEvent.Key(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, pulse.keyCode, 0, pulse.metaState),
            logicalKeyCode = pulse.keyCode,
            pressed = true,
        ))
        imeScheduler.postDelayed(IME_KEY_DWELL_MS) {
            synchronized(lock) {
                if (epoch != imePulseEpoch || !acceptingInput || generation != activeGeneration || !imeActive) {
                    return@synchronized
                }
                val current = sources[IME_SOURCE]
                if (current?.keys?.remove(pulse.keyCode) == true) {
                    val releaseAt = android.os.SystemClock.uptimeMillis()
                    sink.inject(SinkEvent.Key(
                        KeyEvent(releaseAt, releaseAt, KeyEvent.ACTION_UP, pulse.keyCode, 0, pulse.metaState),
                        logicalKeyCode = pulse.keyCode,
                        pressed = false,
                    ))
                }
                if (current != null && current.keys.isEmpty() && current.buttons.isEmpty()) {
                    sources.remove(IME_SOURCE)
                }
                imeInFlightKey = null
                if (imeQueue.isEmpty()) {
                    imeStepScheduled = false
                } else {
                    imeScheduler.postDelayed(IME_KEY_GAP_MS) {
                        synchronized(lock) {
                            if (epoch == imePulseEpoch && acceptingInput &&
                                generation == activeGeneration && imeActive) {
                                beginImePulseLocked(epoch, generation)
                            }
                        }
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
            val st = sources.remove(src) ?: return ReleaseReport(reason, emptyList(), 0, 0, rejectedStale.get())
            val buttons = st.buttons.toList().sortedWith(BUTTON_RELEASE_ORDER)
            for (b in buttons) sink.inject(SinkEvent.PointerButton(b.toSink(), pressed = false))
            val keys = st.keys.toList().sorted()
            for (k in keys) injectAndroidKey(k, pressed = false)
            releasedKeys.addAndGet(keys.size.toLong())
            releasedButtons.addAndGet(buttons.size.toLong())
            ReleaseReport(reason, listOf(src), keys.size, buttons.size, rejectedStale.get())
                .also { reportRef.set(it) }
        }

    private fun releaseAllLocked(reason: ReleaseReason): ReleaseReport {
        cancelImeQueueLocked()
        val all = sources.toMap()
        sources.clear()
        var totalKeys = 0
        var totalButtons = 0
        // Release each source in deviceId order; within a source, the fixed
        // button order (right, middle, left) then keys in keycode order.
        for ((_, st) in all.toSortedMap()) {
            val buttons = st.buttons.toList().sortedWith(BUTTON_RELEASE_ORDER)
            for (b in buttons) sink.inject(SinkEvent.PointerButton(b.toSink(), pressed = false))
            totalButtons += buttons.size
            val keys = st.keys.toList().sorted()
            for (k in keys) injectAndroidKey(k, pressed = false)
            totalKeys += keys.size
        }
        val report = ReleaseReport(reason, all.keys.toList().sorted(), totalKeys, totalButtons, rejectedStale.get())
        releasedKeys.addAndGet(totalKeys.toLong())
        releasedButtons.addAndGet(totalButtons.toLong())
        reportRef.set(report)
        return report
    }

    // ---- internal ---------------------------------------------------------

    private fun keyLocked(src: Int, event: KeyEvent): Boolean {
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val state = sources.getOrPut(src) { SourceState() }
        if (pressed) {
            state.keys.add(event.keyCode)
        } else {
            if (event.keyCode !in state.keys) return false
            state.keys.remove(event.keyCode)
        }
        sink.inject(SinkEvent.Key(event))
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
        negativeKey: Int,
        positiveKey: Int,
    ) {
        val next = when {
            value < -deadZone -> negativeKey
            value > deadZone -> positiveKey
            else -> null
        }
        val previous = state.axisKeys[axis]
        if (previous == next) return
        previous?.let { emitSyntheticKeyLocked(state, it, KeyEvent.ACTION_UP) }
        next?.let { emitSyntheticKeyLocked(state, it, KeyEvent.ACTION_DOWN) }
        state.axisKeys[axis] = next
    }

    private fun injectRelativeAxisLocked(
        state: SourceState,
        axis: GamepadAxis,
        value: Float,
        sensitivity: Float,
        horizontal: Boolean,
    ) {
        val delta = (value * sensitivity * GAMEPAD_CAMERA_SCALE).toInt()
        if (delta == 0) return
        // Keep the axis entry present so releaseSource/releaseAll can clear a
        // future extension without inventing a key release for relative motion.
        state.axisKeys.putIfAbsent(axis, null)
        if (horizontal) sink.inject(SinkEvent.PointerMoveDelta(delta, 0))
        else sink.inject(SinkEvent.PointerMoveDelta(0, delta))
    }

    private fun emitSyntheticKeyLocked(state: SourceState, keyCode: Int, action: Int) {
        if (action == KeyEvent.ACTION_DOWN) state.keys.add(keyCode) else state.keys.remove(keyCode)
        sink.inject(SinkEvent.Key(KeyEvent(action, keyCode)))
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

        private fun sortedMutableSet(): MutableSet<Int> = java.util.TreeSet()
        private fun sortedMutableButtonSet(): MutableSet<PointerButton> =
            java.util.TreeSet(compareByDescending { it.ordinal })

        /** Synthetic source id for IME-committed text injection. */
        internal const val IME_SOURCE: Int = -2
        internal const val IME_KEY_DWELL_MS: Long = 50L
        internal const val IME_KEY_GAP_MS: Long = 10L
        internal const val MAX_PENDING_IME_PULSES: Int = 256
        private const val GAMEPAD_CAMERA_SCALE = 8f

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
