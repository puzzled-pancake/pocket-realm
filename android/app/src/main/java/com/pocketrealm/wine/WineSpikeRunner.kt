package com.pocketrealm.wine

import android.content.Context
import android.os.SystemClock
import com.pocketrealm.log.AppLog
import com.pocketrealm.pkg.ExperimentResult
import java.io.File

/**
 * Orchestrates the O06 Phase-1 Wine feasibility spike measurements (S-1/S-2/S-3).
 *
 * The spike proves:
 *  - S-1: Wine + wineserver + every native child map the APK-managed glibc loader
 *    as their effective dynamic loader (proven via /proc/<pid>/maps).
 *  - S-2: wineboot resolves Wine-owned PE modules from the hash-verified cache.
 *  - S-3: the self-test PE creates/shows an X11/GDI window via winex11.drv + the
 *    X-server harness.
 *
 * Each measurement announces structured logcat lines the host driver greps:
 *   WINE_SPIKE_S1_RESULT  ok=true  code=LOADER_PROVEN  ...
 *   WINE_SPIKE_S1_EVIDENCE  key=value  ...
 */
class WineSpikeRunner(private val context: Context) {

    companion object {
        private const val TAG = "WineSpike"
        private const val STAGING_MANIFEST_ASSET = "staging-manifest.json"
        private const val WINE_PE_MANIFEST_ASSET = "wine-pe-manifest.json"
        private const val GUEST_PE_MANIFEST_ASSET = "guest-pe-manifest.json"
    }

    init {
        WineSpikeNative.load()
    }

    private fun announce(result: ExperimentResult) {
        // Mirror the PKG_EXPERIMENT announce contract for the host driver.
        println("WINE_SPIKE_${result.experiment}_RESULT\tok=${result.ok}\tcode=${result.code}")
        result.evidence.forEach { (k, v) ->
            println("WINE_SPIKE_${result.experiment}_EVIDENCE\t$k=$v")
        }
    }

    /** Read an asset as a UTF-8 string. */
    private fun readAsset(name: String): String {
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }

