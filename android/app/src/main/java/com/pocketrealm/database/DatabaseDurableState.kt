package com.pocketrealm.database

import org.json.JSONObject

/**
 * Pure codec and recovery policy for database generation seals and durable
 * init/migration transactions. Keeping decisions here makes crash windows
 * deterministic and unit-testable without starting MariaDB.
 */
internal object DatabaseDurableState {
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val PROVIDER = Regex("[A-Za-z0-9._+-]{1,96}")
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val SNAPSHOT_ID = Regex("[A-Za-z0-9._-]{1,96}")

    data class Identity(
        val providerId: String,
        val providerClosureSha256: String,
        val bootstrapSha256: String,
        val migrationManifestSha256: String,
        val migrationCount: Int,
    ) {
        init {
            require(PROVIDER.matches(providerId))
            require(SHA256.matches(providerClosureSha256))
            require(SHA256.matches(bootstrapSha256))
            require(SHA256.matches(migrationManifestSha256))
            require(migrationCount >= 0)
        }
    }

    enum class InitRecovery { NONE, KEEP_COMPLETED, QUARANTINE_AND_RETRY, FAIL_CLOSED }
    enum class MigrationRecovery { NONE, RESTORE_AND_RETRY, FAIL_CLOSED }
    enum class RestoreRecovery { ROLLBACK, FINISH_COMMIT }
    enum class OwnershipCompatibility { MISSING, CURRENT, PROVIDER_MISMATCH, GENERATION_MISMATCH, INVALID }
    enum class InitializedDisposition { IDEMPOTENT, DEFER_MIGRATION, FAIL_CLOSED }
    enum class StartBlocker { UNINITIALIZED, MIGRATIONS_STALE, DIRTY, TRANSACTION_PENDING }

    fun generationMarker(generationUuid: String): String {
        require(UUID.matches(generationUuid))
        return JSONObject().put("schema", 1).put("generationUuid", generationUuid).toString()
    }

    fun generationUuid(markerText: String?): String? = runCatching {
        val marker = JSONObject(requireNotNull(markerText))
        check(marker.length() == 2 && marker.getInt("schema") == 1)
        marker.getString("generationUuid").also { check(UUID.matches(it)) }
    }.getOrNull()

    fun initializedSeal(identity: Identity, generationUuid: String, completedAt: Long): String =
        baseSeal(identity, generationUuid, "initializedAt", completedAt).toString()

    fun cleanSeal(
        identity: Identity,
        generationUuid: String,
        stoppedAt: Long,
        detailKey: String? = null,
        detailValue: String? = null,
    ): String = baseSeal(identity, generationUuid, "stoppedAt", stoppedAt).also { seal ->
        if (detailKey != null) {
            require(detailKey in setOf("restoredSnapshot", "restoreCandidate", "restoreRolledBack"))
            seal.put(detailKey, requireNotNull(detailValue))
        }
    }.toString()

    fun migrationSeal(identity: Identity, generationUuid: String, completedAt: Long): String =
        baseSeal(identity, generationUuid, "completedAt", completedAt)
            .put("migrationCount", identity.migrationCount)
            .toString()

    fun initializedCurrent(text: String?, identity: Identity, generationUuid: String?): Boolean =
        ownershipSealCurrent(text, identity, generationUuid, "initializedAt", extraCount = 0)

    fun cleanCurrent(text: String?, identity: Identity, generationUuid: String?): Boolean = runCatching {
        val marker = requireOwnershipSeal(text, identity, requireNotNull(generationUuid), "stoppedAt")
        check(marker.length() in (BASE_SEAL_FIELDS + 1)..(BASE_SEAL_FIELDS + 2))
        if (marker.length() == BASE_SEAL_FIELDS + 2) {
            val details = setOf("restoredSnapshot", "restoreCandidate", "restoreRolledBack")
            check(details.count(marker::has) == 1)
        }
    }.isSuccess

