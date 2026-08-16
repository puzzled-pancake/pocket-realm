package com.pocketrealm.ui

import android.content.Context

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
import androidx.compose.material3.OutlinedTextField
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
import com.pocketrealm.client.FaceLayer
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.client.OverlayControl
import com.pocketrealm.client.OverlayMode
import com.pocketrealm.client.Rp6Control
import com.pocketrealm.client.WowVanillaBindingCatalog
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

/** Durable editor shared by physical pads, keyboard/mouse, and touch input. */
@Composable
fun ControlsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val store = remember(context) { InputProfileStore(context) }
    var host by remember { mutableStateOf(IntegratedClientDisplay.host.value) }
    var profile by remember {
        // Controls must not manufacture a 16:9 aspect reset while no gameplay
        // host exists. The screen has no authority to validate a virtual
        // desktop; load the stored profile without normalization and defer
        // aspect validation to ClientDisplayHost at launch.
        mutableStateOf(loadProfileForControls(context))
    }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    var showAdvanced by remember { mutableStateOf(false) }
    var showBindings by remember { mutableStateOf(false) }
    var bindingSearch by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        IntegratedClientDisplay.host.collectLatest { next ->
            host = next
            if (next != null) next.profile.collect { profile = it }
        }
    }

    fun apply(updated: InputProfile) {
        val activeHost = host
        if (activeHost == null) {
            store.save(updated)
            profile = updated
        } else {
            // The live contract validates aspect/topology and performs a safe
            // held-input release before it publishes the effective profile.
            // Persist only that accepted result, never the unvalidated draft.
            activeHost.switchInputProfile(updated)
            val effective = activeHost.profile.value
            store.save(effective)
            profile = effective
        }
    }

    val touchControls: @Composable () -> Unit = {
        ChoiceRow(
            label = "Gameplay overlay",
            value = profile.overlayMode,
            choices = OverlayMode.values().toList(),
            display = { it.friendlyName() },
            tag = "overlay-mode",
        ) { apply(profile.copy(overlayMode = it)) }
        SupportingText(profile.overlayMode.explanation())
        if (showAdvanced) {
            ValueSlider(
                "Control size", profile.overlayScale, 0.75f..1.5f,
                "%.0f%%".format(profile.overlayScale * 100),
                "Scales touch buttons without changing the game resolution; hit areas never shrink below 48 dp.",
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
                "Sets how much of the right side can be dragged to turn the camera in Full mode.",
                onValueChange = { profile = profile.copy(cameraRegionWidth = it) },
                onValueChangeFinished = { apply(profile) },
            )
            ValueSlider(
                "Touch camera speed", profile.touchCameraSensitivity, 0.15f..1.0f,
                "%.0f%%".format(profile.touchCameraSensitivity * 100),
                "Controls touch-drag look speed only; 100% is the old maximum speed.",
                onValueChange = { profile = profile.copy(touchCameraSensitivity = it) },
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
        }
        if (showBindings) {
            HorizontalDivider()
            Text("Touch button assignments", fontWeight = FontWeight.SemiBold)
            SupportingText("Each visible button sends one keyboard, pointer, or camera action to WoW.")
            val actions = filteredActions(bindingSearch)
            OverlayControl.values().forEach { control ->
                BindingRow(
                    label = control.displayName,
                    tag = "overlay-binding-${control.name}",
                    action = InputProfile.actionFor(profile, control),
                    choices = actions.withCurrent(InputProfile.actionFor(profile, control)),
                ) { action ->
                    apply(profile.copy(
                        scheme = ControlScheme.CUSTOM,
                        overlayBindings = profile.overlayBindings + (control to action),
                    ))
                }
            }
        }
    }

    val physicalControls: @Composable () -> Unit = {
        SupportingText(
            if (profile.scheme == ControlScheme.ANDROID_PORT) {
                "Android Port combat keeps frequent actions direct: R1 targets the nearest enemy " +
                    "and L1 selects and uses the nearest eligible corpse, chest, or ordinary object. " +
                    "Select + L1 is the precise use-at-pointer fallback. L2/R2 are Shift/Ctrl action-page modifiers. " +
                    "Tap Select for the radial menu; its eight entries use face buttons 1-4 and D-pad 5-8. " +
                    "Select + R3 toggles camera/pointer, Select + R1 sends stock G / last hostile, " +
                    "and Select + L3 jumps. L3 by itself jumps whenever the camera is locked " +
                    "and right-clicks the pointer only while the cursor is free. " +
                    "Select + Start opens Move UI: drag a green handle with the pointer, " +
                    "D-pad Down/Up focuses a frame, D-pad Left/Right scales it, and Select + Start or Escape saves. " +
                    "M1/M2 stay disabled because they can latch on some units."
            } else {
                "The default layout puts Target on R1, Use / open at pointer on R2, and Jump on R3. " +
                    "Hold Select + R3 for camera/pointer mode or Select + R2 for left-click. " +
                    "While the camera is locked, L3 jumps instead of toggling auto-run. " +
                    "M1/M2 stay disabled because they can latch on some units."
            },
        )
        if (showAdvanced) ValueSlider(
            "Movement-stick dead zone", profile.deadZone, 0.05f..0.35f,
            "%.0f%%".format(profile.deadZone * 100),
            "Ignores small movement near the centre so the character does not drift.",
            onValueChange = { profile = profile.copy(deadZone = it) },
            onValueChangeFinished = { apply(profile) },
        )
        if (showAdvanced) ValueSlider(
            "Camera-stick dead zone", profile.rightStickDeadZone, 0.05f..0.35f,
            "%.0f%%".format(profile.rightStickDeadZone * 100),
            "Use a smaller value for precision, or a larger one to prevent camera drift.",
            onValueChange = { profile = profile.copy(rightStickDeadZone = it) },
            onValueChangeFinished = { apply(profile) },
        )
        if (showAdvanced) ValueSlider(
            "Right-stick pointer/camera speed", profile.cameraSensitivity, 0.25f..4f,
            "%.2fx".format(profile.cameraSensitivity),
            "Changes how far the free pointer or locked camera moves for the same right-stick movement.",
            onValueChange = { profile = profile.copy(cameraSensitivity = it) },
            onValueChangeFinished = { apply(profile) },
        )
        if (showAdvanced) Text("Analogue trigger timing", fontWeight = FontWeight.SemiBold)
        if (showAdvanced) SupportingText("Separate press and release points prevent trigger flicker near the threshold.")
        if (showAdvanced) ValueSlider(
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
        if (showAdvanced) ValueSlider(
            "L2 release point", profile.leftTriggerOffThreshold,
            0f..profile.leftTriggerOnThreshold,
            "%.0f%%".format(profile.leftTriggerOffThreshold * 100),
            "L2 releases after returning below this point.",
            onValueChange = { profile = profile.copy(leftTriggerOffThreshold = it) },
            onValueChangeFinished = { apply(profile) },
        )
        if (showAdvanced) ValueSlider(
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
        if (showAdvanced) ValueSlider(
            "R2 release point", profile.rightTriggerOffThreshold,
            0f..profile.rightTriggerOnThreshold,
            "%.0f%%".format(profile.rightTriggerOffThreshold * 100),
            "R2 releases after returning below this point.",
            onValueChange = { profile = profile.copy(rightTriggerOffThreshold = it) },
            onValueChangeFinished = { apply(profile) },
        )
        if (showBindings) {
            HorizontalDivider()
            Text("Physical button assignments", fontWeight = FontWeight.SemiBold)
            SupportingText("Editing an assignment changes the named layout to Custom and preserves everything else.")
            Rp6Control.values().forEach { control ->
                val current = InputProfile.actionFor(profile, control)
                val choices = filteredActions(bindingSearch).filter {
                    !control.axisDirection || it == ControllerAction.DISABLED || it.keyCode != null
                }.withCurrent(current)
                BindingRow(
                    label = control.displayName,
                    tag = "control-${control.name}",
                    action = current,
                    choices = choices,
                ) { action ->
                    apply(profile.copy(
                        scheme = ControlScheme.CUSTOM,
                        rp6Bindings = profile.rp6Bindings + (control to action),
                    ))
                }
            }
            LayerBindings(
                title = "L2 + face buttons (actions 5-8)",
                layer = FaceLayer.L2,
                profile = profile,
                choices = filteredActions(bindingSearch),
                apply = ::apply,
            )
            LayerBindings(
                title = "L1 + face buttons (actions 9-12)",
                layer = FaceLayer.L1,
                profile = profile,
                choices = filteredActions(bindingSearch),
                apply = ::apply,
            )

            val duplicates = duplicateOutputWarnings(profile)
            if (duplicates.isNotEmpty()) {
                Text("Duplicate outputs", fontWeight = FontWeight.SemiBold)
                SupportingText(
                    "Duplicates are allowed, but these controls currently send the same output: " +
                        duplicates.joinToString("; "),
                )
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

        ControlSection("Device and recommended layout") {
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
            SupportingText("The built-in layout uses only stock WoW keyboard and pointer actions.")
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
                    "Automatic recognizes this device’s built-in controller; other pads use Android-standard positions.",
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.testTag("controls-show-advanced"),
            ) { Text(if (showAdvanced) "Hide advanced tuning" else "Advanced tuning") }
            OutlinedButton(
                onClick = { showBindings = !showBindings },
                modifier = Modifier.testTag("controls-customize-bindings"),
            ) { Text(if (showBindings) "Close bindings" else "Customize bindings") }
        }
        if (showBindings) {
            OutlinedTextField(
                value = bindingSearch,
                onValueChange = { bindingSearch = it },
                label = { Text("Find an action") },
                supportingText = { Text("Search the complete available WoW key and pointer action list.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("binding-search"),
            )
            val catalogMatches = WowVanillaBindingCatalog.search(bindingSearch)
            ControlSection("WoW 1.12.1 action reference") {
                SupportingText(
                    "${WowVanillaBindingCatalog.userFacing.size} stock actions are documented, alongside every " +
                        "keyboard and mouse output supported by this app. This action list is reference-only: " +
                        "assign a supported output above, " +
                        "then bind that key to the action in WoW's Key Bindings screen.",
                )
                catalogMatches.take(CATALOG_PREVIEW_LIMIT).forEach { binding ->
                    Column {
                        Text("${binding.label} - ${binding.category.displayName}")
                        SupportingText("${binding.id}: ${binding.description}")
                    }
                }
                if (catalogMatches.size > CATALOG_PREVIEW_LIMIT) {
                    SupportingText(
                        "${catalogMatches.size - CATALOG_PREVIEW_LIMIT} more match. Add words to the search to narrow the list.",
                    )
                }
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
                "Unlocked mode makes the right stick a free cursor. Lock camera holds WoW's right mouse button " +
                    "so the same stick turns the view; unlocking releases every right-mouse owner and Android capture.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (profile.scheme == ControlScheme.ANDROID_PORT) {
                    "The leveling loop does not use Select: R1 directly targets the nearest living enemy and L1 " +
                        "selects and uses the nearest eligible corpse, chest, or ordinary object. Select + L1 sends " +
                        "the precise use-at-pointer fallback. Face buttons send actions 1-4 and D-pad sends 5-8; " +
                        "L2/R2 select the Shift/Ctrl pages. Select opens the eight-item radial menu, where those same " +
                        "face and D-pad inputs activate the shown item. Select + R3 toggles camera/pointer; in Pointer " +
                        "mode, hold R3 and move the right stick to drag movable add-on frames. Select + R1 sends stock " +
                    "G for last hostile and Select + L3 jumps. L3 by itself jumps whenever the camera " +
                    "is locked; it right-clicks the pointer only while the cursor is free. " +
                    "Automatic item collection remains an optional client tweak or add-on behavior."
                } else {
                    "For normal leveling: R1 or Target selects one nearest living enemy. " +
                        "Aim the pointer at a container, NPC, object, or lootable corpse, then press R2 or Use / open; " +
                        "this sends a normal right-click. Loot collection is intentionally not a controller action: " +
                        "enable the optional client tweak or an add-on if you want opened loot collected automatically. R3 jumps, " +
                        "face buttons use actions 1-4, L2 + face uses 5-8, and L1 + face uses 9-12. " +
                    "D-pad Up sends stock G for last hostile (not guaranteed to be a corpse), Down sends F1 for " +
                    "self or pet, Left sends B for the backpack, and Right sends L for the quest log. " +
                    "Hold Select + R3 to toggle camera or pointer mode. While the camera is locked, " +
                    "L3 jumps instead of toggling auto-run."
                },
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
private fun LayerBindings(
    title: String,
    layer: FaceLayer,
    profile: InputProfile,
    choices: List<ControllerAction>,
    apply: (InputProfile) -> Unit,
) {
    HorizontalDivider()
    Text(title, fontWeight = FontWeight.SemiBold)
    FACE_CONTROLS.forEach { control ->
        val current = checkNotNull(InputProfile.actionFor(profile, layer, control))
        BindingRow(
            label = control.displayName,
            tag = "layer-${layer.name}-${control.name}",
            action = current,
            choices = choices.withCurrent(current),
        ) { action ->
            val updatedLayer = profile.layerFaceBindings.getValue(layer) + (control to action)
            apply(profile.copy(
                scheme = ControlScheme.CUSTOM,
                layerFaceBindings = profile.layerFaceBindings + (layer to updatedLayer),
            ))
        }
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

private fun OverlayMode.friendlyName(): String = when (this) {
    OverlayMode.AUTO -> "Automatic"
    OverlayMode.MINIMAL -> "Controller utilities"
    OverlayMode.FULL -> "Full touch controls"
    OverlayMode.OFF -> "Off"
}

private fun OverlayMode.explanation(): String = when (this) {
    OverlayMode.AUTO -> "Shows only a compact utility drawer when a controller is active, and full controls for touch-only play."
    OverlayMode.MINIMAL -> "Shows camera or pointer mode, zoom, keyboard and app access without covering gameplay."
    OverlayMode.FULL -> "Shows movement, Target, Use / loot at pointer, Menu and paged action buttons for touch play."
    OverlayMode.OFF -> "Removes every overlay hit area; change this setting here to restore on-screen controls."
}

private fun filteredActions(search: String): List<ControllerAction> {
    val query = search.trim()
    return ControllerAction.values().filter { query.isEmpty() || it.displayName.contains(query, ignoreCase = true) }
}

private fun List<ControllerAction>.withCurrent(current: ControllerAction): List<ControllerAction> =
    if (current in this) this else listOf(current) + this

internal fun duplicateOutputWarnings(profile: InputProfile): List<String> {
    val assignments = buildList {
        Rp6Control.values().forEach { add(it.displayName to InputProfile.actionFor(profile, it)) }
        FaceLayer.values().forEach { layer ->
            FACE_CONTROLS.forEach { control ->
                InputProfile.actionFor(profile, layer, control)?.let { action ->
                    add("${layer.name} + ${control.displayName}" to action)
                }
            }
        }
    }.filterNot { it.second == ControllerAction.DISABLED }
    return assignments.groupBy(Pair<String, ControllerAction>::second)
        .filterValues { it.size > 1 }
        .map { (action, controls) -> "${action.displayName}: ${controls.joinToString { it.first }}" }
        .sorted()
}

private val FACE_CONTROLS = listOf(
    Rp6Control.FACE_BOTTOM,
    Rp6Control.FACE_LEFT,
    Rp6Control.FACE_TOP,
    Rp6Control.FACE_RIGHT,
)

private const val CATALOG_PREVIEW_LIMIT = 24

/**
 * Hostless Controls reads the durable record without applying aspect policy.
 * ClientDisplayHost remains the sole authority that may reset for a different
 * launched virtual desktop.
 */
internal fun loadProfileForControls(context: Context): InputProfile {
    val preferences = context.getSharedPreferences("pocket_input_profile", Context.MODE_PRIVATE)
    return CONTROL_PROFILE_KEYS.asSequence()
        .mapNotNull { preferences.getString(it, null) }
        .mapNotNull { raw -> runCatching { InputProfile.fromJson(JSONObject(raw)) }.getOrNull() }
        .firstOrNull()
        ?: InputProfile.DEFAULT
}

private val CONTROL_PROFILE_KEYS = listOf(
    "profile_v11", "profile_v10", "profile_v9", "profile_v8", "profile_v7", "profile_v6",
    "profile_v5", "profile_v4", "profile_v3", "profile_v2",
)
