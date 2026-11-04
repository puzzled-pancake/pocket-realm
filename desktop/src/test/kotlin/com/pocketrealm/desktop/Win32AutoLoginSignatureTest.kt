package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.storage.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the pinned build-5875 login-window signature — the matcher
 * accepts exactly the qualified flavor's login frame (class + title) and
 * refuses the launcher splash / loading windows the old
 * first-visible-window pick typed into; the poll reports the honest
 * "login window never appeared" verdict on timeout instead of typing
 * blind. The pid guards are exercised without touching real windows.
 */
class Win32AutoLoginSignatureTest {

    private fun descriptor(
        windowClass: String,
        title: String,
        visible: Boolean = true,
        pid: Int = 4242,
    ) = Win32AutoLogin.WindowDescriptor(windowClass, title, visible, pid)

    @Test
    fun thePinnedVanillaLoginFrameMatches() {
        val signature = requireNotNull(Win32AutoLogin.loginSignatureFor(ClientFlavors.VANILLA.flavorId))
        assertTrue(signature.matches(descriptor("WowWindow", "World of Warcraft")))
        // The class comparison is case-insensitive; the title stays exact.
        assertTrue(signature.matches(descriptor("wowwindow", "World of Warcraft")))
    }

    @Test
    fun splashLoaderAndModalSurfacesNeverMatch() {
        val signature = requireNotNull(Win32AutoLogin.loginSignatureFor(ClientFlavors.VANILLA.flavorId))
        assertFalse("classless splash window must not match", signature.matches(descriptor("", "World of Warcraft")))
        assertFalse("a loading box must not match", signature.matches(descriptor("Static", "")))
        assertFalse("a hidden window must not match", signature.matches(descriptor("WowWindow", "World of Warcraft", visible = false)))
        assertFalse("a different title must not match", signature.matches(descriptor("WowWindow", "Pocket Realm")))
        assertFalse("a title suffix must not match", signature.matches(descriptor("WowWindow", "World of Warcraft - Vanilla")))
        assertFalse("an unowned window must not match", signature.matches(descriptor("WowWindow", "World of Warcraft", pid = 0)))
    }

    @Test
    fun flavorsWithoutACapturedSignatureHaveNoneSoAutoLoginRefuses() {
        // tbc/wotlk have CAPTURED signatures (P11 — pinned
        // from the staged exes' own PE strings, out/flavor-capture): they
        // resolve, and their alternates accept the renderer-dependent
        // GxWindowClass* candidates the engine registers at device init.
        // A flavor id with no captured pin returns null so auto-login
        // refuses honestly instead of guessing a window; qualification
        // gating happens upstream of the lookup, not inside it.
        val tbc = requireNotNull(Win32AutoLogin.loginSignatureFor("tbc"))
        assertTrue(tbc.matches(descriptor("GxWindowClassOpenGl", "World of Warcraft")))
        assertTrue(tbc.matches(descriptor("GxWindowClassD3d", "World of Warcraft")))
        val wotlk = requireNotNull(Win32AutoLogin.loginSignatureFor("wotlk"))
        assertTrue(wotlk.matches(descriptor("GxWindowClassOpenGl", "World of Warcraft")))
        assertTrue(wotlk.matches(descriptor("GxWindowClassD3d9Ex", "World of Warcraft")))
        assertNull(Win32AutoLogin.loginSignatureFor("not-a-flavor"))
    }

    @Test
    fun tryLoginRefusesUnknownPidsWithoutTouchingWindows() {
        assertEquals("client pid unknown", Win32AutoLogin.tryLogin(0, "Someone1", "Passw0rd1"))
        assertEquals("client pid unknown", Win32AutoLogin.tryLogin(-3, "Someone1", "Passw0rd1"))
    }

    @Test
    fun theWaitReportsTheHonestNeverAppearedVerdict() {
        var fakeNow = 0L
        val poll = Win32AutoLogin.pollLoginWindow(
            pid = Int.MAX_VALUE, // no process owns windows: nothing can match
            flavorId = ClientFlavors.VANILLA.flavorId,
            timings = Settings.AutoLoginTimings(sessionTimeoutMs = 300_000, pollIntervalMs = 100),
            now = { fakeNow },
            sleep = { fakeNow += it },
        )
        val timeout = poll as? Win32AutoLogin.LoginWindowPoll.NeverAppeared
        if (timeout == null) {
            throw AssertionError(
                "a pid with no windows must time out, never claim a login window",
            )
        }
        assertTrue(
            "the verdict must be the honest DESK-138 reason: ${timeout.reason}",
            "login window never appeared" in timeout.reason,
        )
    }

    @Test
    fun anUnpinnedFlavorRefusesWithoutPolling() {
        val poll = Win32AutoLogin.pollLoginWindow(
            pid = 1,
            flavorId = "not-a-flavor",
            timings = Settings.AutoLoginTimings(),
        )
        val refusal = poll as? Win32AutoLogin.LoginWindowPoll.NeverAppeared
        if (refusal == null) {
            throw AssertionError("an unpinned flavor must refuse, never poll")
        }
        assertTrue(
            "no pinned login-window signature" in refusal.reason,
        )
    }
}
