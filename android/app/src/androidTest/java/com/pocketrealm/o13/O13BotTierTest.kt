package com.pocketrealm.o13

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.RealmRuntimeService
import com.pocketrealm.server.WorldRuntimeService
import com.pocketrealm.supervisor.AndroidRuntimeBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bot generation/ramp/soak acceptance on a named Android lane. */
@RunWith(AndroidJUnit4::class)
class O13BotTierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun boundedGenerationRampAndMeasuredSoak() {
        val arguments = InstrumentationRegistry.getArguments()
        val soakSeconds = arguments.getString("o13SoakSeconds")?.toIntOrNull() ?: 120
        val interruptGeneration = arguments.getString("o13InterruptGeneration")
            ?.toBooleanStrictOrNull() ?: false
        val restoreBaseline = arguments.getString("o13RestoreBaseline")
            ?.toBooleanStrictOrNull() ?: false
        require(soakSeconds in 60..7_200) { "o13SoakSeconds must be in 60..7200" }
        val formalSoak = soakSeconds == 7_200
        val timeline = JSONArray()

        val database = bind(DatabaseService::class.java) { IDatabaseControl.Stub.asInterface(it) }
        var realm: Bound<IRealmControl>? = null
        var world: Bound<IWorldControl>? = null
        var evidenceOutput: Pair<File, JSONObject>? = null
        try {
            val initial = JSONObject(database.api.status())
            if (initial.getString("state") == "RUNNING") assertOk(database.api.stop())
            assertOk(database.api.rollbackPendingRestore())
            var stopped = JSONObject(database.api.status())
            if (!stopped.getBoolean("cleanMarker")) assertOk(database.api.recover())
            stopped = JSONObject(database.api.status())
            assertTrue("bot tiers require the migrated database baseline", stopped.getBoolean("initialized"))
            if (restoreBaseline) {
                val snapshotId = restoreNewestO12Baseline(database.api)
                timeline.put(JSONObject().put("event", "baseline-restored")
                    .put("snapshotId", snapshotId))
            }
            assertOk(database.api.start())
            assertOk(database.api.queryHealth())

            realm = bind(RealmRuntimeService::class.java) { IRealmControl.Stub.asInterface(it) }
            assertOk(realm.api.start())
            waitReady(120_000) { JSONObject(requireNotNull(realm).api.status()) }
            world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }

            val worldLog = File(context.noBackupFilesDir, "server/logs/world.log")
            val logOffset = if (worldLog.isFile) worldLog.length() else 0L
            assertOk(world.api.startBotProfile(AndroidRuntimeBackend.BOT_LOW_25_PROFILE))
            if (interruptGeneration) {
                val observed = waitForGenerationLog(worldLog, logOffset, 420_000)
                assertTrue("interruption must follow a durable generation checkpoint: $observed",
                    observed.startsWith("POCKET_BOT_GENERATION_CHECKPOINT"))
                timeline.put(JSONObject().put("event", "generation-interrupt").put("marker", observed))
                runCatching { world.api.killForTest() }
                world.close(); world = null
                Thread.sleep(1_000)
                world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
                assertEquals("STOPPED", JSONObject(world.api.status()).getString("state"))
                assertOk(world.api.startBotProfile(AndroidRuntimeBackend.BOT_LOW_25_PROFILE))
            }

            val ready = waitReady(600_000) { JSONObject(requireNotNull(world).api.status()) }
            assertTrue(ready.toString(), ready.getBoolean("compiledPlayerbots"))
            assertTrue(ready.toString(), ready.getBoolean("playerbotsEnabled"))
            assertFalse(ready.toString(), ready.getBoolean("auctionHouseBot"))
            assertEquals("complete", ready.getString("botGenerationState"))
            assertEquals(3L, ready.getLong("botAccountCount"))
            assertTrue(ready.toString(), ready.getLong("botsAvailable") in 25..27)
            timeline.put(JSONObject().put("event", "generation-complete")
                .put("accounts", ready.getLong("botAccountCount"))
                .put("available", ready.getLong("botsAvailable")))

            val ramped = waitForBots(requireNotNull(world).api, if (formalSoak) 25 else 20, 900_000)
            timeline.put(JSONObject().put("event", "ramp-complete")
                .put("online", ramped.getLong("botsOnline"))
                .put("effectiveTarget", ramped.getLong("effectiveBotTarget")))
            if (formalSoak) {
                val steady = waitForSteadyP99(requireNotNull(world).api, 250, 6, 600_000)
                timeline.put(JSONObject().put("event", "steady-state")
                    .put("online", steady.getLong("botsOnline"))
                    .put("p99Ms", steady.getLong("worldTickP99Ms")))
            }

