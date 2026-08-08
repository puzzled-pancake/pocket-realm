package com.pocketrealm.client

import android.content.Context
import org.json.JSONObject

/**
 * O14 G4 mobile input UX — versioned logical input profile.
 *
 * Report §16.6/§16.8 require a persisted action map with per-device dead zones
 * that resets to a known layout when the screen aspect changes beyond a tested
 * threshold. The profile is deliberately small, but it is now persisted as a
 * versioned JSON record so a relaunch keeps the user's dead-zone and camera
 * sensitivity choices without carrying a layout across an incompatible aspect.
 *
 * The profile is deliberately small and data-only so the [InputContract] can
 * select it without depending on UI. [InputProfileStore] is the only storage
 * adapter. An aspect mismatch must select the default and report `profileReset`;
 * it must never silently reuse a profile authored against a different aspect
 * (report §16.8).
 *
 * @param version schema version; bump when the record shape changes
 * @param deadZone neutral stick dead zone in `0f..0.5f`; unused by this increment
 *     but reserved so later gamepad support does not change the record shape
 * @param aspectIdentity stable identity of the screen aspect the profile was
 *     authored against (e.g. `"16:9"`). The contract compares this to the active
 *     display's identity and selects the default on mismatch.
 * @param cameraSensitivity relative-pointer multiplier in `0.25f..4f`.
 * @param overlayOpacity default touch-overlay opacity in `0.35f..1f`.
 */
data class InputProfile(
    val version: Int,
    val deadZone: Float,
    val aspectIdentity: String,
    val cameraSensitivity: Float = 1.0f,
    val overlayOpacity: Float = 0.85f,
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported InputProfile version=$version" }
        require(deadZone in 0f..0.5f) { "deadZone out of range: $deadZone" }
        require(aspectIdentity.isNotBlank()) { "aspectIdentity must not be blank" }
        require(cameraSensitivity in 0.25f..4.0f) { "cameraSensitivity out of range: $cameraSensitivity" }
        require(overlayOpacity in 0.35f..1.0f) { "overlayOpacity out of range: $overlayOpacity" }
    }

    companion object {
        /** Current [InputProfile] schema version. */
        const val CURRENT_VERSION: Int = 2

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

        fun fromJson(value: JSONObject): InputProfile {
            val storedVersion = value.optInt("version", 1)
            require(storedVersion in 1..CURRENT_VERSION) { "unsupported InputProfile version=$storedVersion" }
            return InputProfile(
                version = CURRENT_VERSION,
                deadZone = value.optDouble("deadZone", DEFAULT.deadZone.toDouble()).toFloat(),
                aspectIdentity = value.optString("aspectIdentity", DEFAULT_ASPECT_IDENTITY),
                cameraSensitivity = value.optDouble("cameraSensitivity", 1.0).toFloat(),
                overlayOpacity = value.optDouble("overlayOpacity", 0.85).toFloat(),
            )
        }

        fun toJson(profile: InputProfile): JSONObject = JSONObject()
            .put("version", profile.version)
            .put("deadZone", profile.deadZone.toDouble())
            .put("aspectIdentity", profile.aspectIdentity)
            .put("cameraSensitivity", profile.cameraSensitivity.toDouble())
            .put("overlayOpacity", profile.overlayOpacity.toDouble())

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

/** Durable app-private storage for the versioned input profile. */
class InputProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    data class LoadResult(val profile: InputProfile, val resetForAspect: Boolean)

    fun load(aspectIdentity: String): LoadResult {
        val raw = preferences.getString(KEY, null)
        val stored = raw?.let { runCatching { InputProfile.fromJson(JSONObject(it)) }.getOrNull() }
        return if (stored != null) {
            LoadResult(stored, resetForAspect = stored.aspectIdentity != aspectIdentity)
        } else {
            LoadResult(InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity), resetForAspect = false)
        }
    }

    fun save(profile: InputProfile) {
        preferences.edit().putString(KEY, InputProfile.toJson(profile).toString()).apply()
    }

    private companion object {
        const val NAME = "pocket_input_profile"
        const val KEY = "profile_v2"
    }
}
