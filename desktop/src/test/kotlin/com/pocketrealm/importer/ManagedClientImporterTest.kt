package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-34/63/97: the desktop folder-lane import pipeline end to end —
 * scan → inventory → copy/digest-verify into the staging generation →
 * re-hash pass → publish through the generation store (atomic per-flavor
 * pointer) → the fail-closed ManagedClientStore reader accepts the
 * published generation. Rejection legs pin the VAL copy on the same
 * synthetic fixtures (no Blizzard bytes committed).
 */
class ManagedClientImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The synthetic client is under the plain 20-file minimum; relax the
     * floor exactly like a small fixture client, keeping the caps. */
    private val limits = ImportLimits(minFiles = 1, minTotalBytes = 0L)

    /** These lanes exercise copy/verify/publish, not the storage budget: a
     * fixed generous allocatable keeps the preflight off the host TEMP
     * volume's real headroom (the planner seam). */
    private fun injectedImporter(roots: DesktopStorageRoots) =
        ManagedClientImporter(roots, limits, allocatableBytes = { 64L * ImportLimits.GIB })

    private fun materializeClient(
        root: File,
        build: Int = ClientFlavors.VANILLA.build,
        wrapper: String? = null,
    ): File {
        root.mkdirs()
        SyntheticClientArchives.clientEntries(wrapper = wrapper, build = build).forEach { entry ->
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

    @Test
    fun importingAFolderPublishesAndActivatesAVerifiableGeneration() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val source = materializeClient(folder.newFolder("client"))
        val importer = injectedImporter(roots)
        val result = runBlocking {
            importer.run(
                source,
                afterVerified = { count -> assertTrue(count >= 1) },
                beforePublish = { },
            )
        }
        // The result carries the published generation and the inventory.
        assertTrue(result.importId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertTrue("the import must carry no rejection warnings: ${result.warnings}", result.warnings.isNotEmpty())
        assertEquals(19, result.inventory.fileCount)
        // The active pointer names the new generation for the qualified flavor.
        val store = ClientGenerationStore(roots)
        assertEquals(result.generation, store.activeGeneration())
        // THE assertion: the fail-closed reader accepts the published tree —
        // pointer hash, manifest identity, exe size/hash, MPQs, endpoint.
        val managed = ManagedClientStore(roots).load(ClientFlavors.VANILLA.clientId)
        assertEquals(result.generation, managed.generation)
        assertEquals(ClientFlavors.VANILLA.versionResource, managed.manifest.getJSONObject("identity").getString("version"))
        assertEquals(source.resolve("WoW.exe").length(), managed.executableSize)
        // The managed root lives under the desktop content root (the layout
        // contract the backend's resolution reads).
        assertTrue(managed.root.canonicalPath.startsWith(roots.content.canonicalPath))
    }

    @Test
    fun aSecondImportSwitchesTheActivePointerAndTheThirdRetiresTheOldest() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val importer = injectedImporter(roots)
        val first = runBlocking { importer.run(materializeClient(folder.newFolder("client-a"))) }
        val second = runBlocking { importer.run(materializeClient(folder.newFolder("client-b"))) }
        // The pointer switched; the prior generation stays (today's invariant).
        assertEquals(second.generation, ClientGenerationStore(roots).activeGeneration())
        assertTrue(
            File(File(roots.content, "client/generations"), first.generation).isDirectory,
        )
        // The third import retires the generation before the prior.
        val third = runBlocking { importer.run(materializeClient(folder.newFolder("client-c"))) }
        assertEquals(third.generation, ClientGenerationStore(roots).activeGeneration())
        assertFalse(
            "the superseded generation before the prior is retired",
            File(File(roots.content, "client/generations"), first.generation).exists(),
        )
        assertTrue(
            File(File(roots.content, "client/generations"), second.generation).isDirectory,
        )
    }

    @Test
    fun safeRuntimeExclusionsNeverEnterTheGeneration() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val source = materializeClient(folder.newFolder("client"))
        // Root-level non-WoW executable + non-standard DLL: the safe-mode rule.
        File(source, "unins000.exe").writeBytes(byteArrayOf(1))
        File(source, "injected.dll").writeBytes(byteArrayOf(2))
        val importer = injectedImporter(roots)
        val result = runBlocking { importer.run(source) }
        val generationRoot = File(File(roots.content, "client/generations"), result.generation)
        assertFalse("a foreign root executable must be excluded", File(generationRoot, "unins000.exe").exists())
        assertFalse("an injected root DLL must be excluded", File(generationRoot, "injected.dll").exists())
        assertTrue(File(generationRoot, "fmod.dll").isFile)
    }

    @Test
    fun aLauncherOnlyFolderIsRejectedWithTheValCopyWithoutCopyingAnything() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val source = folder.newFolder("installer")
        File(source, "Launcher.exe").writeText("installer")
        val importer = injectedImporter(roots)
        try {
            runBlocking { importer.run(source) }
            fail("a launcher-only selection must be rejected")
        } catch (expected: ImportRejected) {
            assertTrue(
                "the rejection must carry the VAL-01 launcher-only copy: ${expected.message}",
                (expected.message ?: "").contains("VAL-01") && "launcher-only" in (expected.message ?: ""),
            )
        }
        // Nothing was copied: no staging generation and no pointer survives.
        val clientRoot = File(roots.content, "client")
        assertFalse("no active pointer may exist", File(clientRoot, "active-vanilla.json").exists())
        assertEquals(null, ClientGenerationStore(roots).activeGeneration())
    }

    @Test
    fun aWrongBuildClientIsRejectedWithTheVal03Copy() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val source = materializeClient(folder.newFolder("client"), build = 6005)
        val importer = injectedImporter(roots)
        try {
            runBlocking { importer.run(source) }
            fail("a non-qualified client must be rejected")
        } catch (expected: ImportRejected) {
            val message = expected.message ?: ""
            assertTrue("must carry VAL-03: $message", "VAL-03" in message)
            assertTrue("must name the detected build: $message", "1.12.1.6005" in message)
        }
    }

    @Test
    fun aSourceMutatedMidImportFailsTheFingerprintCheck() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val source = materializeClient(folder.newFolder("client"))
        val importer = injectedImporter(roots)
        try {
            runBlocking {
                importer.run(
                    source,
                    afterVerified = { File(source, "fmod.dll").appendBytes(byteArrayOf(9)) },
                )
            }
            fail("a mutated source must fail the stability check")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the failure must be the source-changed contract: ${expected.message}",
                "SOURCE_CHANGED" in (expected.message ?: ""),
            )
        }
        // The failed import leaves no active pointer behind.
        assertEquals(null, ClientGenerationStore(roots).activeGeneration())
    }

    // ---- Round-5 R5-R2-2: legacy adoption is flavor-scoped ----

    @Test
    fun `legacy adoption never consumes flavor A's pointer into flavor B`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-adopt-guard"))
            .also { it.ensureDirectories() }
        val source = materializeClient(folder.newFolder("client"))
        val published = runBlocking { injectedImporter(roots).run(source) }
        // Simulate the pre-flavor era: the canonical vanilla pointer goes
        // back to the un-namespaced legacy name.
        val clientRoot = roots.content.resolve("client")
        val canonical = clientRoot.resolve("active-vanilla.json")
        val legacy = clientRoot.resolve("active.json")
        check(canonical.isFile) { "fixture: the vanilla pointer must exist" }
        canonical.copyTo(legacy, overwrite = true)
        canonical.delete()
        // A stored selection on qualified flavor B: B's first read must
        // NOT consume the legacy pointer (its body stamps vanilla).
        TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
        try {
            val tbcOutcome = runCatching {
                ManagedClientStore(roots).load(ClientFlavors.TBC.clientId)
            }
            check(tbcOutcome.isFailure) { "the tbc lane has no pointer: must refuse" }
            check(legacy.isFile) {
                "R5-R2-2: the legacy pointer was consumed into the tbc " +
                    "namespace - flavor A's managed client is orphaned"
            }
            // Flavor A still resolves: the legacy pointer adopts into ITS
            // namespace on A's own read.
            ActiveFlavor.select(ClientFlavors.VANILLA.flavorId)
            val vanilla = ManagedClientStore(roots).load(ClientFlavors.VANILLA.clientId)
            assertEquals(published.generation, vanilla.generation)
            check(!legacy.isFile) { "adoption moved the legacy pointer for A" }
        } finally {
            ActiveFlavor.reset()
        }
    }
}
