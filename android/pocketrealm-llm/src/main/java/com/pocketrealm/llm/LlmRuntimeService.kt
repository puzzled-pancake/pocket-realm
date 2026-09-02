package com.pocketrealm.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Foreground service hosting llama-server in its own :llm process.
 *
 * Lifecycle: supervisor calls [LlmRuntime.start] BEFORE launching the game
 * container (model memory is claimed first). The service:
 *   1. resolves the compute mode (NPU hybrid only when [HexagonProbe] is
 *      READY and no persisted crash-block is set),
 *   2. execs the bundled binary from nativeLibraryDir via [LlmExec]
 *      (affinity + nice applied in the child before exec),
 *   3. health-polls /health until the model is loaded,
 *   4. watches the child: crash -> restart with backoff; repeated NPU
 *      load deaths -> NPU blocked (persisted) and CPU fallback,
 *   5. watches memory pressure (PSI): on sustained pressure it stops the
 *      child gracefully instead of letting the system kill the game client
 *      (lesson from 2026-08-19: free RAM lies, PSI does not),
 *   6. broadcasts RAM/PSI/mode stats (ACTION_STATS) once per second for the UI.
 *
 * NPU laws baked in (findings log):
 *  - the hexagon backend is loaded ONLY via GGML_BACKEND_PATH after the
 *    pre-flight passes; a CPU-fallback restart clears it, otherwise the
 *    poisoned-registry crash loops;
 *  - SIGTERM only, never SIGKILL: killing a hybrid process mid-load leaks the
 *    DSP session and only a reboot clears it;
 *  - every (re)start is a fresh process — DSP VA space is never reclaimed on
 *    munmap, so model swaps must go through a service restart, never an
 *    in-process reload.
 */
class LlmRuntimeService : Service() {

    private var childPid: AtomicInteger = AtomicInteger(-1)
    private var worker: Thread? = null

    /** Supervisor generation: bumped on every config start; a stale worker
     *  (interrupted mid-backoff) must never exec or orphan a child. */
    private val generation = AtomicInteger(0)

    /** Serializes the NPU block/death preferences across the worker thread
     *  and the reset action (SharedPreferences edit is not a read-modify-
     *  write transaction; without the lock a reset can be silently undone
     *  by a death that was already in flight). */
    private val npuPrefLock = Any()

    /** The child killed by the most recent stop/restart, if it has not been
     *  reaped yet. The graceful SIGTERM teardown of a loaded model takes
     *  seconds; forking the replacement before it finishes means a lost port
     *  bind (miscounted as an NPU load death) and a memory probe that sees
     *  the dying child's pages. The supervisor bounded-waits on it first. */
    private val pendingReap = AtomicInteger(-1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            generation.incrementAndGet() // a stale worker must not exec/orphan after a stop
            stopChild()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_NPU_RESET) {
            generation.incrementAndGet()
            synchronized(npuPrefLock) {
                npuBlockedPref().edit()
                    .putBoolean(LlmRuntime.KEY_NPU_BLOCKED, false)
                    .putInt(KEY_NPU_DEATHS, 0)
                    .apply()
            }
            log("NPU block cleared by user")
            stopChild()
            // Announce the cleared state: the UI process's polls read the
            // prefs file fresh, but an immediate broadcast makes every live
            // receiver drop the banner without waiting for the next tick.
            sendBroadcast(Intent(ACTION_STATS).setPackage(packageName).apply {
                putExtra(EXTRA_PID, -1)
                putExtra(EXTRA_RUNNING, false)
                putExtra(EXTRA_NPU_BLOCKED, false)
                putExtra(EXTRA_MODE, "CPU")
            })
            stopSelf()
            return START_NOT_STICKY
        }
        val config: LlmRuntimeConfig? = intent?.getSerializableExtra(EXTRA_CONFIG) as? LlmRuntimeConfig
        // A sticky restart after process death delivers a NULL intent: the
        // realm is still up and its bots still POST to our port, so resume
        // with the last persisted config instead of stopping.
        val effective = config ?: if (intent == null) loadPersistedConfig() else null
        if (effective == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (config != null) persistConfig(config)
        startForeground(NOTIF_ID, buildNotification())
        // Kill any previous child first: a second server on the same port
        // would fail to bind and thrash the restart loop.
        stopChild()
        val gen = generation.incrementAndGet()
        worker = thread(name = "llm-supervisor") { runSupervisor(effective, gen) }
        return START_STICKY
    }

