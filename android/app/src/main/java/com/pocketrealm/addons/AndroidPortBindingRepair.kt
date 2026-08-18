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
 * Removes only bindings still owned by Android Port after the optional
 * package is removed. A host-side journal is fsynced before WoW can load the
 * add-on, so a forced stop before SavedVariables flush cannot lose provenance.
 * Any binding the player changed after installation is deliberately preserved.
 */
internal object AndroidPortBindingRepair {
    private const val MAX_TREE_ENTRIES = 10_000
    private const val MAX_BINDING_FILES = 128
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private val bindingLine = Regex(
        """^\s*bind\s+"?([^"\s]+)"?\s+"?([^"\r\n]+)"?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val backupEntry = Regex(
        """\["((?:(?:CTRL-)?SHIFT-|CTRL-)?(?:[0-9]|F7|F8|F9|F12))"\]\s*=\s*"([A-Z0-9_]*)"\s*,""",
        RegexOption.IGNORE_CASE,
    )
    private val owned = buildMap<String, Set<String>> {
        val oldKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val modifiers = listOf("", "SHIFT-", "CTRL-", "CTRL-SHIFT-")
        modifiers.forEachIndexed { page, modifier ->
            oldKeys.forEachIndexed { index, key ->
                val commands = linkedSetOf("VCP_ACTION_${page * 10 + index + 1}")
                // Schema 2 keeps only the eight reachable face/D-pad buttons.
                // Accept both identities so update, crash recovery and removal
                // can retire an installation made by either schema exactly.
                if (index < 8) commands += "VCP_ACTION_${page * 8 + index + 1}"
                // 0.6.0 renamed the binding actions from VCP_ to AP_; both
                // spellings stay owned so the transition retires either one.
                commands += "AP_ACTION_${page * 10 + index + 1}"
                if (index < 8) commands += "AP_ACTION_${page * 8 + index + 1}"
                put("$modifier$key", commands)
            }
        }
        put("F12", setOf("VCP_TOGGLE_RADIAL", "AP_TOGGLE_RADIAL"))
        put("F8", setOf("VCP_MOVE_UI", "AP_MOVE_UI"))
        put("F9", setOf("TOGGLEAUTORUN"))
        put("F7", setOf("VCP_NEARBY_INTERACT", "AP_NEARBY_INTERACT"))
    }

    /**
     * The key chords this repair owns on behalf of the controller overlay.
     * The binding editor's reserved-key set is defined to equal this set
     * plus the legacy F6/F9 surrogates (asserted by unit test).
     */
    val ownedKeys: Set<String> get() = owned.keys

    fun captureBeforeLaunch(clientRoot: File, journal: File) {
        val (wtf, bindingFiles) = bindingFiles(clientRoot) ?: return
        val scopes = linkedMapOf<String, Map<String, String>>()
        if (journal.isFile) {
            require(journal.length() in 0..MAX_FILE_BYTES) { "Android Port host journal is too large" }
            val root = JSONObject(journal.readText(Charsets.UTF_8))
            require(root.getInt("schema") == 1) { "Unsupported Android Port host journal" }
            val array = root.getJSONArray("scopes")
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                scopes[item.getString("path")] = parseJsonBackup(item.getJSONObject("previous"))
            }
        }
        bindingFiles.forEach { path ->
            val relative = path.toFile().relativeTo(wtf).invariantSeparatorsPath
            val current = parseCurrent(path.toFile())
            val saved = savedVariableFile(path.parent.toFile())
                ?.let(::parseBackup)
                .orEmpty()
            val previous = scopes[relative].orEmpty().toMutableMap()
            owned.forEach { (key, expected) ->
                if (key !in previous) {
                    val active = current[key]
                    previous[key] = when {
                        active == null -> saved[key].orEmpty()
                        active !in expected -> active
                        key in saved -> saved.getValue(key)
                        // An already-expected command without provenance is
                        // ambiguous. Preserve it rather than deleting a
                        // legitimate pre-existing binding on removal (notably
                        // stock TOGGLEAUTORUN on F9).
                        else -> active
                    }
                }
            }
            scopes[relative] = previous
        }
        val json = JSONObject().put("schema", 1).put("scopes", JSONArray().apply {
            scopes.toSortedMap().forEach { (path, previous) ->
                put(JSONObject().put("path", path).put("previous", JSONObject(previous)))
            }
        }).toString(2)
        writeAtomic(journal, json)
    }

    fun restoreAfterRemoval(clientRoot: File, journal: File): Int {
        val discovered = bindingFiles(clientRoot) ?: return 0
        val wtf = discovered.first
        val bindingFiles = discovered.second
        val durable = linkedMapOf<String, Map<String, String>>()
        if (journal.isFile) {
            require(journal.length() in 0..MAX_FILE_BYTES) { "Android Port host journal is too large" }
            val root = JSONObject(journal.readText(Charsets.UTF_8))
            require(root.getInt("schema") == 1) { "Unsupported Android Port host journal" }
            val array = root.getJSONArray("scopes")
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                durable[item.getString("path")] = parseJsonBackup(item.getJSONObject("previous"))
            }
        }
        var changed = 0
        bindingFiles.forEach { path ->
            val scope = path.parent.toFile()
            val relative = path.toFile().relativeTo(wtf).invariantSeparatorsPath
            val durableBackup = durable[relative].orEmpty()
            val savedBackup = savedVariableFile(scope)
                ?.let(::parseBackup).orEmpty()
            val backup = owned.keys.associateWith { key ->
                durableBackup[key] ?: savedBackup[key].orEmpty()
            }
            if (restoreFile(path.toFile(), backup)) changed += 1
            // Retirement removes the saved-variables backups under both the
            // current and the pre-0.6.0 addon names.
            listOf("AndroidPort.lua", "VanillaConsolePort.lua").forEach { name ->
                val saved = File(scope, "SavedVariables/$name")
                if (saved.isFile) {
                    check(saved.delete()) { "Android Port binding journal could not be retired" }
                    File(saved.parentFile, "$name.bak").delete()
                }
            }
        }
        if (journal.isFile) check(journal.delete()) { "Android Port host journal could not be retired" }
        return changed
    }

    /** Saved-variables backup under the 0.6.0 name, else the pre-rename one. */
    private fun savedVariableFile(scope: File): File? =
        listOf("AndroidPort.lua", "VanillaConsolePort.lua")
            .map { File(scope, "SavedVariables/$it") }
            .firstOrNull { it.isFile && !Files.isSymbolicLink(it.toPath()) }

    private fun bindingFiles(clientRoot: File): Pair<File, List<java.nio.file.Path>>? {
        val wtf = File(clientRoot, "WTF")
        if (!wtf.isDirectory || Files.isSymbolicLink(wtf.toPath())) return null
        val paths = mutableListOf<java.nio.file.Path>()
        Files.walk(wtf.toPath(), 8).use { stream -> stream.forEach(paths::add) }
        require(paths.size <= MAX_TREE_ENTRIES) { "WoW settings tree is unexpectedly large" }
        val bindingFiles = paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.fileName.toString().equals("bindings-cache.wtf", ignoreCase = true)
        }
        require(bindingFiles.size <= MAX_BINDING_FILES) { "too many WoW binding-cache files" }
        return wtf to bindingFiles
    }

    private fun parseJsonBackup(value: JSONObject): Map<String, String> = buildMap {
        value.keys().forEach { key ->
            val normalized = key.uppercase(Locale.ROOT)
            if (normalized in owned) put(normalized, value.getString(key).uppercase(Locale.ROOT))
        }
    }

    private fun parseCurrent(file: File): Map<String, String> {
        require(file.length() in 0..MAX_FILE_BYTES) { "WoW binding cache is unexpectedly large" }
        return buildMap {
            file.useLines(Charsets.UTF_8) { lines -> lines.forEach { line ->
                bindingLine.matchEntire(line)?.let { match ->
                    put(
                        match.groupValues[1].uppercase(Locale.ROOT),
                        match.groupValues[2].trim().uppercase(Locale.ROOT),
                    )
                }
            } }
        }
    }

    private fun parseBackup(file: File): Map<String, String> {
        require(file.length() in 0..MAX_FILE_BYTES) { "Android Port journal is unexpectedly large" }
        val result = linkedMapOf<String, String>()
        backupEntry.findAll(file.readText(Charsets.UTF_8)).forEach { match ->
            val key = match.groupValues[1].uppercase(Locale.ROOT)
            if (key in owned) result[key] = match.groupValues[2].uppercase(Locale.ROOT)
        }
        return result
    }

    private fun restoreFile(file: File, backup: Map<String, String>): Boolean {
        require(file.length() in 0..MAX_FILE_BYTES) { "WoW binding cache is unexpectedly large" }
        val original = file.readText(Charsets.UTF_8)
        val lineEnding = if ("\r\n" in original) "\r\n" else "\n"
        var changed = false
        val restored = original.split(Regex("\r?\n")).mapNotNull { line ->
            val match = bindingLine.matchEntire(line) ?: return@mapNotNull line
            val key = match.groupValues[1].uppercase(Locale.ROOT)
            val command = match.groupValues[2].trim().uppercase(Locale.ROOT)
            val expected = owned[key] ?: return@mapNotNull line
            val previous = backup[key] ?: return@mapNotNull line
            if (command !in expected) return@mapNotNull line
            changed = true
            previous.takeIf(String::isNotEmpty)?.let { "bind $key $it" }
        }.toMutableList()
        if (!changed) return false
        while (restored.lastOrNull()?.isEmpty() == true) restored.removeAt(restored.lastIndex)
        val content = if (restored.isEmpty()) "" else restored.joinToString(lineEnding, postfix = lineEnding)
        if (content == original) return false
        writeAtomic(file, content)
        return true
    }

    private fun writeAtomic(file: File, content: String) = com.pocketrealm.fs.DurableFiles.atomicWrite(file, content)
}
