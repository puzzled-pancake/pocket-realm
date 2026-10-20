// iroh spike — two-endpoint echo over computer.iroh:iroh 1.1.0 (desktop).
//
// GATE harness for docs/NOSTR-IROH-BUILD-PLAN.md stage 2. Measures, per leg:
//   - multi-MB byte-tunnel transfer (64 KiB framed bidirectional echo, sha256-verified)
//   - direct-vs-relay outcome (PathSnapshot.isIp / .isRelay on the selected path
//     + PathEvent.Opened/Selected/Closed streams from both sides)
//   - path-change stall duration (25 ms ping stream; any pong gap > threshold is
//     logged with its duration and correlated with the path events)
//   - long-lived-flow rate-limit behavior (throughput per 5 s window over a
//     multi-minute relayed flow; hard-kill detection = connection/stream errors)
//
// Relay-only addressing (the same shape the discovery record will publish:
// node id + home relay URL, NO IPs) is exercised on the relay leg via
// EndpointAddr(hostId, relayUrl, addresses = []).
package com.pocketrealm.spike.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohException
import computer.iroh.LogLevel
import computer.iroh.PathEvent
import computer.iroh.PathSnapshot
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.presetMinimal
import computer.iroh.presetN0
import computer.iroh.setLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

val ALPN: ByteArray = "pocketrealm/spike-echo/1".toByteArray()
const val FRAME_HEADER: Int = 4
const val CHUNK: Int = 64 * 1024
const val PING_INTERVAL_MS: Long = 25
const val STALL_THRESHOLD_MS: Long = 250

val T0 = System.nanoTime()
fun elapsedMs(): Long = (System.nanoTime() - T0) / 1_000_000
fun ts(): String = "${elapsedMs().toString().padStart(8)}ms"

fun log(msg: String) = println("[${Instant.now()} ${ts()}] $msg")

