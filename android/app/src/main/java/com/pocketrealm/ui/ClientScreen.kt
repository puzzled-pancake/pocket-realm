package com.pocketrealm.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketrealm.importer.DeviceProfile
import com.pocketrealm.importer.ImportWorkerService
import com.pocketrealm.importer.ImportProgressPresentation
import com.pocketrealm.importer.ImportStageProgress
import com.pocketrealm.importer.formatImportBytes
import com.pocketrealm.importer.formatImportDuration
import com.pocketrealm.importer.formatImportPercent
import com.pocketrealm.importer.importPhaseBusy
import com.pocketrealm.importer.importWorkerLabel
import com.pocketrealm.importer.stageTitle
import com.pocketrealm.importer.watchdogRestartNotice
import com.pocketrealm.importer.workerStoppedNotice
import kotlinx.coroutines.delay

// Terminal phases that stop the status poller once the current epoch has
// observed an active run (ImportProgressPresentation terminals).
private val TERMINAL_IMPORT_PHASES = setOf("COMPLETE", "FAILED", "CANCELLED")

// F2a watchdog tuning: a busy journal phase whose worker process has been
// gone this long was LMK-killed, not merely quiet between operations.
private const val WORKER_STALLED_AFTER_SECONDS = 25L
private const val MAX_WATCHDOG_RESTARTS = 4
private const val WATCHDOG_RESTART_INTERVAL_MS = 60_000L
private const val NOTICE_STICKY_MS = 10_000L

/**
 * Managed client import, server-data generation, and the import benchmark.
 * Picking the client folder again after a completed import starts a fresh
 * import (new journal id, new immutable generations).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClientScreen(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val ui = remember { ImportUiState(context) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ui.onFolderPicked(context, uri)
    }

    ui.pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { ui.pendingImport = null },
            title = { Text("Import the game files?") },
            text = {
                Text(
                    "This copies and verifies the WoW 1.12.1 client, then builds the " +
                        "server's maps, collision and navmesh data. Depending on the " +
                        "device this can take over 30 minutes. Keep the device plugged " +
                        "in and awake. The selected folder is only read, never modified.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.pendingImport = null
                    ui.startImport(context, uri)
                }) { Text("Start import") }
            },
            dismissButton = {
                TextButton(onClick = { ui.pendingImport = null }) { Text("Cancel") }
            },
        )
    }

    // Keyed on the import epoch: polling stops
    // at a terminal phase, and starting another import bumps the epoch so the
    // effect relaunches instead of leaving a frozen progress card.
    LaunchedEffect(ui.importEpoch) {
        // A freshly (re)started import's journal row appears only seconds
        // after start; the first polls still observe the PREVIOUS run's
        // terminal phase. Stop only for a terminal phase seen AFTER this
        // epoch observed an active one (verification round 2).
        while (true) {
            runCatching { ImportWorkerService.readStatus(context) }.onSuccess { value ->
                if (ui.applyStatus(value)) return@LaunchedEffect
            }
            ui.takePendingRestart()?.let { restartUri ->
                runCatching { ImportWorkerService.start(context, restartUri) }
            }
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Game setup", style = MaterialTheme.typography.headlineSmall)
        Text("Import the WoW 1.12.1 client and generate the server's world data")
        ImportProgressCard(
            progress = ui.importProgress,
            notice = ui.importNotice ?: ui.importBusyNotice,
            dataPreparationEnabled = ui.dataPreparationEnabled,
            canResume = ui.persistedTree != null && !ui.importComplete,
            onSelect = { folderPicker.launch(null) },
            onResume = {
                ui.persistedTree?.let {
                    ImportWorkerService.start(context, it)
                    ui.beginNewPollEpoch()  // restart the status poller for the resumed run
                    ui.postImportNotice("Resume requested. Verified files and completed stages are retained.")
                }
            },
        )
    }
}

/**
 * State holder for the import screen so the poller/watchdog/start logic lives
 * in plain functions (detekt complexity is counted per function; the screen
 * composable stays a thin shell).
 */