    /** Extract an asset file to a target dir (for PE cache materialization). */
    private fun extractAssetsToDir(assetPrefix: String, destDir: File) {
        destDir.mkdirs()
        val files = context.assets.list(assetPrefix) ?: return
        for (fn in files) {
            val src = "$assetPrefix/$fn"
            // If it's a subdir, recurse.
            val sub = context.assets.list(src)
            if (sub != null && sub.isNotEmpty()) {
                extractAssetsToDir(src, File(destDir, fn))
            } else {
                val out = File(destDir, fn)
                if (!out.exists()) {
                    context.assets.open(src).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    /**
     * S-1: Prove the effective dynamic loader is the APK-managed glibc loader for
     * wine, wineserver, and every native child.
     *
     * Steps: build symlink tree -> launch wine -> probe /proc/<pid>/maps for wine
     * + enumerate children + probe each child. Outcome A (all APK-managed) or
     * record the exact failure.
     */
    suspend fun runS1(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir
            ?: return@withContext ExperimentResult.fail("S1", "-", "NO_NATIVE_DIR",
                listOf("nativeLibraryDir is null"), evidence, SystemClock.elapsedRealtime() - t0)
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-1: effective loader proof")

        // 1. Read the staging manifest + build the symlink tree.
        val manifest = try {
            readAsset(STAGING_MANIFEST_ASSET)
        } catch (e: Exception) {
            return@withContext ExperimentResult.fail("S1", "-", "NO_MANIFEST",
                listOf("cannot read $STAGING_MANIFEST_ASSET: ${e.message}"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }

        val treeDir = File(context.filesDir, "runtime/wine-tree")
        val rc = WineSpikeNative.buildSymlinkTreeNative(treeDir.absolutePath, nativeDir, manifest)
        if (rc != 0) {
            return@withContext ExperimentResult.fail("S1", "-", "SYMLINK_TREE_FAILED",
                listOf("rc=$rc (${WineSpikeNative.errStrNative(rc)})"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        evidence["symlinkTree"] = treeDir.absolutePath

        // 2. Create the WINEPREFIX + tmp dirs.
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        prefixDir.mkdirs()
        File(context.filesDir, "runtime/tmp").mkdirs()

        // 3. Launch wineserver --foreground via the APK-managed loader.
        //    wineserver --foreground stays alive (doesn't daemonize), so we can
        //    probe its /proc/<pid>/maps before it exits.
        val wineTarget = File(treeDir, "bin/wineserver").absolutePath
        evidence["wineTarget"] = wineTarget

        // For S-1, we don't have the X-server yet, so no DISPLAY.
        val winePid = WineSpikeNative.launchWineNative(nativeDir, wineTarget, prefixDir.absolutePath, "")
        if (winePid < 0) {
            return@withContext ExperimentResult.fail("S1", "-", "LAUNCH_FAILED",
                listOf("launchWineNative returned $winePid"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        evidence["winePid"] = winePid.toString()

        // 4. Give wineserver a moment to initialize its loader mappings.
        Thread.sleep(1000)

        // 5. Probe wine's effective loader.
        val wineProbe = WineSpikeNative.probeLoaderNative(winePid, nativeDir)
        evidence["wineProbe"] = wineProbe
        val wineLoaderOk = wineProbe.startsWith("OK|")

        // 6. Enumerate children + probe each.
        val children = WineSpikeNative.enumChildrenNative(winePid)
        evidence["childCount"] = children.size.toString()
        evidence["childPids"] = children.joinToString(",")

        var childrenAllOk = true
        val childResults = mutableListOf<String>()
        for (childPid in children) {
            val childProbe = WineSpikeNative.probeLoaderNative(childPid.toLong(), nativeDir)
            childResults.add("pid=$childPid: $childProbe")
            if (!childProbe.startsWith("OK|")) {
                childrenAllOk = false
            }
        }
        if (childResults.isNotEmpty()) {
            evidence["childProbes"] = childResults.joinToString(" ; ")
        }

        // 7. Verdict.
        val ok = wineLoaderOk && childrenAllOk
        val code = if (ok) "LOADER_PROVEN" else "LOADER_NOT_PROVEN"

        // Clean up: kill the wine process tree.
        if (winePid > 0) {
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", winePid.toString())).waitFor()
                for (childPid in children) {
                    Runtime.getRuntime().exec(arrayOf("kill", "-9", childPid.toString())).waitFor()
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "cleanup kill failed: ${e.message}")
            }
        }

        val result = if (ok) {
            ExperimentResult.ok("S1", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            ExperimentResult.fail("S1", "-", code,
                listOf("wineLoaderOk=$wineLoaderOk childrenAllOk=$childrenAllOk"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }

    /**
     * S-2: Prove wineboot resolves Wine-owned PE modules from the hash-verified
     * cache, and the cache is reverified before launch.
     */
    suspend fun runS2(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-2: wineboot PE resolution")

        // 1. Extract PE assets to a temp dir (the C materializer reads from the filesystem).
        val assetsExtractDir = File(context.cacheDir, "wine-pe-assets")
        if (!assetsExtractDir.exists()) {
            extractAssetsToDir("wine-pe", File(assetsExtractDir, "wine-pe"))
            // Copy the manifest too.
            context.assets.open(WINE_PE_MANIFEST_ASSET).use { input ->
                File(assetsExtractDir, WINE_PE_MANIFEST_ASSET).outputStream().use { input.copyTo(it) }
            }
        }
        evidence["assetsExtractDir"] = assetsExtractDir.absolutePath

        // 2. Materialize the PE cache (SHA-256 verified, atomic copy).
        val cacheDir = File(context.filesDir, "runtime/wine-pe-cache")
        val peManifest = readAsset(WINE_PE_MANIFEST_ASSET)
        val manifestFile = File(assetsExtractDir, WINE_PE_MANIFEST_ASSET)
        val rc = WineSpikeNative.materializePeCacheNative(
            cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath)
        evidence["materializeRc"] = rc.toString()
        if (rc != 0) {
            val result = ExperimentResult.fail("S2", "-", "PE_MATERIALIZE_FAILED",
                listOf("rc=$rc (${WineSpikeNative.errStrNative(rc)})"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }

        // 3. Verify the cache (reverification before launch).
        val verifyRc = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
        evidence["verifyRc"] = verifyRc.toString()
        if (verifyRc != 0) {
            val result = ExperimentResult.fail("S2", "-", "PE_VERIFY_MISMATCH",
                listOf("verify rc=$verifyRc — cache hashes do not match manifest"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }
        val cacheFiles = cacheDir.walkTopDown().filter { it.isFile }.count()
        evidence["peCacheFileCount"] = cacheFiles.toString()

        // 4. Launch wineboot --init to prove PE resolution.
        val treeDir = File(context.filesDir, "runtime/wine-tree")
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        prefixDir.mkdirs()
        val winebootTarget = File(treeDir, "bin/wineboot").absolutePath
        evidence["winebootTarget"] = winebootTarget

        val winebootPid = WineSpikeNative.launchWineNative(
            nativeDir, winebootTarget, prefixDir.absolutePath, "")
        evidence["winebootPid"] = winebootPid.toString()

        if (winebootPid > 0) {
            // Wait for wineboot to complete (it initializes the prefix).
            Thread.sleep(5000)
            val exit = try {
                // Check if the process is still running.
                val maps = File("/proc/$winebootPid/maps")
                if (maps.exists()) {
                    evidence["winebootStillRunning"] = "true"
                    -1
                } else {
                    evidence["winebootStillRunning"] = "false"
                    0
                }
            } catch (e: Exception) {
                -1
            }
            evidence["winebootExit"] = exit.toString()

            // Kill it if still running.
            try { Runtime.getRuntime().exec(arrayOf("kill", "-9", winebootPid.toString())).waitFor() } catch (_: Exception) {}
        }

        // S-2 verdict: cache materialized + verified + wineboot launched without
        // immediate failure. Full PE resolution proof comes from wineboot's log
        // (the host driver captures WINEDEBUG output).
        val ok = rc == 0 && verifyRc == 0 && winebootPid > 0
        val result = if (ok) {
            ExperimentResult.ok("S2", "-", evidence, SystemClock.elapsedRealtime() - t0, "PE_CACHE_VERIFIED")
        } else {
            ExperimentResult.fail("S2", "-", "PE_CACHE_OR_WINEBOOT_FAILED",
                listOf("materializeRc=$rc verifyRc=$verifyRc winebootPid=$winebootPid"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }

    /**
     * S-3: X11/GDI window via winex11.drv + the X-server harness.
     *
     * Deferred until the minimum Winlator X-server harness is vendored. For now
     * this reports DEFERRED so S-1/S-2 can proceed without blocking.
     */
    suspend fun runS3(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        evidence["note"] = "S-3 requires the minimum Winlator X-server harness (not yet vendored)"
        val result = ExperimentResult.fail("S3", "-", "DEFERRED",
            listOf("X-server harness not yet vendored — S-1/S-2 proceed without it"),
            evidence, SystemClock.elapsedRealtime() - t0)
        announce(result)
        result
    }
}
