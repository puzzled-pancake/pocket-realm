package com.pocketrealm.desktop

import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.supervisor.DatabaseStartPreparation
import java.io.File
import org.json.JSONObject

/** Distinct database-refusal codes (DESK-53: never conflate the
 * unseeded, the stale/tampered, and the dirty-generation states). */
internal const val CODE_DB_NOT_SEEDED = "DB-NOT-SEEDED"
internal const val CODE_DB_MIGRATION_PENDING = "DB-MIGRATION-PENDING"
internal const val CODE_DB_PROVIDER_MISMATCH = "DB-PROVIDER-MISMATCH"
internal const val CODE_DB_DIRTY_GENERATION = "DB-DIRTY-GENERATION"
internal const val CODE_DB_BOOTSTRAP = "DB-BOOTSTRAP"

/** The default-flip's legacy-dual-provider refusal (distinct from
 * [CODE_DB_PROVIDER_MISMATCH], which names a provider CLOSURE change over
 * the SQLite datadir): the resolved provider is MariaDB while a populated
 * SQLite datadir sits beside it with NO active-provider marker — booting a
 * fresh MariaDB generation there would strand the legacy state silently,
 * so the start refuses loudly with the adoption remedy instead. */
internal const val CODE_DB_LEGACY_SQLITE = "DB-LEGACY-SQLITE"

/** The engine lane's provider-closure drift refusal (a restaged closure over
 * an EXISTING MariaDB install — distinct from [CODE_DB_PROVIDER_MISMATCH],
 * which names a closure change over the SQLite datadir's seals, and from
 * [CODE_DB_LEGACY_SQLITE]): the datadir's durable seals can no longer prove
 * ownership under the new closure identity (initialized=false +
 * compatibilityMismatch=PROVIDER_IDENTITY over a populated datadir), a
 * state the shared machine would misroute to INITIALIZE, so the start
 * refuses as itself with the re-stage/quarantine remedies. */
internal const val CODE_DB_PROVIDER_CLOSURE_CHANGE = "DB-PROVIDER-CLOSURE-CHANGE"

/** The engine lane's corrupt-durable-seal refusal: the initialized.json
 * marker beside the datadir no longer parses as the ownership seal the
 * engine's own commit writes (initialized=false +
 * compatibilityMismatch=INVALID_DURABLE_STATE over a POPULATED datadir) —
 * corruption the shared machine misroutes to INITIALIZE exactly like a
 * closure change, so the start refuses as itself with the restore and
 * quarantine remedies instead of the misleading bootstrap refusal. */
internal const val CODE_DB_DURABLE_STATE_INVALID = "DB-DURABLE-STATE-INVALID"

/** The engine lane's generation-drift refusal: the durable markers
 * DISAGREE — the initialized.json seal names a generation the datadir's
 * .pocketrealm-generation.json marker does not (initialized=false +
 * compatibilityMismatch=GENERATION over a populated datadir) — drift the
 * shared machine misroutes to INITIALIZE exactly like a closure change, so
 * the start refuses as itself with the restore and quarantine remedies. */
internal const val CODE_DB_GENERATION_DRIFT = "DB-GENERATION-DRIFT"

/** The interrupted-restore recovery (the machine's ROLLBACK_PENDING_RESTORE
 * arm's marker): a durable restore record a dead process left behind was
 * settled by the ownerless engine verb — rolled back, or its commit
 * finished — so the start may proceed over the recovered state. */
internal const val CODE_DB_RESTORE_PENDING = "DB-RESTORE-PENDING"

/** Read a datadir marker's text, or null when it does not exist. */
internal fun readMarker(file: File): String? =
    file.takeIf(File::isFile)?.readText(Charsets.UTF_8)

