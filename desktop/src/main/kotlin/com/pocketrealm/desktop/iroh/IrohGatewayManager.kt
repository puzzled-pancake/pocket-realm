package com.pocketrealm.desktop.iroh

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * W6-N3: the app-side manager of the iroh gateway CHILD PROCESS (why a
 * child: the computer.iroh artifact's Kotlin 2.2/JVM 21 metadata is
 * unreadable by this lane's pinned K2.0.21/JDK17 toolchain — D92 §0). One
 * manager owns at most one HOST process and one JOIN process; the
 * stdout line protocol is [GatewayContract]'s (shared source, no drift).
 *
 * The gateway is located NOISILY: `-Dpocket.gateway.jar` overrides (the
 * JVM test lane), else `gateway/build/libs/gateway-*-all.jar` beside the
 * repository root — a missing jar is a loud refusal at start, never a
 * silent no-op. The JVM is the E:-resident JDK 21
 * (`-Dpocket.gateway.java` overrides; the artifact's class files are
 * Java 21), and JNA extraction is pinned to E: (§7, no C: writes).
 *
 * Failure posture: the process's ERROR line + exit code surface verbatim
 * in [JoinState.Error] / the host descriptor's error channel — the loud
 * join contract's app-side half.
 */
internal class IrohGatewayManager(
    private val jarOverride: File? = System.getProperty("pocket.gateway.jar")?.let(::File),
    private val javaOverride: String? = System.getProperty("pocket.gateway.java"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** The host process (at most one) and its parsed READY payload. */
    private var hostProcess: GatewayProcess? = null

    @Volatile private var hostReady: HostReady? = null

    /** The READY payload's VALIDATED descriptor — built once at parse
     * time (W6-N4 code-red R2-N4-r2: counting the refusal at READ time
     * raced the host process's death, because hostDescriptor()'s
     * liveness gate precedes the validation; validating where the line
     * is parsed makes the warn+counter deterministic). Null = the relay
     * spelling was refused (a counted WARN below) or the payload was
     * malformed. */
    @Volatile private var hostDescriptorCache: IrohJoinDescriptor? = null

    /** The join process (at most one). */
    private var joinProcess: GatewayProcess? = null

    /** The post-READY process-death watcher (code-red R3-2); cancelled by
     * a deliberate stopJoin so a user stop never reads as a failure. */
    private var joinDeathWatcher: kotlinx.coroutines.Job? = null

    private val _joinState = kotlinx.coroutines.flow.MutableStateFlow<JoinState>(JoinState.Idle)
    val joinState: kotlinx.coroutines.flow.StateFlow<JoinState> =
        _joinState.asStateFlow()

    data class HostReady(val nodeHex: String, val relayUrl: String?)

    sealed class JoinState {
        /** No join running. */
        object Idle : JoinState()

        /** The gateway process is dialing/binding. */
        data class Starting(val descriptor: IrohJoinDescriptor) : JoinState()

        /** The tunnel is up: the client may dial 127.0.0.1:3724/8085. */
        data class Ready(val descriptor: IrohJoinDescriptor) : JoinState()

        /** Loud failure (the gateway's error code verbatim). */
        data class Error(val code: String, val detail: String) : JoinState()
    }

    /** The live host descriptor for the listing's `join` field, or null
     * while the host gateway is not READY (honest join:null beats a stale
     * or invented one — the beat with a null descriptor publishes the
     * honest not-joinable listing). */
    fun hostDescriptor(): IrohJoinDescriptor? {
        val live = hostDescriptorCache?.takeIf { hostProcess?.isAlive() == true }
        if (live == null) {
            // A dead process invalidates its cached READY — the next beat
            // sees the honest gateway-down state, never a stale descriptor.
            if (hostReady != null && hostProcess?.isAlive() == false) {
                hostReady = null
                hostDescriptorCache = null
            }
            return null
        }
        return live
    }

    /** The READY payload → validated descriptor: no relay URL (the
     * endpoint never registered with a home relay) is no descriptor, and
     * the constructor validates the rest — a malformed READY line
     * degrades to null, never a bad listing. W6-N4 code-red R2-N4-r2:
     * the refusal is NO LONGER silent — a relay spelling the fence
     * refuses degrades the listing to join:null, so each refusal is a
     * WARN (with the fence's own reason) plus a counter the pins read;
     * the producer-side canonization (GatewayMain.canonicalRelayUrl,
     * D98) owns keeping the known spellings legal. Called ONCE at parse
     * time (see [hostDescriptorCache]). */
    private fun descriptorFromReady(ready: HostReady): IrohJoinDescriptor? {
        val relay = ready.relayUrl ?: return null
        val descriptor = runCatching { IrohJoinDescriptor(nodeHex = ready.nodeHex, relayUrl = relay) }
        return descriptor.getOrElse { refusal ->
            descriptorRefusals += 1
            com.pocketrealm.desktop.DesktopLog.w(
                TAG,
                "gateway: the READY relay was refused by the descriptor " +
                    "fence — the host listing stays join:null until the " +
                    "relay URL is legal: ${refusal.message}",
            )
            null
        }
    }

    /** How many READY relay spellings the descriptor fence has refused
     * on this manager (each one is a join:null listing beat — the
     * R2-N4-r2 pin's observation point). */
    internal var descriptorRefusals: Int = 0
        private set

    /**
     * Ensures the HOST gateway is running (idempotent). The secret is the
     * realm's iroh transport identity — generated once, persisted by the
     * caller (a different secret than the Nostr identity; never displayed).
     */
    @Synchronized
    fun ensureHost(secretHex: String): Boolean {
        // Code-red round-5 R2-r5-2: a CRASHED host gateway is never a
        // latch — the dead process object is cleared here so the next
        // beat RELAUNCHES (the old early-return kept republishing
        // join:null 'Live' listings forever with no failure anywhere).
        hostProcess?.takeIf { !it.isAlive() }?.let { dead ->
            com.pocketrealm.desktop.DesktopLog.w(
                TAG,
                "gateway: the host gateway process died — relaunching on " +
                    "this ensure beat",
            )
            hostProcess = null
            hostReady = null
            hostDescriptorCache = null
            deadHostDeaths += 1
        }
        hostProcess?.let { return it.isAlive() && hostReady != null }
        // Code-red round-4 R3-3: the transport secret rides the inherited
        // STDIN pipe, never argv (a 64-hex argv is Win32_Process-readable
        // by any local process for the endpoint's whole lifetime).
        val process = processLauncher?.invoke(
            listOf(GatewayContract.ARG_MODE, GatewayContract.MODE_HOST, GatewayContract.ARG_SECRET_STDIN),
            secretHex,
        ) ?: launch(
            listOf(GatewayContract.ARG_MODE, GatewayContract.MODE_HOST, GatewayContract.ARG_SECRET_STDIN),
            stdinPayload = secretHex,
        )
        hostProcess = process
        hostReady = null
        hostDescriptorCache = null
        val generation = process
        scope.launch {
            val line = process.awaitLine(GatewayContract.READY_LINE_PREFIX)
            // W6-N4 code-red R2-N4-r4-1: the parse write is keyed to its
            // PRODUCING process and taken under the manager monitor — a
            // dead generation's already-queued READY line must never
            // repopulate the cache over a newer generation (with a
            // regenerated realm key the stale descriptor would advertise
            // a dead endpoint until the next self-heal beat).
            synchronized(this@IrohGatewayManager) {
                if (hostProcess !== generation) return@launch
                if (line != null) {
                    val json = runCatching { JSONObject(line) }.getOrNull()
                    val ready = json?.let {
                        HostReady(it.optString("node", ""), it.optString("relay", "").ifEmpty { null })
                    }
                    hostReady = ready
                    // Validated ONCE at parse time — the refusal warn+counter
                    // below is deterministic (R2-N4-r2), never raced by the
                    // host process's liveness.
                    hostDescriptorCache = ready?.let { descriptorFromReady(it) }
                } else if (!process.isAlive()) {
                    // The error line (if any) is already on the process's log;
                    // the descriptor simply stays null — the listing stays
                    // honest (join:null) until a READY arrives (and the next
                    // ensure beat relaunches — R2-r5-2).
                }
            }
        }
        return true
    }

    /** Stops the host gateway (the heartbeat's gate-drop / close paths). */
    @Synchronized
    fun stopHost() {
        hostProcess?.stop()
        hostProcess = null
        hostReady = null
        hostDescriptorCache = null
    }

    /**
     * Starts the JOIN gateway for [descriptor]. Fails LOUD (returns an
     * error string) when the jar/JRE is missing or a join is already
     * running; the async outcome lands on [joinState] (Ready / Error).
     */
    @Synchronized
    fun startJoin(descriptor: IrohJoinDescriptor, flavorId: String, relayOnly: Boolean): String? {
        val busy = joinProcess?.isAlive() == true
        val failure = if (busy) {
            "a gateway join is already running"
        } else {
            _joinState.value = JoinState.Starting(descriptor)
            launchJoinProcess(descriptor, flavorId, relayOnly)
        }
        return failure
    }

    /** The launch half of [startJoin]: builds the argv, spawns the
     * process (transport secret over stdin — R3-3), arms the READY/Error
     * watcher. Returns the LOUD failure string on a launch refusal
     * (null = spawned, outcome lands async on [joinState]). */
    private fun launchJoinProcess(
        descriptor: IrohJoinDescriptor,
        flavorId: String,
        relayOnly: Boolean,
    ): String? {
        val joinSecret = freshJoinSecret()
        val outcome = runCatching {
            processLauncher?.invoke(joinArgs(descriptor, flavorId, relayOnly), joinSecret)
                ?: launch(joinArgs(descriptor, flavorId, relayOnly), stdinPayload = joinSecret)
        }
        val process = outcome.getOrNull()
        if (process == null) {
            // Round-5 R6-2: the launch-failure ladder is TOTAL — a
            // GatewayLaunchException carries its own BAD-ARGS code; an
            // OS-level spawn IOException (e.g. Windows' CreateProcess
            // command-line cap) lands under the VAL-IROH-JOIN-* code.
            // Either way: JoinState.Error, never an escape, never a
            // stranded Starting.
            val failure = outcome.exceptionOrNull()!!
            val code = if (failure is GatewayLaunchException) {
                GatewayContract.ERR_BAD_ARGS
            } else {
                VAL_IROH_JOIN_LAUNCH_FAILED
            }
            val detail = failure.message
                ?: "the gateway process could not be started"
            _joinState.value = JoinState.Error(code, detail)
            return detail
        }
        joinProcess = process
        armJoinReadyWatcher(process, descriptor)
        return null
    }

    /** The join argv: relay-only carries the listing's home relay and
     * NOTHING else; the direct lane (friend joins) may carry it alongside
     * --direct-allowed. The transport secret is NEVER here — it rides
     * stdin ([launch]'s stdinPayload, R3-3). */
    internal fun joinArgs(
        descriptor: IrohJoinDescriptor,
        flavorId: String,
        relayOnly: Boolean,
    ): List<String> {
        val args = mutableListOf(
            GatewayContract.ARG_MODE, GatewayContract.MODE_JOIN,
            GatewayContract.ARG_SECRET_STDIN,
            GatewayContract.ARG_NODE_HEX, descriptor.nodeHex,
            GatewayContract.ARG_FLAVOR, flavorId,
        )
        if (relayOnly) {
            args += GatewayContract.ARG_RELAY_ONLY
            args += GatewayContract.ARG_RELAY_URL
            args += descriptor.relayUrl
        } else {
            args += GatewayContract.ARG_DIRECT_ALLOWED
            if (descriptor.relayUrl.isNotBlank()) {
                args += GatewayContract.ARG_RELAY_URL
                args += descriptor.relayUrl
            }
        }
        return args
    }

    /** Arms the READY/Error watcher + the post-READY death watcher (the
     * stale-Ready guard, code-red R3-2). Round-4 R4-N3-3: the post-READY
     * ERROR monitor LOOPS for the process's whole life (the old single
     * 60 s await gave up, stranding a late gateway error — e.g. the loud
     * rewrite failure on a late realm-list fetch — in the bounded queue
     * while the death watcher surfaced generic copy), and the death
     * watcher DRAINS the error queue first so the verbatim code/detail
     * always wins over the generic fallback. */
    internal fun armJoinReadyWatcher(process: GatewayProcess, descriptor: IrohJoinDescriptor) {
        scope.launch {
            // Code-red R4-r2-1: READY lands the MOMENT the line arrives —
            // the old code awaited the ERROR line first, whose full 60 s
            // budget exceeded the verb's 45 s deadline and false-timed-out
            // every successful join. A post-READY ERROR line (or process
            // death) flips Ready → Error through the background monitors.
            val ready = process.awaitLine(GatewayContract.READY_LINE_PREFIX)
            if (ready == null) {
                armPreReadyFailureLane(process)
                return@launch
            }
            _joinState.value = JoinState.Ready(descriptor)
            armPostReadyMonitors(process)
        }
    }

    /** The no-READY leg (detekt split, round 4): the process died (or
     * errored) before READY — one error-lane await names the failure. */
    private fun armPreReadyFailureLane(process: GatewayProcess) {
        val error = process.awaitLine(GatewayContract.ERROR_LINE_PREFIX, timeoutSeconds = 10)
        val obj = error?.let { runCatching { JSONObject(it) }.getOrNull() }
        _joinState.value = JoinState.Error(
            obj?.optString("code", GatewayContract.ERR_INTERNAL)
                ?: GatewayContract.ERR_INTERNAL,
            obj?.optString("detail", "the gateway join failed")
                ?: "the gateway process exited before answering",
        )
    }

    /** The post-READY monitors (detekt split, round 4): the death watcher
     * + the looping ERROR monitor. */
    private fun armPostReadyMonitors(process: GatewayProcess) {
        // Code-red R3-2: a post-READY process death (the loud rewrite
        // exit, any crash) must not leave a stale Ready — the watcher
        // flips it to Error — the browser renders the Error lane
        // (code-red round 3) — mirroring hostDescriptor's per-read
        // liveness leg. A deliberate stopJoin cancels it first.
        // Round-4 R4-N3-3: the gateway's OWN error line (queued before
        // death) is surfaced VERBATIM — the generic fallback runs only
        // when the queue is truly empty.
        joinDeathWatcher = scope.launch {
            while (process.isAlive()) {
                delay(JOIN_DEATH_POLL_MS)
            }
            if (_joinState.value is JoinState.Ready) {
                val lastError = process.drainErrorLines().lastOrNull()
                val obj = lastError?.let { runCatching { JSONObject(it) }.getOrNull() }
                _joinState.value = JoinState.Error(
                    obj?.optString("code", GatewayContract.ERR_INTERNAL)
                        ?: GatewayContract.ERR_INTERNAL,
                    obj?.optString("detail", DEFAULT_DEATH_DETAIL)
                        ?: DEFAULT_DEATH_DETAIL,
                )
            }
        }
        // The background ERROR monitor: a fatal gateway error line
        // after READY (e.g. the loud rewrite failure) flips the state
        // so the UI's stop/leave affordances reflect the dead tunnel.
        // Round-4 R4-N3-3: the wait window LOOPS while the process is
        // alive — a timeout is never a surrender, it is another lap.
        while (_joinState.value is JoinState.Ready && process.isAlive()) {
            val error = process.awaitLine(
                GatewayContract.ERROR_LINE_PREFIX,
                timeoutSeconds = errorMonitorWindowSeconds,
            )
            if (error != null && _joinState.value is JoinState.Ready) {
                val obj = runCatching { JSONObject(error) }.getOrDefault(JSONObject())
                _joinState.value = JoinState.Error(
                    obj.optString("code", GatewayContract.ERR_INTERNAL),
                    obj.optString("detail", "the gateway join failed"),
                )
                break
            }
        }
    }

    /** Stops the join gateway (the user's stop verb / the clash guard).
     * Cancels the death watcher FIRST: a deliberate stop is not a failure. */
    @Synchronized
    fun stopJoin() {
        joinDeathWatcher?.cancel()
        joinDeathWatcher = null
        joinProcess?.stop()
        joinProcess = null
        _joinState.value = JoinState.Idle
    }

    /** Everything dies with the model (close()). */
    fun close() {
        stopHost()
        stopJoin()
        scope.cancel()
    }

    /** The post-READY ERROR monitor's wait window per lap (round-4
     * R4-N3-3's injectable seam: the watcher test laps a SHORT window;
     * production laps the default). Internal var, never a property
     * escape into the protocol. */
    internal var errorMonitorWindowSeconds: Long = ERROR_MONITOR_WINDOW_SECONDS

    /** The process-launch seam (round-5 R2-r5-2/R6-2's injectable point):
     * null = the real ProcessBuilder launch; the watcher tests inject a
     * stub factory (host relaunch count) or a throwing launcher (the
     * IOException leg of the total failure ladder). */
    internal var processLauncher: ((args: List<String>, stdinPayload: String?) -> GatewayProcess)? =
        null

    /** How many DEAD host processes the ensure beats have cleared for
     * relaunch (the R2-r5-2 test's observation point). */
    internal var deadHostDeaths: Int = 0
        private set

    // ------------------------------------------------------------ plumbing

    private fun launch(args: List<String>, stdinPayload: String? = null): GatewayProcess {
        // The repo checkout layout: <repo>/gateway/build/libs/gateway-*-all.jar
        // (R6-4-4: this is the recorded DEV-CHECKOUT posture — the packaged
        // transport lane is a recorded follow-up; the copy below names it).
        val repoRoot = System.getProperty("pocket.repo.root")?.let(::File) ?: File(".").absoluteFile
        val jar = jarOverride
            ?: File(repoRoot, "gateway/build/libs")
                .listFiles { file ->
                    file.name.startsWith("gateway-") && file.name.endsWith("-all.jar")
                }
                ?.maxByOrNull { it.lastModified() }
            ?: throw GatewayLaunchException(
                "Internet join needs the iroh gateway transport, which this " +
                    "build does not bundle yet (it ships with the developer " +
                    "checkout: build gateway/ first, or pass -Dpocket.gateway.jar=...)",
            )
        val java = javaOverride ?: File(DEFAULT_JAVA_21).takeIf { it.isFile }?.absolutePath
            ?: throw GatewayLaunchException(
                "Internet join needs a bundled Java 21 runtime for the gateway " +
                    "transport, which this build does not ship yet (developer " +
                    "checkout posture: expected " + DEFAULT_JAVA_21 +
                    "; pass -Dpocket.gateway.java=...)",
            )
        val command = mutableListOf(
            java, "-Djna.tmpdir=" + JNA_TMPDIR, "-jar", jar.absolutePath,
        )
        command += args
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        if (stdinPayload != null) {
            // R3-3: write the transport secret to the child's stdin and
            // CLOSE the pipe so the gateway's readLine returns.
            process.outputStream.use { pipe ->
                pipe.write((stdinPayload + "\n").toByteArray(Charsets.UTF_8))
                pipe.flush()
            }
        }
        return GatewayProcess(process)
    }


    /** The join process's transport identity — a FRESH random secret per
     * join (the joiner's endpoint is ephemeral; nothing about it persists). */
    private fun freshJoinSecret(): String {
        val bytes = ByteArray(IROH_SECRET_BYTES)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The exact line the browser's Error lane renders (round-4
         * R6-4-3: extracted so a model-level test pins the rendered
         * string — the composable shows it verbatim). */
        fun joinErrorLine(code: String, detail: String): String =
            "Internet join failed: $code: $detail"

        /** A 32-byte iroh transport secret (the descriptor's node id
         * derives from it). */
        const val IROH_SECRET_BYTES = 32

        /** The post-READY death watcher's poll grain. */
        const val JOIN_DEATH_POLL_MS = 200L
        const val ERROR_QUEUE_CAP = 64

        /** The post-READY ERROR monitor's per-lap window (R4-N3-3). */
        const val ERROR_MONITOR_WINDOW_SECONDS = 60L

        /** The death fallback's detail — only when the gateway queued NO
         * error line before dying (R4-N3-3: verbatim beats generic). */
        const val DEFAULT_DEATH_DETAIL =
            "the join gateway process exited while the tunnel was up " +
                "(no gateway error line was queued)"

        /** The launch-failure ladder's OS-level code (round-5 R6-2): an
         * IOException from ProcessBuilder lands here, never escaped. */
        const val VAL_IROH_JOIN_LAUNCH_FAILED = "VAL-IROH-JOIN-LAUNCH-FAILED"

        /** The manager's log tag. */
        private const val TAG = "IrohGateway"

        /** The E:-resident JDK 21 the artifact's JVM-21 bytecode needs. */
        const val DEFAULT_JAVA_21 = "E:/pocket_realm_toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe"

        /** JNA's extraction dir (§7: never C:'s temp). */
        const val JNA_TMPDIR = "E:/pocket_realm_toolchain/tmp/jna"
    }
}

class GatewayLaunchException(message: String) : RuntimeException(message)

/** One live gateway child process: line-draining stdout (both protocol
 * lines captured), stderr to the app log, bounded destroy. */
internal class GatewayProcess(private val process: Process) {

    private val readyLines = java.util.concurrent.LinkedBlockingQueue<String>()

        /** Bounded (code-red R4-r2-3): the host role never drains its
         * error lane, so a hostile flow flood must not grow the queue
         * without bound — offers beyond the cap are dropped. */
        private val errorLines =
            java.util.concurrent.LinkedBlockingQueue<String>(
                IrohGatewayManager.ERROR_QUEUE_CAP,
            )

    private val drainThread: Thread

    init {
        drainThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith(GatewayContract.READY_LINE_PREFIX) ->
                                readyLines.add(line.removePrefix(GatewayContract.READY_LINE_PREFIX))

                            line.startsWith(GatewayContract.ERROR_LINE_PREFIX) ->
                                errorLines.offer(line.removePrefix(GatewayContract.ERROR_LINE_PREFIX))

                            else -> com.pocketrealm.desktop.DesktopLog.i(TAG, "gateway: $line")
                        }
                    }
                }
            } catch (_: Exception) {
                // stream closed with the process
            }
        }, "iroh-gateway-stdout")
        drainThread.isDaemon = true
        drainThread.start()
        // stderr: the app log (diagnostics, never the protocol).
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        com.pocketrealm.desktop.DesktopLog.w(TAG, "gateway/stderr: $line")
                    }
                }
            } catch (_: Exception) {
                // stream closed with the process
            }
        }, "iroh-gateway-stderr").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process.isAlive

    /** The bounded error lane's current depth (the R4-r2-3 pin reads it). */
    internal fun pendingErrorLineCount(): Int = errorLines.size

    /** Every queued error line, drained (round-4 R4-N3-3: the death
     * watcher reads the gateway's own last words instead of the generic
     * fallback; the queue stays bounded either way). */
    internal fun drainErrorLines(): List<String> {
        val drained = ArrayList<String>(errorLines.size)
        errorLines.drainTo(drained)
        return drained
    }

    /** The first [prefix] line, or null once the process exits without one. */
    fun awaitLine(prefix: String, timeoutSeconds: Long = AWAIT_LINE_TIMEOUT_SECONDS): String? {
        val queue = when (prefix) {
            GatewayContract.READY_LINE_PREFIX -> readyLines
            else -> errorLines
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var line: String? = null
        while (line == null && System.nanoTime() < deadline) {
            line = queue.poll()
            if (line == null) {
                if (!process.isAlive && queue.isEmpty()) break
                Thread.sleep(AWAIT_LINE_POLL_MS)
            }
        }
        return line
    }

    /** Bounded stop: destroy, then destroyForcibly (the client launcher's
     * escalation posture). */
    fun stop() {
        process.destroy()
        try {
            if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "IrohGateway"

        /** The READY/Error wait budget and poll grain; the stop escalation's
         * grace window; the post-READY death watcher's poll grain. */
        const val AWAIT_LINE_TIMEOUT_SECONDS = 60L
        const val AWAIT_LINE_POLL_MS = 50L
        const val STOP_GRACE_SECONDS = 5L
    }
}
