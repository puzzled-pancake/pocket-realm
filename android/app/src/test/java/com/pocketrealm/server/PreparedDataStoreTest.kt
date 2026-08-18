package com.pocketrealm.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class PreparedDataStoreTest {
    @Test fun validCompleteGenerationOpensAndTamperingFailsClosed() {
        val root = Files.createTempDirectory("o11-data").toFile()
        try {
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
            val manifest = JSONObject().put("schema", 1).put("complete", true)
                .put("mode", "NORMAL").put("clientBuild", 5875).put("cmangosFamily", "classic")
                .put("counts", JSONObject().put("dbc", 100).put("maps", 100)
                    .put("vmapTrees", 1).put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1))
                .put("files", JSONArray(records))
            val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
            File(root, "active.json").writeText(JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", sha256(manifestFile)).toString())

            assertEquals(id, PreparedDataStore(root).requireActive().generation)
            File(generation, "dbc/A.dbc").appendText("tampered")
            // Supervisor preflight reads only the authenticated envelope; the
            // world-side authoritative pass still catches the file mutation.
            assertEquals(id, PreparedDataStore(root).requireActiveEnvelope().generation)
            assertThrows(IllegalStateException::class.java) { PreparedDataStore(root).requireActive() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun runtimeLeasePreventsPublicationUntilWorldReleasesData() {
        val root = Files.createTempDirectory("o11-lease").toFile()
        try {
            val runtime = PreparedDataStore(root).acquireRuntimeLease()
            try {
                assertThrows(IllegalStateException::class.java) {
                    PreparedDataStore.acquirePublicationLease(root)
                }
            } finally {
                runtime.close()
            }
            PreparedDataStore.acquirePublicationLease(root).use { }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun absentOrIncompleteGenerationCannotEnterNormalPlay() {
        val root = Files.createTempDirectory("o11-empty").toFile()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                PreparedDataStore(root).requireActive()
            }
            assertTrue(failure.message.orEmpty().contains("Server world data is not ready"))
            assertTrue(failure.message.orEmpty().contains("Game files"))
            assertFalse(failure.message.orEmpty().contains("O11"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
