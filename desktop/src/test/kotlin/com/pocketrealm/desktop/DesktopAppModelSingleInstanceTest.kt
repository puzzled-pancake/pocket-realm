package com.pocketrealm.desktop

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-113: the single-owner runtime domain. The app model acquires an
 * exclusive FileChannel lock on <roots.realm>/app-instance.lock BEFORE any
 * supervisor/backend exists; a second instance refuses with the holder pid
 * named, and the lock releases on close (the crash case releases at process
 * death, since it is an OS lock — not a marker file).
 */
class DesktopAppModelSingleInstanceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun firstInstanceStampsItsPidAndReleasesOnClose() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val model = DesktopAppModel(roots)
        try {
            val lockFile = File(roots.realm, AppInstanceLock.FILE_NAME)
            assertTrue("the lock file must carry the winner's pid", lockFile.isFile)
            assertEquals(
                "the stamped pid is this JVM's (readable despite the held byte lock)",
                ProcessHandle.current().pid().toInt(),
                AppInstanceLock.heldPid(roots.realm),
            )
        } finally {
            model.close()
        }
        // The OS lock releases on close: a successor acquires cleanly (the
        // process-death case behaves identically — there is no stale marker).
        val successor = DesktopAppModel(roots)
        successor.close()
    }

    @Test
    fun secondInstanceRefusesNamingTheHolderPid() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        roots.ensureDirectories()
        // The live holder: another channel (here: the test's, in production:
        // the first app process) owns the exclusive lock and has stamped its
        // pid AFTER the locked sentinel byte (the holder's file layout).
        val interloper = RandomAccessFile(File(roots.realm, AppInstanceLock.FILE_NAME), "rw")
        interloper.setLength(0)
        interloper.write("L4321".toByteArray())
        val held = interloper.channel.lock(0, 1, false)
        try {
            val failure = runCatching { DesktopAppModel(roots) }.exceptionOrNull()
            assertTrue(
                "the model must refuse before constructing a second supervisor/backend",
                failure is AppInstanceLockHeld,
            )
            val held1 = failure as AppInstanceLockHeld
            assertEquals("the refusal must name the holder pid", 4321, held1.holderPid)
            assertTrue(
                "the refusal must name the lock file",
                held1.lockFile.name == AppInstanceLock.FILE_NAME,
            )
        } finally {
            held.release()
            interloper.close()
        }
    }

    @Test
    fun anUnreadableHolderStillRefusesWithANullPid() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        roots.ensureDirectories()
        val interloper = RandomAccessFile(File(roots.realm, AppInstanceLock.FILE_NAME), "rw")
        interloper.setLength(0)
        interloper.write("Lnot-a-pid".toByteArray())
        val held = interloper.channel.lock(0, 1, false)
        try {
            val failure = runCatching { DesktopAppModel(roots) }.exceptionOrNull()
            assertTrue(failure is AppInstanceLockHeld)
            assertEquals(null, (failure as AppInstanceLockHeld).holderPid)
        } finally {
            held.release()
            interloper.close()
        }
    }
}
