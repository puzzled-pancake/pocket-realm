package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.pocketrealm.desktop.DesktopAppModel

/**
 * The Settings screen, Windows-relevant subset: the AI LLM pointer card,
 * the verbose world log toggle, auto-login, the update check, and the
 * provenance/about card (the Android renderer/display/input sections died
 * with the client stack).
 */
@Composable
@Suppress("LongMethod")
fun SettingsScreen(model: DesktopAppModel, onOpenLlm: () -> Unit) {
    val settings by model.settings.collectAsState()
    val storedAccount by model.storedAccount.collectAsState()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val checkScope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI bot LLM", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Playerbots speak through an external OpenAI-compatible server " +
                        "(configured in the LLM destination). Off keeps bots silent. The switch " +
                        "applies at the next realm start; authored banter and ambient chatter " +
                        "have their own switches in the LLM destination.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenLlm) { Text("Configure AI bot LLM ->") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("World debug logs", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = settings.worldDebugLogs,
                        onCheckedChange = { value ->
                            model.updateSettings { it.copy(worldDebugLogs = value) }
                        },
                    )
                    Text("Verbose world-server log", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Raises the world log level to verbose when the realm next starts and can " +
                        "grow world.log very large. Keep it off unless investigating a problem.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto-login", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = settings.autoLoginOnLaunch,
                        onCheckedChange = { value ->
                            model.updateSettings { it.copy(autoLoginOnLaunch = value) }
                        },
                    )
                    Text("Log in automatically when the client opens", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (storedAccount != null) {
                        "Stored account: ${storedAccount!!.username}"
                    } else {
                        "No stored account - create one on Home after the realm is online."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (storedAccount != null) {
                    OutlinedButton(onClick = { model.clearAccount() }) { Text("Clear") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Updates", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The Windows port is unsigned and does not self-update; releases ship as " +
                        "new packages. The provenance card below identifies the exact build.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(enabled = !checking, onClick = {
                    checking = true
                    checkScope.launch {
                        // Network I/O stays off the UI thread: a slow or
                        // unreachable feed must not freeze the window
                        // (timeouts are 5s connect + 5s read).
                        val status = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO,
                        ) { com.pocketrealm.desktop.UpdateCheck.checkOnce(model.roots) }
                        updateStatus = status
                        checking = false
                    }
                }) { Text(if (checking) "Checking…" else "Check for updates") }
                updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pocket Realm for Windows ${com.pocketrealm.desktop.APP_VERSION}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Native runtime: ${com.pocketrealm.BuildConfig.NATIVE_RUNTIME_BUILD_ID}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Storage: ${model.roots.root}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Sources: pinned submodules (schemas/sources.json); realm lane pins in " +
                        "schemas/realm-runtime-lockfile-sqlite-win.json.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}


