package com.pocketrealm.database

import org.json.JSONObject

/**
 * The stack-up-bot composite's fail-fast core (finding LVF-server-db-014,
 * defect "stackup-wedge"): one TOTAL deadline for the whole op, every stage's
 * window capped by the budget remainder, and a NAMED answer for every exit -
 * a leg answer, a stage timeout, a not-running lane refusal, or the composite
 * total-deadline refusal. The op ALWAYS answers inside [STACKUP_OP_BUDGET_MS]
 * and never hangs past the host console's 900s slow-op window
 * (world_console.py SLOW_OPS); and the composite never fires the realm/world
 * boots over a database lane its own health leg observed as not running (the
 * dependency order DB -> realmd -> world, the same law worldBootRefusal
 * enforces for the standalone world boots).
 *
 * Pure Kotlin + org.json so the host JVM unit battery can pin the deadline
 * logic and simulate the composite timing with a fake clock; the console
 * relay (androidTest) drives the real Binder legs through it with its
 * boundedCall abandonment as the `bound` half.
 *
 * Stage windows are DESIGNED bounds, not guesses (each grounded in a code
 * bound, recorded in the relay's companion before this class owned them):
 * dbInit covers the engine's own bootstrap tool bound (DatabaseEngine
 * runTool timeoutMs 180s) plus first-boot provider staging headroom;
 * dbMigrations is headroom over the recorded fresh 414-file pinned run on
 * this lane (<110s, LV-server-db-33 evidence) plus an interrupted-migration
 * resume's snapshot rollback of the ~73MiB datadir; dbStart covers the
 * engine's 45s daemon socket-readiness window; dbHealth the engine's 30s
 * fixed-query client bound; realmStart/worldStart the on-device console
 * lane's own readiness windows (O09RealmRuntimeTest: realm 60s, world 180s);
 * the supervisor gate its recovery budget (the host console waits 900s for
 * slow ops).
 *
 * The designed windows SUM to 1290s - WORSE than the host's 900s window -
 * which is exactly why the composite budget exists: sequential stages that
 * each sit out their full window are cut off at [STACKUP_OP_BUDGET_MS]
 * (840s, reserve included for the answer's file round-trip), and the budget
 * remainder - not the stage design - is what a late stage is actually given.
 * The recorded wedge sat silent past the stage sum + 210s on the unbounded
 * composite; with the budget enforced at the composite level (every window
 * capped by the remainder, plus the relay's whole-op abandonment net of the
 * same budget), the answer cannot come later than 840s + join, come what may
 * inside the stages.
 */
