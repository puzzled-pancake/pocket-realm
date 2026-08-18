package com.winlator.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

public class Texture {
    protected int textureId = 0;
    protected int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    protected int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    protected int magFilter = GLES20.GL_LINEAR;
    protected int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    private int allocatedFormat;
    protected boolean needsUpdate = true;
    private boolean flipY = false;
    private short allocatedWidth;
    private short allocatedHeight;
    private short readbackWidth;
    private short readbackHeight;
    protected Drawable owner;
    private ByteBuffer readbackBuffer;
    /* Presentation readback runs on the Wine/Gladio GLES context.  Keep the
     * guest's pack state isolated from the tightly-packed Java buffer. */
    private final int[] savedPackState = new int[5];
    private final int[] savedUnpackState = new int[5];
    private static final AtomicInteger READBACK_DIAGNOSTICS = new AtomicInteger();

    private static final int[] PACK_STATE_PNAMES = new int[]{
        GLES30.GL_PACK_ALIGNMENT,
        GLES30.GL_PACK_ROW_LENGTH,
        GLES30.GL_PACK_SKIP_PIXELS,
        GLES30.GL_PACK_SKIP_ROWS,
        GLES30.GL_PIXEL_PACK_BUFFER_BINDING
    };

    private static final int[] UNPACK_STATE_PNAMES = new int[]{
        GLES30.GL_UNPACK_ALIGNMENT,
        GLES30.GL_UNPACK_ROW_LENGTH,
        GLES30.GL_UNPACK_SKIP_PIXELS,
        GLES30.GL_UNPACK_SKIP_ROWS,
        GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING
    };

