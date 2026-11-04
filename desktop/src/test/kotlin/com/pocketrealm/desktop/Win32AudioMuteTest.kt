package com.pocketrealm.desktop

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the per-process game-audio mute contract — the launch-mode poll
 * over a FAKE session muter (the plan's honest-refusal leg: "no audio
 * session found" is a result detail, never a throw). The real Core Audio
 * walk is exercised only where a session exists, so the production SYSTEM
 * muter's pid-0/absent-pid edges get the honest refusal pins here.
 */
class Win32AudioMuteTest {

    @Test
    fun applyLaunchModeConfirmsOnTheFirstSuccess() {
        val calls = AtomicInteger(0)
        val muter = AudioSessionMuter { pid, mute ->
            calls.incrementAndGet()
            assertEquals(4242, pid)
            assertTrue(mute)
            null
        }
        var now = 0L
        val verdict = Win32AudioMute.applyLaunchMode(
            pid = 4242,
            mute = true,
            muter = muter,
            nowMs = { now },
            sleepMs = { now += it },
        )
        assertNull("a confirmed mute is the null verdict", verdict)
        assertEquals(1, calls.get())
    }

    @Test
    fun applyLaunchModePollsUntilTheSessionAppears() {
        val calls = AtomicInteger(0)
        val muter = AudioSessionMuter { _, _ ->
            if (calls.incrementAndGet() < 3) "no audio session found for pid 7 yet" else null
        }
        var now = 0L
        val verdict = Win32AudioMute.applyLaunchMode(
            pid = 7,
            mute = true,
            muter = muter,
            nowMs = { now },
            sleepMs = { now += it },
        )
        assertNull(verdict)
        assertEquals(3, calls.get())
    }

    @Test
    fun applyLaunchModeRefusesHonestlyWhenNoSessionEverAppears() {
        // The plan's leg: the mute REFUSES (result detail) — never throws,
        // never fakes success — when no audio session exists for the pid.
        val calls = AtomicInteger(0)
        val muter = AudioSessionMuter { pid, _ ->
            calls.incrementAndGet()
            "no audio session found for pid $pid yet"
        }
        val verdict = Win32AudioMute.applyLaunchMode(
            pid = 7,
            mute = true,
            muter = muter,
            nowMs = { calls.get() * Win32AudioMuteTestConstants.POLL.toLong() },
            sleepMs = { },
            deadlineMs = Win32AudioMuteTestConstants.DEADLINE,
            pollMs = Win32AudioMuteTestConstants.POLL.toLong(),
        )
        assertNotNull("the deadline expiry is an honest refusal detail", verdict)
        assertTrue(
            "the refusal must say why: $verdict",
            "no audio session found for pid 7" in verdict!!,
        )
        assertEquals(
            "the poll budget was used before giving up",
            (Win32AudioMuteTestConstants.DEADLINE / Win32AudioMuteTestConstants.POLL).toInt(),
            calls.get(),
        )
    }

    @Test
    fun applyLaunchModeReportsMuterFailuresInsteadOfThrowing() {
        val calls = AtomicInteger(0)
        val muter = AudioSessionMuter { _, _ ->
            calls.incrementAndGet(); throw IllegalStateException("COM broke")
        }
        val verdict = Win32AudioMute.applyLaunchMode(
            pid = 9,
            mute = true,
            muter = muter,
            nowMs = { calls.get() * Win32AudioMuteTestConstants.POLL.toLong() },
            sleepMs = { },
            deadlineMs = Win32AudioMuteTestConstants.DEADLINE,
            pollMs = Win32AudioMuteTestConstants.POLL.toLong(),
        )
        assertNotNull(verdict)
        assertTrue("failure detail: $verdict", "audio mute failed: COM broke" in verdict!!)
    }

    @Test
    fun systemMuterRefusesAnUnknownPidHonestly() {
        // Windows-only native walk; on non-Windows the runCatching posture
        // yields the honest failure detail either way. pid 0 is refused
        // before any COM call on every platform.
        assertTrue(Win32AudioMute.SYSTEM.setSessionMute(0, true) != null)
    }

    private object Win32AudioMuteTestConstants {
        const val POLL = 10
        const val DEADLINE = 100L
    }
}
