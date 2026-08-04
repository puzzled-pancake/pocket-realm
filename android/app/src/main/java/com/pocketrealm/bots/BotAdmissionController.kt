package com.pocketrealm.bots

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class BotResourceSample(
    val elapsedMs: Long,
    val onlineBots: Int,
    val worldP99Ms: Int,
    val freeMemoryMiB: Long,
    val freeStorageMiB: Long,
    val thermal: ThermalLevel,
    val hardStallCount: Int = 0,
)

data class BotAdmissionState(
    val selectedTarget: Int,
    val effectiveTarget: Int,
    val adapted: Boolean,
    val reason: String,
    val changed: Boolean,
)

/** Pure policy core; applying a target remains the native control bridge's job. */
class BotAdmissionController(private val profile: BotProfile) {
    private var effectiveTarget = profile.selectedTarget
    private var lastChangeMs = Long.MIN_VALUE
    private var healthySinceMs: Long? = null
    private var performanceReadyAtMs: Long? = null
    private var reason = "selected-profile"

    fun observe(sample: BotResourceSample): BotAdmissionState {
        require(sample.elapsedMs >= 0 && sample.onlineBots >= 0)
        val performanceReady = performanceReadyAtMs?.let {
            sample.elapsedMs - it >= profile.admission.performanceWarmupMs
        } ?: false
        val overload = safetyOverloadReason(sample) ?:
            performanceOverloadReason(sample).takeIf { performanceReady }
        var changed = false
        if (overload != null) {
            healthySinceMs = null
            if (cooldownPassed(sample.elapsedMs) && effectiveTarget > profile.minimumOnline) {
                effectiveTarget = (effectiveTarget - profile.admission.reduceStep)
                    .coerceAtLeast(profile.minimumOnline)
                lastChangeMs = sample.elapsedMs
                changed = true
            }
            reason = if (changed) overload else "$overload;cooldown-or-floor"
        } else if (sample.thermal >= ThermalLevel.MODERATE) {
            healthySinceMs = null
            reason = "thermal-ramp-paused:${sample.thermal.name.lowercase()}"
        } else if (sample.onlineBots < profile.minimumOnline) {
            healthySinceMs = null
            reason = "bot-ramp"
        } else if (!performanceReady) {
            if (performanceReadyAtMs == null) performanceReadyAtMs = sample.elapsedMs
            healthySinceMs = null
            reason = "startup-warmup"
        } else {
            val healthySince = healthySinceMs ?: sample.elapsedMs.also { healthySinceMs = it }
            val healthyLongEnough = sample.elapsedMs - healthySince >= profile.admission.healthyRampMs
            if (healthyLongEnough && cooldownPassed(sample.elapsedMs) &&
                effectiveTarget < profile.selectedTarget) {
                effectiveTarget = (effectiveTarget + profile.admission.increaseStep)
                    .coerceAtMost(profile.selectedTarget)
                lastChangeMs = sample.elapsedMs
                healthySinceMs = sample.elapsedMs
                reason = "healthy-ramp"
                changed = true
            } else {
                reason = if (effectiveTarget == profile.selectedTarget) "selected-profile" else "healthy-hold"
            }
        }
        return BotAdmissionState(
            selectedTarget = profile.selectedTarget,
            effectiveTarget = effectiveTarget,
            adapted = effectiveTarget != profile.selectedTarget,
            reason = reason,
            changed = changed,
        )
    }

    private fun cooldownPassed(nowMs: Long): Boolean =
        lastChangeMs == Long.MIN_VALUE || nowMs - lastChangeMs >= profile.admission.changeCooldownMs

    private fun safetyOverloadReason(sample: BotResourceSample): String? = when {
        sample.thermal >= ThermalLevel.SEVERE -> "thermal:${sample.thermal.name.lowercase()}"
        sample.freeStorageMiB < profile.admission.minFreeStorageMiB -> "storage-floor"
        sample.freeMemoryMiB < profile.admission.minFreeMemoryMiB -> "memory-floor"
        else -> null
    }

    private fun performanceOverloadReason(sample: BotResourceSample): String? = when {
        sample.hardStallCount >= 2 -> "repeated-hard-stall"
        sample.worldP99Ms > profile.admission.maxWorldP99Ms -> "world-p99"
        else -> null
    }
}
