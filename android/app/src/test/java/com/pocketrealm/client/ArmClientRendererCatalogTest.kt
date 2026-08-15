package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmClientRendererCatalogTest {
    private val capable = GladioCapability(
        declaredGlesVersion = 0x00030002,
        eglMajor = 1,
        eglMinor = 5,
        actualGlesMajor = 3,
        actualGlesMinor = 2,
        maxVertexUniformVectors = 256,
        sharedSurfacelessContext = true,
    )

    @Test
    fun defaultAndLegacyPreferencesResolveToAuto() {
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted(null, 0))
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted("OPENGL", 0))
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted("legacy-gladio", 0))
    }

    @Test
    fun completedSchemaActivatesOnlyExactKnownSelection() {
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted("legacy-gladio", 1))
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted("mesa-virgl", 1))
        assertEquals("legacy-gladio",
            ArmClientRendererCatalog.resolvePersisted(
                "legacy-gladio", ArmClientRendererCatalog.SELECTION_SCHEMA,
            ))
        assertEquals("mesa-virgl",
            ArmClientRendererCatalog.resolvePersisted(
                "mesa-virgl", ArmClientRendererCatalog.SELECTION_SCHEMA,
            ))
        assertEquals(ArmClientRendererCatalog.AUTO_ID,
            ArmClientRendererCatalog.resolvePersisted(
                "unknown", ArmClientRendererCatalog.SELECTION_SCHEMA,
            ))
    }

    @Test
    fun resolveVulkanDriverIdFailsClosedForUnknownManualIds() {
        // Host JVM has no ro.hardware.egl, so the detected vendor decides the
        // auto default; both branches must resolve, never fail open.
        val vendorDefault = VulkanDriverCatalog.autoDriverId(ArmRendererAuto.isAdrenoGpu())
        assertEquals(vendorDefault, ArmRendererAuto.resolveVulkanDriverId("auto"))
        assertEquals(vendorDefault, ArmRendererAuto.resolveVulkanDriverId(null))
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            ArmRendererAuto.resolveVulkanDriverId(VulkanDriverCatalog.TURNIP_26_1),
        )
        assertEquals(null, ArmRendererAuto.resolveVulkanDriverId("future-driver"))
    }

    @Test
    fun gladioStaysSelectableForOnDeviceTesting() {
        // The capability probe is informational: weak or failing probes must
        // not hide the experimental lane from device qualification.
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO, Result.success(capable),
        ).available)
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO,
            Result.success(capable.copy(maxVertexUniformVectors = 255)),
        ).available)
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO,
            Result.success(capable.copy(sharedSurfacelessContext = false)),
        ).available)
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO,
            Result.success(capable.copy(actualGlesMajor = 2, actualGlesMinor = 0)),
        ).available)
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO, null,
        ).available)
        val failed = ArmClientRendererCatalog.availability(
            ArmClientRenderer.LEGACY_GLADIO,
            Result.failure(IllegalStateException("EGL capability probe timed out on this device")),
        )
        assertTrue(failed.available)
        assertTrue(failed.reason.contains("not verified"))
        assertEquals(
            ArmClientRenderer.LEGACY_GLADIO,
            ArmClientRendererCatalog.requireRuntimeRenderer(
                "opengl", Result.failure(IllegalStateException("probe timed out"))),
        )
    }

    @Test
    fun virglStaysSelectableForOnDeviceTesting() {
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.MESA_VIRGL, Result.success(capable),
        ).available)
        assertEquals(
            ArmClientRenderer.MESA_VIRGL,
            ArmClientRendererCatalog.requireRuntimeRenderer("virgl", Result.success(capable)),
        )
        assertTrue(ArmClientRendererCatalog.availability(
            ArmClientRenderer.MESA_VIRGL,
            Result.success(capable.copy(sharedSurfacelessContext = false)),
        ).available)
    }

    @Test
    fun experimentalArmRenderersAreUnavailableOnX86() {
        for (renderer in listOf(
            ArmClientRenderer.LEGACY_GLADIO,
            ArmClientRenderer.MESA_VIRGL,
        )) {
            val availability = ArmClientRendererCatalog.availability(
                renderer, Result.success(capable), "x86_64",
            )
            assertFalse(availability.available)
            assertTrue(availability.reason.contains("ARM64"))
            assertTrue(runCatching {
                ArmClientRendererCatalog.requireRuntimeRenderer(
                    renderer.runtimeRenderer, Result.success(capable), "x86_64",
                )
            }.isFailure)
        }
    }

    @Test
    fun glesVersionParsingRejectsNonGlesStrings() {
        assertEquals(3 to 2,
            AndroidGladioCapabilityProbe.parseGlesVersion("OpenGL ES 3.2 vendor"))
        assertTrue(runCatching {
            AndroidGladioCapabilityProbe.parseGlesVersion("OpenGL 4.6")
        }.isFailure)
    }
}
