package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererPackageCatalogTest {
    @Test fun catalogContainsOnlyPinnedBox64DxvkPackages() {
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.default(ArmTranslationBackend.BOX64).id)
        assertEquals(2, RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).size)
        assertEquals(
            vulkanVersion(1, 3),
            RendererPackageCatalog.find(RendererPackageCatalog.BOX64_DEFAULT)!!
                .minimumSystemVulkanApi,
        )
        assertEquals(
            vulkanVersion(1, 1),
            RendererPackageCatalog.find(RendererPackageCatalog.BOX64_LEGACY)!!
                .minimumSystemVulkanApi,
        )
    }

    @Test fun persistedInvalidSelectionMigratesToPinnedDefault() {
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.normalize(ArmTranslationBackend.BOX64, "missing"))
        assertEquals(RendererPackageCatalog.BOX64_DEFAULT,
            RendererPackageCatalog.normalize(
                ArmTranslationBackend.BOX64,
                "fex-dxvk-2.3.1-arm64ec",
            ))
    }

    @Test fun controlProtocolRequiresExactPinnedPackageIdentity() {
        assertEquals(
            RendererPackageCatalog.BOX64_LEGACY,
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_LEGACY,
            ).id,
        )
        assertFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                "missing",
            )
        }
        assertFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "dxvk",
                null,
            )
        }
        assertFailure {
            RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64,
                "opengl",
                null,
            )
        }
    }

    @Test fun eachDxvkPackageHasAnIsolatedRuntimeGeneration() {
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
    }

    private fun assertFailure(block: () -> Unit) {
        check(runCatching(block).isFailure) { "expected request to fail closed" }
    }
}
