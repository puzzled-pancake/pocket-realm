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
 * RP host: the desktop harness lane. Boots the full stack (database,
 * realm, world) exactly like the bring-up gates, then serves the world
 * console ops as one JSON object per stdin line -> one JSON object per
 * stdout line (all diagnostics go to stderr). EOF on stdin drains the
 * stack with a save, so a Python driver owns the whole lifecycle:
 *
 *   gradlew rpHost [-PclientDir=...] [-PrpClient=1]
 *                 [-PrpAccount=...] [-PrpPassword=...]
 *
 * Ops: hello | account {user,pass,gm} | chat {char,channel,target,text}
 *      | memory {player} | reset {player} | online | save | detail
 *      | autologin {user,pass} | stop
 *
 * The chat/memory/reset ops are the same native console verbs the
 * Android relay harness drives (world-chat, llm-memory-state,
 * reset-state) — identical world-thread dispatch, no adb.
 */
@Suppress("TooGenericExceptionCaught") // host lane: every failure answers as JSON, never dies
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("RpHost", "rp host starting")

    // -DclientDir wins; otherwise the staged settings' clientDir (avoids
    // passing space-bearing paths through gradlew.bat/cmd quoting).
    val clientDir = System.getProperty("clientDir")?.let { java.io.File(it) }
        ?: com.pocketrealm.storage.Settings.Snapshot.fromJson(
            runCatching { roots.settingsFile.readText() }.getOrDefault("{}"),
        ).clientDir.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
    val wantClient = System.getProperty("rpClient") == "1"
    val bootAccount = System.getProperty("rpAccount")
    val bootPassword = System.getProperty("rpPassword")

    val backend = DesktopRuntimeBackend(roots, clientDir)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = wantClient,
    )
    val owner = ComponentOwner(sessionId = "rp-host", instanceToken = "rp-host")
    Runtime.getRuntime().addShutdownHook(Thread {
        drain(backend, owner)
    })

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
        drain(backend, owner)
        exitProcess(EXIT_WORLD)
    }
    if (!waitForWorldReady(backend)) {
        System.err.println("WORLD NEVER REACHED READY")
        drain(backend, owner)
        exitProcess(EXIT_WORLD)
    }
    emit(JSONObject().put("event", "ready").put("world", "READY"))

    // The admission target: the world refuses random-bot logins while the
    // target is unset (the app layer owns it on both platforms). The host
    // registers one generation at boot (-DrpBots=N) and re-arms it per op.
    val bootBots = System.getProperty("rpBots")?.toIntOrNull() ?: 0
    if (bootBots > 0) emit(applyBotTarget(bootBots).put("event", "bots"))

    if (wantClient) {
        val client = kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec) }
        val clientPid = client.pid ?: 0
        System.err.println("client: ${client.state} pid=$clientPid (${client.detail})")
        emit(JSONObject().put("event", "client").put("pid", clientPid).put("state", client.state.name))
        if (bootAccount != null && bootPassword != null && clientPid > 0) {
            Thread.sleep(AUTOLOGIN_SETTLE_MS)
            val reason = Win32AutoLogin.tryLogin(clientPid, bootAccount, bootPassword)
            emit(JSONObject().put("event", "autologin").put(
                "ok", reason == null).put("reason", reason ?: "typed"))
        }
    }

    // The op loop: one JSON line in, one JSON line out.
    val sessionKeys = mutableMapOf<String, String>()
    var worldConn: RpWowClient.World? = null
    while (true) {
        val line = readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val response = try {
            val request = JSONObject(trimmed)
            when (val op = request.optString("op")) {
                "hello" -> JSONObject().put("ok", true).put("online", WorldNative.onlinePlayersNative())
                "account" -> {
                    val user = request.getString("user")
                    val pass = request.getString("pass")
                    val created = WorldNative.createAccountNative(
                        user, pass, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
                    val gm = request.optInt("gm", -1)
                    val gmResult = if (gm >= 0) WorldNative.setAccountGmLevelNative(
                        user, gm, ServerRuntimeContract.CONTROL_TIMEOUT_MS) else -1
                    JSONObject().put("ok", created == 0 || created == 10)
                        .put("created", created)
                        .put("gm", gmResult)
                }
                "chat" -> JSONObject(
                    WorldNative.worldChatNative(
                        request.getString("char"),
                        request.getString("channel"),
                        request.optString("target"),
                        request.getString("text"),
                        ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                    ),
                )
                "memory" -> JSONObject(WorldNative.llmMemoryStateNative(request.optString("player")))
                "reset" -> JSONObject(WorldNative.resetStateNative(request.optString("player")))
                "online" -> JSONObject().put("ok", true).put("online", WorldNative.onlinePlayersNative())
                "bots" -> {
                    val response = applyBotTarget(request.optInt("target", 0))
                    response.put("op", "bots")
                }
                "botstatus" -> {
                    val status = WorldNative.botStatusNative()
                    // values[9] of the performance lane is the login gate's
                    // measured CharacterDatabase round-trip: 0 = never
                    // sampled, UINT32_MAX = gate closed / probe expired.
                    val perf = WorldNative.performanceStatusNative()
                    JSONObject().put("ok", true)
                        .put("enabled", if (status.size > 2) status[2] != 0L else false)
                        .put("available", if (status.size > 3) status[3] else 0L)
                        .put("onlineBots", if (status.size > 4) status[4] else 0L)
                        .put("effectiveTarget", if (status.size > 5) status[5] else 0L)
                        .put("accounts", if (status.size > 6) status[6] else 0L)
                        .put("dbProbeDelayMs", if (perf.size > 9) perf[9] else -1L)
                }
                "save" -> JSONObject().put(
                    "ok",
                    kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }.ok,
                )
                "detail" -> JSONObject().put("ok", true).put("detail", WorldNative.detailNative())
                "autologin" -> {
                    val observation = kotlinx.coroutines.runBlocking {
                        backend.observe(RuntimeComponent.CLIENT)
                    }
                    val pid = observation.pid ?: 0
                    if (pid <= 0) {
                        JSONObject().put("ok", false).put("reason", "no client process")
                    } else {
                        val reason = Win32AutoLogin.tryLogin(
                            pid, request.getString("user"), request.getString("pass"))
                        JSONObject().put("ok", reason == null)
                            .put("reason", reason ?: "typed")
                    }
                }
                "stop" -> {
                    emit(JSONObject().put("op", "stop").put("ok", true))
                    break
                }
                "proto-login" -> {
                    val user = request.getString("user").uppercase()
                    val pass = request.getString("pass")
                    val srp = VsrpMath.verifierFor(user, pass)
                    val realmd = com.pocketrealm.database.DatabaseSqliteControlPlane
                        .databaseFile(roots.sqliteDatadir, "classicrealmd")
                    com.pocketrealm.database.DesktopSqliteConnection(realmd.absolutePath).use { db ->
                        db.exec("UPDATE account SET v = '${srp.vHex}', s = '${srp.sHex}' WHERE username = '$user';")
                    }
                    val key = VsrpClient.logon(
                        RealmEndpoint.LOOPBACK_ADDRESS,
                        ServerRuntimeContract.REALM_PORT.toInt(),
                        user, srp,
                    )
                    sessionKeys[user] = key
                    JSONObject().put("ok", true).put("user", user)
                }
                "proto-world" -> {
                    val user = request.getString("user").uppercase()
                    val key = sessionKeys[user]
                    if (key == null) {
                        JSONObject().put("ok", false).put("reason", "proto-login first")
                    } else {
                        worldConn?.close()
                        val conn = RpWowClient.World(
                            RealmEndpoint.LOOPBACK_ADDRESS,
                            ServerRuntimeContract.WORLD_PORT.toInt(),
                            user, key,
                        )
                        val result = conn.auth()
                        worldConn = conn
                        JSONObject().put("ok", true).put("authResult", result)
                    }
                }
                "proto-create" -> JSONObject().put(
                    "ok", true,
                ).put("result", worldConn?.charCreate(request.getString("name")))
                "proto-chars" -> {
                    val chars = worldConn?.charEnum() ?: emptyList()
                    val rows = chars.map { (guid, name) ->
                        JSONObject().put("guid", guid).put("name", name)
                    }
                    JSONObject().put("ok", true).put("chars", rows)
                }
                "proto-pick" -> {
                    worldConn?.playerLogin(request.getLong("guid"))
                    Thread.sleep(PICK_SETTLE_MS)
                    JSONObject().put("ok", true)
                }
                "proto-chat" -> {
                    val conn = worldConn
                    if (conn == null) {
                        JSONObject().put("ok", false).put("reason", "no world conn")
                    } else {
                        val emotes = mutableListOf<Map<String, Long>>()
                        val lines = conn.chatAndCapture(
                            request.optString("kind", "whisper"),
                            request.optString("target"),
                            request.getString("text"),
                            request.optLong("drainMs", 8000L),
                            emotes,
                            request.optBoolean("includeSelf", false),
                        )
                        val rows = lines.map { line ->
                            JSONObject()
                                .put("type", line.type)
                                .put("senderGuid", line.senderGuid)
                                .put("text", line.text)
                        }
                        val emoteRows = emotes.map { emote ->
                            val row = JSONObject()
                            for ((key, value) in emote) row.put(key, value)
                            row
                        }
                        JSONObject().put("ok", true)
                            .put("lines", rows)
                            .put("emotes", emoteRows)
                    }
                }
                "proto-close" -> {
                    worldConn?.close()
                    worldConn = null
                    JSONObject().put("ok", true)
                }
                else -> JSONObject().put("ok", false).put("reason", "unknown-op:$op")
            }
        } catch (failure: Throwable) {
            JSONObject().put("ok", false).put("reason", failure.message ?: failure.toString())
        }
        emit(response.put("op", requestOp(trimmed)))
    }

    val save = kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }
    System.err.println("save: ok=${save.ok} ${save.detail}")
    drain(backend, owner)
    DesktopLog.i("RpHost", "rp host drained")
}

