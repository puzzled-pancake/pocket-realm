package com.pocketrealm.desktop

import java.io.File

/**
 * the desktop client-crash evidence lane, replacing the
 * "crash dumps are Windows' own" deferral. Three read-only, best-effort
 * sources:
 *
 *  - the 1.12 client's own crash texts, written into an `Errors/` folder
 *    under the install directory (the Android card's client-generation
 *    Errors/ scan, desktop edition);
 *  - Windows Error Reporting LocalDumps minidumps (`WoW.exe.*.dmp`) under
 *    %LOCALAPPDATA%\CrashDumps;
 *  - the Application event log's fault records (plain-JVM `wevtutil` read,
 *    no JNA), filtered to the game client.
 *
 * Absence of any source means "no crash yet", never an error. Constructor
 * parameters are the test seams (a temp dumps dir, a fake event-log read).
 */
class DesktopCrashEvidence(
    private val localDumpsDir: File = defaultLocalDumpsDir(),
    private val eventLog: (limit: Int) -> String = ::queryApplicationErrorLog,
) {

    /** The client's own crash texts under `<clientDir>/Errors`, newest
     * first, capped at [MAX_CLIENT_CRASH_ROWS] (the Android card's cap);
     * null clientDir (nothing configured resolves) yields nothing. */
    fun clientCrashFiles(clientDir: File?, cap: Int = MAX_CLIENT_CRASH_ROWS): List<File> =
        if (clientDir == null) {
            emptyList()
        } else {
            filesNewestFirst(File(clientDir, ERRORS_DIR_NAME)).take(cap)
        }

    /** The Diagnostics card rows — "Errors/<name>", the Android twin's shape. */
    fun clientCrashRows(clientDir: File?, cap: Int = MAX_CLIENT_CRASH_ROWS): List<String> =
        clientCrashFiles(clientDir, cap).map { file -> "$ERRORS_DIR_NAME/${file.name}" }

    /** WER LocalDumps minidumps for the game client, newest first. */
    fun werDumpFiles(cap: Int = MAX_WER_DUMP_ROWS): List<File> =
        if (!localDumpsDir.isDirectory) {
            emptyList()
        } else {
            filesNewestFirst(localDumpsDir)
                .filter { file -> file.name.startsWith(WER_DUMP_PREFIX) && file.name.endsWith(WER_DUMP_SUFFIX) }
                .take(cap)
        }

    /** The Diagnostics card rows for the WER dumps: name + size. */
    fun werDumpRows(cap: Int = MAX_WER_DUMP_ROWS): List<String> = werDumpFiles(cap).map { file ->
        "${file.name} (${file.length() / BYTES_PER_KIB} KiB)"
    }

    /** Application event-log text for the game client's faults (best
     * effort: a missing or failing wevtutil yields an empty read). */
    fun applicationErrorLog(limit: Int = MAX_EVENT_LOG_EVENTS): String = eventLog(limit).trim()

    private fun filesNewestFirst(dir: File): List<File> =
        dir.listFiles { file -> file.isFile }
            ?.sortedByDescending { file -> file.lastModified() }
            ?: emptyList()

    companion object {
        /** The Android Diagnostics card's crash-row cap. */
        const val MAX_CLIENT_CRASH_ROWS = 5

        /** Bundle/card cap for the WER minidumps. */
        const val MAX_WER_DUMP_ROWS = 3

        /** Event-log events pulled per read (newest first). */
        const val MAX_EVENT_LOG_EVENTS = 20

        const val ERRORS_DIR_NAME = "Errors"

        private const val WER_DUMP_PREFIX = "WoW.exe"
        private const val WER_DUMP_SUFFIX = ".dmp"
        private const val BYTES_PER_KIB = 1024L

        /** WER's LocalDumps default location. */
        fun defaultLocalDumpsDir(): File = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "CrashDumps",
        )

        /**
         * The plain-JVM event-log read: `wevtutil` over the Application
         * log, newest first, Application Error provider (the classic
         * crash source). Output is filtered to the events that name the
         * game client. Any failure is an honest empty read.
         */
        internal fun queryApplicationErrorLog(limit: Int): String = runCatching {
            val process = ProcessBuilder(
                "wevtutil", "qe", "Application",
                "/q:*[System[(Provider[@Name='Application Error'])]]",
                "/c:$limit", "/rd:true", "/f:text",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) return ""
            output.split(BLANK_LINE)
                .filter { chunk -> chunk.contains(CLIENT_EVENT_NEEDLE, ignoreCase = true) }
                .joinToString(separator = "\n\n")
                .trim()
        }.getOrDefault("")

        private val BLANK_LINE = Regex("\\r?\\n\\s*\\r?\\n")
        private const val CLIENT_EVENT_NEEDLE = "WoW.exe"
    }
}
