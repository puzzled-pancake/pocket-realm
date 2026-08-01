package com.pocketrealm.pkg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.pocketrealm.BuildConfig
import com.pocketrealm.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs the report's PKG-01/02/06 packaging experiments (report §8.4) and
 * returns typed [ExperimentResult]s. Used both by the in-app Capability screen
 * and (primarily) by the host driver `tools/run_pkg_experiments.py` via the
 * instrumented test that surfaces results to logcat.
 *
 * Design:
 *  - PKG-01 executes the APK-packaged PIE launcher (`libpocket_pkg_launcher.so`,
 *    renamed so AGP extracts it under the experiment variant) from its
 *    nativeLibraryDir path. It is a real process exec, not System.loadLibrary.
 *    Captures stdout/stderr/exit/resolved-path/dladdr.
 *  - PKG-02 binds the `:pkg` child, has it load the real realm shared object
 *    by SONAME, confirms hello, triggers a deterministic abort(), then proves
 *    containment: :main PID alive, child PID gone, Binder death observed, and a
 *    fresh PID answers hello after restart.
 *  - PKG-06 loads every .so and runs a long heartbeat; the genuine 30-minute
 *    acceptance runs are driven by the host driver, not by this UI path.
 *
 * All native code is packaged by the Gradle stageNativeLibs task under
 * lib/x86_64/.
 */
class PackagingExperimentRunner(private val context: Context) {

    /** PKG-01: execute the launcher and capture its structured stdout/stderr. */
    suspend fun runPkg01(launcherRelName: String = "libpocket_pkg_launcher.so"): ExperimentResult =
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val runId = currentRunId()
            val ai = context.applicationInfo
            val nativeDir = ai.nativeLibraryDir
            val launcher = if (nativeDir.isNullOrEmpty()) null else File(nativeDir, launcherRelName)

            val evidence = linkedMapOf<String, String>()
            evidence["variant"] = if (BuildConfig.DEBUG) "debug" else "release"
            evidence["nativeLibraryDir"] = nativeDir ?: "(null)"
            evidence["useLegacyPackagingHint"] = (ai.flags and android.content.pm.ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0).toString()

            if (launcher == null || !launcher.isFile) {
                return@withContext ExperimentResult.fail(
                    "PKG-01", runId, "NO_EXECUTABLE_FS_PATH",
                    detail = listOf(
                        "No executable filesystem path for the launcher in this packaging variant.",
                        "This is the documented production-variant (useLegacyPackaging=false) behavior:",
                        "the .so is stored uncompressed in the APK and may not be extracted to disk.",
                        "PKG-01 direct exec is required only on the experiment variant (pkgExperiment)."
                    ),
                    evidence = evidence, ms = SystemClock.elapsedRealtime() - t0
                )
            }

            // Make sure it is executable; extraction should set +x but be explicit.
            if (!launcher.canExecute()) runCatching { launcher.setExecutable(true) }
            evidence["launcherPath"] = launcher.absolutePath
            evidence["launcherCanExecute"] = launcher.canExecute().toString()

            val pb = ProcessBuilder(launcher.absolutePath).redirectErrorStream(false)
            val proc = runCatching { pb.start() }.getOrElse {
                return@withContext ExperimentResult.fail(
                    "PKG-01", runId, "EXEC_FAILED",
                    detail = listOf("ProcessBuilder.start() threw: ${it.javaClass.simpleName}: ${it.message}"),
                    evidence = evidence, ms = SystemClock.elapsedRealtime() - t0
                )
            }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val exit = withTimeoutOrNull(15_000) {
                val code = proc.waitFor(); code
            }
            val ms = SystemClock.elapsedRealtime() - t0
            evidence["stdout"] = out
            evidence["stderr"] = err
            evidence["exitCode"] = (exit?.toString() ?: "TIMEOUT")
            val sawHello = out.contains("PKG_LAUNCHER_HELLO")
            val sawOk = out.contains("PKG_LAUNCHER_OK")
            val realmPath = Regex("realm_path\t(.+)").find(out)?.groupValues?.get(1)?.trim()
            if (!realmPath.isNullOrEmpty()) evidence["realmDladdrPath"] = realmPath
            val pageLine = Regex("page_size\t(\\d+)").find(out)?.groupValues?.get(1)?.trim()
            if (!pageLine.isNullOrEmpty()) evidence["launcherPageSize"] = pageLine

