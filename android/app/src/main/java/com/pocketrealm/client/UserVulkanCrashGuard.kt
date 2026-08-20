package com.pocketrealm.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure crash guard for user-imported Vulkan drivers (plan Phase E): two
 * consecutive early deaths of a user-driver session quarantine the driver
 * with an exact reason; a clean exit or a session that survives the early
 * window resets the streak. SYSTEM/PACKAGED lanes are qualified and exempt
 * (the caller never invokes this for them).
 */
object UserVulkanCrashGuard {
    /** Sessions dying before this uptime count as early deaths. */
    const val EARLY_DEATH_UPTIME_MS = 10_000L

    /** Early deaths in a row before the driver is quarantined. */
    const val QUARANTINE_AFTER_EARLY_DEATHS = 2

    const val QUARANTINE_REASON = "quarantined after 2 early crashes"

    /**
     * An early death is a FAILED session that lived less than the window.
     * Forced stops (user-initiated) are never crashes.
     */
    fun isEarlyDeath(failed: Boolean, forced: Boolean, uptimeMs: Long): Boolean =
        failed && !forced && uptimeMs in 0 until EARLY_DEATH_UPTIME_MS

    /** Fold one session outcome into the driver's persisted state. */
    fun onSessionOutcome(driver: UserVulkanDriver, earlyDeath: Boolean): UserVulkanDriver {
        if (driver.quarantined) return driver
        if (!earlyDeath) return driver.copy(earlyCrashStreak = 0)
        val streak = driver.earlyCrashStreak + 1
        return if (streak >= QUARANTINE_AFTER_EARLY_DEATHS) {
            driver.copy(
                earlyCrashStreak = streak,
                quarantined = true,
                quarantineReason = QUARANTINE_REASON,
            )
        } else {
            driver.copy(earlyCrashStreak = streak)
        }
    }

    /**
     * Full session facts → outcome. A forced stop inside the early window is
     * streak-NEUTRAL (neither a death nor a clean exit): a driver that hangs
     * and gets killed by the user must not wipe an ongoing crash streak —
     * only a clean exit or surviving past the window resets it.
     */
    fun onSessionOutcome(
        driver: UserVulkanDriver,
        failed: Boolean,
        forced: Boolean,
        uptimeMs: Long,
    ): UserVulkanDriver {
        if (driver.quarantined) return driver
        if (forced && uptimeMs < EARLY_DEATH_UPTIME_MS) return driver
        return onSessionOutcome(driver, isEarlyDeath(failed, forced, uptimeMs))
    }

    /**
     * Diagnostics record for the last user-driver session: identity, the
     * emitted Vulkan environment as variable names (absolute guest paths are
     * deliberately excluded — the support bundle verifier rejects them and
     * they add nothing over the file names), and the quarantine state after
     * the outcome was folded in. Pure so the JSON shape is unit-testable.
     */
    fun sessionRecordJson(
        driver: UserVulkanDriver,
        renderer: String,
        vulkanEnvNames: List<String>,
        uptimeMs: Long,
        earlyDeath: Boolean,
    ): String = JSONObject()
        .put("schema", 1)
        .put("driverId", driver.id)
        .put("driverLabel", driver.label)
        .put("driverSha256", driver.sha256)
        .put("driverVulkanApiVersion", driver.vulkanApiVersion ?: JSONObject.NULL)
        .put("renderer", renderer)
        .put("vulkanEnv", JSONArray(vulkanEnvNames))
        .put("uptimeMs", uptimeMs)
        .put("earlyDeath", earlyDeath)
        .put("earlyCrashStreak", driver.earlyCrashStreak)
        .put("quarantined", driver.quarantined)
        .put("quarantineReason", driver.quarantineReason ?: JSONObject.NULL)
        .toString()
}
