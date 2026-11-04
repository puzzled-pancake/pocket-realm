package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.pocketrealm.realm.RealmState
import com.pocketrealm.server.FriendAccessClient
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.AdvertisedAddress
import com.pocketrealm.ui.FriendInviteStatusCopy
import com.pocketrealm.supervisor.GuestCredentials
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.TunnelInterfacePolicy
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.ui.TunnelInvite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The Host tab's Advanced panel (the technical controls, moved from the old
 * single LAN screen): local-network players, friend access and guest
 * logins, the tunnel-hosting option, sign-up limits, permissions and bans.
 * Technical words are allowed HERE; the main Host tab stays plain.
 */

private const val FRIEND_ROSTER_POLL_MS = 5_000L
private const val MAX_INTERFACE_QUICK_PICKS = 3

/** The desktop PlayersScreen twin's moderation surface constants: the
 * plain per-level permission labels (level 3 is "full console", never an
 * unlabeled "gm3") and the ban-reason cap. */
private val GM_LEVELS = listOf(0 to "Player", 1 to "Helper", 2 to "Moderator", 3 to "Full console")
private const val BAN_REASON_MAX_CHARS = 96

@Composable
internal fun LanHostPane(
    state: RealmState,
    settingsReady: Boolean,
    allowLanPlayers: Boolean,
    onAllowLanPlayers: (Boolean) -> Unit,
    friendAccessEnabled: Boolean,
    onFriendAccess: (Boolean) -> Unit,
    tunnelAdvertisedAddress: String,
    onTunnelAdvertised: (String) -> Unit,
    hostError: String?,
    onStartHost: () -> Unit,
    onSaveExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN
    Card(modifier = modifier.testTag("lan-host-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    hosting -> "Hosting on ${running.endpointAddress}" +
                        running.advertisedAddress?.let { " — advertising $it (tunnel)" }.orEmpty()
                    joined -> "Joined to ${running.endpointAddress}; stop the client session before hosting"
                    running != null -> "Local realm already online"
                    state is RealmState.Starting -> "Realm starting…"
                    state is RealmState.Saving || state is RealmState.Stopping -> "Realm busy…"
                    state is RealmState.Recovering -> "Recovering…"
                    state is RealmState.Failed -> "Last start failed; try again"
                    // Design-lan D9: an interface-loss stop explains itself
                    // (the supervisor snapshot carries the reason; the shared
                    // copy is humanized at the decode boundary) — a network
                    // change mid-hosting never presents as a clean,
                    // unexplained stop.
                    state is RealmState.Idle && state.stopCopy != null -> state.stopCopy!!
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
            // Design-tunnel disclosure (the desktop Home card twin):
            // tunnel advertising binds the WILDCARD, so the game ports also
            // listen on every local interface — the local LAN included. The
            // host gets that stated, not just the tunnel-only story.
            if (hosting && !running?.advertisedAddress.isNullOrBlank() &&
                running?.advertisedAddress != running?.endpointAddress
            ) {
                Text(
                    "While tunnel advertising is on, the game ports also listen on this " +
                        "device's other networks (your local LAN included) — the tunnel is " +
                        "not a wall around them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = allowLanPlayers,
                    onCheckedChange = onAllowLanPlayers,
                    modifier = Modifier.testTag("allow-lan-players"),
                )
                Text("  Allow LAN players", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = friendAccessEnabled,
                    onCheckedChange = onFriendAccess,
                    modifier = Modifier.testTag("friend-access-switch"),
                )
                Text(
                    "  Allow friend access (invite codes, port " +
                        ServerRuntimeContract.PROVISION_PORT + ")",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Design-tunnel / (the desktop TunnelAdvertisedField twin):
            // the advertised tunnel address — internet hosting rides
            // Tailscale/ZeroTier, the servers bind all interfaces, and the
            // one realmlist row carries THIS address (never the LAN IP)
            // while it is set. Validated for display through the shared v2
            // grammar; the start re-validates.
            // Tunnel hosting is an expert option, folded behind one switch,
            // open whenever an address is already set. Switching it off
            // clears the address (plain LAN hosting again).
            var showTunnel by rememberSaveable { mutableStateOf(false) }
            val tunnelOpen = showTunnel || tunnelAdvertisedAddress.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = tunnelOpen,
                    onCheckedChange = { open ->
                        showTunnel = open
                        if (!open && tunnelAdvertisedAddress.isNotBlank()) onTunnelAdvertised("")
                    },
                    enabled = !hosting,
                    modifier = Modifier.testTag("tunnel-hosting-switch"),
                )
                Text("  Host over the internet (Tailscale / ZeroTier)", style = MaterialTheme.typography.bodyMedium)
            }
            if (tunnelOpen) {
                val advertisedHint = remember(tunnelAdvertisedAddress) {
                    when {
                        tunnelAdvertisedAddress.isBlank() -> null
                        else -> runCatching {
                            AdvertisedAddress.parse(tunnelAdvertisedAddress.trim())
                        }.exceptionOrNull()?.message
                    }
                }
                OutlinedTextField(
                    value = tunnelAdvertisedAddress,
                    onValueChange = onTunnelAdvertised,
                    label = { Text("Advertise over the internet — tunnel address (e.g. 100.64.1.20)") },
                    isError = tunnelAdvertisedAddress.isNotBlank() && advertisedHint != null,
                    supportingText = {
                        Text(
                            when {
                                tunnelAdvertisedAddress.isBlank() ->
                                    "Optional. Host over the internet through Tailscale or ZeroTier: " +
                                        "paste this host's tunnel address. Leave empty for LAN-only hosting."
                                advertisedHint != null -> advertisedHint ?: "invalid tunnel address"
                                else -> "The realm will advertise this address. Plain-LAN guests will " +
                                    "still see the realm, but only friends inside the tunnel can reach it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("tunnel-advertised-address"),
                )
                val tunnelAddresses = remember {
                    runCatching { TunnelInterfacePolicy.available() }.getOrDefault(emptyList())
                }
                if (tunnelAddresses.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tunnelAddresses.take(MAX_INTERFACE_QUICK_PICKS).forEach { advertised ->
                            OutlinedButton(onClick = { onTunnelAdvertised(advertised.value) }) {
                                Text("Use ${advertised.value}")
                            }
                        }
                    }
                }
                if (tunnelAdvertisedAddress.isNotBlank() && advertisedHint == null) {
                    Text(
                        "How to host over the internet: 1) Install Tailscale (tailscale.com) on this " +
                            "device and on each friend's device, and join the same tailnet (ZeroTier " +
                            "works too). 2) Paste this host's tunnel address above — the quick-pick " +
                            "buttons show detected tunnel adapters. 3) Start hosting; the Friend access " +
                            "card's invite has the exact line your friends paste into realmlist.wtf. " +
                            "While tunnel mode is on, plain-LAN guests see a realm they cannot enter " +
                            "(one advertisement for everyone).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            hostError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (hosting) {
                OutlinedButton(
                    onClick = onSaveExit,
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-stop"),
                ) { Text("Stop hosting & save") }
            } else {
                Button(
                    onClick = onStartHost,
                    enabled = settingsReady &&
                        (state is RealmState.Idle ||
                            state is RealmState.Failed ||
                            state is RealmState.Recovering),
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-start"),
                ) { Text("Start host") }
            }
        }
    }
}

@Composable
internal fun FriendAccessHostPane(
    supervisorClient: RuntimeSupervisorClient,
    endpointAddress: String,
    advertisedAddress: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var stateJson by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var rosterJson by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var note by remember { mutableStateOf("") }
    var freshCode by remember { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshState() {
        stateJson = runCatching { supervisorClient.friendAccessState() }.getOrNull()
    }
    suspend fun refreshRoster() {
        rosterJson = runCatching { supervisorClient.friendRoster() }.getOrNull()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            refreshState()
            refreshRoster()
            delay(FRIEND_ROSTER_POLL_MS)
        }
    }

    Card(modifier = modifier.testTag("friend-access-host-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Friend access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val listener = stateJson?.optJSONObject("listener")
            Text(
                when {
                    listener?.optString("kind") == "Failed" ->
                        "Listener FAILED to bind: " + listener.optString("reason")
                    listener?.optString("kind") == "Listening" ->
                        "Listening on " + listener.optString("address") + ":" + listener.optString("port") +
                            " — friend requests reach the host over your shared LAN while this realm is up."
                    else -> "Off — nothing listens while friend access is off or the realm is not hosting."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-access-listener-state"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(64) },
                    label = { Text("Note (e.g. \"Dave\")") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("friend-invite-note"),
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val created = runCatching { supervisorClient.createFriendInvite(note) }.getOrNull()
                            if (created?.optBoolean("ok") == true) {
                                freshCode = created.optString("inviteId") to created.optString("token")
                                status = null
                            } else {
                                // structured refusal copy: a cap
                                // refusal and an unreachable supervisor
                                // are different situations with different
                                // remedies - never one guessed line
                                status = FriendInviteStatusCopy.statusFor(created)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("friend-invite-create"),
                ) { Text("Invite") }
            }
            freshCode?.let { (inviteId, token) ->
                Text(
                    "Invite $inviteId — give this code to your friend (shown only once):",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    token,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("friend-invite-code"),
                )
                OutlinedButton(onClick = { freshCode = null }) { Text("Done") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // Design-tunnel / design-lan D4 (the shared TunnelInvite
            // artifact): the copyable ADDRESS BLOCK ONLY — it carries the
            // live advertisement (the tunnel address when tunnel mode is
            // on, never the LAN IP then) and NEVER a credential. Mode-true
            // split: with no tunnel advertisement this is the plain LAN case, so
            // the block is the no-tunnel LAN invite — a LAN address is never
            // dressed up as a tunnel setup.
            val clipboard = LocalClipboardManager.current
            val inviteBlock = if (advertisedAddress.isNullOrBlank()) {
                TunnelInvite.lanInviteText(
                    realmName = "my realm",
                    lanAddress = endpointAddress,
                )
            } else {
                val inviteKind = runCatching { AdvertisedAddress.parse(advertisedAddress) }
                    .getOrNull()
                    ?.let { TunnelInvite.tunnelKindFor(it) }
                    ?: TunnelInvite.TunnelKind.OTHER
                TunnelInvite.inviteText(
                    realmName = "my realm",
                    advertised = advertisedAddress,
                    kind = inviteKind,
                )
            }
            Text(
                "Invite block (the line your friend pastes into realmlist.wtf):",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                inviteBlock,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-invite-block"),
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(inviteBlock))
                },
                modifier = Modifier.testTag("friend-invite-copy"),
            ) { Text("Copy invite block") }

            // Guest logins (design-lan D5a, the desktop Guests card twin):
            // the host types the name, the password is GENERATED on this
            // device (80-bit, unambiguous alphabet), provisioned at gmLevel
            // 0 through the createAccount relay, and handed over PRIVATELY
            // — the invite block above never carries a credential.
            var guestName by remember { mutableStateOf("") }
            var guestPending by remember { mutableStateOf(false) }
            var guestStatus by remember { mutableStateOf<String?>(null) }
            var guestHandoff by remember { mutableStateOf<Pair<String, String>?>(null) }
            val guestNameValid = guestName.matches(Regex("[A-Za-z0-9]{1,16}"))
            Text("Guest login", style = MaterialTheme.typography.titleSmall)
            Text(
                "Create an account for a friend without the app — in the room with you, " +
                    "or a tunnel friend you are setting up at your invite's direction. " +
                    "The password is generated for you.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(16) },
                    label = { Text("Guest account name") },
                    isError = guestName.isNotEmpty() && !guestNameValid,
                    supportingText = { Text(if (guestName.isNotEmpty() && !guestNameValid) "1-16 letters or numbers" else "1-16 letters or numbers") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("guest-account-name"),
                )
                Button(
                    onClick = {
                        guestPending = true
                        guestStatus = null
                        scope.launch {
                            val password = GuestCredentials.generatePassword()
                            val created = runCatching {
                                supervisorClient.createAccount(guestName.trim(), password, 0)
                            }.getOrNull()
                            if (created?.optBoolean("ok") == true) {
                                guestHandoff = guestName.trim().uppercase() to password
                            } else {
                                guestStatus = "Refused (" +
                                    (created?.optString("code")
                                        ?.takeIf { it.isNotEmpty() } ?: "no answer") +
                                    ") — try another name."
                            }
                            guestPending = false
                        }
                    },
                    enabled = !guestPending && guestNameValid,
                    modifier = Modifier.testTag("guest-account-create"),
                ) { Text(if (guestPending) "Creating…" else "Create") }
            }
            guestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            guestHandoff?.let { (name, password) ->
                Text(
                    "Give this to your friend privately — in person, or a one-to-one " +
                        "message for a tunnel friend: username $name, password $password — " +
                        "they type it at the login screen. Don't paste it into a group chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("guest-handoff"),
                )
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString("username $name password $password"))
                }) { Text("Copy as one line") }
            }

            val invites = stateJson?.optJSONArray("invites")
            if (invites != null) {
                for (index in 0 until invites.length()) {
                    val row = invites.optJSONObject(index) ?: continue
                    when (row.optString("state")) {
                        "SUBMITTED" -> {
                            // Plan rule 7: peer addresses are NEVER
                            // displayed - the request row names the account
                            // only (the sourceIp stays in the audit trail
                            // the diagnostics lane already guards).
                            Text(
                                "Request: " + row.optString("username"),
                                modifier = Modifier.testTag("friend-request-row"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            val verdict = runCatching {
                                                supervisorClient.approveFriendRequest(row.optString("inviteId"))
                                            }.getOrNull()
                                            status = verdict?.optString("verdict")
                                            refreshState(); refreshRoster()
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { supervisorClient.rejectFriendRequest(row.optString("inviteId")) }
                                        refreshState()
                                    }
                                }) { Text("Reject") }
                            }
                        }
                        "OPEN" -> Row {
                            Text(
                                row.optString("inviteId") + " — waiting for your friend to submit",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { supervisorClient.revokeFriendInvite(row.optString("inviteId")) }
                                    refreshState()
                                }
                            }) { Text("Revoke") }
                        }
                    }
                }
            }

            val roster = rosterJson
            if (roster != null && roster.optBoolean("ok")) {
                val accounts = roster.optJSONArray("accounts")
                if (accounts != null && accounts.length() > 0) {
                    Text("Players", style = MaterialTheme.typography.titleSmall)
                    var onlineCount = 0
                    var banTarget by remember { mutableStateOf<String?>(null) }
                    for (index in 0 until accounts.length()) {
                        val row = accounts.optJSONObject(index) ?: continue
                        if (row.optBoolean("online")) onlineCount += 1
                        Text(
                            row.optString("username") + " · account " + row.optLong("id") + " · " +
                                (if (row.optBoolean("online")) "online" else "offline") +
                                (if (row.optBoolean("banned")) " · BANNED" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (row.optBoolean("banned")) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            supervisorClient.unbanFriendAccount(row.optString("username"))
                                        }
                                        refreshRoster()
                                    }
                                }) { Text("Unban") }
                            } else {
                                OutlinedButton(onClick = { banTarget = row.optString("username") }) {
                                    Text("Ban…")
                                }
                            }
                            GM_LEVELS.forEach { (level, label) ->
                                if (row.optInt("gmlevel") != level) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            runCatching {
                                                supervisorClient.setFriendGmLevel(row.optString("username"), level)
                                            }
                                            refreshRoster()
                                        }
                                    }) { Text(label) }
                                }
                            }
                        }
                        Text(
                            "Full console outranks these moderation tools — grant it only when you mean it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "$onlineCount of ${accounts.length()} in world — failed-logins and last-logon " +
                            "detail live on the desktop Players screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    banTarget?.let { username ->
                        FriendBanDialog(
                            username = username,
                            onDismiss = { banTarget = null },
                            onBan = { duration, reason ->
                                banTarget = null
                                scope.launch {
                                    runCatching {
                                        supervisorClient.banFriendAccount(username, duration, reason)
                                    }
                                    refreshRoster()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FriendBanDialog(
    username: String,
    onDismiss: () -> Unit,
    onBan: (duration: String, reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ban $username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(BAN_REASON_MAX_CHARS) },
                    label = { Text("Reason (kept in the ban record)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onBan("1h", reason) }) { Text("1 hour") }
                    TextButton(onClick = { onBan("1d", reason) }) { Text("24 hours") }
                    TextButton(onClick = { onBan("0", reason) }) { Text("Permanent") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
