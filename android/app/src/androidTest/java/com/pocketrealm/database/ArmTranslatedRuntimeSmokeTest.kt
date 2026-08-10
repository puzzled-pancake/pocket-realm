package com.pocketrealm.database

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/**
 * RP6 bring-up gate for the immutable Box64 executable and the pinned Winlator
 * rootfs.  The large rootfs tar is copied to app-private storage by the device
 * provisioning command; executable code still comes only from nativeLibraryDir.
 */
@RunWith(AndroidJUnit4::class)
class ArmTranslatedRuntimeSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun box64RunsPinnedWineVersion() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        DatabaseNative.load()

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val box64 = File(nativeDir, "libbox64.so")
        val runtime = File(context.noBackupFilesDir, "arm-translated/winlator-ca3d735")
        val rootfs = File(runtime, "rootfs")
        val tar = File(runtime, "rootfs.tar")
        val marker = File(rootfs, ".pocket-rootfs-ready")
        File(runtime, "run").mkdirs()
        rootfs.mkdirs()

        check(box64.isFile) { "APK-managed libbox64.so is missing" }
        if (!marker.isFile) {
            check(tar.isFile) {
                "Pinned rootfs tar is not provisioned at ${tar.absolutePath}"
            }
            val extracted = run(
                executable = File("/system/bin/tar"),
                argv0 = "tar",
                runtime = runtime,
                libraryPath = nativeDir,
                args = listOf("-xf", tar.absolutePath, "-C", rootfs.absolutePath),
                timeoutMs = 180_000,
            )
            check(extracted.ok) {
                "rootfs extraction failed exit=${extracted.exitCode}: ${extracted.stderr.takeLast(1200)}"
            }
            check(File(rootfs, "opt/wine/bin/wine").isFile) { "rootfs Wine executable is absent" }
            marker.writeText("winlator-ca3d735\n")
        }

        val wine = File(rootfs, "opt/wine/bin/wine")
        val lib = File(rootfs, "usr/lib")
        val x86Lib = File(rootfs, "lib/x86_64-linux-gnu")
        val loaderAlias = File(context.filesDir, "ld")
        val loaderTarget = File(lib, "ld-linux-aarch64.so.1").toPath()
        if (Files.exists(loaderAlias.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(loaderAlias.toPath())
        }
        Files.createSymbolicLink(loaderAlias.toPath(), loaderTarget)
        val rootAlias = File(context.filesDir, "rfs")
        if (Files.exists(rootAlias.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(rootAlias.toPath())
        }
        Files.createSymbolicLink(rootAlias.toPath(), rootfs.toPath())
        val home = File(rootfs, "home/xuser").apply { mkdirs() }
        val prefix = File(home, ".wine")
        val process = ProcessBuilder(box64.absolutePath, wine.absolutePath, "--version")
            .directory(runtime).apply {
                environment().clear()
                environment()["LD_LIBRARY_PATH"] = "${lib.absolutePath}:${nativeDir.absolutePath}"
                environment()["HOME"] = home.absolutePath
                environment()["USER"] = "xuser"
                environment()["TMPDIR"] = File(runtime, "run").absolutePath
                environment()["WINEPREFIX"] = prefix.absolutePath
                environment()["BOX64_NOBANNER"] = "0"
                environment()["BOX64_DYNAREC"] = "1"
                environment()["BOX64_LD_LIBRARY_PATH"] = "${x86Lib.absolutePath}:${lib.absolutePath}"
                environment()["BOX64_PATH"] = File(rootfs, "opt/wine/bin").absolutePath
            }.start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Box64/Wine smoke timed out" }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && stdout.contains("wine-")) {
            "Box64/Wine smoke failed exit=${process.exitValue()} " +
                "stdout=${stdout.takeLast(1000)} stderr=${stderr.takeLast(2000)}"
        }

        val evidence = JSONObject()
            .put("schema", 1)
            .put("model", Build.MODEL)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("box64", box64.name)
            .put("wineVersion", stdout.trim())
            .put("rootfs", "winlator-ca3d735")
            .put("qualified", false)
            .put("gate", "wine-version-smoke-only")
        File(context.filesDir, "arm-translated-wine-smoke.json")
            .writeText(evidence.toString(2) + "\n")
        assertTrue(stdout, stdout.contains("wine-"))
    }

    private fun run(
        executable: File,
        argv0: String,
        runtime: File,
        libraryPath: File,
        args: List<String>,
        environment: List<String> = emptyList(),
        timeoutMs: Int,
    ): DatabaseRunResult = DatabaseRunResult.parse(
        DatabaseNative.runBionicProgramNative(
            context.applicationInfo.nativeLibraryDir,
            executable.absolutePath,
            argv0,
            runtime.absolutePath,
            runtime.absolutePath,
            libraryPath.absolutePath,
            args.joinToString("\n"),
            environment.joinToString("\n"),
            "",
            timeoutMs,
            false,
        ),
    )
}
