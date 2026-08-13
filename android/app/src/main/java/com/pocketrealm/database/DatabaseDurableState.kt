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

    private const val BASE_SEAL_FIELDS = 6 // schema + four identity fields + generation
    private const val TRANSACTION_FIELDS_V1 = 11
    private const val TRANSACTION_FIELDS_V2 = 12
    private const val SNAPSHOT_COMPATIBILITY_FIELDS = 6
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
