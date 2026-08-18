package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DatabaseSnapshotStoreTest {
    @Test fun stoppedSnapshotRoundTripPreservesManifestAndBytes() {
        val root = Files.createTempDirectory("db-snapshot-test").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("ibdata1").writeBytes(byteArrayOf(1, 2, 3, 4))
            source.resolve("classic/characters.ibd").apply {
                parentFile.mkdirs()
                writeText("character-sentinel")
            }
            val synced = mutableListOf<String>()
            val store = DatabaseSnapshotStore(root.resolve("snapshots")) { synced += it.name }
            val snapshot = store.create(source, "manual-one", databaseStopped = true,
                compatibility = JSONObject().put("database", "test-db-v1"))

            val restored = root.resolve("restored")
            store.restore(snapshot, restored, databaseStopped = true)

            assertEquals(source.resolve("ibdata1").readBytes().toList(),
                restored.resolve("ibdata1").readBytes().toList())
            assertEquals("character-sentinel", restored.resolve("classic/characters.ibd").readText())
            assertEquals("test-db-v1", JSONObject(snapshot.manifest.readText())
                .getJSONObject("compatibility").getString("database"))
            assertEquals("manual-one", store.list().single().id)
            assertTrue(synced.contains("snapshots"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun corruptedSnapshotIsRejectedBeforeRestoreCompletes() {
        val root = Files.createTempDirectory("db-snapshot-corrupt-test").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("ibdata1").writeText("canonical")
            val store = DatabaseSnapshotStore(root.resolve("snapshots")) { }
            val snapshot = store.create(source, "manual-corrupt", databaseStopped = true)
            snapshot.root.resolve("data/ibdata1").writeText("tampered")

            val failure = runCatching {
                store.restore(snapshot, root.resolve("restored"), databaseStopped = true)
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("hash mismatch"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun partialPublicationAndManifestIdentityAreNeverLoadable() {
        val root = Files.createTempDirectory("db-snapshot-partial-test").toFile()
        try {
            val snapshots = root.resolve("snapshots").apply { mkdirs() }
            snapshots.resolve(".manual-partial.deadbeef.partial").apply {
                mkdirs(); resolve("manifest.json").writeText("{}")
            }
            val store = DatabaseSnapshotStore(snapshots) { }
            assertTrue(store.list().isEmpty())

            snapshots.resolve("manual-wrong").apply {
                mkdirs()
                resolve("manifest.json").writeText(JSONObject()
                    .put("schema", 2).put("snapshotId", "different-id")
                    .put("createdAt", 1).put("files", org.json.JSONArray())
                    .put("compatibility", JSONObject()).toString())
            }
            val failure = runCatching { store.load("manual-wrong") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        } finally {
            root.deleteRecursively()
        }
    }
}
