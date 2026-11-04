package com.pocketrealm.desktop

import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopMariaDbProvider
import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.fs.FileDigests
import com.pocketrealm.importer.ImportLimits
import com.pocketrealm.importer.ManagedClientImporter
import com.pocketrealm.importer.SyntheticClientArchives
import com.pocketrealm.server.PreparedDataStore
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.WorldPresenceSample
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.After
import org.junit.Before

/**
 * The real DesktopRuntimeBackend contract. The database-component legs
 * are pure JVM; the realm/world legs exercise the actual in-process
 * native runtimes and require the DLL lanes (they skip cleanly without
 * them — the live 3724 listener gate is gradlew bootRealmd).
 */
class DesktopRuntimeBackendTest {
    /** W4 flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    /** W4 flip: the MARIADB provider + the persisted vanilla lane pick —
     * the settings stamp the mariadb arm's tests share. */
    private fun selectMariadbProvider(roots: DesktopStorageRoots) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.MARIADB,
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
    }

    /** W4 flip: persists the VANILLA pick into these roots' settings — the
     * per-boot lane the backend/engine resolve when a test constructs its
     * own roots (the fixture pin covers only the holder). */
    private fun pickVanilla(roots: DesktopStorageRoots) {
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile)
                .load().copy(selectedFlavorId = "vanilla"),
        )
    }

    @get:Rule
    val folder = TemporaryFolder()

    // The realm runtimes are the mariadb lane's build (the backend the
    // desktop ships, -mariadb-suffixed build root); the seam stays the
    // shared flavor/backend-agnostic DLL.
    private val realmDll = File("../native/.build-win-x86_64-mariadb/pocket-runtime-build/pocket_realmd_runtime.dll")
    private val seamDll = File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    private fun assumeNativesStaged() {
        requireNativeIfDemanded("realm runtime", realmDll)
        requireNativeIfDemanded("sqlite seam", seamDll)
    }

    private fun spec(endpoint: RealmEndpoint = RealmEndpoint.LOCAL) = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = endpoint,
        includeClient = false,
    )

    private val owner = ComponentOwner(sessionId = "test", instanceToken = "test")

    private var sourceCount = 0

    /** A synthetic packaged/dev provenance whose non-seed fields (the
     * closure identity) stay constant unless [generator] changes. */
    private fun fixtureProvenance(corpusSha: String, generator: String = "synthetic-packaging"): JSONObject {
        val pins = JSONObject()
        DATABASES.forEach { database ->
            val pin = JSONObject()
                .put("sha256", corpusSha)
                .put("gzip_sha256", corpusSha)
                .put("size", 1L)
                .put("gzip_size", 1L)
            pins.put(database, pin)
        }
        return JSONObject()
            .put("schema", 1)
            .put("generator", generator)
            .put("seed_transcripts", pins)
    }

    /** A synthetic seed source (the test stand-in for the packaged app
     * dir): provenance + one (empty) transcript file per database. */
    private fun stageSeedSource(
        corpusSha: String,
        generator: String = "synthetic-packaging",
    ): DesktopSeedLocator.SeedSource {
        val dir = folder.newFolder("app-source-" + (++sourceCount))
        val provenance = File(dir, "BUILD_PROVENANCE.json")
        provenance.writeText(fixtureProvenance(corpusSha, generator).toString())
        val transcripts = DATABASES.associateWith { database ->
            File(dir, "$database.sqlz").apply { writeBytes(byteArrayOf(0)) }
        }
        return DesktopSeedLocator.SeedSource(provenance, transcripts)
    }

    /** The constant fixture identity (corpus "a"*64, the synthetic
     * closure) — the identity the single-field-mismatch tests stamp. */
    private fun fixtureIdentity(): DesktopSqliteSeals.Identity =
        DesktopSqliteSeals.identityFromProvenance(fixtureProvenance("a".repeat(64)).toString())

    /** The identity a default-backend datadir must carry on THIS machine:
     * the resolved real seed source's identity when one resolves (a dev
     * checkout's staging tree — the default-locator backend then admits
     * the datadir), else the fixture (nothing resolvable — the backend's
     * current identity is null and the internally-consistent proof
     * admits). Deterministic either way. */
    private fun stagedIdentity(): DesktopSqliteSeals.Identity =
        DesktopSeedLocator.locate()
            ?.let {
                runCatching { DesktopSqliteSeals.identityFromProvenance(it.provenance.readText()) }
                    .getOrNull()
            }
            ?: fixtureIdentity()

    private fun stampSeals(
        roots: DesktopStorageRoots,
        withClean: Boolean = true,
        identity: DesktopSqliteSeals.Identity = stagedIdentity(),
    ) {
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
        if (withClean) {
            DesktopSqliteSealMarkers.atomicWriteMarker(
                roots.sqliteDatadir, DesktopSqliteSeals.CLEAN_MARKER_NAME,
                DesktopSqliteSeals.cleanSeal(System.currentTimeMillis()),
            )
        }
    }

    private fun materializeDatadir(roots: DesktopStorageRoots, withSeals: Boolean = true) {
        roots.sqliteDatadir.mkdirs()
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).writeBytes(byteArrayOf())
        }
        if (withSeals) stampSeals(roots)
        selectSqliteProvider(roots)
    }

    /** the SQLite lane is the EXPLICIT dev/compat opt-in — the shipped
     * default is MariaDB. Every test that exercises the SQLite datadir's
     * admission gates (this class's fixture materializations) pins the
     * selection explicitly so the lane under test never depends on the
     * shipped default. */
    private fun selectSqliteProvider(roots: DesktopStorageRoots) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
                // W4 flip: the lane selection rides EVERY settings rewrite —
                // a snapshot without it wipes the persisted pick and the
                // boot-lane resolver refuses the lane under test.
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
    }

    /** A synthetic staged MariaDB distribution (the engine twin's staging
     * contract, mirrored from DesktopMariaDbEngineTest) so the backend's
     * MARIADB lane can run JVM-only (GAP-5's observation test). */
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
            .put("provider", com.pocketrealm.database.DatabaseRuntimeContract.WINDOWS_PROVIDER_ID)
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
        File(repo, com.pocketrealm.database.DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH).writeText(
            JSONObject()
                .put("schema", 1)
                .put("abi", "windows-x86_64")
                .put("database_backend", "mariadb")
                .put(
                    "provider",
                    JSONObject().put("id", com.pocketrealm.database.DatabaseRuntimeContract.WINDOWS_PROVIDER_ID),
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
    private class ScriptedMariaDbProcesses :
        com.pocketrealm.database.DesktopMariaDbEngine.Processes {

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
                // the per-lane provisioning verb.
                "CREATE DATABASE IF NOT EXISTS" in sql -> ok()
                "pocket_admin" in sql -> ok()
                "migration_ledger" in sql -> ok()
                else -> refused("scripted: unexpected client sql: ${sql.take(80)}")
            }
        }

        override fun startDaemon(
            executable: File,
            args: List<String>,
            outputRedirect: File,
            errorRedirect: File,
        ): Long {
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

    @Test
    fun mariadbObservationReportsTheDaemonPid() {
        // the MARIADB lane's DATABASE observation pid is the ENGINE'S
        // tracked mariadbd pid, never this JVM's ambient pid — and NULL
        // whenever the daemon is not RUNNING (the SQLite lane's honest null,
        // now the MariaDB lane's too).
        val roots = DesktopStorageRoots(folder.newFolder("mariadb-daemon-pid"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        selectMariadbProvider(roots)
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        val engine = com.pocketrealm.database.DesktopMariaDbEngine(
            roots = roots,
            providerOverride = com.pocketrealm.database.DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        val backend = DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = engine
        }
        // Initialized + migration-current but STOPPED: no live daemon, so the
        // observation must report the honest NULL.
        assertTrue(engine.initialize().getBoolean("ok"))
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        val stopped = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertNull("a stopped daemon has no pid to report", stopped.pid)
        // RUNNING: the observation's pid is the tracked daemon's, not the
        // app JVM's own.
        assertTrue(engine.start().getBoolean("ok"))
        val running = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertEquals(
            "the DATABASE observation must report the mariadbd daemon's pid",
            ScriptedMariaDbProcesses.DAEMON_PID.toInt(),
            running.pid,
        )
        assertTrue(
            "the reported pid must never be this JVM's ambient pid",
            running.pid?.toLong() != ProcessHandle.current().pid(),
        )
        engine.stop()
        val stoppedAfter = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertNull("a clean stop returns to the honest null", stoppedAfter.pid)
    }

    @Test
    fun mariadbProviderSelectionRoutesTheDatabaseComponentToTheEngineLane() {
        // the Settings provider selection is CONSUMABLE —
        // selecting MARIADB routes the DATABASE verbs to the engine lane,
        // never through the SQLite datadir gate. The unstaged state is
        // INJECTED (an engine whose provider override resolves nothing) so
        // the pin is deterministic even on a machine whose dev checkout
        // stages the provider (D19 flipped the shipped default to MariaDB;
        // the staged tree exists in dev lanes). A populated marker-less
        // SQLite datadir beside that selection refuses through the
        // default-flip's LEGACY guard first — never a silent fork over the
        // user's legacy state.
        val roots = DesktopStorageRoots(folder.newFolder("mariadb-selected"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        // Seed the SQLITE datadir too: even a fully seeded SQLite lane must
        // NOT be admitted while the MARIADB provider is selected.
        DatabaseSqliteControlPlane.DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeText("seed") }
        }
        selectMariadbProvider(roots)
        val nowhere = DesktopMariaDbProvider(
            stagedRootOverride = folder.newFolder("no-staged-provider"),
        )
        val engine = com.pocketrealm.database.DesktopMariaDbEngine(
            roots = roots,
            providerOverride = nowhere,
        )
        val backend = DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = engine
        }
        // The legacy guard fires BEFORE the engine is consulted: the
        // populated marker-less SQLite datadir refuses with the adoption
        // remedy, never a silent MariaDB fork.
        val legacy = runBlocking { backend.preflight(spec()) }
        assertFalse("legacy SQLite state must not be silently forked: ${legacy.detail}", legacy.ok)
        assertTrue(
            "refusal must carry the legacy-guard copy: ${legacy.detail}",
            "DB-LEGACY-SQLITE" in legacy.detail,
        )
        // Without the legacy datadir the same unstaged selection refuses
        // with the honest DB-LINK copy (never a SQLite fallback).
        DatabaseSqliteControlPlane.DATABASES.forEach { database ->
            assertTrue(DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).delete())
        }
        val preflight = runBlocking { backend.preflight(spec()) }
        assertFalse("an unstaged provider must refuse the start: ${preflight.detail}", preflight.ok)
        assertTrue(
            "refusal must carry the DB-LINK copy: ${preflight.detail}",
            "DB-LINK" in preflight.detail,
        )
        val observation = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertEquals(ComponentLifecycle.FAILED, observation.state)
        assertTrue(
            "the observation must carry the staged-provider remedy: ${observation.detail}",
            "DB-LINK" in observation.detail,
        )
        assertFalse(observation.ready)
        backend.close()
    }

    @Test
    fun mariadbPreflightRecoversAnInterruptedRestoreLeftByADeadProcess() {
        // The crash window between the engine's beginRestore and its
        // commit/rollback: the durable restore record (and the swapped-in
        // candidate plus quarantined original) survives the process death,
        // so the fresh process's shared DatabaseStartPreparation machine
        // reports ROLLBACK_PENDING_RESTORE on the very first check. The
        // preflight must DRIVE the ownerless recovery — the Android
        // prepareDatabaseForStart loop's leg (AndroidRuntimeBackend.kt:661-
        // 662) — never drop the verdict into the generic DB-NOT-READY
        // refusal whose remedies (initialize, recover) both refuse on the
        // clean-sealed candidate the interrupted beginRestore left behind.
        val roots = DesktopStorageRoots(folder.newFolder("mariadb-restore-crash"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        selectMariadbProvider(roots)
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        fun freshEngine() = com.pocketrealm.database.DesktopMariaDbEngine(
            roots = roots,
            providerOverride = com.pocketrealm.database.DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        // The crashed process: fully prepared, a named backup taken, the
        // restore transaction opened (candidate swapped in) — then it dies
        // with the token, before any commit/rollback.
        val crashed = freshEngine()
        assertTrue(crashed.initialize().getBoolean("ok"))
        assertTrue(crashed.applyPinnedMigrations().getBoolean("ok"))
        val datadir = roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = com.pocketrealm.database.DatabaseSnapshotStore(roots.databaseSnapshots)
            .create(datadir, "manual-crash-${System.currentTimeMillis()}", databaseStopped = true)
        File(datadir, "realm_note.txt").writeText("moved-on")
        assertTrue(crashed.beginRestore(snapshot.id).getBoolean("ok"))
        assertEquals("before", File(datadir, "realm_note.txt").readText())

        // The post-crash process: a FRESH engine (no token, no live window)
        // behind a fresh backend — exactly the wedged start this pins.
        val freshEngine = freshEngine()
        assertTrue(
            "the dead driver's leftover must read pending",
            freshEngine.status().getBoolean("restorePending"),
        )
        val backend = DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = freshEngine
        }
        val preflight = runBlocking { backend.preflight(spec()) }
        assertTrue("the recovery preflight must admit the start: ${preflight.detail}", preflight.ok)
        assertTrue(
            "the copy must name the settled state: ${preflight.detail}",
            "DB-RESTORE-PENDING" in preflight.detail && "rolled back" in preflight.detail,
        )
        assertEquals(
            "the ORIGINAL datadir must be back",
            "moved-on",
            File(datadir, "realm_note.txt").readText(),
        )
        assertFalse(
            File(roots.database, com.pocketrealm.database.DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertTrue(
            "the recovery must retire both restore trees",
            roots.database.listFiles { file ->
                file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                    file.name.startsWith("restore-original-"))
            }.orEmpty().isEmpty(),
        )
        // Convergence: the next preflight over the recovered state is the
        // plain READY verdict — nothing left to settle, no recovery copy.
        val again = runBlocking { backend.preflight(spec()) }
        assertTrue("the recovered lane must admit the next start: ${again.detail}", again.ok)
        assertTrue(
            "the recovered re-run must be the plain READY copy: ${again.detail}",
            again.detail.startsWith("mariadb generation verified"),
        )
        backend.close()
    }

    @Test
    fun recoverDatabaseSettlesAnInterruptedRestoreBeforeTheDirtyGenerationAdmission() {
        // The supervisor's dirty-journal lane reaches recoverDatabase
        // BEFORE any preflight arm (recoverDatabaseAfterComponents runs
        // ahead of the start's ROLLBACK_PENDING_RESTORE arm), so a crash
        // mid-restore-proof — a durable CANDIDATE_ACTIVE record over the
        // CLEAN-sealed candidate beginRestore restamped — must converge
        // HERE: the ordinary recover() refuses on that clean seal
        // ("recovery requested for a clean generation") and wedges.
        val roots = DesktopStorageRoots(folder.newFolder("mariadb-restore-recover"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        selectMariadbProvider(roots)
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val processes = ScriptedMariaDbProcesses()
        fun freshEngine() = com.pocketrealm.database.DesktopMariaDbEngine(
            roots = roots,
            providerOverride = com.pocketrealm.database.DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = processes,
        )
        // The crashed process: fully prepared, a named backup taken, the
        // restore transaction opened (candidate swapped in, clean seal
        // restamped over it) — then it dies with the token, before any
        // commit/rollback, leaving the supervisor journal dirty.
        val crashed = freshEngine()
        assertTrue(crashed.initialize().getBoolean("ok"))
        assertTrue(crashed.applyPinnedMigrations().getBoolean("ok"))
        val datadir = roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = com.pocketrealm.database.DatabaseSnapshotStore(roots.databaseSnapshots)
            .create(datadir, "manual-recover-${System.currentTimeMillis()}", databaseStopped = true)
        File(datadir, "realm_note.txt").writeText("moved-on")
        assertTrue(crashed.beginRestore(snapshot.id).getBoolean("ok"))
        assertEquals("before", File(datadir, "realm_note.txt").readText())
        // The post-crash process: a FRESH engine (no token, no live
        // window) behind a fresh backend — recoverDatabase is the verb
        // the supervisor's dirty-journal lane drives, exactly the wedged
        // start this pins.
        val freshEngine = freshEngine()
        assertTrue("the dead driver's leftover must read pending", freshEngine.status().getBoolean("restorePending"))
        val backend = DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = freshEngine
        }
        val recovery = runBlocking { backend.recoverDatabase() }
        assertTrue(
            "the dirty-journal recovery must converge over the pending restore: ${recovery.detail}",
            recovery.ok,
        )
        assertTrue(
            "the copy must name the settled state (never the clean-seal refusal): ${recovery.detail}",
            "DB-RESTORE-PENDING" in recovery.detail && "rolled back" in recovery.detail,
        )
        assertEquals(
            "the ORIGINAL datadir must be back",
            "moved-on",
            File(datadir, "realm_note.txt").readText(),
        )
        assertFalse(
            File(roots.database, com.pocketrealm.database.DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertTrue(
            "the recovery must retire both restore trees",
            roots.database.listFiles { file ->
                file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                    file.name.startsWith("restore-original-"))
            }.orEmpty().isEmpty(),
        )
        // Convergence: the next preflight over the recovered state is the
        // plain READY verdict — the start the supervisor re-attempts after
        // its dirty-journal lane completes admits with nothing left to settle.
        val again = runBlocking { backend.preflight(spec()) }
        assertTrue("the recovered lane must admit the next start: ${again.detail}", again.ok)
        assertTrue(
            "the recovered re-run must be the plain READY copy: ${again.detail}",
            again.detail.startsWith("mariadb generation verified"),
        )
        backend.close()
    }

    @Test
    fun sqliteProviderSelectionKeepsTheDevCompatLane() {
        // SQLite is the EXPLICIT dev/compat selection (no longer the
        // shipped default): the selection never touches the engine lane, and
        // a seeded SQLite datadir admits exactly as before.
        val roots = DesktopStorageRoots(folder.newFolder("sqlite-selected"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        selectSqliteProvider(roots)
        DatabaseSqliteControlPlane.DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeText("seed") }
        }
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.preflight(spec()) }
        // The SQLite lane's own admission gates decide (the seed bytes here
        // are not a real seeded corpus, so the seal proof refuses — the
        // point is the DB-LINK engine copy never appears).
        assertTrue(
            "the SQLite lane's own gates must decide, not the provider lane: ${result.detail}",
            "DB-LINK" !in result.detail,
        )
    }

    @Test
    fun preflightRefusesUnseededDatadirWhenNoSeedSourceResolves() {
        // DESK-108 rewrote this leg: an unseeded datadir BOOTSTRAPS when
        // the packaged transcripts resolve, so the honest DB-NOT-SEEDED
        // refusal is the truly-missing case. Deterministic only where
        // neither the app dir nor the checkout staging tree ships seeds.
        assumeTrue(
            "needs an environment without packaged/dev seed transcripts",
            DesktopSeedLocator.locate() == null,
        )
        val roots = DesktopStorageRoots(folder.newFolder("empty"))
        
        pickVanilla(roots)
        selectSqliteProvider(roots)
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse(result.ok)
        assertTrue("must name the remedy: ${result.detail}", "seedRealmData" in result.detail)
    }

    @Test
    fun preflightAcceptsAMatchingRevisionDatadir() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.preflight(spec()) }
        assertTrue("matching revision must be admitted: ${result.detail}", result.ok)
        assertTrue("the re-proof must be stated: ${result.detail}", "migration seal re-proof ok" in result.detail)
    }

    @Test
    fun freshBackendStartsWithAnUnspentWorldLifetime() {
        // the spent-world flag mirrors the native m_ever_ready pin;
        // a fresh backend has run no world, so nothing is spent yet. (The
        // READY->spent transition itself rides the world DLL lane — it is
        // set in startWorld right after the READY wait succeeds.)
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("unspent")))
        assertFalse("a fresh backend has run no world", backend.worldLifetimeSpent)
    }

    /**
     * GAP-2 (was DESKPROD-2): a restore's proof boot must not provision a
     * bot population into the restored candidate. The model forces the
     * bots-disabled DESKTOP_PROFILE into the proof's launch spec
     * (DesktopAppModel.bootRestoredStackWorldReadyProof); the backend's
     * world start honors the SPEC, not a fresh settings read, so the proof
     * resolves bots-disabled even while the settings select a bot profile
     * — and the conf that resolution stages is the disabled one
     * (aiplayerbot-disabled.conf, BotTarget = 0: no RandomBotAutoCreate
     * population input reaches the candidate). The provisioning itself is
     * native and gated by exactly these conf lines; the end-to-end
     * restore → proof → ordinary-start sequence is the supervisorStartGate
     * live lane. (A bots-disabled proof no longer exercises the
     * profile-conf staging path during restore — every ordinary Start
     * exercises it; GAP-2 risk note (a). Do not "fix" this back.)
     */
    @Test
    fun proofBootDoesNotProvisionBotPopulation() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-proof-bots"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        // Settings select a real built-in bot profile: the ordinary boot
        // stages aiplayerbot-<id>.conf and a nonzero BotTarget.
        DesktopSettingsStore(roots.settingsFile).save(
            DesktopSettingsStore(roots.settingsFile).load().copy(botProfileId = "mobile-balanced-b100-v1"),
        )
        val backend = DesktopRuntimeBackend(roots)
        val selected = requireNotNull(backend.activeBotProfileId()) {
            "precondition: the settings must resolve a bot profile"
        }
        // The proof's spec: the model forces the bots-disabled profile.
        val proofSpec = spec().copy(profileId = DesktopAppModel.DESKTOP_PROFILE)
        // The ordinary spec: the settings-resolved profile the model's
        // launchSpec would freeze for a user Start.
        val ordinarySpec = spec().copy(profileId = selected)
        assertNull(
            "the proof boot must resolve bots-disabled despite the settings selection",
            backend.launchBotProfile(proofSpec),
        )
        assertEquals(
            "the ordinary boot must keep the settings-resolved selection",
            selected,
            backend.launchBotProfile(ordinarySpec)?.id,
        )
        // And the conf consequence: the proof's resolution stages the
        // DISABLED bot conf and a zero BotTarget over the same prepared
        // data — no population provisioning input in the candidate.
        stageActiveServerGeneration(roots)
        val files = com.pocketrealm.server.ServerRuntimeFiles(roots)
        val conf = files.worldConfig(botProfile = backend.launchBotProfile(proofSpec)).readText()
        val run = File(File(roots.runtime, "server"), "run")
        val disabledConf = File(run, "aiplayerbot-disabled.conf")
        assertTrue("the disabled bot conf must be staged: $disabledConf", disabledConf.isFile)
        val disabledText = disabledConf.readText()
        assertTrue(
            "the population's auto-create must be off:\n$disabledText",
            "AiPlayerbot.RandomBotAutoCreate = 0" in disabledText,
        )
        assertTrue(
            "the bot lane must be fully off:\n$disabledText",
            "AiPlayerbot.Enabled = 0" in disabledText,
        )
        assertTrue(
            "the launch BotTarget must be zero:\n$conf",
            "PocketRealm.BotTarget = 0" in conf,
        )
        assertFalse(
            "no profile conf may be staged for the proof boot",
            File(run, "aiplayerbot-$selected.conf").isFile,
        )
        backend.close()
    }

    /**
     * GAP-3 (owner census UIUX-3, the resolveManagedClient/resolveClientDir
     * legs): with two qualified flavors the client directory resolves the
     * ACTIVE lane through the flavor machine — the selection drives the
     * pointer namespace instead of the former `qualified().single()`
     * crashing the status/preflight re-derivation outside its runCatching.
     */
    @Test
    fun resolveClientDirResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        val roots = DesktopStorageRoots(folder.newFolder("two-qualified-clientdir"))
            .also { it.ensureDirectories() }
            pickVanilla(roots)
        // A vanilla generation imports under today's registry state; the S5
        // flip (TwoQualifiedFlavors) then re-targets the resolution reads.
        val clientRoot = folder.newFolder("client").also { root ->
            SyntheticClientArchives.clientEntries(wrapper = null, build = ClientFlavors.VANILLA.build)
                .forEach { entry ->
                    val target = File(root, entry.path.replace('\\', '/'))
                    if (entry.directory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeBytes(entry.bytes)
                    }
                }
        }
        val published = runBlocking {
            // A fixed generous allocatable: this lane exercises publish +
            // resolution, not the storage budget (the planner seam).
            ManagedClientImporter(
                roots,
                ImportLimits(minFiles = 1, minTotalBytes = 0L),
                allocatableBytes = { 64L * ImportLimits.GIB },
            ).run(clientRoot)
        }
        val backend = DesktopRuntimeBackend(roots)
        try {
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
            assertNull(
                "the tbc lane has no generation: honest null, never a .single() crash",
                backend.resolveClientDir(),
            )

            ActiveFlavor.select(ClientFlavors.VANILLA.flavorId)
            val generationRoot = File(File(File(roots.content, "client"), "generations"), published.generation)
            assertEquals(
                "the vanilla selection resolves the vanilla generation's root",
                generationRoot.canonicalFile,
                backend.resolveClientDir()?.canonicalFile,
            )
        } finally {
            ActiveFlavor.reset()
            backend.close()
        }
    }

    /**
     *
     * with two qualified flavors the backup's compatibility stamp names the
     * ACTIVE lane through the flavor machine — the former
     * `qualified().single()` threw inside the transition lock and refused
     * every named backup at the second qualification.
     */
    @Test
    fun createNamedBackupResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        val roots = DesktopStorageRoots(folder.newFolder("two-qualified-backup"))
            .also { it.ensureDirectories() }
            pickVanilla(roots)
        selectSqliteProvider(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        try {
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
            val created = backend.createNamedBackup("flavorlane")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            val manifest = File(File(roots.database, "snapshots"), requireNotNull(created.snapshotId))
                .resolve("manifest.json")
            assertEquals(
                "the compatibility stamp names the ACTIVE lane",
                ClientFlavors.TBC.flavorId,
                JSONObject(manifest.readText()).getJSONObject("compatibility").getString("flavor"),
            )
        } finally {
            ActiveFlavor.reset()
            backend.close()
        }
    }

    /** Stages a minimal valid NORMAL prepared-data generation the way
     * tools/win_prepare_data.py publishes (manifest + authenticated active
     * pointer) so worldConfig's full body — including the bot conf block —
     * can be pinned (the DesktopServerRuntimeFilesTest fixture, local
     * form). */
    private fun stageActiveServerGeneration(roots: DesktopStorageRoots) {
        val id = "12345678-1234-1234-1234-123456789abc"
        val dataRoot = File(roots.content, "o11-server")
        val generation = File(dataRoot, "generations/$id").apply { mkdirs() }
        val records = listOf(
            "dbc/A.dbc" to "dbc", "maps/0000000.map" to "map",
            "vmaps/000.vmtree" to "vmtree", "mmaps/000.mmap" to "mmap",
            "mmaps/0000000.mmtile" to "mmtile",
        ).map { (path, contents) ->
            val file = File(generation, path).apply { parentFile?.mkdirs(); writeText(contents) }
            JSONObject().put("path", path).put("size", file.length()).put("sha256", sha256Hex(file.readBytes()))
        }
        val manifest = JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL").put("clientBuild", 5875).put("cmangosFamily", "classic")
            .put("counts", JSONObject().put("dbc", 100).put("maps", 100)
                .put("vmapTrees", 1).put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1))
            .put("files", org.json.JSONArray(records))
        val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
        File(dataRoot, "active.json").writeText(
            JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", sha256Hex(manifestFile.readBytes())).toString(),
        )
    }

    @Test
    fun preflightRefusesAStaleCorpusWithTheDistinctCode() {
        // a datadir sealed by an older corpus refuses with
        // DB-MIGRATION-PENDING — never the generic DB-NOT-SEEDED. The
        // datadir stamps the FIXTURE identity so the override differs in
        // exactly one identity field (the corpus).
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots, withSeals = false)
        stampSeals(roots, identity = fixtureIdentity())
        val backend = DesktopRuntimeBackend(roots, seedSourceOverride = stageSeedSource("e".repeat(64)))
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse("stale corpus must refuse: ${result.detail}", result.ok)
        assertTrue("distinct code required: ${result.detail}", "DB-MIGRATION-PENDING" in result.detail)
        assertFalse("must not masquerade as unseeded: ${result.detail}", "DB-NOT-SEEDED" in result.detail)
        assertTrue("must carry the corpus copy: ${result.detail}", "seed corpus advanced" in result.detail)
    }

    @Test
    fun preflightRefusesAProviderClosureChangeWithItsOwnCode() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots, withSeals = false)
        stampSeals(roots, identity = fixtureIdentity())
        // Same corpus, different provider closure (the non-seed fields).
        val backend = DesktopRuntimeBackend(
            roots,
            seedSourceOverride = stageSeedSource("a".repeat(64), generator = "other-packaging"),
        )
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse("closure change must refuse: ${result.detail}", result.ok)
        assertTrue("distinct code required: ${result.detail}", "DB-PROVIDER-MISMATCH" in result.detail)
    }

    @Test
    fun preflightRefusesALegacyDatadirWithoutSeals() {
        // A pre-seal desktop datadir (the old seeder's output) is
        // unprovable: DESK-19's pending-maintenance refusal.
        val roots = DesktopStorageRoots(folder.newFolder("legacy"))
        
        pickVanilla(roots)
        materializeDatadir(roots, withSeals = false)
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse("legacy datadir must refuse: ${result.detail}", result.ok)
        assertTrue("distinct code required: ${result.detail}", "DB-MIGRATION-PENDING" in result.detail)
        assertTrue("must carry the legacy copy: ${result.detail}", "predates the migration seals" in result.detail)
    }

    @Test
    fun startDatabaseRefusesATamperedMigrationMarker() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        // Byte-level tamper: valid JSON, wrong bytes (the digest no
        // longer matches the generation marker's record).
        val marker = DesktopSqliteSealMarkers.migrationMarkerFile(roots.sqliteDatadir)
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir,
            DesktopSqliteSeals.MIGRATION_MARKER_NAME,
            marker.readText().replace("\"schema\":1", "\"schema\":1 "),
        )
        val backend = DesktopRuntimeBackend(roots)
        try {
            runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            fail("a tampered migration marker must refuse start")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue("expected DB-MIGRATION-PENDING: $message", "DB-MIGRATION-PENDING" in message)
            assertTrue("expected the tamper copy: $message", "tampered" in message)
        }
    }

    @Test
    fun startDatabaseRefusesADirtyGenerationWithItsCode() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        assertTrue(DesktopSqliteSealMarkers.cleanMarkerFile(roots.sqliteDatadir).delete())
        val backend = DesktopRuntimeBackend(roots)
        try {
            runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            fail("a dirty generation must refuse start")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue("expected DB-DIRTY-GENERATION: $message", "DB-DIRTY-GENERATION" in message)
        }
    }

    @Test
    fun preflightRefusesAMissingGenerationMarkerWithTheDirtyGenerationCode() {
        // a renamed-away generation marker over a surviving
        // migration seal is a tampered/dirty generation — it refuses with
        // DB-DIRTY-GENERATION, never the DB-MIGRATION-PENDING legacy copy
        // (that copy is the genuinely pre-seal datadir's, which carries no
        // sealed ledger at all).
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        assertTrue(DesktopSqliteSealMarkers.generationMarkerFile(roots.sqliteDatadir).delete())
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse("missing generation marker must refuse: ${result.detail}", result.ok)
        assertTrue("distinct code required: ${result.detail}", "DB-DIRTY-GENERATION" in result.detail)
        assertFalse(
            "must not masquerade as pre-seal legacy: ${result.detail}",
            "predates the migration seals" in result.detail,
        )
    }

    @Test
    fun preflightBootstrapsAnUnseededDatadirFromThePackagedTranscripts() {
        // the flat packaged layout (transcripts + provenance in
        // one dir) seeds the empty datadir through the FULL seeder
        // discipline, and the bootstrapped datadir then starts clean.
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("fresh"))
        
        pickVanilla(roots)
        selectSqliteProvider(roots)
        val backend = DesktopRuntimeBackend(roots, seedSourceOverride = syntheticTranscriptSource())
        val preflight = runBlocking { backend.preflight(spec()) }
        assertTrue("bootstrap must seed: ${preflight.detail}", preflight.ok)
        assertTrue("must be the bootstrap record: ${preflight.detail}", "DB-BOOTSTRAP:" in preflight.detail)
        DATABASES.forEach { database ->
            assertTrue(
                "$database not seeded",
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile,
            )
        }
        assertTrue(
            "the bootstrap must stamp the seals",
            DesktopSqliteSealMarkers.migrationMarkerFile(roots.sqliteDatadir).isFile,
        )
        val started = runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
        assertEquals(ComponentLifecycle.READY, started.state)
        val stop = runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
        assertTrue("clean stop: ${stop.detail}", stop.ok)
    }

    /** A real (small) flat seed source: four gzip transcripts + true
     * pins, laid out exactly like the jpackage app dir. */
    private fun syntheticTranscriptSource(): DesktopSeedLocator.SeedSource {
        val dir = folder.newFolder("packaged-app")
        val sql = mapOf(
            "classicrealmd" to
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, name TEXT);\n" +
                "INSERT INTO realmlist VALUES (1, 'Local');\n",
            "classiccharacters" to "CREATE TABLE characters (guid INTEGER PRIMARY KEY);\n",
            "classiclogs" to "CREATE TABLE logs (id INTEGER PRIMARY KEY);\n",
            "classicmangos" to "CREATE TABLE creature_template (entry INTEGER PRIMARY KEY);\n",
        )
        assertEquals(DATABASES.sorted(), sql.keys.sorted())
        val pins = JSONObject()
        sql.forEach { (database, script) ->
            val raw = script.toByteArray(StandardCharsets.UTF_8)
            val gzipped = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            File(dir, "$database.sqlz").writeBytes(gzipped)
            pins.put(
                database,
                JSONObject()
                    .put("sha256", sha256Hex(raw))
                    .put("gzip_sha256", sha256Hex(gzipped))
                    .put("size", raw.size.toLong())
                    .put("gzip_size", gzipped.size.toLong()),
            )
        }
        val provenance = File(dir, "BUILD_PROVENANCE.json")
        provenance.writeText(JSONObject().put("seed_transcripts", pins).toString())
        return DesktopSeedLocator.SeedSource(
            provenance,
            DATABASES.associateWith { database -> File(dir, "$database.sqlz") },
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun databaseObserveReflectsSeededState() {
        val seededRoots = DesktopStorageRoots(folder.newFolder("seeded"))
        materializeDatadir(seededRoots)
        // Seeded but unclaimed: a materialization at rest is STOPPED —
        // only a claimed (started) datadir is "running".
        val atRest = runBlocking { DesktopRuntimeBackend(seededRoots).observe(RuntimeComponent.DATABASE) }
        assertEquals(ComponentLifecycle.STOPPED, atRest.state)
        assertFalse(atRest.ready)
        val backend = DesktopRuntimeBackend(seededRoots)
        val started = runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
        assertEquals(ComponentLifecycle.READY, started.state)
        assertTrue(started.ready)

        val emptyRoots = DesktopStorageRoots(folder.newFolder("bare"))
        val bare = runBlocking { DesktopRuntimeBackend(emptyRoots).observe(RuntimeComponent.DATABASE) }
        assertEquals(ComponentLifecycle.STOPPED, bare.state)
        assertFalse(bare.ready)
    }

    @Test
    fun databaseStartRefusesWithoutSeedAndStopsCleanWhenAtRest() {
        val bareRoots = DesktopStorageRoots(folder.newFolder("bare"))
        selectSqliteProvider(bareRoots)
        val backend = DesktopRuntimeBackend(bareRoots)
        try {
            runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            fail("database start must refuse without seed")
        } catch (expected: IllegalStateException) {
            assertTrue((expected.message ?: "").contains("DB-NOT-SEEDED"))
        }
        val seededRoots = DesktopStorageRoots(folder.newFolder("seeded"))
        selectSqliteProvider(seededRoots)
        materializeDatadir(seededRoots)
        val seededBackend = DesktopRuntimeBackend(seededRoots)
        runBlocking { seededBackend.start(RuntimeComponent.DATABASE, owner, spec()) }
        val stop = runBlocking { seededBackend.stop(RuntimeComponent.DATABASE, owner) }
        assertTrue("no WAL sidecars at rest: ${stop.detail}", stop.ok)
    }

    @Test
    fun worldStartWithoutPreparedDataFailsWithTheDataMissingPosture() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        try {
            runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec()) }
            fail("world start must refuse without prepared data")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue("must be the honest refusal: $message", "world start refused" in message)
            assertTrue("must carry the prepared-data copy: $message", "Server world data is not ready" in message)
        } finally {
            backend.close()
        }
    }

    /** The crash-signature datadir: a real WAL database snapshotted WITH
     * its live -wal/-shm sidecars (a killed host leaves exactly this),
     * plus the three placeholder databases, sealed generation+migration
     * but NO clean-stop seal (the dirty-generation admission). */
    private fun stageCrashSnapshotDatadir(roots: DesktopStorageRoots) {
        val staging = folder.newFolder("staging")
        val writerDb = File(staging, "classicrealmd.sqlite3")
        val writer = DesktopSqliteConnection(writerDb.absolutePath)
        writer.queryText("PRAGMA journal_mode=WAL;")
        writer.exec("CREATE TABLE incident_marker(value TEXT);")
        writer.exec("INSERT INTO incident_marker VALUES('survives');")
        roots.sqliteDatadir.mkdirs()
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = File(staging, "classicrealmd.sqlite3" + suffix)
            if (source.isFile) {
                source.copyTo(File(roots.sqliteDatadir, source.name), overwrite = true)
            }
        }
        writer.close()
        // The other three databases must be present (the seeded gate).
        DATABASES.filter { it != "classicrealmd" }.forEach { database ->
            val target = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
            val seed = File(staging, database + ".sqlite3")
            val connection = DesktopSqliteConnection(seed.absolutePath)
            connection.exec("CREATE TABLE placeholder(id INTEGER);")
            connection.close()
            seed.copyTo(target, overwrite = true)
        }
        assertTrue(
            "the snapshot must carry the crash signature (a -wal sidecar)",
            File(roots.sqliteDatadir, "classicrealmd.sqlite3-wal").isFile,
        )
        // The generation + migration seals stamp at seed; the crash
        // means no clean-stop seal (recovery owns it and re-stamps at
        // the end).
        stampSeals(roots, withClean = false)
        selectSqliteProvider(roots)
    }

    @Test
    fun recoverDatabaseRestoresADirtyDatadirToAtRest() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        stageCrashSnapshotDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        try {
            val result = runBlocking { backend.recoverDatabase() }
            assertTrue("recovery must succeed: ${result.detail}", result.ok)
            // The:430 stub's real replacement: the
            // revision re-proof is stated and the clean-stop seal is
            // freshly stamped, never "arrives with the engine twin".
            assertTrue(
                "recovery must re-prove the revision: ${result.detail}",
                "revision re-proof ok (migration seal current)" in result.detail,
            )
            assertTrue(
                "recovery must re-stamp the clean seal: ${result.detail}",
                "clean-stop seal re-stamped" in result.detail,
            )
            assertFalse(
                "the engine-twin stub must be gone: ${result.detail}",
                "arrives with the engine twin" in result.detail,
            )
            assertTrue(
                "the re-stamped clean seal must be valid",
                DesktopSqliteSeals.cleanSealCurrent(
                    DesktopSqliteSealMarkers.cleanMarkerFile(roots.sqliteDatadir).takeIf(File::isFile)?.readText(),
                ),
            )
            val sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir)
                .filter { it.isFile }
            assertTrue("sidecars must be drained: $sidecars", sidecars.isEmpty())
            // The recovered database still carries its committed data.
            val readBack = DesktopSqliteConnection(
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd").absolutePath,
            ).use { connection ->
                connection.queryText("SELECT value FROM incident_marker;")
            }
            assertEquals("survives", readBack)
        } finally {
            backend.close()
        }
    }

    @Test
    fun recoverDatabaseRefusesATamperedLedgerBeforeTheIntegrityGate() {
        // the re-proof precedes the integrity gate — a tampered
        // migration ledger refuses as itself (an empty-file datadir
        // would fail the integrity gate with a different error, so
        // reaching the refusal at all proves the ordering).
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val marker = DesktopSqliteSealMarkers.migrationMarkerFile(roots.sqliteDatadir)
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir,
            DesktopSqliteSeals.MIGRATION_MARKER_NAME,
            marker.readText().replace("\"schema\":1", "\"schema\":1 "),
        )
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.recoverDatabase() }
        assertFalse("a tampered ledger must refuse recovery: ${result.detail}", result.ok)
        assertTrue(
            "distinct code required: ${result.detail}",
            "DB-MIGRATION-PENDING" in result.detail,
        )
    }

    @Test
    fun recoverDatabaseRefusesWithoutSeeds() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("empty-roots"))
        
        pickVanilla(roots)
        selectSqliteProvider(roots)
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.recoverDatabase() }
        assertFalse("recovery must not silently seed: ${result.detail}", result.ok)
        assertTrue(
            "refusal must carry the seeded copy: ${result.detail}",
            "DB-NOT-SEEDED" in result.detail && "seedRealmData" in result.detail,
        )
    }

    @Test
    fun realmStartLaunchesAsynchronouslyAndStopsClean() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        materializeDatadir(roots) // files exist but carry no schema
        val backend = DesktopRuntimeBackend(roots)
        try {
            // The backend owns the STARTING->READY wait (the supervisor's
            // contract is backend.start returns READY), so a database-level
            // failure that surfaces asynchronously MUST come back through
            // backend.start as an honest throw carrying the native detail -
            // not as an accepted STARTING launch that hides it.
            val failure = runCatching {
                runBlocking { backend.start(RuntimeComponent.REALM, owner, spec()) }
            }.exceptionOrNull()
            check(failure != null) {
                "the schema-less datadir must fail the start through the READY wait"
            }
            assertTrue(
                "the failure must carry the native detail: ${failure.message}",
                "realm start failed" in (failure.message ?: "") &&
                    // The contract is provider-honest detail, not one code:
                    // DB_REVISION is the sqlite lane's schema-less refusal;
                    // since D19 the staged natives serve the MariaDB lane,
                    // where the same unbootstrapped-datadir start correctly
                    // refuses DB_CONNECT through the app-private socket.
                    ("DB_REVISION" in (failure.message ?: "") || "DB_CONNECT" in (failure.message ?: "")),
            )
            val stop = runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
            val settled = runBlocking { backend.observe(RuntimeComponent.REALM) }
            assertEquals("stop must settle the runtime: ${stop.detail}", ComponentLifecycle.STOPPED, settled.state)
            // The lifecycle record tells the transition story.
            val record = File(File(roots.runtime, "server"), "lifecycle/realm.json")
            assertTrue(record.isFile)
        } finally {
            backend.close()
        }
    }

    @Test
    fun projectRealmEndpointUpdatesTheRealmlistRow() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        selectSqliteProvider(roots)
        roots.sqliteDatadir.mkdirs()
        // the projected database resolves through the flavor
        // registry (the same source the conf's LoginDatabaseInfo uses).
        val realmd = DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            com.pocketrealm.server.ServerRuntimeFiles.serverDatabases().realmd,
        )
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec(
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER, " +
                    "realmbuilds TEXT NOT NULL DEFAULT '');",
            )
            db.exec("INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724, '');")
        }
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.projectRealmEndpoint(owner, RealmEndpoint.LOCAL) }
        assertTrue(result.detail, result.ok)
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            assertEquals("127.0.0.1", db.queryText("SELECT address FROM realmlist WHERE id = 1;"))
            // The projection also writes the flavor's accepted-build list —
            // the seed's realmbuilds is EMPTY, and without this write realmd
            // flags the realm offline for every build until the world's own
            // start UPDATE (the registry vanilla lane's C1 advertisement).
            assertEquals(
                "5875 6005 6141",
                db.queryText("SELECT realmbuilds FROM realmlist WHERE id = 1;"),
            )
        }
    }

    @Test
    fun startClaimsOwnershipAndEveryObservationEchoesIt() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        val observation = runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
        assertEquals(
            "the supervisor's startStage readiness proof requires the observation to carry the owner",
            owner,
            observation.owner,
        )
        val observed = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertEquals(owner, observed.owner)
        val stop = runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
        assertTrue(stop.ok)
        val released = runBlocking { backend.observe(RuntimeComponent.DATABASE) }
        assertEquals("a successful owned stop releases the claim", null, released.owner)
    }

    @Test
    fun stopWithForeignOwnerIsWithheld() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
        val foreign = ComponentOwner(sessionId = "other", instanceToken = "other")
        try {
            runBlocking { backend.stop(RuntimeComponent.DATABASE, foreign) }
            fail("a foreign owner's stop must be withheld")
        } catch (expected: IllegalStateException) {
            assertTrue((expected.message ?: "").contains("ownership mismatch"))
        }
        // The rightful owner can still stop after the withheld attempt.
        val stop = runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
        assertTrue(stop.ok)
    }

    @Test
    fun adoptRefusesEverythingButOwnerlessRunningComponents() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        val backend = DesktopRuntimeBackend(roots)
        runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
        val adopter = ComponentOwner(sessionId = "adopter", instanceToken = "adopter")
        // Owned and READY: not an orphan — adoption must refuse.
        assertFalse(runBlocking { backend.adopt(RuntimeComponent.DATABASE, adopter) }.ok)
        runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
        // Stopped: not a running orphan either.
        assertFalse(runBlocking { backend.adopt(RuntimeComponent.DATABASE, adopter) }.ok)
    }

    // ---------------- DESK-114 client observation ----------------

    /** A fake WoW.exe under [dir]; the scan seam's row carries the path the
     * backend attributes against the configured client directory. */
    private fun scanRow(dir: File, pid: Int): ClientProcessScan =
        ClientProcessScan(pid, File(dir, "WoW.exe").canonicalPath)

    @Test
    fun clientScanObservesAnOwnerlessWowExe() {
        val dir = folder.newFolder("client")
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { listOf(scanRow(dir, 4242)) },
        )
        val observed = runBlocking { backend.observe(RuntimeComponent.CLIENT) }
        assertEquals("a found client is a live client", ComponentLifecycle.READY, observed.state)
        assertTrue(observed.ready)
        assertEquals(
            "a found-but-unowned client must report owner=null so the supervisor's " +
                "adoption/orphan semantics engage",
            null,
            observed.owner,
        )
        assertEquals(4242, observed.pid)
        assertTrue(
            "the detail must name the out-of-session launch: ${observed.detail}",
            "outside this session" in observed.detail,
        )
    }

    @Test
    fun clientScanIgnoresProcessesOutsideTheClientDirectory() {
        val dir = folder.newFolder("client")
        val elsewhere = folder.newFolder("elsewhere")
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { listOf(scanRow(elsewhere, 5151)) },
        )
        val observed = runBlocking { backend.observe(RuntimeComponent.CLIENT) }
        assertEquals(
            "another install's WoW.exe is not this app's client",
            ComponentLifecycle.STOPPED,
            observed.state,
        )
        assertEquals(null, observed.pid)
    }

    @Test
    fun doubleLaunchRefusesWhenAWowExeIsAlreadyUnderTheClientDirectory() {
        val dir = folder.newFolder("client")
        File(dir, "WoW.exe").writeText("placeholder: the guard fires before any launch")
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        pickVanilla(roots)
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { listOf(scanRow(dir, 4242)) },
        )
        try {
            runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec()) }
            fail("a second Start-game must refuse instead of launching a duplicate client")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue("must name the existing client: $message", "client already running" in message)
            assertTrue("must name the holder pid: $message", "4242" in message)
        }
    }

    @Test
    fun closeDrainsAnOwnerlessClientByPid() {
        val windows = "windows" in System.getProperty("os.name").lowercase()
        assumeTrue("Windows-only leg (ping as the drainable process)", windows)
        val dir = folder.newFolder("client")
        File(dir, "WoW.exe").writeText("placeholder")
        // A real, long-lived process standing in for a user-launched WoW.exe.
        val sleeper = ProcessBuilder("ping", "-n", "30", "127.0.0.1").start()
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner {
                listOf(scanRow(dir, sleeper.pid().toInt()))
            },
        )
        backend.close()
        assertTrue("the scanned client must be destroyed by pid", !sleeper.isAlive)
    }

    // ---------------- DESK-61/29 presence + DESK-71 verbs ----------------

    @Test
    fun worldPresenceIsEmptyWhileTheWorldIsStopped() {
        assumeNativesStaged()
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("roots")))
        val sample = runBlocking { backend.observeWorldPresence() }
        assertEquals(
            "EMPTY stays reserved for the stopped world (the supervisor's sampling guard)",
            ComponentLifecycle.STOPPED,
            sample.state,
        )
        assertEquals(0, sample.realPlayers)
        assertTrue(!sample.playerbotsEnabled)
        assertEquals(0, sample.onlinePlayers)
    }

    @Test
    fun foregroundVerbsRefuseHonestlyWithoutAClient() {
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientProcessScanner = ClientProcessScanner { emptyList() },
        )
        val promote = runBlocking { backend.promoteToForeground(RuntimeComponent.WORLD) }
        assertFalse("promote must not fake success without a client: ${promote.detail}", promote.ok)
        assertTrue(
            "the refusal must say why: ${promote.detail}",
            "client not running" in promote.detail,
        )
        // Demotion's postcondition (no client window holds the foreground)
        // genuinely holds with no client at all.
        val demote = runBlocking { backend.demoteToForeground(RuntimeComponent.WORLD) }
        assertTrue("nothing-to-demote is a true postcondition: ${demote.detail}", demote.ok)
    }

    @Test
    fun projectRealmEndpointAcceptsTheLanAddress() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        selectSqliteProvider(roots)
        roots.sqliteDatadir.mkdirs()
        val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec(
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER, " +
                    "realmbuilds TEXT NOT NULL DEFAULT '');",
            )
            db.exec("INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724, '');")
        }
        val backend = DesktopRuntimeBackend(roots)
        val lan = RealmEndpoint.parseLan("192.168.1.50")
        val result = runBlocking { backend.projectRealmEndpoint(owner, lan) }
        assertTrue("LAN projection must pass the dotted-quad guard: ${result.detail}", result.ok)
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            assertEquals("192.168.1.50", db.queryText("SELECT address FROM realmlist WHERE id = 1;"))
            assertEquals(
                "the LAN projection carries the flavor's accepted builds too",
                "5875 6005 6141",
                db.queryText("SELECT realmbuilds FROM realmlist WHERE id = 1;"),
            )
        }
    }

    /** DESK-42's plan-named leg, at the backend level: a LAN_HOST launch
     * spec's conf emission — realmd.conf is written by startRealm BEFORE
     * the native start, so the schema-less-datadir boot (which fails
     * honestly at the READY wait, like realmStartLaunches...) leaves the
     * LAN-bound conf to assert on. */
    @Test
    fun lanHostSpecEmitsTheLanBindConf() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        materializeDatadir(roots)
        // the start-time interface re-check runs before
        // the native; the test's purpose is the conf emission + the honest
        // native failure, so the stub interface keeps the gate open.
        val backend = DesktopRuntimeBackend(
            roots,
            lanInterfaces = DesktopLanInterfaces(
                scan = {
                    listOf(
                        DesktopLanInterfaces.Candidate(
                            "Test LAN", "eth0",
                            RealmEndpoint.parseLan("192.168.1.50"),
                        ),
                    )
                },
                defaultRouteAddress = { null },
            ),
        )
        try {
            val lanHost = RuntimeLaunchSpec(
                mode = RuntimeMode.LAN_HOST,
                profileId = "local",
                endpoint = RealmEndpoint.parseLan("192.168.1.50"),
                includeClient = false,
                allowLanPlayers = true,
            )
            val failure = runCatching {
                runBlocking { backend.start(RuntimeComponent.REALM, owner, lanHost) }
            }.exceptionOrNull()
            check(failure != null) { "the schema-less datadir must fail the start" }
            assertTrue(
                "the failure must be the honest native detail: ${failure.message}",
                "realm start failed" in (failure.message ?: ""),
            )
            val conf = File(File(roots.runtime, "server"), "run/realmd.conf").readText()
            assertTrue("BindIP must carry the LAN endpoint:\n$conf", "BindIP = \"192.168.1.50\"" in conf)
            assertTrue("the realm port stays fixed:\n$conf", "RealmServerPort = 3724" in conf)
        } finally {
            backend.close()
        }
    }

    /**
     * DESK-29's plan-named leg: NON-EMPTY presence while a client runs.
     * The stack is booted for real — the machine's prepared data + seeded
     * datadir under the real storage roots (the only place a world can
     * actually reach READY) — and the leg skips honestly (the NativeGate
     * pattern extended to the boot inputs) wherever natives, seeds,
     * prepared data, or an exclusive runtime domain are missing. The
     * client is a real stand-in process observed through the same
     * ClientProcessScanner seam (no test machine is guaranteed a WoW.exe).
     */
    @Test
    fun worldPresenceIsNonEmptyWhileTheStackAndClientRun() {
        assumeNativesStaged()
        assumeTrue("Windows-only leg", "windows" in System.getProperty("os.name").lowercase())
        val roots = DesktopStorageRoots()
        runCatching { PreparedDataStore(File(roots.content, "o11-server")).requireActive() }
            .onFailure { failure ->
                assumeTrue("prepared world data not staged: ${failure.message}", false)
            }
        val seeded = DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        assumeTrue("datadir not seeded: ${roots.sqliteDatadir}", seeded)
        // DESK-19/53/98 (the start-admission gate): the leg also needs a
        // SEAL-CURRENT datadir — one seeded before the seals existed (or
        // by a stale corpus) honestly refuses start; re-seed (gradlew
        // seedRealmData) to run this leg again. Preflight is a read-only
        // proof on a fully seeded datadir.
        val sealProof = runCatching {
            runBlocking { DesktopRuntimeBackend(roots).preflight(spec()) }
        }.getOrElse { failure ->
            RuntimeActionResult(false, failure.message ?: failure.javaClass.simpleName)
        }
        assumeTrue("datadir not seal-current: ${sealProof.detail}", sealProof.ok)
        // Exclusive with any live app instance (the DESK-113 machinery):
        // the leg boots the real stack, so it must own the runtime domain.
        val lock = runCatching { AppInstanceLock.acquire(roots.realm) }.getOrElse { failure ->
            assumeTrue(
                "a live Pocket Realm instance owns the runtime domain (${failure.message})",
                false,
            )
            throw failure
        }
        var clientRows: List<ClientProcessScan> = emptyList()
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = folder.root,
            clientProcessScanner = ClientProcessScanner { clientRows },
        )
        var client: Process? = null
        try {
            runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            runBlocking { backend.start(RuntimeComponent.REALM, owner, spec()) }
            runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec()) }
            // A real, long-lived client-class process stands in for WoW.exe.
            val sleeper = ProcessBuilder("ping", "-n", "120", "127.0.0.1").start()
            client = sleeper
            clientRows = listOf(
                ClientProcessScan(sleeper.pid().toInt(), File(folder.root, "WoW.exe").canonicalPath),
            )
            val observedClient = runBlocking { backend.observe(RuntimeComponent.CLIENT) }
            assertEquals("the stand-in client must be observable",
                ComponentLifecycle.READY, observedClient.state)
            assertEquals(sleeper.pid().toInt(), observedClient.pid)
            // THE assertion: presence is an honest sample, not the EMPTY lie.
            val sample = runBlocking { backend.observeWorldPresence() }
            assertTrue(
                "presence must be non-empty while the stack and client run: $sample",
                sample != WorldPresenceSample.EMPTY,
            )
            assertEquals(ComponentLifecycle.READY, sample.state)
            assertTrue("online count must be sampled: $sample", sample.onlinePlayers >= 0)
            // a world that reached READY spent this process's one
            // world lifetime — the native refuses every later start with
            // the WRONG_STATE in-process-restart error, and the flag the
            // app model's relaunch action keys off must say so.
            assertTrue("a READY world must spend the process lifetime", backend.worldLifetimeSpent)
        } finally {
            // Drain in the production order; the un-claimed client is
            // destroyed directly (it was never claimed, so the ownership
            // verbs refuse it by contract).
            runCatching { client?.destroy() }
            runCatching { runBlocking { backend.stop(RuntimeComponent.WORLD, owner) } }
            runCatching { runBlocking { backend.stop(RuntimeComponent.REALM, owner) } }
            runCatching { runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) } }
            runCatching { backend.close() }
            runCatching { lock.close() }
        }
    }

    // ---------------- DESK-112/121 client exit + tree drain ----------------

    /** Windows-only legs below need a real drainable process; the shell's
     * ping stands in for WoW.exe like the DESK-114 legs above. */
    private fun assumeWindows() {
        assumeTrue(
            "Windows-only leg (taskkill/ping as the process stand-in)",
            "windows" in System.getProperty("os.name").lowercase(),
        )
    }

    /**
     * A client folder that passes the launch-path identity gate
     * (DESK-52/68 scan): a REAL i386 Windows binary (SysWOW64 ping.exe —
     * PE32, prints usage and exits with a NON-ZERO code when run bare)
     * carrying an appended vanilla version-resource signature, plus the
     * required base MPQ set with valid MPQ headers. The signature block is
     * exactly what the shared ClientPeIdentity parse scans for
     * (0xfeef04bd + FileVersionMS/LS little-endian, encoding the qualified
     * vanilla version resource — resolved from the flavor registry).
     */
    private fun stageGatePassingClientDir(dir: File) {
        val ping = File(System.getenv("WINDIR"), "SysWOW64/ping.exe")
        assumeTrue("SysWOW64 ping.exe not found: $ping", ping.isFile)
        val build = com.pocketrealm.client.ClientFlavors.VANILLA.build
        val ms = (1 shl 16) or 12 // FileVersionMS -> 1.12 (minor 1 rides LS's high word)
        val ls = (1 shl 16) or build // FileVersionLS -> 1.<qualified build>
        val resource = byteArrayOf(
            0xbd.toByte(), 0x04, 0xef.toByte(), 0xfe.toByte(), // VS_VERSIONINFO signature
            // StructVersion DWORD: the shared ClientPeIdentity parse reads
            // FileVersionMS at signature+8 and FileVersionLS at +12 (the same
            // layout SyntheticClientArchives.syntheticPe writes at 0x308/0x30c),
            // so the gap DWORD must sit between the signature and MS.
            0, 0, 0, 0,
            ms.toByte(), (ms ushr 8).toByte(), (ms ushr 16).toByte(), (ms ushr 24).toByte(),
            ls.toByte(), (ls ushr 8).toByte(), (ls ushr 16).toByte(), (ls ushr 24).toByte(),
        )
        File(dir, "WoW.exe").writeBytes(ping.readBytes() + resource)
        val data = File(dir, "Data").apply { mkdirs() }
        for (name in com.pocketrealm.client.ClientFlavors.VANILLA.requiredMpqs.sorted()) {
            File(data, name).writeBytes(byteArrayOf(0x4d, 0x50, 0x51, 0x1a)) // "MPQ\x1a"
        }
    }

    @Test
    fun abnormalClientExitIsJournalledOnTheNextObservation() {
        assumeWindows()
        val dir = folder.newFolder("client")
        stageGatePassingClientDir(dir)
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { emptyList() },
        )
        try {
            val launched = runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec()) }
            assertEquals(ComponentLifecycle.READY, launched.state)
            // The stand-in dies on its own within moments; the first
            // observation that sees the death journals the exit.
            val deadline = System.currentTimeMillis() + CLIENT_DEATH_WAIT_MS
            var observed = runBlocking { backend.observe(RuntimeComponent.CLIENT) }
            while (observed.state != ComponentLifecycle.STOPPED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(CLIENT_DEATH_POLL_MS)
                observed = runBlocking { backend.observe(RuntimeComponent.CLIENT) }
            }
            assertEquals(
                "the dead client must settle to STOPPED: ${observed.detail}",
                ComponentLifecycle.STOPPED,
                observed.state,
            )
            val exit = backend.lastClientExit()
            assertTrue("an abnormal death must be journalled", exit != null)
            assertEquals(1, exit!!.getInt("exitCode"))
            assertFalse("a non-zero death is not clean", exit.getBoolean("clean"))
            assertTrue(exit.getInt("pid") > 0)
        } finally {
            backend.close()
        }
    }

    @Test
    fun treeKillSeesTheClientPidBeforeTheDrain() {
        assumeWindows()
        val dir = folder.newFolder("client")
        File(dir, "WoW.exe").writeText("placeholder")
        val sleeper = ProcessBuilder("ping", "-n", "30", "127.0.0.1").start()
        val killed = mutableListOf<Long>()
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner {
                listOf(scanRow(dir, sleeper.pid().toInt()))
            },
            treeKiller = ClientTreeKiller { pid ->
                killed.add(pid)
                null
            },
        )
        backend.close()
        assertEquals("the tree kill must target the client pid", listOf(sleeper.pid().toLong()), killed)
        assertTrue("the client must be drained", !sleeper.isAlive)
    }

    @Test
    fun theDrainStillSweepsWhenTheTreeKillCannotConfirm() {
        assumeWindows()
        val dir = folder.newFolder("client")
        File(dir, "WoW.exe").writeText("placeholder")
        val sleeper = ProcessBuilder("ping", "-n", "30", "127.0.0.1").start()
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")),
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner {
                listOf(scanRow(dir, sleeper.pid().toInt()))
            },
            treeKiller = ClientTreeKiller { "taskkill rc=128: refused (test seam)" },
        )
        backend.close()
        assertTrue(
            "the destroy escalation must drain when the tree kill fails",
            !sleeper.isAlive,
        )
    }

    // ---------------- DESK-50/51/139 launch boundary ----------------

    /**
     * the launch MERGES the realmlist (the `set realmlist` line
     * moves, every other directive survives) over a backup taken into the
     * runtime root, and the teardown restores the user's original bytes and
     * retires the backup.
     */
    @Test
    fun clientLaunchMergesRealmlistAndTeardownRestoresIt() {
        assumeWindows()
        val dir = folder.newFolder("client")
        stageGatePassingClientDir(dir)
        val original = "set realmlist 9.9.9.9\r\nset patchlist \"\"\r\n# a user comment\r\n"
        File(dir, "realmlist.wtf").writeText(original)
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { emptyList() },
        )
        try {
            val launched = runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec()) }
            assertEquals(ComponentLifecycle.READY, launched.state)
            assertTrue("the detail must say the realmlist was projected", "realmlist projected" in launched.detail)
            val merged = File(dir, "realmlist.wtf").readText()
            assertTrue(
                "the projected endpoint must ride the realmlist line:\n$merged",
                "set realmlist 127.0.0.1" in merged,
            )
            assertTrue(
                "the user's patchlist line must survive the merge:\n$merged",
                "set patchlist \"\"" in merged,
            )
            assertTrue(
                "the user's comment must survive the merge:\n$merged",
                "# a user comment" in merged,
            )
            assertTrue(
                "the old endpoint must be gone:\n$merged",
                "9.9.9.9" !in merged,
            )
            // The backup slot: original bytes + sha256 under the runtime root.
            val backupDir = File(roots.runtime, "realmlist-backup")
            val backups = backupDir.listFiles()!!.filter { it.name.endsWith(".orig") }
            assertEquals("exactly one backup slot", 1, backups.size)
            assertEquals(
                "the backup carries the user's original bytes",
                original,
                backups.single().readText(),
            )
            // Teardown restores.
            val stop = runBlocking { backend.stop(RuntimeComponent.CLIENT, owner) }
            assertTrue(stop.detail, stop.ok)
            assertEquals(
                "the user's original bytes must come back on teardown",
                original,
                File(dir, "realmlist.wtf").readText(),
            )
            val leftovers = backupDir.listFiles()!!.filter { it.name.endsWith(".orig") }
            assertEquals("a successful restore retires the backup slot", 0, leftovers.size)
        } finally {
            backend.close()
        }
    }

    /**
     *
     * every launch merges the ManagedConfigPolicy overlay over the user's
     * WTF/Config.wtf — the EULA/TOS/intro gates die (auto-login can never
     * type into them), the client comes up windowed, and an audio-OFF
     * launch enforces the master-sound CVar while the per-process mute
     * applies to the spawned pid through the seam.
     */
    @Test
    fun clientLaunchEnforcesTheConfigWtfOverlayAndAppliesAudioOff() {
        assumeWindows()
        val dir = folder.newFolder("client")
        stageGatePassingClientDir(dir)
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                audioMode = com.pocketrealm.storage.Settings.AudioMode.OFF,
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
        val muteCalls = mutableListOf<Pair<Int, Boolean>>()
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = dir,
            clientProcessScanner = ClientProcessScanner { emptyList() },
            audioMuter = AudioSessionMuter { pid, mute ->
                synchronized(muteCalls) { muteCalls.add(pid to mute) }
                null
            },
        )
        try {
            val launched = runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec()) }
            assertEquals(ComponentLifecycle.READY, launched.state)
            val configText = File(dir, "WTF/Config.wtf").readText()
            val values = com.pocketrealm.ingame.ConfigWtfCodec.parse(configText)
            assertEquals("1", values["readTOS"])
            assertEquals("1", values["readEULA"])
            assertEquals("1", values["readScanning"])
            assertEquals("0", values["movie"])
            assertEquals("the desktop subset is windowed", "1", values["gxWindow"])
            // The DESK-139 audio leg: OFF enforces the master-sound CVar.
            assertEquals("0", values["MasterSoundEffects"])
            // the per-process mute applied to the spawned pid.
            val deadline = System.currentTimeMillis() + AUDIO_MUTE_WAIT_MS
            var applied = false
            while (System.currentTimeMillis() < deadline && !applied) {
                synchronized(muteCalls) { applied = muteCalls.any { it.first == launched.pid } }
                if (!applied) Thread.sleep(CLIENT_DEATH_POLL_MS)
            }
            assertTrue(
                "the spawned pid must be muted through the seam: $muteCalls",
                applied,
            )
            synchronized(muteCalls) {
                assertTrue(muteCalls.all { it.second })
            }
        } finally {
            backend.close()
        }
    }

    // ---------------- DESK-127 status telemetry ----------------

    @Test
    fun worldObservationDetailAndTelemetryCarryTheStatusIndices() {
        assumeNativesStaged()
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            val observed = runBlocking { backend.observe(RuntimeComponent.WORLD) }
            assertTrue(
                "the observation detail must carry the world-load facts: ${observed.detail}",
                "tickCount=" in observed.detail && "activeSessions=" in observed.detail,
            )
            val world = backend.worldTelemetry()
            assertTrue("status telemetry must render when the lane is staged", world != null)
            assertEquals(1, world!!.getLong("abiVersion"))
            assertTrue(world.has("tickCount") && world.has("lastTickMs"))
            assertTrue(world.has("maxTickMs") && world.has("activeSessions"))
            val realm = backend.realmTelemetry()
            assertTrue(realm != null && realm!!.has("heartbeatCount"))
        } finally {
            backend.close()
        }
    }

    // ---------------- DESK-116/122/123/77 live-control verbs ----------------

    /**
     * The refusal legs: every live verb answers the honest WORLD_NOT_READY
     * result when no READY, session-owned world exists. They hold on every
     * machine — a staged native lane reports the stopped world, an absent
     * one degrades to the same refusal (the guard's documented posture) —
     * so no DLLs are required here. The happy paths ride the native-lane
     * gates (bootWorld / rpHost).
     */
    @Test
    fun liveControlVerbsRefuseHonestlyWithoutAReadyOwnedWorld() {
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            val pause = runBlocking { backend.pauseWorld(owner) }
            assertFalse("pause must refuse: ${pause.detail}", pause.ok)
            assertTrue(
                "the refusal must be WORLD_NOT_READY: ${pause.detail}",
                "WORLD_NOT_READY" in pause.detail,
            )
            assertFalse(runBlocking { backend.resumeWorld(owner) }.ok)
            assertFalse(runBlocking { backend.setBotTarget(owner, 10) }.ok)
            assertFalse(
                runBlocking { backend.startBotProfile(owner, BotProfiles.defaultProfile.id) }.ok,
            )
            assertFalse(runBlocking { backend.characterPersistence(owner, "VALIDUSER", "Charname") }.ok)
            assertFalse(runBlocking { backend.worldChat(owner, "Charname", "say", "", "hello") }.ok)
        } finally {
            backend.close()
        }
    }

    /** the Android op's validation order — the identity tokens
     * validate BEFORE any world contact, so a malformed request is a throw,
     * never a native round-trip. */
    @Test
    fun characterPersistenceValidatesTheTokensBeforeTheWorld() {
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            val badUser = runCatching {
                runBlocking { backend.characterPersistence(owner, "bad name!", "Charname") }
            }.exceptionOrNull()
            assertTrue("the username token must refuse", badUser is IllegalArgumentException)
            val badCharacter = runCatching {
                runBlocking { backend.characterPersistence(owner, "VALIDUSER", "X") }
            }.exceptionOrNull()
            assertTrue("the character name must refuse", badCharacter is IllegalArgumentException)
        } finally {
            backend.close()
        }
    }

    /** the launch-time profile resolution reaches the
     * shared advanced branch through the persisted settings — the launcher
     * override resolves an adv-style profile instead of the plain built-in,
     * and switching the override off restores the built-in. Pure JVM
     * (settings + the shared resolution); no natives. */
    @Test
    fun launchProfileResolvesTheAdvancedBranchThroughThePersistedOverride() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        
        pickVanilla(roots)
        roots.ensureDirectories()
        val builtIn = BotProfiles.LOW_CPU_160
        val advanced = com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
            BotProfiles.defaultProfile,
        ).copy(
            nearbyBotLimit = 5,
            nearbyRadius = 150,
        )
        val settingsStore = com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile)
        settingsStore.save(
            com.pocketrealm.storage.Settings.Snapshot(
                botProfileId = builtIn.id,
                botPopulationTarget = builtIn.selectedTarget,
                botAdvancedEnabled = true,
                botAdvanced = advanced,
            ),
        )
        val backend = DesktopRuntimeBackend(roots)
        val advancedId = backend.activeBotProfileId()
        assertTrue(
            "the override must resolve the adv-style profile: $advancedId",
            advancedId != null && advancedId.startsWith("adv4"),
        )
        settingsStore.save(
            com.pocketrealm.storage.Settings.Snapshot(
                botProfileId = builtIn.id,
                botPopulationTarget = builtIn.selectedTarget,
                botAdvancedEnabled = false,
                botAdvanced = advanced,
            ),
        )
        assertEquals(
            "without the override the plain built-in resolves",
            builtIn.id,
            backend.activeBotProfileId(),
        )
    }

    private companion object {
        /** Bound for the stand-in client's self-exit wait (it exits in ms). */
        const val CLIENT_DEATH_WAIT_MS = 15_000L
        const val CLIENT_DEATH_POLL_MS = 250L

        /** Bound for the DESK-139 mute poll (the seam answers instantly;
         * the budget covers thread scheduling). */
        const val AUDIO_MUTE_WAIT_MS = 10_000L
    }
}
