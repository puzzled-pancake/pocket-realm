package com.pocketrealm.ingame

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * G3-10 work item 2: the `WtfLayout` strategy seam is refactor-by-addition —
 * the vanilla strategy produces byte-for-byte the paths InGameSettingsFiles
 * always produced (pinned here against literal expectations for a canonical
 * client root, account and scope set), and no flavor but vanilla resolves to
 * a layout until H4-5 adds WotLK's per-realm/per-character routes as a NEW
 * strategy.
 */
class InGameSettingsFilesLayoutPinTest {
    private val clientRoot = File("M:/clients/wow-1.12.1-5875")

    @Test fun `vanilla strategy produces the pinned build-5875 paths`() {
        val layout: WtfLayout = VanillaWtfLayout
        assertEquals(File(clientRoot, "WTF/Config.wtf"), layout.configFile(clientRoot))
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1"),
            layout.accountDirectory(clientRoot, "ACCT1"),
        )
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1/SavedVariables.lua"),
            layout.accountSavedVariables(clientRoot, "ACCT1"),
        )
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1/bindings-cache.wtf"),
            layout.accountBindings(clientRoot, "ACCT1"),
        )
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1/Milliseconds/Toon/bindings-cache.wtf"),
            layout.characterBindings(clientRoot, "ACCT1/Milliseconds/Toon"),
        )
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1/Milliseconds/Toon/bindings-cache.wtf"),
            layout.bindingsForScope(clientRoot, "ACCT1/Milliseconds/Toon"),
        )
        assertEquals(
            File(clientRoot, "WTF/Account/ACCT1/bindings-cache.wtf"),
            layout.bindingsForScope(clientRoot, "ACCT1"),
        )
    }

    @Test fun `InGameSettingsFiles path functions delegate to the vanilla layout unchanged`() {
        // The pre-refactor public outputs, asserted against the strategy outputs.
        assertEquals(VanillaWtfLayout.configFile(clientRoot), InGameSettingsFiles.configFile(clientRoot))
        assertEquals(
            VanillaWtfLayout.accountDirectory(clientRoot, "ACCT1"),
            InGameSettingsFiles.accountDirectory(clientRoot, "ACCT1"),
        )
        assertEquals(
            VanillaWtfLayout.accountSavedVariables(clientRoot, "ACCT1"),
            InGameSettingsFiles.accountSavedVariables(clientRoot, "ACCT1"),
        )
        assertEquals(
            VanillaWtfLayout.accountBindings(clientRoot, "ACCT1"),
            InGameSettingsFiles.accountBindings(clientRoot, "ACCT1"),
        )
        assertEquals(
            VanillaWtfLayout.characterBindings(clientRoot, "ACCT1/Milliseconds/Toon"),
            InGameSettingsFiles.characterBindings(clientRoot, "ACCT1/Milliseconds/Toon"),
        )
        assertEquals(
            VanillaWtfLayout.bindingsForScope(clientRoot, "ACCT1/Milliseconds/Toon"),
            InGameSettingsFiles.bindingsForScope(clientRoot, "ACCT1/Milliseconds/Toon"),
        )
    }

    @Test fun `vanilla flavor resolves the vanilla layout and the new lanes resolve their own`() {
        // G3-10 froze the seam with fail-loud placeholders; W3.3 (H4-5)
        // landed the TBC/WotLK strategies as NEW layouts — the vanilla
        // resolution is unchanged and the new lanes resolve their own
        // strategies (WtfFlavorLayoutTest pins their paths).
        assertEquals(VanillaWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.VANILLA))
        assertEquals(TbcWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.TBC))
        assertEquals(WotlkWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.WOTLK))
        // An unknown flavor id still fails loud — never a silent vanilla pick.
        assertThrows(IllegalStateException::class.java) {
            InGameSettingsFiles.layoutFor(
                ClientFlavors.TBC.copy(flavorId = "wotlk-classic-demo"),
            )
        }
    }

    @Test fun `enumerated file lists still resolve through the vanilla strategy`() {
        val root = java.nio.file.Files.createTempDirectory("g3-wtf").toFile()
        try {
            File(root, "WTF/Account/Alpha/SavedVariables.lua").apply { parentFile.mkdirs() }.writeText("")
            File(root, "WTF/Account/Alpha/bindings-cache.wtf").writeText("")
            File(root, "WTF/Account/Beta/bindings-cache.wtf").apply { parentFile.mkdirs() }.writeText("")
            File(root, "WTF/Account/Alpha/Server1/Char1/bindings-cache.wtf").apply {
                parentFile.mkdirs(); writeText("")
            }
            File(root, "WTF/Account/.hidden/bindings-cache.wtf").apply {
                parentFile.mkdirs(); writeText("")
            }

            assertEquals(listOf("Alpha"), InGameSettingsFiles.accountsWithSavedVariables(root))
            assertEquals(listOf("Alpha", "Beta"), InGameSettingsFiles.accountsWithBindings(root))
            // The character-scope walk resolves the per-character override
            // file; the traversal of account-level binding files is the
            // function's pre-refactor behavior (unchanged by the G3-10 seam
            // extraction) and is not re-pinned here.
            val scopes = InGameSettingsFiles.characterScopesWithBindings(root)
            val characterScopes = scopes.filter { scope ->
                scope.count { it == '/' } == 2 && !scope.startsWith("WTF/")
            }
            assertEquals(listOf("Alpha/Server1/Char1"), characterScopes)
        } finally {
            root.deleteRecursively()
        }
    }
}
