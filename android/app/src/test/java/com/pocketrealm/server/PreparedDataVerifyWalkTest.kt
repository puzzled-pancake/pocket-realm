package com.pocketrealm.server

import com.pocketrealm.client.ClientFlavors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * R4-4 (android-core review round 1): [PreparedDataStore.requireActive]
 * re-hashes the ENTIRE prepared dataset inside the serialized transition
 * gate before native boot — the walk must be TOTAL (every manifest file,
 * every byte), which is what these pins prove: corrupting or truncating
 * ANY single manifest-listed file is caught on the next verify, whatever
 * its directory class.
 *
 * Measured budget (the record the gate checks against):
 *  - host (canonical dev machine, 2026-10-09, measured this wave):
 *    SHA-256 throughput 307 MiB/s (3.2 MiB .mmtile sample, python hashlib;
 *    reviewer's independent sample 441 MiB/s, 6.2 MiB .mmtile) over a
 *    2228-3100 MiB generation -> ~7 s desktop-side per verify;
 *  - RP6-class device: UNMEASURED — the per-start verify wall clock is a
 *    named W4.4 record (decision D62); until that number lands, the walk
 *    itself must stay total, never sampled.
 */
class PreparedDataVerifyWalkTest {

    private fun tempRoot(): File = Files.createTempDirectory("r4-verify-walk").toFile()

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

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

    private fun plantGeneration(root: File, manifest: JSONObject, pointerName: String) {
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
        val pointer = JSONObject()
            .put("schema", 1).put("mode", "NORMAL")
            .put("generation", id)
            .put("manifestSha256", sha256(manifestFile))
        File(root, pointerName).writeText(pointer.toString())
    }

    private fun generationRoot(root: File): File =
        File(root, "generations/12345678-1234-1234-1234-123456789abc")

    @Test
    fun `a self-consistent generation verifies end to end`() {
        val root = tempRoot()
        try {
            plantGeneration(root, vanillaManifest(), pointerName = "active-vanilla.json")
            val active = PreparedDataStore(root).requireActive(ClientFlavors.VANILLA)
            assertEquals("12345678-1234-1234-1234-123456789abc", active.generation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `every manifest file class is verified - corruption anywhere is caught`() {
        val root = tempRoot()
        try {
            plantGeneration(root, vanillaManifest(), pointerName = "active-vanilla.json")
            // The walk is TOTAL: corrupt each manifest-listed file in turn
            // (content AND size classes) and require the verify to refuse.
            for (relative in listOf(
                "dbc/A.dbc", "maps/0000000.map", "vmaps/000.vmtree",
                "mmaps/000.mmap", "mmaps/0000000.mmtile",
            )) {
                val file = File(generationRoot(root), relative)
                val original = file.readText()
                file.writeText(original + "corrupted")
                val failure = assertThrows(IllegalStateException::class.java) {
                    PreparedDataStore(root).requireActive(ClientFlavors.VANILLA)
                }
                assertTrue(
                    "the refusal must name the corrupt file: ${failure.message}",
                    relative in failure.message!!,
                )
                file.writeText(original)
            }
            // and the repaired generation verifies clean again
            PreparedDataStore(root).requireActive(ClientFlavors.VANILLA)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a truncated manifest file is caught by the size leg`() {
        val root = tempRoot()
        try {
            plantGeneration(root, vanillaManifest(), pointerName = "active-vanilla.json")
            val file = File(generationRoot(root), "maps/0000000.map")
            file.writeText(file.readText().take(2))
            val failure = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActive(ClientFlavors.VANILLA)
            }
            assertTrue("maps/0000000.map" in failure.message!!)
        } finally {
            root.deleteRecursively()
        }
    }
}
