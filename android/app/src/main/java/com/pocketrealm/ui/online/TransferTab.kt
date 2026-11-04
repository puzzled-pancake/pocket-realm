package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.ui.CharacterTransferControls
import com.pocketrealm.ui.RealmDataControls

/**
 * The Multiplayer › Transfer tab: today's two cards, unchanged in
 * behaviour (copy trimmed to plain language).
 */
@Composable
internal fun TransferTab(realmRunning: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth().testTag("lan-transfer-character")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Move a character to another realm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                CharacterTransferControls(realmRunning = realmRunning)
            }
        }
        Card(Modifier.fillMaxWidth().testTag("lan-transfer-realm")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Move your realm to another device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Back up this realm — accounts, characters and world state — into one " +
                        "file, then import it on your other device. Importing replaces that " +
                        "device's realm, so back that one up first if it has progress you want.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RealmDataControls()
            }
        }
    }
}
