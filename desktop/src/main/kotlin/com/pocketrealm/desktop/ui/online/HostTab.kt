package com.pocketrealm.desktop.ui.online

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.onlineplay.HostBridge
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.PublicHostModerationStore
import com.pocketrealm.supervisor.PublicPlayersCopy
import com.pocketrealm.ui.online.HostStatusCopy
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Multiplayer › Host tab (the Android twin over the desktop model):
 * one button to host online, the game's card while hosting (status line,
 * invite code, players), and the stop button. Everything technical lives
 * behind "▸ Advanced" (the moved tunnel/status/guests panes).
 */
@Composable
internal fun HostTab(
    model: DesktopAppModel,
    hosting: Boolean,
    settings: Settings.Snapshot,
    onOpenPlayers: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!hosting) {
            Card(Modifier.fillMaxWidth().testTag("mp-host-form")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Copy.HOST_GAME_NAME_LABEL + ": " + HostBridge.gameName(model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = settings.realmDescription,
                        onValueChange = { text ->
                            model.updateSettings { it.copy(realmDescription = text) }
                        },
                        label = { Text(Copy.HOST_DESCRIPTION_LABEL) },
                        placeholder = { Text(Copy.HOST_DESCRIPTION_PLACEHOLDER) },
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-description"),
                    )
                    OutlinedTextField(
                        value = settings.gamePassword,
                        onValueChange = { value ->
                            model.updateSettings { it.copy(gamePassword = value.take(Settings.GAME_PASSWORD_MAX)) }
                        },
                        label = { Text(Copy.HOST_PASSWORD_LABEL) },
                        supportingText = { Text(Copy.HOST_PASSWORD_HELPER) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-password"),
                    )
                    SwitchLine(
                        checked = settings.listInBrowser,
                        onChecked = { on -> model.updateSettings { it.copy(listInBrowser = on) } },
                        label = Copy.HOST_LIST_IN_BROWSER,
                        tag = "mp-host-list-in-browser",
                    )
                    SwitchLine(
                        checked = settings.signupOpen,
                        onChecked = { on -> model.updateSettings { it.copy(signupOpen = on) } },
                        label = Copy.HOST_SIGNUP_ALLOWED,
                        tag = "mp-host-signup",
                    )
                    Button(
                        onClick = {
                            model.updateSettings { it.copy(onlineHosting = true) }
                            scope.launch { HostBridge.hostOnline(model, settings.allowLanPlayers) }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("mp-host-online"),
                    ) { Text(Copy.HOST_ONLINE_BUTTON) }
                }
            }
        } else {
            HostLiveCard(model, settings, modifier = Modifier.fillMaxWidth())
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
                model = model,
                hosting = hosting,
                onOpenPlayers = onOpenPlayers,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The hosted game's live card: status, players, invite code, stop. */
@Composable
private fun HostLiveCard(
    model: DesktopAppModel,
    settings: Settings.Snapshot,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val listingState by HostBridge.hostState(model).collectAsState()
    var players by remember { mutableStateOf<List<PublicHostModerationStore.PlayerRow>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        while (true) {
            players = runCatching { HostBridge.players(model) }.getOrDefault(emptyList())
            delay(5_000L)
        }
    }
    val status = HostBridge.hostStatus(listingState, hosting = true)

    Card(modifier.fillMaxWidth().testTag("mp-host-live")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                HostStatusCopy.line(status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("mp-host-status"),
            )
            val online = players.count { it.online }
            val signedUp = players.count { it.signedUpAtMs != null }
            Text(
                "$online ${Copy.HOST_PLAYERS_TITLE.lowercase()} online" +
                    if (signedUp > 0) " · $signedUp signed up" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            HostBridge.inviteCode(model)?.let { code ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.testTag("mp-host-copy-code"),
                    ) { Text(if (copied) Copy.HOST_INVITE_CODE_COPIED else Copy.HOST_COPY_INVITE_CODE) }
                }
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("mp-host-code"),
                )
            }
            HostPlayersSection(model = model, players = players, signupOpen = settings.signupOpen)
            Button(
                onClick = { scope.launch { HostBridge.stopHosting(model) } },
                modifier = Modifier.fillMaxWidth().testTag("mp-host-stop"),
            ) { Text(Copy.HOST_STOP_BUTTON) }
        }
    }
}

/** Everyone who joined over the internet, with the ban verbs (the Android
 * PublicPlayersSection twin over the desktop model). */
@Composable
private fun HostPlayersSection(
    model: DesktopAppModel,
    players: List<PublicHostModerationStore.PlayerRow>,
    signupOpen: Boolean,
) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val act: (com.pocketrealm.supervisor.PublicHostModerator.Action, String) -> Unit = { action, target ->
        scope.launch {
            val result = runCatching { HostBridge.moderate(model, action, target) }.getOrNull()
            note = result?.detail?.takeIf { it.isNotBlank() } ?: "The realm did not answer."
            refresh += 1
        }
    }
    LaunchedEffect(Unit) { refresh += 1 }

    Text(
        "Players (${players.size})" + if (signupOpen) " · sign-up open" else "",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (players.isEmpty()) {
        Text("No one has joined over the internet yet.", style = MaterialTheme.typography.bodySmall)
    }
    val nowMs = System.currentTimeMillis()
    players.forEach { row ->
        Column(Modifier.fillMaxWidth().testTag("public-player-${row.account}")) {
            Text(row.account, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(PublicPlayersCopy.statusLine(row, nowMs), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        act(
                            if (row.accountBanned) com.pocketrealm.supervisor.PublicHostModerator.Action.UNBAN_ACCOUNT
                            else com.pocketrealm.supervisor.PublicHostModerator.Action.BAN_ACCOUNT,
                            row.account,
                        )
                    },
                    modifier = Modifier.testTag("public-player-account-${row.account}"),
                ) { Text(if (row.accountBanned) "Unban account" else "Ban account") }
                if (row.devices > 0) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            act(
                                if (row.deviceBanned) com.pocketrealm.supervisor.PublicHostModerator.Action.UNBAN_DEVICE
                                else com.pocketrealm.supervisor.PublicHostModerator.Action.BAN_DEVICE,
                                row.account,
                            )
                        },
                        modifier = Modifier.testTag("public-player-device-${row.account}"),
                    ) { Text(if (row.deviceBanned) "Unban device" else "Ban device") }
                }
            }
        }
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("public-player-note"))
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
