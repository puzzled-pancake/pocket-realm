package com.pocketrealm.ingame

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * G3-10 +: the WTF file-layout strategy seam. The vanilla strategy is the
 * build-5875 layout verified on device, frozen verbatim:
 * account uvars at `WTF/Account/<name>/SavedVariables.lua`, account bindings
 * at `WTF/Account/<name>/bindings-cache.wtf`, per-character binding
 * overrides at `WTF/Account/<name>/<server>/<char>/bindings-cache.wtf`.
 *
 * W3.3 (H4-5, correction C8): the TBC and WotLK layouts join as NEW
 * strategies — never by editing the vanilla paths in place; the vanilla path
 * functions' outputs are byte-pinned by `InGameSettingsFilesLayoutPinTest`.
 * Both new flavors are 3-level per (the Account/Realm/Character
 * hierarchy), and WotLK additionally carries the WotLK-era char-level cache
 * set (`config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`,
 * `layout-local.txt`, `chat-cache.txt`) — the set the flavor registry
 * mirrors on [ClientFlavor.wtfCharacterCacheFiles].
 *
 * The single-file account-uvar route (`SavedVariables.lua`) is a
 * VANILLA-lane family: TBC/WotLK clients write per-addon Lua files under the
 * account/character `SavedVariables/` directories, and the authored
 * tbc/wotlk settings catalogs carry CVAR-only definitions (no uvar), so the
 * new layouts refuse the single-file route fail-loud with an actionable
 * message while still ENUMERATING accounts through their real per-addon
 * directory route.
 */
internal interface WtfLayout {
    fun configFile(clientRoot: File): File

    fun accountDirectory(clientRoot: File, account: String): File

    /** The single-file uvar route — a vanilla-lane family (see class KDoc). */
    fun accountSavedVariables(clientRoot: File, account: String): File

    /** The enumeration route for the uvar family, per layout shape. */
    fun accountHasUvars(clientRoot: File, account: String): Boolean

    fun accountBindings(clientRoot: File, account: String): File

    fun characterBindings(clientRoot: File, scope: String): File

    fun bindingsForScope(clientRoot: File, scope: String): File

    /**
     * The WotLK-era char-level cache-file set this layout resolves (C8:
     * empty for vanilla and TBC; the five-file set for WotLK).
     */
    val characterCacheFiles: Set<String>

    /**
     * R2-5: whether the single-file uvar route EXISTS on this layout —
     * vanilla only; the tbc/wotlk layouts write per-addon SavedVariables
     * directories and their catalogs carry no uvar entries, so editors
     * consult this BEFORE reaching [accountSavedVariables] (whose fail-loud
     * is the backstop, never the control flow).
     */
    val supportsUvars: Boolean

    /**
     * The char-level cache route for one scope: `<acct>/<realm>/<char>/
     * [fileName]`. Fails loud for a layout whose cache set is empty and for
     * any file name outside the layout's set — an invented cache file is
     * exactly the drift the set exists to prevent.
     */
    fun characterCacheFile(clientRoot: File, scope: String, fileName: String): File

    /** Shared account/server/character scope parser (3 segments, safe). */
    fun characterScopeSegments(scope: String): List<String> {
        val parts = scope.split("/").map(::requireSafeSegment)
        require(parts.size == CHARACTER_SCOPE_SEGMENTS) { "invalid character binding scope: $scope" }
        return parts
    }

    companion object {
        /** account/server/character scope segments. */
        const val CHARACTER_SCOPE_SEGMENTS = 3

        fun requireSafeSegment(segment: String): String {
            require(segment.isNotEmpty() && segment.matches(Regex("[^/\\\\]+")) &&
                segment !in setOf(".", "..")) { "unsafe WTF scope segment: $segment" }
            return segment
        }
    }
}

/** The build-5875 layout, byte-for-byte the paths InGameSettingsFiles has always produced. */
internal object VanillaWtfLayout : WtfLayout {
    override val supportsUvars: Boolean = true

    override fun configFile(clientRoot: File): File = File(clientRoot, "WTF/Config.wtf")

    override fun accountDirectory(clientRoot: File, account: String): File =
        File(File(clientRoot, "WTF/Account"), WtfLayout.requireSafeSegment(account))

    override fun accountSavedVariables(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "SavedVariables.lua")

    override fun accountHasUvars(clientRoot: File, account: String): Boolean =
        accountSavedVariables(clientRoot, account).isFile

    override fun accountBindings(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "bindings-cache.wtf")

    override fun characterBindings(clientRoot: File, scope: String): File {
        val parts = characterScopeSegments(scope)
        return File(clientRoot, "WTF/Account/${parts[0]}/${parts[1]}/${parts[2]}/bindings-cache.wtf")
    }

