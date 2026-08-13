package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.ControllerFamily
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.client.FaceButtonLayout
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.client.OverlayControl
import com.pocketrealm.client.Rp6Control
import kotlinx.coroutines.flow.collectLatest

/** Durable editor shared by physical pads, keyboard/mouse, and touch input. */
@Composable
fun ControlsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val store = remember(context) { InputProfileStore(context) }
    var host by remember { mutableStateOf(IntegratedClientDisplay.host.value) }
    var profile by remember {
        mutableStateOf(store.load(InputProfile.DEFAULT_ASPECT_IDENTITY).profile)
    }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp

    LaunchedEffect(Unit) {
        IntegratedClientDisplay.host.collectLatest { next ->
            host = next
            if (next != null) next.profile.collect { profile = it }
        }
    }

    fun apply(updated: InputProfile) {
        store.save(updated)
        profile = updated
        host?.switchInputProfile(updated)
    }

    val touchControls: @Composable () -> Unit = {
        ToggleRow(
            label = "Show the on-screen controls",
            explanation = "Keeps touch movement, actions, camera drag, and the keyboard available over the game.",
            checked = profile.overlayEnabled,
        ) { apply(profile.copy(overlayEnabled = it)) }
        ValueSlider(
            "Control size", profile.overlayScale, 0.75f..1.5f,
            "%.0f%%".format(profile.overlayScale * 100),
            "Scales touch buttons without changing the game resolution.",
            onValueChange = { profile = profile.copy(overlayScale = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "Opacity", profile.overlayOpacity, 0.35f..1f,
            "%.0f%%".format(profile.overlayOpacity * 100),
            "Lower values reveal more of the world behind the controls.",
            onValueChange = { profile = profile.copy(overlayOpacity = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "Camera-drag area", profile.cameraRegionWidth, 0.25f..0.7f,
            "%.0f%%".format(profile.cameraRegionWidth * 100),
            "Controls how much of the centre screen can be dragged to turn the camera.",
            onValueChange = { profile = profile.copy(cameraRegionWidth = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ToggleRow(
            "Invert horizontal look", "Reverses left and right camera movement.",
            profile.invertCameraX,
        ) { apply(profile.copy(invertCameraX = it)) }
        ToggleRow(
            "Invert vertical look", "Reverses up and down camera movement.",
            profile.invertCameraY,
        ) { apply(profile.copy(invertCameraY = it)) }
        HorizontalDivider()
        Text("Touch button assignments", fontWeight = FontWeight.SemiBold)
        SupportingText("Each button sends one allowlisted keyboard, pointer, or camera action to WoW.")
        OverlayControl.values().forEach { control ->
            BindingRow(
                label = control.displayName,
                tag = "overlay-binding-${control.name}",
                action = InputProfile.actionFor(profile, control),
                choices = ControllerAction.values().toList(),
            ) { action ->
                apply(profile.copy(
                    scheme = ControlScheme.CUSTOM,
                    overlayBindings = profile.overlayBindings + (control to action),
                ))
            }
        }
    }

    val physicalControls: @Composable () -> Unit = {
        ValueSlider(
            "Movement-stick dead zone", profile.deadZone, 0.05f..0.35f,
            "%.0f%%".format(profile.deadZone * 100),
            "Ignores small movement near the centre so the character does not drift.",
            onValueChange = { profile = profile.copy(deadZone = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "Camera-stick dead zone", profile.rightStickDeadZone, 0.05f..0.35f,
            "%.0f%%".format(profile.rightStickDeadZone * 100),
            "Use a smaller value for precision, or a larger one to prevent camera drift.",
            onValueChange = { profile = profile.copy(rightStickDeadZone = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "Pointer and camera speed", profile.cameraSensitivity, 0.25f..4f,
            "%.2fx".format(profile.cameraSensitivity),
            "Changes how far the pointer or camera moves for the same stick movement.",
            onValueChange = { profile = profile.copy(cameraSensitivity = it) },
            onValueChangeFinished = { apply(profile) },
        )
        Text("Analogue trigger timing", fontWeight = FontWeight.SemiBold)
        SupportingText("Separate press and release points prevent trigger flicker near the threshold.")
        ValueSlider(
            "L2 press point", profile.leftTriggerOnThreshold, 0.1f..0.9f,
            "%.0f%%".format(profile.leftTriggerOnThreshold * 100),
            "How far L2 must travel before its mapped action is pressed.",
            onValueChange = {
                profile = profile.copy(
                    leftTriggerOnThreshold = it,
                    leftTriggerOffThreshold = profile.leftTriggerOffThreshold.coerceAtMost(it),
                )
            },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "L2 release point", profile.leftTriggerOffThreshold,
            0f..profile.leftTriggerOnThreshold,
            "%.0f%%".format(profile.leftTriggerOffThreshold * 100),
            "L2 releases after returning below this point.",
            onValueChange = { profile = profile.copy(leftTriggerOffThreshold = it) },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "R2 press point", profile.rightTriggerOnThreshold, 0.1f..0.9f,
            "%.0f%%".format(profile.rightTriggerOnThreshold * 100),
            "How far R2 must travel before its mapped action is pressed.",
            onValueChange = {
                profile = profile.copy(
                    rightTriggerOnThreshold = it,
                    rightTriggerOffThreshold = profile.rightTriggerOffThreshold.coerceAtMost(it),
                )
            },
            onValueChangeFinished = { apply(profile) },
        )
        ValueSlider(
            "R2 release point", profile.rightTriggerOffThreshold,
            0f..profile.rightTriggerOnThreshold,
            "%.0f%%".format(profile.rightTriggerOffThreshold * 100),
            "R2 releases after returning below this point.",
            onValueChange = { profile = profile.copy(rightTriggerOffThreshold = it) },
            onValueChangeFinished = { apply(profile) },
        )
        HorizontalDivider()
        Text("Physical button assignments", fontWeight = FontWeight.SemiBold)
        SupportingText("Editing any assignment changes the named layout to Custom while preserving every other setting.")
        Rp6Control.values().forEach { control ->
            val choices = ControllerAction.values().filter {
                !control.axisDirection || it == ControllerAction.DISABLED || it.keyCode != null
            }
            BindingRow(
                label = control.displayName,
                tag = "control-${control.name}",
                action = InputProfile.actionFor(profile, control),
                choices = choices,
            ) { action ->
                apply(profile.copy(
                    scheme = ControlScheme.CUSTOM,
                    rp6Bindings = profile.rp6Bindings + (control to action),
                ))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Controls", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (host == null) "Saved settings will be used the next time the game opens."
            else "Changes are live. Held inputs are released safely before a new map is applied.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ControlSection("Control profile") {
            ChoiceRow(
                label = "Layout",
                value = profile.scheme,
                choices = ControlScheme.values().toList(),
                display = { it.displayName },
                tag = "control-scheme",
            ) { scheme ->
                apply(InputProfile.profileForScheme(scheme, profile.aspectIdentity, profile))
            }
            SupportingText(profile.scheme.description)
            SupportingText(
                "PocketRealmPad is optional on the RP6. Choose Built-in WoW controls for a complete add-on-free layout.",
            )
            ChoiceRow(
                label = "Input device",
                value = profile.controllerFamily,
                choices = ControllerFamily.values().toList(),
                display = { it.displayName },
                tag = "controller-family",
            ) { family ->
                val faceLayout = when (family) {
                    ControllerFamily.RETROID_POCKET_6 -> FaceButtonLayout.RP6_PRINTED
                    ControllerFamily.XBOX,
                    ControllerFamily.PLAYSTATION,
                    ControllerFamily.GENERIC -> FaceButtonLayout.ANDROID_STANDARD
                    else -> profile.faceButtonLayout
                }
                apply(profile.copy(controllerFamily = family, faceButtonLayout = faceLayout))
            }
            SupportingText(profile.controllerFamily.description)
            if (profile.controllerFamily !in setOf(
                    ControllerFamily.AUTO,
                    ControllerFamily.KEYBOARD_MOUSE,
                    ControllerFamily.TOUCH_ONLY,
                )) {
                ChoiceRow(
                    label = "Face-button positions",
                    value = profile.faceButtonLayout,
                    choices = FaceButtonLayout.values().toList(),
                    display = { it.displayName },
                    tag = "face-button-layout",
                ) { layout -> apply(profile.copy(faceButtonLayout = layout)) }
                SupportingText(
                    "Choose positions rather than printed letters when a pad reports A/B/X/Y differently.",
                )
            } else if (profile.controllerFamily == ControllerFamily.AUTO) {
                SupportingText(
                    "Automatic recognizes the built-in RP6 controller; other pads use Android-standard positions.",
                )
            }
            if (profile.scheme == ControlScheme.POCKET_REALM_PAD ||
                profile.scheme == ControlScheme.POCKET_REALM_PAD_CAMERA) {
                SupportingText(
                    "PocketRealmPad 0.5 uses matching action, layer, navigation, target, bags, and jump commands in WoW.",
                )
            }
        }

        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ControlSection("On-screen controls", Modifier.weight(1f), touchControls)
                ControlSection("Physical controller", Modifier.weight(1f), physicalControls)
            }
        } else {
            ControlSection("On-screen controls", content = touchControls)
            ControlSection("Physical controller", content = physicalControls)
        }

        ControlSection("Keyboard, mouse, and protected Android controls") {
            Text(
                "A physical keyboard and mouse are passed directly to WoW in every mode except touch-only. " +
                    "Choose Keyboard & mouse to ignore gamepads, or Touch-only to ignore all physical gameplay input.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The right stick controls WoW's camera directly. A connected USB/Bluetooth mouse is captured " +
                    "automatically when you click inside the game; Back releases it before leaving the game screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Home, Back, app switching, volume, and power always remain Android controls and cannot be reassigned.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = { apply(InputProfile.DEFAULT.copy(aspectIdentity = profile.aspectIdentity)) },
            modifier = Modifier.testTag("input-reset-defaults"),
        ) { Text("Restore recommended defaults") }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun ControlSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            SupportingText(explanation)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    explanation: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Text("$label - $formattedValue")
    SupportingText(explanation)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
    )
}

@Composable
private fun SupportingText(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    value: T,
    choices: List<T>,
    display: (T) -> String,
    tag: String,
    onChoice: (T) -> Unit,
) {
    var expanded by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) {
                Text(display(value))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(display(choice)) },
                        onClick = {
                            expanded = false
                            onChoice(choice)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    label: String,
    tag: String,
    action: ControllerAction,
    choices: List<ControllerAction>,
    onAction: (ControllerAction) -> Unit,
) {
    var expanded by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) {
                Text(action.displayName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.displayName) },
                        onClick = {
                            expanded = false
                            onAction(choice)
                        },
                    )
                }
            }
        }
    }
}
