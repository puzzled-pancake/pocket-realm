package com.pocketrealm.ui

import com.pocketrealm.client.ControllerFamily
import com.pocketrealm.client.OverlayControl
import com.pocketrealm.client.OverlayMode
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.FaceLayer
import com.pocketrealm.client.Rp6Control
import com.pocketrealm.client.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeUiContractTest {
    @Test
    fun oneTapCameraButtonNamesTheModeItWillEnter() {
        assertEquals("Camera", cameraModeButtonLabel(cameraLocked = false))
        assertEquals("Pointer", cameraModeButtonLabel(cameraLocked = true))
    }

    @Test
    fun controlScaleNeverShrinksEffectiveTouchTargetsBelow48Dp() {
        assertEquals(48f, effectiveTouchTargetDp(0.75f), 0.001f)
        assertEquals(78f, effectiveTouchTargetDp(1.5f), 0.001f)
        assertTrue(effectiveTouchTargetDp(0.75f) >= 48f)
        assertTrue(effectiveTouchTargetDp(1.5f) >= 48f)
    }

    @Test
    fun duplicateWarningsCoverBaseAndEditableFaceLayers() {
        val duplicateLayers = InputProfile.DEFAULT.layerFaceBindings.mapValues { (_, value) -> value.toMutableMap() }
        duplicateLayers.getValue(FaceLayer.L2)[Rp6Control.FACE_BOTTOM] = ControllerAction.KEY_1
        duplicateLayers.getValue(FaceLayer.L1)[Rp6Control.FACE_BOTTOM] = ControllerAction.KEY_1
        val warnings = duplicateOutputWarnings(InputProfile.DEFAULT.copy(layerFaceBindings = duplicateLayers))
        val keyOne = warnings.single { it.contains("Key 1") }
        assertTrue(keyOne.contains("Bottom face button"))
        assertTrue(keyOne.contains("L2 +"))
        assertTrue(keyOne.contains("L1 +"))
    }

    @Test
    fun automaticOverlayIsMinimalOnlyForAnActiveGameplayController() {
        assertEquals(
            OverlayPresentation.MINIMAL,
            overlayPresentation(OverlayMode.AUTO, ControllerFamily.AUTO, physicalControllerConnected = true),
        )
        assertEquals(
            OverlayPresentation.FULL,
            overlayPresentation(OverlayMode.AUTO, ControllerFamily.AUTO, physicalControllerConnected = false),
        )
        assertEquals(
            OverlayPresentation.FULL,
            overlayPresentation(OverlayMode.AUTO, ControllerFamily.TOUCH_ONLY, physicalControllerConnected = true),
        )
        assertEquals(
            OverlayPresentation.FULL,
            overlayPresentation(OverlayMode.AUTO, ControllerFamily.KEYBOARD_MOUSE, physicalControllerConnected = true),
        )
    }

    @Test
    fun manualOverlayModesAlwaysWinAndTouchActionsArePagedWithoutDuplication() {
        assertEquals(
            OverlayPresentation.MINIMAL,
            overlayPresentation(OverlayMode.MINIMAL, ControllerFamily.TOUCH_ONLY, false),
        )
        assertEquals(
            OverlayPresentation.FULL,
            overlayPresentation(OverlayMode.FULL, ControllerFamily.AUTO, true),
        )
        assertEquals(
            OverlayPresentation.OFF,
            overlayPresentation(OverlayMode.OFF, ControllerFamily.AUTO, false),
        )
        assertEquals(3, fullOverlayActionPages.size)
        assertEquals(
            OverlayControl.values().filter {
                it.ordinal in OverlayControl.ACTION_1.ordinal..OverlayControl.ACTION_12.ordinal
            },
            fullOverlayActionPages.flatten(),
        )
    }

    @Test
    fun cameraZoomButtonsUseWowWheelDirections() {
        assertEquals(-1, cameraZoomWheelTicks(zoomIn = true))
        assertEquals(1, cameraZoomWheelTicks(zoomIn = false))
    }

    @Test
    fun loweredTouchCameraSpeedRetainsFractionalMotion() {
        val scaler = TouchCameraScaler(0.35f)
        val deltas = List(10) { scaler.scale(1f, -1f) }
        assertEquals(3, deltas.sumOf { it.first })
        assertEquals(-3, deltas.sumOf { it.second })
        scaler.reset()
        assertEquals(0 to 0, scaler.scale(1f, -1f))
    }

    @Test
    fun fullHdAndDensityScaledLandscapeUseWidePaneLayout() {
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 1920f, heightDp = 1080f))
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 960f, heightDp = 540f))
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 640f, heightDp = 360f))
        assertEquals(PaneLayout.STACKED, paneLayout(widthDp = 540f, heightDp = 960f))
        assertEquals(PaneLayout.STACKED, paneLayout(widthDp = 599f, heightDp = 400f))
    }

    @Test
    fun everyAdvancedControlHasConcisePlainEnglishHelp() {
        // Bot advanced controls moved to the dedicated Bots destination; this
        // contract now covers the controls Settings still renders.
        val required = setOf(
            "Repeated-press guard",
            "Poll interval", "Stable polls", "Login UI settle", "Session timeout",
            "Drain poll", "Input drain timeout", "IME key dwell", "IME key gap",
            "Field settle", "Pointer dwell", "Widescreen FoV fix", "Farclip cap raise",
            "Frill distance raise", "Sound in background", "Sound channel count (64)",
            "Auto-loot opened corpses", "Nameplate distance (41 yd)",
            "Large address aware", "Camera skip glitch fix", "Max camera distance raise",
        )
        assertEquals(required, advancedSettingExplanations.keys)
        advancedSettingExplanations.values.forEach { explanation ->
            assertTrue(explanation, explanation.endsWith('.'))
            assertTrue(explanation, explanation.split(' ').size >= 7)
        }
    }
}
