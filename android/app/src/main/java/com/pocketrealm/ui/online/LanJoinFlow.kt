package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pocketrealm.onlineplay.HostBridge
import com.pocketrealm.onlineplay.JoinBridge
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LAN join's first-join wizard (D7): the shared wizard machine over
 * the LAN verbs — the account step's sign-up rides the friend-access
 * port's SIGNUP lane (the D3 proof with the joiner leg pinned to the lane
 * name), then the realm join dials the address. When the host's game is
 * locked the wrong-password reply prompts for the game password and
 * retries once.
 */
@Composable
internal fun LanJoinFlow(
    canonicalAddress: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logins = remember { HostBridge.savedLogins(context) }
    val realmKey = "lan:$canonicalAddress"

    var step by remember(canonicalAddress) { mutableStateOf<WizardStep?>(null) }
    var account by remember(canonicalAddress) { mutableStateOf<Pair<String, String>?>(null) }
    var gamePassword by remember(canonicalAddress) { mutableStateOf("") }
    var askGamePassword by remember(canonicalAddress) { mutableStateOf(false) }
    var typedGamePassword by remember(canonicalAddress) { mutableStateOf("") }
    var status by remember(canonicalAddress) { mutableStateOf<String?>(null) }

    fun dial(accountName: String?, signupPassword: String?) {
        scope.launch {
            status = "Connecting…"
            val outcome = signupPassword?.let {
                withContext(Dispatchers.IO) {
                    JoinBridge.signupOnLocalHost(canonicalAddress, accountName!!, it, gamePassword.takeIf(String::isNotEmpty))
                }
            }
            if (outcome != null) {
                val reply = (outcome as? com.pocketrealm.server.FriendAccessClient.SignupOutcome.Answered)?.reply
                if (reply == null) {
                    status = (outcome as com.pocketrealm.server.FriendAccessClient.SignupOutcome.Unreachable).reason
                    step = JoinWizard.advance(
                        WizardStep.Connecting(canonicalAddress, accountName),
                        WizardEvent.Refused(status ?: "Could not reach the host."),
                    )
                    return@launch
                }
                if (!reply.ok) {
                    if (reply.code == com.pocketrealm.discovery.iroh.SignupProtocol.PASSWORD_WRONG) {
                        askGamePassword = true
                        status = reply.detail
                        return@launch
                    }
                    status = reply.detail
                    step = JoinWizard.advance(
                        WizardStep.Connecting(canonicalAddress, accountName),
                        WizardEvent.Refused(reply.detail),
                    )
                    return@launch
                }
                account?.let { logins.save(realmKey, it.first, it.second) }
            } else {
                account?.let { logins.save(realmKey, it.first, it.second) }
                    ?: logins.markAccountMade(realmKey)
            }
            status = null
            JoinBridge.joinLocalHost(context, canonicalAddress)
            step = JoinWizard.advance(
                WizardStep.Connecting(canonicalAddress, accountName),
                WizardEvent.Succeeded(accountName ?: ""),
            )
        }
    }

    if (askGamePassword) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.refusalLine("PASSWORD_REQUIRED") ?: Copy.HOST_PASSWORD_LABEL) },
            text = {
                OutlinedTextField(
                    value = typedGamePassword,
                    onValueChange = { typedGamePassword = it },
                    label = { Text(Copy.HOST_PASSWORD_LABEL) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("mp-lan-game-password"),
                )
            },
            confirmButton = {
                Button(
                    enabled = typedGamePassword.isNotEmpty(),
                    onClick = {
                        gamePassword = typedGamePassword
                        askGamePassword = false
                        val made = account
                        if (made != null) {
                            dial(made.first, made.second)
                        }
                    },
                    modifier = Modifier.testTag("mp-lan-game-password-ok"),
                ) { Text(Copy.JOIN_LABEL) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.WIZARD_CANCEL) } },
        )
        return
    }

    if (step == null) {
        LaunchedEffect(canonicalAddress) {
            val saved = HostBridge.savedLogin(logins, realmKey)
            val welcome = WizardStep.Welcome(
                gameName = canonicalAddress,
                description = "",
                versionLine = "",
                playersLine = "",
                signUpAllowed = true,
                hasAccountHere = saved != null,
            )
            val first = JoinWizard.firstStep(welcome, saved?.username)
            step = first
            if (first is WizardStep.Connecting && saved != null) {
                dial(first.accountName, null)
            }
        }
    }

    step?.let { current ->
        status?.let {
            Text(it, modifier = Modifier.testTag("mp-join-status"))
        }
        JoinWizard(
            step = current,
            onEvent = { event ->
                if (event is WizardEvent.Connecting) {
                    account = event.accountPassword?.let { event.accountName to it }
                    val creating = current is WizardStep.CreateAccount
                    step = JoinWizard.advance(current, event)
                    dial(event.accountName, event.accountPassword?.takeIf { creating })
                    return@JoinWizard
                }
                step = JoinWizard.advance(current, event)
            },
            onCancel = onDismiss,
            onFinished = onDismiss,
        )
    }
}
