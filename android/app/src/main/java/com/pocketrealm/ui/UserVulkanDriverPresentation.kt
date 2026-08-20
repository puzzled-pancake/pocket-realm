package com.pocketrealm.ui

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

/**
 * Pure presentation rules for the user-driver picker (plan Phase D): list
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

    /** What deleting a driver must tell the user, including the reset rule. */
    fun deletionNotice(driver: UserVulkanDriver, wasSelected: Boolean): String =
        if (wasSelected) {
            "Deleted ${driver.label}. It was the active driver, so the selection " +
                "was reset to Auto."
        } else {
            "Deleted ${driver.label}."
        }

    /** The section's static explanatory copy (docs link target: Phase F). */
    const val SECTION_NOTE =
        "User drivers are Mesa Turnip builds you import yourself. They run only on " +
            "Adreno GPUs, must be built for 16 KB pages, and a driver that crashes " +
            "twice early is quarantined automatically. Changes apply on the next " +
            "realm launch."

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
