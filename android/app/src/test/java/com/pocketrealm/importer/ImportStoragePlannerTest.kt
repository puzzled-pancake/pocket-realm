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
            source = 5 * gib, extracted = 4 * gib, database = gib, wine = gib,
            snapshot = gib, minimumReserve = 2 * gib, allocatable = 15 * gib,
        )
        assertEquals(12 * gib, plan.requiredBytes - plan.workingMarginBytes)
        assertEquals(ceil(12 * gib * 0.20).toLong(), plan.workingMarginBytes)
        assertTrue(plan.canProceed)
    }

    @Test fun twoGiBFloorAndInsufficientStorageFailClosed() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(gib, 0, 0, 0, 0, 2 * gib, 2 * gib)
        assertEquals(3 * gib, plan.requiredBytes)
        assertFalse(plan.canProceed)
    }
}
