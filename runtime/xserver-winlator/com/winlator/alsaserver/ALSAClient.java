package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Source-matched port of Winlator's ALSAClient (ca3d735 lineage). Speaks the
 * `android_aserver` PCM protocol paired with {@link ALSARequestHandler} over the
 * vendored {@code XConnectorEpoll}. Shared-memory mode is always on: the request
 * handler mmaps a memfd ({@link SysVSharedMemory#createMemoryFd}) and hands the fd
 * to the guest plugin via SCM_RIGHTS; PCM frames are copied out of the shm ring
 * and written to an {@link AudioTrack}.
 *
 * Adapted from the upstream source by inlining the two Winlator UI deps that are
 * not vendored into this project: {@code AudioDriverConfigDialog} (default
 * latency/volume) and {@code KeyValueSet} (the Options parser). Options are
 * constructed directly with defaults.
 */
public class ALSAClient {
    public static final boolean USE_SHARED_MEMORY = true;
    public static final byte BUFFER_OFFSET = 4;
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private volatile AudioTrack audioTrack = null;
    private byte channels = 2;
    private int sampleRate = 0;
    private final AcceptedCursor acceptedCursor = new AcceptedCursor();
    /** Immutable size negotiated with ALSA and used by the shared-memory ring. */
    private int negotiatedRingFrames;
    /** Independently tuned Android queue size. Never changes the guest ring. */
    private int trackBufferFrames;
    private byte frameBytes;
    private int bufferCapacity;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    protected final Options options;
    private final ALSADiagnostics diagnostics;
    private long diagnosticToken;
    private SyntheticTone syntheticTone;
    private boolean syntheticToneActive;
    private static short framesPerBuffer = 256;
    public static final int MAX_NEGOTIATED_RING_FRAMES = 48_000;
    static final int SYNTHETIC_TONE_SAMPLE_RATE = 48_000;
    static final double SYNTHETIC_TONE_FREQUENCY_HZ = 440.0d;
    static final float SYNTHETIC_TONE_AMPLITUDE = 0.04f;

    /** Thread-safe accepted-byte cursor with explicit stale-writer generations. */
    public static final class AcceptedCursor {
        private long generation;
        private long acceptedBytes;

        public synchronized long reset() {
            acceptedBytes = 0;
            return ++generation;
        }

        public synchronized long generation() {
            return generation;
        }

        public synchronized boolean accept(long expectedGeneration, int byteCount) {
            if (expectedGeneration != generation || byteCount <= 0 ||
                acceptedBytes > Long.MAX_VALUE - byteCount) {
                return false;
            }
            acceptedBytes += byteCount;
            return true;
        }

        public synchronized int pointerFrames(int bytesPerFrame) {
            if (bytesPerFrame <= 0) return 0;
            return cumulativePositionFrames(acceptedBytes / bytesPerFrame);
        }
    }

    /** Stateful, content-free diagnostic source; writes only into a private copy. */
    static final class SyntheticTone {
        private static final double TWO_PI = Math.PI * 2.0d;
        private static final double PHASE_INCREMENT =
            TWO_PI * SYNTHETIC_TONE_FREQUENCY_HZ / SYNTHETIC_TONE_SAMPLE_RATE;
        private double phase;

        void reset() {
            phase = 0.0d;
        }

        void fillFloatStereo(ByteBuffer buffer) {
            ByteBuffer output = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int frameCount = output.limit() / (Float.BYTES * 2);
            for (int frame = 0; frame < frameCount; frame++) {
                float sample = (float)(Math.sin(phase) * SYNTHETIC_TONE_AMPLITUDE);
                int offset = frame * Float.BYTES * 2;
                output.putFloat(offset, sample);
                output.putFloat(offset + Float.BYTES, sample);
                phase += PHASE_INCREMENT;
                if (phase >= TWO_PI) phase -= TWO_PI;
            }
        }
    }

    public static class Options {
        // Inlined from the absent com.winlator.contentdialog.AudioDriverConfigDialog.
        public static final short DEFAULT_LATENCY_MILLIS = 100;
        public static final float DEFAULT_VOLUME = 1.0f;

        /** Guest-visible ALSA ring. Keeping this at 100 ms avoids extra socket traffic. */
        public short latencyMillis = DEFAULT_LATENCY_MILLIS;
        // The RP6 routes POWER_SAVING (and flagless 100 ms media streams) to a
        // roughly 150-210 ms deep buffer, which is audibly perceived as
        // repeating/buffering in WoW. Keep the tolerant 100 ms guest queue, but
        // request Android's low-latency mixer so playback remains responsive.
        public byte performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        public float volume = DEFAULT_VOLUME;
        /** Temporary debug experiment. Production construction leaves this false. */
        public boolean syntheticTone = false;
    }

    public ALSAClient(Options options) {
        this(options, ALSADiagnostics.disabled());
    }

    ALSAClient(Options options, ALSADiagnostics diagnostics) {
        this.options = options;
        this.diagnostics = diagnostics != null ? diagnostics : ALSADiagnostics.disabled();
    }

    public synchronized void release() {
        long releasedDiagnosticToken = diagnosticToken;
        diagnosticToken = 0;
        syntheticToneActive = false;
        if (syntheticTone != null) syntheticTone.reset();
        syntheticTone = null;
        acceptedCursor.reset();
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }
        auxBuffer = null;

        AudioTrack track = audioTrack;
        audioTrack = null;
        trackBufferFrames = 0;
        bufferCapacity = 0;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
            }
            catch (IllegalStateException ignored) {
            }
            try {
                track.flush();
            }
            catch (IllegalStateException ignored) {
            }
            finally {
                try {
                    track.release();
                }
                finally {
                    diagnostics.onTrackReleased(releasedDiagnosticToken, true);
                }
            }
        }
        else diagnostics.onTrackReleased(releasedDiagnosticToken, false);
    }

    public static int getPCMEncoding(DataType dataType) {
        switch (dataType) {
            case U8:
                return AudioFormat.ENCODING_PCM_8BIT;
            case S16LE:
            case S16BE:
                return AudioFormat.ENCODING_PCM_16BIT;
            case FLOATLE:
            case FLOATBE:
                return AudioFormat.ENCODING_PCM_FLOAT;
            default:
                return AudioFormat.ENCODING_DEFAULT;
        }
    }

    public static int getChannelConfig(int channels) {
        return channels <= 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
    }

    public synchronized boolean prepare(
        int requestedChannels,
        DataType requestedDataType,
        int requestedSampleRate,
        int requestedRingFrames
    ) {
        int requestedFrameBytes = checkedFrameBytes(requestedChannels, requestedDataType);
        if (checkedBufferSizeInBytes(requestedRingFrames, requestedFrameBytes) < 0 ||
            !isSupportedSampleRate(requestedSampleRate)) {
            syntheticToneActive = false;
            if (syntheticTone != null) syntheticTone.reset();
            syntheticTone = null;
            diagnosticToken = diagnostics.claimStream(this);
            diagnostics.onControlRequest(diagnosticToken, RequestCodes.PREPARE);
            if (options.syntheticTone && diagnostics.isEnabled()) {
                diagnostics.onSyntheticToneDecision(
                    diagnosticToken,
                    ALSADiagnostics.SYNTHETIC_TONE_UNSUPPORTED_FORMAT
                );
            }
            diagnostics.onPrepareAttempt(false);
            return false;
        }

        release();
        diagnosticToken = diagnostics.claimStream(this);
        diagnostics.onControlRequest(diagnosticToken, RequestCodes.PREPARE);
        channels = (byte)requestedChannels;
        dataType = requestedDataType;
        sampleRate = requestedSampleRate;
        negotiatedRingFrames = requestedRingFrames;
        frameBytes = (byte)requestedFrameBytes;
        syntheticToneActive = shouldActivateSyntheticTone(
            options,
            diagnostics.isEnabled(),
            dataType,
            channels,
            sampleRate
        );
        syntheticTone = syntheticToneActive ? new SyntheticTone() : null;
        if (options.syntheticTone && diagnostics.isEnabled()) {
            diagnostics.onSyntheticToneDecision(
                diagnosticToken,
                syntheticToneActive
                    ? ALSADiagnostics.SYNTHETIC_TONE_ACTIVE
                    : ALSADiagnostics.SYNTHETIC_TONE_UNSUPPORTED_FORMAT
            );
        }

        try {
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(getPCMEncoding(dataType))
                .setSampleRate(sampleRate)
                .setChannelMask(getChannelConfig(channels))
                .build();
            audioTrack = new AudioTrack.Builder()
                .setPerformanceMode(options.performanceMode)
                .setAudioFormat(format)
                // Capacity retains the tolerant 100 ms negotiated ring. Live
                // RP6 evidence showed 888 underruns at a 48-56 ms active queue
                // versus only 3 over several minutes at the full 100 ms queue.
                .setBufferSizeInBytes(getBufferSizeInBytes())
                .build();
            diagnostics.onTrackCreated(diagnosticToken);

            bufferCapacity = audioTrack.getBufferCapacityInFrames();
            int requestedTrackBufferFrames = Math.min(bufferCapacity, negotiatedRingFrames);
            int actualTrackBufferFrames = audioTrack.setBufferSizeInFrames(requestedTrackBufferFrames);
            trackBufferFrames = actualTrackBufferFrames > 0
                ? actualTrackBufferFrames
                : audioTrack.getBufferSizeInFrames();
            if (trackBufferFrames <= 0 || trackBufferFrames > bufferCapacity)
                throw new IllegalStateException("Android returned an invalid AudioTrack buffer size");
            int startThresholdFrames = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? audioTrack.getStartThresholdInFrames()
                : bufferCapacity;
            diagnostics.onTrackConfiguration(
                diagnosticToken,
                bufferCapacity,
                trackBufferFrames,
                startThresholdFrames,
                audioTrack.getPerformanceMode()
            );
            if (options.volume != 1.0f) audioTrack.setVolume(options.volume);
            audioTrack.play();
            diagnostics.onPrepareAttempt(true);
            sampleTrackStateForDiagnostics();
            return true;
        }
        catch (RuntimeException error) {
            diagnostics.onPrepareAttempt(false);
            release();
            return false;
        }
    }

    public synchronized void start() {
        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play();
        }
    }

    public synchronized void stop() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
            acceptedCursor.reset();
            if (syntheticTone != null) syntheticTone.reset();
        }
    }

    public synchronized void pause() {
        if (audioTrack != null) audioTrack.pause();
    }

    public synchronized void drain() {
        if (audioTrack != null) audioTrack.flush();
    }

    public void writeDataToTrack(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        }
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }

        final AudioTrack track;
        final long generation;
        final long capturedDiagnosticToken;
        final int bytesPerFrame;
        synchronized (this) {
            track = audioTrack;
            generation = acceptedCursor.generation();
            capturedDiagnosticToken = diagnosticToken;
            bytesPerFrame = Byte.toUnsignedInt(frameBytes);
            if (track != null) {
                data.position(0);
                // Keep the bounded debug-only scan in the same lifecycle
                // critical section as track capture. A concurrent release can
                // therefore reset the stream fingerprint without a stale
                // writer repopulating it afterward.
                if (diagnostics.isEnabled()) {
                    diagnostics.onPcmReceived(capturedDiagnosticToken, data, bytesPerFrame);
                }
                if (syntheticToneActive) {
                    if (data == auxBuffer && syntheticTone != null) {
                        syntheticTone.fillFloatStereo(data);
                    }
                    else {
                        syntheticToneActive = false;
                        if (syntheticTone != null) syntheticTone.reset();
                        syntheticTone = null;
                        diagnostics.onSyntheticToneDecision(
                            capturedDiagnosticToken,
                            ALSADiagnostics.SYNTHETIC_TONE_PRIVATE_BUFFER_UNAVAILABLE
                        );
                    }
                }
            }
        }
        if (track != null) {
            int bytesWritten;

            // The pinned 1.12 provider sends PAUSE for both pause(true) and
            // pause(false). Resume the same initialized stream when PCM starts
            // flowing again; do not allocate a new track or polling thread.
            try {
                if (data.hasRemaining() && track.getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
                    track.play();
                }
            }
            catch (IllegalStateException ignored) {
                diagnostics.onWriteResult(capturedDiagnosticToken, -1, bytesPerFrame);
                data.rewind();
                return;
            }

            do {
                try {
                    bytesWritten = track.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten <= 0) {
                        diagnostics.onWriteResult(capturedDiagnosticToken, bytesWritten, bytesPerFrame);
                        break;
                    }
                    if (!recordSubmittedBytes(track, generation, bytesWritten)) break;
                    diagnostics.onWriteResult(capturedDiagnosticToken, bytesWritten, bytesPerFrame);
                }
                catch (Exception e) {
                    diagnostics.onWriteResult(capturedDiagnosticToken, -1, bytesPerFrame);
                    break;
                }
            }
            while (data.position() != data.limit());

            diagnostics.onPointer(capturedDiagnosticToken, Integer.toUnsignedLong(pointer()));
            sampleTrackStateForDiagnostics();
            String diagnosticLine = diagnostics.maybeCreateWriteLogLine(capturedDiagnosticToken);
            if (diagnosticLine != null) Log.d("PR/ALSA", diagnosticLine);
            data.rewind();
        }
    }

    public synchronized int pointer() {
        int bytesPerFrame = Byte.toUnsignedInt(frameBytes);
        return audioTrack != null
            ? acceptedCursor.pointerFrames(bytesPerFrame)
            : 0;
    }

    private synchronized boolean recordSubmittedBytes(
        AudioTrack expectedTrack,
        long expectedGeneration,
        int byteCount
    ) {
        if (audioTrack != expectedTrack || acceptedCursor.generation() != expectedGeneration) {
            return false;
        }
        return acceptedCursor.accept(expectedGeneration, byteCount);
    }

    /**
     * The pinned Winlator ioplug pair uses a cumulative uint32 frame counter,
     * not a ring-relative cursor. Keeping the source-matched wire behavior is
     * essential: modulo would alias every complete-ring advance to zero and
     * make ALSA lose playback progress even while AudioTrack remains full.
     */
    public static int cumulativePositionFrames(long totalFrames) {
        if (totalFrames < 0) return 0;
        return (int)(totalFrames & 0xffff_ffffL);
    }

    static boolean supportsSyntheticTone(DataType type, int channelCount, int rate) {
        return type == DataType.FLOATLE && channelCount == 2 && rate == SYNTHETIC_TONE_SAMPLE_RATE;
    }

    static boolean shouldActivateSyntheticTone(
        Options options,
        boolean diagnosticsEnabled,
        DataType type,
        int channelCount,
        int rate
    ) {
        return options != null && options.syntheticTone && diagnosticsEnabled &&
            supportsSyntheticTone(type, channelCount, rate);
    }

    void noteRequestForDiagnostics() {
        diagnostics.onRequest();
    }

    void noteControlForDiagnostics(byte requestCode) {
        final long token;
        synchronized (this) {
            token = diagnosticToken;
        }
        diagnostics.onControlRequest(token, requestCode);
    }

    void sampleTrackStateForDiagnostics() {
        if (!diagnostics.isEnabled()) return;
        final AudioTrack track;
        final long token;
        synchronized (this) {
            track = audioTrack;
            token = diagnosticToken;
        }
        if (track == null) return;
        try {
            diagnostics.onTrackState(
                token,
                track.getPlayState(),
                Integer.toUnsignedLong(track.getPlaybackHeadPosition()),
                track.getUnderrunCount()
            );
        }
        catch (IllegalStateException ignored) {
            // A concurrent connector shutdown owns release; the final release
            // counters still provide the authoritative terminal state.
        }
    }

    public synchronized ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public synchronized void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
        else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public synchronized ByteBuffer getAuxBuffer() {
        return auxBuffer;
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannels() {
        return channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public synchronized int getBufferSize() {
        return negotiatedRingFrames;
    }

    public synchronized int getBufferSizeInBytes() {
        return checkedBufferSizeInBytes(negotiatedRingFrames, Byte.toUnsignedInt(frameBytes));
    }

    public synchronized int getSharedMemorySizeInBytes() {
        int pcmBytes = getBufferSizeInBytes();
        return pcmBytes < 0 || pcmBytes > Integer.MAX_VALUE - BUFFER_OFFSET
            ? -1
            : pcmBytes + BUFFER_OFFSET;
    }

    public boolean isValidWriteLength(int byteCount) {
        int bytesPerFrame = Byte.toUnsignedInt(frameBytes);
        return bytesPerFrame > 0 && byteCount >= 0 &&
            byteCount <= getBufferSizeInBytes() && byteCount % bytesPerFrame == 0;
    }

    public static int bufferSizeToLatencyMillis(int bufferSizeInBytes, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        float bufferSize = (float)bufferSizeInBytes / frameBytes;
        return (int)((bufferSize / sampleRate) * 1000);
    }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        int bytesPerFrame = checkedFrameBytes(channels, dataType);
        int burst = Math.max(1, framesPerBuffer);
        if (latencyMillis <= 0 || bytesPerFrame <= 0 || !isSupportedSampleRate(sampleRate)) return -1;
        double exactFrames = ((double)latencyMillis * (double)sampleRate) / 1000.0d;
        long frames = Math.round(exactFrames / burst) * (long)burst;
        if (frames <= 0 || frames > Integer.MAX_VALUE) return -1;
        return checkedBufferSizeInBytes((int)frames, bytesPerFrame);
    }

    public static boolean isSupportedSampleRate(int value) {
        return value >= 8000 && value <= 48000;
    }

    public static int checkedFrameBytes(int channels, DataType dataType) {
        if (channels < 1 || channels > 2 || dataType == null) return -1;
        return channels * Byte.toUnsignedInt(dataType.byteCount);
    }

    public static int checkedBufferSizeInBytes(int frames, int bytesPerFrame) {
        if (frames <= 0 || frames > MAX_NEGOTIATED_RING_FRAMES || bytesPerFrame <= 0) return -1;
        long bytes = (long)frames * bytesPerFrame;
        return bytes > Integer.MAX_VALUE - BUFFER_OFFSET ? -1 : (int)bytes;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            String framesPerBufferStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            framesPerBuffer = Short.parseShort(framesPerBufferStr);
            if (framesPerBuffer == 0) framesPerBuffer = 256;
        }
        catch (Exception e) {
            framesPerBuffer = 256;
        }
    }
}
