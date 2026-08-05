package com.pocketrealm.client

import android.view.KeyEvent

/**
 * O14 increment-2 IME committed-text policy.
 *
 * Maps each supported printable character to the Android keycode + shift state
 * that the existing winlator [com.winlator.xserver.Keyboard] path can prove end
 * to end through the X keysym table and Wine's `WM_CHAR` translation. This is
 * NOT a transliteration layer: every supported character has a real keycode in
 * the winlator `createKeyboard` keysym table, and every unsupported character
 * is rejected visibly (counted in [ImeCommitResult.rejectedChars]) rather than
 * silently substituted.
 *
 * The supported set is bounded to US-layout ASCII characters that appear in the
 * winlator keysym table (min/max pairs at Keyboard.java:289-335). It covers all
 * characters needed for WoW 1.12.1 chat and account/text-field entry without a
 * physical keyboard. Characters outside this set (e.g. accented letters, CJK,
 * emoji) are rejected with their codepoints reported, so the caller can surface
 * the limitation honestly.
 *
 * Design rule (report §16.6/§16.8): do not map a character to a key the X path
 * cannot prove. If Wine's `TranslateMessage` does not produce the expected
 * `WM_CHAR` for a keysym, that character is not in the supported set.
 */
object ImeCharMap {

    /**
     * The bounded supported character set, documented for the user and reported
     * in evidence. Each entry maps a character to the Android keycode and
     * whether Shift must be held (matching the winlator keysym table's
     * min/maj pair).
     *
     * @property char the supported character
     * @property keyCode the Android keycode whose winlator XKeycode carries the
     *   character's keysym (either as the unshifted min or shifted maj keysym)
     * @property shift true if the character is the shifted (maj) keysym
     */
    data class Mapping(val char: Char, val keyCode: Int, val shift: Boolean)

    /** Bounded maximum commit length to prevent unbounded injection loops. */
    const val MAX_COMMIT_LENGTH: Int = 256

    /** The fixed public test phrase for evidence (never a user secret). */
    const val TEST_PHRASE: String = "Pocket Realm 123!?,.-_"

    /**
     * The full supported character set. Derived from the winlator keysym table
     * (Keyboard.java createKeyboard). Each character here has a verified XKeycode
     * → keysym → Wine WM_CHAR path. Characters NOT in this set are rejected.
     */
    private val MAPPINGS: Map<Char, Mapping> = buildMap {
        // a–z (unshifted keysyms 97-122)
        for (c in 'a'..'z') put(c, Mapping(c, KeyEvent.KEYCODE_A + (c - 'a'), shift = false))
        // A–Z (shifted keysyms 65-90)
        for (c in 'A'..'Z') put(c, Mapping(c, KeyEvent.KEYCODE_A + (c - 'A'), shift = true))
        // 0–9 (unshifted keysyms 48-57)
        for (c in '0'..'9') put(c, Mapping(c, KeyEvent.KEYCODE_0 + (c - '0'), shift = false))
        // Space
        put(' ', Mapping(' ', KeyEvent.KEYCODE_SPACE, shift = false))

        // Punctuation — each mapped from the winlator keysym table.
        // Unshifted symbols (min keysym):
        put('-', Mapping('-', KeyEvent.KEYCODE_MINUS, shift = false))      // keysym 45
        put('=', Mapping('=', KeyEvent.KEYCODE_EQUALS, shift = false))     // keysym 61
        put('[', Mapping('[', KeyEvent.KEYCODE_LEFT_BRACKET, shift = false))  // keysym 91
        put(']', Mapping(']', KeyEvent.KEYCODE_RIGHT_BRACKET, shift = false)) // keysym 93
        put('\\', Mapping('\\', KeyEvent.KEYCODE_BACKSLASH, shift = false))   // keysym 92
        put(';', Mapping(';', KeyEvent.KEYCODE_SEMICOLON, shift = false))     // keysym 59
        put('\'', Mapping('\'', KeyEvent.KEYCODE_APOSTROPHE, shift = false))  // keysym 39
        put(',', Mapping(',', KeyEvent.KEYCODE_COMMA, shift = false))        // keysym 44
        put('.', Mapping('.', KeyEvent.KEYCODE_PERIOD, shift = false))        // keysym 46
        put('/', Mapping('/', KeyEvent.KEYCODE_SLASH, shift = false))         // keysym 47
        put('`', Mapping('`', KeyEvent.KEYCODE_GRAVE, shift = false))         // keysym 96

        // Shifted symbols (maj keysym):
        put('!', Mapping('!', KeyEvent.KEYCODE_1, shift = true))    // keysym 33
        put('@', Mapping('@', KeyEvent.KEYCODE_2, shift = true))    // keysym 64
        put('#', Mapping('#', KeyEvent.KEYCODE_3, shift = true))    // keysym 35
        put('$', Mapping('$', KeyEvent.KEYCODE_4, shift = true))    // keysym 36
        put('%', Mapping('%', KeyEvent.KEYCODE_5, shift = true))    // keysym 37
        put('^', Mapping('^', KeyEvent.KEYCODE_6, shift = true))    // keysym 94
        put('&', Mapping('&', KeyEvent.KEYCODE_7, shift = true))    // keysym 38
        put('*', Mapping('*', KeyEvent.KEYCODE_8, shift = true))    // keysym 42
        put('(', Mapping('(', KeyEvent.KEYCODE_9, shift = true))    // keysym 40
        put(')', Mapping(')', KeyEvent.KEYCODE_0, shift = true))    // keysym 41
        put('_', Mapping('_', KeyEvent.KEYCODE_MINUS, shift = true))    // keysym 95
        put('+', Mapping('+', KeyEvent.KEYCODE_EQUALS, shift = true))    // keysym 43
        put('{', Mapping('{', KeyEvent.KEYCODE_LEFT_BRACKET, shift = true))  // keysym 123
        put('}', Mapping('}', KeyEvent.KEYCODE_RIGHT_BRACKET, shift = true)) // keysym 125
        put('|', Mapping('|', KeyEvent.KEYCODE_BACKSLASH, shift = true))     // keysym 124
        put(':', Mapping(':', KeyEvent.KEYCODE_SEMICOLON, shift = true))     // keysym 58
        put('"', Mapping('"', KeyEvent.KEYCODE_APOSTROPHE, shift = true))    // keysym 34
        put('<', Mapping('<', KeyEvent.KEYCODE_COMMA, shift = true))         // keysym 60
        put('>', Mapping('>', KeyEvent.KEYCODE_PERIOD, shift = true))        // keysym 62
        put('?', Mapping('?', KeyEvent.KEYCODE_SLASH, shift = true))         // keysym 63
        put('~', Mapping('~', KeyEvent.KEYCODE_GRAVE, shift = true))         // keysym 126
    }

