package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import java.util.Locale

/**
 * The add-on catalog projected onto the ACTIVE flavor (the multi-flavor
 * lift of the add-ons screens): the screens list ONLY the active flavor's
 * addon set, resolved from the catalog's own research data.
 *
 * A catalog entry's researched client compatibility is its free-text
 * `clientTarget` field ("1.12.1", "1.12.1 + 2.4.3", "1.12 + 2.4.3 +
 * 3.3.5", ...). Each flavor's researched version tokens are matched
 * against that text — a flavor's set is exactly the entries whose research
 * names that client line. The vanilla token matches every researched entry
 * (the catalog is researched from the 1.12 line), so the vanilla lane's
 * list is unchanged from the pre-multi-flavor screens; the TBC and WotLK
 * lanes list only entries whose research covers those clients, and an
 * honestly short (even empty) list beats a mislabeled one.
 *
 * Install-time `## Interface:` acceptance is the separate
 * [com.pocketrealm.client.ClientFlavor.addonInterfaces] seam (DESK-54) on
 * [com.pocketrealm.addons.AddonArchiveValidator.Policy]; this file is the
 * LIST projection only.
 */
object AddonFlavorCatalog {

    /**
     * The client-line tokens a flavor's researched add-ons declare in
     * `clientTarget`. Vanilla matches the whole researched 1.12 line.
     */
    fun versionTokens(flavor: ClientFlavor): List<String> = when (flavor.flavorId) {
        ClientFlavors.VANILLA.flavorId -> listOf("1.12")
        ClientFlavors.TBC.flavorId -> listOf("2.4.3")
        ClientFlavors.WOTLK.flavorId -> listOf("3.3.5")
        else -> emptyList()
    }

    /** True when the entry's researched targets cover [flavor]'s client line. */
    fun supportsFlavor(addon: CatalogAddon, flavor: ClientFlavor): Boolean =
        versionTokens(flavor).any { token -> token in addon.clientTarget }

    /** The active flavor's addon set, catalog order preserved. */
    fun forFlavor(addons: List<CatalogAddon>, flavor: ClientFlavor): List<CatalogAddon> =
        addons.filter { supportsFlavor(it, flavor) }

    /**
     * The query filter of [com.pocketrealm.addons.AddonCatalog.filter]
     * (same fields, same ordering) over an already flavor-projected list —
     * the Browse screens filter the ACTIVE flavor's set with it.
     */
    fun filter(addons: List<CatalogAddon>, query: String): List<CatalogAddon> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return addons.asSequence()
            .filter { addon ->
                needle.isBlank() || listOf(
                    addon.name, addon.category, addon.description, addon.compatibilityRole,
                    addon.compatibilityNotes, addon.handheldVerdict,
                ).any { needle in it.lowercase(Locale.ROOT) }
            }
            .sortedWith(compareByDescending<CatalogAddon> { it.installable }
                .thenByDescending { it.handheldScore }
                .thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }
}
