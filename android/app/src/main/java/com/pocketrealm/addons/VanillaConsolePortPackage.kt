package com.pocketrealm.addons

/**
 * Stable identity for Pocket Realm's project-owned Vanilla 1.12.1 addon.
 * Displayed as "Android Port"; the install id, asset path, and add-on folder
 * keep their historical VanillaConsolePort spellings because they are persisted
 * on devices (registry, saved variables, binding journals).
 */
internal object VanillaConsolePortPackage {
    const val INSTALL_ID = "builtin__vanillaconsoleport"
    const val DISPLAY_NAME = "Android Port"
    const val ASSET_PATH = "addons/vanilla-console-port"
    const val ADDON_FOLDER = "VanillaConsolePort"
    const val VERSION = "0.4.0"
}
