package com.winlator.alsaserver;

import android.media.AudioTrack;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Opt-in, content-free health counters for the Android ALSA endpoint.
 *
 * <p>The production constructor uses a disabled instance, so PCM buffers are
 * never scanned unless a caller explicitly supplies diagnostics. Snapshots
 * retain counters, hashes, cadence, and AudioTrack state only; no samples,
 * paths, or guest data are retained.</p>
 */
public final class ALSADiagnostics {
    public static final int SYNTHETIC_TONE_NONE = 0;
    public static final int SYNTHETIC_TONE_ACTIVE = 1;
    public static final int SYNTHETIC_TONE_UNSUPPORTED_FORMAT = 2;
    public static final int SYNTHETIC_TONE_PRIVATE_BUFFER_UNAVAILABLE = 3;
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;
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
        public final int bufferCapacityFrames;
        public final int trackBufferFrames;
        public final int startThresholdFrames;
        public final int performanceMode;
        public final long streamToken;
        public final long writeSequence;
        public final int writeLengthBytes;
        public final long writeCrc32;
        public final long consecutiveIdenticalWriteCount;
        public final long maxInterWriteGapNanos;
        public final long lastReportedMaxInterWriteGapNanos;
        public final long queueDepthFrames;
        public final long startCount;
        public final long lastStartNanos;
        public final long stopCount;
        public final long lastStopNanos;
        public final long pauseCount;
        public final long lastPauseNanos;
        public final long prepareCount;
        public final long lastPrepareNanos;
        public final int syntheticToneMode;

        private Snapshot(ALSADiagnostics source) {
            connectionsOpened = source.connectionsOpened.get();
            connectionsClosed = source.connectionsClosed.get();
            activeConnections = source.activeConnections.get();
            requestsReceived = source.requestsReceived.get();
            prepareAttempts = source.prepareAttempts.get();
            prepareRejected = source.prepareRejected.get();
            tracksCreated = source.tracksCreated.get();
            tracksReleased = source.tracksReleased.get();
            synchronized (source.residualLock) {
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
                bufferCapacityFrames = source.bufferCapacityFrames.get();
                trackBufferFrames = source.trackBufferFrames.get();
                startThresholdFrames = source.startThresholdFrames.get();
                performanceMode = source.performanceMode.get();
                queueDepthFrames = source.queueDepthFrames.get();
                streamToken = source.currentStreamToken;
                writeSequence = source.writeSequence;
                writeLengthBytes = source.writeLengthBytes;
                writeCrc32 = source.writeCrc32;
                consecutiveIdenticalWriteCount = source.consecutiveIdenticalWriteCount;
                maxInterWriteGapNanos = source.maxInterWriteGapNanos;
                lastReportedMaxInterWriteGapNanos = source.lastReportedMaxInterWriteGapNanos;
                startCount = source.startCount;
                lastStartNanos = source.lastStartNanos;
                stopCount = source.stopCount;
                lastStopNanos = source.lastStopNanos;
                pauseCount = source.pauseCount;
                lastPauseNanos = source.lastPauseNanos;
                prepareCount = source.prepareCount;
                lastPrepareNanos = source.lastPrepareNanos;
                syntheticToneMode = source.syntheticToneMode;
            }
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
    private final AtomicLong tokenSequence = new AtomicLong();
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
    private final AtomicInteger bufferCapacityFrames = new AtomicInteger();
    private final AtomicInteger trackBufferFrames = new AtomicInteger();
    private final AtomicInteger startThresholdFrames = new AtomicInteger();
    private final AtomicInteger performanceMode = new AtomicInteger(AudioTrack.PERFORMANCE_MODE_NONE);
    private final AtomicLong queueDepthFrames = new AtomicLong();
    private final Object residualLock = new Object();
    private long currentStreamToken;
    private long writeSequence;
    private int writeLengthBytes;
    private long writeCrc32;
    private long consecutiveIdenticalWriteCount;
    private long lastWriteNanos;
    private long maxInterWriteGapNanos;
    private long lastReportedMaxInterWriteGapNanos;
    /** Global limiter: deliberately survives stream claim and release. */
    private long lastLogNanos = Long.MIN_VALUE;
    private long startCount;
    private long lastStartNanos;
    private long stopCount;
    private long lastStopNanos;
    private long pauseCount;
    private long lastPauseNanos;
    private long prepareCount;
    private long lastPrepareNanos;
    private int syntheticToneMode;
    private boolean syntheticToneDecisionPending;
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
    }

    void onConnectionClosed(ALSAClient client) {
        if (!enabled) return;
        connectionsClosed.incrementAndGet();
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
    }

    void onRequest() {
        if (enabled) requestsReceived.incrementAndGet();
    }

