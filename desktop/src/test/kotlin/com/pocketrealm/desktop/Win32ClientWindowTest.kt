package com.pocketrealm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The client-window seam's pid-guard contract (the coverage note on
 * Win32ClientWindow): a non-positive pid can never name a window, so
 * promote refuses honestly and demote's nothing-to-demote postcondition
 * holds — both without touching a single real window, which no test
 * machine is guaranteed to have.
 */
class Win32ClientWindowTest {
    @Test
    fun promoteRefusesNonPositivePidsWithoutTouchingWindows() {
        assertEquals("client pid unknown", Win32ClientWindow.promote(0))
        assertEquals("client pid unknown", Win32ClientWindow.promote(-4))
    }

    @Test
    fun demoteTreatsNonPositivePidsAsNothingToDemote() {
        assertNull(Win32ClientWindow.demote(0))
        assertNull(Win32ClientWindow.demote(-4))
    }
}
