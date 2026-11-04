package com.pocketrealm.desktop.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.supervisor.FriendRosterJson
import com.pocketrealm.supervisor.ProvisioningListener
import com.pocketrealm.supervisor.ProvisioningListener.ListenerState.Failed as ListenerFailed
import com.pocketrealm.supervisor.ProvisioningListener.ListenerState.Listening as ListenerListening
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.RuntimeMode
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * The Players screen (design-remote-accounts): the full host management
 * surface for remote friend accounts.
 *
 *  - Friend access card: the provisioning listener's live state (off /
 * listening on <addr:port> / failed to bind — the exposure invariant:
 *    invite affordances never render without it), the invite composer
 *    (note + Generate; the code shows ONCE in a copyable dialog — the raw
 * token is never stored), open invites with Revoke, and the
 *    pending-request cards (requested username, source IP, note, age) with
 *    Approve / Reject.
 * Friends card: the roster — username, accountId, bot label,
 *    gmlevel, online, ban state, last logon (the TIMESTAMP only — the
 * address is never displayed, plan rule 7), failed attempts since
 * that logon (the LIVE failed_logins counter, labeled as such) —
 * with per-row gmlevel (level 3 labeled "full console" honestly),
 * Ban (1h/24h/permanent + reason) and Unban, and the per-friend
 * character list .
 *
 * The host's OWN account stays the Home "Local account" card — Players
 * never creates or edits the host identity .
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // one reviewed surface, four cards
fun PlayersAdvanced(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    val invites by model.friendAccess.invites.collectAsState()
    val listenerState by model.friendAccess.listenerState.collectAsState()
    val status by model.friendAccess.status.collectAsState()
    val freshToken by model.friendAccess.freshToken.collectAsState()
    val snapshot by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var rosterReason by remember { mutableStateOf<String?>("loading") }
    var roster by remember { mutableStateOf<List<FriendRosterJson.AccountRow>>(emptyList()) }
    var expandedCharacters by remember { mutableStateOf<String?>(null) }
    var characters by remember { mutableStateOf<List<FriendRosterJson.CharacterRow>>(emptyList()) }
    var banTarget by remember { mutableStateOf<String?>(null) }

    fun refreshRoster() {
        scope.launch {
            val read = model.friendAccess.roster()
            rosterReason = read.reason
            roster = read.accounts
        }
    }
    LaunchedEffect(Unit) { refreshRoster() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Players", style = MaterialTheme.typography.headlineSmall)

        // ---- Public realm: internet players with account/device bans ----
        PublicPlayersCard(model)

        // ---- Friend access: listener state + invites + pending requests ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Friend access", style = MaterialTheme.typography.titleMedium)
                val listening = listenerState as? ListenerListening
                val failed = listenerState as? ListenerFailed
                Text(
                    when {
                        failed != null ->
                            "Listener FAILED to bind: ${failed.reason} " +
                                "(is something else using port ${ServerRuntimeContract.PROVISION_PORT}?)"
                        listening != null ->
                            "Listening on ${listening.address}:${listening.port}"
                        settings.friendAccessEnabled ->
                            "Off — the provisioning socket binds while a LAN-host realm with friend access is up."
                        else ->
                            "Off — enable it below; nothing listens while friend access is off."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Friends with the Pocket Realm app join with an invite code; the password " +
                        "they choose never leaves their device — they send a verifier, you approve it. " +
                        "Their requests reach this host over your LAN only — a friend joining through " +
                        "your tunnel cannot submit one; create their account on the Guests card.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.runtimeMode == RuntimeMode.LAN_HOST) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(
                            checked = settings.friendAccessEnabled,
                            onCheckedChange = { value ->
                                model.updateSettings { it.copy(friendAccessEnabled = value) }
                            },
                        )
                        Column {
                            Text("Allow friend access while hosting", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Adds the provisioning port (${ServerRuntimeContract.PROVISION_PORT}) to the LAN " +
                                    "posture at the next realm start; Windows Firewall may ask to allow it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Text(
                        "Set mode to Host on LAN (Settings → LAN play) to expose invites.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(64) },
                        label = { Text("Note (e.g. \"Dave\")") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = invites.count { it.state == FriendInviteStore.State.OPEN } <
                            FriendInviteStore.MAX_OPEN_INVITES,
                        onClick = { model.friendAccess.createInvite(note) },
                    ) { Text("Invite") }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                invites.filter { it.state == FriendInviteStore.State.SUBMITTED }.forEach { pending ->
                    PendingRequestCard(
                        row = pending,
                        onApprove = {
                            scope.launch {
                                model.friendAccess.approveInvite(pending.inviteId)
                                refreshRoster()
                            }
                        },
                        onReject = {
                            model.friendAccess.rejectInvite(pending.inviteId)
                            refreshRoster()
                        },
                    )
                }

                invites.filter { it.state == FriendInviteStore.State.OPEN }.forEach { invite ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            invite.inviteId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                invite.note.ifEmpty { "no note" } +
                                    " — waiting for your friend to submit",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = { model.friendAccess.revokeInvite(invite.inviteId) }) {
                            Text("Revoke")
                        }
                    }
                }
                val decided = invites.filter {
                    it.state != FriendInviteStore.State.OPEN && it.state != FriendInviteStore.State.SUBMITTED
                }
                decided.take(RECENT_DECIDED_ROWS)
                    .forEach { invite ->
                        Text(
                            "${invite.inviteId}: ${renderState(invite)}" +
                                (invite.username?.let { " — $it" } ?: "") +
                                (invite.accountId?.let { " (account $it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
        }

        // ---- Friends ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Friends", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { refreshRoster() }) { Text("Refresh") }
                }
                rosterReason?.let { reason ->
                    Text(
                        "The realm must be up to read accounts" +
                            (if (reason != "world-not-ready") " ($reason)" else "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                roster.forEach { account ->
                    FriendRowCard(
                        account = account,
                        charactersShown = expandedCharacters == account.username,
                        characters = if (expandedCharacters == account.username) characters else emptyList(),
                        onToggleCharacters = {
                            if (expandedCharacters == account.username) {
                                expandedCharacters = null
                            } else {
                                expandedCharacters = account.username
                                scope.launch {
                                    characters = model.friendAccess.characters(account.username).characters
                                }
                            }
                        },
                        onGmLevel = { level ->
                            scope.launch {
                                model.friendAccess.setGmLevel(account.username, level)
                                refreshRoster()
                            }
                        },
                        onBan = { banTarget = account.username },
                        onUnban = {
                            scope.launch {
                                model.friendAccess.unban(account.username)
                                refreshRoster()
                            }
                        },
                    )
                }
            }
        }

        // ---- Audit tail (no secrets ride these rows) ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent friend-access activity", style = MaterialTheme.typography.titleMedium)
                val tail = model.friendAccess.auditTail().takeLast(10).reversed()
                if (tail.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                tail.forEach { row ->
                    Text(
                        "${row.optLong("at")} ${row.optString("action")}" +
                            row.optStringOrNull("inviteId")?.let { " $it" }.orEmpty() +
                            row.optStringOrNull("username")?.let { " — $it" }.orEmpty() +
                            " (${row.optString("outcome")})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // The once-only invite code dialog (the raw token is never stored).
    freshToken?.let { (inviteId, token) ->
        AlertDialog(
            onDismissRequest = { model.friendAccess.dismissFreshToken() },
            title = { Text("Invite $inviteId") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Give this code to your friend:")
                    Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Shown only once — the app stores a hash, not the code. Your friend enters it " +
                            "with your realm address " +
                            "${snapshot.realmEndpoint.address}:${ServerRuntimeContract.PROVISION_PORT} — " +
                            "reachable from your LAN only (the provisioning listener binds your LAN " +
                            "address, never a tunnel address). A friend outside your LAN skips this " +
                            "flow: create their account on the Guests card.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection("$inviteId $token"), null)
                    model.friendAccess.dismissFreshToken()
                }) { Text("Copy & close") }
            },
            dismissButton = {
                TextButton(onClick = { model.friendAccess.dismissFreshToken() }) { Text("Close") }
            },
        )
    }

    banTarget?.let { username ->
        BanDialog(
            username = username,
            onDismiss = { banTarget = null },
            onBan = { duration, reason ->
                banTarget = null
                scope.launch {
                    model.friendAccess.ban(username, duration, reason)
                    refreshRoster()
                }
            },
        )
    }
}

@Composable
private fun PendingRequestCard(
    row: FriendInviteStore.InviteRow,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Plan rule 7: peer addresses are NEVER displayed (the
            // sourceIp stays in the audit trail, never on this surface).
            Text(
                "Request: ${row.username ?: "?"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.note.ifEmpty { "no note" } + " — submitted " + renderAge(row.submittedAtMs),
                style = MaterialTheme.typography.bodySmall,
            )
            // its decision window, at the surface where the host acts on
            // it: the invite expires 15 minutes after the friend submitted
            // (SUBMISSION_WINDOW_MS), and a late Approve silently lands
            // EXPIRED — say the deadline and, when it is computable, the
            // time still left.
            Text(
                row.expiresAtMs?.let { expiresAtMs ->
                    val minutesLeft = (expiresAtMs - System.currentTimeMillis()) / MINUTE_MS
                    if (minutesLeft > 0) {
                        "Decide within 15 minutes of submission — about $minutesLeft minute" +
                            (if (minutesLeft == 1L) "" else "s") + " left."
                    } else {
                        "The 15-minute approval window has closed — approving now will not go through."
                    }
                } ?: "Decide within 15 minutes of submission.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}

@Composable
@Suppress("MagicNumber", "LongParameterList") // gm labels; one callback per row action (the RealmControlCard pattern)
private fun FriendRowCard(
    account: FriendRosterJson.AccountRow,
    charactersShown: Boolean,
    characters: List<FriendRosterJson.CharacterRow>,
    onToggleCharacters: () -> Unit,
    onGmLevel: (Int) -> Unit,
    onBan: () -> Unit,
    onUnban: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${account.username} · account ${account.id}" +
                    (if (account.bot) " · bot" else "") + " · " +
                    (if (account.online) "online now" else "offline") + " · " +
                    (if (account.banned) "BANNED" else "not banned"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                // R3-1/R5-4 (rule 7): the last-logon ADDRESS is never
                // rendered — the payload dropped it and this card names
                // only the timestamp. Bot rows carry an explicit "bot"
                // label above, never padding the friends roster.
                "Last logon: " + account.lastLogon.ifEmpty { "never" } +
                    " · failed attempts since then: ${account.failedLogins} (live counter, resets on logon)",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Permissions:", style = MaterialTheme.typography.bodySmall)
                GM_LEVELS.forEach { (level, label) ->
                    if (account.gmLevel == level) {
                        Button(onClick = {}, enabled = false) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onGmLevel(level) }) { Text(label) }
                    }
                }
            }
            Text(
                "Full console outranks these moderation tools — grant it only when you mean it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleCharacters) {
                    Text(if (charactersShown) "Hide characters" else "Characters")
                }
                if (account.banned) {
                    OutlinedButton(onClick = onUnban) { Text("Unban") }
                } else {
                    OutlinedButton(onClick = onBan) { Text("Ban…") }
                }
            }
            if (charactersShown) {
                if (characters.isEmpty()) {
                    Text("No characters yet.", style = MaterialTheme.typography.bodySmall)
                }
                characters.forEach { character ->
                    Text(
                        "${character.name} — level ${character.level} " +
                            "(race ${character.race}, class ${character.klass})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BanDialog(
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

private fun renderState(invite: FriendInviteStore.InviteRow): String = when (invite.state) {
    FriendInviteStore.State.PROVISIONED -> "provisioned"
    FriendInviteStore.State.REJECTED -> "rejected"
    FriendInviteStore.State.EXPIRED -> "expired"
    FriendInviteStore.State.BURNED -> "burned"
    else -> invite.state.name.lowercase()
}

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L

private fun renderAge(submittedAtMs: Long?): String {
    if (submittedAtMs == null) return "just now"
    val minutes = (System.currentTimeMillis() - submittedAtMs) / MINUTE_MS
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "1 minute ago"
        minutes < MINUTES_PER_HOUR -> "$minutes minutes ago"
        else -> "${minutes / MINUTES_PER_HOUR}h ${(minutes % MINUTES_PER_HOUR).toInt()}m ago"
    }
}

private fun org.json.JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

// The levels map to cmangos security classes; 3 is labeled honestly.
@Suppress("MagicNumber") // the gm band is the data
private val GM_LEVELS = listOf(0 to "Player", 1 to "Helper", 2 to "Moderator", 3 to "Full console")
private const val RECENT_DECIDED_ROWS = 6
private const val BAN_REASON_MAX_CHARS = 96

/**
 * The host's self sign-up controls (offered while publishing): the Open
 * sign-up switch, the optional cap, and the signed-up accounts with
 * Remove (a permanent ban — the world has no account delete).
 */
@Composable
internal fun HostSignupControls(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf(model.signupAccounts()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, settings.signupOpen) { accounts = model.signupAccounts() }
    Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-signup")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.signupOpen,
                    onCheckedChange = { open -> model.updateSettings { it.copy(signupOpen = open) } },
                    modifier = Modifier.testTag("realm-browser-signup-switch"),
                )
                Text("Open sign-up", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                SIGNUP_HOST_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.signupOpen) {
                OutlinedTextField(
                    value = if (settings.signupCap == 0) "" else settings.signupCap.toString(),
                    onValueChange = { raw ->
                        val cap = raw.filter(Char::isDigit).take(CAP_DIGITS).toIntOrNull()
                            ?.coerceIn(0, Settings.SIGNUP_CAP_MAX) ?: 0
                        model.updateSettings { it.copy(signupCap = cap) }
                    },
                    label = { Text("Sign-up limit (empty = no limit)") },
                    singleLine = true,
                    modifier = Modifier.testTag("realm-browser-signup-cap"),
                )
            }
            Text(
                "Signed-up accounts (${accounts.size}" +
                    (if (settings.signupCap > 0) " of ${settings.signupCap}" else "") + ")",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (accounts.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            accounts.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.username, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch {
                                val result = model.removeSignupAccount(entry.username)
                                note = if (result.ok) {
                                    "${entry.username} removed (banned permanently; the name stays taken)."
                                } else {
                                    "Could not remove ${entry.username}: ${result.detail} (the realm must be running)."
                                }
                                refresh += 1
                            }
                        },
                        modifier = Modifier.testTag("realm-browser-signup-remove"),
                    ) { Text("Remove") }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal const val SIGNUP_HOST_COPY: String =
    "While on (and the listing is published), anyone who finds the realm can create their own " +
        "account on first join — never a GM account. They choose the password on their own " +
        "device; only a one-way verifier reaches this realm. Remove bans an account permanently. " +
        "Each device can make a few accounts at most."

private const val CAP_DIGITS = 5

/**
 * The public realm's players: everyone who signed up or logged in over
 * the internet, with account bans (the realm's own ban, which also kicks
 * an online player), device bans (the joining app's per-host device id —
 * honest that a modified app can fake it), and the banned devices.
 */
@Composable
internal fun PublicPlayersCard(model: DesktopAppModel) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var players by remember { mutableStateOf(model.publicPlayers()) }
    var bans by remember { mutableStateOf(model.publicDeviceBans()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        while (true) {
            players = model.publicPlayers()
            bans = model.publicDeviceBans()
            kotlinx.coroutines.delay(PLAYERS_POLL_MS)
        }
    }
    val act: (com.pocketrealm.supervisor.PublicHostModerator.Action, String) -> Unit = { action, target ->
        scope.launch {
            note = model.moderatePublicPlayer(action, target).detail
            refresh += 1
        }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("public-players-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Internet players (${players.size})", style = MaterialTheme.typography.titleMedium)
            Text(
                "People who joined your public realm over the internet. Ban account stops the " +
                    "account from logging in and kicks it if online. Ban device blocks the computer " +
                    "or phone it joined from, including new accounts made on it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (players.isEmpty()) {
                Text("No one has joined over the internet yet.", style = MaterialTheme.typography.bodySmall)
            }
            val nowMs = System.currentTimeMillis()
            players.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.account, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            com.pocketrealm.supervisor.PublicPlayersCopy.statusLine(row, nowMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            act(
                                if (row.accountBanned) com.pocketrealm.supervisor.PublicHostModerator.Action.UNBAN_ACCOUNT
                                else com.pocketrealm.supervisor.PublicHostModerator.Action.BAN_ACCOUNT,
                                row.account,
                            )
                        },
                        modifier = Modifier.testTag("public-player-account"),
                    ) { Text(if (row.accountBanned) "Unban account" else "Ban account") }
                    if (row.devices > 0) {
                        TextButton(
                            onClick = {
                                act(
                                    if (row.deviceBanned) com.pocketrealm.supervisor.PublicHostModerator.Action.UNBAN_DEVICE
                                    else com.pocketrealm.supervisor.PublicHostModerator.Action.BAN_DEVICE,
                                    row.account,
                                )
                            },
                            modifier = Modifier.testTag("public-player-device"),
                        ) { Text(if (row.deviceBanned) "Unban device" else "Ban device") }
                    }
                    if (!row.accountBanned && !row.deviceBanned && row.devices > 0) {
                        TextButton(
                            onClick = { act(com.pocketrealm.supervisor.PublicHostModerator.Action.BAN_BOTH, row.account) },
                            modifier = Modifier.testTag("public-player-both"),
                        ) { Text("Ban both") }
                    }
                }
            }
            if (bans.isNotEmpty()) {
                Text("Banned devices", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                bans.forEach { ban ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Device ${com.pocketrealm.discovery.iroh.DeviceIdentity.shortLabel(ban.token)}" +
                                (ban.account?.let { " (used by $it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { act(com.pocketrealm.supervisor.PublicHostModerator.Action.UNBAN_DEVICE, ban.token) },
                            modifier = Modifier.testTag("public-device-unban"),
                        ) { Text("Unban") }
                    }
                }
            }
            Text(
                com.pocketrealm.supervisor.PublicHostModerator.DEVICE_BAN_LIMITS_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val PLAYERS_POLL_MS = 5_000L
