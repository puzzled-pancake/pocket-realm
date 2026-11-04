package com.pocketrealm.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.BuildConfig
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_AWAIT_TIMEOUT_MINUTES = 10L
private const val BACKUP_AWAIT_TIMEOUT_MS = BACKUP_AWAIT_TIMEOUT_MINUTES * 60_000L

/**
 * Whole-realm backup and import: one stopped-state database snapshot (plus
 * the optional saved login) as a single archive. Shared by Settings →
 * Storage & realm data and the LAN screen's Transfer section, which uses it
 * to move a realm between the user's own devices.
 */
@Composable
internal fun RealmDataControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supervisor = remember(context) { RuntimeSupervisorClient(context) }
    val roots = remember(context) { StorageRoots.get(context) }
    var realmDataBusy by remember { mutableStateOf(false) }
    var exportIncludesAccount by remember { mutableStateOf(false) }
    var realmDataStatus by remember { mutableStateOf("Realm characters live in the stopped-realm database snapshots.") }
    var pendingImport by remember { mutableStateOf<Pair<Uri, RealmDataArchive.ArchiveInfo>?>(null) }

    suspend fun awaitBackupCompletion(): Boolean {
        // Bounded: the deadline below stops a backup whose phase never
        // settles from spinning this coroutine forever with a busy status
        // line.
        val deadline = System.currentTimeMillis() + BACKUP_AWAIT_TIMEOUT_MS
        while (true) {
            val status = runCatching { supervisor.backupStatus() }.getOrNull() ?: return false
            realmDataStatus = "${status.optString("kind")}: ${status.optString("phase")}"
            if (status.optString("phase") == "COMPLETE") return true
            if (status.optString("phase") == "FAILED") return false
            if (System.currentTimeMillis() >= deadline) {
                realmDataStatus = "Backup did not settle within $BACKUP_AWAIT_TIMEOUT_MINUTES minutes."
                return false
            }
            delay(500)
        }
    }

    suspend fun exportRealmCharacters(target: Uri) {
        realmDataBusy = true
        try {
            val name = "manual-export-${System.currentTimeMillis()}"
            val accepted = runCatching { supervisor.createBackup(name) }
            if (accepted.getOrNull()?.optBoolean("ok") != true) {
                realmDataStatus = accepted.fold(
                    { it.optString("error").ifBlank { "backup rejected" } },
                    { "backup failed: ${it.javaClass.simpleName}" },
                )
                return
            }
            if (!awaitBackupCompletion()) return
            val snapshotId = runCatching { supervisor.listBackups() }.getOrNull()
                ?.optJSONArray("backups")?.optJSONObject(0)?.optString("snapshotId")
            if (snapshotId.isNullOrBlank()) {
                realmDataStatus = "snapshot not found after backup"
                return
            }
            val snapshotDir = java.io.File(roots.databaseSnapshots, snapshotId)
            // Credentials are excluded by default: an exported ZIP
            // travels wherever the user sends it; re-entering a password on
            // import is cheap, un-leaking a credentials file is not.
            val accountFile = if (exportIncludesAccount) {
                java.io.File(context.noBackupFilesDir, "user-account/account.json").takeIf { it.isFile }
            } else {
                null
            }
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(target)
                    ?: throw IllegalStateException("could not open export target")
                output.use { stream ->
                    RealmDataArchive.writeArchive(
                        stream, snapshotDir, accountFile,
                        RealmDataArchive.meta(
                            snapshotId, System.currentTimeMillis(),
                            BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                        ),
                    )
                }
            }
            realmDataStatus = "Characters exported (snapshot $snapshotId)."
        } catch (failure: Throwable) {
            realmDataStatus = "Export failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            realmDataBusy = false
        }
    }

    suspend fun inspectRealmArchive(source: Uri) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(source)?.use { input -> RealmDataArchive.inspect(input) }
            }.getOrNull()
        }
        if (info == null) {
            realmDataStatus = "Could not read the selected archive."
            return
        }
        if (info.snapshotId.isBlank()) {
            realmDataStatus = "Archive has no snapshot id."
            return
        }
        pendingImport = source to info
    }

    /**
     * Atomic write of the archive's imported credentials record, byte-form
     * of the UserAccountStore.writeLocked discipline (temp + fd.sync +
     * chmod + rename + directory fsync — the desktop twin's
     * writeImportedAccount). Writing the bytes directly over the live
     * account.json would leave a torn record that the next
     * loadOrQuarantine quarantines, silently discarding the previously
     * saved auto-login account. Returns false (writing nothing) when the
     * record is invalid — an invalid record rejects ONLY this leg, never
     * the database import.
     */
    suspend fun writeImportedAccount(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!RealmDataArchive.isValidAccountRecord(bytes)) return@withContext false
        val directory = java.io.File(context.noBackupFilesDir, "user-account")
        directory.mkdirs()
        android.system.Os.chmod(directory.absolutePath,
            android.system.OsConstants.S_IRUSR or android.system.OsConstants.S_IWUSR or
                android.system.OsConstants.S_IXUSR)
        val accountFile = java.io.File(directory, "account.json")
        val temp = java.io.File(directory, ".user-account.${android.os.Process.myPid()}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        android.system.Os.chmod(temp.absolutePath,
            android.system.OsConstants.S_IRUSR or android.system.OsConstants.S_IWUSR)
        android.system.Os.rename(temp.absolutePath, accountFile.absolutePath)
        val descriptor = android.system.Os.open(
            directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        true
    }

    suspend fun restoreRealmCharacters(source: Uri, info: RealmDataArchive.ArchiveInfo) {
        realmDataBusy = true
        try {
            // The id names the extraction root, and it comes out of the
            // ARCHIVE — so the snapshot store's validId gate must run HERE,
            // before target.mkdirs(): an id like "../x" would otherwise walk
            // File(roots.databaseSnapshots, id) and the mkdirs/extract behind
            // it out of the snapshots tree, because the store's own refusal
            // fires only at restore time, after the write (the desktop gate
            // in DesktopAppModel.importRealmArchive, mirrored).
            if (!RealmDataArchive.validSnapshotId(info.snapshotId)) {
                realmDataStatus = "Archive has an invalid snapshot id."
                return
            }
            val target = java.io.File(roots.databaseSnapshots, info.snapshotId)
            if (target.exists()) {
                realmDataStatus = "Snapshot ${info.snapshotId} already exists on this device."
                return
            }
            val accountBytes = try {
                withContext(Dispatchers.IO) {
                    check(target.mkdirs()) { "could not create snapshot directory" }
                    val input = context.contentResolver.openInputStream(source)
                        ?: throw IllegalStateException("could not open archive")
                    input.use { stream -> RealmDataArchive.extractSnapshot(stream, target).second }
                }
            } catch (failure: Throwable) {
                // A half-extracted tree is not a published snapshot.
                withContext(Dispatchers.IO) { target.deleteRecursively() }
                throw failure
            }
            val accepted = runCatching { supervisor.restoreBackup(info.snapshotId) }
            if (accepted.getOrNull()?.optBoolean("ok") != true) {
                // The extract existed only to feed this restore — a refusal
                // must not leave the imported tree published in the
                // snapshots root.
                withContext(Dispatchers.IO) { target.deleteRecursively() }
                realmDataStatus = accepted.fold(
                    { it.optString("error").ifBlank { "restore rejected" } },
                    { "restore failed: ${it.javaClass.simpleName}" },
                )
                return
            }
            if (!awaitBackupCompletion()) return
            // The archive's account bytes are user-picked input: they must
            // pass the store's own decode (RealmDataArchive.isValidAccountRecord)
            // before they may replace this device's saved auto-login record.
            // An invalid record rejects ONLY this leg — the database import
            // above stays — and so does the leg's own IO failure: folded into
            // the note, never thrown past the committed restore above as an
            // "Import failed" (the desktop twin's importedAccountNote).
            val accountNote = accountBytes?.let { bytes ->
                runCatching { writeImportedAccount(bytes) }.fold(
                    { written ->
                        if (written) " Saved login replaced from the archive."
                        else " The archive's saved login was invalid and was not restored."
                    },
                    { failure ->
                        " Characters restored, but the saved login could not be written: " +
                            (failure.message ?: failure.javaClass.simpleName)
                    },
                )
            }.orEmpty()
            realmDataStatus = "Realm data imported.$accountNote"
        } catch (failure: Throwable) {
            realmDataStatus = "Import failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            realmDataBusy = false
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { exportRealmCharacters(uri) }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { inspectRealmArchive(uri) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = exportIncludesAccount,
                onCheckedChange = { exportIncludesAccount = it },
            )
            Text(
                "Include login credentials in the export (PLAIN TEXT)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = {
                exportPicker.launch(
                    "pocket-realm-characters-" +
                        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                            .format(java.util.Date()) + ".zip",
                )
            },
            enabled = !realmDataBusy,
            modifier = Modifier.fillMaxWidth().testTag("settings-realm-export"),
        ) { Text(if (realmDataBusy) "Working…" else "Back up realm characters") }
        OutlinedButton(
            onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = !realmDataBusy,
            modifier = Modifier.fillMaxWidth().testTag("settings-realm-import"),
        ) { Text("Import realm data") }
        Text(
            realmDataStatus,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("settings-realm-data-status"),
        )
        Text(
            "Exports the realm database (characters, bot accounts, login credentials) as one " +
                "file you choose. Importing replaces the current realm database; archives from a " +
                "different app build are rejected by the database seals.",
            style = MaterialTheme.typography.labelSmall,
        )
    }

    pendingImport?.let { (source, info) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import realm data?") },
            text = {
                Text(
                    "Characters from snapshot ${info.snapshotId} (from app ${info.appVersionName}, " +
                        "created " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.US)
                            .format(java.util.Date(info.createdAtMs)) + ") will replace the current " +
                        "realm database. The realm stops during the restore. If the archive carries " +
                        "saved login credentials, they also replace this device's saved auto-login " +
                        "account.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pending = pendingImport
                    pendingImport = null
                    pending?.let { (uri, archiveInfo) ->
                        scope.launch { restoreRealmCharacters(uri, archiveInfo) }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
    }
}