    /** The sorted set of all supported characters (for evidence/diagnostics). */
    val supportedChars: Set<Char> = MAPPINGS.keys.toSortedSet()

    /** True if [char] is in the supported set. */
    fun isSupported(char: Char): Boolean = char in MAPPINGS

    /**
     * The result of mapping a commit string to keycodes. [accepted] is the
     * sequence of mappings for supported characters; [rejected] lists the
     * codepoints of characters that are outside the supported set (so the
     * caller can report them, never silently substitute).
     */
    data class ImeCommitResult(
        val accepted: List<Mapping>,
        val rejected: List<Int>,
    ) {
        /** True if every character was supported. */
        val allAccepted: Boolean get() = rejected.isEmpty()
        /** Number of accepted characters. */
        val acceptedCount: Int get() = accepted.size
    }

    /**
     * Map a committed-text string to the keycode+shift sequence. Characters
     * beyond [MAX_COMMIT_LENGTH] are truncated and counted as rejected.
     */
    fun map(text: String): ImeCommitResult {
        val accepted = mutableListOf<Mapping>()
        val rejected = mutableListOf<Int>()
        if (text.length > MAX_COMMIT_LENGTH) {
            // Truncate; report the overflow as rejected (no silent drop).
            rejected.add(MAX_COMMIT_LENGTH)
        }
        val limited = if (text.length > MAX_COMMIT_LENGTH) text.substring(0, MAX_COMMIT_LENGTH) else text
        for (c in limited) {
            val m = MAPPINGS[c]
            if (m != null) accepted.add(m) else rejected.add(c.code)
        }
        return ImeCommitResult(accepted, rejected)
    }

    /**
     * Build a DOWN+UP [KeyEvent] pair for a [Mapping]. The DOWN event carries
     * the Shift modifier when [Mapping.shift] is true; the UP event releases
     * it. This matches the verified `Keyboard.onKeyEvent` path exactly.
     */
    fun keyEvents(m: Mapping, now: Long): Pair<KeyEvent, KeyEvent> {
        val meta = if (m.shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, m.keyCode, 0, meta)
        val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, m.keyCode, 0, meta)
        return down to up
    }
}
