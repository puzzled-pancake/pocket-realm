/*
 * com.winlator.inputcontrols.ExternalController — STUB for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Upstream detects game
 * controllers for input routing. Keyboard.java calls isGameController() to
 * skip gamepad input; the spike stubs it to false (no gamepad input).
 * See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.inputcontrols;

import android.view.InputDevice;

public class ExternalController {
    /** Returns false (input stubbed). Upstream checks for game-controller sources. */
    public static boolean isGameController(InputDevice device) { return false; }
}