class StackUpComposite(
    private val op: String,
    private val nowMs: () -> Long,
    private val bound: (legName: String, component: String, windowMs: Long, leg: () -> String) -> JSONObject?,
    private val legs: Legs,
) {
    /** The real Binder legs, named for the composite; the relay implements
     * them over the product surface, the unit battery over fakes. */
    interface Legs {
        /** The supervisor DB-RECOVERY gate (heal + engine seal verify). */
        fun dbRecoveryGate(): String

        /** One status read from the database lane (the health refusal's trace). */
        fun dbStatus(): String
        fun dbInitialize(): String
        fun dbMigrations(): String
        fun dbStart(): String

        /** The engine's fixed health query (the service's guarded answer
         * carries "database is not running" when the engine is not RUNNING). */
        fun dbHealth(): String
        fun realmStart(): String
        fun worldStartBotProfile(profileId: String): String
    }

    /** One stage's outcome: its parsed answer, or a complete refusal response
     * the composite must return untouched. */
    private sealed interface Stage {
        data class Answer(val json: JSONObject) : Stage
        data class Refusal(val response: JSONObject) : Stage
    }

    /**
     * Run the composite: supervisor DB-RECOVERY gate -> db init -> migrations
     * -> start -> HEALTH GATE -> realm -> world, every stage bounded by its
     * design capped by the budget remainder. Returns the op's complete
     * answer; every failure path is named (stage timeout, DB_REVISION,
     * DB_NOT_RUNNING, STACKUP_TOTAL_DEADLINE).
     */
    fun run(profileIdArg: String): JSONObject {
        val out = JSONObject().put("op", op)
        // The console tool's documented default (world_console.py repl:
        // stack-up-bot [profile], default mobile-balanced-b100-v1) - a bare
        // stack-up-bot from any host resolves to the same profile the REPL
        // would send, instead of failing the world leg on an empty id.
        val profileId = profileIdArg.ifEmpty { DEFAULT_BOT_PROFILE }
        val opDeadlineMs = nowMs() + STACKUP_OP_BUDGET_MS

        // -- the DB-RECOVERY gate ------------------------------------------
        // Driving db initialize/migrations/start directly over an unsealed
        // generation failed dbMigrations/dbStart and left the world leg at
        // DB_REVISION (the dirty-stop lesson). The sanctioned heal is the
        // supervisor's recovery lane - the same DurableRuntimeSupervisor
        // .recover() an app-led Start realm runs - driven over its Binder,
        // never re-implemented here, PLUS the engine's own marker check
        // (relay composites are not journaled, so the journal alone cannot
        // prove the generation sealed). If the lane cannot heal, fail fast
        // and actionably instead of driving the legs.
        val gate = fire(out, "dbRecovery", "SUPERVISOR",
            SUPERVISOR_RECOVERY_TIMEOUT_MS, opDeadlineMs) { legs.dbRecoveryGate() }
        if (gate is Stage.Refusal) return gate.response
        val recovery = (gate as Stage.Answer).json
        out.put("dbRecoveryDetail",
            recovery.optString("action", recovery.optString("error")))
        if (!recovery.optBoolean("ok")) {
            return out.put("ok", false)
                .put("errorClass", "DB_REVISION")
                .put("error", recovery.optString("error") + "; remedy: one app-led Start " +
                    "realm (Home -> Start) heals the database, then re-run stack-up-bot")
        }

        // -- the database legs, then the lane's HEALTH GATE ----------------
        // The DB lane is the composite's foundation (dependency order
        // DB -> realmd -> world): a lane its own health leg observed as not
        // running REFUSES the composite here, naming the observed lane
        // state, instead of firing the realm/world native boots into a dead
        // lane and answering a vague legs-failed blob (or, the recorded
        // finding, an ok:true with dbHealth:false buried inside while
        // db-health keeps answering "database is not running" for the rest
        // of the session). The engine's RUNNING state is per-process and
        // in-memory (DatabaseEngine.state); initialized=true on disk does
        // not make a running lane - only the health leg proves it is up.
        val dbInit = fire(out, "dbInit", "DATABASE", DB_INIT_STAGE_WINDOW_MS,
            opDeadlineMs) { legs.dbInitialize() }
        if (dbInit is Stage.Refusal) return dbInit.response
        val dbMigrations = fire(out, "dbMigrations", "DATABASE",
            DB_MIGRATIONS_STAGE_WINDOW_MS, opDeadlineMs) { legs.dbMigrations() }
        if (dbMigrations is Stage.Refusal) return dbMigrations.response
        val dbStart = fire(out, "dbStart", "DATABASE", DB_START_STAGE_WINDOW_MS,
            opDeadlineMs) { legs.dbStart() }
        if (dbStart is Stage.Refusal) return dbStart.response
        val health = fire(out, "dbHealth", "DATABASE", DB_HEALTH_STAGE_WINDOW_MS,
            opDeadlineMs) { legs.dbHealth() }
        if (health is Stage.Refusal) return health.response
        if (!(health as Stage.Answer).json.optBoolean("ok")) {
            return out.put("ok", false)
                .put("errorClass", "DB_NOT_RUNNING")
                .put("error", "the composite's dbHealth leg failed " +
                    "(${health.json.optString("error",
                        health.json.optString("errorClass"))}): the database " +
                    "lane is not running (${laneObserved()}); the boot refuses " +
                    "realm+world instead of firing them into a dead lane")
                .put("remedy", "remedy: one app-led Start realm (Home -> Start) heals " +
                    "the database, then re-run stack-up-bot")
        }

        // -- realm, then world, over a PROVEN lane -------------------------
        val realmStart = fire(out, "realmStart", "REALM", REALM_START_STAGE_WINDOW_MS,
            opDeadlineMs) { legs.realmStart() }
        if (realmStart is Stage.Refusal) return realmStart.response
        val worldStart = fire(out, "worldStartBotProfile", "WORLD",
            WORLD_START_STAGE_WINDOW_MS, opDeadlineMs) {
            legs.worldStartBotProfile(profileId)
        }
        if (worldStart is Stage.Refusal) return worldStart.response

        // A failed leg never answers a bare ok:false (the QA hit:
        // dbMigrations/dbStart false with no errorClass and no remedy): the
        // composite's foundation is the database, so the legs failure carries
        // the gate path's own errorClass and the SAME remedy string, with the
        // error naming exactly which legs failed. dbHealth is IN the
        // conjunction: a composite whose health leg failed can never answer
        // ok:true again.
        out.put("ok", out.optBoolean("dbInit") && out.optBoolean("dbMigrations")
            && out.optBoolean("dbStart") && out.optBoolean("dbHealth")
            && out.optBoolean("realmStart") && out.optBoolean("worldStartBotProfile"))
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
     * Fire one stage: its design window capped by the budget remainder, the
     * bounded call, and the leg's ok flag recorded under its name. A stage
     * the budget cannot admit (remainder spent) is refused with the named
     * STACKUP_TOTAL_DEADLINE answer - the composite's guarantee that the op
     * answers inside its budget instead of hanging; a stage whose bounded
     * call outlives its (capped) window is refused with the named
     * stage-timeout answer (the relay's abandonment semantics). A refusal is
     * returned DIRECTLY - the recorded answer shape the host tools consume -
     * with the fired legs' flags kept on `out` for the next answer's trace
     * only.
     */
    private fun fire(
        out: JSONObject,
        legName: String,
        component: String,
        designWindowMs: Long,
        opDeadlineMs: Long,
        leg: () -> String,
    ): Stage {
        val windowMs = designWindowMs
            .coerceAtMost((opDeadlineMs - nowMs()).coerceAtLeast(0L))
        if (windowMs <= 0L) {
            out.put(legName, false)
            return Stage.Refusal(totalDeadlineResponse(op, legName,
                "'$legName' (component $component) could not start inside the " +
                    "budget's remainder"))
        }
        val answer = bound(legName, component, windowMs, leg)
        if (answer == null) {
            out.put(legName, false)
            return Stage.Refusal(stageTimeoutResponse(op, legName, component, windowMs))
        }
        out.put(legName, answer.optBoolean("ok"))
        return Stage.Answer(answer)
    }

    // The health refusal's trace: one bounded status read naming the lane's
    // observed state - the exact fields (initialized=true, state!=RUNNING)
    // the recorded finding saw when db-health kept answering "database is
    // not running" against an initialized generation.
    private fun laneObserved(): String {
        val status = bound("dbStatus", "DATABASE", STATUS_READ_WINDOW_MS) { legs.dbStatus() }
        return if (status == null) {
            "the lane did not answer status within ${STATUS_READ_WINDOW_MS / 1000}s"
        } else {
            "state=${status.optString("state")}, " +
                "initialized=${status.optBoolean("initialized")}, " +
                "providerMode=${status.optString("providerMode")}"
        }
    }

    companion object {
        /**
         * stack-up-bot's composite budget: holds the whole op inside the host
         * console's 900s slow-op window (world_console.py SLOW_OPS) with
         * reserve for the answer's file round-trip. Every stage's effective
         * window is its design capped by this budget's remainder, and the
         * relay runs the whole composite under one abandonment net of exactly
         * this window - so the op ALWAYS answers: leg answer, named stage
         * timeout, or named total-deadline refusal. Never an indefinite hang.
         */
        const val STACKUP_OP_BUDGET_MS = 840_000L

        /** stack-up-bot's supervisor recovery budget; the host console waits 900 s for slow ops. */
        const val SUPERVISOR_RECOVERY_TIMEOUT_MS = 420_000L

        const val DB_INIT_STAGE_WINDOW_MS = 240_000L
        const val DB_MIGRATIONS_STAGE_WINDOW_MS = 300_000L
        const val DB_START_STAGE_WINDOW_MS = 60_000L
        const val DB_HEALTH_STAGE_WINDOW_MS = 30_000L
        const val REALM_START_STAGE_WINDOW_MS = 60_000L
        const val WORLD_START_STAGE_WINDOW_MS = 180_000L

        /** One bounded status read (the health refusal's trace; the world boot precondition probe). */
        const val STATUS_READ_WINDOW_MS = 30_000L

        /** The console tool's documented default bot profile (world_console.py repl). */
        const val DEFAULT_BOT_PROFILE = "mobile-balanced-b100-v1"

        /** The designed windows summed: WORSE than the host's 900s window, and
         * the reason the composite budget - not the stage sum - must bind. */
        val designedWorstCaseMs: Long = SUPERVISOR_RECOVERY_TIMEOUT_MS +
            DB_INIT_STAGE_WINDOW_MS + DB_MIGRATIONS_STAGE_WINDOW_MS +
            DB_START_STAGE_WINDOW_MS + DB_HEALTH_STAGE_WINDOW_MS +
            REALM_START_STAGE_WINDOW_MS + WORLD_START_STAGE_WINDOW_MS

        // The stage-timeout shape shared by every bounded stage: ok:false, the
        // refusing component's typed errorClass, the leg and its window named in
        // the error, and a remedy - the sanctioned database heal for the
        // database stages, the component's own logs for the rest.
        fun stageTimeoutResponse(
            op: String,
            leg: String,
            component: String,
            windowMs: Long,
        ): JSONObject = JSONObject()
            .put("ok", false)
            .put("op", op)
            .put("errorClass", "${component}_STAGE_TIMEOUT")
            .put("timedOutStage", leg)
            .put("stageWindowMs", windowMs)
            .put("error", "$leg (component $component) did not answer within its " +
                "designed ${windowMs / 1000}s window; the boot refused instead of hanging")
            .put("remedy", if (component == "DATABASE") {
                "remedy: one app-led Start realm (Home -> Start) heals the database, " +
                    "then re-run stack-up-bot"
            } else {
                "remedy: inspect the $component process logs (adb logcat), then re-run stack-up-bot"
            })

        /**
         * The composite total-deadline answer - the fail-fast this class
         * exists for: the op's TOTAL budget, not any single stage, is what
         * expired, and the op answers the named refusal instead of hanging
         * (the recorded wedge sat silent past the stage sum + 210s).
         */
        fun totalDeadlineResponse(op: String, timedOutStage: String, detail: String): JSONObject =
            JSONObject()
                .put("ok", false)
                .put("op", op)
                .put("errorClass", "STACKUP_TOTAL_DEADLINE")
                .put("timedOutStage", timedOutStage)
                .put("totalBudgetMs", STACKUP_OP_BUDGET_MS)
                .put("error", "the stack-up composite's total " +
                    "${STACKUP_OP_BUDGET_MS / 1000}s budget was exhausted: $detail; " +
                    "the op answers the named refusal instead of hanging past the " +
                    "host console's 900s slow-op window")
                .put("remedy", "remedy: one app-led Start realm (Home -> Start) heals " +
                    "the database, then re-run stack-up-bot")
    }
}
