package com.pocketrealm.database

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.testFlavor
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.database.DesktopMariaDbEngine.Processes
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop MariaDB engine twin's durable decisions (DESK-5/47/78),
 * exercised JVM-only: a SYNTHETIC staged provider (the physical Windows
 * distribution is the lane's external dependency) plus a scripted
 * [Processes] seam stand in for mariadbd.exe/mariadb.exe, so every
 * transaction/seal/marker leg proves exactly as the Android twin's do:
 *
 *  - an unstaged provider refuses the mutating verbs with the honest
 *    DB-LINK copy and never touches the datadir (the DESK-78 posture;
 *    stop/recover on an inert engine still take their state-gate copies
 *    first, exactly like the Android leg);
 *  - initialize runs the full INIT transaction (OWNED → RUNNING →
 *    COMMITTING → deleted), stamps the initialized seal + clean seal +
 *    active-provider marker, and proves least privilege;
 *  - the prepare-for-start gates refuse with the Android StartBlocker
 *    copies (stale migrations before dirty — the shared precedence);
 *  - applyPinnedMigrations drives the MIGRATION transaction with the
 *    pre-migration snapshot, stamps the shared migration seal, and pins
 *    the revision-mismatch negative test;
 *  - recover() re-proofs the migration ledger AFTER the dirty recovery —
 *    a stale ledger refuses with DB-REVISION even though the daemon came
 *    back healthy (the leg the desktop lane deferred until this twin);
 *  - the owner-release stop on an ended engine never touches the seals.
 */
class DesktopMariaDbEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    /** A synthetic staged distribution + its committed lockfile, laid out
     * exactly as the build lane's staging contract demands. */
    @Suppress("LongMethod") // the staging contract's document tree, one fixture
    private fun stageProvider(base: File): File {
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
            .put("bootstrap_sha256", sha256(bootstrap))
            .put(
                "executables", JSONObject()
                    .put(
                        "mariadbd", JSONObject()
                            .put("name", "bin/mariadbd.exe")
                            .put("size", mariadbd.length())
                            .put("sha256", sha256(mariadbd)),
                    )
                    .put(
                        "mariadb", JSONObject()
                            .put("name", "bin/mariadb.exe")
                            .put("size", mariadb.length())
                            .put("sha256", sha256(mariadb)),
                    ),
            )
        val manifestFile = File(staging, "runtime-manifest.json").apply { writeText(manifest.toString()) }
        // An EMPTY pinned migration set: applyPinnedMigrations drives the
        // full transaction + revision gates with zero SQL entries.
        val migrationManifest = JSONObject()
            .put("schema", 1)
            .put("entries", JSONArray())
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
        File(repo, DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH).writeText(
            JSONObject()
                .put("schema", 1)
                .put("abi", "windows-x86_64")
                .put("database_backend", "mariadb")
                .put("provider", JSONObject().put("id", DatabaseRuntimeContract.WINDOWS_PROVIDER_ID))
                .put("manifest_sha256", sha256(manifestFile))
                .put("bootstrap_sha256", sha256(bootstrap))
                .put("migration_manifest_sha256", sha256(migrationManifestFile))
                .put("migration_count", 0)
                .put(
                    "artifacts", JSONArray()
                        .put(artifactPin(repo, mariadbd))
                        .put(artifactPin(repo, mariadb)),
                )
                .toString(),
        )
        return staging
    }

    /** The lockfile artifact row exactly as tools/build_win_realm_runtime.
     * py writes it: `name` staging-relative (the staged manifest's `name`
     * shape) + `path` repo-relative (provenance) — BOTH forward-slash
     * (python as_posix(); File.toRelativeString would yield backslashes
     * on Windows and never match the manifest's forward-slash names). */
    private fun artifactPin(repo: File, file: File): JSONObject {
        val staging = File(repo, "native/.build-win-x86_64/mariadb-provider")
        return JSONObject()
            .put("name", file.toRelativeString(staging).replace(File.separatorChar, '/'))
            .put("path", file.toRelativeString(repo).replace(File.separatorChar, '/'))
            .put("size", file.length())
            .put("sha256", sha256(file))
    }

    /** The scripted process seam: mariadbd --bootstrap materializes the
     * datadir's mysql/ proof files; the client answers the fixed queries
     * (health OK, the least-privilege negative fails, revision checks
     * accept the pinned columns only); SHUTDOWN ends the daemon. The
     * timeframe-overlay legs simulate the pocketrealm_meta stage ledger
     * (the upsert feeds the read) and the world-DB overlay statements. */
    private class ScriptedProcesses(private val roots: DesktopStorageRoots) : Processes {
        val toolCalls = mutableListOf<List<String>>()

        /** Every client invocation as (args, sql) — the dialect pins. */
        val clientRuns = mutableListOf<Pair<List<String>, String>>()

        /** The world-DB overlay statement batches the leg executed. */
        val worldOverlayRuns = mutableListOf<String>()

        /** The simulated pocketrealm_meta.timeframe_stage_state row, or
         * null when no stage is recorded yet. */
        var stageStateRow: String? = null

        /** Refuse the world-DB overlay statements (the failure funnel). */
        var failWorldOverlay = false

        /** Refuse the authenticated health query (the post-spawn failure
         * funnel: the daemon is up, the health check then fails). */
        var failHealth = false

        /** Make the daemon spawn itself throw (the spawn-failure leg). */
        var failSpawn = false

        var daemonRunning = false
        var daemonPid = 4242L

        private fun ok(stdout: String = "") = DatabaseRunResult(0, 0, false, stdout, "")
        private fun refused(detail: String) = DatabaseRunResult(1, 0, false, "", detail)

        override fun runTool(
            executable: File,
            args: List<String>,
            environment: Map<String, String>,
            stdin: File?,
            timeoutMs: Long,
        ): DatabaseRunResult {
            toolCalls.add(args)
            if (args.contains("--bootstrap")) {
                val datadir = File(args.first { it.startsWith("--datadir=") }.removePrefix("--datadir="))
                File(datadir, "mysql").mkdirs()
                File(datadir, "mysql/global_priv.MYD").writeText("privileges")
                return ok()
            }
            val sql = stdin?.readText() ?: ""
            clientRuns.add(args to sql)
            return when {
                "SHUTDOWN" in sql -> {
                    daemonRunning = false
                    ok()
                }
                "timeframe_stage_state" in sql -> when {
                    "CREATE TABLE" in sql -> ok()
                    "INSERT INTO" in sql -> {
                        // Simulate the upsert: the VALUES row becomes the
                        // table's single row (id = 1), so the next read
                        // answers it back.
                        val match = Regex(
                            "VALUES \\(1, '(.*?)', '(.*?)', (\\d+)\\)",
                        ).find(sql)
                        checkNotNull(match) { "scripted: unparseable stage upsert" }
                        stageStateRow = "${match.groupValues[1]}\t${match.groupValues[2]}\t${match.groupValues[3]}"
                        ok()
                    }
                    else -> ok(stageStateRow ?: "")
                }
                "INSERT IGNORE INTO" in sql -> {
                    worldOverlayRuns.add(sql)
                    if (failWorldOverlay) {
                        refused("scripted: overlay statement rejected")
                    } else {
                        ok()
                    }
                }
                "POCKET_DB_OK" in sql -> if (failHealth) {
                    refused("scripted: health query rejected")
                } else {
                    ok("POCKET_DB_OK\t11.5.2-MariaDB\t1")
                }
                "pocket_forbidden" in sql -> refused("scripted: account administration denied")
                "required_z9999_not_real" in sql -> ok("0")
                "information_schema.COLUMNS" in sql -> ok("1")
                "pocket_admin" in sql -> ok()
                "migration_ledger" in sql -> ok()
                "realmlist" in sql -> {
                    // The projection leg's verify read answers the row its
                    // UPDATE just "wrote": address/port plus the builds
                    // advertisement the UPDATE carried.
                    val builds = Regex("realmbuilds='([^']*)'").find(sql)
                        ?.groupValues?.get(1) ?: ""
                    ok("127.0.0.1\t8085\t$builds")
                }
                else -> refused("scripted: unexpected client sql: ${sql.take(80)}")
            }
        }

        override fun startDaemon(
            executable: File,
            args: List<String>,
            outputRedirect: File,
            errorRedirect: File,
        ): Long {
            check(!failSpawn) { "scripted: daemon spawn refused" }
            daemonRunning = true
            return daemonPid
        }

        override fun alive(pid: Long): Boolean = daemonRunning && pid == daemonPid

        override fun killTree(pid: Long): Boolean {
            daemonRunning = false
            return pid == daemonPid
        }

        override fun pipeExists(name: String): Boolean = daemonRunning && name == "mariadb.sock"
    }

    private class Lane(
        val roots: DesktopStorageRoots,
        val staging: File,
        val processes: ScriptedProcesses,
    ) {
        fun engine(): DesktopMariaDbEngine = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = provider(),
            processes = processes,
        )

        fun provider(): DesktopMariaDbProvider = DesktopMariaDbProvider(
            stagedRootOverride = staging,
            repoRootOverride = staging.parentFile.parentFile.parentFile,
        )
    }

    private fun freshLane(): Lane {
        // Unique roots/repo names: a test may build SEVERAL lanes in one
        // method (the foreign-generation restore leg builds a second
        // lane's datadir), and TemporaryFolder refuses a repeated fixed
        // name within the same test.
        val seq = laneCounter++
        val roots = DesktopStorageRoots(folder.newFolder("roots-$seq")).apply { ensureDirectories() }
        val staging = stageProvider(folder.newFolder("repo-root-$seq"))
        return Lane(roots, staging, ScriptedProcesses(roots))
    }

    /** The fresh-lane fixture's name sequence (per test method). */
    private var laneCounter: Int = 0

    /** Initializes a lane and returns it (the happy-path fixture). */
    private fun initializedLane(): Lane {
        val lane = freshLane()
        val result = lane.engine().initialize()
        assertTrue("initialize must succeed: $result", result.getBoolean("ok"))
        return lane
    }

    // ------------------------------------------------------------------
    // The unstaged posture (DESK-78's declared lane until the distribution)
    // ------------------------------------------------------------------

    @Test
    fun unstagedProviderRefusesTheMutatingVerbsAndStatusStaysHonest() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }
        val engine = DesktopMariaDbEngine(
            roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = folder.newFolder("nothing-here"),
                repoRootOverride = folder.newFolder("repo-empty"),
            ),
        )
        val status = engine.status()
        assertFalse("unstaged provider must not report ready", status.getBoolean("providerReady"))
        assertEquals("STOPPED", status.getString("state"))
        // initialize / start / applyPinnedMigrations hit the provider gate.
        listOf(
            { engine.initialize() },
            { engine.start() },
            { engine.applyPinnedMigrations() },
        ).forEach { verb ->
            try {
                verb()
                fail("an unstaged provider must refuse the mutating verbs")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the DB-LINK copy: ${expected.message}",
                    (expected.message ?: "").contains("DB-LINK"),
                )
            }
        }
        // stop on an inert engine takes its state-gate copy first (the
        // Android leg's order); nothing has leaked into the datadir.
        try {
            engine.stop()
            fail("stop must refuse an engine that never ran")
        } catch (expected: IllegalStateException) {
            assertTrue(
                (expected.message ?: "").contains("database is not running"),
            )
        }
        assertTrue(
            "the datadir must stay untouched by refused verbs",
            roots.mariadbDatadir.listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun bundledImageLockfileVerifiesThePackagedClosure() {
        // The packaged posture: a jpackage install has no schemas/ checkout
        // to walk up to, so packageApp stages the committed lockfile FLAT
        // beside the DLLs and the closure under mariadb-provider/ — both
        // resolved through java.library.path ($APPDIR). Without the bundled
        // pin the MARIADB default could only refuse with DB-LINK inside an
        // install, whatever packageApp staged.
        val appDir = folder.newFolder("appdir")
        val staging = stageProvider(folder.newFolder("repo-root"))
        val repo = staging.parentFile.parentFile.parentFile
        File(repo, DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH)
            .copyTo(File(appDir, DesktopMariaDbProvider.LOCKFILE_BUNDLED_NAME))
        staging.copyRecursively(File(appDir, "mariadb-provider"))
        val originalLibraryPath = System.getProperty("java.library.path")
        System.setProperty("java.library.path", appDir.absolutePath)
        try {
            // No repoRootOverride: the bundled flat pin — never a walked-up
            // checkout's lockfile (here the REAL repo's, which pins
            // different bytes and would refuse this staging) — verifies the
            // packaged closure.
            DesktopMariaDbProvider(stagedRootOverride = File(appDir, "mariadb-provider")).verify()
        } finally {
            System.setProperty("java.library.path", originalLibraryPath)
        }
    }

    @Test
    fun providerVerifyMatchesTheLockfilePinUnderEveryRecordedPathForm() {
        // The staged manifest's executables carry the SHORT staging-relative
        // name ("bin/mariadbd.exe"); the committed lockfile records BOTH
        // forms (the python writer's name + repo-relative path). A lockfile
        // carrying ONLY either form must still pin the real staged bytes —
        // matching one shape only would refuse a real distribution.
        val repoBase = folder.newFolder("repo-pin")
        val staging = stageProvider(repoBase)
        val provider = DesktopMariaDbProvider(
            stagedRootOverride = staging,
            // stageProvider lays the checkout out under base/repo (the same
            // three-parent walk the Lane fixture uses from staging).
            repoRootOverride = staging.parentFile.parentFile.parentFile,
        )
        // Both forms present (the tool's shape): verifies.
        provider.verify()
        // Legacy/alternative shape: repo-relative `path` only (the flagged
        // pre-dual-form lockfile) — the suffix match must still pin it.
        val lockfile = File(
            staging.parentFile.parentFile.parentFile,
            DesktopMariaDbProvider.LOCKFILE_REPOSITORY_PATH,
        )
        val record = JSONObject(lockfile.readText())
        // The ORIGINAL artifact rows, snapshotted once: rewritten() must
        // always derive from the tool's shape, never from the previous
        // leg's mutated rows (JSONObject aliases are live references).
        val originalRows = JSONArray(
            JSONObject(lockfile.readText()).getJSONArray("artifacts").toString(),
        )
        fun rewritten(transform: (JSONObject) -> JSONObject): String {
            val rows = JSONArray()
            for (i in 0 until originalRows.length()) rows.put(transform(originalRows.getJSONObject(i)))
            return record.put("artifacts", rows).toString()
        }
        lockfile.writeText(
            rewritten { row -> JSONObject(row.toString()).apply { remove("name") } },
        )
        provider.verify()
        // And the `name` key only — the manifest's own shape — verifies too.
        lockfile.writeText(
            rewritten { row -> JSONObject(row.toString()).apply { remove("path") } },
        )
        provider.verify()
        // A row pinned to a DIFFERENT path (and no `name` key) never pins
        // the staged bytes.
        lockfile.writeText(
            rewritten { row ->
                JSONObject(row.toString()).apply {
                    remove("name")
                    put("path", "somewhere/else/${File(row.getString("path")).name}")
                }
            },
        )
        try {
            provider.verify()
            fail("a foreign lockfile row must not pin the staged executables")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must carry the pin copy: ${expected.message}",
                (expected.message ?: "").contains("not pinned by the committed lockfile"),
            )
        }
    }

    // ------------------------------------------------------------------
    // Initialize: the INIT transaction + seals
    // ------------------------------------------------------------------

    @Test
    fun initializeRunsTheDurableInitTransactionAndStampsTheSeals() {
        val lane = initializedLane()
        val roots = lane.roots
        val datadir = roots.mariadbDatadir
        // The transaction is COMMITTED (deleted) after the seals.
        assertFalse(
            "a committed init deletes its transaction record",
            File(roots.database, DesktopMariaDbEngine.TRANSACTION_FILE_NAME).isFile,
        )
        // The initialized seal proves the staged closure's identity.
        val identity = lane.provider().verify().identity()
        val generationUuid = DatabaseDurableState.generationUuid(
            File(datadir, DesktopMariaDbEngine.GENERATION_MARKER_NAME).readText(),
        )
        assertTrue(
            "the initialized seal must prove the provider identity + generation",
            DatabaseDurableState.initializedCurrent(
                File(roots.database, DesktopMariaDbEngine.INITIALIZED_MARKER_NAME).readText(),
                identity,
                generationUuid,
            ),
        )
        assertTrue(
            "a fresh generation ends clean-stopped",
            DatabaseDurableState.cleanCurrent(
                File(roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).readText(),
                identity,
                generationUuid,
            ),
        )
        // The active-provider marker is the shared durable decision the
        // ServerRuntimeFiles conf branch reads.
        val active = DatabaseDurableState.parseActiveProviderMarker(
            File(roots.database, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME).readText(),
        )
        assertEquals(DatabaseDurableState.ProviderMode.MARIADB, active?.mode)
        assertEquals(DatabaseRuntimeContract.WINDOWS_PROVIDER_ID, active?.providerId)
        assertTrue("mariadb mode pins the live generation", active?.generationUuid != null)
        // The bootstrap's mysql/ proof landed in the datadir.
        assertTrue(File(datadir, "mysql/global_priv.MYD").isFile)
        assertTrue(File(datadir, "mariadb_upgrade_info").isFile)
    }

    @Test
    fun initializeIsIdempotentOnAnInitializedGeneration() {
        val lane = initializedLane()
        val second = lane.engine().initialize()
        assertTrue(second.getBoolean("ok"))
        assertTrue(second.getBoolean("idempotent"))
    }

    // ------------------------------------------------------------------
    // The prepare-for-start gates
    // ------------------------------------------------------------------

    @Test
    fun startRefusesStaleMigrationsThenAppliesAndStarts() {
        val lane = initializedLane()
        val engine = lane.engine()
        try {
            engine.start()
            fail("start must refuse while the migration ledger is not current")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must be the StartBlocker copy: ${expected.message}",
                (expected.message ?: "").contains("DB-REVISION: pinned migrations are not current"),
            )
        }
        val applied = engine.applyPinnedMigrations()
        assertTrue("apply must succeed: $applied", applied.getBoolean("ok"))
        assertEquals(0, applied.getInt("applied"))
        assertTrue(applied.getBoolean("revisionMismatchRejected"))
        val started = engine.start()
        assertTrue(started.getBoolean("authenticated"))
        assertTrue(started.getBoolean("leastPrivilege"))
        engine.stop()
    }

    @Test
    fun startRefusesADirtyGenerationWithTheRecoveryCopy() {
        val lane = initializedLane()
        val engine = lane.engine()
        // Migrations first (the StartBlocker precedence: staleness would
        // fire before dirtiness), then retire the clean seal.
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).delete(),
        )
        try {
            engine.start()
            fail("start must refuse a dirty generation")
        } catch (expected: IllegalStateException) {
            assertTrue(
                (expected.message ?: "").contains(
                    "DB-RECOVERY: database generation is not sealed by a valid clean-stop marker",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // The status JSON's daemon pid (GAP-5)
    // ------------------------------------------------------------------

    @Test
    fun statusJsonCarriesTheDaemonPidWhenAliveAndOmitsItWhenDown() {
        // GAP-5: the status JSON's daemon pid is the mariadbd the ENGINE
        // spawned and tracks (startDaemon's handle), never the app JVM's
        // ambient pid — and it is omitted (JSON null) whenever the engine is
        // not RUNNING: a fabricated pid is the defect class being fixed.
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        val stoppedBefore = engine.status()
        assertEquals("STOPPED", stoppedBefore.getString("state"))
        assertTrue(
            "a stopped engine must omit the daemon pid",
            stoppedBefore.isNull("daemonPid"),
        )
        engine.start()
        val running = engine.status()
        assertEquals("RUNNING", running.getString("state"))
        assertEquals(
            "the daemon pid must be the tracked mariadbd handle",
            lane.processes.daemonPid,
            running.getLong("daemonPid"),
        )
        assertTrue(
            "the daemon pid must never be the ambient JVM pid",
            running.getLong("daemonPid") != ProcessHandle.current().pid(),
        )
        engine.stop()
        val stoppedAfter = engine.status()
        assertEquals("STOPPED", stoppedAfter.getString("state"))
        assertTrue(
            "a clean stop must omit the daemon pid",
            stoppedAfter.isNull("daemonPid"),
        )
    }

    // ------------------------------------------------------------------
    // Dirty recovery + the migration-ledger re-proof
    // ------------------------------------------------------------------

    @Test
    fun recoverReprovesTheMigrationLedgerAfterTheDirtyRecovery() {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        // Kill the generation dirty (the clean seal retired, no shutdown).
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).delete(),
        )
        val recovered = engine.recover()
        assertTrue("recovery must succeed: $recovered", recovered.getBoolean("ok"))
        assertFalse(recovered.getBoolean("recoveryOutputObserved"))
        assertTrue(recovered.getBoolean("cleanStopped"))
        // The recovery ends clean-stopped AND ledger-current.
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).isFile,
        )
    }

    @Test
    fun recoverRefusesWhenTheMigrationLedgerCannotBeReproven() {
        val lane = initializedLane()
        val engine = lane.engine()
        // The ledger was never proven (no migration seal): a dirty recovery
        // comes back healthy but MUST refuse — the migration-ledger re-proof
        // is the leg recovery exists for.
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).delete(),
        )
        try {
            engine.recover()
            fail("recovery must refuse a generation whose migration ledger is not current")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the re-proof: ${expected.message}",
                (expected.message ?: "").contains("DB-REVISION"),
            )
            assertTrue(
                (expected.message ?: "").contains("migration ledger is not current"),
            )
        }
        // The daemon was clean-stopped by the recovery attempt before the
        // refusal (no live generation left behind).
        assertFalse(lane.processes.daemonRunning)
    }

    // ------------------------------------------------------------------
    // The ownership handshake's stop semantics
    // ------------------------------------------------------------------

    @Test
    fun ownerReleaseOnAnEndedEngineLeavesTheSealsUntouched() {
        val lane = initializedLane()
        val cleanBefore = File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).readText()
        val engine = lane.engine()
        assertTrue(engine.isEnded())
        val released = engine.stopForOwnerRelease()
        assertTrue(released.getBoolean("ok"))
        assertTrue(released.getBoolean("alreadyDown"))
        assertTrue(released.getBoolean("claimReleased"))
        assertEquals(
            "the clean seal is neither fabricated nor deleted by a claim release",
            cleanBefore,
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).readText(),
        )
    }

    // ------------------------------------------------------------------
    // The shared decision codec's desktop consumption
    // ------------------------------------------------------------------

    @Test
    fun durableStateSharedCodecPinsTheDesktopLaneDecisions() {
        // DatabaseDurableState (now SHARED — the DESK-5 manifest leg) drives
        // the same decisions on both lanes; pin the desktop lane's inputs.
        assertEquals(
            DatabaseDurableState.StartBlocker.MIGRATIONS_STALE,
            DatabaseDurableState.startBlocker(true, false, true, false),
        )
        assertEquals(
            DatabaseDurableState.StartBlocker.DIRTY,
            DatabaseDurableState.startBlocker(true, true, false, false),
        )
        assertEquals(
            DatabaseDurableState.StartBlocker.TRANSACTION_PENDING,
            DatabaseDurableState.startBlocker(true, true, true, true),
        )
        assertNull(DatabaseDurableState.startBlocker(true, true, true, false))
        assertTrue(
            DatabaseDurableState.dirtyRecoveryPermitted(
                initialized = true, clean = false, transactionPending = false,
            ),
        )
        assertFalse(
            DatabaseDurableState.dirtyRecoveryPermitted(
                initialized = true, clean = false, transactionPending = true,
            ),
        )
    }

    // ------------------------------------------------------------------
    // The timeframe overlay leg (the MariaDB lane's world-start stage)
    // ------------------------------------------------------------------

    /** The shared started-lane fixture: initialized + migrations sealed +
     * daemon RUNNING (the state a world start reaches the overlay in). */
    private fun startedLane(): Pair<Lane, DesktopMariaDbEngine> {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        assertTrue(engine.start().getBoolean("ok"))
        return lane to engine
    }

    @Test
    fun timeframeOverlayAppliesThePinnedPlanInTheMariadbDialect() {
        // One lane drives the whole order: the overlay leg needs the LIVE
        // daemon (the world-start order: the database component starts
        // before the world), refuses before it, applies after.
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        try {
            engine.applyTimeframeOverlay("vanilla-1.5")
            fail("the overlay leg must refuse while the daemon is not running")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must be the state gate: ${expected.message}",
                (expected.message ?: "").contains("database is not running"),
            )
        }
        assertTrue(engine.start().getBoolean("ok"))
        val result = engine.applyTimeframeOverlay("vanilla-1.5")
        assertTrue("overlay must succeed: $result", result.getBoolean("ok"))
        assertEquals("vanilla-1.5", result.getString("appliedStage"))
        assertEquals(
            com.pocketrealm.client.GeneratedTimeframeOverlayCatalog.overlaySha256["vanilla-1.5"],
            result.getString("overlaySha256"),
        )
        // The world-DB statements are the SAME pinned catalog plan in the
        // MariaDB dialect: INSERT IGNORE (never the SQLite OR form), all
        // of the stage's statements, against the qualified lane's world
        // database as the least-privileged core user.
        val worldRun = lane.processes.clientRuns.single { it.second.contains("INSERT IGNORE INTO") }
        assertTrue(
            "the marker event statement must be present: ${worldRun.second}",
            worldRun.second.contains("INSERT IGNORE INTO game_event"),
        )
        assertTrue(
            "the marker condition statement must be present: ${worldRun.second}",
            worldRun.second.contains("INSERT IGNORE INTO conditions"),
        )
        assertFalse(
            "the SQLite OR-form must be translated away: ${worldRun.second}",
            worldRun.second.contains("OR IGNORE"),
        )
        assertTrue(
            "the world statements run as the least-privileged core user",
            worldRun.first.contains("--user=pocket_core"),
        )
        assertTrue(
            "the world statements target the world database",
            worldRun.first.contains("--database=classicmangos"),
        )
        // The stage ledger upsert is MariaDB's ON DUPLICATE KEY UPDATE
        // form (never the SQLite ON CONFLICT/excluded grammar), run as
        // the owner like every migration-ledger write.
        val upsert = lane.processes.clientRuns.single {
            it.second.contains("timeframe_stage_state") && it.second.contains("INSERT INTO")
        }
        assertTrue(
            "the meta upsert must use MariaDB's upsert grammar: ${upsert.second}",
            upsert.second.contains("ON DUPLICATE KEY UPDATE"),
        )
        assertFalse(
            "the SQLite upsert grammar must not cross the MariaDB client: ${upsert.second}",
            upsert.second.contains("ON CONFLICT"),
        )
        assertTrue(
            "the meta ledger writes run owner-privileged",
            upsert.first.contains("--user=pocket_admin"),
        )
        // PROVIDER-AWARE SETTING PIN: nothing the leg emitted carries the
        // SQLite connection discipline — no PRAGMA, no SET SESSION (the
        // DB-CONFIG refusal family cannot arise on this lane; MariaDB's
        // durability is the daemon's own my.cnf, DatabaseConfigPolicy's
        // innodb-flush-log-at-trx-commit/sync-binlog pins).
        lane.processes.clientRuns.forEach { (_, sql) ->
            assertFalse("no PRAGMA may cross the MariaDB client: $sql", sql.contains("PRAGMA"))
            assertFalse("no SET SESSION may cross the MariaDB client: $sql", sql.contains("SET SESSION"))
        }
        engine.stop()
    }

    @Test
    fun timeframeOverlayIsIdempotentAndTheDefaultStageTouchesNoWorldDb() {
        val (lane, engine) = startedLane()
        val first = engine.applyTimeframeOverlay("vanilla-1.5")
        assertTrue(first.getBoolean("ok"))
        assertEquals(1, lane.processes.worldOverlayRuns.size)
        // Re-selecting the applied stage short-circuits against the MariaDB
        // stage ledger (the retained row answers the read).
        val second = engine.applyTimeframeOverlay("vanilla-1.5")
        assertTrue(second.getBoolean("ok"))
        assertTrue(second.getBoolean("idempotent"))
        assertEquals("vanilla-1.5", second.getString("appliedStage"))
        assertEquals(1, lane.processes.worldOverlayRuns.size)
        // The DEFAULT stage is the seed itself: recorded in MariaDB's meta
        // ledger, zero world-DB statements executed.
        val defaultStage = com.pocketrealm.client.TimeframeStages.defaultStageId(
            com.pocketrealm.client.ClientFlavors.qualified().single().flavorId,
        )
        val defaulted = engine.applyTimeframeOverlay(defaultStage)
        assertTrue(defaulted.getBoolean("ok"))
        assertEquals("default-seed", defaulted.getString("overlaySha256"))
        assertEquals(defaultStage, lane.processes.stageStateRow?.substringBefore('\t'))
        assertEquals(
            "the default stage must not execute world-DB statements",
            1,
            lane.processes.worldOverlayRuns.size,
        )
        // And the recorded default answers the next read (idempotent).
        assertTrue(engine.applyTimeframeOverlay(defaultStage).getBoolean("idempotent"))
        engine.stop()
    }

    @Test
    fun aFailedOverlayStatementRefusesAndRecordsNoStage() {
        val (lane, engine) = startedLane()
        lane.processes.failWorldOverlay = true
        try {
            engine.applyTimeframeOverlay("vanilla-1.9")
            fail("a failed overlay statement must refuse the world start")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the stage and the failure: ${expected.message}",
                (expected.message ?: "").contains("timeframe overlay for \"vanilla-1.9\" failed"),
            )
        }
        // The ledger write follows success only: no stage row was recorded,
        // so the next world start re-applies the idempotent plan cleanly.
        assertNull(lane.processes.stageStateRow)
        engine.stop()
    }

    // ------------------------------------------------------------------
    // The transactional restore (the desktop restore transaction's engine
    // leg — the Android beginRestore/commitRestore/rollbackRestore twin)
    // ------------------------------------------------------------------

    /** A named backup of the lane's datadir through the SHARED store, the
     * way the backend's createNamedBackup arm takes it. */
    private fun namedBackupOf(lane: Lane, id: String): DatabaseSnapshotStore.Snapshot =
        DatabaseSnapshotStore(lane.roots.databaseSnapshots)
            .create(lane.roots.mariadbDatadir, id, databaseStopped = true)

    private fun restoreTrees(roots: DesktopStorageRoots): List<File> =
        roots.database.listFiles { file ->
            file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                file.name.startsWith("restore-original-"))
        }.orEmpty().toList()

    @Test
    fun restoreTransactionSwapsCommitsAndKeepsTheGenerationStartable() {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = namedBackupOf(lane, "manual-keep-${System.currentTimeMillis()}")
        File(datadir, "realm_note.txt").writeText("moved-on")

        val begun = engine.beginRestore(snapshot.id)
        assertTrue("begin must succeed: $begun", begun.getBoolean("ok"))
        assertEquals(snapshot.digest, begun.getString("snapshotDigest"))
        // The candidate is live: the snapshot's bytes are the datadir's,
        // and the swapped-in generation carries a current clean seal (the
        // restamp — the rollbackToSnapshot machinery's own discipline).
        assertEquals("before", File(datadir, "realm_note.txt").readText())
        assertTrue(
            "the restamped clean seal must be current",
            engine.status().getBoolean("cleanMarker"),
        )
        // The window is OPEN (durable record on disk) but driven by THIS
        // engine: restorePending must read false so the proof boot between
        // BEGIN and COMMIT passes the shared preparation machine instead of
        // being treated as a dead process's leftover.
        assertTrue(
            "the window's durable record must exist",
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertFalse(
            "the driven window must not read as a leftover",
            engine.status().getBoolean("restorePending"),
        )

        val committed = engine.commitRestore(begun.getString("restoreToken"))
        assertTrue("commit must succeed: $committed", committed.getBoolean("ok"))
        assertEquals("before", File(datadir, "realm_note.txt").readText())
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertFalse(engine.status().getBoolean("restorePending"))
        assertTrue(
            "commit must retire the candidate/quarantine trees",
            restoreTrees(lane.roots).isEmpty(),
        )
        // The committed generation is startable as-is: the seals and the
        // migration ledger over the restored bytes still admit a start.
        assertTrue(engine.start().getBoolean("ok"))
        engine.stop()
    }

    @Test
    fun restoreRollsTheOriginalDatadirBackWhenTheWindowFails() {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = namedBackupOf(lane, "manual-back-${System.currentTimeMillis()}")
        File(datadir, "realm_note.txt").writeText("moved-on")

        val token = engine.beginRestore(snapshot.id).getString("restoreToken")
        assertEquals("before", File(datadir, "realm_note.txt").readText())
        val rolledBack = engine.rollbackRestore(token)
        assertTrue("rollback must succeed: $rolledBack", rolledBack.getBoolean("ok"))
        assertTrue(rolledBack.getBoolean("rolledBack"))
        assertEquals(
            "the ORIGINAL datadir must be back",
            "moved-on",
            File(datadir, "realm_note.txt").readText(),
        )
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertTrue("rollback must leave no trees", restoreTrees(lane.roots).isEmpty())
        assertFalse(engine.status().getBoolean("restorePending"))
    }

    @Test
    fun anInterruptedRestoreStaysPendingForTheNextEngineUntilRolledBack() {
        val lane = initializedLane()
        val driver = lane.engine()
        assertTrue(driver.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = namedBackupOf(lane, "manual-crash-${System.currentTimeMillis()}")
        File(datadir, "realm_note.txt").writeText("moved-on")
        val token = driver.beginRestore(snapshot.id).getString("restoreToken")

        // A fresh engine over the same roots is the post-crash process: the
        // durable record reads as a pending restore (the shared preparation
        // machine's ROLLBACK_PENDING_RESTORE input) — never a hardcoded
        // false.
        val next = lane.engine()
        assertTrue(
            "the leftover record must read pending once its driver is gone",
            next.status().getBoolean("restorePending"),
        )
        val recovery = next.rollbackRestore(token)
        assertTrue("the recovery rollback must succeed: $recovery", recovery.getBoolean("ok"))
        assertEquals("moved-on", File(datadir, "realm_note.txt").readText())
        assertFalse(next.status().getBoolean("restorePending"))
        assertTrue(restoreTrees(lane.roots).isEmpty())
    }

    @Test
    fun rollbackPendingRestoreSettlesADeadProcessLeftoverWithoutAToken() {
        val lane = initializedLane()
        val driver = lane.engine()
        assertTrue(driver.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = namedBackupOf(lane, "manual-dead-${System.currentTimeMillis()}")
        File(datadir, "realm_note.txt").writeText("moved-on")
        driver.beginRestore(snapshot.id)
        assertEquals(
            "the candidate must be live before the \"crash\"",
            "before",
            File(datadir, "realm_note.txt").readText(),
        )

        // The post-crash process: a FRESH engine with NO token — the driver's
        // in-process one died with it. The ownerless recovery verb settles the
        // leftover record the shared preparation machine reports as
        // ROLLBACK_PENDING_RESTORE (the Android rollbackPendingRestore twin).
        val next = lane.engine()
        assertTrue(next.status().getBoolean("restorePending"))
        val settled = next.rollbackPendingRestore()
        assertTrue("the tokenless recovery must succeed: $settled", settled.getBoolean("ok"))
        assertTrue(settled.getBoolean("pending"))
        assertTrue("a non-COMMITTING leftover must roll back", settled.getBoolean("rolledBack"))
        assertEquals(
            "the ORIGINAL datadir must be back",
            "moved-on",
            File(datadir, "realm_note.txt").readText(),
        )
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
        assertTrue("recovery must leave no trees", restoreTrees(lane.roots).isEmpty())
        assertFalse(next.status().getBoolean("restorePending"))
        // The recovered generation is startable as-is, and the verb is
        // idempotent on a clean lane (nothing pending, nothing touched).
        assertTrue(next.start().getBoolean("ok"))
        next.stop()
        val idle = next.rollbackPendingRestore()
        assertTrue(idle.getBoolean("ok"))
        assertFalse(idle.getBoolean("pending"))
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
    }

    @Test
    fun rollbackPendingRestoreFinishesTheCommitWhenTheCrashHitTheRetirementWindow() {
        val lane = initializedLane()
        val driver = lane.engine()
        assertTrue(driver.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = namedBackupOf(lane, "manual-commit-${System.currentTimeMillis()}")
        File(datadir, "realm_note.txt").writeText("moved-on")
        driver.beginRestore(snapshot.id)

        // The crash window INSIDE commitRestore: the durable phase is already
        // COMMITTING but the record's retirement (finishRestoreCommit) never
        // ran. The phase is durable state on disk, so the window is simulated
        // by rewriting exactly the bytes the crashed commit leg had written.
        val record = File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME)
        DatabaseDurability.atomicWrite(
            record,
            JSONObject(record.readText()).put("phase", "COMMITTING").toString(),
        )

        // The fresh process must FINISH the commit (the shared restoreRecovery
        // verdict for COMMITTING), never roll a committing restore back.
        val next = lane.engine()
        val settled = next.rollbackPendingRestore()
        assertTrue("the tokenless commit-finish must succeed: $settled", settled.getBoolean("ok"))
        assertTrue(settled.getBoolean("pending"))
        assertTrue(settled.getBoolean("committed"))
        assertFalse(settled.getBoolean("rolledBack"))
        assertEquals(
            "the verified candidate must stay live",
            "before",
            File(datadir, "realm_note.txt").readText(),
        )
        assertFalse(record.isFile)
        assertTrue("the finished commit must retire both trees", restoreTrees(lane.roots).isEmpty())
        assertFalse(next.status().getBoolean("restorePending"))
    }

    @Test
    fun beginRestoreRefusesTheNonNamedPendingAndDirtyStates() {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("held")

        // A pre-migration snapshot (the engine's own transactional kind) is
        // not a named backup.
        val preMigration = DatabaseSnapshotStore(lane.roots.databaseSnapshots).list()
            .single { it.id.startsWith("pre-migration-") }
        try {
            engine.beginRestore(preMigration.id)
            fail("a non-named snapshot must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must be the named-backup copy: ${expected.message}",
                (expected.message ?: "").contains("only named backups may be restored"),
            )
        }

        // A second transaction while one is pending refuses by name.
        val first = namedBackupOf(lane, "manual-first-${System.currentTimeMillis()}")
        val second = namedBackupOf(lane, "manual-second-${System.currentTimeMillis()}")
        val token = engine.beginRestore(first.id).getString("restoreToken")
        try {
            engine.beginRestore(second.id)
            fail("a pending restore must refuse a second begin")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the pending verification: ${expected.message}",
                (expected.message ?: "").contains("another restore verification is pending"),
            )
        }
        assertTrue(engine.rollbackRestore(token).getBoolean("rolledBack"))

        // A dirty generation (no clean-stop seal) refuses without a
        // candidate ever being restored.
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).delete(),
        )
        val dirtyBackup = namedBackupOf(lane, "manual-dirty-${System.currentTimeMillis()}")
        try {
            engine.beginRestore(dirtyBackup.id)
            fail("a dirty generation must refuse the restore")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must demand the clean generation: ${expected.message}",
                (expected.message ?: "").contains("clean current stopped datadir required"),
            )
        }
        assertEquals("held", File(datadir, "realm_note.txt").readText())
        assertTrue(restoreTrees(lane.roots).isEmpty())
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
    }

    @Test
    fun beginRestoreRefusesAForeignGenerationCandidateWithoutMovingTheLiveDatadir() {
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        File(lane.roots.mariadbDatadir, "realm_note.txt").writeText("live-a")

        // A DIFFERENT lane's generation: its datadir carries another
        // generation's marker, so the candidate probe must refuse before
        // the live datadir moves at all (the seals outside the datadir pin
        // THIS generation — a foreign one would strand the lane). Built on
        // its own uniquely-named temp folders (the fixture's initializedLane
        // hard-codes "roots"/"repo-root" — once per TemporaryFolder).
        val otherRoots = DesktopStorageRoots(folder.newFolder("roots-foreign")).apply {
            ensureDirectories()
        }
        val other = Lane(
            otherRoots,
            stageProvider(folder.newFolder("repo-root-foreign")),
            ScriptedProcesses(otherRoots),
        )
        assertTrue(other.engine().initialize().getBoolean("ok"))
        assertTrue(other.engine().applyPinnedMigrations().getBoolean("ok"))
        val foreign = DatabaseSnapshotStore(other.roots.databaseSnapshots).create(
            other.roots.mariadbDatadir,
            "manual-foreign-${System.currentTimeMillis()}",
            databaseStopped = true,
        )
        foreign.root.copyRecursively(File(lane.roots.databaseSnapshots, foreign.id))
        try {
            engine.beginRestore(foreign.id)
            fail("a foreign-generation candidate must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must be the generation probe: ${expected.message}",
                (expected.message ?: "").contains("restored candidate generation mismatch"),
            )
        }
        assertEquals(
            "the live datadir must be untouched",
            "live-a",
            File(lane.roots.mariadbDatadir, "realm_note.txt").readText(),
        )
        assertTrue(restoreTrees(lane.roots).isEmpty())
        assertFalse(
            File(lane.roots.database, DesktopMariaDbEngine.RESTORE_RECORD_NAME).isFile,
        )
    }

    // ------------------------------------------------------------------
    // The realm endpoint projection (the row realmd advertises)
    // ------------------------------------------------------------------

    @Test
    fun realmEndpointProjectionWritesTheFlavorsAcceptedBuilds() {
        // The pinned realmd seed's realmlist row ships realmbuilds='' (the
        // source dump's bytes, hash-bound to the manifest), and no migration
        // ever writes the column — realmd's ok_build gate would flag the
        // realm offline for EVERY build until the world's own start UPDATE.
        // The projection is the lane's one owner of the row before realmd
        // serves a logon: it must write the flavor's accepted-build list
        // (the C1 vanilla advertisement "5875 6005 6141") beside the
        // endpoint, and verify the whole row back.
        val (lane, engine) = startedLane()
        val result = engine.projectRealmEndpoint(
            "127.0.0.1",
            com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
            "5875 6005 6141",
        )
        assertTrue("projection must succeed: $result", result.getBoolean("ok"))
        assertEquals("realm-endpoint-projected", result.getString("operation"))
        val run = lane.processes.clientRuns.single { it.second.contains("UPDATE classicrealmd.realmlist") }
        assertTrue(
            "the UPDATE must set the accepted-build list: ${run.second}",
            "realmbuilds='5875 6005 6141'" in run.second,
        )
        assertTrue(
            "the verify read must prove the whole advertisement row: ${run.second}",
            "SELECT address,port,realmbuilds FROM classicrealmd.realmlist WHERE id=1 LIMIT 1;" in run.second,
        )
        assertTrue(
            "the projection runs as the least-privileged core user",
            "--user=pocket_core" in run.first,
        )
        // A second projection of the same endpoint stays idempotent.
        val again = engine.projectRealmEndpoint(
            "127.0.0.1",
            com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
            "5875 6005 6141",
        )
        assertTrue(again.getBoolean("ok"))
        assertEquals("realm-endpoint-already-projected", again.getString("operation"))
        engine.stop()
    }

    @Test
    fun realmEndpointProjectionTargetsTheActiveFlavorsRealmdDatabase() {
        // The MariaDB leg's flavor routing (the sqlite lane's GAP-4 twin,
        // DesktopRuntimeBackend's ServerRuntimeFiles.serverDatabases()
        // resolution): the projected database resolves through the flavor
        // registry AT PROJECTION TIME — this lane booted under the vanilla
        // registry — so the selected lane's row gets the advertisement at
        // the S5 flip, an ambiguous registry refuses loud before any SQL
        // crosses the client, and vanilla-only state keeps the exact
        // classicrealmd bytes (the first test above).
        val (lane, engine) = startedLane()
        var resultOpt: JSONObject? = null
        try {
            val vanilla = testFlavor("vanilla", qualified = true)
            val wotlk = testFlavor("wotlk", qualified = true, build = 12340)
            ActiveFlavor.qualifiedSource = { listOf(vanilla, wotlk) }
            try {
                engine.projectRealmEndpoint(
                    "127.0.0.1",
                    com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                    "12340",
                )
                fail("two qualified flavors without a selection must refuse, not guess vanilla")
            } catch (expected: IllegalStateException) {
                // the machine's own ambiguous-registry refusal (ISE), the
                // shape this seam refused with before the guard existed
                assertTrue(
                    "the refusal must be the machine's honest one: ${expected.message}",
                    (expected.message ?: "").contains("found 2"),
                )
            }
            // R1-r3-4 (round 3): the projection resolves only the VANILLA
            // lane's realmd database (the W1.2 per-flavor-seeding deferral,
            // the android DatabaseEngine.realmdDatabaseName guard's desktop
            // twin): a non-vanilla selection at a multi-qualified flip
            // REFUSES loud before any SQL crosses — never a wrong-lane row
            // mutation (the flip's lane threading rides the boot lane on
            // the backend, not the engine's SQL target).
            ActiveFlavor.select("wotlk")
            val refused = runCatching {
                engine.projectRealmEndpoint(
                    "127.0.0.1",
                    com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                    "12340",
                )
            }.exceptionOrNull()
            assertTrue(
                "a non-vanilla selection at a multi-qualified flip must " +
                    "refuse loud BEFORE any SQL crosses (the guard's " +
                    "exactly-one-qualified leg answers first): $refused",
                refused is IllegalArgumentException,
            )
            ActiveFlavor.select("vanilla")
            val result: JSONObject = engine.projectRealmEndpoint(
                "127.0.0.1",
                com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                "12340",
            )
            resultOpt = result
            assertTrue("the vanilla-selected projection must succeed: $result", result.getBoolean("ok"))
            assertEquals("realm-endpoint-projected", result.getString("operation"))
            // the synthetic vanilla lane's own database name (the fixture's
            // testFlavor("vanilla") carries vanillarealmd, not the registry
            // vanilla's classicrealmd)
            val run = lane.processes.clientRuns
                .singleOrNull { it.second.contains("UPDATE vanillarealmd.realmlist") }
                ?: error(
                    "the vanilla projection never crossed the client; result=" +
                        (resultOpt?.toString() ?: "<no call>") +
                        "; runs=" +
                        lane.processes.clientRuns.map { it.second.lineSequence().firstOrNull() ?: "" },
                )
            assertTrue(
                "the verify read must prove the vanilla lane's row: ${run.second}",
                "SELECT address,port,realmbuilds FROM vanillarealmd.realmlist WHERE id=1 LIMIT 1;" in run.second,
            )
            assertTrue(
                "the projection still runs as the least-privileged core user",
                "--user=pocket_core" in run.first,
            )
        } finally {
            ActiveFlavor.reset()
            engine.stop()
        }
    }

    @Test
    fun realmEndpointProjectionRefusesABuildListOutsideTheColumnShape() {
        val (lane, engine) = startedLane()
        listOf(
            "" to "empty",
            "5875  6005" to "double space",
            "vanilla-builds" to "non-numeric",
            "5875'; DROP TABLE realmlist; --" to "quote escape",
        ).forEach { (builds, why) ->
            try {
                engine.projectRealmEndpoint(
                    "127.0.0.1",
                    com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                    builds,
                )
                fail("a non-build-list advertisement must refuse ($why)")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must name the build-list guard: ${expected.message}",
                    (expected.message ?: "").contains("realm builds advertisement is not a build list"),
                )
            }
        }
        // Nothing crossed the client: the guard fires before any SQL.
        assertTrue(
            "no projection SQL may cross the client for a refused build list",
            lane.processes.clientRuns.none { it.second.contains("realmlist") },
        )
        engine.stop()
    }

    // ------------------------------------------------------------------
    // The SQL transcript lifecycle (no credential-bearing transcript may
    // outlive the verb that consumed it)
    // ------------------------------------------------------------------

    @Test
    fun sqlTranscriptsNeverOutliveTheVerbThatConsumedThem() {
        // The initial-auth transcript embeds the generated pocket_admin
        // (GRANT ALL) and pocket_core passwords in plaintext; the migration
        // and overlay legs already deleted their SQL files, but the
        // initial-auth copy persisted in the run dir forever (verified live
        // at %LOCALAPPDATA%/PocketRealm/database/run/initial-auth.sql).
        // Every fixed-SQL client run must now delete its transcript — the
        // whole lifecycle leaves ZERO .sql files in the run dir, and no
        // run-dir file ever carries the secrets record's material.
        val lane = initializedLane()
        val engine = lane.engine()
        val secrets = JSONObject(
            File(lane.roots.database, DesktopMariaDbEngine.SECRETS_FILE_NAME).readText(),
        )

        fun assertNoTranscripts(stage: String) {
            val transcripts = lane.roots.databaseRun.listFiles { file -> file.extension == "sql" }
                .orEmpty().toList()
            assertTrue(
                "no SQL transcript may outlive its verb ($stage): $transcripts",
                transcripts.isEmpty(),
            )
            // The security pin proper: no run-dir file (transcript or
            // control file) contains either generated secret.
            lane.roots.databaseRun.listFiles().orEmpty().forEach { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                assertTrue(
                    "the $stage stage's ${file.name} must not carry the admin credential",
                    !text.contains(secrets.getString("admin")),
                )
                assertTrue(
                    "the $stage stage's ${file.name} must not carry the core credential",
                    !text.contains(secrets.getString("core")),
                )
            }
        }

        assertNoTranscripts("initialize")
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        assertNoTranscripts("applyPinnedMigrations")
        assertTrue(engine.start().getBoolean("ok"))
        assertNoTranscripts("start")
        assertTrue(engine.queryHealth().getBoolean("ok"))
        assertNoTranscripts("queryHealth")
        assertTrue(engine.applyTimeframeOverlay("vanilla-1.5").getBoolean("ok"))
        assertNoTranscripts("applyTimeframeOverlay")
        assertTrue(
            engine.projectRealmEndpoint(
                "127.0.0.1",
                com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT,
                "5875 6005 6141",
            ).getBoolean("ok"),
        )
        assertNoTranscripts("projectRealmEndpoint")
        engine.stop()
        assertNoTranscripts("stop")
    }

    // ------------------------------------------------------------------
    // The post-spawn failure funnels (start/recover drain what they spawn)
    // ------------------------------------------------------------------

    @Test
    fun startFailureAfterTheDaemonCameUpDrainsTheDaemonItSpawned() {
        // The leaked-live-mariadbd-on-start-failure residual: queryHealth
        // throwing after startDaemon() succeeded used to leave the engine
        // RUNNING with a live daemon nobody drains (the service's guarded()
        // masks the throw; the supervisor's unwind skips a component that
        // reports FAILED with owner=null). initialize() already had the
        // funnel — start() must drain through the same discipline.
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        lane.processes.failHealth = true
        try {
            engine.start()
            fail("a post-spawn health failure must fail the start")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must be the health copy: ${expected.message}",
                (expected.message ?: "").contains("DB-SOCKET: authenticated health query failed"),
            )
        }
        assertFalse(
            "the spawned daemon must be drained, not left running",
            lane.processes.daemonRunning,
        )
        assertEquals("the engine must land FAILED, never a live RUNNING", "FAILED", engine.status().getString("state"))
        assertTrue("a drained failure must omit the daemon pid", engine.status().isNull("daemonPid"))
    }

    @Test
    fun recoverFailureAfterTheDaemonCameUpDrainsTheDaemonItSpawned() {
        // recover() had the same hole: a dirty recovery whose post-spawn
        // health query fails must drain the mariadbd it spawned over the
        // dirty generation, never strand it.
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        assertTrue(
            File(lane.roots.database, DesktopMariaDbEngine.CLEAN_MARKER_NAME).delete(),
        )
        lane.processes.failHealth = true
        try {
            engine.recover()
            fail("a post-spawn health failure must fail the recovery")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must be the health copy: ${expected.message}",
                (expected.message ?: "").contains("DB-SOCKET: authenticated health query failed"),
            )
        }
        assertFalse(
            "the spawned daemon must be drained, not left running",
            lane.processes.daemonRunning,
        )
        assertEquals("FAILED", engine.status().getString("state"))
    }

    @Test
    fun startDaemonSpawnFailureResetsStateInsteadOfStrandingStarting() {
        // processes.startDaemon throwing used to leave state==STARTING (set
        // just before the spawn) with no tracked pid — every later
        // admission reads a half-state. The honest verdict over a daemon
        // that never came up is FAILED.
        val lane = initializedLane()
        val engine = lane.engine()
        assertTrue(engine.applyPinnedMigrations().getBoolean("ok"))
        lane.processes.failSpawn = true
        try {
            engine.start()
            fail("a spawn failure must propagate")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the spawn refusal must propagate as-is: ${expected.message}",
                (expected.message ?: "").contains("scripted: daemon spawn refused"),
            )
        }
        assertEquals(
            "the engine must reset to FAILED, never strand STARTING",
            "FAILED",
            engine.status().getString("state"),
        )
        assertFalse(lane.processes.daemonRunning)
    }
}
