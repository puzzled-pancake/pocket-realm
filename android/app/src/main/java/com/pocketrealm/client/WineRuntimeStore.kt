package com.pocketrealm.client

import android.content.Context
import android.os.SystemClock
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Immutable-code / mutable-data staging used by the production runtime. */
internal class WineRuntimeStore(private val context: Context) {
    data class Prepared(
        val clientId: String,
        val prefixId: String,
        val root: File,
        val tree: File,
        val prefix: File,
        val cache: File,
        val tmp: File,
        val executable: File,
        val workingDir: File,
        val selfTest: Boolean,
    )

    init { WineSpikeNative.load() }

    fun paths(clientId: String): Prepared {
        val selfTest = clientId == ClientRuntimeContract.SELF_TEST_ID
        val managed = if (selfTest) null else ManagedClientStore(context).load(clientId)
        val root = File(
            context.noBackupFilesDir,
            // AF_UNIX sun_path is only 108 bytes on Linux. Keep the physical
            // generation name compact; the full pinned build/client identity
            // remains in prefix-manifest.json and prefixId.
            "wine/w11w64-v1/${if (selfTest) "selftest" else "wow5875"}/p${ClientRuntimeContract.PREFIX_SCHEMA}",
        )
        return Prepared(
            clientId = clientId,
            prefixId = listOfNotNull(
                ClientRuntimeContract.RUNTIME_BUILD_ID,
                ClientRuntimeContract.RENDERER_BUILD_ID.takeUnless { selfTest },
                clientId,
                ClientRuntimeContract.PREFIX_SCHEMA.toString(),
            ).joinToString(":"),
            root = root,
            tree = File(root, "wine-tree"),
            prefix = File(root, "wine-prefix"),
            cache = File(root, "wine-pe-cache"),
            tmp = File(root, "tmp"),
            executable = managed?.executable ?: File(root, "wine-tree/pocket_selftest.exe"),
            workingDir = managed?.root ?: root,
            selfTest = selfTest,
        )
    }

