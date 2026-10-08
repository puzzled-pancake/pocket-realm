package com.pocketrealm.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.FaceLayer
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.Rp6Control
import com.pocketrealm.desktop.DesktopAppModel
import java.awt.event.KeyEvent as AwtKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * DESK-4: the gamepad controls editor — the scheme picker (stock leveling /
 * Android Port / custom), the per-button action map (main + the two face
 * layers), stick dead zones, camera sensitivity and trigger hysteresis, and
 * the gamepad bridge toggle. The profile is the desktop twin of the Android
 * versioned input profile: the same v12 JSON record, persisted under the app
 * settings root.
 *
 * Action targets carry Windows virtual-key codes (the [Vk] table); the
 * bridge injects them via SendInput while WoW.exe owns the foreground.
 */
@Composable
@Suppress("LongMethod") // the editor card is one composed column by design
fun ControlsScreen(model: DesktopAppModel) {
    val store = model.inputProfileStore
    var profile by remember {
        mutableStateOf(store.loadStoredOrDefault().copy(aspectIdentity = InputProfile.DEFAULT_ASPECT_IDENTITY))
    }
    var bridgeOn by remember { mutableStateOf(model.gamepadBridge.injecting) }
    var bridgeNote by remember {
        mutableStateOf(if (model.gamepadBridge.gamepadSupported()) "" else "no XInput driver found — the bridge is inert on this machine")
    }
    var editing by remember { mutableStateOf<Pair<Rp6Control, FaceLayer?>?>(null) }

    fun save(next: InputProfile) {
        profile = next
        store.save(next)
    }

    Column(
        modifier = Modifier.padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Gamepad controls", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A physical controller plays the stock client through the profile's " +
                        "mapped keys: the bridge reads the pad (XInput) and injects the " +
                        "mapped keyboard and mouse events while the game window is " +
                        "foreground. No add-on is required for the stock leveling layout.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = bridgeOn,
                        onCheckedChange = { on ->
                            bridgeOn = on
                            if (on) {
                                model.startGamepadBridgeIfUseful()
                                bridgeNote = if (model.gamepadBridge.gamepadSupported()) {
                                    "injecting while the game window is foreground"
                                } else {
                                    "no XInput driver found — the bridge is inert on this machine"
                                }
                            } else {
                                model.gamepadBridge.stop()
                                bridgeNote = "bridge stopped"
                            }
                        },
                    )
                    Text("Gamepad bridge", style = MaterialTheme.typography.titleMedium)
                }
                if (bridgeNote.isNotEmpty()) {
                    Text(bridgeNote, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Control scheme", style = MaterialTheme.typography.titleMedium)
                ControlScheme.entries.forEach { scheme ->
                    Row(
                        modifier = Modifier.clickable {
                            save(InputProfile.profileForScheme(scheme, profile.aspectIdentity, profile))
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = profile.scheme == scheme,
                            onClick = {
                                save(InputProfile.profileForScheme(scheme, profile.aspectIdentity, profile))
                            },
                        )
                        Column {
                            Text(scheme.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                scheme.description,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Per-button map", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Click a control to choose its output. Editing anything switches the " +
                        "scheme to Custom (the stock layouts stay re-selectable above).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Rp6Control.entries.forEach { control ->
                    val layer = InputProfile.actionFor(profile, control)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = control to null }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            control.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(220.dp),
                        )
                        Text(
                            layer.displayName,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                FaceLayer.entries.forEach { face ->
                    Text(face.name + " layer face buttons", style = MaterialTheme.typography.titleSmall)
                    InputProfile.FACE_CONTROLS.forEach { control ->
                        val layerAction = InputProfile.actionFor(profile, face, control)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editing = control to face }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                control.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(220.dp),
                            )
                            Text(
                                layerAction?.displayName ?: "(layer inactive)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stick and trigger tuning", style = MaterialTheme.typography.titleMedium)
                TuningSlider(
                    label = "Left stick dead zone",
                    value = profile.deadZone,
                    range = 0f..0.5f,
                ) { save(profile.copy(deadZone = it)) }
                TuningSlider(
                    label = "Camera sensitivity",
                    value = profile.cameraSensitivity,
                    range = 0.25f..4f,
                ) { save(profile.copy(cameraSensitivity = it)) }
                TuningSlider(
                    label = "Left trigger on threshold",
                    value = profile.leftTriggerOnThreshold,
                    range = 0.1f..0.9f,
                ) { save(profile.copy(leftTriggerOnThreshold = it)) }
                TuningSlider(
                    label = "Right trigger on threshold",
                    value = profile.rightTriggerOnThreshold,
                    range = 0.1f..0.9f,
                ) { save(profile.copy(rightTriggerOnThreshold = it)) }
            }
        }
    }

    val target = editing
    if (target != null) {
        val (control, face) = target
        var filter by remember(target) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Choose output for " + control.displayName) },
            text = {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter outputs") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.width(420.dp)) {
                    ControllerAction.entries
                        .filter { action ->
                            filter.isBlank() ||
                                action.displayName.contains(filter, ignoreCase = true) ||
                                action.name.contains(filter, ignoreCase = true)
                        }
                        .forEach { action ->
                            item(key = action.name) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val next = if (face == null) {
                                                profile.copy(
                                                    scheme = ControlScheme.CUSTOM,
                                                    rp6Bindings = profile.rp6Bindings + (control to action),
                                                )
                                            } else {
                                                val layers = profile.layerFaceBindings.toMutableMap()
                                                val layerMap = (layers[face] ?: mutableMapOf()).toMutableMap()
                                                layerMap[control] = action
                                                layers[face] = layerMap
                                                profile.copy(
                                                    scheme = ControlScheme.CUSTOM,
                                                    layerFaceBindings = layers,
                                                )
                                            }
                                            save(next)
                                            editing = null
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(action.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                }
            }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var local by remember(label, value) { mutableStateOf(value) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(240.dp))
            Text(String.format("%.2f", local), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(local) },
        )
    }
}
