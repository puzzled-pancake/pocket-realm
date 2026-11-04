package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Before

/**
 * the desktop ClientGenerationStore twin — publish (immutable
 * generation + atomic per-flavor `active-vanilla.json` pointer), the
 * manifest's durable shape, the flavor-scoped prune keep-set through the
 * SHARED GenerationPointers plumbing, the legacy un-namespaced pointer
 * adoption, and the crash-window recovery. The port of the Android store
 * tests' JVM-testable legs (ClientGenerationStoreQuarantineInstrumentedTest's
 * mechanics, GenerationPointerQuarantineTest's pure rules).
 */
class DesktopClientGenerationStoreTest {
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

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanTempRoots() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    private fun roots(): DesktopStorageRoots =
        DesktopStorageRoots(tempRoot("store-roots")).also { it.ensureDirectories() }

    private fun store(): ClientGenerationStore = ClientGenerationStore(roots())

    /** Stage a minimal importable tree (WoW.exe + one MPQ) into the store. */
    private fun stage(
        store: ClientGenerationStore,
        importId: String,
        build: Int = ClientFlavors.VANILLA.build,
    ): File {
        val staging = store.prepare(importId)
        File(staging, "WoW.exe").writeBytes(SyntheticClientArchives.syntheticPe(build))
        File(staging, "Data").mkdirs()
        File(staging, "Data/base.MPQ").writeBytes(SyntheticClientArchives.mpqStub("base.MPQ"))
        return staging
    }

    private fun plan(staging: File, importId: String, build: Int = ClientFlavors.VANILLA.build): ClientGenerationStore.PublishPlan {
        val exe = File(staging, "WoW.exe")
        val digest = com.pocketrealm.fs.FileDigests.sha256(exe)
        val identity = JSONObject()
            .put("machine", 0x14c).put("optionalMagic", 0x10b)
            .put("version", "1.12.1.$build").put("build", build)
            .put("sha256", digest).put("size", exe.length())
        return ClientGenerationStore.PublishPlan(
            identity = identity,
            sourceFingerprint = "fixture-fingerprint",
            journal = ClientGenerationStore.JournalSummary(verified = 2, skipped = 0, maxAttempt = 1),
            durationMs = 12L,
        )
    }

    // ---- publish + activate ----

    @Test
    fun publishWritesTheGenerationAndActivatesThePerFlavorPointer() {
        val store = store()
        val importId = "11111111-1111-1111-1111-111111111111"
        val staging = stage(store, importId)
        val published = store.publish(importId, plan(staging, importId))

        assertEquals(importId, published.id)
        // The staging generation was renamed away...
        assertFalse(File(store.generationsDirectory(), ".staging-$importId").exists())
        // ...the per-flavor pointer is canonical...
        val pointerFile = File(store.rootDirectory(), "active-vanilla.json")
        assertTrue("the canonical per-flavor pointer must exist", pointerFile.isFile)
        val pointer = JSONObject(pointerFile.readText())
        assertEquals(1, pointer.getInt("schema"))
        assertEquals(importId, pointer.getString("generation"))
        assertEquals(ClientFlavors.VANILLA.clientId, pointer.getString("clientId"))
        assertEquals("vanilla", pointer.getString("flavor"))
        assertTrue(pointer.getString("manifestSha256").matches(Regex("[0-9a-f]{64}")))
        assertEquals(pointer.getString("manifestSha256"), published.manifestSha256)
        assertEquals(importId, store.activeGeneration())
        // ...and the manifest passed its own integrity validation.
        val manifest = JSONObject(File(published.root, "client-manifest.json").readText())
        assertEquals(2, manifest.getInt("schema"))
        assertTrue(manifest.getBoolean("complete"))
        assertEquals(ClientFlavors.VANILLA.clientId, manifest.getString("clientId"))
        // The flavor key is OPTIONAL at schema 2; absent means the flavor the
        // clientId already carries (validateManifestShape's default).
        assertEquals("vanilla", manifest.optString("flavor", "vanilla"))
        assertTrue(manifest.getString("managedRoot").endsWith("/$importId"))
        // App-owned files were stamped into the generation.
        assertEquals(
            "set realmlist 127.0.0.1\r\n",
            File(published.root, "realmlist.wtf").readText(),
        )
        assertTrue(File(published.root, "WTF/Config.wtf").isFile)
    }

    /**
     * with two qualified
     * flavors the no-arg [ClientGenerationStore.activeGeneration] resolves
     * the ACTIVE lane through the flavor machine — the selection drives the
     * pointer namespace instead of the former `qualified().single()` default
     * throwing the moment a second flavor qualified.
     */
    @Test
    fun activeGenerationResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        val store = store()
        val importId = "44444444-4444-4444-4444-444444444444"
        val staging = stage(store, importId)
        store.publish(importId, plan(staging, importId))

        TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
        try {
            assertNull(
                "the tbc lane has no pointer: honest null, never a .single() crash",
                store.activeGeneration(),
            )
            ActiveFlavor.select(ClientFlavors.VANILLA.flavorId)
            assertEquals(
                "the vanilla selection resolves the vanilla pointer's generation",
                importId,
                store.activeGeneration(),
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun republishingSameFlavorKeepsThePriorGenerationAndRetiresTheOneBeforeIt() {
        val store = store()
        val firstId = "22222222-2222-2222-2222-222222222222"
        val secondId = "33333333-3333-3333-3333-333333333333"
        val thirdId = "88888888-8888-8888-8888-888888888888"
        val firstStaging = stage(store, firstId)
        store.publish(firstId, plan(firstStaging, firstId))
        val secondStaging = stage(store, secondId)
        store.publish(secondId, plan(secondStaging, secondId))

        assertEquals(secondId, store.activeGeneration())
        // Today's invariant: the activating generation and its PRIOR stay —
        // the previous pointer carries the retired generation's bytes.
        assertTrue(
            "the prior generation must stay",
            File(store.generationsDirectory(), firstId).isDirectory,
        )
        assertTrue(
            "the previous pointer must exist",
            File(store.rootDirectory(), "previous-vanilla.json").isFile,
        )
        val previous = JSONObject(File(store.rootDirectory(), "previous-vanilla.json").readText())
        assertEquals(firstId, previous.getString("generation"))
        assertTrue(File(store.generationsDirectory(), secondId).isDirectory)

        // A third same-flavor activation retires the generation before the
        // prior (the scoped keep-set within one flavor).
        val thirdStaging = stage(store, thirdId)
        store.publish(thirdId, plan(thirdStaging, thirdId))
        assertEquals(thirdId, store.activeGeneration())
        assertFalse(
            "the generation before the prior must be retired",
            File(store.generationsDirectory(), firstId).exists(),
        )
        assertTrue(File(store.generationsDirectory(), secondId).isDirectory)
        assertEquals(
            secondId,
            JSONObject(File(store.rootDirectory(), "previous-vanilla.json").readText()).getString("generation"),
        )
    }

    @Test
    fun legacyActivePointerIsAdoptedOnceUnderTheCanonicalName() {
        val store = store()
        val importId = "44444444-4444-4444-4444-444444444444"
        val staging = stage(store, importId)
        store.publish(importId, plan(staging, importId))
        // Pre-flavor layout: rename the canonical pointer back to the legacy name.
        val canonical = File(store.rootDirectory(), "active-vanilla.json")
        canonical.renameTo(File(store.rootDirectory(), "active.json"))
        assertEquals(importId, store.activeGeneration())
        assertTrue(
            "the canonical name must exist after adoption",
            File(store.rootDirectory(), "active-vanilla.json").isFile,
        )
        assertFalse(
            "the legacy name must not survive as authoritative",
            File(store.rootDirectory(), "active.json").isFile,
        )
    }

    @Test
    fun recoverPublishedCompletesTheCrashWindowAndIsIdempotent() {
        val store = store()
        val importId = "55555555-5555-5555-5555-555555555555"
        val staging = stage(store, importId)
        val published = store.publish(importId, plan(staging, importId))
        // The post-rename pre-pointer crash window replays as a plain
        // recovery: the manifest validates, the pointer re-activates.
        val recovered = store.recoverPublished(importId)
        assertTrue(recovered != null)
        assertEquals(published.id, recovered!!.id)
        assertEquals(published.manifestSha256, recovered.manifestSha256)
        assertEquals(importId, store.activeGeneration())
        assertNull("an unknown import has nothing to recover", store.recoverPublished("66666666-6666-6666-6666-666666666666"))
    }

    @Test
    fun validateManifestShapeRefusesACrossFlavoredManifest() {
        val manifest = JSONObject()
            .put("schema", 2).put("complete", true)
            .put("clientId", ClientFlavors.VANILLA.clientId)
            .put("flavor", "wotlk")
            .put("managedRoot", "content/client/generations/77777777-7777-7777-7777-777777777777")
        try {
            ClientGenerationStore.validateManifestShape(
                manifest,
                "77777777-7777-7777-7777-777777777777",
            )
            org.junit.Assert.fail("a doctored cross-flavor manifest must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must name the disagreement: ${expected.message}",
                "disagrees with" in (expected.message ?: ""),
            )
        }
    }

    // ---- shared GenerationPointers plumbing (desktop pins) ----

    @Test
    fun activatingOneFlavorKeepsAnotherFlavorsGeneration() {
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-tbc-2",
            priorGeneration = null,
            generations = listOf(
                generation("gen-vanilla-1", clientManifest(ClientFlavors.VANILLA.clientId)),
                generation("gen-tbc-1", clientManifest(ClientFlavors.TBC.clientId)),
            ),
            activatingFlavorId = ClientFlavors.TBC.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        assertTrue("vanilla generation must survive a TBC activation", "gen-vanilla-1" in keep)
        assertTrue("gen-tbc-2" in keep)
        assertTrue("gen-tbc-1" !in keep)
    }

    @Test
    fun generationWithUnreadableManifestIsKeptAsVanillaForeign() {
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = "gen-vanilla-2",
            priorGeneration = "gen-vanilla",
            generations = listOf(
                generation("gen-broken", null),
                generation("gen-vanilla", clientManifest(ClientFlavors.VANILLA.clientId)),
            ),
            activatingFlavorId = ClientFlavors.VANILLA.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        assertTrue("unreadable manifest must be treated as vanilla-foreign", "gen-broken" in keep)
    }

    // ---- accessors for the assertions above ----

    private fun generation(name: String, manifestText: String?): File {
        val directory = File(tempRoot("g3-gen"), name).apply { mkdirs() }
        manifestText?.let { File(directory, "client-manifest.json").writeText(it) }
        return directory
    }

    private fun clientManifest(clientId: String): String = JSONObject()
        .put("schema", 2).put("complete", true).put("clientId", clientId)
        .put("files", JSONArray())
        .toString()

    private fun tempRoot(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile().also { tempRoots.add(it) }
}

/** Test-visible accessors mirroring the Android store's private layout (the
 * assertions above pin the ROOT POLICY, so they must read the real paths). */
private fun ClientGenerationStore.rootDirectory(): File {
    val field = ClientGenerationStore::class.java.getDeclaredField("root")
    field.isAccessible = true
    return field.get(this) as File
}

private fun ClientGenerationStore.generationsDirectory(): File {
    val field = ClientGenerationStore::class.java.getDeclaredField("generations")
    field.isAccessible = true
    return field.get(this) as File
}
