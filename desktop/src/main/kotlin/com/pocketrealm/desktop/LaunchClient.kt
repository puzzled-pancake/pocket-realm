package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlin.system.exitProcess

/**
 * Phase-4d bring-up: boot the full realm stack in-process, then launch
 * the user's WoW.exe against it (realmlist re-projected every launch).
 * The client stays up — press Enter in this console to save + stop the
 * realm cleanly (the client can also be closed first).
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
        exitProcess(EXIT_WORLD)
    }
    val deadline = System.currentTimeMillis() + WORLD_READY_TIMEOUT_MS
    var ready = false
    while (!ready && System.currentTimeMillis() < deadline) {
        val world = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.WORLD) }
        if (world.state == ComponentLifecycle.READY) ready = true
        else if (world.state == ComponentLifecycle.FAILED) break
        else Thread.sleep(POLL_SLEEP_MS)
    }
    if (!ready) {
        System.err.println("WORLD NEVER REACHED READY")
        exitProcess(EXIT_WORLD)
    }
    println("realm stack READY (realmd 3724, world 8085)")

    val client = kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec) }
    println("client: ${client.state} pid=${client.pid} (${client.detail})")

    println(">>> Log in with any account (e.g. the authGate's AUTHGATE / AuthGate-Password-1).")
    println(">>> Press Enter here to save the world and stop the realm...")
    readLine()

    val save = kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }
    val clientStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.CLIENT, owner) }
    val worldStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
    val realmStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    println("save=${save.ok} client=${clientStop.ok} world=${worldStop.ok} realm=${realmStop.ok} db=${dbStop.ok}")
    DesktopLog.i("LaunchClient", "session ended")
}

private const val DEFAULT_CLIENT_DIR = "C:/Vanilla wow 1.12.1"
private const val EXIT_CLIENT = 2
private const val EXIT_PREFLIGHT = 3
private const val EXIT_WORLD = 4
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val POLL_SLEEP_MS = 1_000L
