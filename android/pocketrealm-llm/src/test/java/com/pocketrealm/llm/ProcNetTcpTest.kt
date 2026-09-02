package com.pocketrealm.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture guard for the /proc/net/tcp LISTEN-row parser behind the health
 * ownership gate (childOwnsPort): proc_net is SELinux-denied to app domains
 * on Android 10+, so this fixture is the only place the kernel row contract
 * is pinned — state column 0A, the :%04X port suffix on the local-address
 * column only, the inode at column 9, and graceful skipping of the header
 * and malformed short rows.
 */
class ProcNetTcpTest {

    /** Kernel-format sample: two 8080 listeners (loopback + wildcard), an
     *  8081 listener, an ESTABLISHED 8080 row, an 80 listener, and a
     *  truncated 8082 LISTEN row. */
    private val table = """
        sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
         0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10123        0 111001 1 0000000000000000 100 0 0 10 0
         1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000      0        0 111002 1 0000000000000000 100 0 0 10 0
         2: 0100007F:1F91 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10123        0 111003 1 0000000000000000 100 0 0 10 0
         3: 0100007F:D9F2 0100007F:1F90 01 00000000:00000000 00:00000000 00000000  10123        0 111004 1 0000000000000000 20 4 30 10 -1
         4: 0100007F:0050 00000000:0000 0A 00000000:00000000 00:00000000 00000000      0        0 111005 1 0000000000000000 100 0 0 10 0
         5: 0100007F:1F92 00000000:0000 0A
    """.trimIndent() + "\n"

    @Test
    fun listenRowsForThePortYieldTheirSocketInodes() {
        // 1F90 = 8080: both the loopback and the wildcard listener.
        assertEquals(listOf("111001", "111002"), ProcNetTcp.listenInodes(table, 8080))
        assertEquals(listOf("111003"), ProcNetTcp.listenInodes(table, 8081))
        // Ports below 0x1000 are zero-padded to four hex digits by the kernel.
        assertEquals(listOf("111005"), ProcNetTcp.listenInodes(table, 80))
    }

    @Test
    fun nonListenStatesNeverMatchEvenOnTheRightPort() {
        // Row 3 is ESTABLISHED (01) with local port 0xD9F2; row 3's REMOTE
        // address ends in :1F90, which must not count as an 8080 listener —
        // only the local-address column is consulted.
        assertEquals(emptyList<String>(), ProcNetTcp.listenInodes(table, 55_794)) // 0xD9F2
    }

    @Test
    fun theHeaderRowAndShortRowsSelfFilter() {
        // The truncated 8082 (1F92) LISTEN row has no inode column.
        assertEquals(emptyList<String>(), ProcNetTcp.listenInodes(table, 8082))
        // The header alone parses to nothing.
        assertEquals(
            emptyList<String>(),
            ProcNetTcp.listenInodes("sl  local_address rem_address   st\n", 8080),
        )
        assertEquals(emptyList<String>(), ProcNetTcp.listenInodes("", 8080))
    }

    @Test
    fun thePortSuffixIsAnchoredOnTheColon() {
        // 0x90 = 144: ":0090" must not suffix-match ":1F90" or ":0050".
        assertEquals(emptyList<String>(), ProcNetTcp.listenInodes(table, 144))
        // 0x50 = 80 must not match ":0050" twice-over or bleed into :1F90.
        assertEquals(listOf("111005"), ProcNetTcp.listenInodes(table, 80))
    }
}
