package com.pocketrealm.desktop.realmstatus

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT

/**
 * Realm-status browser (design realm-status-browser.md §5.2): Windows
 * Job-Object child ownership — MECHANISM, not aspiration. A plain spawned
 * process on Windows SURVIVES its parent's death, so the page-tunnel child
 * is placed in a Job Object held by the app process with
 * JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: a parent kill or power loss closes
 * the handle and the OS kills the child — no stranded cloudflared.
 *
 * The stock JNA platform Kernel32 does not declare the Job Object calls,
 * so this follows the DesktopChatterPowerMonitor ExtendedKernel32
 * precedent: a private extending interface over the SAME loaded library.
 * Non-Windows callers get the honest `supported=false` (the desktop app is
 * Windows-only; the POSIX lane is pdeathsig and not built here).
 */
@Suppress("MagicNumber", "ReturnCount", "MaxLineLength") // the Win32 constants ARE the ABI; the fail-soft null/false returns are the honest posture
internal object Win32JobObject {

    /** The Kernel32 additions JNA's stock interface does not declare
     * (the Win32 entry-point names ARE the ABI — the
     * DesktopChatterPowerMonitor ExtendedKernel32 precedent). */
    @Suppress("FunctionNaming")
    private interface ExtendedKernel32 : Kernel32 {
        fun CreateJobObjectW(jobAttributes: Pointer?, name: String?): WinNT.HANDLE

        fun SetInformationJobObject(
            job: WinNT.HANDLE,
            infoClass: Int,
            info: Pointer,
            infoLength: Int,
        ): Boolean

        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean

        fun TerminateJobObject(job: WinNT.HANDLE, exitCode: Int): Boolean
    }

    /** JOBOBJECT_BASIC_LIMIT_INFORMATION (the x64 layout: DWORD fields
     * pad to the following 8-byte SIZE_T members exactly as MSVC lays
     * the struct out). */
    class BasicLimitInformation : Structure() {
        @Volatile var perProcessUserTimeLimit: Long = 0
        @Volatile var perJobUserTimeLimit: Long = 0
        @Volatile var limitFlags: Int = 0
        @Volatile var minimumWorkingSetSize: Long = 0
        @Volatile var maximumWorkingSetSize: Long = 0
        @Volatile var activeProcessLimit: Int = 0
        @Volatile var affinity: Long = 0
        @Volatile var priorityClass: Int = 0
        @Volatile var schedulingClass: Int = 0

        override fun getFieldOrder(): List<String> = listOf(
            "perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
            "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
            "affinity", "priorityClass", "schedulingClass",
        )
    }

    /** IO_COUNTERS: six ULARGE_INTEGER counters. */
    class IoCounters : Structure() {
        @Volatile var readOperationCount: Long = 0
        @Volatile var writeOperationCount: Long = 0
        @Volatile var otherOperationCount: Long = 0
        @Volatile var readTransferCount: Long = 0
        @Volatile var writeTransferCount: Long = 0
        @Volatile var otherTransferCount: Long = 0

        override fun getFieldOrder(): List<String> = listOf(
            "readOperationCount", "writeOperationCount", "otherOperationCount",
            "readTransferCount", "writeTransferCount", "otherTransferCount",
        )
    }

    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION: the basic limits + IO counters
     * + the four peak/limit SIZE_Ts. */
    class ExtendedLimitInformation : Structure() {
        @Volatile var basic = BasicLimitInformation()
        @Volatile var ioInfo = IoCounters()
        @Volatile var processMemoryLimit: Long = 0
        @Volatile var jobMemoryLimit: Long = 0
        @Volatile var peakProcessMemoryUsed: Long = 0
        @Volatile var peakJobMemoryUsed: Long = 0

        override fun getFieldOrder(): List<String> = listOf(
            "basic", "ioInfo", "processMemoryLimit", "jobMemoryLimit",
            "peakProcessMemoryUsed", "peakJobMemoryUsed",
        )
    }

    /** JobObjectExtendedLimitInformation (the WIN32 API's class number). */
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9

