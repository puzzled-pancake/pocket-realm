package com.pocketrealm.ui

import android.view.KeyEvent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.ClientDisplayHost
import kotlin.math.roundToInt

/**
 * The default touch-only control layer described by report §16.7. It is an
 * optional Compose overlay over the real X surface: controls synthesize the
 * same generation-gated keys/relative pointer events as physical devices,
 * while the overlay itself can be hidden for screenshots or peripherals.
 */
@Composable
fun TouchOverlay(host: ClientDisplayHost, modifier: Modifier = Modifier) {
    var visible by remember(host.generation) { mutableStateOf(true) }
    var captured by remember(host.generation) { mutableStateOf(host.isPointerCaptured) }
    val opacity = host.activeProfile.overlayOpacity

    Box(modifier.fillMaxSize()) {
        if (visible) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .fillMaxWidth(0.42f)
                    .pointerInput(host.generation) {
                        detectDragGestures { _, drag ->
                            host.dispatchRelativePointer(
                                drag.x.roundToInt(), drag.y.roundToInt(),
                            )
                        }
                    }
                    .background(Color(0x22000000), RoundedCornerShape(12.dp))
                    .testTag("touch-camera-region"),
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Move", color = Color.White, modifier = Modifier.testTag("touch-move-label"))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpacerKey(host, KeyEvent.KEYCODE_A, "A", opacity)
                    SpacerKey(host, KeyEvent.KEYCODE_W, "W", opacity)
                    SpacerKey(host, KeyEvent.KEYCODE_D, "D", opacity)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpacerKey(host, KeyEvent.KEYCODE_S, "S", opacity)
                    SpacerKey(host, KeyEvent.KEYCODE_SPACE, "Jump", opacity)
                    SpacerKey(host, KeyEvent.KEYCODE_ESCAPE, "Menu", opacity)
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    KeyEvent.KEYCODE_1 to "1", KeyEvent.KEYCODE_2 to "2",
                    KeyEvent.KEYCODE_3 to "3", KeyEvent.KEYCODE_4 to "4",
                    KeyEvent.KEYCODE_5 to "5", KeyEvent.KEYCODE_6 to "6",
                    KeyEvent.KEYCODE_7 to "7", KeyEvent.KEYCODE_8 to "8",
                ).forEach { (key, label) -> SpacerKey(host, key, label, opacity) }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OverlayButton(
                label = if (visible) "Hide" else "Show",
                tag = "touch-overlay-toggle",
                opacity = opacity,
            ) { visible = !visible }
            OverlayButton(
                label = if (captured) "Cursor" else "Mouse",
                tag = "touch-pointer-capture",
                opacity = opacity,
            ) {
                captured = host.setPointerCapture(!captured)
            }
            OverlayButton(
                label = "Chat",
                tag = "touch-chat",
                opacity = opacity,
            ) { host.showIme() }
        }
    }
}

@Composable
private fun SpacerKey(host: ClientDisplayHost, keyCode: Int, label: String, opacity: Float) {
    Box(
        Modifier
            .size(if (label.length > 1) 58.dp else 40.dp, 40.dp)
            .background(Color.Black.copy(alpha = opacity), RoundedCornerShape(8.dp))
            .virtualKey(host, keyCode)
            .testTag("touch-key-$label"),
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
            .background(Color.Black.copy(alpha = opacity), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag)
            .pointerInput(tag) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White) }
}

private fun Modifier.virtualKey(host: ClientDisplayHost, keyCode: Int): Modifier =
    pointerInput(host.generation, keyCode) {
        detectTapGestures(
            onPress = {
                host.dispatchVirtualKey(keyCode, true)
                tryAwaitRelease()
                host.dispatchVirtualKey(keyCode, false)
            },
        )
    }
