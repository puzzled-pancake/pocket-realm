package com.pocketrealm.client

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Non-exported :client process. It owns Wine and every native child; the UI
 * process owns only the X server/surface and sends a versioned control protocol.
 */
class ClientRuntimeService : Service() {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WineRuntimeStore
    private lateinit var ownership: ComponentOwnership
    private var prepared: WineRuntimeStore.Prepared? = null
    private var session: SessionRecord? = null
    @Volatile private var armProcess: java.lang.Process? = null

    private data class SessionRecord(
        val id: UUID,
        val prepared: WineRuntimeStore.Prepared,
        val closeFile: File,
        var state: ClientState = ClientState.STARTING,
        var sequence: Long = 1,
        var detail: String = "launch accepted",
        var windowVisible: Boolean = false,
        var forced: Boolean = false,
        var cleanExit: Boolean = false,
        var stdout: String = "",
        var stderr: String = "",
    )

    override fun onCreate() {
        super.onCreate()
        store = WineRuntimeStore(applicationContext)
        ownership = ComponentOwnership("client") {
            Thread({
                runCatching { cancelActiveRuntime() }
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "client-owner-loss").start()
        }
        AppLog.i(TAG, "ClientRuntimeService started pid=${android.os.Process.myPid()}")
    }

    private val binder = object : IClientRuntimeControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder): String =
            guarded(instanceToken) { ownership.claim(sessionId, instanceToken, ownerLease) }

        override fun probe(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val clientId = request.getString("clientId")
            val provider = request.optString("provider", ClientRuntimeProvider.X86_DIRECT_WINE.id)
            val translator = ArmTranslationBackend.parse(request.getString("translator"))
            val x86Provider = provider == ClientRuntimeProvider.X86_DIRECT_WINE.id
            val armProvider = provider == ClientRuntimeProvider.ARM_TRANSLATED_WINE.id
            val providerSupported = x86Provider || armProvider
            val baseSupported = when {
                x86Provider -> Build.SUPPORTED_ABIS.contains("x86_64") && Build.VERSION.SDK_INT >= 26 &&
                    File(applicationInfo.nativeLibraryDir, "libwine_loader_preloader.so").isFile &&
                    File(applicationInfo.nativeLibraryDir, "libwine_spike.so").isFile
                armProvider -> Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
                    ArmTranslatedWineRuntime.isProviderMarkerPresent(applicationContext, translator)
                else -> false
            }
            var clientFailure: String? = null
            val clientSupported = when (clientId) {
                ClientRuntimeContract.SELF_TEST_ID -> true
                ClientRuntimeContract.WOW_5875_ID -> runCatching {
                    ManagedClientStore(applicationContext).load(clientId)
                }.onFailure { clientFailure = it.message ?: it.javaClass.simpleName }.isSuccess
                else -> false
            }
            val supported = baseSupported && clientSupported
            JSONObject()
                .put("ok", true).put("supported", supported)
                .put("provider", provider)
                .put("translator", translator.id)
                .put("runtimeBuildId", if (provider == ClientRuntimeProvider.ARM_TRANSLATED_WINE.id) {
                    ClientRuntimeContract.armRuntimeBuildId(translator)
                } else ClientRuntimeContract.RUNTIME_BUILD_ID)
                .put("immutableCode", true)
                .put("reason", when {
                    supported && armProvider ->
                        "ARM64 ${translator.id}/Wine runtime and authorized client available"
                    supported -> "x86_64 runtime and authorized client available"
                    !providerSupported -> "runtime provider unavailable: $provider"
                    !baseSupported -> "runtime/ABI unavailable"
                    clientFailure != null -> "managed client unavailable: $clientFailure"
                    else -> "unsupported client identity"
                })
                .put("requestedAbi", request.optString("abi"))
        }

