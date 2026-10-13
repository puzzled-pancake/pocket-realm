package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-5 (code-red round 1): [MariaDbBootstrap.laneProvisionSql] is a
 * security-shaped guard — per-lane CREATE DATABASE/GRANT composition with a
 * fail-closed identifier guard. Pin the guard's rejection and each lane's
 * golden shape (the four DB names in all four CREATE/GRANT families, no
 * admin-credential touch).
 */
class MariaDbBootstrapLaneProvisionTest {

    private fun names(realmd: String, world: String, characters: String, logs: String) =
        ClientFlavor.DatabaseNames(
            realmd = realmd, world = world, characters = characters, logs = logs,
        )

    @Test
    fun laneProvisionSqlCarriesEachLaneDatabaseFamily() {
        for ((lane, names) in mapOf(
            "vanilla" to names(
                "classicrealmd", "classicmangos", "classiccharacters", "classiclogs",
            ),
            "tbc" to names("tbcrealmd", "tbcmangos", "tbccharacters", "tbclogs"),
            "wotlk" to names("wotlkrealmd", "wotlkmangos", "wotlkcharacters", "wotlklogs"),
        )) {
            val sql = MariaDbBootstrap.laneProvisionSql(names)
            for (name in listOf(names.realmd, names.world, names.characters, names.logs)) {
                assertTrue(
                    "$lane: CREATE DATABASE missing for $name",
                    "CREATE DATABASE IF NOT EXISTS $name" in sql,
                )
            }
            assertEquals("$lane: the realmd grant covers the family once", 1, sql.split("ON ${names.realmd}.*").size - 1)
            assertEquals("$lane: the world grant covers the family once", 1, sql.split("ON ${names.world}.*").size - 1)
            assertEquals("$lane: the characters grant covers the family once", 1, sql.split("ON ${names.characters}.*").size - 1)
            assertEquals("$lane: the logs grant covers the family once", 1, sql.split("ON ${names.logs}.*").size - 1)
        }
    }

    @Test
    fun laneProvisionSqlRefusesNonIdentifierNames() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MariaDbBootstrap.laneProvisionSql(
                names("tbcrealmd; DROP TABLE x", "tbcmangos", "tbccharacters", "tbclogs"),
            )
        }
        assertTrue(
            "the guard names the offending role: ${failure.message}",
            "realmd database name" in failure.message!!,
        )
        val caseFailure = assertThrows(IllegalArgumentException::class.java) {
            MariaDbBootstrap.laneProvisionSql(
                names("tbcrealmd", "tbcmangos", "TBCCHARACTERS", "tbclogs"),
            )
        }
        assertTrue(
            "the guard is case-sensitive: ${caseFailure.message}",
            "characters database name" in caseFailure.message!!,
        )
    }

    @Test
    fun laneProvisionSqlNeverGrantsAdmin() {
        val sql = MariaDbBootstrap.laneProvisionSql(
            names("tbcrealmd", "tbcmangos", "tbccharacters", "tbclogs"),
        )
        assertTrue(
            "the provision leg must never touch the admin credential",
            "pocket_admin" !in sql,
        )
        assertTrue(
            "the provision leg creates no users (initialize owns the credentials)",
            "IDENTIFIED BY" !in sql,
        )
    }
}
