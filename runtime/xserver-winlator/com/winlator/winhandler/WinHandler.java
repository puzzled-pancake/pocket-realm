/*
 * com.winlator.winhandler.WinHandler — trimmed stub.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Upstream bridges the X
 * server to Wine's window management (focus/foreground/dynamic-resolution).
 * The X-server passes null for WinHandler (window create+map+GLES render does not
 * require it). This stub provides the type + the methods referenced by
 * InputDeviceManager/DesktopHelper so the vendored code compiles.
 * See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.winhandler;

public class WinHandler {
    /** No-op stub. Upstream forwards mouse events to Wine; input forwarding is
     *  not exercised here. */
    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) { }
    /** No-op stub. Upstream tells Wine to bring a window to the foreground. */
    public void bringToFront(String className, long handle) { }
}
