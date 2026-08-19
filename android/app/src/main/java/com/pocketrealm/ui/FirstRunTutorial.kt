package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The verbose client requirement, shown verbatim on the tutorial's second
 * step. Single source: only the tutorial and its JVM tests consume this
 * constant — the import screen and the docs carry their own shorter
 * sentences so no dialog repeats the full paragraph.
 */
internal const val UNCOMPRESSED_CLIENT_REQUIREMENT: String =
    "You need your own copy of the World of Warcraft 1.12.1 (build 5875) " +
        "client that you are entitled to use. It must be a plain, " +
        "already-extracted, uncompressed client folder — the folder that " +
        "directly contains WoW.exe and a Data folder with its complete base " +
        "set of .MPQ files (base, dbc, fonts, interface, misc, model, sound, " +
        "speech, terrain, texture, wmo). It must NOT be an installer or setup " +
        ".exe, a downloader/launcher, or a .zip/.7z/.rar archive — Pocket " +
        "Realm cannot run installers and cannot open archives. If your copy " +
        "is compressed, extract it first (on a PC or with a file manager " +
        "app), then select the extracted folder itself — not the archive, " +
        "and not a parent folder."

internal data class TutorialStep(val title: String, val body: String)

internal val FIRST_RUN_TUTORIAL_STEPS: List<TutorialStep> = listOf(
    TutorialStep(
        title = "Welcome to Pocket Realm",
        body = "This one device becomes both your private World of Warcraft " +
            "realm server and its game client, fully offline. The first step " +
            "is bringing your own game files — Pocket Realm never bundles " +
            "or downloads them.",
    ),
    TutorialStep(
        title = "What you need: an extracted WoW 1.12.1 client",
        body = UNCOMPRESSED_CLIENT_REQUIREMENT,
    ),
    TutorialStep(
        title = "How the selection works",
        body = "Pocket Realm opens the Android folder picker: navigate to the " +
            "extracted client folder and select it. The folder is only read, " +
            "never modified — the app makes its own verified private copy. " +
            "On the next screen tap \"Choose client folder\", pick the " +
            "folder, then confirm with \"Start import\".",
    ),
    TutorialStep(
        title = "What happens next",
        body = "The import copies and verifies the client and builds the " +
            "server's world data — it can take over 30 minutes, so keep the " +
            "device plugged in and awake. Afterward, start the realm from " +
            "Home (the first start creates the realm database and prepares " +
            "the world — several minutes, and it happens only once), create " +
            "a local account, and play.",
    ),
)

/**
 * Unknown (null) inputs hide the tutorial. The default snapshot carries
 * setupComplete=false and the pointer probe starts unresolved, so deciding
 * before both resolve would flash the dialog at returning users during the
 * cold-start window; a replay request overrides the gate at any time.
 */
internal fun tutorialVisible(
    setupComplete: Boolean?,
    hasImportedClient: Boolean?,
    replayRequest: Int,
): Boolean = replayRequest > 0 || (setupComplete == false && hasImportedClient == false)

/**
 * Mirrors ManagedClientStore's active-generation resolution: the modern
 * active.json pointer file or the legacy "active" debug-generation root.
 */
internal fun managedClientImported(pointerFileExists: Boolean, legacyDirExists: Boolean): Boolean =
    pointerFileExists || legacyDirExists

/**
 * A dismissal seals setup only once settings have actually emitted; a null
 * snapshot (settings never loaded) never writes the persisted seal.
 */
internal fun tutorialSealWrite(setupComplete: Boolean?, dismissed: Boolean): Boolean =
    setupComplete == false && dismissed

/**
 * Process-wide replay channel for Settings → Setup → "Show first-run setup
 * guide". A StateFlow (not an event flow) survives activity recreation, so
 * a bump issued while the activity rebuilds is not dropped.
 */
internal object TutorialReplayRequests {
    private val requestsFlow = MutableStateFlow(0)
    val requests: StateFlow<Int> = requestsFlow.asStateFlow()

    fun bump() {
        requestsFlow.value += 1
    }

    fun consume() {
        requestsFlow.value = 0
    }
}

/**
 * One-shot hand-off from the tutorial's final call-to-action to the game
 * setup screen: armed only from a CTA tap (the activity is resumed then),
 * consumed before launching so a recreated composition cannot launch the
 * picker twice. Process death simply drops the hint — the manual button
 * remains on the import screen.
 */
internal object ClientPickerAutoOpen {
    @Volatile
    var pending: Boolean = false

    /** Returns true exactly once per arming; clears before the caller acts. */
    fun consumeOnce(): Boolean {
        val wasPending = pending
        pending = false
        return wasPending
    }
}

/**
 * Four-step first-run guide over the app shell. Tap-outside and system Back
 * are deliberate no-ops: the tutorial must never be silently dismissed, so
 * "Skip" is the explicit escape hatch on every step.
 */
@Composable
internal fun FirstRunTutorialOverlay(onFinish: (chooseFolder: Boolean) -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    val content = FIRST_RUN_TUTORIAL_STEPS[step]
    val lastStep = step == FIRST_RUN_TUTORIAL_STEPS.lastIndex
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("tutorial-dialog"),
        title = {
            Text(
                content.title,
                modifier = Modifier.testTag("tutorial-step-title"),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Step ${step + 1} of ${FIRST_RUN_TUTORIAL_STEPS.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    content.body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("tutorial-body"),
                )
            }
        },
        confirmButton = {
            if (lastStep) {
                TextButton(
                    onClick = { onFinish(true) },
                    modifier = Modifier.testTag("tutorial-finish"),
                ) { Text("Choose the client folder now") }
            } else {
                TextButton(
                    onClick = { step += 1 },
                    modifier = Modifier.testTag("tutorial-next"),
                ) { Text("Next") }
            }
        },
        dismissButton = {
            Row {
                if (step > 0) {
                    TextButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.testTag("tutorial-back"),
                    ) { Text("Back") }
                }
                TextButton(
                    onClick = { onFinish(false) },
                    modifier = Modifier.testTag("tutorial-skip"),
                ) { Text("Skip") }
            }
        },
    )
}
