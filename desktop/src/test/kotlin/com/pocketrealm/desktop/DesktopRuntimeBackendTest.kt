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
        assumeTrue("realm runtime not built (run tools/build_win_realm_runtime.py): $realmDll", realmDll.isFile)
        assumeTrue("sqlite seam not built (run tools/build_win_sqlite_seam.py): $seamDll", seamDll.isFile)
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
        val seeded = runBlocking { DesktopRuntimeBackend(seededRoots).observe(RuntimeComponent.DATABASE) }
        assertEquals(ComponentLifecycle.READY, seeded.state)
        assertTrue(seeded.ready)

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
            // rc=0 means the runtime ACCEPTED the launch; database-level
            // failures surface asynchronously through the state machine
            // (the same model the Android services expose).
            val observation = runBlocking { backend.start(RuntimeComponent.REALM, owner, spec()) }
            assertTrue(
                "launch must be accepted: ${observation.state}",
                observation.state == ComponentLifecycle.STARTING ||
                    observation.state == ComponentLifecycle.READY ||
                    observation.state == ComponentLifecycle.FAILED,
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
        assumeTrue("sqlite seam not built: $seamDll", seamDll.isFile)
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
}
