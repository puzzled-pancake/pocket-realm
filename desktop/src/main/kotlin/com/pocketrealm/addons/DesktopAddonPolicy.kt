package com.pocketrealm.addons

/**
 * The one desktop table the repository and the launch-boundary projector
 * must agree on: which products are retired (an install of a retired
 * product is inert at both the write layer and the projection layer) and
 * the built-in AndroidPort bundle caps (file count, byte size, allowed
 * extensions). Hoisted here (one internal object, `AndroidPortPackage`
 * style) so the twin pair cannot drift over these literals again; the
 * Android originals keep their own copies under the twin contract.
 */
internal object DesktopAddonPolicy {
    /** Registry install ids of products retired from the app. */
    val RETIRED_PRODUCT_INSTALL_IDS: Set<String> = setOf(
        "bundled__pocketrealmpad",
        // Short-lived remote prototype replaced by the clean-room built-in.
        "pepordev__consoleexperienceclassic",
    )

    /** AddOns folder names owned by the retired products (matched case-insensitively). */
    val RETIRED_PRODUCT_FOLDERS: Set<String> = setOf("PocketRealmPad", "PocketRealmPadLauncher")

    const val MAX_BUILTIN_FILES = 512
    const val MAX_BUILTIN_BYTES = 8L * 1024 * 1024
    val BUILTIN_ALLOWED_EXTENSIONS: Set<String> =
        setOf("lua", "toc", "xml", "md", "txt", "tga", "blp")

    fun isRetiredProduct(addon: InstalledAddon): Boolean =
        addon.id in RETIRED_PRODUCT_INSTALL_IDS || addon.folders.any(::isRetiredProductFolder)

    fun isRetiredProductFolder(folder: String): Boolean =
        RETIRED_PRODUCT_FOLDERS.any { it.equals(folder, ignoreCase = true) }
}
