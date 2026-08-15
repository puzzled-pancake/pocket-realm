package com.winlator.xenvironment.components;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android side of Winlator's pinned Vortek Vulkan bridge.
 *
 * Pocket Realm exposes only the Android system Vulkan loader through this
 * component.  Packaged Turnip uses its own guest ICD and never enters this
 * server, which keeps the two selectable driver identities independent.
 */
public final class VortekRendererComponent implements ConnectionHandler, RequestHandler, AutoCloseable {
    private static final String TAG = "PocketVortek";
    private static final int REQUEST_CODE_CREATE_CONTEXT = 1;
    private static final int REQUEST_CODE_SEND_EXTRA_DATA = 2;
    private static final short DEFAULT_IMAGE_CACHE_SIZE = 256;
    private static final int DEFAULT_VK_MAX_VERSION = (1 << 22) | (3 << 12) | 128;
    private static final int MAX_EXTRA_DATA_SIZE = 64 * 1024 * 1024;
    private static final VortekContextRegistry LIVE_CONTEXTS =
            new VortekContextRegistry(VortekRendererComponent::destroyVkContext);

    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private final Options options;
    private final AtomicInteger connectedGuests = new AtomicInteger();
    private volatile XConnectorEpoll connector;
    private volatile boolean loaderReady;
    private volatile boolean acceptingContexts;
    private volatile boolean firstHardwareBuffer;
    private volatile boolean firstPresent;

    static {
        System.loadLibrary("vortekrenderer");
    }

    /** Fields are read by the source-matched native protocol via JNI names. */
    public static final class Options {
        public static final byte RESOURCE_MEMORY_TYPE_AUTO = 0;
        public static final byte RESOURCE_MEMORY_TYPE_OPAQUE_FD = 1;
        public static final byte RESOURCE_MEMORY_TYPE_DMA_BUF = 2;
        public static final byte RESOURCE_MEMORY_TYPE_AHARDWAREBUFFER = 3;

        public int vkMaxVersion = DEFAULT_VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = DEFAULT_IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = RESOURCE_MEMORY_TYPE_AUTO;
        public String[] exposedDeviceExtensions = null;
    }

    public VortekRendererComponent(
            Context context,
            XServer xServer,
            UnixSocketConfig socketConfig,
            Options options) {
        if (context == null || xServer == null || socketConfig == null || options == null) {
            throw new IllegalArgumentException("Vortek component arguments are incomplete");
        }
        this.xServer = xServer;
        this.socketConfig = socketConfig;
        this.options = options;
        loaderReady = initVulkanWrapper();
        if (!loaderReady) {
            throw new IllegalStateException("Android system Vulkan loader is unavailable");
        }
        Log.i(TAG, "milestone=SYSTEM_LOADER_OPEN");
    }

    public synchronized void start() {
        if (connector != null) return;
        if (!loaderReady) throw new IllegalStateException("Vortek component is closed");
        XConnectorEpoll value = new XConnectorEpoll(socketConfig, this, this);
        value.setInitialInputBufferCapacity(8);
        value.setInitialOutputBufferCapacity(0);
        acceptingContexts = true;
        try {
            value.start();
            connector = value;
        }
        catch (Throwable error) {
            acceptingContexts = false;
            value.destroy();
            throw error;
        }
        Log.i(TAG, "milestone=SOCKET_LISTENING path=" + socketConfig.path);
    }

    public void stop() {
        XConnectorEpoll value;
        synchronized (this) {
            acceptingContexts = false;
            value = connector;
            connector = null;
        }
        if (value != null) value.destroy();
        drainTrackedContexts("component-stop");
    }

    public synchronized boolean isReady() {
        return loaderReady && connector != null;
    }

    @Override
    public void close() {
        XConnectorEpoll value;
        synchronized (this) {
            acceptingContexts = false;
            loaderReady = false;
            value = connector;
            connector = null;
        }
        if (value != null) value.destroy();
        drainTrackedContexts("component-close");
    }

    /**
     * Reclaims contexts left by an interrupted prior display generation before
     * any ARM route (including Turnip) starts. Normal connector shutdown owns
     * the first attempt; this process-wide registry is the fail-safe owner.
     */
    public static int reclaimLeakedContexts() {
        return drainTrackedContexts("next-display-generation");
    }

