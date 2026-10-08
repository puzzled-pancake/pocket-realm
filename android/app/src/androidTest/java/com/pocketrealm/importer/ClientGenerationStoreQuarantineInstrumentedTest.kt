package com.pocketrealm.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.client.ClientFlavors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * G3-5 work item 4(b)+(d), store-level on the device lane (the store needs a
 * Context, so this cannot run on the host JVM):
 *
 *  - (b) activating a generation over existing generations leaves the
 *    OTHER flavor's generation directory intact while the same-flavor
 *    third-oldest is still retired — the scoped destructive-maintenance
 *    contract, exercised through [ClientGenerationStore.recoverPublished]
 *    → activation → prune;
 *  - (d) the pre-update un-namespaced `active.json` is adopted as the
 *    vanilla namespace with zero data loss (atomic rename; never both
 *    readable as authoritative).
 *
 * The foreign (TBC) generation is hand-seeded because a TBC client cannot be
 * published through the qualified gates until S5 qualifies it — which is
 * exactly the state this guard defends: the vanilla generation (hours of
 * work) must survive even when a foreign-flavor generation exists.
 */
@RunWith(AndroidJUnit4::class)
class ClientGenerationStoreQuarantineInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(context.noBackupFilesDir, "client")

    @Before fun wipeClientState() {
        File(root, "generations").deleteRecursively()
        root.listFiles { file -> file.name.startsWith("active") || file.name.startsWith("previous") }
            ?.forEach { it.delete() }
    }

    @After fun cleanupClientState() = wipeClientState()

    @Test fun vanillaActivationKeepsForeignGenerationAndRetiresSameFlavorThird() {
        val store = ClientGenerationStore(context)
        val idA = "11111111-1111-1111-1111-111111111111"
        val idTbc = "22222222-2222-2222-2222-222222222222"
        val idOld = "33333333-3333-3333-3333-333333333333"

        val digestA = seedVanillaGeneration(idA, exeBytes = "current-release".toByteArray())
        seedVanillaGeneration(idOld, exeBytes = "older-release".toByteArray())
        seedForeignGeneration(idTbc)

        // Pre-update layout: un-namespaced pointer at idA with a STALE digest,
        // so activation does not early-return and the prune actually runs.
        File(root, "active.json").writeText(
            org.json.JSONObject()
                .put("schema", 1)
                .put("generation", idA)
                .put("clientId", "wow-1.12.1-5875")
                .put("manifestSha256", "ff".repeat(32))
                .put("activatedAtMs", 1L)
                .toString(),
        )

        val published = store.recoverPublished(idA)
        assertNotNull("vanilla generation must recover and activate", published)
        assertEquals(idA, published!!.id)
        assertEquals(digestA, published.manifestSha256)

        // (d) legacy adoption: the un-namespaced pointer is GONE (atomic
        // rename) and the canonical vanilla pointer carries the fresh digest.
        assertFalse("legacy active.json must not remain authoritative", File(root, "active.json").isFile)
        assertTrue("canonical vanilla pointer must exist", File(root, "active-vanilla.json").isFile)
        val pointer = org.json.JSONObject(File(root, "active-vanilla.json").readText())
        assertEquals(idA, pointer.getString("generation"))
        assertEquals(digestA, pointer.getString("manifestSha256"))
        assertEquals(ClientFlavors.VANILLA.flavorId, pointer.optString("flavor"))

        // (b) scoped prune: the vanilla generation and the FOREIGN (TBC)
        // generation both survive; the same-flavor older generation retires
        // (today's within-one-flavor behavior, preserved).
        assertTrue("vanilla generation must survive", generationDir(idA).isDirectory)
        assertTrue(
            "TBC generation must survive a vanilla activation",
            generationDir(idTbc).isDirectory,
        )
        assertFalse(
            "same-flavor third-oldest generation must still retire",
            generationDir(idOld).isDirectory,
        )
        assertTrue(File(generationDir(idTbc), "client-manifest.json").isFile)
    }

    private fun generationDir(id: String): File = File(File(root, "generations"), id)

    private fun seedVanillaGeneration(id: String, exeBytes: ByteArray): String {
        val generation = generationDir(id).apply { mkdirs() }
        File(generation, "WoW.exe").writeBytes(exeBytes)
        val manifest = org.json.JSONObject()
            .put("schema", 2)
            .put("complete", true)
            .put("clientId", "wow-1.12.1-5875")
            .put("executable", "WoW.exe")
            .put("managedRoot", "no_backup/client/generations/$id")
            .put(
                "files",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("path", "WoW.exe")
                        .put("size", exeBytes.size.toLong())
                        .put("sha256", sha256(exeBytes)),
                ),
            )
        File(generation, "client-manifest.json").writeText(manifest.toString())
        return sha256(File(generation, "client-manifest.json").readBytes())
    }

    private fun seedForeignGeneration(id: String) {
        val generation = generationDir(id).apply { mkdirs() }
        File(generation, "WoW.exe").writeBytes("tbc".toByteArray())
        val manifest = org.json.JSONObject()
            .put("schema", 2)
            .put("complete", true)
            .put("clientId", "wow-2.4.3-8606")
            .put("managedRoot", "no_backup/client/generations/$id")
        File(generation, "client-manifest.json").writeText(manifest.toString())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
