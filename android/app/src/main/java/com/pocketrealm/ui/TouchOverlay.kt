package com.pocketrealm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.OverlayControl
import kotlin.math.roundToInt

/** User-configurable touch controls layered over the real client surface. */
@Composable
fun TouchOverlay(host: ClientDisplayHost, modifier: Modifier = Modifier) {
    val profile by host.profile.collectAsState()
    var visible by remember(host.generation) { mutableStateOf(profile.overlayEnabled) }
    var captured by remember(host.generation) { mutableStateOf(host.isPointerCaptured) }
    LaunchedEffect(profile.overlayEnabled) { visible = profile.overlayEnabled }

    val opacity = profile.overlayOpacity
    val scale = profile.overlayScale
    Box(modifier.fillMaxSize()) {
        if (visible) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .fillMaxWidth(profile.cameraRegionWidth)
                    .pointerInput(host.generation, profile.invertCameraX, profile.invertCameraY) {
                        detectDragGestures { _, drag ->
                            val x = if (profile.invertCameraX) -drag.x else drag.x
                            val y = if (profile.invertCameraY) -drag.y else drag.y
                            host.dispatchRelativePointer(x.roundToInt(), y.roundToInt())
                        }
                    }
                    .background(Color(0x18000000), RoundedCornerShape(18.dp))
                    .testTag("touch-camera-region"),
            )

            Column(
                Modifier.align(Alignment.BottomStart).padding(12.dp).scale(scale),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Move", color = Color.White, modifier = Modifier.testTag("touch-move-label"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionKey(host, profile, OverlayControl.MOVE_LEFT, "←", opacity)
                    ActionKey(host, profile, OverlayControl.MOVE_UP, "↑", opacity)
                    ActionKey(host, profile, OverlayControl.MOVE_RIGHT, "→", opacity)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionKey(host, profile, OverlayControl.MOVE_DOWN, "↓", opacity)
                    ActionKey(host, profile, OverlayControl.JUMP, "Jump", opacity)
                    ActionKey(host, profile, OverlayControl.MENU, "Menu", opacity)
                }
            }

            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).scale(scale),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    OverlayControl.ACTION_1,
                    OverlayControl.ACTION_2,
                    OverlayControl.ACTION_3,
                    OverlayControl.ACTION_4,
                    OverlayControl.ACTION_5,
                    OverlayControl.ACTION_6,
                    OverlayControl.ACTION_7,
                    OverlayControl.ACTION_8,
                ).forEachIndexed { index, control ->
                    ActionKey(host, profile, control, (index + 1).toString(), opacity)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OverlayButton(
                label = if (visible) "Hide" else "Show controls",
                tag = "touch-overlay-toggle",
                opacity = opacity,
            ) { visible = !visible }
            OverlayButton(
                label = if (captured) "Cursor" else "Mouse look",
                tag = "touch-pointer-capture",
                opacity = opacity,
            ) {
                captured = host.setPointerCapture(!captured)
            }
            OverlayButton("Keyboard", "touch-chat", opacity) { host.showIme() }
        }
    }
}

@Composable
private fun ActionKey(
    host: ClientDisplayHost,
    profile: InputProfile,
    control: OverlayControl,
    fallbackLabel: String,
    opacity: Float,
) {
    val action = InputProfile.actionFor(profile, control)
    val label = action.shortLabel(fallbackLabel)
    Box(
        Modifier
            .size(if (label.length > 2) 68.dp else 48.dp, 48.dp)
            .background(Color(0xFF101720).copy(alpha = opacity), RoundedCornerShape(14.dp))
            .virtualAction(host, action, VIRTUAL_SOURCE_BASE + control.ordinal)
            .testTag("touch-control-${control.name}"),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White) }
}

@Composable
private fun OverlayButton(
    label: String,
    tag: String,
    opacity: Float,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(Color(0xFF101720).copy(alpha = opacity), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag)
            .pointerInput(tag) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White) }
}

private fun Modifier.virtualAction(
    host: ClientDisplayHost,
    action: ControllerAction,
    source: Int,
): Modifier = pointerInput(host.generation, action, source) {
    detectTapGestures(
        onPress = {
            host.dispatchVirtualAction(action, true, source)
            try {
                tryAwaitRelease()
            } finally {
                host.dispatchVirtualAction(action, false, source)
            }
        },
    )
}

private fun ControllerAction.shortLabel(fallback: String): String = when (this) {
    ControllerAction.DISABLED -> "Off"
    ControllerAction.POINTER_LEFT -> "LMB"
    ControllerAction.POINTER_RIGHT -> "RMB"
    ControllerAction.JUMP -> "Jump"
    ControllerAction.ESCAPE -> "Menu"
    ControllerAction.RADIAL_MENU -> "F7"
    ControllerAction.AUTO_RUN -> "Auto"
    ControllerAction.INTERACT -> "Use"
    ControllerAction.MAP -> "Map"
    ControllerAction.TARGET -> "Tab"
    else -> keyCode?.let { displayName.removePrefix("Key ").removePrefix("Move ").removePrefix("Strafe ") }
        ?: fallback
}

private const val VIRTUAL_SOURCE_BASE = 0x7400
