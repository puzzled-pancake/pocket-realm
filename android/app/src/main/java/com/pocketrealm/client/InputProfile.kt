package com.pocketrealm.client

/**
 * O14 G4 mobile input UX — versioned logical input profile.
 *
 * Report §16.6/§16.8 require a persisted action map with per-device dead zones
 * that resets to a known layout when the screen aspect changes beyond a tested
 * threshold. This first increment ships only the in-memory default profile and
 * the aspect-identity check; on-disk persistence, remapping, and per-device
 * calibration arrive in later O14 increments and are intentionally absent here.
 *
 * The profile is deliberately small and data-only so the [InputContract] can
 * select it without depending on storage or UI. An aspect mismatch must select
 * the default and report `profileReset`; it must never silently reuse a profile
 * authored against a different aspect (report §16.8).
 *
 * @param version schema version; bump when the record shape changes
 * @param deadZone neutral stick dead zone in `0f..0.5f`; unused by this increment
 *     but reserved so later gamepad support does not change the record shape
 * @param aspectIdentity stable identity of the screen aspect the profile was
 *     authored against (e.g. `"16:9"`). The contract compares this to the active
 *     display's identity and selects the default on mismatch.
 */
data class InputProfile(
    val version: Int,
    val deadZone: Float,
    val aspectIdentity: String,
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported InputProfile version=$version" }
        require(deadZone in 0f..0.5f) { "deadZone out of range: $deadZone" }
        require(aspectIdentity.isNotBlank()) { "aspectIdentity must not be blank" }
    }

    companion object {
        /** Current [InputProfile] schema version. */
        const val CURRENT_VERSION: Int = 1

        /**
         * The default profile. Used at first launch and whenever the active
         * display's aspect identity does not match a stored profile. This
         * increment has no storage, so the default is always selected.
         */
        val DEFAULT: InputProfile = InputProfile(
            version = CURRENT_VERSION,
            deadZone = 0.12f,
            aspectIdentity = DEFAULT_ASPECT_IDENTITY,
        )

        /** Aspect identity assumed by the default profile (16:9, 1280x720). */
        const val DEFAULT_ASPECT_IDENTITY: String = "16:9"

        /**
         * Compute a stable aspect identity string from width/height in pixels.
         * Reduces to the coprime `w:h` ratio so 1920x1080 and 1280x720 share the
         * same `"16:9"` identity. Falls back to `"<w>x<h>"` when either is <= 0.
         */
        fun aspectIdentity(width: Int, height: Int): String {
            if (width <= 0 || height <= 0) return "${width}x${height}"
            val g = gcd(width, height)
            return "${width / g}:${height / g}"
        }

        private fun gcd(a: Int, b: Int): Int {
            var x = a; var y = b
            while (y != 0) { val t = x % y; x = y; y = t }
            return x
        }
    }
}
