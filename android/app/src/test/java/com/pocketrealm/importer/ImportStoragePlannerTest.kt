package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class ImportStoragePlannerTest {
    @Test fun reportFormulaIncludesEveryOwnerAndTwentyPercentMargin() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 0, scratchArchive = 0, extracted = 4 * gib,
            database = gib, wine = gib, snapshot = gib, minimumReserve = 2 * gib, allocatable = 15 * gib,
        )
        assertEquals(12 * gib, plan.requiredBytes - plan.workingMarginBytes)
        assertEquals(ceil(12 * gib * 0.20).toLong(), plan.workingMarginBytes)
        assertTrue(plan.canProceed)
    }

    @Test fun twoGiBFloorAndInsufficientStorageFailClosed() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(gib, 0, 0, 0, 0, 0, 0, 2 * gib, 2 * gib)
        assertEquals(3 * gib, plan.requiredBytes)
        assertFalse(plan.canProceed)
    }

    @Test fun archiveLaneDoublesTheArchiveBytesAndStillFailsClosed() {
        val gib = ImportLimits.GIB
        // A 6 GiB RU-style archive: staged copy + ~7.5 GiB extracted client.
        val plan = ImportStoragePlanner.calculate(
            source = 7 * gib + 512 * ImportLimits.GIB / 1024, stagedArchive = 6 * gib,
            scratchArchive = 0, extracted = 4 * gib, database = 0, wine = gib, snapshot = 0,
            minimumReserve = 2 * gib, allocatable = 16 * gib,
        )
        val subtotal = plan.requiredBytes - plan.workingMarginBytes
        // staged archive + uncompressed client + wine prefix
        assertEquals(6 * gib + 7 * gib + 512 * ImportLimits.GIB / 1024 + 4 * gib + gib, subtotal)
        assertFalse("16 GiB free must not satisfy an ~18.5 GiB need", plan.canProceed)
    }

    @Test fun installerLaneTriplesTheArchiveBytesAndStillFailsClosed() {
        val gib = ImportLimits.GIB
        // The 4.97 GiB Inno installer archive: staged copy + scratch
        // extraction + the ~4.7 GiB client extracted out of the installer.
        val plan = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 5 * gib, scratchArchive = 5 * gib,
            extracted = 4 * gib, database = 0, wine = gib, snapshot = 0,
            minimumReserve = 2 * gib, allocatable = 16 * gib,
        )
        val subtotal = plan.requiredBytes - plan.workingMarginBytes
        assertEquals(5 * gib + 5 * gib + 5 * gib + 4 * gib + gib, subtotal)
        assertFalse("16 GiB free must not satisfy a 20 GiB need", plan.canProceed)
    }
}
