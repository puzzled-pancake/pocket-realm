package com.pocketrealm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.log.AppLog
import com.pocketrealm.diagnostics.SupportBundleExporter
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Provenance, storage health, and the structured log ring. */
@Composable
fun DiagnosticsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val roots = remember(context) { StorageRoots.get(context) }
    val report = remember(context) { roots.verify() }
    val supervisor = remember(context) { RuntimeSupervisorClient(context) }
    val scope = rememberCoroutineScope()
    var maintenance by remember { mutableStateOf("No backup operation running") }
    var newestBackup by remember { mutableStateOf<String?>(null) }
    var supportStatus by remember { mutableStateOf("No support bundle created") }

    var logLines by remember { mutableStateOf(AppLog.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            logLines = AppLog.snapshot()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Storage roots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Mutable on internal storage: ${if (report.mutableRootIsInternal) "yes" else "NO"}",
                        style = MaterialTheme.typography.bodyMedium)
                    report.roots.forEach { r ->
                        Text("• ${r.name}: ${if (r.exists) "ok" else "MISSING"} · ${r.usableBytes / (1024 * 1024)} MB free",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup and restore", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Button(onClick = {
                        scope.launch {
                            val name = "backup-${System.currentTimeMillis()}"
                            val accepted = runCatching { supervisor.createBackup(name) }
                            maintenance = accepted.fold(
                                { if (it.optBoolean("ok")) "Backup accepted" else it.optString("error") },
                                { "Backup failed: ${it.javaClass.simpleName}" },
                            )
                            while (accepted.getOrNull()?.optBoolean("ok") == true) {
                                delay(500)
                                val status = runCatching { supervisor.backupStatus() }.getOrNull() ?: break
                                maintenance = "${status.optString("kind")}: ${status.optString("phase")}"
                                if (status.optString("phase") in setOf("COMPLETE", "FAILED")) break
                            }
                            newestBackup = runCatching { supervisor.listBackups() }.getOrNull()
                                ?.optJSONArray("backups")?.takeIf { it.length() > 0 }
                                ?.getJSONObject(0)?.getString("snapshotId")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create named backup") }
                    OutlinedButton(onClick = {
                        newestBackup?.let { id ->
                            scope.launch {
                                val accepted = runCatching { supervisor.restoreBackup(id) }
                                maintenance = accepted.fold(
                                    { if (it.optBoolean("ok")) "Restore verification accepted" else it.optString("error") },
                                    { "Restore failed: ${it.javaClass.simpleName}" },
                                )
                            }
                        }
                    }, enabled = newestBackup != null, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore newest backup")
                    }
                    Text(maintenance, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Redacted support bundle", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Button(onClick = {
                        scope.launch {
                            supportStatus = runCatching {
                                withContext(Dispatchers.IO) { SupportBundleExporter(context).export() }
                            }.fold(
                                { "Created ${it.entries} entries â€¢ manifest ${it.manifestSha256.take(12)}â€¦" },
                                { "Export failed: ${it.javaClass.simpleName}" },
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create support bundle") }
                    Text(supportStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Provenance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Sources: see schemas/sources.json (pinned submodules).",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Flavor: offline-vanilla-1.12 (see schemas/flavor.json).",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Text("Recent log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(logLines.takeLast(120)) { line ->
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(line.ts))
            Text(
                "$ts ${line.level.name.take(1)} ${line.kind}: ${line.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
            )
        }
    }
}
