package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.testFlavor
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.ceil
import org.junit.Before

/**
 * DESK-3/66: the desktop storage planner twin. The [calculate] pins mirror
 * the shared android ImportStoragePlannerTest formulas verbatim (same owner
 * set, same 20%-margin-vs-2GiB-floor rule, same fail-closed verdicts); the
 * instance leg pins the named substitution — `File.getUsableSpace` on the
 * managed-content volume in place of StorageManager.getAllocatableBytes.
 */
class DesktopImportStoragePlannerTest {
    /** W4 flip fixture: the sole-qualified degrade is retired; machinery
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

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test
    fun importTotalCapIsTheQualifiedFlavorStorageBudget() {
        // ImportLimits.maxTotalBytes is flavor data, not a hard-coded cap:
        // its default resolves the qualified flavor's storage.max_total_bytes.
        assertEquals(ClientFlavors.VANILLA.maxTotalBytes, ImportLimits().maxTotalBytes)
    }

    @Test
    fun plannerDefaultEstimateResolvesTheActiveLaneThroughTheFlavorMachine() {
        // The multi-flavor lift: the construction-time extraction budget is
        // the ACTIVE lane's measured estimate, not a frozen vanilla number.
        val tbcEstimate = 8L * ImportLimits.GIB
        ActiveFlavor.qualifiedSource = {
            listOf(
                testFlavor("vanilla", qualified = true),
                testFlavor("tbc", qualified = true, extractedEstimateBytes = tbcEstimate),
            )
        }
        ActiveFlavor.select("tbc")
        val root = folder.newFolder("tbc-roots")
        val roots = DesktopStorageRoots(root).also { it.ensureDirectories() }
        val plan = ImportStoragePlanner(roots).plan(sourceBytes = 0)
        assertEquals(tbcEstimate, plan.extractedDataBytes)
    }

    @Test
    fun reportFormulaIncludesEveryOwnerAndTwentyPercentMargin() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(
            source = 5 * gib, stagedArchive = 0, scratchArchive = 0, extracted = 4 * gib,
            database = gib, wine = gib, snapshot = gib, minimumReserve = 2 * gib, allocatable = 15 * gib,
        )
        assertEquals(12 * gib, plan.requiredBytes - plan.workingMarginBytes)
        assertEquals(ceil(12 * gib * 0.20).toLong(), plan.workingMarginBytes)
        assertTrue(plan.canProceed)
    }

    @Test
    fun twoGiBFloorAndInsufficientStorageFailClosed() {
        val gib = ImportLimits.GIB
        val plan = ImportStoragePlanner.calculate(gib, 0, 0, 0, 0, 0, 0, 2 * gib, 2 * gib)
        assertEquals(3 * gib, plan.requiredBytes)
        assertFalse(plan.canProceed)
    }

    @Test
    fun archiveLaneDoublesTheArchiveBytesAndStillFailsClosed() {
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

    @Test
    fun installerLaneTriplesTheArchiveBytesAndStillFailsClosed() {
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

    @Test
    fun planUsesGetUsableSpaceOnTheContentVolumeAndCountsTheDatadir() {
        val root = folder.newFolder("roots")
        val roots = DesktopStorageRoots(root).also { it.ensureDirectories() }
        val datadirFile = File(roots.sqliteDatadir.apply { mkdirs() }, "classicrealmd.sqlite3").apply {
            writeText(ByteArray(4096).decodeToString())
        }
        assertTrue(datadirFile.isFile)
        val plan = ImportStoragePlanner(roots).plan(sourceBytes = 0)
        assertEquals("allocatable resolves from File.getUsableSpace on the content root",
            roots.content.usableSpace.coerceAtLeast(0), plan.allocatableBytes)
        assertEquals(4096L, plan.databaseBytes)
    }

    @Test
    fun injectedAllocatableReadDrivesTheVerdictWithoutTheHostVolume() {
        val root = folder.newFolder("roots-injected")
        val roots = DesktopStorageRoots(root).also { it.ensureDirectories() }
        // The seam the pipeline tests use: the injected read, not the host
        // TEMP volume's real headroom, decides canProceed in both directions.
        val generous = ImportStoragePlanner(roots, allocatableBytes = { 64L * ImportLimits.GIB })
            .plan(sourceBytes = 0)
        assertEquals(64L * ImportLimits.GIB, generous.allocatableBytes)
        assertTrue("64 GiB injected allocatable must satisfy the budget", generous.canProceed)
        val empty = ImportStoragePlanner(roots, allocatableBytes = { 0L })
            .plan(sourceBytes = 0)
        assertEquals(0L, empty.allocatableBytes)
        assertFalse(empty.canProceed)
    }
    @Test
    fun planCountsBothProvidersDatadirsAndTheRealSnapshotStore() {
        // R4-r2-1 (desktop-twin round 2): the shipped-default MariaDB
        // datadir and the real snapshot store contribute their bytes —
        // measuring only the sqlite datadir under-counted the durable
        // state on the shipped lane (and maxOf(same, same) was a no-op).
        val root = folder.newFolder("roots-both")
        val roots = DesktopStorageRoots(root).also { it.ensureDirectories() }
        fun seed(dir: File, name: String, bytes: Int) {
            File(dir.apply { mkdirs() }, name).writeText(ByteArray(bytes).decodeToString())
        }
        seed(roots.mariadbDatadir, "world-db", 4096)
        seed(roots.sqliteDatadir, "classicrealmd.sqlite3", 4096)
        seed(roots.databaseSnapshots, "snapshot.bin", 8192)
        val plan = ImportStoragePlanner(roots).plan(sourceBytes = 0)
        assertEquals(
            "databaseBytes counts BOTH providers' live datadirs",
            8192L, plan.databaseBytes,
        )
        assertTrue(
            "the snapshot floor reflects the REAL snapshot store",
            plan.minimumSnapshotBytes >= 8192L,
        )
    }
}
