package com.pocketrealm.database

import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.supervisor.ComponentOwner
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop DatabaseService twin (DESK-78): the fault-isolated owner of
 * the MariaDB engine, with the Android service's control surface minus the
 * Binder machinery. Pinned here: the ownership handshake (claim/verify/
 * release), the guarded() error wire shape, and the owner-gated stop verbs'
 * refusal of a mismatched owner — all against the honest unstaged engine
 * (the physical provider distribution is the lane's external dependency).
 */
class DesktopDatabaseServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ownerA = ComponentOwner("session-a", "instance-a")
    private val ownerB = ComponentOwner("session-b", "instance-b")

    /** An engine whose provider is honestly unstaged (no DB-LINK closure):
     * every engine verb fails with the DB-LINK copy, which is exactly what
     * the service's guarded() shape must surface. */
    private fun service(): DesktopDatabaseService {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }
        return DesktopDatabaseService(
            DesktopMariaDbEngine(
                roots,
                providerOverride = DesktopMariaDbProvider(
                    stagedRootOverride = folder.newFolder("nothing-here"),
                    repoRootOverride = folder.newFolder("repo-empty"),
                ),
            ),
        )
    }

    @Test
    fun claimStatusAndTheOwnershipHandshake() {
        val database = service()
        val claimed = database.claim(ownerA)
        assertTrue(claimed.getBoolean("ok"))
        assertEquals("database", claimed.getString("component"))
        assertEquals("session-a", claimed.getString("sessionId"))
        // Status echoes the live claim (the Android decorate twin).
        val status = database.status()
        assertTrue(status.getBoolean("ok"))
        assertEquals("session-a", status.getString("ownerSessionId"))
        assertEquals("instance-a", status.getString("ownerInstanceToken"))
    }

    @Test
    fun aSecondClaimByAnotherSessionRefuses() {
        val database = service()
        database.claim(ownerA)
        // The refusal arrives through the guarded() wire shape (the Android
        // Binder surface never throws to the caller either).
        val refused = database.claim(ownerB)
        assertFalseOk(refused)
        assertTrue(
            "the refusal must name the live claim: ${refused.getString("error")}",
            "database is owned by another runtime session" in refused.getString("error"),
        )
    }

    @Test
    fun ownerScopedVerbsWithholdFromANonOwner() {
        val database = service()
        database.claim(ownerA)
        // The wire shape is the guarded() error, never a throw to the caller.
        val denied = database.projectRealmEndpoint(ownerB, "127.0.0.1", 8085, "5875 6005 6141")
        assertFalseOk(denied)
        assertEquals("IllegalStateException", denied.getString("errorClass"))
        val deniedStop = database.stopOwned(ownerB)
        assertFalseOk(deniedStop)
        val deniedForce = database.forceStopOwned(ownerB)
        assertFalseOk(deniedForce)
    }

    /** The claim law's other half (the Android binder's DatabaseService
     * comment, same posture): backup/restore are maintenance verbs — they
     * carry NO verifyOwner, because the maintenance lane admits restore
     * only on a clean-stopped runtime (a state entered via stopOwned,
     * which has already released the claim) and the verification boot's
     * own claim is released again by its stop before COMMIT. Pinned: the
     * verbs reach the engine and answer ITS honest gates with the slot
     * empty and with a live claim alike — never an ownership mismatch,
     * which would strand every real restore at BEGIN or COMMIT. */
    @Test
    fun restoreVerbsAreClaimLessMaintenanceVerbs() {
        val database = service()
        val begun = database.beginRestore("manual-anything")
        assertFalseOk(begun)
        assertEquals("ProviderNotStaged", begun.getString("errorClass"))
        val committed = database.commitRestore("137ea97e-1b34-4c15-9f2a-6f2b16c58b1f")
        assertFalseOk(committed)
        assertEquals("DB-SNAPSHOT: no restore verification is pending", committed.getString("error"))
        val rolledBack = database.rollbackRestore("137ea97e-1b34-4c15-9f2a-6f2b16c58b1f")
        assertFalseOk(rolledBack)
        assertEquals("DB-SNAPSHOT: no restore verification is pending", rolledBack.getString("error"))
        listOf(begun, committed, rolledBack).forEach { refused ->
            assertTrue(
                "a maintenance verb must never answer an ownership gate: " +
                    refused.getString("error"),
                "ownership" !in refused.getString("error"),
            )
        }
        // A live claim does not scope the family either: the restore-time
        // claim was released on the way to rest, and the proof boot's claim
        // is spent before COMMIT, so verifyOwner here would refuse every
        // real restore.
        database.claim(ownerA)
        val claimed = database.beginRestore("manual-anything")
        assertFalseOk(claimed)
        assertEquals("ProviderNotStaged", claimed.getString("errorClass"))
    }

    @Test
    fun engineFailuresSurfaceThroughTheGuardedWireShape() {
        val database = service()
        // initialize on an unstaged provider: the DB-LINK refusal arrives as
        // the guarded error (ok=false + the honest copy), not a throw.
        val failed = database.initialize()
        assertFalseOk(failed)
        assertEquals("ProviderNotStaged", failed.getString("errorClass"))
        assertTrue(
            "the error copy must carry the DB-LINK refusal: ${failed.getString("error")}",
            failed.getString("error").contains("DB-LINK"),
        )
    }

    @Test
    fun forceStopOnAnEndedEngineReleasesWithoutTheEngine() {
        val database = service()
        database.claim(ownerA)
        // An engine that never ran is observably down: the release succeeds
        // for the claim without an engine operation (the Android contract).
        val released = database.forceStopOwned(ownerA)
        assertTrue(released.getBoolean("ok"))
        assertTrue(released.getBoolean("alreadyDown"))
        assertTrue(released.getBoolean("claimReleased"))
    }

    @Test
    fun killForTestIsDebugOnly() {
        val database = service()
        val refused = database.killForTest(debugBuild = false)
        assertFalseOk(refused)
        assertTrue("test process kill is debug-only" in refused.getString("error"))
    }

    @Test
    fun closeIsSafeOnAnInertEngine() {
        val database = service()
        database.claim(ownerA)
        database.close()
    }

    private fun assertFalseOk(value: JSONObject) {
        assertTrue("guarded responses must parse", value.has("ok"))
        assertEquals(false, value.getBoolean("ok"))
        assertTrue("the error copy must be present", value.getString("error").isNotEmpty())
    }
}
