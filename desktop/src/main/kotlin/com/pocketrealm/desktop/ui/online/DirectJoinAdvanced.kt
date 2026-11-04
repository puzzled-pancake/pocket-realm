package com.pocketrealm.desktop.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.ui.accountCredentialFieldError
import com.pocketrealm.ui.joinerRefusalCopy
import kotlinx.coroutines.launch

/**
 * The Multiplayer Direct join tab's Advanced fold ("more ways to get an
 * account"): the friend-side invite-code request form moved verbatim from
 * the Home join card. The form takes no suggested-host parameter — it
 * seeds its host field from the stored LAN address itself — so
 * [suggestedHostAddress] is carried by the tab contract and stays unused
 * here, exactly as Home called it.
 */
@Composable
internal fun DirectJoinAdvancedPane(
    model: DesktopAppModel,
    suggestedHostAddress: String,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JoinRequestAccessCard(model)
    }
}

/**
 * The join-mode friend card: host address, invite code, desired
 * username, password (typed twice) — the password is derived into an SRP6
 * verifier ON THIS DEVICE and only `{username, sHex, vHex}` crosses to the
 * host, bound to the invite token. On approval the account is saved for
 * auto-login exactly like the host's own account card; on refusal the
 * derived verifier is dropped (the client drops it for every non-PROVISIONED
 * verdict).
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // the friend-side request form is one surface; the poll loop mirrors the flow
private fun JoinRequestAccessCard(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var hostAddress by remember(settings.lanAddress) { mutableStateOf(settings.lanAddress) }
    var inviteId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val usernameError = accountCredentialFieldError(username)
    val passwordError = accountCredentialFieldError(password)
    val passwordsDiffer = password.isNotEmpty() && passwordRepeat.isNotEmpty() && password != passwordRepeat
    val addressValid = runCatching {
        com.pocketrealm.supervisor.RealmEndpoint.parseLan(hostAddress.trim())
    }.isSuccess
    val fieldsValid = addressValid && inviteId.isNotBlank() && code.isNotBlank() &&
        usernameError == null && passwordError == null && !passwordsDiffer

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Request an account from the host", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick your own name and password — the password never leaves this computer. " +
                    "The host sees only a verifier and approves you by name.",
                style = MaterialTheme.typography.bodySmall,
            )
            // exposure honesty: the provisioning listener binds the host's
            // LAN address only, so this card cannot reach a host through a
            // tunnel — say so where the address is typed, not just in the
            // field's refusal.
            Text(
                "This card reaches the host over your shared LAN only — a tunnel " +
                    "address joins the realm but cannot submit a request. Over a " +
                    "tunnel, ask the host to create your account on their Guests card.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = hostAddress,
                onValueChange = { hostAddress = it.take(15) },
                label = { Text("Host address") },
                isError = hostAddress.isNotEmpty() && !addressValid,
                supportingText = {
                    Text(
                        when {
                            hostAddress.isEmpty() -> "the LAN host's IPv4 (e.g. 192.168.1.20)"
                            addressValid -> "the host's provisioning port ${ServerRuntimeContract.PROVISION_PORT} is used automatically"
                            else -> "a canonical private IPv4 address is required (no hostnames)"
                        },
                    )
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = inviteId,
                onValueChange = { inviteId = it.take(16) },
                label = { Text("Invite id (FRD-…)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Invite code") },
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(CREDENTIAL_MAX_CHARS) },
                label = { Text("Desired account name") },
                isError = usernameError != null,
                supportingText = { Text(usernameError ?: "1–16 letters or numbers") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(CREDENTIAL_MAX_CHARS) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordError != null,
                    supportingText = { Text(passwordError ?: "1–16 letters or numbers") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = passwordRepeat,
                    onValueChange = { passwordRepeat = it.take(CREDENTIAL_MAX_CHARS) },
                    label = { Text("Repeat") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordsDiffer,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = pending || fieldsValid,
                onClick = {
                    if (pending) {
                        // The wait is never a dead end: a friend whose host
                        // dropped mid-poll can back out and re-request with
                        // the same (still-valid) invite code.
                        pending = false
                        status = "Request cancelled — nothing was saved. The same invite " +
                            "code still works while it is valid."
                        return@Button
                    }
                    pending = true
                    status = "Sending request to $hostAddress…"
                    scope.launch {
                        when (
                            val verdict = com.pocketrealm.server.FriendAccessClient().requestAccess(
                                host = hostAddress.trim(),
                                port = ServerRuntimeContract.PROVISION_PORT,
                                inviteId = inviteId.trim(),
                                token = code.trim(),
                                username = username.trim(),
                                password = password,
                            )
                        ) {
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Submitted -> {
                                status = "Request submitted — waiting for the host to approve you…"
                                // The read-only poll: the host's approval
                                // is what turns this into a saved account.
                                var unreachableStreak = 0
                                while (pending) {
                                    kotlinx.coroutines.delay(POLL_DELAY_MS)
                                    when (
                                        val poll = com.pocketrealm.server.FriendAccessClient().requestAccess(
                                            host = hostAddress.trim(),
                                            port = ServerRuntimeContract.PROVISION_PORT,
                                            inviteId = verdict.inviteId,
                                            token = code.trim(),
                                            username = username.trim(),
                                            password = password,
                                        )
                                    ) {
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Submitted -> {
                                            unreachableStreak = 0
                                            status = "Request submitted — waiting for the host to approve you…"
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Unreachable -> {
                                            // a dropped host must never present
                                            // as "waiting for the host to click
                                            // Approve" — surface the first mid-wait
                                            // failure and keep retrying.
                                            unreachableStreak += 1
                                            if (unreachableStreak == 1) {
                                                status = "The host became unreachable while you wait " +
                                                    "(they may be saving or offline) — still retrying " +
                                                    "every few seconds. Cancel backs out."
                                            }
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Provisioned -> {
                                            val stored = runCatching {
                                                model.accountStore.save(
                                                    username.trim(), password, poll.accountId,
                                                )
                                            }.isSuccess
                                            status = if (stored) {
                                                // Honest lane copy: on a JOINED host there is no
                                                // local world owner, so the credential-proof gate
                                                // degrades to manual login — the save is real, the
                                                // "auto-login" promise is not (the wiki's guest
                                                // section states the same).
                                                "Approved — account ${poll.accountId} saved. Join the " +
                                                    "LAN host and log in with it."
                                            } else {
                                                "Approved as account ${poll.accountId}, but saving it " +
                                                    "locally failed — rejoin with this name and password."
                                            }
                                            pending = false
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Refused -> {
                                            // indistinguishable by protocol design;
                                            // never assert a cause, never invite retries against a lock.
                                            status = joinerRefusalCopy()
                                            pending = false
                                        }
                                    }
                                }
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Provisioned -> {
                                // Immediate approval answered the submit itself.
                                runCatching {
                                    model.accountStore.save(username.trim(), password, verdict.accountId)
                                }
                                status = "Approved — account ${verdict.accountId} saved. Join the host and log in with it."
                                pending = false
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Refused -> {
                                // indistinguishable by protocol design;
                                // never assert a cause, never invite retries against a lock.
                                status = joinerRefusalCopy()
                                pending = false
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Unreachable -> {
                                status = "Could not reach $hostAddress:${ServerRuntimeContract.PROVISION_PORT} " +
                                    "(${verdict.reason}). Check the host's address, that the realm is up, " +
                                    "and that friend access is enabled there."
                                pending = false
                            }
                        }
                        pending = false
                    }
                },
            ) { Text(if (pending) "Cancel request" else "Request access") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val POLL_DELAY_MS = 3_000L

/** The account credential cap (the realm rule's 16); the field keeps raw
 * keystrokes up to this length — DESK-136 deleted the character-class
 * mangling that silently discarded what the user typed. */
private const val CREDENTIAL_MAX_CHARS = 16
