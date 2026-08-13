package com.winlator.xenvironment.components;

import android.content.Context;

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
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.io.IOException;

/**
 * Android side of Winlator's pinned Vortek Vulkan bridge.
 *
 * Pocket Realm exposes only the Android system Vulkan loader through this
 * component.  Packaged Turnip uses its own guest ICD and never enters this
 * server, which keeps the two selectable driver identities independent.
 */
public final class VortekRendererComponent implements ConnectionHandler, RequestHandler, AutoCloseable {
    private static final int REQUEST_CODE_CREATE_CONTEXT = 1;
    private static final int REQUEST_CODE_SEND_EXTRA_DATA = 2;
    private static final short DEFAULT_IMAGE_CACHE_SIZE = 256;
    private static final int DEFAULT_VK_MAX_VERSION = (1 << 22) | (3 << 12) | 128;
    private static final int MAX_EXTRA_DATA_SIZE = 64 * 1024 * 1024;

    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private final Options options;
    private final WindowAuthorityBindings windowAuthority =
            new WindowAuthorityBindings();
    private XConnectorEpoll connector;
    private volatile boolean ready;

    static {
        System.loadLibrary("vortekrenderer");
    }

    /** Fields are read by the source-matched native protocol via JNI names. */
    public static final class Options {
        public int vkMaxVersion = DEFAULT_VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = DEFAULT_IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = 0;
        /** Native refuses contexts unless the audited System-only safe lane is explicit. */
        public boolean hardenedSafeLane = true;
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
        if (!initVulkanWrapper()) {
            throw new IllegalStateException("Android system Vulkan loader is unavailable");
        }
        ready = true;
    }

    public synchronized void start() {
        if (connector != null) return;
        if (!ready) throw new IllegalStateException("Vortek component is closed");
        XConnectorEpoll value = new XConnectorEpoll(socketConfig, this, this);
        value.setInitialInputBufferCapacity(8);
        value.setInitialOutputBufferCapacity(0);
        value.start();
        connector = value;
    }

    public synchronized void stop() {
        XConnectorEpoll value = connector;
        connector = null;
        if (value != null) value.destroy();
    }

    public synchronized boolean isReady() {
        return ready && connector != null;
    }

    @Override
    public synchronized void close() {
        stop();
        ready = false;
        windowAuthority.close();
    }

    @Keep
    private boolean registerWindowAuthorityGeneration(long generation) {
        return ready && windowAuthority.registerGeneration(generation);
    }

    @Keep
    private boolean unregisterWindowAuthorityGeneration(long generation) {
        return windowAuthority.unregisterGeneration(generation);
    }

    @Keep
    private boolean releaseWindowAuthorityInstance(long generation, long instanceToken) {
        return windowAuthority.releaseInstance(generation, instanceToken);
    }

    @Keep
    private long validateWindowAuthority(long generation, long instanceToken, int windowId) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = findEligibleWindowLocked(windowId);
            return bindWindowAuthorityLocked(generation, instanceToken, window);
        }
    }

    @Keep
    private long getWindowExtentAuthority(long generation, long instanceToken, int windowId) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = findEligibleWindowLocked(windowId);
            if (bindWindowAuthorityLocked(generation, instanceToken, window) == 0L) return 0L;
            return ((long)window.getWidth() << 32) |
                    ((long)window.getHeight() & 0xffffffffL);
        }
    }

    @Keep
    private long getWindowHardwareBufferAuthority(
            long generation,
            long instanceToken,
            int windowId,
            boolean useHALPixelFormatBGRA8888) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = findEligibleWindowLocked(windowId);
            long lifetime = bindWindowAuthorityLocked(generation, instanceToken, window);
            if (lifetime == 0L) return 0L;
            Drawable drawable = window.getContent();
            synchronized (drawable.renderLock) {
                if (!isSameEligibleWindowLocked(windowId, window, lifetime) ||
                        bindWindowAuthorityLocked(generation, instanceToken, window) == 0L) {
                    return 0L;
                }
                Texture texture = drawable.getTexture();
                if (!(texture instanceof GPUImage)) {
                    if (texture != null) {
                        xServer.getRenderer().xServerView.queueEvent(texture::destroy);
                    }
                    drawable.setTexture(new GPUImage(
                            drawable, false, useHALPixelFormatBGRA8888));
                }
                return ((GPUImage)drawable.getTexture()).acquireHardwareBufferPtr();
            }
        }
    }

    @Keep
    private boolean updateWindowContentAuthority(
            long generation, long instanceToken, int windowId) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = findEligibleWindowLocked(windowId);
            long lifetime = bindWindowAuthorityLocked(generation, instanceToken, window);
            if (lifetime == 0L) return false;
            Drawable drawable = window.getContent();
            synchronized (drawable.renderLock) {
                if (!isSameEligibleWindowLocked(windowId, window, lifetime) ||
                        bindWindowAuthorityLocked(generation, instanceToken, window) == 0L ||
                        drawable.getTexture() == null ||
                        drawable.getTexture().getOwner() == null) {
                    return false;
                }
                drawable.forceUpdate();
                return true;
            }
        }
    }

    /** Must be called with WINDOW_MANAGER held. */
    private Window findEligibleWindowLocked(int windowId) {
        if (windowId <= 0) return null;
        Window window = xServer.windowManager.getWindow(windowId);
        if (!WindowAuthorityEligibility.isEligible(
                window != null,
                window == xServer.windowManager.rootWindow,
                window != null && window.getMapState() == Window.MapState.VIEWABLE,
                window != null && window.originClient != null,
                window != null && window.isInputOutput(),
                window != null && window.getContent() != null,
                window != null ? window.getWidth() : 0,
                window != null ? window.getHeight() : 0,
                window != null ? window.authorityLifetime : 0L)) {
            return null;
        }
        return window;
    }

    /** Must be called with WINDOW_MANAGER and then the Drawable render lock. */
    private boolean isSameEligibleWindowLocked(
            int windowId, Window expected, long expectedLifetime) {
        Window current = findEligibleWindowLocked(windowId);
        return current == expected && current != null &&
                current.authorityLifetime == expectedLifetime &&
                current.getContent() == expected.getContent();
    }

    /** Lock order is WINDOW_MANAGER -> component authority map. */
    private long bindWindowAuthorityLocked(
            long generation, long instanceToken, Window window) {
        if (generation == 0L || instanceToken == 0L || window == null) return 0L;
        return ready ? windowAuthority.bind(generation, instanceToken,
                window.id, window.authorityLifetime) : 0L;
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
        if (tag instanceof Long) destroyVkContext((Long)tag);
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {}

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
                client.setTag(contextPtr);
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
    private native void destroyVkContext(long contextPtr);
    private native boolean initVulkanWrapper();
    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);
}
