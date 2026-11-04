package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.onlineplay.JoinBridge
import com.pocketrealm.ui.LanPresenceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Multiplayer › Direct join tab: an invite code from a friend, or the
 * host's local address. Both joins run the same first-join wizard as the
 * game list.
 */
@Composable
internal fun DirectJoinTab(
    joined: Boolean,
    joinedAddress: String?,
    joinEnabled: Boolean,
    clientUnavailableReason: String?,
    supervisorClient: com.pocketrealm.supervisor.RuntimeSupervisorClient,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var lanAddress by rememberSaveable { mutableStateOf("") }
    var lanError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var inviteEntry by remember {
        mutableStateOf<com.pocketrealm.onlineplay.GameBrowserBridge.GameEntry?>(null)
    }
    var lanFlowAddress by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    JoinedPlayersLine(supervisorClient)
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        OnlinePlayCopy.TAB_DIRECT_JOIN,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    clientUnavailableReason?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        label = { Text(OnlinePlayCopy.DIRECT_INVITE_LABEL) },
                        placeholder = { Text(OnlinePlayCopy.DIRECT_INVITE_PLACEHOLDER) },
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
                    ) { Text(OnlinePlayCopy.DIRECT_JOIN_BUTTON) }

                    OutlinedTextField(
                        value = lanAddress,
                        onValueChange = { lanAddress = it; lanError = null; probeStatus = null },
                        label = { Text(OnlinePlayCopy.DIRECT_LAN_LABEL) },
                        supportingText = { Text(OnlinePlayCopy.DIRECT_LAN_HINT) },
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
                                    lanFlowAddress = canonical
                                }
                            },
                            enabled = joinEnabled && lanAddress.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("lan-join"),
                        ) { Text(OnlinePlayCopy.DIRECT_JOIN_BUTTON) }
                        OutlinedButton(
                            onClick = {
                                probing = true
                                probeStatus = null
                                scope.launch {
                                    probeStatus = withContext(Dispatchers.IO) {
                                        JoinBridge.probeLocalHost(lanAddress)
                                    } ?: "Enter the host's local address first."
                                    probing = false
                                }
                            },
                            enabled = joinEnabled && lanAddress.isNotBlank() && !probing,
                            modifier = Modifier.testTag("lan-join-probe"),
                        ) { Text(OnlinePlayCopy.DIRECT_TEST) }
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
                FriendRequestPane(suggestedHostAddress = lanAddress, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    inviteEntry?.let { entry ->
        GameJoinFlow(
            entry = entry,
            supervisorClient = supervisorClient,
            onDismiss = { inviteEntry = null },
        )
    }
    lanFlowAddress?.let { address ->
        LanJoinFlow(
            canonicalAddress = address,
            onDismiss = { lanFlowAddress = null },
        )
    }
}

/** The invite-code join's entry: the code carries the dial target only, so
 * the wizard's facts are the minimum honest set. */
private fun invitedGame(code: String): com.pocketrealm.onlineplay.GameBrowserBridge.GameEntry? {
    val target = JoinBridge.inviteTarget(code) ?: return null
    return com.pocketrealm.onlineplay.GameBrowserBridge.GameEntry(
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

/** The joined-session players line (moved from the old screen). */
@Composable
private fun JoinedPlayersLine(
    supervisorClient: com.pocketrealm.supervisor.RuntimeSupervisorClient,
    modifier: Modifier = Modifier,
) {
    var players by remember { mutableStateOf<LanPresenceModel.LanPlayersOnline?>(null) }
    LaunchedEffect(supervisorClient) {
        while (true) {
            players = LanPresenceModel.decode(supervisorClient.worldPresence())
            delay(LanPresenceModel.POLL_MS)
        }
    }
    LanPresenceModel.line(players)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.testTag("lan-joined-players"),
        )
    }
}
