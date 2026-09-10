package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopSupportBundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Diagnostics screen: storage-root health, the supervisor journal's
 * last durable state, the redacted support bundle (the shared
 * SecretRedactor over the desktop's own artifacts), and the recent app
 * log tail. The Android screen's Wine-session evidence does not exist on
 * Windows (the client is native); crash dumps are Windows' own.
 */
@Composable
@Suppress("LongMethod", "MagicNumber") // log-tail size and poll cadence
fun DiagnosticsScreen(model: DesktopAppModel) {
    val snapshot by model.state.collectAsState()
    var bundleStatus by remember { mutableStateOf<String?>(null) }
    var logTail by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            // File I/O stays off the UI thread (the Android twin's ring
            // buffer became an on-disk file here); the list is assigned
            // only when the tail actually changed so idle logs do not
            // recompose the card every poll.
            val next = kotlinx.coroutines.withContext(Dispatchers.IO) { readLogTail(model) }
            if (next != logTail) {
                logTail = next
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Storage roots", style = MaterialTheme.typography.titleMedium)
                Text("Base: ${model.roots.root}", style = MaterialTheme.typography.bodySmall)
                listOf(
                    "realm" to model.roots.realm,
                    "database" to model.roots.database,
                    "content" to model.roots.content,
                    "runtime" to model.roots.runtime,
                    "settings" to model.roots.settings,
                ).forEach { (name, dir) ->
                    Text(
                        "$name: ${if (dir.isDirectory) "ok" else "MISSING"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val freeMegs = runCatching {
                    model.roots.root.usableSpace / (1024L * 1024L)
                }.getOrDefault(-1L)
                Text("usable space: $freeMegs MiB", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Supervisor journal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "phase=${snapshot.phase} clean=${snapshot.clean} last=${snapshot.lastDurableAction}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                snapshot.lastError?.let {
                    Text(
                        "error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Redacted support bundle", style = MaterialTheme.typography.titleMedium)
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        bundleStatus = "Working…"
                        scope.launch {
                            bundleStatus = withContext(Dispatchers.IO) {
                                runCatching { DesktopSupportBundle(model.roots).export() }
                                    .getOrElse { failure -> "Export failed: ${failure.javaClass.simpleName}" }
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Working…" else "Create support bundle") }
                Text(
                    bundleStatus ?: "No support bundle created. Bundles carry the app log, the " +
                        "supervisor journal, the prepared-data pointer and the runtime conf " +
                        "(secrets redacted) as a zip next to the logs.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent log", style = MaterialTheme.typography.titleMedium)
                if (logTail.isEmpty()) {
                    Text("(empty)", style = MaterialTheme.typography.bodySmall)
                }
                logTail.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Suppress("MagicNumber") // the 120-line tail mirrors the Android diagnostics card
private fun readLogTail(model: DesktopAppModel): List<String> {
    // DesktopLog writes pocket-realm.log; the name is single-sourced with
    // DesktopSupportBundle.APP_LOG_FILE_NAME.
    val file = java.io.File(
        model.roots.logs,
        com.pocketrealm.desktop.DesktopSupportBundle.APP_LOG_FILE_NAME,
    )
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines(Charsets.UTF_8).takeLast(120)
    }.getOrDefault(emptyList())
}
