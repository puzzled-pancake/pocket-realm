package com.pocketrealm.addons

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonPackageMaintenanceTest {
    @Test fun `startup cleanup removes only unpublished staging products`() {
        val packages = Files.createTempDirectory("addon-packages-").toFile().apply { deleteOnExit() }
        val staleDirectory = File(packages, ".staging-mrthinger__wow-voiceover-old").apply {
            mkdirs()
            File(this, "partial-audio.mp3").writeText("partial")
        }
        val staleFile = File(packages, ".staging-interrupted.tmp").apply { writeText("partial") }
        val published = File(packages, "mrthinger__wow-voiceover/release").apply {
            mkdirs()
            File(this, "keep.txt").writeText("published")
        }
        val unrelatedHidden = File(packages, ".keep").apply { writeText("keep") }

        assertEquals(2, cleanupStaleAddonStaging(packages))

        assertFalse(staleDirectory.exists())
        assertFalse(staleFile.exists())
        assertTrue(published.isDirectory)
        assertTrue(unrelatedHidden.isFile)
        assertEquals(0, cleanupStaleAddonStaging(packages))
    }
}
