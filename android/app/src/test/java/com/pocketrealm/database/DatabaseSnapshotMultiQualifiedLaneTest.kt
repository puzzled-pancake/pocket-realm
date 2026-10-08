package com.pocketrealm.database

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 (lanes item 10): the DatabaseSnapshotStore restore lane under TWO
 * qualified flavors — the one census site of the eight whose whole class
 * is pure-JVM constructible (File roots + the persisted-pick seam), so the
 * pin runs the REAL gate: restore -> validatePublished ->
 * checkSnapshotFlavorMatchesLane -> the lane resolution the site shipped.
 *
 * Under two qualified flavors: the persisted tbc pick restores a
 * tbc-flavored snapshot; the vanilla pick refuses naming both flavors; the
 * absent pick refuses loud with the lane's own copy (never a silent
 * vanilla default and never a `.single()` crash). The lane suffix is
 * asserted through the store's own [SNAPSHOT_COMPATIBILITY_LANE] constant,
 * not a test-side literal.
 */
class DatabaseSnapshotMultiQualifiedLaneTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun tearDown() {
        ActiveFlavor.reset()
        tempRoots.forEach { it.deleteRecursively() }
    }

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }

    /** A datadir the store can snapshot (the shim test's minimal shape). */
    private fun sourceDatadir(root: File): File = File(root, "source").apply {
        mkdirs()
        resolve("ibdata1").writeText("canonical-ibdata1")
        resolve("classic/characters.ibd").apply {
            parentFile.mkdirs(); writeText("character-sentinel")
        }
    }

    private fun store(root: File, persistedPick: String?): DatabaseSnapshotStore =
        DatabaseSnapshotStore(
            File(root, "snapshots"),
            { },
            persistedActiveFlavorId = { persistedPick },
        )

    @Test
    fun `the persisted tbc pick restores a tbc flavored snapshot under two qualified flavors`() {
        TwoQualifiedFlavors.install()
        val root = tempRoot("p6-snap-tbc")
        val snapshot = store(root, ClientFlavors.TBC.flavorId).create(
            sourceDatadir(root), "two-qualified-tbc", databaseStopped = true,
            compatibility = JSONObject().put("flavor", ClientFlavors.TBC.flavorId),
        )
        val restored = File(root, "restored")
        store(root, ClientFlavors.TBC.flavorId).restore(snapshot, restored, databaseStopped = true)
        assertEquals(
            "the restore lands the snapshot bytes on the selected lane",
            "character-sentinel",
            File(restored, "classic/characters.ibd").readText(),
        )
    }

    @Test
    fun `the vanilla pick refuses a tbc flavored snapshot naming both flavors`() {
        TwoQualifiedFlavors.install()
        val root = tempRoot("p6-snap-cross")
        val snapshot = store(root, ClientFlavors.TBC.flavorId).create(
            sourceDatadir(root), "two-qualified-cross", databaseStopped = true,
            compatibility = JSONObject().put("flavor", ClientFlavors.TBC.flavorId),
        )
        val failure = assertThrows(IllegalStateException::class.java) {
            store(root, ClientFlavors.VANILLA.flavorId)
                .restore(snapshot, File(root, "restored"), databaseStopped = true)
        }
        assertEquals(
            "the refusal names both flavors — the selection routed the lane",
            "DB-SNAPSHOT: snapshot flavor tbc does not match the active lane vanilla",
            failure.message,
        )
    }

    @Test
    fun `the absent pick refuses loud under two qualified flavors instead of crashing`() {
        TwoQualifiedFlavors.install()
        val root = tempRoot("p6-snap-ambiguous")
        val snapshot = store(root, ClientFlavors.TBC.flavorId).create(
            sourceDatadir(root), "two-qualified-ambiguous", databaseStopped = true,
            compatibility = JSONObject().put("flavor", ClientFlavors.TBC.flavorId),
        )
        val failure = assertThrows(IllegalStateException::class.java) {
            store(root, null).restore(snapshot, File(root, "restored"), databaseStopped = true)
        }
        assertEquals(
            "the ambiguous state is the machine's loud refusal naming the site's lane",
            "expected exactly one qualified flavor, found 2 — " +
                DatabaseSnapshotStore.SNAPSHOT_COMPATIBILITY_LANE,
            failure.message,
        )
        assertTrue(
            "a refused restore must not leave a half-written destination",
            !File(root, "restored").exists() || File(root, "restored").listFiles().isNullOrEmpty(),
        )
    }
}
