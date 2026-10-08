package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stack-up composite's DEADLINE LOGIC (defect stackup-wedge /
 * finding LVF-server-db-014): the designed stage windows sum to 1290s -
 * worse than the host console's 900s slow-op window - so the composite
 * budget, not the stage sum, is the binding bound; every stage's effective
 * window is its design capped by the budget remainder; a stage the budget
 * cannot admit is refused with the named STACKUP_TOTAL_DEADLINE answer; and
 * the budget always leaves the op answering inside the host's patience.
 * The timing behavior itself (orderings, abandonment, the health gate) is
 * pinned by StackUpCompositeTimingTest.
 */
class StackUpCompositeDeadlineTest {

    // -- the production numbers, pinned --------------------------------

    @Test fun designed_stage_windows_sum_to_the_recorded_1290s_bound() {
        assertEquals(1_290_000L, StackUpComposite.designedWorstCaseMs)
        assertEquals(420_000L, StackUpComposite.SUPERVISOR_RECOVERY_TIMEOUT_MS)
        assertEquals(240_000L, StackUpComposite.DB_INIT_STAGE_WINDOW_MS)
        assertEquals(300_000L, StackUpComposite.DB_MIGRATIONS_STAGE_WINDOW_MS)
        assertEquals(60_000L, StackUpComposite.DB_START_STAGE_WINDOW_MS)
        assertEquals(30_000L, StackUpComposite.DB_HEALTH_STAGE_WINDOW_MS)
        assertEquals(60_000L, StackUpComposite.REALM_START_STAGE_WINDOW_MS)
        assertEquals(180_000L, StackUpComposite.WORLD_START_STAGE_WINDOW_MS)
    }

    @Test fun the_composite_budget_binds_below_the_designed_stage_sum() {
        // The recorded wedge sat silent past the 1290s stage sum (+210s of
        // executor patience). The budget must bind BELOW the sum, so the
        // composite can never again run its full designed worst case.
        assertTrue("the composite budget (${StackUpComposite.STACKUP_OP_BUDGET_MS}ms) " +
                "must bind below the designed stage sum " +
                "(${StackUpComposite.designedWorstCaseMs}ms)",
            StackUpComposite.STACKUP_OP_BUDGET_MS < StackUpComposite.designedWorstCaseMs)
    }

    @Test fun the_composite_budget_fits_the_host_consoles_900s_slow_op_window() {
        // tools/world_console.py SLOW_OPS waits 900s for stack-up-bot; the
        // op's total budget must land inside that window, so the host
        // console ALWAYS sees the answer.
        assertTrue("the composite budget (${StackUpComposite.STACKUP_OP_BUDGET_MS}ms) " +
                "must fit the host console's 900s slow-op window",
            StackUpComposite.STACKUP_OP_BUDGET_MS < 900_000L)
    }

    // -- the named answers ---------------------------------------------

    @Test fun total_deadline_answer_is_named_and_actionable() {
        val answer = StackUpComposite.totalDeadlineResponse("stack-up-bot", "dbStart",
            "'dbStart' (component DATABASE) could not start inside the budget's remainder")
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("stack-up-bot", answer.getString("op"))
        assertEquals("STACKUP_TOTAL_DEADLINE", answer.getString("errorClass"))
        assertEquals("dbStart", answer.getString("timedOutStage"))
        assertEquals(StackUpComposite.STACKUP_OP_BUDGET_MS, answer.getLong("totalBudgetMs"))
        assertTrue(answer.getString("error").contains(
            "total ${StackUpComposite.STACKUP_OP_BUDGET_MS / 1000}s budget"))
        assertTrue(answer.getString("error").contains("dbStart"))
        assertTrue("the remedy must name the sanctioned heal",
            answer.getString("remedy").contains("Start realm"))
        assertTrue(answer.getString("remedy").contains("stack-up-bot"))
    }

    @Test fun stage_timeout_answer_keeps_the_landed_shape() {
        val answer = StackUpComposite.stageTimeoutResponse(
            "stack-up-bot", "dbInit", "DATABASE", 240_000L)
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("DATABASE_STAGE_TIMEOUT", answer.getString("errorClass"))
        assertEquals("dbInit", answer.getString("timedOutStage"))
        assertEquals(240_000L, answer.getLong("stageWindowMs"))
        assertTrue(answer.getString("error").contains("dbInit"))
        assertTrue(answer.getString("remedy").contains("stack-up-bot"))
    }

    // -- the deadline math, exercised through the real composite --------

