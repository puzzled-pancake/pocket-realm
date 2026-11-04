package com.pocketrealm.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The GB divisor for the human-readable archive size (1e9 bytes). */
private const val GB = 1_000_000_000.0

/** The import confirmation dialog (extracted from ClientScreen so the
 * screen's composition complexity stays under the detekt threshold; the
 * copy branches on archive-vs-folder and names the ACTIVE lane's client
 * Version). flip : a null version — no flavor picked yet — renders
 * the first-pick copy in BOTH branches; the interpolated form only runs
 * when a lane exists. */
@Composable
internal fun importConfirmDialog(
    pending: ImportUiState.PendingImportPick,
    clientVersion: String?,
    onDismiss: () -> Unit,
    /** true = join only: copy the game files and skip the server build. */
    onConfirm: (joinOnly: Boolean) -> Unit,
) {
    val laneCopy = clientVersion
        ?: return confirmWithFirstPickCopy(pending, onDismiss) { onConfirm(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pending.isArchive) "Install from this archive?" else "Import the game files?") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (pending.isArchive) {
                        val gigabytes = pending.expectedBytes / GB
                        "Pocket Realm copies the ${"%.1f".format(gigabytes)} GB archive and installs " +
                            "the WoW $laneCopy game files from it. You need about twice the archive " +
                            "size in free space. The archive itself is never changed."
                    } else {
                        "Pocket Realm copies the WoW $laneCopy game files from this folder. " +
                            "The folder is never changed."
                    },
                )
                Text(
                    "Full install: also builds your own world, for solo play and hosting. " +
                        "This can take 30 minutes or more; keep the device plugged in.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Join only: just the game files, for joining other people's games. " +
                        "Much faster. You can build your own world later.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(
                    onClick = { onConfirm(true) },
                    modifier = Modifier.testTag("client-import-join-only"),
                ) { Text("Join only") }
                TextButton(
                    onClick = { onConfirm(false) },
                    modifier = Modifier.testTag("client-import-full"),
                ) { Text("Full install") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}


/** The first-pick confirm copy (the flip's render-time null path) the
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
                    "Pick a game version on Home first — the archive import needs it."
                } else {
                    "Pick a game version on Home first — the import needs it."
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
