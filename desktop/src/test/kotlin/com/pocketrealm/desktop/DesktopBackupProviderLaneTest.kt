package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseRuntimeContract
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopMariaDbEngine
import com.pocketrealm.database.DesktopMariaDbProvider
import com.pocketrealm.fs.FileDigests
import com.pocketrealm.storage.Settings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * LV-server-db-37's provider boundary, made ACTIVE (the staged MariaDB
 * provider is the production lane): the DESK-47/106 selection decides WHICH
 * datadir a named backup covers. The MARIADB arm backs up
 * roots.mariadbDatadir in the engine's sanctioned at-rest form — the
 * PHYSICAL datadir copy the engine's own pre-migration snapshot leg takes
 * through the same shared store — gated on the engine's clean-stop proof
 * (mariadbd observably down + a valid clean-stop marker over an
 * initialized generation); the SQLITE dev arm keeps the shipped
 * seeded-datadir gate. A MARIADB backup that covered only sqlite would be a
 * silent wrong backup, so these legs pin the arm's target, its honest
 * reporting (create copy + list rows + verify verdict name the provider),
 * the per-arm refusals, and that list/verify/retainNewest work per
 * provider. The restore legs pin the explicit cross-provider boundary.
 *
 * JVM-only: the MariaDB lane rides the scripted engine seam (a synthetic
 * staged distribution + scripted processes, mirrored from
 * DesktopRuntimeBackendTest), and the SQLITE arm's seeded-file gate needs
 * no natives (plain files satisfy the isFile check the verb runs).
 */
class DesktopBackupProviderLaneTest {
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