    fun migrationsCurrent(text: String?, identity: Identity, generationUuid: String?): Boolean =
        runCatching {
            val marker = requireExactSeal(text, identity, requireNotNull(generationUuid), "completedAt")
            check(marker.length() == BASE_SEAL_FIELDS + 2)
            check(marker.getInt("migrationCount") == identity.migrationCount)
        }.isSuccess

    /**
     * Initialization and clean-stop ownership deliberately exclude the pinned
     * migration revision. A migration-only APK update must keep ownership of
     * the same provider/bootstrap/generation while making only the migration
     * seal stale.
     */
    fun ownershipCompatibility(
        text: String?,
        identity: Identity,
        generationUuid: String?,
        timeKey: String = "initializedAt",
    ): OwnershipCompatibility {
        if (text == null) return OwnershipCompatibility.MISSING
        return runCatching {
            val marker = requireStructurallyValidSeal(text, timeKey)
            if (timeKey == "initializedAt") check(marker.length() == BASE_SEAL_FIELDS + 1)
            val expectedGeneration = requireNotNull(generationUuid)
            when {
                !ownershipMatches(marker, identity) -> OwnershipCompatibility.PROVIDER_MISMATCH
                marker.getString("generationUuid") != expectedGeneration ->
                    OwnershipCompatibility.GENERATION_MISMATCH
                else -> OwnershipCompatibility.CURRENT
            }
        }.getOrDefault(OwnershipCompatibility.INVALID)
    }

    fun transaction(
        kind: String,
        phase: String,
        transactionId: String,
        generationUuid: String,
        identity: Identity,
        snapshotId: String? = null,
        snapshotDigest: String? = null,
    ): String {
        require(kind == "INIT" || kind == "MIGRATION")
        require(validPhase(kind, phase))
        require(UUID.matches(transactionId) && UUID.matches(generationUuid))
        require((snapshotId == null) == (snapshotDigest == null))
        if (kind == "MIGRATION") {
            require(SNAPSHOT_ID.matches(requireNotNull(snapshotId)))
            require(SHA256.matches(requireNotNull(snapshotDigest)))
        } else {
            require(snapshotId == null)
        }
        return identityJson(identity)
            .put("schema", 2)
            .put("kind", kind)
            .put("phase", phase)
            .put("transactionId", transactionId)
            .put("generationUuid", generationUuid)
            .put("snapshotId", snapshotId ?: JSONObject.NULL)
            .put("snapshotDigest", snapshotDigest ?: JSONObject.NULL)
            .put("migrationCount", identity.migrationCount)
            .toString()
    }

    fun withTransactionPhase(text: String, expectedKind: String, phase: String): String {
        val value = JSONObject(text)
        check(value.getString("kind") == expectedKind && validPhase(expectedKind, phase))
        value.put("phase", phase)
        return value.toString()
    }

    fun initRecovery(
        recordText: String?,
        datadirGenerationText: String?,
        datadirEmpty: Boolean,
        identity: Identity,
        initializedCurrent: Boolean,
        cleanCurrent: Boolean,
    ): InitRecovery {
        if (recordText == null) return InitRecovery.NONE
        val record = parseTransaction(recordText, "INIT", identity) ?: return InitRecovery.FAIL_CLOSED
        val ownedGeneration = generationUuid(datadirGenerationText)
        if (datadirEmpty && ownedGeneration == null) return InitRecovery.QUARANTINE_AND_RETRY
        if (ownedGeneration != record.getString("generationUuid")) return InitRecovery.FAIL_CLOSED
        return if (record.getString("phase") == "COMMITTING" && initializedCurrent && cleanCurrent) {
            InitRecovery.KEEP_COMPLETED
        } else {
            InitRecovery.QUARANTINE_AND_RETRY
        }
    }

    fun migrationRecovery(
        recordText: String?,
        datadirGenerationText: String?,
        identity: Identity,
    ): MigrationRecovery {
        if (recordText == null) return MigrationRecovery.NONE
        val record = parseCompatibleMigrationTransaction(recordText, identity)
            ?: return MigrationRecovery.FAIL_CLOSED
        return if (generationUuid(datadirGenerationText) == record.getString("generationUuid")) {
            MigrationRecovery.RESTORE_AND_RETRY
        } else {
            MigrationRecovery.FAIL_CLOSED
        }
    }

