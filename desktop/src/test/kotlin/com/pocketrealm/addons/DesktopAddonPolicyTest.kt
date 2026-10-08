package com.pocketrealm.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-by-field pin of the one desktop addon policy table the repository
 * and the launch-boundary projector share (VanillaAddonPinTest style): the
 * retired-product identities and the built-in bundle caps were previously
 * duplicated between the two and drifted in shape — this freezes the hoisted
 * table so an accidental edit of any literal fails the lane.
 */
class DesktopAddonPolicyTest {
    @Test fun `retired product install ids stay at the shipped literals`() {
        assertEquals(
            setOf("bundled__pocketrealmpad", "pepordev__consoleexperienceclassic"),
            DesktopAddonPolicy.RETIRED_PRODUCT_INSTALL_IDS,
        )
        assertEquals(
            setOf("PocketRealmPad", "PocketRealmPadLauncher"),
            DesktopAddonPolicy.RETIRED_PRODUCT_FOLDERS,
        )
    }

    @Test fun `retired product matching is folder-case-insensitive`() {
        assertTrue(
            DesktopAddonPolicy.isRetiredProduct(
                InstalledAddon(
                    id = "unrelated__addon",
                    repository = "unrelated/addon",
                    displayName = "Unrelated",
                    commitSha = "a".repeat(40),
                    archiveSha256 = "b".repeat(64),
                    installedAtEpochMs = 0L,
                    packagePath = "packages/unrelated__addon/x",
                    folders = listOf("POCKETREALMPADLAUNCHER"),
                ),
            ),
        )
        assertFalse(
            DesktopAddonPolicy.isRetiredProduct(
                InstalledAddon(
                    id = "unrelated__addon",
                    repository = "unrelated/addon",
                    displayName = "Unrelated",
                    commitSha = "a".repeat(40),
                    archiveSha256 = "b".repeat(64),
                    installedAtEpochMs = 0L,
                    packagePath = "packages/unrelated__addon/x",
                    folders = listOf("AndroidPort"),
                ),
            ),
        )
    }

    @Test fun `built-in bundle caps stay at the shipped literals`() {
        assertEquals(512, DesktopAddonPolicy.MAX_BUILTIN_FILES)
        assertEquals(8L * 1024 * 1024, DesktopAddonPolicy.MAX_BUILTIN_BYTES)
        assertEquals(
            setOf("lua", "toc", "xml", "md", "txt", "tga", "blp"),
            DesktopAddonPolicy.BUILTIN_ALLOWED_EXTENSIONS,
        )
    }
}
