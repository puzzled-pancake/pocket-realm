package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.launch

/**
 * Bounded Advanced screen. Values here are presets within safe ranges; runtime
 * tuples / addon profiles / visual overlays are generation-managed elsewhere
 * (O17), not free text here.
 */
@Composable
fun SettingsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Advanced", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Runtime provider — FEX remains laboratory (flavor.json: Automatic never selects it).
        SettingCard("Runtime provider") {
            Settings.RuntimeProvider.values().forEach { p ->
                val selected = snap.provider == p
                AssistChip(
                    onClick = { scope.launch { settings.update { it.copy(provider = p) } } },
                    label = { Text(label(p)) },
                )
            }
            Text("FEX is Advanced/Laboratory; Automatic never selects it before qualification.",
                style = MaterialTheme.typography.bodySmall)
        }

        SettingCard("Renderer") {
            Settings.Renderer.values().forEach { r ->
                AssistChip(
                    onClick = { scope.launch { settings.update { it.copy(renderer = r) } } },
                    label = { Text(if (r == Settings.Renderer.DXVK) "DXVK" else "WineD3D (fallback)") },
                )
            }
        }

        SettingCard("Frame rate (${snap.fpsProfile.hz} FPS)") {
            Slider(
                value = snap.fpsProfile.ordinal.toFloat(),
                onValueChange = { idx ->
                    val profile = Settings.FpsProfile.values()[idx.toInt()]
                    scope.launch { settings.update { it.copy(fpsProfile = profile) } }
                },
                valueRange = 0f..(Settings.FpsProfile.values().lastIndex.toFloat()),
                steps = Settings.FpsProfile.values().lastIndex - 1,
            )
        }

        SettingCard("Bot population target (${snap.botPopulationTarget})") {
            Slider(
                value = snap.botPopulationTarget.toFloat(),
                onValueChange = { v ->
                    scope.launch { settings.update { it.copy(botPopulationTarget = v.toInt()) } }
                },
                valueRange = 100f..1500f,
            )
            Text("Hundreds of total bots on RP6; not a fixed visible capacity.",
                style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        SettingCard("Input safe mode") {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.inputSafeMode,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(inputSafeMode = enabled) } }
                    },
                    modifier = Modifier.testTag("input-safe-mode"),
                )
                Text("  Disable project addons and the touch overlay",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text("Realm and character data are not changed.",
                style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        SettingCard("Setup") {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.setupComplete,
                    onCheckedChange = { v -> scope.launch { settings.update { it.copy(setupComplete = v) } } },
                )
                Text("  First-run wizard complete", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun label(p: Settings.RuntimeProvider) = when (p) {
    Settings.RuntimeProvider.BOX64 -> "Box64 (default)"
    Settings.RuntimeProvider.FEX -> "FEX (Laboratory)"
}
