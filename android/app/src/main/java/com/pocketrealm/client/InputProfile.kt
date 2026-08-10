package com.pocketrealm.client

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

enum class ControlPointer { LEFT, RIGHT }

/** Allowlisted outputs for the handheld profile; arbitrary key strings/macros are not accepted. */
enum class ControllerAction(
    val displayName: String,
    val keyCode: Int? = null,
    val pointer: ControlPointer? = null,
) {
    DISABLED("Disabled"),
    KEY_1("Key 1", KeyEvent.KEYCODE_1),
    KEY_2("Key 2", KeyEvent.KEYCODE_2),
    KEY_3("Key 3", KeyEvent.KEYCODE_3),
    KEY_4("Key 4", KeyEvent.KEYCODE_4),
    KEY_5("Key 5", KeyEvent.KEYCODE_5),
    KEY_6("Key 6", KeyEvent.KEYCODE_6),
    KEY_7("Key 7", KeyEvent.KEYCODE_7),
    KEY_8("Key 8", KeyEvent.KEYCODE_8),
    KEY_9("Key 9", KeyEvent.KEYCODE_9),
    KEY_0("Key 0", KeyEvent.KEYCODE_0),
    MOVE_W("Move W", KeyEvent.KEYCODE_W),
    MOVE_S("Move S", KeyEvent.KEYCODE_S),
    STRAFE_Q("Strafe Q", KeyEvent.KEYCODE_Q),
    STRAFE_E("Strafe E", KeyEvent.KEYCODE_E),
    MOVE_A("Key A", KeyEvent.KEYCODE_A),
    MOVE_D("Key D", KeyEvent.KEYCODE_D),
    SHIFT("Left Shift", KeyEvent.KEYCODE_SHIFT_LEFT),
    CTRL("Left Ctrl", KeyEvent.KEYCODE_CTRL_LEFT),
    AUTO_RUN("Num Lock / auto-run", KeyEvent.KEYCODE_NUM_LOCK),
    RADIAL_MENU("F7 / radial menu", KeyEvent.KEYCODE_F7),
    MAP("M / map", KeyEvent.KEYCODE_M),
    INTERACT("I / interact", KeyEvent.KEYCODE_I),
    JUMP("Space / jump", KeyEvent.KEYCODE_SPACE),
    ESCAPE("Escape", KeyEvent.KEYCODE_ESCAPE),
    ENTER("Enter", KeyEvent.KEYCODE_ENTER),
    TARGET("Tab / target", KeyEvent.KEYCODE_TAB),
    POINTER_LEFT("Mouse left", pointer = ControlPointer.LEFT),
    POINTER_RIGHT("Mouse right", pointer = ControlPointer.RIGHT),
}

/** Physical RP6 controls whose gameplay output can be changed in Settings. */
enum class Rp6Control(val displayName: String, val axisDirection: Boolean = false) {
    LEFT_STICK_UP("Left stick up", true),
    LEFT_STICK_DOWN("Left stick down", true),
    LEFT_STICK_LEFT("Left stick left", true),
    LEFT_STICK_RIGHT("Left stick right", true),
    FACE_BOTTOM("Bottom face button"),
    FACE_LEFT("Left face button"),
    FACE_TOP("Top face button"),
    FACE_RIGHT("Right face button"),
    DPAD_DOWN("D-pad down"),
    DPAD_LEFT("D-pad left"),
    DPAD_UP("D-pad up"),
    DPAD_RIGHT("D-pad right"),
    R1("R1"),
    L1("L1"),
    L2("L2"),
    R2("R2"),
    START("Start"),
    SELECT("Select"),
    L3("L3"),
    R3("R3"),
    REAR_LEFT("Rear left"),
    REAR_RIGHT("Rear right"),
}

