package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseDurableStateTest {
    private val generation = "11111111-1111-4111-8111-111111111111"
    private val transaction = "22222222-2222-4222-8222-222222222222"
    private val identity = DatabaseDurableState.Identity(
        providerId = "mariadb-provider-v1",
        providerClosureSha256 = "a".repeat(64),
        bootstrapSha256 = "b".repeat(64),
        migrationManifestSha256 = "c".repeat(64),
        migrationCount = 9,
    )
    private val generationMarker = DatabaseDurableState.generationMarker(generation)

    @Test fun initRecoveryRequiresExactDurableOwnership() {
        val owned = DatabaseDurableState.transaction(
            kind = "INIT", phase = "RUNNING", transactionId = transaction,
            generationUuid = generation, identity = identity,
        )
        assertEquals(
            DatabaseDurableState.InitRecovery.QUARANTINE_AND_RETRY,
            DatabaseDurableState.initRecovery(owned, generationMarker, false, identity, false, false),
        )
        assertEquals(
            DatabaseDurableState.InitRecovery.FAIL_CLOSED,
            DatabaseDurableState.initRecovery(
                owned,
                DatabaseDurableState.generationMarker("33333333-3333-4333-8333-333333333333"),
                false, identity, false, false,
            ),
        )
        assertEquals(
            DatabaseDurableState.InitRecovery.NONE,
            DatabaseDurableState.initRecovery(null, null, false, identity, false, false),
        )
        // NONE never grants ownership: the engine separately rejects this
        // unowned non-empty state instead of quarantining or deleting it.
    }

    @Test fun completedInitCrashKeepsOnlyExactlySealedGeneration() {
        val committing = DatabaseDurableState.transaction(
            kind = "INIT", phase = "COMMITTING", transactionId = transaction,
            generationUuid = generation, identity = identity,
        )
        assertEquals(
            DatabaseDurableState.InitRecovery.KEEP_COMPLETED,
            DatabaseDurableState.initRecovery(
                committing, generationMarker, false, identity,
                initializedCurrent = true, cleanCurrent = true,
            ),
        )
        assertEquals(
            DatabaseDurableState.InitRecovery.QUARANTINE_AND_RETRY,
            DatabaseDurableState.initRecovery(
                committing, generationMarker, false, identity,
                initializedCurrent = true, cleanCurrent = false,
            ),
        )
    }

    @Test fun pendingOrFailedMigrationAlwaysRestoresSnapshotBeforeRetry() {
        for (phase in listOf("SNAPSHOT_READY", "RUNNING", "FAILED")) {
            val record = DatabaseDurableState.transaction(
                kind = "MIGRATION", phase = phase, transactionId = transaction,
                generationUuid = generation, identity = identity,
                snapshotId = "pre-migration-1", snapshotDigest = "d".repeat(64),
            )
            assertEquals(
                DatabaseDurableState.MigrationRecovery.RESTORE_AND_RETRY,
                DatabaseDurableState.migrationRecovery(record, generationMarker, identity),
            )
        }
    }

    @Test fun staleProviderClosureFailsEverySealAndTransaction() {
        val initialized = DatabaseDurableState.initializedSeal(identity, generation, 1)
        val clean = DatabaseDurableState.cleanSeal(identity, generation, 1)
        val migration = DatabaseDurableState.migrationSeal(identity, generation, 1)
        val stale = identity.copy(providerClosureSha256 = "e".repeat(64))

        assertFalse(DatabaseDurableState.initializedCurrent(initialized, stale, generation))
        assertFalse(DatabaseDurableState.cleanCurrent(clean, stale, generation))
        assertFalse(DatabaseDurableState.migrationsCurrent(migration, stale, generation))
        val tx = DatabaseDurableState.transaction(
            "MIGRATION", "RUNNING", transaction, generation, identity,
            "pre-migration-1", "d".repeat(64),
        )
        assertEquals(
            DatabaseDurableState.MigrationRecovery.FAIL_CLOSED,
            DatabaseDurableState.migrationRecovery(tx, generationMarker, stale),
        )
    }

    @Test fun migrationOnlyApkUpgradePreservesInitializedAndCleanOwnership() {
        val initialized = DatabaseDurableState.initializedSeal(identity, generation, 1)
        val clean = DatabaseDurableState.cleanSeal(identity, generation, 1)
        val migration = DatabaseDurableState.migrationSeal(identity, generation, 1)
        val upgraded = identity.copy(
            migrationManifestSha256 = "e".repeat(64),
            migrationCount = identity.migrationCount + 1,
        )

        assertTrue(DatabaseDurableState.initializedCurrent(initialized, upgraded, generation))
        assertTrue(DatabaseDurableState.cleanCurrent(clean, upgraded, generation))
        assertFalse(DatabaseDurableState.migrationsCurrent(migration, upgraded, generation))
        assertEquals(
            DatabaseDurableState.OwnershipCompatibility.CURRENT,
            DatabaseDurableState.ownershipCompatibility(initialized, upgraded, generation),
        )
    }

    @Test fun providerOrBootstrapUpgradeIsCompatibilityMismatchAndInitFailsClosed() {
        val initialized = DatabaseDurableState.initializedSeal(identity, generation, 1)
        val init = DatabaseDurableState.transaction(
            kind = "INIT", phase = "RUNNING", transactionId = transaction,
            generationUuid = generation, identity = identity,
        )
        for (incompatible in listOf(
            identity.copy(providerId = "mariadb-provider-v2"),
            identity.copy(providerClosureSha256 = "e".repeat(64)),
            identity.copy(bootstrapSha256 = "f".repeat(64)),
        )) {
            assertEquals(
                DatabaseDurableState.OwnershipCompatibility.PROVIDER_MISMATCH,
                DatabaseDurableState.ownershipCompatibility(initialized, incompatible, generation),
            )
            assertEquals(
                DatabaseDurableState.InitRecovery.FAIL_CLOSED,
                DatabaseDurableState.initRecovery(
                    init, generationMarker, datadirEmpty = false, identity = incompatible,
                    initializedCurrent = false, cleanCurrent = false,
                ),
            )
        }
    }

    @Test fun pendingMigrationFromOldRevisionAuthenticatesSnapshotThenRetriesCurrent() {
        val old = identity.copy(migrationManifestSha256 = "e".repeat(64), migrationCount = 8)
        val record = DatabaseDurableState.transaction(
            kind = "MIGRATION", phase = "RUNNING", transactionId = transaction,
            generationUuid = generation, identity = old,
            snapshotId = "pre-migration-old", snapshotDigest = "d".repeat(64),
        )
        val historicalSnapshot = JSONObject()
            .put("provider", old.providerId)
            .put("providerClosureSha256", old.providerClosureSha256)
            .put("bootstrapSha256", old.bootstrapSha256)
            .put("migrationManifestSha256", old.migrationManifestSha256)
            .put("migrationCount", old.migrationCount)
            .put("generationUuid", generation)

        assertEquals(
            DatabaseDurableState.MigrationRecovery.RESTORE_AND_RETRY,
            DatabaseDurableState.migrationRecovery(record, generationMarker, identity),
        )
        assertTrue(DatabaseDurableState.migrationSnapshotCompatible(
            record, historicalSnapshot, identity, generation,
        ))
        assertFalse(DatabaseDurableState.migrationSnapshotCompatible(
            record,
            JSONObject(historicalSnapshot.toString()).put("migrationCount", old.migrationCount + 1),
            identity,
            generation,
        ))
        assertFalse(DatabaseDurableState.migrationSnapshotCompatible(
            record,
            JSONObject(historicalSnapshot.toString()).put("migrationManifestSha256", "f".repeat(64)),
            identity,
            generation,
        ))
    }

    @Test fun initializeIdempotentlyDefersOnlyAuthenticatedPendingMigration() {
        val old = identity.copy(migrationManifestSha256 = "e".repeat(64), migrationCount = 8)
        val migration = DatabaseDurableState.transaction(
            kind = "MIGRATION", phase = "FAILED", transactionId = transaction,
            generationUuid = generation, identity = old,
            snapshotId = "pre-migration-old", snapshotDigest = "d".repeat(64),
        )
        assertEquals(
            DatabaseDurableState.InitializedDisposition.DEFER_MIGRATION,
            DatabaseDurableState.initializedDisposition(migration, generationMarker, identity),
        )
        assertEquals(
            DatabaseDurableState.InitializedDisposition.IDEMPOTENT,
            DatabaseDurableState.initializedDisposition(null, generationMarker, identity),
        )
        val init = DatabaseDurableState.transaction(
            kind = "INIT", phase = "RUNNING", transactionId = transaction,
            generationUuid = generation, identity = identity,
        )
        assertEquals(
            DatabaseDurableState.InitializedDisposition.FAIL_CLOSED,
            DatabaseDurableState.initializedDisposition(init, generationMarker, identity),
        )
        assertEquals("MIGRATION", DatabaseDurableState.transactionKind(migration))
        assertEquals("UNKNOWN", DatabaseDurableState.transactionKind("{}"))
    }

    @Test fun startRejectsStalePendingAndDirtyStatesIndependently() {
        assertEquals(
            DatabaseDurableState.StartBlocker.UNINITIALIZED,
            DatabaseDurableState.startBlocker(false, true, true, false),
        )
        assertEquals(
            DatabaseDurableState.StartBlocker.MIGRATIONS_STALE,
            DatabaseDurableState.startBlocker(true, false, true, false),
        )
        assertEquals(
            DatabaseDurableState.StartBlocker.DIRTY,
            DatabaseDurableState.startBlocker(true, true, false, false),
        )
        assertEquals(
            DatabaseDurableState.StartBlocker.TRANSACTION_PENDING,
            DatabaseDurableState.startBlocker(true, true, true, true),
        )
        assertEquals(null, DatabaseDurableState.startBlocker(true, true, true, false))
    }

    @Test fun dirtyOldRevisionRecoversCleanThenAcceptsCurrentMigrations() {
        val oldRevision = identity.copy(
            migrationManifestSha256 = "e".repeat(64), migrationCount = identity.migrationCount - 1,
        )
        val initialized = DatabaseDurableState.initializedSeal(oldRevision, generation, 1)
        val staleMigrations = DatabaseDurableState.migrationSeal(oldRevision, generation, 1)

        assertTrue(DatabaseDurableState.initializedCurrent(initialized, identity, generation))
        assertFalse(DatabaseDurableState.migrationsCurrent(staleMigrations, identity, generation))
        assertTrue(DatabaseDurableState.dirtyRecoveryPermitted(
            initialized = true, clean = false, transactionPending = false,
        ))

        val cleanAfterRecovery = DatabaseDurableState.cleanSeal(identity, generation, 2)
        val currentMigrations = DatabaseDurableState.migrationSeal(identity, generation, 3)
        assertTrue(DatabaseDurableState.cleanCurrent(cleanAfterRecovery, identity, generation))
        assertTrue(DatabaseDurableState.migrationsCurrent(currentMigrations, identity, generation))
        assertEquals(null, DatabaseDurableState.startBlocker(true, true, true, false))
    }

    @Test fun committingRestoreNeverRollsBackPartiallyDeletedQuarantine() {
        fun record(phase: String) = JSONObject().put("schema", 2).put("phase", phase).toString()
        assertEquals(
            DatabaseDurableState.RestoreRecovery.FINISH_COMMIT,
            DatabaseDurableState.restoreRecovery(record("COMMITTING")),
        )
        assertEquals(
            DatabaseDurableState.RestoreRecovery.ROLLBACK,
            DatabaseDurableState.restoreRecovery(record("CANDIDATE_ACTIVE")),
        )
    }

    @Test fun mutationRequiresIndependentDrainProof() {
        assertTrue(DatabaseMutationGate.permits(true, false, true, false))
        assertFalse(DatabaseMutationGate.permits(true, true, true, false))
        assertFalse(DatabaseMutationGate.permits(true, false, false, false))
        assertFalse(DatabaseMutationGate.permits(true, false, true, true))
        assertFalse(DatabaseMutationGate.permits(false, false, true, false))
    }
}
