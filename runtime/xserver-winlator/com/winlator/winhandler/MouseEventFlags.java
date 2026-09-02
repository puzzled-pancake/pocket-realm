/*
 * com.winlator.winhandler.MouseEventFlags — trimmed stub.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Upstream encodes mouse
 * button flags for WinHandler forwarding. Input is stubbed, so the flag
 * builder returns 0. The signature matches upstream (Pointer.Button, boolean).
 * See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.winhandler;

import com.winlator.xserver.Pointer;

public abstract class MouseEventFlags {
    /** Returns 0 (input stubbed). Upstream computes a button-state bitmask. */
    public static int getFlagFor(Pointer.Button button, boolean pressed) { return 0; }
}
