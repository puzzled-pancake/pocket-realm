package com.pocketrealm.client

/**
 * Single source of truth for the supported game flavors (vanilla / TBC / WotLK).
 *
 * This registry mirrors `schemas/flavor.json` (schema 2). Every client-identity
 * gate — import scanners, managed stores, runtime authorization — must resolve
 * through [ClientFlavors] instead of comparing against a hard-coded id, so that
 * qualifying a new flavor (port plan stage S5) is a data change here, not a
 * code sweep.
 *
 * A flavor with [ClientFlavor.qualified] == false is declared but disabled:
 * its data is informational, import rejects it, and no runtime lane may
 * authorize it. Only the vanilla lane is qualified today.
 */
data class ClientFlavor(
    /** Flavor slug matching schemas/flavor.json: "vanilla", "tbc", "wotlk". */
    val flavorId: String,
    /** Server-core family the flavor's data manifests pin ("classic", "tbc", "wotlk"). */
    val serverFamily: String,
    /** Managed-copy identity, e.g. "wow-1.12.1-5875". */
    val clientId: String,
    /** Client build number, e.g. 5875. */
    val build: Int,
    /** VS_VERSIONINFO product string the PE parser must match, e.g. "1.12.1.5875". */
    val versionResource: String,
    val expansion: String,
    /** Import/launch gate: false means declared-but-disabled. */
    val qualified: Boolean,
    /** Per-flavor database names the server runtime is seeded with. */
    val databases: DatabaseNames,
    /** Add-on `## Interface:` numbers this client's UI API accepts. */
    val addonInterfaces: Set<Int>,
    /** Wine prefix path segment for this client's managed profiles. */
    val winePrefixSegment: String,
    /** Known-pristine WoW.exe SHA-256 (warning-only telltale; may be null until qualified). */
    val knownExeSha256: String?,
    /** Base MPQ set the importer requires under Data/ (empty until qualification). */
    val requiredMpqs: Set<String>,
    /** Storage planner budgets in bytes (null until measured for this flavor). */
    val extractedEstimateBytes: Long?,
    val maxTotalBytes: Long?,
    /** Bot population policy values for this flavor's playerbots build. */
    val botMaxLevel: Int,
    val botRandomMaps: List<Int>,
) {
    data class DatabaseNames(
        val realmd: String,
        val world: String,
        val characters: String,
        val logs: String,
    )

    val isQualified: Boolean get() = qualified
}

object ClientFlavors {
    /** Vanilla 1.12.1 build 5875 — the shipped, fully qualified lane. */
    val VANILLA: ClientFlavor = ClientFlavor(
        flavorId = "vanilla",
        serverFamily = "classic",
        clientId = "wow-1.12.1-5875",
        build = 5875,
        versionResource = "1.12.1.5875",
        expansion = "Vanilla / Classic",
        qualified = true,
        databases = ClientFlavor.DatabaseNames(
            realmd = "classicrealmd",
            world = "classicmangos",
            characters = "classiccharacters",
            logs = "classiclogs",
        ),
        addonInterfaces = setOf(11200),
        winePrefixSegment = "wow5875",
        knownExeSha256 = "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7",
        requiredMpqs = setOf(
            "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq",
            "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq", "wmo.mpq",
        ),
        extractedEstimateBytes = 4L * 1024L * 1024L * 1024L,
        maxTotalBytes = 20L * 1024L * 1024L * 1024L,
        botMaxLevel = 60,
        botRandomMaps = listOf(0, 1),
    )

    /**
     * TBC 2.4.3 build 8606 — declared, disabled pending port plan stages S2–S8
     * (source pins, DB content, client hash qualification). Import must reject.
     */
    val TBC: ClientFlavor = ClientFlavor(
        flavorId = "tbc",
        serverFamily = "tbc",
        clientId = "wow-2.4.3-8606",
        build = 8606,
        versionResource = "2.4.3.8606",
        expansion = "The Burning Crusade",
        qualified = false,
        databases = ClientFlavor.DatabaseNames(
            realmd = "tbcrealmd",
            world = "tbcmangos",
            characters = "tbccharacters",
            logs = "tbclogs",
        ),
        addonInterfaces = setOf(20400),
        winePrefixSegment = "wow8606",
        knownExeSha256 = null,
        requiredMpqs = emptySet(),
        extractedEstimateBytes = null,
        maxTotalBytes = null,
        botMaxLevel = 70,
        botRandomMaps = listOf(0, 1, 530),
    )

    /**
     * WotLK 3.3.5a build 12340 — declared, disabled pending port plan stages
     * S2–S8. Import must reject.
     */
    val WOTLK: ClientFlavor = ClientFlavor(
        flavorId = "wotlk",
        serverFamily = "wotlk",
        clientId = "wow-3.3.5a-12340",
        build = 12340,
        versionResource = "3.3.5.12340",
        expansion = "Wrath of the Lich King",
        qualified = false,
        databases = ClientFlavor.DatabaseNames(
            realmd = "wotlkrealmd",
            world = "wotlkmangos",
            characters = "wotlkcharacters",
            logs = "wotlklogs",
        ),
        addonInterfaces = setOf(30300),
        winePrefixSegment = "wow12340",
        knownExeSha256 = null,
        requiredMpqs = emptySet(),
        extractedEstimateBytes = null,
        maxTotalBytes = null,
        botMaxLevel = 80,
        botRandomMaps = listOf(0, 1, 530, 571),
    )

    val ALL: List<ClientFlavor> = listOf(VANILLA, TBC, WOTLK)

    /** Flavors the app may import and launch today. */
    fun qualified(): List<ClientFlavor> = ALL.filter { it.qualified }

    fun byClientId(clientId: String): ClientFlavor? = ALL.firstOrNull { it.clientId == clientId }

    fun byBuild(build: Int): ClientFlavor? = ALL.firstOrNull { it.build == build }

    fun byVersionResource(version: String): ClientFlavor? =
        ALL.firstOrNull { it.versionResource == version }

    /**
     * True when [clientId] names a flavor the app is allowed to import and run.
     * Every managed-client authorization gate resolves through this — never
     * compare against a single flavor's id.
     */
    fun isManagedClientId(clientId: String?): Boolean =
        clientId != null && byClientId(clientId)?.qualified == true

    /** Qualified flavor matching a PE version resource, or null (import rejection). */
    fun qualifiedByVersionResource(version: String): ClientFlavor? =
        byVersionResource(version)?.takeIf { it.qualified }
}
