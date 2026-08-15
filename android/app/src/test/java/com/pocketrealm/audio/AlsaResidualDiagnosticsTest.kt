package com.winlator.alsaserver

import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class AlsaResidualDiagnosticsTest {
    @Test
    fun `distinct chunks preserve write order hash repeats and cadence window`() {
        val diagnostics = ALSADiagnostics()
        val token = diagnostics.claimStream(null)
        val chunkA = byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0)
        val chunkB = byteArrayOf(3, 0, 0, 0, 4, 0, 0, 0)
        val chunkC = byteArrayOf(5, 0, 0, 0, 6, 0, 0, 0)

        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkA), 4, 1_000L)
        val first = diagnostics.snapshot()
        assertEquals(1L, first.writeSequence)
        assertEquals(crc32(chunkA), first.writeCrc32)
        assertEquals(0L, first.consecutiveIdenticalWriteCount)
        val firstLine = diagnostics.maybeCreateWriteLogLine(token, 1_000L)
        assertNotNull(firstLine)
        assertTrue(firstLine!!.contains("seq=1 len=8"))
        assertTrue(firstLine.contains("crc32=%08x".format(crc32(chunkA))))
        assertTrue(firstLine.contains("accepted=0 head=0 queued=0 underruns=0"))

        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkA), 4, 100_001_000L)
        val repeatedA = diagnostics.snapshot()
        assertEquals(2L, repeatedA.writeSequence)
        assertEquals(crc32(chunkA), repeatedA.writeCrc32)
        assertEquals(1L, repeatedA.consecutiveIdenticalWriteCount)
        assertNull(diagnostics.maybeCreateWriteLogLine(token, 100_001_000L))

        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkB), 4, 1_100_002_000L)
        val differentB = diagnostics.snapshot()
        assertEquals(3L, differentB.writeSequence)
        assertEquals(crc32(chunkB), differentB.writeCrc32)
        assertNotEquals(first.writeCrc32, differentB.writeCrc32)
        assertEquals(0L, differentB.consecutiveIdenticalWriteCount)
        assertNotNull(diagnostics.maybeCreateWriteLogLine(token, 1_100_002_000L))
        assertEquals(1_000_001_000L, diagnostics.snapshot().lastReportedMaxInterWriteGapNanos)

        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkC), 4, 1_200_002_000L)
        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkC), 4, 1_300_002_000L)
        diagnostics.onPcmReceived(token, ByteBuffer.wrap(chunkC), 4, 1_400_002_000L)
        val repeatedC = diagnostics.snapshot()
        assertEquals(6L, repeatedC.writeSequence)
        assertEquals(chunkC.size, repeatedC.writeLengthBytes)
        assertEquals(crc32(chunkC), repeatedC.writeCrc32)
        assertEquals(2L, repeatedC.consecutiveIdenticalWriteCount)
        assertEquals(100_000_000L, repeatedC.maxInterWriteGapNanos)
        assertEquals(12L, repeatedC.pcmFramesReceived)
        assertEquals(12L, repeatedC.nonZeroPcmFramesReceived)
    }

    @Test
    fun `single pass preserves buffer state and hashes mixed zero and nonzero frames`() {
        val diagnostics = ALSADiagnostics()
        val token = diagnostics.claimStream(null)
        val pcm = byteArrayOf(
            0, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 0, 2,
        )
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        buffer.limit(pcm.size)
        buffer.position(3)
        buffer.mark()
        buffer.position(7)

        diagnostics.onPcmReceived(token, buffer, 4, 10L)

        val snapshot = diagnostics.snapshot()
        assertEquals(7, buffer.position())
        assertEquals(pcm.size, buffer.limit())
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        buffer.reset()
        assertEquals("mark survives duplicate scan", 3, buffer.position())
        assertEquals(3L, snapshot.pcmFramesReceived)
        assertEquals(2L, snapshot.nonZeroPcmFramesReceived)
        assertEquals(crc32(pcm), snapshot.writeCrc32)
    }

    @Test
    fun `control counters and uint32 queue wrap are owner scoped`() {
        val diagnostics = ALSADiagnostics()
        val token = diagnostics.claimStream(null)
        diagnostics.onControlRequest(token, RequestCodes.PREPARE, 10L)
        diagnostics.onControlRequest(token, RequestCodes.START, 20L)
        diagnostics.onControlRequest(token, RequestCodes.PAUSE, 30L)
        diagnostics.onControlRequest(token, RequestCodes.PAUSE, 40L)
        diagnostics.onControlRequest(token, RequestCodes.STOP, 50L)
        diagnostics.onPointer(token, 2L)
        diagnostics.onTrackState(token, AudioTrack.PLAYSTATE_PLAYING, 0xffff_fffeL, 7)

        val snapshot = diagnostics.snapshot()
        assertEquals(1L, snapshot.prepareCount)
        assertEquals(10L, snapshot.lastPrepareNanos)
        assertEquals(1L, snapshot.startCount)
        assertEquals(20L, snapshot.lastStartNanos)
        assertEquals(2L, snapshot.pauseCount)
        assertEquals(40L, snapshot.lastPauseNanos)
        assertEquals(1L, snapshot.stopCount)
        assertEquals(50L, snapshot.lastStopNanos)
        assertEquals(2L, snapshot.pointerFrames)
        assertEquals(0xffff_fffeL, snapshot.playbackHeadFrames)
        assertEquals(4L, snapshot.queueDepthFrames)
        assertEquals(7, snapshot.underrunCount)
    }

    @Test
    fun `release and new stream within one second cannot emit a second line`() {
        val diagnostics = ALSADiagnostics()
        val firstToken = diagnostics.claimStream(null)
        diagnostics.onPcmReceived(firstToken, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 4, 100L)
        assertNotNull(diagnostics.maybeCreateWriteLogLine(firstToken, 100L))
        diagnostics.onTrackReleased(firstToken, true)

        val secondToken = diagnostics.claimStream(null)
        diagnostics.onPcmReceived(
            secondToken,
            ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)),
            4,
            500_000_100L,
        )
        assertNull(diagnostics.maybeCreateWriteLogLine(secondToken, 500_000_100L))
        assertNotNull(diagnostics.maybeCreateWriteLogLine(secondToken, 1_000_000_100L))
    }

    @Test
    fun `stale generation callbacks cannot clear or repopulate current stream`() {
        val diagnostics = ALSADiagnostics()
        val staleToken = diagnostics.claimStream(null)
        diagnostics.onPcmReceived(staleToken, ByteBuffer.wrap(byteArrayOf(1, 1, 1, 1)), 4, 10L)

        val currentToken = diagnostics.claimStream(null)
        val currentChunk = byteArrayOf(2, 2, 2, 2)
        diagnostics.onPcmReceived(currentToken, ByteBuffer.wrap(currentChunk), 4, 20L)
        diagnostics.onPointer(currentToken, 100L)
        diagnostics.onTrackState(currentToken, AudioTrack.PLAYSTATE_PLAYING, 40L, 3)
        diagnostics.onTrackConfiguration(currentToken, 4_800, 4_800, 2_400, 12)
        diagnostics.onControlRequest(currentToken, RequestCodes.START, 30L)

        diagnostics.onPcmReceived(staleToken, ByteBuffer.wrap(byteArrayOf(9, 9, 9, 9)), 4, 40L)
        diagnostics.onPointer(staleToken, 999L)
        diagnostics.onTrackState(staleToken, AudioTrack.PLAYSTATE_STOPPED, 999L, 99)
        diagnostics.onTrackConfiguration(staleToken, 1, 1, 1, 1)
        diagnostics.onControlRequest(staleToken, RequestCodes.PAUSE, 50L)
        diagnostics.onWriteResult(staleToken, 400, 4)
        diagnostics.onTrackReleased(staleToken, true)

        val current = diagnostics.snapshot()
        assertEquals(currentToken, current.streamToken)
        assertEquals(1L, current.writeSequence)
        assertEquals(crc32(currentChunk), current.writeCrc32)
        assertEquals(100L, current.pointerFrames)
        assertEquals(40L, current.playbackHeadFrames)
        assertEquals(60L, current.queueDepthFrames)
        assertEquals(3, current.underrunCount)
        assertEquals(4_800, current.bufferCapacityFrames)
        assertEquals(1L, current.startCount)
        assertEquals(0L, current.pauseCount)
        assertEquals(0L, current.successfulFrameWrites)
        assertEquals("stale physical release is still aggregate evidence", 1L, current.tracksReleased)
    }

    @Test
    fun `current release resets coherently and disabled diagnostics are inert`() {
        val diagnostics = ALSADiagnostics()
        val token = diagnostics.claimStream(null)
        diagnostics.onPcmReceived(token, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 4, 100L)
        diagnostics.onPointer(token, 1L)
        diagnostics.onTrackState(token, AudioTrack.PLAYSTATE_PLAYING, 1L, 2)
        diagnostics.onControlRequest(token, RequestCodes.START, 200L)
        diagnostics.onTrackReleased(token, true)

        val released = diagnostics.snapshot()
        assertEquals(0L, released.streamToken)
        assertEquals(0L, released.writeSequence)
        assertEquals(0L, released.writeCrc32)
        assertEquals(0L, released.pointerFrames)
        assertEquals(0L, released.playbackHeadFrames)
        assertEquals(0L, released.queueDepthFrames)
        assertEquals(0, released.underrunCount)
        assertEquals(AudioTrack.PERFORMANCE_MODE_NONE, released.performanceMode)
        assertEquals(1L, released.startCount)

        val disabled = ALSADiagnostics.disabled()
        val disabledBuffer = ByteBuffer.wrap(byteArrayOf(9, 8, 7, 6)).order(ByteOrder.LITTLE_ENDIAN)
        disabledBuffer.position(2)
        assertEquals(0L, disabled.claimStream(null))
        disabled.onPcmReceived(0, disabledBuffer, 4)
        disabled.onControlRequest(0, RequestCodes.PAUSE)
        disabled.onPointer(0, 9L)
        disabled.onTrackReleased(0, true)
        assertEquals(2, disabledBuffer.position())
        assertEquals(ByteOrder.LITTLE_ENDIAN, disabledBuffer.order())
        assertEquals(0L, disabled.snapshot().writeSequence)
        assertEquals(0L, disabled.snapshot().pauseCount)
        assertEquals(0L, disabled.snapshot().tracksReleased)
        assertNull(disabled.maybeCreateWriteLogLine(0))
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().run {
        update(bytes)
        value
    }
}
