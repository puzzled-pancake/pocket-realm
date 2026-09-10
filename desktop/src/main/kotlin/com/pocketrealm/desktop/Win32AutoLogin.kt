package com.pocketrealm.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser.INPUT
import com.sun.jna.platform.win32.WinUser.KEYBDINPUT
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions

/**
 * Win32 SendInput auto-login — the desktop twin of the Android client
 * stack's synthetic-input login (which died with Wine: the X11 topology
 * has no Windows equivalent). Finds the launched WoW.exe's window by pid,
 * focuses it, and types account TAB password ENTER.
 *
 * Credentials are 1..16 ASCII alphanumerics by the account contract, so
 * VkKeyScanW covers every character without IME concerns. Every failure is
 * reported honestly (reason string) — auto-login must degrade to the
 * manual login screen, never crash the app or block the session.
 */
@Suppress("FunctionNaming", "MagicNumber", "ReturnCount") // Win32 API names, VK codes and key-timing constants
object Win32AutoLogin {

    private val user32: ExtendedUser32 =
        Native.load("user32", ExtendedUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)

    private const val VK_TAB = 0x09
    private const val VK_RETURN = 0x0D
    private const val VK_SHIFT = 0x10

    /** Try to log the given account into the client owned by [pid].
     * Returns null on success, or the human-readable failure reason. */
    fun tryLogin(pid: Int, username: String, password: String): String? {
        if (pid <= 0) return "client pid unknown"
        val window = findWindowForPid(pid) ?: return "no visible window for pid $pid"
        if (!user32.SetForegroundWindow(window)) return "could not focus the client window"
        sleep(SETTLE_MS)
        typeText(username) ?.let { return it }
        tap(VK_TAB) ?.let { return it }
        typeText(password)?.let { return it }
        return tap(VK_RETURN)
    }

    /** The visible top-level window owned by [pid], or null (the client is
     * still loading, was closed, or never showed a window). */
    private fun findWindowForPid(pid: Int): HWND? {
        var found: HWND? = null
        user32.EnumWindows(
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
        return found
    }

    private fun typeText(text: String): String? {
        for ((index, char) in text.withIndex()) {
            val vks = user32.VkKeyScanW(char.code)
            if (vks.toInt() == -1) {
                // The character itself never enters the reason: these are
                // credential bytes and app.log rides into support bundles.
                return "credential character at index $index has no VK mapping"
            }
            val code = vks.toInt() and 0xFF
            val shift = (vks.toInt() shr 8) and 0x01 != 0
            if (shift) tap(VK_SHIFT, holdDown = true)?.let { return it }
            tap(code)?.let { return it }
            if (shift) tap(VK_SHIFT, releaseHeld = true)?.let { return it }
            sleep(KEY_GAP_MS)
        }
        return null
    }

    /** Press and release [vk]. [holdDown] sends the press half only and
     * LEAVES THE KEY HELD; [releaseHeld] sends the release half only —
     * together they form modifier chords (shift + letter). Returns the
     * failure reason or null. */
    private fun tap(vk: Int, holdDown: Boolean = false, releaseHeld: Boolean = false): String? {
        if (!releaseHeld) {
            if (send(vk, up = false) != 1) return "SendInput press failed"
            sleep(KEY_HOLD_MS)
        }
        if (!holdDown) {
            if (send(vk, up = true) != 1) return "SendInput release failed"
        }
        return null
    }

    /** 1 when the event was injected; anything else is a failure. */
    private fun send(vk: Int, up: Boolean): Int {
        val keyboard = KEYBDINPUT()
        keyboard.wVk = WORD(vk.toLong())
        keyboard.dwFlags = DWORD(if (up) KEYBDINPUT.KEYEVENTF_KEYUP.toLong() else 0L)
        val input = INPUT()
        input.type = DWORD(INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki = keyboard
        return user32.SendInput(DWORD(1), arrayOf(input), input.size()).toInt()
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private const val SETTLE_MS = 2_500L
    private const val KEY_GAP_MS = 45L
    private const val KEY_HOLD_MS = 25L

    /** The User32 additions JNA's stock interface does not declare. */
    private interface ExtendedUser32 : User32 {
        fun VkKeyScanW(ch: Int): Short
    }
}
