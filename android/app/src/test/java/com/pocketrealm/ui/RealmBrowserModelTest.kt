package com.pocketrealm.ui

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Realm Browser's shared presentation model (the desktop Realms route
 * and the Android LAN browser card): honest this-device/LAN/status rows
 * and the Public realms section — W6-N1 replaced the old "future arc"
 * empty copy with the real relay browser's copy: an honest empty network
 * until hosts publish, listing rows once they do, and an honest
 * "joining is not available yet" until the N3 gateway lands.
 */
class RealmBrowserModelTest {

    @Test
    fun thisDeviceCopyIsModeHonest() {
        assertEquals(
            "This realm — hosting on 192.168.1.10 (ports 3724 / 8085)",
            RealmBrowserModel.thisDeviceCopy(
                running = true, hosting = true, endpointAddress = "192.168.1.10",
            ),
        )
        assertEquals(
            "This device — joined to 192.168.1.10 as a client",
            RealmBrowserModel.thisDeviceCopy(
                running = true, hosting = false, endpointAddress = "192.168.1.10",
            ),
        )
        assertEquals(
            "",
            RealmBrowserModel.thisDeviceCopy(
                running = false, hosting = false, endpointAddress = null,
            ),
        )
    }

    @Test
    fun statusPageUrlServesOnlyWhileThePageIsOnAndAddressed() {
        assertEquals(
            "http://192.168.1.10:18080/",
            RealmBrowserModel.statusPageUrl(
                visibilityOn = true, lanAddress = "192.168.1.10", pagePort = 18080,
            ),
        )
        assertNull(
            RealmBrowserModel.statusPageUrl(
                visibilityOn = false, lanAddress = "192.168.1.10", pagePort = 18080,
            ),
        )
        assertNull(
            RealmBrowserModel.statusPageUrl(
                visibilityOn = true, lanAddress = null, pagePort = 18080,
            ),
        )
    }

    @Test
    fun statusPageOffCopyIsHonestAboutTheSwitch() {
        val off = RealmBrowserModel.statusPageOffCopy(visibilityOn = false)
        assertTrue(off.orEmpty().contains("is off"))
        assertNull(RealmBrowserModel.statusPageOffCopy(visibilityOn = true))
    }

    // ---------------------------------------------------- public realms

    @Test
    fun publicRealmsEmptyStateIsAnHonestEmptyNetwork() {
        val copy = RealmBrowserModel.publicRealmsEmptyCopy(relaysAnsweringRead = 2, relaysOpen = 2, relaysTotal = 4)
        // The browser is live and the network is simply empty...
        assertTrue(copy.contains("browser is live"))
        assertTrue(copy.contains("empty list means empty"))
        assertTrue(copy.contains("2 of 4 relays answering"))
        // ...never a fake capability, never a promise of scanning.
        assertFalse(copy.contains("Scanning"))
        assertFalse(copy.contains("Found 0"))
        // Internet join is honestly not available until N3.
        assertTrue(copy.contains("not available yet"))
    }

    /** R6-3: an unreachable network is NEVER claimed as provably empty. */
    @Test
    fun allRelaysFailedIsEmptyBecauseNothingCouldBeChecked() {
        val copy = RealmBrowserModel.publicRealmsEmptyCopy(relaysAnsweringRead = 0, relaysOpen = 0, relaysTotal = 4)
        assertTrue(copy.contains("None of the 4 configured relays answered the read"))
        assertTrue(copy.contains("not because it is provably empty"))
        assertFalse(copy.contains("not broken"))
        // The relay-IP disclosure rides every variant (R7-4).
        assertTrue(copy.contains(RealmBrowserModel.RELAY_IP_DISCLOSURE))
    }

