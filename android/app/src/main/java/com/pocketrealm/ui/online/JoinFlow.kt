package com.pocketrealm.ui.online

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pocketrealm.onlineplay.GameBrowserBridge
import com.pocketrealm.onlineplay.HostBridge
import com.pocketrealm.onlineplay.JoinBridge
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import kotlinx.coroutines.delay

/**
 * The join flow both entry points share: the password prompt for a locked
 * game, then the first-join wizard (skipped straight to Connecting when a
 * login is saved for that game), driven by the session's progress.
 */
@Composable
internal fun GameJoinFlow(
    entry: GameBrowserBridge.GameEntry,
    supervisorClient: com.pocketrealm.supervisor.RuntimeSupervisorClient,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val logins = remember { HostBridge.savedLogins(context) }
    val target = entry.target

    var typedPassword by remember(entry.summary.id) { mutableStateOf("") }
    var joinPassword by remember(entry.summary.id) { mutableStateOf<String?>(null) }
    var step by remember(entry.summary.id) { mutableStateOf<WizardStep?>(null) }
    var account by remember(entry.summary.id) { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember(entry.summary.id) { mutableStateOf<String?>(null) }
    var needPassword by remember(entry.summary.id) { mutableStateOf(entry.summary.needsPassword) }

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
                account = null
                JoinBridge.joinGame(context, target, joinPassword, null)
            }
            step = first
        }
    }

    step?.let { current ->
        if (current is WizardStep.Connecting) {
            LaunchedEffect(target.nodeHex, current.accountName) {
                while (step is WizardStep.Connecting) {
                    val reply = runCatching { supervisorClient.internetJoinStatus() }.getOrNull()
                    val state = reply?.optString("state").orEmpty()
                    val detail = reply?.optString("detail").orEmpty()
                    status = detail.takeIf { it.isNotBlank() }
                    when {
                        state == "Ready" -> {
                            val made = account
                            if (made != null) {
                                logins.save(target.nodeHex, made.first, made.second)
                            } else {
                                logins.markAccountMade(target.nodeHex)
                            }
                            step = JoinWizard.advance(
                                current,
                                WizardEvent.Succeeded(current.accountName ?: ""),
                            )
                        }
                        state == "Failed" -> {
                            step = JoinWizard.advance(
                                current,
                                WizardEvent.Refused(detail.ifBlank { "Could not reach the game." }),
                            )
                        }
                    }
                    delay(1_000L)
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mp-join-status"))
        }
        JoinWizard(
            step = current,
            onEvent = { event ->
                if (event is WizardEvent.Connecting) {
                    account = event.accountPassword?.let { event.accountName to it }
                    // Only the create form makes an account; the login form
                    // signs in with an account the player already has.
                    val signup = event.accountPassword?.takeIf { current is WizardStep.CreateAccount }?.let {
                        JoinBridge.accountRequest(event.accountName, it)
                    }
                    JoinBridge.joinGame(context, target, joinPassword, signup)
                }
                step = JoinWizard.advance(current, event)
            },
            onCancel = onDismiss,
            onFinished = onDismiss,
        )
    }
}
