package com.pocketrealm.ui.online

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.onlineplay.HostBridge
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import kotlinx.coroutines.delay

/**
 * The Multiplayer › Host tab: one button to host online, the game's card in
 * the list, the invite code while hosting, the players list and the stop
 * button. Everything technical lives under the Advanced fold.
 */
@Composable
internal fun HostTab(
    hosting: Boolean,
    hostingStatusJson: org.json.JSONObject?,
    settingsReady: Boolean,
    snapshot: Settings.Snapshot,
    onSettings: ((Settings.Snapshot) -> Settings.Snapshot) -> Unit,
    supervisorClient: RuntimeSupervisorClient,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!hosting) {
            Card(Modifier.fillMaxWidth().testTag("mp-host-form")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = snapshot.publicRealmName,
                        onValueChange = { name -> onSettings { it.copy(publicRealmName = name) } },
                        label = { Text(OnlinePlayCopy.HOST_GAME_NAME_LABEL) },
                        placeholder = { Text(OnlinePlayCopy.HOST_GAME_NAME_PLACEHOLDER) },
                        singleLine = true,
                        enabled = settingsReady,
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-name"),
                    )
                    OutlinedTextField(
                        value = snapshot.publicRealmDescription,
                        onValueChange = { text -> onSettings { it.copy(publicRealmDescription = text) } },
                        label = { Text(OnlinePlayCopy.HOST_DESCRIPTION_LABEL) },
                        placeholder = { Text(OnlinePlayCopy.HOST_DESCRIPTION_PLACEHOLDER) },
                        enabled = settingsReady,
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-description"),
                    )
                    OutlinedTextField(
                        value = snapshot.gamePassword,
                        onValueChange = { value ->
                            onSettings { it.copy(gamePassword = value.take(com.pocketrealm.storage.GAME_PASSWORD_MAX)) }
                        },
                        label = { Text(OnlinePlayCopy.HOST_PASSWORD_LABEL) },
                        supportingText = { Text(OnlinePlayCopy.HOST_PASSWORD_HELPER) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = settingsReady,
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-password"),
                    )
                    SwitchLine(
                        checked = snapshot.listInBrowser,
                        onChecked = { on -> onSettings { it.copy(listInBrowser = on) } },
                        label = OnlinePlayCopy.HOST_LIST_IN_BROWSER,
                        tag = "mp-host-list-in-browser",
                    )
                    SwitchLine(
                        checked = snapshot.signupOpen,
                        onChecked = { on -> onSettings { it.copy(signupOpen = on) } },
                        label = OnlinePlayCopy.HOST_SIGNUP_ALLOWED,
                        tag = "mp-host-signup",
                    )
                    Button(
                        onClick = {
                            onSettings { it.copy(onlineHosting = true) }
                            HostBridge.hostOnline(context, lanPlayers = snapshot.allowLanPlayers)
                        },
                        enabled = settingsReady,
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-online"),
                    ) { Text(OnlinePlayCopy.HOST_ONLINE_BUTTON) }
                }
            }
        } else {
            val status = HostBridge.hostStatus(hostingStatusJson, hosting = true)
            Card(Modifier.fillMaxWidth().testTag("mp-host-live")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        HostStatusCopy.line(status),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("mp-host-status"),
                    )
                    val players = hostingStatusJson?.optInt("online") ?: 0
                    val peers = hostingStatusJson?.optInt("peers") ?: 0
                    Text(
                        "$players ${OnlinePlayCopy.HOST_PLAYERS_TITLE.lowercase()} online · $peers connected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HostBridge.inviteCode(hostingStatusJson)?.let { code ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(code))
                                    copied = true
                                },
                                modifier = Modifier.testTag("mp-host-copy-code"),
                            ) { Text(if (copied) OnlinePlayCopy.HOST_INVITE_CODE_COPIED else OnlinePlayCopy.HOST_COPY_INVITE_CODE) }
                            OutlinedButton(
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Join my Pocket Realm game with this invite code: $code",
                                        )
                                    context.startActivity(Intent.createChooser(send, null))
                                },
                                modifier = Modifier.testTag("mp-host-share"),
                            ) { Text(OnlinePlayCopy.HOST_SHARE) }
                        }
                    }
                    PublicPlayersSection(
                        supervisorClient = supervisorClient,
                        enabled = true,
                        signupOpen = snapshot.signupOpen,
                        signupCap = snapshot.signupCap,
                    )
                    Button(
                        onClick = {
                            HostBridge.stopHosting(context)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-stop"),
                    ) { Text(OnlinePlayCopy.HOST_STOP_BUTTON) }
                }
            }
        }

        Text(
            if (advancedOpen) "▾ Advanced" else "▸ Advanced",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedOpen = !advancedOpen }
                .padding(vertical = 4.dp)
                .testTag("mp-host-advanced"),
        )
        if (advancedOpen) {
            HostAdvancedPane(
                hosting = hosting,
                settingsReady = settingsReady,
                snapshot = snapshot,
                onSettings = onSettings,
                supervisorClient = supervisorClient,
            )
        }
    }
}

/** The Advanced fold's content (the moved technical panes). */
@Composable
private fun HostAdvancedPane(
    hosting: Boolean,
    settingsReady: Boolean,
    snapshot: Settings.Snapshot,
    onSettings: ((Settings.Snapshot) -> Settings.Snapshot) -> Unit,
    supervisorClient: RuntimeSupervisorClient,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<com.pocketrealm.realm.RealmState?>(null) }
    // The moved panes take the live realm state; poll the shared client.
    LaunchedEffect(supervisorClient) {
        supervisorClient.observeRealmState().collect { status = it }
    }
    val realmState = status ?: com.pocketrealm.realm.RealmState.Idle()
    LanHostPane(
        state = realmState,
        settingsReady = settingsReady,
        allowLanPlayers = snapshot.allowLanPlayers,
        onAllowLanPlayers = { enabled -> onSettings { it.copy(allowLanPlayers = enabled) } },
        friendAccessEnabled = snapshot.friendAccessEnabled,
        onFriendAccess = { enabled -> onSettings { it.copy(friendAccessEnabled = enabled) } },
        tunnelAdvertisedAddress = snapshot.tunnelAdvertisedAddress,
        onTunnelAdvertised = { value -> onSettings { it.copy(tunnelAdvertisedAddress = value.trim()) } },
        hostError = null,
        onStartHost = {
            // The Advanced panel's plain host button (local-network hosting
            // with the stored switches, unchanged behaviour).
            onSettings { it.copy(onlineHosting = false) }
            val selection = com.pocketrealm.bots.BotSelection.resolve(
                savedPresetId = snapshot.botSavedPresetId,
                advancedEnabled = snapshot.botAdvancedEnabled,
                advancedTarget = snapshot.botPopulationTarget,
                advanced = snapshot.botAdvanced,
                profileId = snapshot.botProfileId,
            )
            com.pocketrealm.service.RealmService.hostLan(
                context,
                selection.profile.id,
                includeClient = false,
                advertised = snapshot.tunnelAdvertisedAddress.trim()
                    .takeIf { it.isNotEmpty() },
            )
        },
        onSaveExit = { HostBridge.stopHosting(context) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (hosting) {
        FriendAccessHostPane(
            supervisorClient = supervisorClient,
            endpointAddress = (realmState as? com.pocketrealm.realm.RealmState.Running)?.endpointAddress.orEmpty(),
            advertisedAddress = (realmState as? com.pocketrealm.realm.RealmState.Running)?.advertisedAddress,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchLine(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    label: String,
    tag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.testTag(tag))
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}
