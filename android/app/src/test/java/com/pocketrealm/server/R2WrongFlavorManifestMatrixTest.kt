package com.pocketrealm.server

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.importer.verifiedSourceFlavor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After

/**
 * R2 adversarial breaker (android-core round 1): the wrong-flavor manifest
 * matrix against the fail-closed consume-side gates.
 *
 * Attack class: a data-manifest (or a pointer that authenticates one) that
 * does not belong to the authenticating lane must be REFUSED with a loud,
 * actionable message — never silently coerced onto the lane (port plan
 * invariant 2/4). Every test here plants a doctored generation tree in the
 * VANILLA pointer namespace (the lane that exists today) and demands the
 * reader refuse it.
 */
class R2WrongFlavorManifestMatrixTest {
    /** W4 flip fixture: the bare-root fallback and the wrong-flavor
     * refusal resolve the ACTIVE lane through the holder — pin the
     * VANILLA lane so both legs see one lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @Before
    fun resetFlavorMachine() {
        ActiveFlavor.reset()
    }

    // ------------------------------------------------------------------
    // Pure envelope matrix
    // ------------------------------------------------------------------

    @Test
    fun `tbc identity manifest on the vanilla lane is refused`() {
        val manifest = vanillaManifest()
            .put("clientBuild", 8606)
            .put("cmangosFamily", "tbc")
            .put("flavor", "tbc")
        val failure = assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
        assertTrue(failure.message!!.contains("Rebuild it from Game files"))
    }

    @Test
    fun `a vanilla-labeled manifest carrying a foreign build is refused`() {
        // The coordinated two-field lie: the flavor alias says vanilla while
        // the stamped build says wotlk. The build/family leg must win.
        val manifest = vanillaManifest()
            .put("clientBuild", 12340)
            .put("cmangosFamily", "wotlk")
            .put("flavor", "vanilla")
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
    }

    @Test
    fun `a foreign family under a vanilla build is refused`() {
        val manifest = vanillaManifest().put("cmangosFamily", "wotlk")
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
    }

    @Test
    fun `a flavor alias that disagrees with the lane is refused naming both`() {
        val manifest = vanillaManifest().put("flavor", "wotlk")
        val failure = assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
        assertTrue(failure.message!!.contains("wotlk"))
        assertTrue(failure.message!!.contains("vanilla"))
    }

    @Test
    fun `an empty flavor alias is refused not defaulted`() {
        val manifest = vanillaManifest().put("flavor", "")
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
    }

    @Test
    fun `absent and explicit-null flavor aliases behave identically on the vanilla lane`() {
        // D27 control: the pre-flavor byte shape (no key) must keep opening.
        PreparedDataStore.validateDataManifestEnvelope(vanillaManifest(), ClientFlavors.VANILLA)
        val explicitNull = vanillaManifest().put("flavor", JSONObject.NULL)
        PreparedDataStore.validateDataManifestEnvelope(explicitNull, ClientFlavors.VANILLA)
    }

    // ------------------------------------------------------------------
    // Doctored generation trees through the real fail-closed reader
    // ------------------------------------------------------------------

    @Test
    fun `a self-consistent tbc generation planted in the vanilla pointer namespace is refused`() {
        val root = tempRoot()
        try {
            val tbcManifest = vanillaManifest()
                .put("clientBuild", 8606)
                .put("cmangosFamily", "tbc")
                .put("flavor", "tbc")
            plantGeneration(root, tbcManifest, pointerName = "active-vanilla.json")
            val failure = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            }
            assertTrue(failure.message!!.contains("Rebuild it from Game files"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a stale generation whose manifest digest drifted from the pointer is refused`() {
        val root = tempRoot()
        try {
            plantGeneration(root, vanillaManifest(), pointerName = "active-vanilla.json")
            // Stale/doctored: the manifest bytes moved after the pointer
            // authenticated them (or the pointer names an older digest).
            val generationRoot = File(root, "generations/12345678-1234-1234-1234-123456789abc")
            File(generationRoot, "data-manifest.json").appendText(" ")
            val failure = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            }
            assertTrue(failure.message!!.contains("integrity check"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a pointer body claiming vanilla over a foreign manifest is refused manifest is the authority`() {
        val root = tempRoot()
        try {
            // Pointer body carries flavor "vanilla" (the doctored lie); the
            // manifest it authenticates is tbc. The reader must keep trusting
            // only the MANIFEST, so the pairing refuses.
            plantGeneration(
                root,
                vanillaManifest().put("clientBuild", 8606).put("cmangosFamily", "tbc").put("flavor", "tbc"),
                pointerName = "active-vanilla.json",
                pointerFlavor = "vanilla",
            )
            assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a manifest file path escaping the generation is refused`() {
        val root = tempRoot()
        try {
            val id = "12345678-1234-1234-1234-123456789abc"
            val generation = File(root, "generations/$id").apply { mkdirs() }
            val manifest = vanillaManifest().put(
                "files",
                JSONArray().put(
                    JSONObject().put("path", "../evil.txt").put("size", 4L).put("sha256", "00".repeat(32)),
                ),
            )
            val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
            writePointer(root, "active-vanilla.json", id, sha256(manifestFile))
            val failure = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            }
            assertTrue(failure.message!!.contains("evil.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the genuine vanilla generation still opens`() {
        val root = tempRoot()
        try {
            plantGeneration(root, vanillaManifest(), pointerName = "active-vanilla.json")
            assertEquals(
                "12345678-1234-1234-1234-123456789abc",
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA).generation,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Client-manifest shape gate (the managed-client lane's twin gate)
    // ------------------------------------------------------------------

    @Test
    fun `client manifest with an unqualified clientId is refused`() {
        val manifest = clientManifest("wow-2.4.3-8606")
        assertThrows(IllegalStateException::class.java) {
            com.pocketrealm.importer.ClientGenerationStore.validateManifestShape(manifest, IMPORT_ID)
        }
    }

    @Test
    fun `client manifest flavor alias disagreeing with its clientId is refused`() {
        val manifest = clientManifest("wow-1.12.1-5875").put("flavor", "tbc")
        val failure = assertThrows(IllegalStateException::class.java) {
            com.pocketrealm.importer.ClientGenerationStore.validateManifestShape(manifest, IMPORT_ID)
        }
        assertTrue(failure.message!!.contains("disagrees"))
    }

    @Test
    fun `client manifest control accepts the vanilla shape`() {
        com.pocketrealm.importer.ClientGenerationStore
            .validateManifestShape(clientManifest("wow-1.12.1-5875"), IMPORT_ID)
    }

    // ------------------------------------------------------------------
    // Data-prep source verification (the R2-4 publish-time gate)
    // ------------------------------------------------------------------

    @Test
    fun `data preparation refuses a source generation from another flavor`() {
        val root = tempRoot()
        try {
            File(root, "client-manifest.json").writeText(
                clientManifest("wow-2.4.3-8606").toString(),
            )
            val failure = assertThrows(IllegalStateException::class.java) {
                verifiedSourceFlavor(root)
            }
            assertTrue(failure.message!!.contains("mid-prep flavor switch"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `data preparation refuses an unregistered source identity loud`() {
        val root = tempRoot()
        try {
            File(root, "client-manifest.json").writeText(
                clientManifest("wow-9.9.9-9999").toString(),
            )
            assertThrows(IllegalStateException::class.java) { verifiedSourceFlavor(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `data preparation bare-root falls back to the active lane documented posture`() {
        // The recorded bare-root posture (D48 quarantine tests): a clientRoot
        // with NO manifest stamps the active lane rather than refusing. Pinned
        // here as documentation — a future tightening flips this test
        // deliberately.
        val root = tempRoot()
        try {
            assertEquals(ClientFlavors.VANILLA, verifiedSourceFlavor(root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun tempRoot(): File = Files.createTempDirectory("r2-wrongflavor").toFile()

    private fun vanillaManifest(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("complete", true)
        .put("mode", "NORMAL")
        .put("clientBuild", 5875)
        .put("cmangosFamily", "classic")
        .put(
            "counts",
            JSONObject()
                .put("dbc", 100).put("maps", 100).put("vmapTrees", 1)
                .put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1),
        )

    private fun plantGeneration(root: File, manifest: JSONObject, pointerName: String, pointerFlavor: String? = null) {
        val id = "12345678-1234-1234-1234-123456789abc"
        val generation = File(root, "generations/$id").apply { mkdirs() }
        val records = listOf(
            "dbc/A.dbc" to "dbc", "maps/0000000.map" to "map",
            "vmaps/000.vmtree" to "vmtree", "mmaps/000.mmap" to "mmap",
            "mmaps/0000000.mmtile" to "mmtile",
        ).map { (path, contents) ->
            val file = File(generation, path).apply { parentFile?.mkdirs(); writeText(contents) }
            JSONObject().put("path", path).put("size", file.length()).put("sha256", sha256(file))
        }
        manifest.put("files", JSONArray(records))
        val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
        writePointer(root, pointerName, id, sha256(manifestFile), pointerFlavor)
    }

    private fun writePointer(root: File, pointerName: String, id: String, manifestSha256: String, flavor: String? = null) {
        val pointer = JSONObject()
            .put("schema", 1)
            .put("mode", "NORMAL")
            .put("generation", id)
            .put("manifestSha256", manifestSha256)
        flavor?.let { pointer.put("flavor", it) }
        File(root, pointerName).writeText(pointer.toString())
    }

    private fun clientManifest(clientId: String): JSONObject = JSONObject()
        .put("schema", 2)
        .put("complete", true)
        .put("clientId", clientId)
        .put("managedRoot", "no_backup/client/generations/$IMPORT_ID")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val IMPORT_ID = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
    }
}
