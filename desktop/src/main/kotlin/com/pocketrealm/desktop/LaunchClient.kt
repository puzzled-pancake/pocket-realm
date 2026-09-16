package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlin.system.exitProcess

/**
 * Bring-up: boot the full realm stack in-process, then launch
 * the user's WoW.exe against it (realmlist re-projected every launch).
 * The client stays up — press Enter in this console to save + stop the
 * realm cleanly (the client can also be closed first; Ctrl+C drains the
 * stack through the same shutdown hook instead of orphaning WoW.exe and
 * live WAL sidecars).
 *
 * Run from desktop/:
 *   gradlew launchClient -PclientDir="C:\Vanilla wow 1.12.1"
 */
@Suppress("TooGenericExceptionCaught") // bring-up entry: failures exit honestly
fun main() {
    val clientDir = File(System.getProperty("clientDir") ?: DEFAULT_CLIENT_DIR)
    if (!File(clientDir, "WoW.exe").isFile) {
        System.err.println("WoW.exe not found under $clientDir (pass -DclientDir=...)")
        exitProcess(EXIT_CLIENT)
    }
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("LaunchClient", "full-stack launch starting")

    val backend = DesktopRuntimeBackend(roots, clientDir)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = true,
    )
    val owner = ComponentOwner(sessionId = "launch-client", instanceToken = "launch-client")
    registerDrainHook(backend, owner)

    val preflight = kotlinx.coroutines.runBlocking { backend.preflight(spec) }
    if (!preflight.ok) {
        System.err.println("PREFLIGHT FAILED: ${preflight.detail}")
        exitProcess(EXIT_PREFLIGHT)
    }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec) }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec) }
    try {
        kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec) }
    } catch (failure: Throwable) {
        System.err.println("WORLD START REFUSED: ${failure.message}")
        drainStack(backend, owner, includeClient = false)
        exitProcess(EXIT_WORLD)
    }
    if (!waitForWorldReady(backend)) {
        System.err.println("WORLD NEVER REACHED READY")
        drainStack(backend, owner, includeClient = false)
        exitProcess(EXIT_WORLD)
    }
    println("realm stack READY (realmd 3724, world 8085)")

    val client = kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec) }
    println("client: ${client.state} pid=${client.pid} (${client.detail})")

    println(">>> Log in with any account (e.g. the authGate's AUTHGATE / AuthGate-Password-1).")
    println(">>> Press Enter here to save the world and stop the realm...")
    readLine()

    val save = kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }
    val stops = drainStack(backend, owner, includeClient = true)
    val allClean = save.ok && stops.all { it.second.ok }
    if (!allClean) {
        System.err.println("SESSION END NOT CLEAN (see legs above)")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("LaunchClient", "session ended cleanly")
}

/** Poll the world to READY; FAILED ends the wait as not-ready. */
@Suppress("ReturnCount") // one early return per settled terminal state is the clearest shape
private fun waitForWorldReady(backend: DesktopRuntimeBackend): Boolean {
    val deadline = System.currentTimeMillis() + WORLD_READY_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        val world = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.WORLD) }
        if (world.state == ComponentLifecycle.READY) return true
        if (world.state == ComponentLifecycle.FAILED) return false
        Thread.sleep(POLL_SLEEP_MS)
    }
    return false
}

/** Ctrl+C / unexpected-JVM-exit drain: without it, Windows terminates the
 * process with the natives mid-flight and leaves WoW.exe + WAL sidecars
 * behind. Bounded by the native stop timeouts; best-effort by design. */
private fun registerDrainHook(backend: DesktopRuntimeBackend, owner: ComponentOwner) {
    Runtime.getRuntime().addShutdownHook(Thread {
        drainStack(backend, owner, includeClient = true)
    })
}

/** Stop the whole stack in supervisor order (client → world → realm →
 * database); every leg's verdict is printed, never swallowed. Legs whose
 * component is already STOPPED are skipped, so the shutdown hook's
 * re-drain after a normal drain is a quiet no-op instead of an ownership
 * throw. */
private fun drainStack(
    backend: DesktopRuntimeBackend,
    owner: ComponentOwner,
    includeClient: Boolean,
): List<Pair<String, RuntimeActionResult>> {
    val legs = mutableListOf<Pair<String, RuntimeActionResult>>()
    fun leg(name: String, result: RuntimeActionResult) {
        legs += name to result
        println("$name=${result.ok} ${result.detail}")
    }
    fun pending(component: RuntimeComponent): Boolean = runCatching {
        kotlinx.coroutines.runBlocking { backend.observe(component) }.state != ComponentLifecycle.STOPPED
    }.getOrDefault(true)
    if (includeClient && pending(RuntimeComponent.CLIENT)) {
        runCatching {
            leg("client", kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.CLIENT, owner) })
        }
    }
    if (pending(RuntimeComponent.WORLD)) {
        runCatching { leg("world", kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }) }
    }
    if (pending(RuntimeComponent.REALM)) {
        runCatching { leg("realm", kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }) }
    }
    if (pending(RuntimeComponent.DATABASE)) {
        runCatching { leg("db", kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }) }
    }
    return legs
}

private const val DEFAULT_CLIENT_DIR = "C:/Vanilla wow 1.12.1"
private const val EXIT_CLIENT = 2
private const val EXIT_PREFLIGHT = 3
private const val EXIT_WORLD = 4
private const val EXIT_STOP = 5
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val POLL_SLEEP_MS = 1_000L