    fun initializedDisposition(
        recordText: String?,
        datadirGenerationText: String?,
        identity: Identity,
    ): InitializedDisposition {
        if (recordText == null) return InitializedDisposition.IDEMPOTENT
        return if (
            migrationRecovery(recordText, datadirGenerationText, identity) ==
            MigrationRecovery.RESTORE_AND_RETRY
        ) {
            InitializedDisposition.DEFER_MIGRATION
        } else {
            InitializedDisposition.FAIL_CLOSED
        }
    }

    fun startBlocker(
        initialized: Boolean,
        migrationsCurrent: Boolean,
        clean: Boolean,
        transactionPending: Boolean,
    ): StartBlocker? = when {
        !initialized -> StartBlocker.UNINITIALIZED
        !migrationsCurrent -> StartBlocker.MIGRATIONS_STALE
        !clean -> StartBlocker.DIRTY
        transactionPending -> StartBlocker.TRANSACTION_PENDING
        else -> null
    }

    fun dirtyRecoveryPermitted(
        initialized: Boolean,
        clean: Boolean,
        transactionPending: Boolean,
    ): Boolean = initialized && !clean && !transactionPending

    fun restoreRecovery(recordText: String): RestoreRecovery {
        val record = JSONObject(recordText)
        check(record.getInt("schema") == 2)
        val phase = record.getString("phase")
        check(phase in setOf("PREPARING", "CANDIDATE_ACTIVE", "COMMITTING"))
        return if (phase == "COMMITTING") RestoreRecovery.FINISH_COMMIT else RestoreRecovery.ROLLBACK
    }

    /** Exact-current parser used for all new writes and phase changes. */
    fun parseTransaction(text: String, expectedKind: String, identity: Identity): JSONObject? =
        runCatching {
            val value = requireTransactionStructure(text, expectedKind)
            requireIdentity(value, identity)
            if (value.getInt("schema") == 2) {
                check(value.getInt("migrationCount") == identity.migrationCount)
            }
            value
        }.getOrNull()

    /**
     * Historical parser used only to roll back an interrupted migration. It
     * authenticates the durable provider/bootstrap/generation ownership but
     * intentionally permits the recorded migration revision to differ from
     * the currently pinned revision.
     */
    fun parseCompatibleMigrationTransaction(text: String, identity: Identity): JSONObject? =
        runCatching {
            val value = requireTransactionStructure(text, "MIGRATION")
            check(ownershipMatches(value, identity))
            value
        }.getOrNull()

    fun migrationSnapshotCompatible(
        recordText: String,
        snapshotCompatibility: JSONObject,
        identity: Identity,
        expectedGenerationUuid: String,
    ): Boolean = runCatching {
        val record = checkNotNull(parseCompatibleMigrationTransaction(recordText, identity))
        check(snapshotCompatibility.length() == SNAPSHOT_COMPATIBILITY_FIELDS)
        check(ownershipMatches(snapshotCompatibility, identity))
        check(snapshotCompatibility.getString("generationUuid") == expectedGenerationUuid)
        check(record.getString("generationUuid") == expectedGenerationUuid)
        check(snapshotCompatibility.getString("migrationManifestSha256") ==
            record.getString("migrationManifestSha256"))
        val migrationCount = snapshotCompatibility.getInt("migrationCount")
        check(migrationCount >= 0)
        if (record.getInt("schema") == 2) {
            check(record.getInt("migrationCount") == migrationCount)
        }
    }.isSuccess

    /** Bounded status classification; never returns a value read from disk. */
    fun transactionKind(text: String?): String? {
        if (text == null) return null
        for (kind in listOf("INIT", "MIGRATION")) {
            if (runCatching { requireTransactionStructure(text, kind) }.isSuccess) return kind
        }
        return "UNKNOWN"
    }

