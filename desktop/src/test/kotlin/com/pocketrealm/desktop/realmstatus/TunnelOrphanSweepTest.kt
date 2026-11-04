package com.pocketrealm.desktop.realmstatus

import java.io.File
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The orphan-sweep pins, over the SAME record shape the production
 * spawn seam writes ([TunnelOrphanSweep.realizedCommandLine] — the
 * realized `binary + argument tail` line, never a fabricated one):
 *
 *  - a crash-orphaned child of this install's OWN record is killed
 *    automatically under the triple match (pid + command line + start
 *    time) and its record removed;
 *  - the command-line leg tolerates ONLY the OS's executable-head
 *    quoting — a pid-reused unrelated process never matches;
 *  - a record-less older match is NEVER killed: it surfaces the orphan
 *    notice + transition gate;
 *  - this instance's live child is skipped (record consulted first);
 *  - adoption lands the orphan in the records under the same rules;
 *  - a sweep that dies mid-decision fails open AND is logged — a dead
 *    detector must not masquerade as a clean one.
 */
class TunnelOrphanSweepTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val tail = TunnelOrphanSweep.PAGE_TUNNEL_ARGUMENT_TAIL

    private fun records(): TunnelChildRecords =
        TunnelChildRecords(File(folder.root, "tunnel-records.json"))

    /** The production spawn seam's record, verbatim shape. */
    private fun putSpawnRecord(
        records: TunnelChildRecords,
        pid: Long,
        binary: String,
        startUnixSeconds: Long?,
        instanceId: String = "prior-lifetime",
    ) {
        records.put(
            TunnelChildRecords.Record(
                pid = pid,
                commandLine = TunnelOrphanSweep.realizedCommandLine(binary),
                startUnixSeconds = startUnixSeconds,
                spawnWallMs = 0L,
                instanceId = instanceId,
            ),
        )
    }

    private fun sweep(
        records: TunnelChildRecords,
        table: List<TunnelOrphanSweep.ProcessEntry>,
        ownLive: Set<Long> = emptySet(),
        killed: MutableList<Long> = mutableListOf(),
    ): TunnelOrphanSweep = TunnelOrphanSweep(
        records = records,
        table = object : TunnelOrphanSweep.ProcessTable {
            override fun cloudflaredProcesses(): List<TunnelOrphanSweep.ProcessEntry> = table
        },
        currentInstanceId = "current-instance",
        appStartUnixSeconds = 1_000_000L,
        isOwnLiveChild = { it in ownLive },
        killProcess = { pid -> killed.add(pid.toLong()).let { true } },
    )

    @Test
    fun `a crash-orphaned own child is killed under the triple match and its record removed`() {
        val records = records()
        // The spawn seam's record for a CONFIGURED-PATH binary, left behind
        // by a previous app lifetime (the crash that orphaned the child).
        putSpawnRecord(
            records,
            pid = 4242L,
            binary = "E:\\tools\\cloudflared.exe",
            startUnixSeconds = 900_000L,
        )
        // The process table reports the child with the REALIZED line the
        // OS was handed (identical here — no quoting needed).
        val outcome = sweep(
            records,
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 4242L,
                    commandLine = TunnelOrphanSweep.realizedCommandLine("E:\\tools\\cloudflared.exe"),
                    startUnixSeconds = 900_000L,
                ),
            ),
        ).sweep()
        assertTrue(
            "the §5.3 own-records kill fires for a real record/table pair",
            outcome is TunnelOrphanSweep.Outcome.OwnLeftoverKilled,
        )
        assertEquals(listOf(4242L), (outcome as TunnelOrphanSweep.Outcome.OwnLeftoverKilled).pids)
        assertTrue("the record is consumed with the kill", records.all().isEmpty())
    }

    @Test
    fun `the command-line leg tolerates only the os executable-head quoting`() {
        val records = records()
        // Record: the realized line (unquoted). Table: Windows quoted the
        // executable head because the path contains a space.
        putSpawnRecord(
            records,
            pid = 77L,
            binary = "E:\\my tools\\cloudflared.exe",
            startUnixSeconds = 900_000L,
        )
        assertTrue(
            TunnelOrphanSweep.commandLinesMatch(
                recorded = TunnelOrphanSweep.realizedCommandLine("E:\\my tools\\cloudflared.exe"),
                actual = "\"E:\\my tools\\cloudflared.exe\" $tail",
            ),
        )
        val outcome = sweep(
            records,
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 77L,
                    commandLine = "\"E:\\my tools\\cloudflared.exe\" $tail",
                    startUnixSeconds = 900_000L,
                ),
            ),
        ).sweep()
        assertTrue(outcome is TunnelOrphanSweep.Outcome.OwnLeftoverKilled)
    }

    @Test
    fun `a pid-reused page-tunnel process never matches the record`() {
        val records = records()
        putSpawnRecord(records, pid = 5001L, binary = "cloudflared", startUnixSeconds = 900_000L)
        // After a reboot the OS handed pid 5001 to a DIFFERENT page-tunnel
        // invocation (same shape, different start time — the exact reuse
        // hazard 's start-time leg exists for): the triple match must
        // fail, so the child is never killed under the stale record — at
        // most it surfaces the lost-record gate.
        val outcome = sweep(
            records,
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 5001L,
                    commandLine = TunnelOrphanSweep.realizedCommandLine("cloudflared"),
                    startUnixSeconds = 950_000L,
                ),
            ),
        ).sweep()
        assertTrue(
            "no record match → at most the lost-record gate, never the kill (got $outcome)",
            outcome is TunnelOrphanSweep.Outcome.OrphanDetected,
        )
        // And a different-named binary riding the pid with the page-tunnel
        // shape fails the command-line leg too (basename must agree).
        val records2 = records()
        putSpawnRecord(records2, pid = 5002L, binary = "cloudflared", startUnixSeconds = 900_000L)
        val outcome2 = sweep(
            records2,
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 5002L,
                    commandLine = TunnelOrphanSweep.realizedCommandLine("E:\\other\\cloudflared-debug.exe"),
                    startUnixSeconds = 950_000L,
                ),
            ),
        ).sweep()
        assertTrue(outcome2 is TunnelOrphanSweep.Outcome.OrphanDetected)
    }

    @Test
    fun `a record-less older match surfaces the notice and is never auto-killed`() {
        val outcome = sweep(
            records = records(),
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 313L,
                    commandLine = "cloudflared $tail",
                    startUnixSeconds = 500_000L, // older than this app instance
                ),
            ),
        ).sweep()
        assertTrue(outcome is TunnelOrphanSweep.Outcome.OrphanDetected)
        assertEquals(313L, (outcome as TunnelOrphanSweep.Outcome.OrphanDetected).pid)
    }

    @Test
    fun `the live own child is skipped - the record store is consulted first`() {
        val records = records()
        putSpawnRecord(
            records,
            pid = 8080L,
            binary = "cloudflared",
            startUnixSeconds = 900_000L,
            instanceId = "current-instance",
        )
        val outcome = sweep(
            records,
            table = listOf(
                TunnelOrphanSweep.ProcessEntry(
                    pid = 8080L,
                    commandLine = TunnelOrphanSweep.realizedCommandLine("cloudflared"),
                    startUnixSeconds = 900_000L,
                ),
            ),
            ownLive = setOf(8080L),
        ).sweep()
        assertTrue("a live instance's healthy child is not an orphan", outcome is TunnelOrphanSweep.Outcome.Clear)
    }

    @Test
    fun `adoption lands the orphan under the same kill rules`() {
        val records = records()
        val entry = TunnelOrphanSweep.ProcessEntry(
            pid = 313L,
            commandLine = "cloudflared $tail",
            startUnixSeconds = 500_000L,
        )
        val sweep = sweep(records, table = listOf(entry))
        sweep.adopt(entry)
        val record = records.all().single()
        assertEquals(313L, record.pid)
        assertEquals("cloudflared $tail", record.commandLine)
        assertEquals(500_000L, record.startUnixSeconds)
        // The adopted record now PAIRS with the table: a later sweep of
        // this install routes the child to the own-records kill (never
        // misroutes it back to the lost-record gate).
        val killed = mutableListOf<Long>()
        val outcome = sweep(records, table = listOf(entry), killed = killed).sweep()
        assertTrue(outcome is TunnelOrphanSweep.Outcome.OwnLeftoverKilled)
        assertEquals(listOf(313L), killed)
    }

    @Test
    fun `a sweep that dies mid-decision fails open and is logged`() {
        val logs = mutableListOf<String>()
        val sweep = TunnelOrphanSweep(
            records = records(),
            table = object : TunnelOrphanSweep.ProcessTable {
                override fun cloudflaredProcesses(): List<TunnelOrphanSweep.ProcessEntry> =
                    error("powershell probe died")
            },
            currentInstanceId = "current-instance",
            appStartUnixSeconds = 1_000_000L,
            isOwnLiveChild = { false },
            killProcess = { true },
            logWarning = { logs += it },
        )
        // Detection still fails open (a failed scan never blocks the page)...
        assertTrue(
            "a thrown scan still reports Clear",
            sweep.sweep() is TunnelOrphanSweep.Outcome.Clear,
        )
        // ...but never SILENTLY: the failure reaches the log seam, so the
        // gate stopping tripping is visible in the app log.
        assertEquals(1, logs.size)
        assertTrue(
            "the failure's message reaches the log (got: $logs)",
            logs.single().contains("powershell probe died"),
        )
    }
}
