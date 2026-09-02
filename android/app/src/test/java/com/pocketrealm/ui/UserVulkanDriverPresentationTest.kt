package com.pocketrealm.ui

import com.pocketrealm.client.CommunityVulkanDrivers
import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.UserVulkanDriverImport
import com.pocketrealm.client.UserVulkanDriverValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- Community driver dialog (Phase D of the community-list plan) --------

    @Test
    fun communityRowsShowPinnedDetailLinesAndTheImportedMark() {
        val r8 = CommunityVulkanDrivers.find("community-turnip-26.0.0-r8")!!
        val r2 = CommunityVulkanDrivers.find("community-turnip-25.1.0-r2")!!
        val rows = UserVulkanDriverPresentation.communityDriverRows(
            listOf(r8, r2),
            importedLibrarySha256s = setOf(r2.librarySha256!!),
        )
        assertEquals(listOf(r8.id, r2.id), rows.map { it.id })
        assertEquals("Mesa Turnip 26.0.0 R8 (K11MCH1)", rows[0].label)
        assertTrue(rows[0].detailLine.contains("v26.0.0"))
        assertTrue(rows[0].detailLine.contains("3.3 MB"))
        assertTrue(rows[0].detailLine.contains("K11MCH1/AdrenoToolsDrivers"))
        assertTrue(rows[0].detailLine.contains("MIT"))
        assertNull(rows[0].importedMark)
        assertEquals(UserVulkanDriverPresentation.COMMUNITY_IMPORTED_MARK, rows[1].importedMark)
    }

    @Test
    fun communityDialogCopyIsExactAndCarriesTheDisclaimer() {
        assertEquals("Community drivers…", UserVulkanDriverPresentation.COMMUNITY_BUTTON_LABEL)
        assertEquals("Community Turnip builds", UserVulkanDriverPresentation.COMMUNITY_DIALOG_TITLE)
        assertTrue(
            UserVulkanDriverPresentation.COMMUNITY_DIALOG_NOTE
                .contains("Not qualified by Pocket Realm"),
        )
        assertTrue(
            UserVulkanDriverPresentation.COMMUNITY_DIALOG_NOTE
                .contains("import validation and crash guard"),
        )
        assertEquals("Close", UserVulkanDriverPresentation.COMMUNITY_DIALOG_CLOSE)
    }

    @Test
    fun importInProgressNoticeIsExactAndOnlyTransientLinesMatchTheRestoreClear() {
        assertEquals("Importing driver…", UserVulkanDriverPresentation.IMPORT_IN_PROGRESS_NOTICE)
        // The restore-clear must drop both transient in-progress lines and
        // keep every final notice.
        assertTrue(
            UserVulkanDriverPresentation.isTransientInProgressNotice(
                UserVulkanDriverPresentation.IMPORT_IN_PROGRESS_NOTICE,
            ),
        )
        assertTrue(
            UserVulkanDriverPresentation.isTransientInProgressNotice(
                "Downloading Mesa Turnip 26.0.0 R8 (K11MCH1)… 1 / 3 MB",
            ),
        )
        assertFalse(
            UserVulkanDriverPresentation.isTransientInProgressNotice(
                "Imported Mesa Turnip 26.0.0 R8.",
            ),
        )
        assertFalse(UserVulkanDriverPresentation.isTransientInProgressNotice(null))
    }

    @Test
    fun resetNoticeOnlyClaimsTheSelectionFallbackWhenItApplied() {
        assertEquals(
            "Imported drivers were reset; the selection is Auto again.",
            UserVulkanDriverPresentation.resetNotice(selectionWasUserDriver = true),
        )
        assertEquals(
            "Imported drivers were reset.",
            UserVulkanDriverPresentation.resetNotice(selectionWasUserDriver = false),
        )
    }

    @Test
    fun communityDownloadStatusIsExact() {
        assertEquals(
            "Downloading Mesa Turnip 26.0.0 R8 (K11MCH1)… 1 / 3 MB",
            UserVulkanDriverPresentation.communityDownloadStatus(
                "Mesa Turnip 26.0.0 R8 (K11MCH1)",
                bytes = 1_500_000,
                totalBytes = 3_478_359,
            ),
        )
    }

    @Test
    fun communityFailureNoticesUseTheThrowableMessageOrClass() {
        assertEquals(
            "The download failed: connection reset.",
            UserVulkanDriverPresentation.communityDownloadFailureNotice(
                IllegalStateException("connection reset"),
            ),
        )
        assertEquals(
            "The download failed: IllegalStateException.",
            UserVulkanDriverPresentation.communityDownloadFailureNotice(
                IllegalStateException(),
            ),
        )
        assertEquals(
            "Import failed: disk full",
            UserVulkanDriverPresentation.communityImportFailureNotice(
                RuntimeException("disk full"),
            ),
        )
    }
}
