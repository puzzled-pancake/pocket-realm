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
 *       (both world boots prove the DB-lane precondition first: a stopped
 *       or unanswering lane answers the typed refusal instead of firing a
 *       native boot that never returns, and the start call itself is
 *       bounded like every composite stage - a wedged boot is named, not
 *       sat on)
 *   world-bots <target> | world-bot-status | world-save |
 *   world-account <user> <pass> | world-verify <user> <pass> |
 *   world-gm <user> <level> | world-account-status <user> |
 *   world-persistence <user> <char> | world-pause <0|1> |
 *   world-kill | world-stop
 *   world-chat <char> <channel> [target] <text>   (relay-minimum: channel
 *       is say|party|whisper|yell; target = receiving bot name, whisper
 *       only; text rides arg4 - see tools/rp_harness)
 *   reset-state [player]     (relay-minimum: clears bot_player_facts +
 *       bot_player_relationship for one player or all)
 *   llm-memory-state <player> (relay-minimum: per-bot relationship rows +
 *       per-(bot,prefix) fact counts; empty player = world summary)
 *   stack-up-bot <profileId>   (supervisor DB-RECOVERY gate when the
 *       journal is dirty + engine clean-marker verify/heal when it is
 *       not, then db init+migrations+start → health gate → realm → world;
 *       the composite runs through StackUpComposite - every stage is
 *       bounded by its designed window capped by the op budget's
 *       remainder, a not-running lane refuses BEFORE realm/world fire,
 *       and the whole op answers inside its total budget: a stage that
 *       outlives its window fails the op naming the refusing component,
 *       a spent budget answers the named STACKUP_TOTAL_DEADLINE refusal,
 *       never an indefinite hang past the host console's 900s slow-op
 *       window)
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

    // Cached proxies are revalidated per op: a stale proxy to a dead
    // :world process would wedge the relay permanently (every later op
    // answering DeadObjectException). A dead cache is closed so the
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
            // The DB lane is a world boot's foundation (dependency order
            // DB -> realmd -> world): the recorded row -09 world-start
            // fired a native boot into a STOPPED lane and hung. The
            // precondition is proven first - a stopped or unanswering lane
            // answers the typed refusal naming the sanctioned boot path -
            // and the start call itself is bounded like every composite
            // stage, so a wedged native boot is named, never sat on.
            "world-start" -> worldBootRefusal("world-start", databaseStatus())
                ?: boundWorldStart("world-start") { worldApi().start() }
            "world-start-bot" -> worldBootRefusal("world-start-bot", databaseStatus())
                ?: boundWorldStart("world-start-bot") { worldApi().startBotProfile(arg1) }
            "world-status" -> passthrough(op) { worldApi().status() }
            "world-bots" -> passthrough(op) { worldApi().setBotTarget(arg1.toIntOrNull() ?: 0) }
            "world-bot-status" -> passthrough(op) { worldApi().botStatus() }
            "world-save" -> passthrough(op) { worldApi().save() }
            "world-account" -> passthrough(op) { worldApi().createAccount(arg1, arg2) }
            "world-verify" -> passthrough(op) { worldApi().verifyAccountPassword(arg1, arg2) }
            "world-gm" -> passthrough(op) { worldApi().setAccountGmLevel(arg1, arg2.toIntOrNull() ?: 0) }
            "world-account-status" -> passthrough(op) { worldApi().accountStatus(arg1) }
            "world-persistence" -> passthrough(op) { worldApi().characterPersistence(arg1, arg2) }
            // arg1 = sending char name, arg2 = channel
            // (say|party|whisper|yell), arg3 = receiving bot name (whisper
            // only), arg4 = the chat text
            "world-chat" -> passthrough(op) {
                worldApi().worldChat(arg1, arg2, arg3, arg4) }
            // arg1 = player name ("" = every player)
            "reset-state" -> passthrough(op) { worldApi().resetState(arg1) }
            // arg1 = player name ("" = world summary with
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

    // The world boot's DB-lane precondition, proven BEFORE any native boot
    // is fired: dependency order is DB -> realmd -> world, and a start over
    // a stopped lane wedges the native boot (the recorded row -09 hang)
    // instead of refusing. null = the lane answered RUNNING and the boot may
    // proceed; otherwise the refusal names the observed lane state and the
    // sanctioned boot path. Pure in the status so the guard's decision table
    // is testable against synthetic lanes as well as the real one.
    internal fun worldBootRefusal(op: String, status: JSONObject?): JSONObject? {
        if (status?.optString("state") == "RUNNING") return null
        val observed = if (status == null) {
            "the database lane did not answer status within " +
                "${StackUpComposite.STATUS_READ_WINDOW_MS / 1000}s"
        } else {
            "state=${status.optString("state")}, " +
                "initialized=${status.optBoolean("initialized")}, " +
                "providerMode=${status.optString("providerMode")}"
        }
        return JSONObject()
            .put("ok", false)
            .put("op", op)
            .put("errorClass", if (status == null) "DATABASE_NOT_READY" else "DB_NOT_RUNNING")
            .put("error", "world boot precondition unmet: database lane is not running ($observed)")
            .put("remedy", "run stack-up-bot <profileId> (supervisor DB-RECOVERY gate, " +
                "db init+migrations+start, realm, world) before $op")
    }

    /** One bounded status read from the real database lane (the guard's input). */
    internal fun databaseStatus(): JSONObject? =
        boundLeg("DATABASE", StackUpComposite.STATUS_READ_WINDOW_MS, Long.MAX_VALUE) {
            db().status()
        }

    // One bounded component call: the leg's parsed answer, or null when the
    // call outlives its (effective) window - see [boundedCall].
    private fun boundLeg(
        component: String,
        windowMs: Long,
        opDeadlineMs: Long,
        call: () -> String,
    ): JSONObject? = boundedCall(component, effectiveWindowMs(windowMs, opDeadlineMs)) {
        JSONObject(call())
    }

    // The fail-fast mechanism under every bounded stage: the synchronous
    // Binder call runs on a worker thread and the caller joins it for the
    // stage's window. A call that outlives the window is abandoned on its
    // worker (a wedged transit cannot be interrupted; the recorded row -09
    // composite sat silent past the host's 900s window on exactly such a
    // call) and null is returned so the caller answers the typed stage
    // timeout. A finished worker's failure is rethrown on the caller -
    // join() provides the happens-before.
    internal fun <T> boundedCall(component: String, windowMs: Long, call: () -> T): T? {
        if (windowMs <= 0L) return null
        var answer: T? = null
        var failure: Throwable? = null
        val worker = Thread({
            try {
                answer = call()
            } catch (error: Throwable) {
                failure = error
            }
        }, "console-bound-$component")
        worker.start()
        worker.join(windowMs)
        if (worker.isAlive) return null
        failure?.let { throw it }
        return answer
    }

    private fun effectiveWindowMs(windowMs: Long, opDeadlineMs: Long): Long =
        windowMs.coerceAtMost((opDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L))

    // The stage-timeout shape shared by every bounded stage - owned by
    // StackUpComposite (host-unit-tested); the relay keeps the delegate so
    // the world-boot guard and the instrumentation tests share one shape.
    internal fun stageTimeoutResponse(
        op: String,
        leg: String,
        component: String,
        windowMs: Long,
    ): JSONObject = StackUpComposite.stageTimeoutResponse(op, leg, component, windowMs)

    private fun stackUpBot(profileIdArg: String): JSONObject {
        // The composite (StackUpComposite, host-unit-tested) owns the
        // sequence, the total deadline and every named answer; the relay
        // supplies the real Binder legs and the bounded-call abandonment.
        val composite = StackUpComposite(
            op = "stack-up-bot",
            nowMs = System::currentTimeMillis,
            bound = { _, component, windowMs, leg ->
                // The composite hands each stage its already-capped window
                // (design capped by the op budget's remainder); the relay's
                // abandonment semantics apply within that window.
                boundLeg(component, windowMs, Long.MAX_VALUE, leg)
            },
            legs = object : StackUpComposite.Legs {
                override fun dbRecoveryGate(): String =
                    healDatabaseThroughSupervisor().toString()
                override fun dbStatus(): String = db().status()
                override fun dbInitialize(): String = db().initialize()
                override fun dbMigrations(): String = db().applyPinnedMigrations()
                override fun dbStart(): String = db().start()
                override fun dbHealth(): String = db().queryHealth()
                override fun realmStart(): String = realmApi().start()
                override fun worldStartBotProfile(profileId: String): String =
                    worldApi().startBotProfile(profileId)
            },
        )
        // The composite-level net (defect stackup-wedge / LVF-server-db-014):
        // the whole op is bounded by ITS OWN budget - if the composite body
        // ever outlives it (a stage escaped its bounded window, or a future
        // regression adds an unbounded call), the net abandons it and answers
        // the named total-deadline refusal. The recorded wedge sat silent
        // past the stage sum + 210s; with every stage capped by the budget
        // remainder AND the body under this net, the host console's 900s
        // slow-op window always sees an answer. Never an indefinite hang.
        return boundedCall("STACKUP", StackUpComposite.STACKUP_OP_BUDGET_MS) {
            composite.run(profileIdArg)
        } ?: StackUpComposite.totalDeadlineResponse("stack-up-bot", "composite",
            "the composite body outlived its own budget (a stage escaped its " +
                "bounded window)")
    }
    private fun boundWorldStart(op: String, call: () -> String): JSONObject =
        boundLeg("WORLD", StackUpComposite.WORLD_START_STAGE_WINDOW_MS, Long.MAX_VALUE, call)
            ?.put("op", op)
            ?: StackUpComposite.stageTimeoutResponse(op, op, "WORLD",
                StackUpComposite.WORLD_START_STAGE_WINDOW_MS)

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
     * when it is unsealed, routes by the production preparation order
     * (DatabaseStartPreparation): an interrupted INIT/MIGRATION transaction
     * resumes through the initialize/applyPinnedMigrations legs' own
     * engine-side resume paths, a NEVER-INITIALIZED generation is the
     * first-boot INITIALIZE route, and only an unsealed INITIALIZED
     * generation with no pending transaction has a recover verb that will
     * take it - the exact RECOVER_DIRTY_GENERATION leg the supervisor's
     * prepare lane drives on every DATABASE start - so that one case alone
     * goes to the engine's recover verb and the rest is admitted to the
     * composite's bounded legs with the engine enforcing every seal.
     */
    private fun healDatabaseThroughSupervisor(): JSONObject = runCatching {
        val supervisor = bind("com.pocketrealm.service.RealmService")
            { IRuntimeSupervisorControl.Stub.asInterface(it) }
        try {
            val deadline = System.currentTimeMillis() +
                StackUpComposite.SUPERVISOR_RECOVERY_TIMEOUT_MS
            var recoverySubmitted = false
            while (System.currentTimeMillis() < deadline) {
                val status = JSONObject(supervisor.api.status())
                // clean=true on a settled phase (STOPPED after recovery, or
                // UNCONFIGURED/ERROR on a never-started journal) means the
                // supervisor owns no generation: nothing to heal. Any other
                // phase is an active or interrupted generation.
                val phase = status.optString("phase")
                val settled = phase in setOf("STOPPED", "UNCONFIGURED", "ERROR")
                if (settled && status.optBoolean("clean")) {
                    val engine = JSONObject(db().status())
                    if (!engine.optBoolean("cleanMarker")) {
                        // Route the unsealed generation to the verb that can
                        // actually accept it - the production preparation
                        // order (DatabaseStartPreparation): an interrupted
                        // INIT/MIGRATION transaction resumes through the
                        // initialize/applyPinnedMigrations legs' own
                        // engine-side resume paths, a never-initialized
                        // generation is the first-boot flow (INITIALIZE
                        // route - the recover verb refuses it), and only a
                        // sealed-initialized dirty generation with no
                        // pending transaction has a recover verb that will
                        // take it (dirtyRecoveryPermitted): that one case
                        // goes to the engine half below.
                        val recoverEligible = engine.optBoolean("initialized") &&
                            !engine.optBoolean("databaseTransactionPending")
                        if (!recoverEligible) {
                            return JSONObject().put("ok", true).put("action",
                                "fresh-prepare (unsealed generation admitted to the " +
                                    "composite's bounded db legs: the engine's own " +
                                    "initialize/migrations resume guards own this state)")
                        }
                    }
                    return verifyEngineSealedOrHeal(
                        if (recoverySubmitted) "supervisor-recover" else "none-needed")
                }
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
                    "${StackUpComposite.SUPERVISOR_RECOVERY_TIMEOUT_MS / 1000}s")
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
        // The stack-up-bot composite's stage windows and total budget - the
        // DESIGNED bounds and their grounding (engine tool bounds, the O09
        // readiness windows, the host console's 900s slow-op window) - live
        // in StackUpComposite (host-unit-tested, the single source of truth).
        // This relay contributes only the mechanism: boundedCall abandonment,
        // the per-op Binder revalidation, and the whole-op net that answers
        // the named total-deadline refusal if the composite body ever
        // outlives its own budget.
    }
}
