package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TolerantImportNamesTest {
    @Before fun pinLane() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After fun clearLane() = ActiveFlavor.reset()

    private val policy: ImportExtractionPolicy get() = ImportExtractionPolicy()

    private fun file(name: String) = ImportExtractionPolicy.RawEntry(name, directory = false, size = 1)
    private fun dir(name: String) = ImportExtractionPolicy.RawEntry(name, directory = true, size = 0)

    @Test fun oddNamesAndCaseDuplicatesAreSkippedNotFatal() {
        val result = policy.classify(
            listOf(
                file("WoW.exe"),
                dir("Data"),
                file("Data/common.MPQ"),
                file("Data/Common.mpq"),
                dir("Interface"),
                dir("Interface/AddOns/Foo "),
                file("Interface/AddOns/Foo /Foo.toc"),
                dir("Interface/AddOns/Bar"),
                dir("Interface/AddOns/bar"),
                file("Interface/AddOns/bar/bar.lua"),
                dir("Cache"),
                file("Cache/WDB/x.wdb"),
            ),
        )
        val copied = result.entries.map { it.relativePath }
        assertTrue("Data/Common.mpq" in copied)
        assertTrue("Interface/AddOns/Bar" in copied)
        assertTrue(copied.none { it.startsWith("Interface/AddOns/bar") || it.startsWith("Cache") })
        assertTrue(copied.none { "Foo" in it })
        val reasons = result.excluded.associate { it.relativePath to it.reason }
        assertTrue(reasons.getValue("Data/common.MPQ").contains("differing only in case"))
        assertTrue(reasons.keys.any { it.startsWith("Interface/AddOns/Foo") })
        assertEquals("non-client root directory", reasons.getValue("Cache"))
    }

    @Test fun pathEscapesStillRefuse() {
        assertThrows(ImportRejected::class.java) { policy.classify(listOf(file("../evil.exe"))) }
        assertThrows(ImportRejected::class.java) { policy.classify(listOf(file("/etc/passwd"))) }
    }
}
