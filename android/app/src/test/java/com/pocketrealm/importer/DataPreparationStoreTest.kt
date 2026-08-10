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
}
