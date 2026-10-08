package com.pocketrealm.desktop

import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.client.FaceLayer
import com.pocketrealm.client.ControllerFamily
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.client.Rp6Control
import com.pocketrealm.client.Vk
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-4 (portable subset): the desktop input-profile twin — the v12 JSON
 * record survives a desktop round-trip byte-for-byte, the key-code space is
 * Windows VK (the codes SendInput consumes), and the file-backed store
 * keeps the Android store's load semantics (aspect reset, corrupt-file
 * recovery, legacy keys).
 */
class InputProfileTwinTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `default profile invariants ride across the twin`() {
        assertEquals(InputProfile.CURRENT_VERSION, 12)
        assertEquals("16:9", InputProfile.DEFAULT_ASPECT_IDENTITY)
        assertEquals(0.12f, InputProfile.DEFAULT.deadZone)
        assertEquals(ControlScheme.CLASSIC_CAMERA, InputProfile.DEFAULT.scheme)
    }

    @Test
    fun `json round trip preserves the full record`() {
        val customized = InputProfile.DEFAULT.copy(
            deadZone = 0.2f,
            aspectIdentity = "4:3",
            cameraSensitivity = 2.5f,
            scheme = ControlScheme.CUSTOM,
            controllerFamily = ControllerFamily.XBOX,
            leftTriggerOnThreshold = 0.5f,
        )
        val restored = InputProfile.fromJson(JSONObject(InputProfile.toJson(customized).toString()))
        assertEquals(customized, restored)
        val defaultRestored =
            InputProfile.fromJson(JSONObject(InputProfile.toJson(InputProfile.DEFAULT).toString()))
        assertEquals(InputProfile.DEFAULT, defaultRestored)
    }

    @Test
    fun `key code space is windows virtual keys`() {
        assertEquals(Vk.VK_W, ControllerAction.MOVE_W.keyCode)
        assertEquals(Vk.VK_Q, ControllerAction.STRAFE_Q.keyCode)
        assertEquals(Vk.VK_1, ControllerAction.KEY_1.keyCode)
        assertEquals(Vk.VK_SPACE, ControllerAction.JUMP.keyCode)
        assertEquals(Vk.VK_F6, ControllerAction.TARGET.keyCode)
        // AUTO_RUN keeps NUM_LOCK as the JSON/catalog identity; the bridge
        // dispatches the app-owned F9 edge (a real NumLock tap would toggle
        // the OS numpad mode).
        assertEquals(Vk.VK_NUMLOCK, ControllerAction.AUTO_RUN.keyCode)
        assertEquals(null, ControllerAction.POINTER_LEFT.keyCode)
        assertEquals(-1, ControllerAction.WHEEL_UP.wheelTicks)
    }

    @Test
    fun `scheme presets produce the documented maps`() {
        val classic = InputProfile.profileForScheme(ControlScheme.CLASSIC_CAMERA, "16:9")
        assertEquals(ControllerAction.KEY_1, InputProfile.actionFor(classic, Rp6Control.FACE_BOTTOM))
        assertEquals(
            "the Android Port preset maps d-pad to action slots 5-8",
            ControllerAction.KEY_5,
            InputProfile.actionFor(
                InputProfile.profileForScheme(ControlScheme.ANDROID_PORT, "16:9"),
                Rp6Control.DPAD_DOWN,
            ),
        )
        assertEquals(
            "layer L2 face slots 5-8 are the stock layer default",
            ControllerAction.KEY_5,
            InputProfile.actionFor(classic, FaceLayer.L2, Rp6Control.FACE_BOTTOM),
        )
    }

    @Test
    fun `out of range fields are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            InputProfile.DEFAULT.copy(deadZone = 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InputProfile.DEFAULT.copy(version = 11)
        }
    }

    @Test
    fun `store round trips and resets on aspect change`() {
        val file = File(folder.root, "input-profile.json")
        val store = InputProfileStore(file)
        val stored = InputProfile.DEFAULT.copy(aspectIdentity = "4:3", deadZone = 0.2f)
        store.save(stored)
        val sameAspect = store.load("4:3")
        assertFalse(sameAspect.resetForAspect)
        assertEquals(stored, sameAspect.profile)
        val otherAspect = store.load("16:9")
        assertTrue(otherAspect.resetForAspect)
        assertEquals("16:9", otherAspect.profile.aspectIdentity)
        assertEquals(0.12f, otherAspect.profile.deadZone)
    }

    @Test
    fun `a corrupt store degrades to defaults`() {
        val file = File(folder.root, "input-profile.json")
        file.writeText("{ not json at all")
        val store = InputProfileStore(file)
        assertEquals(InputProfile.DEFAULT, store.loadStoredOrDefault())
        assertEquals(InputProfile.DEFAULT.aspectIdentity, store.load("16:9").profile.aspectIdentity)
    }
}
