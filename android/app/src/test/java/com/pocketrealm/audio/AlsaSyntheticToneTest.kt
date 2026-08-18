package com.winlator.alsaserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AlsaSyntheticToneTest {
    @Test
    fun `float stereo samples are exact little endian low volume and preserve buffer state`() {
        val tone = ALSAClient.SyntheticTone()
        val frames = 6
        val buffer = ByteBuffer.allocate(frames * Float.SIZE_BYTES * 2).order(ByteOrder.BIG_ENDIAN)
        buffer.position(5)
        buffer.mark()
        buffer.position(7)

        tone.fillFloatStereo(buffer)

        assertEquals(7, buffer.position())
        assertEquals(frames * Float.SIZE_BYTES * 2, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        buffer.reset()
        assertEquals("mark remains valid", 5, buffer.position())

        val littleEndian = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame ->
            val left = littleEndian.getFloat(frame * Float.SIZE_BYTES * 2)
            val right = littleEndian.getFloat(frame * Float.SIZE_BYTES * 2 + Float.SIZE_BYTES)
            assertEquals("stereo channels are identical", left, right, 0.0f)
            assertTrue("sample stays inside low-volume bound", abs(left) <= ALSAClient.SYNTHETIC_TONE_AMPLITUDE)
        }

        val expectedSecond = (
            Math.sin(2.0 * Math.PI * ALSAClient.SYNTHETIC_TONE_FREQUENCY_HZ /
                ALSAClient.SYNTHETIC_TONE_SAMPLE_RATE) * ALSAClient.SYNTHETIC_TONE_AMPLITUDE
            ).toFloat()
        val secondBits = expectedSecond.toRawBits()
        val bytes = buffer.array()
        val secondLeftOffset = Float.SIZE_BYTES * 2
        repeat(Float.SIZE_BYTES) { byteIndex ->
            assertEquals(
                "PCM float byte $byteIndex is little endian",
                (secondBits ushr (byteIndex * 8)) and 0xff,
                bytes[secondLeftOffset + byteIndex].toInt() and 0xff,
            )
        }
    }

    @Test
    fun `phase is continuous across variable non-period chunk boundaries`() {
        val continuous = ALSAClient.SyntheticTone()
        val expected = ByteBuffer.allocate(17 * Float.SIZE_BYTES * 2)
        continuous.fillFloatStereo(expected)

        val chunked = ALSAClient.SyntheticTone()
        val actual = ByteBuffer.allocate(expected.capacity())
        var offset = 0
        listOf(3, 5, 9).forEach { frames ->
            val chunk = ByteBuffer.allocate(frames * Float.SIZE_BYTES * 2)
            chunked.fillFloatStereo(chunk)
            System.arraycopy(chunk.array(), 0, actual.array(), offset, chunk.capacity())
            offset += chunk.capacity()
        }

        assertArrayEquals(expected.array(), actual.array())
    }

    @Test
    fun `prepare or generation reset restarts phase without touching source copy`() {
        val sourceBytes = ByteArray(7 * Float.SIZE_BYTES * 2) { index -> (index * 13).toByte() }
        val sourceBefore = sourceBytes.copyOf()
        val source = ByteBuffer.wrap(sourceBytes).order(ByteOrder.LITTLE_ENDIAN)
        source.position(4)
        source.mark()
        source.position(11)
        val privateAux = ByteBuffer.allocate(sourceBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        privateAux.put(source.duplicate().apply { position(0) })

        val tone = ALSAClient.SyntheticTone()
        tone.fillFloatStereo(privateAux)
        assertArrayEquals("guest or SHM source remains unchanged", sourceBefore, sourceBytes)
        assertEquals(11, source.position())
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order())
        source.reset()
        assertEquals(4, source.position())

        tone.reset()
        val afterReset = ByteBuffer.allocate(3 * Float.SIZE_BYTES * 2)
        tone.fillFloatStereo(afterReset)
        val fresh = ByteBuffer.allocate(afterReset.capacity())
        ALSAClient.SyntheticTone().fillFloatStereo(fresh)
        assertArrayEquals("prepare, release, or generation reset restarts phase", fresh.array(), afterReset.array())
    }

    @Test
    fun `activation requires explicit debug diagnostics and exact negotiated format`() {
        val defaults = ALSAClient.Options()
        assertFalse(defaults.syntheticTone)
        assertFalse(
            ALSAClient.shouldActivateSyntheticTone(
                defaults,
                true,
                ALSAClient.DataType.FLOATLE,
                2,
                48_000,
            ),
        )

        val requested = ALSAClient.Options().apply { syntheticTone = true }
        assertFalse(
            "disabled or release diagnostics remain inert",
            ALSAClient.shouldActivateSyntheticTone(
                requested,
                false,
                ALSAClient.DataType.FLOATLE,
                2,
                48_000,
            ),
        )
        assertTrue(
            ALSAClient.shouldActivateSyntheticTone(
                requested,
                true,
                ALSAClient.DataType.FLOATLE,
                2,
                48_000,
            ),
        )
        assertFalse(ALSAClient.supportsSyntheticTone(ALSAClient.DataType.FLOATBE, 2, 48_000))
        assertFalse(ALSAClient.supportsSyntheticTone(ALSAClient.DataType.FLOATLE, 1, 48_000))
        assertFalse(ALSAClient.supportsSyntheticTone(ALSAClient.DataType.FLOATLE, 2, 44_100))
    }

    @Test
    fun `mode and unsupported reason are content free and emitted once`() {
        val diagnostics = ALSADiagnostics()
        val token = diagnostics.claimStream(null)
        diagnostics.onSyntheticToneDecision(token, ALSADiagnostics.SYNTHETIC_TONE_ACTIVE)
        diagnostics.onPcmReceived(token, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 4, 1L)
        val activeLine = diagnostics.maybeCreateWriteLogLine(token, 1L)!!
        assertTrue(activeLine.contains("mode=synthetic-tone"))

        diagnostics.onPcmReceived(token, ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)), 4, 1_000_000_001L)
        val laterLine = diagnostics.maybeCreateWriteLogLine(token, 1_000_000_001L)!!
        assertFalse("mode decision is logged only once", laterLine.contains("mode=synthetic-tone"))

        val unsupported = diagnostics.claimStream(null)
        diagnostics.onSyntheticToneDecision(
            unsupported,
            ALSADiagnostics.SYNTHETIC_TONE_UNSUPPORTED_FORMAT,
        )
        diagnostics.onPcmReceived(
            unsupported,
            ByteBuffer.wrap(byteArrayOf(9, 10, 11, 12)),
            4,
            2_000_000_001L,
        )
        val unsupportedLine = diagnostics.maybeCreateWriteLogLine(unsupported, 2_000_000_001L)!!
        assertTrue(unsupportedLine.contains("mode=guest-pcm"))
        assertTrue(unsupportedLine.contains("reason=float-stereo-48k-required"))
    }
}
