package com.pocketrealm.server

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/**
 * The desktop leg of DESK-128 (per-flavor prepared-data pointer namespace &
 * manifest identity) over the shared PreparedDataStore. The Windows lane's
 * publication source is `tools/win_prepare_data.py`: with the DESK-128
 * `--flavor` argument it writes the canonical `active-<flavorId>.json`
 * pointer (keeping the un-namespaced `active.json` mirror for one
 * transition release) and the manifest `flavor` alias key. These legs pin
 * what the desktop lane (ServerRuntimeFiles' shared store) resolves:
 *
 *  - canonical-pointer resolution for the qualified lane (the tool's exact
 *    two-pointer publication, alias present);
 *  - the copy-then-adopt of a pre-DESK-128 legacy-only datadir (the
 *    transition-release contract: old publications keep resolving);
 *  - two flavors' lanes coexisting in one datadir and resolving per lane —
 *    "vanilla can never authenticate onto a TBC generation" holds in BOTH
 *    directions (identity fields AND the alias key).
 */
class DesktopPreparedDataPointerTest {
    @Test fun canonicalPerFlavorPointerResolvesForTheQualifiedLane() {
        val root = Files.createTempDirectory("o11-desk128-canonical").toFile()
        try {
            val id = UUID.randomUUID().toString()
            val manifestSha = stageGeneration(root, ClientFlavors.VANILLA, id)
            // The exact two-pointer publication win_prepare_data.py writes:
            // identical content under the canonical and the legacy name,
            // manifest carrying the DESK-128 flavor alias.
            val pointer = JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", manifestSha).toString()
            File(root, "active-${ClientFlavors.VANILLA.flavorId}.json").writeText(pointer)
            File(root, "active.json").writeText(pointer)

            val store = PreparedDataStore(root)
            assertEquals(id, store.requireActiveEnvelope().generation)
            assertEquals(id, store.requireActive().generation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun legacyMirrorAdoptsIntoTheCanonicalNamespace() {
        val root = Files.createTempDirectory("o11-desk128-adopt").toFile()
        try {
            // A pre-DESK-128 datadir: ONLY the un-namespaced pointer, and a
            // manifest without the alias key (optString default — the
            // transition release keeps old publications playable).
            val id = UUID.randomUUID().toString()
            val manifestSha = stageGeneration(root, ClientFlavors.VANILLA, id, GenerationFixture(withAlias = false))
            File(root, "active.json").writeText(JSONObject().put("schema", 1)
                .put("mode", "NORMAL").put("generation", id)
                .put("manifestSha256", manifestSha).toString())

            assertEquals(id, PreparedDataStore(root).requireActive().generation)
            // Copy-then-adopt (PreparedDataStore.activePointerFor): the
            // canonical name now exists and the legacy source stays intact.
            assertTrue("canonical pointer adopted",
                File(root, "active-${ClientFlavors.VANILLA.flavorId}.json").isFile)
            assertTrue("legacy mirror kept for concurrent readers",
                File(root, "active.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun twoFlavorLanesCoexistAndResolvePerLane() {
        val root = Files.createTempDirectory("o11-desk128-two").toFile()
        try {
            val vanillaId = UUID.randomUUID().toString()
            val tbcId = UUID.randomUUID().toString()
            val vanillaSha = stageGeneration(root, ClientFlavors.VANILLA, vanillaId)
            val tbcSha = stageGeneration(root, ClientFlavors.TBC, tbcId)
            File(root, "active-${ClientFlavors.VANILLA.flavorId}.json").writeText(
                pointerAt(vanillaId, vanillaSha))
            File(root, "active-${ClientFlavors.TBC.flavorId}.json").writeText(
                pointerAt(tbcId, tbcSha))

            // Both lanes live side by side in one datadir and each resolves
            // ITS OWN generation through its own canonical pointer — one
            // publication no longer overwrites the other flavor's lane.
            val store = PreparedDataStore(root)
            assertEquals(vanillaId, store.requireActiveEnvelope(ClientFlavors.VANILLA).generation)
            assertEquals(tbcId, store.requireActiveEnvelope(ClientFlavors.TBC).generation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun vanillaLaneCanNeverAuthenticateOntoATbcGeneration() {
        val root = Files.createTempDirectory("o11-desk128-cross").toFile()
        try {
            // The canonical vanilla pointer names a TBC-identity generation:
            // the envelope's clientBuild/cmangosFamily identity gate refuses.
            val tbcId = UUID.randomUUID().toString()
            val tbcSha = stageGeneration(root, ClientFlavors.TBC, tbcId)
            File(root, "active-${ClientFlavors.VANILLA.flavorId}.json").writeText(
                pointerAt(tbcId, tbcSha))
            val identity = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope()
            }
            assertTrue(identity.message.orEmpty().contains("incomplete or incompatible"))

            // A vanilla-identity manifest that CLAIMS the tbc alias is
            // equally refused — the alias check is its own fail-closed gate
            // (validateDataManifestEnvelope), not decoration.
            val vanillaId = UUID.randomUUID().toString()
            val lyingSha = stageGeneration(
                root, ClientFlavors.VANILLA, vanillaId,
                GenerationFixture(aliasOverride = "tbc"))
            File(root, "active-${ClientFlavors.VANILLA.flavorId}.json").writeText(
                pointerAt(vanillaId, lyingSha))
            val alias = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope()
            }
            assertTrue(alias.message.orEmpty().contains("built for the \"tbc\" flavor"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pointerAt(generation: String, manifestSha: String): String = JSONObject()
        .put("schema", 1).put("mode", "NORMAL")
        .put("generation", generation).put("manifestSha256", manifestSha)
        .toString()

    /** Stage one generation whose manifest mirrors the tool's published
     * shape, with the identity fields taken from the registry itself;
     * returns the manifest sha256. */
    /** Optional fixture overrides (R2-arch-1): the below-floor pins plant
     * explicit counts; the alias tests override the flavor alias. */
    private class GenerationFixture(
        val withAlias: Boolean = true,
        val aliasOverride: String? = null,
        val counts: JSONObject? = null,
    )

    private fun stageGeneration(
        root: File,
        flavor: ClientFlavor,
        id: String,
        fixture: GenerationFixture = GenerationFixture(),
    ): String {
        val generation = File(root, "generations/$id").apply { mkdirs() }
        val records = listOf(
            "dbc/A.dbc" to "dbc", "maps/0000000.map" to "map",
            "vmaps/000.vmtree" to "vmtree", "mmaps/0000000.mmtile" to "mmtile",
        ).map { (path, contents) ->
            val file = File(generation, path).apply { parentFile?.mkdirs(); writeText(contents) }
            JSONObject().put("path", path).put("size", file.length()).put("sha256", sha256(file))
        }
        val manifest = JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL")
            .put("clientBuild", flavor.build).put("cmangosFamily", flavor.serverFamily)
            // R1-1/R6-3: the fixture carries the flavor's own publication
            // minimums (the envelope's counts gate enforces them per flavor —
            // a small vanilla-shaped count set is refused on tbc/wotlk).
            .put("counts", fixture.counts ?: JSONObject()
                .put("dbc", publicationMinimums(flavor.flavorId).dbc)
                .put("maps", publicationMinimums(flavor.flavorId).maps)
                .put("vmapTrees", publicationMinimums(flavor.flavorId).vmapTrees)
                .put("mmapMaps", publicationMinimums(flavor.flavorId).mmapMaps)
                .put("mmapTiles", publicationMinimums(flavor.flavorId).mmapTiles))
            .put("files", JSONArray(records))
        when {
            fixture.aliasOverride != null -> manifest.put("flavor", fixture.aliasOverride)
            fixture.withAlias -> manifest.put("flavor", flavor.flavorId)
        }
        val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
        return sha256(manifestFile)
    }

    @Test fun belowFloorCountsRefuseTheTbcAndWotlkLanesAtBoot() {
        // R2-arch-1 (W2 code-red round 2): the flavor-routed counts gate is
        // pinned BEHAVIORALLY — a tbc/wotlk-identity manifest with the
        // pre-wave vanilla-flat counts (100/100/1/1/1) is refused with the
        // missing-maps copy, exactly the regression R1-1's fix closes ("a
        // truncated tbc/wotlk generation the official lane refuses must not
        // boot either").
        val flat = JSONObject().put("dbc", 100).put("maps", 100)
            .put("vmapTrees", 1).put("mmapMaps", 1).put("mmapTiles", 1)
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            val root = Files.createTempDirectory("o11-desk128-flat").toFile()
            try {
                val id = UUID.randomUUID().toString()
                val sha = stageGeneration(root, flavor, id, GenerationFixture(counts = flat))
                File(root, "active-${flavor.flavorId}.json").writeText(pointerAt(id, sha))
                val refusal = assertThrows(IllegalStateException::class.java) {
                    PreparedDataStore(root).requireActiveEnvelope(flavor)
                }
                assertTrue(
                    "${flavor.flavorId}: unexpected refusal ${refusal.message}",
                    refusal.message.orEmpty().contains("missing maps needed to play"))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
}
