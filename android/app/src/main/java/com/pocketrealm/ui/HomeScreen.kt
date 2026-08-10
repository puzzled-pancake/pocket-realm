package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.R
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.realm.RealmState
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import kotlinx.coroutines.launch

/**
 * The one-screen-with-one-primary-action Home. Status reflects the real
 * supervisor state; the primary button is Start/Continue/Save&Exit per state.
 * All gameplay-critical flows remain controller-accessible (O14).
 */
@Composable
fun HomeScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val stateUpdates = remember(supervisorClient) { supervisorClient.observeRealmState() }
    val state by stateUpdates.collectAsState(initial = RealmState.Idle)
    val settings = remember(context) { Settings(context) }
    val settingsSnapshot by settings.flow.collectAsState(initial = Settings.Snapshot())
    val botProfile = remember(settingsSnapshot) {
        if (settingsSnapshot.botAdvancedEnabled) {
            BotProfiles.advanced(
                settingsSnapshot.botPopulationTarget,
                settingsSnapshot.botAdvanced,
            )
        } else {
            BotProfiles.forRequestedTarget(settingsSnapshot.botPopulationTarget)
        }
    }
    val displayHost by IntegratedClientDisplay.host.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gmAccount by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf("Create a local account after the world is ready") }

    LaunchedEffect(displayHost?.generation) {
        displayHost?.let { host ->
            context.startActivity(ClientActivity.intent(context, host.generation))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pocket Realm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        StatusCard(state)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(botProfile.displayName, style = MaterialTheme.typography.titleMedium)
                Text(botProfile.summary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${botProfile.minimumOnline}-${botProfile.maximumOnline} bots; " +
                        "${botProfile.nearPlayerTeleportMaxAmount} favored per nearby cluster.",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    if (settingsSnapshot.botAdvancedEnabled) {
                        "Custom low-CPU tuning · ${botProfile.iterationsPerTick} work iterations/tick · " +
                            "shed above ${botProfile.admission.maxWorldP99Ms} ms p99"
                    } else {
                        "Measured preset · adjust advanced bot tuning in Settings"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            is RealmState.Idle, is RealmState.Failed, is RealmState.Recovering -> {
                Button(onClick = {
                    RealmService.start(context, botProfile.id)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.action_start))
                }
            }
            is RealmState.Running -> {
                OutlinedButton(onClick = { RealmService.saveExit(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.action_save_exit))
                }
            }
            is RealmState.Starting, is RealmState.Saving, is RealmState.Stopping -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Working…")
                }
            }
        }

        if (state is RealmState.Running) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local account", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth().testTag("account-username"),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("account-password"),
                    )
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = gmAccount, onCheckedChange = { gmAccount = it },
                            modifier = Modifier.testTag("account-gm"))
                        Text(if (gmAccount) " Local administrator (GM 3)" else " Normal local account (GM 0)")
                    }
                    Button(
                        onClick = {
                            accountStatus = "Creating through the core control channel…"
                            scope.launch {
                                val result = runCatching {
                                    supervisorClient.createAccount(username, password, if (gmAccount) 3 else 0)
                                }
                                password = ""
                                accountStatus = result.fold(
                                    onSuccess = { value ->
                                        "${value.optString("code")}: account ${value.optLong("accountId")} " +
                                            "GM ${value.optInt("gmLevel")}"
                                    },
                                    onFailure = { "Account control failed: ${it.javaClass.simpleName}" },
                                )
                            }
                        },
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("account-create"),
                    ) { Text("Create local account") }
                    Text(accountStatus, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("account-status"))
                }
            }
        }

        displayHost?.let { host ->
            Button(
                onClick = { context.startActivity(ClientActivity.intent(context, host.generation)) },
                modifier = Modifier.fillMaxWidth().testTag("enter-fullscreen-client"),
            ) { Text("Enter game") }
        }
    }
}

@Composable
private fun StatusCard(state: RealmState) {
    val (statusText, detailText) = when (state) {
        is RealmState.Idle -> "Idle" to "Realm not started."
        is RealmState.Starting -> "Starting" to "Bringing the realm up (attempt ${state.attempt})…"
        is RealmState.Running -> "Running" to "Realm is live. Tap to enter the world."
        is RealmState.Saving -> "Saving" to "Draining durable writes (${state.reason.name.lowercase()})…"
        is RealmState.Stopping -> "Stopping" to "Tearing down."
        is RealmState.Recovering -> "Recovering" to state.note
        is RealmState.Failed -> "Failed" to state.message
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detailText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
