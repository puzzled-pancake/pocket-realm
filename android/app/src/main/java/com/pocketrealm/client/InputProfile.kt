package com.pocketrealm.client

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

enum class ControlPointer { LEFT, RIGHT }

/** Named, testable control maps. Per-control edits move a profile to [CUSTOM]. */
enum class ControlScheme(val displayName: String, val description: String) {
    CLASSIC_CAMERA(
        "Built-in WoW controls (no add-on)",
        "The right stick directly controls the camera; no lock button or PocketRealmPad is required.",
    ),
    POCKET_REALM_PAD(
        "PocketRealmPad 0.5 (optional)",
        "Use only when the optional add-on is installed; matches its face, layer, navigation, targeting, inventory, and jump semantics.",
    ),
    POCKET_REALM_PAD_CAMERA(
        "PocketRealmPad + camera lock (optional)",
        "The optional PocketRealmPad map with rear-right changed to a safe tap-to-lock camera toggle; R3 remains Jump.",
    ),
    CUSTOM("Custom", "Individually edited physical or on-screen bindings."),
}

/** How Android button names correspond to the four physical face positions. */
enum class FaceButtonLayout(val displayName: String) {
    ANDROID_STANDARD("Android / Xbox positions"),
    RP6_PRINTED("RP6 printed A/B/X/Y"),
}

/** Physical input-family override. All non-touch families share the remappable logical map. */
enum class ControllerFamily(val displayName: String, val description: String) {
    AUTO("Automatic", "Use the RP6 profile for the built-in Retroid controller and Android-standard positions otherwise."),
    RETROID_POCKET_6("Retroid Pocket 6", "RP6 printed face labels, rear paddles, Z/RZ right stick, and analogue triggers."),
    XBOX("Xbox / Android", "A/B/X/Y Android-standard positions and common gamepad buttons."),
    PLAYSTATION("PlayStation 4 / 5", "Cross/Circle/Square/Triangle positions using Android's standard gamepad events."),
    GENERIC("Generic controller", "Android-standard axes and buttons for third-party pads; every logical output remains editable."),
    KEYBOARD_MOUSE("Keyboard & mouse", "Ignore gamepads; use a physical keyboard/mouse with touch still available for precision."),
    TOUCH_ONLY("On-screen controls only", "Ignore physical gameplay controllers and use the editable touch overlay."),
}

