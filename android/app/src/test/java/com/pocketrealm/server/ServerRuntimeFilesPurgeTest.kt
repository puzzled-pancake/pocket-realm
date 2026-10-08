package com.pocketrealm.server

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Android twin of DesktopServerRuntimeFilesTest's stale-secret purge
 * pin (worldStartPurgesStaleKeysFromInactivePresetConfs). The android
 * ServerRuntimeFiles copy is NOT in shared-sources.json, so the F3 fix —
 * a rotated or cleared external LLM key must not survive forever in every
 * OTHER preset's staged conf — carries its own pins in this unit set.
 * Pure JVM: the purge core is the pure companion seam
 * ([ServerRuntimeFiles.purgeStaleBotConfSecrets]) the instance lane feeds
 * ([ServerRuntimeFiles.worldConfig] runs it before staging the active
 * profile's conf), so no app context is required — the same discipline as
 * [ServerRuntimeFilesRotationTest].
 */
class ServerRuntimeFilesPurgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun purgeEvictsKeyedBotConfsAndKeepsKeylessBytes() {
        val run = folder.newFolder("run")
        // An INACTIVE preset's conf staged by an earlier world start under
        // the since-rotated key — the exact F3 leftover.
        val staleApiKey = File(run, "aiplayerbot-preset-lively-b160-v1.conf").apply {
            writeText(
                "AiPlayerbot.Enabled = 1\n" +
                    "            AiPlayerbot.LLMApiKey = sk-old\n",
            )
        }
        // A chatter-lane conf keyed only through the composer key line.
        val staleComposerKey = File(run, "aiplayerbot-preset-busy-w240-v1.conf").apply {
            writeText(
                "AiPlayerbot.Enabled = 1\n" +
                    "            AiPlayerbot.LLMChatterComposerKey = sk-composer\n",
            )
        }
        // Keyless bytes that must survive: the disabled block and a
        // fail-closed profile conf (an invalid key emits no LLM block).
        val disabled = File(run, "aiplayerbot-disabled.conf").apply {
            writeText("AiPlayerbot.Enabled = 0\nAiPlayerbot.LLMEnabled = 0\n")
        }
        val failClosed = File(run, "aiplayerbot-preset-low-p80-v1.conf").apply {
            writeText("AiPlayerbot.Enabled = 1\n")
        }
        // Non-bot staged files sharing the run dir.
        val mangosd = File(run, "mangosd.conf").apply { writeText("DataDir = \"x\"\n") }
        val lore = File(run, "lore_cards_v112.jsonl").apply { writeText("{}\n") }
        // A DIRECTORY wearing the conf name must be skipped, not purged.
        val trap = File(run, "aiplayerbot-preset-trap.conf").apply { mkdirs() }
        File(trap, "nested").writeText("bytes")

        ServerRuntimeFiles.purgeStaleBotConfSecrets(run)

        assertFalse("the stale LLMApiKey conf must be evicted", staleApiKey.isFile)
        assertFalse("the stale LLMChatterComposerKey conf must be evicted", staleComposerKey.isFile)
        assertTrue("the keyless disabled block keeps its bytes", disabled.isFile)
        assertTrue("a keyless profile conf keeps its bytes", failClosed.isFile)
        assertTrue("mangosd.conf is not the purge's business", mangosd.isFile)
        assertTrue("the staged lore index is not the purge's business", lore.isFile)
        assertTrue("a directory named like a conf is skipped", trap.isDirectory)
        assertTrue("the skipped directory's bytes survive", File(trap, "nested").isFile)
    }

    /**
     * The purge runs BEFORE the same world start re-stages the ACTIVE
     * profile's conf, so the active keyed conf is deleted too — matching
     * the desktop twin's semantics exactly: only the current key, written
     * fresh by the immediately following secureWrite, ever lands.
     */
    @Test
    fun purgeDeletesTheActiveConfTooBecauseTheStartRestagesIt() {
        val run = folder.newFolder("run")
        val active = File(run, "aiplayerbot-preset-lively-b160-v1.conf").apply {
            writeText("AiPlayerbot.Enabled = 1\n            AiPlayerbot.LLMApiKey = sk-old\n")
        }
        ServerRuntimeFiles.purgeStaleBotConfSecrets(run)
        assertFalse(
            "no keyed conf may survive the purge — the re-stage rewrites it",
            active.isFile,
        )
    }

    /**
     * Fail-open per file (the staging posture): the locked-file leg holds
     * a plain FileInputStream — on Windows a file open without
     * FILE_SHARE_DELETE refuses the delete (the rotation pins' idiom) —
     * and the purge must swallow that refusal, keep the locked bytes for
     * the next start, and still evict every other keyed conf.
     */
    @Test
    fun lockedStaleConfFailsOpenAndTheRestStillPurge() {
        val run = folder.newFolder("run")
        val locked = File(run, "aiplayerbot-preset-alive-r320-v1.conf").apply {
            writeText("AiPlayerbot.LLMApiKey = sk-old\n")
        }
        val evictable = File(run, "aiplayerbot-preset-busy-w240-v1.conf").apply {
            writeText("AiPlayerbot.LLMChatterComposerKey = sk-old\n")
        }
        java.io.FileInputStream(locked).use { _ ->
            // Must not throw: a locked file may never refuse a world start.
            ServerRuntimeFiles.purgeStaleBotConfSecrets(run)
        }
        assertTrue("the locked conf is left for the next start", locked.isFile)
        assertTrue(
            "the locked conf keeps its bytes for forensics, not for serving",
            "sk-old" in locked.readText(),
        )
        assertFalse("the other keyed conf is still evicted", evictable.isFile)
    }
}
