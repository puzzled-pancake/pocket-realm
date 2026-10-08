package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Realm Browser's shared presentation model (the desktop Realms route
 * and the Android LAN browser card): honest this-device/LAN/status rows
 * and the internet-discovery empty state that names the future arc
 * instead of faking a live listing.
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

    @Test
    fun internetDiscoveryEmptyStateNamesTheFutureArcWithoutFakingIt() {
        val copy = RealmBrowserModel.INTERNET_DISCOVERY_COPY
        assertTrue(copy.contains("future"))
        assertTrue(copy.contains("Relay/Nostr"))
        // The empty state must never read as a live listing capability.
        assertFalse(copy.contains("Found"))
        assertFalse(copy.contains("Scanning"))
    }

    @Test
    fun rowsCarryLanStatusAndInternetDiscoveryInThePinnedOrder() {
        val rows = RealmBrowserModel.rows(
            running = true,
            hosting = true,
            endpointAddress = "192.168.1.10",
            statusPageUrl = "http://192.168.1.10:18080/",
        )
        assertTrue(rows.first() is RealmBrowserModel.Row.ThisDevice)
        assertTrue(rows[1] is RealmBrowserModel.Row.Lan)
        val status = rows[2] as RealmBrowserModel.Row.StatusPage
        assertEquals("http://192.168.1.10:18080/", status.url)
        assertNull(status.offCopy)
        val internet = rows.last() as RealmBrowserModel.Row.InternetDiscovery
        assertEquals(RealmBrowserModel.INTERNET_DISCOVERY_COPY, internet.copy)
    }
}
