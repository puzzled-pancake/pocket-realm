package com.pocketrealm.llm

/**
 * Pure classification of a never-healthy NPU child's exit — the crash-block
 * counter's gate. Extracted from LlmRuntimeService so the decision table is
 * unit-testable without a device (the service supplies the waitpid status,
 * the uptime, and the server log tail; nothing here touches the filesystem).
 *
 * Three signals, in order of trust:
 *  1. the waitpid status (LlmExec protocol: 0 running, 10000+exit for normal
 *     exits, -signal for kills, -errno for waitpid errors),
 *  2. the `--log-file` tail (which is emitted before `--device` in argv, so
 *     a session-open failure during the parse-time backend load lands in it;
 *     an EMPTY tail means the NPU exec died before writing a line — inside
 *     backend init — and counts),
 *  3. uptime, only when the tail is inconclusive.
 */
internal enum class NpuDeathVerdict {
    /** Our own SIGTERM (stop/restart, PSI watchdog, PDEATHSIG): never counted. */
    DELIBERATE_STOP,

    /** The failure class the death counter exists for. */
    LOAD_DEATH,

    /** Port-bind or other startup collision: retried with backoff, never counted. */
    STARTUP_COLLISION,

    /** The llmexec child exited before exec (staging error): retried, never counted. */
    PRE_EXEC_FAILURE,
}

internal object NpuDeathAttribution {

    /** Exits sooner than this after exec are startup collisions (port bind,
     *  exec error) unless the log tail carries an NPU failure marker — a
     *  session-open abort also dies within seconds of exec and MUST count. */
    const val LOAD_DEATH_GRACE_MS = 15_000L

    /** ggml-hex session-open failure signatures, matched against the
     *  lowercased log tail. Specific error text only: bare
     *  "htp"/"hexagon"/"fastrpc" also appear in benign INFO/WARN lines at
     *  default verbosity and would miscount bind collisions, while real
     *  failures like the CDSP domain error match none of them. */
    val DEATH_MARKERS = listOf(
        "failed to open session",
        "failed to create device/session",
        "failed to enable unsigned pd",
        "unable to get domain struct",
    )

    /** HTTP listener failure signatures (lowercased tail match): a bind
     *  collision is a startup collision, never a counted load death. */
    val COLLISION_MARKERS = listOf(
        "couldn't bind http server socket",
        "failed to initialize http server",
        "exiting due to http server error",
    )

    fun classify(exitStatus: Int, uptimeMs: Long, rawTail: String): NpuDeathVerdict {
        val tail = rawTail.lowercase()
        return when {
            // Deliberate stops take the server's graceful exit-0 path or, if
            // the signal lands before its handler is installed, die by
            // signal 15. (-10 is ECHILD-or-SIGUSR1, not a crash verdict.)
            exitStatus == 10_000 || exitStatus == -15 -> NpuDeathVerdict.DELIBERATE_STOP
            // Crash signals count unconditionally: the poisoned-registry
            // session abort dies on a null-deref before any log line lands.
            exitStatus < 0 && exitStatus != -10 -> NpuDeathVerdict.LOAD_DEATH
            // The llmexec child exited before exec (staging error): exactly
            // 124-127 (parent-gone/nice/affinity/exec). Server exit codes
            // 128-255 are NOT staging errors — they fall to the tail rules.
            exitStatus in 10_124..10_127 -> NpuDeathVerdict.PRE_EXEC_FAILURE
            COLLISION_MARKERS.any { tail.contains(it) } -> NpuDeathVerdict.STARTUP_COLLISION
            tail.isEmpty() || DEATH_MARKERS.any { tail.contains(it) } -> NpuDeathVerdict.LOAD_DEATH
            uptimeMs < LOAD_DEATH_GRACE_MS -> NpuDeathVerdict.STARTUP_COLLISION
            else -> NpuDeathVerdict.LOAD_DEATH
        }
    }

    /** /proc/\<pid\>/stat liveness from one stat line: the state field is the
     *  first token after the kernel's closing paren of `comm`, and `comm`
     *  may itself contain ')' — so parse after the LAST ')'. A zombie (Z)
     *  counts as down: this is a reaping-free liveness check for watcher
     *  threads (only the supervisor waitpids the live child; a waitpid from
     *  any thread reaps and steals the exit status). */
    fun isStatAlive(statLine: String): Boolean {
        val state = statLine.substringAfterLast(')', "").trim().take(1)
        return state.isNotEmpty() && state != "Z"
    }
}
