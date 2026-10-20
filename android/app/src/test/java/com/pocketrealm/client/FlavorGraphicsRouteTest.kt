package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3.6 (D15(4) retirement): the graphics_routes / vanilla_tweaks manifest
 * fields have their Kotlin projection — the registry carries the manifest
 * values, the prepare gate refuses a non-flavor route with the honest
 * copy, and the tweaks/GL surfaces have their per-flavor copy. The vanilla
 * lane is unaffected (all three routes + tweaks stay available).
 */
class FlavorGraphicsRouteTest {

    @Test fun `vanilla keeps all three routes and tweaks`() {
        assertEquals(setOf("dxvk", "opengl", "virgl"), ClientFlavors.VANILLA.graphicsRoutes)
        assertTrue(ClientFlavors.VANILLA.vanillaTweaksEnabled)
    }

    @Test fun `tbc and wotlk ship dxvk only with tweaks off`() {
        // Decision: the GL lanes are validated against vanilla's GL
        // usage only; the later flavors are DXVK (d3d9) routes.
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            assertEquals(setOf("dxvk"), flavor.graphicsRoutes)
            assertTrue(!flavor.vanillaTweaksEnabled)
        }
    }

    @Test fun `the gate refuses the gl routes on the new flavors with the d3 copy`() {
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            // DXVK passes every flavor's gate.
            ClientRuntimeContract.requireFlavorGraphicsRoute(flavor, "dxvk")
            for (glRoute in listOf("opengl", "virgl")) {
                val refusal = assertThrows(IllegalStateException::class.java) {
                    ClientRuntimeContract.requireFlavorGraphicsRoute(flavor, glRoute)
                }
                val message = refusal.message!!
                assertTrue("the refusal must name the route: $message", glRoute in message)
                assertTrue("the refusal must name the flavor: $message", flavor.flavorId in message)
                assertTrue(
                    "the refusal must name decision D3: $message",
                    "decision D3" in message,
                )
                assertTrue(
                    "the refusal must name the DXVK remedy: $message",
                    "DXVK route" in message,
                )
                // And the surface copy names the same fact honestly.
                assertNotNull(
                    ClientRuntimeContract.flavorGraphicsRouteCopy(flavor, glRoute),
                )
            }
        }
    }

    @Test fun `the vanilla gate is unaffected`() {
        for (route in listOf("dxvk", "opengl", "virgl")) {
            ClientRuntimeContract.requireFlavorGraphicsRoute(ClientFlavors.VANILLA, route)
            assertNull(ClientRuntimeContract.flavorGraphicsRouteCopy(ClientFlavors.VANILLA, route))
        }
    }

    @Test fun `the tweaks copy is honest on the new flavors and silent for vanilla`() {
        assertNull(ClientRuntimeContract.flavorTweaksCopy(ClientFlavors.VANILLA))
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            val copy = ClientRuntimeContract.flavorTweaksCopy(flavor)
            assertNotNull(copy)
            assertTrue("the copy must name the flavor: $copy", flavor.expansion in copy!!)
            assertTrue(
                "the copy must say not available: $copy",
                "not available" in copy,
            )
        }
    }
}
