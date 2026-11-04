package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavor
import java.io.File

/**
 * Stable identity for Pocket Realm's project-owned controller add-on,
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
    const val VERSION = "0.6.1"

    /** Install id persisted by 0.5.x and earlier on-device registries. */
    const val LEGACY_INSTALL_ID = "builtin__vanillaconsoleport"
    const val LEGACY_ADDON_FOLDER = "VanillaConsolePort"
}

/**
 * The built-in add-on ships one master toc (the classic interface line) and
 * projects it per client at the launch boundary: the installer's interface
 * check fails closed on a mismatch, so the projected toc must declare the
 * running client's own interface number.
 */
internal object AndroidPortTocProjection {
    /** The one interface the master asset declares. */
    const val MASTER_INTERFACE = 11200
    const val TOC_FILE = "${AndroidPortPackage.ADDON_FOLDER}.toc"
    private val interfaceLine = Regex("""(?m)^## Interface:[ \t]*(\d+)[ \t]*$""")

    /** The interface number [flavor]'s client accepts. */
    fun interfaceFor(flavor: ClientFlavor): Int =
        flavor.addonInterfaces.singleOrNull()
            ?: error("client ${flavor.flavorId} declares no single add-on interface")

    /**
     * Rewrites the master toc text for [flavor]. The master line is verified
     * first so a regenerated asset can never ride a wrong base; a flavor on
     * the master line is a byte-preserving pass-through.
     */
    fun projectTocText(text: String, flavor: ClientFlavor): String {
        val match = interfaceLine.find(text)
            ?: error("the built-in add-on toc declares no Interface line")
        val declared = match.groupValues[1].toInt()
        require(declared == MASTER_INTERFACE) {
            "the built-in add-on toc must stay the $MASTER_INTERFACE master " +
                "(found $declared)"
        }
        val target = interfaceFor(flavor)
        if (target == MASTER_INTERFACE) return text
        return text.replaceRange(match.range, "## Interface: $target")
    }
}

/**
 * The control layout the app picked, handed to the addon through the
 * shipped HostLayout.lua (loaded before Core.lua). Only the two known layout
 * names are ever written, so no player text reaches the Lua source.
 */
internal object AndroidPortHostLayout {
    const val FILE = "HostLayout.lua"
    val LAYOUTS = setOf("star", "forever")

    fun write(addonDir: File, layout: String?) {
        val file = File(addonDir, FILE)
        require(file.isFile) { "Built-in Android Port assets are missing $FILE" }
        if (layout == null) return
        require(layout in LAYOUTS) { "Unknown Android Port control layout" }
        val text = file.readText()
        val projected = text.replace(
            "AndroidPort.HOST_LAYOUT = nil",
            "AndroidPort.HOST_LAYOUT = \"$layout\"",
        )
        require(projected != text) { "Android Port $FILE has no layout line" }
        file.writeBytes(projected.toByteArray(Charsets.UTF_8))
    }
}
