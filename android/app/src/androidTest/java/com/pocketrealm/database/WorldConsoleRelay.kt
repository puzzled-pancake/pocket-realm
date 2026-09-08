package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.BuildConfig
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
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
 * Ops (arg1/arg2 payload; world-chat also reads arg3/arg4):
 *   db-status | db-health | db-stop
 *   realm-start | realm-status | realm-stop
 *   world-start | world-start-bot <profileId> | world-status |
 *   world-bots <target> | world-bot-status | world-save |
 *   world-account <user> <pass> | world-verify <user> <pass> |
 *   world-gm <user> <level> | world-account-status <user> |
 *   world-persistence <user> <char> | world-pause <0|1> |
 *   world-kill | world-stop
 *   world-chat <char> <channel> [target] <text>   (H2 relay-min: channel
 *       is say|party|whisper|yell; target = receiving bot name, whisper
 *       only; text rides arg4 - see tools/rp_harness)
 *   reset-state [player]     (H2 relay-min: clears bot_player_facts +
 *       bot_player_relationship for one player or all)
 *   llm-memory-state <player> (H2 relay-min: per-bot relationship rows +
 *       per-(bot,prefix) fact counts; empty player = world summary)
 *   stack-up-bot <profileId>   (supervisor DB-RECOVERY gate when the
 *       journal is dirty + engine clean-marker verify/heal when it is
 *       not, then db init+migrations+start → realm → world)
 *   quit
 *   ping   (harness attach probe: alive/uptimeMs + runtimeBuildId and the
 *       native source-commit pins baked from the lane lockfile at build
 *       time, so a stale APK is detectable at attach; realm-status /
 *       world-status responses carry the same telltale fields)
 */