fun hex(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes) // for payload digests
fun hexLow(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/** 4-byte big-endian length + payload. */
fun frame(payload: ByteArray): ByteArray {
    val out = ByteArray(FRAME_HEADER + payload.size)
    out[0] = ((payload.size ushr 24) and 0xff).toByte()
    out[1] = ((payload.size ushr 16) and 0xff).toByte()
    out[2] = ((payload.size ushr 8) and 0xff).toByte()
    out[3] = (payload.size and 0xff).toByte()
    payload.copyInto(out, FRAME_HEADER)
    return out
}

class PathRecorder(val label: String) {
    val events = ConcurrentLinkedQueue<String>()

    fun snapshotLine(p: PathSnapshot): String =
        "id=${p.id} sel=${p.isSelected} ip=${p.isIp} relay=${p.isRelay} rtt=${p.rttMs}ms " +
            "remote=${p.remoteAddr} txBytes=${p.stats.udpTxBytes} rxBytes=${p.stats.udpRxBytes} " +
            "cwnd=${p.stats.cwnd} lostPkts=${p.stats.lostPackets} mtu=${p.stats.currentMtu}"

    fun snapshotPaths(paths: List<PathSnapshot>) = paths.joinToString(" | ") { snapshotLine(it) }
}

/** Streaming measurement collected on the joiner side. */
class PingStats {
    val count = AtomicLong(0)
    val lastPongAt = AtomicLong(0)
    val stalls = ConcurrentLinkedQueue<String>()
    val latencies = ConcurrentLinkedQueue<Long>()
}

fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

fun buildOptions(secretHex: String, relayLeg: Boolean, relayUrls: List<String>?) : EndpointOptions {
    val secret = SecretKey.fromBytes(hexToBytes(secretHex))
    val mode = when {
        !relayLeg -> RelayMode.disabled()
        relayUrls.isNullOrEmpty() -> RelayMode.defaultMode()
        else -> RelayMode.customFromUrls(relayUrls)
    }
    return EndpointOptions(
        preset = if (relayLeg) presetN0() else presetMinimal(),
        secretKey = secret.toBytes(),
        alpns = listOf(ALPN),
        relayMode = mode,
        bindAddr = null,
        protocols = null,
    )
}

fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { i ->
    ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
}

/**
 * The FFI's watchPathEvents/watchPaths spawn tokio tasks and panic when called
 * outside the rust reactor ("there is no reactor running", path.rs:232) —
 * uniffi runs exported sync methods on the calling thread. Derive the same
 * path-event stream by diffing Connection.paths() snapshots on a fast poll.
 */
fun pollPaths(
    conn: computer.iroh.Connection,
    who: String,
    rec: PathRecorder,
): ConcurrentLinkedQueue<String> {
    val eventLog = ConcurrentLinkedQueue<String>()
    val thread = Thread({
        var last = emptyMap<String, String>()
        var iterations = 0
        while (conn.closeReason() == null) {
            try {
                val snap = conn.paths()
                val now = snap.associate { p -> p.id to pathKey(p) }
                for ((id, key) in now) if (id !in last) {
                    val l = "${ts()} Opened ${rec.snapshotLine(snap.first { it.id == id })}"
                    eventLog.add(l); log("$who path-diff $l")
                }
                for ((id, key) in last) if (id !in now) {
                    val l = "${ts()} Closed path-id=$id"
                    eventLog.add(l); log("$who path-diff $l")
                }
                for ((id, key) in now) if (id in last && last[id] != key) {
                    val l = "${ts()} SelectionChanged ${rec.snapshotLine(snap.first { it.id == id })}"
                    eventLog.add(l); log("$who path-diff $l")
                }
                last = now
            } catch (e: Exception) {
                log("$who poll error: ${e.message}")
                break
            }
            Thread.sleep(10)
            iterations += 1
        }
        log("$who poller done after $iterations iterations")
    }, "$who-path-poll")
    thread.isDaemon = true
    thread.start()
    return eventLog
}

// ---------------------------------------------------------------------------
// host
// ---------------------------------------------------------------------------

suspend fun runHost(args: Array<String>) {
    val secretHex = argValue(args, "--secret") ?: error("host: --secret <hex32> required")
    val relayLeg = (argValue(args, "--relay-mode") ?: "disabled") == "n0"
    val relayUrls = argValue(args, "--relay")?.split(",")
    val maxSeconds = argValue(args, "--max-seconds")?.toLong() ?: 0L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ep = Endpoint.bind(buildOptions(secretHex, relayLeg, relayUrls))
    val rec = PathRecorder("host")
    if (relayLeg) {
        withTimeout(30_000) { ep.online() }
    }
    val idHex = hexLow(ep.id().toBytes())
    log("SPIKE_HOST_READY id=$idHex addr=${ep.boundSockets().joinToString(",")} relay=${ep.addr().relayUrl() ?: "-"}")

    val acceptJob = scope.launch {
        while (true) {
            val incoming = try {
                ep.acceptNext() ?: break
            } catch (e: IrohException) {
                log("host accept error: ${e.message}")
                break
            }
            launch {
                val remote = try { incoming.remoteAddr() } catch (e: Exception) { null }
                log("host incoming from $remote")
                val conn = try {
                    incoming.accept().connect()
                } catch (e: Exception) {
                    log("host accept handshake failed: $e")
                    return@launch
                }
                pollPaths(conn, "host", rec)
                val echoLoops = mutableListOf<Job>()
                try {
                    while (true) {
                        val bi = conn.acceptBi()
                        echoLoops += launch(Dispatchers.IO) { echoLoop(bi, conn) }
                    }
                } catch (e: Exception) {
                    log("host connection closed: ${e.message} reason=${conn.closeReason()}")
                } finally {
                    echoLoops.forEach { it.cancel() }
                    log("host final paths: ${rec.snapshotPaths(conn.paths())}")
                }
            }
        }
    }

    if (maxSeconds > 0) {
        Thread.sleep(maxSeconds * 1000)
        acceptJob.cancel()
        ep.close()
        scope.cancel()
    } else {
        // Park until killed by the driver.
        Thread.currentThread().join()
    }
}

suspend fun echoLoop(bi: computer.iroh.BiStream, conn: computer.iroh.Connection) {
    val digest = MessageDigest.getInstance("SHA-256")
    var frames = 0L
    var bytes = 0L
    try {
        while (true) {
            val header = bi.recv().readExact(FRAME_HEADER.toUInt())
            if (header == null || header.size < FRAME_HEADER) break
            val len = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (len == 0) break
            val payload = bi.recv().readExact(len.toUInt())
            if (payload == null || payload.size < len) break
            digest.update(payload)
            bytes += payload.size
            frames += 1
            bi.send().writeAll(header)
            bi.send().writeAll(payload)
        }
    } catch (e: Exception) {
        log("host echo stream end: ${e.message}")
    } finally {
        log("host echo stream done frames=$frames bytes=$bytes digest=${hexLow(digest.digest())}")
    }
}

// ---------------------------------------------------------------------------
// join
// ---------------------------------------------------------------------------

suspend fun runJoin(args: Array<String>, out: PrintStream) {
    val secretHex = argValue(args, "--secret") ?: error("join: --secret <hex32> required")
    val hostIdHex = argValue(args, "--host-id") ?: error("join: --host-id <hex32> required")
    val relayLeg = (argValue(args, "--relay-mode") ?: "disabled") == "n0"
    val hostAddr = argValue(args, "--host-addr")
    val homeRelay = argValue(args, "--relay")
    val bulkMb = argValue(args, "--bulk-mb")?.toInt() ?: 16
    val pingsSec = argValue(args, "--pings-sec")?.toLong() ?: 30L
    if (!relayLeg && hostAddr == null) error("join: direct leg needs --host-addr ip:port")
    if (relayLeg && homeRelay == null) error("join: relay leg needs --relay <home relay url> (relay-only addressing: no IP)")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ep = Endpoint.bind(buildOptions(secretHex, relayLeg, null))
    if (relayLeg) {
        withTimeout(30_000) { ep.online() }
        log("join online; home relay=${ep.addr().relayUrl()}")
    }

    val hostId = EndpointId.fromBytes(hexToBytes(hostIdHex))
    // Direct leg: explicit ip:port, no relay. Relay leg: node id + home relay URL
    // ONLY — the exact shape the discovery record will carry (never an IP).
    val addr = if (relayLeg) {
        log("join addressing: RELAY-ONLY (node id + home relay url, no direct addresses)")
        EndpointAddr(hostId, homeRelay, emptyList())
    } else {
        log("join addressing: DIRECT ip=$hostAddr relay=null")
        EndpointAddr(hostId, null, listOf(hostAddr!!))
    }

    val tConnect0 = elapsedMs()
    val conn = ep.connect(addr, ALPN)
    val connectMs = elapsedMs() - tConnect0
    log("join connected in ${connectMs}ms remote=${conn.remoteId().fmtShort()}")

    val rec = PathRecorder("join")
    val eventLog = pollPaths(conn, "join", rec)

    val ping = PingStats()
    val bulkResult = BulkResult()
    val jobs = mutableListOf<Job>()

    if (pingsSec > 0) jobs += scope.launch(Dispatchers.IO) { pingLoop(conn, pingsSec, ping) }
    if (bulkMb > 0) jobs += scope.launch(Dispatchers.IO) { bulkLoop(conn, bulkMb, bulkResult) }

    jobs.forEach { it.join() }

    val finalPaths = conn.paths()
    val summary = buildString {
        append("SPIKE_SUMMARY {")
        append("\"leg\": \"${if (relayLeg) "relay" else "direct"}\", ")
        append("\"connect_ms\": $connectMs, \"total_run_ms\": ${elapsedMs() - tConnect0}, ")
        append("\"bulk\": $bulkResult, ")
        append("\"pings\": {\"count\": ${ping.count.get()}, \"max_gap_ms\": ${maxGapMs(ping)}, \"stalls\": [${ping.stalls.joinToString(", ") { q(it) }}]}, ")
        append("\"path_events\": [${eventLog.joinToString(", ") { q(it) }}], ")
        append("\"final_paths\": [${rec.snapshotPaths(finalPaths)}], ")
        append("\"conn_close_reason\": \"${conn.closeReason() ?: "open"}\"")
        append("}")
    }
    out.println(summary)
    log(summary)
    ep.close()
    scope.cancel()
}

/** Minimal JSON string quoting for summary arrays. */
fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Only the parts of a path snapshot whose change constitutes an EVENT. */
fun pathKey(p: PathSnapshot): String = "sel=${p.isSelected} ip=${p.isIp} relay=${p.isRelay} remote=${p.remoteAddr}"

fun maxGapMs(p: PingStats): Long {
    var max = 0L
    p.stalls.forEach { line ->
        val idx = line.indexOf("gap_ms=")
        if (idx >= 0) {
            val end = line.indexOf(' ', idx)
            val v = line.substring(idx + 7, if (end < 0) line.length else end).toLongOrNull() ?: 0L
            if (v > max) max = v
        }
    }
    return max
}

suspend fun pingLoop(conn: computer.iroh.Connection, seconds: Long, stats: PingStats) {
    val bi = try { conn.openBi() } catch (e: Exception) { log("join ping open failed: $e"); return }
    var seq = 0L
    val deadline = elapsedMs() + seconds * 1000
    while (elapsedMs() < deadline) {
        val start = elapsedMs()
        val buf = ByteArray(8)
        writeLong(buf, 0, seq)
        try {
            bi.send().writeAll(frame(buf))
            val header = bi.recv().readExact(FRAME_HEADER.toUInt())
            if (header == null || header.size < FRAME_HEADER) { log("join ping EOF"); break }
            val plen = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (plen != 8) { log("join ping bad frame len=$plen"); break }
            val pong = bi.recv().readExact(8.toUInt())
            if (pong == null || pong.size < 8) { log("join ping EOF"); break }
            val now = elapsedMs()
            val rtt = now - start
            stats.count.incrementAndGet()
            stats.latencies.add(rtt)
            val last = stats.lastPongAt.getAndSet(now)
            val gap = now - last
            if (gap > STALL_THRESHOLD_MS && last > 0) {
                val line = "${Instant.now()} gap_ms=$gap (pong rtt=$rtt seq=$seq)"
                stats.stalls.add(line)
                log("join STALL-MARKER $line")
            }
        } catch (e: Exception) {
            log("join ping error at seq=$seq: $e")
            break
        }
        seq += 1
        val nextAt = start + PING_INTERVAL_MS
        val sleep = nextAt - elapsedMs()
        if (sleep > 0) Thread.sleep(sleep)
    }
    log("join ping loop done count=${stats.count}")
}

class BulkResult {
    val totalBytes = AtomicLong(0)
    @Volatile var digestHex: String = ""
    @Volatile var durationMs: Long = -1
    @Volatile var ok: Boolean = false
    @Volatile var error: String = ""
    @Volatile var windows: String = ""

    override fun toString(): String =
        "{\"ok\": $ok, \"bytes\": ${totalBytes.get()}, \"duration_ms\": $durationMs, " +
            "\"throughput_mbps\": ${if (durationMs > 0) "%.2f".format(totalBytes.get() * 8000.0 / durationMs / 1_000_000.0) else "0"}, " +
            "\"sha256_ok\": $ok, \"error\": \"$error\", \"throughput_windows_mbps\": [$windows]}"
}

suspend fun bulkLoop(conn: computer.iroh.Connection, megabytes: Int, result: BulkResult) {
    val bi = try { conn.openBi() } catch (e: Exception) { result.error = "open: ${e.message}"; return }
    val total = megabytes.toLong() * 1024 * 1024
    val chunk = ByteArray(CHUNK) { (it % 251).toByte() } // deterministic payload
    val sendDigest = MessageDigest.getInstance("SHA-256")
    val recvDigest = MessageDigest.getInstance("SHA-256")
    val start = elapsedMs()
    var lastWindowAt = start
    var lastWindowBytes = 0L
    val windowLines = mutableListOf<String>()
    var sent = 0L
    try {
        // Reader coroutine: echoes come back while we keep sending.
        val reader = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            var got = 0L
            while (got < total) {
                val header = bi.recv().readExact(FRAME_HEADER.toUInt()) ?: break
                if (header.size < FRAME_HEADER) break
                val len = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                if (len == 0) break
                val payload = bi.recv().readExact(len.toUInt()) ?: break
                if (payload.size < len) break
                recvDigest.update(payload)
                got += len
                result.totalBytes.set(got)
                val now = elapsedMs()
                if (now - lastWindowAt >= 5_000) {
                    val mbps = (got - lastWindowBytes) * 8000.0 / (now - lastWindowAt) / 1_000_000.0
                    windowLines.add("%.2f@%dms".format(mbps, now - start))
                    log("join bulk window: %.2f Mbit/s cumulative=%.1f MB at ${now - start}ms".format(mbps, got / 1048576.0))
                    lastWindowAt = now
                    lastWindowBytes = got
                }
            }
            log("join bulk reader done got=$got")
        }
        while (sent < total) {
            val n = minOf(CHUNK.toLong(), total - sent).toInt()
            val frame = frame(if (n == CHUNK) chunk else chunk.copyOf(n))
            sendDigest.update(frame, FRAME_HEADER, n)
            bi.send().writeAll(frame)
            sent += n
        }
        bi.send().finish()
        reader.join()
        val d = elapsedMs() - start
        result.durationMs = d
        result.digestHex = hexLow(recvDigest.digest())
        result.ok = result.digestHex == hexLow(sendDigest.digest()) && result.totalBytes.get() == total
        if (!result.ok) result.error = "digest mismatch sent=${hexLow(sendDigest.digest())} recv=${result.digestHex} bytes=${result.totalBytes.get()}/$total"
        result.windows = windowLines.joinToString(", ")
        log("join bulk done $megabytes MB in ${d}ms ok=${result.ok}")
    } catch (e: Exception) {
        result.error = "bulk: ${e.message}"
        result.durationMs = elapsedMs() - start
        log("join bulk FAILED: ${e.message}")
    }
}

fun writeLong(buf: ByteArray, off: Int, v: Long) {
    for (i in 0 until 8) buf[off + i] = ((v ushr ((7 - i) * 8)) and 0xff).toByte()
}

// ---------------------------------------------------------------------------

fun main(args: Array<String>) {
    setLogLevel(LogLevel.WARN)
    val mode = args.firstOrNull() ?: error("usage: echo host|join ...")
    val rest = args.drop(1).toTypedArray()
    System.setProperty("jna.tmpdir", System.getProperty("jna.tmpdir", "E:/pocket_realm_toolchain/tmp/jna"))
    runBlocking {
        when (mode) {
            "host" -> runHost(rest)
            "join" -> runJoin(rest, System.out)
            else -> error("unknown mode $mode")
        }
    }
}
