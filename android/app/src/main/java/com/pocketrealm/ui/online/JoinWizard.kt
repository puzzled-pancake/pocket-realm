package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The first-join wizard (D8): the one account-creation path for players.
 * The step machine and every sentence live in [OnlinePlayModel]; this file
 * only renders the current step and reports events. Shared by both
 * platforms (no platform imports).
 */
@Composable
fun JoinWizard(
    step: WizardStep,
    onEvent: (WizardEvent) -> Unit,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
) {
    when (step) {
        is WizardStep.Welcome -> WelcomeStep(step, onEvent, onCancel)
        is WizardStep.CreateAccount -> AccountStep(step.gameName, create = true, onEvent, onCancel)
        is WizardStep.LogIn -> AccountStep(step.gameName, create = false, onEvent, onCancel)
        is WizardStep.Connecting -> ConnectingStep(step, onEvent, onCancel)
        is WizardStep.Done -> DoneStep(step, onFinished)
        is WizardStep.Failed -> FailedStep(step, onEvent, onCancel)
    }
}

@Composable
private fun WelcomeStep(step: WizardStep.Welcome, onEvent: (WizardEvent) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${OnlinePlayCopy.WIZARD_WELCOME_TO} ${step.gameName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.versionLine.isNotEmpty()) {
                    Text(step.versionLine, style = MaterialTheme.typography.bodyMedium)
                }
                if (step.playersLine.isNotEmpty()) {
                    Text(step.playersLine, style = MaterialTheme.typography.bodyMedium)
                }
                if (step.description.isNotEmpty()) {
                    Text("\"${step.description}\"", style = MaterialTheme.typography.bodyMedium)
                }
                if (step.hasAccountHere) {
                    Text(
                        OnlinePlayCopy.WIZARD_HAS_ACCOUNT_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (step.signUpAllowed) {
                Button(
                    onClick = { onEvent(WizardEvent.ChooseCreateAccount) },
                    modifier = Modifier.testTag("mp-wizard-create"),
                ) { Text(OnlinePlayCopy.WIZARD_CREATE_ACCOUNT) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(WizardEvent.ChooseLogIn) },
                modifier = Modifier.testTag("mp-wizard-login-choice"),
            ) { Text(if (step.signUpAllowed) OnlinePlayCopy.WIZARD_ALREADY_HAVE_ONE else OnlinePlayCopy.WIZARD_LOG_IN) }
        },
    )
}

@Composable
private fun AccountStep(
    gameName: String,
    create: Boolean,
    onEvent: (WizardEvent) -> Unit,
    onCancel: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    val error = if (create) {
        JoinWizard.accountInputError(username, password, repeat)
    } else {
        JoinWizard.accountInputError(username, password, null)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (create) "${OnlinePlayCopy.WIZARD_CREATE_ACCOUNT} — $gameName"
                else "${OnlinePlayCopy.WIZARD_LOG_IN} — $gameName",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (create) {
                    Text(
                        OnlinePlayCopy.WIZARD_ACCOUNT_EXPLANATION,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(OnlinePlayCopy.WIZARD_ACCOUNT_NAME_LABEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("mp-wizard-name"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(OnlinePlayCopy.WIZARD_PASSWORD_LABEL) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("mp-wizard-password"),
                )
                if (create) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(OnlinePlayCopy.WIZARD_REPEAT_PASSWORD_LABEL) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("mp-wizard-repeat"),
                    )
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = error == null,
                onClick = {
                    // The password rides the event (the join flow's sign-up
                    // derives the verifier on this device; without it the
                    // CreateAccount path silently became a plain join).
                    onEvent(WizardEvent.Connecting(username.trim(), password))
                },
                modifier = Modifier.testTag("mp-wizard-submit"),
            ) { Text(if (create) OnlinePlayCopy.WIZARD_CREATE_ACCOUNT else OnlinePlayCopy.WIZARD_LOG_IN) }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(WizardEvent.BackToWelcome) }) {
                Text(OnlinePlayCopy.WIZARD_BACK)
            }
        },
    )
}

@Composable
private fun ConnectingStep(
    step: WizardStep.Connecting,
    onEvent: (WizardEvent) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${OnlinePlayCopy.WIZARD_CONNECTING} ${step.gameName}…") },
        text = {
            if (step.accountName != null) {
                Text(step.accountName, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag("mp-wizard-cancel"),
            ) { Text(OnlinePlayCopy.WIZARD_CANCEL) }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(WizardEvent.UseDifferentAccount) },
                modifier = Modifier.testTag("mp-wizard-different"),
            ) { Text(OnlinePlayCopy.WIZARD_USE_DIFFERENT_ACCOUNT) }
        },
    )
}

@Composable
private fun DoneStep(step: WizardStep.Done, onFinished: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFinished,
        title = { Text(JoinWizard.doneLine(step.accountName)) },
        text = {},
        confirmButton = {
            Button(
                onClick = onFinished,
                modifier = Modifier.testTag("mp-wizard-done"),
            ) { Text(OnlinePlayCopy.JOIN_LABEL) }
        },
    )
}

@Composable
private fun FailedStep(step: WizardStep.Failed, onEvent: (WizardEvent) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(step.reason) },
        text = {},
        confirmButton = {
            Button(
                onClick = { onEvent(WizardEvent.ChooseLogIn) },
                modifier = Modifier.testTag("mp-wizard-retry"),
            ) { Text(OnlinePlayCopy.WIZARD_LOG_IN) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(OnlinePlayCopy.WIZARD_CANCEL) }
        },
    )
}
