package com.pocketrealm.client

import com.pocketrealm.importer.SyntheticClientArchives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM unit tests for the [ClientFlavors] registry.
 *
 * The registry mirrors `schemas/flavor.json` (schema 2); these tests pin the
 * documented per-flavor values (builds, version resources, wine prefix
 * segments, addon interfaces, bot policy) so a manifest edit cannot drift from
 * the Kotlin projection, and guard the gate semantics: exactly vanilla is
 * qualified today, and every authorization resolves through
 * [ClientFlavors.isManagedClientId]. Also guards the legacy
 * [ClientRuntimeContract.WOW_5875_ID] compat constant against registry drift.
 */
class ClientFlavorTest {
    @Test
    fun identitiesAreUniqueAcrossAllFlavors() {
        assertEquals(listOf("vanilla", "tbc", "wotlk"), ClientFlavors.ALL.map { it.flavorId })
        val ids = ClientFlavors.ALL.map { it.flavorId }
        val clientIds = ClientFlavors.ALL.map { it.clientId }
        val builds = ClientFlavors.ALL.map { it.build }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(clientIds.size, clientIds.toSet().size)
        assertEquals(builds.size, builds.toSet().size)
    }

    @Test
    fun onlyVanillaIsQualified() {
        // W4 flip: all three lanes are qualified (owner default, §4 W4.4).
        assertEquals(
            listOf(ClientFlavors.VANILLA, ClientFlavors.TBC, ClientFlavors.WOTLK),
            ClientFlavors.qualified(),
        )
        assertTrue(ClientFlavors.VANILLA.isQualified)
 // Flip: the tbc/wotlk identities are qualified too.
        assertTrue(ClientFlavors.TBC.isQualified)
        assertTrue(ClientFlavors.WOTLK.isQualified)
    }

    @Test
    fun managedClientGateAuthorizesOnlyQualifiedIds() {
        assertFalse(ClientFlavors.isManagedClientId(null))
        assertTrue(ClientFlavors.isManagedClientId(ClientFlavors.VANILLA.clientId))
 // Flip: all three registered lanes authorize import/launch.
        assertTrue(ClientFlavors.isManagedClientId(ClientFlavors.TBC.clientId))
        assertTrue(ClientFlavors.isManagedClientId(ClientFlavors.WOTLK.clientId))
        assertFalse(ClientFlavors.isManagedClientId("wow-9.9.9-9999"))
    }

