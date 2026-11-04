package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel

/**
 * DESK-16/31/49/92/105 — the first-run tutorial, the desktop twin of the
 * Android `ui/FirstRunTutorial` flow (one consolidated implementation for
 * the five merged register entries). The step list, visibility gate, and
 * step-walk helpers are compose-free so they stay unit-testable
 * (twin-able); the overlay is the shell-level dialog Main renders over
 * any screen.
 *
 * Desktop flow adjustments per the register plans: the final step hands
 * off to the Import route (the wave-2 import lane — the desktop's
 * pick/import client leg: choose folder → verify → publish), the
 * prepare-world-data step reports the seeded-datadir posture honestly
 * , and the SAF/Android wording has no desktop leg.
 */

internal data class TutorialStep(val title: String, val body: String)

/** the requirement body is DERIVED from the
 * registry's qualified set, never a hard-coded vanilla version — the
 * vanilla MPQ list must not be presented as the generic requirement (the
 * required base set differs per game version). Desktop twin of the
 * android UNCOMPRESSED_CLIENT_REQUIREMENT derivation. */
internal val DESKTOP_CLIENT_REQUIREMENT: String = run {
    val builds = com.pocketrealm.client.ClientFlavors.qualified()
        .joinToString(", ") { flavor ->
            val version = flavor.versionResource.substringBeforeLast(".")
            "${flavor.expansion} $version (build ${flavor.build})"
        }
    "You need your own copy of the game client for one of the supported " +
        "game versions — $builds — that you are entitled to use: an " +
        "already-installed game folder — the folder that directly " +
        "contains WoW.exe and its complete Data folder with the base set " +
        "of .MPQ files (the exact set differs per game version). It must " +
        "NOT be a downloader or launcher you have to run yourself, and " +
        "not a password-protected or split archive. The final step of " +
        "this guide takes you to Import, where you choose that folder — " +
        "Pocket Realm copies and verifies it into app storage, so the " +
        "original folder can move or change afterwards."
}

internal val FIRST_RUN_TUTORIAL_STEPS: List<TutorialStep> = listOf(
    TutorialStep(
        title = "Welcome to Pocket Realm",
        body = "This one PC becomes both your private World of Warcraft " +
            "realm server and its game client, fully offline. The first " +
            "step is bringing your own game files — Pocket Realm never " +
            "bundles or downloads them.",
    ),
    TutorialStep(
        title = "What you need: an extracted WoW client for your game version (Vanilla 1.12.1, TBC 2.4.3, or WotLK 3.3.5a)",
        body = DESKTOP_CLIENT_REQUIREMENT,
    ),
    TutorialStep(
        title = "Prepare the world data (once)",
        body = "The realm needs its world data before the first start. " +
            "Home's Active setup card reports whether the realm databases " +
            "are ready; if a start ever reports them missing, run the " +
            "one-time seed (gradlew seedRealmData from the project's " +
            "desktop folder) and start again. The first start then builds " +
            "and checks the realm databases — several minutes, and it " +
            "happens only once.",
    ),
    TutorialStep(
        title = "Start the realm and create your account",
        body = "Start realm on Home (or Realm + game to launch straight " +
            "into the client). Once the realm says online, create your " +
            "local account on Home's account card — auto-login is on by " +
            "default and logs that account in when the game opens at the " +
            "login screen. You can replay this guide anytime from " +
            "Settings → Setup.",
    ),
)

/**
 * The overlay gate: shown while the first-run seal (`setupComplete`) is
 * unset, hidden once it is set, and a replay request (Settings → Setup)
 * overrides the seal at any time. Desktop settings load synchronously at
 * app-model construction, so the Android twin's cold-start null-window
 * guards have no desktop leg.
 */
internal fun tutorialVisible(setupComplete: Boolean, replayRequests: Int): Boolean =
    replayRequests > 0 || !setupComplete

/** Bounds one step walk (the overlay never advances past the last step). */
internal fun tutorialAdvance(step: Int): Int =
    (step + 1).coerceAtMost(FIRST_RUN_TUTORIAL_STEPS.lastIndex)

/** Bounds one step walk backward (never below the first step). */
internal fun tutorialBack(step: Int): Int = (step - 1).coerceAtLeast(0)

/**
 * Five-step (here: four-step) first-run guide over the desktop shell.
 * Tap-outside is a deliberate no-op: the tutorial must never be silently
 * dismissed, so "Skip" is the explicit escape hatch on every step. The
 * final step's call-to-action opens the game-folder chooser.
 */
@Composable
internal fun FirstRunTutorialOverlay(onFinish: (chooseFolder: Boolean) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val content = FIRST_RUN_TUTORIAL_STEPS[step]
    val lastStep = step == FIRST_RUN_TUTORIAL_STEPS.lastIndex
    AlertDialog(
        onDismissRequest = {},
        title = { Text(content.title) },
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
                Text(content.body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (lastStep) {
                TextButton(onClick = { onFinish(true) }) {
                    Text("Import the client now")
                }
            } else {
                TextButton(onClick = { step = tutorialAdvance(step) }) { Text("Next") }
            }
        },
        dismissButton = {
            Row {
                if (step > 0) {
                    TextButton(onClick = { step = tutorialBack(step) }) { Text("Back") }
                }
                TextButton(onClick = { onFinish(false) }) { Text("Skip") }
            }
        },
    )
}

/**
 * The shell-level host Main renders over any route: it gates the overlay
 * on the model's setup state and replay channel, seals setup on
 * finish/skip, and hands the final call-to-action to [onOpenImport] —
 * Main navigates to the Import route (the wave-2 import lane) with the
 * folder chooser one click away.
 */
@Composable
fun FirstRunTutorialHost(model: DesktopAppModel, onOpenImport: () -> Unit) {
    val settings by model.settings.collectAsState()
    val replayRequests by model.tutorialReplayRequests.collectAsState()
    if (tutorialVisible(settings.setupComplete, replayRequests)) {
        FirstRunTutorialOverlay { chooseFolder ->
            model.consumeTutorialReplay()
            model.completeTutorial()
            if (chooseFolder) onOpenImport()
        }
    }
}
