package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseDurableState
import com.pocketrealm.database.DatabaseRuntimeContract
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopMariaDbEngine
import com.pocketrealm.database.DesktopMariaDbProvider
import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.fs.FileDigests
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The default-flip's pinning suite (the desktop's shipped database provider
 * becomes MariaDB, matching the on-device product):
 *
 *  - default resolution = MARIADB: a store that never chose a provider
 *    resolves the ENGINE lane, and a first boot (no active-provider marker,
 *    nothing initialized) BOOTSTRAPS the MariaDB datadir in-preflight —
 *    never the SQLite datadir — leaving the durable marker naming MARIADB
 *    and a READY second preflight;
 *  - the legacy-dual-provider guard: a populated SQLite datadir with NO
 *    marker under a MariaDB resolution refuses LOUDLY with the distinct
 *    DB-LEGACY-SQLITE code and the adoption remedy (select the SQLITE
 *    provider explicitly, or delete the datadir) — never a silent MariaDB
 *    boot over stranded user state — while a marker naming MARIADB clears
 *    the guard (the populated datadir is dormant legacy state by the
 *    durable decision) and the EXPLICIT SQLITE selection adopts the
 *    datadir (it serves, decided by the SQLite lane's own gates);
 *  - the durable-seal drift guard: a RESTAGED closure over an existing
 *    install (initialized=false + compatibilityMismatch=PROVIDER_IDENTITY
 *    over a populated datadir — the state the shared machine misroutes to
 *    INITIALIZE, whose bootstrap wedges on the non-empty datadir) refuses
 *    as its own DB-PROVIDER-CLOSURE-CHANGE verdict with the re-stage and
 *    quarantine remedies, surfaces the mismatch in the DATABASE
 *    observation, and the quarantine consent really does bootstrap fresh —
 *    and the SAME guard covers the other drift kinds over a populated
 *    datadir (a corrupt initialized.json seal → DB-DURABLE-STATE-INVALID;
 *    a generation drift between the durable markers → DB-GENERATION-DRIFT)
 *    while a genuinely EMPTY datadir keeps the first-boot INITIALIZE
 *    bootstrap;
 *  - the fail-closed dual-provider guards stay intact: DB-PROVIDER-MISMATCH
 *    (the closure-change refusal) and the shared marker cross-claim codec
 *    are pinned by DesktopRuntimeBackendTest and the shared
 *    DatabaseDurableStateTest respectively and run in the same suite.
 *
 * JVM-only: the MariaDB lane rides the scripted engine seam (a synthetic
 * staged distribution + scripted processes, mirrored from
 * DesktopRuntimeBackendTest), so every leg is deterministic on machines
 * with or without a real staged provider distribution.
 */
class DesktopDatabaseProviderDefaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun spec() = com.pocketrealm.supervisor.RuntimeLaunchSpec(
        mode = com.pocketrealm.supervisor.RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = com.pocketrealm.supervisor.RealmEndpoint.LOCAL,
        includeClient = false,
    )

    // ------------------------------------------------------------------
    // Fixtures (the staged-provider + scripted-process seam contract,
    // mirrored from DesktopRuntimeBackendTest so the MARIADB lane runs
    // JVM-only)
    // ------------------------------------------------------------------

    @Suppress("LongMethod") // the staging contract's document tree, one fixture
    private fun stageMariaDbProvider(base: File): File {
        val repo = File(base, "repo").apply { mkdirs() }
        val staging = File(repo, "native/.build-win-x86_64/mariadb-provider").apply { mkdirs() }
        File(staging, "bin").mkdirs()
        val bootstrap = File(staging, "bootstrap.sql").apply { writeText("CREATE TABLE t (id INT);\n") }
        val mariadbd = File(staging, "bin/mariadbd.exe").apply { writeBytes(byteArrayOf(7, 4, 11, 5, 2)) }
        val mariadb = File(staging, "bin/mariadb.exe").apply { writeBytes(byteArrayOf(11, 5, 2)) }
        val manifest = JSONObject()
            .put("schema", 1)
            .put("provider", DatabaseRuntimeContract.WINDOWS_PROVIDER_ID)
            .put("abi", "windows-x86_64")
            .put("bootstrap_sha256", FileDigests.sha256(bootstrap))
            .put(
                "executables", JSONObject()
                    .put(
                        "mariadbd", JSONObject()
                            .put("name", "bin/mariadbd.exe")
                            .put("size", mariadbd.length())
                            .put("sha256", FileDigests.sha256(mariadbd)),
                    )
                    .put(
                        "mariadb", JSONObject()
                            .put("name", "bin/mariadb.exe")
                            .put("size", mariadb.length())
                            .put("sha256", FileDigests.sha256(mariadb)),
                    ),
            )
        val manifestFile = File(staging, "runtime-manifest.json").apply { writeText(manifest.toString()) }
        val migrationManifest = JSONObject()
            .put("schema", 1)
            .put("entries", org.json.JSONArray())
            .put("source_commits", JSONObject().put("cmangos", "c" + "0".repeat(39)))
            .put("app_build_id", "test-build")
            .put(
                "expected_revisions", JSONObject()
                    .put("realm", "realmd_db_version")
                    .put("characters", "character_db_version")
                    .put("logs", "logs_db_version")
                    .put("world", "db_version"),
            )
        val migrationManifestFile = File(staging, "migration-manifest.json")
            .apply { writeText(migrationManifest.toString()) }
        File(repo, "schemas").mkdirs()
        fun artifactPin(file: File): JSONObject = JSONObject()
            .put("name", file.toRelativeString(staging).replace(File.separatorChar, '/'))
            .put("path", file.toRelativeString(repo).replace(File.separatorChar, '/'))
            .put("size", file.length())
            .put("sha256", FileDigests.sha256(file))
        File(repo, DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH).writeText(
            JSONObject()
                .put("schema", 1)
                .put("abi", "windows-x86_64")
                .put("database_backend", "mariadb")
                .put(
                    "provider",
                    JSONObject().put("id", DatabaseRuntimeContract.WINDOWS_PROVIDER_ID),
                )
                .put("manifest_sha256", FileDigests.sha256(manifestFile))
                .put("bootstrap_sha256", FileDigests.sha256(bootstrap))
                .put("migration_manifest_sha256", FileDigests.sha256(migrationManifestFile))
                .put("migration_count", 0)
                .put(
                    "artifacts", org.json.JSONArray()
                        .put(artifactPin(mariadbd))
                        .put(artifactPin(mariadb)),
                )
                .toString(),
        )
        return staging
    }

    /** The scripted MariaDB process seam (the engine twin's, mirrored): the
     * daemon is a tracked fake pid; the client answers the fixed queries;
     * SHUTDOWN ends the daemon. */
    private class ScriptedMariaDbProcesses : DesktopMariaDbEngine.Processes {

        var daemonRunning = false
        var daemonPid = DAEMON_PID

        private fun ok(stdout: String = "") =
            com.pocketrealm.database.DatabaseRunResult(0, 0, false, stdout, "")

        private fun refused(detail: String) =
            com.pocketrealm.database.DatabaseRunResult(1, 0, false, "", detail)

        override fun runTool(
            executable: File,
            args: List<String>,
            environment: Map<String, String>,
            stdin: File?,
            timeoutMs: Long,
        ): com.pocketrealm.database.DatabaseRunResult {
            if (args.contains("--bootstrap")) {
                val datadir = File(args.first { it.startsWith("--datadir=") }.removePrefix("--datadir="))
                File(datadir, "mysql").mkdirs()
                File(datadir, "mysql/global_priv.MYD").writeText("privileges")
                return ok()
            }
            val sql = stdin?.readText() ?: ""
            return when {
                "SHUTDOWN" in sql -> {
                    daemonRunning = false
                    ok()
                }
                "POCKET_DB_OK" in sql -> ok("POCKET_DB_OK\t11.5.2-MariaDB\t1")
                "pocket_forbidden" in sql -> refused("scripted: account administration denied")
                "required_z9999_not_real" in sql -> ok("0")
                "information_schema.COLUMNS" in sql -> ok("1")
                "pocket_admin" in sql -> ok()
                "migration_ledger" in sql -> ok()
                else -> refused("scripted: unexpected client sql: ${sql.take(80)}")
            }
        }

        override fun startDaemon(executable: File, args: List<String>): Long {
            daemonRunning = true
            return daemonPid
        }

        override fun alive(pid: Long): Boolean = daemonRunning && pid == daemonPid

        override fun killTree(pid: Long): Boolean {
            daemonRunning = false
            return pid == daemonPid
        }

        override fun pipeExists(name: String): Boolean = daemonRunning && name == "mariadb.sock"

        companion object {
            /** Not a multiple of 4, so it can never collide with a real
             * Windows pid (least of all the test JVM's own). */
            const val DAEMON_PID = 4242L
        }
    }

    /** A backend whose MariaDB lane runs a SCRIPTED engine over synthetic
     * staging (deterministic on every machine). */
    private fun scriptedMariaDbBackend(roots: DesktopStorageRoots): DesktopRuntimeBackend {
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val engine = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = ScriptedMariaDbProcesses(),
        )
        return DesktopRuntimeBackend(roots).apply { mariaDbEngineOverride = engine }
    }

    /** The active-provider marker file under the database root. */
    private fun activeProviderMarkerFile(roots: DesktopStorageRoots): File =
        File(roots.database, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)

    // ------------------------------------------------------------------
    // Default resolution = MARIADB, first boot initializes MariaDB
    // ------------------------------------------------------------------

    @Test
    fun defaultResolutionBootstrapsTheMariaDbLaneOnAFirstBoot() {
        // The default-flip's core leg: NO settings file (a store that never
        // chose a provider) resolves MARIADB, and the first boot — no
        // active-provider marker, nothing initialized — bootstraps the
        // MariaDB datadir through the engine lane IN-PREFLIGHT. The SQLite
        // datadir is never seeded, and the engine's commit leaves the
        // durable marker naming MARIADB before any server conf can exist.
        val roots = DesktopStorageRoots(folder.newFolder("fresh-mariadb"))
        roots.ensureDirectories()
        assertFalse("precondition: no settings file exists", roots.settingsFile.isFile)
        assertEquals(
            "precondition: the shipped default resolves MARIADB",
            com.pocketrealm.storage.Settings.DatabaseProvider.MARIADB,
            DesktopSettingsStore(roots.settingsFile).load().databaseProvider,
        )
        val backend = scriptedMariaDbBackend(roots)
        try {
            val first = runBlocking { backend.preflight(spec()) }
            assertTrue("the first boot must bootstrap MariaDB: ${first.detail}", first.ok)
            assertTrue(
                "must be the engine-lane bootstrap record: ${first.detail}",
                "DB-BOOTSTRAP:" in first.detail && "mariadb provider lane" in first.detail,
            )
            assertTrue(
                "the engine's initialized generation must exist",
                File(roots.mariadbDatadir, "mysql/global_priv.MYD").isFile,
            )
            DATABASES.forEach { database ->
                assertFalse(
                    "a MariaDB first boot must NEVER seed the SQLite datadir ($database)",
                    DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile,
                )
            }
            val marker = DatabaseDurableState.parseActiveProviderMarker(
                activeProviderMarkerFile(roots).takeIf(File::isFile)?.readText(),
            )
            assertEquals(
                "the durable marker must name MARIADB (the engine wrote it at commit)",
                DatabaseDurableState.ProviderMode.MARIADB,
                marker?.mode,
            )
            // The bootstrapped lane then admits: the second preflight is the
            // machine's READY verdict, not another bootstrap.
            val second = runBlocking { backend.preflight(spec()) }
            assertTrue("the bootstrapped lane must verify: ${second.detail}", second.ok)
            assertTrue(
                "the second preflight must be the READY verdict: ${second.detail}",
                "mariadb generation verified" in second.detail,
            )
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------
    // The provider-closure drift guard (a restaged closure over an
    // existing MariaDB install)
    // ------------------------------------------------------------------

    @Test
    fun aRestagedProviderClosureOverAnInitializedDatadirRefusesAsItsOwnVerdict() {
        // An install that outlives a provider restage: the lockfile + staged
        // manifest change (a different closure identity), so the datadir's
        // durable seals can no longer prove ownership — initialized=false
        // with compatibilityMismatch=PROVIDER_IDENTITY over a POPULATED
        // datadir. The shared DatabaseStartPreparation machine cannot tell
        // that from a first boot (it answers INITIALIZE), and the bootstrap
        // it routes to wedges forever: initialize() refuses "datadir is
        // non-empty without init marker" (reproduced live as
        // DB-BOOTSTRAP-FAILED), and the engine's recover() refuses likewise
        // (dirtyRecoveryPermitted requires an initialized generation). The
        // preflight must refuse as the closure change ITSELF — the re-stage
        // and quarantine remedies — and the DATABASE observation must
        // surface the mismatch instead of the first-boot copy.
        val roots = DesktopStorageRoots(folder.newFolder("closure-drift"))
        roots.ensureDirectories()
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        fun freshEngine() = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        // The install: bootstrapped + migrated under the ORIGINAL closure,
        // then clean-stopped (realm state on disk).
        val install = freshEngine()
        assertTrue(install.initialize().getBoolean("ok"))
        assertTrue(install.applyPinnedMigrations().getBoolean("ok"))
        val note = File(roots.mariadbDatadir, "realm_note.txt").apply { writeText("legacy state") }
        // The restage: new bootstrap bytes, manifest + lockfile re-pinned —
        // a verified closure with a DIFFERENT identity over the same roots.
        val bootstrap = File(staging, "bootstrap.sql").apply { writeText("CREATE TABLE t (id BIGINT);\n") }
        val manifestFile = File(staging, "runtime-manifest.json")
        manifestFile.writeText(
            JSONObject(manifestFile.readText()).put("bootstrap_sha256", FileDigests.sha256(bootstrap)).toString(),
        )
        val lockfile = File(
            staging.parentFile.parentFile.parentFile,
            DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH,
        )
        lockfile.writeText(
            JSONObject(lockfile.readText())
                .put("manifest_sha256", FileDigests.sha256(manifestFile))
                .put("bootstrap_sha256", FileDigests.sha256(bootstrap))
                .toString(),
        )
        // The post-restage process: a FRESH engine (the closure is cached
        // per lifetime) behind a fresh backend — exactly the wedged start.
        val drifted = freshEngine()
        val status = drifted.status()
        assertTrue("the restaged closure itself verifies", status.getBoolean("providerReady"))
        assertFalse("the drifted closure can no longer prove the seals", status.getBoolean("initialized"))
        assertEquals(
            "the honest mismatch name (nothing consumed it before)",
            "PROVIDER_IDENTITY",
            status.getString("compatibilityMismatch"),
        )
        val backend = DesktopRuntimeBackend(roots).apply { mariaDbEngineOverride = drifted }
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertFalse("closure drift must refuse the start: ${preflight.detail}", preflight.ok)
            assertTrue(
                "the refusal must be the dedicated closure-change verdict: ${preflight.detail}",
                preflight.detail.startsWith("DB-PROVIDER-CLOSURE-CHANGE"),
            )
            assertTrue(
                "the copy must name the re-stage remedy: ${preflight.detail}",
                "Re-stage" in preflight.detail,
            )
            assertTrue(
                "the copy must name the quarantine-then-bootstrap remedy: ${preflight.detail}",
                "quarantin" in preflight.detail,
            )
            assertFalse(
                "never the wedged bootstrap leg: ${preflight.detail}",
                "DB-BOOTSTRAP" in preflight.detail || "non-empty without init marker" in preflight.detail,
            )
            assertEquals(
                "the refusal must leave the legacy datadir untouched",
                "legacy state",
                note.readText(),
            )
            // The diagnostics surface: the DATABASE observation carries the
            // mismatch by name, never the first-boot "bootstrap leg pending".
            val observation = runBlocking {
                backend.observe(com.pocketrealm.supervisor.RuntimeComponent.DATABASE)
            }
            assertTrue(
                "the observation must surface the compatibility mismatch: ${observation.detail}",
                "compatibilityMismatch=PROVIDER_IDENTITY" in observation.detail &&
                    "DB-PROVIDER-CLOSURE-CHANGE" in observation.detail,
            )
            assertFalse(
                "never the first-boot copy over drifted seals: ${observation.detail}",
                "bootstrap leg pending" in observation.detail,
            )
            // The adopt remedy is REAL, not decorative: quarantining the
            // legacy datadir + retiring the durable markers (the engine's
            // own QUARANTINE_AND_RETRY discipline) lets the next start
            // bootstrap a fresh generation under the new closure.
            val quarantine = File(roots.database, "init-quarantine-consent")
            assertTrue(roots.mariadbDatadir.renameTo(quarantine))
            listOf(
                DesktopMariaDbEngine.INITIALIZED_MARKER_NAME,
                DesktopMariaDbEngine.CLEAN_MARKER_NAME,
                DesktopMariaDbEngine.SECRETS_FILE_NAME,
            ).forEach { marker -> File(roots.database, marker).delete() }
            val adopted = runBlocking { backend.preflight(spec()) }
            assertTrue(
                "the quarantined consent must bootstrap fresh: ${adopted.detail}",
                adopted.ok && "DB-BOOTSTRAP:" in adopted.detail,
            )
            assertEquals(
                "the quarantined legacy state must survive the adoption",
                "legacy state",
                File(quarantine, "realm_note.txt").readText(),
            )
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------
    // The drift guard's corrupt-seal leg (INVALID_DURABLE_STATE)
    // ------------------------------------------------------------------

    @Test
    fun aCorruptInitializedSealOverAPopulatedDatadirRefusesAsTheCorruptSealVerdict() {
        // The guard's corrupt-seal leg: a malformed initialized.json seal
        // (here a garbage closure sha — the live-reproduced corruption
        // class) over a POPULATED datadir reports initialized=false +
        // compatibilityMismatch=INVALID_DURABLE_STATE. The shared machine
        // misroutes that to INITIALIZE exactly like a closure change, and
        // the bootstrap it routes to dies on the MISLEADING "DB-INIT:
        // datadir is non-empty without init marker" refusal (reproduced
        // live as DB-BOOTSTRAP-FAILED) — a copy that names neither the
        // corrupt seal nor a remedy. The preflight must refuse as the
        // corrupt seal ITSELF, naming initialized.json and the
        // restore/quarantine remedies, and the DATABASE observation must
        // surface the mismatch and its verdict code.
        val roots = DesktopStorageRoots(folder.newFolder("corrupt-seal"))
        roots.ensureDirectories()
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        fun freshEngine() = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        // The install: bootstrapped + migrated under the verified closure
        // (realm state on disk), then its initialized.json seal corrupted.
        val install = freshEngine()
        assertTrue(install.initialize().getBoolean("ok"))
        assertTrue(install.applyPinnedMigrations().getBoolean("ok"))
        val note = File(roots.mariadbDatadir, "realm_note.txt").apply { writeText("legacy state") }
        val seal = File(roots.database, DesktopMariaDbEngine.INITIALIZED_MARKER_NAME)
        seal.writeText(
            JSONObject(seal.readText()).put("providerClosureSha256", "not-a-sha").toString(),
        )
        // The post-corruption process: a FRESH engine behind a fresh
        // backend — exactly the start the machine would misroute.
        val drifted = freshEngine()
        val status = drifted.status()
        assertTrue("precondition: the closure itself still verifies", status.getBoolean("providerReady"))
        assertFalse("the corrupt seal can no longer prove the install", status.getBoolean("initialized"))
        assertEquals(
            "the honest mismatch name (nothing consumed it before)",
            "INVALID_DURABLE_STATE",
            status.getString("compatibilityMismatch"),
        )
        val backend = DesktopRuntimeBackend(roots).apply { mariaDbEngineOverride = drifted }
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertFalse("a corrupt seal over a populated datadir must refuse: ${preflight.detail}", preflight.ok)
            assertTrue(
                "the refusal must be the dedicated corrupt-seal verdict: ${preflight.detail}",
                preflight.detail.startsWith("DB-DURABLE-STATE-INVALID"),
            )
            assertTrue(
                "the copy must name the corrupt initialized.json seal: ${preflight.detail}",
                "initialized.json" in preflight.detail,
            )
            assertTrue(
                "the copy must name the restore remedy: ${preflight.detail}",
                "Restore" in preflight.detail,
            )
            assertTrue(
                "the copy must name the quarantine remedy: ${preflight.detail}",
                "quarantin" in preflight.detail,
            )
            assertFalse(
                "never the misleading wedged bootstrap leg: ${preflight.detail}",
                "DB-BOOTSTRAP" in preflight.detail || "non-empty without init marker" in preflight.detail,
            )
            assertEquals(
                "the refusal must leave the legacy datadir untouched",
                "legacy state",
                note.readText(),
            )
            // The diagnostics surface: the mismatch and ITS verdict code,
            // never the first-boot "bootstrap leg pending" copy.
            val observation = runBlocking {
                backend.observe(com.pocketrealm.supervisor.RuntimeComponent.DATABASE)
            }
            assertTrue(
                "the observation must surface the mismatch and its verdict: ${observation.detail}",
                "compatibilityMismatch=INVALID_DURABLE_STATE" in observation.detail &&
                    "DB-DURABLE-STATE-INVALID" in observation.detail,
            )
            assertFalse(
                "never the first-boot copy over a corrupt seal: ${observation.detail}",
                "bootstrap leg pending" in observation.detail,
            )
            // The quarantine remedy is REAL, not decorative: the consent
            // (datadir aside + durable markers retired) bootstraps fresh.
            val quarantine = File(roots.database, "init-quarantine-consent")
            assertTrue(roots.mariadbDatadir.renameTo(quarantine))
            listOf(
                DesktopMariaDbEngine.INITIALIZED_MARKER_NAME,
                DesktopMariaDbEngine.CLEAN_MARKER_NAME,
                DesktopMariaDbEngine.SECRETS_FILE_NAME,
            ).forEach { marker -> File(roots.database, marker).delete() }
            val adopted = runBlocking { backend.preflight(spec()) }
            assertTrue(
                "the quarantined consent must bootstrap fresh: ${adopted.detail}",
                adopted.ok && "DB-BOOTSTRAP:" in adopted.detail,
            )
            assertEquals(
                "the quarantined legacy state must survive the adoption",
                "legacy state",
                File(quarantine, "realm_note.txt").readText(),
            )
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------
    // The drift guard's generation-drift leg (GENERATION)
    // ------------------------------------------------------------------

    @Test
    fun generationDriftBetweenTheDurableMarkersRefusesAsItsOwnVerdict() {
        // The guard's generation-drift leg: the initialized.json seal names
        // a generation the datadir's .pocketrealm-generation.json marker
        // does not (here the datadir marker rewritten with a fresh valid
        // uuid — e.g. a partial restore rolled one marker but not the
        // other). The engine reports initialized=false +
        // compatibilityMismatch=GENERATION over a POPULATED datadir, the
        // machine misroutes it to INITIALIZE, and the bootstrap wedges on
        // the non-empty datadir exactly like a closure change — so the
        // preflight must refuse as the drift ITSELF, naming BOTH markers.
        val roots = DesktopStorageRoots(folder.newFolder("generation-drift"))
        roots.ensureDirectories()
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        fun freshEngine() = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        // The install: bootstrapped + migrated (realm state on disk), then
        // the datadir's generation marker replaced by a DIFFERENT valid
        // generation — the durable markers now disagree.
        val install = freshEngine()
        assertTrue(install.initialize().getBoolean("ok"))
        assertTrue(install.applyPinnedMigrations().getBoolean("ok"))
        val note = File(roots.mariadbDatadir, "realm_note.txt").apply { writeText("legacy state") }
        File(roots.mariadbDatadir, DesktopMariaDbEngine.GENERATION_MARKER_NAME).writeText(
            DatabaseDurableState.generationMarker(UUID.randomUUID().toString()),
        )
        val drifted = freshEngine()
        val status = drifted.status()
        assertTrue("precondition: the closure itself still verifies", status.getBoolean("providerReady"))
        assertFalse("the disagreeing markers can no longer prove the install", status.getBoolean("initialized"))
        assertEquals(
            "the honest mismatch name (nothing consumed it before)",
            "GENERATION",
            status.getString("compatibilityMismatch"),
        )
        val backend = DesktopRuntimeBackend(roots).apply { mariaDbEngineOverride = drifted }
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertFalse("generation drift must refuse the start: ${preflight.detail}", preflight.ok)
            assertTrue(
                "the refusal must be the dedicated generation-drift verdict: ${preflight.detail}",
                preflight.detail.startsWith("DB-GENERATION-DRIFT"),
            )
            assertTrue(
                "the copy must name BOTH disagreeing markers: ${preflight.detail}",
                "initialized.json" in preflight.detail &&
                    ".pocketrealm-generation.json" in preflight.detail,
            )
            assertTrue(
                "the copy must name the quarantine remedy: ${preflight.detail}",
                "quarantin" in preflight.detail,
            )
            assertFalse(
                "never the wedged bootstrap leg: ${preflight.detail}",
                "DB-BOOTSTRAP" in preflight.detail || "non-empty without init marker" in preflight.detail,
            )
            assertEquals(
                "the refusal must leave the legacy datadir untouched",
                "legacy state",
                note.readText(),
            )
            // The diagnostics surface carries the mismatch and its code.
            val observation = runBlocking {
                backend.observe(com.pocketrealm.supervisor.RuntimeComponent.DATABASE)
            }
            assertTrue(
                "the observation must surface the mismatch and its verdict: ${observation.detail}",
                "compatibilityMismatch=GENERATION" in observation.detail &&
                    "DB-GENERATION-DRIFT" in observation.detail,
            )
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------
    // The widened guard keeps INITIALIZE for a genuinely EMPTY datadir
    // ------------------------------------------------------------------

    @Test
    fun aCorruptSealBesideAGenuinelyEmptyDatadirStillBootstraps() {
        // The guard's population gate: a corrupt initialized.json beside a
        // GENUINELY EMPTY datadir is a stale marker over nothing — the
        // first boot's INITIALIZE must still ride the machine and
        // bootstrap (the engine overwrites the marker at commit), never
        // refuse as drift.
        val roots = DesktopStorageRoots(folder.newFolder("corrupt-seal-empty"))
        roots.ensureDirectories()
        val backend = scriptedMariaDbBackend(roots)
        try {
            File(roots.database, DesktopMariaDbEngine.INITIALIZED_MARKER_NAME)
                .apply { writeText("{\"schema\":2,\"provider\":\"wi") }
            val preflight = runBlocking { backend.preflight(spec()) }
            assertTrue(
                "an empty datadir must keep the first-boot bootstrap: ${preflight.detail}",
                preflight.ok && "DB-BOOTSTRAP:" in preflight.detail,
            )
            assertFalse(
                "never a drift refusal over an empty datadir: ${preflight.detail}",
                "DB-DURABLE-STATE-INVALID" in preflight.detail,
            )
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------
    // The legacy-dual-provider guard (populated SQLite datadir, no marker)
    // ------------------------------------------------------------------

    @Test
    fun mariadbOverAPopulatedLegacySqliteDatadirRefusesLoudly() {
        // A pre-flip desktop realm: a populated SQLite datadir, no
        // active-provider marker (the SQLite lane never wrote one), and a
        // store that never chose a provider (so the flipped default
        // resolves MARIADB). Booting a fresh MariaDB generation beside it
        // would strand that state silently — the start must refuse with
        // the DISTINCT legacy code and the adoption remedy, and the
        // datadir must survive untouched. (Deterministic without any
        // engine: the guard precedes the engine lane.)
        val roots = DesktopStorageRoots(folder.newFolder("legacy-sqlite"))
        roots.ensureDirectories()
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeText("legacy realm state") }
        }
        assertFalse("precondition: no active-provider marker", activeProviderMarkerFile(roots).isFile)
        val backend = DesktopRuntimeBackend(roots)
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertFalse("a legacy populated datadir must refuse: ${preflight.detail}", preflight.ok)
            assertTrue(
                "the distinct legacy code is required: ${preflight.detail}",
                "DB-LEGACY-SQLITE" in preflight.detail,
            )
            assertTrue(
                "must never masquerade as the engine lane's unstaged copy: ${preflight.detail}",
                "DB-LINK" !in preflight.detail,
            )
            assertTrue(
                "the remedy must offer the explicit SQLITE adoption: ${preflight.detail}",
                "Select the SQLite provider" in preflight.detail,
            )
            assertTrue(
                "the remedy must offer the fresh-start deletion: ${preflight.detail}",
                "delete" in preflight.detail,
            )
            DATABASES.forEach { database ->
                assertTrue(
                    "the legacy datadir must survive the refusal ($database)",
                    DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile,
                )
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun aMariadbMarkerClearsTheLegacyGuardOverADormantSqliteDatadir() {
        // The durable decision wins over the guard's heuristic: with the
        // active-provider marker naming MARIADB, the populated SQLite
        // datadir beside the MariaDB one is DORMANT legacy state (the same
        // posture as the shared resolveProviderMode's rollback window) —
        // the preflight proceeds down the engine lane instead of refusing.
        val roots = DesktopStorageRoots(folder.newFolder("dormant-sqlite"))
        roots.ensureDirectories()
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeText("dormant legacy state") }
        }
        DatabaseDurableState.activeProviderMarker(
            DatabaseDurableState.ProviderMode.MARIADB,
            DatabaseRuntimeContract.WINDOWS_PROVIDER_ID,
            "11111111-1111-4111-8111-111111111111",
        ).let { marker -> activeProviderMarkerFile(roots).writeText(marker) }
        val backend = scriptedMariaDbBackend(roots)
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertTrue("the marker must clear the legacy guard: ${preflight.detail}", preflight.ok)
            assertFalse(
                "the legacy refusal must not appear: ${preflight.detail}",
                "DB-LEGACY-SQLITE" in preflight.detail,
            )
            DATABASES.forEach { database ->
                assertEquals(
                    "the dormant datadir must stay untouched by the engine lane's bootstrap " +
                        "(the MariaDB datadir is the target, not this one)",
                    "dormant legacy state",
                    readLegacy(roots, database),
                )
            }
        } finally {
            backend.close()
        }
    }

    private fun readLegacy(roots: DesktopStorageRoots, database: String): String =
        DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).readText()

    // ------------------------------------------------------------------
    // The legacy ADOPTION path: the explicit SQLITE selection serves the
    // populated datadir
    // ------------------------------------------------------------------

    @Test
    fun theExplicitSqliteSelectionAdoptsThePopulatedLegacyDatadir() {
        // The guard's remedy, pinned as behavior: selecting the SQLITE
        // provider explicitly keeps the pre-flip datadir SERVING — the
        // SQLite lane's own admission gates decide (a sealed datadir is
        // admitted), never the legacy guard and never the engine lane.
        val roots = DesktopStorageRoots(folder.newFolder("adopt-sqlite"))
        roots.ensureDirectories()
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeBytes(byteArrayOf()) }
        }
        stampSqliteSeals(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
            ).toJson(),
        )
        val backend = DesktopRuntimeBackend(roots)
        try {
            val preflight = runBlocking { backend.preflight(spec()) }
            assertTrue("the explicit selection must adopt the datadir: ${preflight.detail}", preflight.ok)
            assertTrue(
                "the SQLite lane's own re-proof must decide: ${preflight.detail}",
                "migration seal re-proof ok" in preflight.detail &&
                    "DB-LEGACY-SQLITE" !in preflight.detail &&
                    "DB-LINK" !in preflight.detail,
            )
        } finally {
            backend.close()
        }
    }

    /** Stamp the SQLite lane's seals with THIS machine's resolvable seed
     * identity (the DesktopRuntimeBackendTest stagedIdentity posture: the
     * real seed source's identity when one resolves, else the internally-
     * consistent fixture — deterministic either way). */
    private fun stampSqliteSeals(roots: DesktopStorageRoots) {
        val identity = DesktopSeedLocator.locate()
            ?.let {
                runCatching { DesktopSqliteSeals.identityFromProvenance(it.provenance.readText()) }
                    .getOrNull()
            }
            ?: DesktopSqliteSeals.identityFromProvenance(
                JSONObject().put(
                    "seed_transcripts",
                    JSONObject().also { pins ->
                        DATABASES.forEach { database ->
                            pins.put(
                                database,
                                JSONObject()
                                    .put("sha256", "a".repeat(64))
                                    .put("gzip_sha256", "a".repeat(64))
                                    .put("size", 1L)
                                    .put("gzip_size", 1L),
                            )
                        }
                    },
                ).toString(),
            )
        val sealText = DesktopSqliteSeals.migrationSeal(identity, System.currentTimeMillis())
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.MIGRATION_MARKER_NAME, sealText,
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir,
            DesktopSqliteSeals.GENERATION_MARKER_NAME,
            DesktopSqliteSeals.generationMarker(
                generationUuid = UUID.randomUUID().toString(),
                identity = identity,
                migrationSealSha256 = FileDigests.sha256(sealText),
            ),
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.CLEAN_MARKER_NAME,
            DesktopSqliteSeals.cleanSeal(System.currentTimeMillis()),
        )
    }
}