    private fun baseSeal(
        identity: Identity,
        generationUuid: String,
        timeKey: String,
        time: Long,
    ): JSONObject {
        require(UUID.matches(generationUuid) && time > 0)
        return identityJson(identity)
            .put("schema", 2)
            .put("generationUuid", generationUuid)
            .put(timeKey, time)
    }

    private fun ownershipSealCurrent(
        text: String?,
        identity: Identity,
        generationUuid: String?,
        timeKey: String,
        extraCount: Int?,
    ): Boolean = runCatching {
        val marker = requireOwnershipSeal(text, identity, requireNotNull(generationUuid), timeKey)
        if (extraCount != null) check(marker.length() == BASE_SEAL_FIELDS + 1 + extraCount)
        else check(marker.length() == BASE_SEAL_FIELDS + 1)
    }.isSuccess

    private fun requireOwnershipSeal(
        text: String?,
        identity: Identity,
        generationUuid: String,
        timeKey: String,
    ): JSONObject {
        val marker = requireStructurallyValidSeal(requireNotNull(text), timeKey)
        check(ownershipMatches(marker, identity))
        check(marker.getString("generationUuid") == generationUuid)
        return marker
    }

    private fun requireExactSeal(
        text: String?,
        identity: Identity,
        generationUuid: String,
        timeKey: String,
    ): JSONObject = requireOwnershipSeal(text, identity, generationUuid, timeKey).also {
        check(it.getString("migrationManifestSha256") == identity.migrationManifestSha256)
    }

    private fun requireStructurallyValidSeal(text: String, timeKey: String): JSONObject {
        val marker = JSONObject(text)
        check(marker.getInt("schema") == 2)
        requireIdentityShape(marker)
        check(UUID.matches(marker.getString("generationUuid")))
        check(marker.getLong(timeKey) > 0)
        return marker
    }

    private fun identityJson(identity: Identity): JSONObject = JSONObject()
        .put("provider", identity.providerId)
        .put("providerClosureSha256", identity.providerClosureSha256)
        .put("bootstrapSha256", identity.bootstrapSha256)
        .put("migrationManifestSha256", identity.migrationManifestSha256)

    private fun requireIdentity(value: JSONObject, identity: Identity) {
        check(ownershipMatches(value, identity))
        check(value.getString("migrationManifestSha256") == identity.migrationManifestSha256)
    }

    private fun ownershipMatches(value: JSONObject, identity: Identity): Boolean =
        value.getString("provider") == identity.providerId &&
            value.getString("providerClosureSha256") == identity.providerClosureSha256 &&
            value.getString("bootstrapSha256") == identity.bootstrapSha256

    private fun requireIdentityShape(value: JSONObject) {
        check(PROVIDER.matches(value.getString("provider")))
        check(SHA256.matches(value.getString("providerClosureSha256")))
        check(SHA256.matches(value.getString("bootstrapSha256")))
        check(SHA256.matches(value.getString("migrationManifestSha256")))
    }

    private fun requireTransactionStructure(text: String, expectedKind: String): JSONObject {
        val value = JSONObject(text)
        val schema = value.getInt("schema")
        check(schema == 1 || schema == 2)
        check(value.length() == if (schema == 2) TRANSACTION_FIELDS_V2 else TRANSACTION_FIELDS_V1)
        check(value.getString("kind") == expectedKind)
        check(validPhase(expectedKind, value.getString("phase")))
        check(UUID.matches(value.getString("transactionId")))
        check(UUID.matches(value.getString("generationUuid")))
        requireIdentityShape(value)
        if (schema == 2) check(value.getInt("migrationCount") >= 0)
        if (expectedKind == "MIGRATION") {
            check(SNAPSHOT_ID.matches(value.getString("snapshotId")))
            check(SHA256.matches(value.getString("snapshotDigest")))
        } else {
            check(value.isNull("snapshotId") && value.isNull("snapshotDigest"))
        }
        return value
    }