    /** A sim over green legs; records the window each stage was HANDED and
     * advances the fake clock by each stage's configured spend (default:
     * nothing, or the whole handed window in consume-window mode - the
     * slow-but-alive worst case). */
    private class WindowSim(
        private val clock: FakeClock,
        private val spendMs: Map<String, Long> = emptyMap(),
        private val consumeWindowWhenUnspecified: Boolean = false,
    ) {
        val windows = LinkedHashMap<String, Long>()

        val composite = StackUpComposite(
            op = "stack-up-bot",
            nowMs = { clock.nowMs },
            bound = { legName, _, windowMs, leg ->
                windows[legName] = windowMs
                clock.nowMs += when {
                    spendMs.containsKey(legName) -> spendMs.getValue(legName)
                    consumeWindowWhenUnspecified -> windowMs
                    else -> 0L
                }
                JSONObject(leg())
            },
            legs = AlwaysGreenLegs,
        )
    }

    private object AlwaysGreenLegs : StackUpComposite.Legs {
        private fun ok(action: String? = null): String {
            val json = JSONObject().put("ok", true)
            if (action != null) json.put("action", action)
            return json.toString()
        }
        override fun dbRecoveryGate(): String = ok("none-needed")
        override fun dbStatus(): String = JSONObject().put("state", "RUNNING")
            .put("initialized", true).put("providerMode", "SQLITE").toString()
        override fun dbInitialize(): String = ok()
        override fun dbMigrations(): String = ok()
        override fun dbStart(): String = ok()
        override fun dbHealth(): String = ok()
        override fun realmStart(): String = ok()
        override fun worldStartBotProfile(profileId: String): String = ok()
    }

    @Test fun every_stage_gets_its_full_design_while_the_budget_allows() {
        val clock = FakeClock()
        val sim = WindowSim(clock, spendMs = mapOf(
            "dbRecovery" to 1_000L, "dbInit" to 1_000L, "dbMigrations" to 1_000L,
            "dbStart" to 1_000L, "dbHealth" to 1_000L, "realmStart" to 1_000L,
            "worldStartBotProfile" to 1_000L))
        val answer = sim.composite.run("")
        assertTrue("a green run inside the budget must answer ok",
            answer.optBoolean("ok"))
        // Every design fits its budget remainder on a healthy run.
        assertEquals(420_000L, sim.windows["dbRecovery"])  // min(420s, 840s)
        assertEquals(240_000L, sim.windows["dbInit"])
        assertEquals(300_000L, sim.windows["dbMigrations"])
        assertEquals(60_000L, sim.windows["dbStart"])
        assertEquals(30_000L, sim.windows["dbHealth"])
        assertEquals(60_000L, sim.windows["realmStart"])
        assertEquals(180_000L, sim.windows["worldStartBotProfile"])
    }

    @Test fun later_stage_windows_are_capped_by_the_budget_remainder() {
        val clock = FakeClock()
        // The slow-but-alive worst case: each stage consumes its whole
        // handed window. 420s (gate) + 240s (dbInit) = 660s of the 840s
        // budget; dbMigrations' 300s design no longer fits and is capped to
        // the 180s remainder - consuming it spends the budget entirely, so
        // dbStart cannot start and the op answers the NAMED total-deadline
        // refusal instead of running on.
        val sim = WindowSim(clock, consumeWindowWhenUnspecified = true)
        val answer = sim.composite.run("")
        assertEquals(420_000L, sim.windows["dbRecovery"])
        assertEquals(240_000L, sim.windows["dbInit"])
        assertEquals("dbMigrations' 300s design must be capped to the 180s remainder",
            180_000L, sim.windows["dbMigrations"])
        assertFalse(answer.optBoolean("ok", true))
        assertEquals("STACKUP_TOTAL_DEADLINE", answer.getString("errorClass"))
        assertEquals("dbStart", answer.getString("timedOutStage"))
        assertEquals(StackUpComposite.STACKUP_OP_BUDGET_MS, answer.getLong("totalBudgetMs"))
        // The budget was consumed EXACTLY - never beyond it: the recorded
        // wedge's horizon (the 1290s stage sum + 210s) is unreachable.
        assertEquals(StackUpComposite.STACKUP_OP_BUDGET_MS, clock.nowMs)
        assertTrue("the wedge horizon (stage sum + 210s) must be unreachable",
            clock.nowMs + 210_000L < StackUpComposite.designedWorstCaseMs)
        assertNull("a stage the budget cannot admit is never handed a window",
            sim.windows["dbStart"])
        assertNull("the run must stop at the refusal - no realm leg",
            sim.windows["realmStart"])
    }
}

/** Controllable fake clock shared by the composite sims. */
class FakeClock {
    var nowMs = 0L
}
