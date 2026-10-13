package com.pocketrealm.addons

import com.pocketrealm.desktop.ClientProcessScan
import com.pocketrealm.desktop.ClientProcessScanner
import com.pocketrealm.desktop.DesktopRuntimeBackend
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * DESK-40's launch-boundary refusal pin, at the addon seam: a client that is
 * already running (self-spawned or user-launched, DESK-114's scan) must be
 * refused by the backend's duplicate-launch guard BEFORE the addon projector
 * touches the client directory — a refused launch leaves no Interface/AddOns
 * side effect (the DESK-11 "refused launch must have no side effects" rule,
 * with the addon projection as its newest leg).
 */
class DesktopAddonLaunchRefusalTest {
    /** W4 flip fixture: the sole-qualified degrade is retired; machinery
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

    private val owner = ComponentOwner(sessionId = "test", instanceToken = "test")

    private fun spec() = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = true,
    )

    @Test
    fun `a running client is refused before any addon projection side effect`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        // W4 flip: the backend's start pins the boot lane from the persisted
        // settings BEFORE the duplicate-launch guard — the roots need the
        // pick or the lane resolver refuses before the guard under test.
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile)
                .load().copy(selectedFlavorId = "vanilla"),
        )
        val clientDir = folder.newFolder("client")
        File(clientDir, "WoW.exe").writeBytes(byteArrayOf(0x4D, 0x5A))
        // A user-launched client-class process under the client directory:
        // the same scan observe() reads (DESK-114) must hit it.
        val scanner = ClientProcessScanner {
            listOf(ClientProcessScan(pid = 4242, exePath = File(clientDir, "WoW.exe").canonicalPath))
        }
        val backend = DesktopRuntimeBackend(
            roots,
            clientDir = clientDir,
            clientProcessScanner = scanner,
        )
        try {
            runBlocking {
                try {
                    backend.start(RuntimeComponent.CLIENT, owner, spec())
                    fail("the duplicate-launch guard must refuse a running client")
                } catch (expected: IllegalStateException) {
                    assertTrue(
                        "must be the outside-this-session refusal: ${expected.message}",
                        (expected.message ?: "").contains("launched outside this session"),
                    )
                }
            }
            // THE assertion: the refusal happened before the launch-boundary
            // addon projection — no Interface/AddOns directory (the projector
            // creates it unconditionally once it runs, safe mode included).
            assertFalse(
                "a refused launch must leave no addon projection side effect",
                File(clientDir, "Interface/AddOns").exists(),
            )
        } finally {
            backend.close()
        }
    }
}
