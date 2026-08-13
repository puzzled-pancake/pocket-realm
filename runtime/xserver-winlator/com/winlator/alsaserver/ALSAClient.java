package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.winlator.math.Mathf;
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
    private int position;
    private int bufferSize;
    private byte frameBytes;
    private int previousUnderrunCount = 0;
    private int bufferCapacity;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    protected final Options options;
    private final ALSADiagnostics diagnostics;
    private static short framesPerBuffer = 256;

    public static class Options {
        // Inlined from the absent com.winlator.contentdialog.AudioDriverConfigDialog.
        public static final short DEFAULT_LATENCY_MILLIS = 100;
        public static final float DEFAULT_VOLUME = 1.0f;

        public short latencyMillis = DEFAULT_LATENCY_MILLIS;
        public byte performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        public float volume = DEFAULT_VOLUME;
    }

    public ALSAClient(Options options) {
        this(options, ALSADiagnostics.disabled());
    }

    ALSAClient(Options options, ALSADiagnostics diagnostics) {
        this.options = options;
        this.diagnostics = diagnostics != null ? diagnostics : ALSADiagnostics.disabled();
    }

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        AudioTrack track = audioTrack;
        audioTrack = null;
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
                track.release();
                diagnostics.onTrackReleased();
            }
        }
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

    public void prepare() {
        position = 0;
        previousUnderrunCount = 0;
        frameBytes = (byte)(channels * dataType.byteCount);
        release();

        if (!isValidBufferSize()) {
            diagnostics.onPrepareAttempt(false);
            return;
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
                .setBufferSizeInBytes(getBufferSizeInBytes())
                .build();
            diagnostics.onTrackCreated();

            bufferCapacity = audioTrack.getBufferCapacityInFrames();
            if (options.volume != 1.0f) audioTrack.setVolume(options.volume);
            audioTrack.play();
            diagnostics.onPrepareAttempt(true);
            sampleTrackStateForDiagnostics();
        }
        catch (RuntimeException error) {
            diagnostics.onPrepareAttempt(false);
            release();
        }
    }

    public void start() {
        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play();
        }
    }

    public void stop() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    public void pause() {
        if (audioTrack != null) audioTrack.pause();
    }

    public void drain() {
        if (audioTrack != null) audioTrack.flush();
    }

    public void writeDataToTrack(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        }
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }

        AudioTrack track = audioTrack;
        if (track != null) {
            int bytesWritten;
            data.position(0);
            diagnostics.onPcmReceived(data, Byte.toUnsignedInt(frameBytes));

            do {
                try {
                    bytesWritten = track.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING);
                    diagnostics.onWriteResult(bytesWritten, Byte.toUnsignedInt(frameBytes));
                    if (bytesWritten <= 0) break;
                    increaseBufferSizeIfUnderrunOccurs();
                }
                catch (Exception e) {
                    diagnostics.onWriteResult(-1, Byte.toUnsignedInt(frameBytes));
                    break;
                }
            }
            while (data.position() != data.limit());

            position += data.position();
            diagnostics.onPointer(pointer());
            sampleTrackStateForDiagnostics();
            data.rewind();
        }
    }

    private void increaseBufferSizeIfUnderrunOccurs() {
        AudioTrack track = audioTrack;
        if (track == null) return;
        int underrunCount = track.getUnderrunCount();
        if (underrunCount > previousUnderrunCount && bufferSize < bufferCapacity) {
            previousUnderrunCount = underrunCount;
            bufferSize += framesPerBuffer;
            track.setBufferSizeInFrames(bufferSize);
        }
    }

    public int pointer() {
        return audioTrack != null ? position / frameBytes : 0;
    }

    void noteRequestForDiagnostics() {
        diagnostics.onRequest();
    }

    void sampleTrackStateForDiagnostics() {
        if (!diagnostics.isEnabled()) return;
        AudioTrack track = audioTrack;
        if (track == null) return;
        try {
            diagnostics.onTrackState(
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

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannels(int channels) {
        this.channels = (byte)channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
        else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() {
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

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    public static int bufferSizeToLatencyMillis(int bufferSizeInBytes, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        float bufferSize = (float)bufferSizeInBytes / frameBytes;
        return (int)((bufferSize / sampleRate) * 1000);
    }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        int bufferSize = (int)Mathf.roundTo((latencyMillis * sampleRate) / 1000.0f, framesPerBuffer, false);
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return frameBytes > 0 && channels >= 1 && channels <= 2 &&
            sampleRate >= 8000 && sampleRate <= 48000 &&
            ((bufferSize % frameBytes) == 0) && bufferSize > 0;
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
