package com.pocketrealm.ui

import android.content.Intent
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ClientManifest
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.ClientRuntimeProvider
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.ClientState
import com.pocketrealm.client.DeviceCaps
import com.pocketrealm.client.LaunchRequest
import com.pocketrealm.client.PrefixRequest
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.importer.ImportWorkerService
import com.pocketrealm.importer.ImportProgressPresentation
import com.pocketrealm.importer.ImportStageProgress
import com.pocketrealm.importer.formatImportBytes
import com.pocketrealm.importer.formatImportCpu
import com.pocketrealm.importer.formatImportPercent
import com.pocketrealm.importer.importWorkerLabel
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/** O06 user-facing, redistributable self-test surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientScreen(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val runtime = remember { X86DirectWineRuntime(context) }
    val settings = remember(context) { Settings(context) }
    val settingsSnapshot by settings.flow.collectAsState(initial = Settings.Snapshot())
    var host by remember { mutableStateOf<ClientDisplayHost?>(null) }
    var sessionId by remember { mutableStateOf<UUID?>(null) }
    var state by remember { mutableStateOf<ClientState?>(null) }
    var detail by remember { mutableStateOf("Ready to check the bundled compatibility client") }
    var busy by remember { mutableStateOf(false) }
    var observer by remember { mutableStateOf<Job?>(null) }
    var importProgress by remember { mutableStateOf(ImportProgressPresentation.idle()) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var importComplete by remember { mutableStateOf(false) }
    var dataPreparationEnabled by remember { mutableStateOf(true) }
    var persistedTree by remember {
        mutableStateOf(context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission }?.uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                persistedTree = uri
                ImportWorkerService.start(context, uri)
                importNotice = "Import started. The selected folder remains read-only."
            }.onFailure { importNotice = "Import start failed: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching { ImportWorkerService.readStatus(context) }.onSuccess { value ->
                importProgress = ImportProgressPresentation.fromJson(value)
                importComplete = importProgress.phase == "COMPLETE"
                dataPreparationEnabled = value.optBoolean("dataPreparationEnabled", true)
                if (importProgress.phase !in setOf("IDLE", "PAUSED")) importNotice = null
            }
            delay(1_000)
        }
    }

    fun start() {
        if (busy || sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED)) return
        busy = true; detail = "Probing Wine runtime…"
        scope.launch {
            try {
                val runtimeSelection = ClientRuntimeSelector.select(context)
                check(runtimeSelection.supported) { runtimeSelection.reason }
                check(runtimeSelection.provider == ClientRuntimeProvider.X86_DIRECT_WINE) {
                    "The redistributable self-test is currently qualified only for x86DirectWine"
                }
                val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
                val caps = runtime.probe(
                    DeviceCaps(Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), Build.VERSION.SDK_INT, pageSize),
                    ClientManifest(ClientRuntimeContract.SELF_TEST_ID),
                )
                check(caps.supported) { caps.reason }
                detail = "Preparing isolated prefix…"
                val prefix = runtime.preparePrefix(PrefixRequest(ClientManifest(ClientRuntimeContract.SELF_TEST_ID)))
                var pendingWindow = false
                val display = ClientDisplayHost(context, prefix.runtimeRoot) {
                    val id = sessionId
                    if (id == null) pendingWindow = true else scope.launch { runtime.reportWindowVisible(id) }
                }
                host?.close(); host = display
                detail = "Launching self-test with audio disabled…"
                val session = runtime.launch(LaunchRequest(prefix.prefixId))
                sessionId = session.sessionId; state = session.state
                if (pendingWindow) runtime.reportWindowVisible(session.sessionId)
                observer?.cancel()
                observer = scope.launch {
                    runtime.observe(session.sessionId).collectLatest {
                        state = it.state; detail = it.detail
                        if (it.state in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED)) {
                            val d = runtime.collectDiagnostics(session.sessionId)
                            detail = "${it.detail}; window=${d.windowVisible}, audioOff=${d.audioOff}, " +
                                "keyboard=${d.keyboardSeen}, mouse=${d.mouseSeen}"
                        }
                    }
                }
                display.onResume()
            } catch (t: Throwable) {
                state = ClientState.FAILED
                detail = "${t.javaClass.simpleName}: ${t.message}"
            } finally { busy = false }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> host?.onPause()
                Lifecycle.Event.ON_RESUME -> host?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            observer?.cancel()
            host?.releaseInput()
            host?.close()
            runtime.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Direct x86 Wine", style = MaterialTheme.typography.headlineSmall)
        Text("Redistributable lifecycle self-test • WineD3D • audio off")
        ImportProgressCard(
            progress = importProgress,
            notice = importNotice,
            dataPreparationEnabled = dataPreparationEnabled,
            canResume = persistedTree != null && !importComplete,
            onSelect = { folderPicker.launch(null) },
            onResume = {
                persistedTree?.let {
                    ImportWorkerService.start(context, it)
                    importNotice = "Resume requested. Verified files and completed stages are retained."
                }
            },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("State: ${state?.name ?: "IDLE"}", modifier = Modifier.testTag("client-state"))
                Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("client-detail"))
            }
        }
        Box(Modifier.fillMaxWidth().height(360.dp).background(Color.Black).testTag("client-surface")) {
            host?.let { current ->
                key(current.generation) {
                    AndroidView(factory = { current.container }, modifier = Modifier.fillMaxSize())
                }
                if (!settingsSnapshot.inputSafeMode) TouchOverlay(current)
            } ?: Text("The Windows surface is created before launch", color = Color.White, modifier = Modifier.padding(16.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::start, enabled = !busy, modifier = Modifier.testTag("client-start")) {
                Text(if (busy) "Working…" else "Start self-test")
            }
            OutlinedButton(
                onClick = { sessionId?.let { scope.launch { runtime.requestClose(it) } } },
                enabled = sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED),
                modifier = Modifier.testTag("client-close"),
            ) { Text("Close") }
            OutlinedButton(
                onClick = { sessionId?.let { scope.launch { runtime.forceStop(it) } } },
                enabled = sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED),
                modifier = Modifier.testTag("client-force-stop"),
            ) { Text("Force stop") }
            OutlinedButton(
                onClick = { host?.showIme() },
                enabled = host != null,
                modifier = Modifier.testTag("client-ime-open"),
            ) { Text("Keyboard") }
        }
    }
}

@Composable
private fun ImportProgressCard(
    progress: ImportProgressPresentation,
    notice: String?,
    dataPreparationEnabled: Boolean,
    canResume: Boolean,
    onSelect: () -> Unit,
    onResume: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("client-import-progress")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp)) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ImportPrimaryPane(
                        progress = progress,
                        notice = notice,
                        dataPreparationEnabled = dataPreparationEnabled,
                        canResume = canResume,
                        onSelect = onSelect,
                        onResume = onResume,
                        modifier = Modifier.weight(1.05f),
                    )
                    ImportDetailPane(
                        progress = progress,
                        modifier = Modifier.weight(0.95f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ImportPrimaryPane(
                        progress = progress,
                        notice = notice,
                        dataPreparationEnabled = dataPreparationEnabled,
                        canResume = canResume,
                        onSelect = onSelect,
                        onResume = onResume,
                    )
                    HorizontalDivider()
                    ImportDetailPane(progress = progress)
                }
            }
        }
    }
}

@Composable
private fun ImportPrimaryPane(
    progress: ImportProgressPresentation,
    notice: String?,
    dataPreparationEnabled: Boolean,
    canResume: Boolean,
    onSelect: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Game files", style = MaterialTheme.typography.titleMedium)
        Text(
            progress.phaseTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("client-import-phase"),
        )
        Text(progress.explanation, style = MaterialTheme.typography.bodySmall)

        if (progress.filesTotal > 0) {
            ImportProgressLine(
                label = "Files",
                value = "${progress.filesProcessed} / ${progress.filesTotal}",
                fraction = progress.fileFraction,
                testTag = "client-import-files",
            )
            ImportProgressLine(
                label = "Copied and verified",
                value = "${formatImportBytes(progress.bytesCopied + progress.currentFileCopied)} / " +
                    formatImportBytes(progress.bytesTotal),
                fraction = progress.byteFraction,
                testTag = "client-import-bytes",
            )
        }

        progress.currentPath?.let { path ->
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("client-import-current-path"),
            )
            if (progress.currentFileTotal > 0L) {
                ImportProgressLine(
                    label = "Current file",
                    value = "${formatImportBytes(progress.currentFileCopied)} / " +
                        formatImportBytes(progress.currentFileTotal),
                    fraction = progress.currentFileFraction,
                    testTag = "client-import-current-file",
                )
            }
        }

        progress.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("client-import-error"),
            )
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("client-import-notice"))
        }
        if (!dataPreparationEnabled) {
            Text(
                "Server world-data preparation is not included in this build.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSelect, modifier = Modifier.testTag("client-import-select")) {
                Text("Choose client folder")
            }
            OutlinedButton(
                onClick = onResume,
                enabled = canResume,
                modifier = Modifier.testTag("client-import-resume"),
            ) { Text("Resume") }
        }
        Text(
            "The selected folder is read-only. Pocket Realm works from its verified private copy.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ImportDetailPane(
    progress: ImportProgressPresentation,
    modifier: Modifier = Modifier,
) {
    val active = progress.activeStage
    val ageSeconds = if (progress.updatedAtMs > 0L) progress.updatedAgeSeconds(System.currentTimeMillis()) else 0L
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Current work", style = MaterialTheme.typography.titleMedium)
        if (active != null) {
            Text(active.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("client-import-stage"))
            Text(active.explanation, style = MaterialTheme.typography.bodySmall)
            if (active.total > 0) {
                ImportProgressLine(
                    label = "Stage checkpoint",
                    value = "${active.processed} / ${active.total}",
                    fraction = (active.processed.toFloat() / active.total.toFloat()).coerceIn(0f, 1f),
                    testTag = "client-import-stage-progress",
                )
            } else if (active.state == "RUNNING") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (active.bytesWritten > 0L) {
                Text("Generated ${formatImportBytes(active.bytesWritten)}", style = MaterialTheme.typography.labelMedium)
            }
            active.checkpoint?.let { Text("Checkpoint: $it", style = MaterialTheme.typography.labelSmall) }
        } else {
            Text("No server-data stage is active.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Live activity", style = MaterialTheme.typography.titleSmall)
        Text(
            importWorkerLabel(progress.workerState, progress.workerPresent),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("client-import-worker"),
        )
        if (progress.workerPresent) {
            Text(
                "CPU ${formatImportCpu(progress.cpuPercent)}  •  Memory ${formatImportBytes(progress.rssBytes)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("client-import-resources"),
            )
            Text(
                "${progress.processCount} process${if (progress.processCount == 1) "" else "es"}  •  " +
                    "${progress.threadCount} threads  •  ${progress.cpuSampleWindowMs} ms sample",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (progress.updatedAtMs > 0L) {
            Text("Journal updated ${ageSeconds}s ago", style = MaterialTheme.typography.labelSmall)
        }

        if (progress.stages.isNotEmpty()) {
            HorizontalDivider()
            Text("Preparation stages", style = MaterialTheme.typography.titleSmall)
            progress.stages.forEach { stage -> ImportStageRow(stage) }
        }
    }
}

@Composable
private fun ImportProgressLine(
    label: String,
    value: String,
    fraction: Float,
    testTag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag(testTag)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("$value (${formatImportPercent(fraction)})", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ImportStageRow(stage: ImportStageProgress) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stage.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                stage.total > 0 -> "${stage.state.lowercase().replaceFirstChar(Char::uppercase)} " +
                    "${stage.processed}/${stage.total}"
                else -> stage.state.lowercase().replaceFirstChar(Char::uppercase)
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (stage.state == "RUNNING") FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
