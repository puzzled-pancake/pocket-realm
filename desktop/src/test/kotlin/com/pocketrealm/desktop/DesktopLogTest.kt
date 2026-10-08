package com.pocketrealm.desktop

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESK-110: the typed in-memory ring (the Android AppLog contract) over
 * the desktop log sink.
 */
class DesktopLogTest {

    @Test
    fun ringIsBoundedAt800EntriesAndKeepsTheNewest() {
        // Feed well past the cap; the ring holds exactly the newest 800.
        // The last 800 messages are deterministic even though the ring is
        // a process-wide singleton other tests also write to: this test's
        // entries are appended after everything already in the ring.
        for (index in 1..CAP_PLUS_MARGIN) {
            DesktopLog.log("INFO", "RingTest", "$index")
        }
        val snapshot = DesktopLog.snapshot()
        assertEquals(800, snapshot.size)
        assertEquals("$CAP_PLUS_MARGIN", snapshot.last().message)
        assertEquals(
            "the ring evicts the OLDEST entries first",
            (6..CAP_PLUS_MARGIN).map { it.toString() },
            snapshot.map { it.message },
        )
        assertTrue(snapshot.all { it.kind == "RingTest" && it.level == "INFO" && it.ts > 0 })
    }

    @Test
    fun exportJsonMatchesTheAppLogShape() {
        DesktopLog.w("JsonTest", "export me")
        val parsed = JSONArray(DesktopLog.exportJson())
        assertTrue(parsed.length() > 0)
        val last = parsed.getJSONObject(parsed.length() - 1)
        assertEquals("WARN", last.getString("level"))
        assertEquals("JsonTest", last.getString("kind"))
        assertEquals("export me", last.getString("msg"))
        assertTrue(last.getLong("ts") > 0)
    }

    @Test
    fun snapshotIsACopyNotTheLiveRing() {
        val before = DesktopLog.snapshot()
        DesktopLog.i("CopyTest", "one more")
        val after = DesktopLog.snapshot()
        assertEquals("an earlier snapshot must not grow", before.size + 1, after.size)
    }

    private companion object {
        /** The ring cap plus enough overflow to force eviction. */
        const val CAP_PLUS_MARGIN = 805
    }
}
