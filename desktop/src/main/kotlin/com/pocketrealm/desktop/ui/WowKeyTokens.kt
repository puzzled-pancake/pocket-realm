package com.pocketrealm.desktop.ui

import com.pocketrealm.client.Vk

/**
 * DESK-4/DESK-86 support: the Windows virtual-key code → WTF
 * bindings-cache key-token mapping the bindings editor writes through the
 * shared BindingsFileCodec. Tokens mirror the stock 1.12.1 token space the
 * shared catalog's default-keys table uses (letters/digits/F1-12/arrow and
 * paging keys carry names; OEM punctuation carries the literal character —
 * the stock default table's "ALT--" row is the ground truth for the minus
 * token). Modifiers prefix in the stock order: CTRL- then SHIFT- then ALT-
 * (ground truth "CTRL-SHIFT-PAGEDOWN").
 *
 * Keys with no 1.12.1 token (Windows logo keys, IME keys, raw mouse) return
 * null — the editor refuses them honestly instead of writing a token the
 * client could never resolve.
 */
object WowKeyTokens {

    /** Modifiers that are chords, never a bindable key of their own. */
    val MODIFIER_VKS: Set<Int> = setOf(
        Vk.VK_SHIFT, Vk.VK_LSHIFT, Vk.VK_RSHIFT,
        Vk.VK_CONTROL, Vk.VK_LCONTROL, Vk.VK_RCONTROL,
        Vk.VK_MENU, Vk.VK_LMENU, Vk.VK_RMENU,
        Vk.VK_LWIN, Vk.VK_RWIN,
    )

    /** The WTF key token for one virtual-key code, or null when the client
     * has no such token. */
    fun tokenForVk(vk: Int): String? = when (vk) {
        Vk.VK_BACK -> "BACKSPACE"
        Vk.VK_TAB -> "TAB"
        Vk.VK_RETURN -> "ENTER"
        Vk.VK_PAUSE -> "PAUSE"
        Vk.VK_CAPITAL -> "CAPSLOCK"
        Vk.VK_ESCAPE -> "ESCAPE"
        Vk.VK_SPACE -> "SPACE"
        Vk.VK_PRIOR -> "PAGEUP"
        Vk.VK_NEXT -> "PAGEDOWN"
        Vk.VK_END -> "END"
        Vk.VK_HOME -> "HOME"
        Vk.VK_LEFT -> "LEFT"
        Vk.VK_UP -> "UP"
        Vk.VK_RIGHT -> "RIGHT"
        Vk.VK_DOWN -> "DOWN"
        Vk.VK_INSERT -> "INSERT"
        Vk.VK_DELETE -> "DELETE"
        in Vk.VK_0..Vk.VK_9 -> ('0' + (vk - Vk.VK_0)).toString()
        in Vk.VK_A..Vk.VK_Z -> ('A' + (vk - Vk.VK_A)).toString()
        in Vk.VK_NUMPAD0..Vk.VK_NUMPAD9 -> "NUMPAD" + (vk - Vk.VK_NUMPAD0)
        Vk.VK_MULTIPLY -> "NUMPADMULTIPLY"
        Vk.VK_ADD -> "NUMPADPLUS"
        Vk.VK_SUBTRACT -> "NUMPADMINUS"
        Vk.VK_DECIMAL -> "NUMPADDECIMAL"
        Vk.VK_DIVIDE -> "NUMPADDIVIDE"
        in Vk.VK_F1..Vk.VK_F12 -> "F" + (vk - Vk.VK_F1 + 1)
        Vk.VK_NUMLOCK -> "NUMLOCK"
        Vk.VK_SCROLL -> "SCROLLLOCK"
        Vk.VK_SNAPSHOT -> "PRINTSCREEN"
        // OEM punctuation binds as its literal character in 1.12.1.
        Vk.VK_OEM_1 -> ";"
        Vk.VK_OEM_PLUS -> "+"
        Vk.VK_OEM_COMMA -> ","
        Vk.VK_OEM_MINUS -> "-"
        Vk.VK_OEM_PERIOD -> "."
        Vk.VK_OEM_2 -> "/"
        Vk.VK_OEM_3 -> "`"
        Vk.VK_OEM_4 -> "["
        Vk.VK_OEM_5 -> "\\"
        Vk.VK_OEM_6 -> "]"
        Vk.VK_OEM_7 -> "'"
        else -> null
    }

    /** The full chord token ("CTRL-SHIFT-PAGEDOWN" shape), or null. */
    fun chordToken(ctrl: Boolean, shift: Boolean, alt: Boolean, vk: Int): String? {
        val base = tokenForVk(vk) ?: return null
        return buildString {
            if (ctrl) append("CTRL-")
            if (shift) append("SHIFT-")
            if (alt) append("ALT-")
            append(base)
        }
    }
}