/**
 * The desktop prepare-for-start gate: drives the
 * shared [DatabaseStartPreparation.next] machine over the SQLite
 * datadir's durable state. The seal proofs feeding it:
 *
 *  - revision record (generation marker): the seed identity + the
 * migration seal's digest, persisted at seed; a marker
 *    without it is a LEGACY datadir (seeded before the seals) — unless
 *    the migration-seal marker survives, in which case the generation
 *    record was lost over a sealed ledger and the datadir refuses as
 * DB-DIRTY-GENERATION (DESK-53 never conflates the dirty generation
 *    with the legacy DB-MIGRATION-PENDING state);
 *  - migration-seal proof (shared DatabaseMigrationSeal): marker bytes
 *    hash to the recorded digest AND the shared validator accepts them
 * against the record's identity — the DESK-79 tamper cross-check;
 *  - currency: the record's corpus/closure digests must equal the
 *    CURRENT app's (recomputed from the packaged provenance) — the
 *    "seeded by an older app version" detector; unavailable (no
 *    packaged pins resolvable) degrades to the internally-consistent
 *    proof only, noted in the READY detail;
 *  - clean-stop seal (shared DatabaseGenerationSeal.clean).
 *
 * The SQLITE lane's machine constants: a materialization is never
 * RUNNING (the machine requires a stopped daemon), and no record lanes
 * are fed here — restorePending/databaseTransactionPending are
 * hardcoded false below. Their desktop writer is the MariaDB engine
 * lane (DesktopMariaDbEngine.status over DESK-100's durable restore
 * record and its init/migration transaction records, fed to the
 * machine verbatim by the MARIADB preflight); a leftover restore
 * record there drives the machine's ROLLBACK_PENDING_RESTORE action,
 * whose recovery leg rides the engine's ownerless rollbackPendingRestore
 * verb (the MARIADB preflight's recovery arm).
 */
