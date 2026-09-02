package com.pocketrealm.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseConfigPolicyTest {
    @Test fun armPolicyUsesFlashAndSocketOptimizationsWithoutRelaxingDurability() {
        val config = render("arm64-v8a")

        assertTrue(config.contains("innodb-flush-neighbors=0"))
        assertTrue(config.contains("host-cache-size=0"))
        assertFalse(config.contains("thread-cache-size"))
        assertTrue(config.contains("innodb-flush-log-at-trx-commit=1"))
        assertTrue(config.contains("skip-networking=1"))
        assertTrue(config.contains("skip-name-resolve=1"))
        assertFalse(config.contains("innodb-doublewrite=0"))
        assertFalse(config.contains("innodb-flush-log-at-trx-commit=0"))
        assertFalse(config.contains("innodb-flush-log-at-trx-commit=2"))
    }

    @Test fun x86QualificationPolicyDoesNotInheritUnmeasuredArmOverrides() {
        val config = render("x86_64")

        assertFalse(config.contains("innodb-flush-neighbors"))
        assertFalse(config.contains("host-cache-size"))
        assertFalse(config.contains("thread-cache-size"))
        assertTrue(config.contains("innodb-flush-log-at-trx-commit=1"))
    }

    @Test fun powerCutContractsPerEngineAsAmended() {
        // The engines carry deliberately
        // per-engine power-cut contracts. MariaDB (the server lane) keeps
        // trx_commit=1 (zero committed loss). The SQLite lane (the
        // recommended on-device engine) uses WAL + synchronous=NORMAL:
        // crash-consistent, power cut rolls back to the last WAL
        // checkpoint - bounded seconds of game-progress loss, never
        // corruption - because per-commit fsync costs battery and commit
        // latency on consumer flash that game state does not need.
        val config = render("arm64-v8a")
        assertTrue(config.contains("innodb-flush-log-at-trx-commit=1"))

        val pragmas = DatabaseSqliteConfigPolicy.renderConnectionPragmas()
        assertTrue("PRAGMA synchronous=NORMAL;" in pragmas)
        assertFalse(pragmas.any { it.contains("synchronous=FULL") })
    }

    private fun render(abi: String) = DatabaseConfigPolicy.render(
        abi = abi,
        datadir = "/data/db",
        socket = "/data/run/mariadb.sock",
        pidFile = "/data/run/mariadb.pid",
        errorLog = "/data/run/mariadb.err",
        secureFileDirectory = "/data/import",
    )
}
