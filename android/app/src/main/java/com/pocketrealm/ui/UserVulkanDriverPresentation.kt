package com.pocketrealm.ui

import com.pocketrealm.client.CommunityVulkanDriver
import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.UserVulkanDriverImport
import com.pocketrealm.client.UserVulkanDriverResolution
import com.pocketrealm.client.UserVulkanDriverValidator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One Settings picker row for a user-imported Vulkan driver. */
internal data class UserVulkanDriverRow(
    val id: String,
    val label: String,
    val importedLine: String,
    val selected: Boolean,
    val enabled: Boolean,
    val statusLine: String,
)

/** One Settings dialog row for a curated community driver download. */
internal data class CommunityDriverRow(
    val id: String,
    val label: String,
    val detailLine: String,
    val importedMark: String?,
)

/**
 * Pure presentation rules for the user-driver picker: list
 * ordering, label formatting, the exact validator/quarantine/Adreno status
 * strings, and import/deletion notices. Compose stays a thin shell around
 * this; every string here is assertable in JVM tests.
 */
internal object UserVulkanDriverPresentation {

    fun rows(
        drivers: List<UserVulkanDriver>,
        selectedVulkanDriverId: String,
        adrenoGpu: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): List<UserVulkanDriverRow> = drivers
        .sortedWith(compareByDescending<UserVulkanDriver> { it.addedAt }.thenBy { it.id })
        .map { driver ->
            UserVulkanDriverRow(
                id = driver.id,
                label = driver.vulkanApiVersion?.let { "${driver.label} (Vulkan $it)" }
                    ?: "${driver.label} (Vulkan version unknown)",
                importedLine = importedLine(driver.addedAt, nowMs),
                selected = driver.id == selectedVulkanDriverId,
                enabled = adrenoGpu && !driver.quarantined,
                statusLine = when {
                    driver.quarantined ->
                        UserVulkanDriverResolution.quarantinedDriverReason(driver)
                    !adrenoGpu -> UserVulkanDriverResolution.ADRENO_ONLY_REASON
                    meetsVulkan13(driver) ->
                        "Validated at import: aarch64 ELF, 16 KB page alignment."
                    else -> apiVersionWarning(driver)
                },
            )
        }

    /** Import outcome as one honest line — the exact rejection or the success + warning. */
    fun importResultNotice(result: UserVulkanDriverImport): String = when (result) {
        is UserVulkanDriverImport.Rejected -> result.reason
        is UserVulkanDriverImport.Imported -> {
            val base = "Imported ${result.driver.label}" +
                result.driver.vulkanApiVersion?.let { " (Vulkan $it)" }.orEmpty() + "."
            result.warning?.let { "$base $it" } ?: base
        }
    }

    /**
     * Transient status while the SAF import copies. Deliberately not part
     * of the restored state: after a process death mid-import no import is
     * running, so the line is cleared on first composition.
     */
    const val IMPORT_IN_PROGRESS_NOTICE = "Importing driver…"

    /** What deleting a driver must tell the user, including the reset rule. */
    fun deletionNotice(driver: UserVulkanDriver, wasSelected: Boolean): String =
        if (wasSelected) {
            "Deleted ${driver.label}. It was the active driver, so the selection " +
                "was reset to Auto."
        } else {
            "Deleted ${driver.label}."
        }

    /** What a lane reset must tell the user — the selection rule only when it applied. */
    fun resetNotice(selectionWasUserDriver: Boolean): String =
        if (selectionWasUserDriver) {
            "Imported drivers were reset; the selection is Auto again."
        } else {
            "Imported drivers were reset."
        }

    /**
     * True for the transient in-progress lines (import copy, community
     * download): they must not survive process death, so the restore path
     * clears them when nothing is actually running.
     */
    fun isTransientInProgressNotice(status: String?): Boolean =
        status == IMPORT_IN_PROGRESS_NOTICE || status?.startsWith("Downloading ") == true

    /** The section's static explanatory copy. */
    const val SECTION_NOTE =
        "User drivers are Mesa Turnip builds you import yourself. They run only on " +
            "Adreno GPUs, must be built for 16 KB pages, and a driver that crashes " +
            "twice early is quarantined automatically. Changes apply on the next " +
            "realm launch. The project wiki page \"Choosing a Vulkan Driver\" " +
            "(docs/wiki/) explains where builds come from and the exact import rules."

    // --- Curated community downloads -----------------------------------------

    /** Rows for the "Community drivers…" dialog, newest-manifest-first order. */
    fun communityDriverRows(
        drivers: List<CommunityVulkanDriver>,
        importedLibrarySha256s: Set<String>,
    ): List<CommunityDriverRow> = drivers.map { driver ->
        CommunityDriverRow(
            id = driver.id,
            label = driver.label,
            detailLine = communityDetailLine(driver),
            importedMark = driver.librarySha256
                ?.takeIf { it in importedLibrarySha256s }
                ?.let { COMMUNITY_IMPORTED_MARK },
        )
    }

    private fun communityDetailLine(driver: CommunityVulkanDriver): String =
        "v${driver.version} · " +
            String.format(Locale.ROOT, "%.1f", driver.size / (1024f * 1024f)) +
            " MB · ${driver.repo} · ${driver.license}"

    /** Progress line while a pinned download streams (updated per chunk). */
    fun communityDownloadStatus(label: String, bytes: Long, totalBytes: Long): String =
        "Downloading $label… ${bytes / (1024L * 1024L)} / ${totalBytes / (1024L * 1024L)} MB"

    /** A pinned download that failed at the network/IO layer (exact format). */
    fun communityDownloadFailureNotice(failure: Throwable): String =
        "The download failed: ${failure.message ?: failure.javaClass.simpleName}."

    /** An import that failed after a verified download (exact format). */
    fun communityImportFailureNotice(failure: Throwable): String =
        "Import failed: ${failure.message ?: failure.javaClass.simpleName}"

    const val COMMUNITY_BUTTON_LABEL = "Community drivers…"

    const val COMMUNITY_DIALOG_TITLE = "Community Turnip builds"

    const val COMMUNITY_DIALOG_NOTE =
        "Digest-pinned downloads of community Mesa Turnip builds (MIT). Not " +
            "qualified by Pocket Realm — the same import validation and crash " +
            "guard apply as for any imported driver."

    const val COMMUNITY_IMPORTED_MARK = "Imported"

    const val COMMUNITY_DIALOG_CLOSE = "Close"

    private fun meetsVulkan13(driver: UserVulkanDriver): Boolean =
        driver.vulkanApiVersion?.let { UserVulkanDriverValidator.apiVersionWarning(it) == null }
            ?: true

    private fun apiVersionWarning(driver: UserVulkanDriver): String =
        requireNotNull(UserVulkanDriverValidator.apiVersionWarning(
            requireNotNull(driver.vulkanApiVersion),
        ))

    internal fun importedLine(addedAt: Long, nowMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val today = format.format(Date(nowMs))
        return if (format.format(Date(addedAt)) == today) {
            "Imported today"
        } else {
            "Imported ${format.format(Date(addedAt))}"
        }
    }
}
