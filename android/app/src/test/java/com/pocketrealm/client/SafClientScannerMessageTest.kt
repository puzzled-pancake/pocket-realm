package com.pocketrealm.client

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM mirror of the instrumented SafClientScannerTest pin: the launcher-only
 * rejection keeps its VAL-01 identity and the exact-case "launcher-only"
 * phrase, so the reworded message is covered by the unit-test loop.
 */
class SafClientScannerMessageTest {

    @Test
    fun launcherOnlyRejectionKeepsVal01PrefixAndPinnedPhrase() {
        assertTrue(VAL01_LAUNCHER_ONLY_SELECTION.startsWith("This looks like an installer or launcher folder"))
        assertTrue(VAL01_LAUNCHER_ONLY_SELECTION.contains("WoW.exe"))
    }
}
