package com.pocketrealm.importer

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * DESK-97 scratch-lane name policy pins: classification must reject
 * Windows-illegal trailing dot/space components exactly like the client
 * archive lane. java.io writes of "X" and "X." land on the same physical
 * file, so without the rejection archive entries X and X. pass the VAL-06
 * duplicate gate and silently overwrite each other mid-extraction.
 */
class ScratchArchiveExtractorTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule val temp = TemporaryFolder()

    @Test fun trailingDotOrSpaceEntryNamesAreRejectedAsVal07() {
        listOf("setup.exe.", "setup-1.bin ", "slice/setup-2.bin.")
            .forEach { name ->
                val zipFile = SyntheticClientArchives.zip(
                    SyntheticClientArchives.uniqueFile(temp.newFolder(), "aliasing"),
                    listOf(
                        SyntheticClientArchives.Entry("setup.exe", ByteArray(2)),
                        SyntheticClientArchives.Entry(name, ByteArray(2)),
                    ),
                )
                val error = assertThrows(name, ImportRejected::class.java) {
                    ScratchArchiveExtractor.extract(zipFile, ArchiveFormat.ZIP, temp.newFolder(), ImportLimits())
                }
                assertTrue(error.message!!.startsWith("VAL-07: trailing dot or space"))
            }
    }
}
