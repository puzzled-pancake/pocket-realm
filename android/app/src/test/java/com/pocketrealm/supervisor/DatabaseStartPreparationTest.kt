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
}
