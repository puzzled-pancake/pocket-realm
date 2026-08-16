package com.pocketrealm.addons

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time, idempotent migration of persisted device data from the pre-0.6.0
 * VanillaConsolePort identity to Android Port. Runs at two boundaries:
 * [AddonRepository] construction (registry + binding journal, after publisher
 * recovery and before any installed state is read) and the top of
 * [AddonRuntimeProjector.project] (everything, before the installed/journal
 * evaluation that would otherwise mistake an unmigrated device for a removal
 * and delete the player's saved variables).
 *
 * Every step is best-effort: a missing or corrupt file skips that step so a
 * bad file can never block a launch (safe mode keeps its corrupt-registry
 * immunity), and all writes are atomic. The registry remap completes before
 * the journal move; the intermediate state {journal moved, registry still on
 * the legacy id} would make the next launch retire a still-installed addon.
 */
internal object AndroidPortMigrator {
    private const val LEGACY_JOURNAL = "vanilla-console-port-bindings.json"
    private const val JOURNAL = "android-port-bindings.json"
    private const val OWNERSHIP_FILE = ".pocketrealm-managed.json"
    private const val MAX_TREE_ENTRIES = 10_000
    private const val MAX_FILE_BYTES = 1024L * 1024L

    private val legacyBindingCommands = buildSet {
        (1..40).forEach { action -> add("VCP_ACTION_$action") }
        add("VCP_TOGGLE_RADIAL")
        add("VCP_MOVE_UI")
        add("VCP_NEARBY_INTERACT")
    }

    /** [clientRoot] is null outside the launch boundary (repository init). */
    fun migrate(root: File, clientRoot: File?) {
        val legacySeen = remapRegistries(root)
        val journalMoved = moveJournal(root)
        if (clientRoot == null) return
        // Never touch the WTF of an unowned manual VanillaConsolePort
        // install: without prior ownership evidence its saved variables and
        // binding table belong to that manual addon, not to this app.
        val ownedLegacyInstall = legacySeen || journalMoved ||
            File(root, JOURNAL).isFile || registriesHaveCurrentId(root) ||
            ownershipListsLegacyFolder(clientRoot)
        if (!ownedLegacyInstall) return
        migrateSavedVariables(clientRoot)
        migrateBindingCaches(clientRoot)
    }

    /** Returns true when a legacy-id entry was present in either registry. */
    private fun remapRegistries(root: File): Boolean {
        var legacySeen = false
        for (name in listOf("registry.json", "registry.previous.json")) {
            val file = File(root, name)
            if (!file.isFile) continue
            val rewritten = runCatching { remapRegistry(file) }.getOrNull() ?: continue
            legacySeen = legacySeen || rewritten
        }
        return legacySeen
    }

    private fun remapRegistry(file: File): Boolean {
        val rootJson = JSONObject(file.readText(Charsets.UTF_8))
        if (rootJson.optInt("schema") != 1) return false
        val installed = rootJson.optJSONArray("installed") ?: return false
        var legacySeen = false
        var hasCurrent = false
        repeat(installed.length()) { index ->
            val item = installed.optJSONObject(index) ?: return@repeat
            when (item.optString("id")) {
                AndroidPortPackage.LEGACY_INSTALL_ID -> legacySeen = true
                AndroidPortPackage.INSTALL_ID -> hasCurrent = true
            }
        }
        if (legacySeen && !hasCurrent) {
            repeat(installed.length()) { index ->
                val item = installed.optJSONObject(index) ?: return@repeat
                if (item.optString("id") != AndroidPortPackage.LEGACY_INSTALL_ID) return@repeat
                item.put("id", AndroidPortPackage.INSTALL_ID)
                item.put("repository", "builtin:${AndroidPortPackage.ASSET_PATH}")
                val folders = item.optJSONArray("folders") ?: JSONArray()
                repeat(folders.length()) { position ->
                    if (folders.optString(position).equals(AndroidPortPackage.LEGACY_ADDON_FOLDER, ignoreCase = true)) {
                        folders.put(position, AndroidPortPackage.ADDON_FOLDER)
                    }
                }
                // packagePath keeps pointing at the 0.5.x package directory
                // until the digest refresh republishes; loadRegistryStrict
                // only requires the directory to exist.
            }
            writeAtomic(file, rootJson.toString(2))
        }
        return legacySeen
    }

    /** Returns true when the legacy journal existed and was moved. */
    private fun moveJournal(root: File): Boolean {
        val legacy = File(root, LEGACY_JOURNAL)
        val current = File(root, JOURNAL)
        if (!legacy.isFile || current.isFile) return false
        return runCatching {
            try {
                Files.move(legacy.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(legacy.toPath(), current.toPath())
            }
            true
        }.recoverCatching { legacy.renameTo(current) }.getOrDefault(false)
    }

    private fun registriesHaveCurrentId(root: File): Boolean =
        listOf("registry.json", "registry.previous.json").any { name ->
            val file = File(root, name)
            file.isFile && runCatching {
                val installed = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("installed")
                    ?: return@runCatching false
                (0 until installed.length()).any { index ->
                    installed.optJSONObject(index)?.optString("id") == AndroidPortPackage.INSTALL_ID
                }
            }.getOrDefault(false)
        }

    private fun ownershipListsLegacyFolder(clientRoot: File): Boolean = runCatching {
        val file = File(File(clientRoot, "Interface/AddOns"), OWNERSHIP_FILE)
        val folders = if (file.isFile) {
            JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("folders")
        } else null
        if (folders == null) {
            false
        } else {
            (0 until folders.length()).any { index ->
                folders.optString(index).equals(AndroidPortPackage.LEGACY_ADDON_FOLDER, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    /**
     * Vanilla 1.12 stores addon saved variables per character under
     * `WTF/Account/<account>/<realm>/<character>/SavedVariables/<Addon>.lua`.
     * Each legacy file is copied beside itself under the new addon name with
     * its top-level assignment tokens rewritten; the source stays in place.
     */
    private fun migrateSavedVariables(clientRoot: File) {
        forEachWtfFile(clientRoot, fileName = "VanillaConsolePort.lua") { file ->
            if (file.parentFile?.name != "SavedVariables") return@forEachWtfFile
            val target = File(file.parentFile, "AndroidPort.lua")
            if (target.isFile) return@forEachWtfFile
            val content = file.readText(Charsets.UTF_8)
            writeAtomic(target, rewriteSavedVariableTokens(content))
        }
    }

    private fun rewriteSavedVariableTokens(content: String): String = content
        .replace("VanillaConsolePortCharacterDB =", "AndroidPortCharacterDB =")
        .replace("VanillaConsolePortDB =", "AndroidPortDB =")

    /**
     * Rewrites `bind KEY COMMAND` lines whose command is a legacy VCP_ action
     * to the AP_ spelling, preserving each line's own terminator (including
     * the on-device \r\r\n form). Files without legacy commands round-trip
     * byte-identically and are not rewritten.
     */
    private fun migrateBindingCaches(clientRoot: File) {
        forEachWtfFile(clientRoot, fileName = "bindings-cache.wtf") { file ->
            val original = file.readText(Charsets.UTF_8)
            val rewritten = rewriteBindingCommands(original) ?: return@forEachWtfFile
            writeAtomic(file, rewritten)
        }
    }

    private fun rewriteBindingCommands(text: String): String? {
        var changed = false
        val rebuilt = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val terminator = terminatorAt(text, index)
            if (terminator == null) {
                val tail = text.substring(index)
                changed = changed or appendRewritten(tail, rebuilt)
                break
            }
            changed = changed or appendRewritten(text.substring(index, terminator.first), rebuilt)
            rebuilt.append(text, terminator.first, terminator.first + terminator.second.length)
            index = terminator.first + terminator.second.length
        }
        return if (changed) rebuilt.toString() else null
    }

    private fun appendRewritten(body: String, out: StringBuilder): Boolean {
        val match = BIND_LINE.matchEntire(body) ?: run {
            out.append(body)
            return false
        }
        val command = match.groupValues[2].trim().uppercase(Locale.ROOT)
        if (command !in legacyBindingCommands) {
            out.append(body)
            return false
        }
        // Emission mirrors BindingsFileCodec.assign: unquoted bind line.
        out.append("bind ").append(match.groupValues[1].uppercase(Locale.ROOT))
            .append(' ').append("AP_").append(command.removePrefix("VCP_"))
        return true
    }

    private fun terminatorAt(text: String, from: Int): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        listOf("\r\r\n", "\r\n", "\n").forEach { token ->
            val at = text.indexOf(token, from)
            if (at >= 0 && (best == null || at < best!!.first)) best = at to token
        }
        // A lone \r terminates only when not part of \r\n or \r\r\n.
        var at = text.indexOf('\r', from)
        while (at >= 0) {
            val next = text.getOrNull(at + 1)
            if (next != '\r' && next != '\n') {
                if (best == null || at < best!!.first) best = at to "\r"
                break
            }
            at = text.indexOf('\r', at + 1)
        }
        return best
    }

    private fun forEachWtfFile(clientRoot: File, fileName: String, action: (File) -> Unit) {
        val wtf = File(clientRoot, "WTF")
        if (!wtf.isDirectory || Files.isSymbolicLink(wtf.toPath())) return
        val paths = mutableListOf<java.nio.file.Path>()
        val walked = runCatching {
            Files.walk(wtf.toPath(), 8).use { stream -> stream.forEach(paths::add) }
        }.isSuccess
        if (!walked || paths.size > MAX_TREE_ENTRIES) return
        paths.forEach { path ->
            val file = path.toFile()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
            if (!file.name.equals(fileName, ignoreCase = true)) return@forEach
            if (file.length() > MAX_FILE_BYTES) return@forEach
            runCatching { action(file) }
        }
    }

    private fun writeAtomic(file: File, content: String) = com.pocketrealm.fs.DurableFiles.atomicWrite(file, content)

    private val BIND_LINE = Regex(
        """^\s*bind\s+"?([^"\s]+)"?\s+"?([^"\r\n]+?)"?\s*$""",
        RegexOption.IGNORE_CASE,
    )
}
