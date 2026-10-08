package com.pocketrealm.importer

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.server.PreparedDataStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * G3-5 quarantine guards for the durable generation pointers, on the pure
 * shared plumbing ([GenerationPointers]) plus the JVM-testable fail-closed
 * data reader:
 *
 *  - the prune keep-set is scoped to the activating flavor: another flavor's
 *    generation (and any generation with an unreadable manifest — fail-safe,
 *    not fail-destructive) always survives an activation, while the old
 *    within-one-flavor behavior (third-oldest retired) is preserved;
 *  - the pre-flavor un-namespaced `active.json` is adopted as the vanilla
 *    namespace on first read with zero data loss (generations intact, pointer
 *    digests preserved; the data lane copies, keeping the source for a
 *    concurrent reader);
 *  - a hand-mangled cross-flavor pointer can never authenticate the wrong
 *    lane's generation (defense-in-depth behind the manifest gates).
 *
 * The client store's rename-based adoption and the adversarial
 * vanilla-active × TBC-activation androidTest run on the device lane
 * (deferred; no device in this stage's environment) — the pure rules those
 * paths apply are the ones pinned here.
 */
class GenerationPointerQuarantineTest {
    private val tempRoots = mutableListOf<File>()

    @After fun cleanTempRoots() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    // ---- scoped prune keep-set ----

    @Test fun `activating one flavor keeps another flavor's generation`() {
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-tbc-2",
            priorGeneration = null,
            generations = listOf(
                generation("gen-vanilla-1", clientManifest("wow-1.12.1-5875")),
                generation("gen-tbc-1", clientManifest("wow-2.4.3-8606")),
            ),
            activatingFlavorId = ClientFlavors.TBC.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        assertTrue("vanilla generation must survive a TBC activation", "gen-vanilla-1" in keep)
        assertTrue("gen-tbc-2" in keep)
        assertTrue("gen-tbc-1" !in keep)
    }

    @Test fun `generation with unreadable manifest is kept as vanilla-foreign`() {
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-vanilla-2",
            priorGeneration = "gen-vanilla",
            generations = listOf(
                generation("gen-broken", null),
                generation("gen-vanilla", clientManifest("wow-1.12.1-5875")),
            ),
            activatingFlavorId = ClientFlavors.VANILLA.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        assertTrue("unreadable manifest must be treated as vanilla-foreign", "gen-broken" in keep)
    }

    @Test fun `same flavor prune still retires the third-oldest generation`() {
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-d",
            priorGeneration = "gen-c",
            generations = listOf(
                generation("gen-a", clientManifest("wow-1.12.1-5875")),
                generation("gen-b", clientManifest("wow-1.12.1-5875")),
                generation("gen-c", clientManifest("wow-1.12.1-5875")),
            ),
            activatingFlavorId = ClientFlavors.VANILLA.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        // Old single-lane behavior preserved within one flavor: only the
        // activating generation and its predecessor survive.
        assertEquals(setOf("gen-d", "gen-c"), keep)
    }

    @Test fun `data generations resolve their flavor through cmangosFamily`() {
        val vanilla = generation("gen-data-vanilla", dataManifestText("classic"), manifestName = "data-manifest.json")
        val wotlk = generation("gen-data-wotlk", dataManifestText("wotlk"), manifestName = "data-manifest.json")
        assertEquals(ClientFlavors.VANILLA.flavorId, GenerationPointers.dataGenerationFlavor(vanilla))
        assertEquals(ClientFlavors.WOTLK.flavorId, GenerationPointers.dataGenerationFlavor(wotlk))
        assertEquals(null, GenerationPointers.dataGenerationFlavor(generation("gen-data-none", null)))
        // Hours of mmaps work for another family must survive a vanilla activation.
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-data-vanilla-2",
            priorGeneration = "gen-data-vanilla",
            generations = listOf(vanilla, wotlk),
            activatingFlavorId = ClientFlavors.VANILLA.flavorId,
            generationFlavor = GenerationPointers::dataGenerationFlavor,
        )
        assertTrue("wotlk data generation must survive a vanilla activation", "gen-data-wotlk" in keep)
    }

    // ---- legacy `active.json` adoption (data lane, end-to-end) ----

    @Test fun `legacy active pointer is adopted as vanilla with generations intact`() {
        val root = tempRoot("o11-adopt")
        val id = "12345678-1234-1234-1234-123456789abc"
        val generationDir = File(root, "generations/$id").apply { mkdirs() }
        val manifest = writeDataManifest(generationDir, ClientFlavors.VANILLA, id)
        // Pre-update layout: un-namespaced active pointer only.
        val legacyActive = JSONObject().put("schema", 1).put("mode", "NORMAL")
            .put("generation", id).put("manifestSha256", sha256(manifest)).toString()
        File(root, "active.json").writeText(legacyActive)

        val active = PreparedDataStore(root).requireActiveEnvelope()

        assertEquals(id, active.generation)
        assertEquals(sha256(manifest), active.manifestSha256)
        // Adoption: the canonical name exists, carries the pre-update digest,
        // and (copy-then-adopt) the legacy source stays for a reader that
        // already opened it — inert from now on.
        val adopted = File(root, "active-vanilla.json")
        assertTrue("canonical active pointer must exist after adoption", adopted.isFile)
        assertEquals(legacyActive, adopted.readText())
        assertTrue("copy-adopt keeps the legacy source file", File(root, "active.json").isFile)
        // Zero data loss: the generation directory is intact.
        assertTrue(File(generationDir, "data-manifest.json").isFile)
    }

    @Test fun `canonical pointer wins once it exists and the legacy seed stays inert`() {
        val root = tempRoot("o11-adopt-wins")
        val id = "12345678-1234-1234-1234-123456789abc"
        val generationDir = File(root, "generations/$id").apply { mkdirs() }
        val manifest = writeDataManifest(generationDir, ClientFlavors.VANILLA, id)
        val canonicalDigest = sha256(manifest)
        File(root, "active-vanilla.json").writeText(
            JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", canonicalDigest).toString(),
        )
        // A stale un-namespaced pointer left behind must stay inert.
        File(root, "active.json").writeText(
            JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", "88888888-8888-8888-8888-888888888888")
                .put("manifestSha256", "bb".repeat(32)).toString(),
        )

        assertEquals(id, PreparedDataStore(root).requireActiveEnvelope().generation)
        val canonical = File(root, "active-vanilla.json")
        assertEquals(canonicalDigest, JSONObject(canonical.readText()).getString("manifestSha256"))
    }

    /** Defense-in-depth: a vanilla-lane read never resolves another flavor's pointer namespace. */
    @Test fun `vanilla lane never resolves a tbc-namespaced pointer`() {
        val root = tempRoot("o11-cross-flavor")
        val tbcId = "12345678-1234-1234-1234-123456789abc"
        val tbcGeneration = File(root, "generations/$tbcId").apply { mkdirs() }
        val tbcManifest = writeDataManifest(tbcGeneration, ClientFlavors.TBC, tbcId)
        File(root, "active-tbc.json").writeText(
            JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", tbcId).put("manifestSha256", sha256(tbcManifest)).toString(),
        )
        // The vanilla lane reads ONLY active-vanilla.json: absent here, the
        // TBC pointer is invisible to it (and its manifest would refuse the
        // lane anyway — pinned by the G3-2 manifest tests).
        val message = assertThrows(IllegalStateException::class.java) {
            PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
        }.message
        assertTrue(
            "vanilla lane must report missing data, not resolve the TBC pointer",
            message!!.contains("not ready yet"),
        )
    }

    // ---- helpers ----

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }

    private fun generation(name: String, manifestText: String?, manifestName: String = "client-manifest.json"): File {
        val directory = File(tempRoot("g3-gen"), name).apply { mkdirs() }
        manifestText?.let { File(directory, manifestName).writeText(it) }
        return directory
    }

    private fun clientManifest(clientId: String): String = JSONObject()
        .put("schema", 2).put("complete", true).put("clientId", clientId).toString()

    private fun dataManifestText(family: String): String = JSONObject()
        .put("schema", 1).put("complete", true).put("mode", "NORMAL")
        .put("clientBuild", 5875).put("cmangosFamily", family).toString()

    private fun writeDataManifest(
        generation: File,
        flavor: ClientFlavor,
        importId: String,
    ): File {
        val manifest = DataPreparationStore.buildDataManifestJson(
            flavor = flavor,
            counts = JSONObject().put("dbc", 100).put("maps", 100).put("vmapTrees", 1)
                .put("vmapTiles", 1).put("mmapMaps", 1).put("mmapTiles", 1),
            files = listOf(
                JSONObject().put("path", "dbc/A.dbc").put("size", 3L).put("sha256", "cc".repeat(32)),
            ),
            generator = JSONObject(),
            importId = importId,
            publishedAtMs = 1759200000000,
        )
        return File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
