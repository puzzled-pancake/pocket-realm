package com.pocketrealm.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Pins the console relay's boot fail-fast contract (the row LV-server-db-09
 * hang): a bounded stage answers, a wedged stage is abandoned inside its
 * window instead of hanging the op, the stage-timeout answer names the
 * refusing component, and the world boot's DB-lane precondition refuses a
 * stopped lane - the REAL lane included, since a fresh instrumentation
 * process boots :database stopped, the exact recorded precondition - instead
 * of firing a native boot that never returns. Mechanism windows here are
 * sub-second; the production windows live in WorldConsoleRelay's companion,
 * each grounded in a code-designed bound (the engine's own tool bounds, the
 * O09RealmRuntimeTest readiness windows, the host console's 900s slow-op
 * window).
 */
@RunWith(AndroidJUnit4::class)
class WorldConsoleRelayBootGuardTest {
    private val relay = WorldConsoleRelay()

    @Test fun boundedCall_answers_inside_its_window() {
        assertEquals("answered", relay.boundedCall("TEST", 10_000L) { "answered" })
    }

    @Test fun wedged_stage_is_abandoned_inside_its_window() {
        val elapsedMs = measureTimeMillis {
            assertNull(relay.boundedCall("TEST", 300L) { Thread.sleep(3_000); "late" })
        }
        assertTrue("a wedged stage must answer inside its window, took ${elapsedMs}ms",
            elapsedMs < 2_000)
    }

    @Test fun bounded_stage_propagates_the_worker_failure() {
        assertThrows(IllegalStateException::class.java) {
            relay.boundedCall<String>("TEST", 10_000L) { error("leg failed") }
        }
    }

    @Test fun stage_timeout_names_the_refusing_component_and_window() {
        val timeout = relay.stageTimeoutResponse(
            "stack-up-bot", "dbInit", "DATABASE", 180_000L)
        assertFalse(timeout.optBoolean("ok", true))
        assertEquals("DATABASE_STAGE_TIMEOUT", timeout.getString("errorClass"))
        assertEquals("dbInit", timeout.getString("timedOutStage"))
        assertEquals(180_000L, timeout.getLong("stageWindowMs"))
        assertTrue(timeout.getString("error").contains("dbInit"))
        assertTrue(timeout.getString("error").contains("DATABASE"))
        assertTrue(timeout.getString("remedy").contains("stack-up-bot"))
    }

    @Test fun stopped_lane_refusal_names_the_db_precondition() {
        val stopped = JSONObject().put("state", "STOPPED")
            .put("initialized", false).put("providerMode", "MARIADB")
        val refusal = relay.worldBootRefusal("world-start", stopped)
        assertNotNull(refusal)
        assertEquals("DB_NOT_RUNNING", refusal!!.getString("errorClass"))
        assertTrue(refusal.getString("error").contains("state=STOPPED"))
        assertTrue(refusal.getString("error").contains("initialized=false"))
        assertTrue(refusal.getString("remedy").contains("stack-up-bot"))
    }

    @Test fun unanswering_lane_refusal_is_typed_not_ready() {
        val refusal = relay.worldBootRefusal("world-start-bot", null)
        assertNotNull(refusal)
        assertEquals("DATABASE_NOT_READY", refusal!!.getString("errorClass"))
        assertTrue(refusal.getString("remedy").contains("stack-up-bot"))
    }

    @Test fun running_lane_passes_the_precondition() {
        val running = JSONObject().put("state", "RUNNING")
            .put("initialized", true).put("providerMode", "MARIADB")
        assertNull(relay.worldBootRefusal("world-start", running))
    }

    @Test fun world_start_guard_matches_the_real_db_lane() {
        val status = relay.databaseStatus()
        assertNotNull("the real database lane must answer status", status)
        val refusal = relay.worldBootRefusal("world-start", status)
        // The guard's decision must MATCH the observed lane: a fresh
        // instrumentation process boots :database stopped (the recorded
        // row -09 precondition), but an earlier test in this process may
        // have left the engine RUNNING - either way the refusal is decided
        // by the lane's state, never fired blindly.
        if (status!!.optString("state") == "RUNNING") {
            assertNull("a running lane must pass the precondition", refusal)
        } else {
            assertNotNull("a stopped lane must refuse the world boot", refusal)
            assertEquals("DB_NOT_RUNNING", refusal!!.getString("errorClass"))
            assertTrue(refusal.getString("error").contains("state="))
        }
    }
}
