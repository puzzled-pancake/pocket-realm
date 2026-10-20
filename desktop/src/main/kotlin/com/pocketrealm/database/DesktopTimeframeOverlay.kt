package com.pocketrealm.database

import com.pocketrealm.fs.FileDigests
import java.io.File

/**
 * Design-timeframe §2/§4.3: the DESKTOP stage-overlay applier — applied at
 * world start, BEFORE native boot, when the desired stage differs from the
 * durably-recorded applied one, with the ledger row written on success and
 * ANY failure refusing the world start loudly (never boot a world whose DB
 * state contradicts the conf). The decision surface (default verdict,
 * statement plan, meta bookkeeping) is the SHARED [TimeframeOverlayPlan];
 * this object is the desktop SQLite-seam execution leg. The Android lane's
 * execution leg is `TimeframeOverlayApplier` over the same plan, so the
 * two platforms cannot fork.
 *
 * v1 honesty (design §9.1/§9.2, mirrored by the manifests): the staged
 * overlays carry the stage-MARKER scaffolding only — reversible, idempotent
 * `INSERT OR IGNORE` statements against the seed's own `game_event` /
 * `conditions` schema, in id ranges measured absent from the pinned seed.
 * With no measured demotion rows referencing them (the DB pin's work),
 * the markers are inert and the DEFAULT stage applies no world-DB SQL at
 * all — existing worlds are unaffected by construction.
 *
 * The applied stage lives in the engine-created `pocketrealm_meta`
 * database (its ledger DDL is the shared [DatabaseSqliteControlPlane]
 * shape, created here identically when this lane runs first), so "this
 * datadir is at stage X" survives restarts. PROVIDER-AWARE since the
 * MariaDB-first default-flip: a MARIADB active-provider marker routes
 * the overlay to the MariaDB engine leg
 * (DesktopMariaDbEngine.applyTimeframeOverlay — same shared plan,
 * MariaDB's INSERT IGNORE dialect, the stage ledger in the MariaDB
 * pocketrealm_meta schema); this object executes only when the SQLite
 * lane is live, and refuses (never silently substitutes) a MariaDB
 * world it was handed without that leg.
 */
// One reviewed mechanism object: the desired/applied ledger resolution,
// the seam execution, and the meta bookkeeping - the design §4.3
// world-start leg in one place.
@Suppress("TooManyFunctions")
internal object DesktopTimeframeOverlay {

    /** The durable applied-stage record (the meta ledger row). */
    data class AppliedState(
        val appliedStage: String,
        val overlaySha256: String,
        val appliedAtMs: Long,
    )

    fun readAppliedStage(sqliteDatadir: File): String? {
        if (!DatabaseSqliteControlPlane.databaseFile(
                sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
            ).isFile
        ) {
            return null
        }
        openMeta(sqliteDatadir).use { meta ->
            return meta.queryText(TimeframeOverlayPlan.APPLIED_STAGE_QUERY)
        }
    }

    /**
     * The world-start leg: bring the datadir from its recorded stage to
     * [desiredStage] (the concrete stage the world conf names). The DEFAULT
     * stage is the seed itself: recorded, never executed against the world
     * DB. Returns the recorded state.
     *
     * PROVIDER-AWARE (the MariaDB-first default-flip): the durable
     * active-provider marker routes the leg BEFORE any short-circuit —
     * a MARIADB marker hands the WHOLE overlay (every stage, the default
     * one included) to [mariadbLeg], the engine's MariaDB execution leg,
     * and this SQLite datadir is a retained artifact its ledger must
     * never speak for (a durable SQLite ledger already naming the
     * desired stage must not license a MariaDB world whose DB never
     * received the overlay — the guard's law, kept structural: the
     * marker branch returns before the SQLite ledger is ever read). A
     * MARIADB marker with no leg supplied refuses loudly — the SQLite
     * execution below must never write a MariaDB world's stage.
     */
    fun prepareForStart(
        sqliteDatadir: File,
        desiredStage: String,
        mariadbLeg: ((desiredStage: String) -> AppliedState)? = null,
    ): AppliedState {
        if (activeProviderIsMariaDb(sqliteDatadir)) {
            val leg = checkNotNull(mariadbLeg) {
                "timeframe overlay: the active provider is MARIADB - the overlay applies " +
                    "through the MariaDB engine leg (DesktopMariaDbEngine.applyTimeframeOverlay), " +
                    "never against the retained SQLite datadir"
            }
            return leg.invoke(desiredStage)
        }
        val applied = readAppliedStage(sqliteDatadir)
        if (applied == desiredStage) {
            return recordedState(sqliteDatadir, desiredStage)
        }
        return when {
            TimeframeOverlayPlan.isDefaultStage(desiredStage) ->
                recordApplied(sqliteDatadir, desiredStage, overlaySha256 = "default-seed")
            else -> applyAndRecord(sqliteDatadir, desiredStage)
        }
    }