/** Allowlisted outputs for the handheld profile; arbitrary key strings/macros are not accepted. */
enum class ControllerAction(
    val displayName: String,
    val keyCode: Int? = null,
    val pointer: ControlPointer? = null,
    val cameraLockToggle: Boolean = false,
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
    INVENTORY("B / controller inventory", KeyEvent.KEYCODE_B),
    PRP_BANK("F8 / ability bank", KeyEvent.KEYCODE_F8),
    PRP_LAYER_2("F9 / ability layer 2", KeyEvent.KEYCODE_F9),
    PRP_LAYER_3("F10 / ability layer 3", KeyEvent.KEYCODE_F10),
    NAV_UP("Up / menu navigation", KeyEvent.KEYCODE_DPAD_UP),
    NAV_DOWN("Down / menu navigation", KeyEvent.KEYCODE_DPAD_DOWN),
    NAV_LEFT("Left / menu navigation", KeyEvent.KEYCODE_DPAD_LEFT),
    NAV_RIGHT("Right / menu navigation", KeyEvent.KEYCODE_DPAD_RIGHT),
    POINTER_LEFT("Mouse left", pointer = ControlPointer.LEFT),
    POINTER_RIGHT("Mouse right", pointer = ControlPointer.RIGHT),
    CAMERA_LOCK("Camera lock (toggle)", cameraLockToggle = true),
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
    val rightStickDeadZone: Float = 0.05f,
    val overlayOpacity: Float = 0.85f,
    val overlayEnabled: Boolean = true,
    val overlayScale: Float = 1.0f,
    val cameraRegionWidth: Float = 0.42f,
    val invertCameraX: Boolean = false,
    val invertCameraY: Boolean = false,
    val leftTriggerOnThreshold: Float = 0.30f,
    val leftTriggerOffThreshold: Float = 0.20f,
    val rightTriggerOnThreshold: Float = 0.40f,
    val rightTriggerOffThreshold: Float = 0.25f,
    val scheme: ControlScheme = ControlScheme.CLASSIC_CAMERA,
    val controllerFamily: ControllerFamily = ControllerFamily.AUTO,
    val faceButtonLayout: FaceButtonLayout = FaceButtonLayout.ANDROID_STANDARD,
    val rp6Bindings: Map<Rp6Control, ControllerAction> = defaultRp6Bindings(),
    val overlayBindings: Map<OverlayControl, ControllerAction> = defaultOverlayBindings(),
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported InputProfile version=$version" }
        require(deadZone in 0f..0.5f) { "deadZone out of range: $deadZone" }
        require(aspectIdentity.isNotBlank()) { "aspectIdentity must not be blank" }
        require(cameraSensitivity in 0.25f..4.0f) { "cameraSensitivity out of range: $cameraSensitivity" }
        require(rightStickDeadZone in 0.05f..0.35f) { "rightStickDeadZone out of range: $rightStickDeadZone" }
        require(overlayOpacity in 0.35f..1.0f) { "overlayOpacity out of range: $overlayOpacity" }
        require(overlayScale in 0.75f..1.5f) { "overlayScale out of range: $overlayScale" }
        require(cameraRegionWidth in 0.25f..0.7f) { "cameraRegionWidth out of range: $cameraRegionWidth" }
        require(leftTriggerOffThreshold in 0f..leftTriggerOnThreshold) { "invalid left trigger hysteresis" }
        require(leftTriggerOnThreshold in 0.1f..0.9f) { "left trigger threshold out of range" }
        require(rightTriggerOffThreshold in 0f..rightTriggerOnThreshold) { "invalid right trigger hysteresis" }
        require(rightTriggerOnThreshold in 0.1f..0.9f) { "right trigger threshold out of range" }
    }

    companion object {
        /** Current [InputProfile] schema version. */
        const val CURRENT_VERSION: Int = 7

        private const val V6_DEFAULT_RIGHT_STICK_DEAD_ZONE: Float = 0.12f

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

        fun defaultRp6Bindings(): Map<Rp6Control, ControllerAction> = classicRp6Bindings(cameraLock = false)

        fun classicRp6Bindings(cameraLock: Boolean): Map<Rp6Control, ControllerAction> = linkedMapOf(
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
            Rp6Control.REAR_RIGHT to if (cameraLock) ControllerAction.CAMERA_LOCK else ControllerAction.POINTER_RIGHT,
        )

        /** Position-first PocketRealmPad 0.5 contract from the imported implementation bundle. */
        fun pocketRealmPadBindings(cameraLock: Boolean): Map<Rp6Control, ControllerAction> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to ControllerAction.MOVE_W,
            Rp6Control.LEFT_STICK_DOWN to ControllerAction.MOVE_S,
            Rp6Control.LEFT_STICK_LEFT to ControllerAction.STRAFE_Q,
            Rp6Control.LEFT_STICK_RIGHT to ControllerAction.STRAFE_E,
            Rp6Control.FACE_RIGHT to ControllerAction.KEY_1,
            Rp6Control.FACE_BOTTOM to ControllerAction.KEY_2,
            Rp6Control.FACE_LEFT to ControllerAction.KEY_3,
            Rp6Control.FACE_TOP to ControllerAction.KEY_4,
            Rp6Control.DPAD_UP to ControllerAction.NAV_UP,
            Rp6Control.DPAD_DOWN to ControllerAction.NAV_DOWN,
            Rp6Control.DPAD_LEFT to ControllerAction.NAV_LEFT,
            Rp6Control.DPAD_RIGHT to ControllerAction.NAV_RIGHT,
            Rp6Control.R1 to ControllerAction.PRP_BANK,
            Rp6Control.L1 to ControllerAction.PRP_LAYER_2,
            Rp6Control.L2 to ControllerAction.PRP_LAYER_3,
            Rp6Control.R2 to ControllerAction.POINTER_RIGHT,
            Rp6Control.START to ControllerAction.RADIAL_MENU,
            Rp6Control.SELECT to ControllerAction.INVENTORY,
            Rp6Control.L3 to ControllerAction.TARGET,
            Rp6Control.R3 to ControllerAction.JUMP,
            Rp6Control.REAR_LEFT to ControllerAction.TARGET,
            Rp6Control.REAR_RIGHT to if (cameraLock) ControllerAction.CAMERA_LOCK else ControllerAction.JUMP,
        )

        fun defaultOverlayBindings(): Map<OverlayControl, ControllerAction> = linkedMapOf(
            OverlayControl.MOVE_UP to ControllerAction.MOVE_W,
            OverlayControl.MOVE_DOWN to ControllerAction.MOVE_S,
            OverlayControl.MOVE_LEFT to ControllerAction.STRAFE_Q,
            OverlayControl.MOVE_RIGHT to ControllerAction.STRAFE_E,
            OverlayControl.JUMP to ControllerAction.JUMP,
            // Escape is always useful in the stock client. F7 becomes the
            // PocketRealmPad quick menu only when that integration is active.
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

        fun pocketRealmPadOverlayBindings(): Map<OverlayControl, ControllerAction> =
            defaultOverlayBindings() + (OverlayControl.MENU to ControllerAction.RADIAL_MENU)

        fun actionFor(profile: InputProfile, control: Rp6Control): ControllerAction =
            profile.rp6Bindings[control] ?: defaultRp6Bindings().getValue(control)

        fun actionFor(profile: InputProfile, control: OverlayControl): ControllerAction =
            profile.overlayBindings[control] ?: defaultOverlayBindings().getValue(control)

        fun profileForScheme(
            scheme: ControlScheme,
            aspectIdentity: String,
            base: InputProfile = DEFAULT.copy(aspectIdentity = aspectIdentity),
        ): InputProfile = when (scheme) {
            ControlScheme.CLASSIC_CAMERA -> base.copy(
                scheme = scheme,
                rp6Bindings = classicRp6Bindings(cameraLock = false),
                overlayBindings = defaultOverlayBindings(),
            )
            ControlScheme.POCKET_REALM_PAD -> base.copy(
                scheme = scheme,
                rp6Bindings = pocketRealmPadBindings(cameraLock = false),
                overlayBindings = pocketRealmPadOverlayBindings(),
            )
            ControlScheme.POCKET_REALM_PAD_CAMERA -> base.copy(
                scheme = scheme,
                rp6Bindings = pocketRealmPadBindings(cameraLock = true),
                overlayBindings = pocketRealmPadOverlayBindings(),
            )
            ControlScheme.CUSTOM -> base.copy(scheme = scheme)
        }

        fun fromJson(value: JSONObject): InputProfile {
            val storedVersion = value.optInt("version", 1)
            require(storedVersion in 1..CURRENT_VERSION) { "unsupported InputProfile version=$storedVersion" }
            val missingRightStickDeadZone = if (storedVersion <= 6) {
                V6_DEFAULT_RIGHT_STICK_DEAD_ZONE
            } else {
                DEFAULT.rightStickDeadZone
            }
            val storedRightStickDeadZone = value.optDouble(
                "rightStickDeadZone",
                missingRightStickDeadZone.toDouble(),
            ).toFloat()
            // v7 lowers only the exact former default. A user-tuned value --
            // including a v7 profile explicitly set back to 0.12 -- is data,
            // not a migration marker, and must be preserved.
            val rightStickDeadZone = if (
                storedVersion <= 6 && storedRightStickDeadZone == V6_DEFAULT_RIGHT_STICK_DEAD_ZONE
            ) DEFAULT.rightStickDeadZone else storedRightStickDeadZone
            val bindings = (if (storedVersion < 5) {
                classicRp6Bindings(cameraLock = false)
            } else if (storedVersion == 5) {
                classicRp6Bindings(cameraLock = true)
            } else defaultRp6Bindings()).toMutableMap()
            value.optJSONObject("rp6Bindings")?.let { stored ->
                Rp6Control.values().forEach { control ->
                    val actionName = stored.optString(control.name, "")
                    val action = ControllerAction.values().firstOrNull { it.name == actionName }
                    if (action != null) bindings[control] = action
                }
            }
            val overlayBindings = (if (storedVersion < 5) {
                legacyOverlayBindings()
            } else defaultOverlayBindings()).toMutableMap()
            value.optJSONObject("overlayBindings")?.let { stored ->
                OverlayControl.values().forEach { control ->
                    val actionName = stored.optString(control.name, "")
                    val action = ControllerAction.values().firstOrNull { it.name == actionName }
                    if (action != null) overlayBindings[control] = action
                }
            }
            val legacyDefault = storedVersion < 5 && bindings == classicRp6Bindings(cameraLock = false)
            val legacyOverlayDefault = storedVersion < 5 && overlayBindings == legacyOverlayBindings()
            val parsedScheme = value.optString("scheme")
                .takeIf { it.isNotBlank() }
                ?.let { stored -> ControlScheme.values().firstOrNull { it.name == stored } }
                ?: if (legacyDefault) ControlScheme.CLASSIC_CAMERA else ControlScheme.CUSTOM
            val v5CameraLockDefault = storedVersion == 5 &&
                parsedScheme == ControlScheme.CLASSIC_CAMERA &&
                bindings == classicRp6Bindings(cameraLock = true)
            // The first v5 development build temporarily made F7 the classic
            // overlay default. Repair only that exact default; custom and PRP
            // profiles retain their explicit choice.
            val repairedOverlay = if (
                storedVersion == 5 && parsedScheme == ControlScheme.CLASSIC_CAMERA &&
                overlayBindings == pocketRealmPadOverlayBindings()
            ) defaultOverlayBindings() else if (legacyOverlayDefault) {
                defaultOverlayBindings()
            } else overlayBindings
            return InputProfile(
                version = CURRENT_VERSION,
                deadZone = value.optDouble("deadZone", DEFAULT.deadZone.toDouble()).toFloat(),
                aspectIdentity = value.optString("aspectIdentity", DEFAULT_ASPECT_IDENTITY),
                cameraSensitivity = value.optDouble("cameraSensitivity", 1.0).toFloat(),
                rightStickDeadZone = rightStickDeadZone,
                overlayOpacity = value.optDouble("overlayOpacity", 0.85).toFloat(),
                overlayEnabled = value.optBoolean("overlayEnabled", true),
                overlayScale = value.optDouble("overlayScale", 1.0).toFloat(),
                cameraRegionWidth = value.optDouble("cameraRegionWidth", 0.42).toFloat(),
                invertCameraX = value.optBoolean("invertCameraX", false),
                invertCameraY = value.optBoolean("invertCameraY", false),
                leftTriggerOnThreshold = value.optDouble("leftTriggerOnThreshold", 0.30).toFloat(),
                leftTriggerOffThreshold = value.optDouble("leftTriggerOffThreshold", 0.20).toFloat(),
                rightTriggerOnThreshold = value.optDouble("rightTriggerOnThreshold", 0.40).toFloat(),
                rightTriggerOffThreshold = value.optDouble("rightTriggerOffThreshold", 0.25).toFloat(),
                scheme = parsedScheme,
                controllerFamily = value.optString("controllerFamily")
                    .takeIf { it.isNotBlank() }
                    ?.let { stored -> ControllerFamily.values().firstOrNull { it.name == stored } }
                    ?: ControllerFamily.AUTO,
                faceButtonLayout = value.optString("faceButtonLayout")
                    .takeIf { it.isNotBlank() }
                    ?.let { stored -> FaceButtonLayout.values().firstOrNull { it.name == stored } }
                    ?: FaceButtonLayout.ANDROID_STANDARD,
                rp6Bindings = if (legacyDefault || v5CameraLockDefault) defaultRp6Bindings() else bindings,
                overlayBindings = repairedOverlay,
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
                .put("rightStickDeadZone", profile.rightStickDeadZone.toDouble())
                .put("overlayOpacity", profile.overlayOpacity.toDouble())
                .put("overlayEnabled", profile.overlayEnabled)
                .put("overlayScale", profile.overlayScale.toDouble())
                .put("cameraRegionWidth", profile.cameraRegionWidth.toDouble())
                .put("invertCameraX", profile.invertCameraX)
                .put("invertCameraY", profile.invertCameraY)
                .put("leftTriggerOnThreshold", profile.leftTriggerOnThreshold.toDouble())
                .put("leftTriggerOffThreshold", profile.leftTriggerOffThreshold.toDouble())
                .put("rightTriggerOnThreshold", profile.rightTriggerOnThreshold.toDouble())
                .put("rightTriggerOffThreshold", profile.rightTriggerOffThreshold.toDouble())
                .put("scheme", profile.scheme.name)
                .put("controllerFamily", profile.controllerFamily.name)
                .put("faceButtonLayout", profile.faceButtonLayout.name)
                .put("rp6Bindings", bindings)
                .put("overlayBindings", overlayBindings)
        }

        private fun legacyOverlayBindings(): Map<OverlayControl, ControllerAction> =
            defaultOverlayBindings()

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
        // A partially-written/corrupt current record must not hide the last
        // valid legacy profile. A valid current record remains authoritative,
        // including an intentional aspect mismatch.
        val stored = (listOf(KEY) + LEGACY_KEYS).asSequence()
            .mapNotNull { preferences.getString(it, null) }
            .mapNotNull { raw ->
                runCatching { InputProfile.fromJson(JSONObject(raw)) }.getOrNull()
            }
            .firstOrNull()
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
        const val KEY = "profile_v7"
        val LEGACY_KEYS = listOf("profile_v6", "profile_v5", "profile_v4", "profile_v3", "profile_v2")
    }
}
