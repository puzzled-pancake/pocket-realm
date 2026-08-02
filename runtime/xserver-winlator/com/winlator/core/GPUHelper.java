/*
 * com.winlator.core.GPUHelper — trimmed from Winlator ca3d735 (LGPL-2.1).
 *
 * The upstream app-shell helpers remain omitted. The native EGL context marker
 * is required: Gladio shares its WineD3D context with the Android renderer so
 * the GLX window texture is visible to GLRenderer.
 */
package com.winlator.core;

public class GPUHelper {
    public static native void setGlobalEGLContext();
}
