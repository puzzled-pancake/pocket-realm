package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.realm.RealmState
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.RealmEndpoint
import kotlinx.coroutines.launch

/**
 * Dedicated LAN destination (brief §44). Owns host/join networking only;
 * how bots behave around several humans belongs to the Bots playstyle
 * selector. Future sections (player lists, discovery, invitations) are
 * deliberately absent until actually supported.
 */
@Composable
fun LanScreen() {
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val state by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle)
    val settings = remember(context) { Settings(context) }
    val snapshot by settings.flow.collectAsState(initial = Settings.Snapshot())
    val scope = rememberCoroutineScope()
    var lanAddress by remember { mutableStateOf("") }
    var lanError by remember { mutableStateOf<String?>(null) }

    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN

    val joinLan = {
        runCatching {
            val canonical = RealmEndpoint.parseLan(lanAddress).address
            RealmService.joinLan(context, canonical)
        }.onSuccess { lanError = null }
            .onFailure { lanError = it.message ?: "Enter a private IPv4 address" }
        Unit
    }
    val startHost = {
        val selection = BotSelection.resolve(
            savedPresetId = snapshot.botSavedPresetId,
            advancedEnabled = snapshot.botAdvancedEnabled,
            advancedTarget = snapshot.botPopulationTarget,
            advanced = snapshot.botAdvanced,
            profileId = snapshot.botProfileId,
        )
        RealmService.hostLan(context, selection.profile.id, includeClient = false)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth > 720.dp
        val hostPane: @Composable (Modifier) -> Unit = { modifier ->
            LanHostPane(
                state = state,
                allowLanPlayers = snapshot.allowLanPlayers,
                onAllowLanPlayers = { enabled ->
                    scope.launch { settings.update { it.copy(allowLanPlayers = enabled) } }
                },
                onStartHost = startHost,
                onSaveExit = { RealmService.saveExit(context) },
                modifier = modifier,
            )
        }
        val joinPane: @Composable (Modifier) -> Unit = { modifier ->
            LanJoinPane(
                lanAddress = lanAddress,
                onLanAddress = { lanAddress = it; lanError = null },
                onJoinLan = joinLan,
                lanError = lanError,
                joined = joined,
                joinedAddress = if (joined) running?.endpointAddress else null,
                enabled = !hosting && !joined,
                modifier = modifier,
            )
        }
        if (twoPane) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                hostPane(Modifier.weight(1f).fillMaxSize())
                joinPane(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                hostPane(Modifier.fillMaxWidth())
                joinPane(Modifier.fillMaxWidth())
                if (hosting || joined) {
                    Text(
                        "Connected players: no remote player tracking yet; this section appears when the realm reports sessions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanHostPane(
    state: RealmState,
    allowLanPlayers: Boolean,
    onAllowLanPlayers: (Boolean) -> Unit,
    onStartHost: () -> Unit,
    onSaveExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    Card(modifier = modifier.testTag("lan-host-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    hosting -> "Hosting on ${running.endpointAddress}"
                    running != null -> "Local realm already online"
                    state is RealmState.Starting -> "Realm starting…"
                    state is RealmState.Saving || state is RealmState.Stopping -> "Realm busy…"
                    state is RealmState.Recovering -> "Recovering…"
                    state is RealmState.Failed -> "Last start failed; try again"
                    else -> "Local realm stopped"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("lan-host-status"),
            )
            Text(
                "Hosting binds realmd and the world to the exact active private IPv4 interface. " +
                    "Ports 3724 and 8085 only; MariaDB, RA, and SOAP remain private or disabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = allowLanPlayers,
                    onCheckedChange = onAllowLanPlayers,
                    modifier = Modifier.testTag("allow-lan-players"),
                )
                Text("  Allow LAN players", style = MaterialTheme.typography.bodyMedium)
            }
            if (hosting) {
                OutlinedButton(
                    onClick = onSaveExit,
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-stop"),
                ) { Text("Stop hosting & save") }
            } else {
                Button(
                    onClick = onStartHost,
                    enabled = state is RealmState.Idle ||
                        state is RealmState.Failed ||
                        state is RealmState.Recovering,
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-start"),
                ) { Text("Start host") }
            }
        }
    }
}

@Composable
private fun LanJoinPane(
    lanAddress: String,
    onLanAddress: (String) -> Unit,
    onJoinLan: () -> Unit,
    lanError: String?,
    joined: Boolean,
    joinedAddress: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("lan-join-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (joined) {
                Text(
                    "Connected to $joinedAddress",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("lan-join-status"),
                )
                Text(
                    "Log in with an account from that host. Save & exit returns you to your own realm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                OutlinedTextField(
                    value = lanAddress,
                    onValueChange = onLanAddress,
                    label = { Text("Host IPv4") },
                    supportingText = {
                        Text(
                            lanError
                                ?: "Private/link-local IPv4; ports 3724 and 8085 are fixed",
                        )
                    },
                    isError = lanError != null,
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("lan-join-address"),
                )
                Button(
                    onClick = onJoinLan,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("lan-join"),
                ) { Text("Join LAN") }
                Text(
                    "Discovery, hostnames, IPv6, UPnP, and mDNS are not used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
