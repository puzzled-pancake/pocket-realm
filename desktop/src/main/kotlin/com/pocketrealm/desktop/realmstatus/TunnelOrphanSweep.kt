package com.pocketrealm.desktop.realmstatus

/**
 * Realm-status browser (design realm-status-browser.md) the orphan
 * sweep — kill rules are STRICT, detection is layered.
 *
 *  - Killing is OWN-RECORDS-ONLY, always: a leftover is killed only when
 *    pid AND full command line AND process start-time all match a durable
 *    record of THIS INSTALL at kill time. No bare process-name scan and no
 *    command-line-only scan ever kills anything. (The records are durable
 *    per install: a crash-leftover child of a PREVIOUS app lifetime is
 *    still this app's own record — the record is what survives the crash.)
 *  - The record store is consulted FIRST: a process matching the record of
 *    a LIVE owning instance is not an orphan and is skipped — a second app
 *    instance's sweep must never announce the first instance's healthy
 * child as "not started by this app". On this install the DESK-113
 *    exclusive single-instance lock IS the owning-instance-liveness
 *    oracle: when this sweep runs, the only possibly-running instance is
 *    this one, so "the record's child is live" reduces to "the tunnel
 *    manager of this process currently owns that pid" (the
 *    [isOwnLiveChild] seam).
 *  - Record-less matches (state directory wiped, reinstall, or the record
 *    lost in the very crash that orphaned the child) are NEVER auto-killed
 *    — the same command line is what a user debugging this very page would
 *    legitimately run. Older-than-this-app record-less matches surface the
 * loud hosting-card notice and hold the transition gate (every
 *    transition that binds 18080 — FRIENDS included) until the host
 *    acknowledges or adopts (the controller remembers an explicit ack
 *    PER-PID, so a re-detected acknowledged pid does not re-trip the gate;
 *    only a NEW record-less orphan does).
 *
 * The scan rides the [TunnelOrphanSweep.ProcessTable] seam (the production
 * Windows table enumerates cloudflared processes with their command
 * lines); every DECISION is pure over that seam and unit-pinned.
 */
