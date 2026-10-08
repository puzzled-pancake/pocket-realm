package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import kotlinx.coroutines.launch

/**
 * DESK-44/69/102: the in-app world-data preparation card — the progress
 * surface the register names for every placement: DESK-44's "Settings/
 * Diagnostics progress card", DESK-69's Settings "Prepare world data" flow,
 * and DESK-102's "progress card on Home". One card, three placements:
 * HomeScreen, SettingsScreen and DiagnosticsScreen all render this; the lane
 * itself runs through DataPreparationHost →
 * com.pocketrealm.importer.DataPreparationStore (checkpointed DBC_MAPS →
 * VMAP_EXTRACT → VMAP_ASSEMBLE → MMAPS → MANIFEST over the staged extractor
 * EXEs, published through the shared PreparedDataStore).
 */
@Composable
internal fun DataPreparationCard(model: DesktopAppModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(model.worldDataStatus()) }
    var preparing by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server world data", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    status.ready -> "Ready (${status.detail}). Starting the realm uses this prepared data."
                    else -> "Not prepared yet — the realm needs generated maps and collision " +
                        "data before it can start. This runs once per client import and can " +
                        "resume if interrupted."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !preparing,
                onClick = {
                    preparing = true
                    result = null
                    phase = "starting"
                    scope.launch {
                        try {
                            val outcome = model.prepareWorldData { tick -> phase = tick }
                            result = "World data ready — generation ${outcome.id.take(8)}… published."
                            status = model.worldDataStatus()
                            phase = null
                        } catch (failure: Throwable) {
                            result = "Preparation failed: ${failure.message}"
                            phase = null
                        } finally {
                            preparing = false
                        }
                    }
                },
            ) {
                Text(
                    if (preparing) "Preparing world data…" else if (status.ready) "Re-prepare world data…" else "Prepare world data…",
                )
            }
            if (phase != null) {
                Text("Working — ${phase ?: ""}…", style = MaterialTheme.typography.bodySmall)
            }
            if (result != null) {
                Text(result!!, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
