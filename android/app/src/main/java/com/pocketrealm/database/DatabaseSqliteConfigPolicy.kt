package com.pocketrealm.database

/**
 * Fixed connection policy for the in-tree SQLite backend.
 *
 * Durability contract: SQLite WAL with `synchronous=NORMAL`. WAL keeps
 * crash consistency - a power cut or process kill rolls back to the last
 * WAL checkpoint with NO corruption possible - and the auto-checkpoint
 * cadence bounds the rollback window to seconds of game progress. The
 * previous setting (`synchronous=FULL`, fsync per commit, power-cut parity
 * with MariaDB trx_commit=1) was retired deliberately: on consumer flash
 * it costs battery and commit latency for a guarantee game state does not
 * need (losing seconds of bot progress on a battery pull is acceptable;
 * losing the character database is not, and WAL already prevents that).
 *
 * Read performance: `cache_size` is raised to 64 MiB per connection - the
 * 1.36M-row content corpus is read-heavy and the amalgamation's 2 MB
 * default page cache starved bulk loads (the 223k-cell bot equipment
 * cache build).
 *
 * These pragmas are applied per connection by the hardened DO_SQLITE
 * backend; this object is the single source of truth both the native policy
 * and the SQLite provider are checked against.
 */
internal object DatabaseSqliteConfigPolicy {
    const val SYNCHRONOUS = "NORMAL"
    const val BUSY_TIMEOUT_MS = 500
    const val CACHE_SIZE_KIB = 65_536

    fun renderConnectionPragmas(): List<String> = listOf(
        "PRAGMA journal_mode=WAL;",
        "PRAGMA synchronous=$SYNCHRONOUS;",
        "PRAGMA busy_timeout=$BUSY_TIMEOUT_MS;",
        "PRAGMA foreign_keys=OFF;",
        "PRAGMA secure_delete=OFF;",
        "PRAGMA cache_size=-$CACHE_SIZE_KIB;",
    )
}
