package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolute

/**
 * W6-N4: the shared gateway-subprocess harness, extracted verbatim from
 * GatewaySubprocessE2E (the N3 two-process loopback E2E) so the reconnect
 * proof (ReconnectRelayE2E / ReconnectDockerE2E) drives the SAME launch /
 * READY-parse / stop machinery instead of drifting a copy. Behavior is
 * byte-for-byte the N3 harness's; only the home moved.
 */

/** One live gateway child process: stdout line queue, stderr tail, the
 * bounded READY/Error awaits. W6-N4 code-red R5-r4-N4-1: the drain
 * thread ALSO appends every line to a non-consuming TRANSCRIPT —
 * awaitLine/awaitReadyOrThrow/awaitErrorOrThrow destructively poll the
 * queue, so the evidence archivers read [stdoutLines] (the full
 * transcript), never the drained queue residue. */
class GatewayProc(private val process: Process) : AutoCloseable {
    private val stdout = java.util.concurrent.LinkedBlockingQueue<String>()

    /** The per-line transcript: every stdout line ever seen, in order —
     * appended by the drain thread, never drained. */
    private val transcript = ArrayList<String>()

    /** Whether any PR_GATEWAY_ERROR line has arrived (the loss
     * watcher's error-line leg; non-consuming, unlike a queue scan). */
    @Volatile
    private var sawError = false

    /** Whether the process is still running (the loss watcher's exit
     * leg reads this). */
    fun isAlive(): Boolean = process.isAlive

    /** The process's exit value (valid only once !isAlive). */
    fun exitValue(): Int = process.exitValue()

    /** Whether any PR_GATEWAY_ERROR line has arrived (the loss
     * watcher's error-line leg). */
    fun sawErrorLine(): Boolean = sawError

    /** Every stdout line ever seen, oldest first — the NON-consuming
     * transcript the evidence archivers read (the queue pollers drain
     * [stdout]; this survives any await). */
    fun stdoutLines(): List<String> = synchronized(transcript) { ArrayList(transcript) }

    private val drain = Thread({
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            synchronized(transcript) { transcript.add(line) }
            if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) sawError = true
            stdout.add(line)
        }
    }).apply { isDaemon = true; start() }
    private val stderrTail = java.util.concurrent.LinkedBlockingQueue<String>()

    init {
        Thread({
            process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                stderrTail.add(line)
                if (stderrTail.size > 20) stderrTail.poll()
            }
        }).apply { isDaemon = true; start() }
    }

    /** Waits (bounded) for the READY line; throws with the process's
     * error line (or exit code + stderr tail) — never a silent hang. */
    fun awaitReadyOrThrow(timeoutMs: Long = 45_000): ReadyLine {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            stdout.poll()?.let { line ->
                if (line.startsWith(GatewayContract.READY_LINE_PREFIX)) {
                    return parseReady(line.removePrefix(GatewayContract.READY_LINE_PREFIX))
                }
                // an early ERROR line is the answer
                if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                    throw AssertionError(
                        "gateway errored before READY: " +
                            line.removePrefix(GatewayContract.ERROR_LINE_PREFIX),
                    )
                }
            }
            if (!process.isAlive) {
                throw AssertionError(
                    "gateway exited (code ${process.exitValue()}) before READY; " +
                        "stderr: ${stderrTail.joinToString(" | ")}",
                )
            }
            Thread.sleep(50)
        }
        throw AssertionError("gateway never became READY in ${timeoutMs}ms")
    }

    /** Waits (bounded) for the named ERROR line (the loud-failure path). */
    fun awaitErrorOrThrow(code: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            stdout.poll()?.let { line ->
                if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                    val payload = line.removePrefix(GatewayContract.ERROR_LINE_PREFIX)
                    kotlin.test.assertTrue(
                        payload.contains(code),
                        "expected error $code, got: $payload",
                    )
                    return
                }
            }
            if (!process.isAlive) {
                throw AssertionError(
                    "gateway exited (code ${process.exitValue()}) without the " +
                        "expected $code line; stderr: ${stderrTail.joinToString(" | ")}",
                )
            }
            Thread.sleep(50)
        }
        throw AssertionError("gateway never emitted $code in ${timeoutMs}ms")
    }

    /** Waits (bounded) for process exit; returns the exit code (the
     * reconnect proof asserts the joiner's DIAL_FAILED exit). */
    fun awaitExit(timeoutMs: Long = 30_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return process.exitValue()
            Thread.sleep(50)
        }
        throw AssertionError("gateway did not exit within ${timeoutMs}ms")
    }

    private fun parseReady(json: String): ReadyLine = parseReadyJson(json)

    fun stop() {
        process.destroy()
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    override fun close() = stop()
}

