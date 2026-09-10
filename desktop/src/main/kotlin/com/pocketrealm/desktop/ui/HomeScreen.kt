package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.UserAccountStore
import java.io.File
import kotlinx.coroutines.launch

/**
 * The de-cliented Home screen: realm control, active setup, and the local
 * account card (the Android HomeScreen minus every Wine/renderer/Vulkan
 * surface — the WoW client is native and always available when installed).
 */
@Composable
@Suppress("LongMethod")
fun HomeScreen(model: DesktopAppModel, onOpenBots: () -> Unit, onOpenSettings: () -> Unit) {
    val snapshot by model.state.collectAsState()
    val settings by model.settings.collectAsState()
    val storedAccount by model.storedAccount.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf<String?>(null) }
    var showForceStop by remember { mutableStateOf(false) }

    val botProfile = remember(settings) {
        BotSelection.resolve(
            savedPresetId = settings.botSavedPresetId,
            advancedEnabled = false,
            advancedTarget = settings.botPopulationTarget,
            advanced = com.pocketrealm.bots.BotAdvancedSettings.fromProfile(BotProfiles.defaultProfile),
            profileId = settings.botProfileId,
        ).profile
    }
    val botsSelected = settings.botProfileId.isNotEmpty() || settings.botSavedPresetId != null
    val clientDir = remember(settings) { model.resolveClientDir() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RealmControlCard(
            phase = snapshot.phase,
            detail = snapshot.lastError ?: snapshot.lastDurableAction,
            busy = busy,
            onStartRealm = { includeClient ->
                busy = true
                scope.launch {
                    model.startRealm(includeClient)
                    if (includeClient) model.autoLoginIfEnabled()
                    busy = false
                }
            },
            onSaveAndExit = {
                busy = true
                scope.launch {
                    model.saveAndExit()
                    busy = false
                }
            },
            onForceStop = { showForceStop = true },
        )
        ActiveSetupCard(
            botsSelected = botsSelected,
            profileName = if (botsSelected) botProfile.displayName else null,
            target = if (botsSelected) botProfile.selectedTarget else 0,
            autoLogin = settings.autoLoginOnLaunch,
            storedAccountName = storedAccount?.username,
            clientDir = clientDir,
            onOpenBots = onOpenBots,
            onOpenSettings = onOpenSettings,
            onChooseClientDir = { dir ->
                model.updateSettings { it.copy(clientDir = dir.absolutePath) }
            },
        )
        AccountCard(
            model = model,
            worldReady = snapshot.phase == RuntimePhase.WORLD_READY ||
                snapshot.phase == RuntimePhase.RUNNING ||
                snapshot.phase == RuntimePhase.PAUSED,
            status = accountStatus,
            onStatus = { accountStatus = it },
            storedAccount = storedAccount,
        )
    }

    if (showForceStop) {
        AlertDialog(
            onDismissRequest = { showForceStop = false },
            title = { Text("Force stop realm") },
            text = {
                Text(
                    "This force-stops the leftover world from the failed start. " +
                        "Unsaved progress in that world is lost; the realm databases are " +
                        "checked and repaired automatically on the next start.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceStop = false
                    busy = true
                    scope.launch {
                        model.consentedForceStopOrphanStack()
                        busy = false
                    }
                }) { Text("Force stop") }
            },
            dismissButton = { TextButton(onClick = { showForceStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // one action row per runtime phase
private fun RealmControlCard(
    phase: RuntimePhase,
    detail: String,
    busy: Boolean,
    onStartRealm: (includeClient: Boolean) -> Unit,
    onSaveAndExit: () -> Unit,
    onForceStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (title, subtitle) = when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED -> "Ready to start" to
                    "Your local realm is stopped and safely saved."
                RuntimePhase.PREPARING, RuntimePhase.DB_STARTING, RuntimePhase.REALM_STARTING,
                RuntimePhase.WORLD_STARTING, RuntimePhase.CLIENT_STARTING, RuntimePhase.RECOVERING ->
                    "Working" to "Preparing the realm (${
                        phase.name.lowercase().replace('_', ' ')
                    })."
                RuntimePhase.WORLD_READY -> "Realm online" to
                    "The world and loopback-only services are ready."
                RuntimePhase.RUNNING -> "Realm online" to "The world and your game client are up."
                RuntimePhase.PAUSED -> "Realm paused" to "The world is paused for a sit-and-talk session."
                RuntimePhase.CLIENT_FAILED -> "Realm online; game needs retry" to
                    "The game client failed to start or exited unexpectedly."
                RuntimePhase.STOPPING -> "Stopping" to "Closing the client and local services safely."
                RuntimePhase.ERROR -> "Needs attention" to detail
            }
            Text("Realm", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED, RuntimePhase.ERROR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { onStartRealm(false) }) {
                            Text(if (busy) "Starting realm…" else "Start realm")
                        }
                        OutlinedButton(enabled = !busy, onClick = { onStartRealm(true) }) {
                            Text("Realm + game")
                        }
                    }
                    Text(
                        "Start the realm, create your account, then start the game.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (phase == RuntimePhase.ERROR) {
                        OutlinedButton(onClick = onForceStop) { Text("Force stop realm") }
                    }
                }
                RuntimePhase.WORLD_READY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { onStartRealm(true) }) {
                            Text(if (busy) "Starting game…" else "Start game")
                        }
                        OutlinedButton(enabled = !busy, onClick = onSaveAndExit) {
                            Text("Save & exit")
                        }
                    }
                }
                RuntimePhase.CLIENT_FAILED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { onStartRealm(true) }) {
                            Text(if (busy) "Retrying game…" else "Retry game")
                        }
                        OutlinedButton(enabled = !busy, onClick = onSaveAndExit) {
                            Text("Save & exit")
                        }
                    }
                }
                RuntimePhase.RUNNING, RuntimePhase.PAUSED -> {
                    Button(enabled = !busy, onClick = onSaveAndExit) {
                        Text(if (busy) "Saving…" else "Save & exit")
                    }
                }
                else -> OutlinedButton(enabled = false, onClick = {}) { Text("Working…") }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun ActiveSetupCard(
    botsSelected: Boolean,
    profileName: String?,
    target: Int,
    autoLogin: Boolean,
    storedAccountName: String?,
    clientDir: File?,
    onOpenBots: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseClientDir: (File) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Active setup", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBots) { Text("Bots") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            Text(
                if (botsSelected && profileName != null) {
                    "$profileName · $target bots"
                } else {
                    "No bot population selected — the realm starts with bots off (pick a profile in Bots)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Game client: " + when {
                    clientDir != null -> "ready (${clientDir.absolutePath})"
                    else -> "not found — choose the folder that contains WoW.exe"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (clientDir == null) {
                ClientDirPicker(onChooseClientDir)
            }
            Text(
                if (autoLogin) {
                    if (storedAccountName != null) {
                        "Auto-login: $storedAccountName"
                    } else {
                        "Auto-login waiting for account"
                    }
                } else {
                    "Manual login"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ClientDirPicker(onChoose: (File) -> Unit) {
    // java.awt.FileDialog blocks its OWN thread until dismissed; compose on
    // the UI thread would freeze, so the dialog opens on a worker and posts
    // the chosen folder back through state.
    var choosing by remember { mutableStateOf(false) }
    OutlinedButton(enabled = !choosing, onClick = {
        choosing = true
        Thread {
            val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
            val dialog = java.awt.FileDialog(frame, "Choose the folder that contains WoW.exe")
            dialog.mode = java.awt.FileDialog.LOAD
            dialog.file = "WoW.exe"
            dialog.isVisible = true // blocks this worker thread until dismissed
            val dir = dialog.directory
            if (dir != null && File(dir, "WoW.exe").isFile) {
                onChoose(File(dir))
            }
            choosing = false
        }.apply { isDaemon = true }.start()
    }) { Text(if (choosing) "Choosing…" else "Choose game folder…") }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // gmLevel 3 = realm administrator
private fun AccountCard(
    model: DesktopAppModel,
    worldReady: Boolean,
    status: String?,
    onStatus: (String?) -> Unit,
    storedAccount: UserAccountStore.UserAccount?,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gmAccount by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Local account", style = MaterialTheme.typography.titleMedium)
                if (storedAccount != null) {
                    OutlinedButton(onClick = {
                        model.clearAccount()
                        onStatus("Saved account removed")
                    }) { Text("Clear") }
                }
            }
            if (storedAccount != null) {
                Text("Saved: ${storedAccount.username}", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(16).filter { ch -> ch.isLetterOrDigit() } },
                label = { Text("Account name") },
                supportingText = { Text("1–16 letters or numbers") },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(16).filter { ch -> ch.isLetterOrDigit() } },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("1–16 letters or numbers, stored uppercase — any case works when you log in") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = gmAccount, onCheckedChange = { gmAccount = it })
                Column {
                    Text(if (gmAccount) "Administrator" else "Player", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (gmAccount) {
                            "Administrator accounts can use privileged realm commands; " +
                                "choose this only for local maintenance."
                        } else {
                            "Player accounts have normal gameplay permissions and are the recommended choice."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                enabled = worldReady && !pending &&
                    UserAccountStore.isValidCredential(username) &&
                    UserAccountStore.isValidCredential(password),
                onClick = {
                    pending = true
                    onStatus("Creating through the core control channel…")
                    scope.launch {
                        val result = model.createAccount(username, password, if (gmAccount) 3 else 0)
                        onStatus(
                            when {
                                result.ok && result.code == "ACCOUNT_CREATED" ->
                                    "Account ${result.accountId} created; auto-login saved"
                                result.ok -> "Existing account verified; auto-login saved"
                                else -> "Account not provisioned: ${result.code} (${result.detail})"
                            },
                        )
                        pending = false
                    }
                },
            ) { Text(if (pending) "Creating…" else "Create & remember") }
            Text(
                status ?: if (worldReady) {
                    "Create a local account after the world is ready"
                } else {
                    "Start the realm before creating a new local account."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
