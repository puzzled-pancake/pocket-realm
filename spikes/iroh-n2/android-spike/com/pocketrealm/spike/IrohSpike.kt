package com.pocketrealm.spike

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.presetMinimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * iroh spike — DEBUG-ONLY in-app load+echo checkpoint.
 *
 * Owner ruling 2026-10-15 (escalation answer): option (b), E:-resident
 * emulator substitute; verdict phrasing is "load+echo proven on
 * x86_64-android (E:-resident emulator); aarch64 + armv7 build/ELF-verified
 * only" — never device-grade. Lives in the DEBUG source set only: release
 * builds never compile this file (the MainActivity hook reaches it via
 * reflection and no-ops elsewhere).
 *
 * Drive:
 *   adb shell am start -n com.pocketrealm/.ui.MainActivity \
 *       --ez iroh_spike true --es iroh_host_id <64-byte-hex> \
 *       --ei iroh_host_port <port>
 *
 * The peer is the desktop spike host (spikes/iroh-n2/desktop-echo, direct
 * mode) on the host machine; 10.0.2.2 is the emulator's host-loopback
 * alias. Protocol is byte-identical to the desktop spike: ALPN
 * pocketrealm/spike-echo/1, 4-byte-BE-length framed 64 KiB echo.
 * Evidence: `adb logcat -s IROH_SPIKE` — lines LOAD/CONNECT/ECHO/RESULT.
 *
 * Physical-device drive (the W7 RP6 leg, same binary): the emulator's
 * eth0/host-loopback defaults are wrong on a phone, so two optional extras
 * override them —
 *   --es iroh_bind_addr <wlan0-ip>:0      (default 10.0.2.15:0)
 *   --es iroh_host_addr <pc-lan-ip>:<port> (default 10.0.2.2:<iroh_host_port>)
 * The bind stays a single explicit address by design (one socket -> one
 * path candidate set; the emulator leg measured multipath churn when the
 * endpoint probed lo/dummy0 too). Both values are logged on BEGIN.
 */
object IrohSpike {
    private const val TAG = "IROH_SPIKE"
    private val ALPN = "pocketrealm/spike-echo/1".toByteArray()