    /** The durable active-provider verdict (the ServerRuntimeFiles conf
     * branch's exact source): true when the marker names MARIADB. */
    private fun activeProviderIsMariaDb(sqliteDatadir: File): Boolean {
        val marker = DatabaseDurableState.parseActiveProviderMarker(
            File(sqliteDatadir.parentFile, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
                .takeIf(File::isFile)?.readText(),
        )
        return marker?.mode == DatabaseDurableState.ProviderMode.MARIADB
    }

    /** The non-default leg: pinned statement plan, execution, ledger (the
     * lane check already ran in [prepareForStart]). */
    private fun applyAndRecord(sqliteDatadir: File, desiredStage: String): AppliedState {
        val statements = TimeframeOverlayPlan.statementsFor(desiredStage)
        applyOverlay(sqliteDatadir, desiredStage, statements)
        return recordApplied(sqliteDatadir, desiredStage, overlaySha256 = stagedSha256(desiredStage))
    }

    private fun stagedSha256(desiredStage: String): String =
        TimeframeOverlayPlan.overlaySha256(desiredStage)
            ?: throw IllegalStateException(TimeframeOverlayPlan.unstagedRefusal(desiredStage))

    /** Execute the plan's statements against the world DB. The statement
     * list is RE-policy-checked here too (defense-in-depth with the plan's
     * own check — an applier never executes an unvetted statement), which
     * is also the hostile-SQL test seam. */
    internal fun applyOverlay(
        sqliteDatadir: File,
        desiredStage: String,
        statements: List<String>,
    ) {
        val vetted = TimeframeOverlayPlan.policyChecked(
            statements.mapIndexed { index, sql ->
                DatabaseSqliteControlPlane.SeedStatement(index, 0, sql)
            },
            desiredStage,
        )
        val worldDb = DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, TimeframeOverlayPlan.worldDatabase(),
        )
        check(worldDb.isFile) {
            "timeframe overlay: the world database is missing at $worldDb - " +
                "the datadir must be seeded before a non-default stage can apply"
        }
        DesktopSqliteConnection(worldDb.absolutePath).use { db ->
            vetted.forEach { statement -> db.exec(statement) }
        }
    }

    private fun recordApplied(
        sqliteDatadir: File,
        stage: String,
        overlaySha256: String,
    ): AppliedState {
        val appliedAt = System.currentTimeMillis()
        openMeta(sqliteDatadir).use { meta ->
            meta.exec(TimeframeOverlayPlan.stageStateUpsert(stage, overlaySha256, appliedAt))
        }
        return AppliedState(stage, overlaySha256, appliedAt)
    }

    private fun recordedState(sqliteDatadir: File, stage: String): AppliedState {
        openMeta(sqliteDatadir).use { meta ->
            val sha = meta.queryText(TimeframeOverlayPlan.APPLIED_SHA_QUERY)
            val at = meta.queryLong(TimeframeOverlayPlan.APPLIED_AT_QUERY)
            return AppliedState(stage, sha ?: "", at ?: 0L)
        }
    }

    /** Opens (creating if absent) the meta database with the engine's
     * ledger DDL plus the stage-state table; closed connections leave no
     * WAL sidecars (the clean-stop seal's contract). */
    private fun openMeta(sqliteDatadir: File): DesktopSqliteConnection {
        val file = DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
        )
        file.parentFile?.mkdirs()
        return DesktopSqliteConnection(file.absolutePath).also { meta ->
            meta.exec(DatabaseSqliteControlPlane.LEDGER_DDL)
            meta.exec(TimeframeOverlayPlan.STAGE_STATE_DDL)
            meta.queryText("PRAGMA journal_mode=WAL;")
        }
    }
}
