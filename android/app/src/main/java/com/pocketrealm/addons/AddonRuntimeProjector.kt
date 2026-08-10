package com.pocketrealm.addons

import android.content.Context
import android.system.Os
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
) {
    private val root = rootOverride ?: File(context.applicationContext.noBackupFilesDir, "addons")
    private val registry = File(root, "registry.json")

    fun project(clientRoot: File, safeMode: Boolean): List<String> {
        val addons = File(clientRoot, "Interface/AddOns").apply { mkdirs() }
        val ownership = File(addons, OWNERSHIP_FILE)
        val previous = readOwned(ownership).distinctBy { it.lowercase(Locale.ROOT) }
        val previousKeys = previous.map { it.lowercase(Locale.ROOT) }.toSet()
        val requested = linkedMapOf<String, Pair<String, File>>()
        if (!safeMode && registry.isFile) {
            readInstalled().forEach { addon ->
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

        requested.values.forEach { (folder, _) ->
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
            requested.values.forEach { (folder, _) ->
                val staged = safeChild(stagingRoot, folder)
                val target = safeChild(addons, folder)
                check(staged.renameTo(target)) { "Addon could not be activated: $folder" }
                published += target
            }
            val applied = requested.values.map { it.first }
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

    private companion object { const val OWNERSHIP_FILE = ".pocketrealm-managed.json" }
}
