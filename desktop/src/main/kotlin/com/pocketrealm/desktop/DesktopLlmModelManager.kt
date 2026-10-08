package com.pocketrealm.desktop

import com.pocketrealm.llm.DesktopLlmModelStaging
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmTierProfile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The desktop embedded-model lane manager (DESK-48/80/103): stages the
 * pinned GGUF from the shared [LlmModelRegistry] through
 * [DesktopLlmModelStaging] into `<root>/llm/models`, then launches a
 * bundled `llama-server.exe` sidecar (ProcessBuilder + health-probed
 * endpoint) whose URL feeds the existing external-endpoint conf plumbing
 * (LlmRuntimePolicy.confBlockEmbedded — the Android confBlock twin; there
 * is no separate desktop embedded conf emission).
 *
 * DELIBERATELY ABSENT (android-specific, per the treatments): NPU/Hexagon
 * compute placement, battery-aware pacing, decode-core fields, and the
 * foreground-service delivery — the desktop lane is a plain CPU/GPU
 * llama-server child process.
 *
 * Honesty posture: the llama-server binary is a PACKAGING dependency —
 * [resolveSidecarBinary] resolves it from the jpackage app dir
 * (java.library.path, like ServerRuntimeFiles.resolveBundledAsset) or the
 * dev checkout's native staging tree; when it is absent every launch
 * returns null with [ERR_NO_SIDECAR_BINARY] and the conf emission fails
 * CLOSED to no LLM block (the reviewed fail-closed posture), never a
 * broken endpoint. The manager is a per-process singleton ([shared]) so
 * the conf writer inside world start and the LlmScreen drive exactly one
 * sidecar.
 */
@Suppress("TooManyFunctions", "ReturnCount", "LongParameterList") // the :llm twin's verb
// count; the ensure/probe gates return early per unprovable state; the argv
// builder mirrors the Android runtimeConfig contract's parameter set
internal class DesktopLlmModelManager internal constructor(private val roots: DesktopStorageRoots) {

    data class SidecarState(
        val running: Boolean,
        val endpoint: String?,
        val detail: String,
    )

    @Volatile
    private var process: Process? = null

    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var lastDetail: String = "sidecar not running"

    /** The model id the running sidecar serves (null when not running). */
    @Volatile
    private var runningModelId: String? = null

    /** The staged model for the settings-selected registry id, or null
     * when it is absent or fails its sha256 proof. The staging root is
     * <runtime>/llm (DESK-28's %LOCALAPPDATA%/PocketRealm/runtime/llm). */
    fun stagedModel(modelId: String?): File? {
        val descriptor = LlmModelRegistry.byId(modelId)
        return DesktopLlmModelStaging.modelIfVerified(roots.llm, descriptor)
    }

    /** Presence check WITHOUT the multi-GB sha256 pass (file + pinned size
     * only) — the UI model card uses this inside composition; the conf
     * lane's [stagedModel] still proves the full digest before any launch. */
    fun stagedFilePresent(modelId: String?): File? {
        val descriptor = LlmModelRegistry.byId(modelId)
        val target = DesktopLlmModelStaging.modelPath(roots.llm, descriptor)
        return if (target.isFile && (descriptor.size <= 0 || target.length() == descriptor.size)) {
            target
        } else {
            null
        }
    }

    /** Blocking staged download (worker thread; the UI layer owns its
     * dispatcher). Reuses a verified copy without network use. */
    fun downloadModel(
        modelId: String?,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        val descriptor = LlmModelRegistry.byId(modelId)
        require(!descriptor.localOnly) {
            "model ${descriptor.id} is local-only (staged by hand; no download source)"
        }
        return DesktopLlmModelStaging.download(roots.llm, descriptor, onProgress, isCancelled)
    }

    /** The manager's observable state (the LlmScreen model card); the
     * selected model id rides the detail so the card names what the next
     * world start would stage. */
    fun state(modelId: String?): SidecarState = SidecarState(
        running = process?.isAlive == true,
        endpoint = endpoint,
        detail = "$lastDetail (selected model ${LlmModelRegistry.byId(modelId).id})",
    )

    /**
     * Ensure the sidecar is running for the selected model and return its
     * OpenAI-compatible chat-completions endpoint, or null when the lane
     * cannot serve (no staged model, no bundled binary, boot/health
     * failure) — the caller's fail-closed shape. A running sidecar for the
     * SAME model AND the same engine knobs is reused; a model or knob
     * change stops and relaunches. [decodeThreads]/[offloadLayers] are the
     * DESK-9 advanced engine knobs (normalized through the LlmRuntimePolicy
     * twin, defaults = the desktop's measured CPU posture).
     */
    @Suppress("ReturnCount") // the fail-closed early-return chain is the lane's contract
    fun ensureRunning(
        modelId: String?,
        decodeThreads: Int = com.pocketrealm.server.LlmRuntimePolicy.DEFAULT_THREADS,
        offloadLayers: Int = com.pocketrealm.server.LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS,
    ): String? {
        val current = process
        if (current?.isAlive == true && endpoint != null) {
            if (runningModelId == LlmModelRegistry.byId(modelId).id &&
                runningThreads == decodeThreads &&
                runningOffloadLayers == offloadLayers
            ) {
                return endpoint
            }
            stop()
        }
        val descriptor = LlmModelRegistry.byId(modelId)
        val model = stagedModel(modelId) ?: run {
            lastDetail = "model ${descriptor.fileName} is not staged (sha256-unverified copies refuse)"
            return null
        }
        val binary = resolveSidecarBinary() ?: run {
            lastDetail = ERR_NO_SIDECAR_BINARY
            return null
        }
        runningModelId = descriptor.id
        runningThreads = decodeThreads
        runningOffloadLayers = offloadLayers
        return launch(binary, model, descriptor.tierProfile, decodeThreads, offloadLayers)
    }

