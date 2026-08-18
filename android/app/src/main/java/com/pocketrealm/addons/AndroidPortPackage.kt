package com.pocketrealm.addons

/**
 * Stable identity for Pocket Realm's project-owned Vanilla 1.12.1 addon,
 * displayed as "Android Port". Version 0.6.0 renamed every on-disk spelling
 * from the historical VanillaConsolePort era; [AndroidPortMigrator] remaps
 * the persisted device data (registry ids/folders, saved variables, binding
 * tables, binding journal) at the repository and launch boundaries.
 */
internal object AndroidPortPackage {
    const val INSTALL_ID = "builtin__androidport"
    const val DISPLAY_NAME = "Android Port"
    const val ASSET_PATH = "addons/android-port"
    const val ADDON_FOLDER = "AndroidPort"
    const val VERSION = "0.6.0"

    /** Install id persisted by 0.5.x and earlier on-device registries. */
    const val LEGACY_INSTALL_ID = "builtin__vanillaconsoleport"
    const val LEGACY_ADDON_FOLDER = "VanillaConsolePort"
}
