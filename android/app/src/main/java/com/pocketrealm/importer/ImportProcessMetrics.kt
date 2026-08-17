package com.pocketrealm.importer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max

internal data class ImportProcessMetrics(
    val workerPresent: Boolean,
    val cpuPercent: Double? = null,
    val sampleWindowMs: Long = 0,
    val rssBytes: Long = 0,
    val threadCount: Int = 0,
    val processCount: Int = 0,
    val state: String = "absent",
    /** F8 B: most recent OS-recorded death reason of the :import process. */
    val lastExit: WorkerExit? = null,
) {
    data class WorkerExit(val reason: String, val ageMs: Long)
}

/**
 * Low-overhead, same-UID process telemetry for the user-visible import page.
 * The worker writes only its PID at lifecycle boundaries; UI polling reads
 * procfs and never sends a signal or changes import state.
 */
internal object ImportProcessMetricsSampler {
    private data class ProcessSample(
        val elapsedMs: Long,
        val rootPid: Int,
        val cpuTicks: Long,
    )

    private data class ProcRecord(
        val pid: Int,
        val state: Char,
        val cpuTicks: Long,
        val rssBytes: Long,
        val threads: Int,
    )

    private var previous: ProcessSample? = null

    @Synchronized
    fun sample(context: Context): ImportProcessMetrics {
        val rootPid = marker(context).takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull()
            ?: return absent(context)
        val root = readProcess(rootPid, requireImportProcess = true, context = context)
            ?: return absent(context)
        val records = collectProcessTree(root, context)
        val now = SystemClock.elapsedRealtime()
        val ticks = records.sumOf(ProcRecord::cpuTicks)
        val prior = previous
        val windowMs = if (prior?.rootPid == rootPid) now - prior.elapsedMs else 0L
        val deltaTicks = if (prior?.rootPid == rootPid) ticks - prior.cpuTicks else -1L
        val cpuPercent = if (windowMs >= MIN_SAMPLE_MS && deltaTicks >= 0L) {
            val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
                .getOrDefault(100L).coerceAtLeast(1L)
            deltaTicks.toDouble() * 100_000.0 / ticksPerSecond.toDouble() / windowMs.toDouble()
        } else null
        previous = ProcessSample(now, rootPid, ticks)
        val hasRunnable = records.any { it.state == 'R' || it.state == 'D' }
        val state = when {
            records.size > 1 || hasRunnable || (cpuPercent ?: 0.0) >= 0.5 -> "working"
            else -> "waiting"
        }
        return ImportProcessMetrics(
            workerPresent = true,
            cpuPercent = cpuPercent,
            sampleWindowMs = max(0L, windowMs),
            rssBytes = records.sumOf(ProcRecord::rssBytes),
            threadCount = records.sumOf(ProcRecord::threads),
            processCount = records.size,
            state = state,
        )
    }

    @Synchronized
    fun markStarted(context: Context) {
        val target = marker(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${Process.myPid()}.tmp")
        temp.writeText(Process.myPid().toString())
        if (!temp.renameTo(target)) {
            temp.delete()
            return
        }
        previous = null
    }

    @Synchronized
    fun markStopped(context: Context) {
        val target = marker(context)
        if (target.readTextOrNull()?.trim()?.toIntOrNull() == Process.myPid()) target.delete()
        previous = null
    }

    private fun collectProcessTree(root: ProcRecord, context: Context): List<ProcRecord> {
        val records = mutableListOf(root)
        val seen = mutableSetOf(root.pid)
        val pending = ArrayDeque<Int>()
        pending.add(root.pid)
        while (pending.isNotEmpty() && records.size < MAX_PROCESSES) {
            val parent = pending.removeFirst()
            // ProcessBuilder children are forked by whichever worker thread calls
            // start(), so the leader's children file alone misses them; scan every
            // thread of the parent.
            val children = descendantPids(parent)
            for (pid in children) {
                if (!seen.add(pid)) continue
                val child = readProcess(pid, requireImportProcess = false, context = context) ?: continue
                records += child
                pending.add(pid)
                if (records.size >= MAX_PROCESSES) break
            }
        }
        return records
    }

    private fun descendantPids(parent: Int): List<Int> =
        File("/proc/$parent/task").listFiles().orEmpty()
            .flatMap { thread -> File(thread, "children").readTextOrNull()
                ?.trim()?.takeIf(String::isNotBlank)
                ?.split(Regex("\\s+"))?.mapNotNull(String::toIntOrNull).orEmpty() }
            .distinct()

    /** Direct children forked by any thread of this process; usable only in-process. */
    fun forkedChildPids(): Set<Int> =
        File("/proc/self/task").listFiles().orEmpty()
            .flatMapTo(mutableSetOf()) { thread -> File(thread, "children").readTextOrNull()
                ?.trim()?.takeIf(String::isNotBlank)
                ?.split(Regex("\\s+"))?.mapNotNull(String::toIntOrNull).orEmpty() }

    /** Cumulative CPU seconds (user+system) of a same-UID process, or null if unreadable. */
    fun processCpuSeconds(pid: Int): Double? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
        val ticks = fields[11].toLong() + fields[12].toLong()
        ticks.toDouble() / clockTicksPerSecond()
    }.getOrNull()