    /** R6-4-1: a relay whose subscription was CLOSED (read refused) does
     * not make the list provably empty even though its socket is OPEN. */
    @Test
    fun readRefusedRelayNeverClaimsProvableEmptiness() {
        // socket OPEN but the read refused: 0 answering of 4.
        val copy = RealmBrowserModel.publicRealmsEmptyCopy(
            relaysAnsweringRead = 0, relaysOpen = 4, relaysTotal = 4,
        )
        assertTrue(copy.contains("answered the read"))
        assertFalse(copy.contains("not broken"))
        // contrast: an actually-answering relay makes emptiness provable
        val honest = RealmBrowserModel.publicRealmsEmptyCopy(
            relaysAnsweringRead = 1, relaysOpen = 4, relaysTotal = 4,
        )
        assertTrue(honest.contains("not broken"))
    }

    @Test
    fun noRelaysConfiguredNamesIt() {
        val copy = RealmBrowserModel.publicRealmsEmptyCopy(relaysAnsweringRead = 0, relaysOpen = 0, relaysTotal = 0)
        assertTrue(copy.contains("No relays are configured"))
    }

    /** R7-4: the relay-IP disclosure is part of the shared browse copy
     * both platforms render. */
    @Test
    fun browseCopyCarriesTheRelayIpDisclosure() {
        assertTrue(RealmBrowserModel.RELAY_IP_DISCLOSURE.contains("discloses your IP address"))
        for (open in listOf(0, 1, 4)) {
            assertTrue(
                RealmBrowserModel.publicRealmsEmptyCopy(relaysAnsweringRead = open, relaysOpen = open, relaysTotal = 4)
                    .contains(RealmBrowserModel.RELAY_IP_DISCLOSURE),
            )
        }
    }

    @Test
    fun idleCopyNamesTheStoppedBrowserWithTheDisclosure() {
        assertTrue(RealmBrowserModel.PUBLIC_REALMS_IDLE_COPY.contains("not running right now"))
        assertTrue(RealmBrowserModel.PUBLIC_REALMS_IDLE_COPY.contains(RealmBrowserModel.RELAY_IP_DISCLOSURE))
    }

    @Test
    fun publicRealmRowLabelsBotsAndNamesTheAge() {
        val row = RealmBrowserModel.publicRealmRow(
            RealmBrowserModel.PublicRealmListing(
                name = "Northdale",
                flavor = "wotlk",
                build = ClientFlavors.WOTLK.build,
                timeframeStage = "progression-wotlk-1x",
                motd = "Casual quests and dungeons, no rush.",
                rates = com.pocketrealm.discovery.nostr.RealmListingContent.ListingRates(1.0, 1.0, 1.0),
                humans = 3,
                bots = 12,
                publishedAtSeconds = 1_760_000_000L,
                statusPageUrl = "https://quiet-meadow-abc123.trycloudflare.com",
            ),
            nowSeconds = 1_760_000_000L + 120,
        )
        assertEquals("Northdale", row.name)
        assertEquals(
            "wotlk (build " + ClientFlavors.WOTLK.build + ") · progression-wotlk-1x",
            row.detail,
        )
        // Bots are LABELED by construction — the players line always
        // names them, never a padded total.
        assertTrue(row.playersCopy.contains("3 humans"))
        assertTrue(row.playersCopy.contains("12 bots"))
        assertTrue(row.playersCopy.contains("bot"))
        assertEquals("published 2 minutes ago", row.ageCopy)
        assertEquals("https://quiet-meadow-abc123.trycloudflare.com", row.statusPageUrl)
    }

    @Test
    fun publicRealmRowAgeCopyNeverGoesNegative() {
        val row = RealmBrowserModel.publicRealmRow(
            RealmBrowserModel.PublicRealmListing(
                name = "N", flavor = "vanilla",
                build = ClientFlavors.VANILLA.build, timeframeStage = "s",
                motd = "", rates = null,
                humans = 0, bots = 0,
                publishedAtSeconds = 1_760_000_100L,
                statusPageUrl = null,
            ),
            nowSeconds = 1_760_000_000L, // a skewed clock must not lie
        )
        assertEquals("published just now", row.ageCopy)
    }

