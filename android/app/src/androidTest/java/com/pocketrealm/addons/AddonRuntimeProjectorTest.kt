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

    @Before fun setUp() {
        addonRoot = File(context.cacheDir, "addon-projector-packages-test").also {
            it.deleteRecursively(); check(it.mkdirs())
        }
        clientRoot = File(context.cacheDir, "addon-projector-test").also {
            it.deleteRecursively(); check(it.mkdirs())
        }
    }

    @After fun tearDown() {
        addonRoot.deleteRecursively()
        clientRoot.deleteRecursively()
    }

    @Test fun managedFoldersProjectAtLaunchAndSafeModeLeavesImportedFoldersAlone() {
        val packageRoot = createPackage("QuestHelper")
        writeRegistry(listOf(installed("quest", "QuestHelper", packageRoot)))
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

    @Test fun retiredControllerProductIsRemovedWhileOtherAddonsRemain() {
        val quest = createPackage("QuestHelper", "packages/quest/commit")
        val retired = createPackage("PocketRealmPad", "packages/retired/commit")
        writeRegistry(listOf(
            installed("quest", "QuestHelper", quest),
            installed("bundled__pocketrealmpad", "PocketRealmPad", retired),
        ))
        val runtimeRetired = File(clientRoot, "Interface/AddOns/PocketRealmPad").apply { mkdirs() }
        File(runtimeRetired, "stale.lua").writeText("stale=true")
        val helper = File(clientRoot, "Interface/AddOns/PocketRealmPadLauncher").apply { mkdirs() }
        File(helper, "stale.lua").writeText("stale=true")
        File(runtimeRetired.parentFile, ".pocketrealm-managed.json").writeText(
            JSONObject().put("schema", 1).put(
                "folders",
                JSONArray().put("PocketRealmPad").put("PocketRealmPadLauncher"),
            ).toString(),
        )

        val applied = AddonRuntimeProjector(context, addonRoot).project(clientRoot, safeMode = false)

        assertEquals(listOf("QuestHelper"), applied)
        assertFalse(runtimeRetired.exists())
        assertFalse(helper.exists())
        assertTrue(File(clientRoot, "Interface/AddOns/QuestHelper/QuestHelper.lua").isFile)
    }

    @Test fun unownedFolderCollisionFailsBeforeChangingClientTree() {
        val packageRoot = createPackage("QuestHelper")
        writeRegistry(listOf(installed("quest", "QuestHelper", packageRoot)))
        val collision = File(clientRoot, "Interface/AddOns/QuestHelper").apply { mkdirs() }
        val marker = File(collision, "local.txt").apply { writeText("keep") }

        assertThrows(IllegalArgumentException::class.java) {
            AddonRuntimeProjector(context, addonRoot).project(clientRoot, safeMode = false)
        }
        assertEquals("keep", marker.readText())
        assertFalse(File(clientRoot, "Interface/AddOns/.pocketrealm-managed.json").exists())
    }

    @Test fun builtInPackageProjectsCurrentApkAssetsInsteadOfPinnedCache() {
        val stale = createPackage(
            AndroidPortPackage.ADDON_FOLDER,
            "packages/${AndroidPortPackage.INSTALL_ID}/old",
        )
        File(stale, "${AndroidPortPackage.ADDON_FOLDER}/stale.txt").writeText("old")
        writeRegistry(listOf(installed(
            AndroidPortPackage.INSTALL_ID,
            AndroidPortPackage.ADDON_FOLDER,
            stale,
        )))

        val applied = AddonRuntimeProjector(context, addonRoot).project(clientRoot, safeMode = false)

        assertEquals(listOf(AndroidPortPackage.ADDON_FOLDER), applied)
        val projected = File(clientRoot, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}")
        assertTrue(File(projected, "Core.lua").readText().contains("AP.VERSION"))
        assertFalse(File(projected, "stale.txt").exists())
    }

    @Test fun directLaunchRetiresManagedRemotePrototypeWithoutOpeningAddonsScreen() {
        val old = createPackage("ConsoleExperienceClassic", "packages/pepordev__consoleexperienceclassic/old")
        writeRegistry(listOf(installed(
            "pepordev__consoleexperienceclassic",
            "ConsoleExperienceClassic",
            old,
        )))
        val runtime = File(clientRoot, "Interface/AddOns/ConsoleExperienceClassic").apply { mkdirs() }
        File(runtime, "old.lua").writeText("old=true")
        File(runtime.parentFile, ".pocketrealm-managed.json").writeText(
            JSONObject().put("schema", 1)
                .put("folders", JSONArray().put("ConsoleExperienceClassic")).toString(),
        )

        assertEquals(emptyList<String>(), AddonRuntimeProjector(context, addonRoot)
            .project(clientRoot, safeMode = false))
        assertFalse(runtime.exists())
    }

    @Test fun legacyRegistryIdentityMigratesAndOldOwnedFolderIsRetiredAtLaunch() {
        writeRegistry(listOf(installed(
            AndroidPortPackage.LEGACY_INSTALL_ID,
            AndroidPortPackage.LEGACY_ADDON_FOLDER,
            createPackage(AndroidPortPackage.LEGACY_ADDON_FOLDER, "packages/legacy/old"),
        ).put("repository", "builtin:addons/vanilla-console-port")))
        val runtimeLegacy = File(clientRoot, "Interface/AddOns/${AndroidPortPackage.LEGACY_ADDON_FOLDER}").apply { mkdirs() }
        File(runtimeLegacy, "old.lua").writeText("old=true")
        File(runtimeLegacy.parentFile, ".pocketrealm-managed.json").writeText(
            JSONObject().put("schema", 1)
                .put("folders", JSONArray().put(AndroidPortPackage.LEGACY_ADDON_FOLDER)).toString(),
        )
        val savedVariables = File(clientRoot, "WTF/Account/ACC/SavedVariables").apply { mkdirs() }
        File(savedVariables, "VanillaConsolePort.lua").writeText("VanillaConsolePortDB = { keep = true }")
        val bindings = File(clientRoot, "WTF/Account/ACC/bindings-cache.wtf").apply {
            parentFile.mkdirs(); writeText("bind 1 VCP_ACTION_1\n")
        }

        val applied = AddonRuntimeProjector(context, addonRoot).project(clientRoot, safeMode = false)

        assertEquals(listOf(AndroidPortPackage.ADDON_FOLDER), applied)
        assertFalse(runtimeLegacy.exists())
        assertTrue(File(clientRoot, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}/Core.lua").isFile)
        assertEquals("AndroidPortDB = { keep = true }", File(savedVariables, "AndroidPort.lua").readText())
        assertEquals("bind 1 AP_ACTION_1\n", bindings.readText())
    }

    @Test fun unownedManualControllerAddonAndBindingsAreNeverRetiredByName() {
        writeRegistry(emptyList())
        val manual = File(clientRoot, "Interface/AddOns/AndroidPort").apply { mkdirs() }
        File(manual, "manual.lua").writeText("manual=true")
        val bindings = File(clientRoot, "WTF/Account/TEST/bindings-cache.wtf").apply {
            parentFile!!.mkdirs(); writeText("bind 1 AP_ACTION_1\n")
        }

        assertEquals(emptyList<String>(), AddonRuntimeProjector(context, addonRoot)
            .project(clientRoot, safeMode = false))
        assertTrue(File(manual, "manual.lua").isFile)
        assertEquals("bind 1 AP_ACTION_1\n", bindings.readText())
    }

    @Test fun unownedManualLegacyNamedAddonKeepsItsConfigurationAndBindings() {
        writeRegistry(emptyList())
        val manual = File(clientRoot, "Interface/AddOns/PocketRealmPad").apply { mkdirs() }
        File(manual, "manual.lua").writeText("manual=true")
        val account = File(clientRoot, "WTF/Account/TEST").apply { mkdirs() }
        val bindings = File(account, "bindings-cache.wtf").apply { writeText("bind F8 PRP_CUSTOM\n") }
        val saved = File(account, "SavedVariables/PocketRealmPad.lua").apply {
            parentFile!!.mkdirs(); writeText("ManualPocketRealmPadDB = { keep = true }")
        }

        assertEquals(emptyList<String>(), AddonRuntimeProjector(context, addonRoot)
            .project(clientRoot, safeMode = false))
        assertTrue(File(manual, "manual.lua").isFile)
        assertEquals("bind F8 PRP_CUSTOM\n", bindings.readText())
        assertEquals("ManualPocketRealmPadDB = { keep = true }", saved.readText())
    }

    private fun createPackage(folder: String, relative: String = "packages/repository/commit"): File {
        val packageRoot = File(addonRoot, relative).apply { mkdirs() }
        val addon = File(packageRoot, folder).apply { mkdirs() }
        File(addon, "$folder.toc").writeText("## Interface: 11200\n$folder.lua\n")
        File(addon, "$folder.lua").writeText("local enabled = true\n")
        return packageRoot
    }

    private fun installed(id: String, folder: String, packageRoot: File): JSONObject = JSONObject()
        .put("id", id)
        .put("repository", "https://github.com/example/$id")
        .put("displayName", folder)
        .put("commitSha", "0".repeat(40))
        .put("archiveSha256", "a".repeat(64))
        .put("installedAtEpochMs", 1L)
        .put("packagePath", packageRoot.relativeTo(addonRoot).invariantSeparatorsPath)
        .put("folders", JSONArray().put(folder))

    private fun writeRegistry(installed: List<JSONObject>) {
        File(addonRoot, "registry.json").writeText(
            JSONObject().put("schema", 1).put("installed", JSONArray(installed)).toString(),
        )
    }
}
