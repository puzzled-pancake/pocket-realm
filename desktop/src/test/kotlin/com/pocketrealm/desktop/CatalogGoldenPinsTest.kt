package com.pocketrealm.desktop

import com.pocketrealm.client.WowVanillaBindingCatalog
import com.pocketrealm.ingame.WowSettingSection
import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESK-86/DESK-96: golden pins over the shared stock catalogs as compiled
 * into the desktop. The catalogs carry their own integrity hashes; these
 * legs recompute them from the live data, so any silent catalog drift (a
 * new binding id, a re-ordered row, an edited stock default) fails the
 * desktop build instead of shipping.
 */
class CatalogGoldenPinsTest {

    private fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun bindingCatalogPinsTwoHundredElevenPublicIdsPlusThreePointer() {
        assertEquals(
            "the stock 1.12.1 public binding IDs",
            211,
            WowVanillaBindingCatalog.userFacing.size,
        )
        assertEquals(3, WowVanillaBindingCatalog.internalPointer.size)
        assertEquals(
            "allSupported = public + the three internal pointer gestures",
            WowVanillaBindingCatalog.userFacing.size + WowVanillaBindingCatalog.internalPointer.size,
            WowVanillaBindingCatalog.allSupported.size,
        )
    }

    @Test
    fun bindingCatalogOrderHashesMatchThePinnedManifest() {
        val publicIds = WowVanillaBindingCatalog.userFacing.joinToString("\n") { it.id }
        assertEquals(
            "public id-order pin",
            WowVanillaBindingCatalog.PUBLIC_ID_ORDER_SHA256,
            sha256Hex(publicIds),
        )
        val supportedIds = WowVanillaBindingCatalog.allSupported.joinToString("\n") { it.id }
        assertEquals(
            "supported id-order pin (public + hidden pointer)",
            WowVanillaBindingCatalog.SUPPORTED_ID_ORDER_SHA256,
            sha256Hex(supportedIds),
        )
    }

    @Test
    fun bindingCatalogStockDefaultsArePinnedByteForByte() {
        assertEquals(
            WowVanillaBindingCatalog.DEFAULTS_COUNT,
            WowVanillaBindingCatalog.stockKeyBindings.size,
        )
        val rendered = WowVanillaBindingCatalog.stockKeyBindings.entries
            .joinToString("\n") { (command, keys) -> command + "=" + keys.slots.joinToString(",") }
        assertEquals(
            "stock default keys capture pin (COMMAND=KEY1,KEY2 lines, declaration order)",
            WowVanillaBindingCatalog.DEFAULTS_CAPTURE_SHA256,
            sha256Hex(rendered),
        )
        // Every stock default must name a catalog command — a typo'd id
        // would bind nothing in the client.
        val known = WowVanillaBindingCatalog.allSupported.map { it.id }.toSet()
        WowVanillaBindingCatalog.stockKeyBindings.forEach { (command, _) ->
            assertTrue("stock default for unknown command $command", command in known)
        }
    }

    @Test
    fun settingsCatalogPinsItsDefinitions() {
        assertEquals(
            WowVanillaSettingsCatalog.SETTING_COUNT,
            WowVanillaSettingsCatalog.all.size,
        )
        assertEquals(
            "settings id-order pin",
            WowVanillaSettingsCatalog.ID_ORDER_SHA256,
            sha256Hex(WowVanillaSettingsCatalog.all.joinToString("\n") { it.id }),
        )
    }

    @Test
    fun settingsCatalogSectionScopeMatchesTheStockPanels() {
        val graphics = WowVanillaSettingsCatalog.forSection(WowSettingSection.GRAPHICS).size
        val sound = WowVanillaSettingsCatalog.forSection(WowSettingSection.SOUND).size
        val interfaceRows = WowVanillaSettingsCatalog.forSection(WowSettingSection.INTERFACE).size
        val advanced = WowVanillaSettingsCatalog.forSection(WowSettingSection.INTERFACE_ADVANCED).size
        assertEquals(24, graphics)
        assertEquals(11, sound)
        assertEquals(37, interfaceRows)
        assertEquals(37, advanced)
        assertEquals(WowVanillaSettingsCatalog.all.size, graphics + sound + interfaceRows + advanced)
    }
}
