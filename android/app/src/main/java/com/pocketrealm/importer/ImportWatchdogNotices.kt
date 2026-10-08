package com.pocketrealm.importer

/**
 * User-visible watchdog wording keyed on the OS-recorded death reason
 * (ImportProcessMetricsSampler reason tokens, inlined here as literals so
 * this file stays android-free and compilable on the desktop — the constants
 * are `const val`s, so the inlining is behavior-identical). Pure functions so
 * the copy is unit-testable. During a lowmemorykiller storm the honest advice
 * is "close other apps", never just "tap Resume".
 */
internal fun watchdogRestartNotice(exitReason: String?, attempt: Int, maxRestarts: Int): String {
    val counter = "($attempt/$maxRestarts)"
    return if (exitReason == EXIT_REASON_LOW_MEMORY) {
        "Android stopped the import to free memory — progress is kept. Restarting automatically $counter. " +
            "Closing other apps (browsers especially) lets it finish sooner."
    } else {
        "The system stopped the import worker — restarting it automatically $counter."
    }
}

/** The reason tokens ImportProcessMetricsSampler records (kept in sync). */
internal const val EXIT_REASON_LOW_MEMORY = "LOW_MEMORY"
internal const val EXIT_REASON_CRASH = "CRASH"

internal fun workerStoppedNotice(exitReason: String?): String =
    if (exitReason == EXIT_REASON_LOW_MEMORY) {
        "Android keeps stopping the import to free memory. Tap Resume to try again — closing other " +
            "apps (browsers especially) will let it finish. Nothing already imported is lost."
    } else if (exitReason == EXIT_REASON_CRASH) {
        "The import worker crashed. Tap Resume to continue from the last checkpoint."
    } else {
        "Worker was stopped by the system — tap Resume to continue."
    }
