/*
 * com.winlator.XServerDisplayActivity — STUB for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). The upstream class is a
 * full Android Activity that hosts the X-server UI (touchpad, input controls,
 * debug dialog, fullscreen toggle). Pocket Realm runs the X-server from an
 * instrumented test, not an Activity, so this is a minimal stub providing only
 * what XServer.java requires: a host reference + getDebugDialog() returning a
 * DebugDialog. See docs/patches/wine-provider-provenance.md.
 */
package com.winlator;

import com.winlator.contentdialog.DebugDialog;

public class XServerDisplayActivity {
    private DebugDialog debugDialog = new DebugDialog();
    public DebugDialog getDebugDialog() { return debugDialog; }
}
