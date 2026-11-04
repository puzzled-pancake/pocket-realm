package com.pocketrealm.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference

/**
 * The client-window seam behind the shared ForegroundPromotionPolicy's
 * verbs: finds the visible top-level window of a pid and moves
 * the OS foreground to or away from it.
 *
 * On Android the supervisor promotes/demotes the world/database services'
 * foreground-service priority. The desktop runtime serves those components
 * in THIS process — its only foreground surface is the WoW window — so the
 * same promote/demote decisions act on the client window here: promote
 * pulls the game forward while players are present, demote hands the
 * foreground back to the app's own console. Every outcome is honest: a
 * missing window or a refused SetForegroundWindow is a reported failure,
 * never a faked ok.
 */
internal object Win32ClientWindow {
    private val user32 = User32.INSTANCE

    /** The first visible top-level window owned by [pid], or null (the
     * client is minimized-to-tray-less, closed, or never showed a window). */
    fun visibleWindowFor(pid: Int): HWND? {
        var found: HWND? = null
        val enumerated = user32.EnumWindows(
            WNDENUMPROC { hwnd, _ ->
                val owner = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value == pid && user32.IsWindowVisible(hwnd) && found == null) {
                    found = hwnd
                }
                true
            },
            Pointer.NULL,
        )
        if (!enumerated) return null
        return found
    }

    /**
     * Pulls the client window to the foreground. Returns null on success,
     * or the honest failure reason (Win32 foreground restrictions can
     * legitimately refuse the change — that is reported, never swallowed).
     */
    @Suppress("ReturnCount") // one early verdict per Win32 outcome is the clearest shape
    fun promote(pid: Int): String? {
        if (pid <= 0) return "client pid unknown"
        val window = visibleWindowFor(pid) ?: return "no visible window for pid $pid"
        return if (user32.SetForegroundWindow(window)) {
            null
        } else {
            "SetForegroundWindow refused the client window (pid $pid)"
        }
    }

    /**
     * Drops the client window's foreground: when it currently holds the
     * focus, the app's own console window takes it back (a demote must not
     * minimize or hide the user's game). Returns null on success — including
     * the genuinely-nothing-to-demote cases (window gone, focus already
     * elsewhere) — or the honest failure reason.
     */
    @Suppress("ReturnCount") // one early verdict per Win32 outcome is the clearest shape
    fun demote(pid: Int): String? {
        if (pid <= 0) return null
        val window = visibleWindowFor(pid) ?: return null
        if (user32.GetForegroundWindow() != window) return null
        // GetConsoleWindow is a kernel32 export (the jpackage launcher is
        // --win-console, so the app owns one; a windowless launch gets null).
        val console = Kernel32.INSTANCE.GetConsoleWindow() ?: return DEMOTE_REFUSED
        return if (user32.SetForegroundWindow(console)) null else DEMOTE_REFUSED
    }

    private const val DEMOTE_REFUSED =
        "client window holds the foreground and could not be demoted"
}