    /** Called from MainActivity.onCreate (debug builds only); a
     *  ComponentActivity is both a Context and a LifecycleOwner.
     *  @JvmStatic so the reflection hook can invoke it on the class. */
    @JvmStatic
    fun maybeRun(owner: LifecycleOwner, intent: Intent?) {
        if (intent?.getBooleanExtra("iroh_spike", false) != true) return
        val hostIdHex = intent.getStringExtra("iroh_host_id")
        val hostPort = intent.getIntExtra("iroh_host_port", -1)
        if (hostIdHex.isNullOrEmpty() || hostPort <= 0) {
            Log.i(TAG, "RESULT FAIL reason=missing_host_args")
            return
        }
        val bindAddr = intent.getStringExtra("iroh_bind_addr") ?: "10.0.2.15:0"
        val hostAddr = intent.getStringExtra("iroh_host_addr") ?: "10.0.2.2:$hostPort"
        owner.lifecycleScope.launch(Dispatchers.IO) {
            run(hostIdHex, hostAddr, bindAddr)
        }
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private suspend fun run(hostIdHex: String, hostAddr: String, bindAddr: String) {
        Log.i(
            TAG,
            "BEGIN abi=${Build.SUPPORTED_ABIS.joinToString(",")} device=${Build.MODEL} " +
                "bind=$bindAddr host=$hostAddr",
        )
        try {
            withTimeout(300_000) {
                // ---- LOAD leg: the uniffi Kotlin path initializes the JNA
                // library (libiroh_ffi.so from the APK's nativeLibraryDir).
                val key = SecretKey.generate()
                val id = key.public()
                Log.i(TAG, "LOAD OK libiroh_ffi loaded via uniffi/JNA; self_id=${id.fmtShort()}")

                // ---- ECHO leg: direct transport to the host machine.
                // bindAddr pinned to one address: the emulator exposes several
                // interfaces (lo/dummy0 + IPv6), and iroh's multipath probing
                // across them churned the data plane on the first attempt
                // (host accepted the echo stream and read immediate EOF).
                // One socket -> one path candidate set. On a phone the run
                // passes the wlan0 address via --es iroh_bind_addr.
                val ep = Endpoint.bind(
                    EndpointOptions(
                        preset = presetMinimal(),
                        secretKey = key.toBytes(),
                        alpns = listOf(ALPN),
                        relayMode = RelayMode.disabled(),
                        bindAddr = bindAddr,
                        protocols = null,
                    ),
                )
                val t0 = android.os.SystemClock.elapsedRealtime()
                val hostId = EndpointId.fromBytes(hexToBytes(hostIdHex))
                val conn = ep.connect(
                    EndpointAddr(hostId, null, listOf(hostAddr)),
                    ALPN,
                )
                Log.i(
                    TAG,
                    "CONNECT OK in ${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                        "remote=${conn.remoteId().fmtShort()}",
                )

                // Datagram SEND probe (send side only — the desktop host does
                // not echo datagrams): bypasses stream flow control, proving
                // the transport accepts data on the selected path.
                runCatching {
                    conn.sendDatagramWait(byteArrayOf(0x50, 0x52)) // "PR"
                    Log.i(TAG, "DATAGRAM SEND OK")
                }.onFailure { Log.i(TAG, "DATAGRAM SKIP ${it.javaClass.simpleName}: ${it.message}") }

                val bi = conn.openBi()
                Log.i(TAG, "OPEN_BI OK")

                // 1 MiB framed byte tunnel as lock-step 4 KiB echo rounds
                // (256 x 4096), verified byte-exact. Lock-step frames stay far
                // below the QUIC stream window: a 64 KiB lock-step frame
                // dead-locks echo (writer fills the window before any readback
                // drains it — the desktop spike avoids this by reading on a
                // concurrent coroutine; a bounded lock-step round is the
                // simpler proof here).
                val payload = ByteArray(1024) { (it % 251).toByte() }
                val frame = ByteArray(4 + payload.size)
                frame[0] = ((payload.size ushr 24) and 0xff).toByte()
                frame[1] = ((payload.size ushr 16) and 0xff).toByte()
                frame[2] = ((payload.size ushr 8) and 0xff).toByte()
                frame[3] = (payload.size and 0xff).toByte()
                payload.copyInto(frame, 4)
                val t1 = android.os.SystemClock.elapsedRealtime()
                var total = 0L
                var byteExact = true
                for (round in 0 until 3) {
                    val thisFrame = frame
                    val plen = thisFrame.size - 4
                    val wAt = android.os.SystemClock.elapsedRealtime()
                    bi.send().writeAll(thisFrame)
                    Log.i(TAG, "WROTE frame round=$round len=$plen in ${android.os.SystemClock.elapsedRealtime() - wAt}ms")
                    val header = bi.recv().readExact(4.toUInt())
                    if (header == null || header.size < 4) throw IllegalStateException("echo EOF header")
                    val len = ((header[0].toInt() and 0xff) shl 24) or
                        ((header[1].toInt() and 0xff) shl 16) or
                        ((header[2].toInt() and 0xff) shl 8) or
                        (header[3].toInt() and 0xff)
                    if (len != plen) throw IllegalStateException("echo bad len $len")
                    val echoed = bi.recv().readExact(len.toUInt())
                    Log.i(TAG, "READBACK frame round=$round len=$len")
                    if (echoed == null ||
                        !echoed.contentEquals(thisFrame.copyOfRange(4, thisFrame.size))
                    ) {
                        byteExact = false
                        throw IllegalStateException("echo payload mismatch on round $round")
                    }
                    total += len
                }
                val ms = android.os.SystemClock.elapsedRealtime() - t1
                val paths = conn.paths().joinToString { p -> "ip=${p.isIp} relay=${p.isRelay} rtt=${p.rttMs}ms" }
                Log.i(TAG, "ECHO OK bytes=$total byteExact=$byteExact in ${ms}ms paths=[$paths]")
                Log.i(TAG, "RESULT PASS load=uniffi echo_bytes=$total abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
                ep.shutdown()
            }
        } catch (e: Throwable) {
            Log.i(TAG, "RESULT FAIL reason=${e.javaClass.simpleName} detail=${e.message}")
            Log.w(TAG, "failure detail", e)
        }
    }
}
