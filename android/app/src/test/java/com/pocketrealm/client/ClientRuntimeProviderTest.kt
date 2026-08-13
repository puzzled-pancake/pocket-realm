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
    fun audioPreferenceIsAppliedOnlyByThePackagedArmProvider() {
        assertEquals(
            "on",
            ClientAudioPolicy.effectiveMode(ClientRuntimeProvider.ARM_TRANSLATED_WINE, "on"),
        )
        assertEquals(
            "off",
            ClientAudioPolicy.effectiveMode(ClientRuntimeProvider.X86_DIRECT_WINE, "on"),
        )
        assertEquals(
            "off",
            ClientAudioPolicy.effectiveMode(ClientRuntimeProvider.ARM_TRANSLATED_WINE, "off"),
        )
        assertTrue(runCatching {
            ClientAudioPolicy.effectiveMode(ClientRuntimeProvider.ARM_TRANSLATED_WINE, "auto")
        }.isFailure)
    }

    @Test
    fun armRouteIsExactlyBox64AndDxvk() {
        assertEquals(ArmTranslationBackend.BOX64, ArmTranslationBackend.parse("box64"))
        assertTrue(runCatching { ArmTranslationBackend.parse("fex") }.isFailure)
        assertTrue(runCatching { ArmTranslationBackend.parse("unknown") }.isFailure)
        assertTrue(runCatching { ArmTranslationBackend.parse(null) }.isFailure)
        assertEquals(
            "winlator-ca3d735-box64-0.4.0-wine-10.10",
            ClientRuntimeContract.armRuntimeBuildId(ArmTranslationBackend.BOX64),
        )
        assertEquals(
            "system-vulkan-vortek-2.1-dxvk-2.4.1-d3d9",
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_DEFAULT,
                VulkanDriverCatalog.SYSTEM_DEFAULT,
            ),
        )
        assertEquals(
            "turnip-26.1.0-dxvk-1.10.3-d3d9",
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64,
                "dxvk",
                RendererPackageCatalog.BOX64_LEGACY,
                VulkanDriverCatalog.TURNIP_26_1,
            ),
        )
        assertTrue(runCatching {
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64,
                "opengl",
                null,
                null,
            )
        }.isFailure)
        assertEquals(
            listOf("C:/WoW/WoW.exe"),
            ClientRuntimeContract.armClientArguments("C:/WoW/WoW.exe", "dxvk"),
        )
        assertTrue(runCatching {
            ClientRuntimeContract.armClientArguments("C:/WoW/WoW.exe", "opengl")
        }.isFailure)
        assertEquals(
            "d3d9=n,b;dxgi=n,b;winealsa.drv=d;winepulse.drv=d",
            ClientRuntimeContract.armWineDllOverrides(audioOn = false),
        )
        assertEquals(
            "d3d9=n,b;dxgi=n,b;winepulse.drv=d",
            ClientRuntimeContract.armWineDllOverrides(audioOn = true),
        )
        assertTrue(ClientRuntimeContract.isArmDxvkLogAttested(
            "info: Game: WoW.exe\ninfo: DXVK: v2.4.1\ninfo: Vortek (Adreno 740)",
            "2.4.1",
            VulkanDriverCatalog.requireForRequest(VulkanDriverCatalog.SYSTEM_DEFAULT),
        ))
        assertTrue(ClientRuntimeContract.isArmDxvkLogAttested(
            "info: Game: WoW.exe\ninfo: DXVK: v1.10.3\ninfo: Turnip Adreno (TM) 740",
            "1.10.3",
            VulkanDriverCatalog.requireForRequest(VulkanDriverCatalog.TURNIP_26_1),
        ))
        assertFalse(ClientRuntimeContract.isArmDxvkLogAttested(
            "info: Game: WoW.exe\ninfo: DXVK: v1.10.3\ninfo: Turnip Adreno (TM) 740",
            "2.4.1",
            VulkanDriverCatalog.requireForRequest(VulkanDriverCatalog.TURNIP_26_1),
        ))
        assertFalse(ClientRuntimeContract.isArmDxvkLogAttested(
            "WineD3D mapped a window",
            "2.4.1",
            VulkanDriverCatalog.requireForRequest(VulkanDriverCatalog.SYSTEM_DEFAULT),
        ))
        assertEquals(
            "# Pocket Realm generated; applied before DXVK creates the D3D9 device.\n" +
                "d3d9.maxFrameRate = 30\n" +
                "dxgi.maxFrameRate = 30\n",
            ClientRuntimeContract.dxvkFrameCapConfig(30),
        )
        assertTrue(runCatching { ClientRuntimeContract.dxvkFrameCapConfig(31) }.isFailure)
        assertTrue("opengl32.dll" in ClientRuntimeContract.ARM_REQUIRED_WINE_GUEST_DLLS)
    }
}
