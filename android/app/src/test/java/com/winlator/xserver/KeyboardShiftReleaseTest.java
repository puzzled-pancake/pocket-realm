package com.winlator.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * Shift must stay held across an unrelated key's release when another source
 * (physical keyboard key or an injected hold from the input contract) owns
 * it. The legacy ACTION_UP path injected an unconditional Shift release, so
 * only the first tap of a modifier-held chord saw the Shift mask.
 */
public final class KeyboardShiftReleaseTest {
    /**
     * Records injections and loops them back through setKeyPress/setKeyRelease
     * exactly like XServer.injectKeyPress/injectKeyRelease do, so the
     * pressed-key state a production run maintains is observable here.
     */
    private static final class RecordingKeyboard extends Keyboard {
        final List<String> events = new ArrayList<>();

        RecordingKeyboard() {
            super(null);
        }

        @Override
        protected void injectKeyPress(XKeycode xKeycode, int keysym) {
            events.add("press " + xKeycode);
            setKeyPress(xKeycode.id, keysym);
        }

        @Override
        protected void injectKeyRelease(XKeycode xKeycode) {
            events.add("release " + xKeycode);
            setKeyRelease(xKeycode.id);
        }
    }

    @Test
    public void shiftedKeyPressSynthesizesAndReleasesShift() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, '2', true));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2, '2', true));

        assertEquals(
            "press " + XKeycode.KEY_SHIFT_L + "\n" +
                "press " + XKeycode.KEY_2 + "\n" +
                "release " + XKeycode.KEY_SHIFT_L + "\n" +
                "release " + XKeycode.KEY_2,
            String.join("\n", keyboard.events));
    }

    @Test
    public void foreignHeldShiftSurvivesUnrelatedKeyReleases() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        // Shift held by its own key event (a physical key or an injected hold).
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, false));
        // Repeated digit taps while the Shift hold continues.
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, '1', false));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, '1', false));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, '2', false));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2, '2', false));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, false));

        assertEquals(
            "press " + XKeycode.KEY_SHIFT_L + "\n" +
                "press " + XKeycode.KEY_1 + "\n" +
                "release " + XKeycode.KEY_1 + "\n" +
                "press " + XKeycode.KEY_2 + "\n" +
                "release " + XKeycode.KEY_2 + "\n" +
                "release " + XKeycode.KEY_SHIFT_L,
            String.join("\n", keyboard.events));
    }

    @Test
    public void shiftedPressEventDoesNotStealForeignShiftRelease() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, false));
        // Android reports isShiftPressed() on events typed while the physical
        // Shift key is down; that must not transfer release ownership.
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 'A', true));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 'A', true));
        assertTrue(keyboard.onKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, false));

        assertEquals(
            "press " + XKeycode.KEY_SHIFT_L + "\n" +
                "press " + XKeycode.KEY_A + "\n" +
                "release " + XKeycode.KEY_A + "\n" +
                "release " + XKeycode.KEY_SHIFT_L,
            String.join("\n", keyboard.events));
    }
}
