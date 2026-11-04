package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Realm-status browser (design realm-status-browser.md) the PAGE's own
 * cloudflared quick-tunnel child. The two-tunnel contract in one place:
 *
 *  - the game TCP lane (3724/8085) NEVER touches quick tunnels; this child
 *    is spawned for the disposable HTTP page ONLY, with the invocation
 *    `cloudflared tunnel --url http://127.0.0.1:18080`;
 * OS-level child ownership: on Windows the child joins a Job
 *    Object with KILL_ON_JOB_CLOSE held by this process, so a parent kill
 *    or power loss cannot strand it;
 * the child is recorded durably at spawn (its own-records kill
 *    rules) and the record is removed on its own observed exit;
 *  - the public URL is parsed from the child's stdout and SHAPE-GATED
 * before it ever reaches the payload or the copy button;
 *  - a child exit flips `public.tunnelState` to `down` with `url = null`,
 *    then retries in a BOUNDED loop (3 re-spawns with backoff) before the
 * terminal `down` with the honest retry affordance;
 * discovery: the settings path first, then PATH lookup; a
 *    missing/non-executable binary refuses nothing — it lands in PUBLIC
 *    with `tunnelState = "binary-missing"`, `url = null`, and the failure
 *    copy names the EXACT command the app tried.
 *
 * The app does not silently download binaries .
 */