        override fun preparePrefix(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            checkNoActiveSession()
            val translator = ArmTranslationBackend.parse(request.getString("translator"))
            val renderer = request.optString("renderer", "wined3d")
            val rendererPackageId = request.optionalString("rendererPackageId")
            if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && renderer == "dxvk") {
                requireNotNull(rendererPackageId) { "DXVK requires an explicit renderer package" }
            }
            val p = store.prepare(
                request.getString("clientId"), renderer,
                request.optString("audioMode", "off"),
                translator,
                request.optBoolean("inputSafeMode", false),
                rendererPackageId,
            )
            synchronized(lock) { prepared = p }
            JSONObject().put("ok", true).put("prefixId", p.prefixId)
                .put("runtimeRoot", p.root.absolutePath).put("prefixPath", p.prefix.absolutePath)
                .put("detail", "prefix ready and manifest-compatible")
        }

        override fun launch(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val p = synchronized(lock) {
                check(session == null || session!!.state in TERMINAL_STATES) { "a client session is already active" }
                checkNotNull(prepared) { "preparePrefix must succeed before launch" }
            }
            check(request.getString("prefixId") == p.prefixId) { "prefix identity mismatch" }
            check(ArmTranslationBackend.parse(request.getString("translator")) ==
                (p.armTranslator ?: ArmTranslationBackend.BOX64)) {
                "translator identity mismatch"
            }
            if (p.armRenderer != null) {
                check(request.optString("renderer") == p.armRenderer) {
                    "renderer identity mismatch"
                }
                check(request.optionalString("rendererPackageId") == p.armRendererPackageId) {
                    "renderer package identity mismatch"
                }
            } else {
                check(request.optionalString("rendererPackageId") == null) {
                    "x86 direct Wine does not accept an ARM renderer package"
                }
            }
            check(request.optString("display", ":0") == ":0") { "only the app-private :0 display is authorized" }
            check(request.optString("audioMode", "off") == "off") { "O06 requires audio-off" }
            val socket = File(p.tmp, ".X11-unix/X0")
            check(socket.exists()) { "display surface/transport must exist before launch" }

            val id = UUID.randomUUID()
            val closeFile = File(p.root, "sessions/$id/close.request")
            closeFile.parentFile!!.mkdirs(); closeFile.delete()
            val record = SessionRecord(id, p, closeFile)
            synchronized(lock) { session = record; persist(record) }
            executor.execute { runSession(record) }
            JSONObject().put("ok", true).put("sessionId", id.toString()).put("state", record.state.name)
        }

        override fun requestClose(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                if (r.state !in TERMINAL_STATES) {
                    r.closeFile.parentFile!!.mkdirs()
                    r.closeFile.writeText(r.id.toString())
                    transition(r, ClientState.CLOSE_REQUESTED, "token-scoped WM_CLOSE requested")
                }
            }
            JSONObject().put("ok", true).put("requested", r.state == ClientState.CLOSE_REQUESTED)
                .put("state", r.state.name).put("detail", r.detail)
        }

        override fun forceStop(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                check(r.state !in TERMINAL_STATES) { "session is already terminal" }
            }
            val cancelled = cancelActiveRuntime()
            check(cancelled) { "active Wine process group was not found" }
            synchronized(lock) {
                r.forced = true
                transition(r, ClientState.FORCE_STOPPED, "session process group killed")
            }
            JSONObject().put("ok", true).put("cancelled", cancelled).put("state", r.state.name)
        }

        override fun status(sessionId: String): String = guarded(sessionId) {
            eventJson(requireSession(sessionId))
        }

        override fun collectDiagnostics(sessionId: String): String = guarded(sessionId) {
            diagnosticsJson(requireSession(sessionId))
        }

        override fun reportWindowVisible(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                r.windowVisible = true
                if (r.state == ClientState.STARTING) transition(r, ClientState.RUNNING, "mapped client window visible")
            }
            eventJson(r)
        }

        override fun statusCurrent(): String = guarded("") {
            val value = synchronized(lock) {
                session?.let(::eventJsonUnsafe) ?: JSONObject().put("ok", true)
                    .put("sequence", 0).put("state", ClientState.EXITED.name)
                    .put("detail", "no active client session")
            }
            ownership.decorate(value)
        }

        override fun closeOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { checkNotNull(session) { "no client session" } }
            synchronized(lock) {
                if (r.state !in TERMINAL_STATES) {
                    r.closeFile.parentFile!!.mkdirs()
                    r.closeFile.writeText(r.id.toString())
                    transition(r, ClientState.CLOSE_REQUESTED, "owned graceful close requested")
                }
            }
            eventJson(r)
        }

        override fun releaseOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { checkNotNull(session) { "no client session" } }
            check(r.state in TERMINAL_STATES) { "client session is not terminal" }
            ownership.clear(instanceToken)
            JSONObject().put("ok", true).put("released", true).put("state", r.state.name)
        }

        override fun forceStopOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { checkNotNull(session) { "no client session" } }
            if (r.state !in TERMINAL_STATES) {
                val cancelled = cancelActiveRuntime()
                synchronized(lock) {
                    r.forced = true
                    transition(r, ClientState.FORCE_STOPPED,
                        if (cancelled) "owned process group killed" else "owned session already absent")
                }
            }
            ownership.clear(instanceToken)
            Thread({
                Thread.sleep(150)
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "client-force-retire").start()
            JSONObject().put("ok", true).put("state", r.state.name)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { cancelActiveRuntime() }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSession(r: SessionRecord) {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            runArmSession(r)
            return
        }
        val windowsClosePath = "Z:" + r.closeFile.absolutePath.replace('/', '\\')
        val env = buildList {
            add("LD_DEBUG=")
            add("WINEDEBUG=-all")
            add("POCKET_AUDIO_MODE=off")
            add("WINEDLLOVERRIDES=winealsa.drv=d,winepulse.drv=d")
            if (r.prepared.selfTest) {
                add("POCKET_SELFTEST_INTERACTIVE=1"); add("POCKET_CLOSE_FILE=$windowsClosePath")
            } else {
                add("WINEESYNC=0"); add("WINEFSYNC=0")
                add("POCKET_GLADIO_X11_SOCKET=${File(r.prepared.tmp, ".X11-unix/X0").absolutePath}")
            }
        }.joinToString(";")
        val raw = try {
            WineSpikeNative.runWineDirectNative(
                applicationInfo.nativeLibraryDir, r.prepared.executable.absolutePath,
                r.prepared.prefix.absolutePath, r.prepared.workingDir.absolutePath,
                ":0", "", env, 6 * 60 * 60 * 1000,
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                r.stderr = "${t.javaClass.simpleName}: ${t.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "native launcher threw")
            }
            return
        }
        val result = parseWineRunResult(raw)
        synchronized(lock) {
            r.stdout = result.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.stderr = result.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.cleanExit = result.rc == 0 && result.exitedCleanly && result.exitCode == 0 &&
                !result.timedOut && (!r.prepared.selfTest || r.stdout.contains("POCKET_SELFTEST_OK"))
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut}",
                )
            } else persist(r)
        }
    }

    private fun runArmSession(r: SessionRecord) {
        when (r.prepared.armTranslator ?: ArmTranslationBackend.BOX64) {
            ArmTranslationBackend.BOX64 -> runArmBox64Session(r)
            ArmTranslationBackend.FEX -> runArmFexSession(r)
        }
    }

    private fun runArmBox64Session(r: SessionRecord) {
        val rootfs = r.prepared.tree
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val box64 = File(nativeDir, "libbox64.so")
        val wine = File(rootfs, "opt/wine/bin/wine")
        val armLib = File(rootfs, "usr/lib")
        val x86Lib = File(rootfs, "lib/x86_64-linux-gnu")
        val home = File(rootfs, "home/xuser")
        val renderer = checkNotNull(r.prepared.armRenderer) { "ARM renderer identity missing" }
        File(r.prepared.tmp, "shm").mkdirs()
        val env = listOf(
            "HOME=${home.absolutePath}",
            "USER=xuser",
            "DISPLAY=:0",
            "ANDROID_SYSVSHM_SERVER=${File(rootfs, "tmp/.sysvshm/SM0").absolutePath}",
            "WINEPREFIX=${r.prepared.prefix.absolutePath}",
            "WINEDEBUG=-all",
            "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1",
            "WINEESYNC=0",
            "WINEFSYNC=0",
            "WINEDLLOVERRIDES=${if (renderer == "dxvk") "d3d9=n,b;dxgi=n,b" else "d3d9=b;dxgi=b"};winealsa.drv=d;winepulse.drv=d",
            "BOX64_NOBANNER=1",
            "BOX64_DYNAREC=1",
            "BOX64_UNITYPLAYER=0",
            // Match Winlator's conservative preset.  The generic Box64 defaults are
            // too aggressive for Wine's mixed 32/64-bit process tree on Android.
            "BOX64_DYNAREC_SAFEFLAGS=2",
            "BOX64_DYNAREC_FASTNAN=0",
            "BOX64_DYNAREC_FASTROUND=0",
            "BOX64_DYNAREC_X87DOUBLE=1",
            "BOX64_DYNAREC_BIGBLOCK=1",
            "BOX64_DYNAREC_STRONGMEM=1",
            "BOX64_DYNAREC_FORWARD=128",
            "BOX64_DYNAREC_CALLRET=0",
            "BOX64_DYNAREC_WAIT=1",
            "BOX64_DYNAREC_NATIVEFLAGS=0",
            "BOX64_DYNAREC_WEAKBARRIER=1",
            "BOX64_X11GLX=1",
            "BOX64_LD_LIBRARY_PATH=${x86Lib.absolutePath}",
            "BOX64_PATH=${File(rootfs, "opt/wine/bin").absolutePath}",
            "VK_ICD_FILENAMES=${File(rootfs, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json").absolutePath}",
            "MESA_VK_WSI_PRESENT_MODE=mailbox",
            "MESA_VK_WSI_USE_HWBUF=1",
            // Winlator forces sysmem on Adreno 7xx except 710/720/732. RP6 is 740.
            "TU_DEBUG=noconform,sysmem",
            "DXVK_STATE_CACHE_PATH=${File(r.prepared.cache, "dxvk").absolutePath}",
            "DXVK_LOG_PATH=${File(r.prepared.root, "sessions/${r.id}").absolutePath}",
            "DXVK_LOG_LEVEL=info",
            "vblank_mode=0",
            "FONTCONFIG_FILE=${File(rootfs, "etc/fonts/fonts.conf").absolutePath}",
            "FONTCONFIG_PATH=${File(rootfs, "etc/fonts").absolutePath}",
        )
        val stdoutFile = File(r.prepared.root, "sessions/${r.id}/stdout.log")
        val stderrFile = File(r.prepared.root, "sessions/${r.id}/stderr.log")
        stdoutFile.parentFile!!.mkdirs()
        val command = listOf(box64.absolutePath, wine.absolutePath) +
            ClientRuntimeContract.armClientArguments(r.prepared.executable.absolutePath, renderer)
        val process = try {
            ProcessBuilder(command)
                .directory(r.prepared.workingDir)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .apply {
                    environment().clear()
                    for (entry in env) {
                        environment()[entry.substringBefore('=')] = entry.substringAfter('=')
                    }
                    if (renderer == "opengl") {
                        environment().remove("VK_ICD_FILENAMES")
                        environment().remove("MESA_VK_WSI_PRESENT_MODE")
                        environment().remove("MESA_VK_WSI_USE_HWBUF")
                        environment().remove("TU_DEBUG")
                        environment().remove("DXVK_STATE_CACHE_PATH")
                        environment().remove("DXVK_LOG_PATH")
                        environment().remove("DXVK_LOG_LEVEL")
                        environment()["POCKET_GLADIO_X11_SOCKET"] =
                            File(rootfs, "tmp/.X11-unix/X0").absolutePath
                    }
                    environment()["LD_LIBRARY_PATH"] = "${armLib.absolutePath}:${nativeDir.absolutePath}"
                    environment()["TMPDIR"] = r.prepared.tmp.absolutePath
                    environment()["PATH"] = listOf(
                        File(rootfs, "opt/wine/bin"),
                        File(rootfs, "usr/local/bin"),
                        File(rootfs, "usr/bin"),
                        File(rootfs, "bin"),
                    ).joinToString(":") { it.absolutePath }
                    environment()["LANG"] = "C.UTF-8"
                    environment()["LC_ALL"] = "C.UTF-8"
                }
                .start()
        } catch (t: Throwable) {
            synchronized(lock) {
                r.stderr = "${t.javaClass.simpleName}: ${t.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "ARM Box64 launcher threw")
            }
            return
        }
        armProcess = process
        val exitCode = try {
            process.waitFor()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            -1
        } finally {
            if (armProcess === process) armProcess = null
        }
        synchronized(lock) {
            r.stdout = readTail(stdoutFile)
            r.stderr = readTail(stderrFile)
            r.cleanExit = exitCode == 0
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "box64 exit=$exitCode",
                )
            } else persist(r)
        }
    }

    /** Native Android ARM64EC Wine with its FEXCore WoW64 DLL backend.
     *
     * This intentionally does not invoke the ordinary Linux FEX executable.
     * ARM64EC Wine is Bionic-native; HODLL selects the pinned FEXCore DLL for
     * x86/x86-64 code. Renderer selection stays orthogonal: DXVK uses the
     * ARM64EC DLL set and Turnip, while client OpenGL uses the Bionic Gladio
     * libGL bridge and passes WoW's native -opengl switch.
     */
    private fun runArmFexSession(r: SessionRecord) {
        val rootfs = r.prepared.tree
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val wineRoot = File(rootfs, "opt/proton-9.0-arm64ec")
        val wine = File(wineRoot, "bin/wine")
        val armLib = File(rootfs, "usr/lib")
        val sysvShm = File(armLib, "libandroid-sysvshm.so")
        val renderer = checkNotNull(r.prepared.armRenderer) { "ARM renderer identity missing" }
        check(wine.isFile && wine.canExecute()) { "native ARM64EC Wine is unavailable" }
        check(File(r.prepared.prefix,
            "drive_c/windows/system32/libwow64fex.dll").isFile) {
            "FEXCore WoW64 DLL is unavailable in the selected prefix"
        }

        val env = mutableMapOf(
            "HOME" to File(rootfs, "home/xuser").absolutePath,
            "USER" to "xuser",
            "DISPLAY" to ":0",
            "ANDROID_SYSVSHM_SERVER" to File(r.prepared.tmp, ".sysvshm/SM0").absolutePath,
            "WINEPREFIX" to r.prepared.prefix.absolutePath,
            "WINEDEBUG" to "-all",
            "HODLL" to "libwow64fex.dll",
            "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER" to "1",
            "WINEESYNC" to "0",
            "WINEFSYNC" to "0",
            "WINE_NO_DUPLICATE_EXPLORER" to "1",
            "WINE_DISABLE_FULLSCREEN_HACK" to "1",
            "WINE_X11FORCEGLX" to "1",
            "WINE_GST_NO_GL" to "1",
            "WINEDLLOVERRIDES" to if (renderer == "dxvk") {
                "d3d9=n,b;dxgi=n,b;winealsa.drv=d;winepulse.drv=d"
            } else {
                // Match the pinned ARM64EC Winlator policy on Adreno: prefer
                // the packaged PE OpenGL frontend, which then calls the
                // aarch64-unix opengl32 module and the Gladio host libGL.
                "opengl32=n,b;d3d9=b;dxgi=b;winealsa.drv=d;winepulse.drv=d"
            },
            "POCKET_GLADIO_X11_SOCKET" to File(r.prepared.tmp, ".X11-unix/X0").absolutePath,
            "FEX_TSOENABLED" to "1",
            "FEX_VECTORTSOENABLED" to "0",
            "FEX_MEMCPYSETTSOENABLED" to "0",
            "FEX_HALFBARRIERTSOENABLED" to "1",
            "FEX_X87REDUCEDPRECISION" to "1",
            "FEX_MULTIBLOCK" to "1",
            "LD_LIBRARY_PATH" to listOf(armLib, File("/system/lib64"), nativeDir)
                .joinToString(":") { it.absolutePath },
            "LD_PRELOAD" to sysvShm.takeIf { it.isFile }?.absolutePath.orEmpty(),
            "PREFIX" to File(rootfs, "usr").absolutePath,
            "PATH" to listOf(File(wineRoot, "bin"), File(rootfs, "usr/bin"))
                .joinToString(":") { it.absolutePath },
            "XDG_DATA_DIRS" to File(rootfs, "usr/share").absolutePath,
            "XDG_CONFIG_DIRS" to File(rootfs, "usr/etc/xdg").absolutePath,
            "GST_PLUGIN_PATH" to File(rootfs, "usr/lib/gstreamer-1.0").absolutePath,
            "VK_LAYER_PATH" to listOf(
                File(rootfs, "usr/share/vulkan/implicit_layer.d"),
                File(rootfs, "usr/share/vulkan/explicit_layer.d"),
            ).joinToString(":") { it.absolutePath },
            "FONTCONFIG_PATH" to File(rootfs, "usr/etc/fonts").absolutePath,
            "XLOCALEDIR" to File(rootfs, "usr/share/X11/locale").absolutePath,
            "XKEYSYMDB" to File(rootfs, "usr/share/X11/XKeysymDB").absolutePath,
            "TMPDIR" to r.prepared.tmp.absolutePath,
            "OPENSSL_CONF" to File(rootfs, "usr/etc/tls/openssl.cnf").absolutePath,
            "SSL_CERT_FILE" to File(rootfs, "usr/etc/tls/cert.pem").absolutePath,
            "SSL_CERT_DIR" to File(rootfs, "usr/etc/tls/certs").absolutePath,
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
        )
        if (renderer == "dxvk") {
            env.putAll(mapOf(
                "VK_ICD_FILENAMES" to File(rootfs,
                    "usr/share/vulkan/icd.d/wrapper_icd.aarch64.json").absolutePath,
                "ADRENOTOOLS_DRIVER_PATH" to "${armLib.absolutePath}/",
                "ADRENOTOOLS_HOOKS_PATH" to armLib.absolutePath,
                "ADRENOTOOLS_DRIVER_NAME" to "vulkan.ad07xx.so",
                "WRAPPER_LAYER_PATH" to armLib.absolutePath,
                "WRAPPER_CACHE_PATH" to File(r.prepared.cache, "wrapper").absolutePath,
                "WRAPPER_VK_VERSION" to "1.4.315",
                "TU_DEBUG" to "noconform,sysmem",
                "DXVK_STATE_CACHE_PATH" to File(r.prepared.cache, "dxvk").absolutePath,
                "DXVK_LOG_PATH" to File(r.prepared.root, "sessions/${r.id}").absolutePath,
                "DXVK_LOG_LEVEL" to "info",
                "MESA_VK_WSI_PRESENT_MODE" to "mailbox",
            ))
        }
        val stdoutFile = File(r.prepared.root, "sessions/${r.id}/stdout.log")
        val stderrFile = File(r.prepared.root, "sessions/${r.id}/stderr.log")
        stdoutFile.parentFile!!.mkdirs()
        val command = listOf(wine.absolutePath) + ClientRuntimeContract.armFexClientArguments(
            r.prepared.executable.absolutePath,
            renderer,
            ClientDisplayProfile.QUALITY.resolution,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(r.prepared.workingDir)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .apply {
                    environment().clear()
                    environment().putAll(env)
                }
                .start()
        } catch (failure: Throwable) {
            synchronized(lock) {
                r.stderr = "${failure.javaClass.simpleName}: ${failure.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "ARM64EC/FEXCore launcher threw")
            }
            return
        }
        armProcess = process
        val exitCode = try {
            process.waitFor()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            -1
        } finally {
            if (armProcess === process) armProcess = null
        }
        synchronized(lock) {
            r.stdout = readTail(stdoutFile)
            r.stderr = readTail(stderrFile)
            r.cleanExit = exitCode == 0
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "fexcore/$renderer exit=$exitCode",
                )
            } else persist(r)
        }
    }

    private fun cancelActiveRuntime(): Boolean =
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            armProcess?.let { process ->
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                true
            } ?: false
        } else {
            WineSpikeNative.cancelActiveDirectNative()
        }

    private fun readTail(file: File): String = if (!file.isFile) "" else
        file.readText().takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)

    private fun transition(r: SessionRecord, state: ClientState, detail: String) {
        r.state = state; r.detail = detail; r.sequence++
        persist(r)
        AppLog.i(TAG, "session=${r.id} state=$state detail=$detail")
    }

    private fun checkNoActiveSession() = synchronized(lock) {
        check(session == null || session!!.state in TERMINAL_STATES) { "cannot prepare while a session is active" }
    }

    private fun requireProtocol(request: JSONObject) {
        check(request.optInt("protocol", -1) == ClientRuntimeContract.PROTOCOL_VERSION) {
            "unsupported ClientRuntime protocol"
        }
    }

    private fun JSONObject.optionalString(name: String): String? =
        takeIf { has(name) && !isNull(name) }?.getString(name)?.also {
            require(it.isNotBlank()) { "$name must not be blank" }
        }

    private fun requireSession(value: String): SessionRecord = synchronized(lock) {
        val id = UUID.fromString(value)
        checkNotNull(session?.takeIf { it.id == id }) { "unknown or stale session token" }
    }

    private fun eventJson(r: SessionRecord) = synchronized(lock) {
        eventJsonUnsafe(r)
    }

    private fun eventJsonUnsafe(r: SessionRecord) = JSONObject().put("ok", true)
        .put("sequence", r.sequence).put("state", r.state.name).put("detail", r.detail)
        .put("cleanExit", r.cleanExit).put("forced", r.forced)
        .put("windowVisible", r.windowVisible)

    private fun diagnosticsJson(r: SessionRecord) = synchronized(lock) {
        JSONObject().put("ok", true).put("sessionId", r.id.toString()).put("state", r.state.name)
            .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
            .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
            .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
            .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
            .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
            .put("rightButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
                r.stdout.contains("btn=r"))
            .put("middleButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
                r.stdout.contains("btn=m"))
            .put("wheelSeen", r.stdout.contains("POCKET_SELFTEST_WHEEL "))
            .put("relativeMotionSeen", r.stdout.contains("POCKET_SELFTEST_RELMOVE "))
            .put("charSeen", r.stdout.contains("POCKET_SELFTEST_CHAR "))
            .put("charCount", countOccurrences(r.stdout, "POCKET_SELFTEST_CHAR "))
            .put("stdoutTail", r.stdout).put("stderrTail", r.stderr).put("detail", r.detail)
    }

    private fun persist(r: SessionRecord) {
        val out = File(noBackupFilesDir, "wine/last-session.json")
        out.parentFile!!.mkdirs()
        val temp = File(out.parentFile, ".last-session.tmp")
        temp.writeText(diagnosticsJsonUnsafe(r).toString())
        if (!temp.renameTo(out)) { out.delete(); temp.renameTo(out) }
        trimSessionLogs(r.prepared.root)
    }

    private fun diagnosticsJsonUnsafe(r: SessionRecord) = JSONObject()
        .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION).put("sessionId", r.id.toString())
        .put("state", r.state.name).put("sequence", r.sequence).put("detail", r.detail)
        .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
        .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
        .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
        .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
        .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
        .put("rightButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
            r.stdout.contains("btn=r"))
        .put("middleButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
            r.stdout.contains("btn=m"))
        .put("wheelSeen", r.stdout.contains("POCKET_SELFTEST_WHEEL "))
        .put("relativeMotionSeen", r.stdout.contains("POCKET_SELFTEST_RELMOVE "))
        .put("charSeen", r.stdout.contains("POCKET_SELFTEST_CHAR "))
        .put("charCount", countOccurrences(r.stdout, "POCKET_SELFTEST_CHAR "))
        .put("stdoutTail", r.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))
        .put("stderrTail", r.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))

    private fun trimSessionLogs(root: File) {
        val dir = File(root, "sessions")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(8).forEach { it.deleteRecursively() }
    }

    /** Count non-overlapping occurrences of [needle] in [haystack]. */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) { count++; idx = haystack.indexOf(needle, idx + needle.length) }
        return count
    }

    private inline fun guarded(input: String, block: () -> JSONObject): String {
        if (input.toByteArray().size > ClientRuntimeContract.MAX_CONTROL_BYTES) {
            return JSONObject().put("ok", false).put("error", "control payload too large").toString()
        }
        return try { block().toString() }
        catch (t: Throwable) {
            AppLog.e(TAG, "control request failed", t)
            JSONObject().put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}").toString()
        }
    }

    companion object {
        private const val TAG = "ClientRuntime"
        private val TERMINAL_STATES = setOf(ClientState.EXITED, ClientState.FORCE_STOPPED, ClientState.FAILED)
    }
}
