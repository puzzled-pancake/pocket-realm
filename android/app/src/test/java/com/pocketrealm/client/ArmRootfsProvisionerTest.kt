package com.pocketrealm.client

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files

class ArmRootfsProvisionerTest {
    @Test fun `archive root directory is skipped but root files and links are not`() {
        assertTrue(ArmRootfsProvisioner.isArchiveRootDirectory("./", directory = true))
        assertTrue(ArmRootfsProvisioner.isArchiveRootDirectory(".", directory = true))
        assertFalse(ArmRootfsProvisioner.isArchiveRootDirectory("./", directory = false))
        assertFalse(ArmRootfsProvisioner.isArchiveRootDirectory(".", directory = false))
        assertFalse(ArmRootfsProvisioner.isArchiveRootDirectory("../", directory = true))
        assertFalse(ArmRootfsProvisioner.isArchiveRootDirectory("etc", directory = true))
    }

    @Test fun `only obsolete container z drive link is skipped`() {
        val obsolete = TarArchiveEntry("./.wine/dosdevices/z:", TarArchiveEntry.LF_SYMLINK).apply {
            linkName = "/data/data/com.winlator/files/rootfs"
        }
        assertTrue(ArmRootfsProvisioner.isRetiredContainerRootLink(
            "arm-translated-wine/container_pattern.tzst", obsolete,
        ))

        val otherPath = TarArchiveEntry("./.wine/dosdevices/x:", TarArchiveEntry.LF_SYMLINK).apply {
            linkName = "/data/data/com.winlator/files/rootfs"
        }
        assertFalse(ArmRootfsProvisioner.isRetiredContainerRootLink(
            "arm-translated-wine/container_pattern.tzst", otherPath,
        ))

        val otherTarget = TarArchiveEntry("./.wine/dosdevices/z:", TarArchiveEntry.LF_SYMLINK).apply {
            linkName = "/unexpected"
        }
        assertFalse(ArmRootfsProvisioner.isRetiredContainerRootLink(
            "arm-translated-wine/container_pattern.tzst", otherTarget,
        ))
    }

    @Test fun `audio provider accepts only the pinned AArch64 plugin identity`() {
        val digest = "209927b86066863fbe4f3607273577d4af1534036d3b5b59f87b882b15f3346c"
        assertTrue(ArmRootfsProvisioner.isExpectedAudioPluginIdentity(73_216L, digest, 0x00b7))
        assertFalse(ArmRootfsProvisioner.isExpectedAudioPluginIdentity(55_280L, digest, 0x00b7))
        assertFalse(ArmRootfsProvisioner.isExpectedAudioPluginIdentity(73_216L, digest, 0x003e))
        assertFalse(ArmRootfsProvisioner.isExpectedAudioPluginIdentity(
            73_216L, "0".repeat(64), 0x00b7,
        ))
    }

    @Test fun `provisioning strips only unscoped OpenGL clients`() {
        val root = Files.createTempDirectory("pocket-rootfs-policy").toFile()
        try {
            val libraries = root.resolve("usr/lib").apply { mkdirs() }
            listOf("libGL.so", "libGL.so.1", "libGL.so.1.7.0", "libGL.so.1.6.0").forEach {
                libraries.resolve(it).writeText("provider OpenGL client")
            }
            val retained = libraries.resolve("libX11.so.6").apply { writeText("retained") }
            assertFalse(ArmRootfsProvisioner.hasNoUnscopedOpenGlClient(root))

            ArmRootfsProvisioner.stripUnscopedOpenGlClient(root)

            assertTrue(ArmRootfsProvisioner.hasNoUnscopedOpenGlClient(root))
            assertTrue(retained.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `provisioning unlinks outside OpenGL symlink without touching target`() {
        val root = Files.createTempDirectory("pocket-rootfs-symlink").toFile()
        val outside = Files.createTempFile("pocket-rootfs-outside", ".so").toFile()
        try {
            val libraries = root.resolve("usr/lib").apply { mkdirs() }
            val outsideAlias = libraries.resolve("libGL.so.backup")
            try {
                Files.createSymbolicLink(outsideAlias.toPath(), outside.toPath())
            } catch (unsupported: java.io.IOException) {
                assumeNoException("host cannot create symbolic links", unsupported)
            } catch (unsupported: UnsupportedOperationException) {
                assumeNoException("host cannot create symbolic links", unsupported)
            }

            ArmRootfsProvisioner.stripUnscopedOpenGlClient(root)

            assertTrue(outside.isFile)
            assertFalse(Files.exists(
                outsideAlias.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}
