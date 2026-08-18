package com.pocketrealm.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyControllerBindingRepairTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `clean substantive account receives target surrogate without retired addon state`() {
        val client = temporary.newFolder("client-clean-target")
        val bindings = File(client, "WTF/Account/PLAYER/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("bind W MOVEFORWARD\nbind ESCAPE TOGGLEGAMEMENU\n")
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind W MOVEFORWARD\nbind ESCAPE TOGGLEGAMEMENU\n" +
                "bind F6 TARGETNEARESTENEMY\nbind F9 TOGGLEAUTORUN\n",
            bindings.readText(),
        )
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `retired commands become stock leveling bindings and repair is idempotent`() {
        val client = temporary.newFolder("client")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText(
                "bind W MOVEFORWARD\r\n" +
                    "bind \"1\" \"PRP_ACTION1\"\r\n" +
                    "bind F8 PRP_MOD_BANK\r\n" +
                    "bind F10 PRP_MOD_CTRL\r\n" +
                    "bind F11 PRP_EXTRA_BANK\r\n" +
                    "bind F7 PRP_QUICK_MENU\r\n" +
                    "bind B PRP_INVENTORY\r\n" +
                    "bind UP PRP_NAV_UP\r\n" +
                    "bind \"TAB\" \"PRP_TARGET_NEXT_ENEMY\"\r\n",
            )
        }
        val saved = File(account, "SavedVariables/PocketRealmPadLauncher.lua").apply {
            parentFile!!.mkdirs()
            writeText("""
                PocketRealmPadLauncherDB = {
                    ["claimed"] = { ["F8"] = true, ["F7"] = true },
                    ["previous"] = {
                        ["F8"] = "TOGGLEBAG1",
                        ["F10"] = "TOGGLEBAG3",
                        ["F11"] = "TOGGLEBAG4",
                        ["F7"] = false,
                        ["TAB"] = "TARGETNEARESTENEMY",
                    },
                }
            """.trimIndent())
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        val repaired = bindings.readText()
        assertTrue("bind W MOVEFORWARD" in repaired)
        assertTrue("bind 1 ACTIONBUTTON1" in repaired)
        assertTrue("bind B TOGGLEBACKPACK" in repaired)
        assertTrue("bind UP MOVEFORWARD" in repaired)
        assertTrue("bind F8 TOGGLEBAG1" in repaired)
        assertTrue("bind F10 TOGGLEBAG3" in repaired)
        assertTrue("bind F11 TOGGLEBAG4" in repaired)
        assertFalse("bind F7 " in repaired)
        assertTrue("bind TAB TARGETNEARESTENEMY" in repaired)
        assertTrue("bind F6 TARGETNEARESTENEMY" in repaired)
        assertFalse("PRP_" in repaired)
        assertFalse(saved.exists())
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `unrelated bindings and settings are untouched`() {
        val client = temporary.newFolder("client-clean")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText("bind TAB TARGETNEARESTENEMY\nbind F6 TARGETNEARESTENEMY\n")
        }
        val unrelated = File(account, "SavedVariables/QuestHelper.lua").apply {
            parentFile!!.mkdirs()
            writeText("keep=true")
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind TAB TARGETNEARESTENEMY\nbind F6 TARGETNEARESTENEMY\n" +
                "bind F9 TOGGLEAUTORUN\n",
            bindings.readText(),
        )
        assertTrue(unrelated.isFile)
    }

    @Test fun `fake loot all is retired and F11 plus target surrogate are restored`() {
        val client = temporary.newFolder("client-loot")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText(
                "bind TAB TARGETNEARESTENEMY\n" +
                    "bind F6 OPENALLBAGS\n" +
                    "bind F11 POCKETREALM_LOOT_ALL\n" +
                    "bind F7 POCKETREALM_LOOT_ALL\n",
            )
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        val repaired = bindings.readText()
        assertFalse("POCKETREALM_LOOT_ALL" in repaired)
        assertTrue("bind F11 TOGGLEBAG4" in repaired)
        assertFalse("bind F7 " in repaired)
        assertTrue("bind F6 OPENALLBAGS" in repaired)
        assertFalse("bind F6 TARGETNEARESTENEMY" in repaired)
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `launcher journal wins over unrelated saved variables deterministically`() {
        val client = temporary.newFolder("client-journal")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText("bind F8 PRP_MOD_BANK\nbind F6 TARGETNEARESTENEMY\n")
        }
        File(account, "SavedVariables/PocketRealmPadLauncher.lua.bak").apply {
            parentFile!!.mkdirs()
            writeText("""["previous"] = { ["F8"] = "TOGGLEBAG1", },""")
        }
        File(account, "SavedVariables/PocketRealmPadLauncher.lua").writeText(
            """["previous"] = { ["F8"] = "TOGGLEBAG2", },""",
        )
        File(account, "SavedVariables/PocketRealmPad.lua").writeText("PocketRealmPadDB = {}")
        File(account, "SavedVariables/PocketRealmPad.lua.bak").writeText("")

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertTrue("bind F8 TOGGLEBAG2" in bindings.readText())
        assertFalse(File(account, "SavedVariables/PocketRealmPad.lua").exists())
        assertFalse(File(account, "SavedVariables/PocketRealmPadLauncher.lua").exists())
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `existing F6 bindings are never overwritten by target repair`() {
        val client = temporary.newFolder("client-target-duplicate")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText(
                "bind F6 OPENALLBAGS\n" +
                    "bind TAB TARGETNEARESTENEMY\n",
            )
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind F6 OPENALLBAGS\nbind TAB TARGETNEARESTENEMY\nbind F9 TOGGLEAUTORUN\n",
            bindings.readText(),
        )
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `existing F9 binding is never overwritten by autorun repair`() {
        val client = temporary.newFolder("client-autorun-duplicate")
        val bindings = File(client, "WTF/Account/PLAYER/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("bind F6 TARGETNEARESTENEMY\nbind F9 OPENALLBAGS\n")
        }

        assertEquals(0, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind F6 TARGETNEARESTENEMY\nbind F9 OPENALLBAGS\n",
            bindings.readText(),
        )
    }

    @Test fun `retired F9 command without previous binding receives autorun surrogate`() {
        val client = temporary.newFolder("client-retired-f9-empty")
        val bindings = File(client, "WTF/Account/PLAYER/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("bind W MOVEFORWARD\nbind F9 PRP_MOD_CTRL\nbind F6 TARGETNEARESTENEMY\n")
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind W MOVEFORWARD\nbind F6 TARGETNEARESTENEMY\nbind F9 TOGGLEAUTORUN\n",
            bindings.readText(),
        )
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `retired F9 command restores arbitrary previous binding instead of autorun`() {
        val client = temporary.newFolder("client-retired-f9-previous")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply {
            writeText("bind W MOVEFORWARD\nbind F9 PRP_MOD_CTRL\nbind F6 TARGETNEARESTENEMY\n")
        }
        File(account, "SavedVariables/PocketRealmPadLauncher.lua").apply {
            parentFile!!.mkdirs()
            writeText("""["previous"] = { ["F9"] = "OPENALLBAGS", },""")
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals(
            "bind W MOVEFORWARD\nbind F6 TARGETNEARESTENEMY\nbind F9 OPENALLBAGS\n",
            bindings.readText(),
        )
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `sparse generated character target is deleted and account bindings remain complete`() {
        val client = temporary.newFolder("client-sparse-character")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        val accountBindings = File(account, "bindings-cache.wtf").apply {
            writeText("bind W MOVEFORWARD\r\nbind ESCAPE TOGGLEGAMEMENU\r\n")
        }
        val characterBindings = File(account, "Realm/Character/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("\r\nbind F6 TARGETNEARESTENEMY\r\n\r\n")
        }

        assertEquals(2, LegacyControllerBindingRepair.repair(client))
        assertFalse(characterBindings.exists())
        val repairedAccount = accountBindings.readText()
        assertTrue("bind W MOVEFORWARD" in repairedAccount)
        assertTrue("bind ESCAPE TOGGLEGAMEMENU" in repairedAccount)
        assertEquals(
            1,
            Regex("(?m)^bind F6 TARGETNEARESTENEMY\\r?$").findAll(repairedAccount).count(),
        )
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `empty character binding scope is never populated`() {
        val client = temporary.newFolder("client-empty-character")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        File(account, "bindings-cache.wtf").writeText("bind W MOVEFORWARD\n")
        val characterBindings = File(account, "Realm/Character/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("\n")
        }

        assertEquals(1, LegacyControllerBindingRepair.repair(client))
        assertEquals("\n", characterBindings.readText())
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }

    @Test fun `target-only character scope is retained without substantive account fallback`() {
        val client = temporary.newFolder("client-target-only-no-fallback")
        val characterBindings = File(
            client,
            "WTF/Account/PLAYER/Realm/Character/bindings-cache.wtf",
        ).apply {
            parentFile!!.mkdirs()
            writeText("bind F6 TARGETNEARESTENEMY\n")
        }

        assertEquals(0, LegacyControllerBindingRepair.repair(client))
        assertEquals("bind F6 TARGETNEARESTENEMY\n", characterBindings.readText())
    }

    @Test fun `target-only cache outside an account scope is never deleted`() {
        val client = temporary.newFolder("client-target-outside-account")
        val unrelated = File(client, "WTF/Profiles/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("bind F6 TARGETNEARESTENEMY\n")
        }

        assertEquals(0, LegacyControllerBindingRepair.repair(client))
        assertEquals("bind F6 TARGETNEARESTENEMY\n", unrelated.readText())
    }

    @Test fun `substantive character binding scope is preserved and receives target surrogate`() {
        val client = temporary.newFolder("client-substantive-character")
        val account = File(client, "WTF/Account/PLAYER").apply { mkdirs() }
        File(account, "bindings-cache.wtf").writeText("bind W MOVEFORWARD\n")
        val characterBindings = File(account, "Realm/Character/bindings-cache.wtf").apply {
            parentFile!!.mkdirs()
            writeText("bind W MOVEFORWARD\nbind F8 PRP_MOD_BANK\n")
        }
        File(account, "SavedVariables/PocketRealmPadLauncher.lua").apply {
            parentFile!!.mkdirs()
            writeText("""["previous"] = { ["F8"] = "TOGGLEBAG1", },""")
        }

        assertEquals(2, LegacyControllerBindingRepair.repair(client))
        val repaired = characterBindings.readText()
        assertTrue("bind W MOVEFORWARD" in repaired)
        assertTrue("bind F8 TOGGLEBAG1" in repaired)
        assertTrue("bind F6 TARGETNEARESTENEMY" in repaired)
        assertEquals(0, LegacyControllerBindingRepair.repair(client))
    }
}
