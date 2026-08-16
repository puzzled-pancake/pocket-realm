package com.pocketrealm.ui

import com.pocketrealm.ingame.InGameSettingsEditor
import com.pocketrealm.ingame.InGameSettingsFiles
import com.pocketrealm.ingame.WowSettingSection
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class InGameSettingsContractTest {

    @Test
    fun `six nested-graph destinations have titles`() {
        listOf(
            InGameRoutes.HUB,
            InGameRoutes.GRAPHICS,
            InGameRoutes.SOUND,
            InGameRoutes.INTERFACE,
            InGameRoutes.INTERFACE_ADVANCED,
            InGameRoutes.BINDINGS,
        ).forEach { route ->
            assertTrue(InGameRoutes.titleFor(route).isNotBlank())
        }
        assertEquals("In-Game Settings", InGameRoutes.titleFor(InGameRoutes.HUB))
        assertEquals("Key Bindings", InGameRoutes.titleFor(InGameRoutes.BINDINGS))
    }

    @Test
    fun `every hub row route is one of the registered destinations`() {
        WowSettingSection.entries.forEach { section ->
            assertTrue(InGameRoutes.routeFor(section).startsWith("settings/ingame/"))
        }
    }

    @Test
    fun `status line maps the four client activities`() {
        assertEquals(
            "Changes apply immediately",
            InGameSettingsPresenter.statusLine(InGameSettingsEditor.ClientActivity.STOPPED),
        )
        assertEquals(
            "Client running",
            InGameSettingsPresenter.statusLine(InGameSettingsEditor.ClientActivity.RUNNING),
        )
        assertEquals(
            "Launching",
            InGameSettingsPresenter.statusLine(InGameSettingsEditor.ClientActivity.LAUNCHING),
        )
        assertEquals(
            "Checking client state…",
            InGameSettingsPresenter.statusLine(InGameSettingsEditor.ClientActivity.UNKNOWN),
        )
    }

    @Test
    fun `queued and blocked lines render only when nonzero`() {
        assertEquals(
            "1 queued change applies next launch",
            InGameSettingsPresenter.queuedLine(1),
        )
        assertEquals(
            "3 queued changes apply next launch",
            InGameSettingsPresenter.queuedLine(3),
        )
        assertNull(InGameSettingsPresenter.queuedLine(0))
        assertEquals("2 blocked (audio is off)", InGameSettingsPresenter.blockedLine(2))
        assertNull(InGameSettingsPresenter.blockedLine(0))
    }

    @Test
    fun `reconcile summary uses the three-term contract`() {
        assertEquals(
            "4 applied · 1 superseded · 2 blocked",
            InGameSettingsPresenter.summaryLine(4, 1, 2),
        )
    }

    @Test
    fun `binding scopes map to the verified WTF layout and reject traversal`() {
        val root = File("/client")
        assertEquals(
            File("/client/WTF/Account/HI/bindings-cache.wtf").path,
            InGameSettingsFiles.accountBindings(root, "HI").path,
        )
        assertEquals(
            File("/client/WTF/Account/HI/MaNGOS/char-1/bindings-cache.wtf").path,
            InGameSettingsFiles.bindingsForScope(root, "HI/MaNGOS/char-1").path,
        )
        assertThrows(IllegalArgumentException::class.java) {
            InGameSettingsFiles.accountDirectory(root, "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InGameSettingsFiles.characterBindings(root, "HI/MaNGOS")
        }
    }
}
