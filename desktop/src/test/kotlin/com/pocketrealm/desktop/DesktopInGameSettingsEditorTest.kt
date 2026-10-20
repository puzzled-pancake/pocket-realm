package com.pocketrealm.desktop

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.ingame.BindingsFileCodec
import com.pocketrealm.ingame.ConfigWtfCodec
import com.pocketrealm.ingame.DesktopConfigWtf
import com.pocketrealm.ingame.DesktopInGameSettingsEditor
import com.pocketrealm.ingame.InGameSettingsEditLock
import com.pocketrealm.ingame.InGameSettingsFiles
import com.pocketrealm.ingame.SavedVariablesCodec
import com.pocketrealm.ingame.WowSettingBackend
import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.After
import org.junit.Before

/**
 * DESK-12/22/41/59/85/96: the desktop in-game settings editor — direct WTF
 * edits while the client is stopped, queue-while-running with the drain on
 * a clean stop, over the shared codecs and the shared, pinned stock
 * catalogs. The lock file is exercised for contract parity (on the desktop
 * it degenerates to an in-process mutex).
 */
class DesktopInGameSettingsEditorTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @get:Rule
    val folder = TemporaryFolder()

    private var clientRunning = false

    private fun syntheticClientRoot(folderName: String = "client"): File {
        val root = folder.newFolder(folderName)
        // A real Config.wtf line through the shared codec (format-agnostic).
        val config = InGameSettingsFiles.configFile(root)
        config.parentFile.mkdirs()
        InGameSettingsFiles.writeAtomic(
            config,
            ConfigWtfCodec.merge(null, emptyList(), listOf(ConfigWtfCodec.UserOverride("farclip", "500.000000"))).text,
        )
        // An account uvar file and an account bindings file.
        val accountDir = InGameSettingsFiles.accountDirectory(root, TEST_ACCOUNT)
        accountDir.mkdirs()
        val uvars = InGameSettingsFiles.accountSavedVariables(root, TEST_ACCOUNT)
        InGameSettingsFiles.writeAtomic(uvars, "uvarVolume = \"0.8\"\n")
        val bindings = InGameSettingsFiles.accountBindings(root, TEST_ACCOUNT)
        InGameSettingsFiles.writeAtomic(bindings, BindingsFileCodec.assign("", "TAB", TARGET_COMMAND))
        return root
    }

    /** The launch conditions the drain resolves (DESK-50's overlay input). */
    private fun conditions(audioMode: String = "on"): DesktopConfigWtf.DesktopLaunchConditions =
        DesktopConfigWtf.conditions(
            audioMode = audioMode,
            endpoint = RealmEndpoint.LOCAL,
            effectiveTweaks = ClientTweaksConfig(),
        )

    private fun editor(root: File, audioMode: String = "on"): DesktopInGameSettingsEditor =
        DesktopInGameSettingsEditor(
            clientRoot = { root },
            queueFile = File(folder.newFolder("runtime"), "ingame-settings/queue.json"),
            clientRunning = { clientRunning },
            launchConditions = { conditions(audioMode) },
        )

    @Test
    fun `direct cvar edit writes the WTF tree while the client is stopped`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")
        assertNotNull(farclip)
        val outcome = editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip!!, "600.000000"))
        assertTrue("expected the direct lane: $outcome", outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        assertEquals(
            DesktopInGameSettingsEditor.EditLane.APPLIED_DIRECT,
            (outcome as DesktopInGameSettingsEditor.EditOutcome.Landed).lane,
        )
        assertEquals("600.000000", ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"])
        // The edit lock was taken and left its file behind (contract parity).
        assertTrue(File(root, InGameSettingsEditLock.LOCK_FILE_NAME).isFile)
        // No queue leaked for a direct write.
        assertEquals(0, editor.queuedCount())
    }

    @Test
    fun `direct binding edit reassigns and clears the old key`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        val outcome = editor.apply(
            DesktopInGameSettingsEditor.Edit.Binding(TARGET_COMMAND, TEST_ACCOUNT, "CTRL-F2", null),
        )
        assertTrue(outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        val parsed = BindingsFileCodec.parse(
            InGameSettingsFiles.accountBindings(root, TEST_ACCOUNT).readText(),
        )
        assertEquals(TARGET_COMMAND, parsed["CTRL-F2"])
        assertFalse("the old TAB binding must have been released", parsed.containsKey("TAB"))
    }

    @Test
    fun `uvar edit lands in the account saved variables`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        val uvarDefinition = WowVanillaSettingsCatalog.userEditable
            .first { it.backend == WowSettingBackend.UVAR }
        val outcome = editor.apply(
            DesktopInGameSettingsEditor.Edit.UvarSetting(uvarDefinition, TEST_ACCOUNT, "9"),
        )
        assertTrue("expected the direct lane: $outcome", outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        val values = SavedVariablesCodec.parse(
            InGameSettingsFiles.accountSavedVariables(root, TEST_ACCOUNT).readText(),
        )
        assertEquals(
            "9",
            values[uvarDefinition.key]?.let { value ->
                when (value) {
                    is SavedVariablesCodec.Value.Str -> value.raw
                    is SavedVariablesCodec.Value.Num -> value.raw
                    else -> null
                }
            },
        )
    }

    @Test
    fun `edits while the client runs queue and drain on a clean stop`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        clientRunning = true
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        val outcome = editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "700.000000"))
        assertTrue(outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        assertEquals(
            DesktopInGameSettingsEditor.EditLane.QUEUED_FOR_CLEAN_STOP,
            (outcome as DesktopInGameSettingsEditor.EditOutcome.Landed).lane,
        )
        assertEquals(1, editor.queuedCount())
        // The file is untouched while the client runs.
        assertEquals("500.000000", ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"])

        // The clean stop: the drain applies the queue and clears it.
        clientRunning = false
        val summary = editor.applyQueuedOnCleanStop()
        assertTrue("drain must report the applied change: $summary", "1" in summary)
        assertEquals(0, editor.queuedCount())
        assertEquals("700.000000", ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"])

        // Apply-once: a second drain is a no-op.
        assertEquals(0, editor.queuedCount())
    }

    @Test
    fun `queue survives a restart of the editor object`() {
        val root = syntheticClientRoot()
        val queueFile = File(folder.newFolder("runtime2"), "ingame-settings/queue.json")
        val first = DesktopInGameSettingsEditor({ root }, queueFile, { true }) { conditions() }
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        first.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "650.000000"))
        first.apply(
            DesktopInGameSettingsEditor.Edit.Binding(TARGET_COMMAND, TEST_ACCOUNT, "F8", null),
        )
        assertEquals(2, first.queuedCount())

        // A fresh editor over the same queue file (app restart) drains too.
        val second = DesktopInGameSettingsEditor({ root }, queueFile, { false }) { conditions() }
        second.applyQueuedOnCleanStop()
        assertEquals(0, second.queuedCount())
        assertEquals("650.000000", ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"])
        assertEquals(TARGET_COMMAND, BindingsFileCodec.parse(
            InGameSettingsFiles.accountBindings(root, TEST_ACCOUNT).readText(),
        )["F8"])
    }

    @Test
    fun `client root re-resolves per operation - fresh install configures the client later`() {
        // The app-model lazy builds the editor once per process; on a fresh
        // install that happens (Route.InGameSettings) before any client is
        // configured, so the seam must consult the supplier, not a snapshot.
        var resolved: File? = null
        val editor = DesktopInGameSettingsEditor(
            clientRoot = { resolved },
            queueFile = File(folder.newFolder("runtime-fresh"), "ingame-settings/queue.json"),
            clientRunning = { clientRunning },
            launchConditions = { conditions() },
        )
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        // No client yet: the same honest refusal the screen would surface...
        assertEquals(
            DesktopInGameSettingsEditor.EditOutcome.Refused("no client folder is configured"),
            editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "600.000000")),
        )
        assertNull(editor.readConfigValues())

        // ...then the client resolves — the SAME editor object, no rebuild —
        // and the edit lands in the freshly configured WTF tree.
        resolved = syntheticClientRoot()
        val outcome = editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "600.000000"))
        assertTrue("expected the direct lane after the client resolved: $outcome", outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        assertEquals(
            "600.000000",
            ConfigWtfCodec.parse(InGameSettingsFiles.configFile(resolved!!).readText())["farclip"],
        )
        assertEquals("600.000000", editor.readConfigValues()?.get("farclip"))
    }

    @Test
    fun `queued edit drains into the re-imported generation without resurrecting the retired one`() {
        val retiredRoot = syntheticClientRoot("client-old")
        var resolved: File? = retiredRoot
        val editor = DesktopInGameSettingsEditor(
            clientRoot = { resolved },
            queueFile = File(folder.newFolder("runtime-reimport"), "ingame-settings/queue.json"),
            clientRunning = { clientRunning },
            launchConditions = { conditions() },
        )
        clientRunning = true
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        val outcome = editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "700.000000"))
        assertTrue(outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        assertEquals(1, editor.queuedCount())

        // A re-import retires (and prunes) the old generation; the active
        // client moves to the new one mid-process.
        val activeRoot = syntheticClientRoot("client-new")
        resolved = activeRoot
        retiredRoot.deleteRecursively()

        clientRunning = false
        val summary = editor.applyQueuedOnCleanStop()
        assertTrue("drain must report the applied change: $summary", summary.contains("applied 1"))
        assertEquals(0, editor.queuedCount())
        assertEquals(
            "700.000000",
            ConfigWtfCodec.parse(InGameSettingsFiles.configFile(activeRoot).readText())["farclip"],
        )
        // Nothing may resurrect under the retired path: the drain (and its
        // edit-lock acquisition) must never touch the pruned generation.
        assertFalse("the retired generation must stay pruned", retiredRoot.exists())
    }

    @Test
    fun `discard queue clears pending changes`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        clientRunning = true
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(farclip, "480.000000"))
        assertEquals(1, editor.queuedCount())
        editor.discardQueue()
        assertEquals(0, editor.queuedCount())
        clientRunning = false
        assertTrue(editor.applyQueuedOnCleanStop().isNotEmpty())
        assertEquals("500.000000", ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"])
    }

    @Test
    fun `missing binding scope refuses honestly`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        val outcome = editor.apply(
            DesktopInGameSettingsEditor.Edit.Binding(TARGET_COMMAND, "NO_SUCH_ACCOUNT", "TAB", null),
        )
        assertTrue("expected refusal: $outcome", outcome is DesktopInGameSettingsEditor.EditOutcome.Refused)
        assertTrue(
            (outcome as DesktopInGameSettingsEditor.EditOutcome.Refused).reason.contains("log in"),
        )
    }

    @Test
    fun `reads expose the synthetic WTF tree`() {
        val root = syntheticClientRoot()
        val editor = editor(root)
        assertEquals(listOf(TEST_ACCOUNT), editor.uvarAccounts())
        // The shared scanner derives a 4-name scope from the account-level
        // bindings file as well (the Android-pinned behavior); the UI reads
        // and writes each scope string verbatim, so both rows are honest.
        assertEquals(
            listOf(TEST_ACCOUNT to "Account", "WTF/Account/TESTACCT" to "Character"),
            editor.bindingScopes(),
        )
        assertEquals("500.000000", editor.readConfigValues()?.get("farclip"))
        assertEquals(TARGET_COMMAND, editor.readBindings(TEST_ACCOUNT)?.get("TAB"))
    }

    @Test
    fun `queued master sound edit is blocked at drain while the audio-off overlay owns the key`() {
        val root = syntheticClientRoot()
        val editor = editor(root, audioMode = "off")
        clientRunning = true
        val master = WowVanillaSettingsCatalog.byId("sound.master")!!
        val outcome = editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(master, "1"))
        assertTrue("expected the queue lane: $outcome", outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        // The classification is the DESK-50 overlay's own decision: blocked
        // under the audio-off launch, plain user-owned under audio-on.
        assertEquals(setOf("sound.master"), editor.blockedQueueKeys(conditions("off")))
        assertEquals(emptySet<String>(), editor.blockedQueueKeys(conditions("on")))

        clientRunning = false
        val summary = editor.applyQueuedOnCleanStop()
        // The queued-changes status surfaces the blocked key...
        assertTrue("drain must surface the blocked key: $summary", summary.contains("blocked"))
        assertTrue("drain must name the blocked key: $summary", summary.contains("sound.master"))
        // ...the WTF tree stays untouched for the enforced key (the next
        // launch's DesktopConfigWtf.enforceAtLaunch would stomp it anyway)...
        assertNull(
            ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["MasterSoundEffects"],
        )
        // ...and the queue drained.
        assertEquals(0, editor.queuedCount())
    }

    @Test
    fun `queued master sound edit delivers at drain while audio is on`() {
        val root = syntheticClientRoot()
        val editor = editor(root, audioMode = "on")
        clientRunning = true
        val master = WowVanillaSettingsCatalog.byId("sound.master")!!
        editor.apply(DesktopInGameSettingsEditor.Edit.CvarSetting(master, "1"))
        clientRunning = false
        val summary = editor.applyQueuedOnCleanStop()
        assertTrue("drain must apply the change: $summary", summary.contains("applied 1"))
        // Audio ON: the master CVar is user-owned, the edit lands.
        assertEquals(
            "1",
            ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["MasterSoundEffects"],
        )
    }

    @Test
    fun `corrupt queue file logs a warning and resets honestly`() {
        val root = syntheticClientRoot()
        val runtime = folder.newFolder("runtime-corrupt")
        val queueFile = File(runtime, "ingame-settings/queue.json")
        queueFile.parentFile?.mkdirs()
        queueFile.writeText("not json { at all")
        val editor = DesktopInGameSettingsEditor({ root }, queueFile, { false }) { conditions() }
        val before = DesktopLog.snapshot().size
        assertEquals(0, editor.queuedCount())
        val warned = DesktopLog.snapshot().drop(before)
            .filter { it.level == "WARN" && it.message.contains("unreadable") }
        assertTrue("the parse failure must be logged, not swallowed: ${DesktopLog.snapshot()}", warned.isNotEmpty())
    }

    @Test
    fun `corrupt queue section alone keeps the delivered record`() {
        val root = syntheticClientRoot()
        val runtime = folder.newFolder("runtime-queuejunk")
        val queueFile = File(runtime, "ingame-settings/queue.json")
        queueFile.parentFile?.mkdirs()
        // A hand-mangled queue section, but a perfectly readable envelope and
        // delivered record: farclip was already delivered at revision 5.
        queueFile.writeText(
            JSONObject()
                .put("schema", 1)
                .put("revision", 0)
                .put("queue", "{not json at all")
                .put("directEditJournal", JSONObject())
                .put(
                    "delivered",
                    JSONArray().put(
                        JSONObject()
                            .put("key", "graphics.terrainDistance")
                            .put("scope", "config")
                            .put("value", "650.000000")
                            .put("revision", 5),
                    ),
                )
                .toString(),
        )
        val editor = DesktopInGameSettingsEditor({ root }, queueFile, { clientRunning }) { conditions() }
        // The junk queue section reads as empty (lenient section codec)...
        assertEquals(0, editor.queuedCount())
        // ...so a freshly staged edit (revision 1) does NOT outrank the
        // surviving delivery record (revision 5): the drain must keep the
        // file's live value, not re-deliver over it.
        clientRunning = true
        editor.apply(
            DesktopInGameSettingsEditor.Edit.CvarSetting(
                WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!,
                "650.000000",
            ),
        )
        clientRunning = false
        val summary = editor.applyQueuedOnCleanStop()
        assertFalse("the stale edit must not re-deliver: $summary", summary.contains("applied 1"))
        assertEquals(
            "500.000000",
            ConfigWtfCodec.parse(InGameSettingsFiles.configFile(root).readText())["farclip"],
        )
    }

    private companion object {
        const val TEST_ACCOUNT = "TESTACCT"
        const val TARGET_COMMAND = "TARGETNEARESTENEMY"
    }

    @Test
    fun `a carried vanilla uvar queue degrades cleanly on a non-vanilla lane - R2-5`() {
        // shaped: tbc qualified + selected while the queue was staged on
        // vanilla. The drain resolves the ACTIVE flavor's catalog — the
        // vanilla uvar ids resolve null there and are BLOCKED (retained),
        // never a mid-drain crash from the tbc layout's fail-loud backstop.
        ActiveFlavor.reset()
        // The tree is built and the uvar entry STAGED on the vanilla lane
        // (the carried state) — then the lane flips to tbc and the drain
        // runs there.
        val root = syntheticClientRootVanillaShape()
        val editor = editor(root)
        clientRunning = true
        val uvar = WowVanillaSettingsCatalog.byId("interface.instantQuestText")!!
        assertEquals(WowSettingBackend.UVAR, uvar.backend)
        val outcome = editor.apply(
            DesktopInGameSettingsEditor.Edit.UvarSetting(uvar, TEST_ACCOUNT, "1"),
        )
        assertTrue(outcome is DesktopInGameSettingsEditor.EditOutcome.Landed)
        ActiveFlavor.qualifiedSource = {
            listOf(
                com.pocketrealm.client.ClientFlavors.VANILLA,
                com.pocketrealm.client.ClientFlavors.TBC.copy(qualified = true),
            )
        }
        ActiveFlavor.select("tbc")
        try {
            clientRunning = false
            // The drain on the tbc lane: no throw; the queue entry is
            // blocked and retained (never delivered against a lane that
            // has no uvar route).
            val summary = editor.applyQueuedOnCleanStop()
            assertTrue(summary.isNotEmpty())
            assertTrue(
                "the blocked uvar entry must be retained: $summary",
                editor.queuedCount() > 0 || "blocked" in summary,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    /** A vanilla-shaped client tree built through the VANILLA layout (the
     * carried state the queue was staged against). */
    private fun syntheticClientRootVanillaShape(): File {
 // Flip: the layout routes resolve the ACTIVE flavor — this helper
        // builds the VANILLA shape, so it pins the vanilla lane itself (the
        // calling test's finally may have reset the holder).
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
        val root = folder.newFolder("client-vanilla-shape")
        val config = InGameSettingsFiles.configFile(root)
        config.parentFile.mkdirs()
        InGameSettingsFiles.writeAtomic(config, "")
        val accountDir = InGameSettingsFiles.accountDirectory(root, TEST_ACCOUNT)
        accountDir.mkdirs()
        val uvars = InGameSettingsFiles.accountSavedVariables(root, TEST_ACCOUNT)
        InGameSettingsFiles.writeAtomic(uvars, "questFadingDisable = \"1\"\n")
        return root
    }
}
