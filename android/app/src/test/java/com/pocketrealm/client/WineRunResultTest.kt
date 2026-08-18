package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineRunResultTest {
    @Test fun parsesBoundedNativeResult() {
        val result = parseWineRunResult(
            "RC=0|EXIT=0|TIMED_OUT=0|TREE_DRAINED=1|DESCS=1\n  pid=1|ppid=0|comm=wine" +
                "\n@@@STDOUT@@@\nPOCKET_SELFTEST_OK\n@@@STDERR@@@\nwarning",
        )
        assertEquals(0, result.rc)
        assertTrue(result.exitedCleanly)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.processTreeDrained)
        assertTrue(result.stdout.contains("POCKET_SELFTEST_OK"))
        assertEquals("warning", result.stderr)
    }

    @Test fun recognizesSignalExit() {
        val result = parseWineRunResult("RC=0|EXIT=9|TIMED_OUT=0|DESCS=0\n@@@STDOUT@@@\n\n@@@STDERR@@@\n")
        assertFalse(result.exitedCleanly)
        assertEquals(-1, result.exitCode)
        assertFalse(result.processTreeDrained)
    }
}
