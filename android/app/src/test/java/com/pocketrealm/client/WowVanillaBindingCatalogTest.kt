package com.pocketrealm.client

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WowVanillaBindingCatalogTest {
    private fun idOrderSha256(bindings: List<WowBindingDefinition>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bindings.joinToString("\n", transform = WowBindingDefinition::id).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun manifestHasEverySupportedActiveIdExactlyOnce() {
        assertEquals(211, WowVanillaBindingCatalog.userFacing.size)
        assertEquals(3, WowVanillaBindingCatalog.internalPointer.size)
        assertEquals(214, WowVanillaBindingCatalog.allSupported.size)
        assertEquals(
            WowVanillaBindingCatalog.allSupported.size,
            WowVanillaBindingCatalog.allSupported.map(WowBindingDefinition::id).distinct().size,
        )
        assertEquals(
            WowVanillaBindingCatalog.PUBLIC_ID_ORDER_SHA256,
            idOrderSha256(WowVanillaBindingCatalog.userFacing),
        )
        assertEquals(
            WowVanillaBindingCatalog.SUPPORTED_ID_ORDER_SHA256,
            idOrderSha256(WowVanillaBindingCatalog.allSupported),
        )
        WowVanillaBindingCatalog.allSupported.forEach { binding ->
            assertTrue(binding.id.isNotBlank())
            assertTrue(binding.label.isNotBlank())
            assertTrue(binding.description.isNotBlank())
            assertEquals(binding, WowVanillaBindingCatalog.find(binding.id))
        }
    }

    @Test
    fun categoryCountsMatchStockSourceAndCatalogSplits() {
        val expected = mapOf(
            WowBindingCategory.MOVEMENT to 15,
            WowBindingCategory.CHAT to 11,
            WowBindingCategory.MAIN_ACTION_BAR to 12,
            WowBindingCategory.SELF_ACTION_BAR to 12,
            WowBindingCategory.SHAPESHIFT_BAR to 10,
            WowBindingCategory.BONUS_ACTION_BAR to 10,
            WowBindingCategory.ACTION_PAGES to 10,
            WowBindingCategory.TARGETING to 21,
            WowBindingCategory.CHARACTER_AND_UI to 19,
            WowBindingCategory.BAGS to 7,
            WowBindingCategory.MISCELLANEOUS to 9,
            WowBindingCategory.CAMERA to 18,
            WowBindingCategory.MULTI_ACTION_BARS to 48,
            WowBindingCategory.RAID_MARKERS to 9,
        )
        assertEquals(expected.keys, WowBindingCategory.entries.toSet() - WowBindingCategory.INTERNAL_POINTER)
        expected.forEach { (category, count) ->
            assertEquals(category.name, count, WowVanillaBindingCatalog.inCategory(category).size)
        }
    }

    @Test
    fun allNumberedStockNamespacesAreComplete() {
        fun assertRange(prefix: String, range: IntRange) {
            val expected = range.map { "$prefix$it" }
            val actual = WowVanillaBindingCatalog.userFacing
                .map(WowBindingDefinition::id)
                .filter { it.startsWith(prefix) }
            assertEquals(prefix, expected, actual)
        }

        assertRange("ACTIONBUTTON", 1..12)
        assertRange("SELFACTIONBUTTON", 1..12)
        assertRange("SHAPESHIFTBUTTON", 1..10)
        assertRange("BONUSACTIONBUTTON", 1..10)
        (1..4).forEach { bar -> assertRange("MULTIACTIONBAR${bar}BUTTON", 1..12) }
        assertEquals(
            48,
            WowVanillaBindingCatalog.userFacing.count { it.id.startsWith("MULTIACTIONBAR") },
        )
    }

    @Test
    fun unsupportedCommentedDebugAndPlatformIdsStayAbsent() {
        val unsupported = buildList {
            add("INTERACTTARGET")
            add("LOOTALL")
            addAll(listOf("MOVEVIEWIN", "MOVEVIEWOUT", "MOVEVIEWLEFT", "MOVEVIEWRIGHT", "MOVEVIEWUP", "MOVEVIEWDOWN"))
            addAll(listOf("TOGGLESTATS", "TOGGLETRIS", "TOGGLEPORTALS", "TOGGLECOLLISION",
                "TOGGLECOLLISIONDISPLAY", "TOGGLEPLAYERBOUNDS", "TOGGLEPERFORMANCEDISPLAY",
                "TOGGLEPERFORMANCEVALUES", "RESETPERFORMANCEVALUES"))
            addAll(listOf("ITUNES_PLAYPAUSE", "ITUNES_NEXTTRACK", "ITUNES_BACKTRACK",
                "ITUNES_VOLUMEUP", "ITUNES_VOLUMEDOWN"))
        }
        unsupported.forEach { id ->
            assertEquals(id, null, WowVanillaBindingCatalog.find(id))
        }
    }

    @Test
    fun hiddenPointerBindingsAreInternalOnly() {
        val hiddenIds = setOf("TURNORACTION", "CAMERAORSELECTORMOVE", "CAMERAORSELECTORMOVESTICKY")
        assertEquals(hiddenIds, WowVanillaBindingCatalog.internalPointer.map { it.id }.toSet())
        assertFalse(WowVanillaBindingCatalog.userFacing.any { it.id in hiddenIds })
        WowVanillaBindingCatalog.internalPointer.forEach { binding ->
            assertEquals(WowBindingCategory.INTERNAL_POINTER, binding.category)
            assertEquals(WowBindingSemantics.INTERNAL_POINTER_HOLD, binding.semantics)
        }
    }

    @Test
    fun orderAndSearchAreDeterministicAndFilterable() {
        assertEquals("MOVEANDSTEER", WowVanillaBindingCatalog.userFacing.first().id)
        assertEquals("RAIDTARGETNONE", WowVanillaBindingCatalog.userFacing.last().id)
        assertEquals(
            listOf("MINIMAPZOOMIN", "MINIMAPZOOMOUT", "CAMERAZOOMIN", "CAMERAZOOMOUT"),
            WowVanillaBindingCatalog.search("zoom").map { it.id },
        )
        val basicTargets = WowVanillaBindingCatalog.search(
            query = "target",
            categories = setOf(WowBindingCategory.TARGETING),
            includeAdvanced = false,
        )
        assertTrue(basicTargets.isNotEmpty())
        assertTrue(basicTargets.all { !it.advanced && it.category == WowBindingCategory.TARGETING })
        assertFalse(WowVanillaBindingCatalog.search("TURNORACTION").isNotEmpty())
        assertNotNull(
            WowVanillaBindingCatalog.search("TURNORACTION", includeInternal = true).singleOrNull(),
        )
    }

    @Test
    fun basicRecommendationIsAStablePublicSubset() {
        assertTrue(WowVanillaBindingCatalog.basicRecommended.isNotEmpty())
        assertTrue(WowVanillaBindingCatalog.basicRecommended.all { !it.advanced })
        assertTrue(WowVanillaBindingCatalog.basicRecommended.all {
            it in WowVanillaBindingCatalog.userFacing
        })
        assertTrue(WowVanillaBindingCatalog.basicRecommended.any { it.id == "TARGETNEARESTENEMY" })
        assertTrue(WowVanillaBindingCatalog.basicRecommended.any { it.id == "CAMERAZOOMIN" })
        assertFalse(WowVanillaBindingCatalog.basicRecommended.any { it.id == "RAIDTARGET1" })
    }
}
