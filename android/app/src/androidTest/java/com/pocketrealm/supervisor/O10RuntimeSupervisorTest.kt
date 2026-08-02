package com.pocketrealm.supervisor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.service.RealmService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class O10RuntimeSupervisorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val timeline = JSONArray()

    @Test fun durableDependencyOwnershipShutdownAndRecovery() {
        val sessionBeforeFirstStart = persistedSession()
        RealmService.start(context)
        var supervisor = bindSupervisor()
        var ready = waitPhase(
            supervisor.api, 300_000, RuntimePhase.WORLD_READY,
            sessionNot = sessionBeforeFirstStart, tolerateInitialError = true)
        assertOwnedServerStack(ready)
        assertForegroundSupervisor()
        timeline.put(event("server-ready").put("sessionId", ready.getString("sessionId")))

        // O10 deliberately keeps the qualified realm alive if client launch is
        // unavailable/fails; O12 will attach the production display session.
        assertAccepted(supervisor.api.relaunchClient())
        val clientFailed = waitPhase(supervisor.api, 30_000, RuntimePhase.CLIENT_FAILED)
        assertEquals("READY", component(clientFailed, "database").getString("state"))
        assertEquals("READY", component(clientFailed, "realm").getString("state"))
        assertEquals("READY", component(clientFailed, "world").getString("state"))
        timeline.put(event("client-failure-isolated"))

        assertAccepted(supervisor.api.stop(false))
        val stopped = waitPhase(supervisor.api, 120_000, RuntimePhase.STOPPED)
        assertTrue(stopped.getBoolean("clean"))
        assertEquals("clean-stop-committed", stopped.getString("lastDurableAction"))
        assertJournal(stopped, clean = true)
        timeline.put(event("clean-stop"))
        supervisor.close()

        // A killed supervisor cannot clean its journal. Its Binder-owned child
        // services lose the owner and apply their safe teardown policy. The
        // next explicit Start must recover before creating a new session.
        RealmService.start(context)
        supervisor = bindSupervisor()
        ready = waitPhase(supervisor.api, 240_000, RuntimePhase.WORLD_READY)
        val killedSession = ready.getString("sessionId")
        JSONObject(supervisor.api.killSupervisorForTest()).also { assertTrue(it.getBoolean("ok")) }
        supervisor.awaitDeath(15_000)
        supervisor.close()
        Thread.sleep(2_000)
        assertJournalPhaseNotClean()

        RealmService.start(context)
        supervisor = bindSupervisor()
        val recovered = waitPhase(
            supervisor.api, 300_000, RuntimePhase.WORLD_READY, sessionNot = killedSession)
        assertOwnedServerStack(recovered)
        assertNotEquals(killedSession, recovered.getString("sessionId"))
        timeline.put(event("supervisor-death-recovered"))

        // Kill only the token-matched world generation. The supervisor must
        // classify it realm-fatal, retire all verified dependants, and keep a
        // dirty journal rather than claiming a clean stop.
        assertAccepted(supervisor.api.forceComponentForTest("world"))
        val failed = waitPhase(supervisor.api, 120_000, RuntimePhase.ERROR)
        assertFalse(failed.getBoolean("clean"))
        assertTrue(failed.getString("lastError").contains("WORLD"))
        assertEquals(Recoverability.RECOVERY_REQUIRED.name, failed.getString("recoverability"))
        timeline.put(event("owned-world-failure-realm-fatal"))

        val failedSession = failed.getString("sessionId")
        assertAccepted(supervisor.api.start(AndroidRuntimeBackend.DEFAULT_PROFILE, false))
        val restarted = waitPhase(
            supervisor.api, 300_000, RuntimePhase.WORLD_READY,
            sessionNot = failedSession, tolerateInitialError = true)
        assertOwnedServerStack(restarted)
        assertAccepted(supervisor.api.stop(false))
        val final = waitPhase(supervisor.api, 120_000, RuntimePhase.STOPPED)
        assertTrue(final.getBoolean("clean"))
        assertJournal(final, clean = true)
        timeline.put(event("final-clean-recovery-cycle"))

        val evidence = JSONObject()
            .put("schema", 1).put("feature", "O10").put("ok", true)
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("pageSize", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("journalSchema", 2).put("ownershipTokenBits", 256)
            .put("readinessFromPidOnly", false)
            .put("clientFailureIsolated", true)
            .put("worldFailureRealmFatal", true)
            .put("supervisorDeathRecovered", true)
            .put("exactShutdownOrderUnitProven", true)
            .put("timeline", timeline)
        val output = File(context.getExternalFilesDir(null), "evidence/O10_RUNTIME_SUPERVISOR.json")
        output.parentFile!!.mkdirs(); output.writeText(evidence.toString(2))
        supervisor.close()
    }

    private fun assertOwnedServerStack(status: JSONObject) {
        assertFalse(status.getBoolean("clean"))
        val session = status.getString("sessionId")
        val tokens = mutableSetOf<String>()
        listOf("database", "realm", "world").forEach { name ->
            val value = component(status, name)
            assertEquals("READY", value.getString("state"))
            val token = value.getString("instanceToken")
            assertTrue(token.matches(Regex("[0-9a-f]{64}")))
            tokens += token
        }
        assertEquals(3, tokens.size)
        assertTrue(session.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("STOPPED", component(status, "client").getString("state"))
    }

    private fun assertForegroundSupervisor() {
        val process = context.getSystemService(android.app.ActivityManager::class.java)
            .runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "${context.packageName}:supervisor" }
        assertTrue("supervisor process missing", process != null)
        assertTrue("supervisor not foreground-service importance: ${process?.importance}",
            process?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
                process?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
    }

    private fun assertJournal(status: JSONObject, clean: Boolean) {
        val journal = JSONObject(File(context.noBackupFilesDir, "runtime-supervisor/journal.json").readText())
        assertEquals(status.getString("phase"), journal.getString("phase"))
        assertEquals(clean, journal.getBoolean("clean"))
        assertEquals(2, journal.getInt("schema"))
    }

    private fun assertJournalPhaseNotClean() {
        val journal = JSONObject(File(context.noBackupFilesDir, "runtime-supervisor/journal.json").readText())
        assertFalse(journal.getBoolean("clean"))
        assertTrue(journal.getString("phase") in setOf(
            RuntimePhase.WORLD_READY.name, RuntimePhase.CLIENT_FAILED.name, RuntimePhase.RUNNING.name))
    }

    private fun persistedSession(): String? {
        val file = File(context.noBackupFilesDir, "runtime-supervisor/journal.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()).optString("sessionId").takeIf { it.isNotBlank() } }
            .getOrNull()
    }

    private fun component(status: JSONObject, name: String) =
        status.getJSONObject("components").getJSONObject(name)

    private fun event(name: String) = JSONObject().put("event", name)

    private fun assertAccepted(raw: String) {
        val value = JSONObject(raw)
        assertTrue("operation rejected: $value", value.getBoolean("ok") && value.getBoolean("accepted"))
    }

    private fun waitPhase(
        api: IRuntimeSupervisorControl,
        timeoutMs: Long,
        expected: RuntimePhase,
        sessionNot: String? = null,
        tolerateInitialError: Boolean = false,
    ): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.status())
        while (System.nanoTime() < deadline) {
            if (latest.getString("phase") == expected.name &&
                (sessionNot == null || latest.optString("sessionId") != sessionNot)) return latest
            if (!tolerateInitialError && latest.getString("phase") == RuntimePhase.ERROR.name &&
                expected != RuntimePhase.ERROR) {
                throw AssertionError("supervisor failed before $expected: $latest")
            }
            Thread.sleep(200)
            latest = JSONObject(api.status())
        }
        throw AssertionError("timed out waiting for $expected: $latest")
    }

    private fun bindSupervisor(): BoundSupervisor {
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        var api: IRuntimeSupervisorControl? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                api = IRuntimeSupervisorControl.Stub.asInterface(service)
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { died.countDown() }
            override fun onBindingDied(name: ComponentName?) { died.countDown() }
        }
        assertTrue(context.bindService(Intent(context, RealmService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(10, TimeUnit.SECONDS))
        return BoundSupervisor(context, connection, checkNotNull(api), died)
    }

    private data class BoundSupervisor(
        val context: Context,
        val connection: ServiceConnection,
        val api: IRuntimeSupervisorControl,
        val died: CountDownLatch,
    ) {
        fun awaitDeath(timeoutMs: Long) = assertTrue("supervisor Binder did not die",
            died.await(timeoutMs, TimeUnit.MILLISECONDS))
        fun close() = runCatching { context.unbindService(connection) }.let { Unit }
    }
}
