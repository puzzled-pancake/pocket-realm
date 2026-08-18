package com.pocketrealm.addons

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Reserves app-owned balanced key surrogates without overwriting a player
 * binding, and performs one-way cleanup left by the retired controller add-on.
 */
internal object LegacyControllerBindingRepair {
    private const val MAX_BINDING_FILES = 128
    private const val MAX_BINDING_BYTES = 1024L * 1024L
    private const val MAX_SAVED_VARIABLE_FILES = 128
    private val binding = Regex(
        """^\s*bind\s+"?([^"\s]+)"?\s+"?([^"\r\n]+)"?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private const val RETIRED_LOOT_ALL = "POCKETREALM_LOOT_ALL"
    private const val TARGET_SURROGATE_KEY = "F6"
    private const val TARGET_COMMAND = "TARGETNEARESTENEMY"
    private const val AUTO_RUN_SURROGATE_KEY = "F9"
    private const val AUTO_RUN_COMMAND = "TOGGLEAUTORUN"
    private val stockBindings = mapOf(
        "1" to "ACTIONBUTTON1",
        "2" to "ACTIONBUTTON2",
        "3" to "ACTIONBUTTON3",
        "4" to "ACTIONBUTTON4",
        "5" to "ACTIONBUTTON5",
        "6" to "ACTIONBUTTON6",
        "7" to "ACTIONBUTTON7",
        "8" to "ACTIONBUTTON8",
        "UP" to "MOVEFORWARD",
        "DOWN" to "MOVEBACKWARD",
        "LEFT" to "TURNLEFT",
        "RIGHT" to "TURNRIGHT",
        "B" to "TOGGLEBACKPACK",
        "F11" to "TOGGLEBAG4",
        "TAB" to "TARGETNEARESTENEMY",
    )
    private val retiredSavedVariables = setOf(
        "pocketrealmpad.lua",
        "pocketrealmpad.lua.bak",
        "pocketrealmpadlauncher.lua",
        "pocketrealmpadlauncher.lua.bak",
    )
    private val savedPreviousEntry = Regex(
        """\["([A-Z0-9]+)"\]\s*=\s*(?:"([A-Z0-9_]+)"|(false))\s*,""",
    )
    private val launcherClaimedKeys = buildSet {
        (1..8).forEach { add(it.toString()) }
        (7..11).forEach { add("F$it") }
        addAll(listOf("UP", "DOWN", "LEFT", "RIGHT", "B", "TAB"))
    }

    /** Returns the number of binding-cache files changed. */
    fun repair(clientRoot: File): Int {
        val wtf = File(clientRoot, "WTF")
        if (!wtf.isDirectory || Files.isSymbolicLink(wtf.toPath())) return 0
        val paths = mutableListOf<java.nio.file.Path>()
        Files.walk(wtf.toPath(), 8).use { stream -> stream.forEach(paths::add) }
        require(paths.size <= 10_000) { "WoW settings tree is unexpectedly large" }

        val savedVariablePaths = paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.fileName.toString().lowercase() in retiredSavedVariables
        }
        require(savedVariablePaths.size <= MAX_SAVED_VARIABLE_FILES) { "too many retired add-on settings files" }
        val previousByAccount = linkedMapOf<java.nio.file.Path, MutableMap<String, String?>>()
        // Only the launcher owned the previous-binding journal. Read its .bak
        // first, then merge the current journal over matching entries. Empty
        // SavedVariables from the companion add-on must never erase recovery
        // data, and Files.walk ordering must not decide which binding survives.
        savedVariablePaths
            .filter { it.fileName.toString().startsWith("PocketRealmPadLauncher", ignoreCase = true) }
            .sortedBy { !it.fileName.toString().endsWith(".bak", ignoreCase = true) }
            .forEach { path ->
                val account = path.parent?.parent?.toAbsolutePath()?.normalize() ?: return@forEach
                previousByAccount.getOrPut(account) { linkedMapOf() }
                    .putAll(parsePreviousBindings(path.toFile()))
            }

        val bindingFiles = paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.fileName.toString().equals("bindings-cache.wtf", ignoreCase = true)
        }
        require(bindingFiles.size <= MAX_BINDING_FILES) { "too many WoW binding-cache files" }
        val substantiveAccountBindings = bindingFiles.mapNotNull { candidate ->
            val account = accountRoot(candidate) ?: return@mapNotNull null
            candidate.takeIf {
                candidate.parent.toAbsolutePath().normalize() == account &&
                    hasSubstantiveBinding(candidate.toFile())
            }?.let { account }
        }.toSet()
        val changed = bindingFiles.count { path ->
            val file = path.toFile()
            val account = accountRoot(path)
            val accountLevel = account != null &&
                path.parent.toAbsolutePath().normalize() == account
            when {
                // A prior Pocket Realm build appended its F6 surrogate to an
                // otherwise-empty character binding file. WoW then selected
                // that one-line character scope instead of the complete
                // account scope, leaving absolute mouse motion alive while
                // movement, buttons, Escape and camera input all appeared
                // dead. Delete only our exact generated fingerprint so WoW
                // falls back to the intact account bindings.
                !accountLevel && account in substantiveAccountBindings &&
                    isGeneratedSparseTargetFile(file) -> {
                    check(file.delete()) { "generated sparse character bindings could not be removed" }
                    true
                }
                // Never populate an empty character scope merely to add the
                // target surrogate. Substantive character scopes still need
                // the same retired-command cleanup and F6 reservation as an
                // account scope.
                accountLevel || hasSubstantiveBinding(file) ->
                    repairFile(file, previousByAccount[account])
                else -> false
            }
        }
        savedVariablePaths.forEach { path ->
            check(Files.deleteIfExists(path)) { "retired add-on settings could not be removed" }
        }
        return changed
    }

    private fun repairFile(file: File, previous: Map<String, String?>?): Boolean {
        require(file.length() in 0..MAX_BINDING_BYTES) { "WoW binding cache is unexpectedly large" }
        val original = file.readText(Charsets.UTF_8)
        val lineEnding = if ("\r\n" in original) "\r\n" else "\n"
        val lines = original.split(Regex("\r?\n"))
        val claimedKeys = linkedSetOf<String>()
        val surrogateBindings = mapOf(
            TARGET_SURROGATE_KEY to TARGET_COMMAND,
            AUTO_RUN_SURROGATE_KEY to AUTO_RUN_COMMAND,
        )
        val retained = lines.filterNot { line ->
            binding.matchEntire(line)?.let { match ->
                val key = match.groupValues[1].trim().uppercase()
                val command = match.groupValues[2].trim().uppercase()
                when {
                    command.startsWith("PRP_") || command == RETIRED_LOOT_ALL -> {
                        claimedKeys += key
                        true
                    }
                    else -> false
                }
            } ?: false
        }.toMutableList()
        if (
            claimedKeys.isEmpty() &&
            surrogateBindings.keys.all { key -> retained.hasBindingFor(key) }
        ) return false
        while (retained.lastOrNull()?.isEmpty() == true) retained.removeAt(retained.lastIndex)
        claimedKeys.mapNotNull { key ->
            val action = if (previous?.containsKey(key) == true) previous[key] else stockBindings[key]
            action?.let { key to it }
        }
            .sortedBy { (key, _) -> if (key == "TAB") 99 else key.toIntOrNull() ?: 98 }
            .forEach { (key, action) -> retained += "bind $key $action" }
        // Reserve each surrogate only when it is genuinely unbound. An
        // arbitrary player binding is never overwritten merely to provide a
        // controller shortcut.
        surrogateBindings.forEach { (key, command) ->
            if (!retained.hasBindingFor(key)) {
                retained += "bind $key $command"
            }
        }
        writeAtomic(file, retained.joinToString(lineEnding, postfix = lineEnding))
        return true
    }

    private fun List<String>.hasBindingFor(key: String): Boolean = any { line ->
        binding.matchEntire(line)?.groupValues?.get(1)?.trim()?.equals(key, ignoreCase = true) == true
    }

    private fun isGeneratedSparseTargetFile(file: File): Boolean {
        require(file.length() in 0..MAX_BINDING_BYTES) { "WoW binding cache is unexpectedly large" }
        val content = file.readText(Charsets.UTF_8)
        val generated = setOf(
            "bind $TARGET_SURROGATE_KEY $TARGET_COMMAND",
            "bind $AUTO_RUN_SURROGATE_KEY $AUTO_RUN_COMMAND",
        )
        val lines = content.split(Regex("\r?\n")).filter(String::isNotBlank).map(String::trim)
        return lines.isNotEmpty() && lines.all { it in generated }
    }

    private fun hasSubstantiveBinding(file: File): Boolean {
        require(file.length() in 0..MAX_BINDING_BYTES) { "WoW binding cache is unexpectedly large" }
        return file.useLines(Charsets.UTF_8) { lines ->
            lines.any { line ->
                binding.matchEntire(line)?.let { match ->
                    val key = match.groupValues[1].trim()
                    val command = match.groupValues[2].trim()
                    val targetSurrogate = key.equals(TARGET_SURROGATE_KEY, ignoreCase = true) &&
                        command.equals(TARGET_COMMAND, ignoreCase = true)
                    val autoRunSurrogate = key.equals(AUTO_RUN_SURROGATE_KEY, ignoreCase = true) &&
                        command.equals(AUTO_RUN_COMMAND, ignoreCase = true)
                    !targetSurrogate && !autoRunSurrogate
                } == true
            }
        }
    }

    private fun accountRoot(path: java.nio.file.Path): java.nio.file.Path? {
        var current: java.nio.file.Path? = path.parent
        while (current != null) {
            if (current.parent?.fileName?.toString().equals("Account", ignoreCase = true)) {
                return current.toAbsolutePath().normalize()
            }
            current = current.parent
        }
        return null
    }

    private fun parsePreviousBindings(file: File): Map<String, String?> {
        require(file.length() in 0..MAX_BINDING_BYTES) { "retired add-on settings are unexpectedly large" }
        val result = linkedMapOf<String, String?>()
        savedPreviousEntry.findAll(file.readText(Charsets.UTF_8)).forEach { match ->
            val key = match.groupValues[1]
            if (key in launcherClaimedKeys) {
                result[key] = match.groupValues[2].takeIf { it.isNotEmpty() }
            }
        }
        return result
    }

    private fun writeAtomic(file: File, content: String) = com.pocketrealm.fs.DurableFiles.atomicWrite(file, content)
}
