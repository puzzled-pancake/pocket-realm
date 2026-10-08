package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-6/18/37/100: the desktop snapshot lane over a temp-root seeded
 * datadir — the SHARED DatabaseSnapshotStore through the backend's
 * create/list/verify/retainNewest verbs (all gated on the same
 * runtimes-at-rest proof as recovery), and the app model's transactional
 * restore: the candidate is hash-restored and swapped in by BEGIN, the
 * world-ready proof decides COMMIT vs ROLLBACK (the proof itself is the
 * injected seam here — the production proof boots db+realm+world; the
 * transaction discipline around it is what these legs pin). The
 * integrity-gate legs need the sqlite seam DLL and skip cleanly without it.
 */
class DesktopSnapshotsRestoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val seamDll = File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    private fun assumeSeamStaged() = requireNativeIfDemanded("sqlite seam", seamDll)

    private val owner = ComponentOwner(sessionId = "test", instanceToken = "test")

    private fun spec() = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )

    /** A real seeded datadir: every seeded database present with a sentinel
     * row (cleanly closed, so no WAL sidecars survive the seeding). Sealed
     * with this machine's resolvable seed identity (the DESK-19/53/98
     * start-admission gate the backend's start verb now runs — an unsealed
     * datadir is a legacy refuse-at-start state). The EXPLICIT SQLITE
     * selection is pinned too (the default-flip: the shipped default is
     * MariaDB; the snapshot lane this class tests is the SQLite datadir's,
     * so every fixture keeps that lane regardless of the shipped
     * default). */
    private fun seedDatadir(roots: DesktopStorageRoots, sentinel: String) {
        roots.sqliteDatadir.mkdirs()
        DATABASES.forEach { database ->
            DesktopSqliteConnection(
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).absolutePath,
            ).use { connection ->
                connection.queryText("PRAGMA journal_mode=WAL;")
                connection.exec("CREATE TABLE realm_note(value TEXT);")
                connection.exec("INSERT INTO realm_note VALUES('$sentinel');")
            }
        }
        stampSeals(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
            ).toJson(),
        )
    }

    /** Stamp generation+migration+clean markers carrying [identity]'s
     * proof (the seeder's at-seed stamping, in test form). */
    private fun stampSeals(
        roots: DesktopStorageRoots,
        identity: DesktopSqliteSeals.Identity? = DesktopSeedLocator.locate()
            ?.let {
                runCatching { DesktopSqliteSeals.identityFromProvenance(it.provenance.readText()) }
                    .getOrNull()
            },
    ) {
        // The provenance-less fallback: a self-consistent fixture identity
        // (the default-locator backend then has no current identity to
        // differ from, so the internally-consistent proof admits).
        val resolved = identity ?: DesktopSqliteSeals.identityFromProvenance(
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
        val sealText = DesktopSqliteSeals.migrationSeal(resolved, System.currentTimeMillis())
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.MIGRATION_MARKER_NAME, sealText,
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir,
            DesktopSqliteSeals.GENERATION_MARKER_NAME,
            DesktopSqliteSeals.generationMarker(
                generationUuid = java.util.UUID.randomUUID().toString(),
                identity = resolved,
                migrationSealSha256 = com.pocketrealm.fs.FileDigests.sha256(sealText),
            ),
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.CLEAN_MARKER_NAME,
            DesktopSqliteSeals.cleanSeal(System.currentTimeMillis()),
        )
    }

    private fun readSentinel(roots: DesktopStorageRoots): String =
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd").absolutePath,
        ).use { connection -> requireNotNull(connection.queryText("SELECT value FROM realm_note;")) }

    private fun writeSentinel(roots: DesktopStorageRoots, value: String) {
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd").absolutePath,
        ).use { connection -> connection.exec("UPDATE realm_note SET value = '$value';") }
    }

    private fun leftoverRestoreDirs(roots: DesktopStorageRoots): List<File> =
        roots.database.listFiles { file ->
            file.isDirectory && (file.name.startsWith("restore-candidate-") ||
                file.name.startsWith("restore-original-"))
        }.orEmpty().toList()

    /** The dev lane's roots with the SQLITE provider SELECTED: the shipped
     * default flipped to MariaDB (D19), and these legs pin the SQLite arm —
     * an explicit selection keeps them on that lane regardless of the
     * default (the backup verbs are provider-aware now: a MARIADB selection
     * over these roots would refuse as an uninitialized MariaDB datadir,
     * not silently snapshot sqlite). */
    private fun sqliteRoots(name: String): DesktopStorageRoots {
        val roots = DesktopStorageRoots(folder.newFolder(name))
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
            ).toJson(),
        )
        return roots
    }

    @Test
    fun namedBackupRoundTripPreservesTheSentinelThroughARestore() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-roundtrip")
        seedDatadir(roots, "before")
        DesktopAppModel(roots).use { model ->
            val created = model.backend.createNamedBackup("alpha")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            val id = requireNotNull(created.snapshotId)
            assertTrue("the id must be a named backup: $id", id.startsWith("manual-alpha-"))

            val listed = model.listBackups().single()
            assertEquals(id, listed.snapshotId)
            assertEquals(created.digest, listed.digest)
            assertTrue("verify must pass: ${model.backend.verifyBackup(id).detail}",
                model.backend.verifyBackup(id).ok)

            // The live datadir moves on after the backup...
            writeSentinel(roots, "after")
            assertEquals("after", readSentinel(roots))

            // ...and the transactional restore (world-ready proof passed)
            // brings the backup's bytes back and commits.
            val operation = runBlocking { model.restoreBackup(id) { null } }
            assertTrue("restore must commit: ${operation.detail}", operation.ok)
            assertEquals("the backup's bytes must be live again", "before", readSentinel(roots))
            assertEquals(
                "commit must retire the quarantine copy",
                emptyList<File>(),
                leftoverRestoreDirs(roots),
            )
            assertTrue("the backup must survive its own restore", model.listBackups().isNotEmpty())
        }
    }

    @Test
    fun restoreRollsBackWhenTheWorldReadyProofFails() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-rollback")
        seedDatadir(roots, "before")
        DesktopAppModel(roots).use { model ->
            val created = model.backend.createNamedBackup("beta")
            assertTrue(created.ok)
            val id = requireNotNull(created.snapshotId)
            writeSentinel(roots, "after")

            val operation = runBlocking { model.restoreBackup(id) { "injected boot failure" } }
            assertFalse("a failed proof must fail the operation: ${operation.detail}", operation.ok)
            assertTrue(
                "the verdict must be the rollback: ${operation.detail}",
                "RESTORE-ROLLED-BACK" in operation.detail,
            )
            assertEquals("the ORIGINAL datadir must be back", "after", readSentinel(roots))
            assertEquals(
                "the transaction must leave no candidate/quarantine trees",
                emptyList<File>(),
                leftoverRestoreDirs(roots),
            )
            assertTrue("the backup must survive the rollback", model.listBackups().isNotEmpty())
        }
    }

    @Test
    fun restoreRefusesAnUnknownOrCorruptSnapshotWithoutTouchingTheDatadir() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-unknown")
        seedDatadir(roots, "held")
        DesktopAppModel(roots).use { model ->
            val operation = runBlocking { model.restoreBackup("manual-nonexistent-1") { null } }
            assertFalse(operation.ok)
            assertTrue(
                "the refusal must come from BEGIN: ${operation.detail}",
                "RESTORE-REFUSED" in operation.detail,
            )
            assertEquals("held", readSentinel(roots))
        }
    }

    @Test
    fun backupAndRestoreRefuseWhileADatabaseClaimIsLive() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-live")
        seedDatadir(roots, "held")
        val backend = DesktopRuntimeBackend(roots)
        try {
            val started = runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            assertEquals(ComponentLifecycle.READY, started.state)

            val created = backend.createNamedBackup("held")
            assertFalse("a live claim must refuse the backup: ${created.detail}", created.ok)
            assertTrue(
                "the refusal must be the at-rest guard: ${created.detail}",
                "database is still claimed by a runtime session" in created.detail,
            )
            val refused = runCatching { backend.beginSnapshotRestore("manual-x") }.exceptionOrNull()
            assertTrue(
                "begin must hit the same guard: $refused",
                refused is IllegalStateException &&
                    "database is still claimed by a runtime session" in refused!!.message.orEmpty(),
            )

            // Release the claim and both verbs work again.
            val stopped = runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
            assertTrue("the stop must release the claim: ${stopped.detail}", stopped.ok)
            val released = backend.createNamedBackup("released")
            assertTrue("the at-rest backup must succeed: ${released.detail}", released.ok)
        } finally {
            backend.close()
        }
    }

    @Test
    fun invalidBackupNamesAreRefusedBeforeTheStore() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-name")
        seedDatadir(roots, "named")
        DesktopAppModel(roots).use { model ->
            val created = model.backend.createNamedBackup("bad/name")
            assertFalse(created.ok)
            assertTrue(
                "the name rule must refuse: ${created.detail}",
                "DB-SNAPSHOT: invalid backup name" in created.detail,
            )
        }
    }

    @Test
    fun retainNewestTrimsOlderBackups() {
        assumeSeamStaged()
        val roots = sqliteRoots("roots-retain")
        seedDatadir(roots, "keep")
        DesktopAppModel(roots).use { model ->
            assertTrue(model.backend.createNamedBackup("one").ok)
            Thread.sleep(25)
            assertTrue(model.backend.createNamedBackup("two").ok)
            Thread.sleep(25)
            assertTrue(model.backend.createNamedBackup("three").ok)
            assertEquals(3, model.listBackups().size)

            val result = model.backend.retainNewestBackups(2)
            assertTrue("retain must succeed: ${result.detail}", result.ok)
            val kept = model.listBackups()
            assertEquals(2, kept.size)
            assertFalse(
                "the oldest must be retired: ${kept.map { it.snapshotId }}",
                kept.any { it.snapshotId.contains("-one-") },
            )
        }
    }
}
