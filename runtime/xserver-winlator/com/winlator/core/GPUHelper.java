/*
 * com.winlator.core.GPUHelper — trimmed from Winlator ca3d735 (LGPL-2.1).
 *
 * The upstream app-shell helpers remain omitted. The native EGL context marker
 * is required: Gladio shares its WineD3D context with the Android renderer so
 * the GLX window texture is visible to GLRenderer.
 */
package com.winlator.core;

public class GPUHelper {
    public static final class SystemVulkanProbe {
        public final int apiVersion;
        public final boolean nativeTextureCompressionBC;
        public final String[] deviceExtensions;

        private SystemVulkanProbe(int apiVersion, boolean nativeTextureCompressionBC,
                                  String[] deviceExtensions) {
            this.apiVersion = apiVersion;
            this.nativeTextureCompressionBC = nativeTextureCompressionBC;
            this.deviceExtensions = deviceExtensions.clone();
        }
    }

    static {
        System.loadLibrary("winlator");
    }

    private static native SystemVulkanProbe nativeProbeSystemVulkan();

    /** Legacy API-only caller; sourced from the same checked one-device snapshot. */
    public static int vkGetApiVersion() {
        return probeSystemVulkan().apiVersion;
    }

    /** Checked, vendor-neutral host capability snapshot. */
    public static SystemVulkanProbe probeSystemVulkan() {
        SystemVulkanProbe probe = nativeProbeSystemVulkan();
        if (probe == null || probe.apiVersion <= 0 || probe.deviceExtensions == null) {
            throw new IllegalStateException("Android system Vulkan device is unavailable");
        }
        return probe;
    }

    /** Register the caller's current EGL context for one live surface generation. */
    public static native boolean setGlobalEGLContext(long generation);

    /** Clear the global share context only when the same surface generation owns it. */
    public static native void clearGlobalEGLContext(long generation);
}
