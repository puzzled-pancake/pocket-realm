package com.pocketrealm.ingame

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W3.3 (H4-5, correction C8): the TBC and WotLK WTF layouts — golden path
 * pins per flavor, synthetic-tree round-trips through the codecs the
 * editor uses, and the corruption-recovery contract (a corrupt line never
 * takes the file down; the next atomic rewrite restores a clean file, so
 * recovery loses nothing that was still parseable).
 *
 * The layouts encode the repo's recorded C8 contract: TBC = 3-level WITHOUT
 * the char-level cache set; WotLK = 3-level PLUS the five-file char cache
 * set (mirrored on ClientFlavor.wtfCharacterCacheFiles from the manifest's
 * `wtf` descriptor). The vanilla lane stays byte-frozen
 * (InGameSettingsFilesLayoutPinTest).
 */
class WtfFlavorLayoutTest {

    // The synthetic client root derives from the OWNING registry (the tbc
    // clientId) — never a literal build number outside its owning table.
    private val root = File("M:/clients", com.pocketrealm.client.ClientFlavors.TBC.clientId)

    // ---- golden paths -----------------------------------------------------

    @Test fun `tbc layout golden paths`() {
        val layout: WtfLayout = TbcWtfLayout
        assertEquals(File(root, "WTF/Config.wtf"), layout.configFile(root))
        assertEquals(File(root, "WTF/Account/ACCT1"), layout.accountDirectory(root, "ACCT1"))
        assertEquals(
            File(root, "WTF/Account/ACCT1/bindings-cache.wtf"),
            layout.accountBindings(root, "ACCT1"),
        )
        assertEquals(
            File(root, "WTF/Account/ACCT1/Milliseconds/Toon/bindings-cache.wtf"),
            layout.characterBindings(root, "ACCT1/Milliseconds/Toon"),
        )
        assertEquals(
            File(root, "WTF/Account/ACCT1/Milliseconds/Toon/bindings-cache.wtf"),
            layout.bindingsForScope(root, "ACCT1/Milliseconds/Toon"),
        )
        assertEquals(
            File(root, "WTF/Account/ACCT1/bindings-cache.wtf"),
            layout.bindingsForScope(root, "ACCT1"),
        )
    }

    @Test fun `wotlk layout golden paths match tbc shapes plus the cache set`() {
        val layout: WtfLayout = WotlkWtfLayout
        val tbc: WtfLayout = TbcWtfLayout
        // The config/binding route SHAPES are the 3-level family shared
        // with TBC.
        assertEquals(tbc.configFile(root), layout.configFile(root))
        assertEquals(tbc.accountBindings(root, "ACCT1"), layout.accountBindings(root, "ACCT1"))
        assertEquals(
            tbc.characterBindings(root, "ACCT1/Milliseconds/Toon"),
            layout.characterBindings(root, "ACCT1/Milliseconds/Toon"),
        )
        // ...and the WotLK-only char-level cache set rides the C8 data.
        assertEquals(
            ClientFlavors.WOTLK.wtfCharacterCacheFiles,
            layout.characterCacheFiles,
        )
        assertEquals(
            setOf(
                "config-cache.wtf", "bindings-cache.wtf", "macros-cache.txt",
                "layout-local.txt", "chat-cache.txt",
            ),
            layout.characterCacheFiles,
        )
        assertEquals(
            File(root, "WTF/Account/ACCT1/Milliseconds/Toon/config-cache.wtf"),
            layout.characterCacheFile(root, "ACCT1/Milliseconds/Toon", "config-cache.wtf"),
        )
        assertEquals(
            File(root, "WTF/Account/ACCT1/Milliseconds/Toon/chat-cache.txt"),
            layout.characterCacheFile(root, "ACCT1/Milliseconds/Toon", "chat-cache.txt"),
        )
    }

    @Test fun `the char cache set is wotlk-only and fail loud elsewhere`() {
        assertEquals(emptySet<String>(), TbcWtfLayout.characterCacheFiles)
        assertEquals(emptySet<String>(), VanillaWtfLayout.characterCacheFiles)
        listOf(VanillaWtfLayout, TbcWtfLayout).forEach { layout ->
            val refusal = assertThrows(IllegalStateException::class.java) {
                layout.characterCacheFile(root, "ACCT1/Milliseconds/Toon", "config-cache.wtf")
            }
            assertTrue(
                "the refusal must name the C8 correction: ${refusal.message}",
                "correction C8" in refusal.message!!,
            )
        }
        // An INVENTED cache file name on the wotlk lane fails loud too.
        val invented = assertThrows(IllegalArgumentException::class.java) {
            WotlkWtfLayout.characterCacheFile(root, "ACCT1/Milliseconds/Toon", "registry.xml")
        }
        assertTrue(
            "the refusal must name the cache set: ${invented.message}",
            "not in the WotLK char-level cache set" in invented.message!!,
        )
    }

