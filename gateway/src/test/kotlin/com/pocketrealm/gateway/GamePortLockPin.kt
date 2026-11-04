package com.pocketrealm.gateway

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W6-N4 R4-r2-3's proposed pin: the cross-seat
 * game-port lock's release must NEVER delete the lock file — a skipping
 * seat's afterEach runs even when its beforeEach aborted without
 * acquiring, and deleting the file under a live holder re-opens the
 * exact two-inode bind race the guard was built to close.
 *
 * The pin exercises the semantics on a SANDBOX lock file: deleting the
 * SHARED file mid-run is itself the race (the concurrent
 * validation caught exactly that — this pin's earlier cleanSlate deleted
 * the shared file under the other seat's lock).
 */
class GamePortLockPin {

    private val sandboxLock: File = File.createTempFile("pocket-realm-game-ports-pin", ".lock")

    @BeforeTest
    fun cleanSlate() {
        releaseGamePortLock()
        sandboxLock.delete()
    }

    @AfterTest
    fun releaseWhateverWeHold() {
        releaseGamePortLock()
        sandboxLock.delete()
    }

    @Test
    fun releaseGamePortLockNeverDeletesTheLockFile() {
        // Cold release (nothing acquired — the aborted-beforeEach shape):
        // the on-disk file must survive untouched.
        sandboxLock.writeText("pre-existing")
        releaseGamePortLock()
        assertTrue(
            sandboxLock.exists(),
            "a cold release (nothing acquired) must never delete the lock file",
        )
        // A held release: the file must STILL survive (a leftover file
        // never blocks — tryLock re-locks it — while a delete re-opens
        // the two-inode race under a concurrent seat).
        assertTrue(
            acquireGamePortLock(budgetMs = 5_000, lockFile = sandboxLock),
            "this pin must hold the lock for the held-release leg",
        )
        releaseGamePortLock()
        assertTrue(
            sandboxLock.exists(),
            "a held release must never delete the lock file",
        )
    }

    @Test
    fun theLockIsReAcquirableAfterAHeldRelease() {
        assertTrue(acquireGamePortLock(budgetMs = 5_000, lockFile = sandboxLock))
        releaseGamePortLock()
        assertTrue(
            acquireGamePortLock(budgetMs = 5_000, lockFile = sandboxLock),
            "a released lock must be immediately re-acquirable (the file " +
                "survives but holds no lock)",
        )
        releaseGamePortLock()
    }
}
