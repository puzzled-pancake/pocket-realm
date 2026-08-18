package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DataPreparationStoreTest {
    @Test fun `zero length recovery candidates are bounded to regular files and sorted`() {
        val root = Files.createTempDirectory("o11-vmap-repair").toFile()
        try {
            val nested = root.resolve("nested").apply { mkdirs() }
            root.resolve("z.wmo").writeBytes(byteArrayOf())
            nested.resolve("a.m2").writeBytes(byteArrayOf())
            root.resolve("valid.wmo").writeBytes(byteArrayOf(1, 2, 3))
            root.resolve("empty-directory").mkdir()

            assertEquals(
                listOf("nested/a.m2", "z.wmo"),
                zeroLengthFiles(root).map { it.relativeTo(root).invariantSeparatorsPath },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `zero length recovery candidates are empty when root is absent`() {
        val root = Files.createTempDirectory("o11-vmap-missing").toFile()
        root.deleteRecursively()
        assertEquals(emptyList<java.io.File>(), zeroLengthFiles(root))
    }

    @Test fun `mmap map ids union terrain tiles and vmtrees and ignore other vmap artifacts`() {
        val root = Files.createTempDirectory("o11-mmap-ids").toFile()
        try {
            val maps = root.resolve("maps").apply { mkdirs() }
            val vmaps = root.resolve("vmaps").apply { mkdirs() }
            maps.resolve("0002035.map").writeBytes(byteArrayOf(1))
            maps.resolve("0010230.map").writeBytes(byteArrayOf(1))
            maps.resolve("0010231.map").writeBytes(byteArrayOf(1))
            vmaps.resolve("000.vmtree").writeBytes(byteArrayOf(1))
            vmaps.resolve("070.vmtree").writeBytes(byteArrayOf(1))
            vmaps.resolve("349.vmtree").writeBytes(byteArrayOf(1))
            // WMO-only dungeon maps must not be excluded by sharing the vmaps
            // directory with model artifacts that have non-numeric prefixes.
            vmaps.resolve("Uldamanpassage.wmo.vmo").writeBytes(byteArrayOf(1))
            vmaps.resolve("temp_gameobject_models").writeBytes(byteArrayOf(1))
            maps.resolve("notes.txt").writeBytes(byteArrayOf(1))

            assertEquals(listOf(0, 1, 70, 349), derivedMmapMapIds(maps, vmaps))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `mmap map ids fall back to vmtrees when no terrain tiles exist`() {
        val root = Files.createTempDirectory("o11-mmap-vmtree-only").toFile()
        try {
            val maps = root.resolve("maps").apply { mkdirs() }
            val vmaps = root.resolve("vmaps").apply { mkdirs() }
            vmaps.resolve("070.vmtree").writeBytes(byteArrayOf(1))
            vmaps.resolve("533.vmtree").writeBytes(byteArrayOf(1))

            assertEquals(listOf(70, 533), derivedMmapMapIds(maps, vmaps))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `mmap map ids are empty without either source`() {
        val root = Files.createTempDirectory("o11-mmap-none").toFile()
        root.deleteRecursively()
        assertEquals(emptyList<Int>(), derivedMmapMapIds(root, root))
    }
}
