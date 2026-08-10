package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererPackageCatalogTest {
    @Test fun defaultsAndCompatibilityAreProviderSpecific() {
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.default(ArmTranslationBackend.BOX64).id)
        assertEquals(RendererPackageCatalog.FEX_DEFAULT,
            RendererPackageCatalog.default(ArmTranslationBackend.FEX).id)
        assertEquals(2, RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).size)
        assertEquals(1, RendererPackageCatalog.compatible(ArmTranslationBackend.FEX).size)
    }

    @Test fun invalidAndCrossProviderSelectionsFallBackToCompatibleDefault() {
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.normalize(ArmTranslationBackend.BOX64, "missing"))
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.normalize(ArmTranslationBackend.BOX64,
                RendererPackageCatalog.FEX_DEFAULT))
        assertEquals(RendererPackageCatalog.FEX_DEFAULT,
            RendererPackageCatalog.normalize(ArmTranslationBackend.FEX,
                RendererPackageCatalog.BOX64_LEGACY))
    }

    @Test fun openGlNeverCarriesADxvkPackage() {
        assertNull(RendererPackageCatalog.resolve(
            ArmTranslationBackend.BOX64, "opengl", RendererPackageCatalog.BOX64_DEFAULT))
    }

    @Test fun controlProtocolSelectionFailsClosed() {
        assertEquals(
            RendererPackageCatalog.BOX64_LEGACY,
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_LEGACY,
            )!!.id,
        )
        assertTrueFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.FEX_DEFAULT,
            )
        }
        assertTrueFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.FEX,
                "dxvk",
                "missing",
            )
        }
        assertTrueFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                null,
            )
        }
        assertTrueFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "opengl",
                RendererPackageCatalog.BOX64_DEFAULT,
            )
        }
    }

    @Test fun everySelectableRendererHasAnIsolatedRuntimeGeneration() {
        assertEquals(
            RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.runtimeGeneration(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_DEFAULT,
            ),
        )
        assertEquals(
            RendererPackageCatalog.BOX64_LEGACY,
            RendererPackageCatalog.runtimeGeneration(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_LEGACY,
            ),
        )
        assertEquals(
            "opengl",
            RendererPackageCatalog.runtimeGeneration(
                ArmTranslationBackend.BOX64,
                "opengl",
                null,
            ),
        )
    }

    private fun assertTrueFailure(block: () -> Unit) {
        check(runCatching(block).isFailure) { "expected request to fail closed" }
    }
}
