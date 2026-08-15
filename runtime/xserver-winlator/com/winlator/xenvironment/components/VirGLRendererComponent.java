package com.winlator.xenvironment.components;

import android.opengl.GLES20;

import androidx.annotation.Keep;

import com.winlator.renderer.Texture;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VirGLRendererComponent implements ConnectionHandler, RequestHandler, AutoCloseable {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong successfulFlushes = new AtomicLong();
    private final Set<Long> initializedClients = ConcurrentHashMap.newKeySet();
    private final Set<Long> capsReadyClients = ConcurrentHashMap.newKeySet();

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        if (xServer == null || socketConfig == null) {
            throw new IllegalArgumentException("VirGL component arguments are incomplete");
        }
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(0);
        connector.setInitialOutputBufferCapacity(0);
        // Admission can be rejected from handleNewConnection (missing/stale
        // EGL generation or the one-client guard). A per-client poll thread
        // makes killConnection use XConnector's deferred self-cleanup path,
        // so native storage cannot be freed before the Java tag callback has
        // returned and published its result.
        connector.setMultithreadedClients(true);
        connector.start();
    }

    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
        activeConnections.set(0);
        successfulFlushes.set(0);
        initializedClients.clear();
        capsReadyClients.clear();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isStarted() {
        return connector != null;
    }

    public int getActiveConnectionCount() {
        return activeConnections.get();
    }

    public long getSuccessfulFlushCount() {
        return successfulFlushes.get();
    }

    public int getInitializedConnectionCount() {
        return initializedClients.size();
    }

    public int getCapsReadyConnectionCount() {
        return capsReadyClients.size();
    }

    /** Exact live-root probe used after the Android GLSurfaceView publishes its generation. */
    public boolean probeSurfaceGeneration(long surfaceGeneration) {
        return surfaceGeneration > 0 && probeSharedContext(surfaceGeneration);
    }

    private void killConnection(ConnectedClient client) {
        XConnectorEpoll current = connector;
        if (current != null) current.killConnection(client);
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        Object tag = client.getTag();
        client.setTag(null);
        long clientPtr = tag instanceof Long ? (Long)tag : 0L;
        if (clientPtr != 0) destroyClient(clientPtr);
        initializedClients.remove(clientPtr);
        capsReadyClients.remove(clientPtr);
        if (clientPtr != 0) activeConnections.updateAndGet(value -> Math.max(0, value - 1));
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        long surfaceGeneration = xServer.getRenderer().getSurfaceGeneration();
        if (surfaceGeneration <= 0) {
            killConnection(client);
            return;
        }
        long clientPtr = handleNewConnection(client.fd, surfaceGeneration);
        client.setTag(clientPtr);
        if (clientPtr != 0) {
            successfulFlushes.set(0);
            activeConnections.incrementAndGet();
        }
        else killConnection(client);
    }

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        Object tag = client.getTag();
        long clientPtr = tag instanceof Long ? (Long)tag : 0L;
        if (clientPtr == 0) return false;
        int milestones = handleRequest(clientPtr);
        if (milestones < 0) {
            killConnection(client);
            return false;
        }
        if ((milestones & 1) != 0) initializedClients.add(clientPtr);
        if ((milestones & 2) != 0) capsReadyClients.add(clientPtr);
        return true;
    }

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) return;

        synchronized (drawable.renderLock) {
            drawable.setData(null);
            Texture texture = drawable.getTexture();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            boolean validateContent = successfulFlushes.get() == 0;
            if (texture.copyFromReadBuffer(drawable.width, drawable.height, validateContent) &&
                    validateContent) {
                successfulFlushes.compareAndSet(0, 1);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) onDrawListener.run();
    }

    private native long handleNewConnection(int fd, long surfaceGeneration);

    private native int handleRequest(long clientPtr);

    private native boolean probeSharedContext(long surfaceGeneration);

    private native void destroyClient(long clientPtr);
}
