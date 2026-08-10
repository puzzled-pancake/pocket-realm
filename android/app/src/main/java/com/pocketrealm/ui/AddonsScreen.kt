package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrealm.addons.AddonRepository

/** Public-GitHub addon installation with immutable commits and Vanilla TOC validation. */
@Composable
fun AddonsScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val state by repository.state.collectAsState()
    var url by remember { mutableStateOf("") }
    val busy = state.operation != null

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add-ons", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Install Vanilla 1.12 add-ons from a public GitHub repository. Pocket Realm pins the exact " +
                "commit, rejects unsafe archives, and applies changes only when the game next starts.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a repository", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Public GitHub URL") },
                    supportingText = { Text("Example format: https://github.com/owner/addon") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("addon-repository-url"),
                )
                Button(
                    onClick = { repository.install(url) },
                    enabled = !busy && url.isNotBlank(),
                    modifier = Modifier.testTag("addon-install"),
                ) { Text("Check and install") }
                if (url.isBlank()) {
                    Text("Enter a repository URL to enable installation.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Add-on Lua runs inside the game. Only install repositories you trust; a valid " +
                        "download and checksum do not prove that its code is harmless.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        state.operation?.let { operation ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(operation.stage.label, fontWeight = FontWeight.SemiBold)
                    Text(operation.repository, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (operation.bytesTotal != null && operation.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (operation.bytesDone.toFloat() / operation.bytesTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (operation.cancellable) {
                        OutlinedButton(onClick = repository::cancelCurrent) { Text("Cancel") }
                    } else {
                        Text(
                            "Finishing the change safely…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.error?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.errorTitle ?: "Could not complete add-on change",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(error)
                    Text("The installed add-on set was not changed.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Installed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (repository.canRollback() && !busy) {
                OutlinedButton(onClick = repository::rollback) { Text("Undo last change") }
            }
        }
        if (state.installed.isEmpty()) {
            Text("No project-managed add-ons are installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.installed.forEach { addon ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(addon.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(addon.repository, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Commit ${addon.commitSha.take(12)} · ${addon.folders.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { repository.install(addon.repository) },
                            enabled = !busy,
                        ) { Text("Check for update") }
                        OutlinedButton(
                            onClick = { repository.remove(addon.id) },
                            enabled = !busy,
                        ) { Text("Remove") }
                    }
                }
            }
        }
    }
}
