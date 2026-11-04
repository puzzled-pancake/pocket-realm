package com.pocketrealm.desktop.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.iroh.IrohGatewayManager

/**
 * The join lane's standing technical controls (moved verbatim from the old
 * browser; an Advanced-named file may name the transport): the relay-only
 * switch (default ON; OFF is the friend-join affordance) and the live
 * join's stop — without them the guards' "stop it first" copy would be a
 * dead end and the OFF posture unreachable.
 */
@Composable
internal fun JoinLaneAdvanced(model: DesktopAppModel, modifier: Modifier = Modifier) {
    val joinState by model.irohJoinState.collectAsState()
    val settings by model.settings.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = settings.irohRelayOnly,
                onCheckedChange = { model.setIrohRelayOnly(it) },
                modifier = Modifier.testTag("mp-find-relay-only"),
            )
            Text(
                "Relay-only joins (recommended): the connection goes through " +
                    "the listing's home relay. iroh may still move an established " +
                    "connection onto a direct path in transport. Off allows " +
                    "direct (hole-punched) friend joins.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("mp-find-relay-only-copy"),
            )
        }
        if (joinState is IrohGatewayManager.JoinState.Starting ||
            joinState is IrohGatewayManager.JoinState.Ready
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { model.stopIrohJoin() },
                    modifier = Modifier.testTag("mp-find-stop-join"),
                ) {
                    Text("Stop internet join")
                }
                Text(
                    when (joinState) {
                        is IrohGatewayManager.JoinState.Starting -> "Connecting…"
                        else -> "Connected (tunnel up)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The join lane's live-error lane: a post-READY join failure (the
 * gateway's loud rewrite exit, any crash) renders the actionable code +
 * detail — the stop button and 'Connected' copy must never silently
 * vanish. Rendered OUTSIDE the fold (the Find tab calls it directly): a
 * live failure is never hidden behind a collapsed fold.
 */
@Composable
internal fun JoinLaneErrorLine(joinState: IrohGatewayManager.JoinState, modifier: Modifier = Modifier) {
    if (joinState is IrohGatewayManager.JoinState.Error) {
        val failure = joinState
        Text(
            IrohGatewayManager.joinErrorLine(failure.code, failure.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.testTag("mp-join-error"),
        )
    }
}
