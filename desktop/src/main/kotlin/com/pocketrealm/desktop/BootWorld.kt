package com.pocketrealm.desktop

import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Phase-4 gate: the FULL world boot. Starts database + realm + world
 * in-process against the prepared world data (gradlew realmdHold's
 * siblings), waits for the world to reach READY (vmaps/mmaps load,
 * grid preheating), proves 127.0.0.1:8085 accepts a connection, saves,
 * and stops everything cleanly with the WAL-sidecar seal.
 *
 * Run from desktop/: gradlew bootWorld   (after win_prepare_data.py)
 */
@Suppress("TooGenericExceptionCaught") // bring-up gate: any failure exits honestly
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("BootWorld", "phase-4 world boot gate starting")

    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "boot-world", instanceToken = "boot-world")

    val preflight = kotlinx.coroutines.runBlocking { backend.preflight(spec) }
    if (!preflight.ok) {
        System.err.println("PREFLIGHT FAILED: ${preflight.detail}")
        exitProcess(EXIT_PREFLIGHT)
    }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec) }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec) }
    println("realm up; starting world (this loads vmaps/mmaps and takes a while)")

    val world = try {
        kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec) }
    } catch (failure: Throwable) {
        System.err.println("WORLD START REFUSED: ${failure.message}")
        exitProcess(EXIT_WORLD_START)
    }
    println("world: state=${world.state} detail=${world.detail.take(DETAIL_TAIL_CHARS)}")

    val ready = waitFor(backend, RuntimeComponent.WORLD, ComponentLifecycle.READY, WORLD_READY_TIMEOUT_MS)
    if (!ready) {
        val observed = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.WORLD) }
        System.err.println("WORLD NEVER REACHED READY: ${observed.state} ${observed.detail.take(DETAIL_TAIL_CHARS)}")
        kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
        exitProcess(EXIT_WORLD_READY)
    }
    println("world READY")

    if (!waitForPort(ServerRuntimeContract.WORLD_PORT.toInt(), LISTEN_TIMEOUT_MS)) {
        System.err.println("WORLD NEVER LISTENED ON ${ServerRuntimeContract.WORLD_PORT}")
        exitProcess(EXIT_LISTEN)
    }
    println("gate: 127.0.0.1:${ServerRuntimeContract.WORLD_PORT} accepted a connection")

    val save = kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }
    println("save: ok=${save.ok} detail=${save.detail}")

    val worldStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
    val realmStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    println("stops: world=${worldStop.ok} realm=${realmStop.ok} db=${dbStop.ok}")
    val allClean = worldStop.ok && realmStop.ok && dbStop.ok && save.ok
    if (!allClean) {
        System.err.println("CLEAN STOP OR SAVE FAILED")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("BootWorld", "phase-4 world boot gate passed")
    println("PHASE-4 WORLD BOOT GATE PASSED")
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_WORLD_START = 3
private const val EXIT_WORLD_READY = 4
private const val EXIT_LISTEN = 5
private const val EXIT_STOP = 6
private const val DETAIL_TAIL_CHARS = 160
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val LISTEN_TIMEOUT_MS = 60_000L
private const val POLL_SLEEP_MS = 1_000L
private const val CONNECT_TIMEOUT_MS = 2_000

private fun waitFor(
    backend: DesktopRuntimeBackend,
    component: RuntimeComponent,
    target: ComponentLifecycle,
    timeoutMs: Long,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    var settled = false
    while (!settled && System.currentTimeMillis() < deadline) {
        val observed = kotlinx.coroutines.runBlocking { backend.observe(component) }
        settled = observed.state == target || observed.state == ComponentLifecycle.FAILED
        if (!settled) Thread.sleep(POLL_SLEEP_MS)
    }
    return settled
}

private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { it.connect(InetSocketAddress(RealmEndpoint.LOOPBACK_ADDRESS, port), CONNECT_TIMEOUT_MS) }
            return true
        } catch (_: java.io.IOException) {
            Thread.sleep(POLL_SLEEP_MS)
        }
    }
    return false
}
