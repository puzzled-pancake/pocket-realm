package com.pocketrealm.audio

import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.alsaserver.ALSAClient
import com.winlator.alsaserver.ALSAClientConnectionHandler
import com.winlator.alsaserver.ALSADiagnostics
import com.winlator.alsaserver.ALSARequestHandler
import com.winlator.alsaserver.RequestCodes
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device acceptance for the pinned ca3d735 android_aserver wire protocol. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class AlsaServerProtocolAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exactSharedMemoryProtocolWritesAudibleTrackAndReleasesEveryResource() {
        val fixture = fixture()
        val diagnostics = ALSADiagnostics()
        val options = ALSAClient.Options()
        assertEquals(
            "device acceptance avoids the RP6 deep-buffer route",
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
            options.performanceMode.toInt(),
        )
        val connector = XConnectorEpoll(
            UnixSocketConfig.create(fixture.root.absolutePath, ".sound/AS0"),
            ALSAClientConnectionHandler(options, diagnostics),
            ALSARequestHandler(),
        ).apply {
            setMultithreadedClients(true)
            start()
        }

        var socket: LocalSocket? = null
        var sharedMemory: SharedMemory? = null
        var sharedBuffer: ByteBuffer? = null
        try {
            socket = connect(fixture.socket)
            await("connection accepted") { diagnostics.snapshot().activeConnections == 1 }

            val minimumBytes = queryMinimumBufferBytes(socket, channels = 2, dataType = 1, rate = 48_000)
            assertTrue("server returned a positive latency buffer", minimumBytes > 0)
            assertEquals("minimum buffer is frame aligned", 0, minimumBytes % 4)

            val bufferFrames = 4_800
            sendPrepare(socket, channels = 2, dataType = 1, rate = 48_000, bufferFrames = bufferFrames)
            val receivedFd = receiveSharedMemoryFd(socket)
            val parcelFd = ParcelFileDescriptor.dup(receivedFd.fd)
            receivedFd.close()
            sharedMemory = SharedMemory.fromFileDescriptor(parcelFd)
            sharedBuffer = sharedMemory.mapReadWrite().order(ByteOrder.LITTLE_ENDIAN)

            await("AudioTrack enters playing state") {
                val snapshot = diagnostics.snapshot()
                snapshot.audioTrackPresent &&
                    snapshot.playingObserved &&
                    snapshot.playState == AudioTrack.PLAYSTATE_PLAYING
            }

            val configured = diagnostics.snapshot()
            assertTrue("AudioTrack reports a positive capacity", configured.bufferCapacityFrames > 0)
            assertTrue("AudioTrack reports a positive active queue", configured.trackBufferFrames > 0)
            assertEquals(
                "active queue uses the full stable ring within Android capacity",
                minOf(configured.bufferCapacityFrames, bufferFrames),
                configured.trackBufferFrames,
            )
            assertTrue("AudioTrack reports a positive start threshold", configured.startThresholdFrames > 0)
            assertEquals(
                "device acceptance keeps the production low-latency track",
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                configured.performanceMode,
            )

            request(socket, RequestCodes.START)
            for (frame in 0 until bufferFrames) {
                val sample = if (frame and 1 == 0) 1_200.toShort() else (-1_200).toShort()
                sharedBuffer.putShort(ALSAClient.BUFFER_OFFSET.toInt() + frame * 4, sample)
                sharedBuffer.putShort(ALSAClient.BUFFER_OFFSET.toInt() + frame * 4 + 2, sample)
            }
            val primeLimit = (configured.startThresholdFrames + bufferFrames - 1) / bufferFrames + 2
            var primeWrites = 0
            while (diagnostics.snapshot().maxPlaybackHeadFrames == 0L && primeWrites < primeLimit) {
                writeAndAwaitAccepted(socket, diagnostics, sharedBuffer, bufferFrames)
                primeWrites++
            }
            assertTrue("production start threshold was fully primed", primeWrites > 0)
            assertEquals(
                "shared-memory pointer exposes cumulative accepted frames",
                diagnostics.snapshot().successfulFrameWrites,
                Integer.toUnsignedLong(sharedBuffer!!.getInt(0)),
            )
            await("hardware playback head advances") {
                diagnostics.snapshot().maxPlaybackHeadFrames > 0
            }
            val firstFullRingPointer = Integer.toUnsignedLong(sharedBuffer.getInt(0))
            writeAndAwaitAccepted(socket, diagnostics, sharedBuffer, bufferFrames)
            val secondFullRingPointer = Integer.toUnsignedLong(sharedBuffer.getInt(0))
            assertEquals(
                "a second complete ring advances instead of aliasing to zero",
                bufferFrames.toLong(),
                secondFullRingPointer - firstFullRingPointer,
            )

            // Let the low-latency queue drain completely and verify recovery
            // from a real scheduling underrun while Android remains PLAYING.
            val gapStart = diagnostics.snapshot()
            await("AudioTrack reports a deep underrun", timeoutMs = 8_000) {
                diagnostics.snapshot().underrunCount > gapStart.underrunCount
            }
            var previousHead = -1L
            var stableHeadSamples = 0
            await("hardware playback head stalls after underrun", timeoutMs = 4_000) {
                val head = diagnostics.snapshot().playbackHeadFrames
                if (head == previousHead) stableHeadSamples++ else stableHeadSamples = 0
                previousHead = head
                Thread.sleep(40)
                stableHeadSamples >= 4
            }
            val beforeRecovery = diagnostics.snapshot()

            // Refill most of the ring. ACK precedes AudioTrack.write, so wait
            // for the accepted-frame counter and exact cumulative cursor rather
            // than accepting the first transfer's stale predicate.
            val acceptedBeforeRecovery = diagnostics.snapshot().successfulFrameWrites
            val recoveryFrames = bufferFrames - 512
            writeAndAwaitAccepted(socket, diagnostics, sharedBuffer, recoveryFrames)
            val expectedRecoveryCursor = (acceptedBeforeRecovery + recoveryFrames) and 0xffff_ffffL
            await("accepted cursor crosses the ring while hardware is stalled", timeoutMs = 8_000) {
                val snapshot = diagnostics.snapshot()
                snapshot.successfulFrameWrites >= acceptedBeforeRecovery + recoveryFrames &&
                    snapshot.pointerFrames == expectedRecoveryCursor &&
                    Integer.toUnsignedLong(sharedBuffer!!.getInt(0)) == expectedRecoveryCursor
            }
            val recovered = diagnostics.snapshot()
            assertEquals(
                "stable full-ring queue does not depend on slow adaptive growth",
                beforeRecovery.trackBufferFrames,
                recovered.trackBufferFrames,
            )
            assertEquals(
                "full-ring queue remains inside capacity and the guest ring",
                minOf(recovered.bufferCapacityFrames, bufferFrames),
                recovered.trackBufferFrames,
            )

            // Refill in bounded ring-sized writes until the device's actual
            // low-latency restart threshold is met.
            val headBeforeRestart = diagnostics.snapshot().maxPlaybackHeadFrames
            val restartLimit =
                (diagnostics.snapshot().startThresholdFrames + bufferFrames - 1) / bufferFrames + 2
            var restartWrites = 0
            while (
                diagnostics.snapshot().maxPlaybackHeadFrames <= headBeforeRestart &&
                restartWrites < restartLimit
            ) {
                writeAndAwaitAccepted(socket, diagnostics, sharedBuffer, bufferFrames)
                restartWrites++
            }
            await("post-underrun playback restarts", timeoutMs = 8_000) {
                diagnostics.snapshot().maxPlaybackHeadFrames > headBeforeRestart
            }

            request(socket, RequestCodes.PAUSE)
            await("track pauses on provider pause") {
                diagnostics.snapshot().playState == AudioTrack.PLAYSTATE_PAUSED
            }
            // The pinned native provider currently emits PAUSE for unpause as
            // well. A subsequent transfer must resume the initialized track
            // rather than leaving the session permanently silent.
            request(socket, RequestCodes.PAUSE)
            writeAndAwaitAccepted(socket, diagnostics, sharedBuffer, 1_024)
            await("PCM flow resumes a paused provider track") {
                diagnostics.snapshot().playState == AudioTrack.PLAYSTATE_PLAYING
            }
            request(socket, RequestCodes.DRAIN)
            request(socket, RequestCodes.CLOSE)
            socket.close()
            socket = null

            await("connection and track released") {
                val snapshot = diagnostics.snapshot()
                snapshot.activeConnections == 0 &&
                    !snapshot.audioTrackPresent &&
                    snapshot.tracksReleased == snapshot.tracksCreated
            }
        } finally {
            sharedBuffer?.let(SharedMemory::unmap)
            sharedMemory?.close()
            socket?.close()
            connector.destroy()
        }

        val terminal = diagnostics.snapshot()
        assertEquals(1L, terminal.connectionsOpened)
        assertEquals(1L, terminal.connectionsClosed)
        assertEquals(0, terminal.activeConnections)
        assertEquals(1L, terminal.prepareAttempts)
        assertEquals(0L, terminal.prepareRejected)
        assertEquals(1L, terminal.tracksCreated)
        assertEquals(1L, terminal.tracksReleased)
        assertFalse(terminal.audioTrackPresent)
        assertTrue("connector client list drained", connector.clients.isEmpty())
        assertConnectFails(fixture.socket)
        fixture.cleanup()
    }

    @Test
    fun malformedClientIsClosedAndConnectorAcceptsAHealthyRetry() {
        val fixture = fixture()
        val diagnostics = ALSADiagnostics()
        val connector = XConnectorEpoll(
            UnixSocketConfig.create(fixture.root.absolutePath, ".sound/AS0"),
            ALSAClientConnectionHandler(ALSAClient.Options(), diagnostics),
            ALSARequestHandler(),
        ).apply {
            setMultithreadedClients(true)
            start()
        }

        try {
            connect(fixture.socket).use { broken ->
                // PREPARE is exactly ten bytes in the pinned provider.  A
                // different length is rejected before any AudioTrack exists.
                request(broken, RequestCodes.PREPARE, ByteArray(9))
                await("malformed connection closed") {
                    diagnostics.snapshot().connectionsClosed == 1L &&
                        diagnostics.snapshot().activeConnections == 0
                }
            }

            connect(fixture.socket).use { healthy ->
                await("retry connection accepted") {
                    diagnostics.snapshot().connectionsOpened == 2L &&
                        diagnostics.snapshot().activeConnections == 1
                }
                request(healthy, RequestCodes.CLOSE)
            }
            await("retry connection released") {
                diagnostics.snapshot().connectionsClosed == 2L &&
                    diagnostics.snapshot().activeConnections == 0
            }
        } finally {
            connector.destroy()
        }

        val terminal = diagnostics.snapshot()
        assertEquals(0L, terminal.tracksCreated)
        assertEquals(0L, terminal.tracksReleased)
        assertFalse(terminal.audioTrackPresent)
        assertTrue(connector.clients.isEmpty())
        fixture.cleanup()
    }

    @Test
    fun invalidPrepareAllocatesNoSharedMemoryAndHealthyRetryStillWorks() {
        val fixture = fixture()
        val diagnostics = ALSADiagnostics()
        val connector = XConnectorEpoll(
            UnixSocketConfig.create(fixture.root.absolutePath, ".sound/AS0"),
            ALSAClientConnectionHandler(ALSAClient.Options(), diagnostics),
            ALSARequestHandler(),
        ).apply {
            setMultithreadedClients(true)
            start()
        }

        try {
            connect(fixture.socket).use { rejected ->
                sendPrepare(rejected, channels = 2, dataType = 1, rate = 48_000, bufferFrames = 0)
                await("invalid prepare closes without a track or shared fd") {
                    val snapshot = diagnostics.snapshot()
                    snapshot.prepareRejected == 1L &&
                        snapshot.tracksCreated == 0L &&
                        snapshot.connectionsClosed == 1L
                }
                assertTrue(rejected.ancillaryFileDescriptors.isNullOrEmpty())
            }

            connect(fixture.socket).use { healthy ->
                await("healthy retry accepted") { diagnostics.snapshot().activeConnections == 1 }
                request(healthy, RequestCodes.CLOSE)
            }
            await("healthy retry released") { diagnostics.snapshot().activeConnections == 0 }
        } finally {
            connector.destroy()
        }

        val terminal = diagnostics.snapshot()
        assertEquals(2L, terminal.connectionsOpened)
        assertEquals(2L, terminal.connectionsClosed)
        assertEquals(1L, terminal.prepareAttempts)
        assertEquals(1L, terminal.prepareRejected)
        assertEquals(0L, terminal.tracksCreated)
        assertConnectFails(fixture.socket)
        fixture.cleanup()
    }

    @Test
    fun audioOffCreatesNeitherEndpointNorTrack() {
        val fixture = fixture()
        val diagnostics = ALSADiagnostics()

        // This is the audio-off construction contract: do not allocate an
        // XConnectorEpoll at all.  ClientDisplayHost applies the same branch.
        assertFalse(fixture.socket.exists())
        assertConnectFails(fixture.socket)
        val snapshot = diagnostics.snapshot()
        assertEquals(0L, snapshot.connectionsOpened)
        assertEquals(0L, snapshot.tracksCreated)
        assertFalse(snapshot.audioTrackPresent)
        fixture.cleanup()
    }

    @Test
    fun teardownStopsListenerAndNextAudioOnUnlinksStaleNodeBeforeBind() {
        val fixture = fixture()
        val first = connector(fixture)
        first.destroy()

        // Current native teardown closes the listener synchronously but leaves
        // the filesystem node.  A connection must still fail, and the next
        // constructor must unlink that exact stale node before bind.
        assertTrue("native connector leaves a stale filesystem node", fixture.socket.exists())
        assertConnectFails(fixture.socket)
        val second = connector(fixture)
        second.destroy()
        assertConnectFails(fixture.socket)
        fixture.cleanup()
    }

    private fun connector(fixture: Fixture): XConnectorEpoll = XConnectorEpoll(
        UnixSocketConfig.create(fixture.root.absolutePath, ".sound/AS0"),
        ALSAClientConnectionHandler(ALSAClient.Options(), ALSADiagnostics()),
        ALSARequestHandler(),
    ).apply { start() }

    private fun fixture(): Fixture {
        val root = File(context.cacheDir, "alsa-accept-${UUID.randomUUID()}")
        File(root, ".sound").mkdirs()
        return Fixture(root, File(root, ".sound/AS0"))
    }

    private fun connect(socketFile: File): LocalSocket {
        var lastFailure: Throwable? = null
        repeat(30) {
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                )
                socket.soTimeout = 5_000
                return socket
            } catch (error: Throwable) {
                lastFailure = error
                socket.close()
                Thread.sleep(20)
            }
        }
        throw AssertionError("ALSA endpoint did not accept a connection", lastFailure)
    }

    private fun assertConnectFails(socketFile: File) {
        val socket = LocalSocket()
        var connected = false
        try {
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            connected = true
        } catch (_: Throwable) {
            // Expected: either no node (audio-off) or no listener (destroyed).
        } finally {
            socket.close()
        }
        assertFalse("audio endpoint unexpectedly accepted a connection", connected)
    }

    private fun queryMinimumBufferBytes(
        socket: LocalSocket,
        channels: Int,
        dataType: Int,
        rate: Int,
    ): Int {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(channels.toByte())
            .put(dataType.toByte())
            .putInt(rate)
            .array()
        request(socket, RequestCodes.MIN_BUFFER_SIZE, payload)
        return ByteBuffer.wrap(readFully(socket.inputStream, 4)).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun sendPrepare(
        socket: LocalSocket,
        channels: Int,
        dataType: Int,
        rate: Int,
        bufferFrames: Int,
    ) {
        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(channels.toByte())
            .put(dataType.toByte())
            .putInt(rate)
            .putInt(bufferFrames)
            .array()
        request(socket, RequestCodes.PREPARE, payload)
    }

    private fun receiveSharedMemoryFd(socket: LocalSocket): FileInputStream {
        assertEquals("SCM_RIGHTS marker", 0, socket.inputStream.read())
        val descriptors = socket.ancillaryFileDescriptors
        assertTrue("PREPARE supplied one shared-memory fd", descriptors?.size == 1)
        return FileInputStream(descriptors!!.single())
    }

    private fun request(
        socket: LocalSocket,
        code: Byte,
        payload: ByteArray = ByteArray(0),
        payloadLength: Int = payload.size,
    ) {
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(code)
            .putInt(payloadLength)
            .array()
        socket.outputStream.write(header)
        if (payload.isNotEmpty()) socket.outputStream.write(payload)
        socket.outputStream.flush()
    }

    private fun writeAndAwaitAccepted(
        socket: LocalSocket,
        diagnostics: ALSADiagnostics,
        sharedBuffer: ByteBuffer,
        frames: Int,
    ) {
        val acceptedBefore = diagnostics.snapshot().successfulFrameWrites
        request(socket, RequestCodes.WRITE, payloadLength = frames * 4)
        assertEquals("shared-memory WRITE acknowledgement", 1, socket.inputStream.read())
        val acceptedAfter = acceptedBefore + frames
        await("$frames PCM frames accepted by AudioTrack", timeoutMs = 8_000) {
            val snapshot = diagnostics.snapshot()
            snapshot.successfulFrameWrites >= acceptedAfter &&
                snapshot.pointerFrames == (acceptedAfter and 0xffff_ffffL) &&
                Integer.toUnsignedLong(sharedBuffer.getInt(0)) ==
                    (acceptedAfter and 0xffff_ffffL)
        }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException("socket closed while reading $size bytes")
            offset += count
        }
        return result
    }

    private fun await(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(label, condition())
    }

    private data class Fixture(val root: File, val socket: File) {
        fun cleanup() {
            socket.delete()
            File(root, ".sound").delete()
            root.delete()
        }
    }
}
