package com.pocketrealm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2-3/R3-1 (code-red round 1): the probe's loopback belt must cover EVERY
 * realmlist.wtf a client can consult (root + every Data/<locale>/ pack +
 * WTF) and must FAIL CLOSED when none exists — the old first-candidate
 * rewrite left a Truewow repack's Data/enUS realmlist pointing at its live
 * server and silently skipped clients with no candidate at all (fail-open
 * on a network-surface control).
 */
class FlavorLoginProbeRealmlistTest {

    private fun newTree(): File = java.nio.file.Files.createTempDirectory("probe-realmlist").toFile()

    @Test fun `root and locale realmlists all get rewritten`() {
        val tree = newTree()
        try {
            File(tree, "realmlist.wtf").writeText("set realmlist us.logon.worldofwarcraft.com\n")
            File(tree, "Data/enUS").mkdirs()
            File(tree, "Data/enUS/realmlist.wtf").writeText("set realmlist live.example.com\n")
            File(tree, "Data/enGB").mkdirs()
            File(tree, "Data/enGB/realmlist.wtf").writeText("set realmlist eu.example.com\n")

            val notes = aimRealmListsAtLoopback(tree)

            // Every candidate is covered — including the non-enUS locale.
            assertEquals(3, notes.size)
            listOf(
                "realmlist.wtf",
                "Data/enUS/realmlist.wtf",
                "Data/enGB/realmlist.wtf",
            ).forEach { rel ->
                val content = File(tree, rel).readText()
                assertEquals("set realmlist 127.0.0.1\n", content)
                assertTrue(
                    "$rel missing from the evidence notes: $notes",
                    notes.any { it.replace("\\", "/").contains(rel) && "rewritten" in it },
                )
            }
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test fun `a client dir with no realmlist fails closed naming the dir`() {
        val tree = newTree()
        try {
            File(tree, "WoW.exe").writeText("not really a client")
            val failure = assertThrows(IllegalStateException::class.java) {
                aimRealmListsAtLoopback(tree)
            }
            val message = failure.message!!
            assertTrue(
                "the refusal must name the client dir: $message",
                tree.absolutePath in message,
            )
            assertTrue(
                "the refusal must carry the no-live-dial invariant: $message",
                "must never dial a live server" in message,
            )
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test fun `already-loopback files are idempotently rewritten`() {
        val tree = newTree()
        try {
            File(tree, "realmlist.wtf").writeText("set realmlist 127.0.0.1\n")
            val notes = aimRealmListsAtLoopback(tree)
            assertEquals(1, notes.size)
            assertTrue("rewritten to loopback" in notes.single())
            assertEquals("set realmlist 127.0.0.1\n", File(tree, "realmlist.wtf").readText())
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test fun `a comment mentioning the loopback IP does not skip the rewrite`() {
        val tree = newTree()
        try {
            File(tree, "realmlist.wtf").writeText(
                "## verified against 127.0.0.1\nset realmlist logon.example.org\n",
            )
            aimRealmListsAtLoopback(tree)
            assertEquals(
                "set realmlist 127.0.0.1\n",
                File(tree, "realmlist.wtf").readText(),
            )
        } finally {
            tree.deleteRecursively()
        }
    }
}
