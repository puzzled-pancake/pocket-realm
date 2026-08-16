package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared settings-row primitives, hoisted from BotsScreen/ControlsScreen so
 * the in-game settings screens reuse the same 48 dp anatomy
 * (docs/UI_LANDSCAPE_REFACTOR_BRIEF_2026-08-15.md).
 */
internal object SettingsRowDefaults {
    val RowHeight = 48.dp
    val HorizontalPadding = 4.dp
}

@Composable
internal fun SettingRowLabel(
    label: String,
    support: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (support != null) {
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    tag: String,
    support: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowDefaults.RowHeight)
            .padding(horizontal = SettingsRowDefaults.HorizontalPadding)
            .toggleableRow(enabled, checked, onChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingRowLabel(label, support, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.testTag(tag))
    }
}

private fun Modifier.toggleableRow(
    enabled: Boolean,
    value: Boolean,
    onChange: (Boolean) -> Unit,
): Modifier = if (enabled) {
    Modifier.then(
        Modifier.clickable(enabled = true) { onChange(!value) }
            .semantics { role = Role.Switch },
    )
} else {
    Modifier
}

@Composable
internal fun FloatSteppedSlider(
    label: String,
    value: Float?,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    tag: String,
    enabled: Boolean = true,
    onCommit: (Float) -> Unit,
) {
    var raw by remember(value) { mutableStateOf(value ?: range.start) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowDefaults.RowHeight)
            .padding(horizontal = SettingsRowDefaults.HorizontalPadding),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SettingRowLabel(label)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = raw,
            onValueChange = { raw = it },
            onValueChangeFinished = {
                val snapped = ((raw / step).roundToInt() * step)
                    .coerceIn(range.start, range.endInclusive)
                raw = snapped
                onCommit(snapped)
            },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
internal fun ChoiceRow(
    label: String,
    selectedId: String?,
    choices: List<Pair<String, String>>,
    tag: String,
    support: String? = null,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowDefaults.RowHeight)
            .padding(horizontal = SettingsRowDefaults.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingRowLabel(label, support, Modifier.weight(1f))
        Column {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = SettingsRowDefaults.RowHeight).testTag(tag),
            ) {
                Text(choices.firstOrNull { it.first == selectedId }?.second
                    ?: selectedId ?: "—")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}