    /** R6-2-1: the relay-IP disclosure holds while listings are visible —
     * the populated section carries it, not just the empty states. */
    @Test
    fun populatedPublicRealmsRowCarriesTheRelayIpDisclosure() {
        val row = RealmBrowserModel.publicRealmRow(
            RealmBrowserModel.PublicRealmListing(
                name = "Northdale", flavor = "wotlk",
                build = ClientFlavors.WOTLK.build,
                timeframeStage = "progression-wotlk-1x", humans = 3, bots = 12,
                motd = "Casual quests and dungeons, no rush.",
                rates = com.pocketrealm.discovery.nostr.RealmListingContent.ListingRates(1.0, 1.0, 1.0),
                publishedAtSeconds = 1_760_000_000L, statusPageUrl = null,
            ),
            nowSeconds = 1_760_000_060L,
        )
        val rows = RealmBrowserModel.rows(
            RealmBrowserModel.RowsInputs(
                running = true, hosting = true,
                endpointAddress = "192.168.1.10",
                statusPageUrl = "http://192.168.1.10:18080/",
            ),
            publicRealms = listOf(row),
            relays = RealmBrowserModel.RelayConnectivity(answeringRead = 1, open = 1, total = 1),
        )
        val public = rows.last() as RealmBrowserModel.Row.PublicRealms
        assertTrue(public.listings.isNotEmpty())
        assertEquals(
            RealmBrowserModel.RELAY_IP_DISCLOSURE,
            public.disclosureCopy,
        )
    }

    @Test
    fun rowsCarryLanStatusAndPublicRealmsInThePinnedOrder() {
        val row = RealmBrowserModel.publicRealmRow(
            RealmBrowserModel.PublicRealmListing(
                name = "Northdale", flavor = "wotlk",
                build = ClientFlavors.WOTLK.build,
                timeframeStage = "progression-wotlk-1x", humans = 3, bots = 12,
                motd = "Casual quests and dungeons, no rush.",
                rates = com.pocketrealm.discovery.nostr.RealmListingContent.ListingRates(1.0, 1.0, 1.0),
                publishedAtSeconds = 1_760_000_000L, statusPageUrl = null,
            ),
            nowSeconds = 1_760_000_060L,
        )
        val rows = RealmBrowserModel.rows(
            RealmBrowserModel.RowsInputs(
                running = true,
                hosting = true,
                endpointAddress = "192.168.1.10",
                statusPageUrl = "http://192.168.1.10:18080/",
            ),
            publicRealms = listOf(row),
            relays = RealmBrowserModel.RelayConnectivity(answeringRead = 1, open = 1, total = 1),
        )
        assertTrue(rows.first() is RealmBrowserModel.Row.ThisDevice)
        assertTrue(rows[1] is RealmBrowserModel.Row.Lan)
        val status = rows[2] as RealmBrowserModel.Row.StatusPage
        assertEquals("http://192.168.1.10:18080/", status.url)
        assertNull(status.offCopy)
        val public = rows.last() as RealmBrowserModel.Row.PublicRealms
        assertEquals(RealmBrowserModel.PUBLIC_REALMS_TITLE, public.title)
        assertNull(public.emptyCopy) // listings exist → no empty copy
        assertEquals(1, public.listings.size)
        assertEquals("Northdale", public.listings[0].name)
    }

    @Test
    fun rowsWithNoListingsCarryTheHonestEmptyCopy() {
        val rows = RealmBrowserModel.rows(
            RealmBrowserModel.RowsInputs(
                running = false,
                hosting = false,
                endpointAddress = null,
                statusPageUrl = null,
            ),
            relays = RealmBrowserModel.RelayConnectivity(
                answeringRead = 0, open = 2, total = 4,
            ),
        )
        val public = rows.last() as RealmBrowserModel.Row.PublicRealms
        // R5-r5-2: open sockets WITHOUT an answered read never make the
        // list provably empty — the copy must name the refused read.
        assertEquals(
            RealmBrowserModel.publicRealmsEmptyCopy(
                relaysAnsweringRead = 0, relaysOpen = 2, relaysTotal = 4,
            ),
            public.emptyCopy,
        )
        assertFalse(public.emptyCopy.orEmpty().contains("not broken"))
        assertTrue(public.listings.isEmpty())
    }
}
