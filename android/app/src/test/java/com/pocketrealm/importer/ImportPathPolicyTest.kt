package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

class ImportPathPolicyTest {
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

 // Flip: getter, not a field - ImportLimits' default resolves the
    // active lane and fields initialize before the @Before pin.
    private val policy: ImportPathPolicy get() = ImportPathPolicy(ImportLimits(minFiles = 1, minTotalBytes = 0))

    @Test fun acceptsAndNormalizesSafeRelativePaths() {
        assertEquals("Data/base.MPQ", policy.normalize("Data\\base.MPQ"))
        assertEquals("Data/ABC.MPQ", policy.normalize("Data/ＡＢＣ.MPQ"))
    }

    @Test fun rejectsAbsoluteTraversalControlAndProviderSeparators() {
        listOf("/etc/passwd", "C:/Windows", "Data/../WoW.exe", "Data//x", "Data/\u0000x")
            .forEach { value -> assertThrows(value, ImportRejected::class.java) { policy.normalize(value) } }
        assertThrows(ImportRejected::class.java) { policy.providerChild("Data", "sub/name") }
    }

    @Test fun normalizeEntryRejectsTrailingDotOrSpaceComponents() {
        assertEquals("Data/base.MPQ", policy.normalizeEntry("Data\\base.MPQ"))
        listOf("WoW.exe.", "WoW.exe ", "Data/patch-3.mpq.", "Data/sub ")
            .forEach { value -> assertThrows(value, ImportRejected::class.java) { policy.normalizeEntry(value) } }
    }

    @Test fun caseFoldKeyDetectsPortableCollisions() {
        assertEquals(policy.caseFoldKey("Data/base.MPQ"), policy.caseFoldKey("data/BASE.mpq"))
        assertNotEquals(policy.caseFoldKey("Data/base.MPQ"), policy.caseFoldKey("Data/dbc.MPQ"))
    }

    @Test fun boundsDepthAndComponentLength() {
        assertThrows(ImportRejected::class.java) {
            ImportPathPolicy(ImportLimits(minFiles = 1, minTotalBytes = 0, maxDepth = 2)).normalize("a/b/c")
        }
        assertThrows(ImportRejected::class.java) {
            ImportPathPolicy(ImportLimits(minFiles = 1, minTotalBytes = 0, maxComponentChars = 3)).normalize("abcd")
        }
    }
}
