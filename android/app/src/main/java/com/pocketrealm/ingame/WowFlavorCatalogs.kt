package com.pocketrealm.ingame

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.WowBindingCategory
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
@Suppress("TooManyFunctions")
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

    // ------------------------------------------------------------------
    // R5-1 (W4 code-red round 5): the RENDER-side seam — the in-game
    // settings/bindings/controls screens resolve every catalog read
    // through these, never a per-lane object directly, so a tbc/wotlk
    // lane renders its OWN authored definitions instead of 98 vanilla
    // rows that resolve null. The binding catalogs for tbc/wotlk
    // deliberately author no stock-default/reserved key data: the seam
    // answers those with the honest empty defaults.
    // ------------------------------------------------------------------

    /** The flavor's settings-catalog section listing. */
    fun settingsForSection(
        flavor: ClientFlavor,
    ): (WowSettingSection) -> List<WowSettingDefinition> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaSettingsCatalog::forSection
            ClientFlavors.TBC.flavorId -> WowTbcSettingsCatalog::forSection
            ClientFlavors.WOTLK.flavorId -> WowWotlkSettingsCatalog::forSection
            else -> error("no settings catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's settings-catalog group headings for a section. */
    fun settingsGroups(flavor: ClientFlavor): (WowSettingSection) -> List<String> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaSettingsCatalog::groups
            ClientFlavors.TBC.flavorId -> WowTbcSettingsCatalog::groups
            ClientFlavors.WOTLK.flavorId -> WowWotlkSettingsCatalog::groups
            else -> error("no settings catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's renderer-fixed reason copy (pure prose, lane-stable). */
    fun fixedReasonRenderer(flavor: ClientFlavor): String =
        when (flavor.flavorId) {
            ClientFlavors.TBC.flavorId -> WowTbcSettingsCatalog.FIXED_REASON_IN_GAME
            ClientFlavors.WOTLK.flavorId -> WowWotlkSettingsCatalog.FIXED_REASON_IN_GAME
            else -> WowVanillaSettingsCatalog.FIXED_REASON_RENDERER
        }

    /** The flavor's binding-catalog user-facing command list. */
    fun bindingsUserFacing(flavor: ClientFlavor): List<WowBindingDefinition> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaBindingCatalog.userFacing
            ClientFlavors.TBC.flavorId -> WowTbcBindingCatalog.userFacing
            ClientFlavors.WOTLK.flavorId -> WowWotlkBindingCatalog.userFacing
            else -> error("no binding catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's reserved keys (authored for vanilla; tbc/wotlk author
     * none yet — the honest empty set). */
    fun bindingsReservedKeys(flavor: ClientFlavor): Set<String> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaBindingCatalog.reservedKeys
            else -> emptySet()
        }

    /** The flavor's stock default keys for a command (null where the lane
     * authors none). */
    fun bindingsStockDefaultKeys(
        flavor: ClientFlavor,
        commandId: String,
    ): WowVanillaBindingCatalog.WowBindingDefaultKeys? =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId ->
                WowVanillaBindingCatalog.stockDefaultKeys(commandId)
            else -> null
        }

    /** The flavor's binding-catalog category listing. */
    fun bindingsInCategory(
        flavor: ClientFlavor,
        category: WowBindingCategory,
    ): List<WowBindingDefinition> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId ->
                WowVanillaBindingCatalog.inCategory(category)
            ClientFlavors.TBC.flavorId ->
                WowTbcBindingCatalog.inCategory(category)
            ClientFlavors.WOTLK.flavorId ->
                WowWotlkBindingCatalog.inCategory(category)
            else -> error("no binding catalog registered for flavor \"${flavor.flavorId}\"")
        }

    /** The flavor's binding-catalog search. */
    fun bindingsSearch(
        flavor: ClientFlavor,
        query: String,
        categories: Set<WowBindingCategory> = emptySet(),
        includeAdvanced: Boolean = true,
        includeInternal: Boolean = false,
    ): List<WowBindingDefinition> =
        when (flavor.flavorId) {
            ClientFlavors.VANILLA.flavorId -> WowVanillaBindingCatalog.search(
                query, categories, includeAdvanced, includeInternal,
            )
            ClientFlavors.TBC.flavorId -> WowTbcBindingCatalog.search(
                query, categories, includeAdvanced, includeInternal,
            )
            ClientFlavors.WOTLK.flavorId -> WowWotlkBindingCatalog.search(
                query, categories, includeAdvanced, includeInternal,
            )
            else -> error("no binding catalog registered for flavor \"${flavor.flavorId}\"")
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
