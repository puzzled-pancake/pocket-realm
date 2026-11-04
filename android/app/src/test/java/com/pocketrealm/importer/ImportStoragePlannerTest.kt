package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.testFlavor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.ceil
import org.junit.Before

class ImportStoragePlannerTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @Test fun importTotalCapIsTheQualifiedFlavorStorageBudget() {
        // ImportLimits.maxTotalBytes is flavor data, not a hard-coded cap:
        // its default resolves the qualified flavor's storage.max_total_bytes.
        assertEquals(ClientFlavors.VANILLA.maxTotalBytes, ImportLimits().maxTotalBytes)
    }

    @After fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test fun importTotalCapResolvesTheActiveLaneThroughTheFlavorMachine() {
        // The multi-flavor lift: with a second qualified lane selected, the
        // cap is THAT lane's measured storage.max_total_bytes — never a
        // silent vanilla constant.
        val tbcBudget = 24L * ImportLimits.GIB
        ActiveFlavor.qualifiedSource = {
            listOf(
                testFlavor("vanilla", qualified = true),
                testFlavor("tbc", qualified = true, maxTotalBytes = tbcBudget),
            )
        }
        ActiveFlavor.select("tbc")
        assertEquals(tbcBudget, ImportLimits().maxTotalBytes)
    }

    @Test fun ambiguousQualifiedStateKeepsFailingLoudInLimits() {
        // Zero qualified flavors: the former single-qualified honesty is
        // preserved by the machine (the site's lane suffix names it).
        ActiveFlavor.qualifiedSource = { emptyList() }
        try {
            ImportLimits()
            fail("limits over an empty registry must fail loud")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "no qualified flavor is registered — " +
                    "import size limits resolve the qualified lane",
                expected.message,
            )
        }
    }

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
        val plan = ImportStoragePlanner.calculate(gib, 0, 0, 0, 0, 0, 0, 0, 2 * gib, 2 * gib)
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
    }            @Test
        fun preparedRowRefusesTheExactFitLane() {
            // The prepared-server-data OUTPUT the
            // flow writes after the import is its own budget row — an
            // allocatable == the db-less need lane must refuse once the
            // prepared bytes are charged.
            val gib = ImportLimits.GIB
            // positional order: source, staged, scratch, extracted,
            // prepared, database, wine, snapshot, minimumReserve, allocatable
            val need = ImportStoragePlanner.calculate(
                5 * gib, 0, 0, 4 * gib, 0, 0, gib, 0, 2 * gib, 15 * gib,
            ).requiredBytes
            assertEquals(12 * gib, need)
            val exactFit = ImportStoragePlanner.calculate(
                5 * gib, 0, 0, 4 * gib, 0, 0, gib, 0, 2 * gib, need,
            )
            assertTrue(exactFit.canProceed)
            val charged = ImportStoragePlanner.calculate(
                5 * gib, 0, 0, 4 * gib, gib, 0, gib, 0, 2 * gib, need,
            )
            assertFalse("the prepared row must push an exact-fit lane over", charged.canProceed)
        }

    @Test fun removableVolumeSplitsTheRowsAndGatesEachVolumeIndependently() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 6 * gib, scratchArchive = 0, extracted = 4 * gib,
            prepared = gib, database = gib, wine = gib, snapshot = gib,
            minimumReserve = 2 * gib, allocatable = 5 * gib,
            contentOnRemovable = true, contentAllocatable = 20 * gib,
        )
        assertTrue(plan.contentOnRemovable)
        // content rows on the card: source + staged + extracted + prepared
        val contentSubtotal = plan.contentVolumeRequiredBytes - ceil(16 * gib * 0.20).toLong()
        assertEquals(5 * gib + 6 * gib + 4 * gib + gib, contentSubtotal)
        // internal rows: database + wine + snapshot at the 2 GiB reserve floor
        assertEquals(3 * gib + 2 * gib, plan.requiredBytes)
        assertTrue("small internal + roomy card must proceed", plan.canProceed)
        // Card too small: the gate must fail even though internal fits.
        val tightCard = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 6 * gib, scratchArchive = 0, extracted = 4 * gib,
            prepared = gib, database = gib, wine = gib, snapshot = gib,
            minimumReserve = 2 * gib, allocatable = 5 * gib,
            contentOnRemovable = true, contentAllocatable = 10 * gib,
        )
        assertFalse("a card too small for the content rows must refuse", tightCard.canProceed)
        assertTrue(
            "the refusal must name both volumes",
            "sdcard(need=" in tightCard.preflightMessage() && "internal(need=" in tightCard.preflightMessage(),
        )
        // Internal too small: fails even with a roomy card.
        val tightInternal = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 6 * gib, scratchArchive = 0, extracted = 4 * gib,
            prepared = gib, database = gib, wine = gib, snapshot = gib,
            minimumReserve = 2 * gib, allocatable = gib,
            contentOnRemovable = true, contentAllocatable = 20 * gib,
        )
        assertFalse("internal too small for db/wine/snapshot must refuse", tightInternal.canProceed)
    }

    @Test fun removableVolumeCarriesTheInstallerScratchRow() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 5 * gib, scratchArchive = 5 * gib, extracted = 4 * gib,
            prepared = 0, database = 0, wine = gib, snapshot = 0,
            minimumReserve = 2 * gib, allocatable = 8 * gib,
            contentOnRemovable = true, contentAllocatable = 30 * gib,
        )
        // card: staged + scratch + source + extracted; internal: wine only at the floor
        val contentSubtotal = plan.contentVolumeRequiredBytes - ceil(19 * gib * 0.20).toLong()
        assertEquals(5 * gib + 5 * gib + 5 * gib + 4 * gib, contentSubtotal)
        assertEquals(3 * gib, plan.requiredBytes)
        assertTrue(plan.canProceed)
    }

}
