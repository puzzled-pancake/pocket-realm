package com.pocketrealm.ingame

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * G3-10: the WTF file-layout strategy seam. The vanilla strategy is the
 * build-5875 layout verified on device, frozen verbatim:
 * account uvars at `WTF/Account/<name>/SavedVariables.lua`, account bindings
 * at `WTF/Account/<name>/bindings-cache.wtf`, per-character binding
 * overrides at `WTF/Account/<name>/<server>/<char>/bindings-cache.wtf`.
 * WotLK's per-realm/per-character routes (H4-5) enter as a NEW strategy —
 * never by editing the vanilla paths in place; the vanilla path functions'
 * outputs are byte-pinned by `InGameSettingsFilesLayoutPinTest`.
 */
internal interface WtfLayout {
    fun configFile(clientRoot: File): File

    fun accountDirectory(clientRoot: File, account: String): File

    fun accountSavedVariables(clientRoot: File, account: String): File

    fun accountBindings(clientRoot: File, account: String): File

    fun characterBindings(clientRoot: File, scope: String): File

    fun bindingsForScope(clientRoot: File, scope: String): File
}

/** The build-5875 layout, byte-for-byte the paths InGameSettingsFiles has always produced. */
internal object VanillaWtfLayout : WtfLayout {
    /** account/server/character scope segments. */
    private const val CHARACTER_SCOPE_SEGMENTS = 3

    override fun configFile(clientRoot: File): File = File(clientRoot, "WTF/Config.wtf")

    override fun accountDirectory(clientRoot: File, account: String): File =
        File(File(clientRoot, "WTF/Account"), requireSafeSegment(account))

    override fun accountSavedVariables(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "SavedVariables.lua")

    override fun accountBindings(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "bindings-cache.wtf")

    override fun characterBindings(clientRoot: File, scope: String): File {
        val parts = scope.split("/").map(::requireSafeSegment)
        require(parts.size == CHARACTER_SCOPE_SEGMENTS) { "invalid character binding scope: $scope" }
        return File(clientRoot, "WTF/Account/${parts[0]}/${parts[1]}/${parts[2]}/bindings-cache.wtf")
    }

    override fun bindingsForScope(clientRoot: File, scope: String): File =
        if (scope.contains("/")) characterBindings(clientRoot, scope)
        else accountBindings(clientRoot, scope)

    private fun requireSafeSegment(segment: String): String {
        require(segment.isNotEmpty() && segment.matches(Regex("[^/\\\\]+")) &&
            segment !in setOf(".", "..")) { "unsafe WTF scope segment: $segment" }
        return segment
    }
}

internal object InGameSettingsFiles {

    private const val MAX_TREE_ENTRIES = 10_000

    /** The layout for [flavor]; only vanilla is defined until H4-5 lands WotLK's. */
    fun layoutFor(flavor: com.pocketrealm.client.ClientFlavor): WtfLayout =
        when (flavor.flavorId) {
            com.pocketrealm.client.ClientFlavors.VANILLA.flavorId -> VanillaWtfLayout
            else -> error("WTF layout for flavor \"${flavor.flavorId}\" is not defined yet (H4-5)")
        }

    fun configFile(clientRoot: File): File = VanillaWtfLayout.configFile(clientRoot)

    fun accountDirectory(clientRoot: File, account: String): File =
        VanillaWtfLayout.accountDirectory(clientRoot, account)

    fun accountSavedVariables(clientRoot: File, account: String): File =
        VanillaWtfLayout.accountSavedVariables(clientRoot, account)

    fun accountBindings(clientRoot: File, account: String): File =
        VanillaWtfLayout.accountBindings(clientRoot, account)

    fun characterBindings(clientRoot: File, scope: String): File =
        VanillaWtfLayout.characterBindings(clientRoot, scope)

    fun bindingsForScope(clientRoot: File, scope: String): File =
        VanillaWtfLayout.bindingsForScope(clientRoot, scope)

    /** Accounts that have logged in at least once (their uvar file exists). */
    fun accountsWithSavedVariables(clientRoot: File): List<String> =
        accountDirectories(clientRoot).filter {
            accountSavedVariables(clientRoot, it).isFile
        }

    /** Accounts whose binding table exists. */
    fun accountsWithBindings(clientRoot: File): List<String> =
        accountDirectories(clientRoot).filter {
            accountBindings(clientRoot, it).isFile
        }

    /** Character scopes (`account/server/char`) that have binding overrides. */
    fun characterScopesWithBindings(clientRoot: File): List<String> {
        val wtf = File(clientRoot, "WTF")
        if (!wtf.isDirectory || Files.isSymbolicLink(wtf.toPath())) return emptyList()
        val paths = mutableListOf<java.nio.file.Path>()
        Files.walk(wtf.toPath(), 8).use { stream -> stream.forEach(paths::add) }
        check(paths.size <= MAX_TREE_ENTRIES) { "WoW settings tree is unexpectedly large" }
        return paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                path.fileName.toString().equals("bindings-cache.wtf", ignoreCase = true) &&
                path.toFile().parentFile?.parentFile?.parentFile?.parentFile != null
        }.map { path ->
            val account = path.getName(path.nameCount - 4).toString()
            val server = path.getName(path.nameCount - 3).toString()
            val character = path.getName(path.nameCount - 2).toString()
            "$account/$server/$character"
        }.sorted()
    }

    private fun accountDirectories(clientRoot: File): List<String> {
        val accounts = File(clientRoot, "WTF/Account")
        if (!accounts.isDirectory || Files.isSymbolicLink(accounts.toPath())) return emptyList()
        return accounts.listFiles { file ->
            file.isDirectory && !Files.isSymbolicLink(file.toPath()) &&
                !file.name.startsWith(".")
        }?.map { it.name }?.sorted().orEmpty()
    }

    /** Atomic temp+fsync+rename write, the established host-side discipline. */
    fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
