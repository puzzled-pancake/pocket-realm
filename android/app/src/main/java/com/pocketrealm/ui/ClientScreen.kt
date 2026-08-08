package com.pocketrealm.ui

import android.content.Intent
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ClientManifest
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.ClientState
import com.pocketrealm.client.DeviceCaps
import com.pocketrealm.client.LaunchRequest
import com.pocketrealm.client.PrefixRequest
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.importer.ImportWorkerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/** O06 user-facing, redistributable self-test surface. */
@Composable
fun ClientScreen(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val runtime = remember { X86DirectWineRuntime(context) }
    var host by remember { mutableStateOf<ClientDisplayHost?>(null) }
    var sessionId by remember { mutableStateOf<UUID?>(null) }
    var state by remember { mutableStateOf<ClientState?>(null) }
    var detail by remember { mutableStateOf("Ready to run the legal O06 self-test") }
    var busy by remember { mutableStateOf(false) }
    var observer by remember { mutableStateOf<Job?>(null) }
    var importStatus by remember { mutableStateOf("No managed client imported") }
    var persistedTree by remember {
        mutableStateOf(context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission }?.uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                persistedTree = uri
                ImportWorkerService.start(context, uri)
                importStatus = "Import started in isolated worker"
            }.onFailure { importStatus = "Import start failed: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching { ImportWorkerService.readStatus(context) }.onSuccess { value ->
                val error = value.optString("lastError").takeIf { it.isNotBlank() && it != "null" }
                val checkpoint = value.optString("lastRelativePath")
                    .takeIf { it.isNotBlank() && it != "null" }
                importStatus = "${value.getString("phase")} • ${value.getInt("filesProcessed")}/" +
                    "${value.getInt("filesTotal")} files" +
                    (checkpoint?.let { " • $it" } ?: "") + (error?.let { " • $it" } ?: "")
            }
            delay(1_000)
        }
    }

    fun start() {
        if (busy || sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED)) return
        busy = true; detail = "Probing Wine runtime…"
        scope.launch {
            try {
                val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
                val caps = runtime.probe(
                    DeviceCaps(Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), Build.VERSION.SDK_INT, pageSize),
                    ClientManifest(ClientRuntimeContract.SELF_TEST_ID),
                )
                check(caps.supported) { caps.reason }
                detail = "Preparing isolated prefix…"
                val prefix = runtime.preparePrefix(PrefixRequest(ClientManifest(ClientRuntimeContract.SELF_TEST_ID)))
                var pendingWindow = false
                val display = ClientDisplayHost(context, prefix.runtimeRoot) {
                    val id = sessionId
                    if (id == null) pendingWindow = true else scope.launch { runtime.reportWindowVisible(id) }
                }
                host?.close(); host = display
                detail = "Launching self-test with audio disabled…"
                val session = runtime.launch(LaunchRequest(prefix.prefixId))
                sessionId = session.sessionId; state = session.state
                if (pendingWindow) runtime.reportWindowVisible(session.sessionId)
                observer?.cancel()
                observer = scope.launch {
                    runtime.observe(session.sessionId).collectLatest {
                        state = it.state; detail = it.detail
                        if (it.state in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED)) {
                            val d = runtime.collectDiagnostics(session.sessionId)
                            detail = "${it.detail}; window=${d.windowVisible}, audioOff=${d.audioOff}, " +
                                "keyboard=${d.keyboardSeen}, mouse=${d.mouseSeen}"
                        }
                    }
                }
                display.onResume()
            } catch (t: Throwable) {
                state = ClientState.FAILED
                detail = "${t.javaClass.simpleName}: ${t.message}"
            } finally { busy = false }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> host?.onPause()
                Lifecycle.Event.ON_RESUME -> host?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            observer?.cancel()
            host?.releaseInput()
            host?.close()
            runtime.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Direct x86 Wine", style = MaterialTheme.typography.headlineSmall)
        Text("Redistributable lifecycle self-test • WineD3D • audio off")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Managed WoW 1.12.1 client", style = MaterialTheme.typography.titleMedium)
                Text(importStatus, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("client-import-status"))
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.testTag("client-import-select"),
                ) { Text("Select client folder") }
                OutlinedButton(
                    onClick = {
                        persistedTree?.let {
                            ImportWorkerService.start(context, it)
                            importStatus = "Resuming verified import"
                        }
                    },
                    enabled = persistedTree != null,
                    modifier = Modifier.testTag("client-import-resume"),
                ) { Text("Resume import") }
                Text("The selected folder remains read-only. Pocket Realm creates a verified app-private copy.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("State: ${state?.name ?: "IDLE"}", modifier = Modifier.testTag("client-state"))
                Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("client-detail"))
            }
        }
        Box(Modifier.fillMaxWidth().height(360.dp).background(Color.Black).testTag("client-surface")) {
            host?.let { current ->
                key(current.generation) {
                    AndroidView(factory = { current.container }, modifier = Modifier.fillMaxSize())
                }
            } ?: Text("The Windows surface is created before launch", color = Color.White, modifier = Modifier.padding(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::start, enabled = !busy, modifier = Modifier.testTag("client-start")) {
                Text(if (busy) "Working…" else "Start self-test")
            }
            OutlinedButton(
                onClick = { sessionId?.let { scope.launch { runtime.requestClose(it) } } },
                enabled = sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED),
                modifier = Modifier.testTag("client-close"),
            ) { Text("Close") }
            OutlinedButton(
                onClick = { sessionId?.let { scope.launch { runtime.forceStop(it) } } },
                enabled = sessionId != null && state !in setOf(ClientState.EXITED, ClientState.FAILED, ClientState.FORCE_STOPPED),
                modifier = Modifier.testTag("client-force-stop"),
            ) { Text("Force stop") }
            OutlinedButton(
                onClick = { host?.showIme() },
                enabled = host != null,
                modifier = Modifier.testTag("client-ime-open"),
            ) { Text("Keyboard") }
        }
    }
}
