package com.pocketrealm.addons

import java.io.File

/**
 * Resolves the bundled add-on assets (the Android `assets/` tree: the
 * researched catalog JSON and the project-owned `addons/android-port` Lua
 * tree) from wherever the desktop lane carries them. Sources, in order:
 *
 *  1. the jpackage app dir entries of `java.library.path` (packageApp stages
 *     the whole `addons/` tree beside the DLLs — the same $APPDIR seam the
 *     LLM assets use through [com.pocketrealm.server.ServerRuntimeFiles]);
 *  2. a bounded walk up from `user.dir` to the checkout's
 *     `android/app/src/main/assets/addons` (dev runs from the repo).
 *
 * The app's own storage roots are deliberately NOT sources — a previously
 * staged copy must never verify or shadow the bundled bytes (the
 * ServerRuntimeFiles SOURCES-ONLY rule).
 */
internal object DesktopAddonAssets {
    private const val MAX_WALK_UP = 6

    /** The researched catalog JSON (`AddonCatalog`'s bundled input) — the
     * ACTIVE flavor's per-flavor asset (R6-r2-5): catalog-tbc.json /
     * catalog-wotlk.json for the new lanes, the byte-frozen catalog-v1.json
     * for vanilla. */
    fun catalogJson(flavorId: String = "vanilla"): File? =
        firstExisting(catalogAssetName(flavorId))

    /** The per-flavor catalog asset name (mirrors the android
     * AddonCatalog.catalogAssetFor). */
    fun catalogAssetName(flavorId: String): String = when (flavorId) {
        "vanilla" -> "catalog-v1.json"
        "tbc" -> "catalog-tbc.json"
        "wotlk" -> "catalog-wotlk.json"
        else -> error("no addon catalog asset registered for flavor \"$flavorId\"")
    }

    /** The project-owned Android Port add-on tree (`android-port/AndroidPort`). */
    fun androidPortRoot(): File? = firstExisting(AndroidPortPackage.ASSET_PATH.substringAfterLast('/'))

    private fun firstExisting(relative: String): File? = candidates(relative).firstOrNull { it.exists() }

    private fun candidates(relative: String): List<File> =
        libraryPathCandidates(relative) + walkUpCandidates(relative)

    /** `<library-path-entry>/addons/<relative>` — the packaged-image layout. */
    private fun libraryPathCandidates(relative: String): List<File> =
        (System.getProperty("java.library.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { entry -> File(File(entry), "addons/$relative") }

    /** `<walk-up>/android/app/src/main/assets/addons/<relative>` — the checkout layout. */
    private fun walkUpCandidates(relative: String): List<File> =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(MAX_WALK_UP)
            .map { dir -> File(File(dir, "android/app/src/main/assets/addons"), relative) }
            .toList()
}
