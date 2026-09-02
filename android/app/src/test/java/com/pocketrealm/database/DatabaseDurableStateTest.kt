package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    @Test fun translationConsumerGateRefusesEveryStalenessLeg() {
        // The translation-record consumer gate: every refusal leg is
        // pure and JVM-tested before the importer consumes a record.
        val sealText = "the-clean-stop-seal-bytes"
        val sealSha = com.pocketrealm.fs.FileDigests.sha256(sealText)
        val recordGeneration = "012348af-1234-4123-8123-0123456789ab"
        fun record(
            phase: String = "EXPORTED",
            sealPin: String? = sealSha,
            generationUuid: String = recordGeneration,
            closure: String = identity.providerClosureSha256,
            manifest: String = identity.migrationManifestSha256,
            count: Int = identity.migrationCount,
            databases: JSONObject? = JSONObject().put(
                "classiccharacters", JSONObject().put(
                    "characters", JSONObject()
                        .put("rows", 2).put("sha256", "d".repeat(64)).put("bytes", 99),
                ),
            ),
        ): String {
            val value = JSONObject().put("schema", 1).put("phase", phase)
                .put("generationUuid", generationUuid)
                .put("providerClosureSha256", closure)
                .put("migrationManifestSha256", manifest)
                .put("migrationCount", count)
                .put("databases", databases ?: JSONObject())
            if (sealPin != null) value.put("cleanStopSealSha256", sealPin)
            return value.toString()
        }
        fun decide(
            recordText: String?,
            liveSeal: String? = sealText,
            liveGeneration: String? = recordGeneration,
            stagedOk: Boolean = true,
        ) = DatabaseDurableState.translationConsumable(
            recordText, liveSeal, identity, liveGeneration,
        ) { _, _, _, _, _ -> stagedOk }

        // the happy path
        assertEquals(DatabaseDurableState.TranslationConsumption.CONSUMABLE, decide(record()))
        // mid-export / failed phases are never consumable
        assertEquals(
            DatabaseDurableState.TranslationConsumption.NOT_EXPORTED,
            decide(record(phase = "EXPORTING")),
        )
        assertEquals(
            DatabaseDurableState.TranslationConsumption.NOT_EXPORTED,
            decide(record(phase = "EXPORT_FAILED")),
        )
        // EXPORTED without the seal pin (crash between stop and record)
        assertEquals(
            DatabaseDurableState.TranslationConsumption.MISSING_SEAL_PIN,
            decide(record(sealPin = null)),
        )
        // any later provider cycle rewrites the seal -> pin mismatch
        assertEquals(
            DatabaseDurableState.TranslationConsumption.SEAL_PIN_MISMATCH,
            decide(record(), liveSeal = "a-later-stop-seal-with-a-fresh-timestamp"),
        )
        assertEquals(
            DatabaseDurableState.TranslationConsumption.SEAL_PIN_MISMATCH,
            decide(record(), liveSeal = null), // start() deleted the seal
        )
        // provider/manifest updates invalidate the baseline
        assertEquals(
            DatabaseDurableState.TranslationConsumption.IDENTITY_MISMATCH,
            decide(record(closure = "e".repeat(64))),
        )
        assertEquals(
            DatabaseDurableState.TranslationConsumption.IDENTITY_MISMATCH,
            decide(record(manifest = "f".repeat(64))),
        )
        assertEquals(
            DatabaseDurableState.TranslationConsumption.IDENTITY_MISMATCH,
            decide(record(count = 414)),
        )
        // re-initialization changed the generation
        assertEquals(
            DatabaseDurableState.TranslationConsumption.GENERATION_MISMATCH,
            decide(record(), liveGeneration = "012348af-1234-4123-8123-0123456789cd"),
        )
        // empty baselines are vacuous and must refuse
        assertEquals(
            DatabaseDurableState.TranslationConsumption.EMPTY_BASELINE,
            decide(record(databases = JSONObject())),
        )
        // any recorded table whose staged TSV fails byte verification
        assertEquals(
            DatabaseDurableState.TranslationConsumption.STAGING_INCOMPLETE,
            decide(record(), stagedOk = false),
        )
        // structurally broken records refuse
        assertEquals(
            DatabaseDurableState.TranslationConsumption.INVALID_RECORD,
            decide(null),
        )
        assertEquals(
            DatabaseDurableState.TranslationConsumption.INVALID_RECORD,
            decide("not-json"),
        )
    }

    // ------------------------------------------------------------------
    // Provider-mode resolution + the active-provider marker.
    // ------------------------------------------------------------------

    @Test fun providerModeResolutionCoversWindowAndRollbackStates() {
        fun resolve(capable: Boolean, sqliteSeal: Boolean, mariadbMarker: Boolean) =
            DatabaseDurableState.resolveProviderMode(capable, sqliteSeal, mariadbMarker)

        // cutover completed on a capable APK: SQLite serves
        assertEquals(DatabaseDurableState.ProviderMode.SQLITE, resolve(true, true, true))
        assertEquals(DatabaseDurableState.ProviderMode.SQLITE, resolve(true, true, false))
        // APK-level rollback: the same datadir on a NON-capable (default)
        // APK must boot MariaDB - the old datadir is kept, never deleted
        assertEquals(DatabaseDurableState.ProviderMode.MARIADB, resolve(false, true, true))
        // fresh install of a window APK: straight to SQLite, no MariaDB
        // bootstrap that would immediately be translated away
        assertEquals(DatabaseDurableState.ProviderMode.SQLITE, resolve(true, false, false))
        // fresh install of a default APK: MariaDB as always
        assertEquals(DatabaseDurableState.ProviderMode.MARIADB, resolve(false, false, false))
        // the window transition state: MariaDB generation present, sqlite
        // datadir not yet provisioned - the old provider stays active
        assertEquals(DatabaseDurableState.ProviderMode.MARIADB, resolve(true, false, true))
    }

    @Test fun activeProviderMarkerRoundTripsAndRefusesCrossModeSpoofs() {
        val mariadb = DatabaseDurableState.activeProviderMarker(
            DatabaseDurableState.ProviderMode.MARIADB,
            "mariadb-12.3.2-termux-bionic-arm64",
            "0f0e3442-2d3f-4a5b-8c9d-0e1f2a3b4c5d",
        )
        val parsedMariaDb = DatabaseDurableState.parseActiveProviderMarker(mariadb)
        assertEquals(DatabaseDurableState.ProviderMode.MARIADB, parsedMariaDb?.mode)
        assertEquals("mariadb-12.3.2-termux-bionic-arm64", parsedMariaDb?.providerId)

        val sqlite = DatabaseDurableState.activeProviderMarker(
            DatabaseDurableState.ProviderMode.SQLITE,
            DatabaseRuntimeContract.SQLITE_PROVIDER_ID,
            null,
        )
        val parsedSqlite = DatabaseDurableState.parseActiveProviderMarker(sqlite)
        assertEquals(DatabaseDurableState.ProviderMode.SQLITE, parsedSqlite?.mode)
        assertEquals("sqlite-3.46.1-in-tree", parsedSqlite?.providerId)
        assertNull(parsedSqlite?.generationUuid)

        // a marker naming the WRONG provider family for its mode refuses
        assertNull(DatabaseDurableState.parseActiveProviderMarker(
            DatabaseDurableState.activeProviderMarker(
                DatabaseDurableState.ProviderMode.SQLITE,
                "mariadb-12.3.2-termux-bionic-arm64",
                null,
            ),
        ))
        // MariaDB mode without a generation refuses AT CONSTRUCTION (the
        // marker pins the live generation for :realm/:world config shaping)
        assertTrue(
            runCatching {
                DatabaseDurableState.activeProviderMarker(
                    DatabaseDurableState.ProviderMode.MARIADB,
                    "mariadb-12.3.2-termux-bionic-arm64",
                    null,
                )
            }.isFailure,
        )
        assertNull(DatabaseDurableState.parseActiveProviderMarker(null))
        assertNull(DatabaseDurableState.parseActiveProviderMarker("{not json"))
    }

    // ------------------------------------------------------------------
    // The interrupted-provisioning recovery.
    // ------------------------------------------------------------------

    @Test fun provisioningRecoveryCoversEveryCrashWindow() {
        val newGen = "33333333-3333-4333-8333-333333333333"
        val oldGen = "44444444-4444-4444-8444-444444444444"
        fun decide(record: String?, live: String?, seals: Boolean = false, retired: Boolean = false) =
            DatabaseDurableState.provisioningRecovery(record, live, seals, retired)

        // a fully sealed datadir owned by the record: the crash hit the
        // final commit window - the provision is complete
        assertEquals(DatabaseDurableState.ProvisioningRecovery.KEEP_COMPLETED,
            decide(newGen, newGen, seals = true))
        // the record predates a re-provision's move: the OLD live datadir
        // is intact - discard the record and retry later
        assertEquals(DatabaseDurableState.ProvisioningRecovery.DISCARD_RECORD,
            decide(newGen, oldGen))
        // a re-provision moved the old datadir out (partial or absent
        // live): restore the retired datadir - NEVER re-translate
        assertEquals(DatabaseDurableState.ProvisioningRecovery.RESTORE_RETIRED,
            decide(newGen, null, retired = true))
        assertEquals(DatabaseDurableState.ProvisioningRecovery.RESTORE_RETIRED,
            decide(newGen, newGen, seals = false, retired = true))
        // a fresh provision died mid-seed/import: quarantine and retry
        assertEquals(DatabaseDurableState.ProvisioningRecovery.QUARANTINE_AND_RETRY,
            decide(newGen, newGen, seals = false))
        // nothing happened yet
        assertEquals(DatabaseDurableState.ProvisioningRecovery.DISCARD_RECORD,
            decide(newGen, null))
        // an unparseable record never silently discards anything
        assertEquals(DatabaseDurableState.ProvisioningRecovery.DISCARD_RECORD,
            decide(null, newGen))
    }

    @Test fun sqliteClosureDigestIsCorpusStableButBuildSensitive() {
        // ownership must survive a corpus advance (only the seed pins
        // move) and must move when the provider build moves — a digest
        // that flipped on seed changes would unown the datadir on every
        // content refresh, and one that ignored the build would keep a
        // swapped provider's ownership
        val artifact = org.json.JSONObject()
            .put("path", "native/x/libpocket_world_runtime.so")
            .put("sha256", "a".repeat(64))
        val seeds = org.json.JSONObject()
            .put("classicmangos", org.json.JSONObject()
                .put("sha256", "b".repeat(64)).put("size", 122363148L))
        val base = org.json.JSONObject()
            .put("schema", 1).put("abi", "arm64-v8a")
            .put("database_backend", "sqlite")
            .put("cmangos_commit", "082afd60")
            .put("artifacts", org.json.JSONArray().put(artifact))
            .put("seed_transcripts", seeds)
        val corpusAdvanced = org.json.JSONObject(base.toString()).put(
            "seed_transcripts",
            org.json.JSONObject().put("classicmangos", org.json.JSONObject()
                .put("sha256", "c".repeat(64)).put("size", 125000000L)),
        )
        val buildAdvanced = org.json.JSONObject(base.toString())
            .put("cmangos_commit", "ffffffff")
        assertEquals(
            DatabaseDurableState.sqliteClosureDigest(base.toString()),
            DatabaseDurableState.sqliteClosureDigest(corpusAdvanced.toString()),
        )
        assertFalse(
            DatabaseDurableState.sqliteClosureDigest(base.toString()) ==
                DatabaseDurableState.sqliteClosureDigest(buildAdvanced.toString()),
        )
    }
}
