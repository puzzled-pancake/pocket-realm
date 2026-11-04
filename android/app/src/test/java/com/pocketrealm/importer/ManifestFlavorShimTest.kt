package com.pocketrealm.importer

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.server.PreparedDataStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * the durable manifest readers accept both encodings — the flavor
 * rides ONLY as an optional `optString("flavor", "vanilla")`-defaulted alias
 * at the existing schema numbers (client-manifest schema 2, data-manifest
 * schema 1), validated against the content (clientId / clientBuild /
 * cmangosFamily), never required by its presence. The committed
 * current-release fixtures carry the shipping build's serialization — the
 * writer `put`-chain insertion order the Android org.json serializer emits —
 * and must be accepted verbatim; a flavor value that disagrees with the
 * stamped identity must be refused with no silent coercion. The writer
 * builder is pinned against the fixture content in the sorted-key canonical
 * form (the JVM classpath's org.json hash-scrambles key order; the shipped
 * device serializer preserves insertion order — that is the byte shape the
 * fixture records).
 */
class ManifestFlavorShimTest {
    private fun fixtureBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/g3/$name")!!.readBytes()

    private fun fixtureText(name: String): String =
        String(fixtureBytes(name), Charsets.UTF_8)

    @Test fun `current release client manifest fixture is accepted verbatim`() {
        val manifest = JSONObject(fixtureText("client-manifest-current-release.json"))
        // Absent flavor key on the current-release bytes: the shape gate must
        // accept it exactly as today's build wrote it.
        assertTrue(!manifest.has("flavor"))
        ClientGenerationStore.validateManifestShape(manifest, CLIENT_IMPORT_ID)
    }

    @Test fun `current release client manifest writer bytes are pinned by the fixture`() {
        // Content-stability: the pure builder (the publish() writer body) must
        // reproduce the committed current-release fixture exactly — any key
        // insertion or value change flips this test and forces a deliberate
        // fixture rotation. The committed fixture carries the SHIPPING (Android
        // org.json) byte shape — chain insertion order, top-level key for key
        // with the writer's `put` order. The JVM classpath's org.json
        // hash-scrambles order, so this lane compares the canonical
        // (sorted-key) form; the on-device serialization order is the fixture's
        // (Android org.json preserves insertion order).
        val identity = JSONObject()
            .put("machine", 0x14c).put("optionalMagic", 0x10b)
            .put("build", 5875).put("version", "1.12.1.5875")
            .put("size", 4096L).put("sha256", "11".repeat(32))
        val journalEntries = listOf(
            ImportJournal.JournalEntry(
                "Data/base.MPQ", 1024L, ImportFileState.VERIFIED, "22".repeat(32), 1, null, true,
            ),
            ImportJournal.JournalEntry(
                "Data/texture.MPQ", 512L, ImportFileState.VERIFIED, "33".repeat(32), 1, null, true,
            ),
            ImportJournal.JournalEntry(
                "Data/speech.MPQ", 256L, ImportFileState.SKIPPED, null, 1, "duplicate", true,
            ),
        )
        val files = listOf(
            JSONObject().put("path", "Data/base.MPQ").put("size", 1024L).put("sha256", "22".repeat(32)),
            JSONObject().put("path", "WoW.exe").put("size", 4096L).put("sha256", "11".repeat(32)),
        )
        val rebuilt = ClientGenerationStore.buildManifestJson(
            identity = identity,
            clientId = "wow-1.12.1-5875",
            sourceFingerprint = "zip-sha256:44" + "4".repeat(56),
            journalEntries = journalEntries,
            files = files,
            importId = CLIENT_IMPORT_ID,
            durationMs = 42000L,
            publishedAtMs = 1759200000000,
        )
        assertEquals(
            canonical(fixtureText("client-manifest-current-release.json")),
            canonical(rebuilt.toString(2)),
        )
    }

    @Test fun `client manifest flavor disagreeing with clientId is refused`() {
        val manifest = JSONObject(fixtureText("client-manifest-current-release.json"))
        manifest.put("flavor", ClientFlavors.TBC.flavorId)
        assertThrows(IllegalStateException::class.java) {
            ClientGenerationStore.validateManifestShape(manifest, CLIENT_IMPORT_ID)
        }
    }

