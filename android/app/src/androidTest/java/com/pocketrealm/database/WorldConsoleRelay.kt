package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Long-lived console relay for interactive host driving ("a script to
 * play with the server"): polls filesDir/console-in.json, executes one
 * command against the REAL product Binder surface, writes the response
 * to filesDir/console-out.json, deletes the in-file, and keeps the
 * services bound so the stack STAYS UP between commands (a plain
 * one-shot instrumentation would tear everything down on exit).
 *
 * The host side is tools/world_console.py (adb root + push/pull of the
 * two files; run-as cannot cross the API-35 FUSE boundary).
 *
 * Ops (arg1/arg2 payload):
 *   db-status | db-health | db-stop
 *   realm-start | realm-status | realm-stop
 *   world-start | world-start-bot <profileId> | world-status |
 *   world-bots <target> | world-bot-status | world-save |
 *   world-account <user> <pass> | world-verify <user> <pass> |
 *   world-gm <user> <level> | world-account-status <user> |
 *   world-persistence <user> <char> | world-pause <0|1> |
 *   world-kill | world-stop
 *   stack-up-bot <profileId>   (db init+migrations+start → realm → world)
 *   quit
 */
@RunWith(AndroidJUnit4::class)
class WorldConsoleRelay {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connections = ArrayList<ServiceConnection>()
    private var control: IDatabaseControl? = null
    private var realm: Bound<IRealmControl>? = null
    private var world: Bound<IWorldControl>? = null

    private val inFile = File(context.filesDir, "console-in.json")
    private val outFile = File(context.filesDir, "console-out.json")
    private val startedAtMs = System.currentTimeMillis()

    @Test fun relay() {
        inFile.delete(); outFile.delete()
        while (true) {
            val command = readCommand() ?: run {
                Thread.sleep(300); continue
            }
            val op = command.optString("op")
            val response = if (op == "quit") {
                JSONObject().put("ok", true).put("op", op).put("bye", true)
            } else {
                runCatching { execute(op, command) }
                    .fold(onSuccess = { it },
                          onFailure = { failure ->
                              JSONObject().put("ok", false).put("op", op)
                                  .put("error", failure.message
                                      ?: failure.javaClass.simpleName) })
            }
            outFile.writeText(response.put("atMs", System.currentTimeMillis()).toString())
            inFile.delete()
            if (op == "quit") return
        }
    }

    private fun readCommand(): JSONObject? = runCatching {
        if (!inFile.isFile) null else JSONObject(inFile.readText())
    }.getOrNull()

    private fun db(): IDatabaseControl =
        control ?: bind("com.pocketrealm.database.DatabaseService")
            { IDatabaseControl.Stub.asInterface(it) }.also { control = it }.api

    private fun realmApi(): IRealmControl =
        realm?.api ?: bind("com.pocketrealm.server.RealmRuntimeService")
            { IRealmControl.Stub.asInterface(it) }.also { realm = it }.api

    private fun worldApi(): IWorldControl =
        world?.api ?: bind("com.pocketrealm.server.WorldRuntimeService")
            { IWorldControl.Stub.asInterface(it) }.also { world = it }.api

    private fun execute(op: String, command: JSONObject): JSONObject {
        val arg1 = command.optString("arg1")
        val arg2 = command.optString("arg2")
        return when (op) {
            "db-status" -> passthrough(op) { db().status() }
            "db-health" -> passthrough(op) { db().queryHealth() }
            "db-stop" -> passthrough(op) { db().stop() }
            "realm-start" -> passthrough(op) { realmApi().start() }
            "realm-status" -> passthrough(op) { realmApi().status() }
            "realm-stop" -> passthrough(op) { realmApi().stop() }
            "world-start" -> passthrough(op) { worldApi().start() }
            "world-start-bot" -> passthrough(op) { worldApi().startBotProfile(arg1) }
            "world-status" -> passthrough(op) { worldApi().status() }
            "world-bots" -> passthrough(op) { worldApi().setBotTarget(arg1.toIntOrNull() ?: 0) }
            "world-bot-status" -> passthrough(op) { worldApi().botStatus() }
            "world-save" -> passthrough(op) { worldApi().save() }
            "world-account" -> passthrough(op) { worldApi().createAccount(arg1, arg2) }
            "world-verify" -> passthrough(op) { worldApi().verifyAccountPassword(arg1, arg2) }
            "world-gm" -> passthrough(op) { worldApi().setAccountGmLevel(arg1, arg2.toIntOrNull() ?: 0) }
            "world-account-status" -> passthrough(op) { worldApi().accountStatus(arg1) }
            "world-persistence" -> passthrough(op) { worldApi().characterPersistence(arg1, arg2) }
            "world-pause" -> passthrough(op) { worldApi().setWorldPaused(arg1.toIntOrNull() ?: 0) }
            "world-kill" -> {
                val killed = runCatching { worldApi().killForTest() }
                    .fold({ JSONObject().put("ok", true).put("op", op).put("killed", true) },
                          { JSONObject().put("ok", true).put("op", op).put("killed", true)
                              .put("note", "killForTest never returns cleanly: ${it.message}") })
                // Both world exits retire the :world process (killForTest kills
                // immediately; the clean stop arms the 250 ms retireCleanProcess
                // fuse) - drop the stale proxy so the next world op rebinds to
                // the fresh process.
                world?.close(); world = null
                killed
            }
            "world-stop" -> {
                val stopped = passthrough(op) { worldApi().stop() }
                world?.close(); world = null
                stopped
            }
            "ping" -> JSONObject().put("ok", true).put("op", op)
                .put("alive", true).put("uptimeMs",
                    System.currentTimeMillis() - startedAtMs)
            "stack-up-bot" -> stackUpBot(arg1)
            else -> JSONObject().put("ok", false).put("op", op)
                .put("error", "unknown op (see WorldConsoleRelay doc)")
        }
    }

    private fun passthrough(op: String, call: () -> String): JSONObject =
        JSONObject(call()).put("op", op)

    private fun stackUpBot(profileId: String): JSONObject {
        val out = JSONObject().put("op", "stack-up-bot")
        val database = db()
        out.put("dbInit", JSONObject(database.initialize()).optBoolean("ok"))
        out.put("dbMigrations", JSONObject(database.applyPinnedMigrations()).optBoolean("ok"))
        out.put("dbStart", JSONObject(database.start()).optBoolean("ok"))
        out.put("dbHealth", JSONObject(database.queryHealth()).optBoolean("ok"))
        out.put("realmStart", JSONObject(realmApi().start()).optBoolean("ok"))
        val worldStart = JSONObject(worldApi().startBotProfile(profileId))
        out.put("worldStartBotProfile", worldStart.optBoolean("ok"))
        out.put("ok", out.optBoolean("dbStart") && out.optBoolean("realmStart")
            && worldStart.optBoolean("ok"))
        return out
    }

    private fun <T> bind(component: String, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var binder: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = convert(service!!); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connections.add(connection)
        check(context.bindService(Intent().setClassName(context, component), connection,
            Context.BIND_AUTO_CREATE)) { "bindService failed: $component" }
        check(latch.await(60, TimeUnit.SECONDS)) { "bind timeout: $component" }
        return Bound(binder!!, connection)
    }

    private inner class Bound<T>(val api: T, private val connection: ServiceConnection) {
        fun close() {
            runCatching { context.unbindService(connection) }
            connections.remove(connection)
        }
    }
}
