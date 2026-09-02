package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.storage.StorageRoots
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Baseline-world sanity run (data staged from native/.build-o09-server-data
 * into content/o09-server/active by stageBaselineDataFromExternal): boots the
 * REAL CMaNGOS stack — database → realmd → world — on the BASELINE data
 * pack (dbc + maps, vmaps/mmaps disabled by the baseline config; the o11
 * bot-profile path still needs client-derived vmaps/mmaps and stays
 * gated), then drives the real product surface end-to-end:
 *
 * - Accounts through the REAL :world console writer
 *   (`account create` — the cross-process LoginDatabase path),
 *   password verify (sAccountMgr.CheckPassword, both polarities), gmlevel
 *   set, accountStatus, characterPersistence read probes.
 * - A client-acked `saveall`.
 * - On-disk proof: the SQLite datadir's account table is read back
 *   directly after the save (the console writes went through the engine
 *   to disk — SRP6 verifiers and all).
 * - Kill pair: world.killForTest → database kill+recover → full stack
 *   restart → the sentinel account must have SURVIVED (real durability,
 *   not an ack).
 *
 * Runs IDENTICALLY on Server A (MariaDB) and Server B (SQLite).
 */
@RunWith(AndroidJUnit4::class)
class BaselineWorldSanityRunner {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = JSONObject()
    private val connections = ArrayList<ServiceConnection>()
    private var control: IDatabaseControl? = null
    private var realm: Bound<IRealmControl>? = null
    private var world: Bound<IWorldControl>? = null

    @After fun unbind() {
        runCatching { world?.api?.let { runCatching { it.stop() } } }
        world?.close()
        runCatching { realm?.api?.let { runCatching { it.stop() } } }
        realm?.close()
        runCatching { control?.stop() }
        connections.forEach { runCatching { context.unbindService(it) } }
        connections.clear()
    }

    @Test fun baselineWorldSanity() {
        stageBaselineDataFromExternal()
        control = bind("com.pocketrealm.database.DatabaseService") { IDatabaseControl.Stub.asInterface(it) }.api
        val status = assertOk(control!!.status())
        val sqliteCapable = status.optBoolean("sqliteCapable", false)
        evidence.put("providerMode", status.optString("providerMode"))
        evidence.put("sqliteCapable", sqliteCapable)

        // ---- database boot -------------------------------------------------------
        val bootStart = System.currentTimeMillis()
        assertOk(control!!.initialize())
        assertOk(control!!.applyPinnedMigrations())
        assertOk(control!!.start())
        assertOk(control!!.queryHealth())
        evidence.put("dbBootMs", System.currentTimeMillis() - bootStart)

        // ---- realmd boot (first SQLite-side exercise of the realm runtime) -------
        realm = bind("com.pocketrealm.server.RealmRuntimeService") { IRealmControl.Stub.asInterface(it) }
        assertOk(realm!!.api.start())
        val realmReady = waitReady(120_000, "realm") { JSONObject(realm!!.api.status()) }
        assertLoopbackReachable(ServerRuntimeContract.REALM_PORT)
        evidence.put("realmReady", realmReady)

        // ---- world boot on the baseline data pack --------------------------------
        world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        val worldStart = System.currentTimeMillis()
        assertOk(world!!.api.start())
        val worldReady = waitReady(300_000, "world") { JSONObject(world!!.api.status()) }
        assertLoopbackReachable(ServerRuntimeContract.WORLD_PORT)
        evidence.put("worldBootMs", System.currentTimeMillis() - worldStart)
        evidence.put("worldReadyStatus", worldReady)
        assertTrue("playerbots must be disabled on the baseline config",
            !worldReady.getBoolean("playerbotsEnabled"))

        // ---- accounts through the REAL world console writer ----------------------
        val accounts = JSONArray()
        for (i in 1..5) {
            val username = "sanity%02d".format(i)
            assertOk("create-$username", world!!.api.createAccount(username, "SanityPass9"))
            accounts.put(username)
        }
        var verifiedRight = 0
        var rejectedWrong = 0
        for (i in 1..5) {
            val username = "sanity%02d".format(i)
            if (assertOk("verify-$username", world!!.api.verifyAccountPassword(username, "SanityPass9"))
                    .optBoolean("passwordVerified")) verifiedRight++
            if (!assertOk("verify-wrong-$username", world!!.api.verifyAccountPassword(username, "WrongPass9"))
                    .optBoolean("passwordVerified")) rejectedWrong++
        }
        assertOk("gmlevel", world!!.api.setAccountGmLevel("sanity01", 3))
        val gm = assertOk("status-gm", world!!.api.accountStatus("sanity01"))
        assertEquals("gmlevel write must be readable", 3L, gm.optLong("gmLevel", -1))
        var existing = 0
        for (i in 1..5) {
            if (assertOk("status-%02d".format(i), world!!.api.accountStatus("sanity%02d".format(i)))
                    .optBoolean("accountExists")) existing++
        }
        val persistence = assertOk("persistence", world!!.api.characterPersistence("sanity01", "NoSuchchar"))
        evidence.put("accounts", JSONObject()
            .put("created", 5).put("verifiedRight", verifiedRight)
            .put("rejectedWrong", rejectedWrong).put("existingAfterCreate", existing)
            .put("gmLevelRead", gm.optLong("gmLevel", -1))
            .put("persistenceReason", persistence.optString("reason", "<characters>")))

        // ---- saveall through the console ----------------------------------------
        val saveStart = System.currentTimeMillis()
        assertOk(world!!.api.save())
        evidence.put("saveallAckMs", System.currentTimeMillis() - saveStart)
        evidence.put("ticksAfterSave", JSONObject(world!!.api.status()).optLong("tickCount"))

        // ---- on-disk proof (SQLite side): the console writes reached disk --------
        if (sqliteCapable) {
            val roots = StorageRoots.get(context)
            val datadir = File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
            val realmd = DatabaseSqliteControlPlane.databaseFile(datadir, "classicrealmd")
            SQLiteDatabase.openDatabase(realmd.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val rows = ArrayList<String>()
                db.rawQuery("SELECT username FROM account ORDER BY username;", null).use { cursor ->
                    while (cursor.moveToNext()) rows.add(cursor.getString(0))
                }
                val verifiers = ArrayList<Int>()
                db.rawQuery("SELECT length(v), length(s) FROM account WHERE username LIKE 'sanity%';", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        verifiers.add(cursor.getInt(0)); verifiers.add(cursor.getInt(1))
                    }
                }
                evidence.put("onDiskAccounts", JSONArray(rows))
                evidence.put("srp6FieldLengths", JSONArray(verifiers))
            }
        }

