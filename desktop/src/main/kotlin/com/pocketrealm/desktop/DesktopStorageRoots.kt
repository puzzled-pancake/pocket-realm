package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import java.io.File

/**
 * Desktop path policy — the twin of the Android StorageRoots layout, rooted
 * at %LOCALAPPDATA%\PocketRealm (per-user, no elevation, no Program Files
 * write issues). Subdirectory names mirror the Android StorageRoots contract
 * so shared policies that reason about layout (import generations, run
 * artifacts) keep their shape.
 *
 * Root separation policy (the Android StorageRoots taxonomy, DESK-129/131):
 *  - [scratch] is the transient staging workspace (import extraction,
 *    seeder partials). Windows has no OS cache-pressure clearing, so the
 *    app owns the purge: [ensureDirectories] empties it on every clean
 *    start. It NEVER holds durable realm state.
 *  - [exports] is the ONLY user-facing output root (realm archives,
 *    support-style bundles) — never the live database, mirroring the
 *    Android "exports are never the live DB" invariant.
 *  - [databaseRun]/[databaseSnapshots] split the database root the same
 *    way Android splits databaseDatadir/databaseRun/databaseSnapshots
 *    (the snapshot store's lane consumes them).
 */
class DesktopStorageRoots(baseDir: File? = null) {
    val root: File = baseDir ?: defaultRoot()

    /** Durable supervisor journal + recovery records. */
    val realm: File = File(root, "realm")

    /** Seeded realm databases (the SQLite provider's datadir lives under
     * here, mirroring the Android database root layout). */
    val database: File = File(root, "database")

    /** Imported client content + prepared DBC/maps data. */
    val content: File = File(root, "content")

    /** Runtime working state (conf files, logs, run markers). */
    val runtime: File = File(root, "runtime")

    /** Long-lived user settings. */
    val settings: File = File(root, "settings")

    /** transient import/verification staging (the Android
     * StorageRoots scratch root's policy — staging space, never durable
     * realm state; Windows has no OS cache-pressure clearing, so clean-up
     * is the app's own job). Lives under the runtime root (the app-owned
     * cache analog); [ensureDirectories] purges it on every clean start. */
    val scratch: File = File(runtime, "scratch")

    /** the ONLY user-facing output root (realm archives,
     * support bundles) — never the live database. */
    val exports: File = File(root, "exports")

    val settingsFile: File = File(settings, "settings.json")

    val supervisorJournalDir: File = File(realm, "runtime-supervisor")

    val logs: File = File(runtime, "logs")

    /** The SQLite provider's datadir (the shared control plane's
     * SQLITE_DATADIR_NAME under the database root). */
    val sqliteDatadir: File = File(database, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)

    /** the MariaDB provider's datadir — a SIBLING of the
     * sqlite datadir under the database root, mirroring the Android
     * dual-datadir layout (each provider's state stays distinct). */
    val mariadbDatadir: File = File(database, "mariadb-datadir")

    /** stopped-state, hash-verified datadir snapshots —
     * the Android StorageRoots contract's databaseSnapshots layout
     * (`database/snapshots`) mirrored under the desktop database root. */
    val databaseSnapshots: File = File(database, "snapshots")

    /** Daemon/lock-file area of the database root (the Android
     * databaseRun split; free while the desktop provider is in-process). */
    val databaseRun: File = File(database, "run")

    fun ensureDirectories() {
        // sqliteDatadir too (the Android twin mkdirs databaseDatadir at
        // construction): an EMPTY datadir is the unseeded state the
        // bootstrap lane seeds — its existence is not a seed proof.
        val failed = listOf(
            root, realm, database, sqliteDatadir, mariadbDatadir, content, runtime, settings,
            supervisorJournalDir, logs, scratch, exports, databaseRun, databaseSnapshots,
        ).filter { !it.mkdirs() && !it.isDirectory }
        // a misconfigured D50 override (a
        // regular file, an un-creatable drive) previously booted into
        // scattered raw IO failures — mkdirs results were ignored and the
        // truthful verify().ok was consulted by nothing on the boot path.
        // ONE actionable error naming the override and the first
        // un-creatable root is the fail-fast posture instead.
        if (failed.isNotEmpty()) {
            val override = System.getProperty(ENV_DATA_ROOT)?.takeIf { it.isNotBlank() }
                ?: System.getenv(ENV_DATA_ROOT)?.takeIf { it.isNotBlank() }
                ?: "<unset>"
            error(
                "the PocketRealm data root is not usable: cannot create " +
                    "${failed.first().absolutePath} (${failed.size} root(s) failed). " +
                    "POCKET_REALM_DATA_ROOT is $override — fix or clear the " +
                    "override (the D50 relocation), then start again.",
            )
        }
        purgeScratch()
    }