    fun prepare(clientId: String, renderer: String, audioMode: String): Prepared {
        require(renderer == "wined3d") { "O06 qualifies WineD3D only" }
        require(audioMode == "off") { "O06 requires the audio-off diagnostic profile" }
        val p = paths(clientId)
        p.root.mkdirs(); p.prefix.mkdirs(); p.tmp.mkdirs()
        ensureSharedCaches(p)
        File(p.tmp, ".X11-unix").mkdirs()

        val staging = readAsset("staging-manifest.json")
        check(WineSpikeNative.buildSymlinkTreeNative(
            p.tree.absolutePath, context.applicationInfo.nativeLibraryDir, staging) == 0) {
            "Wine logical tree could not be built"
        }
        prepareData(p)
        materializePeCaches(p)

        val manifestFile = File(p.root, "prefix-manifest.json")
        val compatible = manifestFile.isFile && runCatching {
            val old = JSONObject(manifestFile.readText())
            old.getString("runtime_build_id") == ClientRuntimeContract.RUNTIME_BUILD_ID &&
                (p.selfTest || old.optString("renderer_build_id") == ClientRuntimeContract.RENDERER_BUILD_ID) &&
                old.getInt("prefix_schema") == ClientRuntimeContract.PREFIX_SCHEMA &&
                old.getString("windows_arch") == "win32-on-wow64" &&
                old.getString("renderer") == renderer && old.getString("audio_mode") == audioMode
        }.getOrDefault(false)

        if (!compatible && p.prefix.listFiles()?.isNotEmpty() == true) {
            val preserved = File(p.root, "wine-prefix-preserved-${System.currentTimeMillis()}")
            check(p.prefix.renameTo(preserved)) { "Incompatible prefix could not be preserved" }
            p.prefix.mkdirs()
            prunePreservedPrefixes(p.root)
        }

        if (!prefixReady(p.prefix, 1_000)) initializePrefix(p)
        check(prefixReady(p.prefix, 1_000)) { "Wine prefix did not become ready" }
        check(p.executable.isFile) { "Authorized client executable is absent" }
        if (!p.selfTest) enforceManagedSafeMode(p)

        val manifest = JSONObject()
            .put("runtime_build_id", ClientRuntimeContract.RUNTIME_BUILD_ID)
            .put("prefix_schema", ClientRuntimeContract.PREFIX_SCHEMA)
            .put("windows_arch", "win32-on-wow64")
            .put("renderer", renderer)
            .put("audio_mode", audioMode)
            .put("client_id", clientId)
            .put("code_location", "apk-nativeLibraryDir")
            .put("code_immutable", true)
            .put("prefix_quota_bytes", ClientRuntimeContract.PREFIX_QUOTA_BYTES)
            .put("preserved_prefix_quota_bytes", ClientRuntimeContract.PRESERVED_PREFIX_QUOTA_BYTES)
            .put("max_preserved_prefixes", ClientRuntimeContract.MAX_PRESERVED_PREFIXES)
            .put("cache_quota_bytes", ClientRuntimeContract.CACHE_QUOTA_BYTES)
            .put("log_quota_bytes", ClientRuntimeContract.LOG_QUOTA_BYTES)
        if (!p.selfTest) {
            val managed = ManagedClientStore(context).load(clientId)
            val identity = managed.manifest.getJSONObject("identity")
            manifest
                .put("renderer_build_id", ClientRuntimeContract.RENDERER_BUILD_ID)
                .put("renderer_provider", JSONObject()
                    .put("client", "gladio-eaa2a8d")
                    .put("client_sha256", "3344560af7565a32b25acbcd927a6b35cccbb5997f0280d444f87316b2510d06")
                    .put("server", "pocket-gladio-o07v1")
                    .put("server_sha256", "2d20db2c12b007b2251edce9421264ea168da0bb463718d9baa8f2c02403584f")
                    .put("api", "OpenGL 3.0 / GLSL 1.30 over GLES 3.0")
                    .put("internal_format_queries", true)
                    .put("modern_instancing", false))
                .put("client_executable_sha256", identity.getString("sha256"))
                .put("client_version", identity.getString("version"))
                .put("working_directory", "app-private-managed-client")
                .put("safe_profile", JSONObject()
                    .put("resolution_ceiling", "1280x720")
                    .put("qualified_effective_resolution", "800x600")
                    .put("fps_cap", 30)
                    .put("audio", "off")
                    .put("realm_endpoint", "127.0.0.1")
                    .put("addons", "off"))
                .put("known_deviations", JSONArray()
                    .put("GLES shader target is 300 es")
                    .put("renderer advertises a constrained GL 3.0 capability subset")
                    .put("texture copy uses GLES readback/upload instead of glCopyTexImage2D"))
        }
        writeAtomic(manifestFile, manifest.toString(2))
        enforceQuotas(p)
        return p
    }

    private fun initializePrefix(p: Prepared) {
        val wineboot = File(p.tree, "lib/wine/x86_64-windows/wineboot.exe")
        check(wineboot.isFile) { "wineboot.exe is not cache-backed" }
        val raw = WineSpikeNative.runWineDirectNative(
            context.applicationInfo.nativeLibraryDir, wineboot.absolutePath,
            p.prefix.absolutePath, p.prefix.absolutePath, "", "--init",
            "LD_DEBUG=;WINEDEBUG=-all;WINEDLLOVERRIDES=winex11.drv=d",
            60_000,
        )
        val result = parseWineRunResult(raw)
        check(result.rc == 0 && result.exitedCleanly && result.exitCode == 0 && !result.timedOut) {
            "wineboot failed: rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut} " +
                result.stderr.takeLast(800)
        }
        check(prefixReady(p.prefix)) { "wineboot registry transaction did not stabilize" }
        val wineserver = File(p.tree, "bin/wineserver")
        for (arg in listOf("-k", "-w")) {
            val serverResult = parseWineRunResult(WineSpikeNative.runWineViaProotNative(
                context.applicationInfo.nativeLibraryDir, wineserver.absolutePath, "wineserver",
                p.prefix.absolutePath, "", arg, "", 15_000,
            ))
            check(serverResult.exitedCleanly && serverResult.exitCode == 0 && !serverResult.timedOut) {
                "wineserver $arg failed"
            }
        }
        linkBuiltins(p)
    }

