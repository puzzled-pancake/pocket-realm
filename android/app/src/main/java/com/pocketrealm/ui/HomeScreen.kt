package com.pocketrealm.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.R
import com.pocketrealm.realm.RealmState
import com.pocketrealm.service.RealmBridge
import com.pocketrealm.service.RealmService

/**
 * The one-screen-with-one-primary-action Home. Status reflects the real
 * supervisor state; the primary button is Start/Continue/Save&Exit per state.
 * All gameplay-critical flows remain controller-accessible (O14).
 */
@Composable
fun HomeScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val state by RealmBridge.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pocket Realm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        StatusCard(state)

        Spacer(Modifier.height(8.dp))

        when (state) {
            is RealmState.Idle, is RealmState.Failed -> {
                Button(onClick = { RealmService.start(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.action_start))
                }
            }
            is RealmState.Running -> {
                OutlinedButton(onClick = { RealmService.saveExit(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.action_save_exit))
                }
            }
            is RealmState.Starting, is RealmState.Saving, is RealmState.Stopping, is RealmState.Recovering -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Working…")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: RealmState) {
    val (statusText, detailText) = when (state) {
        is RealmState.Idle -> "Idle" to "Realm not started."
        is RealmState.Starting -> "Starting" to "Bringing the realm up (attempt ${state.attempt})…"
        is RealmState.Running -> "Running" to "Realm is live. Tap to enter the world."
        is RealmState.Saving -> "Saving" to "Draining durable writes (${state.reason.name.lowercase()})…"
        is RealmState.Stopping -> "Stopping" to "Tearing down."
        is RealmState.Recovering -> "Recovering" to state.note
        is RealmState.Failed -> "Failed" to state.message
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detailText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
