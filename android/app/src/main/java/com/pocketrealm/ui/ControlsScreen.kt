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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.client.OverlayControl
import com.pocketrealm.client.Rp6Control
import kotlinx.coroutines.flow.collectLatest

/** Complete, durable editor for the physical RP6 and on-screen control maps. */
@Composable
fun ControlsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val store = remember(context) { InputProfileStore(context) }
    var host by remember { mutableStateOf(IntegratedClientDisplay.host.value) }
    var profile by remember {
        mutableStateOf(store.load(InputProfile.DEFAULT_ASPECT_IDENTITY).profile)
    }

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

        ControlSection("Touch controls") {
            ToggleRow("Show the on-screen controls", profile.overlayEnabled) {
                apply(profile.copy(overlayEnabled = it))
            }
            ValueSlider(
                "Control size", profile.overlayScale, 0.75f..1.5f,
                "%.0f%%".format(profile.overlayScale * 100),
                onValueChange = { profile = profile.copy(overlayScale = it) },
                onValueChangeFinished = { apply(profile) },
            )
            ValueSlider(
                "Opacity", profile.overlayOpacity, 0.35f..1f,
                "%.0f%%".format(profile.overlayOpacity * 100),
                onValueChange = { profile = profile.copy(overlayOpacity = it) },
                onValueChangeFinished = { apply(profile) },
            )
            ValueSlider(
                "Mouse-look area", profile.cameraRegionWidth, 0.25f..0.7f,
                "%.0f%%".format(profile.cameraRegionWidth * 100),
                onValueChange = { profile = profile.copy(cameraRegionWidth = it) },
                onValueChangeFinished = { apply(profile) },
            )
            ToggleRow("Invert horizontal look", profile.invertCameraX) { apply(profile.copy(invertCameraX = it)) }
            ToggleRow("Invert vertical look", profile.invertCameraY) { apply(profile.copy(invertCameraY = it)) }
            HorizontalDivider()
            OverlayControl.values().forEach { control ->
                BindingRow(
                    label = control.displayName,
                    tag = "overlay-binding-${control.name}",
                    action = InputProfile.actionFor(profile, control),
                    choices = ControllerAction.values().toList(),
                ) { action -> apply(profile.copy(overlayBindings = profile.overlayBindings + (control to action))) }
            }
        }

        ControlSection("Retroid Pocket controller") {
            ValueSlider(
                "Stick dead zone", profile.deadZone, 0.05f..0.35f,
                "%.0f%%".format(profile.deadZone * 100),
                onValueChange = { profile = profile.copy(deadZone = it) },
                onValueChangeFinished = { apply(profile) },
            )
            ValueSlider(
                "Pointer and camera speed", profile.cameraSensitivity, 0.25f..4f,
                "%.2fx".format(profile.cameraSensitivity),
                onValueChange = { profile = profile.copy(cameraSensitivity = it) },
                onValueChangeFinished = { apply(profile) },
            )
            HorizontalDivider()
            Rp6Control.values().forEach { control ->
                val choices = ControllerAction.values().filter {
                    !control.axisDirection || it == ControllerAction.DISABLED || it.keyCode != null
                }
                BindingRow(
                    label = control.displayName,
                    tag = "control-${control.name}",
                    action = InputProfile.actionFor(profile, control),
                    choices = choices,
                ) { action -> apply(profile.copy(rp6Bindings = profile.rp6Bindings + (control to action))) }
            }
        }

        ControlSection("Protected Android controls") {
            Text(
                "Home, Back, app switching, volume, and power always remain Android controls. " +
                    "They cannot be captured or reassigned by a game profile.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = {
                apply(InputProfile.DEFAULT.copy(aspectIdentity = profile.aspectIdentity))
            },
            modifier = Modifier.testTag("input-reset-defaults"),
        ) { Text("Restore WoW – Retroid defaults") }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun ControlSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Text("$label · $formattedValue")
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
    )
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