internal class DesktopDatabasePreparation(
    private val sqliteDatadir: File,
    private val seedSourceProvider: () -> DesktopSeedLocator.SeedSource?,
) {

    /** One start-admission verdict: [ready] proceeds; every other
     * outcome carries a DISTINCT refusal code plus the honest remedy. */
    data class Verdict(val ready: Boolean, val code: String, val detail: String)

    fun evaluate(): Verdict {
        val record = DesktopSqliteSeals.revisionRecord(
            readMarker(DesktopSqliteSealMarkers.generationMarkerFile(sqliteDatadir)),
        )
        val cleanCurrent = DesktopSqliteSeals.cleanSealCurrent(
            readMarker(DesktopSqliteSealMarkers.cleanMarkerFile(sqliteDatadir)),
        )
        val sealProof = record != null && DesktopSqliteSeals.sealMatchesRecord(
            readMarker(DesktopSqliteSealMarkers.migrationMarkerFile(sqliteDatadir)),
            record,
        )
        val current = currentIdentity()
        val corpusCurrent = record != null && current != null &&
            record.identity.manifestSha256 == current.manifestSha256
        val closureCurrent = record != null && current != null &&
            record.identity.bootstrapSha256 == current.bootstrapSha256
        // The DESK-53 dirty-generation signal: the generation record is
        // gone while the datadir still carries its sealed migration ledger
        // (a renamed-away or corrupt generation marker over a surviving
        // seal) — a tampered/dirty generation that must never masquerade
        // as the pre-seal legacy state, whose datadir carries no ledger at
        // all. (The database files alone are not the signal: every
        // evaluate() caller gates on a fully materialized datadir, legacy
        // ones included.)
        val sealedLedgerSurvives = record == null &&
            readMarker(DesktopSqliteSealMarkers.migrationMarkerFile(sqliteDatadir)) != null
        val status = JSONObject()
            .put("state", "STOPPED")
            .put("restorePending", false)
            .put("databaseTransactionPending", false)
            .put("providerMode", "SQLITE")
            .put("initialized", record != null)
            .put("cleanMarker", cleanCurrent)
            .put(
                "migrationsCurrent",
                sealProof && (current == null || (corpusCurrent && closureCurrent)),
            )
            .put("migrationManifestCount", current?.migrationCount ?: -1)
            .put("migrationSealedCount", record?.identity?.migrationCount ?: -1)
        return verdictFor(
            action = DatabaseStartPreparation.next(status),
            current = current,
            corpusCurrent = corpusCurrent,
            closureCurrent = closureCurrent,
            sealProof = sealProof,
            sealedLedgerSurvives = sealedLedgerSurvives,
        )
    }

    private fun verdictFor(
        action: DatabaseStartPreparation.Action,
        current: DesktopSqliteSeals.Identity?,
        corpusCurrent: Boolean,
        closureCurrent: Boolean,
        sealProof: Boolean,
        sealedLedgerSurvives: Boolean,
    ): Verdict = when (action) {
        DatabaseStartPreparation.Action.READY -> Verdict(
            ready = true,
            code = "",
            detail = "seeded datadir verified; migration seal re-proof ok" +
                if (current == null) " (packaged pins unavailable; record internally consistent)" else "",
        )
        DatabaseStartPreparation.Action.INITIALIZE ->
            if (sealedLedgerSurvives) Verdict(
                ready = false,
                code = CODE_DB_DIRTY_GENERATION,
                detail = "the generation marker is missing while the migration seal remains " +
                    "(removed or tampered); delete $sqliteDatadir and relaunch to re-seed " +
                    "from the packaged transcripts",
            )
            else Verdict(
                ready = false,
                code = CODE_DB_MIGRATION_PENDING,
                detail = "datadir predates the migration seals (seeded by an app version without " +
                    "them); delete $sqliteDatadir and relaunch to re-seed from the packaged transcripts",
            )
        DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION -> Verdict(
            ready = false,
            code = CODE_DB_DIRTY_GENERATION,
            detail = "database generation is not clean-stopped; recover the database " +
                "(Diagnostics) before start",
        )
        DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS -> pinnedMigrationsVerdict(
            current, corpusCurrent, closureCurrent, sealProof,
        )
        DatabaseStartPreparation.Action.PROVISION_SQLITE_PROVIDER -> Verdict(
            ready = false,
            code = CODE_DB_MIGRATION_PENDING,
            detail = "seed corpus advanced past the sealed generation; delete $sqliteDatadir " +
                "and relaunch to re-seed from the packaged transcripts",
        )
        else -> Verdict(
            ready = false,
            code = CODE_DB_MIGRATION_PENDING,
            detail = "pending database transaction record (never produced by the desktop " +
                "lane); recover the database before start",
        )
    }

    private fun pinnedMigrationsVerdict(
        current: DesktopSqliteSeals.Identity?,
        corpusCurrent: Boolean,
        closureCurrent: Boolean,
        sealProof: Boolean,
    ): Verdict = Verdict(
        ready = false,
        code = if (current != null && !closureCurrent) CODE_DB_PROVIDER_MISMATCH
        else CODE_DB_MIGRATION_PENDING,
        detail = when {
            !sealProof ->
                "migration seal does not match the generation marker's record (tampered or " +
                    "stale ledger)"
            current == null -> "record no longer parses against the packaged pins"
            !closureCurrent ->
                "provider closure changed since this datadir was seeded; delete $sqliteDatadir " +
                    "and relaunch to re-seed from the packaged transcripts"
            !corpusCurrent ->
                "seed corpus advanced since this datadir was seeded; delete $sqliteDatadir " +
                    "and relaunch to re-seed from the packaged transcripts"
            else -> "migrations are not current for this datadir"
        },
    )

    /** The CURRENT app's seal identity (DESK-98's seed revision), from
     * the packaged/dev provenance; null when no provenance is
     * resolvable or it does not parse. */
    private fun currentIdentity(): DesktopSqliteSeals.Identity? =
        seedSourceProvider()?.let { source ->
            runCatching {
                DesktopSqliteSeals.identityFromProvenance(source.provenance.readText(Charsets.UTF_8))
            }.getOrNull()
        }
}