    private fun validPhase(kind: String, phase: String): Boolean = when (kind) {
        "INIT" -> phase in setOf("OWNED", "RUNNING", "COMMITTING")
        "MIGRATION" -> phase in setOf("SNAPSHOT_READY", "RUNNING", "FAILED")
        else -> false
    }

    // ------------------------------------------------------------------
    // The translation-record consumer gate: verifies an exported baseline
    // against the live clean-stop seal, provider identity, generation,
    // and staged-file intactness before any import may consume it. Pure
    // and JVM-testable by design; the engine supplies the staged-file
    // verification as a predicate.
    // ------------------------------------------------------------------

    enum class TranslationConsumption {
        CONSUMABLE,
        NOT_EXPORTED,          // phase != EXPORTED (mid-export or failed)
        MISSING_SEAL_PIN,      // EXPORTED without the cleanStopSealSha256 pin
        SEAL_PIN_MISMATCH,     // any later provider cycle (start deletes the
                               // seal, stop rewrites it with a fresh timestamp)
        IDENTITY_MISMATCH,     // record predates a provider/manifest update
        GENERATION_MISMATCH,   // record predates a re-initialization
        EMPTY_BASELINE,        // no databases/tables recorded
        STAGING_INCOMPLETE,    // a recorded table's staged TSV failed verification
        INVALID_RECORD,
    }

    /**
     * Decide whether the translation staging may be consumed by the
     * SQLite import leg. Every refusal leg is a documented user promise:
     * a stale baseline (post-export datadir writes, restores, provider
     * updates, re-inits) must never be imported over. The
     * [stagedTableVerified] predicate receives each recorded table's
     * (database, table, rows, sha256, bytes) and must verify the staged
     * file byte-for-byte (existence + recorded values) before any
     * INSERT happens on the consumer side.
     */
    fun translationConsumable(
        recordText: String?,
        liveCleanSealText: String?,
        identity: Identity,
        liveGenerationUuid: String?,
        stagedTableVerified: (database: String, table: String, rows: Long, sha256: String, bytes: Long) -> Boolean,
    ): TranslationConsumption {
        if (recordText == null) return TranslationConsumption.INVALID_RECORD
        val record = runCatching {
            val value = JSONObject(recordText)
            check(value.getInt("schema") == 1)
            value
        }.getOrNull() ?: return TranslationConsumption.INVALID_RECORD
        if (record.optString("phase") != "EXPORTED") return TranslationConsumption.NOT_EXPORTED
        val pin = record.optString("cleanStopSealSha256")
        if (!SHA256.matches(pin)) return TranslationConsumption.MISSING_SEAL_PIN
        val liveSeal = liveCleanSealText ?: return TranslationConsumption.SEAL_PIN_MISMATCH
        if (com.pocketrealm.fs.FileDigests.sha256(liveSeal) != pin) {
            return TranslationConsumption.SEAL_PIN_MISMATCH
        }
        if (record.optString("providerClosureSha256") != identity.providerClosureSha256 ||
            record.optString("migrationManifestSha256") != identity.migrationManifestSha256 ||
            record.optInt("migrationCount", -1) != identity.migrationCount
        ) return TranslationConsumption.IDENTITY_MISMATCH
        if (record.optString("generationUuid") != liveGenerationUuid) {
            return TranslationConsumption.GENERATION_MISMATCH
        }
        val databases = record.optJSONObject("databases")
            ?: return TranslationConsumption.EMPTY_BASELINE
        var tables = 0
        val databasesIterator = databases.keys()
        while (databasesIterator.hasNext()) {
            val database = databasesIterator.next()
            val tablesObject = databases.optJSONObject(database) ?: continue
            val tablesIterator = tablesObject.keys()
            while (tablesIterator.hasNext()) {
                val table = tablesIterator.next()
                val entry = tablesObject.optJSONObject(table)
                    ?: return TranslationConsumption.INVALID_RECORD
                tables++
                val ok = runCatching {
                    stagedTableVerified(
                        database, table,
                        entry.getLong("rows"), entry.getString("sha256"), entry.getLong("bytes"),
                    )
                }.getOrDefault(false)
                if (!ok) return TranslationConsumption.STAGING_INCOMPLETE
            }
        }
        if (tables == 0) return TranslationConsumption.EMPTY_BASELINE
        return TranslationConsumption.CONSUMABLE
    }

