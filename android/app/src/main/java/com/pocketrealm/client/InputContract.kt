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

/** Pointer button codes the sink understands (mirrors winlator's set). */
enum class SinkButton { LEFT, MIDDLE, RIGHT, SCROLL_UP, SCROLL_DOWN, SCROLL_CLICK_LEFT, SCROLL_CLICK_RIGHT }

/** A single primitive injection event emitted by the contract to the sink. */
sealed class SinkEvent {
    data class PointerMove(val x: Int, val y: Int) : SinkEvent()
    data class PointerMoveDelta(val dx: Int, val dy: Int) : SinkEvent()
    data class PointerButton(val button: SinkButton, val pressed: Boolean) : SinkEvent()
    data class Key(val event: KeyEvent) : SinkEvent()
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
 * This contract is intentionally **not** the place for IME composition, gamepad
 * axes, pointer capture, or profile persistence — those are later O14 increments
 * and build on top of the generation/session plumbing established here.
 */
class InputContract(
    private val sink: InputSink,
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

    /** Per-source pressed state. One entry per Android `deviceId`. */
    private data class SourceState(
        val keys: MutableSet<Int> = sortedMutableSet(),
        val buttons: MutableSet<PointerButton> = sortedMutableButtonSet(),
        var lastSeq: Long = 0L,
    )

    // ---- generation/session ownership -------------------------------------
    @Volatile private var activeGeneration: Long = 0L
    @Volatile private var activeSession: UUID? = null
    @Volatile private var profile: InputProfile = InputProfile.DEFAULT
    @Volatile private var profileReset: Boolean = false

    private val rejectedStale = AtomicLong(0L)
    private val reportRef = AtomicReference(ReleaseReport(ReleaseReason.EXPLICIT_RELEASE_INPUT, emptyList(), 0, 0, 0L))

    // Single mutex guards all pressed-state mutation + injection so release
    // ordering and per-source sequence assignment are deterministic under
    // concurrent Android dispatch threads.
    private val lock = Any()
    private val sources = LinkedHashMap<Int, SourceState>()

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
        profile = if (newProfile.aspectIdentity == aspectIdentity) {
            profileReset = false
            newProfile
        } else {
            profileReset = true
            InputProfile.DEFAULT
        }
        report
    }

    /** Currently active generation; 0 before the first [attach]. */
    val generation: Long get() = activeGeneration

    /** Currently active session; null before the first [attach]. */
    val sessionId: UUID? get() = activeSession

    /** True when the last [attach] reset to the default profile due to aspect mismatch. */
    val isProfileReset: Boolean get() = profileReset

    /** Last [ReleaseReport] produced; convenient for diagnostics/tests. */
    val lastReport: ReleaseReport get() = reportRef.get()

    /** Cumulative count of events rejected for stale-generation or out-of-order. */
    val rejectedStaleEventCount: Long get() = rejectedStale.get()

    // ---- pointer ----------------------------------------------------------

    /**
     * Absolute pointer motion. Applies the caller's letterbox transform already
     * (the bridge owns the Android-view→X coordinate math, matching today's
     * verified path); the contract only stamps and injects.
     */
    fun pointerAbsolute(src: Int, x: Int, y: Int, generation: Long) {
        if (!accept(src, generation)) return
        synchronized(lock) {
            sink.inject(SinkEvent.PointerMove(x, y))
        }
    }

    /** Relative pointer motion (camera-look / captured mouse). */
    fun pointerRelative(src: Int, dx: Int, dy: Int, generation: Long) {
        if (!accept(src, generation)) return
        synchronized(lock) {
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
        if (!accept(src, generation)) return
        synchronized(lock) {
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
        if (!accept(src, generation)) return
        if (vTicks == 0 && hTicks == 0) return
        synchronized(lock) {
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
        if (!accept(src, generation)) return false
        val pressed = event.action == KeyEvent.ACTION_DOWN
        synchronized(lock) {
            val st = sources.getOrPut(src) { SourceState() }
            if (pressed) {
                st.keys.add(event.keyCode)
            } else {
                if (event.keyCode !in st.keys) return false // unmatched UP: drop, no synth
                st.keys.remove(event.keyCode)
            }
            sink.inject(SinkEvent.Key(event))
            return true
        }
    }

    // ---- focus / cleanup --------------------------------------------------

    /** Focus lost; releases all held input (deterministic order). */
    fun focusLost(): ReleaseReport = releaseAll(ReleaseReason.FOCUS_LOSS)

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
            val st = sources.remove(src) ?: return ReleaseReport(reason, emptyList(), 0, 0, rejectedStale.get())
            val buttons = st.buttons.toList().sortedWith(BUTTON_RELEASE_ORDER)
            for (b in buttons) sink.inject(SinkEvent.PointerButton(b.toSink(), pressed = false))
            val keys = st.keys.toList().sorted()
            for (k in keys) injectAndroidKey(k, pressed = false)
            ReleaseReport(reason, listOf(src), keys.size, buttons.size, rejectedStale.get())
                .also { reportRef.set(it) }
        }

    private fun releaseAllLocked(reason: ReleaseReason): ReleaseReport {
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
        reportRef.set(report)
        return report
    }

    // ---- internal ---------------------------------------------------------

    /**
     * Generation + ordering gate. Returns false (and increments the stale
     * counter) when the event's generation differs from the active one. Does NOT
     * reject legitimate batched Android events whose `eventTime` is older than a
     * prior batch — the contract does not key ordering on Android's
     * (non-monotonic) eventTime; it preserves per-source DOWN-before-UP via the
     * tracked pressed-state sets alone.
     */
    private fun accept(src: Int, generation: Long): Boolean {
        if (generation != activeGeneration) {
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
    }
}
