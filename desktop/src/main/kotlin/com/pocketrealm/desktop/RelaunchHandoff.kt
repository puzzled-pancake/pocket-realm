package com.pocketrealm.desktop

import java.lang.ProcessBuilder
import java.lang.ProcessHandle

/**
 * The relaunch handoff contract (round-1's close-vs-relaunch race, shared
 * by BOTH spawn-first paths): a process that starts its successor BEFORE
 * draining — the spent-world relaunch (DesktopAppRelauncher) and the
 * updater's restart-into-update (UpdateApply.relaunchAndExit) — stamps the
 * handoff env var naming the predecessor pid. The fresh instance's entry
 * (Main) then waits a bounded window for the single-instance lock instead
 * of refusing with the "already running" dialog: the predecessor's drain
 * (world save + component stops) is bounded by the supervisor's stop
 * budgets and routinely outlasts a fresh JVM+Compose boot.
 *
 * A plain double-launch carries no env var and refuses instantly — the
 * wait is reserved for the spawn-first handoff.
 */
internal object RelaunchHandoff {
    const val ENV = "POCKETREALM_RELAUNCH_HANDOFF_PID"

    /** The bounded wait window: the predecessor's drain (save control
     * timeout + the component stop budgets) bounds the handoff. */
    const val WAIT_MS = 120_000L
    const val POLL_MS = 500L

    /** Stamp [builder] with the handoff marker: THIS process is the
     * draining predecessor of whatever it spawns. */
    fun stamp(builder: ProcessBuilder): ProcessBuilder = builder.apply {
        environment()[ENV] = ProcessHandle.current().pid().toString()
    }

    /** True when this process was itself relaunched (env marker present). */
    fun isHandoffLaunch(): Boolean = System.getenv(ENV) != null

    /**
     * The fresh instance's bounded wait: re-attempt [factory] (constructing
     * the app model — a held lock throws before any runtime seam exists, so
     * a failed attempt constructs nothing) until it succeeds or the window
     * closes. Only [held] failures are retried; anything else propagates
     * honestly. Null on timeout. [sleeper]/[now] injectable for tests.
     */
    fun <T> waitFor(
        factory: () -> T,
        held: (Throwable) -> Boolean = { it is AppInstanceLockHeld },
        pollMs: Long = POLL_MS,
        deadlineMs: Long = WAIT_MS,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = System::currentTimeMillis,
    ): T? {
        val deadline = now() + deadlineMs
        while (now() < deadline) {
            sleeper(pollMs)
            try {
                return factory()
            } catch (failure: Throwable) {
                if (!held(failure)) throw failure
            }
        }
        return null
    }
}
