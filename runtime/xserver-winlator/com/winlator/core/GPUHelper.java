/*
 * com.winlator.core.GPUHelper — TRIMMED for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Only
 * `setGlobalEGLContext` (a native EGL marker) is referenced by the vendored
 * renderer (GLRenderer.java). The upstream SharedPreferences/app-shell bits are
 * dropped. The native method is a no-op stub here (Pocket Realm's spike does not
 * load libwinlator.so; EGL context globality is a VirGL/Vortek concern, not the
 * X11 display path). See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.core;

public class GPUHelper {
    /** No-op stub. Upstream declares this `native` (libwinlator.so); Pocket Realm
     *  does not build that native module, so the EGL-global-context marker has
     *  no effect on the X11/GDI display path exercised by S-3. */
    public static void setGlobalEGLContext() { }
}
