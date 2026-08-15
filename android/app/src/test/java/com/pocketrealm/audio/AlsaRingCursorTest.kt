package com.pocketrealm.audio

import android.media.AudioTrack
import com.winlator.alsaserver.ALSAClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AlsaRingCursorTest {
    @Test
    fun `cursor preserves cumulative accepted frames across ring boundaries`() {
        assertEquals(0, ALSAClient.cumulativePositionFrames(0))
        assertEquals(4_799, ALSAClient.cumulativePositionFrames(4_799))
        assertEquals(4_800, ALSAClient.cumulativePositionFrames(4_800))
        assertEquals(5_400, ALSAClient.cumulativePositionFrames(5_400))
        assertEquals(9_600, ALSAClient.cumulativePositionFrames(9_600))
    }

    @Test
    fun `cursor publishes the low unsigned 32 bits at the protocol boundary`() {
        assertEquals(4_294_967_295L, Integer.toUnsignedLong(ALSAClient.cumulativePositionFrames(0xffff_ffffL)))
        assertEquals(123L, Integer.toUnsignedLong(ALSAClient.cumulativePositionFrames(0x1_0000_0000L + 123L)))
    }

    @Test
    fun `deep underrun recovery advances from accepted frames rather than a stopped playback head`() {
        val acceptedBeforeGap = 17_160_000L
        val acceptedAfterGap = acceptedBeforeGap + 1_024L

        assertEquals(
            acceptedAfterGap.toInt(),
            ALSAClient.cumulativePositionFrames(acceptedAfterGap),
        )
    }

    @Test
    fun `negative cumulative position fails closed at zero`() {
        assertEquals(0, ALSAClient.cumulativePositionFrames(-1))
    }

    @Test
    fun `complete ring writes never alias to zero progress`() {
        val cursor = ALSAClient.AcceptedCursor()
        val generation = cursor.reset()

        assertTrue(cursor.accept(generation, 4_800 * 4))
        val first = cursor.pointerFrames(4)
        assertTrue(cursor.accept(generation, 4_800 * 4))
        val second = cursor.pointerFrames(4)

        assertEquals(4_800, first)
        assertEquals(9_600, second)
        assertEquals(4_800, second - first)
    }

    @Test
    fun `accepted byte cursor carries partial frame remainder`() {
        val cursor = ALSAClient.AcceptedCursor()
        val generation = cursor.reset()

        assertTrue(cursor.accept(generation, 3))
        assertEquals(0, cursor.pointerFrames(4))
        assertTrue(cursor.accept(generation, 5))
        assertEquals(2, cursor.pointerFrames(4))
    }

    @Test
    fun `stale blocking writer cannot repopulate a reset cursor`() {
        val cursor = ALSAClient.AcceptedCursor()
        val oldGeneration = cursor.reset()
        assertTrue(cursor.accept(oldGeneration, 4_096))
        val currentGeneration = cursor.reset()

        assertFalse(cursor.accept(oldGeneration, 4_096))
        assertEquals(0, cursor.pointerFrames(4))
        assertTrue(cursor.accept(currentGeneration, 4_096))
        assertEquals(1_024, cursor.pointerFrames(4))
    }

    @Test
    fun `concurrent accepted writes and pointer reads remain coherent`() {
        val cursor = ALSAClient.AcceptedCursor()
        val generation = cursor.reset()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val writers = (0 until 4).map {
                pool.submit {
                    start.await()
                    repeat(1_000) { assertTrue(cursor.accept(generation, 4)) }
                }
            }
            val reader = pool.submit {
                start.await()
                repeat(1_000) {
                    assertTrue(Integer.toUnsignedLong(cursor.pointerFrames(4)) in 0L..4_000L)
                }
            }
            start.countDown()
            (writers + reader).forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4_000, cursor.pointerFrames(4))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `prepare dimensions and latency math fail before integer overflow`() {
        assertEquals(
            ALSAClient.MAX_NEGOTIATED_RING_FRAMES * 8,
            ALSAClient.checkedBufferSizeInBytes(ALSAClient.MAX_NEGOTIATED_RING_FRAMES, 8),
        )
        assertEquals(
            -1,
            ALSAClient.checkedBufferSizeInBytes(ALSAClient.MAX_NEGOTIATED_RING_FRAMES + 1, 8),
        )
        assertEquals(-1, ALSAClient.checkedBufferSizeInBytes(Int.MAX_VALUE, 8))
        assertEquals(
            -1,
            ALSAClient.latencyMillisToBufferSize(Int.MAX_VALUE, 2, ALSAClient.DataType.FLOATLE, 48_000),
        )
        assertEquals(
            -1,
            ALSAClient.latencyMillisToBufferSize(100, 2, ALSAClient.DataType.FLOATLE, 96_000),
        )
    }

    @Test
    fun `production audio avoids the RP6 deep buffer route`() {
        assertEquals(
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
            ALSAClient.Options().performanceMode.toInt(),
        )
        assertEquals(100, ALSAClient.Options().latencyMillis.toInt())
        assertEquals(4_800 * 8, ALSAClient.checkedBufferSizeInBytes(4_800, 8))
    }
}
