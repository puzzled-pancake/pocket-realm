package com.pocketrealm.supervisor

import org.json.JSONObject

/**
 * Pure, bounded decision policy for bringing the app-owned database to the
 * only state from which the active provider (MariaDB, or the in-tree
 * SQLite provider in dual-provider window builds) may be started.  The
 * engine independently enforces every precondition; this policy only
 * chooses the safe recovery order for the production Start flow.
 */
internal object DatabaseStartPreparation {
    enum class Action {
        ROLLBACK_PENDING_RESTORE,
        RESUME_INITIALIZATION,
        RESUME_MIGRATIONS,
        INITIALIZE,
        RECOVER_DIRTY_GENERATION,
        APPLY_PINNED_MIGRATIONS,
        PROVISION_SQLITE_PROVIDER,
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
        val manifestCount = status.optInt("migrationManifestCount", -1)
        val sealedCount = status.optInt("migrationSealedCount", -1)
        val manifestAdvanced = sealedCount in 0 until manifestCount
        return when (status.optString("providerMode", "MARIADB")) {
            "SQLITE" -> {
                if (!status.optBoolean("initialized")) return Action.INITIALIZE
                if (!status.optBoolean("cleanMarker")) return Action.RECOVER_DIRTY_GENERATION
                if (!status.optBoolean("migrationsCurrent")) {
                    // The seed folded the whole corpus at provision time; a
                    // seal behind the pinned manifest means the corpus
                    // advanced - re-provision (fresh seed + user-state
                    // carry), never on-device dialect application.
                    if (manifestAdvanced) return Action.PROVISION_SQLITE_PROVIDER
                    return Action.APPLY_PINNED_MIGRATIONS
                }
                Action.READY
            }
            else -> {
                // MariaDB must be fully prepared first: the window
                // translation boots it to serve the export, so its gates
                // (initialized, clean, migrations current) precede the
                // cutover decision.
                if (!status.optBoolean("initialized")) return Action.INITIALIZE
                if (!status.optBoolean("cleanMarker")) return Action.RECOVER_DIRTY_GENERATION
                if (!status.optBoolean("migrationsCurrent")) return Action.APPLY_PINNED_MIGRATIONS
                // The dual-provider window: a sqlite-capable APK with a
                // current MariaDB generation translates + imports + cuts
                // over before the first SQLite-served boot.
                if (status.optBoolean("sqliteCapable") && !status.optBoolean("sqliteInitialized")) {
                    return Action.PROVISION_SQLITE_PROVIDER
                }
                Action.READY
            }
        }
    }
}
