package com.pocketrealm.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The GB divisor for the human-readable archive size (1e9 bytes). */
private const val GB = 1_000_000_000.0

/** The import confirmation dialog (extracted from ClientScreen so the
 * screen's composition complexity stays under the detekt threshold; the
 * copy branches on archive-vs-folder and names the ACTIVE lane's client
 * version). W4 flip (R6-4): a null version — no flavor picked yet — renders
 * the first-pick copy in BOTH branches; the interpolated form only runs
 * when a lane exists. */
@Composable
internal fun importConfirmDialog(
    pending: ImportUiState.PendingImportPick,
    clientVersion: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val laneCopy = clientVersion
        ?: return confirmWithFirstPickCopy(pending, onDismiss, onConfirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pending.isArchive) "Install from this archive?" else "Import the game files?") },
        text = {
            Text(
                if (pending.isArchive) {
                    val gigabytes = pending.expectedBytes / GB
                    "Pocket Realm copies the ${"%.1f".format(gigabytes)} GB archive into app storage, " +
                        "detects the WoW $laneCopy client inside, extracts and verifies it, then builds " +
                        "the server's maps, collision and navmesh data. Original installer archives " +
                        "(containing setup.exe + setup-*.bin) are unpacked on device too — plan for " +
                        "well over 30 minutes (an hour or more for a ~5 GB installer archive) and " +
                        "roughly twice the archive size in free space " +
                        "(three times for an installer archive); keep the device plugged in and " +
                        "awake. The archive itself is only read, never modified, and the staged " +
                        "copy is deleted after the client is published."
                } else {
                    "This copies and verifies the WoW $laneCopy client, then builds the " +
                        "server's maps, collision and navmesh data. Depending on the " +
                        "device this can take over 30 minutes. Keep the device plugged " +
                        "in and awake. The selected folder is only read, never modified. " +
                        "The folder must be the plain, already-extracted (uncompressed) " +
                        "WoW $laneCopy client itself — not an installer or launcher."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (pending.isArchive) "Install" else "Start import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}


/** The first-pick confirm copy (the W4 flip's render-time null path): the
 * same archive/folder shape, naming the lane pick instead of a version. */
@Composable
private fun confirmWithFirstPickCopy(
    pending: ImportUiState.PendingImportPick,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pending.isArchive) "Install from this archive?" else "Import the game files?") },
        text = {
            Text(
                if (pending.isArchive) {
                    "Pick a game flavor on Home first — the archive import follows " +
                        "that lane's client and preparation."
                } else {
                    "Pick a game flavor on Home first — the import follows that " +
                        "lane's client and preparation."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (pending.isArchive) "Install" else "Start import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
