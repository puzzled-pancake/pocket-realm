package com.pocketrealm.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAL sync policy for the SQLite lane: WAL +
 * synchronous=NORMAL - crash-consistent, power cut rolls back to the last
 * WAL checkpoint (bounded progress loss, no corruption). The earlier
 * per-commit-fsync contract (synchronous=FULL) was retired for its
 * battery/latency cost on consumer flash; game state tolerates seconds of
 * rollback, not database loss, and WAL already prevents the latter.
 */
class DatabaseSqlitePolicyTest {
    @Test fun walSyncPolicyIsNormalWithCheckpointBoundedDurability() {
        val pragmas = DatabaseSqliteConfigPolicy.renderConnectionPragmas()

        assertTrue("PRAGMA synchronous=NORMAL;" in pragmas)
        // FULL is the retired per-commit-fsync contract; OFF would disable
        // WAL syncs entirely and is not the shipped contract either.
        assertFalse(pragmas.any { it.contains("synchronous=FULL") })
        assertFalse(pragmas.any { it.contains("synchronous=OFF") })
        assertTrue("PRAGMA journal_mode=WAL;" in pragmas)
    }

    @Test fun busyTimeoutIsNotTheTwoMillisecondHazard() {
        // The in-tree backend shipped busy_timeout=2ms with a silent
        // false-on-BUSY: dropped writes and a wedged write transaction under
        // the bot save waves. The policy pins the hardened value the
        // connection layer must apply.
        val pragmas = DatabaseSqliteConfigPolicy.renderConnectionPragmas()
        assertTrue("PRAGMA busy_timeout=500;" in pragmas)
        assertEquals(500, DatabaseSqliteConfigPolicy.BUSY_TIMEOUT_MS)
    }

    @Test fun pageCacheCoversTheReadHeavyCorpus() {
        // The 1.36M-row content corpus is read-heavy; the amalgamation's
        // 2 MB default page cache starved bulk loads (equipment-cache
        // build). Negative KiB form = bytes-of-cache, per SQLite docs.
        val pragmas = DatabaseSqliteConfigPolicy.renderConnectionPragmas()
        assertTrue("PRAGMA cache_size=-65536;" in pragmas)
        assertEquals(65_536, DatabaseSqliteConfigPolicy.CACHE_SIZE_KIB)
    }

    @Test fun contractIsStableForBothEngineLanes() {
        assertEquals("NORMAL", DatabaseSqliteConfigPolicy.SYNCHRONOUS)
    }
}
