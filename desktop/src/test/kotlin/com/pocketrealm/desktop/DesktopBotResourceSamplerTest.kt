package com.pocketrealm.desktop

import com.pocketrealm.bots.ThermalLevel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-8/23/43/99: the JVM sampler twin's contract — the tick lanes decode
 * from the two shared status arrays exactly like the Android sampler's
 * performance lane, the OS surfaces (process CPU, committed footprint,
 * free physical memory) come from the com.sun.management bean, storage
 * from File.getUsableSpace on the storage root, and thermal is the
 * constant normal level (the desktop has no thermal status). Pure JVM:
 * the arrays are injected, no natives are touched.
 */
class DesktopBotResourceSamplerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun performance(
        p99: Int = 42,
        stalls: Int = 1,
    ): LongArray = longArrayOf(
        1L, // ABI version (unused by the sampler)
        2_048L, // tick samples
        10L, // p50
        25L, // p95
        p99.toLong(), // p99
        90L, // max
        stalls.toLong(), // hard stall window count
        7L, // hard stall total
        123L, // last stall elapsed
        5L, // db probe delay ms
    )

    private fun status(): LongArray = longArrayOf(
        1L, // ABI version
        2L, // READY
        0L, // last error
        16L, // heartbeat ms
        1_000L, // tick count
        42L, // last tick ms
        90L, // max tick ms
        3L, // active sessions
    )

    @Test
    fun readDecodesTheSharedStatusLanesAndTheOsSurfaces() {
        val root = folder.newFolder("storage")
        val sampler = DesktopBotResourceSampler(root)
        val metrics = sampler.read(performance(), status())
        assertEquals(2_048L, metrics.tickSamples)
        assertEquals(10, metrics.worldP50Ms)
        assertEquals(25, metrics.worldP95Ms)
        assertEquals(42, metrics.worldP99Ms)
        assertEquals(90, metrics.worldMaxMs)
        assertEquals(1, metrics.hardStallCount)
        assertEquals(7L, metrics.hardStallTotal)
        assertEquals(123L, metrics.lastHardStallElapsedMs)
        assertEquals(5L, metrics.dbProbeDelayMs)
        // The statusNative tick surface rides along (the world's own view).
        assertEquals(2L, metrics.worldState)
        assertEquals(1_000L, metrics.statusTickCount)
        assertEquals(42L, metrics.statusLastTickMs)
        assertEquals(90L, metrics.statusMaxTickMs)
        // The OS bean surfaces are real on any JVM.
        assertTrue(metrics.freeMemoryMiB > 0)
        assertTrue(metrics.freeStorageMiB > 0)
        assertTrue(metrics.committedMemoryMiB > 0)
        assertTrue("processCpuLoad is 0..1 or -1", metrics.processCpuLoad == -1.0 ||
            metrics.processCpuLoad in 0.0..1.0)
        // DESK-8: thermal is the constant normal level on desktop.
        assertEquals(ThermalLevel.NONE, metrics.thermal)
    }

    @Test
    fun storageComesFromTheRootsUsableSpace() {
        val root = folder.newFolder("storage2")
        // Read the expected side BEFORE the sampler and allow disk-scale
        // drift: usable space is a LIVE value, and concurrent writers on the
        // same drive (a parallel gradle lane, say) move it between this read
        // and the sampler's own — saw 3 MiB drift mid-suite. A
        // WRONG root errs by whole GiB, so 256 MiB still fails those.
        val expected = root.usableSpace / (1024L * 1024L)
        val metrics = DesktopBotResourceSampler(root).read(performance(), status())
        assertTrue(
            "freeStorageMiB derives from File.getUsableSpace on the root " +
                "(expected ~$expected MiB, was ${metrics.freeStorageMiB})",
            Math.abs(metrics.freeStorageMiB - expected) <= 256,
        )
    }

    @Test
    fun contractMismatchesRefuseHonestly() {
        val sampler = DesktopBotResourceSampler(folder.newFolder("storage3"))
        try {
            sampler.read(LongArray(9), status())
            fail("a short performance lane must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue((expected.message ?: "").contains("performance status contract"))
        }
        try {
            sampler.read(performance(), LongArray(7))
            fail("a short status lane must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue((expected.message ?: "").contains("native status contract"))
        }
    }
}
