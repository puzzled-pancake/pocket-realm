package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportPathPolicyTest {
    private val policy = ImportPathPolicy(ImportLimits(minFiles = 1, minTotalBytes = 0))

    @Test fun acceptsAndNormalizesSafeRelativePaths() {
        assertEquals("Data/base.MPQ", policy.normalize("Data\\base.MPQ"))
        assertEquals("Data/ABC.MPQ", policy.normalize("Data/ＡＢＣ.MPQ"))
    }

    @Test fun rejectsAbsoluteTraversalControlAndProviderSeparators() {
        listOf("/etc/passwd", "C:/Windows", "Data/../WoW.exe", "Data//x", "Data/\u0000x")
            .forEach { value -> assertThrows(value, ImportRejected::class.java) { policy.normalize(value) } }
        assertThrows(ImportRejected::class.java) { policy.providerChild("Data", "sub/name") }
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
