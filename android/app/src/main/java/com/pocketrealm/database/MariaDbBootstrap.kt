package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavor

/**
 * W1.2 (completion plan §4 W1 item 2): the MariaDB first-run bootstrap's
 * per-flavor surface. The CREATE DATABASE / GRANT literals the two engine
 * twins (android DatabaseEngine, desktop DesktopMariaDbEngine) used to ship
 * as bare vanilla text now compose from the RESOLVED lane's
 * [ClientFlavor.DatabaseNames] — so the names come from the flavor registry
 * (the same registry every other per-flavor consumer routes through) and a
 * future qualification flip changes the registry, never this SQL.
 *
 * The resolution LAW stays each engine's own (the recorded W1.2 deferral,
 * docs/DECISIONS-PORT.md D72: resolve fail-loud, require the vanilla lane,
 * refuse BEFORE any SQL crosses — android from the static registry, desktop
 * from ServerRuntimeFiles.serverFlavor()); this object owns the shape guard
 * and the SQL composition both twins share, so the twins cannot drift.
 *
 * JVM-clean by the shared-sources contract: no android imports (compiled
 * straight into the desktop build from desktop/shared-sources.json).
 */
object MariaDbBootstrap {

    /** The database-name shape every interpolated identifier must satisfy —
     * the engines' own DATABASE_NAME regex, hoisted so the guard and the
     * consumers cannot disagree. Plain lowercase identifiers only: the names
     * are interpolated into GRANT ... ON <db>.* statements, never bound. */
    const val NAME_PATTERN = "[a-z][a-z0-9_]{0,63}"
    val NAME_REGEX = Regex(NAME_PATTERN)

    /** The secret-escape class the composition refuses (R2-r5-1, widened in
     * R3-r6-1/R2-r6-1): anything that could terminate the IDENTIFIED BY '…'
     * literal or smuggle a statement boundary — quote, backtick, semicolon,
     * leading/trailing whitespace, and the BACKSLASH (under MariaDB's
     * default escape mode a trailing backslash consumes the closing quote).
     * The generated secrets are hex-48 and match this by construction. */
    private val SECRET_ESCAPE = Regex("""['`;\\]|^\s|\s$""")

    /**
     * The resolved lane's four core database names, each shape-guarded
     * fail-loud before a caller may interpolate any of them into SQL. A
     * registry entry that drifts out of identifier shape refuses here with
     * the offending flavor named, never at the SQL layer.
     */
    fun databaseNames(flavor: ClientFlavor): ClientFlavor.DatabaseNames {
        val names = flavor.databases
        for ((role, name) in listOf(
            "realmd" to names.realmd,
            "world" to names.world,
            "characters" to names.characters,
            "logs" to names.logs,
        )) {
            check(NAME_REGEX.matches(name)) {
                "flavor ${flavor.flavorId}: $role database name " +
                    "'$name' is not a plain identifier (must match ${NAME_PATTERN})"
            }
        }
        return names
    }