    public Texture(Drawable owner) {
        this.owner = owner;
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

    public void allocateTexture(short width, short height, ByteBuffer data) {
        generateTextureId();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        saveUnpackState();
        try {
            setTightUnpackState();
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

            if (data != null) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
                allocatedFormat = format;
                allocatedWidth = width;
                allocatedHeight = height;
            }

            setTextureParameters();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        finally {
            restoreUnpackState();
        }
    }

    public Drawable getOwner() {
        return owner;
    }

    public void setOwner(Drawable owner) {
        this.owner = owner;
    }

    public boolean isFlipY() {
        return flipY;
    }

    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public void updateFromDrawable() {
        if (owner == null || owner.getData() == null) return;

        ByteBuffer data = owner.getData();
        short sourceWidth = data == readbackBuffer && readbackWidth > 0 ? readbackWidth : owner.width;
        short sourceHeight = data == readbackBuffer && readbackHeight > 0 ? readbackHeight : owner.height;
        if (isAllocated() && (allocatedFormat != format || allocatedWidth != sourceWidth ||
                allocatedHeight != sourceHeight)) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(1, textureIds, 0);
            textureId = 0;
            allocatedFormat = 0;
            allocatedWidth = 0;
            allocatedHeight = 0;
        }
        if (!isAllocated()) {
            allocateTexture(sourceWidth, sourceHeight, data);
            needsUpdate = false;
        }
        else if (needsUpdate) {
            saveUnpackState();
            try {
                setTightUnpackState();
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, sourceWidth, sourceHeight, format, GLES20.GL_UNSIGNED_BYTE, data);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                needsUpdate = false;
            }
            finally {
                restoreUnpackState();
            }
        }
    }

    private void saveUnpackState() {
        for (int i = 0; i < UNPACK_STATE_PNAMES.length; i++) {
            GLES30.glGetIntegerv(UNPACK_STATE_PNAMES[i], savedUnpackState, i);
        }
    }

    private void setTightUnpackState() {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
    }

    private void restoreUnpackState() {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, savedUnpackState[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, savedUnpackState[1]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, savedUnpackState[2]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, savedUnpackState[3]);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, savedUnpackState[4]);
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    public boolean copyFromReadBuffer(short width, short height, boolean validateContent) {
        Drawable currentOwner = owner;
        if (currentOwner == null || width <= 0 || height <= 0) return false;
        long requestedBytes = (long)width * height * 4L;
        if (requestedBytes > Integer.MAX_VALUE) return false;
        int byteCount = (int)requestedBytes;
        if (readbackBuffer == null || readbackBuffer.capacity() != byteCount) {
            readbackBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
        }
        readbackBuffer.clear();
        for (int i = 0; i < PACK_STATE_PNAMES.length; i++) {
            GLES30.glGetIntegerv(PACK_STATE_PNAMES[i], savedPackState, i);
        }
        try {
            /* The presentation buffer is always a tightly packed RGBA image;
             * GL_PACK_ROW_LENGTH/SKIP_* and a guest PBO must not reinterpret
             * the Java ByteBuffer or make the readback write elsewhere. */
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readbackBuffer);
        }
        finally {
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, savedPackState[0]);
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, savedPackState[1]);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, savedPackState[2]);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, savedPackState[3]);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, savedPackState[4]);
        }
        readbackBuffer.rewind();

        // Keep the first few native readbacks observable while qualifying the
        // ARM OpenGL lane.  This distinguishes bad Wine/Gladio pixels from an
        // Android-side texture upload/compositor problem without retaining
        // frame data or logging any client text.
        if (READBACK_DIAGNOSTICS.getAndIncrement() < 3) {
            int pixels = width * height;
            int step = Math.max(1, pixels / 4096);
            int sampled = 0;
            int nonBinary = 0;
            int nonBlack = 0;
            for (int pixel = 0; pixel < pixels; pixel += step) {
                int offset = pixel * 4;
                int red = readbackBuffer.get(offset) & 0xff;
                int green = readbackBuffer.get(offset + 1) & 0xff;
                int blue = readbackBuffer.get(offset + 2) & 0xff;
                if (red != 0 || green != 0 || blue != 0) nonBlack++;
                if ((red != 0 && red != 255) || (green != 0 && green != 255) ||
                    (blue != 0 && blue != 255)) nonBinary++;
                sampled++;
            }
            Log.i("PR/Texture", "readback=" + width + "x" + height +
                " samples=" + sampled + " nonBlack=" + nonBlack +
                " nonBinary=" + nonBinary);
        }

        boolean hasContent = !validateContent;
        if (validateContent) {
            int pixelCount = width * height;
            int pixelStep = Math.max(1, pixelCount / 4096);
            int visibleSamples = 0;
            for (int pixel = 0; pixel < pixelCount && visibleSamples < 16; pixel += pixelStep) {
                int offset = pixel * 4;
                if ((readbackBuffer.get(offset) & 0xff) != 0 ||
                    (readbackBuffer.get(offset + 1) & 0xff) != 0 ||
                    (readbackBuffer.get(offset + 2) & 0xff) != 0) {
                    visibleSamples++;
                }
            }
            hasContent = visibleSamples >= 16;
        }

        /*
         * This method runs on Wine's GLX request thread. Uploading here makes
         * textureId belong to Wine's EGL context and therefore relies on the
         * Android GLSurfaceView context sharing that object for its entire
         * lifecycle. Surface recreation can replace that context while Wine
         * continues rendering, leaving a valid CPU readback but a black Java
         * renderer. Keep the frame in the drawable's locked CPU buffer and let
         * updateFromDrawable() allocate/update the texture on the Android GL
         * thread instead. The full-frame readback already existed, so this
         * changes ownership rather than adding another readback.
         */
        format = GLES20.GL_RGBA;
        readbackWidth = width;
        readbackHeight = height;
        currentOwner.setData(readbackBuffer);
        needsUpdate = true;
        return hasContent;
    }

    public void destroy() {
        if (textureId > 0) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            textureId = 0;
            allocatedFormat = 0;
            allocatedWidth = 0;
            allocatedHeight = 0;
        }
    }
}
