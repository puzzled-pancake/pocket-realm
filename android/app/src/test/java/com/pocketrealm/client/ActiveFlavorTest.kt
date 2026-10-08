package com.pocketrealm.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The multi-flavor active-lane state machine (the lift of the former
 * single-qualified fail-loud sites): resolution laws, fail-loud honesty
 * for genuinely ambiguous states, the switcher chip mapping, and the
 * holder's seam-driven selection.
 */
class ActiveFlavorTest {

    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test
    fun soleQualifiedFlavorIsTheDefaultResolution() {
        // Today's registry state: exactly one qualified lane, no explicit
        // selection — byte-identical to the old qualified().single().
        val vanilla = testFlavor("vanilla", qualified = true)
        val resolved = ActiveFlavorState().resolve(listOf(vanilla), TEST_LANE)
        assertEquals(vanilla, resolved)
    }

    @Test
    fun explicitSelectionResolvesOverTheDefault() {
        val vanilla = testFlavor("vanilla", qualified = true)
        val tbc = testFlavor("tbc", qualified = true, botMaxLevel = 70)
        val qualified = listOf(vanilla, tbc)
        val state = ActiveFlavorState().select("tbc", qualified)
        assertEquals(tbc, state.resolve(qualified, TEST_LANE))
    }

    @Test
    fun multipleQualifiedWithoutSelectionFailsLoudWithTheOriginalMessage() {
        val qualified = listOf(
            testFlavor("vanilla", qualified = true),
            testFlavor("tbc", qualified = true),
        )
        try {
            ActiveFlavorState().resolve(qualified, "storage budgets resolve the qualified lane")
            fail("ambiguous multi-qualified state must fail loud")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "expected exactly one qualified flavor, found 2 — " +
                    "storage budgets resolve the qualified lane",
                expected.message,
            )
        }
    }

    @Test
    fun zeroQualifiedFlavorsFailLoud() {
        try {
            ActiveFlavorState().resolve(emptyList(), TEST_LANE)
            fail("empty registry must fail loud")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "no qualified flavor is registered — $TEST_LANE",
                expected.message,
            )
        }
    }

    @Test
    fun staleSelectionFailsLoudEvenAgainstASoleQualifiedFlavor() {
        // A pick that lost qualification must never silently downgrade to
        // another lane (the original code's VANILLA-silent-default refusal).
        val vanilla = testFlavor("vanilla", qualified = true)
        try {
            ActiveFlavorState(selectedFlavorId = "tbc").resolve(listOf(vanilla), TEST_LANE)
            fail("stale selection must fail loud")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "active flavor \"tbc\" is not qualified — $TEST_LANE",
                expected.message,
            )
        }
    }

    @Test
    fun selectRefusesUnqualifiedFlavors() {
        val qualified = listOf(
            testFlavor("vanilla", qualified = true),
            testFlavor("tbc", qualified = false),
        )
        try {
            ActiveFlavorState().select("tbc", qualified)
            fail("declared-but-disabled flavors are not selectable")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "refusal names the declared lanes: ${expected.message}",
                expected.message.orEmpty().contains("flavor \"tbc\" is not qualified"),
            )
        }
    }

    @Test
    fun clearReturnsToTheDefaultResolution() {
        val vanilla = testFlavor("vanilla", qualified = true)
        val tbc = testFlavor("tbc", qualified = true)
        val qualified = listOf(vanilla, tbc)
        val state = ActiveFlavorState().select("tbc", qualified).clear()
        try {
            state.resolve(qualified, TEST_LANE)
            fail("cleared selection over a multi-qualified list is ambiguous again")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().startsWith("expected exactly one qualified flavor"))
        }
    }

    @Test
    fun switcherChipsCarryEveryDeclaredFlavorWithHonestSelectableFlags() {
        ActiveFlavor.qualifiedSource = {
            listOf(testFlavor("vanilla", qualified = true))
        }
        val chips = ActiveFlavor.switcherChips()
        assertEquals(listOf("vanilla", "tbc", "wotlk"), chips.map { it.flavor.flavorId })
        assertEquals(listOf("Vanilla", "TBC", "WotLK"), chips.map { it.label })
        // The sole qualified lane is selected by default; the declared-but-
        // disabled flavors render present but unselectable.
        assertTrue(chips.single { it.flavor.flavorId == "vanilla" }.selected)
        assertFalse(chips.single { it.flavor.flavorId == "tbc" }.selectable)
        assertFalse(chips.single { it.flavor.flavorId == "wotlk" }.selectable)
    }

    @Test
    fun holderSelectionDrivesResolveAndChipsThroughTheQualifiedSource() {
        val vanilla = testFlavor("vanilla", qualified = true)
        val tbc = testFlavor("tbc", qualified = true, botMaxLevel = 70)
        ActiveFlavor.qualifiedSource = { listOf(vanilla, tbc) }

        ActiveFlavor.select("tbc")
        assertEquals("tbc", ActiveFlavor.selectedId)
        assertEquals(tbc, ActiveFlavor.resolve(TEST_LANE))
        val tbcChip = ActiveFlavor.switcherChips().single { it.flavor.flavorId == "tbc" }
        assertTrue(tbcChip.selected)
        assertTrue(tbcChip.selectable)

        ActiveFlavor.clearSelection()
        // Multi-qualified + no selection: the honest loud state again.
        try {
            ActiveFlavor.resolve(TEST_LANE)
            fail("cleared holder selection must resolve loudly, not to vanilla")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains(TEST_LANE))
        }
    }
}

/** The failure-suffix lane the machine tests resolve through. */
private const val TEST_LANE = "test lane resolves the qualified lane"

/**
 * A synthetic [ClientFlavor] for the machine tests: the full constructor
 * with the identity fields that matter and neutral fill elsewhere.
 */
internal fun testFlavor(
    flavorId: String,
    qualified: Boolean,
    build: Int = 5875,
    botMaxLevel: Int = 60,
    botRandomMaps: List<Int> = listOf(0, 1),
    addonInterfaces: Set<Int> = setOf(11200),
    extractedEstimateBytes: Long? = 4L * 1024L * 1024L * 1024L,
    maxTotalBytes: Long? = 20L * 1024L * 1024L * 1024L,
): ClientFlavor = ClientFlavor(
    flavorId = flavorId,
    serverFamily = flavorId,
    clientId = "wow-test-$flavorId",
    build = build,
    versionResource = "$build.0.0.$build",
    expansion = flavorId,
    qualified = qualified,
    databases = ClientFlavor.DatabaseNames(
        realmd = "${flavorId}realmd",
        world = "${flavorId}mangos",
        characters = "${flavorId}characters",
        logs = "${flavorId}logs",
    ),
    addonInterfaces = addonInterfaces,
    winePrefixSegment = flavorId,
    knownExeSha256 = null,
    requiredMpqs = emptySet(),
    extractedEstimateBytes = extractedEstimateBytes,
    maxTotalBytes = maxTotalBytes,
    botMaxLevel = botMaxLevel,
    botRandomMaps = botRandomMaps,
    realmdAcceptedBuilds = "$build",
    proofResponseBytes = null,
    wtfLayout = "account-level",
    wtfCharacterCacheFiles = emptySet(),
    knownExeMd5 = null,
    knownExeSizeBytes = null,
    largeAddressAware = false,
)
