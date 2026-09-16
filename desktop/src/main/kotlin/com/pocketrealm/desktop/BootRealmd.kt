package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.server.ServerRuntimeContract
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Gate entry point: boot realmd IN-PROCESS through the real
 * DesktopRuntimeBackend against the seeded datadir, prove the listener
 * accepts a TCP connection on 127.0.0.1:3724, then stop cleanly and
 * verify the stop was graceful. Run from desktop/:
 *
 *   gradlew bootRealmd
 *
 * Exit 0 = the whole gate passed (start -> listening -> clean stop).
 */
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("BootRealmd", "realmd gate starting")

    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "boot-realmd-gate", instanceToken = "boot-realmd-gate")

    val preflight = kotlinx.coroutines.runBlocking { backend.preflight(spec) }
    if (!preflight.ok) {
        System.err.println("PREFLIGHT FAILED: ${preflight.detail}")
        exitProcess(EXIT_PREFLIGHT)
    }
    println("preflight: ${preflight.detail}")

    val database = kotlinx.coroutines.runBlocking {
        backend.start(RuntimeComponent.DATABASE, owner, spec)
    }
    println("database: state=${database.state} ready=${database.ready}")

    val realm = kotlinx.coroutines.runBlocking {
        backend.start(RuntimeComponent.REALM, owner, spec)
    }
    println("realm: state=${realm.state} detail=${realm.detail.take(DETAIL_TAIL_CHARS)}")
    if (realm.state != ComponentLifecycle.READY && realm.state != ComponentLifecycle.STARTING) {
        System.err.println("REALM DID NOT START: ${realm.detail}")
        exitProcess(EXIT_START)
    }

    // The gate: 127.0.0.1:3724 must ACCEPT a connection (realmd's own
    // listener; the protocol-level auth slice extends this in the
    // win_console bridge work).
    val listening = waitForListener(port = ServerRuntimeContract.REALM_PORT, timeoutMs = LISTEN_TIMEOUT_MS)
    if (!listening) {
        System.err.println("REALM NEVER LISTENED ON 3724")
        kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
        exitProcess(EXIT_LISTEN)
    }
    println("gate: 127.0.0.1:3724 accepted a connection")

    val finalRealm = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.REALM) }
    println("realm final: state=${finalRealm.state} detail=${finalRealm.detail.take(DETAIL_TAIL_CHARS)}")

    val stop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    println("stop: ok=${stop.ok} detail=${stop.detail}")
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    println("database stop: ok=${dbStop.ok} detail=${dbStop.detail}")

    if (!stop.ok || !dbStop.ok) {
        System.err.println("CLEAN STOP FAILED")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("BootRealmd", "realmd gate passed")
    println("REALMD GATE PASSED")
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_START = 3
private const val EXIT_LISTEN = 4
private const val EXIT_STOP = 5
private const val LISTEN_TIMEOUT_MS = 20_000L
private const val CONNECT_TIMEOUT_MS = 1_000
private const val POLL_SLEEP_MS = 250L
private const val DETAIL_TAIL_CHARS = 120

private fun waitForListener(port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(RealmEndpoint.LOOPBACK_ADDRESS, port), CONNECT_TIMEOUT_MS)
                return true
            }
        } catch (_: java.io.IOException) {
            Thread.sleep(POLL_SLEEP_MS)
        }
    }
    return false
}
