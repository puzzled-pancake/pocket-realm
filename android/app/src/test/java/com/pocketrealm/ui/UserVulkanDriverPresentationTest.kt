package com.pocketrealm.ui

import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.UserVulkanDriverImport
import com.pocketrealm.client.UserVulkanDriverValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase D: pure presentation rules behind the user-driver picker. */
class UserVulkanDriverPresentationTest {

    private fun driver(
        id: String = "user-turnip-26-3",
        label: String = "Turnip 26.3",
        version: String? = "1.3.290",
        addedAt: Long = 1_800_000_000_000L,
        quarantined: Boolean = false,
        reason: String? = null,
    ) = UserVulkanDriver(
        id = id, label = label, libraryFileName = "driver.so", sha256 = "a".repeat(64),
        vulkanApiVersion = version, addedAt = addedAt,
        quarantined = quarantined, quarantineReason = reason,
    )

    @Test
    fun rowsSortNewestFirstThenByIdAndCarrySelection() {
        val drivers = listOf(
            driver(id = "user-a", addedAt = 100L),
            driver(id = "user-c", addedAt = 300L),
            driver(id = "user-b", addedAt = 300L),
        )
        val rows = UserVulkanDriverPresentation.rows(drivers, "user-a", adrenoGpu = true)
        assertEquals(listOf("user-b", "user-c", "user-a"), rows.map { it.id })
        assertEquals(listOf(false, false, true), rows.map { it.selected })
    }

    @Test
    fun labelsShowTheParsedVulkanVersionOrItsAbsence() {
        val rows = UserVulkanDriverPresentation.rows(
            listOf(driver(version = "1.3.290"), driver(id = "user-bare", version = null)),
            "none", adrenoGpu = true,
        )
        assertTrue(rows.any { it.label == "Turnip 26.3 (Vulkan 1.3.290)" })
        assertTrue(rows.any { it.label.endsWith("(Vulkan version unknown)") })
    }

    @Test
    fun quarantinedRowsAreDisabledWithTheExactQuarantineString() {
        val rows = UserVulkanDriverPresentation.rows(
            listOf(driver(id = "user-bad", quarantined = true, reason = "quarantined after 2 early crashes")),
            "user-bad", adrenoGpu = true,
        )
        assertFalse(rows.single().enabled)
        assertEquals(
            "Imported driver Turnip 26.3 is quarantined: quarantined after 2 early crashes",
            rows.single().statusLine,
        )
    }

    @Test
    fun nonAdrenoDevicesGetTheAdrenoOnlyReasonOnEveryUserRow() {
        val rows = UserVulkanDriverPresentation.rows(
            listOf(driver()), "none", adrenoGpu = false,
        )
        assertFalse(rows.single().enabled)
        assertTrue(rows.single().statusLine.contains("Adreno-only"))
        assertTrue(rows.single().statusLine.contains("system Vortek bridge"))
    }

    @Test
    fun vulkanBelow13SurfacesTheDxvkWarningBelow13Only() {
        val old = UserVulkanDriverPresentation.rows(
            listOf(driver(id = "user-old", version = "1.1.262")), "none", adrenoGpu = true,
        ).single()
        assertTrue(old.enabled) // warn-only, never disabled
        assertTrue(old.statusLine.contains("1.1.262"))
        assertTrue(old.statusLine.contains("DXVK 2.4.1"))
        val modern = UserVulkanDriverPresentation.rows(
            listOf(driver(id = "user-new", version = "1.4.318")), "none", adrenoGpu = true,
        ).single()
        assertTrue(modern.statusLine.contains("16 KB page alignment"))
    }

    @Test
    fun importNoticesPassValidatorOutputThroughVerbatim() {
        // The presentation contract is verbatim pass-through: a rejection
        // produced by the real validator must surface byte-identical.
        val realRejection = UserVulkanDriverValidator.elfRejection(ByteArray(32))!!
        assertEquals(
            realRejection,
            UserVulkanDriverPresentation.importResultNotice(
                UserVulkanDriverImport.Rejected(realRejection),
            ),
        )
        assertEquals(
            "Imported Turnip 26.3 (Vulkan 1.3.290).",
            UserVulkanDriverPresentation.importResultNotice(
                UserVulkanDriverImport.Imported(driver(), warning = null),
            ),
        )
        val realWarning = UserVulkanDriverValidator.apiVersionWarning("1.1.262")!!
        assertEquals(
            "Imported Turnip 26.3 (Vulkan 1.1.262). $realWarning",
            UserVulkanDriverPresentation.importResultNotice(
                UserVulkanDriverImport.Imported(
                    driver(version = "1.1.262"),
                    warning = realWarning,
                ),
            ),
        )
    }

    @Test
    fun deletingTheActiveDriverExplainsTheResetToAuto() {
        assertEquals(
            "Deleted Turnip 26.3. It was the active driver, so the selection was reset to Auto.",
            UserVulkanDriverPresentation.deletionNotice(driver(), wasSelected = true),
        )
        assertEquals(
            "Deleted Turnip 26.3.",
            UserVulkanDriverPresentation.deletionNotice(driver(), wasSelected = false),
        )
    }

    @Test
    fun importedLineDistinguishesTodayFromOlderDates() {
        assertEquals(
            "Imported today",
            UserVulkanDriverPresentation.importedLine(1_000L, nowMs = 2_000L),
        )
        assertTrue(
            UserVulkanDriverPresentation.importedLine(0L, nowMs = 1_800_000_000_000L)
                .matches(Regex("Imported \\d{4}-\\d{2}-\\d{2}")),
        )
    }
}