    /** Claims a fresh current-stream identity and clears only per-stream data. */
    long claimStream(ALSAClient client) {
        if (!enabled) return 0;
        long token = tokenSequence.incrementAndGet();
        if (token == 0) token = tokenSequence.incrementAndGet();
        synchronized (residualLock) {
            currentStreamToken = token;
            clearCurrentStreamLocked(true);
            activeClient = new WeakReference<>(client);
        }
        return token;
    }

    void onPrepareAttempt(boolean accepted) {
        if (!enabled) return;
        prepareAttempts.incrementAndGet();
        if (!accepted) prepareRejected.incrementAndGet();
    }

    void onTrackCreated(long token) {
        if (!enabled) return;
        tracksCreated.incrementAndGet();
        synchronized (residualLock) {
            if (token == currentStreamToken) audioTrackPresent.set(true);
        }
    }

    void onTrackConfiguration(
        long token,
        int capacityFrames,
        int activeBufferFrames,
        int thresholdFrames,
        int mode
    ) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token != currentStreamToken) return;
            bufferCapacityFrames.set(Math.max(0, capacityFrames));
            trackBufferFrames.set(Math.max(0, activeBufferFrames));
            startThresholdFrames.set(Math.max(0, thresholdFrames));
            performanceMode.set(mode);
        }
    }

    /** Aggregate releases are counted, but only the owning token may reset state. */
    void onTrackReleased(long token, boolean trackExisted) {
        if (!enabled) return;
        if (trackExisted) tracksReleased.incrementAndGet();
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            clearCurrentStreamLocked(false);
            currentStreamToken = 0;
            activeClient = new WeakReference<>(null);
        }
    }

    private void clearCurrentStreamLocked(boolean clearControls) {
        audioTrackPresent.set(false);
        playState.set(AudioTrack.PLAYSTATE_STOPPED);
        playingObserved.set(false);
        pcmFramesReceived.set(0);
        nonZeroPcmFramesReceived.set(0);
        successfulFrameWrites.set(0);
        failedWriteCalls.set(0);
        bufferCapacityFrames.set(0);
        trackBufferFrames.set(0);
        startThresholdFrames.set(0);
        performanceMode.set(AudioTrack.PERFORMANCE_MODE_NONE);
        pointerFrames.set(0);
        playbackHeadFrames.set(0);
        maxPlaybackHeadFrames.set(0);
        queueDepthFrames.set(0);
        underrunCount.set(0);
        writeSequence = 0;
        writeLengthBytes = 0;
        writeCrc32 = 0;
        consecutiveIdenticalWriteCount = 0;
        lastWriteNanos = 0;
        maxInterWriteGapNanos = 0;
        lastReportedMaxInterWriteGapNanos = 0;
        syntheticToneMode = SYNTHETIC_TONE_NONE;
        syntheticToneDecisionPending = false;
        if (clearControls) {
            startCount = 0;
            lastStartNanos = 0;
            stopCount = 0;
            lastStopNanos = 0;
            pauseCount = 0;
            lastPauseNanos = 0;
            prepareCount = 0;
            lastPrepareNanos = 0;
        }
    }

    void onPcmReceived(long token, ByteBuffer data, int frameBytes) {
        if (!enabled) return;
        onPcmReceived(token, data, frameBytes, System.nanoTime());
    }

    void onPcmReceived(long token, ByteBuffer data, int frameBytes, long nowNanos) {
        if (!enabled || frameBytes <= 0) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
        }

        ByteBuffer view = data.duplicate();
        int limit = view.limit();
        long frames = limit / frameBytes;
        long nonZeroFrames = 0;
        boolean frameNonZero = false;
        CRC32 crc32 = new CRC32();
        for (int index = 0; index < limit; index++) {
            int value = Byte.toUnsignedInt(view.get(index));
            crc32.update(value);
            frameNonZero |= value != 0;
            if ((index + 1) % frameBytes == 0) {
                if (frameNonZero) nonZeroFrames++;
                frameNonZero = false;
            }
        }

        long checksum = crc32.getValue();
        synchronized (residualLock) {
            if (token != currentStreamToken) return;
            pcmFramesReceived.addAndGet(frames);
            nonZeroPcmFramesReceived.addAndGet(nonZeroFrames);
            long gap = lastWriteNanos == 0 || nowNanos < lastWriteNanos
                ? 0
                : nowNanos - lastWriteNanos;
            maxInterWriteGapNanos = Math.max(maxInterWriteGapNanos, gap);
            if (writeSequence > 0 && writeLengthBytes == limit && writeCrc32 == checksum) {
                consecutiveIdenticalWriteCount++;
            }
            else consecutiveIdenticalWriteCount = 0;
            writeSequence++;
            writeLengthBytes = limit;
            writeCrc32 = checksum;
            lastWriteNanos = nowNanos;
        }
    }

    void onControlRequest(long token, byte requestCode) {
        if (!enabled) return;
        onControlRequest(token, requestCode, System.nanoTime());
    }

    void onControlRequest(long token, byte requestCode, long nowNanos) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            switch (requestCode) {
                case RequestCodes.START:
                    startCount++;
                    lastStartNanos = nowNanos;
                    break;
                case RequestCodes.STOP:
                    stopCount++;
                    lastStopNanos = nowNanos;
                    break;
                case RequestCodes.PAUSE:
                    pauseCount++;
                    lastPauseNanos = nowNanos;
                    break;
                case RequestCodes.PREPARE:
                    prepareCount++;
                    lastPrepareNanos = nowNanos;
                    break;
                default:
                    break;
            }
        }
    }

    void onSyntheticToneDecision(long token, int mode) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            syntheticToneMode = mode;
            syntheticToneDecisionPending = mode != SYNTHETIC_TONE_NONE;
        }
    }

    void onWriteResult(long token, int bytesWritten, int frameBytes) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            if (bytesWritten > 0 && frameBytes > 0) {
                successfulFrameWrites.addAndGet(bytesWritten / frameBytes);
            }
            else failedWriteCalls.incrementAndGet();
        }
    }

    void onPointer(long token, long frames) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            long normalized = Math.max(0, frames) & 0xffff_ffffL;
            pointerFrames.set(normalized);
            updateQueueDepthLocked(normalized, playbackHeadFrames.get());
        }
    }

    void onTrackState(long token, int state, long headFrames, int underruns) {
        if (!enabled) return;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken) return;
            playState.set(state);
            if (state == AudioTrack.PLAYSTATE_PLAYING) playingObserved.set(true);
            long normalizedHead = Math.max(0, headFrames) & 0xffff_ffffL;
            playbackHeadFrames.set(normalizedHead);
            maxPlaybackHeadFrames.accumulateAndGet(normalizedHead, Math::max);
            underrunCount.set(Math.max(0, underruns));
            updateQueueDepthLocked(pointerFrames.get(), normalizedHead);
        }
    }

    private void updateQueueDepthLocked(long acceptedFrames, long headFrames) {
        queueDepthFrames.set((acceptedFrames - headFrames) & 0xffff_ffffL);
    }

    String maybeCreateWriteLogLine(long token) {
        if (!enabled) return null;
        return maybeCreateWriteLogLine(token, System.nanoTime());
    }

    String maybeCreateWriteLogLine(long token, long nowNanos) {
        if (!enabled) return null;
        synchronized (residualLock) {
            if (token == 0 || token != currentStreamToken || writeSequence == 0 ||
                (lastLogNanos != Long.MIN_VALUE && nowNanos - lastLogNanos < LOG_INTERVAL_NANOS)) {
                return null;
            }
            lastLogNanos = nowNanos;
            lastReportedMaxInterWriteGapNanos = maxInterWriteGapNanos;
            maxInterWriteGapNanos = 0;
            String modeSuffix = "";
            if (syntheticToneDecisionPending) {
                switch (syntheticToneMode) {
                    case SYNTHETIC_TONE_ACTIVE:
                        modeSuffix = " mode=synthetic-tone";
                        break;
                    case SYNTHETIC_TONE_UNSUPPORTED_FORMAT:
                        modeSuffix = " mode=guest-pcm synthetic-tone=inactive(reason=float-stereo-48k-required)";
                        break;
                    case SYNTHETIC_TONE_PRIVATE_BUFFER_UNAVAILABLE:
                        modeSuffix = " mode=guest-pcm synthetic-tone=inactive(reason=private-aux-unavailable)";
                        break;
                    default:
                        break;
                }
                syntheticToneDecisionPending = false;
            }
            return String.format(
                Locale.US,
                "seq=%d len=%d crc32=%08x repeat=%d gapMaxMs=%.3f accepted=%d head=%d " +
                    "queued=%d underruns=%d controls=prepare:%d@%dms,start:%d@%dms," +
                    "stop:%d@%dms,pause:%d@%dms%s",
                writeSequence,
                writeLengthBytes,
                writeCrc32,
                consecutiveIdenticalWriteCount,
                lastReportedMaxInterWriteGapNanos / 1_000_000.0d,
                pointerFrames.get(),
                playbackHeadFrames.get(),
                queueDepthFrames.get(),
                underrunCount.get(),
                prepareCount,
                lastPrepareNanos / 1_000_000L,
                startCount,
                lastStartNanos / 1_000_000L,
                stopCount,
                lastStopNanos / 1_000_000L,
                pauseCount,
                lastPauseNanos / 1_000_000L,
                modeSuffix
            );
        }
    }

    /** Samples the current AudioTrack before returning a coherent counter view. */
    public Snapshot snapshot() {
        ALSAClient client = activeClient.get();
        if (client != null) client.sampleTrackStateForDiagnostics();
        return new Snapshot(this);
    }
}
