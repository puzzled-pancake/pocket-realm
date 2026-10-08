package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.GeneratedTimeframeOverlayCatalog
import com.pocketrealm.client.TimeframeStages

/**
 * The stage-overlay decision surface, SHARED by both platforms' appliers
 * (design-timeframe §2/§4.3): the default-stage verdict, the pinned
 * overlay's statement plan (split + the INSERT OR IGNORE-only policy), and
 * the pocketrealm_meta stage-state bookkeeping text. Android-free so the
 * exact same statements and policy execute through the desktop SQLite seam
 * and the Android SQLiteDatabase seam — the lanes cannot fork.
 *
 * v1 honesty (mirrors the manifests): the staged overlays are the inert
 * marker scaffolding; the DEFAULT stage applies no world-DB SQL at all
 * (the seed IS the final patch — existing worlds unaffected).
 */
internal object TimeframeOverlayPlan {

    /** The meta table carrying the single applied-stage row (beside the
     * engine's ledger DDL, created identically when an applier runs
     * first). */
    const val STAGE_STATE_DDL: String = """
        CREATE TABLE IF NOT EXISTS timeframe_stage_state (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          applied_stage TEXT NOT NULL,
          overlay_sha256 TEXT NOT NULL,
          applied_at INTEGER NOT NULL
        );
    """

    const val APPLIED_STAGE_QUERY: String =
        "SELECT applied_stage FROM timeframe_stage_state WHERE id = 1;"
    const val APPLIED_SHA_QUERY: String =
        "SELECT overlay_sha256 FROM timeframe_stage_state WHERE id = 1;"
    const val APPLIED_AT_QUERY: String =
        "SELECT applied_at FROM timeframe_stage_state WHERE id = 1;"

    /** The statement excerpt a policy refusal quotes (readability, not security). */
    const val POLICY_EXCERPT_CHARS = 80

    /** The one qualified lane the world runs (the shared exactly-one gate;
     * the vanilla fallback keeps the failure mode a loud overlay-missing
     * refusal instead of a zero-flavor crash). */
    fun qualifiedLane(): ClientFlavor =
        ClientFlavors.qualified().singleOrNull() ?: ClientFlavors.VANILLA

    /** True when [desiredStage] is the qualified lane's final patch — the
     * seed's own state: record it, touch no world DB. */
    fun isDefaultStage(desiredStage: String): Boolean =
        desiredStage == TimeframeStages.defaultStageId(qualifiedLane().flavorId)

    /** The pinned catalog digest for one stage, or null when unstaged. */
    fun overlaySha256(stageId: String): String? =
        GeneratedTimeframeOverlayCatalog.overlaySha256[stageId]

    /** The overlay's statement plan for one stage: the pinned catalog SQL
     * split by the shared seed scanner, every statement policy-checked to
     * be a flag-set INSERT OR IGNORE (never a destructive one — design §2:
     * backward moves re-apply an earlier overlay over the same datadir, so
     * nothing may be destroyed). Throws loudly on any hostile statement. */
    fun statementsFor(desiredStage: String): List<String> {
        val sql = GeneratedTimeframeOverlayCatalog.overlaySql(desiredStage)
            ?: throw IllegalStateException(unstagedRefusal(desiredStage))
        return policyChecked(DatabaseSqliteControlPlane.splitSeedStatements(sql), desiredStage)
    }

    /** The statement policy, also applied by the execution legs to any
     * statement list they receive (defense-in-depth: a compromised catalog
     * cannot smuggle a destructive statement past an applier). */
    fun policyChecked(statements: List<DatabaseSqliteControlPlane.SeedStatement>, desiredStage: String): List<String> {
        check(statements.isNotEmpty()) {
            "timeframe overlay for \"$desiredStage\" stages no statements"
        }
        return statements.map { statement ->
            val firstCode = statement.sql.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
                ?: ""
            check(firstCode.uppercase().startsWith("INSERT OR IGNORE INTO")) {
                "timeframe overlay for \"$desiredStage\" carries a non-flag-set " +
                    "statement (only INSERT OR IGNORE is allowed): " +
                    statement.sql.take(POLICY_EXCERPT_CHARS)
            }
            statement.sql
        }
    }

    /** The refusal copy for a stage whose overlay is not staged in v1
     * (tbc/wotlk: the qualified lane only). */
    fun unstagedRefusal(desiredStage: String): String =
        "timeframe overlay: no overlay staged for \"$desiredStage\" " +
            "(staged stages: " +
            GeneratedTimeframeOverlayCatalog.overlaySha256.keys.sorted() + ")"

    /** The world DB of the qualified lane (classicmangos today). */
    fun worldDatabase(): String = qualifiedLane().databases.world