    @get:Rule
    val folder = TemporaryFolder()

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
                .put("provider", JSONObject().put("id", DatabaseRuntimeContract.WINDOWS_PROVIDER_ID))
                .put("manifest_sha256", FileDigests.sha256(manifestFile))
                .put("bootstrap_sha256", FileDigests.sha256(bootstrap))
                .put("migration_manifest_sha256", FileDigests.sha256(migrationManifestFile))
                .put("migration_count", 0)
                .put(
                    "artifacts", JSONArray()
                        .put(artifactPin(mariadbd))
                        .put(artifactPin(mariadb)),
                )
                .toString(),
        )
        return staging
    }

    /** The scripted MariaDB process seam: the daemon is a tracked fake pid;
     * the client answers the engine's fixed queries; SHUTDOWN ends the
     * daemon (mirrored from DesktopRuntimeBackendTest). */
    private class ScriptedMariaDbProcesses : DesktopMariaDbEngine.Processes {
        var daemonRunning = false
        var daemonPid = 4242L

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
                // W4 (D72 retirement): the per-lane provisioning verb.
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
    }

    /** One MARIADB-selected backend over a synthetic staged provider and
     * the scripted process seam. */
    private class MariaDbLane(
        val roots: DesktopStorageRoots,
        val engine: DesktopMariaDbEngine,
        val backend: DesktopRuntimeBackend,
    )

    private fun selectProvider(roots: DesktopStorageRoots, provider: Settings.DatabaseProvider) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            // W4 flip: the lane selection rides every settings rewrite (the
            // engines here are constructed WITHOUT a bootLane supplier, so
            // they resolve the lane from the persisted pick).
            Settings.Snapshot(
                databaseProvider = provider,
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
    }

    private fun mariadbLane(folderName: String): MariaDbLane {
        val roots = DesktopStorageRoots(folder.newFolder(folderName)).apply { ensureDirectories() }
        selectProvider(roots, Settings.DatabaseProvider.MARIADB)
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        val engine = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = ScriptedMariaDbProcesses(),
        )
        return MariaDbLane(roots, engine, DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = engine
        })
    }

    /** An initialized, clean-stopped MariaDB lane (initialize() ends with
     * the engine's own clean-stop seal). */
    private fun initializedMariaDbLane(folderName: String): MariaDbLane {
        val lane = mariadbLane(folderName)
        val result = lane.engine.initialize()
        assertTrue("initialize must succeed: $result", result.getBoolean("ok"))
        return lane
    }

    /** A lane whose generation is startable (initialize + the pinned
     * migration seal — the engine's start admission refuses a stale ledger,
     * and these legs need a daemon that CAN run). */
    private fun startableMariaDbLane(folderName: String): MariaDbLane {
        val lane = initializedMariaDbLane(folderName)
        val migrations = lane.engine.applyPinnedMigrations()
        assertTrue("applyPinnedMigrations must succeed: $migrations", migrations.getBoolean("ok"))
        return lane
    }

    /** The SQLITE dev lane's seeded datadir: plain files satisfy the arm's
     * isFile gate (the verb checks presence, not SQLite shape — the shape
     * is the restore integrity gate's job). */
    private fun seedSqliteFiles(roots: DesktopStorageRoots) {
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
                .apply { parentFile?.mkdirs(); writeText("seed-$database") }
        }
    }

    /** The snapshot's manifest paths, for coverage assertions. */
    private fun snapshotPaths(roots: DesktopStorageRoots, snapshotId: String): List<String> {
        val manifest = JSONObject(File(File(roots.databaseSnapshots, snapshotId), "manifest.json").readText())
        val files = manifest.getJSONArray("files")
        return (0 until files.length()).map { files.getJSONObject(it).getString("path") }
    }

    // ------------------------------------------------------------------
    // The MARIADB arm: covers the MariaDB datadir, honestly
    // ------------------------------------------------------------------

    @Test
    fun mariadbBackupCoversTheMariadbDatadirAndReportsItHonestly() {
        val lane = initializedMariaDbLane("roots-mariadb-cover")
        lane.backend.use { backend ->
            // The sqlite datadir is NEVER seeded on this lane: a backup that
            // needed it (the old unconditional arm) must be impossible.
            assertTrue(
                "the sqlite datadir must be empty on this lane",
                lane.roots.sqliteDatadir.listFiles().isNullOrEmpty(),
            )

            val created = backend.createNamedBackup("alpha")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            val id = requireNotNull(created.snapshotId)

            // HONEST reporting: the copy names the provider and the datadir
            // the snapshot covered.
            assertTrue(
                "the copy must name the MARIADB lane: ${created.detail}",
                "MARIADB" in created.detail,
            )
            assertTrue(
                "the copy must name the MariaDB datadir it covered: ${created.detail}",
                lane.roots.mariadbDatadir.absolutePath in created.detail,
            )

            // COVERAGE: the snapshot holds the MariaDB datadir's proof files
            // (the engine's at-rest form — the physical copy) and none of the
            // SQLite lane's databases.
            val paths = snapshotPaths(lane.roots, id)
            assertTrue(
                "the snapshot must cover the MariaDB datadir (mysql/global_priv.MYD): $paths",
                "mysql/global_priv.MYD" in paths,
            )
            DATABASES.forEach { database ->
                assertFalse(
                    "a MARIADB backup must not carry the sqlite datadir's $database: $paths",
                    paths.any { it.startsWith("$database.") },
                )
            }

            // The list row carries the provider stamp.
            val listed = backend.listBackups().single()
            assertEquals(id, listed.snapshotId)
            assertEquals("MARIADB", listed.provider)

            // The verify verdict names the provider it verified.
            val verified = backend.verifyBackup(id)
            assertTrue("verify must pass: ${verified.detail}", verified.ok)
            assertTrue(
                "the verify verdict must name the provider: ${verified.detail}",
                "provider MARIADB" in verified.detail,
            )
        }
    }

    @Test
    fun mariadbBackupRefusesALiveDaemonThenWorksAfterTheCleanStop() {
        val lane = startableMariaDbLane("roots-mariadb-live")
        lane.backend.use { backend ->
            // The daemon starts OUTSIDE the backend's ownership (no DATABASE
            // claim), so the at-rest guard passes and the MARIADB arm's own
            // clean-stop gate is the refusal that must fire.
            assertTrue(lane.engine.start().getBoolean("ok"))
            val refused = backend.createNamedBackup("live")
            assertFalse("a live mariadbd must refuse the backup: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal must be the clean-stop gate: ${refused.detail}",
                "mariadbd is still RUNNING" in refused.detail,
            )

            // The clean-stop (engine-owned) admits the backup.
            assertTrue(lane.engine.stop().getBoolean("ok"))
            val created = backend.createNamedBackup("stopped")
            assertTrue("the clean-stopped backup must succeed: ${created.detail}", created.ok)
            assertTrue(
                "the snapshot must cover the MariaDB datadir: ${created.detail}",
                lane.roots.mariadbDatadir.absolutePath in created.detail,
            )
        }
    }

    @Test
    fun mariadbBackupRefusesADirtyGenerationWithTheRecoveryCopy() {
        val lane = startableMariaDbLane("roots-mariadb-dirty")
        lane.backend.use { backend ->
            assertTrue(lane.engine.start().getBoolean("ok"))
            lane.engine.killForTest() // dirty: clean-stop marker gone
            val refused = backend.createNamedBackup("dirty")
            assertFalse("a dirty generation must refuse the backup: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal must demand recovery first: ${refused.detail}",
                "DB-RECOVERY" in refused.detail,
            )
            assertTrue(
                "no snapshot may exist after the refusal",
                backend.listBackups().isEmpty(),
            )
        }
    }

    @Test
    fun mariadbBackupRefusesAnUninitializedGeneration() {
        val lane = mariadbLane("roots-mariadb-uninit")
        lane.backend.use { backend ->
            val refused = backend.createNamedBackup("fresh")
            assertFalse("an uninitialized datadir must refuse: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal must name the uninitialized generation: ${refused.detail}",
                "DB-NOT-INITIALIZED" in refused.detail,
            )
        }
    }

    @Test
    fun mariadbBackupRefusesAnUnstagedProviderWithTheDbLinkCopy() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-mariadb-unstaged")).apply {
            ensureDirectories()
        }
        selectProvider(roots, Settings.DatabaseProvider.MARIADB)
        val engine = DesktopMariaDbEngine(
            roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = folder.newFolder("nothing-staged"),
                repoRootOverride = folder.newFolder("repo-empty"),
            ),
            processes = ScriptedMariaDbProcesses(),
        )
        DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = engine
        }.use { backend ->
            val refused = backend.createNamedBackup("unstaged")
            assertFalse("an unstaged provider must refuse: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal must carry the DB-LINK copy: ${refused.detail}",
                "DB-LINK" in refused.detail,
            )
            assertTrue(
                "no snapshot may exist after the refusal",
                backend.listBackups().isEmpty(),
            )
        }
    }

    @Test
    fun retainNewestTrimsOlderBackupsOnTheMariadbLane() {
        val lane = initializedMariaDbLane("roots-mariadb-retain")
        lane.backend.use { backend ->
            assertTrue(backend.createNamedBackup("one").ok)
            Thread.sleep(25)
            assertTrue(backend.createNamedBackup("two").ok)
            Thread.sleep(25)
            assertTrue(backend.createNamedBackup("three").ok)
            assertEquals(3, backend.listBackups().size)

            val retained = backend.retainNewestBackups(2)
            assertTrue("retain must succeed: ${retained.detail}", retained.ok)
            val kept = backend.listBackups()
            assertEquals(2, kept.size)
            assertFalse(
                "the oldest must be retired: ${kept.map { it.snapshotId }}",
                kept.any { it.snapshotId.contains("-one-") },
            )
            assertTrue(
                "every kept backup must still name its provider",
                kept.all { it.provider == "MARIADB" },
            )
            kept.forEach { backup ->
                assertTrue(
                    "every kept backup must still verify: ${backup.snapshotId}",
                    backend.verifyBackup(backup.snapshotId).ok,
                )
            }
        }
    }

    @Test
    fun backupAndRetentionRefuseWhileARestoreVerificationIsPending() {
        // The pending-restore gate the Android engine twin enforces
        // (DatabaseEngine.kt:2543 refuses createNamedBackup while a
        // restore record exists), extended over the retention door: a
        // durable record a DEAD process left behind names the snapshot it
        // is restoring, and the engine's requireRestore re-loads that
        // snapshot on every settle — retiring it (BACKUP_RETENTION=3 is
        // user-reachable from Diagnostics, gated only on !busy) would
        // strand the record so every rollback dies and every start
        // refuses DB-RESTORE-PENDING-FAILED permanently.
        val roots = DesktopStorageRoots(folder.newFolder("roots-backup-pending")).apply {
            ensureDirectories()
        }
        selectProvider(roots, Settings.DatabaseProvider.MARIADB)
        val staging = stageMariaDbProvider(folder.newFolder("repo-root"))
        fun freshEngine() = DesktopMariaDbEngine(
            roots = roots,
            providerOverride = DesktopMariaDbProvider(
                stagedRootOverride = staging,
                repoRootOverride = staging.parentFile.parentFile.parentFile,
            ),
            processes = ScriptedMariaDbProcesses(),
            // W4 flip: the engine resolves the lane from the persisted pick.
            bootLane = { null },
        )
        // The crashed process: prepared, a named backup taken, the restore
        // transaction opened — then it dies with the token, before any
        // commit/rollback.
        val crashed = freshEngine()
        assertTrue(crashed.initialize().getBoolean("ok"))
        assertTrue(crashed.applyPinnedMigrations().getBoolean("ok"))
        val datadir = roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        val snapshot = com.pocketrealm.database.DatabaseSnapshotStore(
            roots.databaseSnapshots,
            // W4 flip: the restore compatibility gate resolves the lane from
            // the persisted pick — the test pins it through the seam.
            persistedActiveFlavorId = { "vanilla" },
        ).create(datadir, "manual-pending-${System.currentTimeMillis()}", databaseStopped = true)
        File(datadir, "realm_note.txt").writeText("moved-on")
        assertTrue(crashed.beginRestore(snapshot.id).getBoolean("ok"))

        // The post-crash backend: a FRESH engine (no token — the record is
        // the authority) sees the leftover read pending, and both backup
        // verbs must refuse naming it while the record's own snapshot
        // survives every refused retention.
        val freshEngine = freshEngine()
        DesktopRuntimeBackend(roots).apply {
            mariaDbEngineOverride = freshEngine
        }.use { backend ->
            assertTrue(
                "the dead driver's leftover must read pending",
                freshEngine.status().getBoolean("restorePending"),
            )
            val refused = backend.createNamedBackup("pending")
            assertFalse("a pending restore must refuse the backup: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal must name the pending restore: ${refused.detail}",
                "restore verification is pending" in refused.detail,
            )
            val retained = backend.retainNewestBackups(0)
            assertFalse("a pending restore must refuse the retention: ${retained.detail}", retained.ok)
            assertTrue(
                "the refusal must name the pending restore: ${retained.detail}",
                "restore verification is pending" in retained.detail,
            )
            assertTrue(
                "the pending record's own snapshot must survive the refused retention",
                backend.listBackups().any { it.snapshotId == snapshot.id },
            )
            // Convergence: once the record settles (the ownerless verb the
            // recovery lane drives), the retention gate opens again — the
            // rollback itself proves the record's snapshot was never
            // retired out from under it.
            assertTrue(freshEngine.rollbackPendingRestore().getBoolean("ok"))
            assertEquals(
                "the ORIGINAL datadir must be back",
                "moved-on",
                File(datadir, "realm_note.txt").readText(),
            )
            val after = backend.retainNewestBackups(0)
            assertTrue("the settled lane must admit the retention: ${after.detail}", after.ok)
            assertTrue("every snapshot may now be retired", backend.listBackups().isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // The SQLITE arm: the dev lane, unchanged in shape, now stamped
    // ------------------------------------------------------------------

    @Test
    fun sqliteBackupKeepsTheDevLaneArmAndStampsItSqlite() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-sqlite-dev")).apply {
            ensureDirectories()
        }
        selectProvider(roots, Settings.DatabaseProvider.SQLITE)
        seedSqliteFiles(roots)
        DesktopRuntimeBackend(roots).use { backend ->
            val created = backend.createNamedBackup("dev")
            assertTrue("the dev-lane backup must succeed: ${created.detail}", created.ok)
            val id = requireNotNull(created.snapshotId)
            assertTrue(
                "the copy must name the SQLITE lane: ${created.detail}",
                "SQLITE" in created.detail,
            )
            assertTrue(
                "the copy must name the sqlite datadir it covered: ${created.detail}",
                roots.sqliteDatadir.absolutePath in created.detail,
            )
            val paths = snapshotPaths(roots, id)
            DATABASES.forEach { database ->
                assertTrue(
                    "the SQLITE backup must cover the sqlite datadir's $database: $paths",
                    paths.any { it.startsWith("$database.") },
                )
            }

            val listed = backend.listBackups().single()
            assertEquals("SQLITE", listed.provider)
            assertTrue(backend.verifyBackup(id).ok)
        }
    }

    /** A pre-boundary snapshot (no provider stamp — everything the
     * SQLite-only lane ever wrote) must still list, and read as the SQLITE
     * lane that took it. */
    @Test
    fun legacyUnstampedSnapshotsReadAsTheSqliteLane() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-legacy-stamp")).apply {
            ensureDirectories()
        }
        selectProvider(roots, Settings.DatabaseProvider.SQLITE)
        seedSqliteFiles(roots)
        DesktopRuntimeBackend(roots).use { backend ->
            assertTrue(backend.createNamedBackup("legacy").ok)
            val id = requireNotNull(backend.listBackups().single().snapshotId)
            // Strip the provider stamp the way the pre-boundary build wrote
            // manifests, re-sealing the publication the store validates.
            val snapshotDir = File(roots.databaseSnapshots, id)
            val manifestFile = File(snapshotDir, "manifest.json")
            val flavor = JSONObject(manifestFile.readText())
                .getJSONObject("compatibility").optString("flavor")
            val legacy = JSONObject(manifestFile.readText())
                .put("compatibility", JSONObject().put("flavor", flavor))
            manifestFile.writeText(legacy.toString())
            File(snapshotDir, "manifest.sha256").writeText(FileDigests.sha256(manifestFile) + "\n")

            val listed = backend.listBackups().single()
            assertEquals("the unstamped legacy snapshot must read as SQLITE", "SQLITE", listed.provider)
        }
    }

    // ------------------------------------------------------------------
    // The restore boundary: same-lane through the engine's own
    // transaction, cross-provider explicit — never a silent swap
    // ------------------------------------------------------------------

    @Test
    fun mariadbRestoreRunsTheEngineTransactionAndRefusesAcrossProviders() {
        val lane = startableMariaDbLane("roots-restore-boundary")
        lane.backend.use { backend ->
            val datadir = lane.roots.mariadbDatadir
            File(datadir, "realm_note.txt").writeText("before")
            val created = backend.createNamedBackup("cross")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            val id = requireNotNull(created.snapshotId)
            File(datadir, "realm_note.txt").writeText("moved-on")

            // MARIADB selected + a MARIADB backup: the engine's own restore
            // transaction (durable record, quarantine, candidate swap,
            // clean-seal restamp over the drain proofs) now SERVES the arm —
            // begin swaps the snapshot's bytes in, commit retires the
            // quarantine and the record.
            val begun = backend.beginSnapshotRestore(id)
            assertEquals(
                "the snapshot's bytes must be live after BEGIN",
                "before",
                File(datadir, "realm_note.txt").readText(),
            )
            val committed = backend.commitSnapshotRestore(begun.token)
            assertTrue("commit must succeed: ${committed.detail}", committed.ok)
            assertEquals("before", File(datadir, "realm_note.txt").readText())
            assertEquals(
                "no restore trees may survive the commit",
                emptyList<File>(),
                lane.roots.database.listFiles { file ->
                    file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                        file.name.startsWith("restore-original-"))
                }.orEmpty().toList(),
            )
            assertFalse(
                "the engine's restore record must be retired",
                lane.engine.status().getBoolean("restorePending"),
            )

            // SQLITE selected + a MARIADB backup: the mismatch refuses by
            // name, and nothing was swapped anywhere.
            selectProvider(lane.roots, Settings.DatabaseProvider.SQLITE)
            val mismatch = runCatching { backend.beginSnapshotRestore(id) }.exceptionOrNull()
            assertTrue(
                "the cross-provider restore must refuse by name: $mismatch",
                mismatch is IllegalStateException &&
                    "does not match the selected provider SQLITE" in mismatch!!.message.orEmpty(),
            )
        }
    }

    /**
     * The MARIADB lane's model leg (DesktopSnapshotsRestoreTest's
     * SQLITE-lane transaction, on the engine lane): restoreBackup's
     * BEGIN→PROVE→STOP→COMMIT runs through the engine's restore verbs and
     * the completion copy still discloses the WRONG_STATE spend-lifetime
     * consequence — the disclosure stays exercisable on the MariaDB
     * default, not only the SQLITE dev lane. The proof is the injected
     * seam (the transaction discipline is the pin; the production proof
     * boots db+realm+world through the same verbs).
     */
    @Test
    fun mariadbRestoreKeepsTheSpendLifetimeDisclosureExercisable() {
        val lane = startableMariaDbLane("roots-restore-disclosure")
        val datadir = lane.roots.mariadbDatadir
        File(datadir, "realm_note.txt").writeText("before")
        DesktopAppModel(lane.roots).use { model ->
            model.backend.mariaDbEngineOverride = lane.engine
            val created = model.backend.createNamedBackup("disclosure")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            File(datadir, "realm_note.txt").writeText("moved-on")

            val operation = runBlocking {
                model.restoreBackup(requireNotNull(created.snapshotId)) { null }
            }
            assertTrue("restore must commit: ${operation.detail}", operation.ok)
            assertTrue(
                "the completion copy must keep disclosing the relaunch requirement: ${operation.detail}",
                "the next start needs an app relaunch" in operation.detail,
            )
            assertTrue(
                "the copy must keep naming the bots-disabled proof: ${operation.detail}",
                "bots disabled" in operation.detail,
            )
            assertEquals(
                "the snapshot's bytes must be live again",
                "before",
                File(datadir, "realm_note.txt").readText(),
            )
        }
    }
}