    override fun bindingsForScope(clientRoot: File, scope: String): File =
        if (scope.contains("/")) characterBindings(clientRoot, scope)
        else accountBindings(clientRoot, scope)

    // G3-10/: the vanilla client keeps NO char-level cache set — the
    // account-level single files are the whole story.
    override val characterCacheFiles: Set<String> = emptySet()

    override fun characterCacheFile(clientRoot: File, scope: String, fileName: String): File =
        error(
            "the vanilla (build-5875) WTF layout keeps no char-level cache files " +
                "(correction C8) — requested \"$fileName\"",
        )
}

/**
 * The TBC (build-8606) layout — H4-5's 3-level strategy, correction C8: the
 * per-realm/per-character hierarchy is already real in TBC. The config and
 * binding routes keep the account/character shapes the editor serves
 * (the authored TBC catalog carries CVAR-only definitions, so Config.wtf and
 * the bindings routes are the families the editor actually touches); the
 * single-file uvar route is refused fail-loud — TBC writes per-addon Lua
 * files under the account/character `SavedVariables/` directories, which is
 * the route the enumeration uses.
 */
internal object TbcWtfLayout : WtfLayout {
    override val supportsUvars: Boolean = false

    override fun configFile(clientRoot: File): File = File(clientRoot, "WTF/Config.wtf")

    override fun accountDirectory(clientRoot: File, account: String): File =
        File(File(clientRoot, "WTF/Account"), WtfLayout.requireSafeSegment(account))

    override fun accountSavedVariables(clientRoot: File, account: String): File =
        error(
            "the single-file SavedVariables.lua route is a vanilla-lane family — the " +
                "TBC client writes per-addon files under WTF/Account/<name>/SavedVariables/ " +
                "and the TBC settings catalog carries no uvar entries",
        )

    override fun accountHasUvars(clientRoot: File, account: String): Boolean =
        File(accountDirectory(clientRoot, account), "SavedVariables").isDirectory

    override fun accountBindings(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "bindings-cache.wtf")

    override fun characterBindings(clientRoot: File, scope: String): File {
        val parts = characterScopeSegments(scope)
        return File(clientRoot, "WTF/Account/${parts[0]}/${parts[1]}/${parts[2]}/bindings-cache.wtf")
    }

    override fun bindingsForScope(clientRoot: File, scope: String): File =
        if (scope.contains("/")) characterBindings(clientRoot, scope)
        else accountBindings(clientRoot, scope)

    // the char-level cache set is genuinely WotLK-only — TBC keeps the
    // account/char split WITHOUT the cache-file family.
    override val characterCacheFiles: Set<String> = emptySet()

    override fun characterCacheFile(clientRoot: File, scope: String, fileName: String): File =
        error(
            "the TBC (build-8606) WTF layout keeps no char-level cache files " +
                "(correction C8: the cache set is WotLK-era) — requested \"$fileName\"",
        )
}

/**
 * The WotLK (build-12340) layout — H4-5's 3-level strategy PLUS the
 * WotLK-era char-level cache set: `config-cache.wtf`,
 * `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`,
 * `chat-cache.txt` at the character level (the set mirrored on
 * [ClientFlavor.WOTLK.wtfCharacterCacheFiles]). The single-file uvar route
 * is refused exactly like TBC's (per-addon SavedVariables directories).
 */
internal object WotlkWtfLayout : WtfLayout {
    override val supportsUvars: Boolean = false

    override fun configFile(clientRoot: File): File = File(clientRoot, "WTF/Config.wtf")

    override fun accountDirectory(clientRoot: File, account: String): File =
        File(File(clientRoot, "WTF/Account"), WtfLayout.requireSafeSegment(account))

    override fun accountSavedVariables(clientRoot: File, account: String): File =
        error(
            "the single-file SavedVariables.lua route is a vanilla-lane family — the " +
                "WotLK client writes per-addon files under WTF/Account/<name>/SavedVariables/ " +
                "and the WotLK settings catalog carries no uvar entries",
        )

    override fun accountHasUvars(clientRoot: File, account: String): Boolean =
        File(accountDirectory(clientRoot, account), "SavedVariables").isDirectory

    override fun accountBindings(clientRoot: File, account: String): File =
        File(accountDirectory(clientRoot, account), "bindings-cache.wtf")

    override fun characterBindings(clientRoot: File, scope: String): File {
        val parts = characterScopeSegments(scope)
        return File(clientRoot, "WTF/Account/${parts[0]}/${parts[1]}/${parts[2]}/bindings-cache.wtf")
    }

