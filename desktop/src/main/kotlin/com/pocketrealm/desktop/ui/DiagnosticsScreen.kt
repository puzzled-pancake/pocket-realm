package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopCrashEvidence
import com.pocketrealm.desktop.DesktopLog
import com.pocketrealm.desktop.DesktopRuntimeBackend
import com.pocketrealm.desktop.DesktopSupportBundle
import com.pocketrealm.desktop.RestoreGate
import com.pocketrealm.supervisor.RuntimeFailureCopy
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
 * log tail.
 *
 * DESK-107/112/126: the "crash dumps are Windows' own" deferral is
 * replaced with real client crash evidence — the client's own Errors/
 * crash texts, the WER LocalDumps minidumps with the Application event
 * log's WoW.exe faults, and the journalled last client exit.
 * DESK-110: the Recent log card reads the typed in-memory ring.
 * DESK-127: the World load card renders the statusNative telemetry
 * (tick / last-tick / max-tick / active sessions) the desktop previously
 * discarded.
 */
@Composable
@Suppress("LongMethod", "MagicNumber") // poll cadence and formatting constants
fun DiagnosticsScreen(model: DesktopAppModel) {
    val snapshot by model.state.collectAsState()
    var bundleStatus by remember { mutableStateOf<String?>(null) }
    var logLines by remember { mutableStateOf(DesktopLog.snapshot()) }
    var poll by remember { mutableStateOf(DiagnosticsPoll()) }
    var busy by remember { mutableStateOf(false) }
    val crashEvidence = remember { DesktopCrashEvidence() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LaunchedEffect(Unit) {
        while (true) {
            // DESK-110: the ring is in-memory — no file I/O on the poll
            // path; the list is assigned only when the tail actually
            // changed so idle logs do not recompose the card every poll.
            val freshLog = DesktopLog.snapshot()
            if (freshLog != logLines) {
                logLines = freshLog
            }
            // Crash evidence + telemetry poll on IO (filesystem + JNI).
            val next = withContext(Dispatchers.IO) { pollOnce(model, crashEvidence) }
            if (next != poll) {
                poll = next
            }
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // DESK-44/102: the Diagnostics progress card for the in-app
        // world-data preparation lane (the Home/Settings card's twin view).
        DataPreparationCard(model)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Storage roots", style = MaterialTheme.typography.titleMedium)
                Text("Base: ${model.roots.root}", style = MaterialTheme.typography.bodySmall)
                // The Android verify():Report mirror (per-root exists +
                // usable bytes), DESK-131: the card reports the full root
                // taxonomy instead of five hardcoded names + one global
                // usable-space line.
                val report = model.roots.verify()
                report.roots.forEach { status ->
                    val megs = if (status.usableBytes >= 0) {
                        " (${status.usableBytes / (1024L * 1024L)} MiB free)"
                    } else {
                        ""
                    }
                    Text(
                        "${status.name}: ${if (status.exists) "ok" else "MISSING"}$megs",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "layout: ${if (report.ok) "ok" else "INCOMPLETE"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
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
                // DESK-137: the decode boundary renders human copy; the raw
                // detail stays in the log (DesktopAppModel logs it verbatim).
                snapshot.lastError?.let { raw ->
                    Text(
                        "error: ${RuntimeFailureCopy.humanize(raw)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        BackupsCard(model)
        CrashEvidenceCard(poll, model)
        WorldLoadCard(poll)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Redacted support bundle", style = MaterialTheme.typography.titleMedium)
                val scope = rememberCoroutineScope()
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        bundleStatus = "Working…"
                        scope.launch {
                            bundleStatus = withContext(Dispatchers.IO) {
                                runCatching {
                                    DesktopSupportBundle(model.roots, model.resolveClientDir()).export()
                                }.getOrElse { failure -> "Export failed: ${failure.javaClass.simpleName}" }
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Working…" else "Create support bundle") }
                Text(
                    bundleStatus ?: "No support bundle created. Bundles carry the app log tail, the " +
                        "typed log ring, the supervisor journal, the client exit and crash evidence, " +
                        "the prepared-data pointer, the session's own topology (runtime mode + LAN " +
                        "address) and the runtime conf (secrets redacted) as a zip next to the logs. " +
                        "Client crash minidumps are NEVER included — they are process memory images " +
                        "that can contain your login credential — only their names, sizes, and dates.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent log", style = MaterialTheme.typography.titleMedium)
                // DESK-110: the typed ring's tail (kind-tagged), mirroring
                // the Android diagnostics card's slice of AppLog.
                val tail = remember(logLines) { logLines.takeLast(RECENT_LOG_ROWS) }
                if (tail.isEmpty()) {
                    Text("(empty)", style = MaterialTheme.typography.bodySmall)
                }
                tail.forEach { line ->
                    Text(
                        "${timeFormat.format(Date(line.ts))} ${line.level}/${line.kind}: ${line.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * DESK-6/18/37/100: the backup-and-restore card (the Android Diagnostics
 * "Backup and restore" card's desktop twin): create a named backup of the
 * stopped datadir, restore the newest or any named backup through the
 * transactional restore (boot-to-world-ready proof, rollback on failure),
 * and the retain-newest trim. Backups load once per entry and after every
 * action — never on the 2 s poll (listing validates every manifest).
 *
 * GAP-1 (was DESKPROD-1): both restore buttons are consent-gated — they
 * enable only on [com.pocketrealm.desktop.RestoreGate.Ready] (the
 * supervisor holds no world) and route through ONE AlertDialog that names
 * what success does (replaces the current realm databases; deletes the
 * quarantined pre-restore state after the proof boot passes) before
 * [DesktopAppModel.restoreBackup] runs.
 */
@Composable
@Suppress("LongMethod") // one card, five controls + the GAP-1 consent dialog
private fun BackupsCard(model: DesktopAppModel) {
    var backups by remember { mutableStateOf<List<DesktopRuntimeBackend.BackupEntry>>(emptyList()) }
    var status by remember { mutableStateOf("No backup operation ran yet.") }
    var busy by remember { mutableStateOf(false) }
    // GAP-1 (was DESKPROD-1): the restore consent gate — recomputed as the
    // supervisor phase moves. The model's restoreBackup re-checks the same
    // gate (defense in depth), and the backend's runtimes-at-rest refusal
    // remains the final arbiter; the busy flag still guards re-entrancy.
    val runtimeState by model.state.collectAsState()
    val gate = remember(runtimeState.phase) { model.restoreGate() }
    // The pending restore awaiting explicit consent — non-null renders the
    // one AlertDialog both restore buttons share (the HomeScreen D8
    // stop-with-guests consent shape).
    var pendingRestoreId by remember { mutableStateOf<String?>(null) }
    // GAP-2 (was DESKPROD-2): set when a restore left this app process's
    // one world lifetime spent (the proof boot reached WORLD_READY —
    // DESK-25's contract is that READY spends it; even a rolled-back
    // restore whose proof reached READY leaves it spent), so the card
    // OFFERS the relaunch instead of leaving the user to discover
    // WRONG_STATE on the next Start. Offered, never auto-taken: a surprise
    // app restart mid-session would be a second consent problem.
    var restoreSpentLifetime by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        backups = withContext(Dispatchers.IO) { model.listBackups() }
    }

    LaunchedEffect(Unit) { runCatching { refresh() } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup and restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Named backups of the realm databases, taken only while the realm is " +
                    "stopped. A restore REPLACES your current realm databases with the " +
                    "backup's: the current state is quarantined, the backup is booted " +
                    "bots-disabled to prove it world-ready, and the quarantined " +
                    "pre-restore databases are DELETED once the proof passes. Any " +
                    "failure rolls the original databases back. The proof boot spends " +
                    "this app session's one world start, so the next Start needs an " +
                    "app relaunch.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Working…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching {
                                model.backend.createNamedBackup("backup-${System.currentTimeMillis()}")
                            }.fold(
                                { created -> created.detail },
                                { failure ->
                                    "Backup failed: ${failure.message ?: failure.javaClass.simpleName}"
                                },
                            )
                        }
                        runCatching { refresh() }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Working…" else "Create named backup") }
            OutlinedButton(
                // GAP-1: gate-first enablement — the supervisor phase must be
                // Ready, not merely "not busy" (the old cliff surfaced
                // RESTORE-REFUSED as UI); the tap now only asks for consent.
                enabled = gate is RestoreGate.Ready && !busy && backups.isNotEmpty(),
                onClick = {
                    val newest = backups.firstOrNull() ?: return@OutlinedButton
                    pendingRestoreId = newest.snapshotId
                },
            ) { Text("Restore newest backup") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching { model.backend.retainNewestBackups(BACKUP_RETENTION) }.fold(
                                { result -> result.detail },
                                { failure -> "Retain failed: ${failure.message}" },
                            )
                        }
                        runCatching { refresh() }
                        busy = false
                    }
                },
            ) { Text("Keep newest $BACKUP_RETENTION backups") }
            if (backups.isEmpty()) {
                Text("No named backups yet.", style = MaterialTheme.typography.bodySmall)
            }
            backups.forEach { backup ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            backup.snapshotId,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${backup.provider} · created " +
                                "${BACKUP_DATE_FORMAT.format(Date(backup.createdAtMs))} · " +
                                "${backup.digest.take(12)}…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        // GAP-1: same gate + consent routing as the newest
                        // button — one destructive verb, one dialog.
                        enabled = gate is RestoreGate.Ready && !busy,
                        onClick = { pendingRestoreId = backup.snapshotId },
                    ) { Text("Restore") }
                }
            }
            if (gate is RestoreGate.Blocked && backups.isNotEmpty()) {
                Text(
                    "Restore unavailable: ${gate.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // GAP-2 (was DESKPROD-2): the DESK-25 relaunch action for a
            // restore whose proof spent the process's one world start —
            // the same affordance the Home card offers a spent lifetime,
            // surfaced here because THIS card caused the spend.
            if (restoreSpentLifetime) {
                RestoreRelaunchOffer(
                    model = model,
                    busy = busy,
                    onBusyChange = { busy = it },
                )
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }

    // GAP-1 (was DESKPROD-1): the consent dialog both restore buttons route
    // through — success REPLACES the current world (the quarantined
    // pre-restore databases are deleted once the proof boot passes), which
    // the old one-tap path never disclosed. The HomeScreen D8
    // stop-with-guests consent shape; confirm stays idempotent through the
    // existing busy flag, and a phase change raced under the open dialog is
    // caught by the model's own gate re-check (RESTORE-REFUSED, never a
    // swap).
    //
    // GAP-2 (was DESKPROD-2): the dialog also names what the PROOF does —
    // it boots bots-disabled (so the backup's state is restored exactly,
    // with no bot population added) and it spends this app session's one
    // world start (DESK-25), so the next Start needs an app relaunch that
    // the card offers once the restore completes.
    pendingRestoreId?.let { snapshotId ->
        AlertDialog(
            onDismissRequest = { pendingRestoreId = null },
            title = { Text("Restore $snapshotId?") },
            text = {
                Text(
                    "Your current realm databases are replaced by this backup's; the " +
                        "current state is deleted after the proof boot passes. Any " +
                        "failure rolls the original databases back.\n\n" +
                        "The proof boot runs with bots disabled (no bot population is " +
                        "added to the restored state) and spends this app session's " +
                        "one world start — the next Start needs an app relaunch, " +
                        "offered here once the restore completes.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestoreId = null
                        busy = true
                        status = "Restoring $snapshotId…"
                        scope.launch {
                            status = model.restoreBackup(snapshotId).detail
                            // The flag alone is the honest condition: it is
                            // exactly when the next start refuses WRONG_STATE
                            // (a proof that reached READY spends it even if
                            // the transaction then rolled back).
                            restoreSpentLifetime = model.worldLifetimeSpent()
                            runCatching { refresh() }
                            busy = false
                        }
                    },
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreId = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * GAP-2 (was DESKPROD-2): the DESK-25 relaunch action a completed restore
 * offers when its proof boot spent this app process's one world start —
 * the Home card's spent-lifetime affordance, surfaced on the card that
 * caused the spend. OFFERED, never auto-taken: a surprise app restart
 * mid-session would be a second consent problem. A refused relaunch
 * leaves this app alive (the verb spawns first), so the refusal copy
 * lands in the status line; a successful one exits the process from
 * inside the verb.
 */
@Composable
private fun RestoreRelaunchOffer(
    model: DesktopAppModel,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
) {
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text(
        "The proof boot spent this app session's one world start; the next " +
            "Start needs an app relaunch (your restored world is already saved).",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        enabled = !busy,
        onClick = {
            onBusyChange(true)
            status = "Restarting…"
            scope.launch {
                val result = model.relaunchApp()
                if (!result.ok) {
                    // A refusal leaves this app alive — say so.
                    status = "Relaunch refused: ${result.detail}"
                    onBusyChange(false)
                }
                // A successful relaunch exits the process from inside the
                // verb; no state to update.
            }
        },
    ) { Text(if (busy) "Restarting…" else "Restart realm (relaunch app)") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** One poll's worth of display-ready crash-evidence + telemetry lines. */
private data class DiagnosticsPoll(
    val crashRows: List<String> = emptyList(),
    val dumpRows: List<String> = emptyList(),
    val hasEventLog: Boolean = false,
    val clientExitLine: String? = null,
    val worldLines: List<String> = emptyList(),
    val realmHeartbeatLine: String? = null,
)

/** DESK-112: the journalled exit of the last WoW.exe death, human copy. */
private fun clientExitLine(exit: org.json.JSONObject?): String? {
    if (exit == null) return null
    val code = exit.optInt("exitCode", -1)
    val pid = exit.optInt("pid", -1)
    return if (exit.optBoolean("clean", false)) {
        "Last client exit: pid $pid, exit code $code (normal exit)"
    } else {
        "Last client exit: pid $pid, exit code $code (ABNORMAL)"
    }
}

@Suppress("TooGenericExceptionCaught") // every lane is best effort by contract
private fun pollOnce(model: DesktopAppModel, crashEvidence: DesktopCrashEvidence): DiagnosticsPoll {
    val exit = runCatching { model.backend.lastClientExit() }.getOrNull()
    val world = runCatching { model.backend.worldTelemetry() }.getOrNull()
    val realm = runCatching { model.backend.realmTelemetry() }.getOrNull()
    val worldLines = world?.let { json ->
        listOf(
            "state: ${json.optString("state")}",
            "heartbeatMs: ${json.optLong("heartbeatMs")}",
            "tickCount: ${json.optLong("tickCount")}",
            "lastTickMs: ${json.optLong("lastTickMs")}",
            "maxTickMs: ${json.optLong("maxTickMs")}",
            "activeSessions: ${json.optLong("activeSessions")}",
        )
    } ?: emptyList()
    return DiagnosticsPoll(
        crashRows = runCatching { crashEvidence.clientCrashRows(model.resolveClientDir()) }
            .getOrDefault(emptyList()),
        dumpRows = runCatching { crashEvidence.werDumpRows() }.getOrDefault(emptyList()),
        hasEventLog = runCatching { crashEvidence.applicationErrorLog() }
            .getOrDefault("").isNotEmpty(),
        clientExitLine = clientExitLine(exit),
        worldLines = worldLines,
        realmHeartbeatLine = realm?.let { "realm heartbeatCount: ${it.optLong("heartbeatCount")}" },
    )
}

@Composable
private fun CrashEvidenceCard(poll: DiagnosticsPoll, model: DesktopAppModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Client crash evidence", style = MaterialTheme.typography.titleMedium)
            poll.clientExitLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (poll.crashRows.isEmpty() && poll.dumpRows.isEmpty() && !poll.hasEventLog) {
                Text("No client crash evidence found.", style = MaterialTheme.typography.bodySmall)
            }
            poll.crashRows.forEach { row ->
                Text(
                    "• $row",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (poll.crashRows.isNotEmpty()) {
                Text(
                    "Crash texts live in the game client folder's Errors/ directory " +
                        "(${model.resolveClientDir()?.absolutePath ?: "no client folder configured"}).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            poll.dumpRows.forEach { row ->
                Text(
                    "• WER dump: $row",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (poll.dumpRows.isNotEmpty()) {
                Text(
                    "Minidumps: ${DesktopCrashEvidence.defaultLocalDumpsDir().absolutePath} " +
                        "(Windows Error Reporting LocalDumps).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (poll.hasEventLog) {
                Text(
                    "Windows Application log carries WoW.exe fault records; they are " +
                        "exported in the support bundle.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WorldLoadCard(poll: DiagnosticsPoll) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("World load", style = MaterialTheme.typography.titleMedium)
            // DESK-127: the statusNative telemetry indices the observation
            // detail used to discard, rendered like the Android status JSON.
            if (poll.worldLines.isEmpty()) {
                Text(
                    "World telemetry unavailable (native runtime not staged).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            poll.worldLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            poll.realmHeartbeatLine?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private const val RECENT_LOG_ROWS = 120

/** The retain-newest trim count (the Diagnostics card's "Keep newest"). */
private const val BACKUP_RETENTION = 3

/** The backup row's created-at format. */
private val BACKUP_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