class CloudflaredPageTunnel(
    private val records: TunnelChildRecords,
    /** The explicit `cloudflaredPath` setting ("" = PATH lookup). */
    private val configuredPath: () -> String,
    /** The child-log line sink (the app log). */
    private val logLine: (String) -> Unit = {},
    /** The sleep seam (tests run the retry schedule without real waits). */
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val _state = MutableStateFlow(TunnelStateReport(TunnelState.DOWN, null, null))

    /** One tunnel-state report: the payload expression plus the
     * discovery failure's exact-command copy when present. */
    data class TunnelStateReport(
        val state: TunnelState,
        /** The shape-gated public URL — non-null ONLY while live. */
        val url: String?,
        /** The failure copy naming the exact command tried. */
        val failureCopy: String?,
    )

    val state: StateFlow<TunnelStateReport> = _state.asStateFlow()

    @Volatile private var process: java.lang.Process? = null

    @Volatile private var jobHandle: Any? = null

    @Volatile private var workerThread: Thread? = null

    private val instanceId = java.util.UUID.randomUUID().toString()

    /** The pid this manager currently owns (the sweep's liveness oracle). */
    @Volatile var liveChildPid: Long? = null
        private set

    fun isOwnLiveChild(pid: Long): Boolean = liveChildPid == pid

    /**
     * Starts (or re-arms) the tunnel: `starting` → `live` (URL parsed,
     * shape-gated) or the bounded-retry terminal `down`, or
     * `binary-missing` with the exact-command copy. Idempotent while a
     * child is already up. The retry budget ( 3 re-spawns) covers the
     * WHOLE start attempt — a child that dies mid-session re-enters the
     * same bounded loop until the budget is spent (terminal `down`).
     */
    @Synchronized
    fun start() {
        if (workerThread?.isAlive == true) return
        _state.value = TunnelStateReport(TunnelState.STARTING, null, null)
        val worker = Thread({ runWithRetries() }, "realm-status-page-tunnel")
        worker.isDaemon = true
        workerThread = worker
        worker.start()
    }

    /** Stops the child and clears the record (the visibility OFF leg). */
    @Synchronized
    fun stop() {
        workerThread = null // the watch loop stops retrying
        val child = process
        process = null
        liveChildPid = null
        val job = jobHandle
        jobHandle = null
        if (child != null) {
            destroyAndReap(child, PROCESS_WAIT_SECONDS)
        }
        // The job handle closes LAST (kill-on-close is the backstop).
        if (job is WinNTHandle) {
            runCatching { Win32JobObject.terminate(job.handle) }
            runCatching { Win32JobObject.close(job.handle) }
        }
        _state.value = TunnelStateReport(TunnelState.DOWN, null, null)
    }

    /** The Job-Object handle wrapper (keeps the JNA type out of fields). */
    private class WinNTHandle(val handle: com.sun.jna.platform.win32.WinNT.HANDLE)

    private fun runWithRetries() {
        var attempt = 0
        while (attempt <= MAX_RESPAWN_ATTEMPTS && workerThread != null) {
            when (spawnOnce()) {
                SpawnOutcome.BINARY_MISSING -> return // terminal, honest copy set
                SpawnOutcome.SPAWNED -> {
                    // Block until the child dies (or stop() destroys it):
                    // the exit IS the flip, and a mid-session death
                    // falls through into the same bounded retry budget.
                    val child = process
                    if (child != null) runCatching { child.waitFor() }
                }
                SpawnOutcome.SPAWN_FAILED -> Unit
            }
            if (workerThread == null) return // stop() ran: never retry past teardown
            attempt += 1
            if (attempt <= MAX_RESPAWN_ATTEMPTS) {
                logLine("page tunnel child gone; retry $attempt/$MAX_RESPAWN_ATTEMPTS")
                sleepMs(BACKOFF_BASE_MS shl (attempt - 1))
                _state.value = TunnelStateReport(TunnelState.STARTING, null, null)
            }
        }
        if (workerThread != null) {
            _state.value = TunnelStateReport(TunnelState.DOWN, null, null)
            logLine("page tunnel terminal down after $MAX_RESPAWN_ATTEMPTS retries")
        }
    }

    private enum class SpawnOutcome { SPAWNED, BINARY_MISSING, SPAWN_FAILED }

    /** Resolves the binary: the settings path, else PATH. Returns
     * null with the failure copy set when nothing executable resolves. */
    @Suppress("ReturnCount") // the two-lane discovery + two failure exits are the posture
    private fun resolveBinary(): Pair<String, String>? {
        val configured = configuredPath().trim()
        if (configured.isNotEmpty()) {
            val file = File(configured)
            if (file.isFile && file.canExecute()) {
                return file.absolutePath to TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENT_TAIL
            }
            val tried = "\"$configured\" ${TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENT_TAIL}"
            _state.value = TunnelStateReport(
                TunnelState.BINARY_MISSING,
                null,
                "cloudflared was not found at the configured path, so the public link " +
                    "could not start. The app tried: $tried — install cloudflared or " +
                    "fix the path in Settings, then enable the Public showcase again.",
            )
            return null
        }
        val tried = "cloudflared ${TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENT_TAIL}"
        val onPath = probeExitsCleanly(listOf("cloudflared", "--version"), PROCESS_WAIT_SECONDS)
        if (!onPath) {
            _state.value = TunnelStateReport(
                TunnelState.BINARY_MISSING,
                null,
                "The cloudflared tool was not found on this PC, so the public link " +
                    "could not start. The app tried: $tried — install cloudflared " +
                    "(and put it on PATH, or set its path in Settings), then enable " +
                    "the Public showcase again.",
            )
            return null
        }
        return "cloudflared" to tried
    }

    @Suppress("ReturnCount") // the spawn outcomes ARE the state machine's inputs
    private fun spawnOnce(): SpawnOutcome {
        val (binary, triedCommand) = resolveBinary() ?: return SpawnOutcome.BINARY_MISSING
        val child = try {
            ProcessBuilder(ArrayList<String>(1 + TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENTS.size).apply {
                add(binary)
                addAll(TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENTS)
            })
                .redirectErrorStream(true)
                .start()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logLine("page tunnel spawn failed: ${failure.message}")
            return SpawnOutcome.SPAWN_FAILED
        }
        process = child
        liveChildPid = child.pid()
        // the OS-level ownership. The job is created per spawn and
        // held until stop() (or process death closes it — which kills the
        // child; that IS the contract).
        val job = runCatching { Win32JobObject.createKillOnCloseJob() }.getOrNull()
        if (job != null) {
            val assigned = runCatching { Win32JobObject.assign(job, child.pid()) }.getOrDefault(false)
            if (assigned) {
                jobHandle = WinNTHandle(job)
            } else {
                runCatching { Win32JobObject.close(job) }
                logLine("page tunnel job assignment refused — the child runs WITHOUT " +
                    "kill-on-close ownership until the next spawn")
            }
        }
        // the durable spawn record (pid + full command line +
        // start-time + spawn wall-clock) — the own-records kill rules'
        // input. The recorded command line is the REALIZED one (exactly
        // what ProcessBuilder handed the OS for this child), so the
        // triple match can actually hold for a crash-orphaned child.
        records.put(
            TunnelChildRecords.Record(
                pid = child.pid(),
                commandLine = TunnelOrphanSweep.realizedCommandLine(binary),
                startUnixSeconds = runCatching {
                    Win32JobObject.processStartUnixSeconds(child.pid())
                }.getOrNull(),
                spawnWallMs = System.currentTimeMillis(),
                instanceId = instanceId,
            ),
        )
        // The stdout reader: the URL parse and the exit flip .
        Thread({
            val parser = URL_PARSER
            child.inputStream.bufferedReader().forEachLine { line ->
                val match = parser.find(line)?.groupValues?.get(1)
                if (match != null && RealmStatusPayload.isShapeValidPublicUrl(match) &&
                    workerThread != null
                ) {
                    _state.value = TunnelStateReport(TunnelState.LIVE, match, null)
                    logLine("page tunnel live: $match")
                }
            }
            // The child exited: never a stale URL — the retry loop
            // (or stop()) owns the state from here.
            records.remove(child.pid())
            if (liveChildPid == child.pid()) liveChildPid = null
        }, "realm-status-page-tunnel-stdout").apply { isDaemon = true }.start()
        return SpawnOutcome.SPAWNED
    }

    companion object {
        /** The page tunnel's ONE allowed URL — the loopback page endpoint. */
        const val PAGE_TUNNEL_URL: String = "http://127.0.0.1:18080"

        /** three re-spawn attempts, then terminal down. */
        const val MAX_RESPAWN_ATTEMPTS = 3

        /** Backoff base (doubled per attempt: 2s, 4s, 8s). */
        const val BACKOFF_BASE_MS = 2_000L

        /** The bounded process waits (seconds): a probe or reap that
         * outlives it is force-killed rather than waited on forever —
         * the same reviewed envelope as the controller's PowerShell
         * probe bound (RealmStatusController.PS_TIMEOUT_SECONDS). */
        internal const val PROCESS_WAIT_SECONDS = 10L

        /** The PATH probe, BOUNDED: waitFor-first + destroyForcibly
         * on timeout (the controller's powerShellCloudflaredProcesses
         * pattern) — a `cloudflared --version` that never exits is
         * not-on-PATH, never a wedged worker thread. */
        internal fun probeExitsCleanly(command: List<String>, timeoutSeconds: Long): Boolean =
            runCatching {
                val probe = ProcessBuilder(command)
                probe.redirectErrorStream(true)
                val seen = probe.start()
                val finished = seen.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    seen.destroyForcibly()
                    return@runCatching false
                }
                seen.inputStream.readBytes()
                seen.exitValue() == 0
            }.getOrDefault(false)

        /** stop()'s bounded child reap: graceful destroy, a BOUNDED wait,
         * then the forced kill — a failed reap still falls through to the
         * Job-Object terminate backstop instead of wedging stop() on the
         * tunnel lock. True when the child died within the bound. */
        internal fun destroyAndReap(child: Process, timeoutSeconds: Long): Boolean {
            runCatching { child.destroy() }
            val reaped = runCatching { child.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!reaped) {
                runCatching { child.destroyForcibly() }
            }
            return reaped
        }

        /** The trycloudflare stdout shape — parse-gated before any
         * payload or copy-button use. */
        private val URL_PARSER = Regex("(https://[a-z0-9-]+\\.trycloudflare\\.com)")
    }
}
