package com.pocketrealm.ui

import com.pocketrealm.discovery.nostr.RealmListingContent

/**
 * Pure presentation model for the Realm Browser surface (the desktop
 * "Realms" route and the Android LAN destination's browser card): LAN
 * realms through the shared [com.pocketrealm.ui.LanJoinProbe] seam, the
 * hosted realm-status page link, and the Public realms section fed by the
 * shared [com.pocketrealm.discovery.nostr.PublicRealmBrowser]. The Compose
 * twins render this verbatim; the copy and row typing are pinned by
 * RealmBrowserModelTest on both trees.
 */
object RealmBrowserModel {

    /**
     * The relay-IP disclosure (plan ruling 3, code-red R7-4): the BROWSE
     * path connects to the public relays and thereby discloses this
     * device's IP to them — stated wherever the browser runs, both
     * platforms, not only on the desktop publish card.
     */
    const val RELAY_IP_DISCLOSURE: String =
        "Connecting to the public relays discloses your IP address to " +
            "them (as does the iroh relay that carries internet joins), " +
            "and a player who connects to a realm directly may learn its " +
            "host's IP address."

    /**
     * The Public realms empty state (W6-N1, relay-aware per code-red
     * R6-3): the browser is LIVE and the network is simply empty — but an
     * empty list is only claimed as "empty, not broken" when at least one
     * relay actually answered; an unreachable network says so.
     */
    /** R6-4-1: emptiness is provable only from relays that ANSWERED THE
     * READ — a socket that stays open after a refused subscription never
     * makes the list provably empty. */
    fun publicRealmsEmptyCopy(relaysAnsweringRead: Int, relaysOpen: Int, relaysTotal: Int): String {
        val reach = when {
            relaysTotal <= 0 ->
                "No relays are configured, so nothing can be checked."
            relaysAnsweringRead <= 0 -> {
                val openNote = if (relaysOpen > 0) {
                    " ($relaysOpen of them hold open sockets but refused the read)"
                } else {
                    ""
                }
                "None of the $relaysTotal configured relays answered the read " +
                    "right now$openNote — this list is empty because nothing " +
                    "could be checked, not because it is provably empty."
            }
            else ->
                "hosts appear here while their realms are up and published " +
                    "(listings expire after 10 minutes, so with $relaysAnsweringRead of " +
                    "$relaysTotal relays answering the read, an empty list means empty, " +
                    "not broken)."
        }
        return "No public realms are visible right now. The relay browser is " +
            "live — $reach " +
            "Playing over the internet today still runs through a host's address " +
            "(LAN join accepts tunnel addresses); joining straight from this " +
            "list is not available yet. $RELAY_IP_DISCLOSURE"
    }

    /**
     * The empty copy for a browser that is not browsing (the screens'
     * closed path).
     */
    const val PUBLIC_REALMS_IDLE_COPY: String =
        "The public relay browser is not running right now. Open the Realms " +
            "browser to check the public relays. $RELAY_IP_DISCLOSURE"

    /** The section title the twins render verbatim. */
    const val PUBLIC_REALMS_TITLE: String = "Public realms"

    /** One browser row kind; the platforms render their own affordances. */
    sealed class Row {
        /** This device's own realm (hosting or joined), when one is up. */
        data class ThisDevice(val copy: String) : Row()

        /** A LAN/tunnel host the player can probe and join by address. */
        data class Lan(val addressHint: String) : Row()

        /** The hosted realm-status page: a live URL, or the honest off copy. */
        data class StatusPage(val url: String?, val offCopy: String?) : Row()

        /** The Public realms section: the live listings (with the
         * relay-IP disclosure always present — R6-2-1), or the honest
         * empty state when the network has none. */
        data class PublicRealms(
            val title: String,
            val emptyCopy: String?,
            val listings: List<PublicRealm>,
            val disclosureCopy: String = RELAY_IP_DISCLOSURE,
        ) : Row() {

            /** One published realm (bots labeled, never padded). */
            data class PublicRealm(
                val name: String,
                val detail: String,
                val motd: String,
                val ratesCopy: String,
                val playersCopy: String,
                val ageCopy: String,
                val statusPageUrl: String?,
                /** How many relays of the read set carried this exact
                 * event (the write-ALL/read-ALL merge evidence). */
                val relaysSeen: Int = 1,
                /** W6-N3: the honest join posture — [JoinAvailability]
                 * drives the row's action copy (never an address; the
                 * descriptor itself never renders). */
                val joinAvailability: JoinAvailability = JoinAvailability.None,
                /** The listing event id this row's join action resolves
                 * through (the renderer keys its descriptor map by THIS,
                 * never by name). */
                val joinKey: String? = null,
            )

            /** W6-N3: whether this listing is joinable from THIS device,
             * as the row must say it. */
            enum class JoinAvailability {
                /** The listing carries no descriptor: the host's gateway
                 * is down — honest "not joinable right now". */
                None,

                /** A descriptor is carried and this device's active flavor
                 * matches the listing's — the join action may show. */
                Joinable,

                /** A descriptor is carried but this device's active client
                 * is another flavor — the copy names the switch. */
                FlavorMismatch,
            }
        }
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

