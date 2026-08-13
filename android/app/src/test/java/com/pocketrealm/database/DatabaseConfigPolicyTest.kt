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

    private fun render(abi: String) = DatabaseConfigPolicy.render(
        abi = abi,
        datadir = "/data/db",
        socket = "/data/run/mariadb.sock",
        pidFile = "/data/run/mariadb.pid",
        errorLog = "/data/run/mariadb.err",
        secureFileDirectory = "/data/import",
    )
}