@Suppress("LongParameterList") // the sweep seam: records + table + identity + predicates + kill/log are all test seams
class TunnelOrphanSweep(
    private val records: TunnelChildRecords,
    private val table: ProcessTable,
    private val currentInstanceId: String,
    /** This app instance's start (the "older than this app instance" leg). */
    private val appStartUnixSeconds: Long,
    /** True when the pid is the live child THIS instance's tunnel manager
     * currently owns (the owning-instance liveness oracle). */
    private val isOwnLiveChild: (Long) -> Boolean,
    private val killProcess: (Int) -> Boolean,
    /** The failure seam: a sweep that dies mid-decision fails OPEN but is
     * still logged — a dead detector must not look like a clean sweep. */
    private val logWarning: (String) -> Unit = {},
) {

    /** One cloudflared process as the table sees it. */
    data class ProcessEntry(val pid: Long, val commandLine: String, val startUnixSeconds: Long?)

    /** The environment the sweep scans (the seam the tests fake). */
    interface ProcessTable {
        /** Every cloudflared process on the machine (any invocation). */
        fun cloudflaredProcesses(): List<ProcessEntry>
    }

    sealed class Outcome {
        /** No record-less orphan is live: transitions that bind 18080 may run. */
        object Clear : Outcome()

        /** This install killed crash-leftover child(ren) of its OWN records
         * (the triple match held at kill time). */
        data class OwnLeftoverKilled(val pids: List<Long>) : Outcome()

        /** A record-less orphan is live: the transition gate holds until
         * the host acknowledges or adopts. */
        data class OrphanDetected(val pid: Long) : Outcome()
    }

    /** Runs one sweep. Idempotent; a failed scan never blocks the page
     * (detection fails open by design — the kill rules stay fail-closed),
     * never throws into the caller, and reports the failure through the
     * [logWarning] seam (a silent Clear would hide a dead detector). The
     * branch shape mirrors §5.3's disposition order (consult records → own
     * kill → notice). */
    @Suppress("NestedBlockDepth", "SwallowedException", "MagicNumber")
    fun sweep(): Outcome = try {
        val live = table.cloudflaredProcesses()
            .filter { it.commandLine.contains(PAGE_TUNNEL_ARGUMENT_TAIL) }
        val ownKilled = mutableListOf<Long>()
        var orphan: ProcessEntry? = null
        for (entry in live) {
            val record = records.matching(entry.pid, entry.commandLine, entry.startUnixSeconds)
            when {
                // The record store consulted first: this running instance's
                // own healthy child is never an orphan and never touched.
                record != null && isOwnLiveChild(entry.pid) -> Unit
                // A durable record with a triple match at kill time, and the
                // child is NOT owned by a live manager: a crash-leftover of
                // this install. The record is exactly what makes this kill
                // safe across reboots (pid reuse dies on the start-time leg).
                record != null -> {
                    if (killProcess(entry.pid.toInt())) {
                        records.remove(entry.pid)
                        ownKilled.add(entry.pid)
                    }
                }
                // Record-less matches are never auto-killed; only one older
                // than this app instance surfaces the orphan notice + gate.
                entry.startUnixSeconds != null && entry.startUnixSeconds < appStartUnixSeconds -> {
                    if (orphan == null) orphan = entry
                }
            }
        }
        when {
            orphan != null -> Outcome.OrphanDetected(orphan.pid)
            ownKilled.isNotEmpty() -> Outcome.OwnLeftoverKilled(ownKilled)
            else -> Outcome.Clear
        }
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        logWarning("orphan sweep failed (detection fails open): ${failure.message}")
        Outcome.Clear
    }

    /** Adoption: take an orphan into the records under the SAME
     * kill rules — after this, the child is this instance's own. */
    fun adopt(entry: ProcessEntry) {
        records.put(
            TunnelChildRecords.Record(
                pid = entry.pid,
                commandLine = entry.commandLine,
                startUnixSeconds = entry.startUnixSeconds,
                // The record's wall-clock leg: the start time back in ms
                // (the sweep's tolerance lives in the record matching).
                spawnWallMs = (entry.startUnixSeconds ?: 0L) * 1_000L,
                instanceId = currentInstanceId,
            ),
        )
    }

    companion object {
        /**
         * The page-tunnel invocation's argument tail — the EXACT command
         * line this app spawns (`cloudflared tunnel --url
         * http://127.0.0.1:18080`, with any resolved binary path in
         * front). A sweep match requires this tail verbatim: any other
         * cloudflared invocation (a different URL, a user's own debug
         * run) never matches, and a game-lane cloudflared `access tcp`
         * recipe never matches either — the page tunnel and the game TCP
         * lane stay disjoint by construction (§5's two-tunnel contract).
         */
        const val PAGE_TUNNEL_ARGUMENT_TAIL: String = "tunnel --url http://127.0.0.1:18080"

        /** The spawn arguments as ProcessBuilder receives them (one source
         * of truth for the spawn AND the record AND the sweep match). */
        val PAGE_TUNNEL_ARGUMENTS: List<String> = PAGE_TUNNEL_ARGUMENT_TAIL.split(' ')

        /** The command line the spawn REALIZES — what the OS is handed for
         * this child (the executable + the argument tail joined with
         * single spaces). The durable record stores THIS, so the §5.3
         * triple match pairs the record with the process table's real
         * `CommandLine` instead of a fabricated string. */
        fun realizedCommandLine(binary: String): String =
            "$binary $PAGE_TUNNEL_ARGUMENT_TAIL"

        /**
         * The record↔table command-line comparison (§5.3's full-command-
         * line leg). Exact equality first; then the ONE documented
         * tolerance: Windows/ProcessBuilder may QUOTE the executable head
         * when its path contains spaces, so the comparison strips quote
         * characters before comparing — the executable identity and the
         * argument tail must still agree verbatim. A pid-reused unrelated
         * process (different executable or different arguments) never
         * matches.
         */
        @Suppress("ReturnCount") // the exact → quote-normalized → tail+basename ladder is the documented tolerance
        fun commandLinesMatch(recorded: String, actual: String): Boolean {
            if (recorded == actual) return true
            val recordedStripped = recorded.replace("\"", "")
            val actualStripped = actual.replace("\"", "")
            if (recordedStripped == actualStripped) return true
            // Same argument tail AND the same executable basename, with
            // only the path spelling differing (e.g. relative vs absolute
            // resolution of the same binary).
            if (!recordedStripped.endsWith(PAGE_TUNNEL_ARGUMENT_TAIL) ||
                !actualStripped.endsWith(PAGE_TUNNEL_ARGUMENT_TAIL)
            ) {
                return false
            }
            val recordedBinary = recordedStripped
                .removeSuffix(PAGE_TUNNEL_ARGUMENT_TAIL).trim()
                .substringAfterLast('\\').substringAfterLast('/')
            val actualBinary = actualStripped
                .removeSuffix(PAGE_TUNNEL_ARGUMENT_TAIL).trim()
                .substringAfterLast('\\').substringAfterLast('/')
            return recordedBinary.equals(actualBinary, ignoreCase = true)
        }
    }
}
