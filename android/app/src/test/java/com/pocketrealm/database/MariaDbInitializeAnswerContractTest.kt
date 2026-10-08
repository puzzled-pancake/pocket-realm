package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pin for LVF-server-db-015 (mariadb-fake-ok): a MariaDB initialize answer
 * with ok:true MUST carry BOTH pinned least-privilege probe keys
 * (leastPrivilegeVerified = the pocket_core health query,
 * privilegedActionDenied = the pocket_core CREATE USER denial). The former
 * key-less idempotent early-return answered ok on a leftover initialized
 * datadir in ~134 ms with the daemon never booting - a fake-ok no state
 * file can ever legitimately prove. Every MariaDB initialize answer is now
 * built through DatabaseDurableState.verifiedMariaDbInitializeAnswer, which
 * refuses (fails loud) any ok:true without both keys.
 */
class MariaDbInitializeAnswerContractTest {
    private val generation = "11111111-1111-4111-8111-111111111111"
    private val bootstrapSha = "b".repeat(64)
    private val closureSha = "a".repeat(64)

    @Test fun firstRunOkShapeCarriesBothProbeKeys() {
        val answer = DatabaseDurableState.verifiedMariaDbInitializeAnswer {
            put("ok", true).put("initialized", true)
                .put("generationUuid", generation)
                .put("bootstrapSha256", bootstrapSha)
                .put("providerClosureSha256", closureSha)
                .put("leastPrivilegeVerified", true)
                .put("privilegedActionDenied", true)
                .put("cleanStopped", true)
        }
        assertTrue(answer.getBoolean("ok"))
        assertTrue(answer.getBoolean("leastPrivilegeVerified"))
        assertTrue(answer.getBoolean("privilegedActionDenied"))
    }

    @Test fun idempotentReVerifiedOkShapeCarriesBothProbeKeys() {
        // The idempotent lane's new shape: the daemon genuinely booted, the
        // probes re-ran, and the generation was clean-stopped before this
        // answer was built.
        val answer = DatabaseDurableState.verifiedMariaDbInitializeAnswer {
            put("ok", true).put("initialized", true).put("idempotent", true)
                .put("generationUuid", generation)
                .put("leastPrivilegeVerified", true)
                .put("privilegedActionDenied", true)
                .put("cleanStopped", true)
        }
        assertTrue(answer.getBoolean("ok"))
        assertTrue(answer.getBoolean("leastPrivilegeVerified"))
        assertTrue(answer.getBoolean("privilegedActionDenied"))
        assertTrue(answer.getBoolean("idempotent"))
        assertTrue(answer.getBoolean("cleanStopped"))
    }

    @Test fun keyLessIdempotentOkIsRefused() {
        // EXACTLY the observed LVF-server-db-015 answer: the old
        // mariadbInitialize idempotent early-return shape (ok/initialized/
        // idempotent, NO probe keys). Building it through the production
        // guard must fail loud - it is the fake-ok this pin exists for.
        try {
            DatabaseDurableState.verifiedMariaDbInitializeAnswer {
                put("ok", true).put("initialized", true).put("idempotent", true)
            }
            fail("key-less idempotent ok:true must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                expected.message!!.contains("leastPrivilegeVerified") &&
                    expected.message!!.contains("privilegedActionDenied"),
            )
        }
    }

    @Test fun keyLessFirstRunOkIsRefused() {
        try {
            DatabaseDurableState.verifiedMariaDbInitializeAnswer {
                put("ok", true).put("initialized", true)
                    .put("generationUuid", generation)
                    .put("bootstrapSha256", bootstrapSha)
                    .put("providerClosureSha256", closureSha)
                    .put("cleanStopped", true)
            }
            fail("key-less first-run ok:true must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("probe keys"))
        }
    }

    @Test fun eitherProbeKeyAloneIsInsufficient() {
        for (partial in listOf("leastPrivilegeVerified", "privilegedActionDenied")) {
            try {
                DatabaseDurableState.verifiedMariaDbInitializeAnswer {
                    put("ok", true).put("initialized", true).put("idempotent", true)
                        .put(partial, true)
                }
                fail("ok:true with only $partial must be refused")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("probe keys"))
            }
        }
    }

    @Test fun failureShapesPassThroughWithoutProbeKeys() {
        // guarded() failure shapes (ok:false + errorClass/error) are not
        // success claims - the contract only constrains ok:true answers.
        val failure = DatabaseDurableState.verifiedMariaDbInitializeAnswer {
            put("ok", false)
                .put("errorClass", "IllegalStateException")
                .put("error", "DB-RECOVERY: database generation is not sealed")
        }
        assertFalse(failure.getBoolean("ok"))
        assertEquals("IllegalStateException", failure.getString("errorClass"))
        assertEquals(false, failure.has("leastPrivilegeVerified"))
        assertEquals(false, failure.has("privilegedActionDenied"))
    }
}
