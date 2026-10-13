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
 * authorize it. All three lanes are qualified since the W4 flip (owner
 * default, completion-plan §4 W4.4 / decision D86): with several qualified
 * flavors an ABSENT active selection refuses loud
 * ([com.pocketrealm.client.ActiveFlavorState.resolve]) — the Home flavor
 * cards own the first pick, and the pick persists in settings.
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
    /** Known-pristine WoW.exe SHA-256 (warning-only telltale; null until S5.1 registration). */
    val knownExeSha256: String?,
    /**
     * Base MPQ set the importer requires, as lowercase paths relative to
     * `Data/` with forward slashes (vanilla's flat layout carries no path
     * segment; tbc/wotlk add the `enus/` locale segment — S5 item 5 owns the
     * scanners' Data/&lt;locale&gt; traversal). Registered per flavor at S5.1.
     */
    val requiredMpqs: Set<String>,
    /** Storage planner budgets in bytes (null until measured for this flavor). */
    val extractedEstimateBytes: Long?,
    val maxTotalBytes: Long?,
    /** Bot population policy values for this flavor's playerbots build. */
    val botMaxLevel: Int,
    val botRandomMaps: List<Int>,
    /**
     * Correction C1: the build set this flavor's realmd advertises via
     * `realmlist.realmbuilds` varchar(64) (never `gamebuild` — that column
     * is TrinityCore's schema and may not exist anywhere). Mirrors
     * `schemas/flavor.json` `realmd.accepted_builds`.
     */
    val realmdAcceptedBuilds: String,
    /**
     * Correction C3: verified proof-response frame length for this flavor's
     * SRP6 logon (vanilla: 26 bytes — cmd+error+M2+LoginFlags; tbc/wotlk: 32
     * bytes — cmd+error+M2+accountFlags+surveyId+unkFlags, verified against
     * the branches' AuthSocket.cpp at S8). The auth-challenge OPCODE itself
     * is the shared [ClientFlavors.AUTH_CHALLENGE_OPCODE] constant, never a
     * per-flavor value.
     */
    val proofResponseBytes: Int?,
    /** Correction C8: WTF write-out layout ("account-level" / "3-level"). */
    val wtfLayout: String,
    /**
     * Correction C8: the WotLK-era char-level cache-file set; empty for
     * vanilla (account-level) and TBC (3-level without the cache set).
     */
    val wtfCharacterCacheFiles: Set<String>,
    /** Correction C6: pristine-exe MD5 cross-check (32 hex) — null until S5.1 registration. */
    val knownExeMd5: String?,
    /** Correction C6: pristine-exe size in bytes — null until measured at S5.1. */
    val knownExeSizeBytes: Long?,
    /**
     * Correction C7: whether the shipped client image is large-address-aware.
     * Vanilla false (the LAA tweak patch exists because the 5875 exe is not),
     * WotLK false (register §0 C7: 3.3.5a is NOT LAA — 2 GB VA), TBC false
     * (measured at S5.1 from the staged 8606 exe's PE characteristics — no
     * 0x0020 LAA bit).
     */
    val largeAddressAware: Boolean?,
    /**
     * W3.2 (D15(5) retirement): the lore-corpus asset this flavor's LLM lane
     * loads — the manifest's `llm.lore_cards` binding. The owner decision
     * binds every flavor to the ONE shared corpus asset (no per-flavor
     * jsonl); the per-flavor era filtering is the native load-time lint
     * against the active stage's ban lists (PlayerbotLlmTruthCore
     * EraLintCards), so the FILE name is shared while the FILTER is
     * flavor-routed. ServerRuntimeFiles resolves its staged-file name
     * through this field instead of a duplicated const.
     */
    val loreCardsFile: String,
    /**
     * W3.2: the prompt-pack identity line's world title per flavor (the
     * manifest's `llm.prompt_identity`): "World of Warcraft: Classic" /
     * "…: The Burning Crusade" / "…: Wrath of the Lich King". Consumed by
     * the LlmPromptPack editor preview and (via the generated
     * ExpansionTitleFor selector) the native trained renderer; the vanilla
     * output stays byte-identical (trained-output freeze).
     */
    val promptIdentity: String,
    /**
     * W3.6 (D15(4) retirement): the graphics routes the flavor's client is
     * validated for — the manifest's `wine.graphics_routes`. Vanilla ships
     * the DXVK + Legacy OpenGL (Gladio) + Mesa VirGL lanes; tbc/wotlk ship
     * DXVK ONLY (decision D3: the GL lanes are validated against vanilla's
     * GL usage, never the later clients' D3D9). Consumed by
     * ClientRuntimeContract.requireFlavorGraphicsRoute at the prepare gate
     * with the honest per-flavor refusal copy.
     */
    val graphicsRoutes: Set<String>,
    /**
     * W3.6: whether the vanilla-tweaks feature set is available for the
     * flavor — the manifest's `wine.vanilla_tweaks` (true for the 1.12.1
     * lane, false for tbc/wotlk; the tweak set is validated against the
     * 1.12.1 client only). Surfaces render the honest "not available for
     * this flavor" copy from ClientRuntimeContract.flavorTweaksCopy.
     */
    val vanillaTweaksEnabled: Boolean,
) {
    data class DatabaseNames(
        val realmd: String,
        val world: String,
        val characters: String,
        val logs: String,
    )

    val isQualified: Boolean get() = qualified

    /**
     * F1-10 (lanes item 12): the account-side expansion level the core's
     * `account set addon` console verb writes — the `account.expansion`
     * column value the core's expansion gating reads per account row
     * (0 = classic, 1 = tbc, 2 = wotlk). Resolved from [serverFamily]
     * (the core family whose gating consumes it), never a per-callsite
     * literal, so a new flavor fails loud here until its family is
     * registered — the same law as every other client-identity gate.
     */
    val accountExpansion: Int
        get() = when (serverFamily) {
            "classic" -> 0
            "tbc" -> 1
            "wotlk" -> 2
            else -> error("server family \"$serverFamily\" has no account expansion level")
        }
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
        realmdAcceptedBuilds = "5875 6005 6141",
        proofResponseBytes = 26,
        wtfLayout = "account-level",
        wtfCharacterCacheFiles = emptySet(),
        knownExeMd5 = null,
        knownExeSizeBytes = 4775986L,
        largeAddressAware = false,
        loreCardsFile = "lore_cards_v112.jsonl",
        promptIdentity = "World of Warcraft: Classic",
        graphicsRoutes = setOf("dxvk", "opengl", "virgl"),
        vanillaTweaksEnabled = true,
    )

    /**
     * TBC 2.4.3 build 8606 — qualified at the W4 flip (owner default,
     * completion-plan §4 W4.4 / decision D86). GATED: the flip commits only
     * when the W4.1 desktop end-to-end, the W4.2 3×3 auth matrix, and the
     * W4.3 friend-arc evidence are green (D86's flip-gate paragraph).
     * Storage budgets are the W4 measured values (the manifest's
     * storage.note carries the measurement provenance).
     */
    val TBC: ClientFlavor = ClientFlavor(
        flavorId = "tbc",
        serverFamily = "tbc",
        clientId = "wow-2.4.3-8606",
        build = 8606,
        versionResource = "2.4.3.8606",
        expansion = "The Burning Crusade",
        qualified = true,
        databases = ClientFlavor.DatabaseNames(
            realmd = "tbcrealmd",
            world = "tbcmangos",
            characters = "tbccharacters",
            logs = "tbclogs",
        ),
        addonInterfaces = setOf(20400),
        winePrefixSegment = "wow8606",
        knownExeSha256 = "8f8d7f4cf3909e61fd34b09df9c9b56c21aec76a9ad1883353f1fa5d9b8411e2",
        requiredMpqs = setOf(
            "common.mpq", "expansion.mpq", "patch.mpq", "patch-2.mpq",
            "enus/locale-enus.mpq", "enus/speech-enus.mpq",
            "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
            "enus/patch-enus.mpq", "enus/patch-enus-2.mpq",
        ),
        extractedEstimateBytes = 12L * 1024L * 1024L * 1024L,
        maxTotalBytes = 24L * 1024L * 1024L * 1024L,
        botMaxLevel = 70,
        botRandomMaps = listOf(0, 1, 530),
        realmdAcceptedBuilds = "8606",
        proofResponseBytes = 32,
        wtfLayout = "3-level",
        wtfCharacterCacheFiles = emptySet(),
        knownExeMd5 = "57c5c03097103e15f9abe2803aebdc3c",
        knownExeSizeBytes = 8272528L,
        largeAddressAware = false,
        loreCardsFile = "lore_cards_v112.jsonl",
        promptIdentity = "World of Warcraft: The Burning Crusade",
        graphicsRoutes = setOf("dxvk"),
        vanillaTweaksEnabled = false,
    )

    /**
     * WotLK 3.3.5a build 12340 — qualified at the W4 flip (owner default,
     * completion-plan §4 W4.4 / decision D86). GATED: the flip commits only
     * when the W4.1 desktop end-to-end, the W4.2 3×3 auth matrix, and the
     * W4.3 friend-arc evidence are green (D86's flip-gate paragraph).
     * Storage budgets are the W4 measured values (the manifest's
     * storage.note carries the measurement provenance).
     */
    val WOTLK: ClientFlavor = ClientFlavor(
        flavorId = "wotlk",
        serverFamily = "wotlk",
        clientId = "wow-3.3.5a-12340",
        build = 12340,
        versionResource = "3.3.5.12340",
        expansion = "Wrath of the Lich King",
        qualified = true,
        databases = ClientFlavor.DatabaseNames(
            realmd = "wotlkrealmd",
            world = "wotlkmangos",
            characters = "wotlkcharacters",
            logs = "wotlklogs",
        ),
        addonInterfaces = setOf(30300),
        winePrefixSegment = "wow12340",
        knownExeSha256 = "aa63a5750d60ef16746c686b3d5e26876d98953eab08b1c026cd0faf78e88cb8",
        requiredMpqs = setOf(
            "common.mpq", "common-2.mpq", "expansion.mpq", "lichking.mpq",
            "patch.mpq", "patch-2.mpq", "patch-3.mpq",
            "enus/locale-enus.mpq", "enus/speech-enus.mpq",
            "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
            "enus/lichking-locale-enus.mpq", "enus/lichking-speech-enus.mpq",
            "enus/patch-enus.mpq", "enus/patch-enus-2.mpq", "enus/patch-enus-3.mpq",
        ),
        extractedEstimateBytes = 24L * 1024L * 1024L * 1024L,
        maxTotalBytes = 40L * 1024L * 1024L * 1024L,
        botMaxLevel = 80,
        botRandomMaps = listOf(0, 1, 530, 571),
        realmdAcceptedBuilds = "12340",
        proofResponseBytes = 32,
        wtfLayout = "3-level",
        wtfCharacterCacheFiles = setOf(
            "config-cache.wtf",
            "bindings-cache.wtf",
            "macros-cache.txt",
            "layout-local.txt",
            "chat-cache.txt",
        ),
        knownExeMd5 = "45892bdedd0ad70aed4ccd22d9fb5984",
        knownExeSizeBytes = 7704216L,
        largeAddressAware = false,
        loreCardsFile = "lore_cards_v112.jsonl",
        promptIdentity = "World of Warcraft: Wrath of the Lich King",
        graphicsRoutes = setOf("dxvk"),
        vanillaTweaksEnabled = false,
    )

    val ALL: List<ClientFlavor> = listOf(VANILLA, TBC, WOTLK)

    /**
     * Correction C3 (register §0): `SMSG_AUTH_CHALLENGE` = 0x1EC in ALL THREE
     * branches — ONE shared constant, never per-flavor. The wire harnesses
     * (desktop RpWowClient, rp_harness probes) assert this value and fail
     * loud on any other; per-branch SRP6 frame LAYOUTS are the per-flavor
     * [ClientFlavor.proofResponseBytes] descriptors verified at S8. Mirror:
     * schemas/flavor.json top-level `auth` block.
     */
    const val AUTH_CHALLENGE_OPCODE: Int = 0x1EC

    /** Flavors the app may import and launch today. */
    fun qualified(): List<ClientFlavor> = ALL.filter { it.qualified }

    fun byClientId(clientId: String): ClientFlavor? = ALL.firstOrNull { it.clientId == clientId }

    /**
     * Flavor owning a server-core family ("classic"/"tbc"/"wotlk") — the
     * flavor dimension the o11 data manifests pin as `cmangosFamily` (G3-2/G3-5).
     */
    fun byServerFamily(family: String): ClientFlavor? = ALL.firstOrNull { it.serverFamily == family }

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
