/*
 * com.winlator.core.ImageUtils — TRIMMED for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). Only `getScaledSize` is
 * referenced by the vendored renderer (GLRenderer.java). The upstream
 * bitmap-decode helpers are dropped. See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.core;

public abstract class ImageUtils {
    public static int[] getScaledSize(float oldWidth, float oldHeight, float newWidth, float newHeight) {
        if (newWidth > 0 && newHeight == 0) {
            newHeight = (newWidth / oldWidth) * oldHeight;
            newWidth = (newHeight / oldHeight) * oldWidth;
        } else if (newWidth == 0 && newHeight > 0) {
            newWidth = (newHeight / oldHeight) * oldWidth;
            newHeight = (newWidth / oldWidth) * oldHeight;
        }
        return new int[]{(int) newWidth, (int) newHeight};
    }
}
