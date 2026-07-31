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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.log.AppLog
import com.pocketrealm.storage.StorageRoots
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Provenance, storage health, and the structured log ring. */
@Composable
fun DiagnosticsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val roots = remember(context) { StorageRoots.get(context) }
    val report = remember(context) { roots.verify() }

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
