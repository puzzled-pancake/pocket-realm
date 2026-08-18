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
    private val appContext = context.applicationContext
    private val root = rootOverride ?: File(appContext.noBackupFilesDir, "addons")
    private val registry = File(root, "registry.json")

    fun project(clientRoot: File, safeMode: Boolean): List<String> {
        // 0.6.0 renamed the built-in addon. Normalize the persisted device
        // data before any installed or journal state is read below: reading
        // first would mistake an unmigrated device for a removal and retire
        // the player's saved variables. Runs in safe mode too; every step is
        // best-effort so a corrupt file cannot block a launch.
        runCatching { AndroidPortMigrator.migrate(root, clientRoot) }
        val addons = File(clientRoot, "Interface/AddOns").apply { mkdirs() }
        val ownership = File(addons, OWNERSHIP_FILE)
        val previous = readOwned(ownership)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val androidPortWasManaged = previous.any {
            it.equals(AndroidPortPackage.ADDON_FOLDER, ignoreCase = true)
        }
        val previousKeys = previous.map { it.lowercase(Locale.ROOT) }.toSet()
        // A null source denotes the APK-owned built-in tree. It is copied
        // directly from current assets at every launch, so an app upgrade
        // cannot race an asynchronous package-cache refresh.
        val requested = linkedMapOf<String, Pair<String, File?>>()
        val installed = if (registry.isFile) {
            runCatching { readInstalled() }.getOrElse { failure ->
                if (safeMode) emptyList() else throw failure
            }
        } else emptyList()
        // The built-in semantic actions emit qualified balanced F6 target and
        // F9 auto-run surrogates, including when the retired controller add-on
        // was never installed. The repair is idempotent, preserves arbitrary
        // player bindings on either key, and retires legacy claims when found.
        LegacyControllerBindingRepair.repair(clientRoot)
        if (!safeMode) {
            installed.filterNot(::isRetiredProduct).forEach { addon ->
                addon.folders.forEach { folder ->
                    val source = if (addon.id == AndroidPortPackage.INSTALL_ID) {
                        require(folder == AndroidPortPackage.ADDON_FOLDER) {
                            "Built-in Android Port registry has an unexpected folder"
                        }
                        null
                    } else {
                        val packageRoot = safePackage(addon.packagePath)
                        safeChild(packageRoot, folder).also {
                            require(it.isDirectory) { "Installed addon folder is missing: $folder" }
                        }
                    }
                    val folderKey = folder.lowercase(Locale.ROOT)
                    require(requested.put(folderKey, folder to source) == null) {
                        "Two installed packages provide the same addon folder: $folder"
                    }
                }
            }
        }
        val androidPortInstalled = installed.any { it.id == AndroidPortPackage.INSTALL_ID }
        val desiredFolders = requested.values.map { it.first }

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
                val target = safeChild(stagingRoot, folder)
                if (source == null) {
                    copyBuiltInAssetTree("${AndroidPortPackage.ASSET_PATH}/$folder", target)
                } else {
                    copyTree(source, target)
                }
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
        // Binding mutation happens after folder publication is committed but
        // still before Wine launches. A collision or staging failure therefore
        // cannot leave stale binding provenance behind. If this step fails,
        // launch fails closed and retries against the committed folder state.
        if (!safeMode && androidPortInstalled) {
            AndroidPortBindingRepair.captureBeforeLaunch(clientRoot, File(root, AP_BINDING_JOURNAL))
        } else if (!androidPortInstalled &&
            (androidPortWasManaged || File(root, AP_BINDING_JOURNAL).isFile)) {
            AndroidPortBindingRepair.restoreAfterRemoval(clientRoot, File(root, AP_BINDING_JOURNAL))
        }
        return desiredFolders
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

    private fun isRetiredProduct(addon: InstalledAddon): Boolean =
        addon.id in RETIRED_PRODUCT_INSTALL_IDS || addon.folders.any(::isRetiredProductFolder)

    private fun isRetiredProductFolder(folder: String): Boolean =
        RETIRED_PRODUCT_FOLDERS.any { it.equals(folder, ignoreCase = true) }

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

    private fun copyBuiltInAssetTree(assetRoot: String, destination: File) {
        var files = 0
        var bytes = 0L
        fun copy(path: String, target: File) {
            val children = appContext.assets.list(path)?.sorted().orEmpty()
            if (children.isNotEmpty()) {
                check(target.mkdirs() || target.isDirectory) { "Built-in add-on directory could not be staged" }
                children.forEach { name ->
                    require(name.matches(Regex("[A-Za-z0-9_. -]+")) && name != "." && name != "..") {
                        "Built-in add-on contains an unsafe asset name"
                    }
                    copy("$path/$name", safeChild(target, name))
                }
                return
            }
            files += 1
            require(files <= MAX_BUILTIN_FILES) { "Built-in add-on contains too many files" }
            require(target.extension.lowercase(Locale.ROOT) in BUILTIN_ALLOWED_EXTENSIONS) {
                "Built-in add-on contains a forbidden file type: ${target.name}"
            }
            target.parentFile!!.mkdirs()
            appContext.assets.open(path).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_BUILTIN_BYTES) { "Built-in add-on is larger than supported" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        copy(assetRoot, destination)
        require(files > 0) { "Built-in Android Port assets are missing" }
        val toc = File(destination, "${AndroidPortPackage.ADDON_FOLDER}.toc")
        require(toc.isFile && Regex("""(?m)^## Interface:\s*11200\s*$""").containsMatchIn(toc.readText())) {
            "Built-in Android Port is not an Interface 11200 add-on"
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
        val RETIRED_PRODUCT_INSTALL_IDS = setOf(
            "bundled__pocketrealmpad",
            "pepordev__consoleexperienceclassic",
        )
        val RETIRED_PRODUCT_FOLDERS = setOf("PocketRealmPad", "PocketRealmPadLauncher")
        const val MAX_BUILTIN_FILES = 512
        const val MAX_BUILTIN_BYTES = 8L * 1024 * 1024
        val BUILTIN_ALLOWED_EXTENSIONS = setOf("lua", "toc", "xml", "md", "txt", "tga", "blp")
        const val AP_BINDING_JOURNAL = "android-port-bindings.json"
    }
}
