package com.pocketrealm.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class O09RealmRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val timeline = JSONArray()

    @Test fun nativeRealmWorldLifecycleAndRecovery() {
        val database = bind(DatabaseService::class.java) { IDatabaseControl.Stub.asInterface(it) }
        val initialDb = JSONObject(database.api.status())
        if (initialDb.getString("state") == "RUNNING") assertOk(database.api.stop())
        if (!JSONObject(database.api.status()).getBoolean("cleanMarker")) assertOk(database.api.recover())
        assertOk(database.api.applyPinnedMigrations())
        assertOk(database.api.start())
        assertOk(database.api.queryHealth())

        val realm = bind(RealmRuntimeService::class.java) { IRealmControl.Stub.asInterface(it) }
        repeat(20) { cycle ->
            assertOk(realm.api.start())
            val ready = waitStatus(60_000) { JSONObject(realm.api.status()) }
            assertEquals("READY", ready.getString("state"))
            assertLoopbackReachable(ServerRuntimeContract.REALM_PORT)
            timeline.put(JSONObject().put("event", "realm-ready").put("cycle", cycle + 1)
                .put("pid", ready.getInt("pid")).put("heartbeat", ready.getLong("heartbeatMs")))
            assertOk(realm.api.stop())
            assertEquals("STOPPED", JSONObject(realm.api.status()).getString("state"))
        }

        assertOk(realm.api.start())
        waitStatus(60_000) { JSONObject(realm.api.status()) }
        var world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
        assertOk(world.api.start())
        val worldReady = waitStatus(180_000) { JSONObject(world.api.status()) }
        assertEquals("READY", worldReady.getString("state"))
        assertTrue(worldReady.getBoolean("compiledPlayerbots"))
        assertTrue(!worldReady.getBoolean("playerbotsEnabled"))
        assertLoopbackReachable(ServerRuntimeContract.WORLD_PORT)
        val invalidAccounts = listOf(
            "" to "SafePass9", "HAS SPACE" to "SafePass9", "QUOTE'" to "SafePass9",
            "SEMI;" to "SafePass9", "LINE\nBREAK" to "SafePass9",
            "ABCDEFGHIJKLMNOPQ" to "SafePass9", "UNICODEÉ" to "SafePass9",
            "VALID9" to "", "VALID9" to "BAD PASS", "VALID9" to "BAD;PASS",
            "VALID9" to "ABCDEFGHIJKLMNOPQ",
        )
        invalidAccounts.forEach { (user, password) ->
            val rejected = JSONObject(world.api.createAccount(user, password))
            assertTrue("unsafe account token was accepted: $user", !rejected.optBoolean("ok", true))
        }
        assertEquals("READY", JSONObject(world.api.status()).getString("state"))
        val username = "O09A" + (System.currentTimeMillis() % 10_000_000).toString().padStart(7, '0')
        assertOk(world.api.createAccount(username, "SafePass9"))
        assertOk(world.api.save())
        assertOk(world.api.stop())
        assertOk(realm.api.stop())
        timeline.put(JSONObject().put("event", "world-clean-stop").put("account", username)
            .put("ticks", worldReady.getLong("tickCount")))
        world.close(); Thread.sleep(750)
        world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }

        // Kill only :world. Binder death is expected; :database and :realm
        // remain separate. Rebinding must yield a clean native STOPPED state,
        // while the app-private lifecycle record remains dirty/classified.
        assertOk(realm.api.start()); waitStatus(60_000) { JSONObject(realm.api.status()) }
        assertOk(world.api.start()); waitStatus(180_000) { JSONObject(world.api.status()) }
        runCatching { world.api.killForTest() }
        world.close(); Thread.sleep(1_000)
        val worldAfterKill = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
        assertEquals("STOPPED", JSONObject(worldAfterKill.api.status()).getString("state"))
        assertTrue(lifecycleRecord().let { !it.getBoolean("clean") && it.getString("operation") == "kill-for-test" })
        assertOk(worldAfterKill.api.start()); waitStatus(180_000) { JSONObject(worldAfterKill.api.status()) }
        assertOk(worldAfterKill.api.stop()); assertOk(realm.api.stop())
        timeline.put(JSONObject().put("event", "world-kill-recovered"))
        worldAfterKill.close(); Thread.sleep(750)
        val worldForDbKill = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }

        // Kill MariaDB while both listeners are active, then explicitly stop
        // dependants and invoke O08's dirty recovery. It must never be reported
        // as a clean database stop.
        assertOk(realm.api.start()); waitStatus(60_000) { JSONObject(realm.api.status()) }
        assertOk(worldForDbKill.api.start()); waitStatus(180_000) { JSONObject(worldForDbKill.api.status()) }
        val killedDb = JSONObject(database.api.killForTest())
        assertTrue(killedDb.getBoolean("ok")); assertTrue(!killedDb.getBoolean("cleanMarker"))
        // The database disappeared beneath the world, so this cannot be a
        // clean world shutdown. Retire that fault domain explicitly and let
        // the next bind create a fresh native process generation.
        runCatching { worldForDbKill.api.killForTest() }
        worldForDbKill.close(); Thread.sleep(1_000)
        runCatching { realm.api.stop() }
        assertTrue(lifecycleRecord("world").let {
            !it.getBoolean("clean") && it.getString("operation") == "kill-for-test"
        })
        assertTrue(lifecycleRecord("realm").getBoolean("clean"))
        val recovered = JSONObject(database.api.recover())
        assertTrue(recovered.getBoolean("ok") && recovered.getBoolean("recovered"))
        timeline.put(JSONObject().put("event", "database-kill-recovered")
            .put("recoveryOutputObserved", recovered.optBoolean("recoveryOutputObserved")))

        // Dependency-order proof after recovery: DB -> realmd -> world, then
        // exact reverse stop order.
        assertOk(database.api.start()); assertOk(database.api.queryHealth())
        assertOk(realm.api.start()); waitStatus(60_000) { JSONObject(realm.api.status()) }
        val finalWorld = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
        assertOk(finalWorld.api.start()); waitStatus(180_000) { JSONObject(finalWorld.api.status()) }
        assertOk(finalWorld.api.save()); assertOk(finalWorld.api.stop())
        assertOk(realm.api.stop()); assertOk(database.api.stop())
        timeline.put(JSONObject().put("event", "final-clean-dependency-cycle"))

        val evidence = JSONObject().put("schema", 1).put("feature", "O09")
            .put("ok", true).put("serial", android.os.Build.SERIAL)
            .put("api", android.os.Build.VERSION.SDK_INT).put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("pageSize", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("realmCleanCycles", 20).put("playerbots", false)
            .put("databaseTcp", false).put("controlFuzzCases", invalidAccounts.size)
            .put("loopbackSocketProbes", true)
            .put("timeline", timeline)
        val output = File(context.getExternalFilesDir(null), "evidence/O09_REALM_RUNTIME.json")
        output.parentFile!!.mkdirs(); output.writeText(evidence.toString(2))
        finalWorld.close(); realm.close(); database.close()
    }

    private fun assertLoopbackReachable(port: Int) {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
    }

    private fun lifecycleRecord(component: String = "world"): JSONObject = JSONObject(
        File(context.noBackupFilesDir, "server/lifecycle/$component.json").readText()
    )

    private fun assertOk(raw: String): JSONObject = JSONObject(raw).also {
        assertTrue("control failure: $it", it.getBoolean("ok"))
    }

    private fun waitStatus(timeoutMs: Long, read: () -> JSONObject): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = read()
        while (System.nanoTime() < deadline) {
            when (latest.getString("state")) {
                "READY" -> return latest
                "FAILED" -> throw AssertionError("native component failed: $latest")
            }
            Thread.sleep(100); latest = read()
        }
        throw AssertionError("component readiness timed out: $latest")
    }

    private fun <T> bind(type: Class<*>, convert: (IBinder) -> T): Bound<T> {
        val connected = CountDownLatch(1)
        var api: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                api = convert(service); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(10, TimeUnit.SECONDS))
        return Bound(context, connection, checkNotNull(api))
    }

    private data class Bound<T>(val context: Context, val connection: ServiceConnection, val api: T) {
        fun close() = runCatching { context.unbindService(connection) }.let { Unit }
    }
}
