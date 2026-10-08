package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side simulation of the stack-up composite's TIMING (defect
 * stackup-wedge / finding LVF-server-db-014): the real StackUpComposite
 * runner driven over a fake clock and fake legs, mirroring the relay's
 * bounded-call abandonment semantics (a wedged stage consumes its handed
 * window and is abandoned, never invoked). Pins: the designed leg order
 * (DB -> realmd -> world), the landed hardening (a wedged stage answers its
 * named stage timeout inside the budget), the lane health gate (a
 * not-running lane refuses BEFORE the realm/world boots fire, naming the
 * observed lane state), the supervisor gate's DB_REVISION path, and the
 * composite total deadline (the 1290s designed worst case collapses into
 * the 840s budget with a named answer - the observed "silent past
 * sum + 210s" wedge is structurally impossible).
 */
class StackUpCompositeTimingTest {

    /** The composite driven over a fake clock; legs answer canned JSON in
     * zero fake time unless configured; a WEDGED leg is abandoned by the
     * bound - its handed window elapses and the leg is never invoked - the
     * relay's real abandonment semantics at simulation scale. */
    private class TimingSim(private val clock: FakeClock) {
        val costs = mutableMapOf<String, Long>()
        val wedged = mutableSetOf<String>()
        val answers = mutableMapOf<String, String>()
        val fired = mutableListOf<String>()
        var worldProfile: String? = null

        val legs = object : StackUpComposite.Legs {
            override fun dbRecoveryGate(): String = canned("dbRecovery")
            override fun dbStatus(): String = canned("dbStatus")
            override fun dbInitialize(): String = canned("dbInit")
            override fun dbMigrations(): String = canned("dbMigrations")
            override fun dbStart(): String = canned("dbStart")
            override fun dbHealth(): String = canned("dbHealth")
            override fun realmStart(): String = canned("realmStart")
            override fun worldStartBotProfile(profileId: String): String {
                worldProfile = profileId
                return canned("worldStartBotProfile")
            }
        }

        val composite = StackUpComposite(
            op = "stack-up-bot",
            nowMs = { clock.nowMs },
            bound = { legName, _, windowMs, leg ->
                when {
                    legName in wedged -> { clock.nowMs += windowMs; null }
                    else -> {
                        clock.nowMs += costs[legName] ?: 0L
                        fired += legName
                        JSONObject(leg())
                    }
                }
            },
            legs = legs,
        )

        private fun canned(legName: String): String =
            answers[legName] ?: JSONObject().put("ok", true).toString()
    }

    // -- the designed order, over a healthy lane ------------------------

    @Test fun green_run_fires_the_legs_in_designed_order_and_answers_ok() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.costs["dbRecovery"] = 5_000L
        sim.costs["dbInit"] = 10_000L
        sim.costs["dbMigrations"] = 8_000L
        sim.costs["dbStart"] = 2_000L
        sim.costs["dbHealth"] = 1_000L
        sim.costs["realmStart"] = 3_000L
        sim.costs["worldStartBotProfile"] = 4_000L
        val answer = sim.composite.run("")
        assertTrue(answer.optBoolean("ok"))
        assertEquals("stack-up-bot", answer.getString("op"))
        assertEquals(listOf("dbRecovery", "dbInit", "dbMigrations", "dbStart",
            "dbHealth", "realmStart", "worldStartBotProfile"), sim.fired)
        assertEquals("a bare stack-up-bot resolves the console tool's default profile",
            StackUpComposite.DEFAULT_BOT_PROFILE, sim.worldProfile)
        assertTrue("a healthy run is far inside the budget",
            clock.nowMs < StackUpComposite.STACKUP_OP_BUDGET_MS)
        assertNull("a green answer carries no errorClass", answer.opt("errorClass"))
    }

    @Test fun an_explicit_profile_argument_reaches_the_world_leg() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.composite.run("raid-heavy-b200-v9")
        assertEquals("raid-heavy-b200-v9", sim.worldProfile)
    }

    // -- the landed hardening, kept: abandonment inside the window ------

    @Test fun a_wedged_stage_is_abandoned_and_answers_its_named_timeout() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.costs["dbRecovery"] = 1_000L
        sim.wedged.add("dbInit")
        val answer = sim.composite.run("")
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("DATABASE_STAGE_TIMEOUT", answer.getString("errorClass"))
        assertEquals("dbInit", answer.getString("timedOutStage"))
        assertEquals(240_000L, answer.getLong("stageWindowMs"))
        // The wedge cost exactly its handed window - far inside the budget.
        assertEquals(241_000L, clock.nowMs)
        assertTrue(clock.nowMs < StackUpComposite.STACKUP_OP_BUDGET_MS)
        assertEquals("only the gate ran before the refusal", listOf("dbRecovery"),
            sim.fired)
        assertTrue("the database remedy names the sanctioned heal",
            answer.getString("remedy").contains("Start realm"))
    }

    // -- the lane health gate: no realm/world over a dead DB lane -------

    @Test fun a_not_running_lane_refuses_before_realm_and_world_fire() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.answers["dbHealth"] = JSONObject().put("ok", false)
            .put("errorClass", "IllegalStateException")
            .put("error", "database is not running").toString()
        // The lane's own status trace: initialized generation, engine down -
        // the exact recorded finding (initialized=true, db-health not running).
        sim.answers["dbStatus"] = JSONObject().put("state", "STOPPED")
            .put("initialized", true).put("providerMode", "SQLITE").toString()
        val answer = sim.composite.run("")
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("DB_NOT_RUNNING", answer.getString("errorClass"))
        assertTrue(answer.getString("error").contains("database is not running"))
        assertTrue("the refusal must name the observed lane state",
            answer.getString("error").contains("state=STOPPED"))
        assertTrue(answer.getString("error").contains("initialized=true"))
        assertTrue("the remedy names the sanctioned heal",
            answer.getString("remedy").contains("Start realm"))
        assertTrue(answer.getString("remedy").contains("stack-up-bot"))
        assertEquals("the boot stops at the health leg (plus its dbStatus trace " +
                "probe) - realm and world never fire",
            listOf("dbRecovery", "dbInit", "dbMigrations", "dbStart", "dbHealth",
                "dbStatus"),
            sim.fired)
        assertNull("worldStartBotProfile must be absent - the world leg never fired",
            answer.opt("worldStartBotProfile"))
        assertTrue("the refusal is well inside the budget",
            clock.nowMs < StackUpComposite.STACKUP_OP_BUDGET_MS)
    }

    @Test fun an_unanswering_status_probe_is_named_in_the_health_refusal() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.answers["dbHealth"] = JSONObject().put("ok", false)
            .put("error", "database is not running").toString()
        sim.wedged.add("dbStatus")
        val answer = sim.composite.run("")
        assertEquals("DB_NOT_RUNNING", answer.getString("errorClass"))
        assertTrue(answer.getString("error").contains(
            "did not answer status within " +
                "${StackUpComposite.STATUS_READ_WINDOW_MS / 1000}s"))
    }

    // -- the supervisor gate: DB_REVISION, kept -------------------------

    @Test fun a_failed_recovery_gate_fails_fast_with_db_revision() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        sim.answers["dbRecovery"] = JSONObject().put("ok", false)
            .put("error", "supervisor recovery did not reach a clean settled journal")
            .toString()
        val answer = sim.composite.run("")
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("DB_REVISION", answer.getString("errorClass"))
        assertTrue(answer.getString("error")
            .contains("supervisor recovery did not reach a clean settled journal"))
        assertEquals("the gate's error rides dbRecoveryDetail",
            "supervisor recovery did not reach a clean settled journal",
            answer.getString("dbRecoveryDetail"))
        assertTrue(answer.getString("error").contains("remedy"))
        assertEquals("no db leg runs over an unhealed lane", listOf("dbRecovery"),
            sim.fired)
    }

    // -- the composite total deadline ------------------------------------

    @Test fun the_designed_1290s_worst_case_collapses_into_the_budget() {
        val clock = FakeClock()
        val sim = TimingSim(clock)
        // Every stage answers, but consumes its whole HANDED window - the
        // slow-but-alive worst case that used to run the op past the stage
        // sum: 420s (gate) + 240s (dbInit) + 180s (dbMigrations capped to
        // the remainder) spends the entire 840s budget.
        sim.costs["dbRecovery"] = 420_000L
        sim.costs["dbInit"] = 240_000L
        sim.costs["dbMigrations"] = 180_000L
        val answer = sim.composite.run("")
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("STACKUP_TOTAL_DEADLINE", answer.getString("errorClass"))
        assertEquals("dbStart", answer.getString("timedOutStage"))
        assertEquals(StackUpComposite.STACKUP_OP_BUDGET_MS, answer.getLong("totalBudgetMs"))
        assertEquals("the op answers at the budget, never beyond it",
            StackUpComposite.STACKUP_OP_BUDGET_MS, clock.nowMs)
        assertTrue("the wedge horizon (1290s stage sum + 210s) is unreachable",
            clock.nowMs + 210_000L < StackUpComposite.designedWorstCaseMs)
        assertEquals("dbStart and everything after it never fired",
            listOf("dbRecovery", "dbInit", "dbMigrations"), sim.fired)
    }
}