            val samples = JSONArray()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(soakSeconds.toLong())
            var p99Violations = 0
            var hardStallIntervals = 0
            var overshootSamples = 0
            while (System.nanoTime() < deadline) {
                val status = JSONObject(requireNotNull(world).api.status())
                Log.i(TAG, "soak status=$status")
                assertEquals("READY", status.getString("state"))
                assertTrue(status.toString(), status.getBoolean("playerbotsEnabled"))
                assertFalse(status.toString(), status.getBoolean("auctionHouseBot"))
                assertTrue(status.toString(), status.getLong("botsOnline") >= 20)
                assertTrue(status.toString(), status.getLong("effectiveBotTarget") in 20..25)
                val selectedTarget = status.getLong("selectedBotTarget")
                assertTrue("bot overshoot must stay within the documented one-bot grace",
                    status.getLong("botsOnline") <= selectedTarget + 1)
                if (status.getLong("botsOnline") > selectedTarget) overshootSamples++
                assertTrue(status.toString(), status.getLong("freeMemoryMiB") >= 768)
                assertTrue(status.toString(), status.getLong("freeStorageMiB") >= 2_048)
                if (status.getLong("worldTickP99Ms") > 250) p99Violations++
                if (status.getLong("worldHardStalls") >= 2) hardStallIntervals++
                samples.put(JSONObject()
                    .put("elapsedSeconds", soakSeconds - TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()))
                    .put("botsOnline", status.getLong("botsOnline"))
                    .put("effectiveTarget", status.getLong("effectiveBotTarget"))
                    .put("adapted", status.optBoolean("botTargetAdapted"))
                    .put("admissionReason", status.optString("botAdmissionReason"))
                    .put("p50Ms", status.getLong("worldTickP50Ms"))
                    .put("p95Ms", status.getLong("worldTickP95Ms"))
                    .put("p99Ms", status.getLong("worldTickP99Ms"))
                    .put("maxMs", status.getLong("worldTickWindowMaxMs"))
                    .put("hardStalls", status.getLong("worldHardStalls"))
                    .put("worldPssMiB", status.getLong("worldPssMiB"))
                    .put("freeMemoryMiB", status.getLong("freeMemoryMiB"))
                    .put("freeStorageMiB", status.getLong("freeStorageMiB"))
                    .put("thermal", status.getString("thermalLevel")))
                Thread.sleep(10_000)
            }
            if (formalSoak) {
                assertEquals("SOAK-25 must not need target reduction", 0,
                    (0 until samples.length()).count { samples.getJSONObject(it).getBoolean("adapted") })
                assertEquals("world p99 budget violations", 0, p99Violations)
                assertEquals("repeated hard-stall intervals", 0, hardStallIntervals)
            }

            assertOk(requireNotNull(world).api.save())
            val finalStatus = JSONObject(requireNotNull(world).api.status())
            val evidence = JSONObject().put("schema", 1).put("feature", "O13")
                .put("ok", true).put("formalSoak25", formalSoak)
                .put("serial", android.os.Build.SERIAL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.first())
                .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("profileId", AndroidRuntimeBackend.BOT_LOW_25_PROFILE)
                .put("interruptedGeneration", interruptGeneration)
                .put("restoredBaseline", restoreBaseline)
                .put("soakSeconds", soakSeconds)
                .put("p99Violations", p99Violations)
                .put("hardStallIntervals", hardStallIntervals)
                .put("overshootSamples", overshootSamples)
                .put("shutdownVerified", true)
                .put("finalStatus", finalStatus).put("timeline", timeline).put("samples", samples)
            val output = File(context.getExternalFilesDir(null),
                "evidence/O13_BOT_TIER_${if (formalSoak) "SOAK25" else "SMOKE"}.json")
            evidenceOutput = output to evidence
        } finally {
            var cleanupFailure: Throwable? = null
            fun attempt(name: String, action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    cleanupFailure?.addSuppressed(failure)
                        ?: run { cleanupFailure = AssertionError("bot tier $name cleanup failed", failure) }
                }
            }
            attempt("world") {
                world?.let { bound ->
                    try { assertOk(bound.api.stop()) } finally { bound.close(); world = null }
                }
            }
            attempt("realm") {
                realm?.let { bound ->
                    try { assertOk(bound.api.stop()) } finally { bound.close(); realm = null }
                }
            }
            attempt("database") { assertOk(database.api.stop()) }
            database.close()
            if (cleanupFailure != null) throw cleanupFailure as Throwable
        }
        evidenceOutput?.let { (output, evidence) ->
            output.parentFile!!.mkdirs()
            output.writeText(evidence.toString(2))
        }
    }

    private fun waitForGenerationLog(file: File, offset: Long, timeoutMs: Long): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (file.isFile && file.length() > 0L) {
                // CMaNGOS opens world.log with truncation on every world start.
                // Treat a shorter file as a new generation instead of seeking
                // beyond EOF and silently missing the generation marker.
                val effectiveOffset = if (file.length() < offset) 0L else offset
                if (file.length() <= effectiveOffset) {
                    Thread.sleep(50)
                    continue
                }
                val appended = file.inputStream().use { input ->
                    input.skip(effectiveOffset)
                    input.bufferedReader().readText()
                }
                Regex("POCKET_BOT_GENERATION_CHECKPOINT created=\\d+").find(appended)
                    ?.value?.let { return it }
            }
            Thread.sleep(50)
        }
        throw AssertionError("bot generation marker was not observed")
    }

    private fun waitForBots(api: IWorldControl, target: Int, timeoutMs: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.status())
        var nextLog = System.nanoTime()
        while (System.nanoTime() < deadline) {
            if (latest.optLong("botsOnline") >= target) return latest
            if (latest.getString("state") == "FAILED") throw AssertionError("world failed: $latest")
            if (System.nanoTime() >= nextLog) {
                Log.i(TAG, "ramp target=$target status=$latest")
                nextLog = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            }
            Thread.sleep(1_000); latest = JSONObject(api.status())
        }
        throw AssertionError("bot ramp timed out at $target: $latest")
    }

    private fun restoreNewestO12Baseline(database: IDatabaseControl): String {
        val backups = assertOk(database.listBackups()).getJSONArray("backups")
        val candidate = (0 until backups.length()).map(backups::getJSONObject)
            .filter { it.getString("snapshotId").startsWith("manual-o12-") }
            .maxByOrNull { it.getLong("createdAt") }
            ?: throw AssertionError("no manual baseline backup is available")
        val snapshotId = candidate.getString("snapshotId")
        val restore = assertOk(database.beginRestore(snapshotId))
        val token = restore.getString("restoreToken")
        var realm: Bound<IRealmControl>? = null
        var world: Bound<IWorldControl>? = null
        try {
            assertOk(database.start())
            assertOk(database.queryHealth())
            realm = bind(RealmRuntimeService::class.java) { IRealmControl.Stub.asInterface(it) }
            assertOk(realm.api.start())
            waitReady(120_000) { JSONObject(requireNotNull(realm).api.status()) }
            world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
            assertOk(world.api.startNormal())
            waitReady(600_000) { JSONObject(requireNotNull(world).api.status()) }
            assertOk(world.api.stop())
            world.close(); world = null
            assertOk(realm.api.stop())
            realm.close(); realm = null
            assertOk(database.stop())
            assertOk(database.commitRestore(token))
            return snapshotId
        } catch (failure: Throwable) {
            world?.let { runCatching { it.api.stop() }; it.close() }
            realm?.let { runCatching { it.api.stop() }; it.close() }
            runCatching { database.stop() }
            runCatching { database.rollbackRestore(token) }
            throw failure
        }
    }

    private fun waitReady(timeoutMs: Long, read: () -> JSONObject): JSONObject {
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

    private fun waitForSteadyP99(
        api: IWorldControl,
        maxP99Ms: Long,
        consecutiveSamples: Int,
        timeoutMs: Long,
    ): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var consecutive = 0
        var latest = JSONObject(api.status())
        while (System.nanoTime() < deadline) {
            latest = JSONObject(api.status())
            val steady = latest.getString("state") == "READY" &&
                latest.getLong("botsOnline") >= 25 &&
                latest.getLong("effectiveBotTarget") == 25L &&
                !latest.optBoolean("botTargetAdapted") &&
                latest.getLong("worldTickP99Ms") <= maxP99Ms
            consecutive = if (steady) consecutive + 1 else 0
            Log.i(TAG, "steady-state $consecutive/$consecutiveSamples status=$latest")
            if (consecutive >= consecutiveSamples) return latest
            Thread.sleep(10_000)
        }
        throw AssertionError("steady-state p99 timed out: $latest")
    }

    private fun assertOk(raw: String): JSONObject = JSONObject(raw).also {
        assertTrue("control failure: $it", it.getBoolean("ok"))
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

    private companion object {
        const val TAG = "O13BotTierTest"
    }
}
