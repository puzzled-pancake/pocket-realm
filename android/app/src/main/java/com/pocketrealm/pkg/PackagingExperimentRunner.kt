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
 * Runs the packaging experiments and
 * returns typed [ExperimentResult]s. Used both by the in-app Capability screen
 * and (primarily) by the host driver `tools/run_pkg_experiments.py` via the
 * instrumented test that surfaces results to logcat.
 *
 * Design:
 *  - The launcher experiment executes the APK-packaged PIE launcher (`libpocket_pkg_launcher.so`,
 *    renamed so AGP extracts it under the experiment variant) from its
 *    nativeLibraryDir path. It is a real process exec, not System.loadLibrary.
 *    Captures stdout/stderr/exit/resolved-path/dladdr.
 *  - The containment experiment binds the `:pkg` child, has it load the real realm shared object
 *    by SONAME, confirms hello, triggers a deterministic abort(), then proves
 *    containment: :main PID alive, child PID gone, Binder death observed, and a
 *    fresh PID answers hello after restart.
 *  - The smoke experiment loads every .so and runs a long heartbeat; the genuine 30-minute
 *    acceptance runs are driven by the host driver, not by this UI path.
 *
 * All native code is packaged by the Gradle stageNativeLibs task under
 * lib/x86_64/.
 */
class PackagingExperimentRunner(private val context: Context) {

    /**
     * Launcher experiment: execute the launcher and capture its structured stdout/stderr.
     *
     * All product and qualification variants use the proven extracted
     * packaging model: the launcher is extracted into nativeLibraryDir with
     * +x. It MUST exec and
     *    dlopen libpocketrealm.so. We set LD_LIBRARY_PATH=nativeLibraryDir so the
     *    standalone exec's dynamic linker can find the app libraries (a bare exec
     *    does not inherit the app's linker namespace). This is the documented
     *    requirement and the basis for the Lane-A decision.
     */
    suspend fun runPkg01(launcherRelName: String = "libpocket_pkg_launcher.so"): ExperimentResult =
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val runId = currentRunId()
            val ai = context.applicationInfo
            val nativeDir = ai.nativeLibraryDir
            val launcher = if (nativeDir.isNullOrEmpty()) null else File(nativeDir, launcherRelName)
            val extractNative = (ai.flags and android.content.pm.ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0)

            val evidence = linkedMapOf<String, String>()
            evidence["variant"] = variantLabel()
            evidence["nativeLibraryDir"] = nativeDir ?: "(null)"
            evidence["extractNativeLibs"] = extractNative.toString()

            if (launcher == null || !launcher.isFile) {
                return@withContext ExperimentResult.fail(
                    "PKG-01", runId, "NO_EXECUTABLE_FS_PATH",
                    listOf(
                        "Every runtime variant requires extracted native executables.",
                        "The launcher is absent from nativeLibraryDir (extractNativeLibs=$extractNative).",
                    ),
                    evidence,
                    SystemClock.elapsedRealtime() - t0,
                )
            }

            // Make sure it is executable; extraction should set +x but be explicit.
            if (!launcher.canExecute()) runCatching { launcher.setExecutable(true) }
            evidence["launcherPath"] = launcher.absolutePath
            evidence["launcherCanExecute"] = launcher.canExecute().toString()
            // The standalone exec does NOT inherit the app linker namespace, so
            // the launcher's dlopen("libpocketrealm.so") cannot find the app
            // libraries unless LD_LIBRARY_PATH points at nativeLibraryDir.
            evidence["ldLibraryPath"] = nativeDir.orEmpty()