    override fun bindingsForScope(clientRoot: File, scope: String): File =
        if (scope.contains("/")) characterBindings(clientRoot, scope)
        else accountBindings(clientRoot, scope)

    /** The WotLK-era char-level cache set (correction) — the client
     * rewrites these at exit; the editor reads them UNDER Config.wtf for
     * keys the client persists there. Mirrors ClientFlavor.WOTLK
     * .wtfCharacterCacheFiles (the manifest's `wtf` descriptor). */
    override val characterCacheFiles: Set<String> = setOf(
        "config-cache.wtf",
        "bindings-cache.wtf",
        "macros-cache.txt",
        "layout-local.txt",
        "chat-cache.txt",
    )

    override fun characterCacheFile(clientRoot: File, scope: String, fileName: String): File {
        require(fileName in characterCacheFiles) {
            "\"$fileName\" is not in the WotLK char-level cache set " +
                characterCacheFiles.sorted().toString()
        }
        val parts = characterScopeSegments(scope)
        return File(clientRoot, "WTF/Account/${parts[0]}/${parts[1]}/${parts[2]}/$fileName")
    }
}

internal object InGameSettingsFiles {

    private const val MAX_TREE_ENTRIES = 10_000

    /** The [ActiveFlavor] lane suffix naming the WTF-layout resolution. */
    private const val WTF_LAYOUT_LANE =
        "the WTF layout resolves the active flavor's file routes"

    /**
     * The layout for [flavor]: vanilla byte-frozen, tbc/wotlk per H4-5/C8
     * (W3.3). Unknown flavor ids fail loud — never a silent vanilla pick.
     */
    fun layoutFor(flavor: ClientFlavor): WtfLayout =
        when (flavor.flavorId) {
            com.pocketrealm.client.ClientFlavors.VANILLA.flavorId -> VanillaWtfLayout
            com.pocketrealm.client.ClientFlavors.TBC.flavorId -> TbcWtfLayout
            com.pocketrealm.client.ClientFlavors.WOTLK.flavorId -> WotlkWtfLayout
            else -> error("WTF layout for flavor \"${flavor.flavorId}\" is not defined")
        }

    /**
     * The ACTIVE flavor's layout. The boot-lane call sites (the launch
     * delivery) run under the exactly-one-qualified law today, so the live
     * resolution equals the booting flavor; when a second flavor qualifies
     * (W4), the start paths thread their start snapshot through
     * [layoutFor] explicitly (the D71/D72 boot-lane-binding law).
     */
    fun activeLayout(): WtfLayout = layoutFor(ActiveFlavor.resolve(WTF_LAYOUT_LANE))

    fun configFile(clientRoot: File): File = activeLayout().configFile(clientRoot)

    fun accountDirectory(clientRoot: File, account: String): File =
        activeLayout().accountDirectory(clientRoot, account)

    fun accountSavedVariables(clientRoot: File, account: String): File =
        activeLayout().accountSavedVariables(clientRoot, account)

    fun accountBindings(clientRoot: File, account: String): File =
        activeLayout().accountBindings(clientRoot, account)

    fun characterBindings(clientRoot: File, scope: String): File =
        activeLayout().characterBindings(clientRoot, scope)

    fun bindingsForScope(clientRoot: File, scope: String): File =
        activeLayout().bindingsForScope(clientRoot, scope)

    /** The char-level cache route for the ACTIVE flavor's layout. */
    fun characterCacheFile(clientRoot: File, scope: String, fileName: String): File =
        activeLayout().characterCacheFile(clientRoot, scope, fileName)

    /** Accounts that have logged in at least once (their uvar route exists:
     * the single file for vanilla, the per-addon SavedVariables directory
     * for tbc/wotlk). */
    fun accountsWithSavedVariables(clientRoot: File): List<String> =
        accountsWithSavedVariablesFor(activeLayout(), clientRoot)

    /** Accounts whose binding table exists. */
    fun accountsWithBindings(clientRoot: File): List<String> =
        accountsWithBindingsFor(activeLayout(), clientRoot)

    /** The layout-parameterized enumeration seams (the static functions
     * resolve the ACTIVE flavor; tests drive explicit layouts). */
    internal fun accountsWithSavedVariablesFor(
        layout: WtfLayout,
        clientRoot: File,
    ): List<String> = accountDirectories(clientRoot).filter { layout.accountHasUvars(clientRoot, it) }

    internal fun accountsWithBindingsFor(
        layout: WtfLayout,
        clientRoot: File,
    ): List<String> = accountDirectories(clientRoot).filter {
        layout.accountBindings(clientRoot, it).isFile
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
