package com.pocketrealm.o13

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.ClientDisplayService
import com.pocketrealm.client.IClientDisplayControl
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.WorldRuntimeService
import com.pocketrealm.service.RealmService
import com.pocketrealm.supervisor.AndroidRuntimeBackend
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
import com.pocketrealm.storage.Settings
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit physical-device verifier: start the production-owned bot realm and
 * client and prove readiness while instrumentation remains attached. Persistent
 * human handoff is exercised through Home because Android force-stops an
 * instrumented target package when the runner exits.
 */
@RunWith(AndroidJUnit4::class)
class O13LiveBootTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun startBotsClientAndVerifyRunning() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "explicit -e pocketLiveBoot true opt-in is required",
            arguments.getString("pocketLiveBoot") == "true",
        )
        val profileId = arguments.getString("pocketBotProfile")
            ?: AndroidRuntimeBackend.BOT_LOW_25_PROFILE
        val profile = requireNotNull(BotProfiles.find(profileId)) {
            "pocketBotProfile must name a registered bot profile"
        }
        val holdSeconds = arguments.getString("pocketHoldSeconds")?.toIntOrNull() ?: 0
        require(holdSeconds in 0..7_200) { "pocketHoldSeconds must be in 0..7200" }
        // This live stress entry point is intentionally explicit about the
        // graphics lane.  It prevents a stale user preference from silently
        // turning an OpenGL-vs-DXVK run into a mixed comparison.  The setting
        // is persisted by the normal app Settings store and is picked up by
        // AndroidRuntimeBackend before the client prefix is prepared.
        runBlocking {
            Settings(context).update { current ->
                current.copy(
                    renderer = Settings.Renderer.OPENGL,
                    provider = Settings.RuntimeProvider.BOX64,
                )
            }
        }
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

        initializeDatabaseIfNeeded()
        // A Binder-created service is destroyed with its last observer. Start
        // the production foreground service first so it retains the owner
        // lease and all component bindings after this handoff test unbinds.
        context.startForegroundService(Intent(context, RealmService::class.java))

        val supervisor = bind(RealmService::class.java) {
            IRuntimeSupervisorControl.Stub.asInterface(it)
        }
        try {
            val beforeStart = JSONObject(supervisor.api.status())
            val retainedBotRealm = beforeStart.getString("phase") == "CLIENT_FAILED" &&
                beforeStart.optBoolean("supervisorGenerationActive") &&
                beforeStart.optString("requestedProfile") == profileId
            val accepted = JSONObject(if (retainedBotRealm) supervisor.api.relaunchClient()
                else supervisor.api.start(profileId, true))
            assertTrue(accepted.toString(), accepted.getBoolean("ok"))
            val running = waitFor(BOOT_TIMEOUT_MS) {
                JSONObject(supervisor.api.status()).takeIf { status ->
                    when (status.getString("phase")) {
                        // A replaced APK may retain the last durable RUNNING
                        // snapshot while no supervisor generation is alive.
                        // Do not mistake that recovery input for this run's
                        // readiness; require the newly owned generation.
                        "RUNNING" -> status.optBoolean("supervisorGenerationActive") &&
                            status.getLong("updatedAtWallMs") >
                            beforeStart.getLong("updatedAtWallMs")
                        "UNCONFIGURED", "ERROR", "CLIENT_FAILED" -> {
                            if (status.getLong("updatedAtWallMs") >
                                beforeStart.getLong("updatedAtWallMs")) error(status.toString())
                            false
                        }
                        else -> false
                    }
                }
            }
            assertTrue(running.toString(), running.getBoolean("supervisorGenerationActive"))
            assertTrue(running.toString(),
                running.getString("requestedProfile") == profileId)

            val world = bind(WorldRuntimeService::class.java) { IWorldControl.Stub.asInterface(it) }
            try {
                val ready = waitFor(BOT_READY_TIMEOUT_MS) {
                    JSONObject(world.api.status()).takeIf { status ->
                        status.getString("state") == "READY" &&
                            status.optBoolean("playerbotsEnabled") &&
                            status.optString("botGenerationState") == "complete"
                    }
                }
                assertTrue(ready.toString(), ready.getBoolean("compiledPlayerbots"))
                assertTrue(ready.toString(), ready.getBoolean("playerbotsEnabled"))
                assertFalse(ready.toString(), ready.getBoolean("auctionHouseBot"))
                assertTrue(ready.toString(), ready.getLong("botsAvailable") >= profile.selectedTarget)

                val display = bind(ClientDisplayService::class.java) {
                    IClientDisplayControl.Stub.asInterface(it)
                }
                try {
                    val displayReady = waitFor(DISPLAY_TIMEOUT_MS) {
                        JSONObject(display.api.status()).takeIf { status ->
                            status.optBoolean("prepared") && status.optBoolean("rendererReady") &&
                                status.optBoolean("windowVisible")
                        }
                    }
                    assertTrue(displayReady.toString(), displayReady.getBoolean("rendererReady"))
                    val liveEvidence = File(context.filesDir, "live-boot.json")
                    liveEvidence.writeText(JSONObject()
                        .put("schema", 1).put("profile", profileId)
                        .put("phase", running.getString("phase"))
                        .put("botsAvailable", ready.getLong("botsAvailable"))
                        .put("botsOnline", ready.optLong("botsOnline"))
                        .put("activeBots", ready.optLong("activeBots"))
                        .put("realPlayers", ready.optLong("realPlayers"))
                        .put("botsSameActiveZone", ready.optLong("botsSameActiveZone"))
                        .put("botsWithin150", ready.optLong("botsWithin150"))
                        .put("botsWithin500", ready.optLong("botsWithin500"))
                        .put("botsWithin1500", ready.optLong("botsWithin1500"))
                        .put("selectedBotTarget", profile.selectedTarget)
                        .put("rendererReady", true).put("windowVisible", true)
                        .put("verifiedAtMs", System.currentTimeMillis()).toString(2))

                    // Instrumentation force-stops the target package when it
                    // exits. An explicit hold keeps the production processes
                    // and UI alive for a human-operated physical-device stress
                    // session while continuously recording honest live metrics.
                    val holdDeadline = System.nanoTime() +
                        TimeUnit.SECONDS.toNanos(holdSeconds.toLong())
                    while (System.nanoTime() < holdDeadline) {
                        val sample = JSONObject(world.api.status())
                        liveEvidence.writeText(JSONObject()
                            .put("schema", 1).put("profile", profileId)
                            .put("phase", JSONObject(supervisor.api.status()).getString("phase"))
                            .put("botsAvailable", sample.optLong("botsAvailable"))
                            .put("botsOnline", sample.optLong("botsOnline"))
                            .put("activeBots", sample.optLong("activeBots"))
                            .put("realPlayers", sample.optLong("realPlayers"))
                            .put("botsSameActiveZone", sample.optLong("botsSameActiveZone"))
                            .put("botsWithin150", sample.optLong("botsWithin150"))
                            .put("botsWithin500", sample.optLong("botsWithin500"))
                            .put("botsWithin1500", sample.optLong("botsWithin1500"))
                            .put("botsLevelDelta2", sample.optLong("botsLevelDelta2"))
                            .put("botsLevelDelta4", sample.optLong("botsLevelDelta4"))
                            .put("botLoginsLast60s", sample.optLong("botLoginsLast60s"))
                            .put("botTeleportsLast60s", sample.optLong("botTeleportsLast60s"))
                            .put("botRerandomizesLast60s", sample.optLong("botRerandomizesLast60s"))
                            .put("effectiveBotTarget", sample.optLong("effectiveBotTarget"))
                            .put("selectedBotTarget", profile.selectedTarget)
                            .put("botTargetAdapted", sample.optBoolean("botTargetAdapted"))
                            .put("botAdmissionReason", sample.optString("botAdmissionReason"))
                            .put("worldTickP50Ms", sample.optLong("worldTickP50Ms"))
                            .put("worldTickP95Ms", sample.optLong("worldTickP95Ms"))
                            .put("worldTickP99Ms", sample.optLong("worldTickP99Ms"))
                            .put("worldTickWindowMaxMs", sample.optLong("worldTickWindowMaxMs"))
                            .put("worldHardStalls", sample.optLong("worldHardStalls"))
                            .put("worldPssMiB", sample.optLong("worldPssMiB"))
                            .put("freeMemoryMiB", sample.optLong("freeMemoryMiB"))
                            .put("thermalLevel", sample.optString("thermalLevel"))
                            .put("rendererReady", true).put("windowVisible", true)
                            .put("verifiedAtMs", System.currentTimeMillis()).toString(2))
                        Thread.sleep(10_000)
                    }
                } finally {
                    display.close()
                }
            } finally {
                world.close()
            }
        } finally {
            // Unbind observers only. RealmService is a foreground started
            // service and retains the production owner lease/runtime.
            supervisor.close()
        }
    }

    private fun initializeDatabaseIfNeeded() {
        val database = bind(DatabaseService::class.java) { IDatabaseControl.Stub.asInterface(it) }
        try {
            var status = requireOk("database status", database.api.status())
            if (status.getString("state") == "RUNNING") {
                requireOk("database stop", database.api.stop())
                status = requireOk("database stopped status", database.api.status())
            }
            if (status.optBoolean("restorePending")) {
                requireOk("database rollback pending restore", database.api.rollbackPendingRestore())
                status = requireOk("database rollback status", database.api.status())
            }
            if (!status.getBoolean("initialized")) {
                requireOk("database initialize", database.api.initialize())
                requireOk("database migrations", database.api.applyPinnedMigrations())
                status = requireOk("database initialized status", database.api.status())
            }
            if (!status.getBoolean("cleanMarker")) {
                requireOk("database recovery", database.api.recover())
            }
        } finally {
            database.close()
        }
    }

    private fun requireOk(label: String, raw: String): JSONObject = JSONObject(raw).also {
        check(it.optBoolean("ok")) { "$label failed: $raw" }
    }

    private fun <T> waitFor(timeoutMs: Long, read: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(250)
        }
        error("live boot timed out after ${timeoutMs}ms")
    }

    private fun <T> bind(type: Class<*>, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var api: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                api = convert(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        check(context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE))
        check(latch.await(15, TimeUnit.SECONDS)) { "${type.simpleName} bind timed out" }
        return Bound(context, connection, checkNotNull(api))
    }

    private class Bound<T>(
        private val context: Context,
        private val connection: ServiceConnection,
        val api: T,
    ) : AutoCloseable {
        override fun close() = context.unbindService(connection)
    }

    companion object {
        private const val BOOT_TIMEOUT_MS = 15 * 60 * 1_000L
        private const val BOT_READY_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val DISPLAY_TIMEOUT_MS = 60_000L
    }
}
