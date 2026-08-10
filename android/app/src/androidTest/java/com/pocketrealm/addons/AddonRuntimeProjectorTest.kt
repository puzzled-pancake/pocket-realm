package com.pocketrealm.addons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddonRuntimeProjectorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var addonRoot: File
    private lateinit var clientRoot: File

    @Before
    fun setUp() {
        addonRoot = File(context.cacheDir, "addon-projector-packages-test").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        clientRoot = File(context.cacheDir, "addon-projector-test").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        addonRoot.deleteRecursively()
        clientRoot.deleteRecursively()
    }

    @Test
    fun managedFoldersProjectAtLaunchAndSafeModeLeavesImportedFoldersAlone() {
        val packageRoot = createPackage("QuestHelper")
        writeRegistry("QuestHelper", packageRoot)
        val imported = File(clientRoot, "Interface/AddOns/ManualAddon").apply { mkdirs() }
        File(imported, "ManualAddon.toc").writeText("## Interface: 11200")

        val projector = AddonRuntimeProjector(context, addonRoot)
        assertEquals(listOf("QuestHelper"), projector.project(clientRoot, safeMode = false))
        assertTrue(File(clientRoot, "Interface/AddOns/QuestHelper/QuestHelper.lua").isFile)
        assertTrue(File(imported, "ManualAddon.toc").isFile)

        assertEquals(emptyList<String>(), projector.project(clientRoot, safeMode = true))
        assertFalse(File(clientRoot, "Interface/AddOns/QuestHelper").exists())
        assertTrue(File(imported, "ManualAddon.toc").isFile)
    }

    @Test
    fun unownedFolderCollisionFailsBeforeChangingClientTree() {
        val packageRoot = createPackage("QuestHelper")
        writeRegistry("QuestHelper", packageRoot)
        val collision = File(clientRoot, "Interface/AddOns/QuestHelper").apply { mkdirs() }
        val marker = File(collision, "local.txt").apply { writeText("keep") }

        assertThrows(IllegalArgumentException::class.java) {
            AddonRuntimeProjector(context, addonRoot).project(clientRoot, safeMode = false)
        }
        assertEquals("keep", marker.readText())
        assertFalse(File(clientRoot, "Interface/AddOns/.pocketrealm-managed.json").exists())
    }

    private fun createPackage(folder: String): File {
        val relative = "packages/repository/0123456789abcdef0123456789abcdef01234567"
        val packageRoot = File(addonRoot, relative).apply { mkdirs() }
        val addon = File(packageRoot, folder).apply { mkdirs() }
        File(addon, "$folder.toc").writeText("## Interface: 11200\n$folder.lua\n")
        File(addon, "$folder.lua").writeText("local enabled = true\n")
        return packageRoot
    }

    private fun writeRegistry(folder: String, packageRoot: File) {
        val packagePath = packageRoot.relativeTo(addonRoot).invariantSeparatorsPath
        val installed = JSONObject()
            .put("id", "repository")
            .put("repository", "https://github.com/example/repository")
            .put("displayName", "Repository")
            .put("commitSha", "0123456789abcdef0123456789abcdef01234567")
            .put("archiveSha256", "a".repeat(64))
            .put("installedAtEpochMs", 1L)
            .put("packagePath", packagePath)
            .put("folders", JSONArray().put(folder))
        File(addonRoot, "registry.json").writeText(
            JSONObject().put("schema", 1).put("installed", JSONArray().put(installed)).toString(),
        )
    }
}
