package com.pocketrealm.client

/** A bounded scheduler used by the display main loop and deterministic tests. */
internal interface AutoLoginScheduler {
    fun nowMs(): Long
    fun postDelayed(delayMs: Long, action: () -> Unit)
}

internal data class AutoLoginWindow(
    val name: String,
    val className: String,
    val width: Int,
    val height: Int,
    val processId: Int,
    val renderable: Boolean,
    val topLevel: Boolean,
    val desktop: Boolean = false,
)

internal enum class AutoLoginWindowLane { X86_DIRECT, ARM_TRANSLATED }

/** Secret-bearing value with a deliberately redacted string representation. */
class SinglePlayerAutoLoginCredentials internal constructor(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "SinglePlayerAutoLoginCredentials(<redacted>)"
}

/**
 * Exact mapped topology accepted for the qualified build-5875 login window.
 * The x86 lane publishes a blank-title `wow.exe` top-level plus a same-sized
 * anonymous backing window. The current ARM Winlator transport omits WM_CLASS
 * and _NET_WM_PID, so it is accepted only as one anonymous 800x600 top-level;
 * the caller separately gates this matcher to the production build id.
 */
internal object Build5875LoginTopology {
    fun matches(
        windows: List<AutoLoginWindow>,
        lane: AutoLoginWindowLane,
        expectedWidth: Int = 1920,
        expectedHeight: Int = 1080,
    ): Boolean {
        val large = windows.filter {
            it.renderable && it.topLevel && it.width >= 640 && it.height >= 480
        }
        return when (lane) {
            AutoLoginWindowLane.X86_DIRECT -> {
                val clients = large.filter {
                    it.name.isEmpty() && it.className.equals("wow.exe", ignoreCase = true) &&
                        it.processId > 0 && it.width in 640..1280 && it.height in 480..720
                }
                clients.size == 1 && large.size == 2 && large.any {
                    it !== clients.single() && it.name.isEmpty() && it.className.isEmpty() &&
                        it.processId == 0 && it.width == clients.single().width &&
                        it.height == clients.single().height
                }
            }
            AutoLoginWindowLane.ARM_TRANSLATED -> {
                val mapped = windows.filter { it.renderable }
                val topLevel = mapped.filter { it.topLevel }
                // Winlator may expose the same render surface twice: the
                // actual non-desktop top-level and a same-sized composited
                // child.  The child is not a second login target. Prefer the
                // unique game top-level, while retaining the legacy FEX shape
                // where the game is the sole exact-size child of explorer.
                val topTargets = topLevel.filter {
                    !it.desktop && it.width == expectedWidth && it.height == expectedHeight
                }
                val nestedTargets = mapped.filter {
                    !it.topLevel && !it.desktop &&
                        it.width == expectedWidth && it.height == expectedHeight
                }
                val target = when {
                    topTargets.size == 1 -> topTargets.single()
                    topTargets.isEmpty() && nestedTargets.size == 1 -> nestedTargets.single()
                    else -> return false
                }
                val desktops = topLevel.filter {
                    it.desktop && it.width == expectedWidth && it.height == expectedHeight
                }
                // ARM Winlator metadata (WM_NAME/WM_CLASS/_NET_WM_PID) is not
                // stable across Wine/DXVK revisions. Bind to a separate game
                // drawable, never explorer's virtual desktop. The legacy ARM
                // shape has the game at the root; the FEX shape has one exact
                // virtual desktop and an exact-size game child. Any other
                // non-tiny renderable window is a modal/decoy and cancels.
                val validContainer = if (target.topLevel) desktops.isEmpty() else desktops.size == 1
                validContainer && mapped.all {
                    it === target || it in desktops || !it.topLevel ||
                        (it.width <= 16 && it.height <= 16)
                }
            }
        }
    }
}

internal interface AutoLoginBridge {
    fun isActive(generation: Long): Boolean
    fun isRendererReady(): Boolean
    fun isInputNeutral(generation: Long): Boolean
    fun mappedWindows(): List<AutoLoginWindow>
    fun queueCredentials(username: String, password: String, generation: Long): Boolean
    fun isCredentialQueueIdle(): Boolean
    fun cancelCredentialQueue(generation: Long)
}

/**
 * One-shot, generation-owned single-player login. Credential strings are
 * deliberately plain constructor parameters rather than a data class so no
 * generated toString/copy path can surface them in logs or assertion output.
 */
