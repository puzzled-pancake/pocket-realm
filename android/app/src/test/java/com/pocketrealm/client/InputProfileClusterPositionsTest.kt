package com.pocketrealm.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cluster-anchor persistence: round-trip, tolerance, and deterministic order. */
class InputProfileClusterPositionsTest {
    @Test
    fun populatedAnchorsRoundTripExactly() {
        val tuned = InputProfile.DEFAULT.copy(
            overlayClusterPositions = linkedMapOf(
                OverlayClusterId.DRAWER to ClusterAnchor(0.75f, 0.02f),
                OverlayClusterId.MOVEMENT to ClusterAnchor(0f, 0.9f),
            ),
        )

        val reloaded = InputProfile.fromJson(InputProfile.toJson(tuned))

        assertEquals(tuned, reloaded)
    }

    @Test
    fun emptyDefaultRoundTripsToEmptyMap() {
        assertEquals(emptyMap<OverlayClusterId, ClusterAnchor>(), InputProfile.DEFAULT.overlayClusterPositions)
        assertEquals(
            InputProfile.DEFAULT,
            InputProfile.fromJson(InputProfile.toJson(InputProfile.DEFAULT)),
        )
    }

    @Test
    fun serializationOrderIsIndependentOfMapInsertionOrder() {
        val a = InputProfile.DEFAULT.copy(
            overlayClusterPositions = linkedMapOf(
                OverlayClusterId.DRAWER to ClusterAnchor(0.1f, 0.2f),
                OverlayClusterId.ACTIONS to ClusterAnchor(0.3f, 0.4f),
            ),
        )
        val b = InputProfile.DEFAULT.copy(
            overlayClusterPositions = linkedMapOf(
                OverlayClusterId.ACTIONS to ClusterAnchor(0.3f, 0.4f),
                OverlayClusterId.DRAWER to ClusterAnchor(0.1f, 0.2f),
            ),
        )

        // The managed-preset comparison re-serializes stored records and
        // compares strings, so the byte layout must be canonical.
        assertEquals(InputProfile.toJson(a).toString(), InputProfile.toJson(b).toString())
    }

    @Test
    fun malformedAnchorsClampOrDropInsteadOfDiscardingTheProfile() {
        val stored = JSONObject()
            .put("version", InputProfile.CURRENT_VERSION)
            .put("aspectIdentity", "16:9")
            .put(
                "overlayClusterPositions",
                JSONObject()
                    .put("DRAWER", JSONObject().put("x", 1.5).put("y", -0.5))
                    .put("MOVEMENT", JSONObject().put("x", 0.25))
                    .put("ACTIONS", "not an object")
                    .put("FUTURE_CLUSTER", JSONObject().put("x", 0.5).put("y", 0.5)),
            )

        val parsed = InputProfile.fromJson(stored)

        assertEquals(
            linkedMapOf(OverlayClusterId.DRAWER to ClusterAnchor(1.0f, 0.0f)),
            parsed.overlayClusterPositions,
        )
    }

    @Test
    fun outOfRangeAnchorsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            InputProfile.DEFAULT.copy(
                overlayClusterPositions = mapOf(
                    OverlayClusterId.DRAWER to ClusterAnchor(1.2f, 0.5f),
                ),
            )
        }
    }

    @Test
    fun overlayLayoutRoundTripsAndAbsentFieldUpgradesToConsole() {
        assertEquals(OverlayLayout.CONSOLE, InputProfile.DEFAULT.overlayLayout)

        val classic = InputProfile.DEFAULT.copy(overlayLayout = OverlayLayout.CLASSIC)
        assertEquals(classic, InputProfile.fromJson(InputProfile.toJson(classic)))

        val storedV11 = JSONObject()
            .put("version", 11)
            .put("aspectIdentity", "16:9")
        assertEquals(OverlayLayout.CONSOLE, InputProfile.fromJson(storedV11).overlayLayout)
    }

    @Test
    fun consoleControlsHaveDefaultBindingsAppendedAfterTheClassicGrid() {
        val defaults = InputProfile.defaultOverlayBindings()

        assertEquals(ControllerAction.SHIFT, defaults.getValue(OverlayControl.MODIFIER_SHIFT))
        assertEquals(ControllerAction.CTRL, defaults.getValue(OverlayControl.MODIFIER_CTRL))
        assertEquals(ControllerAction.POINTER_LEFT, defaults.getValue(OverlayControl.MOUSE_LEFT))
        assertEquals(ControllerAction.POINTER_RIGHT, defaults.getValue(OverlayControl.MOUSE_RIGHT))
        assertEquals(ControllerAction.CAMERA_LOCK, defaults.getValue(OverlayControl.LOOK_TOGGLE))
        assertEquals(ControllerAction.CONSOLE_RADIAL, defaults.getValue(OverlayControl.RADIAL))
        assertEquals(ControllerAction.NEARBY_USE, defaults.getValue(OverlayControl.NEARBY_USE))
        assertEquals(ControllerAction.MOVE_UI, defaults.getValue(OverlayControl.MOVE_UI))
        assertTrue(OverlayControl.ACTION_12.ordinal < OverlayControl.MODIFIER_SHIFT.ordinal)
    }
}
