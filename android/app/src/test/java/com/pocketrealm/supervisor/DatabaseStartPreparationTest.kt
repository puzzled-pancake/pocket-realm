package com.pocketrealm.supervisor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseStartPreparationTest {
    private fun status(
        initialized: Boolean = false,
        migrations: Boolean = false,
        clean: Boolean = false,
        restore: Boolean = false,
        transaction: String? = null,
    ) = JSONObject()
        .put("state", "STOPPED")
        .put("initialized", initialized)
        .put("migrationsCurrent", migrations)
        .put("cleanMarker", clean)
        .put("restorePending", restore)
        .put("databaseTransactionPending", transaction != null)
        .put("databaseTransactionKind", transaction ?: JSONObject.NULL)

    @Test fun `fresh database initializes before migrations`() {
        assertEquals(
            DatabaseStartPreparation.Action.INITIALIZE,
            DatabaseStartPreparation.next(status()),
        )
        assertEquals(
            DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS,
            DatabaseStartPreparation.next(status(initialized = true, clean = true)),
        )
    }

    @Test fun `pending durable operations take precedence`() {
        assertEquals(
            DatabaseStartPreparation.Action.ROLLBACK_PENDING_RESTORE,
            DatabaseStartPreparation.next(status(restore = true, transaction = "MIGRATION")),
        )
        assertEquals(
            DatabaseStartPreparation.Action.RESUME_INITIALIZATION,
            DatabaseStartPreparation.next(status(transaction = "INIT")),
        )
        assertEquals(
            DatabaseStartPreparation.Action.RESUME_MIGRATIONS,
            DatabaseStartPreparation.next(status(initialized = true, transaction = "MIGRATION")),
        )
    }

    @Test fun `dirty generation recovers before a revision upgrade`() {
        assertEquals(
            DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION,
            DatabaseStartPreparation.next(status(initialized = true, migrations = false, clean = false)),
        )
    }

    @Test fun `only fully sealed stopped generation is ready`() {
        assertEquals(
            DatabaseStartPreparation.Action.READY,
            DatabaseStartPreparation.next(status(initialized = true, migrations = true, clean = true)),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown pending transaction fails closed`() {
        DatabaseStartPreparation.next(status(transaction = "UNKNOWN"))
    }

    @Test(expected = IllegalStateException::class)
    fun `running database cannot be prepared`() {
        DatabaseStartPreparation.next(
            status(initialized = true, migrations = true, clean = true).put("state", "RUNNING"),
        )
    }

    // ------------------------------------------------------------------
    // P6: provider-mode routing (the dual-provider window + the sqlite
    // lifecycle). Keys absent (pre-P6 status) must behave exactly as the
    // MariaDB-only policy did.
    // ------------------------------------------------------------------

    private fun windowStatus(
        providerMode: String,
        initialized: Boolean,
        migrations: Boolean,
        clean: Boolean,
        sqliteCapable: Boolean = true,
        sqliteInitialized: Boolean = false,
        manifestCount: Int = 412,
        sealedCount: Int? = null,
    ) = status(initialized, migrations, clean)
        .put("providerMode", providerMode)
        .put("sqliteCapable", sqliteCapable)
        .put("sqliteInitialized", sqliteInitialized)
        .put("migrationManifestCount", manifestCount)
        .put("migrationSealedCount", sealedCount ?: JSONObject.NULL)

    @Test fun `window transition provisions before serving the old provider`() {
        // sqlite-capable APK, current MariaDB generation, sqlite datadir
        // absent: translate + import + cutover FIRST.
        assertEquals(
            DatabaseStartPreparation.Action.PROVISION_SQLITE_PROVIDER,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "MARIADB", initialized = true, migrations = true, clean = true,
            )),
        )
    }

    @Test fun `legacy apk boots the old policy unchanged`() {
        // a default (non-sqlite) APK over a current MariaDB generation
        assertEquals(
            DatabaseStartPreparation.Action.READY,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "MARIADB", initialized = true, migrations = true, clean = true,
                sqliteCapable = false,
            )),
        )
        // a capable APK whose MariaDB generation needs recovery: the
        // fail-closed MariaDB paths run BEFORE the window transition
        assertEquals(
            DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "MARIADB", initialized = true, migrations = true, clean = false,
            )),
        )
        assertEquals(
            DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "MARIADB", initialized = true, migrations = false, clean = true,
            )),
        )
    }

    @Test fun `sqlite mode seeds then seals migrations`() {
        // fresh sqlite datadir: initialize (seed replay) first
        assertEquals(
            DatabaseStartPreparation.Action.INITIALIZE,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "SQLITE", initialized = false, migrations = false, clean = false,
            )),
        )
        // initialized but unsealed: applyPinnedMigrations verifies the
        // folded ledger + probes and stamps the migration seal
        assertEquals(
            DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "SQLITE", initialized = true, migrations = false, clean = true,
                sealedCount = null,
            )),
        )
        // fully sealed: ready (start runs the integrity gate)
        assertEquals(
            DatabaseStartPreparation.Action.READY,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "SQLITE", initialized = true, migrations = true, clean = true,
                sqliteInitialized = true, sealedCount = 412,
            )),
        )
    }

    @Test fun `manifest advance re-provisions instead of applying`() {
        // the corpus grew since this datadir was provisioned: the SQLite
        // lane re-provisions (fresh seed + user-state carry); the migration
        // seal is behind the pinned manifest
        assertEquals(
            DatabaseStartPreparation.Action.PROVISION_SQLITE_PROVIDER,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "SQLITE", initialized = true, migrations = false, clean = true,
                sqliteInitialized = true, manifestCount = 415, sealedCount = 412,
            )),
        )
    }

    @Test fun `sqlite dirty generation recovers`() {
        assertEquals(
            DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION,
            DatabaseStartPreparation.next(windowStatus(
                providerMode = "SQLITE", initialized = true, migrations = false, clean = false,
            )),
        )
    }
}