    /** JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE (winbase.h). */
    const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000

    /** PROCESS_SET_QUOTA | PROCESS_TERMINATE — the access AssignProcess
     * needs on the child. */
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    /** The start-time query's least-privileged access right. */
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    private val kernel32: ExtendedKernel32 by lazy {
        Native.load("kernel32", ExtendedKernel32::class.java)
    }

    fun isSupported(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Creates the kill-on-close job. Returns null when the OS refuses
     * (the caller reports tunnel trouble honestly instead of pretending
     * the child is owned); the handle MUST stay open for the job's life —
     * closing it kills the assigned children (that IS the contract).
     */
    fun createKillOnCloseJob(): WinNT.HANDLE? {
        if (!isSupported()) return null
        val job = runCatching { kernel32.CreateJobObjectW(null, null) }.getOrNull()
            ?: return null
        val info = ExtendedLimitInformation()
        info.basic.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        info.write()
        val ok = runCatching {
            kernel32.SetInformationJobObject(
                job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                info.pointer,
                info.size(),
            )
        }.getOrDefault(false)
        if (!ok) {
            runCatching { Kernel32.INSTANCE.CloseHandle(job) }
            return null
        }
        return job
    }

    /** Places an already-spawned child pid into [job]. */
    fun assign(job: WinNT.HANDLE, pid: Long): Boolean {
        if (!isSupported()) return false
        val process = runCatching {
            kernel32.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, pid.toInt())
        }.getOrNull() ?: return false
        if (process.pointer == null || process == WinBase.INVALID_HANDLE_VALUE) return false
        return try {
            runCatching { kernel32.AssignProcessToJobObject(job, process) }.getOrDefault(false)
        } finally {
            // The ASSIGNMENT keeps the child in the job (membership is a
            // kernel counter, not this handle); the process handle itself
            // must not leak.
            runCatching { Kernel32.INSTANCE.CloseHandle(process) }
        }
    }

    /** The hard teardown leg (the bounded stop's terminator). */
    fun terminate(job: WinNT.HANDLE): Boolean =
        runCatching { kernel32.TerminateJobObject(job, 1) }.getOrDefault(false)

    fun close(job: WinNT.HANDLE) {
        runCatching { Kernel32.INSTANCE.CloseHandle(job) }
    }

    /** A live process's creation time as a Unix-epoch second (the kill
     * rule's start-time leg), or null when the pid names nothing queryable
     * (already dead, or an access refusal). */
    fun processStartUnixSeconds(pid: Long): Long? {
        if (!isSupported()) return null
        val process = runCatching {
            Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid.toInt(),
            )
        }.getOrNull() ?: return null
        if (process.pointer == null || process == WinBase.INVALID_HANDLE_VALUE) return null
        return try {
            val creation = WinBase.FILETIME()
            val exit = WinBase.FILETIME()
            val kernel = WinBase.FILETIME()
            val user = WinBase.FILETIME()
            val ok = runCatching {
                Kernel32.INSTANCE.GetProcessTimes(process, creation, exit, kernel, user)
            }.getOrDefault(false)
            if (!ok) null else fileTimeToUnixSeconds(creation)
        } finally {
            runCatching { Kernel32.INSTANCE.CloseHandle(process) }
        }
    }

    /** FILETIME (100ns since 1601) → Unix seconds. */
    private fun fileTimeToUnixSeconds(fileTime: WinBase.FILETIME): Long {
        val raw100Ns = (fileTime.dwHighDateTime.toLong() shl 32) or
            (fileTime.dwLowDateTime.toLong() and 0xFFFF_FFFFL)
        // 116444736000000000 = the 1601→1970 offset in 100ns units.
        val unix100Ns = raw100Ns - 116_444_736_000_000_000L
        return unix100Ns / 10_000_000L
    }

    /** A convenience probe used by tests: true when the pid answers a
     * start-time query (alive and queryable). */
    fun processQueryable(pid: Long): Boolean = processStartUnixSeconds(pid) != null
}
