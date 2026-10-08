package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * G3-9: snapshot manifests created by the current release keep validating on
 * the multi-flavor build. The flavor dimension rides INSIDE the free-form
 * `compatibility` object — never as a sixth top-level key (`manifest.length()
 * == 5` stays), and the per-file `record.length() == 3` rows are never
 * relaxed. The committed fixtures are the exact manifest + sidecar bytes the
 * current release wrote (captured through the store itself on the
 * unparameterized tree); they must load, restore and verify verbatim, a
 * one-byte content tamper must still fail the digest seal, a
 * compatibility-carried flavor round-trips, and a cross-flavor restore is
 * refused with a `DB-SNAPSHOT:` error naming both flavors.
 */
class DatabaseSnapshotFlavorShimTest {
    private val tempRoots = mutableListOf<File>()

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }

    @JvmOverloads
    private fun publishFixture(
        root: File,
        manifestName: String = "snapshot-manifest.json",
        tamper: Boolean = false,
    ): DatabaseSnapshotStore {
        val snapshots = File(root, "snapshots").apply { mkdirs() }
        val published = File(snapshots, SNAPSHOT_ID).apply { mkdirs() }
        File(published, "data/classic").apply { mkdirs() }
        File(published, "data/classic/characters.ibd").writeText("character-sentinel")
        File(published, "data/ibdata1").writeText("canonical-ibdata1")
        val manifestBytes = fixtureBytes("$manifestName").let { bytes ->
            if (!tamper) bytes
            else {
                // Alter exactly one byte INSIDE the compatibility object
                // ("classic-set-v1" → "classic-set-v2", same length) while
                // keeping the ORIGINAL published sidecar: the digest seal —
                // published digest vs actual manifest bytes — must catch the
                // content tamper before anything else is read.
                String(bytes, Charsets.UTF_8).replace("classic-set-v1", "classic-set-v2")
                    .toByteArray(Charsets.UTF_8)
            }
        }
        File(published, "manifest.json").writeBytes(manifestBytes)
        File(published, "manifest.sha256").writeBytes(fixtureBytes("snapshot-manifest.sha256"))
        return DatabaseSnapshotStore(snapshots, syncDirectory = { })
    }

    @Test fun `current release manifest fixture loads verifies and restores verbatim`() {
        val root = tempRoot("g3-snap-load")
        val store = publishFixture(root)
        val snapshot = store.load(SNAPSHOT_ID)

        assertEquals(SNAPSHOT_ID, snapshot.id)
        val manifest = JSONObject(snapshot.manifest.readText())
        // The strict top-level shape the current release wrote is intact:
        // five keys, schema 2 — flavor is NOT among them.
        assertEquals(5, manifest.length())
        assertEquals(2, manifest.getInt("schema"))
        assertTrue(!manifest.has("flavor"))

        val restored = File(root, "restored")
        store.restore(snapshot, restored, databaseStopped = true)
        assertEquals("character-sentinel", File(restored, "classic/characters.ibd").readText())
        assertEquals("canonical-ibdata1", File(restored, "ibdata1").readText())
        store.verify(restored, snapshot)
    }

    @Test fun `one byte altered inside compatibility still fails the digest seal`() {
        val root = tempRoot("g3-snap-tamper")
        val store = publishFixture(root, tamper = true)
        // The seal chain (published sidecar digest vs actual manifest bytes)
        // fires at load — before any content is read or restored.
        val failure = assertThrows(IllegalStateException::class.java) {
            store.restore(store.load(SNAPSHOT_ID), File(root, "restored"), databaseStopped = true)
        }
        assertTrue(
            "digest seal must catch the content tamper: ${failure.message}",
            failure.message!!.startsWith("DB-SNAPSHOT: manifest digest mismatch"),
        )
    }

    @Test fun `compatibility carried flavor round trips through create`() {
        val root = tempRoot("g3-snap-flavor")
        val source = File(root, "source").apply { mkdirs() }
        source.resolve("ibdata1").writeText("canonical-ibdata1")
        source.resolve("classic/characters.ibd").apply {
            parentFile.mkdirs(); writeText("character-sentinel")
        }
        val store = DatabaseSnapshotStore(File(root, "snapshots"), syncDirectory = { })
        val snapshot = store.create(
            source, "flavored", databaseStopped = true,
            compatibility = JSONObject()
                .put("database", "classic-set-v1")
                .put("flavor", ClientFlavors.VANILLA.flavorId),
        )
        val manifest = JSONObject(snapshot.manifest.readText())
        assertEquals(5, manifest.length())
        assertEquals(
            ClientFlavors.VANILLA.flavorId,
            manifest.getJSONObject("compatibility").getString("flavor"),
        )
        // Vanilla-lane restore of a vanilla-flavored snapshot is accepted.
        store.restore(snapshot, File(root, "restored"), databaseStopped = true)
    }

    @Test fun `tbc flavored snapshot refuses the vanilla lane naming both flavors`() {
        val root = tempRoot("g3-snap-cross")
        val source = File(root, "source").apply { mkdirs() }
        source.resolve("ibdata1").writeText("canonical-ibdata1")
        val store = DatabaseSnapshotStore(File(root, "snapshots"), syncDirectory = { })
        val snapshot = store.create(
            source, "cross-flavor", databaseStopped = true,
            compatibility = JSONObject().put("flavor", ClientFlavors.TBC.flavorId),
        )
        val failure = assertThrows(IllegalStateException::class.java) {
            store.restore(snapshot, File(root, "restored"), databaseStopped = true)
        }
        assertEquals(
            "DB-SNAPSHOT: snapshot flavor tbc does not match the active lane vanilla",
            failure.message,
        )
    }

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/g3/snapshot/$name")!!.readBytes()

    @org.junit.After fun cleanTempRoots() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val SNAPSHOT_ID = "current-release-one"
    }
}