/** The parsed PR_GATEWAY_READY payload. Extracted verbatim (the
 * [ReadyLine.dialSocket] derivation included). */
data class ReadyLine(
    val node: String,
    val relay: String?,
    val portsList: List<Int>,
    val boundSockets: List<String>,
) {
    /** The mux's realm lane (the JOIN gateway binds these). */
    val ports: Int get() = portsList.first()

    /** The iroh endpoint's actual UDP socket (the DIRECT lane's dial
     * input — NOT the mux port). The endpoint binds the wildcard, so
     * the one-machine loopback E2E re-points the dial at 127.0.0.1 on
     * the same port. */
    val dialSocket: String
        get() {
            val raw = boundSockets.firstOrNull()
                ?: error("the host READY carried no bound socket to dial")
            val port = raw.substringAfterLast(':')
            return "127.0.0.1:$port"
        }
}

/** Launches `java -jar gateway-…-all.jar <args>` on the CURRENT test
 * JVM's java (JDK 21 here — the artifact's floor). Extracted verbatim. */
fun launchGateway(vararg args: String): GatewayProc {
    val javaBin = Path(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
    )
    val jar = locateFatJar()
    val command = mutableListOf(
        javaBin.toString(),
        "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
        "-jar", jar,
    )
    command += args.toList()
    return GatewayProc(ProcessBuilder(command).start())
}

/** The CURRENT fat jar (the test task dependsOn fatJar) — shared by the
 * E2E and the reconnect proofs. A seat run (-Ppocket.seat, exposed to
 * the test JVM as a system property) prefers the jar ITS OWN
 * build-seat-<id> produced — never another seat's or the canonical
 * build's; default runs search the canonical build/libs first. */
