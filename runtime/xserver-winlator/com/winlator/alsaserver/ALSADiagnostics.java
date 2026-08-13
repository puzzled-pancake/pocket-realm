package com.winlator.alsaserver;

import android.media.AudioTrack;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in, content-free health counters for the Android ALSA endpoint.
 *
 * <p>The production constructor uses a disabled instance, so PCM buffers are
 * never scanned unless a caller explicitly supplies diagnostics.  Snapshots
 * retain counts and AudioTrack state only; no samples, paths, or guest data are
 * retained.</p>
 */
public final class ALSADiagnostics {
    private static final ALSADiagnostics DISABLED = new ALSADiagnostics(false);

    public static final class Snapshot {
        public final long connectionsOpened;
        public final long connectionsClosed;
        public final int activeConnections;
        public final long requestsReceived;
        public final long prepareAttempts;
        public final long prepareRejected;
        public final long tracksCreated;
        public final long tracksReleased;
        public final boolean audioTrackPresent;
        public final int playState;
        public final boolean playingObserved;
        public final long pcmFramesReceived;
        public final long nonZeroPcmFramesReceived;
        public final long successfulFrameWrites;
        public final long failedWriteCalls;
        public final long pointerFrames;
        public final long playbackHeadFrames;
        public final long maxPlaybackHeadFrames;
        public final int underrunCount;

        private Snapshot(ALSADiagnostics source) {
            connectionsOpened = source.connectionsOpened.get();
            connectionsClosed = source.connectionsClosed.get();
            activeConnections = source.activeConnections.get();
            requestsReceived = source.requestsReceived.get();
            prepareAttempts = source.prepareAttempts.get();
            prepareRejected = source.prepareRejected.get();
            tracksCreated = source.tracksCreated.get();
            tracksReleased = source.tracksReleased.get();
            audioTrackPresent = source.audioTrackPresent.get();
            playState = source.playState.get();
            playingObserved = source.playingObserved.get();
            pcmFramesReceived = source.pcmFramesReceived.get();
            nonZeroPcmFramesReceived = source.nonZeroPcmFramesReceived.get();
            successfulFrameWrites = source.successfulFrameWrites.get();
            failedWriteCalls = source.failedWriteCalls.get();
            pointerFrames = source.pointerFrames.get();
            playbackHeadFrames = source.playbackHeadFrames.get();
            maxPlaybackHeadFrames = source.maxPlaybackHeadFrames.get();
            underrunCount = source.underrunCount.get();
        }
    }

    private final boolean enabled;
    private final AtomicLong connectionsOpened = new AtomicLong();
    private final AtomicLong connectionsClosed = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong requestsReceived = new AtomicLong();
    private final AtomicLong prepareAttempts = new AtomicLong();
    private final AtomicLong prepareRejected = new AtomicLong();
    private final AtomicLong tracksCreated = new AtomicLong();
    private final AtomicLong tracksReleased = new AtomicLong();
    private final AtomicBoolean audioTrackPresent = new AtomicBoolean();
    private final AtomicInteger playState = new AtomicInteger(AudioTrack.PLAYSTATE_STOPPED);
    private final AtomicBoolean playingObserved = new AtomicBoolean();
    private final AtomicLong pcmFramesReceived = new AtomicLong();
    private final AtomicLong nonZeroPcmFramesReceived = new AtomicLong();
    private final AtomicLong successfulFrameWrites = new AtomicLong();
    private final AtomicLong failedWriteCalls = new AtomicLong();
    private final AtomicLong pointerFrames = new AtomicLong();
    private final AtomicLong playbackHeadFrames = new AtomicLong();
    private final AtomicLong maxPlaybackHeadFrames = new AtomicLong();
    private final AtomicInteger underrunCount = new AtomicInteger();
    private volatile WeakReference<ALSAClient> activeClient = new WeakReference<>(null);

    public ALSADiagnostics() {
        this(true);
    }

    private ALSADiagnostics(boolean enabled) {
        this.enabled = enabled;
    }

    static ALSADiagnostics disabled() {
        return DISABLED;
    }

    boolean isEnabled() {
        return enabled;
    }

    void onConnectionOpened(ALSAClient client) {
        if (!enabled) return;
        connectionsOpened.incrementAndGet();
        activeConnections.incrementAndGet();
        activeClient = new WeakReference<>(client);
    }

    void onConnectionClosed(ALSAClient client) {
        if (!enabled) return;
        connectionsClosed.incrementAndGet();
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        ALSAClient current = activeClient.get();
        if (current == client) activeClient = new WeakReference<>(null);
    }

    void onRequest() {
        if (enabled) requestsReceived.incrementAndGet();
    }

    void onPrepareAttempt(boolean accepted) {
        if (!enabled) return;
        prepareAttempts.incrementAndGet();
        if (!accepted) prepareRejected.incrementAndGet();
    }

    void onTrackCreated() {
        if (!enabled) return;
        tracksCreated.incrementAndGet();
        audioTrackPresent.set(true);
    }

    void onTrackReleased() {
        if (!enabled) return;
        tracksReleased.incrementAndGet();
        audioTrackPresent.set(false);
        playState.set(AudioTrack.PLAYSTATE_STOPPED);
    }

    void onPcmReceived(ByteBuffer data, int frameBytes) {
        if (!enabled || frameBytes <= 0) return;
        ByteBuffer view = data.duplicate();
        view.position(0);
        int limit = view.limit();
        long frames = limit / frameBytes;
        long nonZeroFrames = 0;
        for (int frame = 0; frame < frames; frame++) {
            boolean nonZero = false;
            int end = Math.min(limit, (frame + 1) * frameBytes);
            for (int index = frame * frameBytes; index < end; index++) {
                if (view.get(index) != 0) {
                    nonZero = true;
                    break;
                }
            }
            if (nonZero) nonZeroFrames++;
        }
        pcmFramesReceived.addAndGet(frames);
        nonZeroPcmFramesReceived.addAndGet(nonZeroFrames);
    }

    void onWriteResult(int bytesWritten, int frameBytes) {
        if (!enabled) return;
        if (bytesWritten > 0 && frameBytes > 0) {
            successfulFrameWrites.addAndGet(bytesWritten / frameBytes);
        }
        else failedWriteCalls.incrementAndGet();
    }

    void onPointer(long frames) {
        if (enabled) pointerFrames.set(Math.max(0, frames));
    }

    void onTrackState(int state, long headFrames, int underruns) {
        if (!enabled) return;
        playState.set(state);
        if (state == AudioTrack.PLAYSTATE_PLAYING) playingObserved.set(true);
        long normalizedHead = Math.max(0, headFrames);
        playbackHeadFrames.set(normalizedHead);
        maxPlaybackHeadFrames.accumulateAndGet(normalizedHead, Math::max);
        underrunCount.set(Math.max(0, underruns));
    }

    /** Samples the current AudioTrack before returning a coherent counter view. */
    public Snapshot snapshot() {
        ALSAClient client = activeClient.get();
        if (client != null) client.sampleTrackStateForDiagnostics();
        return new Snapshot(this);
    }
}