internal class SinglePlayerAutoLoginController(
    username: String,
    password: String,
    private val generation: Long,
    private val lane: AutoLoginWindowLane,
    private val expectedWidth: Int = 1920,
    private val expectedHeight: Int = 1080,
    private val bridge: AutoLoginBridge,
    private val scheduler: AutoLoginScheduler,
    private val onDiagnostic: (String) -> Unit = {},
    private val onTerminal: () -> Unit = {},
) {
    internal enum class State { NEW, WATCHING, INJECTING, COMPLETE, CANCELLED }

    private var epoch = 0L
    private var deadlineMs = 0L
    private var stableMatches = 0
    private var topologyReadySinceMs: Long? = null
    private var username: String? = username
    private var password: String? = password
    private var lastDiagnostic = ""
    @Volatile internal var state: State = State.NEW
        private set

    @Synchronized
    fun start(initialTopology: List<AutoLoginWindow> = bridge.mappedWindows()) {
        if (state != State.NEW) return
        state = State.WATCHING
        // Start the bounded secret lifetime with the host, not with Wine's
        // first window. A failed runtime must not retain credentials forever.
        deadlineMs = scheduler.nowMs() + SESSION_TIMEOUT_MS
        if (!topologyMatches(initialTopology)) resetTopologyReadiness()
        diagnostic("watching")
        schedulePoll(0)
    }

    @Synchronized
    fun onTopologyChanged(topology: List<AutoLoginWindow> = bridge.mappedWindows()) {
        when (state) {
            State.NEW -> {
                start(topology)
            }
            // This is the immutable map/unmap snapshot captured by the X
            // thread. A short-lived modal therefore resets the continuous
            // gate even if it disappears before the next scheduled poll.
            // Non-top-level D3D child churn still matches and remains neutral.
            State.WATCHING -> if (!topologyMatches(topology)) {
                resetTopologyReadiness()
                diagnostic("waiting:qualified-topology")
            }
            State.INJECTING -> {
                if (!topologyMatches(topology)) cancel()
            }
            else -> Unit
        }
    }

    @Synchronized
    fun cancel() {
        if (state == State.COMPLETE || state == State.CANCELLED) return
        val wasInjecting = state == State.INJECTING
        state = State.CANCELLED
        epoch++
        if (wasInjecting) bridge.cancelCredentialQueue(generation)
        dropSecrets()
        onTerminal()
    }

    private fun schedulePoll(delayMs: Long) {
        val scheduledEpoch = epoch
        scheduler.postDelayed(delayMs) {
            if (scheduledEpoch == epoch) poll()
        }
    }

    @Synchronized
    private fun poll() {
        if (state != State.WATCHING) return
        if (!bridge.isActive(generation) || scheduler.nowMs() >= deadlineMs) {
            diagnostic("cancelled:inactive-or-session-timeout")
            cancel()
            return
        }
        val rendererReady = bridge.isRendererReady()
        val inputNeutral = bridge.isInputNeutral(generation)
        val topologyMatches = topologyMatches()
        if (!rendererReady || !inputNeutral || !topologyMatches) {
            resetTopologyReadiness()
            diagnostic(when {
                !rendererReady -> "waiting:renderer"
                !inputNeutral -> "waiting:input-neutral"
                else -> "waiting:qualified-topology"
            })
            schedulePoll(POLL_MS)
            return
        }
        val readySinceMs = topologyReadySinceMs ?: scheduler.nowMs().also {
            topologyReadySinceMs = it
        }
        stableMatches++
        diagnostic("settling:qualified-topology")
        if (stableMatches < REQUIRED_STABLE_POLLS ||
            scheduler.nowMs() - readySinceMs < LOGIN_UI_SETTLE_MS) {
            schedulePoll(POLL_MS)
            return
        }
        // Re-read every mutable gate immediately before the one-shot claim.
        // The window may have mapped long before the login form is interactive.
        if (!bridge.isActive(generation) || !bridge.isRendererReady() ||
            !bridge.isInputNeutral(generation) || !topologyMatches()) {
            resetTopologyReadiness()
            schedulePoll(POLL_MS)
            return
        }
        // Claim the generation's sole attempt before any key is queued.
        state = State.INJECTING
        diagnostic("injecting")
        val queuedUsername = username
        val queuedPassword = password
        if (queuedUsername == null || queuedPassword == null ||
            !bridge.queueCredentials(queuedUsername, queuedPassword, generation)) {
            bridge.cancelCredentialQueue(generation)
            state = State.CANCELLED
            diagnostic("cancelled:queue-rejected")
            epoch++
            dropSecrets()
            onTerminal()
            return
        }
        deadlineMs = scheduler.nowMs() + INPUT_DRAIN_TIMEOUT_MS
        scheduleDrainCheck()
    }

    private fun scheduleDrainCheck() {
        val scheduledEpoch = epoch
        scheduler.postDelayed(DRAIN_POLL_MS) {
            drainPoll(scheduledEpoch)
        }
    }

    @Synchronized
    private fun drainPoll(scheduledEpoch: Long) {
        if (scheduledEpoch != epoch || state != State.INJECTING) return
        if (!bridge.isActive(generation) || !topologyMatches() || scheduler.nowMs() > deadlineMs) {
            cancel()
        } else if (bridge.isCredentialQueueIdle()) {
            bridge.cancelCredentialQueue(generation)
            state = State.COMPLETE
            diagnostic("complete")
            epoch++
            dropSecrets()
            onTerminal()
        } else {
            scheduleDrainCheck()
        }
    }

    private fun dropSecrets() {
        username = null
        password = null
    }

    private fun resetTopologyReadiness() {
        stableMatches = 0
        topologyReadySinceMs = null
    }

    private fun diagnostic(value: String) {
        if (lastDiagnostic == value) return
        lastDiagnostic = value
        onDiagnostic(value)
    }

    private fun topologyMatches(): Boolean =
        topologyMatches(bridge.mappedWindows())

    private fun topologyMatches(windows: List<AutoLoginWindow>): Boolean =
        Build5875LoginTopology.matches(
            windows, lane, expectedWidth, expectedHeight,
        )

    companion object {
        private const val POLL_MS = 250L
        private const val REQUIRED_STABLE_POLLS = 4
        private const val LOGIN_UI_SETTLE_MS = 8_000L
        private const val SESSION_TIMEOUT_MS = 5 * 60_000L
        private const val DRAIN_POLL_MS = 50L
        private const val INPUT_DRAIN_TIMEOUT_MS = 5_000L
    }
}
