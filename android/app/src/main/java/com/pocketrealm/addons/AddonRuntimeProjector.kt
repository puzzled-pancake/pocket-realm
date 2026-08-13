package com.pocketrealm.addons

import android.content.Context
import android.system.Os
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** Applies only project-owned addon folders at a launch boundary. */
class AddonRuntimeProjector(
    context: Context,
    rootOverride: File? = null,
    private val profileOverride: InputProfile? = null,
) {
    private val appContext = context.applicationContext
    private val root = rootOverride ?: File(context.applicationContext.noBackupFilesDir, "addons")
    private val registry = File(root, "registry.json")

    fun project(clientRoot: File, safeMode: Boolean): List<String> {
        val addons = File(clientRoot, "Interface/AddOns").apply { mkdirs() }
        val ownership = File(addons, OWNERSHIP_FILE)
        val previous = readOwned(ownership).distinctBy { it.lowercase(Locale.ROOT) }
        val previousKeys = previous.map { it.lowercase(Locale.ROOT) }.toSet()
        val requested = linkedMapOf<String, Pair<String, File>>()
        val installed = if (registry.isFile) {
            runCatching { readInstalled() }.getOrElse { failure ->
                if (safeMode) emptyList() else throw failure
            }
        } else emptyList()
        val hasPocketRealmPad = installed.any { addon ->
            addon.folders.any { it.equals(POCKET_REALM_PAD_FOLDER, ignoreCase = true) }
        }
        if (!safeMode) {
            installed.forEach { addon ->
                val packageRoot = safePackage(addon.packagePath)
                addon.folders.forEach { folder ->
                    val source = safeChild(packageRoot, folder)
                    require(source.isDirectory) { "Installed addon folder is missing: $folder" }
                    require(requested.put(folder.lowercase(Locale.ROOT), folder to source) == null) {
                        "Two installed packages provide the same addon folder: $folder"
                    }
                }
            }
        }
        // Once projected, keep the tiny disabled helper managed even after PRP
        // is removed. It must run once (and may harmlessly remain thereafter)
        // to restore the user's previous bindings from its SavedVariables.
        val keepRestorationHelper = hasPocketRealmPad ||
            LAUNCHER_FOLDER.lowercase(Locale.ROOT) in previousKeys
        val generatedFolders = if (keepRestorationHelper) listOf(LAUNCHER_FOLDER) else emptyList()
        require(generatedFolders.none { requested.containsKey(it.lowercase(Locale.ROOT)) }) {
            "Installed addon conflicts with the launcher-managed PocketRealmPad helper"
        }
        val desiredFolders = requested.values.map { it.first } + generatedFolders

        desiredFolders.forEach { folder ->
            val target = safeChild(addons, folder)
            require(!target.exists() || folder.lowercase(Locale.ROOT) in previousKeys) {
                "Addon '$folder' conflicts with a client-owned addon; remove or rename it before enabling this package"
            }
        }

        val token = System.nanoTime()
        val stagingRoot = safeChild(addons, ".pocketrealm-stage-$token")
        val backupRoot = safeChild(addons, ".pocketrealm-backup-$token")
        val movedPrevious = mutableListOf<Pair<File, File>>()
        val published = mutableListOf<File>()
        try {
            check(stagingRoot.mkdirs()) { "Addon staging directory could not be created" }
            requested.values.forEach { (folder, source) ->
                copyTree(source, safeChild(stagingRoot, folder))
            }
            if (keepRestorationHelper) {
                writePocketRealmPadLauncher(
                    safeChild(stagingRoot, LAUNCHER_FOLDER),
                    safeMode || !hasPocketRealmPad,
                )
            }
            check(backupRoot.mkdirs()) { "Addon rollback directory could not be created" }
            previous.forEach { folder ->
                val target = safeChild(addons, folder)
                if (target.exists()) {
                    val backup = safeChild(backupRoot, folder)
                    backup.parentFile!!.mkdirs()
                    check(target.renameTo(backup)) { "Managed addon could not be prepared for replacement: $folder" }
                    movedPrevious += target to backup
                }
            }
            desiredFolders.forEach { folder ->
                val staged = safeChild(stagingRoot, folder)
                val target = safeChild(addons, folder)
                check(staged.renameTo(target)) { "Addon could not be activated: $folder" }
                published += target
            }
            val applied = desiredFolders
            writeOwned(ownership, applied)
            backupRoot.deleteRecursively()
            return applied
        } catch (failure: Throwable) {
            published.asReversed().forEach { it.deleteRecursively() }
            var restoreFailed = false
            movedPrevious.asReversed().forEach { (target, backup) ->
                target.parentFile!!.mkdirs()
                if (!backup.renameTo(target)) restoreFailed = true
            }
            if (restoreFailed) {
                throw IllegalStateException(
                    "Addon activation failed and its rollback is preserved at ${backupRoot.absolutePath}",
                    failure,
                )
            }
            backupRoot.deleteRecursively()
            throw failure
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun readInstalled(): List<InstalledAddon> {
        val json = JSONObject(registry.readText())
        require(json.getInt("schema") == 1) { "Unsupported addon registry" }
        val values = json.getJSONArray("installed")
        return List(values.length()) { index ->
            val item = values.getJSONObject(index)
            InstalledAddon(
                id = item.getString("id"),
                repository = item.getString("repository"),
                displayName = item.getString("displayName"),
                commitSha = item.getString("commitSha"),
                archiveSha256 = item.getString("archiveSha256"),
                installedAtEpochMs = item.getLong("installedAtEpochMs"),
                packagePath = item.getString("packagePath"),
                folders = item.getJSONArray("folders").let { folders ->
                    List(folders.length()) { folders.getString(it) }
                },
            )
        }
    }

    private fun readOwned(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        val json = JSONObject(file.readText())
        val folders = json.getJSONArray("folders")
        List(folders.length()) { folders.getString(it) }
    }.getOrElse { emptyList() }

    private fun writeOwned(file: File, folders: List<String>) {
        val content = JSONObject().put("schema", 1).put("folders", JSONArray(folders)).toString(2)
        val temp = File(file.parentFile, ".$OWNERSHIP_FILE.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.rename(temp.absolutePath, file.absolutePath)
    }

    /**
     * Projects a tiny app-owned companion next to PocketRealmPad. It changes
     * bindings only after the user selects a PocketRealmPad control profile,
     * remembers the prior WoW binding for every claimed key, and restores those
     * bindings when the profile or safe mode disables integration.
     */
    private fun writePocketRealmPadLauncher(destination: File, safeMode: Boolean) {
        check(destination.mkdirs()) { "PocketRealmPad launcher directory could not be created" }
        val profile = profileOverride ?: InputProfileStore(appContext)
            .load(InputProfile.DEFAULT_ASPECT_IDENTITY).profile
        val enabled = !safeMode && profile.usesPocketRealmPadCommands()
        writeSynced(
            File(destination, "$LAUNCHER_FOLDER.toc"),
            """## Interface: 11200
## Title: Pocket Realm - PocketRealmPad bindings
## Notes: Applies the control profile selected in the Pocket Realm launcher.
## OptionalDeps: PocketRealmPad
## SavedVariables: PocketRealmPadLauncherDB
$LAUNCHER_FOLDER.lua
""",
        )
        writeSynced(File(destination, "$LAUNCHER_FOLDER.lua"), launcherLua(enabled))
    }

    private fun launcherLua(enabled: Boolean): String {
        val active = if (enabled) "true" else "false"
        return """local wanted = $active
local stamp = "pocketrealm-pad-v1-" .. (wanted and "on" or "off")
local keys = {
  ["1"]="PRP_ACTION1", ["2"]="PRP_ACTION2", ["3"]="PRP_ACTION3", ["4"]="PRP_ACTION4",
  ["5"]="PRP_ACTION5", ["6"]="PRP_ACTION6", ["7"]="PRP_ACTION7", ["8"]="PRP_ACTION8",
  ["F8"]="PRP_MOD_BANK", ["F9"]="PRP_MOD_SHIFT", ["F10"]="PRP_MOD_CTRL",
  ["UP"]="PRP_NAV_UP", ["DOWN"]="PRP_NAV_DOWN", ["LEFT"]="PRP_NAV_LEFT", ["RIGHT"]="PRP_NAV_RIGHT",
  ["F7"]="PRP_QUICK_MENU", ["B"]="PRP_INVENTORY", ["TAB"]="PRP_TARGET_NEXT_ENEMY"
}
local frame = CreateFrame("Frame")
frame:RegisterEvent("PLAYER_LOGIN")
frame:SetScript("OnEvent", function()
  if type(SetBinding) ~= "function" or type(SaveBindings) ~= "function" then return end
  if type(PocketRealmPadLauncherDB) ~= "table" then PocketRealmPadLauncherDB = {} end
  local db = PocketRealmPadLauncherDB
  if db.stamp == stamp then return end
  if type(db.previous) ~= "table" then db.previous = {} end
  if wanted then
    if not db.active then
      for key, command in pairs(keys) do
        local prior = GetBindingAction and GetBindingAction(key) or ""
        db.previous[key] = prior ~= "" and prior or false
      end
    end
    for key, command in pairs(keys) do SetBinding(key, command) end
    db.active = true
  elseif db.active then
    for key, command in pairs(keys) do
      local prior = db.previous[key]
      if prior and prior ~= "" then SetBinding(key, prior) else SetBinding(key) end
    end
    db.active = false
  end
  SaveBindings((GetCurrentBindingSet and GetCurrentBindingSet()) or 1)
  db.stamp = stamp
end)
"""
    }

    private fun InputProfile.usesPocketRealmPadCommands(): Boolean =
        scheme == ControlScheme.POCKET_REALM_PAD ||
            scheme == ControlScheme.POCKET_REALM_PAD_CAMERA ||
            rp6Bindings.values.any { it in POCKET_REALM_PAD_ACTIONS } ||
            overlayBindings.values.any { it in POCKET_REALM_PAD_ACTIONS }

    private fun writeSynced(file: File, content: String) {
        FileOutputStream(file).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun copyTree(source: File, destination: File) {
        require(!destination.exists()) { "Addon staging collision" }
        source.walkTopDown().forEach { item ->
            require(!Files.isSymbolicLink(item.toPath())) { "Installed addon contains a symbolic link" }
            val relative = item.relativeTo(source).path
            val target = if (relative.isEmpty()) destination else safeChild(destination, relative)
            if (item.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "Addon directory could not be created" }
            } else {
                target.parentFile!!.mkdirs()
                item.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                }
            }
        }
    }

    private fun safePackage(relative: String): File {
        require(!relative.startsWith('/') && ".." !in relative.split('/')) { "Unsafe addon package path" }
        val result = File(root, relative)
        require(result.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Unsafe addon package path" }
        require(Files.exists(result.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(result.toPath())) {
            "Addon package is unavailable"
        }
        return result
    }

    private fun safeChild(parent: File, name: String): File {
        require(name.isNotBlank() && !name.startsWith('/') && ".." !in name.replace('\\', '/').split('/')) {
            "Unsafe addon folder"
        }
        val child = File(parent, name)
        require(child.canonicalPath.startsWith(parent.canonicalPath + File.separator)) { "Unsafe addon folder" }
        return child
    }

    private companion object {
        const val OWNERSHIP_FILE = ".pocketrealm-managed.json"
        const val POCKET_REALM_PAD_FOLDER = "PocketRealmPad"
        const val LAUNCHER_FOLDER = "PocketRealmPadLauncher"
        val POCKET_REALM_PAD_ACTIONS = setOf(
            ControllerAction.PRP_BANK,
            ControllerAction.PRP_LAYER_2,
            ControllerAction.PRP_LAYER_3,
            ControllerAction.NAV_UP,
            ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT,
            ControllerAction.INVENTORY,
            ControllerAction.RADIAL_MENU,
        )
    }
}
