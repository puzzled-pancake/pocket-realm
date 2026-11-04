package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.supervisor.PublicHostModerationStore
import com.pocketrealm.supervisor.PublicHostModerator
import com.pocketrealm.supervisor.PublicPlayersCopy
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The host's players: everyone who signed up or logged in over the
 * internet, with account and device bans, plus the banned devices (moved
 * from the old publish card; the tags are unchanged). */
@Composable
internal fun PublicPlayersSection(
    supervisorClient: RuntimeSupervisorClient,
    enabled: Boolean,
    signupOpen: Boolean,
    signupCap: Int,
) {
    val scope = rememberCoroutineScope()
    var players by remember { mutableStateOf<List<PublicHostModerationStore.PlayerRow>>(emptyList()) }
    var deviceBans by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var signedUp by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, signupOpen, refresh) {
        while (true) {
            val reply = runCatching { supervisorClient.publicPlayers() }.getOrNull()
            val rows = reply?.optJSONArray("players")
            players = (0 until (rows?.length() ?: 0)).map { PublicPlayersCopy.rowFromJson(rows!!.getJSONObject(it)) }
            signedUp = players.count { it.signedUpAtMs != null }
            val bans = reply?.optJSONArray("deviceBans")
            deviceBans = (0 until (bans?.length() ?: 0)).map { bans!!.getJSONObject(it) }
            if (!enabled) break
            delay(PLAYERS_POLL_MS)
        }
    }
    if (!enabled && players.isEmpty() && deviceBans.isEmpty()) return
    val act: (PublicHostModerator.Action, String) -> Unit = { action, target ->
        scope.launch {
            val reply = runCatching {
                supervisorClient.moderatePublicPlayer(action.name, target, PublicHostModerator.DEFAULT_REASON)
            }.getOrNull()
            note = reply?.optString("detail")?.takeIf { it.isNotBlank() } ?: "The realm did not answer."
            refresh += 1
        }
    }
    Text(
        "Players (" + players.size + ")" + if (signupOpen || signedUp > 0) {
            " · $signedUp signed up" + if (signupCap > 0) " of $signupCap" else ""
        } else {
            ""
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (players.isEmpty()) {
        Text("No one has joined over the internet yet.", style = MaterialTheme.typography.bodySmall)
    }
    val nowMs = System.currentTimeMillis()
    players.forEach { row ->
        Column(Modifier.fillMaxWidth().testTag("public-player-" + row.account)) {
            Text(row.account, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(PublicPlayersCopy.statusLine(row, nowMs), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        act(
                            if (row.accountBanned) PublicHostModerator.Action.UNBAN_ACCOUNT
                            else PublicHostModerator.Action.BAN_ACCOUNT,
                            row.account,
                        )
                    },
                    modifier = Modifier.testTag("public-player-account-" + row.account),
                ) { Text(if (row.accountBanned) "Unban account" else "Ban account") }
                if (row.devices > 0) {
                    TextButton(
                        onClick = {
                            act(
                                if (row.deviceBanned) PublicHostModerator.Action.UNBAN_DEVICE
                                else PublicHostModerator.Action.BAN_DEVICE,
                                row.account,
                            )
                        },
                        modifier = Modifier.testTag("public-player-device-" + row.account),
                    ) { Text(if (row.deviceBanned) "Unban device" else "Ban device") }
                }
                if (!row.accountBanned && !row.deviceBanned && row.devices > 0) {
                    TextButton(
                        onClick = { act(PublicHostModerator.Action.BAN_BOTH, row.account) },
                        modifier = Modifier.testTag("public-player-both-" + row.account),
                    ) { Text("Ban both") }
                }
            }
        }
    }
    if (deviceBans.isNotEmpty()) {
        Text("Banned devices", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        deviceBans.forEach { ban ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val account = ban.optString("account")
                Text(
                    "Device " + ban.optString("label") + if (account.isNotEmpty()) " (used by $account)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { act(PublicHostModerator.Action.UNBAN_DEVICE, ban.optString("token")) },
                    modifier = Modifier.testTag("public-device-unban-" + ban.optString("label")),
                ) { Text("Unban") }
            }
        }
    }
    Text(
        PublicHostModerator.DEVICE_BAN_LIMITS_COPY,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("public-player-note"))
    }
}

private const val PLAYERS_POLL_MS = 5_000L