    private fun enforceManagedSafeMode(p: Prepared) {
        check(p.clientId == ClientRuntimeContract.WOW_5875_ID) { "safe profile target mismatch" }
        val realmlist = "set realmlist 127.0.0.1\r\n"
        val config = """SET readTOS "1"
SET readEULA "1"
SET readScanning "1"
SET movie "0"
SET gxResolution "1280x720"
SET gxWindow "1"
SET gxMaximize "0"
SET gxVSync "0"
SET gxMultisample "1"
SET gxMultisampleQuality "0.000000"
SET maxFPS "30"
SET Sound_EnableAllSound "0"
SET Sound_EnableMusic "0"
SET Sound_EnableSFX "0"
SET Sound_EnableAmbience "0"
SET ffxGlow "0"
SET ffxDeath "0"
SET farclip "177"
SET realmName "MaNGOS"
""".replace("\n", "\r\n")
        writeAtomic(File(p.workingDir, "realmlist.wtf"), realmlist)
        File(p.workingDir, "WTF").mkdirs()
        writeAtomic(File(p.workingDir, "WTF/Config.wtf"), config)
        val record = JSONObject()
            .put("schema", 1).put("client_id", p.clientId)
            .put("renderer", "wined3d").put("resolution", "1280x720")
            .put("fps_cap", 30).put("audio", "off")
            .put("realm_endpoint", "127.0.0.1").put("realm_name", "MaNGOS").put("addons", "off")
            .put("passwords_stored", false).put("source_modified", false)
        writeAtomic(File(p.workingDir, "managed-safe-profile.json"), record.toString(2))
    }

