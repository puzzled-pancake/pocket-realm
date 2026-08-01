package com.pocketrealm.pkg

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.Os
import android.system.OsConstants
import androidx.core.content.getSystemService
import java.io.File
import java.util.UUID

/**
 * The X0 capability snapshot (report §20.1 / §25.7 G0). Captured on-device and
 * compared against `adb` by the host driver. Per the O05 design, fields that are
 * not genuinely equivalent between app and adb are recorded SEPARATELY and never
 * forced equal:
 *  - [allocatableBytes] comes from `StorageManager.getAllocatableBytes()`; the
 *    adb `df` figure is recorded by the host driver as a distinct field.
 *  - [glVendor]/[glRenderer]/[glVersion] are the app's in-process GLES strings;
 *    the host/emulator graphics configuration is recorded separately.
 *
 * No "boot id": [testRunId] is a generated UUID persisted under runtime-state/,
 * explicitly a test-run identifier.
 */
data class CapabilityReport(
    val testRunId: String,
    val sdkInt: Int,
    val buildId: String?,
    val abilist: List<String>,
    val abilist32: List<String>,
    val abilist64: List<String>,
    val pageSizeBytes: Int,
    val totalRamBytes: Long,
    val allocatableBytes: Long,
    val allocatableVolumeLabel: String,
    val glVendor: String?,
    val glRenderer: String?,
    val glVersion: String?,
    val vulkanFeature: Boolean,
    val packageName: String,
    val processName: String,
    val nativeLibraryDirObserved: String?,
    val capturedAtEpochMs: Long,
) {
    companion object {
        /**
         * Probe device + process capabilities. [nativeLibraryDirObserved] is the
         * applicationInfo.nativeLibraryDir (may be null/empty under the
         * production packaging variant); the host driver records the dladdr
         * path separately for the PKG experiments.
         */
        @Suppress("DEPRECATION")
        fun probe(context: Context, testRunId: String): CapabilityReport {
            val am = context.getSystemService<ActivityManager>()
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

            val abi = Build.SUPPORTED_ABIS.toList()
            val abi32 = Build.SUPPORTED_32_BIT_ABIS.toList()
            val abi64 = Build.SUPPORTED_64_BIT_ABIS.toList()

            val pageSize = Os.sysconf(OsConstants._SC_PAGE_SIZE).toInt()

            // Allocatable bytes via StorageManager (report's "allocatable
            // storage"), distinct from a raw df free count. Resolved against the
            // storage volume that backs noBackupFilesDir so the UUID maps to a
            // real mount; falls back to UUID_DEFAULT if the volume lookup fails.
            val sm = context.getSystemService<StorageManager>()
            var allocatable = 0L
            var label = ""
            runCatching {
                val vol = sm?.getStorageVolume(context.noBackupFilesDir)
                val uuid = try {
                    vol?.let { it.javaClass.getMethod("getUuid").invoke(it) as? java.util.UUID }
                } catch (_: Throwable) { null }
                    ?: android.os.storage.StorageManager.UUID_DEFAULT
                allocatable = sm?.getAllocatableBytes(uuid) ?: 0L
                label = "noBackupFilesDir"
            }

            // App GLES strings: create a short-lived EGL context so glGetString
            // has a current context (calling it without one is undefined / can
            // crash native-side). These are the app's in-process GL strings and
            // are recorded separately from the host/emulator graphics config.
            val glStrings = probeGlStrings()
            val glVendor = glStrings?.get(0)
            val glRenderer = glStrings?.get(1)
            val glVersion = glStrings?.get(2)
            val hasVulkan = runCatching {
                context.packageManager.getSystemAvailableFeatures()
                    ?.any { it.name == "android.hardware.vulkan.level" || it.name == "android.hardware.vulkan.compute" || it.name == "android.hardware.vulkan.version" }
            }.getOrNull() ?: false

            val ai = context.applicationInfo
            val procName = runCatching {
                am?.runningAppProcesses?.firstOrNull { it.processName == context.packageName }?.processName
            }.getOrNull() ?: context.packageName

            return CapabilityReport(
                testRunId = testRunId,
                sdkInt = Build.VERSION.SDK_INT,
                buildId = Build.ID,
                abilist = abi,
                abilist32 = abi32,
                abilist64 = abi64,
                pageSizeBytes = pageSize,
                totalRamBytes = mem.totalMem,
                allocatableBytes = allocatable,
                allocatableVolumeLabel = label,
                glVendor = glVendor,
                glRenderer = glRenderer,
                glVersion = glVersion,
                vulkanFeature = hasVulkan,
                packageName = context.packageName,
                processName = procName,
                nativeLibraryDirObserved = ai.nativeLibraryDir?.ifEmpty { null },
                capturedAtEpochMs = System.currentTimeMillis(),
            )
        }

        /**
         * Create a short-lived EGL 1.4 context, make it current, and read the
         * GL vendor/renderer/version strings. Returns null if EGL is unavailable
         * (e.g. a headless instrumented process without a display). All EGL
         * resources are torn down before returning.
         */
        private fun probeGlStrings(): List<String>? {
            return runCatching {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == null || display == EGL14.EGL_NO_DISPLAY) return@runCatching null
                val vers = IntArray(2)
                if (!EGL14.eglInitialize(display, vers, 0, vers, 1)) return@runCatching null
                val cfgAttr = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val numCfg = IntArray(1)
                if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, numCfg, 0) || numCfg[0] == 0) {
                    EGL14.eglTerminate(display); return@runCatching null
                }
                val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttr, 0)
                if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                    EGL14.eglTerminate(display); return@runCatching null
                }
                val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
                if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroySurface(display, surface); EGL14.eglTerminate(display); return@runCatching null
                }
                EGL14.eglMakeCurrent(display, surface, surface, ctx)
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                val version = GLES20.glGetString(GLES20.GL_VERSION)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglTerminate(display)
                listOf(vendor ?: "", renderer ?: "", version ?: "")
            }.getOrNull()
        }
    }

    /** Serialize to JSON for the host driver to pull and compare. */
    fun toJson(): String {
        val e = { s: String -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        val arr = { xs: List<String> -> xs.joinToString(",", "[", "]") { e(it) } }
        return buildString {
            append("{")
            append("\"testRunId\":").append(e(testRunId)).append(",")
            append("\"sdkInt\":").append(sdkInt).append(",")
            append("\"buildId\":").append(e(buildId ?: "")).append(",")
            append("\"abilist\":").append(arr(abilist)).append(",")
            append("\"abilist32\":").append(arr(abilist32)).append(",")
            append("\"abilist64\":").append(arr(abilist64)).append(",")
            append("\"pageSizeBytes\":").append(pageSizeBytes).append(",")
            append("\"totalRamBytes\":").append(totalRamBytes).append(",")
            append("\"allocatableBytes\":").append(allocatableBytes).append(",")
            append("\"allocatableVolumeLabel\":").append(e(allocatableVolumeLabel)).append(",")
            append("\"glVendor\":").append(e(glVendor ?: "")).append(",")
            append("\"glRenderer\":").append(e(glRenderer ?: "")).append(",")
            append("\"glVersion\":").append(e(glVersion ?: "")).append(",")
            append("\"vulkanFeature\":").append(vulkanFeature).append(",")
            append("\"packageName\":").append(e(packageName)).append(",")
            append("\"processName\":").append(e(processName)).append(",")
            append("\"nativeLibraryDirObserved\":").append(e(nativeLibraryDirObserved ?: "")).append(",")
            append("\"capturedAtEpochMs\":").append(capturedAtEpochMs)
            append("}")
        }
    }

    /**
     * Write the report JSON to a file the host driver can `adb pull`. Returns
     * the file path. Placed under app-private files so it never holds secrets.
     */
    fun writeToFile(context: android.content.Context, name: String = "capability-report.json"): File {
        val f = File(context.getDir("capability", android.content.Context.MODE_PRIVATE).apply { mkdirs() }, name)
        val tmp = File(f.parentFile, "$name.tmp")
        tmp.writeText(toJson())
        tmp.renameTo(f)
        return f
    }
}