fun locateFatJar(): String {
    val seat = System.getProperty("pocket.seat")
    var dir = Path(System.getProperty("user.dir")).toAbsolutePath()
    repeat(4) {
        val gateway = dir.resolve("gateway")
        val libDirs = ArrayList<java.nio.file.Path>()
        if (seat != null) {
            libDirs.add(gateway.resolve("build-seat-$seat").resolve("libs"))
        }
        libDirs.add(gateway.resolve("build").resolve("libs"))
        gateway.toFile().listFiles { f -> f.isDirectory && f.name.startsWith("build-seat-") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { libDirs.add(it.toPath().resolve("libs")) }
        for (libs in libDirs) {
            if (libs.toFile().isDirectory) {
                val jar = libs.toFile().listFiles { f ->
                    f.name.startsWith("gateway-") && f.name.endsWith("-all.jar")
                }?.maxByOrNull { it.lastModified() }
                if (jar != null) return jar.absolutePath
            }
        }
        dir = dir.parent ?: return@repeat
    }
    throw AssertionError("the gateway fat jar was not found — run the fatJar task first")
}

/** A free loopback port (the fake stack / nothing else binds it first). */
fun freeLoopbackPort(): Int =
    java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

/** The PR_GATEWAY_READY JSON payload → [ReadyLine] (shared with the
 * docker harness, whose processes are read through `docker logs` rather
 * than a child-process pipe). */
fun parseReadyJson(json: String): ReadyLine {
    val node = Regex("\"node\":\"([0-9a-f]{64})\"").find(json)?.groupValues?.get(1)
        ?: throw AssertionError("READY without a node id: $json")
    val relay = Regex("\"relay\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    val ports = Regex("\"ports\":\\[([0-9,]+)\\]").find(json)?.groupValues?.get(1)
        ?.split(',')?.map { it.trim().toInt() }
        ?: throw AssertionError("READY without ports: $json")
    val addrs = Regex("\"addrs\":\\[([^\\]]*)\\]").find(json)?.groupValues?.get(1)
        ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
        ?: emptyList()
    return ReadyLine(node, relay?.takeIf { it.isNotEmpty() }, ports, addrs)
}

/** A fresh 64-hex transport secret (D96 posture: identities are
 * generated at runtime, never embedded in tracked files). */
fun freshHexSecret(): String {
    val bytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** The wave evidence root (the gitignored out/completion tree) — anchored
 * by walking up to the checkout root, because a test JVM's user.dir is
 * the gateway module dir (W6-N4 code-red: shared by both reconnect
 * legs so the archived narration/payload files land in ONE place). */
fun waveEvidenceDir(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    repeat(4) {
        val current = dir ?: return File("out/completion/w6/n4")
        if (File(current, "gateway/build/libs").isDirectory) {
            return File(current, "out/completion/w6/n4")
        }
        dir = current.parentFile
    }
    return File("out/completion/w6/n4")
}

/** True when BOTH game ports can be bound the way the join gateway
 * binds them — a plain ServerSocket bind with NO SO_REUSEADDR, exactly
 * JoinGateway.bindListeners's semantics (W6-N4 code-red R5-2/R4-r2-3:
 * the permissive REUSEADDR probe lied — it succeeded over transient
 * holders the real bind then hit, turning a guard skip into a red).
 * A concurrent seat (or a local realm stack) holding 3724/8085 makes
 * the port-bound E2E lanes SKIP honestly instead of phantom-red. */
fun gamePortsFree(): Boolean =
    listOf(
        GatewayContract.PORT_REALM,
        GatewayContract.PORT_WORLD,
    ).all { port ->
        runCatching {
            java.net.ServerSocket().use { socket ->
                socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
                true
            }
        }.getOrDefault(false)
    }

/** The port-probe cadence and the guard's post-lock probe grace (the
 * faithful no-REUSEADDR probe retries inside the grace — the previous
 * test's teardown and the other seat's just-finished flows decay within
 * it; a persistent holder survives and the E2E skips honestly). */
internal const val PORT_PROBE_CADENCE_MS: Long = 250
internal const val POST_LOCK_PROBE_GRACE_MS: Long = 15_000

internal val gamePortLockPath =
    File(System.getProperty("java.io.tmpdir", "."), "pocket-realm-game-ports.lock")

private var gamePortChannel: java.nio.channels.FileChannel? = null
private var gamePortLock: java.nio.channels.FileLock? = null

/** Acquires the CROSS-SEAT game-port lock (3724/8085) — the
 * probe-then-act race two concurrent seats otherwise run (both probe
 * free, both bind, both phantom-red) is closed by serializing the
 * port-bound E2Es through one lock file (the focused-android wrapper's
 * O_CREAT|O_EXCL pattern, blocking variant; W6-N4 code-red R5-2).
 * False = another seat held the lock for the whole budget → the caller
 * SKIPS honestly. [lockFile] is the pin's seam (tests exercise the
 * semantics on a sandbox file — deleting the SHARED file mid-run is
 * itself the two-inode race, R4-r2-3). */
fun acquireGamePortLock(
    budgetMs: Long = GAME_PORT_LOCK_BUDGET_MS,
    lockFile: File = gamePortLockPath,
): Boolean {
    val deadline = System.currentTimeMillis() + budgetMs
    while (true) {
        val channel = runCatching {
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
            )
        }.getOrNull() ?: return false
        val lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock != null) {
            gamePortChannel = channel
            gamePortLock = lock
            return true
        }
        runCatching { channel.close() }
        if (System.currentTimeMillis() >= deadline) return false
        Thread.sleep(PORT_PROBE_CADENCE_MS)
    }
}

/** Releases the cross-seat game-port lock. W6-N4 code-red R4-r2-3: the
 * lock FILE is NEVER deleted — a skipping seat's afterEach runs even
 * when its beforeEach aborted without acquiring, and deleting the file
 * under a live holder's open channel (Windows FILE_SHARE_DELETE) lets
 * the next seat create a fresh inode and lock "the" lock beside the
 * first — re-opening the exact bind race this guard closes. A leftover
 * file never blocks: tryLock re-locks a file whose holder died. The
 * release itself is a no-op unless THIS seat holds the lock. */
fun releaseGamePortLock() {
    if (gamePortLock == null) return // never acquired here: nothing to release, never delete
    runCatching { gamePortLock?.release() }
    runCatching { gamePortChannel?.close() }
    gamePortLock = null
    gamePortChannel = null
}

/** How long a seat waits for the cross-seat lock before skipping (the
 * other seat's port-bound E2E suite runs minutes at most; a REAL
 * holder is transient). */
internal const val GAME_PORT_LOCK_BUDGET_MS: Long = 30_000