    fun clockTicksPerSecond(): Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).coerceAtLeast(1L)

    private fun readProcess(pid: Int, requireImportProcess: Boolean, context: Context): ProcRecord? = runCatching {
        require(pid > 0)
        val statusText = File("/proc/$pid/status").readText()
        val uid = statusText.lineSequence().first { it.startsWith("Uid:") }
            .substringAfter(':').trim().split(Regex("\\s+"), limit = 2).first().toInt()
        require(uid == Process.myUid())
        if (requireImportProcess) {
            val cmdline = File("/proc/$pid/cmdline").readBytes()
                .toString(Charsets.UTF_8).trimEnd('\u0000')
            require(cmdline == "${context.packageName}:import")
        }
        val stat = File("/proc/$pid/stat").readText()
        val closing = stat.lastIndexOf(')')
        require(closing > 0)
        val fields = stat.substring(closing + 2).trim().split(Regex("\\s+"))
        val state = fields[0].single()
        val cpuTicks = Math.addExact(fields[11].toLong(), fields[12].toLong())
        val rssKb = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"), limit = 2)?.firstOrNull()
            ?.toLongOrNull() ?: 0L
        val threads = statusText.lineSequence().firstOrNull { it.startsWith("Threads:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        ProcRecord(pid, state, cpuTicks, Math.multiplyExact(rssKb, 1024L), threads)
    }.getOrNull()

    private fun absent(context: Context): ImportProcessMetrics {
        previous = null
        return ImportProcessMetrics(workerPresent = false, lastExit = lastWorkerExit(context))
    }

    /**
     * F8 B: ask the OS why the worker died instead of guessing. The watchdog
     * previously inferred every disappearance as "stopped by the system",
     * which during a lowmemorykiller storm told the user to press Resume when
     * the truthful advice was "close other apps". ApplicationExitInfo needs
     * API 30+; older devices keep the generic wording. Only exits recent
     * enough to belong to this import are trusted.
     */
    fun lastWorkerExit(context: Context): ImportProcessMetrics.WorkerExit? = runCatching {
        if (Build.VERSION.SDK_INT < MIN_EXIT_INFO_API) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = manager.getHistoricalProcessExitReasons("${context.packageName}:import", 0, 1)
            .firstOrNull() ?: return null
        // ApplicationExitInfo.getTimestamp() is epoch/wall-clock based (it must
        // survive reboots), NOT elapsedRealtime — comparing clocks here makes
        // every age negative and silently disables the reason (round-1 blocker).
        val ageMs = System.currentTimeMillis() - info.timestamp
        if (ageMs < 0L || ageMs > EXIT_FRESHNESS_MS) return null
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> EXIT_REASON_LOW_MEMORY
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> EXIT_REASON_CRASH
            ApplicationExitInfo.REASON_ANR -> EXIT_REASON_ANR
            ApplicationExitInfo.REASON_SIGNALED -> EXIT_REASON_SIGNALED
            else -> return null
        }
        ImportProcessMetrics.WorkerExit(reason, ageMs)
    }.getOrNull()

    private fun marker(context: Context): File =
        File(context.noBackupFilesDir, "importer/worker.pid")

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private const val MIN_SAMPLE_MS = 400L
    private const val MAX_PROCESSES = 32
    private const val EXIT_FRESHNESS_MS = 15L * 60L * 1000L
    private const val MIN_EXIT_INFO_API = 30

    /** Reason tokens shared with the UI watchdog wording. */
    const val EXIT_REASON_LOW_MEMORY = "LOW_MEMORY"
    const val EXIT_REASON_CRASH = "CRASH"
    const val EXIT_REASON_ANR = "ANR"
    const val EXIT_REASON_SIGNALED = "SIGNALED"
}
