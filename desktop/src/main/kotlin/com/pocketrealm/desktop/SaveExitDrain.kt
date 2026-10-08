package com.pocketrealm.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The ONE save-exit drain, shared by both session-end entry points
 * (the SaveExitReceiver analog):
 *
 *  - the window's `onCloseRequest` (the user closes Pocket Realm), and
 *  - a JVM shutdown hook (Windows logoff/shutdown/restart/taskkill, which
 *    never fires `onCloseRequest`).
 *
 * The once-flag makes the two paths idempotent in either order: whoever
 * gets the CAS first drains (save world -> stop natives -> client destroy,
 * bounded by the supervisor's stop timeouts), and the other entry absorbs.
 * The ABSORPTION differs by caller: a second close click during the drain
 * is discarded by the UI, but the shutdown hook after a window-close drain
 * must WAIT for the in-flight drain — the hook returning early would let
 * JVM teardown kill the world save mid-flight (the exact dirty outcome the
 * drain exists to prevent), so the hook joins the published drain Job.
 */
class SaveExitDrain(
    private val drain: suspend () -> Unit,
    private val close: () -> Unit,
    private val tag: String = "Main",
) {
    /** The app seam: drain the model's supervisor stack, then close it. */
    constructor(model: DesktopAppModel) : this(
        drain = { model.saveAndExit() },
        close = { model.close() },
    )

    /** The once-flag and the in-flight Job live under ONE lock: the claim
     * (requested = true) and the publication of the drain's Job are
     * ATOMIC together, so an entry that sees the claim can never fail to
     * find the Job it must wait for. (The previous AtomicBoolean-CAS +
     * afterwards-set shape left a sub-millisecond window where a shutdown
     * hook lost the race, read a null Job, and returned — letting JVM
     * teardown kill a just-started window-close drain.) */
    private val stateLock = Any()
    private var requested = false
    private var inFlight: Job? = null

    /** True once any entry point has requested (or finished) the drain. */
    fun requested(): Boolean = synchronized(stateLock) { requested }

    /**
     * Window-close entry: starts the drain on a background thread and
     * returns true; returns false (draining nothing twice) when this or the
     * shutdown hook already started it.
     */
    fun requestAsync(onSettled: () -> Unit): Boolean {
        val job: Job
        synchronized(stateLock) {
            if (requested) return false
            requested = true
            job = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
                runDrain()
                onSettled()
            }
            inFlight = job
        }
        // Started OUTSIDE the lock (the lazy job runs nothing until start);
        // the publication above is already visible to any waiter.
        job.start()
        return true
    }

    /**
     * Shutdown-hook entry: runs the same bounded drain synchronously on the
     * hook thread (there is no UI thread or coroutine scope left during JVM
     * shutdown); when the window-close path already owns the drain, the
     * hook BLOCKS on that in-flight drain instead of bailing — a logoff
     * during the save must not tear the JVM down under it. Because the
     * claim and the Job publication are one atomic section, a hook that
     * finds the claim always finds the Job (a null Job here means the
     * SYNCHRONOUS path won the claim, which is this same entry already
     * draining on its own thread — nothing to wait for).
     */
    fun runBlockingOnShutdown() {
        synchronized(stateLock) {
            if (!requested) {
                requested = true
                kotlinx.coroutines.runBlocking { runDrain() }
                return
            }
        }
        val job = synchronized(stateLock) { inFlight } ?: return
        kotlinx.coroutines.runBlocking { job.join() }
    }

    private suspend fun runDrain() {
        runCatching { drain() }
        runCatching { close() }
        DesktopLog.i(tag, "session drained; exiting")
    }
}
