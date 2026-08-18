package com.pocketrealm.client

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmRuntimeRetirementTest {
    @Test
    fun `retirement moves a large tree without deleting it on the caller`() {
        withRoot { root ->
            val active = File(root, "winlator-ca3d735/rootfs/.pocket-rootfs-ready")
                .apply { parentFile!!.mkdirs(); writeText("ready") }
            File(root, "fexcore-2608/rootfs/usr/share/locale/test/message")
                .apply { parentFile!!.mkdirs(); writeText("legacy") }
            val queued = mutableListOf<File>()

            ArmRuntimeRetirement.retireFexGeneration(root, queued::add)

            assertFalse(File(root, "fexcore-2608").exists())
            assertTrue("the active Box64 runtime must not be touched", active.isFile)
            assertEquals(1, queued.size)
            assertTrue(queued.single().isDirectory)
            val movedLeaf = File(queued.single(), "rootfs/usr/share/locale/test/message")
            assertTrue(movedLeaf.isFile)
            assertEquals("legacy", movedLeaf.readText())
        }
    }

    @Test
    fun `a tombstone left by process death is discovered and reclaimed later`() {
        withRoot { root ->
            val tombstone = File(root, ".retired-fexcore-2608-0123456789abcdef0123456789abcdef")
                .apply { mkdirs(); resolve("pending").writeText("data") }
            val queued = mutableListOf<File>()

            ArmRuntimeRetirement.retireFexGeneration(root, queued::add)

            assertEquals(listOf(tombstone.canonicalFile), queued.map(File::getCanonicalFile))
            assertTrue(ArmRuntimeRetirement.deleteTombstone(root, tombstone))
            assertFalse(tombstone.exists())
            ArmRuntimeRetirement.retireFexGeneration(root, queued::add)
            assertEquals(1, queued.size)
        }
    }

    @Test
    fun `cleanup accepts only exact direct-child tombstones`() {
        withRoot { root ->
            val active = File(root, "winlator-ca3d735").apply { mkdirs() }
            val misleading = File(root, ".retired-fexcore-2608-not-a-token")
                .apply { mkdirs(); resolve("keep").writeText("data") }
            val outside = Files.createTempDirectory("pr-retired-outside").toFile()
            try {
                assertFalse(ArmRuntimeRetirement.deleteTombstone(root, active))
                assertFalse(ArmRuntimeRetirement.deleteTombstone(root, misleading))
                assertFalse(ArmRuntimeRetirement.deleteTombstone(root, outside))
                assertTrue(active.exists())
                assertTrue(misleading.exists())
                assertTrue(outside.exists())
            } finally {
                outside.deleteRecursively()
            }
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = Files.createTempDirectory("pr-arm-runtime-retire").toFile()
        val root = File(parent, "arm-translated").apply { mkdirs() }
        try {
            block(root)
        } finally {
            parent.deleteRecursively()
        }
    }
}
