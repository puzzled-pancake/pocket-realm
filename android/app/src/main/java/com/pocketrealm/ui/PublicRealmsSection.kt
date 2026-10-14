package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.discovery.nostr.PublicRealmBrowser

/**
 * The Public realms section (W6-N1): the shared relay browser's live
 * listings — or the honest empty-network copy when none are published.
 * Rows are the shared model's projection; internet join is not offered
 * from a listing (it arrives with the N3 gateway, not with discovery).
 */
@Composable
fun PublicRealmsSection(browser: PublicRealmBrowser) {
    val browserState by browser.state.collectAsState()
    val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            RealmBrowserModel.PUBLIC_REALMS_TITLE,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("realm-browser-public-title"),
        )
        // R6-2-1: the relay-IP disclosure holds while the browser is
        // connected — populated or not.
        Text(
            RealmBrowserModel.RELAY_IP_DISCLOSURE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("realm-browser-public-disclosure"),
        )
        if (browserState.isEmpty) {
            Text(
                if (browserState.browsing) {
                    RealmBrowserModel.publicRealmsEmptyCopy(
                        browserState.relaysAnsweringRead,
                        browserState.relaysOpen,
                        browserState.relaysTotal,
                    )
                } else {
                    RealmBrowserModel.PUBLIC_REALMS_IDLE_COPY
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("realm-browser-public-empty"),
            )
        } else {
            browserState.listings.map { card ->
                RealmBrowserModel.publicRealmRow(
                    RealmBrowserModel.PublicRealmListing(
                        name = card.name,
                        flavor = card.flavor,
                        build = card.build,
                        timeframeStage = card.timeframeStage,
                        motd = card.motd,
                        rates = card.rates,
                        humans = card.humans,
                        bots = card.bots,
                        publishedAtSeconds = card.publishedAtSeconds,
                        statusPageUrl = card.statusPageUrl,
                        relaysSeen = card.relaysSeen,
                    ),
                    nowSeconds = nowSeconds,
                )
            }.forEach { row -> PublicRealmListingRow(row) }
        }
    }
}

/** One published realm (bots labeled, never padded). */
@Composable
private fun PublicRealmListingRow(row: RealmBrowserModel.Row.PublicRealms.PublicRealm) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("realm-browser-public-listing"),
        )
        Text(row.detail, style = MaterialTheme.typography.bodySmall)
        Text(row.playersCopy, style = MaterialTheme.typography.bodySmall)
        Text(
            row.ageCopy + if (row.relaysSeen > 1) {
                " · seen on ${row.relaysSeen} relays"
            } else {
                ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.statusPageUrl?.let { url ->
            Text(
                "Status page: $url",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