@RunWith(AndroidJUnit4::class)
class WorldConsoleRelay {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connections = ArrayList<ServiceConnection>()
    private var control: Bound<IDatabaseControl>? = null
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
                    .fold(onSuccess = { it }, onFailure = { opFailure(op, it) })
            }
            outFile.writeText(response.put("atMs", System.currentTimeMillis()).toString())
            inFile.delete()
            if (op == "quit") return
        }
    }

    private fun readCommand(): JSONObject? = runCatching {
        if (!inFile.isFile) null else JSONObject(inFile.readText())
    }.getOrNull()

    // Failure shape follows the services' guarded() convention (errorClass +
    // truthful error). A DeadObjectException means the target process died
    // mid-op - the per-op pingBinder revalidation cannot fully close that
    // race - so drop every dead cache (the next op rebinds) and answer with
    // the dead component's NOT_READY class instead of a bare exception name
    // the host cannot branch on.
    private fun opFailure(op: String, failure: Throwable): JSONObject {
        val deadComponents = ArrayList<String>()
        if (control?.alive() == false) { control?.close(); control = null; deadComponents.add("DATABASE") }
        if (realm?.alive() == false) { realm?.close(); realm = null; deadComponents.add("REALM") }
        if (world?.alive() == false) { world?.close(); world = null; deadComponents.add("WORLD") }
        val response = JSONObject().put("ok", false).put("op", op)
            .put("errorClass", if (failure is DeadObjectException && deadComponents.isNotEmpty())
                deadComponents.first() + "_NOT_READY" else failure.javaClass.simpleName)
            .put("error", failure.message ?: failure.javaClass.simpleName)
        if (failure is DeadObjectException)
            response.put("remedy", "component process died mid-op; the stale binder was " +
                "dropped and the next op rebinds - retry the op (world-start-bot to boot a world)")
        return response
    }

    // Cached proxies are revalidated per op: a :world death used to wedge the
    // relay permanently (every later op answered DeadObjectException until
    // world-kill's unconditional unbind). A dead cache is closed so the
    // rebind below reconnects to the recreated service process.
    private fun <T> rebind(cached: Bound<T>?, component: String, convert: (IBinder) -> T): Bound<T> {
        if (cached != null && cached.alive()) return cached
        cached?.close()
        return bind(component, convert)
    }

    private fun db(): IDatabaseControl =
        rebind(control, "com.pocketrealm.database.DatabaseService")
            { IDatabaseControl.Stub.asInterface(it) }.also { control = it }.api

    private fun realmApi(): IRealmControl =
        rebind(realm, "com.pocketrealm.server.RealmRuntimeService")
            { IRealmControl.Stub.asInterface(it) }.also { realm = it }.api

    private fun worldApi(): IWorldControl =
        rebind(world, "com.pocketrealm.server.WorldRuntimeService")
            { IWorldControl.Stub.asInterface(it) }.also { world = it }.api

    private fun execute(op: String, command: JSONObject): JSONObject {
        val arg1 = command.optString("arg1")
        val arg2 = command.optString("arg2")
        val arg3 = command.optString("arg3")
        val arg4 = command.optString("arg4")
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
            // H2 relay-min: arg1 = sending char name, arg2 = channel
            // (say|party|whisper|yell), arg3 = receiving bot name (whisper
            // only), arg4 = the chat text
            "world-chat" -> passthrough(op) {
                worldApi().worldChat(arg1, arg2, arg3, arg4) }
            // H2 relay-min: arg1 = player name ("" = every player)
            "reset-state" -> passthrough(op) { worldApi().resetState(arg1) }
            // H2 relay-min: arg1 = player name ("" = world summary with
            // online bot names)
            "llm-memory-state" -> passthrough(op) { worldApi().llmMemoryState(arg1) }
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
                // Attach-time stale-APK telltale: the harness compares these
                // against the CURRENT lane lockfile (schemas/realm-runtime-
                // lockfile*.json) before driving any op, so a stale relay is
                // refused at attach instead of failing mid-run on missing JNI
                // ops (world-chat/reset-state/llm-memory-state). Same values
                // ride realm-status/world-status via ServerStatusJson.
                .put("runtimeBuildId", BuildConfig.NATIVE_RUNTIME_BUILD_ID)
                .put("nativeCmangosCommit", BuildConfig.NATIVE_CMANGOS_COMMIT)
                .put("nativePlayerbotsCommit", BuildConfig.NATIVE_PLAYERBOTS_COMMIT)
            "stack-up-bot" -> stackUpBot(arg1)
            else -> JSONObject().put("ok", false).put("op", op)
                .put("error", "unknown op (see WorldConsoleRelay doc)")
        }
    }

    private fun passthrough(op: String, call: () -> String): JSONObject =
        JSONObject(call()).put("op", op)

    private fun stackUpBot(profileId: String): JSONObject {
        val out = JSONObject().put("op", "stack-up-bot")
        // DB-RECOVERY gate (the dirty-stop lesson): driving db
        // initialize/migrations/start directly over an unsealed generation
        // failed dbMigrations/dbStart and left the world leg at DB_REVISION.
        // The sanctioned heal is the supervisor's recovery lane - the same
        // DurableRuntimeSupervisor.recover() an app-led Start realm runs -
        // driven over its Binder, never re-implemented here, PLUS the
        // engine's own marker check (relay composites are not journaled, so
        // the journal alone cannot prove the generation sealed). If the lane
        // cannot heal, fail fast and actionably instead of driving the legs.
        val recovery = healDatabaseThroughSupervisor()
        out.put("dbRecovery", recovery.optBoolean("ok"))
            .put("dbRecoveryDetail", recovery.optString("action", recovery.optString("error")))
        if (!recovery.optBoolean("ok")) {
            return out.put("ok", false)
                .put("errorClass", "DB_REVISION")
                .put("error", recovery.optString("error") + "; remedy: one app-led Start " +
                    "realm (Home -> Start) heals the database, then re-run stack-up-bot")
        }
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
        // A failed leg never answers a bare ok:false (the QA hit:
        // dbMigrations/dbStart false with no errorClass and no remedy): the
        // composite's foundation is the database, so the legs failure carries
        // the gate path's own errorClass and the SAME remedy string, with the
        // error naming exactly which legs failed.
        if (!out.optBoolean("ok")) {
            val failedLegs = listOf(
                "dbInit", "dbMigrations", "dbStart", "dbHealth",
                "realmStart", "worldStartBotProfile",
            ).filterNot { leg -> out.optBoolean(leg) }
            return out.put("errorClass", "DB_REVISION")
                .put("error", "legs failed: ${failedLegs.joinToString(",")}; " +
                    "remedy: one app-led Start realm (Home -> Start) heals the database, " +
                    "then re-run stack-up-bot")
        }
        return out
    }

    /**
     * DB-RECOVERY gate for stack-up-bot: a dirty supervisor journal (a dirty
     * stop, or the attach restart tearing an app-led generation down) is
     * healed through the supervisor's own recover verb - RealmService's
     * DurableRuntimeSupervisor.recover() -> recoverDatabase() ->
     * prepareDatabaseForStart(), the exact DB-RECOVERY lane an app-led
     * Start realm runs - so the composite's db legs always run over a
     * sealed, prepared generation. A clean stopped journal skips that lane.
     *
     * The journal is not the only truth: relay composite boots are NOT
     * journaled, so a power-loss/emulator kill during one leaves the journal
     * STOPPED-clean while the engine died unsealed (db-status cleanMarker
     * false). The gate therefore also verifies the ENGINE's own marker and,
     * when it is unsealed, heals the generation with the engine's own
     * recover verb - the exact RECOVER_DIRTY_GENERATION leg the supervisor's
     * prepare lane drives on every DATABASE start - before answering
     * none-needed.
     */
    private fun healDatabaseThroughSupervisor(): JSONObject = runCatching {
        val supervisor = bind("com.pocketrealm.service.RealmService")
            { IRuntimeSupervisorControl.Stub.asInterface(it) }
        try {
            val deadline = System.currentTimeMillis() + SUPERVISOR_RECOVERY_TIMEOUT_MS
            var recoverySubmitted = false
            while (System.currentTimeMillis() < deadline) {
                val status = JSONObject(supervisor.api.status())
                // clean=true on a settled phase (STOPPED after recovery, or
                // UNCONFIGURED/ERROR on a never-started journal) means the
                // supervisor owns no generation: nothing to heal. Any other
                // phase is an active or interrupted generation.
                val phase = status.optString("phase")
                val settled = phase in setOf("STOPPED", "UNCONFIGURED", "ERROR")
                if (settled && status.optBoolean("clean"))
                    return verifyEngineSealedOrHeal(
                        if (recoverySubmitted) "supervisor-recover" else "none-needed")
                if (phase == "RECOVERING") {
                    Thread.sleep(1_000); continue
                }
                if (!recoverySubmitted) {
                    // the verb answers accepted immediately; a busy
                    // coordinator (another lifecycle operation in flight) is
                    // retried until the deadline
                    if (JSONObject(supervisor.api.recover()).optBoolean("accepted"))
                        recoverySubmitted = true
                    Thread.sleep(1_000); continue
                }
                // a submitted recovery settled anywhere but a clean journal:
                // it failed; the journal fields name the leg
                return JSONObject().put("ok", false)
                    .put("error", "supervisor recovery did not reach a clean settled journal" +
                        " (${phase}/${status.optString("lastDurableAction")}):" +
                        " ${status.optString("lastError")}")
            }
            JSONObject().put("ok", false)
                .put("error", "supervisor DB recovery did not settle within " +
                    "${SUPERVISOR_RECOVERY_TIMEOUT_MS / 1000}s")
        } finally {
            supervisor.close()
        }
    }.getOrElse { failure ->
        JSONObject().put("ok", false)
            .put("error", "supervisor DB-RECOVERY lane unreachable: " +
                (failure.message ?: failure.javaClass.simpleName))
    }

    /**
     * The gate's engine half: success is a clean journal AND a sealed engine
     * (db-status cleanMarker true). An unsealed generation is healed with the
     * engine's own recover verb, then re-verified; a recovery that the
     * engine refuses (fail-closed: pending transaction, live process) fails
     * the gate with the engine's typed error.
     */
    private fun verifyEngineSealedOrHeal(action: String): JSONObject = runCatching {
        val engine = JSONObject(db().status())
        if (engine.optBoolean("cleanMarker")) {
            return JSONObject().put("ok", true).put("action", action)
        }
        val recovered = JSONObject(db().recover())
        if (!recovered.optBoolean("ok")) {
            return JSONObject().put("ok", false)
                .put("error", "engine dirty-generation recovery failed: " +
                    recovered.optString("error", recovered.optString("errorClass")))
        }
        if (!JSONObject(db().status()).optBoolean("cleanMarker")) {
            return JSONObject().put("ok", false)
                .put("error", "engine clean-stop marker still absent after recovery")
        }
        JSONObject().put("ok", true).put("action", "engine-recover")
    }.getOrElse { failure ->
        JSONObject().put("ok", false)
            .put("error", "engine clean-marker verification failed: " +
                (failure.message ?: failure.javaClass.simpleName))
    }

    private fun <T> bind(component: String, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var binder: T? = null
        var rawBinder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = convert(service!!); rawBinder = service; latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connections.add(connection)
        check(context.bindService(Intent().setClassName(context, component), connection,
            Context.BIND_AUTO_CREATE)) { "bindService failed: $component" }
        check(latch.await(60, TimeUnit.SECONDS)) { "bind timeout: $component" }
        return Bound(binder!!, rawBinder!!, connection)
    }

    // The cached half of the per-op liveness contract: linkToDeath flips the
    // flag the moment the owning process dies (a crash, killForTest, or a
    // clean-stop retire), alive() re-proves it with a real pingBinder round
    // trip, and close() always unlinks so a recycled connection never fires
    // into a dropped cache.
    private inner class Bound<T>(
        val api: T,
        private val binder: IBinder,
        private val connection: ServiceConnection,
    ) {
        @Volatile private var dead = false
        private val deathRecipient = IBinder.DeathRecipient { dead = true }

        init { runCatching { binder.linkToDeath(deathRecipient, 0) } }

        fun alive(): Boolean = !dead && binder.pingBinder()

        fun close() {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { context.unbindService(connection) }
            connections.remove(connection)
        }
    }

    companion object {
        /** stack-up-bot's supervisor recovery budget; the host console waits 900 s for slow ops. */
        private const val SUPERVISOR_RECOVERY_TIMEOUT_MS = 420_000L
    }
}