    /**
     * The initial-auth bootstrap SQL the engines run once after the pinned
     * provider bootstrap: the four flavor-routed CREATE DATABASE literals +
     * the pocket_core least-privilege grants scoped to exactly those
     * databases. `pocketrealm_meta` is the app's own flavor-agnostic meta
     * database and stays a literal. The credential literals thread through
     * from the caller exactly as before (the generated secrets never at rest
     * outside secrets.json; both engines delete the transcript after the
     * verb that consumed it).
     *
     * R2-r4-1 (code-red round 4): the shape guard lives HERE at the
     * interpolation point, not only in [databaseNames] — [names] is a bare
     * publicly-constructible data class that carries no proof it passed the
     * resolver, so the composition itself refuses any name that could break
     * out of an identifier before a single byte of SQL is built.
     *
     * R2-r5-1 (code-red round 5): the same rule applies to the credential
     * literals — both twins generate hex secrets, but a corrupted or
     * hand-edited desktop secrets.json flows verbatim into root-context
     * SQL, so the composition refuses any secret that could escape the
     * IDENTIFIED BY '…' literal or smuggle a statement boundary (quote,
     * backtick, semicolon, leading or trailing whitespace) before a single
     * byte of SQL is built.
     *
     * R3-r6-1/R2-r6-1 (code-red round 6): the escape class also refuses the
     * BACKSLASH — under MariaDB/MySQL's default escape mode a trailing
     * backslash consumes the closing quote (' swallows it), and an EMPTY
     * credential composes IDENTIFIED BY '' — both accepted by the round-5
     * class and now refused loud. The generated secrets are hex-48, so
     * neither character is ever legitimate.
     */
    fun initialAuthSql(
        names: ClientFlavor.DatabaseNames,
        adminSecret: String,
        coreSecret: String,
    ): String {
        for ((role, name) in listOf(
            "realmd" to names.realmd,
            "characters" to names.characters,
            "logs" to names.logs,
            "world" to names.world,
        )) {
            require(NAME_REGEX.matches(name)) {
                "initialAuthSql: $role database name '$name' is not a plain " +
                    "identifier (must match ${NAME_PATTERN}) — resolve the " +
                    "names through databaseNames(), never compose SQL from " +
                    "unvalidated literals"
            }
        }
        for ((parameter, secret) in listOf(
            "adminSecret" to adminSecret,
            "coreSecret" to coreSecret,
        )) {
            require(secret.isNotEmpty()) {
                "initialAuthSql: $parameter is empty — a credential literal " +
                    "must never compose to IDENTIFIED BY ''; resolve secrets " +
                    "from the engine's validated store, never compose SQL " +
                    "from unvalidated literals"
            }
            require(!SECRET_ESCAPE.containsMatchIn(secret)) {
                "initialAuthSql: $parameter contains a character that escapes " +
                    "the IDENTIFIED BY literal (quote, backtick, semicolon, " +
                    "or leading/trailing whitespace) — the generated secrets " +
                    "are hex; resolve secrets from the engine's validated " +
                    "store, never compose SQL from unvalidated literals"
            }
        }
        return """
        CREATE USER IF NOT EXISTS 'pocket_admin'@'localhost' IDENTIFIED BY '$adminSecret';
        ALTER USER 'pocket_admin'@'localhost' IDENTIFIED BY '$adminSecret';
        GRANT ALL PRIVILEGES ON *.* TO 'pocket_admin'@'localhost' WITH GRANT OPTION;
        CREATE DATABASE IF NOT EXISTS ${names.realmd} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.characters} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.logs} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.world} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS pocketrealm_meta CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE USER IF NOT EXISTS 'pocket_core'@'localhost' IDENTIFIED BY '$coreSecret';
        ALTER USER 'pocket_core'@'localhost' IDENTIFIED BY '$coreSecret';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.realmd}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.characters}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.logs}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES,EXECUTE,CREATE ROUTINE,ALTER ROUTINE ON ${names.world}.* TO 'pocket_core'@'localhost';
        GRANT SELECT ON pocketrealm_meta.* TO 'pocket_core'@'localhost';
        ALTER USER 'root'@'localhost' IDENTIFIED BY '$adminSecret';
        FLUSH PRIVILEGES;
        """.trimIndent()
    }

    /**
     * W4 (D72 retirement, owner ruling 2026-10-12): the IDEMPOTENT per-lane
     * provisioning SQL the engines run before a lane's migration ledger
     * applies — the four CREATE DATABASE IF NOT EXISTS of [names] plus the
     * pocket_core grants scoped to exactly those databases. The first
     * initialize() created only the FIRST lane's family; every further
     * qualified lane provisions its own here. The same shape/secret guards
     * as [initialAuthSql] apply (the interpolation point IS the guard).
     */
    fun laneProvisionSql(
        names: ClientFlavor.DatabaseNames,
    ): String {
        for ((role, name) in listOf(
            "realmd" to names.realmd,
            "characters" to names.characters,
            "logs" to names.logs,
            "world" to names.world,
        )) {
            require(NAME_REGEX.matches(name)) {
                "laneProvisionSql: $role database name '$name' is not a plain " +
                    "identifier (must match ${NAME_PATTERN}) — resolve the " +
                    "names through databaseNames(), never compose SQL from " +
                    "unvalidated literals"
            }
        }
        return """
        CREATE DATABASE IF NOT EXISTS ${names.realmd} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.characters} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.logs} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        CREATE DATABASE IF NOT EXISTS ${names.world} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.realmd}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.characters}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON ${names.logs}.* TO 'pocket_core'@'localhost';
        GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES,EXECUTE,CREATE ROUTINE,ALTER ROUTINE ON ${names.world}.* TO 'pocket_core'@'localhost';
        FLUSH PRIVILEGES;
        """.trimIndent()
    }
}