    @Test fun `the single-file uvar route is vanilla-only and fail loud`() {
        // vanilla keeps the frozen route...
        assertEquals(
            File(root, "WTF/Account/ACCT1/SavedVariables.lua"),
            VanillaWtfLayout.accountSavedVariables(root, "ACCT1"),
        )
        // ...and the new lanes refuse it with the honest per-flavor message.
        listOf(TbcWtfLayout, WotlkWtfLayout).forEach { layout ->
            val refusal = assertThrows(IllegalStateException::class.java) {
                layout.accountSavedVariables(root, "ACCT1")
            }
            assertTrue(
                "the refusal must name the vanilla-only family: ${refusal.message}",
                "vanilla-lane family" in refusal.message!!,
            )
            assertTrue(
                "the refusal must name the per-addon route: ${refusal.message}",
                "SavedVariables/" in refusal.message!!,
            )
        }
    }

    @Test fun `layoutFor resolves every flavor and never a silent vanilla`() {
        assertEquals(VanillaWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.VANILLA))
        assertEquals(TbcWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.TBC))
        assertEquals(WotlkWtfLayout, InGameSettingsFiles.layoutFor(ClientFlavors.WOTLK))
    }

    // ---- synthetic-tree round-trips ---------------------------------------

    /** Builds a logged-in WotLK-style 3-level tree with per-addon
     * SavedVariables directories and a char cache set. */
    private fun wotlkTree(): File {
        val tree = java.nio.file.Files.createTempDirectory("w3-wotlk-wtf").toFile()
        File(tree, "WTF/Account/ALPHA/SavedVariables").mkdirs()
        File(tree, "WTF/Account/ALPHA/bindings-cache.wtf").writeText(
            "bind \"W\" \"MOVEFORWARD\"\r\n",
        )
        File(tree, "WTF/Account/ALPHA/Milliseconds/Toon/SavedVariables").mkdirs()
        File(tree, "WTF/Account/ALPHA/Milliseconds/Toon/config-cache.wtf").writeText(
            "SET cameraYawSmoothSpeed \"90.000000\"\r\n",
        )
        File(tree, "WTF/Account/ALPHA/Milliseconds/Toon/bindings-cache.wtf").writeText(
            "bind \"W\" \"MOVEBACKWARD\"\r\n",
        )
        // a hidden OS dir the enumerations must never report
        File(tree, "WTF/Account/.Hidden/bindings-cache.wtf").apply {
            parentFile.mkdirs(); writeText("")
        }
        return tree
    }

    @Test fun `wotlk synthetic tree round trips enumerations and binding writes`() {
        val tree = wotlkTree()
        try {
            // The uvar enumeration rides the per-addon DIRECTORY route.
            assertEquals(listOf("ALPHA"), InGameSettingsFiles.accountsWithSavedVariablesFor(
                WotlkWtfLayout, tree,
            ))
            assertEquals(listOf("ALPHA"), InGameSettingsFiles.accountsWithBindingsFor(
                WotlkWtfLayout, tree,
            ))
            // The char cache route resolves inside the REAL tree.
            val cache = WotlkWtfLayout.characterCacheFile(
                tree, "ALPHA/Milliseconds/Toon", "config-cache.wtf",
            )
            assertTrue(cache.isFile)
            assertEquals(
                mapOf("cameraYawSmoothSpeed" to "90.000000"),
                ConfigWtfCodec.parse(cache.readText()),
            )
            // Binding round-trip through the account route: assign, read
            // back, clear.
            val bindings = WotlkWtfLayout.accountBindings(tree, "ALPHA")
            var text = bindings.readText()
            text = BindingsFileCodec.assign(text, "CTRL-SHIFT-P", "TOGGLETIME")
            InGameSettingsFiles.writeAtomic(bindings, text)
            assertEquals(
                "TOGGLETIME",
                BindingsFileCodec.parse(bindings.readText())["CTRL-SHIFT-P"],
            )
            var reread = bindings.readText()
            BindingsFileCodec.keysForCommand(reread, "TOGGLETIME").forEach { key ->
                reread = BindingsFileCodec.assign(reread, key, null)
            }
            InGameSettingsFiles.writeAtomic(bindings, reread)
            assertFalse(
                BindingsFileCodec.parse(bindings.readText()).containsKey("CTRL-SHIFT-P"),
            )
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test fun `tbc synthetic tree enumerates through the per-addon directory`() {
        val tree = java.nio.file.Files.createTempDirectory("w3-tbc-wtf").toFile()
        try {
            File(tree, "WTF/Account/BETA/SavedVariables").mkdirs()
            File(tree, "WTF/Account/BETA/bindings-cache.wtf")
                .writeText("bind \"W\" \"MOVEFORWARD\"\r\n")
            File(tree, "WTF/Account/GAMMA/bindings-cache.wtf").apply {
                parentFile.mkdirs(); writeText("")
            }
            assertEquals(
                listOf("BETA"),
                InGameSettingsFiles.accountsWithSavedVariablesFor(TbcWtfLayout, tree),
            )
            assertEquals(
                listOf("BETA", "GAMMA"),
                InGameSettingsFiles.accountsWithBindingsFor(TbcWtfLayout, tree),
            )
        } finally {
            tree.deleteRecursively()
        }
    }

    // ---- corruption recovery ----------------------------------------------

    @Test fun `corrupted binding and cache files recover without silent loss`() {
        val tree = wotlkTree()
        try {
            // A corrupted account bindings file: the garbage line is
            // skipped, the valid binds still parse (the codec's
            // line-level recovery), and an assign+rewrite produces a clean
            // file carrying BOTH the surviving binds and the new one.
            val bindings = WotlkWtfLayout.accountBindings(tree, "ALPHA")
            bindings.writeText(
                "bind \"W\" \"MOVEFORWARD\"\r\nGARBAGE @@\u0000LINE\r\n" +
                    "bind \"CTRL-X\" \"MOVEBACKWARD\"\r\n",
            )
            val parsed = BindingsFileCodec.parse(bindings.readText())
            assertEquals("MOVEFORWARD", parsed["W"])
            assertEquals("MOVEBACKWARD", parsed["CTRL-X"])
            var text = bindings.readText()
            text = BindingsFileCodec.assign(text, "CTRL-SHIFT-P", "TOGGLETIME")
            InGameSettingsFiles.writeAtomic(bindings, text)
            val recovered = BindingsFileCodec.parse(bindings.readText())
            assertEquals("MOVEBACKWARD", recovered["CTRL-X"])
            assertEquals("TOGGLETIME", recovered["CTRL-SHIFT-P"])
            assertFalse(recovered.containsKey("GARBAGE"))

            // The WotLK char cache file: corrupt lines drop out, the valid
            // cvar survives, and the merge-rewrite restores a clean file.
            val cache = WotlkWtfLayout.characterCacheFile(
                tree, "ALPHA/Milliseconds/Toon", "config-cache.wtf",
            )
            cache.writeText(
                "SET cameraYawSmoothSpeed \"90.000000\"\r\nSET broken\r\njunk-junk\r\n",
            )
            val merged = ConfigWtfCodec.merge(
                cache.readText(),
                emptyList(),
                listOf(ConfigWtfCodec.UserOverride("cameraYawSmoothSpeed", "120.000000")),
            ).text
            InGameSettingsFiles.writeAtomic(cache, merged)
            val cacheRecovered = ConfigWtfCodec.parse(cache.readText())
            assertEquals("120.000000", cacheRecovered["cameraYawSmoothSpeed"])
            // `SET broken` is a TOLERANT parse (empty value - the codec's
            // documented recovery), and the prose line takes no key at all:
            // neither corrupt line takes the file down.
            assertFalse(cacheRecovered.containsKey("junk-junk"))
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test fun `wotlk cache routes reject unsafe scope segments`() {
        listOf("ACCT1/../EVIL/Toon", "ACCT1/Milliseconds/..").forEach { scope ->
            assertThrows(IllegalArgumentException::class.java) {
                WotlkWtfLayout.characterCacheFile(root, scope, "config-cache.wtf")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WotlkWtfLayout.characterCacheFile(root, "ACCT1/Toon", "config-cache.wtf")
        }
    }
}