    private const val BASE_SEAL_FIELDS = 6 // schema + four identity fields + generation
    private const val TRANSACTION_FIELDS_V1 = 11
    private const val TRANSACTION_FIELDS_V2 = 12
    private const val SNAPSHOT_COMPATIBILITY_FIELDS = 6

    // ------------------------------------------------------------------
    // Provider-mode resolution + the active-provider marker. Pure and
    // JVM-testable; the engine and ServerRuntimeFiles (separate processes)
    // must reach the SAME decision from durable state, so the decision is
    // a function and its durable cache is a codec pair.
    // ------------------------------------------------------------------

    enum class ProviderMode { MARIADB, SQLITE }

    /**
     * The active-provider decision, in precedence order:
     * 1. A VALID SQLite initialized seal AND a sqlite-capable APK mean the
     *    cutover completed - SQLite serves. (A non-sqlite APK with a
     *    dormant sqlite datadir stays MariaDB: that is the window's
     *    APK-level rollback - the MariaDB datadir is never deleted while
     *    the window lasts, so it remains the rollback anchor.)
     * 2. No MariaDB initialized marker at all (fresh install - including
     *    a failed/partial prior init, whose marker only appears at
     *    completion) on a sqlite-capable APK goes straight to SQLite: the
     *    window never bootstraps MariaDB it will immediately translate
     *    away. A non-capable APK bootstraps MariaDB as today.
     * 3. Otherwise MariaDB: an existing MariaDB generation (current or
     *    not - a stale seal must recover through the OLD provider's
     *    fail-closed paths, never silently discard) keeps MariaDB until
     *    the window translation completes.
     */
    fun resolveProviderMode(
        sqliteCapable: Boolean,
        sqliteInitializedSealValid: Boolean,
        mariadbInitializedMarkerPresent: Boolean,
    ): ProviderMode = when {
        sqliteCapable && sqliteInitializedSealValid -> ProviderMode.SQLITE
        sqliteCapable && !mariadbInitializedMarkerPresent -> ProviderMode.SQLITE
        else -> ProviderMode.MARIADB
    }

    /** The durable cache of [resolveProviderMode], written by the engine
     * at every provider transition so :realm/:world can shape their
     * DatabaseInfo without re-deriving (or disagreeing with) the engine. */
    const val ACTIVE_PROVIDER_MARKER_NAME = "active-provider.json"

    fun activeProviderMarker(mode: ProviderMode, providerId: String, generationUuid: String?): String {
        require(providerId.matches(Regex("[A-Za-z0-9._+-]{1,96}")))
        if (mode == ProviderMode.SQLITE) {
            require(generationUuid == null || UUID.matches(generationUuid))
        } else {
            requireNotNull(generationUuid) { "MariaDB mode pins the live generation" }
            check(UUID.matches(generationUuid))
        }
        return JSONObject()
            .put("schema", 1)
            .put("mode", mode.name)
            .put("provider", providerId)
            .put("generationUuid", generationUuid ?: JSONObject.NULL)
            .toString()
    }

    data class ActiveProvider(val mode: ProviderMode, val providerId: String, val generationUuid: String?)