    @Test
    fun versionResourceResolutionCoversAllFlavorsButQualificationGatesIt() {
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.byVersionResource("1.12.1.5875"))
        assertEquals(ClientFlavors.TBC, ClientFlavors.byVersionResource("2.4.3.8606"))
        assertEquals(ClientFlavors.WOTLK, ClientFlavors.byVersionResource("3.3.5.12340"))
        assertNull(ClientFlavors.byVersionResource("9.9.9.9999"))
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.qualifiedByVersionResource("1.12.1.5875"))
 // Flip: the tbc/wotlk identities resolve qualified now.
        assertEquals(ClientFlavors.TBC, ClientFlavors.qualifiedByVersionResource("2.4.3.8606"))
        assertEquals(ClientFlavors.WOTLK, ClientFlavors.qualifiedByVersionResource("3.3.5.12340"))
        assertNull(ClientFlavors.qualifiedByVersionResource("9.9.9.9999"))
    }

    @Test
    fun requiredMpqsAreTheS51ExactSetsPerFlavor() {
        assertEquals(
            setOf(
                "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq",
                "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq", "wmo.mpq",
            ),
            ClientFlavors.VANILLA.requiredMpqs,
        )
        assertEquals(11, ClientFlavors.VANILLA.requiredMpqs.size)
        // S5.1 registration (lanes item 13): the exact sets enumerated from the
        // staged genuine clients — Data/ root plus the enus/ locale segment,
        // lowercase forward-slash paths relative to Data/ (correction).
        assertEquals(
            setOf(
                "common.mpq", "expansion.mpq", "patch.mpq", "patch-2.mpq",
                "enus/locale-enus.mpq", "enus/speech-enus.mpq",
                "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
                "enus/patch-enus.mpq", "enus/patch-enus-2.mpq",
            ),
            ClientFlavors.TBC.requiredMpqs,
        )
        assertEquals(
            setOf(
                "common.mpq", "common-2.mpq", "expansion.mpq", "lichking.mpq",
                "patch.mpq", "patch-2.mpq", "patch-3.mpq",
                "enus/locale-enus.mpq", "enus/speech-enus.mpq",
                "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
                "enus/lichking-locale-enus.mpq", "enus/lichking-speech-enus.mpq",
                "enus/patch-enus.mpq", "enus/patch-enus-2.mpq", "enus/patch-enus-3.mpq",
            ),
            ClientFlavors.WOTLK.requiredMpqs,
        )
        // No patch-4: the WotLK exact set deliberately excludes it.
        assertFalse("patch-4.mpq" in ClientFlavors.WOTLK.requiredMpqs)
    }

    @Test
    fun vanillaDatabaseNamesAreTheClassicProvenanceSet() {
        val dbs = ClientFlavors.VANILLA.databases
        assertEquals("classicrealmd", dbs.realmd)
        assertEquals("classicmangos", dbs.world)
        assertEquals("classiccharacters", dbs.characters)
        assertEquals("classiclogs", dbs.logs)
    }

    @Test
    fun documentedIdentityValuesMatchTheManifest() {
        assertEquals(5875, ClientFlavors.VANILLA.build)
        assertEquals(8606, ClientFlavors.TBC.build)
        assertEquals(12340, ClientFlavors.WOTLK.build)
        assertEquals("1.12.1.5875", ClientFlavors.VANILLA.versionResource)
        assertEquals("2.4.3.8606", ClientFlavors.TBC.versionResource)
        assertEquals("3.3.5.12340", ClientFlavors.WOTLK.versionResource)
        assertEquals("wow5875", ClientFlavors.VANILLA.winePrefixSegment)
        assertEquals("wow8606", ClientFlavors.TBC.winePrefixSegment)
        assertEquals("wow12340", ClientFlavors.WOTLK.winePrefixSegment)
        assertEquals(setOf(11200), ClientFlavors.VANILLA.addonInterfaces)
        assertEquals(setOf(20400), ClientFlavors.TBC.addonInterfaces)
        assertEquals(setOf(30300), ClientFlavors.WOTLK.addonInterfaces)
        assertEquals(60, ClientFlavors.VANILLA.botMaxLevel)
        assertEquals(70, ClientFlavors.TBC.botMaxLevel)
        assertEquals(80, ClientFlavors.WOTLK.botMaxLevel)
    }

    @Test
    fun winePrefixSegmentsAreDistinctAcrossFlavors() {
        // Two flavors must never share a wine prefix segment: the same
        // datadir invariant check_flavor.py enforces on the manifest,
        // mirrored here against the Kotlin projection.
        val segments = ClientFlavors.ALL.map { it.winePrefixSegment }
        assertEquals(segments.size, segments.toSet().size)
    }

    @Test
    fun legacyWow5875IdStaysPinnedToTheVanillaRegistry() {
        // The pre-registry constant survives only as a derived alias; if this
        // drifts, old call sites would authorize a client id the gate rejects.
        assertEquals(ClientFlavors.VANILLA.clientId, ClientRuntimeContract.WOW_5875_ID)
        assertTrue(ClientFlavors.isManagedClientId(ClientRuntimeContract.WOW_5875_ID))
    }

    @Test
    fun serverFamilyAndExpansionStringsMatchTheDeclaredEras() {
        // server_family is the server-core family the flavor's data manifests pin.
        assertEquals("classic", ClientFlavors.VANILLA.serverFamily)
        assertEquals("tbc", ClientFlavors.TBC.serverFamily)
        assertEquals("wotlk", ClientFlavors.WOTLK.serverFamily)
        assertEquals("Vanilla / Classic", ClientFlavors.VANILLA.expansion)
        assertEquals("The Burning Crusade", ClientFlavors.TBC.expansion)
        assertEquals("Wrath of the Lich King", ClientFlavors.WOTLK.expansion)
    }

    @Test
    fun knownExeSha256IsRegisteredForEveryFlavor() {
        // Pinned literals of the documented pristine WoW.exe SHA-256s
        // (schemas/flavor.json wine.known_exe_sha256): vanilla's inspected
        // 5875 copy plus the S5.1 registrations measured on the staged
        // genuine tbc/wotlk clients (lanes item 13). A registry edit cannot
        // silently swap the warning-only telltale.
        assertEquals(
            "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7",
            ClientFlavors.VANILLA.knownExeSha256,
        )
        assertEquals(
            "8f8d7f4cf3909e61fd34b09df9c9b56c21aec76a9ad1883353f1fa5d9b8411e2",
            ClientFlavors.TBC.knownExeSha256,
        )
        assertEquals(
            "aa63a5750d60ef16746c686b3d5e26876d98953eab08b1c026cd0faf78e88cb8",
            ClientFlavors.WOTLK.knownExeSha256,
        )
    }

    @Test
    fun vanillaStorageBudgetsArePinnedAndUnqualifiedFlavorsHaveNone() {
        // schemas/flavor.json storage: extracted_estimate_bytes 6 GiB
        // (raised to cover the measured 5.15 GiB client tree),
        // max_total_bytes 20 GiB for the shipped lane.
        assertEquals(6L * 1024L * 1024L * 1024L, ClientFlavors.VANILLA.extractedEstimateBytes)
        assertEquals(20L * 1024L * 1024L * 1024L, ClientFlavors.VANILLA.maxTotalBytes)
        // Null until measured for the flavor (port plan S5).
 // Flip: the estimates cover the measured client trees (the
        // managed-copy floor) — 12 GiB tbc / 24 GiB wotlk; caps 24/40 GiB.
        assertEquals(12L * 1024L * 1024L * 1024L, ClientFlavors.TBC.extractedEstimateBytes)
        assertEquals(24L * 1024L * 1024L * 1024L,
            ClientFlavors.TBC.maxTotalBytes)
        assertEquals(24L * 1024L * 1024L * 1024L, ClientFlavors.WOTLK.extractedEstimateBytes)
        assertEquals(40L * 1024L * 1024L * 1024L,
            ClientFlavors.WOTLK.maxTotalBytes)
    }

    @Test
    fun declaredFlavorDatabaseNamesFollowTheFlavorSlug() {
        // Vanilla's classic* provenance set is pinned above; the declared lanes
        // follow the same <slug>* convention for when seeds their databases.
        val tbc = ClientFlavors.TBC.databases
        assertEquals("tbcrealmd", tbc.realmd)
        assertEquals("tbcmangos", tbc.world)
        assertEquals("tbccharacters", tbc.characters)
        assertEquals("tbclogs", tbc.logs)
        val wotlk = ClientFlavors.WOTLK.databases
        assertEquals("wotlkrealmd", wotlk.realmd)
        assertEquals("wotlkmangos", wotlk.world)
        assertEquals("wotlkcharacters", wotlk.characters)
        assertEquals("wotlklogs", wotlk.logs)
    }

    @Test
    fun clientIdAndBuildResolversResolveEveryFlavorAndRejectUnknowns() {
        ClientFlavors.ALL.forEach { flavor ->
            assertEquals(flavor, ClientFlavors.byClientId(flavor.clientId))
            assertEquals(flavor, ClientFlavors.byBuild(flavor.build))
        }
        assertNull(ClientFlavors.byClientId("wow-9.9.9-9999"))
        assertNull(ClientFlavors.byClientId(""))
        assertNull(ClientFlavors.byBuild(9999))
    }

    @Test
    fun botRandomMapsFollowTheExpansionContinentSet() {
        assertEquals(listOf(0, 1), ClientFlavors.VANILLA.botRandomMaps)
        assertEquals(listOf(0, 1, 530), ClientFlavors.TBC.botRandomMaps)
        assertEquals(listOf(0, 1, 530, 571), ClientFlavors.WOTLK.botRandomMaps)
    }

    @Test
    fun planCorrectionsC1C3C7C8ArePinnedInTheRegistry() {
        // realmbuilds accepted sets (never a gamebuild column).
        assertEquals("5875 6005 6141", ClientFlavors.VANILLA.realmdAcceptedBuilds)
        assertEquals("8606", ClientFlavors.TBC.realmdAcceptedBuilds)
        assertEquals("12340", ClientFlavors.WOTLK.realmdAcceptedBuilds)
        // ONE shared auth-challenge opcode; every branch's frame length is
        // verified (S8, lanes item 14): vanilla 26 (BUILD_6005 form), tbc/wotlk
        // 32 (sAuthLogonProof_S: cmd+error+M2[20]+accountFlags+surveyId+unkFlags).
        assertEquals(0x1EC, ClientFlavors.AUTH_CHALLENGE_OPCODE)
        assertEquals(26, ClientFlavors.VANILLA.proofResponseBytes)
        assertEquals(32, ClientFlavors.TBC.proofResponseBytes)
        assertEquals(32, ClientFlavors.WOTLK.proofResponseBytes)
        // shipped 5875, 8606 and 12340 images are all NOT large-address-
        // aware — tbc/wotlk measured at S5.1 from the staged exes' PE
        // characteristics (no 0x0020 LAA bit).
        assertEquals(false, ClientFlavors.VANILLA.largeAddressAware)
        assertEquals(false, ClientFlavors.TBC.largeAddressAware)
        assertEquals(false, ClientFlavors.WOTLK.largeAddressAware)
        // WTF layouts and the WotLK-only char-level cache set.
        assertEquals("account-level", ClientFlavors.VANILLA.wtfLayout)
        assertEquals("3-level", ClientFlavors.TBC.wtfLayout)
        assertEquals("3-level", ClientFlavors.WOTLK.wtfLayout)
        assertTrue(ClientFlavors.VANILLA.wtfCharacterCacheFiles.isEmpty())
        assertTrue(ClientFlavors.TBC.wtfCharacterCacheFiles.isEmpty())
        assertEquals(
            setOf(
                "config-cache.wtf", "bindings-cache.wtf", "macros-cache.txt",
                "layout-local.txt", "chat-cache.txt",
            ),
            ClientFlavors.WOTLK.wtfCharacterCacheFiles,
        )
    }

    @Test
    fun planCorrectionC6ExeProvenanceIsRegisteredPerFlavor() {
        // vanilla's pristine-exe size is the pinned installer fact (its
        // MD5 cross-check stays unregistered); tbc/wotlk carry the full S5.1
        // registrations measured on the staged genuine clients (lanes item 13)
        // — sizes and MD5s equal the anzz1 pristine prefixes.
        assertEquals(4775986L, ClientFlavors.VANILLA.knownExeSizeBytes)
        assertNull(ClientFlavors.VANILLA.knownExeMd5)
        assertEquals(8272528L, ClientFlavors.TBC.knownExeSizeBytes)
        assertEquals("57c5c03097103e15f9abe2803aebdc3c", ClientFlavors.TBC.knownExeMd5)
        assertEquals(7704216L, ClientFlavors.WOTLK.knownExeSizeBytes)
        assertEquals("45892bdedd0ad70aed4ccd22d9fb5984", ClientFlavors.WOTLK.knownExeMd5)
    }

    @Test
    fun detectedMessageForParsedButUnmatchedIdentityNamesTheResource() {
        // A version resource no qualified flavor claims still parses: the
        // identity carries the detected version with flavorId null, and the
        // VAL-03 detected copy names the resource plus the rejection reason.
        val identity = ClientPeIdentity.parse(SyntheticClientArchives.syntheticPe(build = 8606))
        assertEquals("1.12.1.8606", identity.version)
        assertEquals(8606, identity.build)
        assertNull(identity.flavorId)
        val message = ClientPeIdentity.detected(identity)
        assertTrue(message.contains("no qualified flavor matches"))
        assertTrue(message.contains(identity.version!!))
    }

    @Test
    fun accountExpansionLevelsFollowTheCoreExpansionDomain() {
        // F1-10 (lanes item 12): the account-side expansion level the
        // core's `account set addon` console verb writes — the values the
        // account-creation wirings chain after a fresh create, resolved
        // through the registry so a callsite literal can never drift from
        // the flavor data.
        assertEquals(0, ClientFlavors.VANILLA.accountExpansion)
        assertEquals(1, ClientFlavors.TBC.accountExpansion)
        assertEquals(2, ClientFlavors.WOTLK.accountExpansion)
        // An unregistered family fails loud instead of guessing a level.
        val stranger = ClientFlavor(
            flavorId = "stranger", serverFamily = "somewhere-else",
            clientId = "wow-9.9.9-9999", build = 9999, versionResource = "9.9.9.9999",
            expansion = "Stranger Things", qualified = false,
            databases = ClientFlavor.DatabaseNames(
                realmd = "strangerrealmd", world = "strangermangos",
                characters = "strangercharacters", logs = "strangerlogs"),
            addonInterfaces = emptySet(), winePrefixSegment = "wow9999",
            knownExeSha256 = null, requiredMpqs = emptySet(),
            extractedEstimateBytes = null, maxTotalBytes = null,
            botMaxLevel = 60, botRandomMaps = listOf(0),
            realmdAcceptedBuilds = "9999", proofResponseBytes = null,
            wtfLayout = "account-level", wtfCharacterCacheFiles = emptySet(),
            knownExeMd5 = null, knownExeSizeBytes = null, largeAddressAware = null,
            loreCardsFile = "lore_cards_v112.jsonl", promptIdentity = "WoW: Stranger",
            graphicsRoutes = setOf("dxvk"), vanillaTweaksEnabled = false,
        )
        val failure = runCatching { stranger.accountExpansion }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("somewhere-else"))
    }
}
