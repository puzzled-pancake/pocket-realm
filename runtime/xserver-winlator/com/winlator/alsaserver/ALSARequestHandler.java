package com.winlator.alsaserver;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        ALSAClient alsaClient = (ALSAClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (inputStream.available() < 5) return false;
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();
        if (requestLength < 0) throw new IOException("Invalid negative ALSA request length");

        switch (requestCode) {
            case RequestCodes.CLOSE:
                requireEmpty(requestLength);
                alsaClient.release();
                break;
            case RequestCodes.START:
                requireEmpty(requestLength);
                alsaClient.noteControlForDiagnostics(requestCode);
                alsaClient.start();
                break;
            case RequestCodes.STOP:
                requireEmpty(requestLength);
                alsaClient.noteControlForDiagnostics(requestCode);
                alsaClient.stop();
                break;
            case RequestCodes.PAUSE:
                requireEmpty(requestLength);
                alsaClient.noteControlForDiagnostics(requestCode);
                alsaClient.pause();
                break;
            case RequestCodes.PREPARE:
                if (inputStream.available() < requestLength) return false;

                if (requestLength != 10) throw new IOException("Invalid ALSA PREPARE length");
                int prepareChannels = inputStream.readUnsignedByte();
                int dataTypeOrdinal = inputStream.readUnsignedByte();
                if (prepareChannels < 1 || prepareChannels > 2 ||
                    dataTypeOrdinal >= ALSAClient.DataType.values().length) {
                    throw new IOException("Invalid ALSA PREPARE format");
                }

                int prepareSampleRate = inputStream.readInt();
                int prepareRingFrames = inputStream.readInt();
                if (!alsaClient.prepare(
                    prepareChannels,
                    ALSAClient.DataType.values()[dataTypeOrdinal],
                    prepareSampleRate,
                    prepareRingFrames
                )) {
                    throw new IOException("ALSA PREPARE could not create a valid audio stream");
                }

                if (ALSAClient.USE_SHARED_MEMORY) {
                    try {
                        createSharedMemory(alsaClient, outputStream);
                    }
                    catch (IOException | RuntimeException error) {
                        alsaClient.release();
                        throw error;
                    }
                }
                break;
            case RequestCodes.WRITE:
                if (!alsaClient.isValidWriteLength(requestLength)) {
                    throw new IOException("ALSA WRITE is outside the negotiated frame ring");
                }
                ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
                if (sharedBuffer != null) {
                    if (requestLength > sharedBuffer.capacity() - ALSAClient.BUFFER_OFFSET ||
                        requestLength > alsaClient.getAuxBuffer().capacity()) {
                        throw new IOException("ALSA WRITE exceeds allocated shared memory");
                    }
                    copySharedBuffer(alsaClient, requestLength, outputStream);
                    alsaClient.writeDataToTrack(alsaClient.getAuxBuffer());
                    sharedBuffer.putInt(0, alsaClient.pointer());
                }
                else {
                    if (inputStream.available() < requestLength) return false;
                    alsaClient.writeDataToTrack(inputStream.readByteBuffer(requestLength));
                }
                break;
            case RequestCodes.DRAIN:
                requireEmpty(requestLength);
                alsaClient.drain();
                break;
            case RequestCodes.POINTER:
                requireEmpty(requestLength);
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(alsaClient.pointer());
                }
                break;
            case RequestCodes.MIN_BUFFER_SIZE:
                if (requestLength != 6) throw new IOException("Invalid ALSA MIN_BUFFER_SIZE length");
                if (inputStream.available() < requestLength) return false;
                byte minChannels = inputStream.readByte();
                int minDataTypeOrdinal = inputStream.readUnsignedByte();
                if (minChannels < 1 || minChannels > 2 ||
                    minDataTypeOrdinal >= ALSAClient.DataType.values().length) {
                    throw new IOException("Invalid ALSA MIN_BUFFER_SIZE format");
                }
                ALSAClient.DataType dataType = ALSAClient.DataType.values()[minDataTypeOrdinal];
                int sampleRate = inputStream.readInt();
                if (!ALSAClient.isSupportedSampleRate(sampleRate)) {
                    throw new IOException("Invalid ALSA MIN_BUFFER_SIZE sample rate");
                }
                int minBufferSize = ALSAClient.latencyMillisToBufferSize(alsaClient.options.latencyMillis, minChannels, dataType, sampleRate);
                if (minBufferSize <= 0) {
                    throw new IOException("ALSA MIN_BUFFER_SIZE overflowed or is unsupported");
                }

                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(minBufferSize);
                }
                break;
            default:
                throw new IOException("Unknown ALSA request code");
        }
        alsaClient.noteRequestForDiagnostics();
        return true;
    }

    private static void requireEmpty(int requestLength) throws IOException {
        if (requestLength != 0) throw new IOException("ALSA control request must have no payload");
    }

    private void copySharedBuffer(ALSAClient alsaClient, int requestLength, XOutputStream outputStream) throws IOException {
        ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
        ByteBuffer auxBuffer = alsaClient.getAuxBuffer();

        auxBuffer.position(0).limit(requestLength);
        sharedBuffer.position(ALSAClient.BUFFER_OFFSET).limit(ALSAClient.BUFFER_OFFSET + requestLength);
        auxBuffer.put(sharedBuffer);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
        }
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        int shmSize = alsaClient.getSharedMemorySizeInBytes();
        if (shmSize <= ALSAClient.BUFFER_OFFSET) {
            throw new IOException("Invalid ALSA shared-memory size");
        }
        int fd = SysVSharedMemory.createMemoryFd("alsa-shm"+(++maxSHMemoryId), shmSize);
        if (fd < 0) throw new IOException("ALSA shared-memory fd creation failed");

        try {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, shmSize, 0, false);
            if (buffer == null) throw new IOException("ALSA shared-memory mapping failed");
            boolean attached = false;
            try {
                alsaClient.setSharedBuffer(buffer);
                attached = true;
            }
            finally {
                if (!attached) SysVSharedMemory.unmapSHMSegment(buffer, shmSize);
            }

            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte((byte)0);
                outputStream.setAncillaryFd(fd);
            }
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }
}
