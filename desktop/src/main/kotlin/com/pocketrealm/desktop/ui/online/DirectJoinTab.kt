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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.onlineplay.GameBrowserBridge
import com.pocketrealm.desktop.onlineplay.JoinBridge
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.ui.online.GameSummary
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import kotlinx.coroutines.launch

/**
 * The Multiplayer › Direct join tab (the Android twin): an invite code
 * from a friend, or the host's local address. The invite join runs the
 * same first-join wizard as the game list; the local join is the plain
 * LAN join (the sign-up wizard for it is Phase 6).
 */
@Composable
internal fun DirectJoinTab(
    model: DesktopAppModel,
    joined: Boolean,
    joinedAddress: String?,
    joinEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var lanAddress by rememberSaveable { mutableStateOf("") }
    var lanError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var inviteEntry by remember { mutableStateOf<GameBrowserBridge.GameEntry?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (joined) {
            Card(Modifier.fillMaxWidth().testTag("mp-direct-joined")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Connected to $joinedAddress",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("lan-join-status"),
                    )
                    Text(
                        "Log in with an account from that host. Stopping the session returns you to your own realm.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Copy.TAB_DIRECT_JOIN,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        label = { Text(Copy.DIRECT_INVITE_LABEL) },
                        placeholder = { Text(Copy.DIRECT_INVITE_PLACEHOLDER) },
                        singleLine = true,
                        enabled = joinEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("mp-direct-invite"),
                    )
                    Button(
                        onClick = {
                            inviteEntry = invitedGame(inviteCode)
                            if (inviteEntry == null) lanError = "That invite code is not valid."
                        },
                        enabled = joinEnabled && inviteCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("mp-direct-invite-join"),
                    ) { Text(Copy.DIRECT_JOIN_BUTTON) }

                    OutlinedTextField(
                        value = lanAddress,
                        onValueChange = { lanAddress = it; lanError = null; probeStatus = null },
                        label = { Text(Copy.DIRECT_LAN_LABEL) },
                        supportingText = { Text(Copy.DIRECT_LAN_HINT) },
                        isError = lanError != null,
                        singleLine = true,
                        enabled = joinEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("lan-join-address"),
                    )
                    lanError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val canonical = JoinBridge.localAddress(lanAddress)
                                if (canonical == null) {
                                    lanError = "Enter the host's local address, like 192.168.1.20."
                                } else {
                                    model.updateSettings {
                                        it.copy(runtimeMode = RuntimeMode.LAN_JOIN, lanAddress = canonical)
                                    }
                                    scope.launch { model.startRealm(includeClient = true) }
                                }
                            },
                            enabled = joinEnabled && lanAddress.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("lan-join"),
                        ) { Text(Copy.DIRECT_JOIN_BUTTON) }
                        OutlinedButton(
                            onClick = {
                                probing = true
                                probeStatus = null
                                scope.launch {
                                    probeStatus = JoinBridge.probeLocalHost(lanAddress)
                                        ?: "Enter the host's local address first."
                                    probing = false
                                }
                            },
                            enabled = joinEnabled && lanAddress.isNotBlank() && !probing,
                            modifier = Modifier.testTag("lan-join-probe"),
                        ) { Text(Copy.DIRECT_TEST) }
                    }
                    probeStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                if (advancedOpen) "▾ More ways to get an account" else "▸ More ways to get an account",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedOpen = !advancedOpen }
                    .padding(vertical = 4.dp)
                    .testTag("mp-direct-advanced"),
            )
            if (advancedOpen) {
                DirectJoinAdvancedPane(
                    model = model,
                    suggestedHostAddress = lanAddress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    inviteEntry?.let { entry ->
        GameJoinFlow(
            entry = entry,
            model = model,
            onDismiss = { inviteEntry = null },
        )
    }
}

/** The invite-code join's entry: the code carries the dial target only, so
 * the wizard's facts are the minimum honest set. */
private fun invitedGame(code: String): GameBrowserBridge.GameEntry? {
    val target = JoinBridge.inviteTarget(code) ?: return null
    return GameBrowserBridge.GameEntry(
        summary = GameSummary(
            id = target.id,
            name = target.name,
            gameVersion = "",
            needsVersionLabel = "",
            patch = "",
            playersOnline = 0,
            playerLimit = null,
            bots = 0,
            upSeconds = null,
            region = null,
            needsPassword = false,
            newPlayersWelcome = target.signupOpen,
            description = "",
            seenMinutesAgo = 0,
            hostAppVersion = null,
            joinReady = true,
            sameVersion = true,
        ),
        target = target,
    )
}
