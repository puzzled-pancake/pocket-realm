package com.pocketrealm.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the client-crash evidence scan — the client's own Errors/
 * crash texts (5-row cap, newest first) and the WER LocalDumps minidumps,
 * both best effort.
 */
class DesktopCrashEvidenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun clientCrashRowsAreNewestFirstAndCappedAtFive() {
        val clientDir = folder.newFolder("client")
        val errors = File(clientDir, "Errors").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (index in 1..7) {
            val file = File(errors, "Errors$index.log").apply { writeText("crash text $index") }
            assertTrue(file.setLastModified(now - index * 60_000L))
        }
        val evidence = DesktopCrashEvidence(
            localDumpsDir = folder.newFolder("dumps"),
            eventLog = { "" },
        )
        val rows = evidence.clientCrashRows(clientDir)
        assertEquals("5-row cap mirrors the Android card", 5, rows.size)
        assertEquals(
            (1..5).map { index -> "Errors/Errors$index.log" },
            rows,
        )
        assertEquals(
            "the file lane caps identically",
            evidence.clientCrashFiles(clientDir).map { it.name },
            (1..5).map { index -> "Errors$index.log" },
        )
    }

    @Test
    fun noClientDirOrNoErrorsFolderYieldsNothing() {
        val evidence = DesktopCrashEvidence(
            localDumpsDir = folder.newFolder("dumps"),
            eventLog = { "" },
        )
        assertTrue(evidence.clientCrashRows(null).isEmpty())
        assertTrue(evidence.clientCrashRows(folder.newFolder("bare-client")).isEmpty())
    }

    @Test
    fun werDumpsKeepOnlyTheGameClientsMinidumps() {
        val dumps = folder.newFolder("dumps")
        File(dumps, "WoW.exe.1234.dmp").writeText("dump")
        File(dumps, "WoW.exe.5678.dmp").writeText("dump")
        File(dumps, "notepad.exe.999.dmp").writeText("dump")
        File(dumps, "WoW.exe.4242.dmp.txt").writeText("not a dump")
        val evidence = DesktopCrashEvidence(
            localDumpsDir = dumps,
            eventLog = { "" },
        )
        val files = evidence.werDumpFiles()
        assertEquals(listOf("WoW.exe.1234.dmp", "WoW.exe.5678.dmp"), files.map { it.name }.sorted())
        assertTrue(evidence.werDumpRows().first().endsWith("KiB)"))
    }

    @Test
    fun missingLocalDumpsDirIsAnHonestEmptyRead() {
        val evidence = DesktopCrashEvidence(
            localDumpsDir = File(folder.root, "never-created"),
            eventLog = { "" },
        )
        assertTrue(evidence.werDumpFiles().isEmpty())
    }

    @Test
    fun eventLogTextComesFromTheReadSeam() {
        val evidence = DesktopCrashEvidence(
            localDumpsDir = folder.newFolder("dumps"),
            eventLog = { limit -> "Faulting application name: WoW.exe (limit $limit)" },
        )
        assertEquals(
            "Faulting application name: WoW.exe (limit ${DesktopCrashEvidence.MAX_EVENT_LOG_EVENTS})",
            evidence.applicationErrorLog(),
        )
    }
}
