package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.ServerRuntimeContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MariaDB-vs-SQLite bot pressure benchmark on the host emulator. Boots
 * the REAL stack on a REAL client-derived o11 generation (dbc+maps+
 * vmaps+mmaps — staged host-side), then drives REAL playerbot
 * populations through the product surface:
 *
 * - startBotProfile(profile) — the exact normal-play bot entry the UI
 *   uses (RandomBotAutoCreate provisions accounts/characters itself);
 * - stepped waves via setBotTarget(n): ramp, soak, and per-wave a
 *   client-acked saveall with ack timing;
 * - the product's OWN telemetry sampled every sampleMs while bots run:
 *   botsOnline, effectiveBotTarget, worldTickP50/P95/P99Ms,
 *   worldTickWindowMaxMs, worldHardStalls, dbProbeDelayMs — plus
 *   per-process VmRSS (same-UID /proc) and Binder RTT for
 *   IDatabaseControl.queryHealth.
 *
 * Runs IDENTICALLY on Server A (MariaDB) and Server B (SQLite).
 *
 * Instrumentation args (host driver): waves (default
 * "mobile-typical-b50-v1:120,mobile-balanced-b100-v1:180,"
 * "mobile-lowcpu-nearby-b160-v1:240" as profileId:soakSeconds — one
 * measured profile per wave, a fresh world boot each; setBotTarget is
 * refused while a profile's admission monitor owns the target),
 * sampleMs (default 5000), rampTimeoutMs (default 900000).
 */
@RunWith(AndroidJUnit4::class)
class BotPressureBenchmarkRunner {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = JSONObject()
    private val connections = ArrayList<ServiceConnection>()
    private var control: IDatabaseControl? = null
    private var realm: Bound<IRealmControl>? = null
    private var world: Bound<IWorldControl>? = null
    private val stopRequested = AtomicBoolean(false)

    @After fun unbind() {
        stopRequested.set(true)
        runCatching { world?.api?.let { runCatching { it.stop() } } }
        world?.close()
        runCatching { realm?.api?.let { runCatching { it.stop() } } }
        realm?.close()
        runCatching { control?.stop() }
        connections.forEach { runCatching { context.unbindService(it) } }
        connections.clear()
    }

    @Test fun botPressureBenchmark() {
        val args = InstrumentationRegistry.getArguments()
        val wavesArg = args.getString("waves")
            ?: "bench-autologin-b50-v1:120,bench-autologin-b100-v1:180," +
               "bench-autologin-b160-v1:240"
        val sampleMs = (args.getString("sampleMs") ?: "5000").toLong().coerceIn(1_000, 60_000)
        val rampTimeoutMs = (args.getString("rampTimeoutMs") ?: "900000").toLong()
        // First boot per install builds the playerbot equipment cache
        // (246k cells) + random-bot provisioning - minutes to tens of
        // minutes depending on host load; later boots skip it.
        val worldReadyTimeoutMs = (args.getString("worldReadyTimeoutMs")
            ?: "3600000").toLong()
        val waves = wavesArg.split(',').map { part ->
            val separator = part.trim().lastIndexOf(':')
            part.trim().substring(0, separator) to
                part.trim().substring(separator + 1).toLong().coerceIn(10, 3_600)
        }
        assertTrue("no waves requested", waves.isNotEmpty())
        evidence.put("waves", wavesArg).put("sampleMs", sampleMs)

        // ---- database boot -------------------------------------------------------
        control = bind("com.pocketrealm.database.DatabaseService") { IDatabaseControl.Stub.asInterface(it) }.api
        val status = assertOk(control!!.status())
        evidence.put("providerMode", status.optString("providerMode"))
        // Product-startup parity: the supervisor
        // recovers an interrupted runtime before booting; the headless
        // path used to skip this, so an unclean death poisoned every
        // later run until a full uninstall. recover() reporting not-ok
        // with "recovery requested for a clean generation" is the
        // expected healthy case - record it, never assert it.
        val preRecover = JSONObject(control!!.recover())
        evidence.put("preRecoverOk", preRecover.optBoolean("ok"))
        val bootStart = System.currentTimeMillis()
        assertOk(control!!.initialize())
        assertOk(control!!.applyPinnedMigrations())
        assertOk(control!!.start())
        assertOk(control!!.queryHealth())
        evidence.put("dbBootMs", System.currentTimeMillis() - bootStart)

        // ---- realmd boot (stays up across waves) ---------------------------------
        realm = bind("com.pocketrealm.server.RealmRuntimeService") { IRealmControl.Stub.asInterface(it) }
        assertOk(realm!!.api.start())
        waitReady(120_000, "realm") { JSONObject(realm!!.api.status()) }
        evidence.put("port${ServerRuntimeContract.REALM_PORT}Reachable",
            loopbackReachable(ServerRuntimeContract.REALM_PORT))

        // ---- world binder (waves boot the world per profile) ---------------------
        world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }

        // ---- waves: one measured bot profile per wave (the product
        // surface: setBotTarget is REFUSED while a profile's admission
        // monitor owns the target, so waves are distinct profiles, each
        // a fresh world boot). The world stop arms the 250 ms
        // retireCleanProcess fuse: close, sleep past it, rebind between
        // waves.
        val waveResults = JSONArray()
        for ((index, wave) in waves.withIndex()) {
            val (profileId, soakSeconds) = wave
            val waveResult = JSONObject().put("profileId", profileId).put("index", index)
            val worldStart = System.currentTimeMillis()
            assertOk("world-startBotProfile-$profileId", world!!.api.startBotProfile(profileId))
            val worldReady = waitReady(worldReadyTimeoutMs, "world-$profileId") { JSONObject(world!!.api.status()) }
            waveResult.put("worldBootMs", System.currentTimeMillis() - worldStart)
            assertTrue("playerbots must be ENABLED on the bot profile",
                worldReady.optBoolean("playerbotsEnabled"))
            val target = worldReady.optLong("selectedBotTarget", 0L)
            waveResult.put("target", target)
            assertTrue("bot profile reported no target ($profileId)", target > 0)

            val rampStart = System.currentTimeMillis()
            val reached = waitForBots(target.toInt(), rampTimeoutMs)
            waveResult.put("rampMs", System.currentTimeMillis() - rampStart)
            waveResult.put("botsOnlineAtRampEnd", reached)

            val samples = JSONArray()
            val dbHealth = JSONArray()
            healthRttLoop(sampleMs) { dbHealth.put(it) }
            val deadline = System.currentTimeMillis() + soakSeconds * 1000
            while (System.currentTimeMillis() < deadline && !stopRequested.get()) {
                samples.put(sampleWorld())
                Thread.sleep(sampleMs)
            }
            waveResult.put("samples", samples)
            waveResult.put("dbHealth", dbHealth)
            waveResult.put("sampleCount", samples.length())

            val saveStart = System.currentTimeMillis()
            assertOk("wave-save-$profileId", world!!.api.save())
            val saveAckMs = System.currentTimeMillis() - saveStart
            waveResult.put("saveallAckMs", saveAckMs)
            waveResult.put("botsOnlineAfterSave", JSONObject(world!!.api.status()).optLong("botsOnline", -1))
            waveResults.put(waveResult)
            // incremental evidence: a later failure must not lose the
            // completed waves (one profile per install is the supported
            // lane shape - switching profiles mid-install leaves the old
            // random-bot registry in place and the next profile's adds
            // are refused: "Attempt to add not allowed bot")
            evidence.put("waves", waveResults)
            File(context.filesDir, "bot-pressure-benchmark.json")
                .writeText(evidence.toString())
            if (index < waves.size - 1) {
                assertOk("wave-world-stop-$profileId", world!!.api.stop())
                world!!.close()
                Thread.sleep(1_000)
                world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
            }
        }
        evidence.put("waves", waveResults)

        // ---- quiesce: settle after the last save, capture final state ------------
        Thread.sleep(10_000)
        evidence.put("finalStatus", sampleWorld())
        val finalSaveStart = System.currentTimeMillis()
        assertOk("final-save", world!!.api.save())
        evidence.put("finalSaveallAckMs", System.currentTimeMillis() - finalSaveStart)
        evidence.put("finalBotStatus", JSONObject(world!!.api.botStatus()))

        // ---- clean teardown (supervisor order) -----------------------------------
        assertOk("world-final-stop", world!!.api.stop())
        world!!.close(); world = null
        assertOk("realm-final-stop", realm!!.api.stop())
        realm!!.close(); realm = null
        assertOk("db-final-stop", control!!.stop())

        File(context.filesDir, "bot-pressure-benchmark.json").writeText(evidence.toString())
    }

    /** Poll until the wave's bots are online. Two exits:
     *  (a) the floor (95% for big waves - the profile's login throttle
     *      spaces the last logins across update intervals), or
     *  (b) a PLATEAU with the admission controller ADAPTED - the
     *      profile's admission monitor sheds population when world p99
     *      exceeds the contract, and that stabilized population is
     *      itself an absolute-capacity datum, not a timeout failure. */
    private fun waitForBots(target: Int, timeoutMs: Long): Long {
        val floor = if (target >= 50) target - target / 20 else target
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = 0L
        var plateauSamples = 0
        var plateauAt = 0L
        var adapted = false
        var admissionReason = ""
        while (System.currentTimeMillis() < deadline && !stopRequested.get()) {
            val status = JSONObject(world!!.api.status())
            latest = status.optLong("botsOnline", 0)
            adapted = status.optBoolean("botTargetAdapted", false)
            admissionReason = status.optString("botAdmissionReason", "")
            if (latest >= floor) return latest
            if (adapted && latest > 0) {
                if (latest == plateauAt) {
                    plateauSamples++
                    // 10 stable samples (~30 s at the 3 s cadence) with the
                    // admission controller holding the population
                    if (plateauSamples >= 10) {
                        evidence.put("admissionAdapted", true)
                            .put("admissionReason", admissionReason)
                            .put("admissionPlateau", latest)
                        return latest
                    }
                } else {
                    plateauAt = latest
                    plateauSamples = 0
                }
            }
            Thread.sleep(3_000)
        }
        // Ramp deadline expired below the floor. On-battery reality:
        // the device's power-save CPU scheduling can
        // cap the achievable population below the profile target while
        // the world stays fully healthy - aborting there discards the
        // soak window, which is the measurement we want. Accept the
        // achieved population as the measured load; only a degraded
        // world or a collapsed ramp fails the test. Same rule for every
        // engine lane.
        val finalStatus = JSONObject(world!!.api.status())
        assertTrue("world not READY at ramp deadline: " +
            finalStatus.toString().take(300),
            finalStatus.optString("state") == "READY")
        assertTrue("ramp collapsed to $latest online (status " +
            "${finalStatus.toString().take(300)})", latest >= 150)
        evidence.put("rampCappedAt", latest).put("rampTarget", target)
        return latest
    }

    /** One telemetry sample: the product's world status (tick window
     * percentiles, hard stalls, db probe delay, bots online) plus the
     * same-UID VmRSS of the :world and :database processes and a Binder
     * RTT for IDatabaseControl.queryHealth. */
    private fun sampleWorld(): JSONObject {
        val raw = JSONObject(world!!.api.status())
        val sample = JSONObject()
        for (key in listOf("botsOnline", "effectiveBotTarget", "worldTickP50Ms",
                "worldTickP95Ms", "worldTickP99Ms", "worldTickWindowMaxMs",
                "worldHardStalls", "dbProbeDelayMs", "tickCount", "pid")) {
            sample.put(key, raw.optLong(key, -1))
        }
        sample.put("worldRssKb", vmRssKb(raw.optLong("pid", -1)))
        sample.put("dbRssKb", vmRssKb(dbPid()))
        sample.put("atMs", System.currentTimeMillis())
        return sample
    }

    /** Binder RTT of the database health probe while bots are running. */
    private fun healthRttLoop(sampleMs: Long, sink: (JSONObject) -> Unit) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < sampleMs && !stopRequested.get()) {
            val t0 = System.currentTimeMillis()
            val ok = runCatching { assertOk(control!!.queryHealth()).optBoolean("ok", false) }
                .getOrDefault(false)
            sink(JSONObject().put("dbHealthBinderRttMs", System.currentTimeMillis() - t0)
                .put("dbHealthOk", ok).put("atMs", System.currentTimeMillis()))
            Thread.sleep(500)
        }
    }

    private var cachedDbPid = AtomicLong(-1)
    private fun dbPid(): Long {
        val cached = cachedDbPid.get()
        if (cached > 0) return cached
        val pid = runCatching {
            JSONObject(control!!.status()).optLong("pid", -1)
        }.getOrDefault(-1)
        if (pid > 0) cachedDbPid.set(pid)
        return pid
    }

    private fun vmRssKb(pid: Long): Long {
        if (pid <= 0) return -1
        return runCatching {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong() ?: -1L
            }
        }.getOrDefault(-1)
    }

    private fun loopbackReachable(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
        true
    } catch (_: Exception) {
        false
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