/** The op name for echo-back (best-effort; errors echo a blank). */
private fun requestOp(line: String): String = runCatching {
    JSONObject(line).optString("op")
}.getOrDefault("")

/** Register a fresh admission generation and set the random-bot target
 * (the app layers' startAdmissionMonitor contract, minimal form: the
 * harness needs a fixed healthy-machine target, no resource sampling). */
private fun applyBotTarget(target: Int): JSONObject {
    val generation = System.nanoTime()
    val beginRc = WorldNative.beginAdmissionBotTargetGenerationNative(generation)
    if (beginRc != 0) {
        return JSONObject().put("ok", false).put("reason", "begin:$beginRc")
    }
    val rc = WorldNative.setAdmissionBotTargetNative(target, generation)
    return JSONObject().put("ok", rc == 0).put("target", target).put("rc", rc)
}

/** One protocol line on stdout, flushed (JavaExec pipes buffer otherwise).
 * Every line carries the @@RP@@ prefix: the native lane and gradle print
 * their own banner text to stdout, and the Python driver must be able to
 * tell protocol from noise. */
private fun emit(value: JSONObject) {
    System.out.println("@@RP@@" + value.toString())
    System.out.flush()
}

/** Poll the world to READY; FAILED ends the wait as not-ready. */
@Suppress("ReturnCount")
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

/** Best-effort stack drain (world -> realm -> database; client too). */
private fun drain(backend: DesktopRuntimeBackend, owner: ComponentOwner) {
    for (component in listOf(
        RuntimeComponent.CLIENT, RuntimeComponent.WORLD,
        RuntimeComponent.REALM, RuntimeComponent.DATABASE,
    )) {
        runCatching {
            kotlinx.coroutines.runBlocking { backend.stop(component, owner) }
        }
    }
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_WORLD = 3
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val POLL_SLEEP_MS = 1_000L
private const val AUTOLOGIN_SETTLE_MS = 25_000L
private const val PICK_SETTLE_MS = 3_000L
