package com.pocketrealm.server

/** Shared bounds for the controller's one-button nearby use/open request. */
internal object NearbyInteractPolicy {
    const val DEFAULT_TRIGGER_GUARD_MS = 250
    const val MIN_TRIGGER_GUARD_MS = 100
    const val MAX_TRIGGER_GUARD_MS = 2_000
    const val TRIGGER_GUARD_STEP_MS = 50

    fun normalizeTriggerGuardMs(value: Int): Int =
        value.coerceIn(MIN_TRIGGER_GUARD_MS, MAX_TRIGGER_GUARD_MS)
}
