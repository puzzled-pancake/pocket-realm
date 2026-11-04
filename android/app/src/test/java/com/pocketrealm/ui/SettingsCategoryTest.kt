package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsCategoryTest {
    @Test fun everyCategoryRoundTripsThroughItsRoute() {
        SettingsCategory.entries.forEach { category ->
            val route = SettingsRoutes.category(category)
            assertEquals("settings/${category.slug}", route)
            assertEquals(category, SettingsCategory.fromSlug(route.removePrefix("settings/")))
        }
    }

    @Test fun unknownSlugsFallBackToTheHub() {
        assertNull(SettingsCategory.fromSlug(null))
        assertNull(SettingsCategory.fromSlug("llm"))
    }

    @Test fun categoryPagesAreTitledByTheirCategory() {
        assertEquals("Graphics & display", screenTitle(SettingsRoutes.CATEGORY, "graphics"))
        assertEquals("Settings", screenTitle(SettingsRoutes.CATEGORY, "nope"))
        assertEquals("Settings", screenTitle("settings"))
    }
}