            if (exit == 0 && sawHello && sawOk) {
                ExperimentResult.ok("PKG-01", runId, evidence, ms,
                    code = if (realmPath.isNullOrEmpty()) "OK_NO_DLADDR" else "OK")
            } else {
                ExperimentResult.fail("PKG-01", runId, "NONZERO_OR_MISSING_MARKERS",
                    detail = listOf("exit=$exit hello=$sawHello ok=$sawOk"),
                    evidence = evidence, ms = ms)
            }
        }

    /** PKG-02: isolated child loads real realm .so, crashes, and survives a restart. */
    suspend fun runPkg02(): ExperimentResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val runId = currentRunId()
        val mainPid = Process.myPid()
        val evidence = linkedMapOf<String, String>()
        evidence["mainPid"] = mainPid.toString()

        // 1. Bind the :pkg child.
        val conn = PkgConnection(context)
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { conn.bind() }
        if (bound == null || bound.service == null) {
            return@withContext ExperimentResult.fail("PKG-02", runId, "BIND_TIMEOUT",
                listOf("Could not bind :pkg child within ${BIND_TIMEOUT_MS}ms"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        val svc1: IPkgIsolation = bound.service ?: return@withContext ExperimentResult.fail(
            "PKG-02", runId, "BIND_NO_SERVICE",
            listOf("bind() returned but service proxy was null"), evidence, SystemClock.elapsedRealtime() - t0)
        val childPidBefore = svc1.pid()
        evidence["childPidBeforeCrash"] = childPidBefore.toString()

        // 2. Load the real realm shared object by SONAME in the child.
        val info = runCatching { svc1.loadRealmSoBySoname() }.getOrElse {
            conn.unbindSafe(); return@withContext ExperimentResult.fail("PKG-02", runId, "LOAD_REALM_SO_THREW",
                listOf("${it.javaClass.simpleName}: ${it.message}"), evidence, SystemClock.elapsedRealtime() - t0)
        }
        evidence["realmLoaded"] = info.loaded.toString()
        evidence["realmErr"] = info.err.toString()
        evidence["realmSoname"] = info.soname
        evidence["realmDladdrPath"] = info.path
        evidence["realmBaseAddr"] = info.baseAddr.toString()
        if (!info.isLoaded) {
            conn.unbindSafe(); return@withContext ExperimentResult.fail("PKG-02", runId, "REALM_SO_NOT_LOADED",
                listOf("dlopen(\"${info.soname}\") did not load (err=${info.err})"), evidence, SystemClock.elapsedRealtime() - t0)
        }

        // 3. Confirm hello before crash.
        val hello1 = svc1.hello()
        evidence["helloBeforeCrash"] = hello1
        if (hello1 != "pocket-realm-pkg-ok") {
            conn.unbindSafe(); return@withContext ExperimentResult.fail("PKG-02", runId, "HELLO_MISMATCH",
                listOf("hello before crash was: $hello1"), evidence, SystemClock.elapsedRealtime() - t0)
        }

        // 4. Trigger deterministic abort(); expect Binder death.
        var deathObserved = false
        conn.attachDeathRecipient { deathObserved = true }
        try { svc1.crash(PkgNative.CRASH_ABORT) } catch (_: Throwable) { /* expected: IPC broken */ }

        // 5. Wait for death notification (the child process dies).
        val died = withTimeoutOrNull(DEATH_TIMEOUT_MS) {
            while (!deathObserved) kotlinx.coroutines.delay(100)
            true
        }
        evidence["binderDeathObserved"] = deathObserved.toString()
        evidence["mainPidStillAlive"] = (Process.myPid() == mainPid).toString()
        conn.unbindSafe()

        if (died == null || !deathObserved) {
            return@withContext ExperimentResult.fail("PKG-02", runId, "NO_BINDER_DEATH",
                listOf("Binder death not observed within ${DEATH_TIMEOUT_MS}ms"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }

        // 6. Restart the child and prove a NEW PID answers hello.
        val conn2 = PkgConnection(context)
        val bound2 = withTimeoutOrNull(BIND_TIMEOUT_MS) { conn2.bind() }
        val svc2: IPkgIsolation? = bound2?.service
        if (svc2 == null) {
            return@withContext ExperimentResult.fail("PKG-02", runId, "RESTART_BIND_FAILED",
                listOf("Could not rebind :pkg after crash"), evidence, SystemClock.elapsedRealtime() - t0)
        }
        val childPidAfter = svc2.pid()
        val hello2 = svc2.hello()
        evidence["childPidAfterCrash"] = childPidAfter.toString()
        evidence["helloAfterRestart"] = hello2
        conn2.unbindSafe()
        val ms = SystemClock.elapsedRealtime() - t0

        val newPid = childPidAfter != childPidBefore
        val containment = Process.myPid() == mainPid && newPid && hello2 == "pocket-realm-pkg-ok"
        if (containment) {
            ExperimentResult.ok("PKG-02", runId, evidence, ms, code = "CONTAINMENT_PROVEN")
        } else {
            ExperimentResult.fail("PKG-02", runId, "CONTAINMENT_NOT_PROVEN",
                listOf("mainAlive=${Process.myPid() == mainPid} newPid=$newPid hello=$hello2"),
                evidence, ms)
        }
    }

    /**
     * PKG-06 smoke: load the JNI shim + realm .so by SONAME, then heartbeat.
     * The genuine 30-minute run is driven by the host driver; this in-process
     * path runs a bounded [durationSeconds] for the deterministic test.
     */
    suspend fun runPkg06(durationSeconds: Long): ExperimentResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val runId = currentRunId()
        val evidence = linkedMapOf<String, String>()
        val conn = PkgConnection(context)
        val svc: IPkgIsolation? = withTimeoutOrNull(BIND_TIMEOUT_MS) { conn.bind() }?.service
        if (svc == null) {
            return@withContext ExperimentResult.fail("PKG-06", runId, "BIND_TIMEOUT",
                listOf("Could not bind :pkg child"), evidence, SystemClock.elapsedRealtime() - t0)
        }
        val pageSize = svc.probePageSize()
        evidence["pageSize"] = pageSize.toString()
        val info = svc.loadRealmSoBySoname()
        evidence["realmLoaded"] = info.loaded.toString()
        evidence["realmDladdrPath"] = info.path
        if (!info.isLoaded) {
            conn.unbindSafe(); return@withContext ExperimentResult.fail("PKG-06", runId, "REALM_SO_NOT_LOADED",
                listOf("dlopen(\"${info.soname}\") did not load"), evidence, SystemClock.elapsedRealtime() - t0)
        }
        val ticks = mutableListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1000
        var tickCount = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            tickCount++
            // A live hello proves the :pkg process and the realm .so are still resident.
            val h = runCatching { svc.hello() }.getOrElse {
                conn.unbindSafe(); return@withContext ExperimentResult.fail("PKG-06", runId, "TICK_HELLO_FAILED",
                    listOf("tick $tickCount hello failed: ${it.message}"), evidence, SystemClock.elapsedRealtime() - t0)
            }
            ticks += "tick=$tickCount t=${System.currentTimeMillis()} hello=$h pid=${svc.pid()}"
            kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
        }
        evidence["tickCount"] = tickCount.toString()
        evidence["firstTick"] = ticks.firstOrNull().orEmpty()
        evidence["lastTick"] = ticks.lastOrNull().orEmpty()
        conn.unbindSafe()
        val ms = SystemClock.elapsedRealtime() - t0
        ExperimentResult.ok("PKG-06", runId, evidence, ms, code = "SMOKE_OK")
    }

    private fun currentRunId(): String = PkgRunIds.current()

    companion object {
        private const val TAG = "PkgRunner"
        private const val BIND_TIMEOUT_MS = 30_000L
        private const val DEATH_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}

/** Manages binding to the :pkg child with a Binder death recipient. */
private class PkgConnection(private val context: Context) {
    /** The AIDL proxy to the :pkg service; null until connected. */
    var service: IPkgIsolation? = null
        private set
    private var conn: ServiceConnection? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var boundBinder: IBinder? = null

    suspend fun bind(): PkgConnection {
        val intent = Intent(context, PkgIsolationService::class.java)
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundBinder = binder
                // Cross-process: convert the IBinder to an AIDL proxy. A plain
                // LocalBinder cast would fail across processes.
                service = IPkgIsolation.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        context.bindService(intent, conn!!, Context.BIND_AUTO_CREATE)
        // Wait until the binder proxy is delivered.
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (service == null && SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        return this
    }

    fun attachDeathRecipient(onDeath: () -> Unit) {
        val dr = IBinder.DeathRecipient { onDeath() }
        deathRecipient = dr
        // The proxy's underlying IBinder lives in :pkg; its death == the child
        // process death (the deterministic PKG-02 abort() tears it down).
        boundBinder?.linkToDeath(dr, 0)
    }

    fun unbindSafe() {
        runCatching { deathRecipient?.let { boundBinder?.unlinkToDeath(it, 0) } }
        runCatching { conn?.let { context.unbindService(it) } }
        service = null
        conn = null
    }
}
