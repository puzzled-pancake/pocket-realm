package com.pocketrealm.supervisor

/**
 * Human-readable mapping for raw supervisor failure details.
 *
 * Applied at the UI decode boundary (`RuntimeSupervisorClient.decodeRealmState`)
 * so journal details like `UNVERIFIED_ORPHAN` or a start timeout never reach
 * the user verbatim; the raw detail still goes to logs unchanged.
 */
object RuntimeFailureCopy {

    fun humanize(detail: String): String = when {
        "UNVERIFIED_ORPHAN" in detail ->
            "A previous realm start left a world running that this device cannot " +
                "verify as its own, so automatic recovery will not stop it. It must " +
                "be force-stopped before the realm can start again — use Force stop " +
                "realm below."
        "DB_OWNED_BY_DEAD_SESSION" in detail ->
            "The realm database is still locked by an earlier realm session on " +
                "this device that ended without releasing the lock, so retrying " +
                "the start cannot fix it. Use Force stop realm below to release " +
                "the lock; the databases are checked and repaired automatically " +
                "on the next start."
        "TimeoutCancellationException" in detail ->
            "A realm component stopped responding during the last operation. " +
                "Tap Start to recover safely and try again."
        else -> detail
    }
}