private class ImportUiState(context: android.content.Context) {
    var importProgress by mutableStateOf(ImportProgressPresentation.idle())
    var importNotice by mutableStateOf<String?>(null)
    var importBusyNotice by mutableStateOf<String?>(null)
    var importComplete by mutableStateOf(false)
    var dataPreparationEnabled by mutableStateOf(true)
    var persistedTree by mutableStateOf(
        context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission }?.uri)
    var importEpoch by mutableStateOf(0)
    // F2d: the pick is staged behind a confirmation dialog; the persistable
    // permission is only taken on confirm so a cancel leaves no grant behind.
    var pendingImport by mutableStateOf<android.net.Uri?>(null)

    // Poller/watchdog bookkeeping (not composable state).
    private var observedActiveRun = false
    // F2a watchdog: restart a busy-but-dead worker using the journal's own
    // source URI. ActivityManager's service-restart backoff after an LMK kill
    // runs 32-290 s; without this the only escape is the manual Resume button.
    // The >=60 s rate limit bounds this counter (which resets if the UI
    // process itself is killed).
    private var watchdogRestarts = 0
    private var lastWatchdogRestartMs = 0L

    /**
     * Starts a fresh poller epoch: the effect relaunches AND the per-epoch
     * guards reset. Without the observedActiveRun reset, the relaunched
     * poller's first polls still observe the PREVIOUS run's terminal phase
     * and would exit immediately, freezing the card for the new run (the
     * exact bug the verification round fixed for the old local
     * variable). The watchdog budget also resets: this is a new run, and the
     * >=60 s in-process rate limit plus the 25 s stall requirement bound the
     * reset behavior.
     */
    fun beginNewPollEpoch() {
        importEpoch += 1
        observedActiveRun = false
        watchdogRestarts = 0
        lastWatchdogRestartMs = 0L
    }

    fun onFolderPicked(context: android.content.Context, uri: android.net.Uri) {
        val phase = runCatching {
            ImportProgressPresentation.fromJson(ImportWorkerService.readStatus(context)).phase
        }.getOrNull()
        if (phase != null && importPhaseBusy(phase)) {
            // The worker ignores new starts mid-import; do not claim one
            // started or silently swap the folder under it.
            importBusyNotice = "An import is already running. Choose the folder again after it finishes."
        } else {
            pendingImport = uri
        }
    }

    fun startImport(context: android.content.Context, uri: android.net.Uri) {
        // Process death between pick and confirm discards the dialog (plain
        // remember, not saveable) and usually the SAF grant with it; a stale
        // grant that somehow survives surfaces here as the runCatching
        // failure notice below.
        runCatching {
            // The busy gate runs at confirm time: a run may have started
            // while the dialog was open.
            val phase = ImportProgressPresentation.fromJson(
                ImportWorkerService.readStatus(context),
            ).phase
            if (importPhaseBusy(phase)) {
                importBusyNotice = "An import is already running. Choose the folder again after it finishes."
                return
            }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            persistedTree = uri
            ImportWorkerService.start(context, uri)
            beginNewPollEpoch()  // restart the status poller for the new run
            postImportNotice("Import started. The selected folder remains read-only.")
        }.onFailure { postImportNotice("Import start failed: ${it.message}") }
    }

    /** Applies one status poll; true when the poller should stop. */
    fun applyStatus(value: org.json.JSONObject): Boolean {
        importProgress = ImportProgressPresentation.fromJson(value)
        importComplete = importProgress.phase == "COMPLETE"
        dataPreparationEnabled = value.optBoolean("dataPreparationEnabled", true)
        if (importPhaseBusy(importProgress.phase)) {
            observedActiveRun = true
            // Round-2 fix: keep a fresh notice on screen long enough to read
            // (the 1 s poll previously wiped the restart wording within ~1 s).
            if (System.currentTimeMillis() - importNoticeSetAtMs >= NOTICE_STICKY_MS) importNotice = null
        }
        if (!importPhaseBusy(importProgress.phase)) importBusyNotice = null
        // Round-2 fix: only watchdog-originated busy notices clear when the
        // worker returns — the "already running" notice must persist while a
        // healthy import works (workerPresent is true throughout).
        if (importProgress.workerPresent && stoppedNoticeActive) {
            importBusyNotice = null
            stoppedNoticeActive = false
        }
        applyWatchdog()
        // Terminal phase after activity: stop polling — the
        // screen can stay open long after a finished run.
        return observedActiveRun && importProgress.phase in TERMINAL_IMPORT_PHASES
    }

    /** Notices survive the poller's busy-phase wipe for this long (round 2). */
    fun postImportNotice(text: String) {
        importNotice = text
        importNoticeSetAtMs = System.currentTimeMillis()
    }

    private var importNoticeSetAtMs = 0L
    private var stoppedNoticeActive = false

    private fun applyWatchdog() {
        val now = System.currentTimeMillis()
        when (importWatchdogAction(ImportWatchdogFacts(
            phaseBusy = importPhaseBusy(importProgress.phase),
            workerPresent = importProgress.workerPresent,
            updatedAgeSeconds = importProgress.updatedAgeSeconds(now),
            dataPreparationEnabled = dataPreparationEnabled,
            restartsUsed = watchdogRestarts,
            msSinceLastRestart = now - lastWatchdogRestartMs,
            hasSourceUri = importProgress.sourceUri != null,
        ))) {
            ImportWatchdogAction.RESTART -> {
                val sourceUriString = checkNotNull(importProgress.sourceUri)
                val sourceUri = android.net.Uri.parse(sourceUriString)
                // The restart target is the worker service; the UI process
                // cannot verify the worker pid from here, so the next poll's
                // workerPresent observation is the success signal.
                watchdogRestarts += 1
                lastWatchdogRestartMs = now
                postImportNotice(watchdogRestartNotice(
                    importProgress.workerExitReason, watchdogRestarts, MAX_WATCHDOG_RESTARTS))
                pendingRestartUri = sourceUri
            }
            ImportWatchdogAction.SHOW_MANUAL_RESUME -> {
                importBusyNotice = workerStoppedNotice(importProgress.workerExitReason)
                stoppedNoticeActive = true
            }
            ImportWatchdogAction.NONE -> Unit
        }
    }

    private var pendingRestartUri: android.net.Uri? = null

    /** Set by applyWatchdog; the poller performs the actual service start. */
    fun takePendingRestart(): android.net.Uri? {
        val uri = pendingRestartUri
        pendingRestartUri = null
        return uri
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
                Text(if (progress.phase == "COMPLETE") "Import again" else "Choose client folder")
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
                "Memory ${formatImportBytes(progress.rssBytes)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("client-import-resources"),
            )
            Text(
                "${progress.processCount} process${if (progress.processCount == 1) "" else "es"}  •  " +
                    "${progress.threadCount} threads",
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

        BenchmarkCard(progress = progress)
    }
}