    // ------------------------------------------------------------------
    // The MariaDB dialect of the same decision surface. The desktop's
    // active provider defaults to MARIADB (Settings.databaseProvider's
    // default-flip), so the overlay must execute against the LIVE
    // MariaDB generation's world database — through the engine's own
    // client, with MariaDB's INSERT/upsert grammar. The SQLite dialect
    // above stays the pinned catalog form; these legs translate it
    // MECHANICALLY so the two lanes cannot fork on statement content.
    // ------------------------------------------------------------------

    /**
     * The MariaDB stage-state table. The SQLite leg's meta store is a
     * FILE (pocketrealm_meta.sqlite3); MariaDB's is the pocketrealm_meta
     * SCHEMA the engine creates beside migration_ledger, so the DDL
     * carries the qualifier and the dialect (INT/BIGINT; no rowid
     * INTEGER-PK form).
     */
    const val MARIADB_STAGE_STATE_DDL: String = """
        CREATE TABLE IF NOT EXISTS pocketrealm_meta.timeframe_stage_state (
          id INT PRIMARY KEY CHECK (id = 1),
          applied_stage TEXT NOT NULL,
          overlay_sha256 TEXT NOT NULL,
          applied_at BIGINT NOT NULL
        );
    """

    /** The MariaDB applied-state read: one row, three tab-separated
     * columns (stage, sha, applied_at) — the client's --batch
     * --skip-column-names shape; empty output = no record yet. */
    const val MARIADB_APPLIED_STAGE_QUERY: String =
        "SELECT applied_stage,overlay_sha256,applied_at FROM " +
            "pocketrealm_meta.timeframe_stage_state WHERE id = 1;"

    /**
     * The MariaDB meta upsert: `ON DUPLICATE KEY UPDATE` over the id
     * primary key (the SQLite leg's ON CONFLICT(id)/excluded grammar is
     * not MariaDB's). Same escaping discipline as [stageStateUpsert].
     */
    fun mariadbStageStateUpsert(stage: String, overlaySha256: String, appliedAtMs: Long): String {
        val stageLiteral = stage.replace("'", "''")
        val shaLiteral = overlaySha256.replace("'", "''")
        return "INSERT INTO pocketrealm_meta.timeframe_stage_state " +
            "(id, applied_stage, overlay_sha256, applied_at) " +
            "VALUES (1, '$stageLiteral', '$shaLiteral', $appliedAtMs) " +
            "ON DUPLICATE KEY UPDATE applied_stage = VALUES(applied_stage), " +
            "overlay_sha256 = VALUES(overlay_sha256), applied_at = VALUES(applied_at);"
    }

    /**
     * The MariaDB translation of the vetted statement plan: the pinned
     * catalog is SQLite-dialect (`INSERT OR IGNORE INTO` — the policy's
     * own vocabulary), and MariaDB's ignore-duplicate form is
     * `INSERT IGNORE INTO`. Only that mechanical prefix changes, and
     * only on the statement's first CODE line (the shared splitter keeps
     * a statement's leading comment block attached — the raw statement
     * text starts with its comments, never with the INSERT); any
     * statement that is not the policy's own INSERT OR IGNORE form is
     * refused (defense-in-depth with [policyChecked], which the caller
     * runs on the untranslated plan first).
     */
    fun toMariaadbDialect(statements: List<String>, desiredStage: String): List<String> =
        statements.map { statement ->
            val lines = statement.lines()
            val firstCode = lines.indexOfFirst { it.isNotBlank() && !it.trim().startsWith("--") }
            check(firstCode >= 0 && lines[firstCode].trim().uppercase().startsWith("INSERT OR IGNORE INTO")) {
                "timeframe overlay for \"$desiredStage\" carries a non-flag-set " +
                    "statement (only INSERT OR IGNORE is allowed): " +
                    statement.take(POLICY_EXCERPT_CHARS)
            }
            lines.mapIndexed { index, line ->
                if (index == firstCode) line.replaceFirst(INSERT_OR_IGNORE, "INSERT IGNORE") else line
            }.joinToString("\n")
        }

    private val INSERT_OR_IGNORE = Regex("(?i)INSERT\\s+OR\\s+IGNORE")

    /** The meta upsert: both platforms' appliers execute exactly this
     * statement (DesktopSqlite binds no parameters; the stage ids are
     * registry constants and the digest is hex, with the quote escaped
     * defensively anyway). */
    fun stageStateUpsert(stage: String, overlaySha256: String, appliedAtMs: Long): String {
        val stageLiteral = stage.replace("'", "''")
        val shaLiteral = overlaySha256.replace("'", "''")
        return "INSERT INTO timeframe_stage_state (id, applied_stage, overlay_sha256, applied_at) " +
            "VALUES (1, '$stageLiteral', '$shaLiteral', $appliedAtMs) " +
            "ON CONFLICT(id) DO UPDATE SET applied_stage = excluded.applied_stage, " +
            "overlay_sha256 = excluded.overlay_sha256, applied_at = excluded.applied_at;"
    }
}
