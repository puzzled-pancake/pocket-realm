package com.pocketrealm.database

import com.pocketrealm.desktop.DesktopOwnershipSlot
import com.pocketrealm.supervisor.ComponentOwner
import org.json.JSONObject

/**
 * The desktop DatabaseService twin (DESK-78): the fault-isolated owner of
 * the MariaDB engine and its live datadir — the Android DatabaseService's
 * control surface, minus the Android-process machinery.
 *
 * Deliberate lane differences, all documented posture:
 *  - no Binder/IBinder lease: the owner's liveness IS this JVM (the
 *    DesktopOwnershipSlot contract — a dead JVM cannot hold a claim, so
 *    the Android owner-loss thread has no desktop analog; the next
 *    process finds every unsealed generation dirty and recovers it);
 *  - no foreground-service promotion: a desktop daemon needs no FGS —
 *    the promote/demote intents have no desktop receiver;
 *  - every control verb keeps the Android guarded() wire shape (ok /
 *    errorClass / error) so a desktop control caller reads the same
 *    responses the Binder callers do.
 *
 * The engine behind `engine` is the DESK-5 [DesktopMariaDbEngine]; while
 * the provider distribution is unstaged, every mutating verb returns the
 * honest DB-LINK failure — never a silent SQLite fallback.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
// The Android service's control surface, kept verb-for-verb; the guarded()
// contract answers every control failure as JSON, never dies.
internal class DesktopDatabaseService(private val engine: DesktopMariaDbEngine) {

    private val ownership = DesktopOwnershipSlot("database")

    fun claim(owner: ComponentOwner): JSONObject = guarded {
        ownership.claim(owner)
        JSONObject()
            .put("ok", true)
            .put("claimed", true)
            .put("component", "database")
            .put("sessionId", owner.sessionId)
            .put("instanceToken", owner.instanceToken)
    }

    fun status(): JSONObject = guarded {
        val value = engine.status()
        // The Android ComponentOwnership.decorate twin: status echoes the
        // live claim (the owner the supervisor's readiness proofs key off).
        ownership.current()?.let { holder ->
            value.put("ownerSessionId", holder.sessionId)
            value.put("ownerInstanceToken", holder.instanceToken)
        }
        value
    }

    fun initialize(): JSONObject = guarded { engine.initialize() }

    fun start(): JSONObject = guarded { engine.start() }

    fun queryHealth(): JSONObject = guarded { engine.queryHealth() }

    /** R1-r3-5 (desktop-twin round 3): the harness gates' verifier
     * seeding + proof-3 read + cleanup for the MariaDB lane — the engine's
     * owner-gated SQL path. The read is not `guarded` (that helper types
     * JSONObject); the engine method is internally synchronized. */
    fun seedSrpVerifierRow(account: String, vHex: String, sHex: String): JSONObject =
        guarded { engine.seedSrpVerifierRow(account, vHex, sHex) }

    fun readVerifierSessionKey(account: String): String? =
        engine.readVerifierSessionKey(account)

    fun deleteVerifierRow(account: String): JSONObject =
        guarded { engine.deleteVerifierRow(account) }

    fun projectRealmEndpoint(
        owner: ComponentOwner,
        address: String,
        worldPort: Int,
        realmbuilds: String,
    ): JSONObject = guarded {
        ownership.verifyOwner(owner)
        engine.projectRealmEndpoint(address, worldPort, realmbuilds)
    }

    fun applyPinnedMigrations(): JSONObject = guarded { engine.applyPinnedMigrations() }

    /** The desktop restore transaction's engine lane (the Android control
     * surface's restore family, DESK-6/18/37/100): BEGIN/COMMIT/ROLLBACK
     * over the engine's durable restore record — guarded so every engine
     * refusal (drain proofs, generation probe, seal restamp) answers on
     * the wire as JSON, never a crash. Maintenance verbs under the claim
     * law, deliberately claim-less exactly like the Android binder's
     * restore verbs: the maintenance lane reaches them only on a
     * clean-stopped runtime — a state entered via [stopOwned], which has
     * already released the claim — and the verification boot's own claim
     * is released again by its stop before COMMIT, so no verifyOwner here
     * could ever pass; the engine holds the destructive half (each leg
     * refuses anything but a clean stopped datadir). */
    fun beginRestore(snapshotId: String): JSONObject = guarded { engine.beginRestore(snapshotId) }

    fun commitRestore(restoreToken: String): JSONObject = guarded { engine.commitRestore(restoreToken) }

    fun rollbackRestore(restoreToken: String): JSONObject = guarded { engine.rollbackRestore(restoreToken) }

    /** The restore family's ownerless crash-recovery leg (the Android
     * binder's rollbackPendingRestore twin, claim-less by the same law):
     * settles a durable restore record a dead process left behind with NO
     * caller token — the record is the authority — guarded so every engine
     * refusal answers on the wire as JSON, never a crash. */
    fun rollbackPendingRestore(): JSONObject = guarded { engine.rollbackPendingRestore() }

    /** The MariaDB lane's world-start timeframe-overlay leg (design
     * timeframe §4.3): bring the live generation to the stage the world
     * conf names; the guarded shape keeps every engine refusal on the
     * wire as JSON, never a crash. */
    fun applyTimeframeOverlay(desiredStage: String): JSONObject =
        guarded { engine.applyTimeframeOverlay(desiredStage) }

    fun stop(): JSONObject = guarded { engine.stop() }

    fun stopOwned(owner: ComponentOwner): JSONObject = guarded {
        ownership.verifyOwner(owner)
        // Releasing the claim is a handshake in THIS process, never an
        // engine operation (the Android stopOwned contract): an engine that
        // is observably down has nothing to stop — the owner-gated release
        // succeeds for the claim while the durable seals stay as they are.
        val stopped = engine.stopForOwnerRelease()
        ownership.release(owner)
        stopped
    }

    fun forceStopOwned(owner: ComponentOwner): JSONObject = guarded {
        ownership.verifyOwner(owner)
        // Same law as stopOwned on the forced lane: killing an engine that
        // is already down would refuse (killForTest has nothing to kill)
        // and strand the escalation fallback with a stale claim. The
        // release is still owner-gated, never unconditional.
        if (engine.isEnded()) {
            ownership.release(owner)
            JSONObject().put("ok", true).put("alreadyDown", true).put("claimReleased", true)
        } else {
            engine.killForTest()
        }
    }

    /** The debug-only forced kill (BuildConfig.DEBUG-gated like Android). */
    fun killForTest(debugBuild: Boolean): JSONObject = guarded {
        check(debugBuild) { "test process kill is debug-only" }
        engine.killForTest()
    }

    fun recover(): JSONObject = guarded { engine.recover() }

    fun close() {
        runCatching { engine.close() }
    }

    /** The Android guarded() wire shape, verbatim. */
    private inline fun guarded(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (error: Throwable) {
        JSONObject()
            .put("ok", false)
            .put("errorClass", error.javaClass.simpleName)
            .put("error", (error.message ?: "database request failed").take(MAX_ERROR_CHARS))
    }

    private companion object {
        /** The Android guarded() copy cap. */
        const val MAX_ERROR_CHARS = 1024
    }
}