    private fun npuBlockedPref() = getSharedPreferences(LlmRuntime.PREFS, MODE_PRIVATE)

    /** Remember the exact running config so a sticky restart (NULL intent
     *  after the :llm process was killed) can resume instead of abandoning
     *  a realm whose bots still target our port. */
    private fun persistConfig(config: LlmRuntimeConfig) {
        try {
            val bytes = ByteArrayOutputStream().use { buf ->
                ObjectOutputStream(buf).use { it.writeObject(config) }
                buf.toByteArray()
            }
            npuBlockedPref().edit()
                .putString(LlmRuntime.KEY_LAST_CONFIG, Base64.encodeToString(bytes, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            log("config persist failed: ${e.message}")
        }
    }

    private fun loadPersistedConfig(): LlmRuntimeConfig? = try {
        npuBlockedPref().getString(LlmRuntime.KEY_LAST_CONFIG, null)?.let { encoded ->
            ObjectInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.NO_WRAP)))
                .use { it.readObject() as LlmRuntimeConfig }
        }
    } catch (e: Exception) {
        log("config restore failed: ${e.message}")
        null
    }

    /** Bounded wait (10 s) for the child killed by the previous generation;
     *  a graceful multi-GB teardown usually finishes well inside it. The wait
     *  is supersession-aware: a worker that becomes stale mid-wait stops
     *  waiting immediately (narrowing the unguarded window to ≤250 ms — a
     *  stale worker must never burn the full bound while its successor
     *  proceeds). If the child is STILL dying at the deadline, the obligation
     *  is re-published: this worker may itself be superseded next, and the
     *  generation after it must not fork into the dying child's port
     *  (compareAndSet so a newer stop's own obligation is never clobbered). */
    private fun awaitPendingReap(gen: Int) {
        val pid = pendingReap.getAndSet(-1)
        if (pid <= 0) return
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var gone = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (LlmExec.nativeWaitPid(pid) != 0) {
                gone = true
                break
            }
            if (gen != generation.get() || Thread.currentThread().isInterrupted) break
            SystemClock.sleep(250)
        }
        if (!gone) pendingReap.compareAndSet(-1, pid)
        reapInThread(pid) // a daemon finishes the wait either way
    }

    private fun runSupervisor(config: LlmRuntimeConfig, gen: Int) {
        var backoffMs = 1_000L
        // §4.4: the config actually exec'd. When the warm-up probe proves the
        // model's own template burns the budget on a thinking preamble, this
        // becomes config + --chat-template <staged content> and the child is
        // (deliberately, post-healthy) restarted onto it. Exactly one retry
        // per supervisor run: a model that still thinks under the override
        // is accepted and logged, never looped. If the override child never
        // reaches healthy (e.g. a staged template file became unreadable at
        // arm time), armedTemplate reverts the exec config to the model's
        // own template instead of crash-looping the dead override forever.
        var effectiveConfig = config
        var templateRetryDone = false
        var armedTemplate = false
        while (!Thread.currentThread().isInterrupted) {
            // A pending reap (stop/restart of a previous generation) gates
            // EVERY fork, not just supervisor entry: a graceful teardown can
            // outlast the 10 s bound, and forking while the old child still
            // holds the port only produces bind-failure exits.
            awaitPendingReap(gen)
            // The bounded reap can take up to 10 s: re-check authority before
            // extracting skels or forking, so a superseded worker cannot race
            // its successor's extraction (unlinking its in-flight temp) or
            // fork a doomed child.
            if (gen != generation.get() || Thread.currentThread().isInterrupted) return
            // Skels are extracted whenever NPU is wanted and BEFORE the probe:
            // a fresh install has no filesDir/dsp yet, and probe() reports
            // SKELS_MISSING until extraction ran (never gate extraction on a
            // probe that depends on it).
            if (wantsNpu(effectiveConfig)) extractDspSkels()
            // Resolve the mode fresh on every (re)start: the probe re-reads
            // memory and DSP state, and a fallback restart must observe a
            // newly-set block flag.
            val probe = probeFor(effectiveConfig)
            val blocked = npuBlockedPref().getBoolean(LlmRuntime.KEY_NPU_BLOCKED, false)
            val npuActive = wantsNpu(effectiveConfig) && probe.ready && !blocked
            val state = when {
                npuActive -> "NPU-HYBRID"
                wantsNpu(effectiveConfig) && blocked -> "CPU (NPU blocked)"
                wantsNpu(effectiveConfig) -> "CPU (NPU unavailable: ${probe.detail})"
                else -> "CPU"
            }
            broadcastState(state, if (npuActive) probe else null, blocked)

            val execAtMs = SystemClock.elapsedRealtime()
            val pid = execServer(effectiveConfig, npuActive)
            // A stop/new-config may have landed while we probed and forked:
            // never leave an untracked child behind (it would fight for the
            // port and leak its RSS + DSP session).
            if (gen != generation.get()) {
                if (pid > 0) {
                    killChild(pid)
                    reapInThread(pid)
                }
                return
            }
            if (pid < 0) {
                log("exec failed: errno ${-pid}; retrying in ${backoffMs}ms")
                SystemClock.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                continue
            }
            childPid.set(pid)
            if (gen != generation.get()) {
                killChild(pid)
                childPid.compareAndSet(pid, -1) // never leave a stale pid for stopChild to signal
                reapInThread(pid)
                return
            }
            log("server pid=$pid mode=$state; waiting for /health")

            val healthy = AtomicBoolean(false)
            val loadPeakKb = AtomicInteger(0)
            val psi = thread(name = "llm-psi") { watchPressure(pid) }
            val stats = thread(name = "llm-stats") { broadcastStats(pid, healthy, loadPeakKb, state, probe, blocked) }
            // Health + exit wait in one loop, no deadline: a multi-GB model
            // may legitimately take minutes to load, and a slow-but-real load
            // must not be miscounted as a load death (the death attribution
            // keys on !healthy at exit). The child's exit is the only
            // terminating event.
            var exitStatus = 0 // nativeWaitPid result once the child is gone
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    killChild(pid)
                    break
                }
                val r = LlmExec.nativeWaitPid(pid)
                if (r != 0) {
                    exitStatus = r
                    log("server exited: $r")
                    break
                }
                // Health is proven by OUR child where the socket table is
                // readable (ownership gate; see childOwnsPort); where it is
                // SELinux-denied the gate degrades to probe-only and a
                // squatter answering 200 can flip healthy — documented
                // residual, and such exits still classify as collisions.
                if (!healthy.get() && childOwnsPort(pid, effectiveConfig.port) && probeHealth(effectiveConfig)) {
                    healthy.set(true)
                    // Proven health is the ONLY backoff reset: a fork that
                    // dies moments later (bind collision) must keep growing
                    // the restart delay toward the 30 s cap instead of
                    // churning every second — each NPU retry re-extracts the
                    // DSP skels and re-reads the death-attribution log.
                    backoffMs = 1_000L
                    // A clean load clears the consecutive-death counter.
                    synchronized(npuPrefLock) {
                        npuBlockedPref().edit().putInt(KEY_NPU_DEATHS, 0).apply()
                    }
                    log("healthy")
                    // §4.4 warm-up probe: one tiny generation through the real
                    // chat path. This pays the measured first-request penalty
                    // here (the next player-facing request finds warm code
                    // paths) AND detects the thinking-template failure shape
                    // (reasoning_content non-empty, or empty content — the
                    // §1.4 budget-burn). One retry per supervisor run; a
                    // deliberate POST-HEALTHY kill is never attributed as an
                    // NPU load death (attribution only fires pre-healthy).
                    val thinks = warmUpThinks(effectiveConfig)
                    // Read the staged template NOW and pass its CONTENT via
                    // --chat-template: the vendored 6d05498 binary predates
                    // --chat-template-file (verified by string extraction
                    // from libllama-server-impl.so; the desktop b10520 has
                    // both - never trust the desktop build's flag surface).
                    // The ~1.6 KB template rides argv (no shell involved;
                    // argv strings allow newlines, NUL-free jinja).
                    val stagedTemplate: String? = effectiveConfig.chatTemplateFile
                        ?.takeIf { path: String -> File(path).isFile }
                        ?.let { path: String ->
                            runCatching { File(path).readText() }.getOrNull()
                        }
                        ?.takeIf { text: String -> text.isNotEmpty() }
                    if (thinks == true && !templateRetryDone && stagedTemplate != null) {
                        templateRetryDone = true
                        armedTemplate = true
                        effectiveConfig = effectiveConfig.copy(
                            extraArgs = effectiveConfig.extraArgs +
                                listOf("--chat-template", stagedTemplate)
                        )
                        log("warm-up: thinking template detected; restarting with --chat-template")
                        killChild(pid)
                        // fall through: the exit-wait below observes the
                        // deliberate exit and the loop re-execs the override
                    } else if (thinks == true) {
                        // never arm without a readable staged template, and
                        // never reconsider this run
                        templateRetryDone = true
                        log(if (stagedTemplate != null)
                            "warm-up: template override armed but the model still thinks; " +
                                "accepting it (one retry per run)"
                        else
                            "warm-up: thinking template detected; no usable staged template - " +
                                "chat may come back empty on this model")
                    }
                }
                SystemClock.sleep(500)
            }
            // The child is gone: drop it from the kill path so a later
            // stop/onDestroy cannot SIGTERM a recycled pid.
            childPid.compareAndSet(pid, -1)
            psi.interrupt()
            stats.interrupt()
            if (Thread.currentThread().isInterrupted) {
                // SIGTERMed but possibly still dying, and the psi/stats
                // watchers have abandoned the pid — leave a daemon reaper.
                reapInThread(pid)
                return
            }

            // A superseded worker's child was killed (or abandoned) by the
            // newer generation: its exit is deliberate by definition and
            // must never be attributed — a stale worker racing the reap
            // could otherwise see ECHILD and misread a benign tail.
            if (gen != generation.get()) return
            if (!healthy.get() && npuActive) {
                attributeNpuDeath(exitStatus, SystemClock.elapsedRealtime() - execAtMs)
            }
            // §4.4: an override-armed child that never reached healthy would
            // crash-loop on the dead --chat-template-file flag - revert to
            // the model's own template (templateRetryDone stays true: the
            // probe already proved it thinks; fail open to the model default
            // rather than loop)
            if (armedTemplate && !healthy.get()) {
                log("template-override child never became healthy; reverting to the model's own template")
                effectiveConfig = config
                armedTemplate = false
            }
            // Doc contract: after a PSI protective stop the server restarts
            // once pressure clears — the reload itself is a multi-GB
            // allocation that would worsen the very pressure that triggered
            // the kill.
            while (psiPressureHigh() && gen == generation.get() &&
                !Thread.currentThread().isInterrupted
            ) {
                log("memory pressure still high; deferring restart")
                SystemClock.sleep(30_000L)
            }
            if (gen != generation.get() || Thread.currentThread().isInterrupted) return
            log("restarting server in ${backoffMs}ms")
            SystemClock.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun wantsNpu(config: LlmRuntimeConfig) =
        config.computeMode == ComputeMode.NPU || config.computeMode == ComputeMode.AUTO

    private fun probeFor(config: LlmRuntimeConfig) =
        HexagonProbe.probe(this, File(config.modelFile))

    /** Load-phase death in NPU mode: persist a block after [NPU_DEATH_LIMIT]
     *  in a row. Locked against ACTION_NPU_RESET so an in-flight death can
     *  never silently undo a user reset. */
    private fun noteNpuLoadDeath() {
        synchronized(npuPrefLock) {
            val p = npuBlockedPref()
            val deaths = p.getInt(KEY_NPU_DEATHS, 0) + 1
            val tail = logTail()
            log("NPU load death #$deaths; log tail: $tail")
            p.edit()
                .putInt(KEY_NPU_DEATHS, deaths)
                .putString(KEY_NPU_TAIL, tail)
                .putBoolean(LlmRuntime.KEY_NPU_BLOCKED, deaths >= NPU_DEATH_LIMIT)
                .apply()
        }
    }

    /** Classify an exit of a never-healthy NPU child. The decision table is
     *  [NpuDeathAttribution.classify] (pure, unit-tested); this supplies the
     *  waitpid status, the uptime, and the server log tail and acts on the
     *  verdict. */
    private fun attributeNpuDeath(exitStatus: Int, uptimeMs: Long) {
        when (NpuDeathAttribution.classify(exitStatus, uptimeMs, logTail())) {
            NpuDeathVerdict.DELIBERATE_STOP ->
                log("server stopped by request (status $exitStatus); not a load death")
            NpuDeathVerdict.LOAD_DEATH -> noteNpuLoadDeath()
            NpuDeathVerdict.STARTUP_COLLISION ->
                log("server exited ${uptimeMs}ms after exec (status $exitStatus); startup collision, not a load death")
            NpuDeathVerdict.PRE_EXEC_FAILURE ->
                log("child failed before exec: ${exitStatus - 10_000}")
        }
    }

    /** True when memory pressure is at the level that triggers a protective
     *  stop. PSI-unreadable (old kernel) keeps the historical unconditional
     *  restart. */
    private fun psiPressureHigh(): Boolean = try {
        val txt = File("/proc/pressure/memory").readText()
        val some = Regex("some avg10=(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val full = Regex("full avg10=(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        some > PSI_SOME_STOP || full > PSI_FULL_STOP
    } catch (_: Exception) {
        false
    }

    /** Liveness check that does NOT reap (see [NpuDeathAttribution.isStatAlive]). */
    private fun childUp(pid: Int): Boolean = try {
        NpuDeathAttribution.isStatAlive(File("/proc/$pid/stat").readText())
    } catch (_: Exception) {
        false
    }

    /** Reap a child nobody waits on (superseded-worker kills) so it does not
     *  stay a zombie for the lifetime of the :llm process. */
    private fun reapInThread(pid: Int) {
        thread(name = "llm-reaper", isDaemon = true) {
            while (LlmExec.nativeWaitPid(pid) == 0) {
                SystemClock.sleep(500)
            }
        }
    }

    /** Last lines of the server log, bounded: the NPU-mode log is accepted
     *  as unbounded on disk, so the tail is read through a fixed trailing
     *  window instead of pulling the whole file into the :llm heap. Keeps
     *  the newest 400 chars (the actual exit reason lives at the end). */
    private fun logTail(): String = try {
        val f = File(filesDir, "logs/llama-server.log")
        if (!f.isFile) {
            ""
        } else {
            RandomAccessFile(f, "r").use { raf ->
                val window = minOf(LOG_TAIL_WINDOW_BYTES, raf.length()).toInt()
                raf.seek(raf.length() - window)
                val bytes = ByteArray(window)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }.lineSequence().toList().takeLast(6).joinToString(" | ").takeLast(400)
        }
    } catch (_: Exception) {
        ""
    }

    private fun broadcastState(state: String, probe: HexagonProbe.Result?, blocked: Boolean) {
        sendBroadcast(Intent(ACTION_STATS).setPackage(packageName).apply {
            putExtra(EXTRA_PID, -1)
            putExtra(EXTRA_RUNNING, false)
            putExtra(EXTRA_MODE, state)
            putExtra(EXTRA_NPU_BLOCKED, blocked)
            probe?.let {
                putExtra(EXTRA_NPU_STATE, it.state.name)
                putExtra(EXTRA_NPU_DETAIL, it.detail)
            }
        })
    }

    private fun execServer(config: LlmRuntimeConfig, npuActive: Boolean): Int {
        val libDir = applicationInfo.nativeLibraryDir
        val server = File(libDir, SERVER_LIB)
        if (!server.exists()) return -1
        if (!File(config.modelFile).exists()) return -2

        val argv = mutableListOf(
            server.absolutePath,
            "--host", if (config.bindLoopbackOnly) "127.0.0.1" else "0.0.0.0",
            "--port", config.port.toString(),
            "-m", config.modelFile,
            "-c", config.contextSize.toString(),
            "-t", config.threads.toString(),
        )
        if (config.apiKey.isNotEmpty()) argv += listOf("--api-key", config.apiKey)
        val useMtp = config.useMtp && !npuActive // hybrid MTP unmeasured; keep it off on the NPU path
        if (useMtp) argv += listOf(
            "--spec-type", "draft-mtp",
            "--spec-draft-n-max", config.mtpDraftMax.toString(),
        )
        if (npuActive) {
            // The log target must come BEFORE --device: parsing --device
            // triggers ggml_backend_load_all(), which is where a session-open
            // failure is logged — a failure printed before --log-file is
            // applied reaches only logcat, and the death discriminator would
            // then misread the exit as a startup collision. Earlier options
            // (--host/--port/-m/-c/-t) carry service-generated values and
            // cannot fail parsing.
            File(filesDir, "logs").mkdirs() // the server's fopen does not mkdir
            argv += listOf("--log-file", File(filesDir, "logs/llama-server.log").absolutePath)
            // Hexagon hybrid: prefill is HTP-bound (core-invariant), decode
            // scales with -t/mask. --device HTP0 (never "cpu" — the parser
            // rejects CPU-type devices); explicit -ngl for predictability;
            // --load-mode none keeps weights out of a second mmap copy.
            // No --no-warmup: the warmup pass pre-faults the DMA buffers.
            argv += listOf("--device", "HTP0", "-ngl", config.npuLayers.toString())
            if ("--load-mode" !in config.extraArgs && "--no-mmap" !in config.extraArgs) {
                argv += listOf("--load-mode", "none")
            }
        } else {
            argv += listOf("--no-warmup")
        }
        argv += config.extraArgs

        val envp = mutableListOf(
            "LD_LIBRARY_PATH=$libDir" + if (npuActive) ":/vendor/lib64" else "",
            "PATH=/system/bin",
            "HOME=${filesDir.absolutePath}",
        )
        if (npuActive) {
            // Load the hexagon backend by path (renamed libggmlhex.so is never
            // matched by the exe-dir scan, so without this env the CPU path is
            // untouched). ADSP_LIBRARY_PATH must contain the extracted skels —
            // the driver resolves file:///libggml-htp-v<arch>.so through it.
            envp += "GGML_BACKEND_PATH=${File(libDir, "libggmlhex.so").absolutePath}"
            envp += "ADSP_LIBRARY_PATH=${HexagonProbe.dspDir(this).absolutePath};/vendor/dsp/cdsp"
        }
        return LlmExec.nativeExec(argv.toTypedArray(), envp.toTypedArray(), config.nice, config.cpuMaskHex)
    }

    /** Copy the packaged DSP skels to filesDir/dsp. Always overwrite-copy:
     *  APK assets may be compressed (AssetFileDescriptor.openFd throws for
     *  those), and a few MB per NPU start is negligible. Each file is written
     *  to a temp name and renamed (atomic on the same filesystem) so a killed
     *  or failed copy can never leave a truncated skel that the probe — and
     *  then the DSP loader — would accept as present. */
    private fun extractDspSkels() {
        try {
            val out = HexagonProbe.dspDir(this)
            out.mkdirs()
            val names = assets.list("dsp") ?: return
            // Stale temp files from previous workers (unique suffix) first.
            out.listFiles { f -> f.name.startsWith(".") }?.forEach { it.delete() }
            val workerTag = Thread.currentThread().id
            for (name in names) {
                try {
                    val tmp = File(out, ".$name.$workerTag.tmp")
                    assets.open("dsp/$name").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    val target = File(out, name)
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        check(tmp.renameTo(target)) { "cannot replace $name" }
                    }
                } catch (e: Exception) {
                    File(out, ".$name.$workerTag.tmp").delete()
                    log("skel extraction failed for $name: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log("skel extraction failed: ${e.message}")
        }
    }

    private fun probeHealth(config: LlmRuntimeConfig): Boolean =
        probe("http://127.0.0.1:${config.port}/health")

    /**
     * §4.4 warm-up probe: POST one 8-token chat completion through the real
     * /v1/chat/completions path the world server uses. Null = the probe
     * itself failed (no verdict, no restart — fail-open to the model's own
     * template); true = the thinking-template failure shape (non-empty
     * `reasoning_content`, or empty content — the §1.4 budget-burn); false =
     * the model answers chat directly.
     */
    private fun warmUpThinks(config: LlmRuntimeConfig): Boolean? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:${config.port}/v1/chat/completions")
                .openConnection() as HttpURLConnection)
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(WARM_UP_BODY.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                log("warm-up: HTTP ${connection.responseCode} (no template verdict)")
                null
            } else {
                val message = JSONObject(
                    connection.inputStream.bufferedReader().use { it.readText() }
                ).getJSONArray("choices").getJSONObject(0).optJSONObject("message")
                if (message == null) {
                    null
                } else {
                    val reasoning = message.optString("reasoning_content", "")
                    val content = message.optString("content", "")
                    reasoning.isNotEmpty() || content.isEmpty()
                }
            }
        } catch (e: Exception) {
            log("warm-up probe failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Set once /proc/net/tcp proves unreadable (the normal case: Android
     *  10+ SELinux neverallows app domains reading proc_net). */
    @Volatile
    private var portTableUnavailable = false

    /** True when a LISTEN socket for [port] is owned by an fd of [pid] —
     *  binds the /health verdict to OUR child, not to whatever else holds
     *  the port. On Android 10+ /proc/net/tcp is SELinux-denied to app
     *  domains, so this degrades (logged once) to "ownership unknown" —
     *  the caller then falls back to the plain probe, accepting that a
     *  deliberately squattering local app answering 200 can flip `healthy`
     *  (an adjacent, documented residual of the loopback-no-auth posture;
     *  the bind-collision classification still keeps such exits uncounted). */
    private fun childOwnsPort(pid: Int, port: Int): Boolean {
        if (portTableUnavailable) return true // unknown — caller's probe decides
        return try {
            val listeners = ProcNetTcp.listenInodes(File("/proc/net/tcp").readText(), port)
            if (listeners.isEmpty()) {
                false
            } else {
                val childSockets = File("/proc/$pid/fd").listFiles()
                    ?.mapNotNull { f -> runCatching { Os.readlink(f.absolutePath) }.getOrNull() }
                    ?.filter { it.startsWith("socket:[") }
                    ?.map { it.removePrefix("socket:[").removeSuffix("]") }
                    ?.toSet()
                childSockets?.any { listeners.contains(it) } == true
            }
        } catch (e: Exception) {
            log("socket table unreadable (${e.message}); health ownership check degraded to probe-only")
            portTableUnavailable = true
            true // unknown — degrade to the pre-ownership behavior, loudly
        }
    }

    private fun probe(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1_000
        c.readTimeout = 1_000
        try { c.responseCode in 200..299 } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    /** Once a second, broadcast child RAM (VmRSS/VmHWM), PSI and MemAvailable.
     *  VmHWM is the kernel high-water mark, so a sample after a burst always
     *  contains the true peak — no need for tighter polling. */
    private fun broadcastStats(
        pid: Int,
        healthy: AtomicBoolean,
        loadPeakKb: AtomicInteger,
        mode: String,
        probe: HexagonProbe.Result?,
        blocked: Boolean,
    ) {
        try {
            while (childUp(pid) && !Thread.currentThread().isInterrupted) {
                val rss = readStatusKb(pid, "VmRSS")
                val hwm = readStatusKb(pid, "VmHWM")
                if (rss != null && hwm != null) {
                    if (!healthy.get()) loadPeakKb.set(max(loadPeakKb.get(), hwm))
                    val psiTxt = try { File("/proc/pressure/memory").readText() } catch (_: Exception) { "" }
                    val some = Regex("some avg10=(\\d+)").find(psiTxt)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val full = Regex("full avg10=(\\d+)").find(psiTxt)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val memAvail = try {
                        Regex("MemAvailable:\\s+(\\d+)").find(File("/proc/meminfo").readText())
                            ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    } catch (_: Exception) { -1 }
                    sendBroadcast(Intent(ACTION_STATS).setPackage(packageName).apply {
                        putExtra(EXTRA_PID, pid)
                        putExtra(EXTRA_RUNNING, true)
                        putExtra(EXTRA_HEALTHY, healthy.get())
                        putExtra(EXTRA_MODE, mode)
                        putExtra(EXTRA_NPU_BLOCKED, blocked)
                        probe?.let {
                            putExtra(EXTRA_NPU_STATE, it.state.name)
                            putExtra(EXTRA_NPU_DETAIL, it.detail)
                        }
                        putExtra(EXTRA_RSS_KB, rss)
                        putExtra(EXTRA_HWM_KB, hwm)
                        putExtra(EXTRA_LOAD_PEAK_KB, loadPeakKb.get())
                        putExtra(EXTRA_PSI_SOME, some)
                        putExtra(EXTRA_PSI_FULL, full)
                        putExtra(EXTRA_MEM_AVAIL_KB, memAvail)
                    })
                }
                SystemClock.sleep(1_000)
            }
        } catch (_: InterruptedException) {
        }
        sendBroadcast(Intent(ACTION_STATS).setPackage(packageName).apply {
            putExtra(EXTRA_PID, pid)
            putExtra(EXTRA_RUNNING, false)
        })
    }

    private fun readStatusKb(pid: Int, key: String): Int? = try {
        Regex("$key:\\s+(\\d+) kB").find(File("/proc/$pid/status").readText())
            ?.groupValues?.get(1)?.toIntOrNull()
    } catch (_: Exception) {
        null
    }

    /** Poll PSI (inotify does not fire on procfs) and SIGTERM the child when
     *  memory pressure threatens the game client. SIGTERM, never SIGKILL:
     *  a hybrid process killed mid-load leaks its HTP session (reboot-only fix).
     *  PSI-unreadable kernels (missing or SELinux-denied /proc/pressure)
     *  disable the watchdog quietly — the same degradation contract as
     *  [psiPressureHigh]; an uncaught read failure here would kill the whole
     *  :llm process (and via PDEATHSIG the child with it). */
    private fun watchPressure(pid: Int) {
        try {
            val psiFile = File("/proc/pressure/memory")
            while (childUp(pid) && !Thread.currentThread().isInterrupted) {
                val txt = try {
                    psiFile.readText()
                } catch (e: Exception) {
                    log("PSI unavailable (${e.message}); memory watchdog disabled")
                    return
                }
                val some = Regex("some avg10=(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val full = Regex("full avg10=(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (some > PSI_SOME_STOP || full > PSI_FULL_STOP) {
                    log("PSI some=$some full=$full -> stopping server to protect game client")
                    killChild(pid)
                    return
                }
                SystemClock.sleep(2_000)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun killChild(pid: Int) {
        try {
            Os.kill(pid, 15) // SIGTERM
        } catch (_: Exception) {
        }
    }

    private fun stopChild() {
        val pid = childPid.getAndSet(-1)
        if (pid > 0) {
            killChild(pid)
            pendingReap.set(pid) // the next generation must not fork until this one is gone
        }
        worker?.interrupt()
    }

    override fun onDestroy() {
        stopChild()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LLM Runtime", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketRealm LLM runtime")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // replace with app icon
            .setOngoing(true)
            .build()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val TAG = "LlmRuntime"
        const val EXTRA_CONFIG = "config"
        const val ACTION_STOP = "com.pocketrealm.llm.STOP"
        const val ACTION_NPU_RESET = "com.pocketrealm.llm.NPU_RESET"
        const val ACTION_STATS = "com.pocketrealm.llm.STATS"
        const val EXTRA_PID = "pid"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_HEALTHY = "healthy"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NPU_STATE = "npuState"
        const val EXTRA_NPU_DETAIL = "npuDetail"
        const val EXTRA_NPU_BLOCKED = "npuBlocked"
        const val EXTRA_RSS_KB = "rssKb"
        const val EXTRA_HWM_KB = "hwmKb"
        const val EXTRA_LOAD_PEAK_KB = "loadPeakKb"
        const val EXTRA_PSI_SOME = "psiSome10"
        const val EXTRA_PSI_FULL = "psiFull10"
        const val EXTRA_MEM_AVAIL_KB = "memAvailKb"
        const val SERVER_LIB = "libllamaserver.so" // bundled binary (see README)
        const val NOTIF_ID = 4421
        const val CHANNEL_ID = "llm_runtime"

        private const val KEY_NPU_DEATHS = "npuDeaths"
        private const val KEY_NPU_TAIL = "npuTail"
        private const val NPU_DEATH_LIMIT = 2

        /** §4.4 warm-up probe body: one tiny generation, no kwargs - the
         *  probe must see the template's OWN default (kwargs could mask the
         *  very failure being detected). */
        private const val WARM_UP_BODY =
            """{"messages":[{"role":"user","content":"Say ready."}],"max_tokens":8,"temperature":0,"stream":false}"""

        /** PSI levels that trigger the protective stop (and gate the
         *  restart until they clear). */
        private const val PSI_SOME_STOP = 70
        private const val PSI_FULL_STOP = 25

        /** Trailing bytes of the server log inspected for death attribution. */
        private const val LOG_TAIL_WINDOW_BYTES = 64L * 1024L
    }
}
