package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test fun curatedRetroidPocket6CarriesSocAndActiveCooling() {
        val info = DeviceProfile.from(
            model = "Retroid Pocket 6",
            socHint = "kalama",
            abi = "arm64-v8a",
            api = 33,
            cores = 8,
            ramBytes = 8L * 1024 * 1024 * 1024,
        )
        assertEquals("Retroid Pocket 6", info.label)
        assertEquals("Snapdragon 8 Gen 2", info.soc)
        assertTrue(info.activelyCooled)
    }

    @Test fun unknownDevicesFallBackToBuildSocHint() {
        val info = DeviceProfile.from(
            model = "Some Other Handheld",
            socHint = "sm8550",
            abi = "arm64-v8a",
            api = 31,
            cores = 6,
            ramBytes = 4L * 1024 * 1024 * 1024,
        )
        assertEquals("Some Other Handheld", info.label)
        assertEquals("sm8550", info.soc)
        assertFalse(info.activelyCooled)
    }

    @Test fun blankSocHintBecomesUnknown() {
        val info = DeviceProfile.from("Device", "", "x86_64", 28, 4, 0L)
        assertEquals("unknown", info.soc)
        assertEquals("x86_64", info.abi)
    }

    @Test fun retroidPocket6BaselineMatchesMeasuredFirstFullImport() {
        val baseline = DeviceProfile.RETROID_POCKET_6_BASELINE
        assertEquals("Retroid Pocket 6", baseline.deviceLabel)
        assertEquals(1_120_336L, baseline.totalMs)
        assertEquals(43, baseline.mmapMaps)
        assertEquals(6, baseline.mmapThreads)
        // The recorded stage split must still add up to the recorded totals.
        assertEquals(baseline.copyMs + baseline.dataMs, baseline.totalMs)
        assertTrue("navmesh stage must dominate the data phases", baseline.mmapMs < baseline.dataMs)
        assertEquals("18m 40s", formatImportDuration(baseline.totalMs))
    }
}
