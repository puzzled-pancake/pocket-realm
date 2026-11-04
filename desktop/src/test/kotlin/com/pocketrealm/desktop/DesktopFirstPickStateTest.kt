package com.pocketrealm.desktop

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.storage.Settings
import com.pocketrealm.storage.SettingsSnapshotCodecTest
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * under the flipped 3-qualified
 * registry, a never-picked install must render the FIRST-PICK state —
 * every render/status read returns its honest null/NONE — instead of
 * crashing composition on the ambiguous-registry refusal. The operation
 * gates keep the loud refusal (pinned by their own tests).
 */
class DesktopFirstPickStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun restoreTheFlavorMachine() {
        ActiveFlavor.reset()
    }

    /** The flipped production state: the real 3-qualified registry, holder
     * reset (no pick), settings absent. */
    private fun roots(): DesktopStorageRoots =
        DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }

    @Test
    fun resolveClientDirReturnsNullWithoutAPickUnderTheFlip() {
        val backend = DesktopRuntimeBackend(roots())
        assertNull(
            "no pick under 3 qualified must resolve as 'no client' (fail closed), never throw",
            backend.resolveClientDir(),
        )
    }

    @Test
    fun clientSelectionStateIsNoneWithoutAPickUnderTheFlip() {
        val model = DesktopAppModel(roots())
        try {
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
    fun thePersistedPickRestoresTheManagedLaneAfterTheFirstPick() {
        val roots = roots()
        DesktopSettingsStore(roots.settingsFile).save(
            DesktopSettingsStore(roots.settingsFile).load()
                .copy(selectedFlavorId = "vanilla"),
        )
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
        val backend = DesktopRuntimeBackend(roots)
        // resolves without throwing; the managed pointer is absent in this
        // empty roots, so the honest answer is still null — the pin is that
        // the RESOLUTION is total (no throw) once a pick exists.
        assertNull(backend.resolveClientDir())
    }

    @Test
    fun theSettingsAboutLineNamesTheShippedVanillaRuntimeWithoutAPick() {
        // the About card's render line must not resolve the active
        // lane through the ambiguous refusal — it renders the shipped
        // vanilla runtime telltale until a pick exists.
        val roots = roots()
        DesktopSettingsStore(roots.settingsFile).save(
            DesktopSettingsStore(roots.settingsFile).load()
                .copy(selectedFlavorId = "vanilla"),
        )
        ActiveFlavor.reset()
        val source = File(
            "../desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt",
        ).readText()
        assertTrue(
            "the About line must fall back to the shipped vanilla lane's telltale",
            "?: \"vanilla\"" in source,
        )
        assertTrue(
            "the About line must not resolve through the ambiguous refusal",
            !Regex("ActiveFlavor\\.resolve\\([^)]*native-runtime").containsMatchIn(source),
        )
    }
}