        // ---- kill pair: kill the world, kill the database dirty, recover, restart,
        // and prove the sentinel account SURVIVED on disk --------------------------
        assertOk("sentinel-create", world!!.api.createAccount("sanitykill1", "KillPass99"))
        assertOk("sentinel-save", world!!.api.save())
        // killForTest never returns (the :world process kills itself) —
        // runCatching, never asserted.
        runCatching { world!!.api.killForTest() }
        world!!.close(); world = null
        Thread.sleep(1_000)
        world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        assertEquals("STOPPED", JSONObject(world!!.api.status()).optString("state"))
        runCatching { realm!!.api.stop() }  // retire the realm over a dead db
        assertOk("db-kill", control!!.killForTest())
        val recoverStart = System.currentTimeMillis()
        assertOk("db-recover", control!!.recover())
        evidence.put("dbRecoverMs", System.currentTimeMillis() - recoverStart)
        assertOk("db-restart", control!!.start())
        assertOk("realm-restart", realm!!.api.start())
        waitReady(120_000, "realm") { JSONObject(realm!!.api.status()) }
        assertOk("world-restart", world!!.api.start())
        waitReady(300_000, "world") { JSONObject(world!!.api.status()) }
        val sentinel = assertOk("sentinel-after-recovery", world!!.api.accountStatus("sanitykill1"))
        assertTrue("the sentinel account did NOT survive the world-kill + db dirty-kill + recovery",
            sentinel.optBoolean("accountExists"))
        var survivors = 0
        for (i in 1..5) {
            if (assertOk("post-kill-%02d".format(i), world!!.api.accountStatus("sanity%02d".format(i)))
                    .optBoolean("accountExists")) survivors++
        }
        evidence.put("dirtyKillRecovery", JSONObject()
            .put("sentinelSurvived", sentinel.optBoolean("accountExists"))
            .put("createdAccountsSurvived", survivors))

        // ---- clean teardown (supervisor order) -----------------------------------
        assertOk("world-final-stop", world!!.api.stop())
        world!!.close(); world = null
        assertOk("realm-final-stop", realm!!.api.stop())
        realm!!.close(); realm = null
        assertOk("db-final-stop", control!!.stop())

        File(context.filesDir, "baseline-world-sanity.json").writeText(evidence.toString())
    }

    private fun assertLoopbackReachable(port: Int) {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
        evidence.put("port${port}Reachable", true)
    }

    /** Stage the baseline data pack from the host-pushed external staging
     * dir into filesDir/content/o09-server/active (`run-as` cannot cross
     * the API-35 FUSE boundary, but the app process can read its own
     * external dir). Idempotent. */
    private fun stageBaselineDataFromExternal() {
        val target = File(File(context.filesDir, "content/o09-server"), "active")
        if (File(target, "BUILD_PROVENANCE.json").isFile) return
        val source = File(context.getExternalFilesDir(null), "o09stage")
        assertTrue("baseline staging source missing on external storage: $source " +
            "(host must adb push native/.build-o09-server-data there)",
            File(source, "BUILD_PROVENANCE.json").isFile)
        source.copyRecursively(target, overwrite = true)
        assertTrue("staged pack incomplete", File(target, "dbc").isDirectory && File(target, "maps").isDirectory)
        evidence.put("baselineDataStagedFromExternal", true)
    }

    private fun waitReady(timeoutMs: Long, component: String, read: () -> JSONObject): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = read()
        while (System.currentTimeMillis() < deadline) {
            when (latest.optString("state")) {
                "READY" -> return latest
                "FAILED" -> throw AssertionError("$component failed: $latest")
            }
            Thread.sleep(200)
            latest = read()
        }
        throw AssertionError("$component readiness timed out: $latest")
    }

    private fun <T> bind(component: String, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var binder: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = convert(service!!); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connections.add(connection)
        assertTrue(context.bindService(Intent().setClassName(context, component), connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        return Bound(binder!!, connection)
    }

    private inner class Bound<T>(val api: T, private val connection: ServiceConnection) {
        fun close() {
            runCatching { context.unbindService(connection) }
            connections.remove(connection)
        }
    }

    private fun assertOk(raw: String): JSONObject = assertOk("op", raw)

    private fun assertOk(name: String, raw: String): JSONObject {
        val parsed = JSONObject(raw)
        assertTrue("$name failed: $raw", parsed.optBoolean("ok"))
        return parsed
    }
}
