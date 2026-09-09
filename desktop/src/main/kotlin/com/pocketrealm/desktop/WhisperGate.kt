package com.pocketrealm.desktop

import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Phase-5 bridge gate: the world-chat injection surface, proven against
 * the LIVE world without a client (the conversational LLM gates need a
 * real player session — that half is the interactive campaign):
 *
 *  - onlinePlayers is honest (0 without a client),
 *  - a whisper from an offline sender fails with sender-not-online
 *    (the documented injection design),
 *  - an unknown channel fails with unknown-channel,
 *  - the LLM memory state reads back as honest JSON for any name.
 *
 * Run from desktop/: gradlew whisperGate
 */
@Suppress("TooGenericExceptionCaught", "LongMethod") // bring-up gate, linear by design
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("WhisperGate", "phase-5 bridge gate starting")

    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "whisper-gate", instanceToken = "whisper-gate")

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
    println("world READY; exercising the bridge surface")

    val online = WorldNative.onlinePlayersNative()
    check(online == 0) { "expected 0 online players without a client, got $online" }
    println("onlinePlayers: $online (honest without a client)")

    val whisper = JSONObject(
        WorldNative.worldChatNative(
            "NoSuchPlayer", "whisper", "Anybody", "hello?", CHAT_TIMEOUT_MS,
        ),
    )
    check(!whisper.getBoolean("ok")) { "offline-sender whisper must fail: $whisper" }
    check(whisper.optString("reason").contains("sender-not-online")) {
        "unexpected failure reason: ${whisper.optString("reason")}"
    }
    println("offline whisper refused: ${whisper.optString("reason")} (the documented posture)")

    val bogusChannel = JSONObject(
        WorldNative.worldChatNative("NoSuchPlayer", "bogus", "Anybody", "x", CHAT_TIMEOUT_MS),
    )
    check(!bogusChannel.getBoolean("ok") && bogusChannel.optString("reason") == "unknown-channel") {
        "unknown channel must fail honestly: $bogusChannel"
    }
    println("unknown channel refused: ${bogusChannel.optString("reason")}")

    val memory = JSONObject(WorldNative.llmMemoryStateNative("NoSuchPlayer"))
    check(memory.has("ok")) { "memory state must be JSON: $memory" }
    println("llmMemoryState: $memory")

    val worldStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
    val realmStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    if (!worldStop.ok || !realmStop.ok || !dbStop.ok) {
        System.err.println("CLEAN STOP FAILED: ${worldStop.detail} / ${realmStop.detail} / ${dbStop.detail}")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("WhisperGate", "phase-5 bridge gate passed")
    println("PHASE-5 BRIDGE GATE PASSED")
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_WORLD = 3
private const val EXIT_STOP = 4
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val POLL_SLEEP_MS = 1_000L
private const val CHAT_TIMEOUT_MS = 10_000L
