package com.pocketrealm.desktop.ui.online

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.onlineplay.GameBrowserBridge
import com.pocketrealm.desktop.onlineplay.HostBridge
import com.pocketrealm.desktop.onlineplay.JoinBridge
import com.pocketrealm.ui.online.GameList
import com.pocketrealm.ui.online.JoinWizard
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import com.pocketrealm.ui.online.WizardEvent
import com.pocketrealm.ui.online.WizardStep
import kotlinx.coroutines.launch
import java.io.File

/**
 * The join flow both entry points share (the Android twin over the desktop
 * model): the password prompt for a locked game, then the first-join
 * wizard (skipped straight to Connecting when a login is saved for that
 * game). The join verb resolves when the tunnel is up and the client has
 * launched — or with the loud refusal detail.
 */
@Composable
internal fun GameJoinFlow(
    entry: GameBrowserBridge.GameEntry,
    model: DesktopAppModel,
    onDismiss: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val logins = remember(model) { HostBridge.savedLogins(model) }
    val target = entry.target

    var typedPassword by remember(entry.summary.id) { mutableStateOf("") }
    var joinPassword by remember(entry.summary.id) { mutableStateOf<String?>(null) }
    var step by remember(entry.summary.id) { mutableStateOf<WizardStep?>(null) }
    var needPassword by remember(entry.summary.id) { mutableStateOf(entry.summary.needsPassword) }
    var clientReady by remember(entry.summary.id) {
        mutableStateOf(target == null || model.joinClientReady(target.flavorId))
    }

    if (target == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(GameList.JOIN_OFFLINE_NOW) },
            text = { Text("The host's game is not taking players right now. Try again in a minute.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    if (needPassword) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.refusalLine("PASSWORD_REQUIRED") ?: Copy.HOST_PASSWORD_LABEL) },
            text = {
                OutlinedTextField(
                    value = typedPassword,
                    onValueChange = { typedPassword = it },
                    label = { Text(Copy.HOST_PASSWORD_LABEL) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("mp-join-password"),
                )
            },
            confirmButton = {
                Button(
                    enabled = typedPassword.isNotEmpty(),
                    onClick = {
                        joinPassword = typedPassword
                        needPassword = false
                    },
                    modifier = Modifier.testTag("mp-join-password-ok"),
                ) { Text(Copy.JOIN_LABEL) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(Copy.WIZARD_CANCEL) }
            },
        )
        return
    }

    // Joining starts the game's own version of WoW, so a folder for that
    // version must be known first (no import needed).
    if (!clientReady) {
        GameFolderDialog(
            versionLine = GameList.row(entry.summary).versionLine,
            onChoose = { dir -> model.chooseJoinClientDir(target.flavorId, dir) },
            onReady = { clientReady = true },
            onDismiss = onDismiss,
        )
        return
    }

    // Runs the join from the Connecting step: [signup] creates the account
    // first; [login] is typed into the game's login screen once it opens.
    fun connect(connecting: WizardStep.Connecting, signup: JoinBridge.AccountRequest?, login: Pair<String, String>?) {
        step = connecting
        scope.launch {
            val outcome = JoinBridge.joinGame(model, target, joinPassword, signup, login)
            step = if (outcome.ok) {
                if (login != null) {
                    logins.save(target.nodeHex, login.first, login.second)
                } else {
                    logins.markAccountMade(target.nodeHex)
                }
                JoinWizard.advance(connecting, WizardEvent.Succeeded(connecting.accountName ?: ""))
            } else if (outcome.detail == DesktopAppModel.JOIN_NEEDS_GAME_FOLDER) {
                clientReady = false
                connecting
            } else {
                JoinWizard.advance(connecting, WizardEvent.Refused(outcome.detail))
            }
        }
    }

    // Open the wizard once the password question (if any) is settled.
    val opened = remember(target.nodeHex, joinPassword) { mutableStateOf(false) }
    if (!opened.value) {
        LaunchedEffect(target.nodeHex, joinPassword) {
            opened.value = true
            val saved = HostBridge.savedLogin(logins, target.nodeHex)
            val row = GameList.row(entry.summary)
            val welcome = WizardStep.Welcome(
                gameName = entry.summary.name,
                description = entry.summary.description,
                versionLine = row.versionLine,
                playersLine = row.playersLine,
                signUpAllowed = target.signupOpen,
                hasAccountHere = saved != null,
            )
            val first = JoinWizard.firstStep(welcome, saved?.username)
            if (first is WizardStep.Connecting) {
                // The saved-login skip: dial straight in and sign in with it.
                val login = saved?.takeIf { it.complete }?.let { it.username!! to it.password!! }
                connect(first, signup = null, login = login)
            } else {
                step = first
            }
        }
    }

    step?.let { current ->
        JoinWizard(
            step = current,
            onEvent = { event ->
                val next = JoinWizard.advance(current, event)
                if (event is WizardEvent.Connecting && next is WizardStep.Connecting) {
                    val password = event.accountPassword
                    // Only the create form makes an account; the login form
                    // signs in with an account the player already has.
                    val signup = if (current is WizardStep.CreateAccount && password != null) {
                        JoinBridge.accountRequest(event.accountName, password)
                    } else {
                        null
                    }
                    connect(next, signup, password?.let { event.accountName to it })
                } else {
                    step = next
                }
            },
            onCancel = {
                if (current is WizardStep.Connecting) model.stopIrohJoin()
                onDismiss()
            },
            onFinished = onDismiss,
        )
    }
}

/** Asks for the game folder of the version the game runs, checks it, and
 * remembers it for later joins. */
@Composable
private fun GameFolderDialog(
    versionLine: String,
    onChoose: (java.io.File) -> String?,
    onReady: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your game folder") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "This game runs $versionLine. Pick the folder with WoW.exe for that version. " +
                        "You only need to do this once.",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !choosing,
                onClick = {
                    choosing = true
                    // java.awt.FileDialog blocks its own thread until closed.
                    Thread {
                        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
                        val dialog = java.awt.FileDialog(frame, "Choose the folder that contains WoW.exe")
                        dialog.mode = java.awt.FileDialog.LOAD
                        dialog.file = "WoW.exe"
                        dialog.isVisible = true
                        val dir = dialog.directory?.let(::File)
                        if (dir != null) {
                            val refusal = onChoose(dir)
                            error = refusal
                            if (refusal == null) onReady()
                        }
                        choosing = false
                    }.apply { isDaemon = true }.start()
                },
                modifier = Modifier.testTag("mp-join-choose-folder"),
            ) { Text(if (choosing) "Choosing…" else "Choose folder…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.WIZARD_CANCEL) } },
    )
}