    fun parseActiveProviderMarker(text: String?): ActiveProvider? = runCatching {
        val marker = JSONObject(requireNotNull(text))
        check(marker.length() == 4 && marker.getInt("schema") == 1)
        val mode = when (marker.getString("mode")) {
            "MARIADB" -> ProviderMode.MARIADB
            "SQLITE" -> ProviderMode.SQLITE
            else -> return@runCatching null
        }
        val provider = marker.getString("provider")
        check(provider.matches(Regex("[A-Za-z0-9._+-]{1,96}")))
        check(mode == ProviderMode.MARIADB || provider.startsWith("sqlite-")) {
            "sqlite mode must name a sqlite provider"
        }
        check(mode == ProviderMode.SQLITE || provider.startsWith("mariadb-")) {
            "mariadb mode must name a mariadb provider"
        }
        val generation = if (marker.isNull("generationUuid")) null else marker.getString("generationUuid")
        check(mode == ProviderMode.MARIADB || generation == null || UUID.matches(generation))
        check(mode == ProviderMode.SQLITE || generation != null)
        ActiveProvider(mode, provider, generation)
    }.getOrNull()

    // ------------------------------------------------------------------
    // The interrupted-provisioning recovery
    // decision. The INIT transaction record spans the ENTIRE provision
    // (record written first; deleted only after the final seal+marker
    // commit), so every crash window is covered. The re-provision variant
    // moves the live datadir to a FIXED retire name (recorded by
    // convention) before seeding - recovery restores it rather than ever
    // re-translating from the frozen MariaDB datadir (a hard design rule:
    // post-cutover user state must never be silently discarded).
    // ------------------------------------------------------------------

    enum class ProvisioningRecovery { KEEP_COMPLETED, DISCARD_RECORD, RESTORE_RETIRED, QUARANTINE_AND_RETRY }

    /**
     * The SQLite provider's OWNSHIP closure digest - the
     * provenance asset with `seed_transcripts` REMOVED, canonically
     * re-serialized, hashed. The exclusion is load-bearing: the seed pins
     * change with every corpus advance, and ownership must stay
     * corpus-stable (the MariaDB lane's migration-only-update contract);
     * the corpus rides the migration-manifest identity instead. Pure and
     * JVM-tested: a seed_transcripts-only change leaves the digest
     * unchanged; any provider-build change moves it.
     */
    fun sqliteClosureDigest(provenanceText: String): String =
        com.pocketrealm.fs.FileDigests.sha256(
            JSONObject(provenanceText).apply { remove("seed_transcripts") }.toString(),
        )

    /**
     * Decide how to recover a pending sqlite INIT transaction. Inputs:
     * the record's generation, the LIVE datadir's generation marker (null
     * when absent/empty), whether the live sqlite seals are currently
     * valid, and whether the fixed retire directory exists. Decision
     * order: a fully sealed live datadir owned by the record completes;
     * a live datadir from a DIFFERENT generation means the record predates
     * any move (the old state is intact - discard the record); a retire
     * directory means a re-provision moved the old datadir out (discard
     * the partial and restore it); a partial datadir owned by the record
     * with no retire is a fresh provision (quarantine and retry).
     */
    fun provisioningRecovery(
        recordGenerationUuid: String?,
        liveGenerationUuid: String?,
        liveSealsValid: Boolean,
        retiredPresent: Boolean,
    ): ProvisioningRecovery = when {
        recordGenerationUuid == null -> ProvisioningRecovery.DISCARD_RECORD
        liveGenerationUuid == recordGenerationUuid && liveSealsValid ->
            ProvisioningRecovery.KEEP_COMPLETED
        liveGenerationUuid != null && liveGenerationUuid != recordGenerationUuid ->
            ProvisioningRecovery.DISCARD_RECORD
        retiredPresent -> ProvisioningRecovery.RESTORE_RETIRED
        liveGenerationUuid == recordGenerationUuid -> ProvisioningRecovery.QUARANTINE_AND_RETRY
        else -> ProvisioningRecovery.DISCARD_RECORD
    }
}

/** Pure mutation gate used before any datadir rename/delete/restore. */
internal object DatabaseMutationGate {
    fun permits(
        lifecycleStopped: Boolean,
        runnerThreadAlive: Boolean,
        nativeProcessGroupDrained: Boolean,
        pidProcessExists: Boolean,
    ): Boolean = lifecycleStopped && !runnerThreadAlive && nativeProcessGroupDrained && !pidProcessExists
}
