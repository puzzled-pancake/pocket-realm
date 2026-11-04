package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.server.FriendAccessClient
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.ui.FriendRequestOutcome
import com.pocketrealm.ui.RealFriendAccessTransport
import com.pocketrealm.ui.joinerRefusalCopy
import com.pocketrealm.ui.requestFriendAccess
import kotlinx.coroutines.launch

/**
 * The Direct-join tab's Advanced panel: asking a host for an account with
 * an invite (the old friend-request card, moved verbatim). Technical words
 * are allowed here; the Direct-join tab itself stays plain.
 */

private const val FRIEND_REQUEST_FIELD_MAX_CHARS = 16

@Composable
internal fun FriendRequestPane(
    suggestedHostAddress: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostAddress by remember { mutableStateOf(suggestedHostAddress) }
    var inviteId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var savedAccountId by remember { mutableStateOf(0L) }

    // Design-remote-accounts (the desktop JoinRequestAccessCard twin):
    // the provisioning listener binds the HOST'S PRIVATE LAN address only,
    // so this field validates the LAN grammar (parseLan — private/link-local
    // only, hostnames and public IPv4 refused) and the card carries the
    // note. A tunnel (CGNAT) address can join the realm through the Join
    // pane, but it can never reach this card — green-lighting it here would
    // only guarantee a "could not reach" after the connect timeout.
    val addressValid = runCatching { RealmEndpoint.parseLan(hostAddress.trim()) }.isSuccess
    val fieldsValid = addressValid && inviteId.isNotBlank() && code.isNotBlank() &&
        username.matches(Regex("[A-Za-z0-9]{1,16}")) &&
        password.matches(Regex("[A-Za-z0-9]{1,16}")) && password == passwordRepeat

    Card(modifier = modifier.testTag("friend-request-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Request an account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick your own name and password — the password never leaves this device. " +
                    "The host sees only a verifier and approves you by name.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = hostAddress,
                onValueChange = { hostAddress = it.take(15) },
                label = { Text("Host IPv4") },
                isError = hostAddress.isNotEmpty() && !addressValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-host"),
            )
            Text(
                "This card reaches the host over your shared LAN only — a tunnel " +
                    "address joins the realm but cannot submit a request.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-request-lan-only-note"),
            )
            OutlinedTextField(
                value = inviteId,
                onValueChange = { inviteId = it.take(16) },
                label = { Text("Invite id (FRD-…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-invite-id"),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Invite code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-code"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Desired account name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-username"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-password"),
            )
            OutlinedTextField(
                value = passwordRepeat,
                onValueChange = { passwordRepeat = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Repeat password") },
                isError = passwordRepeat.isNotEmpty() && password != passwordRepeat,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-password-repeat"),
            )
            Button(
                onClick = {
                    pending = true
                    status = "Sending request to " + hostAddress.trim() + "…"
                        scope.launch {
                            val outcome = requestFriendAccess(
                                transport = RealFriendAccessTransport(FriendAccessClient()),
                                host = hostAddress.trim(),
                                port = ServerRuntimeContract.PROVISION_PORT,
                                inviteId = inviteId.trim(),
                                token = code.trim(),
                                username = username.trim(),
                                password = password,
                                onApproved = { accountId ->
                                    // (design-remote-accounts): save for
                                    // auto-login exactly like the host's account card.
                                    savedAccountId = runCatching {
                                        UserAccountStore(context).save(username.trim(), password, accountId)
                                        accountId
                                    }.getOrDefault(0L)
                                },
                                onWaitUpdate = { waitStatus -> status = waitStatus },
                            )
                            when (outcome) {
                                is FriendRequestOutcome.Approved -> {
                                    status = if (savedAccountId > 0) {
                                        "Approved — account $savedAccountId saved; join the host and play."
                                    } else {
                                        "Approved as account " + outcome.accountId + ", but saving it failed — " +
                                            "log in with this name and password."
                                    }
                                }
                                FriendRequestOutcome.Refused ->
                                    // the refusal cause is
                                    // indistinguishable by protocol design -
                                    // never assert one, never invite retries
                                    // against a rate lock.
                                    status = joinerRefusalCopy()
                                is FriendRequestOutcome.Unreachable -> status = outcome.reason
                                FriendRequestOutcome.Submitted ->
                                    // The poll budget spans the host's whole
                                    // 15-minute decision window: no decision means
                                    // the request expired host-side. Say the checking
                                    // stopped and what to do next — never a
                                    // present-tense "the host has not decided yet".
                                    status = "Stopped checking after 15 minutes — the host " +
                                        "did not answer in time, so this " +
                                        "request expired. Ask for a fresh invite and " +
                                        "submit again."
                            }
                            pending = false
                        }
                },
                enabled = !pending && fieldsValid,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-submit"),
            ) { Text(if (pending) "Waiting for the host…" else "Request access") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