    private fun materializePeCaches(p: Prepared) {
        val extracted = File(context.cacheDir, "client-runtime-assets")
        if (extracted.exists()) extracted.deleteRecursively()
        extracted.mkdirs()
        extract("wine-pe", File(extracted, "wine-pe"))
        extract("guest-pe", File(extracted, "guest-pe"))
        copyAsset("wine-pe-manifest.json", File(extracted, "wine-pe-manifest.json"))
        copyAsset("guest-pe-manifest.json", File(extracted, "guest-pe-manifest.json"))
        val peManifest = readAsset("wine-pe-manifest.json")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            p.cache.absolutePath, peManifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(p.cache.absolutePath, peManifest) == 0)
        val guestManifest = readAsset("guest-pe-manifest.json")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            p.cache.absolutePath, guestManifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(p.cache.absolutePath, guestManifest) == 0)
        // Extraction is transient input to the canonical hash-verified cache.
        // Keeping it would duplicate ~600 MiB in cacheDir without an owner.
        extracted.deleteRecursively()
    }

    /** PE/data caches are immutable and keyed by RUNTIME_BUILD_ID, not by the
     * proprietary client. Prefixes stay isolated; only reproducible caches are
     * shared. This also migrates the legacy O06 per-selftest cache layout. */
    private fun ensureSharedCaches(p: Prepared) {
        val generationRoot = File(context.noBackupFilesDir, "wine/w11w64-v1")
        val sharedRoot = File(generationRoot, "shared").apply { mkdirs() }
        val legacySelfTest = File(generationRoot, "selftest/p${ClientRuntimeContract.PREFIX_SCHEMA}")
        for (name in listOf("wine-pe-cache", "wine-data-cache")) {
            val shared = File(sharedRoot, name)
            val legacy = File(legacySelfTest, name)
            if (!shared.exists()) {
                if (legacy.isDirectory && !Files.isSymbolicLink(legacy.toPath())) {
                    check(legacy.renameTo(shared)) { "legacy $name cache migration failed" }
                } else shared.mkdirs()
            }
            val link = File(p.root, name)
            val correct = Files.isSymbolicLink(link.toPath()) &&
                Files.readSymbolicLink(link.toPath()) == shared.toPath()
            if (!correct) {
                if (Files.isSymbolicLink(link.toPath())) Files.delete(link.toPath())
                else if (link.exists()) link.deleteRecursively()
                Files.createSymbolicLink(link.toPath(), shared.toPath())
            }
        }
    }

    private fun prepareData(p: Prepared) {
        val extracted = File(context.cacheDir, "client-runtime-data")
        if (extracted.exists()) extracted.deleteRecursively()
        extracted.mkdirs()
        extract("wine-data", File(extracted, "wine-data"))
        copyAsset("wine-data-manifest.json", File(extracted, "wine-data-manifest.json"))
        val manifest = readAsset("wine-data-manifest.json")
        val cache = File(p.root, "wine-data-cache")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            cache.absolutePath, manifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(cache.absolutePath, manifest) == 0)
        val alias = File(context.applicationInfo.dataDir, "wine").toPath()
        val target = File(cache, "wine-data").toPath()
        if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isSymbolicLink(alias) || Files.readSymbolicLink(alias) != target)) {
            Files.delete(alias)
        }
        if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(alias, target)
        extracted.deleteRecursively()
    }

    private fun linkBuiltins(p: Prepared) {
        val system32 = File(p.prefix, "drive_c/windows/system32").apply { mkdirs() }
        val syswow64 = File(p.prefix, "drive_c/windows/syswow64").apply { mkdirs() }
        val entries = JSONObject(readAsset("wine-pe-manifest.json")).getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val destinationDir = when (entry.getString("arch")) {
                "x86_64-windows" -> system32
                "i386-windows" -> syswow64
                else -> continue
            }
            val source = File(p.cache, entry.getString("asset_path"))
            val destination = File(destinationDir, source.name).toPath()
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(destination, source.toPath())
            }
        }
    }

    private fun prefixReady(prefix: File, timeoutMs: Long = 30_000): Boolean {
        val required = listOf(".update-timestamp", "system.reg", "user.reg", "userdef.reg")
            .map { File(prefix, it) }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = ""; var stable = 0
        do {
            val ready = required.all { it.isFile && it.length() > 0 } &&
                File(prefix, "dosdevices").isDirectory && File(prefix, "drive_c/windows").isDirectory
            val signature = if (ready) required.joinToString { "${it.length()}:${it.lastModified()}" } else ""
            stable = if (ready && signature == previous) stable + 1 else 0
            if (stable >= 4 || (timeoutMs <= 1_000 && ready)) return true
            previous = signature
            Thread.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun enforceQuotas(p: Prepared) {
        val prefixBytes = sizeOf(p.prefix)
        val preserved = p.root.listFiles { file ->
            file.isDirectory && file.name.startsWith("wine-prefix-preserved-")
        }.orEmpty()
        val preservedBytes = preserved.sumOf(::sizeOf)
        val cacheBytes = sizeOf(File(context.noBackupFilesDir, "wine/w11w64-v1/shared"))
        check(prefixBytes <= ClientRuntimeContract.PREFIX_QUOTA_BYTES) { "prefix quota exceeded" }
        check(preserved.size <= ClientRuntimeContract.MAX_PRESERVED_PREFIXES) {
            "preserved prefix generation limit exceeded"
        }
        check(preservedBytes <= ClientRuntimeContract.PRESERVED_PREFIX_QUOTA_BYTES) {
            "preserved prefix quota exceeded"
        }
        check(cacheBytes <= ClientRuntimeContract.CACHE_QUOTA_BYTES) { "cache quota exceeded" }
    }

    private fun prunePreservedPrefixes(root: File) {
        val preserved = root.listFiles { file ->
            file.isDirectory && file.name.startsWith("wine-prefix-preserved-")
        }.orEmpty().sortedByDescending { it.lastModified() }
        preserved.drop(ClientRuntimeContract.MAX_PRESERVED_PREFIXES).forEach { it.deleteRecursively() }
    }

    private fun sizeOf(root: File): Long {
        if (!root.exists()) return 0
        // A Wine prefix contains dosdevices/z: -> /. Kotlin FileTreeWalk can
        // descend directory symlinks and accidentally charge the whole device
        // to the prefix. Files.walk does not follow links unless explicitly
        // asked, and regular-file checks are NOFOLLOW_LINKS.
        return Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { Files.size(it) }.sum()
        }
    }

    private fun readAsset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
    private fun copyAsset(name: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
    }
    private fun extract(prefix: String, destination: File) {
        destination.mkdirs()
        for (name in context.assets.list(prefix).orEmpty()) {
            val source = "$prefix/$name"
            val children = context.assets.list(source).orEmpty()
            if (children.isNotEmpty()) extract(source, File(destination, name))
            else copyAsset(source, File(destination, name))
        }
    }
    private fun writeAtomic(target: File, value: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(value)
        check(temp.renameTo(target)) { "atomic manifest replace failed" }
    }
}
