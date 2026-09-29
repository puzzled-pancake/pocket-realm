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
        assertEquals(listOf(ClientFlavors.VANILLA), ClientFlavors.qualified())
        assertTrue(ClientFlavors.VANILLA.isQualified)
        // Declared-but-disabled: import must reject until port plan S5 qualifies them.
        assertFalse(ClientFlavors.TBC.isQualified)
        assertFalse(ClientFlavors.WOTLK.isQualified)
    }

    @Test
    fun managedClientGateAuthorizesOnlyQualifiedIds() {
        assertFalse(ClientFlavors.isManagedClientId(null))
        assertTrue(ClientFlavors.isManagedClientId(ClientFlavors.VANILLA.clientId))
        assertFalse(ClientFlavors.isManagedClientId(ClientFlavors.TBC.clientId))
        assertFalse(ClientFlavors.isManagedClientId(ClientFlavors.WOTLK.clientId))
        assertFalse(ClientFlavors.isManagedClientId("wow-9.9.9-9999"))
    }

    @Test
    fun versionResourceResolutionCoversAllFlavorsButQualificationGatesIt() {
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.byVersionResource("1.12.1.5875"))
        assertEquals(ClientFlavors.TBC, ClientFlavors.byVersionResource("2.4.3.8606"))
        assertEquals(ClientFlavors.WOTLK, ClientFlavors.byVersionResource("3.3.5.12340"))
        assertNull(ClientFlavors.byVersionResource("9.9.9.9999"))
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.qualifiedByVersionResource("1.12.1.5875"))
        assertNull(ClientFlavors.qualifiedByVersionResource("2.4.3.8606"))
        assertNull(ClientFlavors.qualifiedByVersionResource("3.3.5.12340"))
        assertNull(ClientFlavors.qualifiedByVersionResource("9.9.9.9999"))
    }

    @Test
    fun requiredMpqsAreVanillaOnlyUntilQualification() {
        assertEquals(
            setOf(
                "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq",
                "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq", "wmo.mpq",
            ),
            ClientFlavors.VANILLA.requiredMpqs,
        )
        assertEquals(11, ClientFlavors.VANILLA.requiredMpqs.size)
        // Empty until the genuine qualified client derives the set (port plan S5).
        assertTrue(ClientFlavors.TBC.requiredMpqs.isEmpty())
        assertTrue(ClientFlavors.WOTLK.requiredMpqs.isEmpty())
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
    fun knownExeSha256IsVanillaOnlyUntilQualification() {
        // Pinned literal of the documented pristine 1.12.1 WoW.exe
        // (schemas/flavor.json wine.known_exe_sha256); a registry edit cannot
        // silently swap the warning-only telltale.
        assertEquals(
            "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7",
            ClientFlavors.VANILLA.knownExeSha256,
        )
        // Null until the genuine qualified client pins the hash (port plan S5).
        assertNull(ClientFlavors.TBC.knownExeSha256)
        assertNull(ClientFlavors.WOTLK.knownExeSha256)
    }

    @Test
    fun vanillaStorageBudgetsArePinnedAndUnqualifiedFlavorsHaveNone() {
        // schemas/flavor.json storage: extracted_estimate_bytes 4 GiB,
        // max_total_bytes 20 GiB for the shipped lane.
        assertEquals(4L * 1024L * 1024L * 1024L, ClientFlavors.VANILLA.extractedEstimateBytes)
        assertEquals(20L * 1024L * 1024L * 1024L, ClientFlavors.VANILLA.maxTotalBytes)
        // Null until measured for the flavor (port plan S5).
        assertNull(ClientFlavors.TBC.extractedEstimateBytes)
        assertNull(ClientFlavors.TBC.maxTotalBytes)
        assertNull(ClientFlavors.WOTLK.extractedEstimateBytes)
        assertNull(ClientFlavors.WOTLK.maxTotalBytes)
    }

    @Test
    fun declaredFlavorDatabaseNamesFollowTheFlavorSlug() {
        // Vanilla's classic* provenance set is pinned above; the declared lanes
        // follow the same <slug>* convention for when S5 seeds their databases.
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
}
