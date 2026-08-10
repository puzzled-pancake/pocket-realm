package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRuntimeProviderTest {
    @Test
    fun x86IsSelectedBeforeAnyArmCompatibilityAbi() {
        val selected = ClientRuntimeSelector.selectForAbis(listOf("x86_64", "arm64-v8a"))
        assertEquals(ClientRuntimeProvider.X86_DIRECT_WINE, selected.provider)
        assertTrue(selected.supported)
    }

    @Test
    fun arm64NeverFallsBackToX86WhenTranslatedProviderIsMissing() {
        val selected = ClientRuntimeSelector.selectForAbis(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(ClientRuntimeProvider.ARM_TRANSLATED_WINE, selected.provider)
        assertFalse(selected.supported)
        assertTrue(selected.reason.contains("not packaged"))
    }

    @Test
    fun arm64CanOnlyBecomeSelectableAfterExplicitProviderMarker() {
        val selected = ClientRuntimeSelector.selectForAbis(
            listOf("arm64-v8a"),
            armTranslatedWineAvailable = true,
        )
        assertEquals(ClientRuntimeProvider.ARM_TRANSLATED_WINE, selected.provider)
        assertTrue(selected.supported)
    }

    @Test
    fun unsupportedAbiFailsClosed() {
        val selected = ClientRuntimeSelector.selectForAbis(listOf("armeabi-v7a"))
        assertFalse(selected.supported)
        assertTrue(selected.reason.contains("no supported"))
    }

    @Test
    fun armTranslatorSelectionIsExplicitAndUnknownValuesFailClosed() {
        assertEquals(ArmTranslationBackend.BOX64, ArmTranslationBackend.parse("box64"))
        assertEquals(ArmTranslationBackend.FEX, ArmTranslationBackend.parse("fex"))
        assertTrue(runCatching { ArmTranslationBackend.parse("unknown") }.isFailure)
        assertTrue(runCatching { ArmTranslationBackend.parse(null) }.isFailure)
        assertEquals(
            "winlator-ca3d735-box64-0.4.0-wine-10.10",
            ClientRuntimeContract.armRuntimeBuildId(ArmTranslationBackend.BOX64),
        )
        assertEquals(
            "winlator-bionic-v3.1.h-fexcore-2608-proton-9-arm64ec",
            ClientRuntimeContract.armRuntimeBuildId(ArmTranslationBackend.FEX),
        )
        assertEquals(
            "turnip-26.1.0-dxvk-2.4.1-d3d9",
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_DEFAULT,
            ),
        )
        assertEquals(
            "turnip-26.1.0-dxvk-1.10.3-d3d9",
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_LEGACY,
            ),
        )
        assertEquals(
            "gladio-eaa2a8d-arm64-glibc-android-gles",
            ClientRuntimeContract.armRendererBuildId(ArmTranslationBackend.BOX64, "opengl"),
        )
        assertEquals(
            "turnip-26.2.0-dxvk-2.3.1-arm64ec",
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.FEX,
                "dxvk",
                RendererPackageCatalog.FEX_DEFAULT,
            ),
        )
        assertEquals(
            "gladio-eaa2a8d-arm64-bionic-378e5bb9-android-gles",
            ClientRuntimeContract.armRendererBuildId(ArmTranslationBackend.FEX, "opengl"),
        )
        assertEquals(
            listOf("C:/WoW/WoW.exe"),
            ClientRuntimeContract.armClientArguments("C:/WoW/WoW.exe", "dxvk"),
        )
        assertEquals(
            listOf("C:/WoW/WoW.exe", "-opengl"),
            ClientRuntimeContract.armClientArguments("C:/WoW/WoW.exe", "opengl"),
        )
        assertEquals(
            listOf(
                "explorer",
                "/desktop=shell,1920x1080",
                "Z:\\data\\user\\0\\com.pocketrealm\\client\\WoW.exe",
                "-opengl",
            ),
            ClientRuntimeContract.armFexClientArguments(
                "/data/user/0/com.pocketrealm/client/WoW.exe",
                "opengl",
                "1920x1080",
            ),
        )
    }
}
