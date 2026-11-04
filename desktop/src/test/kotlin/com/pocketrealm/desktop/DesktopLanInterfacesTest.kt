package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the desktop interface seam — the measured failure
 * shape (a Hyper-V virtual adapter whose `isVirtual` flag is false and
 * whose `eth` name sorts first) must never lead the candidate list, the
 * default-route owner must lead it, and the liveness verdict must refuse
 * filtered-out interfaces.
 */
class DesktopLanInterfacesTest {

    private fun candidate(displayName: String, name: String, address: String) =
        DesktopLanInterfaces.Candidate(displayName, name, RealmEndpoint.parseLan(address))

    /** The measured machine shape from design-lan: eth14 = "Hyper-V
     * Virtual Ethernet Adapter" (172.21.208.1, isVirtual=false) sorts
     * before wlan2 (192.168.1.217) in the SHARED scan. */
    private val machineScan = listOf(
        candidate("Hyper-V Virtual Ethernet Adapter", "eth14", "172.21.208.1"),
        candidate("MediaTek Wi-Fi 6E MT7922 Wireless LAN Card", "wlan2", "192.168.1.217"),
        candidate("Realtek Gaming 2.5GbE Family Controller", "eth0", "192.168.1.42"),
        candidate("vEthernet (Default Switch)", "eth15", "172.29.80.1"),
        candidate("WAN Miniport (IP)", "eth16", "169.254.12.34"),
    )

    @Test
    fun virtualAdaptersAreExcludedAndPhysicalOnesKept() {
        val lan = DesktopLanInterfaces(scan = { machineScan }, defaultRouteAddress = { null })
        val addresses = lan.available().map { it.address }
        assertFalse("the Hyper-V adapter must be filtered: $addresses", "172.21.208.1" in addresses)
        assertFalse("the vEthernet switch must be filtered: $addresses", "172.29.80.1" in addresses)
        assertFalse("the WAN Miniport must be filtered: $addresses", "169.254.12.34" in addresses)
        assertTrue("wlan2 must survive: $addresses", "192.168.1.217" in addresses)
        assertTrue("the physical NIC must survive: $addresses", "192.168.1.42" in addresses)
    }

    @Test
    fun theDefaultRouteOwnerLeadsTheList() {
        val lan = DesktopLanInterfaces(
            scan = { machineScan },
            defaultRouteAddress = { "192.168.1.42" },
        )
        assertEquals("192.168.1.42", lan.available().first().address)
    }

    @Test
    fun withoutARouteOwnerTheSharedNamePrefixOrderStillApplies() {
        val lan = DesktopLanInterfaces(scan = { machineScan }, defaultRouteAddress = { null })
        // With no route owner, the shared order stands: same wlan/eth prefix
        // rank, then name — eth0 (a physical NIC) leads, and the filtered-out
        // virtual eth14 no longer jumps the queue ahead of it.
        assertEquals("192.168.1.42", lan.available().first().address)
    }

    @Test
    fun aFailedRouteProbeDegradesToNoPreference() {
        val lan = DesktopLanInterfaces(
            scan = { machineScan },
            defaultRouteAddress = { error("probe failure") },
        )
        // A failed probe degrades to the shared order (no preference).
        assertEquals("192.168.1.42", lan.available().first().address)
    }

    @Test
    fun aFailedScanDegradesToAnEmptyList() {
        val lan = DesktopLanInterfaces(scan = { error("no interfaces") }, defaultRouteAddress = { null })
        assertTrue(lan.available().isEmpty())
        // With nothing scanned, nothing is a current interface.
        assertFalse(
            lan.isCurrentPrivateInterface(RealmEndpoint.parseLan("192.168.1.217")),
        )
    }

    @Test
    fun livenessRefusesFilteredOutInterfaces() {
        val lan = DesktopLanInterfaces(
            scan = { machineScan },
            defaultRouteAddress = { null },
        )
        // The virtual adapter's address is LIVE on the machine but must not
        // pass the liveness re-check — it is exactly the address no guest
        // can reach (design-lan).
        assertFalse(
            lan.isCurrentPrivateInterface(RealmEndpoint.parseLan("172.21.208.1")),
        )
        assertTrue(
            lan.isCurrentPrivateInterface(RealmEndpoint.parseLan("192.168.1.217")),
        )
    }

    @Test
    fun loopbackIsNeverACurrentLanInterface() {
        val lan = DesktopLanInterfaces(scan = { machineScan }, defaultRouteAddress = { null })
        assertFalse(lan.isCurrentPrivateInterface(RealmEndpoint.LOCAL))
    }
}
