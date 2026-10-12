package com.pocketrealm.ingame

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3.3: the flavor-routed catalog resolver — each flavor's authored catalog
 * answers its own lookups, the vanilla catalog answers only the vanilla
 * lane, and an unknown flavor id fails loud. (The editors route through
 * this resolver; the source pins below keep the vanilla references out of
 * the editors so the routing cannot silently fork again.)
 */
class WowFlavorCatalogsTest {

    @Test fun `each flavor resolves its own settings catalog`() {
        // The vanilla catalog keeps its own definitions...
        val vanilla = WowFlavorCatalogs.settingsById(ClientFlavors.VANILLA)
        assertEquals(
            WowVanillaSettingsCatalog.byId("graphics.resolution"),
            vanilla("graphics.resolution"),
        )
        // ...the TBC catalog answers TBC lookups (its authored surface)...
        val tbc = WowFlavorCatalogs.settingsById(ClientFlavors.TBC)
        assertNotNull(tbc("graphics.gxResolution"))
        // ...and the catalogs are genuinely distinct surfaces: the vanilla
        // UVAR family (only the vanilla catalog defines uvars) resolves
        // null on the tbc/wotlk lanes.
        val uvarId = "interface.instantQuestText"
        if (WowVanillaSettingsCatalog.byId(uvarId)?.backend == WowSettingBackend.UVAR) {
            assertNull("the tbc catalog must not carry the vanilla uvar family", tbc(uvarId))
            assertNull(
                "the wotlk catalog must not carry the vanilla uvar family",
                WowFlavorCatalogs.settingsById(ClientFlavors.WOTLK)(uvarId),
            )
        }
        // The wotlk catalog answers its own lookups.
        val wotlk = WowFlavorCatalogs.settingsById(ClientFlavors.WOTLK)
        assertNotNull(wotlk("graphics.gxResolution"))
    }

    @Test fun `each flavor resolves its own binding catalog`() {
        // The stock movement binds exist in every era's catalog...
        for (flavor in ClientFlavors.ALL) {
            val find = WowFlavorCatalogs.bindingFind(flavor)
            assertNotNull("${flavor.flavorId} binding catalog lost MOVEBACKWARD", find("MOVEBACKWARD"))
            assertNotNull("${flavor.flavorId} binding catalog lost TURNORACTION", find("TURNORACTION"))
        }
    }

    @Test fun `unknown flavor ids fail loud never a silent vanilla pick`() {
        val bogus = ClientFlavors.VANILLA.copy(flavorId = "wotlk-classic-demo")
        val settingsRefusal = assertThrows(IllegalStateException::class.java) {
            WowFlavorCatalogs.settingsById(bogus)
        }
        assertTrue(
            "the refusal must name the flavor: ${settingsRefusal.message}",
            "wotlk-classic-demo" in settingsRefusal.message!!,
        )
        val bindingRefusal = assertThrows(IllegalStateException::class.java) {
            WowFlavorCatalogs.bindingFind(bogus)
        }
        assertTrue("wotlk-classic-demo" in bindingRefusal.message!!)
    }

    @Test fun `the editors route through the resolver - no vanilla references remain`() {
        // Structural pin: the two editors resolve catalogs ONLY through
        // WowFlavorCatalogs (a re-introduced per-callsite vanilla reference
        // would silently fork the flavor routing again).
        for (editor in listOf(
            "../../android/app/src/main/java/com/pocketrealm/ingame/InGameSettingsEditor.kt",
            "../../desktop/src/main/kotlin/com/pocketrealm/ingame/DesktopInGameSettingsEditor.kt",
        )) {
            val text = java.io.File(editor).readText()
            assertTrue(
                "$editor: no WowFlavorCatalogs routing found",
                "WowFlavorCatalogs" in text,
            )
            for (banned in listOf(
                "WowVanillaSettingsCatalog.byId",
                "WowVanillaBindingCatalog.find",
                "WowVanillaBindingCatalog.search",
            )) {
                assertTrue(
                    "$editor: the vanilla catalog reference $banned re-appeared — " +
                        "route through WowFlavorCatalogs (W3.3)",
                    banned !in text,
                )
            }
        }
    }
}
