package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DESK-54: the validator's accepted-interface seam routes through
 * [ClientFlavor.addonInterfaces] — never a hard-coded 11200 inside the
 * validator — so qualifying a new flavor is a data change in the flavor
 * registry, not a silent accept/reject skew here. The vanilla literals stay
 * pinned by the shared VanillaAddonPinTest; this pins the ROUTING.
 */
class DesktopAddonValidatorFlavorSeamTest {

    @Test
    fun `vanilla policy interfaces are the qualified flavor registry values`() {
        assertEquals(
            ClientFlavors.VANILLA.addonInterfaces,
            AddonArchiveValidator.Policy.VANILLA.acceptedInterfaces,
        )
        assertEquals(
            ClientFlavors.VANILLA.addonInterfaces,
            AddonArchiveValidator.Policy.VOICEOVER_PLAYER.acceptedInterfaces,
        )
        assertEquals(
            setOf(11200),
            ClientFlavors.VANILLA.addonInterfaces,
        )
    }

    @Test
    fun `voiceover data policy adds only the data module marker interface`() {
        assertEquals(
            ClientFlavors.VANILLA.addonInterfaces +
                AddonArchiveValidator.Policy.VOICEOVER_DATA_MODULE_INTERFACE,
            AddonArchiveValidator.Policy.VOICEOVER_DATA.acceptedInterfaces,
        )
        assertEquals(setOf(11200, 100000), AddonArchiveValidator.Policy.VOICEOVER_DATA.acceptedInterfaces)
    }

    @Test
    fun `a policy built without an explicit set inherits the flavor registry`() {
        val inherited = AddonArchiveValidator.Policy(expectedFolders = setOf("Something"))
        assertEquals(ClientFlavors.VANILLA.addonInterfaces, inherited.acceptedInterfaces)
    }
}
