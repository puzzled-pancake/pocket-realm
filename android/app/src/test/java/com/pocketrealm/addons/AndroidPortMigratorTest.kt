package com.pocketrealm.addons

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidPortMigratorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `registry remap rewrites id folders and repository in both registries`() {
        val root = temp.newFolder("addons")
        listOf("registry.json", "registry.previous.json").forEach { name ->
            File(root, name).writeText(registryJson(
                legacyInstalled().put("repository", "builtin:addons/vanilla-console-port"),
            ))
        }
        AndroidPortMigrator.migrate(root, null)
        listOf("registry.json", "registry.previous.json").forEach { name ->
            val entry = JSONObject(File(root, name).readText()).getJSONArray("installed").getJSONObject(0)
            assertEquals(AndroidPortPackage.INSTALL_ID, entry.getString("id"))
            assertEquals("builtin:addons/android-port", entry.getString("repository"))
            assertEquals(AndroidPortPackage.ADDON_FOLDER, entry.getJSONArray("folders").getString(0))
            // packagePath keeps pointing at the 0.5.x package until refresh.
            assertEquals("packages/legacy/pkg", entry.getString("packagePath"))
        }
    }

    @Test fun `registry without legacy or current builtin entries is untouched`() {
        val root = temp.newFolder("addons")
        val other = installed("quest", "QuestHelper", "packages/quest/commit")
        val original = registryJson(other)
        File(root, "registry.json").writeText(original)

        AndroidPortMigrator.migrate(root, null)

        assertEquals(original, File(root, "registry.json").readText())
    }

    @Test fun `corrupt registry is skipped without throwing`() {
        val root = temp.newFolder("addons")
        File(root, "registry.json").writeText("{ not json")
        AndroidPortMigrator.migrate(root, null)
        assertEquals("{ not json", File(root, "registry.json").readText())
    }

    @Test fun `legacy journal moves once and is never overwritten`() {
        val root = temp.newFolder("addons")
        File(root, "vanilla-console-port-bindings.json").writeText("""{"schema":1,"scopes":[]}""")
        AndroidPortMigrator.migrate(root, null)
        val moved = File(root, "android-port-bindings.json")
        assertTrue(moved.isFile)
        assertFalse(File(root, "vanilla-console-port-bindings.json").exists())

        moved.writeText("""{"schema":1,"scopes":[]}""")
        AndroidPortMigrator.migrate(root, null)
        assertEquals("""{"schema":1,"scopes":[]}""", moved.readText())
    }

    @Test fun `saved variables are copied beside their source with rewritten tokens`() {
        val (root, clientRoot) = roots()
        File(root, "vanilla-console-port-bindings.json").writeText("""{"schema":1,"scopes":[]}""")
        val saved = File(clientRoot, "WTF/Account/ACC/Realm/Char/SavedVariables/VanillaConsolePort.lua").apply {
            parentFile!!.mkdirs()
            writeText(
                """
                VanillaConsolePortDB = {
                    ["bindingBackup"] = { },
                    ["nested"] = { ["VanillaConsolePortDB"] = "key form is never rewritten" },
                }
                VanillaConsolePortCharacterDB = { }
                """.trimIndent(),
            )
        }
        val sourceContent = saved.readText()

        AndroidPortMigrator.migrate(root, clientRoot)

        val target = File(saved.parentFile, "AndroidPort.lua")
        val migrated = target.readText()
        assertTrue(migrated.contains("AndroidPortDB = {"))
        assertTrue(migrated.contains("AndroidPortCharacterDB = { }"))
        assertTrue(migrated.contains("[\"VanillaConsolePortDB\"] = \"key form is never rewritten\""))
        assertEquals(sourceContent, saved.readText())
    }

    @Test fun `existing new-name saved variables are never clobbered`() {
        val (root, clientRoot) = roots()
        File(root, "vanilla-console-port-bindings.json").writeText("""{"schema":1,"scopes":[]}""")
        val savedVariables = File(clientRoot, "WTF/Account/ACC/SavedVariables").apply { mkdirs() }
        File(savedVariables, "VanillaConsolePort.lua").writeText("VanillaConsolePortDB = { old = true }")
        File(savedVariables, "AndroidPort.lua").writeText("AndroidPortDB = { fresh = true }")

        AndroidPortMigrator.migrate(root, clientRoot)

        assertEquals("AndroidPortDB = { fresh = true }", File(savedVariables, "AndroidPort.lua").readText())
    }

    @Test fun `binding caches rewrite only legacy commands preserving each terminator`() {
        val (root, clientRoot) = roots()
        File(root, "vanilla-console-port-bindings.json").writeText("""{"schema":1,"scopes":[]}""")
        val account = File(clientRoot, "WTF/Account/ACC").apply { mkdirs() }
        File(account, "bindings-cache.wtf").writeText(
            "bind 1 VCP_ACTION_1\r\nbind F12 VCP_TOGGLE_RADIAL\r\r\nbind F8 MOVE_UI\nbind \"CTRL-3\" \"VCP_NEARBY_INTERACT\"",
        )
        val character = File(clientRoot, "WTF/Account/ACC/Realm/Char").apply { mkdirs() }
        File(character, "bindings-cache.wtf").writeText("bind 5 VCP_ACTION_5\n")

        AndroidPortMigrator.migrate(root, clientRoot)

        assertEquals(
            "bind 1 AP_ACTION_1\r\nbind F12 AP_TOGGLE_RADIAL\r\r\nbind F8 MOVE_UI\nbind CTRL-3 AP_NEARBY_INTERACT",
            File(account, "bindings-cache.wtf").readText(),
        )
        assertEquals("bind 5 AP_ACTION_5\n", File(character, "bindings-cache.wtf").readText())

        // Second run is a byte-for-byte no-op.
        val settled = File(account, "bindings-cache.wtf").readText()
        AndroidPortMigrator.migrate(root, clientRoot)
        assertEquals(settled, File(account, "bindings-cache.wtf").readText())
    }

    @Test fun `empty character binding overrides stay empty`() {
        val (root, clientRoot) = roots()
        File(root, "vanilla-console-port-bindings.json").writeText("""{"schema":1,"scopes":[]}""")
        val character = File(clientRoot, "WTF/Account/ACC/Realm/Char").apply { mkdirs() }
        File(character, "bindings-cache.wtf").writeText("")

        AndroidPortMigrator.migrate(root, clientRoot)

        assertEquals("", File(character, "bindings-cache.wtf").readText())
    }

    @Test fun `without ownership evidence the wtf tree is untouched`() {
        val (root, clientRoot) = roots()
        val savedVariables = File(clientRoot, "WTF/Account/ACC/SavedVariables").apply { mkdirs() }
        val saved = File(savedVariables, "VanillaConsolePort.lua").apply {
            writeText("VanillaConsolePortDB = { manual = true }")
        }
        val account = File(clientRoot, "WTF/Account/ACC").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply { writeText("bind 1 VCP_ACTION_1\n") }

        AndroidPortMigrator.migrate(root, clientRoot)

        assertFalse(File(savedVariables, "AndroidPort.lua").exists())
        assertEquals("VanillaConsolePortDB = { manual = true }", saved.readText())
        assertEquals("bind 1 VCP_ACTION_1\n", bindings.readText())
    }

    @Test fun `ownership file listing the legacy folder alone migrates the wtf tree`() {
        val (root, clientRoot) = roots()
        val addons = File(clientRoot, "Interface/AddOns").apply { mkdirs() }
        File(addons, ".pocketrealm-managed.json").writeText(
            JSONObject().put("schema", 1).put("folders", JSONArray().put("VanillaConsolePort")).toString(),
        )
        val savedVariables = File(clientRoot, "WTF/Account/ACC/SavedVariables").apply { mkdirs() }
        File(savedVariables, "VanillaConsolePort.lua").writeText("VanillaConsolePortDB = { ours = true }")

        AndroidPortMigrator.migrate(root, clientRoot)

        assertEquals("AndroidPortDB = { ours = true }", File(savedVariables, "AndroidPort.lua").readText())
    }

    private fun roots(): Pair<File, File> = temp.newFolder("addons") to temp.newFolder("client")

    private fun registryJson(vararg installed: JSONObject): String =
        JSONObject().put("schema", 1).put("installed", JSONArray(installed.toList())).toString()

    private fun installed(id: String, folder: String, packagePath: String): JSONObject = JSONObject()
        .put("id", id)
        .put("repository", "https://github.com/example/$id")
        .put("displayName", folder)
        .put("commitSha", "0".repeat(40))
        .put("archiveSha256", "a".repeat(64))
        .put("installedAtEpochMs", 1L)
        .put("packagePath", packagePath)
        .put("folders", JSONArray().put(folder))

    private fun legacyInstalled(): JSONObject = installed(
        AndroidPortPackage.LEGACY_INSTALL_ID,
        AndroidPortPackage.LEGACY_ADDON_FOLDER,
        "packages/legacy/pkg",
    )
}