/** App-owned touch controls. Android system navigation is intentionally absent. */
enum class OverlayControl(val displayName: String) {
    MOVE_UP("Move up"),
    MOVE_DOWN("Move down"),
    MOVE_LEFT("Move left"),
    MOVE_RIGHT("Move right"),
    JUMP("Jump"),
    MENU("Menu"),
    ACTION_1("Action 1"),
    ACTION_2("Action 2"),
    ACTION_3("Action 3"),
    ACTION_4("Action 4"),
    ACTION_5("Action 5"),
    ACTION_6("Action 6"),
    ACTION_7("Action 7"),
    ACTION_8("Action 8"),
}

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
 * @param deadZone neutral stick dead zone in `0f..0.5f`.
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
    val overlayEnabled: Boolean = true,
    val overlayScale: Float = 1.0f,
    val cameraRegionWidth: Float = 0.42f,
    val invertCameraX: Boolean = false,
    val invertCameraY: Boolean = false,
    val rp6Bindings: Map<Rp6Control, ControllerAction> = defaultRp6Bindings(),
    val overlayBindings: Map<OverlayControl, ControllerAction> = defaultOverlayBindings(),
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported InputProfile version=$version" }
        require(deadZone in 0f..0.5f) { "deadZone out of range: $deadZone" }
        require(aspectIdentity.isNotBlank()) { "aspectIdentity must not be blank" }
        require(cameraSensitivity in 0.25f..4.0f) { "cameraSensitivity out of range: $cameraSensitivity" }
        require(overlayOpacity in 0.35f..1.0f) { "overlayOpacity out of range: $overlayOpacity" }
        require(overlayScale in 0.75f..1.5f) { "overlayScale out of range: $overlayScale" }
        require(cameraRegionWidth in 0.25f..0.7f) { "cameraRegionWidth out of range: $cameraRegionWidth" }
    }

    companion object {
        /** Current [InputProfile] schema version. */
        const val CURRENT_VERSION: Int = 4

        /**
         * The default profile. Used at first launch and whenever the active
         * display's aspect identity does not match a stored profile. This
         * profile is selected.
         */
        val DEFAULT: InputProfile = InputProfile(
            version = CURRENT_VERSION,
            deadZone = 0.12f,
            aspectIdentity = DEFAULT_ASPECT_IDENTITY,
        )

        /** Aspect identity assumed by the default profile (16:9, 1280x720). */
        const val DEFAULT_ASPECT_IDENTITY: String = "16:9"

        fun defaultRp6Bindings(): Map<Rp6Control, ControllerAction> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to ControllerAction.MOVE_W,
            Rp6Control.LEFT_STICK_DOWN to ControllerAction.MOVE_S,
            Rp6Control.LEFT_STICK_LEFT to ControllerAction.STRAFE_Q,
            Rp6Control.LEFT_STICK_RIGHT to ControllerAction.STRAFE_E,
            Rp6Control.FACE_BOTTOM to ControllerAction.KEY_1,
            Rp6Control.FACE_LEFT to ControllerAction.KEY_2,
            Rp6Control.FACE_TOP to ControllerAction.KEY_3,
            Rp6Control.FACE_RIGHT to ControllerAction.KEY_4,
            Rp6Control.DPAD_DOWN to ControllerAction.KEY_5,
            Rp6Control.DPAD_LEFT to ControllerAction.KEY_6,
            Rp6Control.DPAD_UP to ControllerAction.KEY_7,
            Rp6Control.DPAD_RIGHT to ControllerAction.KEY_8,
            Rp6Control.R1 to ControllerAction.KEY_9,
            Rp6Control.L1 to ControllerAction.KEY_0,
            Rp6Control.L2 to ControllerAction.SHIFT,
            Rp6Control.R2 to ControllerAction.CTRL,
            Rp6Control.START to ControllerAction.RADIAL_MENU,
            Rp6Control.SELECT to ControllerAction.MAP,
            Rp6Control.L3 to ControllerAction.AUTO_RUN,
            Rp6Control.R3 to ControllerAction.POINTER_LEFT,
            Rp6Control.REAR_LEFT to ControllerAction.INTERACT,
            Rp6Control.REAR_RIGHT to ControllerAction.POINTER_RIGHT,
        )

        fun defaultOverlayBindings(): Map<OverlayControl, ControllerAction> = linkedMapOf(
            OverlayControl.MOVE_UP to ControllerAction.MOVE_W,
            OverlayControl.MOVE_DOWN to ControllerAction.MOVE_S,
            OverlayControl.MOVE_LEFT to ControllerAction.STRAFE_Q,
            OverlayControl.MOVE_RIGHT to ControllerAction.STRAFE_E,
            OverlayControl.JUMP to ControllerAction.JUMP,
            OverlayControl.MENU to ControllerAction.ESCAPE,
            OverlayControl.ACTION_1 to ControllerAction.KEY_1,
            OverlayControl.ACTION_2 to ControllerAction.KEY_2,
            OverlayControl.ACTION_3 to ControllerAction.KEY_3,
            OverlayControl.ACTION_4 to ControllerAction.KEY_4,
            OverlayControl.ACTION_5 to ControllerAction.KEY_5,
            OverlayControl.ACTION_6 to ControllerAction.KEY_6,
            OverlayControl.ACTION_7 to ControllerAction.KEY_7,
            OverlayControl.ACTION_8 to ControllerAction.KEY_8,
        )

        fun actionFor(profile: InputProfile, control: Rp6Control): ControllerAction =
            profile.rp6Bindings[control] ?: defaultRp6Bindings().getValue(control)

        fun actionFor(profile: InputProfile, control: OverlayControl): ControllerAction =
            profile.overlayBindings[control] ?: defaultOverlayBindings().getValue(control)

        fun fromJson(value: JSONObject): InputProfile {
            val storedVersion = value.optInt("version", 1)
            require(storedVersion in 1..CURRENT_VERSION) { "unsupported InputProfile version=$storedVersion" }
            val bindings = defaultRp6Bindings().toMutableMap()
            value.optJSONObject("rp6Bindings")?.let { stored ->
                Rp6Control.values().forEach { control ->
                    val actionName = stored.optString(control.name, "")
                    val action = ControllerAction.values().firstOrNull { it.name == actionName }
                    if (action != null) bindings[control] = action
                }
            }
            val overlayBindings = defaultOverlayBindings().toMutableMap()
            value.optJSONObject("overlayBindings")?.let { stored ->
                OverlayControl.values().forEach { control ->
                    val actionName = stored.optString(control.name, "")
                    val action = ControllerAction.values().firstOrNull { it.name == actionName }
                    if (action != null) overlayBindings[control] = action
                }
            }
            return InputProfile(
                version = CURRENT_VERSION,
                deadZone = value.optDouble("deadZone", DEFAULT.deadZone.toDouble()).toFloat(),
                aspectIdentity = value.optString("aspectIdentity", DEFAULT_ASPECT_IDENTITY),
                cameraSensitivity = value.optDouble("cameraSensitivity", 1.0).toFloat(),
                overlayOpacity = value.optDouble("overlayOpacity", 0.85).toFloat(),
                overlayEnabled = value.optBoolean("overlayEnabled", true),
                overlayScale = value.optDouble("overlayScale", 1.0).toFloat(),
                cameraRegionWidth = value.optDouble("cameraRegionWidth", 0.42).toFloat(),
                invertCameraX = value.optBoolean("invertCameraX", false),
                invertCameraY = value.optBoolean("invertCameraY", false),
                rp6Bindings = bindings,
                overlayBindings = overlayBindings,
            )
        }

        fun toJson(profile: InputProfile): JSONObject {
            val bindings = JSONObject()
            Rp6Control.values().forEach { control ->
                bindings.put(control.name, actionFor(profile, control).name)
            }
            val overlayBindings = JSONObject()
            OverlayControl.values().forEach { control ->
                overlayBindings.put(control.name, actionFor(profile, control).name)
            }
            return JSONObject()
                .put("version", profile.version)
                .put("deadZone", profile.deadZone.toDouble())
                .put("aspectIdentity", profile.aspectIdentity)
                .put("cameraSensitivity", profile.cameraSensitivity.toDouble())
                .put("overlayOpacity", profile.overlayOpacity.toDouble())
                .put("overlayEnabled", profile.overlayEnabled)
                .put("overlayScale", profile.overlayScale.toDouble())
                .put("cameraRegionWidth", profile.cameraRegionWidth.toDouble())
                .put("invertCameraX", profile.invertCameraX)
                .put("invertCameraY", profile.invertCameraY)
                .put("rp6Bindings", bindings)
                .put("overlayBindings", overlayBindings)
        }

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
            ?: LEGACY_KEYS.firstNotNullOfOrNull { preferences.getString(it, null) }
        val stored = raw?.let { runCatching { InputProfile.fromJson(JSONObject(it)) }.getOrNull() }
        return if (stored != null && stored.aspectIdentity == aspectIdentity) {
            LoadResult(stored, resetForAspect = false)
        } else if (stored != null) {
            LoadResult(InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity), resetForAspect = true)
        } else {
            LoadResult(InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity), resetForAspect = false)
        }
    }

    fun save(profile: InputProfile) {
        check(preferences.edit().putString(KEY, InputProfile.toJson(profile).toString()).commit()) {
            "input profile could not be persisted"
        }
    }

    private companion object {
        const val NAME = "pocket_input_profile"
        const val KEY = "profile_v4"
        val LEGACY_KEYS = listOf("profile_v3", "profile_v2")
    }
}