    /** The browsable facts of one listing (a [com.pocketrealm.discovery.
     * nostr.PublicRealmBrowser.ListingCard]'s fields, bundled so the row
     * projection takes one argument). */
    data class PublicRealmListing(
        /** The listing event's id — the join action's identity key
         * (code-red R1-3/R4-3/R6-2: never the name). */
        val eventId: String,
        val name: String,
        val flavor: String,
        val build: Int,
        val timeframeStage: String,
        val motd: String,
        val rates: RealmListingContent.ListingRates?,
        val humans: Int,
        val bots: Int,
        val publishedAtSeconds: Long,
        val statusPageUrl: String?,
        val relaysSeen: Int = 1,
        /** W6-N3: the listing's join descriptor presence (never rendered
         * itself — the availability copy is what shows). */
        val joinCarried: Boolean = false,
    )

    /**
     * Projects one browser listing into the pinned row copy. Bots are
     * labeled by construction — the players line always names them, and
     * the counts ride the frozen-schema content the store verified, never
     * a padded total.
     */
    fun publicRealmRow(
        listing: PublicRealmListing,
        nowSeconds: Long,
        activeFlavorId: String? = null,
    ): Row.PublicRealms.PublicRealm {
        val ageMinutes =
            ((nowSeconds - listing.publishedAtSeconds).coerceAtLeast(0L)) / SECONDS_PER_MINUTE
        val rates = listing.rates
        val ratesCopy = if (rates == null) {
            ""
        } else {
            " · rates " + (rates.xpKill ?: 1.0) + "x/" +
                (rates.xpQuest ?: 1.0) + "x/" + (rates.xpHonor ?: 1.0) + "x"
        }
        // W6-N3: the join availability — honest per posture, never an
        // address (the descriptor's node id/relay never render). Round-4
        // R1-r4-2 alignment: a device with NO flavor picked (the ambiguous
        // state the desktop guard refuses with the pick-first row) is NOT
        // "Joinable" here either — the flavor lane is simply not matched
        // yet, so the row shows the mismatch copy.
        val joinAvailability = when {
            !listing.joinCarried -> Row.PublicRealms.JoinAvailability.None
            activeFlavorId != null && activeFlavorId == listing.flavor ->
                Row.PublicRealms.JoinAvailability.Joinable
            else -> Row.PublicRealms.JoinAvailability.FlavorMismatch
        }
        return Row.PublicRealms.PublicRealm(
            name = listing.name,
            detail = "${listing.flavor} (build ${listing.build}) · ${listing.timeframeStage}",
            motd = listing.motd,
            ratesCopy = ratesCopy,
            playersCopy = "${listing.humans} human${if (listing.humans == 1) "" else "s"}, " +
                "${listing.bots} bot${if (listing.bots == 1) "" else "s"} (of the players online)",
            ageCopy = when {
                ageMinutes <= 0 -> "published just now"
                ageMinutes == 1L -> "published 1 minute ago"
                else -> "published $ageMinutes minutes ago"
            },
            statusPageUrl = listing.statusPageUrl,
            relaysSeen = listing.relaysSeen,
            joinAvailability = joinAvailability,
            joinKey = listing.eventId,
        )
    }

    /** W6-N3: the row's join-action copy (the renderer shows it verbatim;
     * "Join" is a VERB the caller wires, the rest is honest posture). */
    fun joinActionCopy(availability: Row.PublicRealms.JoinAvailability): String? = when (availability) {
        Row.PublicRealms.JoinAvailability.Joinable -> "Join over the internet"
        Row.PublicRealms.JoinAvailability.FlavorMismatch ->
            "Internet join: switch to this realm's flavor first"
        Row.PublicRealms.JoinAvailability.None ->
            "Internet join: the host's gateway is offline right now"
    }

    /** The relay connectivity a renderer passes in (from the browser
     * state's relaysOpen/relaysTotal). */
    /** R5-r5-2: carries the READ-answer distinction explicitly — a relay
     * whose socket is open but whose subscription was refused never
     * counts as answering. */
    data class RelayConnectivity(
        val answeringRead: Int,
        val open: Int,
        val total: Int,
    ) {
        companion object {
            val ASSUMED_LIVE = RelayConnectivity(answeringRead = 1, open = 1, total = 1)
        }
    }

    /** The this-device/LAN/status inputs bundled (R1 detekt: the rows
     * projection takes one argument per concern). */
    data class RowsInputs(
        val running: Boolean,
        val hosting: Boolean,
        val endpointAddress: String?,
        val statusPageUrl: String?,
    )

    /** The full row order the browser renders for a non-hosting client.
     * The empty-state copy is relay-aware (R6-3): [relays] comes from the
     * live browser state. */
    fun rows(
        inputs: RowsInputs,
        publicRealms: List<Row.PublicRealms.PublicRealm> = emptyList(),
        relays: RelayConnectivity = RelayConnectivity.ASSUMED_LIVE,
    ): List<Row> = buildList {
        thisDeviceCopy(inputs.running, inputs.hosting, inputs.endpointAddress)
            .takeIf { it.isNotEmpty() }
            ?.let { add(Row.ThisDevice(it)) }
        add(Row.Lan("Host address (LAN or tunnel IPv4)"))
        add(
            Row.StatusPage(
                inputs.statusPageUrl,
                statusPageOffCopy(inputs.statusPageUrl != null),
            ),
        )
        add(
            Row.PublicRealms(
                title = PUBLIC_REALMS_TITLE,
                emptyCopy = if (publicRealms.isEmpty()) {
                    publicRealmsEmptyCopy(relays.answeringRead, relays.open, relays.total)
                } else {
                    null
                },
                listings = publicRealms,
                disclosureCopy = RELAY_IP_DISCLOSURE,
            ),
        )
    }

    private const val SECONDS_PER_MINUTE = 60L
}