    @Test fun `client manifest with matching explicit flavor is accepted`() {
        val manifest = JSONObject(fixtureText("client-manifest-current-release.json"))
        manifest.put("flavor", ClientFlavors.VANILLA.flavorId)
        ClientGenerationStore.validateManifestShape(manifest, CLIENT_IMPORT_ID)
    }

    @Test fun `current release data manifest fixture is accepted verbatim on the vanilla lane`() {
        val manifest = JSONObject(fixtureText("data-manifest-current-release.json"))
        assertTrue(!manifest.has("flavor"))
        PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
    }

    @Test fun `current release data manifest writer bytes are pinned by the fixture`() {
        val counts = JSONObject().put("dbc", 120).put("maps", 130).put("vmapTrees", 2)
            .put("vmapTiles", 9).put("mmapMaps", 120).put("mmapTiles", 50)
        val generator = JSONObject().put("cmangosCommit", "c096bada9e4ed23ad4ca706c67160a26d7121337")
            .put("artifactSet", JSONArray())
            .put("abi", "arm64-v8a").put("api", 34).put("pageSize", 16384)
        val dataFiles = listOf(
            JSONObject().put("path", "dbc/Spell.dbc").put("size", 8L).put("sha256", "55".repeat(32)),
            JSONObject().put("path", "maps/0002035.map").put("size", 16L).put("sha256", "66".repeat(32)),
        )
        val rebuilt = DataPreparationStore.buildDataManifestJson(
            flavor = ClientFlavors.VANILLA,
            counts = counts,
            files = dataFiles,
            generator = generator,
            importId = CLIENT_IMPORT_ID,
            publishedAtMs = 1759200000000,
        )
        assertEquals(
            canonical(fixtureText("data-manifest-current-release.json")),
            canonical(rebuilt.toString(2)),
        )
    }

    @Test fun `data manifest flavor disagreeing with cmangosFamily is refused`() {
        val manifest = JSONObject(fixtureText("data-manifest-current-release.json"))
        manifest.put("flavor", ClientFlavors.TBC.flavorId)
        val refused = assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
        assertTrue(
            "refusal names both flavors",
            refused.message!!.contains("tbc") && refused.message!!.contains("vanilla"),
        )
    }

    @Test fun `data manifest with matching explicit flavor is accepted`() {
        val manifest = JSONObject(fixtureText("data-manifest-current-release.json"))
        manifest.put("flavor", ClientFlavors.VANILLA.flavorId)
        PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
    }

    /**
     * End-to-end over the fail-closed reader: the committed fixture bytes,
     * published byte-for-byte into a generation with its pointer, authenticate
     * on the vanilla lane with zero edits.
     */
    @Test fun `published fixture generation authenticates through the reader verbatim`() {
        val root = Files.createTempDirectory("o11-g32-fixture").toFile()
        try {
            val id = CLIENT_IMPORT_ID
            val generation = File(root, "generations/$id").apply { mkdirs() }
            val manifestFile = File(generation, "data-manifest.json")
            manifestFile.writeBytes(fixtureBytes("data-manifest-current-release.json"))
            // The fixture's two records must exist on disk with their pinned bytes.
            File(generation, "dbc/Spell.dbc").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(8))
            File(generation, "maps/0002035.map").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(16))
            File(root, "active-vanilla.json").writeText(
                JSONObject().put("schema", 1).put("mode", "NORMAL")
                    .put("generation", id).put("manifestSha256", sha256(manifestFile)).toString(),
            )
            val active = PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            assertEquals(id, active.generation)
            assertEquals(sha256(manifestFile), active.manifestSha256)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** Order-insensitive, content-exact serialization (sorted keys, compact). */
    private fun canonical(text: String): String = canonicalJson(JSONObject(text))

    private fun canonicalJson(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.get(index))
        }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private companion object {
        const val CLIENT_IMPORT_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
