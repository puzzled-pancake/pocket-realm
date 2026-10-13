package com.pocketrealm.desktop

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.importer.ImportLimits
import com.pocketrealm.importer.ManagedClientImporter
import com.pocketrealm.importer.SyntheticClientArchives
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Before
import org.junit.After

/**
 * DESK-135: the client-selection affordance's UI-less state leg. The
 * "Change game folder…"/import control renders in EVERY state (the plan's
 * core change: it is no longer gated on `clientDir == null`); this pins the
 * pure state that decides what the affordance says, for both null and
 * non-null resolved dirs, and the generation status it surfaces once
 * managed generations exist (the switcher half).
 */
class DesktopClientSelectionStateTest {
    /** W4 flip fixture: these tests read the SELECTION resolution
     * (folder/managed/none), not the no-pick refusal - the holder is
     * pinned to the vanilla lane so resolveClientDir sees one lane. */
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

    private val limits: ImportLimits get() = ImportLimits(minFiles = 1, minTotalBytes = 0L)

    /** The lanes under test exercise scan/copy/publish, not the storage budget:
     * a fixed generous allocatable keeps the preflight off the host TEMP
     * volume's real headroom (the planner seam). */
    private fun injectedImporter(roots: DesktopStorageRoots) =
        ManagedClientImporter(roots, limits, allocatableBytes = { 64L * ImportLimits.GIB })

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

    @Test
    fun withNothingResolvingTheStateIsNone() {
        val model = DesktopAppModel(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            assertNull("no client resolves: the picker is the only path", model.resolveClientDir())
            assertEquals(
                DesktopAppModel.ClientSelectionState.NONE,
                model.clientSelectionState(),
            )
            assertNull(model.activeClientGeneration())
        } finally {
            model.close()
        }
    }

    @Test
    fun withAValidLegacyFolderTheStateIsFolder() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val client = materializeClient(folder.newFolder("client"))
        val model = DesktopAppModel(roots)
        try {
            model.updateSettings { it.copy(clientDir = client.absolutePath) }
            assertEquals(client.canonicalFile, model.resolveClientDir()?.canonicalFile)
            assertEquals(
                DesktopAppModel.ClientSelectionState.FOLDER,
                model.clientSelectionState(),
            )
        } finally {
            model.close()
        }
    }

    @Test
    fun withAPublishedGenerationTheStateIsManagedAndTheGenerationIsNamed() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val model = DesktopAppModel(roots)
        try {
            // A legacy folder exists first; the import must supersede it in
            // the affordance state (the generation-switcher half).
            model.updateSettings {
                it.copy(clientDir = materializeClient(folder.newFolder("legacy")).absolutePath)
            }
            assertEquals(DesktopAppModel.ClientSelectionState.FOLDER, model.clientSelectionState())
            val published = runBlocking {
                injectedImporter(roots).run(materializeClient(folder.newFolder("imported")))
            }
            assertEquals(DesktopAppModel.ClientSelectionState.MANAGED_GENERATION, model.clientSelectionState())
            val generation = model.activeClientGeneration()
            assertTrue(generation != null)
            assertEquals(published.generation, generation!!.generation)
            assertEquals(ClientFlavors.VANILLA.clientId, generation.clientId)
            assertEquals(ClientFlavors.VANILLA.versionResource, generation.version)
            assertNull("a healthy generation reports no broken refusal", generation.broken)
            assertTrue(generation.root!!.isDirectory)
        } finally {
            model.close()
        }
    }

    /**
     * GAP-3 (owner census UIUX-3, the DesktopAppModel.activeClientGeneration
     * leg): with two qualified flavors the generation resolves the ACTIVE
     * lane through the flavor machine — the selection drives the pointer
     * namespace instead of the former `qualified().single()` throwing
     * inside the client-picker state at the second qualification.
     */
    @Test
    fun activeClientGenerationResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-two-qualified"))
        val model = DesktopAppModel(roots)
        try {
            // The generation imports under today's registry state; the S5
            // flip (TwoQualifiedFlavors) then re-targets the reads only.
            val published = runBlocking {
                injectedImporter(roots).run(materializeClient(folder.newFolder("imported")))
            }
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
            assertNull(
                "the tbc lane has no generation: honest null, never a .single() crash",
                model.activeClientGeneration(),
            )
            assertEquals(
                "no client resolves for the unpopulated lane: the picker is the only path",
                DesktopAppModel.ClientSelectionState.NONE,
                model.clientSelectionState(),
            )

            ActiveFlavor.select(ClientFlavors.VANILLA.flavorId)
            val generation = model.activeClientGeneration()
            assertEquals(published.generation, generation?.generation)
            assertEquals(ClientFlavors.VANILLA.clientId, generation?.clientId)
        } finally {
            ActiveFlavor.reset()
            model.close()
        }
    }

    @Test
    fun aWrongBuildFolderReadsAsNoneNotFolder() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val wrongBuild = materializeClient(folder.newFolder("wrong"), build = 6005)
        val fixtureRealmlist = File(wrongBuild, "realmlist.wtf").readText()
        val model = DesktopAppModel(roots)
        try {
            model.updateSettings { it.copy(clientDir = wrongBuild.absolutePath) }
            assertNull("the identity gate applies to the resolution", model.resolveClientDir())
            assertEquals(
                "a refused folder is the picker state",
                DesktopAppModel.ClientSelectionState.NONE,
                model.clientSelectionState(),
            )
            assertEquals(
                "the refused folder's realmlist is untouched (no projection side effect)",
                fixtureRealmlist,
                File(wrongBuild, "realmlist.wtf").readText(),
            )
        } finally {
            model.close()
        }
    }
}
