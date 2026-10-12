package com.pocketrealm.ingame

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.WowBindingDefinition
import com.pocketrealm.client.WowTbcBindingCatalog
import com.pocketrealm.client.WowVanillaBindingCatalog
import com.pocketrealm.client.WowWotlkBindingCatalog

/**
 * W3.3: the flavor-routed settings/binding catalog resolver — the one seam
 * the in-game settings editors (both twins) resolve through, replacing the
 * per-callsite vanilla references. Each flavor's authored catalog
 * ([WowVanillaSettingsCatalog] / [WowTbcSettingsCatalog] /
 * [WowWotlkSettingsCatalog], same for the binding catalogs) answers its own
 * flavor's lookups; an unknown flavor id fails loud — never a silent
 * vanilla pick.
 */
internal object WowFlavorCatalogs {

    /** The flavor's settings-catalog `byId` lookup. */
    fun settingsById(flavor: ClientFlavor): (String) -> WowSettingDefinition? =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaSettingsCatalog::byId
            ClientFlavors.TBC.flavorId -> WowTbcSettingsCatalog::byId
            ClientFlavors.WOTLK.flavorId -> WowWotlkSettingsCatalog::byId
            else -> error("no settings catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's settings-catalog `byKey` lookup (the delivery loop's
     * reverse mapping from a Config.wtf key to its definition). */
    fun settingsByKey(flavor: ClientFlavor): (String) -> WowSettingDefinition? =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaSettingsCatalog::byKey
            ClientFlavors.TBC.flavorId -> WowTbcSettingsCatalog::byKey
            ClientFlavors.WOTLK.flavorId -> WowWotlkSettingsCatalog::byKey
            else -> error("no settings catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's binding-catalog `find` lookup. */
    fun bindingFind(flavor: ClientFlavor): (String) -> WowBindingDefinition? =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaBindingCatalog::find
            ClientFlavors.TBC.flavorId -> WowTbcBindingCatalog::find
            ClientFlavors.WOTLK.flavorId -> WowWotlkBindingCatalog::find
            else -> error("no binding catalog registered for flavor \"${flavor.flavorId}\"")
        }
}
