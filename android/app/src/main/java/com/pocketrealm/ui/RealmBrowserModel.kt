package com.pocketrealm.ui

/**
 * Pure presentation model for the Realm Browser surface (the desktop
 * "Realms" route and the Android LAN destination's browser card): LAN
 * realms through the shared [com.pocketrealm.ui.LanJoinProbe] seam, the
 * hosted realm-status page link, and the honest internet-discovery empty
 * state. The Compose twins render this verbatim; the copy and row typing
 * are pinned by RealmBrowserModelTest on both trees.
 */
object RealmBrowserModel {

    /** The internet-discovery empty state: a future arc, never faked. */
    const val INTERNET_DISCOVERY_COPY: String =
        "No internet realm discovery yet. Relay/Nostr discovery is a future " +
            "arc — nothing scans, lists, or announces internet realms today. " +
            "Play over the internet today by exchanging a host's address " +
            "directly (LAN join accepts tunnel addresses)."

    /** One browser row kind; the platforms render their own affordances. */
    sealed class Row {
        /** This device's own realm (hosting or joined), when one is up. */
        data class ThisDevice(val copy: String) : Row()

        /** A LAN/tunnel host the player can probe and join by address. */
        data class Lan(val addressHint: String) : Row()

        /** The hosted realm-status page: a live URL, or the honest off copy. */
        data class StatusPage(val url: String?, val offCopy: String?) : Row()

        /** The honest internet-discovery empty state (future arc). */
        data class InternetDiscovery(val copy: String) : Row()
    }

    /** This-device row copy from the live runtime mode ("", when idle). */
    fun thisDeviceCopy(running: Boolean, hosting: Boolean, endpointAddress: String?): String = when {
        running && hosting -> "This realm — hosting on $endpointAddress (ports 3724 / 8085)"
        running -> "This device — joined to $endpointAddress as a client"
        else -> ""
    }

    /** The realm-status page URL for a bound page server, else null. */
    fun statusPageUrl(visibilityOn: Boolean, lanAddress: String?, pagePort: Int): String? =
        if (visibilityOn && !lanAddress.isNullOrBlank()) {
            "http://$lanAddress:$pagePort/"
        } else {
            null
        }

    /** The honest off-copy when the hosted page is not serving. */
    fun statusPageOffCopy(visibilityOn: Boolean): String? =
        if (visibilityOn) null
        else "The hosted realm-status page is off. Hosts can turn it on in the " +
            "realm-status settings; the browser links it here once it serves."

    /** The full row order the browser renders for a non-hosting client. */
    fun rows(
        running: Boolean,
        hosting: Boolean,
        endpointAddress: String?,
        statusPageUrl: String?,
    ): List<Row> = buildList {
        thisDeviceCopy(running, hosting, endpointAddress)
            .takeIf { it.isNotEmpty() }
            ?.let { add(Row.ThisDevice(it)) }
        add(Row.Lan("Host address (LAN or tunnel IPv4)"))
        add(Row.StatusPage(statusPageUrl, statusPageOffCopy(statusPageUrl != null)))
        add(Row.InternetDiscovery(INTERNET_DISCOVERY_COPY))
    }
}
