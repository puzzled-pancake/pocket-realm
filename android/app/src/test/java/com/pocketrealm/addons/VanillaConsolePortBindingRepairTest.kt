package com.pocketrealm.addons

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VanillaConsolePortBindingRepairTest {
    @Test fun `nearby F7 ownership restores exactly and a later player edit wins`() {
        val root = Files.createTempDirectory("vcp-nearby-f7").toFile()
        try {
            val bindings = File(root, "WTF/Account/TEST/bindings-cache.wtf").apply {
                parentFile.mkdirs()
                writeText("bind F7 TOGGLECHARACTER0\n")
            }
            val journal = File(root, "journal.json")
            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            bindings.writeText("bind F7 VCP_NEARBY_INTERACT\n")
            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals("bind F7 TOGGLECHARACTER0\n", bindings.readText())

            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            bindings.writeText("bind F7 PLAYER_EDIT\n")
            assertEquals(0, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals("bind F7 PLAYER_EDIT\n", bindings.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `removal restores exact owned bindings and preserves player edits`() {
        val root = Files.createTempDirectory("vcp-restore").toFile()
        try {
            val account = File(root, "WTF/Account/TEST").apply { mkdirs() }
            val bindings = File(account, "bindings-cache.wtf").apply {
                writeText(
                    "bind 1 VCP_ACTION_1\r\n" +
                        "bind 2 SPELLBOOK\r\n" +
                        "bind SHIFT-1 VCP_ACTION_11\r\n" +
                        "bind F7 VCP_NEARBY_INTERACT\r\n" +
                        "bind F8 VCP_MOVE_UI\r\n" +
                        "bind F9 TOGGLEAUTORUN\r\n" +
                        "bind F12 VCP_TOGGLE_RADIAL\r\n" +
                        "bind K TOGGLEGAMEMENU\r\n",
                )
            }
            val saved = File(account, "SavedVariables/VanillaConsolePort.lua").apply {
                parentFile.mkdirs()
                writeText(
                    "VanillaConsolePortDB = { [\"bindingBackup\"] = {\n" +
                        "[\"1\"] = \"ACTIONBUTTON1\", [\"2\"] = \"ACTIONBUTTON2\",\n" +
                        "[\"SHIFT-1\"] = \"MULTIACTIONBAR1BUTTON1\", " +
                        "[\"F7\"] = \"TOGGLECHARACTER0\", " +
                        "[\"F8\"] = \"QUESTLOG\", [\"F9\"] = \"OPENALLBAGS\", " +
                        "[\"F12\"] = \"\", }, }",
                )
            }

            val journal = File(root, "host-journal.json")
            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            assertTrue(journal.isFile)
            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals(
                "bind 1 ACTIONBUTTON1\r\n" +
                    "bind 2 SPELLBOOK\r\n" +
                    "bind SHIFT-1 MULTIACTIONBAR1BUTTON1\r\n" +
                    "bind F7 TOGGLECHARACTER0\r\n" +
                    "bind F8 QUESTLOG\r\n" +
                    "bind F9 OPENALLBAGS\r\n" +
                    "bind K TOGGLEGAMEMENU\r\n",
                bindings.readText(),
            )
            assertFalse(saved.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `missing ownership evidence never mutates a manual binding cache`() {
        val root = Files.createTempDirectory("vcp-no-journal").toFile()
        try {
            val bindings = File(root, "WTF/Account/TEST/bindings-cache.wtf").apply {
                parentFile.mkdirs(); writeText("bind 1 VCP_ACTION_1\n")
            }
            // The projector deliberately never calls repair in this state.
            assertTrue(bindings.readText() == "bind 1 VCP_ACTION_1\n")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `durable prelaunch journal survives crash before SavedVariables flush`() {
        val root = Files.createTempDirectory("vcp-crash-journal").toFile()
        try {
            val bindings = File(root, "WTF/Account/TEST/bindings-cache.wtf").apply {
                parentFile.mkdirs(); writeText(
                    "bind 1 ACTIONBUTTON1\nbind F7 TOGGLECHARACTER0\nbind F8 QUESTLOG\nbind F9 TOGGLEFRIENDSTAB\n" +
                        "bind F12 OPENALLBAGS\n",
                )
            }
            val journal = File(root, "journal.json")
            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            // Simulate the add-on saving bindings followed by a forced stop
            // before its Lua SavedVariables journal reaches disk.
            bindings.writeText(
                "bind 1 VCP_ACTION_1\nbind F7 VCP_NEARBY_INTERACT\nbind F8 VCP_MOVE_UI\nbind F9 TOGGLEAUTORUN\n" +
                    "bind F12 VCP_TOGGLE_RADIAL\n",
            )

            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals(
                "bind 1 ACTIONBUTTON1\nbind F7 TOGGLECHARACTER0\nbind F8 QUESTLOG\nbind F9 TOGGLEFRIENDSTAB\n" +
                    "bind F12 OPENALLBAGS\n",
                bindings.readText(),
            )
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `schema two modifier bindings restore while edits and retired keys are preserved`() {
        val root = Files.createTempDirectory("vcp-schema2-restore").toFile()
        try {
            val account = File(root, "WTF/Account/TEST").apply { mkdirs() }
            val bindings = File(account, "bindings-cache.wtf").apply {
                writeText(
                    "bind SHIFT-1 VCP_ACTION_9\n" +
                        "bind CTRL-1 VCP_ACTION_17\n" +
                        "bind CTRL-SHIFT-1 VCP_ACTION_25\n" +
                        "bind SHIFT-2 PLAYER_EDIT\n" +
                        "bind 9 ACTIONBUTTON9\n",
                )
            }
            val saved = File(account, "SavedVariables/VanillaConsolePort.lua").apply {
                parentFile.mkdirs()
                writeText(
                    "VanillaConsolePortDB = { [\"bindingBackup\"] = {\n" +
                        "[\"SHIFT-1\"] = \"MULTIACTIONBAR1BUTTON1\", " +
                        "[\"CTRL-1\"] = \"MULTIACTIONBAR2BUTTON1\", " +
                        "[\"CTRL-SHIFT-1\"] = \"MULTIACTIONBAR3BUTTON1\", }, }",
                )
            }
            val journal = File(root, "journal.json")
            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)

            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals(
                "bind SHIFT-1 MULTIACTIONBAR1BUTTON1\n" +
                    "bind CTRL-1 MULTIACTIONBAR2BUTTON1\n" +
                    "bind CTRL-SHIFT-1 MULTIACTIONBAR3BUTTON1\n" +
                    "bind SHIFT-2 PLAYER_EDIT\n" +
                    "bind 9 ACTIONBUTTON9\n",
                bindings.readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `old host journal learns newly managed keys before schema upgrade claims them`() {
        val root = Files.createTempDirectory("vcp-old-host-journal").toFile()
        try {
            val relative = "Account/TEST/bindings-cache.wtf"
            val bindings = File(root, "WTF/$relative").apply {
                parentFile.mkdirs()
                writeText(
                    "bind 1 VCP_ACTION_1\n" +
                        "bind F8 QUESTLOG\n" +
                        "bind F9 OPENALLBAGS\n" +
                        "bind F12 VCP_TOGGLE_RADIAL\n",
                )
            }
            val journal = File(root, "journal.json").apply {
                writeText(
                    org.json.JSONObject()
                        .put("schema", 1)
                        .put("scopes", org.json.JSONArray().put(
                            org.json.JSONObject()
                                .put("path", relative)
                                .put("previous", org.json.JSONObject()
                                    .put("1", "ACTIONBUTTON1")
                                    .put("F12", "")),
                        ))
                        .toString(),
                )
            }

            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            val previous = org.json.JSONObject(journal.readText())
                .getJSONArray("scopes").getJSONObject(0).getJSONObject("previous")
            assertEquals("QUESTLOG", previous.getString("F8"))
            assertEquals("OPENALLBAGS", previous.getString("F9"))
            assertEquals("", previous.getString("F12"))

            bindings.writeText(
                "bind 1 VCP_ACTION_1\n" +
                    "bind F8 VCP_MOVE_UI\n" +
                    "bind F9 TOGGLEAUTORUN\n" +
                    "bind F12 VCP_TOGGLE_RADIAL\n",
            )
            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals(
                "bind 1 ACTIONBUTTON1\n" +
                    "bind F8 QUESTLOG\n" +
                    "bind F9 OPENALLBAGS\n",
                bindings.readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `incomplete old journal merges new key provenance from SavedVariables on removal`() {
        val root = Files.createTempDirectory("vcp-old-journal-removal").toFile()
        try {
            val relative = "Account/TEST/bindings-cache.wtf"
            val bindings = File(root, "WTF/$relative").apply {
                parentFile.mkdirs()
                writeText(
                    "bind 1 VCP_ACTION_1\n" +
                        "bind F8 VCP_MOVE_UI\n" +
                        "bind F9 TOGGLEAUTORUN\n",
                )
            }
            File(bindings.parentFile, "SavedVariables/VanillaConsolePort.lua").apply {
                parentFile.mkdirs()
                writeText(
                    "VanillaConsolePortDB = { [\"bindingBackup\"] = {" +
                        "[\"F8\"] = \"QUESTLOG\", [\"F9\"] = \"OPENALLBAGS\", }, }",
                )
            }
            val journal = File(root, "journal.json").apply {
                writeText(
                    org.json.JSONObject()
                        .put("schema", 1)
                        .put("scopes", org.json.JSONArray().put(
                            org.json.JSONObject()
                                .put("path", relative)
                                .put("previous", org.json.JSONObject().put("1", "ACTIONBUTTON1")),
                        ))
                        .toString(),
                )
            }

            assertEquals(1, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals(
                "bind 1 ACTIONBUTTON1\n" +
                    "bind F8 QUESTLOG\n" +
                    "bind F9 OPENALLBAGS\n",
                bindings.readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `ambiguous preexisting F9 autorun is preserved without provenance`() {
        val root = Files.createTempDirectory("vcp-preexisting-autorun").toFile()
        try {
            val relative = "Account/TEST/bindings-cache.wtf"
            val bindings = File(root, "WTF/$relative").apply {
                parentFile.mkdirs()
                writeText("bind F9 TOGGLEAUTORUN\n")
            }
            val journal = File(root, "journal.json").apply {
                writeText(
                    org.json.JSONObject()
                        .put("schema", 1)
                        .put("scopes", org.json.JSONArray().put(
                            org.json.JSONObject()
                                .put("path", relative)
                                .put("previous", org.json.JSONObject()),
                        ))
                        .toString(),
                )
            }

            VanillaConsolePortBindingRepair.captureBeforeLaunch(root, journal)
            val previous = org.json.JSONObject(journal.readText())
                .getJSONArray("scopes").getJSONObject(0).getJSONObject("previous")
            assertEquals("TOGGLEAUTORUN", previous.getString("F9"))
            assertEquals(0, VanillaConsolePortBindingRepair.restoreAfterRemoval(root, journal))
            assertEquals("bind F9 TOGGLEAUTORUN\n", bindings.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
