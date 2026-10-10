package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1.2 (completion plan §4 W1 item 2): the MariaDB bootstrap's
 * CREATE DATABASE / GRANT database names are flavor-routed through the
 * registry ([MariaDbBootstrap] over each engine's resolved lane), never a
 * bare vanilla literal. These pins run on BOTH lanes (shared-sources.json
 * shared_tests): the desktop build compiles the same production + test
 * sources straight from the android tree.
 *
 * The per-lane RESOLUTION laws (android: static exactly-one-qualified
 * registry; desktop: ServerRuntimeFiles.serverFlavor()) each require the
 * vanilla lane before any SQL crosses — the recorded W1.2 deferral
 * (DECISIONS-PORT D72) — so the shipped lane's SQL below is pinned
 * byte-equivalent to the legacy literals: flipping a flavor routes the SQL
 * by changing the RESOLUTION, never the composition.
 */
class MariaDbBootstrapFlavorRoutingTest {

    private fun ClientFlavor.DatabaseNames.toNameSet(): Set<String> =
        setOf(realmd, world, characters, logs)

    private fun createDatabases(sql: String): List<String> =
        Regex("""CREATE DATABASE IF NOT EXISTS ([a-z][a-z0-9_]{0,63}) """)
            .findAll(sql).map { it.groupValues[1] }.toList()

    @Test fun perFlavorDatabaseNameSetsArePinned() {
        assertEquals(
            setOf("classicrealmd", "classicmangos", "classiccharacters", "classiclogs"),
            ClientFlavors.VANILLA.databases.toNameSet(),
        )
        assertEquals(
            setOf("tbcrealmd", "tbcmangos", "tbccharacters", "tbclogs"),
            ClientFlavors.TBC.databases.toNameSet(),
        )
        assertEquals(
            setOf("wotlkrealmd", "wotlkmangos", "wotlkcharacters", "wotlklogs"),
            ClientFlavors.WOTLK.databases.toNameSet(),
        )
    }

    @Test fun perFlavorNameSetsAreDisjoint() {
        val sets = ClientFlavors.ALL.map { it.databases.toNameSet() }
        assertEquals(3, sets.size)
        assertEquals(12, sets.reduce(Set<String>::union).size)
    }

    @Test fun everyRegistryNamePassesTheSharedShapeGuard() {
        // the guard the engines route through accepts every registry entry
        for (flavor in ClientFlavors.ALL) {
            assertEquals(flavor.databases, MariaDbBootstrap.databaseNames(flavor))
        }
    }

    @Test fun shapeGuardRefusesAMalformedRegistryNameFailLoud() {
        // the drifted copy rides the TBC lane's names (the vanilla names stay
        // in their two owning pins below — the set pin and the legacy-SQL
        // byte-equivalence pin); the guard is name-agnostic, so the failure
        // shape it proves is identical
        val drifted = ClientFlavors.TBC.copy(
            databases = ClientFlavor.DatabaseNames(
                realmd = "Tbc Realmd", world = "tbcmangos",
                characters = "tbccharacters", logs = "tbclogs",
            ),
        )
        val failure = runCatching { MariaDbBootstrap.databaseNames(drifted) }
            .exceptionOrNull()
        assertTrue("malformed name must refuse loud", failure is IllegalStateException)
        assertTrue(
            "the refusal names the offending flavor and role: ${failure?.message}",
            failure!!.message!!.contains("tbc") && failure.message!!.contains("realmd"),
        )
    }

    @Test fun initialAuthSqlRoutesExactlyTheResolvedFlavorsNames() {
        for (flavor in ClientFlavors.ALL) {
            val names = MariaDbBootstrap.databaseNames(flavor)
            val sql = MariaDbBootstrap.initialAuthSql(names, "admin-pw", "core-pw")
            // all four CREATE DATABASE literals ride the resolved names,
            // followed by the flavor-agnostic meta database
            assertEquals(
                "flavor ${flavor.flavorId}: the four CREATE DATABASE literals + meta",
                listOf(
                    names.realmd, names.characters, names.logs, names.world,
                    "pocketrealm_meta",
                ),
                createDatabases(sql),
            )
            // the GRANT surface scopes to exactly those four databases
            for (name in names.toNameSet()) {
                assertTrue(
                    "flavor ${flavor.flavorId}: GRANT ... ON $name.* present",
                    sql.contains("ON $name.* TO 'pocket_core'@'localhost'"),
                )
            }
            // no OTHER flavor's names may leak into the composition
            for (other in ClientFlavors.ALL - flavor) {
                for (foreign in other.databases.toNameSet()) {
                    assertFalse(
                        "flavor ${flavor.flavorId}: foreign name $foreign must not appear",
                        sql.contains(foreign),
                    )
                }
            }
        }
    }

    @Test fun initialAuthSqlKeepsTheFlavorAgnosticMetaDatabaseAndCredentials() {
        val sql = MariaDbBootstrap.initialAuthSql(
            MariaDbBootstrap.databaseNames(ClientFlavors.VANILLA), "admin-pw", "core-pw",
        )
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS pocketrealm_meta"))
        assertTrue(sql.contains("GRANT SELECT ON pocketrealm_meta.* TO 'pocket_core'@'localhost'"))
        assertTrue(sql.contains("'pocket_admin'@'localhost' IDENTIFIED BY 'admin-pw'"))
        assertTrue(sql.contains("'pocket_core'@'localhost' IDENTIFIED BY 'core-pw'"))
        assertTrue(sql.contains("ALTER USER 'root'@'localhost' IDENTIFIED BY 'admin-pw'"))
        assertTrue(sql.contains("FLUSH PRIVILEGES;"))
    }

    @Test fun vanillaCompositionIsByteEquivalentToTheLegacyBootstrapLiterals() {
        // the shipped lane's SQL must be byte-identical to the text the
        // engines shipped before W1.2 (the deferral's whole point: the
        // refactor moves the NAMES' SOURCE, never the shipped behavior)
        val sql = MariaDbBootstrap.initialAuthSql(
            MariaDbBootstrap.databaseNames(ClientFlavors.VANILLA), "A", "C",
        )
        assertEquals(
            """
            CREATE USER IF NOT EXISTS 'pocket_admin'@'localhost' IDENTIFIED BY 'A';
            ALTER USER 'pocket_admin'@'localhost' IDENTIFIED BY 'A';
            GRANT ALL PRIVILEGES ON *.* TO 'pocket_admin'@'localhost' WITH GRANT OPTION;
            CREATE DATABASE IF NOT EXISTS classicrealmd CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE DATABASE IF NOT EXISTS classiccharacters CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE DATABASE IF NOT EXISTS classiclogs CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE DATABASE IF NOT EXISTS classicmangos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE DATABASE IF NOT EXISTS pocketrealm_meta CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE USER IF NOT EXISTS 'pocket_core'@'localhost' IDENTIFIED BY 'C';
            ALTER USER 'pocket_core'@'localhost' IDENTIFIED BY 'C';
            GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classicrealmd.* TO 'pocket_core'@'localhost';
            GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classiccharacters.* TO 'pocket_core'@'localhost';
            GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classiclogs.* TO 'pocket_core'@'localhost';
            GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES,EXECUTE,CREATE ROUTINE,ALTER ROUTINE ON classicmangos.* TO 'pocket_core'@'localhost';
            GRANT SELECT ON pocketrealm_meta.* TO 'pocket_core'@'localhost';
            ALTER USER 'root'@'localhost' IDENTIFIED BY 'A';
            FLUSH PRIVILEGES;
            """.trimIndent(),
            sql,
        )
    }
    @Test fun initialAuthSqlItselfRefusesAnUnvalidatedNameAtTheInterpolationPoint() {
        // R2-r4-1 (code-red round 4): DatabaseNames is publicly constructible,
        // so the composition API cannot assume its input passed the resolver —
        // the shape guard must live at the interpolation point itself
        // (red-first against the pre-round-4 composition, which interpolated
        // verbatim)
        val hostile = ClientFlavor.DatabaseNames(
            realmd = "x`; DROP TABLE classicrealmd; --",
            world = "classicmangos",
            characters = "classiccharacters",
            logs = "classiclogs",
        )
        val failure = runCatching {
            MariaDbBootstrap.initialAuthSql(hostile, "admin-pw", "core-pw")
        }.exceptionOrNull()
        assertTrue(
            "initialAuthSql must refuse a non-identifier name before any SQL is built",
            failure is IllegalArgumentException,
        )
        assertTrue(
            "the refusal names the role and the remedy: ${failure?.message}",
            failure!!.message!!.contains("realmd") &&
                failure.message!!.contains("databaseNames()"),
        )
        // and the guards cover every interpolated role, not just the first
        for (bad in listOf(
            ClientFlavor.DatabaseNames("classicrealmd", "classicmangos", "classiccharacters", "classiclogs; --"),
            ClientFlavor.DatabaseNames("classicrealmd", "classicmangos; --", "classiccharacters", "classiclogs"),
            ClientFlavor.DatabaseNames("classicrealmd", "classicmangos", "classiccharacters; --", "classiclogs"),
        )) {
            assertTrue(
                "every role is guarded at composition: $bad",
                runCatching { MariaDbBootstrap.initialAuthSql(bad, "a", "c") }
                    .exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test fun initialAuthSqlRefusesASecretThatEscapesTheIdentifierLiteral() {
        // R2-r5-1 (code-red round 5): the credential literals interpolate
        // into IDENTIFIED BY '…' — the composition must refuse anything that
        // could escape that literal (the generated secrets are hex-48, but
        // the composition API cannot assume its input passed a validated
        // store), red-first against the pre-round-5 composition which
        // interpolated verbatim.
        val names = MariaDbBootstrap.databaseNames(ClientFlavors.VANILLA)
        // R3-r6-1/R2-r6-1 (code-red round 6): the escape class also refuses
        // the trailing BACKSLASH (MariaDB's default escape mode lets '
        // consume the closing quote) and the EMPTY credential (IDENTIFIED
        // BY ''); generated secrets are hex-48 so neither is legitimate
        val backslash = "pw" + Character.toString(92)
        for ((parameter, hostile) in listOf(
            "adminSecret" to "x'; GRANT ALL PRIVILEGES ON *.* TO 'pocket_core'@'localhost;--",
            "adminSecret" to "pw`",
            "coreSecret" to "c;--",
            "coreSecret" to " padded",
            "adminSecret" to "padded ",
            "adminSecret" to backslash,
            "coreSecret" to "",
            "adminSecret" to "",
        )) {
            val (admin, core) =
                if (parameter == "adminSecret") hostile to "core-pw" else "admin-pw" to hostile
            val failure = runCatching {
                MariaDbBootstrap.initialAuthSql(names, admin, core)
            }.exceptionOrNull()
            assertTrue(
                "initialAuthSql must refuse the escaping $parameter: $hostile",
                failure is IllegalArgumentException,
            )
            assertTrue(
                "the refusal names the parameter and the remedy: ${failure?.message}",
                failure!!.message!!.contains(parameter) &&
                    failure.message!!.contains("validated"),
            )
        }
    }
}