    /** The engine knobs the running sidecar was launched with (the
     *  relaunch-reuse gate must treat a knob change like a model switch). */
    private var runningThreads: Int = com.pocketrealm.server.LlmRuntimePolicy.DEFAULT_THREADS
    private var runningOffloadLayers: Int = com.pocketrealm.server.LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS

    /** The live endpoint, or null. */
    fun activeEndpoint(): String? = if (process?.isAlive == true) endpoint else null

    /** Stop the sidecar (world stop / backend close; idempotent). */
    fun stop() {
        val current = process ?: return
        process = null
        endpoint = null
        runningModelId = null
        runCatching { current.destroy() }
        // A hard kill fallback keeps shutdown bounded (llama-server handles
        // TERM; destroyForcibly is the Windows TerminateProcess backstop).
        runCatching {
            current.onExit().get(SIDECAR_STOP_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { runCatching { current.destroyForcibly() } }
        lastDetail = "sidecar stopped"
    }

    /**
     * Pump the child's merged stdout/stderr into <llm>/sidecar-stdout.log.
     * The sidecar is a chatty llama.cpp server (boot banner, per-request
     * slot timings, and — when a generation dies — the exact server-side
     * error). Windows anonymous pipes are a tiny fixed buffer: with no
     * reader, the child's first log burst past that buffer blocks its
     * writer threads and every diagnostic word it ever prints is lost
     * with the process. The file is truncated at each launch (one
     * sidecar per JVM, so the log always reflects the live child), the
     * reader thread is a daemon that EOFs when the child dies, and the
     * child's exit code is appended so a dead lane leaves its cause on
     * disk. Drain failures never break the launch.
     */
    private fun drainSidecarOutput(started: Process) {
        val logFile = sidecarOutputFile()
        try {
            roots.llm.mkdirs()
            logFile.writeText("")
        } catch (_runCatching: java.io.IOException) {
            return // no log sink available: launch stays honest, output lost
        }
        Thread {
            try {
                started.inputStream.use { input ->
                    java.io.FileOutputStream(logFile, true /* append */).use { sink ->
                        input.copyTo(sink, SIDE_CAR_DRAIN_BUFFER_BYTES)
                    }
                }
            } catch (_runCatching: java.io.IOException) {
                // the child died mid-read or the sink vanished: fall through
            } finally {
                try {
                    val exited = started.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS)
                    logFile.appendText(
                        "\n[sidecar exited code=%s alive=%s]\n".format(
                            exited.exitValue().toString(),
                            exited.isAlive.toString(),
                        ),
                    )
                } catch (_runCatching: Exception) {
                    // exit bookkeeping is best-effort
                }
            }
        }.apply {
            isDaemon = true
            name = "llama-sidecar-stdout-drain"
        }.start()
    }

    /** The sidecar's stdout/stderr sink (truncated per launch). */
    internal fun sidecarOutputFile(): File = File(roots.llm, SIDECAR_OUTPUT_LOG_NAME)

    /** Spawn + health-probe; the honest failure is the caller's null. */
    private fun launch(
        binary: File,
        model: File,
        tier: LlmTierProfile,
        decodeThreads: Int,
        offloadLayers: Int,
    ): String? {
        val port = freePort() ?: run {
            lastDetail = "no free loopback port for the sidecar"
            return null
        }
        val target = endpointFor(port)
        return try {
            val started = ProcessBuilder(
                sidecarArgs(binary, model, tier, port, decodeThreads, offloadLayers),
            ).redirectErrorStream(true).start()
            process = started
            endpoint = target
            drainSidecarOutput(started)
            lastDetail = "sidecar booting (${binary.name}, model ${model.name})"
            if (awaitHealth(port, started)) {
                lastDetail = "sidecar serving $target"
                target
            } else {
                stop()
                lastDetail = "sidecar never became healthy within ${HEALTH_WAIT_MS}ms"
                null
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            stop()
            lastDetail = "sidecar launch failed: ${failure.message ?: failure.javaClass.simpleName}"
            null
        }
    }

    /**
     * The child argv (internal: the DESK-9 engine-knob contract is pinned
     * by test without spawning a multi-GB-class server). It mirrors the
     * Android :llm contract (LlmRuntimeService.execServer CPU path x
     * LlmRuntimePolicy.runtimeConfig): -m/--host/--port/-c are service
     * values, -t is the DESK-9 decode-threads knob (llama-server's default
     * is hardware concurrency - exactly what the Android lane refuses to
     * hand a game coexistence budget), -ngl rides only when the offload
     * knob is above the CPU-only desktop default 0, --jinja applies the
     * model's real chat template and --load-mode none keeps the weights
     * out of a second mmap copy (the runtimeConfig extraArgs, verbatim),
     * --no-webui keeps a browser surface off.
     */
    internal fun sidecarArgs(
        binary: File,
        model: File,
        tier: LlmTierProfile,
        port: Int,
        decodeThreads: Int,
        offloadLayers: Int,
    ): List<String> {
        val offload = com.pocketrealm.server.LlmRuntimePolicy.normalizeOffloadLayers(offloadLayers)
        val argv = mutableListOf(
            binary.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", tier.contextLength.toString(),
            "-t", com.pocketrealm.server.LlmRuntimePolicy.normalizeThreads(decodeThreads).toString(),
        )
        if (offload > 0) {
            argv += listOf("-ngl", offload.toString())
        }
        argv += listOf("--jinja", "--load-mode", "none", "--no-webui")
        return argv
    }

    /** The OpenAI-compatible chat-completions endpoint form the conf
     * carries (the normalizer's bare-origin shape appends the same path). */
    internal fun endpointFor(port: Int): String =
        "http://127.0.0.1:$port${DESKTOP_EXTERNAL_CHAT_COMPLETIONS_PATH}"

    /** Poll GET /health until 200 or the deadline; the process dying
     * early ends the wait immediately (no fixed-sleep hang). */
    @Suppress("ReturnCount") // the early exits ARE the algorithm: a dead
    // process, a healthy probe, the deadline — each is its own honest verdict
    private fun awaitHealth(port: Int, started: Process): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) return false
            if (healthOk(port)) return true
            Thread.sleep(HEALTH_POLL_MS)
        }
        return false
    }

    /** One health probe (GET /health == 200). Internal: the test drives
     * it against a live loopback fixture server. */
    internal fun healthOk(port: Int): Boolean {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health"))
            .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
            .GET()
            .build()
        return runCatching {
            probeClient.send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode() == HTTP_OK
        }.getOrDefault(false)
    }

    /**
     * The bundled llama-server.exe, resolved like
     * ServerRuntimeFiles.resolveBundledAsset: the jpackage app dir
     * (java.library.path; packageApp stages binaries side by side there)
     * first, then the dev checkout's native staging tree. Never the run
     * dir (a staged copy must not verify itself).
     */
    internal fun resolveSidecarBinary(): File? {
        val candidates = (System.getProperty("java.library.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(File(it), SIDECAR_BINARY_NAME) } +
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .take(MAX_BINARY_WALK_UP)
                .flatMap { dir ->
                    listOf(
                        File(dir, "native/.build-win-x86_64/llama-staging/$SIDECAR_BINARY_NAME"),
                        File(dir, SIDECAR_BINARY_NAME),
                    )
                }
        return candidates.firstOrNull { it.isFile }
    }

    private fun freePort(): Int? {
        val port = runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrNull() ?: return null
        return port.takeIf {
            it in com.pocketrealm.server.LlmRuntimePolicy.MIN_PORT..
                com.pocketrealm.server.LlmRuntimePolicy.MAX_PORT
        }
    }

    private val probeClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    }

    companion object {
        /** llama-server's health endpoint answers 200 on its listen port. */
        const val SIDECAR_BINARY_NAME = "llama-server.exe"

        /** The honest refusal detail when no binary is bundled. */
        const val ERR_NO_SIDECAR_BINARY =
            "llama-server.exe is not bundled with this install; the on-device lane stays off"

        const val DESKTOP_EXTERNAL_CHAT_COMPLETIONS_PATH = "/v1/chat/completions"

        /** The sidecar's captured stdout/stderr, under the llm root. */
        const val SIDECAR_OUTPUT_LOG_NAME = "sidecar-stdout.log"

        private const val SIDE_CAR_DRAIN_BUFFER_BYTES = 8 * 1024

        private const val HEALTH_WAIT_MS = 30_000L
        private const val HEALTH_POLL_MS = 250L
        private const val SIDECAR_STOP_WAIT_SECONDS = 5L
        private const val PROBE_TIMEOUT_SECONDS = 2L
        private const val HTTP_OK = 200
        private const val MAX_BINARY_WALK_UP = 6

        @Volatile
        private var sharedInstance: DesktopLlmModelManager? = null

        /** The per-process manager (one sidecar per JVM). */
        @Suppress("ReturnCount") // the standard double-checked-locking shape
        fun shared(roots: DesktopStorageRoots): DesktopLlmModelManager {
            sharedInstance?.let { return it }
            synchronized(this) {
                sharedInstance?.let { return it }
                return DesktopLlmModelManager(roots).also { sharedInstance = it }
            }
        }
    }
}
