package com.pocketrealm.addons

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The desktop launch-boundary addon legs (DESK-11/40/83/84): the projector
 * twin's projection / safe-mode no-op / conflict refusal / toc fail-closed
 * pins, plus the shared pre-0.6.0 [AndroidPortMigrator] running over a real
 * client WTF tree through [DesktopAddonRuntimeProjector.project].
 */
class DesktopAddonRuntimeProjectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The real bundled asset tree, resolved the way the app resolves it. */
    private val bundledRoot: File by lazy {
        File("../android/app/src/main/assets/addons/android-port").canonicalFile
    }

    private fun clientRoot(): File = folder.newFolder("client")

    /** A registry root whose registry.json carries one entry: [ids] folders. */
    private fun registryRootWith(vararg folders: String, id: String = AndroidPortPackage.INSTALL_ID): File {
        val root = folder.newFolder("addons-root" + System.nanoTime())
        val registry = File(root, "registry.json")
        val installed = JSONArray().apply {
            folders.forEach { folderName ->
                put(JSONObject()
                    .put("id", id)
                    .put("repository", "builtin:${AndroidPortPackage.ASSET_PATH}")
                    .put("displayName", AndroidPortPackage.DISPLAY_NAME)
                    .put("commitSha", "a".repeat(40))
                    .put("archiveSha256", "b".repeat(64))
                    .put("installedAtEpochMs", 0L)
                    .put("packagePath", "packages/x/$id")
                    .put("folders", JSONArray(listOf(folderName))))
            }
        }
        registry.writeText(JSONObject().put("schema", 1).put("installed", installed).toString(2))
        // The strict projector path reads registry.json only; the package
        // presence check belongs to the repository reader, not the projector.
        return root
    }

    @Test
    fun `projects the bundled built-in add-on into the client Interface AddOns`() {
        val addonRoot = registryRootWith(AndroidPortPackage.ADDON_FOLDER)
        val client = clientRoot()
        val projected = DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)
        assertEquals(listOf(AndroidPortPackage.ADDON_FOLDER), projected)
        val addonFolder = File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}")
        assertTrue(addonFolder.isDirectory)
        val toc = File(addonFolder, "${AndroidPortPackage.ADDON_FOLDER}.toc")
        assertTrue(toc.isFile)
        assertTrue(toc.readText().contains("## Interface: 11200"))
        // The launch re-verifies the bundled toc through the copy itself.
        assertEquals(File(bundledRoot, AndroidPortPackage.ADDON_FOLDER).walkTopDown().count { it.isFile },
            addonFolder.walkTopDown().count { it.isFile })
        // Ownership is published for the next launch's replacement choreography.
        val ownership = File(client, "Interface/AddOns/.pocketrealm-managed.json")
        assertTrue(ownership.isFile)
        assertTrue(ownership.readText().contains(AndroidPortPackage.ADDON_FOLDER))
        // No staging or backup residue survives a clean projection (the
        // ownership file legitimately stays — it is asserted above).
        File(client, "Interface/AddOns").listFiles()!!.forEach {
            assertFalse(
                "residue: ${'$'}{it.name}",
                it.name.startsWith(".pocketrealm-stage-") || it.name.startsWith(".pocketrealm-backup-"),
            )
        }
    }

    @Test
    fun `input safe mode projects none and leaves no managed folders`() {
        val addonRoot = registryRootWith(AndroidPortPackage.ADDON_FOLDER)
        val client = clientRoot()
        val projected = DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = true)
        assertTrue(projected.isEmpty())
        assertFalse(File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}").exists())
        // Ownership still flips to empty: the managed set this launch is none.
        val ownership = File(client, "Interface/AddOns/.pocketrealm-managed.json")
        assertTrue(ownership.isFile)
        assertTrue(ownership.readText().contains("\"folders\": []"))
    }

    @Test
    fun `a client-owned folder with the managed name refuses the launch`() {
        val addonRoot = registryRootWith(AndroidPortPackage.ADDON_FOLDER)
        val client = clientRoot()
        val foreign = File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}")
        foreign.mkdirs()
        File(foreign, "Player.Toc").writeText("## Interface: 11200\n")
        try {
            DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)
            fail("projection must refuse an unowned client-owned addon folder")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                (expected.message ?: "").contains("conflicts with a client-owned addon"),
            )
        }
        // The refusal left the foreign folder untouched (staging cleaned up).
        assertTrue(File(foreign, "Player.Toc").isFile)
        File(client, "Interface/AddOns").listFiles()!!.forEach {
            assertFalse("residue: ${it.name}", it.name.startsWith(".pocketrealm-stage-"))
        }
    }

    @Test
    fun `a missing package directory fails closed at the projector's own folder check`() {
        // The registry is read through the shared AddonRegistryCodec's pure
        // decode (no filesystem coupling); package presence stays the
        // projector's own per-folder policy — the failure is its message.
        val addonRoot = registryRootWith("SomeAddon", id = "owner__someaddon")
        val client = clientRoot()
        try {
            DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)
            fail("projection must refuse an installed package that is unavailable")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                (expected.message ?: "").contains("Addon package is unavailable"),
            )
        }
        assertFalse(File(client, "Interface/AddOns/SomeAddon").exists())
    }

    @Test
    fun `input safe mode tolerates a registry whose package directories are missing`() {
        val addonRoot = registryRootWith("SomeAddon", id = "owner__someaddon")
        val client = clientRoot()
        assertTrue(
            DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = true).isEmpty(),
        )
    }

    @Test
    fun `a retired product row is inert at the launch boundary`() {
        // The retired-product table is the one shared DesktopAddonPolicy the
        // repository also filters writes through: such a row never reaches
        // the package checks, so even its absent package cannot block a launch.
        val addonRoot = registryRootWith("PocketRealmPad", id = "bundled__pocketrealmpad")
        val client = clientRoot()
        assertTrue(
            DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false).isEmpty(),
        )
        assertFalse(File(client, "Interface/AddOns/PocketRealmPad").exists())
    }

    @Test
    fun `a corrupt bundled toc fails closed before anything is published`() {
        val fakeBundled = folder.newFolder("fake-bundle")
        val addon = File(fakeBundled, AndroidPortPackage.ADDON_FOLDER)
        addon.mkdirs()
        File(addon, "${AndroidPortPackage.ADDON_FOLDER}.toc")
            .writeText("## Interface: 20500\n${AndroidPortPackage.ADDON_FOLDER}.lua\n")
        File(addon, "${AndroidPortPackage.ADDON_FOLDER}.lua").writeText("print('wrong flavor')\n")
        val addonRoot = registryRootWith(AndroidPortPackage.ADDON_FOLDER)
        val client = clientRoot()
        try {
            DesktopAddonRuntimeProjector(addonRoot, builtinAssetRoot = fakeBundled)
                .project(client, safeMode = false)
            fail("projection must refuse a bundled add-on that is not Interface 11200")
        } catch (expected: IllegalArgumentException) {
            assertTrue((expected.message ?: "").contains("not an Interface 11200 add-on"))
        }
        assertFalse(File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}").exists())
    }

    @Test
    fun `projection runs the pre-0_6_0 migration over the client WTF tree`() {
        // A legacy device: registry still on the VanillaConsolePort identity,
        // saved variables and binding caches in the legacy spellings.
        val addonRoot = registryRootWith(
            AndroidPortPackage.LEGACY_ADDON_FOLDER,
            id = AndroidPortPackage.LEGACY_INSTALL_ID,
        )
        val client = clientRoot()
        val saved = File(client, "WTF/Account/ACCT1/SavedVariables")
        saved.mkdirs()
        File(saved, "VanillaConsolePort.lua").writeText(
            "VanillaConsolePortCharacterDB = {\n\t[\"pos\"] = 1,\n}\nVanillaConsolePortDB = {\n}\n",
        )
        val bindings = File(client, "WTF/Account/ACCT1/bindings-cache.wtf")
        bindings.writeText(
            "bind F7 VCP_NEARBY_INTERACT\r\nbind F6 TARGETNEARESTENEMY\r\n",
        )

        DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)

        // The WTF leg: saved variables copied under the new name with the
        // top-level tokens rewritten (sources stay in place), and the legacy
        // binding commands rewritten to the AP_ spelling.
        val migrated = File(saved, "AndroidPort.lua")
        assertTrue(migrated.isFile)
        assertTrue(migrated.readText().contains("AndroidPortCharacterDB ="))
        assertTrue(migrated.readText().contains("AndroidPortDB ="))
        assertFalse(migrated.readText().contains("VanillaConsolePortCharacterDB"))
        // The legacy source stays in place (the migration copies, not moves).
        assertTrue(File(saved, "VanillaConsolePort.lua").isFile)
        val rewritten = bindings.readText()
        assertTrue(rewritten.contains("bind F7 AP_NEARBY_INTERACT"))
        assertFalse(rewritten.contains("VCP_NEARBY_INTERACT"))
        assertTrue(rewritten.contains("bind F6 TARGETNEARESTENEMY"))
        // The add-on itself was still projected (the legacy install remaps to
        // the current id inside the migration).
        assertTrue(File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}").isDirectory)
    }

    @Test
    fun `removal retires the managed folder and restores journaled bindings`() {
        // First launch projects and captures the binding journal.
        val addonRoot = registryRootWith(AndroidPortPackage.ADDON_FOLDER)
        val client = clientRoot()
        val bindings = File(client, "WTF/Account/ACCT1/bindings-cache.wtf")
        bindings.parentFile.mkdirs()
        bindings.writeText("bind F7 AP_NEARBY_INTERACT\nbind F6 TARGETNEARESTENEMY\n")
        DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)
        val journal = File(addonRoot, "android-port-bindings.json")
        assertTrue(journal.isFile)

        // The registry then drops the add-on (a deliberate removal leaves a
        // registry with an empty installed set): the next launch retires the
        // folder and retires the binding journal.
        val removed = JSONObject()
            .put("schema", 1)
            .put("installed", JSONArray())
        File(addonRoot, "registry.json").writeText(removed.toString(2))
        DesktopAddonRuntimeProjector(addonRoot).project(client, safeMode = false)
        assertFalse(File(client, "Interface/AddOns/${AndroidPortPackage.ADDON_FOLDER}").exists())
        assertFalse("journal must be retired on removal", journal.isFile)
        assertTrue("the player's own F6 surrogate stays", bindings.readText().contains("bind F6 TARGETNEARESTENEMY"))
        val ownership = File(client, "Interface/AddOns/.pocketrealm-managed.json")
        assertFalse(ownership.readText().contains(AndroidPortPackage.ADDON_FOLDER))
    }
}
