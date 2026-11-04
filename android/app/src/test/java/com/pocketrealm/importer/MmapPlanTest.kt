package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmapPlanTest {
    @Test fun everyDerivedMapGetsANavmesh() {
        // Test maps and the Emerald Dream included: a bot that reaches any
        // map finds navigation instead of a missing tile.
        val derived = listOf(0, 1, 13, 25, 29, 30, 33, 42, 169, 349, 451, 489, 489)
        assertEquals(derived.distinct(), mmapBuildMapIds(derived))
    }

    @Test fun transportTilesAreRecognisedByName() {
        assertTrue(isGameObjectTile("go3831.mmtile"))
        assertTrue(isGameObjectTile("go0360.mmtile"))
        assertTrue(!isGameObjectTile("0003132.mmtile"))
        assertTrue(!isGameObjectTile("go3831.mmap"))
    }

    @Test fun continentsRunAloneAndSmallMapsShareBatches() {
        val ids = listOf(0, 1) + (30..70) + listOf(530, 571)
        val batches = mmapBatches(ids, batchSize = 16)
        assertEquals(listOf(listOf(0), listOf(1), listOf(530), listOf(571)), batches.take(4))
        val small = batches.drop(4)
        assertEquals((30..70).toList(), small.flatten())
        assertTrue(small.all { it.size <= 16 })
        assertEquals(3, small.size)
    }

    @Test fun everyMapIsPlannedExactlyOnce() {
        val ids = (0..600).filter { it % 7 == 0 } + listOf(1, 530, 571)
        val planned = mmapBatches(ids.distinct().sorted()).flatten()
        assertEquals(ids.distinct().sorted().toSet(), planned.toSet())
        assertEquals(planned.size, planned.toSet().size)
    }

    @Test fun threadCountLeavesTwoCoresAndStaysBounded() {
        assertEquals(2, mmapThreadCount(1))
        assertEquals(2, mmapThreadCount(4))
        assertEquals(6, mmapThreadCount(8))
        assertEquals(10, mmapThreadCount(32))
    }
}
