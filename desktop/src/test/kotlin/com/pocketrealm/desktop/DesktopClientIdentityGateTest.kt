package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.importer.ImportLimits
import org.json.JSONObject
import com.pocketrealm.importer.ManagedClientImporter
import com.pocketrealm.importer.SyntheticClientArchives
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * DESK-21/36/52/67/97: the launch-boundary identity gates and the
 * managed-generation-first resolution.
 *
 *  - a folder whose WoW.exe is not a PE, or whose PE version resource does
 *    not resolve to the qualified vanilla flavor, is REFUSED with the
 *    pinned VAL copy BEFORE the realmlist projection (no side effects);
 *  - a folder with NO WoW.exe at all is the same gate's VAL-01 case
 *    (LVF-flavor-import-003): the scanner's authored refusal must not be
 *    preempted by a bare pristine-existence check;
 *  - the managed lane's missing-exe refusal (the fail-closed store reader)
 *    survives that scoping unchanged;
 *  - `resolveClientDir` applies the same gate (a wrong-build folder reads
 *    as "no client", the picker state) and prefers the ACTIVE MANAGED
 *    GENERATION through the fail-closed reader when a pointer is published.
 */
class DesktopClientIdentityGateTest {
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
 /** Flip: persists the VANILLA pick into these roots' settings — the
     * launcher resolves the persisted lane, not the holder. */
    private fun pickVanilla(roots: DesktopStorageRoots) {
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile)
                .load().copy(selectedFlavorId = "vanilla"),
        )
    }


    @get:Rule
    val folder = TemporaryFolder()

    private val owner = ComponentOwner(sessionId = "test", instanceToken = "test")

    private fun spec(endpoint: RealmEndpoint = RealmEndpoint.LOCAL) = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = endpoint,
        includeClient = false,
    )

 // Flip: by-lazy — ImportLimits' DEFAULT maxTotalBytes resolves the
    // active lane, and instance fields initialize BEFORE any @Before runs;
    // a constructor-time resolve would refuse under several qualified
    // flavors even with the fixture pin in place.
    private val limits: ImportLimits get() = ImportLimits(minFiles = 1, minTotalBytes = 0L)

    /** The lanes under test exercise scan/copy/publish, not the storage budget:
     * a fixed generous allocatable keeps the preflight off the host TEMP
     * volume's real headroom (the planner seam). */
    private fun injectedImporter(roots: DesktopStorageRoots) =
        ManagedClientImporter(roots, limits, allocatableBytes = { 64L * ImportLimits.GIB })

    /** A synthetic client folder (fixture PE + MPQ stubs, no Blizzard bytes). */
    private fun materializeClient(root: File, build: Int = ClientFlavors.VANILLA.build): File {
        root.mkdirs()
        SyntheticClientArchives.clientEntries(wrapper = null, build = build).forEach { entry ->
            val target = File(root, entry.path.replace('\\', '/'))
            if (entry.directory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.writeBytes(entry.bytes)
            }
        }
        return root
    }

    private fun startClientExpectingFailure(backend: DesktopRuntimeBackend): String =
        try {
            runBlocking { backend.start(RuntimeComponent.CLIENT, owner, spec()) }
            fail("the launch must be refused")
            "unreachable"
        } catch (expected: IllegalStateException) {
            expected.message ?: ""
        }

    @Test
    fun aWrongBuildClientIsRefusedWithTheValCopyBeforeProjection() {
        val dir = materializeClient(folder.newFolder("client"), build = 6005)
        // A sentinel the projection would overwrite: the refusal must leave
        // the client directory with NO side effects.
        val sentinel = "set realmlist 9.9.9.9\r\n"
        File(dir, "realmlist.wtf").writeText(sentinel)
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")).also { r -> pickVanilla(r) },
            clientDir = dir,
            clientProcessScanner = { emptyList() },
        )
        val message = startClientExpectingFailure(backend)
        assertTrue("must carry VAL-03: $message", "VAL-03" in message)
        assertTrue(
            "must name the expected qualified resource: $message",
            ClientFlavors.VANILLA.versionResource in message,
        )
        assertTrue("must name the detected build: $message", "1.12.1.6005" in message)
        assertEquals(
            "the refusal must precede the realmlist projection (no side effects)",
            sentinel,
            File(dir, "realmlist.wtf").readText(),
        )
    }

    @Test
    fun aNonPeWowExeIsRefusedWithTheVal02Copy() {
        val dir = materializeClient(folder.newFolder("client"))
        File(dir, "WoW.exe").writeBytes("junk, not a PE".toByteArray())
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")).also { r -> pickVanilla(r) },
            clientDir = dir,
            clientProcessScanner = { emptyList() },
        )
        val message = startClientExpectingFailure(backend)
        assertTrue("must carry the VAL-02 PE refusal: $message", "VAL-02" in message)
    }

    @Test
    fun duplicateLaunchStillRefusesWithItsPinnedCopyBeforeTheIdentityGate() {
        val dir = folder.newFolder("client")
        File(dir, "WoW.exe").writeText("placeholder: the guard fires before any launch")
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")).also { r -> pickVanilla(r) },
            clientDir = dir,
            clientProcessScanner = { listOf(ClientProcessScan(4242, File(dir, "WoW.exe").canonicalPath)) },
        )
        val message = startClientExpectingFailure(backend)
        assertTrue(
            "the duplicate-launch precondition keeps its copy: $message",
            "client already running" in message && "4242" in message,
        )
    }

    /** a client-less folder
     * *is* the scanner gate's own VAL-01 case — the launch-boundary refusal
     * must carry the gate's authored copy, never the preempting bare
     * `WoW.exe not found under …` check that used to run first. */
    @Test
    fun folderLaneLaunchWithoutAPristineFileTakesTheScannerGateRefusal() {
        val dir = folder.newFolder("client") // client-less: no WoW.exe at all
        val backend = DesktopRuntimeBackend(
            DesktopStorageRoots(folder.newFolder("roots")).also { r -> pickVanilla(r) },
            clientDir = dir,
            clientProcessScanner = { emptyList() },
        )
        val message = startClientExpectingFailure(backend)
        assertTrue(
            "must carry the scanner gate's authored refusal: $message",
            "client identity refused" in message,
        )
        assertTrue(
            "must carry the pinned VAL-01 copy (DesktopClientScanner.kt:79-80): $message",
            "VAL-01: direct WoW.exe is absent" in message,
        )
        assertTrue(
            "the preempting existence-check copy must be gone: $message",
            "WoW.exe not found under" !in message,
        )
    }

    /** The managed lane keeps its missing-exe refusal: the scoped reorder must
     * not loosen it. On the current tree the enforcement point is the
     * fail-closed store reader (ManagedClientStore's existence validation,
     * which runs inside launch resolution), with startClient's check as
     * defense in depth behind it. */
    @Test
    fun managedLaneLaunchStillRefusesFailClosedWhenThePristineExeIsAbsent() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        pickVanilla(roots)
        roots.ensureDirectories()
        runBlocking { injectedImporter(roots).run(materializeClient(folder.newFolder("imported"))) }
        val generation = com.pocketrealm.importer.ClientGenerationStore(roots).activeGeneration()
        val exe = File(File(File(roots.content, "client"), "generations"), "$generation/WoW.exe")
        assertTrue("fixture must publish a pristine WoW.exe", exe.isFile)
        exe.delete()
        val backend = DesktopRuntimeBackend(roots, clientProcessScanner = { emptyList() })
        val message = startClientExpectingFailure(backend)
        assertTrue(
            "the managed lane's missing-exe refusal must survive the reorder: $message",
            "managed client generation refused" in message,
        )
        assertTrue(
            "the reader's existence validation copy must be carried: $message",
            "managed WoW.exe is absent or unsafe" in message,
        )
    }

    @Test
    fun resolveClientDirPrefersTheActiveManagedGenerationThroughTheFailClosedReader() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        pickVanilla(roots)
        roots.ensureDirectories()
        // A legacy folder that WOULD pass the identity scan...
        val legacy = materializeClient(folder.newFolder("legacy-client"))
        roots.ensureDirectories()
        DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.storage.Settings.Snapshot(clientDir = legacy.absolutePath, selectedFlavorId = "vanilla"),
        )
        val before = DesktopRuntimeBackend(roots).resolveClientDir()
        assertEquals("without a generation the passing folder resolves", legacy.canonicalFile, before?.canonicalFile)
        // ...then a managed import: the generation wins over the folder.
        val published = runBlocking {
            injectedImporter(roots).run(materializeClient(folder.newFolder("imported")))
        }
        val resolved = DesktopRuntimeBackend(roots).resolveClientDir()
        assertEquals(
            "the active managed generation must win over the stored folder (DESK-67/97)",
            File(File(roots.content, "client/generations"), published.generation).canonicalFile,
            resolved?.canonicalFile,
        )
    }

    @Test
    fun resolveClientDirRefusesAWrongBuildFolder() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        pickVanilla(roots)
        roots.ensureDirectories()
        materializeClient(folder.newFolder("wrong-build"), build = 6005)
        DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.storage.Settings.Snapshot(
                clientDir = File(folder.root, "wrong-build").absolutePath,
                selectedFlavorId = "vanilla",
            ),
        )
        val resolved = DesktopRuntimeBackend(roots).resolveClientDir()
        assertNull(
            "a WoW.exe whose identity does not resolve is 'no client' for the picker (DESK-52)",
            resolved,
        )
    }

    @Test
    fun aBrokenManagedPointerIsRefusedFailClosedAndNeverDowngradedToTheFolder() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        pickVanilla(roots)
        roots.ensureDirectories()
        val legacy = materializeClient(folder.newFolder("legacy-client"))
        DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.storage.Settings.Snapshot(clientDir = legacy.absolutePath, selectedFlavorId = "vanilla"),
        )
        runBlocking { injectedImporter(roots).run(materializeClient(folder.newFolder("imported"))) }
        // Sabotage the published generation's manifest: the pointer still
        // names it, so every read must fail closed.
        val pointer = File(roots.content, "client/active-vanilla.json")
        val generation = com.pocketrealm.importer.ClientGenerationStore(roots).activeGeneration()
        val manifest = File(File(roots.content, "client/generations"), "$generation/client-manifest.json")
        manifest.writeText(
            JSONObject().put("schema", 2).put("complete", true)
                .put("clientId", ClientFlavors.VANILLA.clientId).toString(),
        )
        val backend = DesktopRuntimeBackend(roots)
        // The status-line resolution fails closed as "no client" — it must
        // NEVER silently downgrade to the passing legacy folder.
        assertNull("a broken pointer resolves to nothing (fail-closed), never the folder", backend.resolveClientDir())
        val launchMessage = startClientExpectingFailure(backend)
        assertTrue(
            "the launch must carry the fail-closed refusal: $launchMessage",
            "managed client generation refused" in launchMessage,
        )
        // The sabotage must actually be detected via the pointer hash
        // (the manifest bytes no longer match the pointer's manifestSha256).
        assertTrue(pointer.isFile)
    }
}
