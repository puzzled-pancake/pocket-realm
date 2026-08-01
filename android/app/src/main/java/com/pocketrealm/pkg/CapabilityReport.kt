package com.pocketrealm.pkg

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.Os
import android.system.OsConstants
import androidx.core.content.getSystemService
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

            // App GLES strings require a current EGL context; calling glGetString
            // without one can crash native-side, so we do NOT probe GLES here.
            // The host driver records the host/emulator graphics config; a future
            // surface-backed probe can populate these from a real GL context.
            val glVendor: String? = null
            val glRenderer: String? = null
            val glVersion: String? = null
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
    }
}
