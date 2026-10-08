package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.storage.Settings
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
 * stack's synthetic-input login. DESK-138: the automation no longer types
 * into the first visible window of the client pid — it polls for the
 * PINNED qualified-vanilla login-frame window signature (class + title, keyed
 * off the qualified [ClientFlavors] lane, pinned like RpWowClient pins
 * protocol constants) until the DESK-133-configured timeout, and reports
 * the honest "login window never appeared" reason instead of typing
 * credentials into a splash, loader, or modal.
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

    /**
     * The pinned window signature of one qualified flavor's login frame.
     * DESK-138 pins these like protocol constants: the qualified vanilla
     * 1.12.1 client's login frame is the top-level visible window of
     * class `WowWindow` titled exactly `World of Warcraft` — the launcher
     * splash and loading windows the old first-visible-window pick
     * accepted carry neither.
     *
     * [alternateWindowClasses] (P11, lanes item 19) covers the flavors whose
     * engine registers the login frame under a renderer-dependent class name:
     * the staged 8606/12340 exes each embed several `GxWindowClass*` literals
     * (OpenGL / Direct3D / Direct3D9Ex) and pick one at device init, so the
     * pinned signature accepts any captured candidate while the exact title
     * still discriminates the frame. The alternates are captured from the
     * client binaries by tools/capture_flavor_framexml.py (static PE string
     * scan — the clients were never launched) and recorded in
     * out/flavor-capture/<flavor>/manifest.json.
     */
    data class LoginWindowSignature(
        val windowClass: String,
        val title: String,
        val alternateWindowClasses: Set<String> = emptySet(),
    ) {
        /** Every class name this signature accepts (primary first). */
        val acceptedWindowClasses: List<String> =
            listOf(windowClass) + alternateWindowClasses.toList()

        /** True when [descriptor] is exactly this login frame: visible,
         * pid-owned, class case-insensitive over the accepted set, title exact. */
        fun matches(descriptor: WindowDescriptor): Boolean =
            descriptor.visible &&
                descriptor.ownerPid > 0 &&
                acceptedWindowClasses.any {
                    descriptor.windowClass.equals(it, ignoreCase = true)
                } &&
                descriptor.title == title
    }

    /** The observable facts of one top-level window (the EnumWindows row),
     * kept descriptor-shaped so the matcher is unit-testable without Win32. */
    data class WindowDescriptor(
        val windowClass: String,
        val title: String,
        val visible: Boolean,
        val ownerPid: Int,
    )

    /** The outcome of one [pollLoginWindow] wait. */
    sealed class LoginWindowPoll {
        /** The pinned login frame held the signature for the settle budget. */
        data class Found(val hwnd: HWND) : LoginWindowPoll()

        /** The budget was spent without a stable match; [reason] is the
         * honest verdict the user/log gets (degrade to manual login). */
        class NeverAppeared(val reason: String) : LoginWindowPoll()
    }

    /**
     * Login-frame signatures per flavor id (keyed off the shared
     * [ClientFlavors] registry — a flavor without an entry has no pin, so
     * auto-login refuses honestly for it instead of guessing a window).
     *
     * The tbc/wotlk entries (P11, lanes item 19) are pinned from the staged
     * clients' own binaries via tools/capture_flavor_framexml.py: both exes
     * embed the `GxWindowClassOpenGl`/`GxWindowClassD3d` literals (12340
     * additionally `GxWindowClassD3d9Ex`) and the exact null-terminated
     * UTF-16LE `World of Warcraft` title string; capture artifacts live in
     * out/flavor-capture/{tbc,wotlk}/manifest.json. The classes are
     * renderer-dependent (the engine registers the one matching the active
     * Gx device), so every captured candidate is accepted.
     */
    private val LOGIN_SIGNATURES: Map<String, LoginWindowSignature> = mapOf(
        ClientFlavors.VANILLA.flavorId to LoginWindowSignature(
            windowClass = "WowWindow",
            title = "World of Warcraft",
        ),
        ClientFlavors.TBC.flavorId to LoginWindowSignature(
            windowClass = "GxWindowClassOpenGl",
            title = "World of Warcraft",
            alternateWindowClasses = setOf("GxWindowClassD3d"),
        ),
        ClientFlavors.WOTLK.flavorId to LoginWindowSignature(
            windowClass = "GxWindowClassOpenGl",
            title = "World of Warcraft",
            alternateWindowClasses = setOf("GxWindowClassD3d", "GxWindowClassD3d9Ex"),
        ),
    )

    /** The pinned signature for [flavorId], or null when the flavor has no
     * qualified login-frame pin (auto-login must refuse, not approximate). */
    fun loginSignatureFor(flavorId: String): LoginWindowSignature? =
        LOGIN_SIGNATURES[flavorId]

    /** Poll clock seams (injectable so tests drive the timeout instantly). */
    fun interface NowMs { fun now(): Long }
    fun interface SleepMs { fun sleep(ms: Long) }

    /**
     * Polls the pid's top-level windows for the flavor's pinned login
     * signature — replacing the old fixed 2.5 s settle — until the
     * [Settings.AutoLoginTimings] budget is spent, and requires the match
     * to hold continuously (`requiredStablePolls` consecutive matches
     * spanning `loginUiSettleMs`, the Android settle contract) before
     * handing the window over. Returns [LoginWindowPoll.Found] with the
     * stable login window, or [LoginWindowPoll.NeverAppeared] with the
     * honest reason on timeout.
     */
    fun pollLoginWindow(
        pid: Int,
        flavorId: String,
        timings: Settings.AutoLoginTimings,
        now: NowMs = NowMs { System.currentTimeMillis() },
        sleep: SleepMs = SleepMs { Thread.sleep(it) },
    ): LoginWindowPoll {
        val signature = loginSignatureFor(flavorId)
            ?: return LoginWindowPoll.NeverAppeared(
                "no pinned login-window signature for flavor $flavorId",
            )
        val deadline = now.now() + timings.sessionTimeoutMs
        var stableMatches = 0
        var matchedSinceMs: Long? = null
        var matchedDescriptor: WindowDescriptor? = null
        var matchedHwnd: HWND? = null
        while (now.now() < deadline) {
            // Re-enumerate every poll and compare the DESCRIPTOR (structural),
            // never the HWND reference: each enumeration materializes a fresh
            // JNA handle for the same OS window, and re-checking class+title
            // every poll is exactly the continuous-match gate the settle
            // contract wants (a modal replacing the frame resets it).
            val found = findWindowForPid(pid) { descriptor -> signature.matches(descriptor) }
            val descriptor = found?.second
            if (descriptor != matchedDescriptor) {
                matchedDescriptor = descriptor
                matchedHwnd = found?.first
                matchedSinceMs = if (found == null) null else now.now()
                stableMatches = if (found == null) 0 else 1
            } else if (found != null) {
                matchedHwnd = found.first
                stableMatches++
            }
            val since = matchedSinceMs
            if (matchedDescriptor != null && since != null &&
                stableMatches >= timings.requiredStablePolls &&
                now.now() - since >= timings.loginUiSettleMs
            ) {
                return LoginWindowPoll.Found(matchedHwnd!!)
            }
            sleep.sleep(timings.pollIntervalMs)
        }
        return LoginWindowPoll.NeverAppeared(
            "login window never appeared within ${timings.sessionTimeoutMs / 1_000} s " +
                "(waited for the pinned ${signature.windowClass} login frame of pid $pid)",
        )
    }

    /** Try to log the given account into the client owned by [pid].
     * Returns null on success, or the human-readable failure reason.
     * The [timings] and [flavorId] parameters are the DESK-133/138
     * surfaces; the defaults keep the RpHost probe lane's 3-arg calls
     * on the vanilla pinned behavior. */
    fun tryLogin(
        pid: Int,
        username: String,
        password: String,
        timings: Settings.AutoLoginTimings = Settings.AutoLoginTimings(),
        flavorId: String = ClientFlavors.VANILLA.flavorId,
    ): String? {
        if (pid <= 0) return "client pid unknown"
        val window = when (val poll = pollLoginWindow(pid, flavorId, timings)) {
            is LoginWindowPoll.NeverAppeared -> return poll.reason
            is LoginWindowPoll.Found -> poll.hwnd
        }
        if (!user32.SetForegroundWindow(window)) return "could not focus the client window"
        typeText(username, timings)?.let { return it }
        tap(VK_TAB, timings.imeKeyDwellMs)?.let { return it }
        sleep(timings.fieldSettleMs)
        typeText(password, timings)?.let { return it }
        return tap(VK_RETURN, timings.imeKeyDwellMs)
    }

    /** The visible top-level window owned by [pid] whose descriptor
     * satisfies [matches] — the window handle paired with the descriptor
     * that matched — or null (the client is still loading, was closed, or
     * never showed the wanted window). */
    private fun findWindowForPid(
        pid: Int,
        matches: (WindowDescriptor) -> Boolean,
    ): Pair<HWND, WindowDescriptor>? {
        var found: Pair<HWND, WindowDescriptor>? = null
        user32.EnumWindows(
            WNDENUMPROC { hwnd, _ ->
                val owner = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value == pid && user32.IsWindowVisible(hwnd) && found == null) {
                    val descriptor = WindowDescriptor(
                        windowClass = windowClassOf(hwnd),
                        title = windowTitleOf(hwnd),
                        visible = true,
                        ownerPid = owner.value,
                    )
                    if (matches(descriptor)) found = hwnd to descriptor
                }
                true
            },
            Pointer.NULL,
        )
        return found
    }

    private fun windowClassOf(hwnd: HWND): String {
        val buffer = CharArray(WINDOW_CLASS_BUFFER_CHARS)
        val copied = user32.GetClassNameW(hwnd, buffer, WINDOW_CLASS_BUFFER_CHARS)
        return if (copied > 0) String(buffer, 0, copied) else ""
    }

    private fun windowTitleOf(hwnd: HWND): String {
        val buffer = CharArray(WINDOW_TITLE_BUFFER_CHARS)
        val copied = user32.GetWindowTextW(hwnd, buffer, WINDOW_TITLE_BUFFER_CHARS)
        return if (copied > 0) String(buffer, 0, copied) else ""
    }

    private fun typeText(text: String, timings: Settings.AutoLoginTimings): String? {
        for ((index, char) in text.withIndex()) {
            val vks = user32.VkKeyScanW(char.code)
            if (vks.toInt() == -1) {
                // The character itself never enters the reason: these are
                // credential bytes and app.log rides into support bundles.
                return "credential character at index $index has no VK mapping"
            }
            val code = vks.toInt() and 0xFF
            val shift = (vks.toInt() shr 8) and 0x01 != 0
            if (shift) tap(VK_SHIFT, timings.imeKeyDwellMs)?.let { return it }
            tap(code, timings.imeKeyDwellMs)?.let { return it }
            if (shift) tap(VK_SHIFT, timings.imeKeyDwellMs, releaseHeld = true)?.let { return it }
            sleep(timings.imeKeyGapMs)
        }
        return null
    }

    /** Press and release [vk]. [holdDown] sends the press half only and
     * LEAVES THE KEY HELD; [releaseHeld] sends the release half only —
     * together they form modifier chords (shift + letter). Returns the
     * failure reason or null. */
    private fun tap(
        vk: Int,
        dwellMs: Long,
        holdDown: Boolean = false,
        releaseHeld: Boolean = false,
    ): String? {
        if (!releaseHeld) {
            if (send(vk, up = false) != 1) return "SendInput press failed"
            sleep(dwellMs)
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

    private const val WINDOW_CLASS_BUFFER_CHARS = 256
    private const val WINDOW_TITLE_BUFFER_CHARS = 128

    /** The User32 additions JNA's stock interface does not declare. */
    private interface ExtendedUser32 : User32 {
        fun VkKeyScanW(ch: Int): Short
        fun GetClassNameW(hwnd: HWND, buffer: CharArray, maxCount: Int): Int
        fun GetWindowTextW(hwnd: HWND, buffer: CharArray, maxCount: Int): Int
    }
}