            val pb = ProcessBuilder(launcher.absolutePath).redirectErrorStream(false)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            val proc = runCatching { pb.start() }.getOrElse {
                return@withContext ExperimentResult.fail(
                    "PKG-01", runId, "EXEC_FAILED",
                    detail = listOf("ProcessBuilder.start() threw: ${it.javaClass.simpleName}: ${it.message}"),
                    evidence = evidence, ms = SystemClock.elapsedRealtime() - t0
                )
            }
            // Drain stdout/stderr on background threads so a blocked launcher
            // cannot deadlock the wait. The wait itself uses the timed
            // Process.waitFor(timeout) overload in a polling loop: the coroutine
            // cancellation of withTimeoutOrNull { waitFor() } does NOT interrupt
            // Java's blocking waitFor(), so a hung launcher would pin the thread
            // until the process died on its own. The timed overload returns false
            // on timeout, after which we destroyForcibly() to reclaim the PID.
            val outRef = java.util.concurrent.atomic.AtomicReference("")
            val errRef = java.util.concurrent.atomic.AtomicReference("")
            val outT = Thread { outRef.set(runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            val errT = Thread { errRef.set(runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")) }
            outT.isDaemon = true; errT.isDaemon = true
            outT.start(); errT.start()
            var timedOut = false
            var exit: Int? = null
            val waitDeadline = SystemClock.elapsedRealtime() + EXEC_TIMEOUT_MS
            while (exit == null && SystemClock.elapsedRealtime() < waitDeadline) {
                if (proc.waitFor(WAIT_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exit = proc.exitValue()
                }
            }
            if (exit == null) {
                timedOut = true
                runCatching { proc.destroyForcibly() }
                runCatching { proc.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS) }
                exit = runCatching { proc.exitValue() }.getOrNull()
            }
            runCatching { outT.join(2_000) }; runCatching { errT.join(2_000) }
            val out = outRef.get()
            val err = errRef.get()
            val ms = SystemClock.elapsedRealtime() - t0
            evidence["stdout"] = out
            evidence["stderr"] = err
            evidence["exitCode"] = if (timedOut) "TIMEOUT" else (exit?.toString() ?: "NO_EXIT")
            val sawHello = out.contains("PKG_LAUNCHER_HELLO")
            val sawOk = out.contains("PKG_LAUNCHER_OK")
            val realmPath = Regex("realm_path\t(.+)").find(out)?.groupValues?.get(1)?.trim()
            if (!realmPath.isNullOrEmpty()) evidence["realmDladdrPath"] = realmPath
            val pageLine = Regex("page_size\t(\\d+)").find(out)?.groupValues?.get(1)?.trim()
            if (!pageLine.isNullOrEmpty()) evidence["launcherPageSize"] = pageLine

            if (!timedOut && exit == 0 && sawHello && sawOk) {
                ExperimentResult.ok("PKG-01", runId, evidence, ms,
                    code = if (realmPath.isNullOrEmpty()) "OK_NO_DLADDR" else "OK")
            } else {
                ExperimentResult.fail("PKG-01", runId, "NONZERO_OR_MISSING_MARKERS",
                    detail = listOf("exit=$exit timeout=$timedOut hello=$sawHello ok=$sawOk"),
                    evidence = evidence, ms = ms)
            }
        }

    private fun variantLabel(): String = BuildConfig.BUILD_TYPE
        // BUILD_TYPE still distinguishes the historical qualification variant.
        // NOTE:
        // BuildConfig.DEBUG is true for BOTH debug and pkgExperiment (the latter
        // is initWith(debug)), so it cannot distinguish them — BUILD_TYPE can.

    /** Containment experiment: isolated child loads real realm .so, crashes, and survives a restart. */
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
        // @Volatile: the flag is written on a Binder thread and read here.
        val deathObserved = java.util.concurrent.atomic.AtomicBoolean(false)
        conn.attachDeathRecipient { deathObserved.set(true) }
        try { svc1.crash(PkgNative.CRASH_ABORT) } catch (_: Throwable) { /* expected: IPC broken */ }

        // 5. Wait for death notification (the child process dies).
        val died = withTimeoutOrNull(DEATH_TIMEOUT_MS) {
            while (!deathObserved.get()) kotlinx.coroutines.delay(100)
            true
        }
        evidence["binderDeathObserved"] = deathObserved.get().toString()
        evidence["mainPidStillAlive"] = (Process.myPid() == mainPid).toString()
        conn.unbindSafe()

        if (died == null || !deathObserved.get()) {
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
     * Smoke run: enumerate EVERY native library packaged in the APK and prove
     * each loads (via the :pkg child's RTLD_NOLOAD-then-RTLD_NOW probe), then
     * heartbeat for [durationSeconds]. Each tick is printed to logcat
     * (tick lines) so the full per-tick history is captured by the host driver.
     *
     * Enumeration reads the APK's own `lib/<abi>/` `.so` entries directly (not
     * nativeLibraryDir, which is empty under the production variant), so it works
     * under BOTH packaging variants. The launcher (`libpocket_pkg_launcher.so`)
     * is a PIE executable with no DT_SONAME and is LISTED but excluded from the
     * load set (it is a launcher artifact, not a dlopen target).
     *
     * The genuine 30-minute run is driven by the host driver with a large
     * durationSeconds; the deterministic instrumented test uses a short one.
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

        // Enumerate the packaged native libraries straight from the APK(s).
        val entries = mutableListOf<String>()
        for ((apk, abi) in packagedNativeApks()) {
            enumerateApkLibs(apk, abi)?.let { entries.addAll(it) }
        }
        val distinct = entries.distinct().sorted()
        evidence["packagedLibCount"] = distinct.size.toString()
        evidence["packagedLibs"] = distinct.joinToString(",")
        // The launcher is a .so-named PIE executable: no DT_SONAME, not a dlopen
        // target. List it as excluded rather than falsely "loading" it.
        val launcherSoname = "libpocket_pkg_launcher.so"
        val (loadable, excluded) = distinct.partition { it != launcherSoname }
        if (excluded.isNotEmpty()) {
            evidence["excludedLibs"] = excluded.joinToString(",") { "$it=EXCLUDED_EXECUTABLE" }
            evidence["excludedLibsNote"] = "${excluded.joinToString()} are PIE executables with no DT_SONAME; loadable libraries are probed, not these."
        }
        // Prove every loadable .so loads (RTLD_NOLOAD then RTLD_NOW) and record
        // each resolved path. This is the closure check: the realm facade plus
        // libc++_shared.so, libandroidx.graphics.path.so,
        // libdatastore_shared_counter.so, and libpocketpkgtest.so all must load.
        val perLib = linkedMapOf<String, String>()
        var allLoaded = true
        for (soname in loadable) {
            val info = runCatching { svc.probeSoBySoname(soname) }.getOrNull()
            if (info == null || !info.isLoaded) {
                allLoaded = false
                perLib[soname] = "FAIL err=${info?.err ?: -1}"
            } else {
                perLib[soname] = "OK path=${info.path} base=0x${info.baseAddr.toString(16)}"
            }
        }
        evidence["perLibProbeCount"] = perLib.size.toString()
        for ((soname, result) in perLib) evidence["lib:$soname"] = result
        // Also exercise the realm facade by its dedicated SONAME loader (the containment
        // path) so the realm symbol + base are recorded consistently per lane.
        val realmInfo = svc.loadRealmSoBySoname()
        evidence["realmLoaded"] = realmInfo.loaded.toString()
        evidence["realmSoname"] = realmInfo.soname
        evidence["realmDladdrPath"] = realmInfo.path
        if (!allLoaded || !realmInfo.isLoaded) {
            val failedLibs = perLib.filter { it.value.startsWith("FAIL") }.keys.toMutableList()
            if (!realmInfo.isLoaded) failedLibs.add(realmInfo.soname)
            conn.unbindSafe()
            return@withContext ExperimentResult.fail("PKG-06", runId, "LIB_NOT_LOADED",
                listOf("Failed to load: $failedLibs"), evidence, SystemClock.elapsedRealtime() - t0)
        }

        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1000
        var tickCount = 0
        var lastErr: Throwable? = null
        // Heartbeat: every tick re-probes page size and liveness, proving the
        // :pkg process and the loaded libraries stay resident. Every tick logged.
        while (SystemClock.elapsedRealtime() < deadline) {
            tickCount++
            val tickResult = runCatching { Triple(svc.hello(), svc.pid(), svc.probePageSize()) }
            if (tickResult.isFailure) {
                lastErr = tickResult.exceptionOrNull()
                break
            }
            val (h, pid, ps) = tickResult.getOrThrow()
            println("PKG-06 TICK\ttick=$tickCount\tt=${System.currentTimeMillis()}\thello=$h\tpid=$pid\tpageSize=$ps")
            if (tickCount == 1) evidence["firstTick"] = "tick=1 hello=$h pid=$pid"
            kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
        }
        evidence["tickCount"] = tickCount.toString()
        conn.unbindSafe()
        val ms = SystemClock.elapsedRealtime() - t0
        if (lastErr != null) {
            ExperimentResult.fail("PKG-06", runId, "TICK_HELLO_FAILED",
                listOf("tick $tickCount failed: ${lastErr!!.message}"), evidence, ms)
        } else {
            ExperimentResult.ok("PKG-06", runId, evidence, ms, code = "SMOKE_OK")
        }
    }

    /** The APK(s) carrying native libs for the active ABI (base + splits). */
    private fun packagedNativeApks(): List<Pair<String, String>> {
        val ai = context.applicationInfo
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "x86_64"
        val paths = mutableListOf<String>()
        ai.sourceDir?.let { paths.add(it) }
        ai.splitSourceDirs?.let { paths.addAll(it) }
        return paths.map { it to abi }
    }

    /** `lib/<abi>/` `.so` entry names inside [apkPath]; null if unreadable. */
    private fun enumerateApkLibs(apkPath: String, abi: String): List<String>? = runCatching {
        java.util.zip.ZipFile(File(apkPath)).use { zf ->
            zf.entries().toList()
                .filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                .map { it.name.substringAfterLast('/') }
        }
    }.getOrNull()

    private fun currentRunId(): String = PkgRunIds.current(context)

    companion object {
        private const val TAG = "PkgRunner"
        private const val BIND_TIMEOUT_MS = 30_000L
        private const val DEATH_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val EXEC_TIMEOUT_MS = 30_000L
        private const val WAIT_POLL_MS = 500L  // slice for the timed waitFor() loop
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
        // process death (the deterministic abort() tears it down).
        boundBinder?.linkToDeath(dr, 0)
    }

    fun unbindSafe() {
        runCatching { deathRecipient?.let { boundBinder?.unlinkToDeath(it, 0) } }
        runCatching { conn?.let { context.unbindService(it) } }
        service = null
        conn = null
    }
}
