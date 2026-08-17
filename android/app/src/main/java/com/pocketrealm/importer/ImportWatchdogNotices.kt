package com.pocketrealm.importer

/**
 * F8 B: user-visible watchdog wording keyed on the OS-recorded death reason
 * (ImportProcessMetricsSampler reason tokens). Pure functions so the copy is
 * unit-testable. During a lowmemorykiller storm the honest advice is "close
 * other apps", never just "tap Resume".
 */
internal fun watchdogRestartNotice(exitReason: String?, attempt: Int, maxRestarts: Int): String {
    val counter = "($attempt/$maxRestarts)"
    return if (exitReason == ImportProcessMetricsSampler.EXIT_REASON_LOW_MEMORY) {
        "Android stopped the import to free memory — progress is kept. Restarting automatically $counter. " +
            "Closing other apps (browsers especially) lets it finish sooner."
    } else {
        "The system stopped the import worker — restarting it automatically $counter."
    }
}

internal fun workerStoppedNotice(exitReason: String?): String =
    if (exitReason == ImportProcessMetricsSampler.EXIT_REASON_LOW_MEMORY) {
        "Android keeps stopping the import to free memory. Tap Resume to try again — closing other " +
            "apps (browsers especially) will let it finish. Nothing already imported is lost."
    } else if (exitReason == ImportProcessMetricsSampler.EXIT_REASON_CRASH) {
        "The import worker crashed. Tap Resume to continue from the last checkpoint."
    } else {
        "Worker was stopped by the system — tap Resume to continue."
    }
