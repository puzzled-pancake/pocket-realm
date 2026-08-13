package com.pocketrealm.supervisor

import org.json.JSONObject

/**
 * Pure, bounded decision policy for bringing the app-owned database to the
 * only state from which MariaDB may be started.  The engine independently
 * enforces every precondition; this policy only chooses the safe recovery
 * order for the production Start flow.
 */
internal object DatabaseStartPreparation {
    enum class Action {
        ROLLBACK_PENDING_RESTORE,
        RESUME_INITIALIZATION,
        RESUME_MIGRATIONS,
        INITIALIZE,
        RECOVER_DIRTY_GENERATION,
        APPLY_PINNED_MIGRATIONS,
        READY,
    }

    fun next(status: JSONObject): Action {
        check(status.optString("state", "STOPPED") != "RUNNING") {
            "database preparation requires a stopped daemon"
        }
        if (status.optBoolean("restorePending")) return Action.ROLLBACK_PENDING_RESTORE
        if (status.optBoolean("databaseTransactionPending")) {
            val kind = status.optString("databaseTransactionKind", "")
            return when (kind) {
                "INIT" -> Action.RESUME_INITIALIZATION
                "MIGRATION" -> Action.RESUME_MIGRATIONS
                else -> error("unknown pending database transaction")
            }
        }
        if (!status.optBoolean("initialized")) return Action.INITIALIZE
        if (!status.optBoolean("cleanMarker")) return Action.RECOVER_DIRTY_GENERATION
        if (!status.optBoolean("migrationsCurrent")) return Action.APPLY_PINNED_MIGRATIONS
        return Action.READY
    }
}
