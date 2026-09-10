package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real DesktopRuntimeBackend contract. The database-component legs
 * are pure JVM; the realm/world legs exercise the actual in-process
 * native runtimes and require the DLL lanes (they skip cleanly without
 * them — the live 3724 listener gate is gradlew bootRealmd).
 */
class DesktopRuntimeBackendTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val realmDll = File("../native/.build-win-x86_64/pocket-runtime-build/pocket_realmd_runtime.dll")
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

    private fun materializeDatadir(roots: DesktopStorageRoots) {
        roots.sqliteDatadir.mkdirs()
        DATABASES.forEach { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).writeBytes(byteArrayOf())
        }
    }

    @Test
    fun preflightFailsHonestlyWithoutSeededDatabases() {
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("empty")))
        val result = runBlocking { backend.preflight(spec()) }
        assertFalse(result.ok)
        assertTrue("must name the missing databases: ${result.detail}", "classicrealmd" in result.detail)
        assertTrue("must name the remedy: ${result.detail}", "seedRealmData" in result.detail)
    }

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
        val backend = DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("bare")))
        try {
            runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            fail("database start must refuse without seed")
        } catch (expected: IllegalStateException) {
            assertTrue((expected.message ?: "").contains("DB-NOT-SEEDED"))
        }
        val seededRoots = DesktopStorageRoots(folder.newFolder("seeded"))
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

    @Test
    fun realmStartLaunchesAsynchronouslyAndStopsClean() {
        assumeNativesStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
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
                    "DB_REVISION" in (failure.message ?: ""),
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
        roots.sqliteDatadir.mkdirs()
        val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec("CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER);")
            db.exec("INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724);")
        }
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking { backend.projectRealmEndpoint(owner, RealmEndpoint.LOCAL) }
        assertTrue(result.detail, result.ok)
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            assertEquals("127.0.0.1", db.queryText("SELECT address FROM realmlist WHERE id = 1;"))
        }
    }

    @Test
    fun startClaimsOwnershipAndEveryObservationEchoesIt() {
        val roots = DesktopStorageRoots(folder.newFolder("seeded"))
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
}