    private int registerContext(long contextPtr) throws IOException {
        if (!acceptingContexts) {
            destroyVkContext(contextPtr);
            throw new IOException("Vortek component is closing");
        }
        try {
            return LIVE_CONTEXTS.register(contextPtr);
        }
        catch (IllegalStateException error) {
            throw new IOException("Duplicate Vortek context pointer", error);
        }
    }

    private static boolean destroyTrackedContext(long contextPtr) {
        return LIVE_CONTEXTS.destroy(contextPtr);
    }

    private static int drainTrackedContexts(String reason) {
        int count = LIVE_CONTEXTS.drain();
        if (count > 0) Log.w(TAG, "reclaimed Vortek contexts=" + count + " reason=" + reason);
        return count;
    }

    @Keep
    private int getWindowWidth(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getWidth() : 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getHeight() : 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null || window.getContent() == null) return 0;
        Drawable drawable = window.getContent();
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            if (!(texture instanceof GPUImage)) {
                if (texture != null) {
                    xServer.getRenderer().xServerView.queueEvent(texture::destroy);
                }
                drawable.setTexture(new GPUImage(
                        drawable, false, useHALPixelFormatBGRA8888));
            }
            long hardwareBuffer = ((GPUImage)drawable.getTexture()).getHardwareBufferPtr();
            if (hardwareBuffer != 0 && !firstHardwareBuffer) {
                firstHardwareBuffer = true;
                Log.i(TAG, "milestone=FIRST_HARDWARE_BUFFER windowId=" + windowId);
            }
            return hardwareBuffer;
        }
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null || window.getContent() == null) return;
        Drawable drawable = window.getContent();
        synchronized (drawable.renderLock) {
            drawable.forceUpdate();
        }
        if (!firstPresent) {
            firstPresent = true;
            Log.i(TAG, "milestone=FIRST_PRESENT windowId=" + windowId);
        }
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        final Object tag;
        synchronized (client) {
            tag = client.getTag();
            // Claim teardown before entering native code.  This prevents a
            // repeated shutdown callback from destroying the same pointer.
            client.setTag(null);
        }
        if (tag instanceof Long) {
            destroyTrackedContext((Long)tag);
        }
        int count = connectedGuests.updateAndGet(value -> Math.max(0, value - 1));
        Log.i(TAG, "milestone=GUEST_DISCONNECTED guests=" + count);
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        int count = connectedGuests.incrementAndGet();
        Log.i(TAG, "milestone=GUEST_CONNECTED guests=" + count);
    }

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        XInputStream input = client.getInputStream();
        if (input.available() < 8) return false;
        int requestCode = input.readInt();
        int requestLength = input.readInt();
        if (requestLength < 0 || requestLength > MAX_EXTRA_DATA_SIZE) {
            throw new IOException("Invalid Vortek request length");
        }

        if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
            if (requestLength != 0) throw new IOException("Invalid Vortek create request");
            synchronized (client) {
                if (client.getTag() != null) {
                    throw new IOException("Repeated Vortek context creation");
                }
            }
            long contextPtr = createVkContext(client.fd, options);
            if (contextPtr <= 0) throw new IOException("Failed to create Vortek context");

            synchronized (client) {
                if (client.getTag() != null) {
                    // The native context exists, but the connection no longer
                    // has an unclaimed slot.  Reclaim it and fail closed.
                    destroyVkContext(contextPtr);
                    throw new IOException("Vortek context ownership changed");
                }
                int count = registerContext(contextPtr);
                client.setTag(contextPtr);
                Log.i(TAG, "milestone=CONTEXT_CREATED contexts=" + count);
            }
        }
        else if (requestCode > Short.MAX_VALUE &&
                (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
            final Object tag;
            synchronized (client) {
                tag = client.getTag();
            }
            if (!(tag instanceof Long) ||
                    !handleExtraDataRequest((Long)tag, requestCode & 0xffff, requestLength)) {
                throw new IOException("Failed to handle Vortek extra-data request");
            }
        }
        else {
            throw new IOException("Unknown Vortek control request");
        }
        return true;
    }

    private native long createVkContext(int clientFd, Options options);
    private static native void destroyVkContext(long contextPtr);
    private native boolean initVulkanWrapper();
    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);
}