@Composable
private fun BenchmarkCard(progress: ImportProgressPresentation) {
    val context = LocalContext.current
    var thermal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            thermal = thermalStatusLabel(context)
            delay(2_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Benchmark", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("client-benchmark"))
        val benchmark = progress.benchmark
        if (benchmark != null) {
            Text(
                "${benchmark.deviceLabel} — full import in ${formatImportDuration(benchmark.totalMs)}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("client-benchmark-headline"),
            )
            benchmark.stages.forEach { stage ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stageTitle(stage.stage), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(formatImportDuration(stage.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "Client copy and verify ${formatImportDuration(benchmark.copyMs)}  •  " +
                    "server data ${formatImportDuration(benchmark.dataMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
            if (benchmark.mmapMaps > 0) {
                Text(
                    "Navmesh maps ${benchmark.mmapMaps}  •  generator threads ${benchmark.mmapThreads}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Text(
                "The first complete import on this device records a timed benchmark.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Reference: ${DeviceProfile.RETROID_POCKET_6_BASELINE.deviceLabel} full ${DeviceProfile.RETROID_POCKET_6_BASELINE.mmapMaps}-map " +
                "import in ${formatImportDuration(DeviceProfile.RETROID_POCKET_6_BASELINE.totalMs)} " +
                "(navmesh ${formatImportDuration(DeviceProfile.RETROID_POCKET_6_BASELINE.mmapMs)} at " +
                "${DeviceProfile.RETROID_POCKET_6_BASELINE.mmapThreads} threads)",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("client-benchmark-reference"),
        )

        progress.device?.let { device ->
            Text("Device", style = MaterialTheme.typography.titleSmall)
            Text(
                "${device.label} • ${device.soc}" +
                    "${if (device.activelyCooled) " • actively cooled" else " • passive cooling"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("client-benchmark-device"),
            )
            Text(
                "${formatImportBytes(device.ramBytes)} RAM  •  ${device.cores} cores  •  ${device.abi}  •  Android API ${device.api}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        thermal?.let {
            Text(
                "Thermal now: $it",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("client-benchmark-thermal"),
            )
        }
        if (progress.benchmarkHistory.isNotEmpty()) {
            Text("Past runs", style = MaterialTheme.typography.titleSmall)
            progress.benchmarkHistory.forEach { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.deviceLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatImportDuration(entry.totalMs)}  •  " +
                            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
                                .format(java.util.Date(entry.createdAtMs)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun thermalStatusLabel(context: android.content.Context): String? = runCatching {
    val manager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        ?: return null
    when (manager.currentThermalStatus) {
        android.os.PowerManager.THERMAL_STATUS_NONE -> "nominal"
        android.os.PowerManager.THERMAL_STATUS_LIGHT -> "light throttle"
        android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderate throttle"
        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "severe throttle"
        android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown imminent"
        else -> null
    }
}.getOrNull()

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

internal enum class ImportWatchdogAction { RESTART, SHOW_MANUAL_RESUME, NONE }

/**
 * Pure decision for the F2a auto-continue watchdog so the restart policy is
 * unit-testable independent of Compose: a busy journal phase whose worker
 * process has been gone for a while was LMK-killed (ActivityManager's
 * service-restart backoff runs 32-290 s), and the UI restarts it itself —
 * rate-limited, capped, only with the journal's own source URI.
 */
internal data class ImportWatchdogFacts(
    val phaseBusy: Boolean,
    val workerPresent: Boolean,
    val updatedAgeSeconds: Long,
    val dataPreparationEnabled: Boolean,
    val restartsUsed: Int,
    val msSinceLastRestart: Long,
    val hasSourceUri: Boolean,
)

internal fun importWatchdogAction(facts: ImportWatchdogFacts): ImportWatchdogAction {
    if (!facts.phaseBusy || facts.workerPresent ||
        facts.updatedAgeSeconds <= WORKER_STALLED_AFTER_SECONDS
    ) {
        return ImportWatchdogAction.NONE
    }
    val restartAllowed = facts.dataPreparationEnabled &&
        facts.restartsUsed < MAX_WATCHDOG_RESTARTS &&
        facts.msSinceLastRestart >= WATCHDOG_RESTART_INTERVAL_MS
    return if (restartAllowed && facts.hasSourceUri) ImportWatchdogAction.RESTART
    else ImportWatchdogAction.SHOW_MANUAL_RESUME
}
