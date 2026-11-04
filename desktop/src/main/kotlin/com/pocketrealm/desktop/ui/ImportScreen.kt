package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.DesktopClientScanner
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.importer.formatImportBytes
import com.pocketrealm.importer.formatImportPercent
import com.pocketrealm.importer.importPhaseBusy
import com.pocketrealm.importer.showsAsFilePath
import com.pocketrealm.importer.stageTitle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * the client import lane. A picked folder is scanned first (the
 * identity gates, VAL copy — rejecting a selection never copies it), then
 * imported into a managed generation: staging → hash-verify → fsync →
 * immutable publication → atomic per-flavor active-pointer switch. The
 * active generation's facts are shown at the top, so re-importing a new
 * client IS the generation switch (the Android managed-import model —
 * DESK-135's selection affordance lands here once a generation exists).
 */
@Composable
@Suppress("LongMethod") // one card per import stage, in lane order
fun ImportScreen(model: DesktopAppModel) {
    val scope = rememberCoroutineScope()
    // A capture-once remember would keep the pre-import state after a
    // successful import (the import IS the generation switch), so the lanes
    // re-read the pointer when they publish — see onGenerationPublished.
    var generation by remember { mutableStateOf(model.activeClientGeneration()) }
    val refreshGeneration: suspend () -> Unit = {
        generation = withContext(Dispatchers.IO) { model.activeClientGeneration() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Game client generation", style = MaterialTheme.typography.titleMedium)
                val active = generation // local capture: smart casts below
                when {
                    active == null -> Text(
                        // flavor-neutral — the
                        // import accepts any qualified flavor's client, so the
                        // copy never names one lane's build.
                        "No managed client generation is active. Import your " +
                            "genuine game client folder below — the app copies, " +
                            "verifies, and publishes it into app storage, so the " +
                            "original folder can move or change.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    active.broken != null -> Text(
                        "The active generation failed verification: ${active.broken}. " +
                            "Import the client folder again to republish it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            "Active: build ${active.version ?: active.clientId} " +
                                "(generation ${active.generation.take(8)}…)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Importing another client switches the active generation; " +
                                "the previous generation is retired automatically.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        ImportLaneCard(model, scope, onGenerationPublished = refreshGeneration)
        ArchiveImportLaneCard(model, scope, onGenerationPublished = refreshGeneration)
        ImportJournalCard(model)
    }
}

/**
 * the durable import view — the journal twin's latest row and
 * data stages rendered through the SHARED ImportProgressPresentation (the
 * same wording and fractions the Android import page shows). Polls while
 * visible; every read runs off the UI thread (the journal is SQLite).
 */
@Composable
private fun ImportJournalCard(model: DesktopAppModel) {
    var progress by remember {
        mutableStateOf(com.pocketrealm.importer.ImportProgressPresentation.idle())
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            progress = withContext(Dispatchers.IO) { model.importProgress() }
            delay(if (importPhaseBusy(progress.phase)) 1_000L else 5_000L)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import journal", style = MaterialTheme.typography.titleMedium)
            Text(progress.phaseTitle, style = MaterialTheme.typography.bodyMedium)
            Text(progress.explanation, style = MaterialTheme.typography.bodySmall)
            if (importPhaseBusy(progress.phase)) {
                Text(
                    formatImportPercent(progress.fileFraction) + " — " +
                        progress.filesProcessed + " of " + progress.filesTotal + " files, " +
                        formatImportBytes(progress.bytesCopied) + " of " +
                        formatImportBytes(progress.bytesTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            progress.currentPath?.takeIf { showsAsFilePath(progress.phase, it) }?.let { path ->
                Text(
                    "Current file: " + path + " (" +
                        formatImportBytes(progress.currentFileCopied) + " of " +
                        formatImportBytes(progress.currentFileTotal) + ")",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            progress.stages.forEach { stage ->
                Text(
                    stageTitle(stage.id) + " — " + stage.state.lowercase() +
                        (stage.checkpoint?.let { " · " + it } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            progress.error?.let { error ->
                Text("Last error: " + error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImportLaneCard(
    model: DesktopAppModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onGenerationPublished: suspend () -> Unit,
) {
    var picked by remember { mutableStateOf<File?>(null) }
    var scanSummary by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import from folder", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose the extracted game folder itself — the one that contains " +
                    "WoW.exe and its Data directory — not an installer or launcher folder.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !importing, onClick = {
                    pickClientFolder { folder ->
                        picked = folder
                        result = null
                        // The scan preview runs on the picker's worker thread
                        // (a 4.7 MB exe read must not touch the UI thread).
                        scanSummary = folder?.let { source ->
                            val scan = DesktopClientScanner().scan(source)
                            when {
                                scan.supported ->
                                    "Ready: ${scan.version} (build ${scan.build}) — " +
                                        scan.warnings.joinToString("; ").ifBlank { "no warnings" }
                                else -> "Refused: " + scan.failures.joinToString("; ")
                            }
                        }
                    }
                }) { Text(if (picked == null) "Choose client folder…" else "Choose another folder…") }
                Button(
                    enabled = !importing && picked != null && scanSummary?.startsWith("Ready") == true,
                    onClick = {
                        val source = picked ?: return@Button
                        importing = true
                        phase = "starting import"
                        result = null
                        scope.launch {
                            try {
                                val outcome = model.importClientFolder(source) { tick -> phase = tick }
                                result = "Published generation ${outcome.generation.take(8)}… " +
                                    "(${outcome.inventory.fileCount} files, " +
                                    "${outcome.inventory.totalBytes / (1024 * 1024)} MB). " +
                                    "It is now the active client."
                                phase = null
                                onGenerationPublished()
                            } catch (failure: Throwable) {
                                result = "Import failed: ${failure.message}"
                                phase = null
                            } finally {
                                importing = false
                            }
                        }
                    },
                ) { Text(if (importing) "Importing…" else "Import & publish generation") }
            }
            if (scanSummary != null) {
                Text(scanSummary!!, style = MaterialTheme.typography.bodySmall)
            }
            if (phase != null) {
                Text("Importing — ${phase ?: ""}…", style = MaterialTheme.typography.bodySmall)
            }
            if (result != null) {
                Text(result!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ArchiveImportLaneCard(
    model: DesktopAppModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onGenerationPublished: suspend () -> Unit,
) {
    var importing by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import from archive", style = MaterialTheme.typography.titleMedium)
            Text(
                "A .zip, .7z or .rar of the client works too — the archive is sniffed, the client " +
                    "detected (even one wrapper folder deep), and Inno Setup installers are " +
                    "extracted on the spot. Password-protected, multi-volume and solid-RAR4 " +
                    "archives are refused.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !importing,
                onClick = {
                    importing = true
                    phase = "picking archive"
                    result = null
                    pickArchiveFile { archive ->
                        if (archive == null) {
                            importing = false
                            phase = null
                            return@pickArchiveFile
                        }
                        phase = "detecting client in archive"
                        scope.launch {
                            try {
                                val outcome = model.importClientArchive(
                                    archive,
                                    onPhase = { tick -> phase = tick },
                                )
                                result = "Published generation ${outcome.generation.take(8)}… " +
                                    "(${outcome.inventory.fileCount} files). It is now the active client."
                                phase = null
                                onGenerationPublished()
                            } catch (failure: Throwable) {
                                result = "Import failed: ${failure.message}"
                                phase = null
                            } finally {
                                importing = false
                            }
                        }
                    }
                },
            ) { Text(if (importing) "Importing…" else "Choose client archive…") }
            if (phase != null) {
                Text("Importing — ${phase ?: ""}…", style = MaterialTheme.typography.bodySmall)
            }
            if (result != null) {
                Text(result!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The java.awt.FileDialog folder picker on a worker thread (the Home
 * picker's exact pattern — FileDialog blocks its OWN thread only). */
private fun pickClientFolder(onPicked: (File?) -> Unit) {
    Thread {
        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, "Choose the folder that contains WoW.exe")
        dialog.mode = java.awt.FileDialog.LOAD
        dialog.file = "WoW.exe"
        dialog.isVisible = true // blocks this worker thread until dismissed
        val dir = dialog.directory
        val folder = dir?.let { File(it) }?.takeIf { File(it, "WoW.exe").isFile }
        onPicked(folder)
    }.apply { isDaemon = true }.start()
}

/** The java.awt.FileDialog archive picker (the same worker-thread pattern).
 * FileDialog.setFilenameFilter is a documented no-op on Windows, so the
 * dialog offers every file and the SNIFFER is the gate: whatever is picked
 * flows into the archive lane, whose VAL-11/VAL-13 verdicts answer unknown
 * formats with the pinned copy instead of a silently non-filtering filter. */
private fun pickArchiveFile(onPicked: (File?) -> Unit) {
    Thread {
        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, "Choose the client archive (.zip / .7z / .rar / installer)")
        dialog.mode = java.awt.FileDialog.LOAD
        dialog.isVisible = true // blocks this worker thread until dismissed
        val file = dialog.file?.let { name -> dialog.directory?.let { dir -> File(dir, name) } }
            ?.takeIf { it.isFile }
        onPicked(file)
    }.apply { isDaemon = true }.start()
}
