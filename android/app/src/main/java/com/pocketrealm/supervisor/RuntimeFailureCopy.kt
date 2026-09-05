package com.pocketrealm.supervisor

/**
 * Human-readable mapping for raw supervisor failure details (plan F1).
 *
 * Applied at the UI decode boundary (`RuntimeSupervisorClient.decodeRealmState`)
 * so journal details like `UNVERIFIED_ORPHAN` or a start timeout never reach
 * the user verbatim; the raw detail still goes to logs unchanged.
 */
object RuntimeFailureCopy {

    fun humanize(detail: String): String = when {
        "UNVERIFIED_ORPHAN" in detail ->
            "A component from a previous session is still running but its ownership " +
                "could not be verified. Tap Start to recover it safely and try again."
        "TimeoutCancellationException" in detail ->
            "A realm component stopped responding during the last operation. " +
                "Tap Start to recover safely and try again."
        else -> detail
    }
}