    /** The app-owned scratch purge (Windows never clears caches under
     * pressure): staging leftovers from a previous session must not
     * outlive a clean start. Durable roots are never touched. */
    fun purgeScratch() {
        if (!scratch.isDirectory) return
        scratch.listFiles()?.forEach { child ->
            if (!child.deleteRecursively()) {
                child.deleteOnExit()
            }
        }
    }

    /** Verify the root layout is fully present; the diagnostics storage
     * card mirrors the Android wizard/diagnostics report (per-root
     * exists + usable bytes). */
    fun verify(): Report {
        val roots = listOf(
            "realm" to realm,
            "database" to database,
            "database/datadir" to sqliteDatadir,
            "database/mariadb-datadir" to mariadbDatadir,
            "database/run" to databaseRun,
            "database/snapshots" to databaseSnapshots,
            "content" to content,
            "runtime" to runtime,
            "runtime/scratch" to scratch,
            "settings" to settings,
            "exports" to exports,
        )
        return Report(
            roots = roots.map { (name, dir) ->
                RootStatus(name, dir.absolutePath, dir.isDirectory, usableBytes(dir))
            },
            rootsUnderBase = roots.all { (_, dir) -> dir.startsWith(root) },
        )
    }

    private fun usableBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(-1L)

    data class RootStatus(val name: String, val path: String, val exists: Boolean, val usableBytes: Long)

    data class Report(val roots: List<RootStatus>, val rootsUnderBase: Boolean) {
        val ok: Boolean get() = roots.all { it.exists } && rootsUnderBase
    }

    companion object {
        /**
         * The desktop product's data root. D50 (round-4): the
         * E-drive rule is honored through the additive [ENV_DATA_ROOT]
         * override — set it and every root lands under that directory
         * (the ANDROID_SDK_ROOT pattern: env first, another host overrides
         * without editing); UNSET, the shipped default stays byte-identical
         * to the historical %LOCALAPPDATA%\PocketRealm location. A
         * portable-root + migration as product behavior is a deliberately
         * deferred future lane, not this override.
         */
        const val ENV_DATA_ROOT = "POCKET_REALM_DATA_ROOT"

        /**
         * the hermetic env-leg seam for the default-arm tests — a JVM
         * cannot clear an env var, so a runner whose process carries the
         * D50 user-scope POCKET_REALM_DATA_ROOT (every shell started after
         * the W0 relocation) made the two LOCALAPPDATA-default assertions
         * machine-dependent (the exact D44 class). Null = the live
         * environment (production, zero behavior change); a test sets it
         * to "" to simulate the override being absent (blank falls
         * through, the documented blank arm). The property seam above
         * still wins first.
         */
        @Volatile
        internal var hermeticEnvLeg: String? = null

        fun defaultRoot(): File {
            System.getProperty(ENV_DATA_ROOT)?.let { configured ->
                if (configured.isNotBlank()) return configuredRoot(configured)
            }
            val envLeg = hermeticEnvLeg ?: System.getenv(ENV_DATA_ROOT)
            envLeg?.let { configured ->
                if (configured.isNotBlank()) return configuredRoot(configured)
            }
            return File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                "PocketRealm",
            )
        }

 /** (desktop-twin ): a RELATIVE override resolves
         * per-process CWD — the same env value yields different physical
         * roots for the rpHost harness vs a jpackage install vs a dev run,
         * and with a CWD on C: the whole durable tree lands on C: while
         * e-gate's env legs only match C-drive-prefixed values. The
         * override must be an ABSOLUTE path, or the boot fails with the
         * same actionable shape as the other misconfigured arms. */
        private fun configuredRoot(configured: String): File {
            val candidate = File(configured)
            check(candidate.isAbsolute) {
                "POCKET_REALM_DATA_ROOT must be an ABSOLUTE path (got " +
                    "'$configured') — a relative value resolves per-process " +
                    "CWD, so the rpHost harness, a jpackage install and a dev " +
                    "run would each land the durable tree somewhere else. " +
                    "Set an absolute path (the D50 relocation), then start again."
            }
            return candidate
        }
    }
}
