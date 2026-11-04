package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The WoW Forever scheme: Android Port's key contract with Forever's shoulders and layout. */
class InputProfileWowForeverTest {
    @Test
    fun foreverKeepsTheAndroidPortKeyContractAndMovesLbToTargetSelf() {
        val profile = InputProfile.profileForScheme(ControlScheme.WOW_FOREVER, InputProfile.DEFAULT_ASPECT_IDENTITY)
        val androidPort = InputProfile.androidPortBindings()

        assertEquals(ControlScheme.WOW_FOREVER, profile.scheme)
        Rp6Control.values().filter { it != Rp6Control.L1 }.forEach { control ->
            assertEquals(control.name, androidPort[control], profile.rp6Bindings[control])
        }
        assertEquals(ControllerAction.F1, profile.rp6Bindings[Rp6Control.L1])
        assertEquals(ControllerAction.TARGET_PULSE, profile.rp6Bindings[Rp6Control.R1])
        assertEquals(ControllerAction.SHIFT, profile.rp6Bindings[Rp6Control.L2])
        assertEquals(ControllerAction.CTRL, profile.rp6Bindings[Rp6Control.R2])
    }

    @Test
    fun foreverRoundTripsThroughStorage() {
        val profile = InputProfile.profileForScheme(ControlScheme.WOW_FOREVER, InputProfile.DEFAULT_ASPECT_IDENTITY)

        assertEquals(profile, InputProfile.fromJson(InputProfile.toJson(profile)))
    }

    @Test
    fun onlyTheAddonSchemesPickAnAddonLayout() {
        assertEquals("forever", InputProfile.addonControlLayout(ControlScheme.WOW_FOREVER))
        assertEquals("star", InputProfile.addonControlLayout(ControlScheme.ANDROID_PORT))
        assertNull(InputProfile.addonControlLayout(ControlScheme.CLASSIC_CAMERA))
        assertNull(InputProfile.addonControlLayout(ControlScheme.CUSTOM))
    }
}
