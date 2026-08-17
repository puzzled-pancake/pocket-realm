package com.pocketrealm.client

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

enum class ControlPointer { LEFT, MIDDLE, RIGHT }

enum class FaceLayer { L2, L1 }

/** Named, testable control maps. Per-control edits move a profile to [CUSTOM]. */
enum class ControlScheme(val displayName: String, val description: String) {
    CLASSIC_CAMERA(
        "Built-in leveling controls (no add-on)",
        "Target, loot/interact, jump, movement and abilities work without an add-on. Lock camera makes the right stick look; unlock it to move the cursor.",
    ),
    ANDROID_PORT(
        "Android Port",
        "Optional Vanilla 1.12.1 layout: direct target/use shoulders, eight action buttons, LT/RT modifier pages and radial menu. Unreliable RP6 M1/M2 rear buttons stay disabled.",
    ),
    CUSTOM("Custom", "Individually edited physical or on-screen bindings."),
}

/** How Android button names correspond to the four physical face positions. */
enum class FaceButtonLayout(val displayName: String) {
    ANDROID_STANDARD("Android / Xbox positions"),
    RP6_PRINTED("RP6 printed A/B/X/Y"),
}

/** Amount of touch chrome shown while the client is running. */
enum class OverlayMode {
    /** Minimal with a controller, full for touch-only play. */
    AUTO,
    MINIMAL,
    FULL,
    OFF,
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
    val targetPulse: Boolean = false,
    val useLootClick: Boolean = false,
    val nearbyUsePulse: Boolean = false,
    val autoRunPulse: Boolean = false,
    val wheelTicks: Int? = null,
    val radialMenuPulse: Boolean = false,
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
    // Retain NUM_LOCK as the catalog identity for backward-compatible JSON
    // and complete key exposure, but dispatch Auto-run through the app-owned
    // F9 edge. Winlator models Num Lock as a sticky modifier, so consecutive
    // controller down/up pulses otherwise alternate press-only/release-only.
    AUTO_RUN("Auto-run", KeyEvent.KEYCODE_NUM_LOCK, autoRunPulse = true),
    MAP("M / map", KeyEvent.KEYCODE_M),
    INTERACT("I key (legacy/custom)", KeyEvent.KEYCODE_I),
    JUMP("Space / jump", KeyEvent.KEYCODE_SPACE),
    ESCAPE("Escape", KeyEvent.KEYCODE_ESCAPE),
    ENTER("Enter", KeyEvent.KEYCODE_ENTER),
    // Winlator's Android TAB translation reaches X11 but is not observed by
    // the 1.12.1 client on the qualified ARM path. F6 is unbound by the stock
    // client, survives the same path, and is reserved by the launch-time
    // binding projection for the built-in controller Target action.
    TARGET("Target nearest enemy", KeyEvent.KEYCODE_F6),
    INVENTORY("B / backpack", KeyEvent.KEYCODE_B),
    NAV_UP("Up / menu navigation", KeyEvent.KEYCODE_DPAD_UP),
    NAV_DOWN("Down / menu navigation", KeyEvent.KEYCODE_DPAD_DOWN),
    NAV_LEFT("Left / menu navigation", KeyEvent.KEYCODE_DPAD_LEFT),
    NAV_RIGHT("Right / menu navigation", KeyEvent.KEYCODE_DPAD_RIGHT),
    POINTER_LEFT("Mouse left", pointer = ControlPointer.LEFT),
    POINTER_RIGHT("Mouse right", pointer = ControlPointer.RIGHT),
    CAMERA_LOCK("Camera lock (toggle)", cameraLockToggle = true),

    // Schema-v11 additions are appended so ordinal-derived transient touch
    // source ids for every pre-existing action remain stable.
    TARGET_PULSE("Target nearest enemy (single press)", targetPulse = true),
    USE_LOOT_CLICK("Use / open (right-click)", useLootClick = true),
    LAYER_L2("Face layer: actions 5-8"),
    LAYER_L1("Face layer: actions 9-12"),
    KEY_MINUS("Key -", KeyEvent.KEYCODE_MINUS),
    KEY_EQUALS("Key =", KeyEvent.KEYCODE_EQUALS),
    KEY_C("Key C", KeyEvent.KEYCODE_C),
    KEY_F("Key F", KeyEvent.KEYCODE_F),
    KEY_G("Key G / target last hostile (not corpse-guaranteed)", KeyEvent.KEYCODE_G),
    KEY_H("Key H", KeyEvent.KEYCODE_H),
    KEY_J("Key J", KeyEvent.KEYCODE_J),
    KEY_K("Key K", KeyEvent.KEYCODE_K),
    KEY_L("Key L / quest log", KeyEvent.KEYCODE_L),
    KEY_N("Key N", KeyEvent.KEYCODE_N),
    KEY_O("Key O", KeyEvent.KEYCODE_O),
    KEY_P("Key P", KeyEvent.KEYCODE_P),
    KEY_R("Key R", KeyEvent.KEYCODE_R),
    KEY_T("Key T", KeyEvent.KEYCODE_T),
    KEY_U("Key U", KeyEvent.KEYCODE_U),
    KEY_V("Key V", KeyEvent.KEYCODE_V),
    KEY_X("Key X", KeyEvent.KEYCODE_X),
    KEY_Y("Key Y", KeyEvent.KEYCODE_Y),
    KEY_Z("Key Z", KeyEvent.KEYCODE_Z),
    SHIFT_RIGHT("Right Shift", KeyEvent.KEYCODE_SHIFT_RIGHT),
    CTRL_RIGHT("Right Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT),
    ALT_LEFT("Left Alt", KeyEvent.KEYCODE_ALT_LEFT),
    ALT_RIGHT("Right Alt", KeyEvent.KEYCODE_ALT_RIGHT),
    BACKSPACE("Backspace", KeyEvent.KEYCODE_DEL),
    INSERT("Insert", KeyEvent.KEYCODE_INSERT),
    DELETE("Delete", KeyEvent.KEYCODE_FORWARD_DEL),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("Page Up", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("Page Down", KeyEvent.KEYCODE_PAGE_DOWN),
    F1("F1 / target self", KeyEvent.KEYCODE_F1),
    F2("F2", KeyEvent.KEYCODE_F2),
    F3("F3", KeyEvent.KEYCODE_F3),
    F4("F4", KeyEvent.KEYCODE_F4),
    F5("F5", KeyEvent.KEYCODE_F5),
    F7("F7", KeyEvent.KEYCODE_F7),
    F8("F8", KeyEvent.KEYCODE_F8),
    F9("F9", KeyEvent.KEYCODE_F9),
    F10("F10", KeyEvent.KEYCODE_F10),
    F11("F11", KeyEvent.KEYCODE_F11),
    F12("F12", KeyEvent.KEYCODE_F12),
    COMMA("Comma", KeyEvent.KEYCODE_COMMA),
    PERIOD("Period", KeyEvent.KEYCODE_PERIOD),
    SEMICOLON("Semicolon", KeyEvent.KEYCODE_SEMICOLON),
    APOSTROPHE("Apostrophe", KeyEvent.KEYCODE_APOSTROPHE),
    LEFT_BRACKET("Left bracket", KeyEvent.KEYCODE_LEFT_BRACKET),
    RIGHT_BRACKET("Right bracket", KeyEvent.KEYCODE_RIGHT_BRACKET),
    GRAVE("Grave", KeyEvent.KEYCODE_GRAVE),
    SLASH("Slash", KeyEvent.KEYCODE_SLASH),
    BACKSLASH("Backslash", KeyEvent.KEYCODE_BACKSLASH),
    CAPS_LOCK("Caps Lock", KeyEvent.KEYCODE_CAPS_LOCK),
    NUMPAD_DIVIDE("Numpad /", KeyEvent.KEYCODE_NUMPAD_DIVIDE),
    NUMPAD_MULTIPLY("Numpad *", KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
    NUMPAD_SUBTRACT("Numpad -", KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
    NUMPAD_ADD("Numpad +", KeyEvent.KEYCODE_NUMPAD_ADD),
    NUMPAD_DOT("Numpad .", KeyEvent.KEYCODE_NUMPAD_DOT),
    NUMPAD_0("Numpad 0", KeyEvent.KEYCODE_NUMPAD_0),
    NUMPAD_1("Numpad 1", KeyEvent.KEYCODE_NUMPAD_1),
    NUMPAD_2("Numpad 2", KeyEvent.KEYCODE_NUMPAD_2),
    NUMPAD_3("Numpad 3", KeyEvent.KEYCODE_NUMPAD_3),
    NUMPAD_4("Numpad 4", KeyEvent.KEYCODE_NUMPAD_4),
    NUMPAD_5("Numpad 5", KeyEvent.KEYCODE_NUMPAD_5),
    NUMPAD_6("Numpad 6", KeyEvent.KEYCODE_NUMPAD_6),
    NUMPAD_7("Numpad 7", KeyEvent.KEYCODE_NUMPAD_7),
    NUMPAD_8("Numpad 8", KeyEvent.KEYCODE_NUMPAD_8),
    NUMPAD_9("Numpad 9", KeyEvent.KEYCODE_NUMPAD_9),
    POINTER_MIDDLE("Mouse middle", pointer = ControlPointer.MIDDLE),
    WHEEL_UP("Mouse wheel up", wheelTicks = -1),
    WHEEL_DOWN("Mouse wheel down", wheelTicks = 1),

    // Appended so ordinal-derived transient touch source ids remain stable.
    // Android Port binds its radial menu to the app-owned F12 edge.
    CONSOLE_RADIAL("Console radial menu", radialMenuPulse = true),
    NEARBY_USE("Nearby use / open (realm-assisted)", nearbyUsePulse = true),
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
    TARGET("Target nearest enemy"),
    USE_LOOT("Use or open at pointer"),
    AUTO_RUN("Auto-run"),
    ACTION_1("Action 1"),
    ACTION_2("Action 2"),
    ACTION_3("Action 3"),
    ACTION_4("Action 4"),
    ACTION_5("Action 5"),
    ACTION_6("Action 6"),
    ACTION_7("Action 7"),
    ACTION_8("Action 8"),
    ACTION_9("Action 9"),
    ACTION_10("Action 10"),
    ACTION_11("Action 11"),
    ACTION_12("Action 12"),
}

/**
 * Touch-overlay cluster whose placement the player can rearrange. Ids are
 * stable serialization keys; append new clusters so stored positions survive.
 */
enum class OverlayClusterId { DRAWER, TARGET_ROW, MOVEMENT, ACTIONS }

/**
 * Normalized top-left of a touch cluster inside the overlay container, each
 * fraction in `0f..1f`. Absent anchor = the cluster's stock alignment.
 */
data class ClusterAnchor(val xFraction: Float, val yFraction: Float)

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
    val overlayMode: OverlayMode = OverlayMode.AUTO,
    val overlayScale: Float = 1.0f,
    val overlayClusterPositions: Map<OverlayClusterId, ClusterAnchor> = emptyMap(),
    val cameraRegionWidth: Float = 0.42f,
    val touchCameraSensitivity: Float = 0.35f,
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
    val layerFaceBindings: Map<FaceLayer, Map<Rp6Control, ControllerAction>> = defaultLayerFaceBindings(),
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
        require(touchCameraSensitivity in 0.15f..1.0f) {
            "touchCameraSensitivity out of range: $touchCameraSensitivity"
        }
        require(leftTriggerOffThreshold in 0f..leftTriggerOnThreshold) { "invalid left trigger hysteresis" }
        require(leftTriggerOnThreshold in 0.1f..0.9f) { "left trigger threshold out of range" }
        require(rightTriggerOffThreshold in 0f..rightTriggerOnThreshold) { "invalid right trigger hysteresis" }
        require(rightTriggerOnThreshold in 0.1f..0.9f) { "right trigger threshold out of range" }
        overlayClusterPositions.values.forEach { anchor ->
            require(anchor.xFraction in 0f..1f && anchor.yFraction in 0f..1f) {
                "overlay cluster position out of range: $anchor"
            }
        }
    }

    companion object {
        /** Current [InputProfile] schema version. */
        const val CURRENT_VERSION: Int = 12

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
            Rp6Control.DPAD_DOWN to ControllerAction.F1,
            Rp6Control.DPAD_LEFT to ControllerAction.INVENTORY,
            Rp6Control.DPAD_UP to ControllerAction.KEY_G,
            Rp6Control.DPAD_RIGHT to ControllerAction.KEY_L,
            Rp6Control.R1 to ControllerAction.TARGET_PULSE,
            Rp6Control.L1 to ControllerAction.LAYER_L1,
            Rp6Control.L2 to ControllerAction.LAYER_L2,
            Rp6Control.R2 to ControllerAction.USE_LOOT_CLICK,
            Rp6Control.START to ControllerAction.ESCAPE,
            Rp6Control.SELECT to ControllerAction.MAP,
            Rp6Control.L3 to ControllerAction.AUTO_RUN,
            Rp6Control.R3 to ControllerAction.JUMP,
            // RP6 M1/M2 can mechanically latch on some units. Essential
            // camera/pointer utilities live on Select chords instead, so the
            // default never depends on a rear-button release arriving.
            Rp6Control.REAR_LEFT to ControllerAction.DISABLED,
            Rp6Control.REAR_RIGHT to ControllerAction.DISABLED,
        )

        /**
         * The selected Vanilla 1.12.1 controller addon's keyboard contract. Pocket Realm
         * supplies these ordinary key/mouse events through Winlator; the addon
         * remains optional and receives no raw Android controller authority.
         */
        fun androidPortBindings(): Map<Rp6Control, ControllerAction> = linkedMapOf(
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
            // Vanilla 1.12 has no INTERACTTARGET action. The managed addon and
            // realm use F7 to choose and open one eligible nearby corpse,
            // chest or ordinary usable object through the normal server path.
            Rp6Control.R1 to ControllerAction.TARGET_PULSE,
            Rp6Control.L1 to ControllerAction.NEARBY_USE,
            Rp6Control.L2 to ControllerAction.SHIFT,
            Rp6Control.R2 to ControllerAction.CTRL,
            Rp6Control.START to ControllerAction.ESCAPE,
            Rp6Control.SELECT to ControllerAction.CONSOLE_RADIAL,
            // R3 selects at the pointer; L3 right-clicks it for precise
            // manual interaction. Auto-run stays on the touch overlay.
            Rp6Control.L3 to ControllerAction.POINTER_RIGHT,
            Rp6Control.R3 to ControllerAction.POINTER_LEFT,
            // RP6 exposes its rear M1/M2 paddles as C/Z. Some units latch
            // those inputs, so this optional preset never assigns them.
            Rp6Control.REAR_LEFT to ControllerAction.DISABLED,
            Rp6Control.REAR_RIGHT to ControllerAction.DISABLED,
        )

        /** Exact uncustomized Console Port map written by the first schema-v11 build. */
        private fun v11AndroidPortBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "KEY_9",
            Rp6Control.L1 to "KEY_0",
            Rp6Control.L2 to "SHIFT",
            Rp6Control.R2 to "CTRL",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "CONSOLE_RADIAL",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "POINTER_LEFT",
            Rp6Control.REAR_LEFT to "DISABLED",
            Rp6Control.REAR_RIGHT to "DISABLED",
        )

        /** Exact uncustomized map shipped immediately before nearby Use/Open. */
        private fun v11AndroidPortDirectBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "TARGET_PULSE",
            Rp6Control.L1 to "USE_LOOT_CLICK",
            Rp6Control.L2 to "SHIFT",
            Rp6Control.R2 to "CTRL",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "CONSOLE_RADIAL",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "POINTER_LEFT",
            Rp6Control.REAR_LEFT to "DISABLED",
            Rp6Control.REAR_RIGHT to "DISABLED",
        )

        /** Exact uncustomized map shipped when nearby Use/Open replaced direct L1 use, before L3 became pointer right. */
        private fun v11AndroidPortNearbyUseBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "TARGET_PULSE",
            Rp6Control.L1 to "NEARBY_USE",
            Rp6Control.L2 to "SHIFT",
            Rp6Control.R2 to "CTRL",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "CONSOLE_RADIAL",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "POINTER_LEFT",
            Rp6Control.REAR_LEFT to "DISABLED",
            Rp6Control.REAR_RIGHT to "DISABLED",
        )

        /** Exact action names stored by schema 7; retired actions are intentionally not enum members. */
        private fun legacyV7ClassicBindingNames(cameraLock: Boolean): Map<Rp6Control, String> =
            linkedMapOf(
                Rp6Control.LEFT_STICK_UP to "MOVE_W",
                Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
                Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
                Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
                Rp6Control.FACE_BOTTOM to "KEY_1",
                Rp6Control.FACE_LEFT to "KEY_2",
                Rp6Control.FACE_TOP to "KEY_3",
                Rp6Control.FACE_RIGHT to "KEY_4",
                Rp6Control.DPAD_DOWN to "KEY_5",
                Rp6Control.DPAD_LEFT to "KEY_6",
                Rp6Control.DPAD_UP to "KEY_7",
                Rp6Control.DPAD_RIGHT to "KEY_8",
                Rp6Control.R1 to "KEY_9",
                Rp6Control.L1 to "KEY_0",
                Rp6Control.L2 to "SHIFT",
                Rp6Control.R2 to "CTRL",
                Rp6Control.START to "RADIAL_MENU",
                Rp6Control.SELECT to "MAP",
                Rp6Control.L3 to "AUTO_RUN",
                Rp6Control.R3 to "POINTER_LEFT",
                Rp6Control.REAR_LEFT to "INTERACT",
                Rp6Control.REAR_RIGHT to if (cameraLock) "CAMERA_LOCK" else "POINTER_RIGHT",
            )

        fun defaultOverlayBindings(): Map<OverlayControl, ControllerAction> = linkedMapOf(
            OverlayControl.MOVE_UP to ControllerAction.MOVE_W,
            OverlayControl.MOVE_DOWN to ControllerAction.MOVE_S,
            OverlayControl.MOVE_LEFT to ControllerAction.STRAFE_Q,
            OverlayControl.MOVE_RIGHT to ControllerAction.STRAFE_E,
            OverlayControl.JUMP to ControllerAction.JUMP,
            OverlayControl.MENU to ControllerAction.ESCAPE,
            OverlayControl.TARGET to ControllerAction.TARGET_PULSE,
            OverlayControl.USE_LOOT to ControllerAction.USE_LOOT_CLICK,
            OverlayControl.AUTO_RUN to ControllerAction.AUTO_RUN,
            OverlayControl.ACTION_1 to ControllerAction.KEY_1,
            OverlayControl.ACTION_2 to ControllerAction.KEY_2,
            OverlayControl.ACTION_3 to ControllerAction.KEY_3,
            OverlayControl.ACTION_4 to ControllerAction.KEY_4,
            OverlayControl.ACTION_5 to ControllerAction.KEY_5,
            OverlayControl.ACTION_6 to ControllerAction.KEY_6,
            OverlayControl.ACTION_7 to ControllerAction.KEY_7,
            OverlayControl.ACTION_8 to ControllerAction.KEY_8,
            OverlayControl.ACTION_9 to ControllerAction.KEY_9,
            OverlayControl.ACTION_10 to ControllerAction.KEY_0,
            OverlayControl.ACTION_11 to ControllerAction.KEY_MINUS,
            OverlayControl.ACTION_12 to ControllerAction.KEY_EQUALS,
        )

        fun defaultLayerFaceBindings(): Map<FaceLayer, Map<Rp6Control, ControllerAction>> = linkedMapOf(
            FaceLayer.L2 to linkedMapOf(
                Rp6Control.FACE_BOTTOM to ControllerAction.KEY_5,
                Rp6Control.FACE_LEFT to ControllerAction.KEY_6,
                Rp6Control.FACE_TOP to ControllerAction.KEY_7,
                Rp6Control.FACE_RIGHT to ControllerAction.KEY_8,
            ),
            FaceLayer.L1 to linkedMapOf(
                Rp6Control.FACE_BOTTOM to ControllerAction.KEY_9,
                Rp6Control.FACE_LEFT to ControllerAction.KEY_0,
                Rp6Control.FACE_TOP to ControllerAction.KEY_MINUS,
                Rp6Control.FACE_RIGHT to ControllerAction.KEY_EQUALS,
            ),
        )

        fun actionFor(profile: InputProfile, layer: FaceLayer, control: Rp6Control): ControllerAction? =
            profile.layerFaceBindings[layer]?.get(control) ?: defaultLayerFaceBindings()[layer]?.get(control)

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
                layerFaceBindings = defaultLayerFaceBindings(),
            )
            ControlScheme.ANDROID_PORT -> base.copy(
                scheme = scheme,
                rp6Bindings = androidPortBindings(),
                overlayBindings = defaultOverlayBindings(),
                // LT/RT are real Shift/Ctrl keys for the addon's four pages;
                // the native face-only layers are deliberately inactive.
                layerFaceBindings = defaultLayerFaceBindings(),
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
            val storedBindings = value.optJSONObject("rp6Bindings")
            val bindings = defaultRp6Bindings().toMutableMap()
            storedBindings?.let { stored ->
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
            val storedScheme = value.optString("scheme")
            val retiredAddonScheme = storedScheme in RETIRED_ADDON_SCHEMES
            val legacyDefault = storedVersion < 5 && storedBindings == null
            val v5CameraLockDefault = storedVersion == 5 && (
                storedBindings == null || storedBindings.matchesRp6(legacyV7ClassicBindingNames(cameraLock = true))
            )
            val v7ClassicDefault = storedVersion in 6..7 && (
                storedBindings == null || storedBindings.matchesRp6(legacyV7ClassicBindingNames(cameraLock = false))
            )
            // v8/v9 could persist the retired add-on layout as CUSTOM after
            // PocketRealmPad was removed. Match the whole generated profile,
            // including its forced RP6 face convention, so a genuine custom
            // map is never rewritten. START may already have received the
            // earlier RADIAL_MENU -> ESCAPE repair.
            val retiredGeneratedProfile = storedVersion in 8..9 &&
                storedBindings.matchesRetiredGeneratedProfile() &&
                value.optString("controllerFamily") == ControllerFamily.RETROID_POCKET_6.name &&
                value.optString("faceButtonLayout") == FaceButtonLayout.RP6_PRINTED.name
            val parsedScheme = storedScheme
                .takeIf { it.isNotBlank() }
                // Profiles saved by 0.5.x store the pre-rename enum name.
                ?.let { stored ->
                    if (stored == "VANILLA_CONSOLE_PORT") {
                        ControlScheme.ANDROID_PORT
                    } else {
                        ControlScheme.values().firstOrNull { it.name == stored }
                    }
                }
                ?: if (
                    legacyDefault || v5CameraLockDefault || v7ClassicDefault ||
                        retiredAddonScheme || retiredGeneratedProfile
                ) {
                    ControlScheme.CLASSIC_CAMERA
                } else ControlScheme.CUSTOM
            val repairedScheme = if (retiredGeneratedProfile) ControlScheme.CLASSIC_CAMERA else parsedScheme
            val v10GeneratedDefault = storedVersion == 10 &&
                repairedScheme == ControlScheme.CLASSIC_CAMERA &&
                storedBindings?.matchesRp6(v10ClassicBindingNames()) == true
            val v10GeneratedOverlay = storedVersion == 10 &&
                repairedScheme == ControlScheme.CLASSIC_CAMERA &&
                value.optJSONObject("overlayBindings").matchesOverlay(v10OverlayBindingNames())
            val v11PreRefinementDefault = storedVersion == 11 &&
                repairedScheme == ControlScheme.CLASSIC_CAMERA &&
                storedBindings?.matchesRp6(v11PreRefinementBindingNames()) == true
            val v11RearButtonDefault = storedVersion == 11 &&
                repairedScheme == ControlScheme.CLASSIC_CAMERA &&
                storedBindings?.matchesRp6(v11RearButtonBindingNames()) == true
            val v11AndroidPortDefault = storedVersion == 11 &&
                repairedScheme == ControlScheme.ANDROID_PORT &&
                (storedBindings?.matchesRp6(v11AndroidPortBindingNames()) == true ||
                    storedBindings?.matchesRp6(v11AndroidPortDirectBindingNames()) == true ||
                    storedBindings?.matchesRp6(v11AndroidPortNearbyUseBindingNames()) == true)
            val repairedOverlay = if (retiredAddonScheme || v10GeneratedOverlay) {
                defaultOverlayBindings()
            } else overlayBindings
            val layerFaceBindings = defaultLayerFaceBindings().mapValues { (_, defaults) -> defaults.toMutableMap() }
                .toMutableMap()
            value.optJSONObject("layerFaceBindings")?.let { layers ->
                FaceLayer.values().forEach { layer ->
                    layers.optJSONObject(layer.name)?.let { storedLayer ->
                        val parsed = layerFaceBindings.getValue(layer)
                        FACE_CONTROLS.forEach { control ->
                            ControllerAction.values().firstOrNull {
                                it.name == storedLayer.optString(control.name, "")
                            }?.let { parsed[control] = it }
                        }
                    }
                }
            }
            // Cluster anchors are parsed per entry and clamped or dropped, so a
            // malformed anchor degrades to the stock alignment instead of
            // discarding the whole stored profile.
            val clusterPositions = linkedMapOf<OverlayClusterId, ClusterAnchor>()
            value.optJSONObject("overlayClusterPositions")?.let { stored ->
                stored.keys().asSequence().sorted().forEach { key ->
                    val cluster = OverlayClusterId.values().firstOrNull { it.name == key }
                        ?: return@forEach
                    val anchor = stored.optJSONObject(key) ?: return@forEach
                    val x = anchor.optDouble("x", Double.NaN).toFloat()
                    val y = anchor.optDouble("y", Double.NaN).toFloat()
                    if (!x.isNaN() && !y.isNaN()) {
                        clusterPositions[cluster] = ClusterAnchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                    }
                }
            }
            return InputProfile(
                version = CURRENT_VERSION,
                deadZone = value.optDouble("deadZone", DEFAULT.deadZone.toDouble()).toFloat(),
                aspectIdentity = value.optString("aspectIdentity", DEFAULT_ASPECT_IDENTITY),
                cameraSensitivity = value.optDouble("cameraSensitivity", 1.0).toFloat(),
                rightStickDeadZone = rightStickDeadZone,
                overlayOpacity = value.optDouble("overlayOpacity", 0.85).toFloat(),
                overlayEnabled = value.optBoolean("overlayEnabled", true),
                overlayMode = if (storedVersion <= 10) {
                    if (value.optBoolean("overlayEnabled", true)) OverlayMode.AUTO else OverlayMode.OFF
                } else {
                    value.optString("overlayMode")
                        .takeIf { it.isNotBlank() }
                        ?.let { stored -> OverlayMode.values().firstOrNull { it.name == stored } }
                        ?: OverlayMode.AUTO
                },
                overlayScale = value.optDouble("overlayScale", 1.0).toFloat(),
                overlayClusterPositions = clusterPositions,
                cameraRegionWidth = value.optDouble("cameraRegionWidth", 0.42).toFloat(),
                touchCameraSensitivity = value.optDouble("touchCameraSensitivity", 0.35).toFloat(),
                invertCameraX = value.optBoolean("invertCameraX", false),
                invertCameraY = value.optBoolean("invertCameraY", false),
                leftTriggerOnThreshold = value.optDouble("leftTriggerOnThreshold", 0.30).toFloat(),
                leftTriggerOffThreshold = value.optDouble("leftTriggerOffThreshold", 0.20).toFloat(),
                rightTriggerOnThreshold = value.optDouble("rightTriggerOnThreshold", 0.40).toFloat(),
                rightTriggerOffThreshold = value.optDouble("rightTriggerOffThreshold", 0.25).toFloat(),
                scheme = repairedScheme,
                controllerFamily = if (retiredGeneratedProfile) {
                    ControllerFamily.AUTO
                } else {
                    value.optString("controllerFamily")
                        .takeIf { it.isNotBlank() }
                        ?.let { stored -> ControllerFamily.values().firstOrNull { it.name == stored } }
                        ?: ControllerFamily.AUTO
                },
                faceButtonLayout = if (retiredGeneratedProfile) {
                    FaceButtonLayout.ANDROID_STANDARD
                } else {
                    value.optString("faceButtonLayout")
                        .takeIf { it.isNotBlank() }
                        ?.let { stored -> FaceButtonLayout.values().firstOrNull { it.name == stored } }
                        ?: FaceButtonLayout.ANDROID_STANDARD
                },
                rp6Bindings = if (v11AndroidPortDefault) {
                    androidPortBindings()
                } else if (
                    legacyDefault ||
                        (repairedScheme == ControlScheme.CLASSIC_CAMERA && v5CameraLockDefault) ||
                        (repairedScheme == ControlScheme.CLASSIC_CAMERA && v7ClassicDefault) ||
                        retiredAddonScheme || retiredGeneratedProfile || v10GeneratedDefault ||
                        v11PreRefinementDefault || v11RearButtonDefault
                ) {
                    defaultRp6Bindings()
                } else bindings,
                overlayBindings = repairedOverlay,
                layerFaceBindings = layerFaceBindings,
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
            val layerFaceBindings = JSONObject()
            FaceLayer.values().forEach { layer ->
                val storedLayer = JSONObject()
                FACE_CONTROLS.forEach { control ->
                    actionFor(profile, layer, control)?.let { storedLayer.put(control.name, it.name) }
                }
                layerFaceBindings.put(layer.name, storedLayer)
            }
            // Sorted key order keeps the canonical re-serialization used by the
            // managed-preset comparison independent of map insertion order.
            val clusterPositions = JSONObject()
            profile.overlayClusterPositions.keys.sortedBy { it.name }.forEach { cluster ->
                val anchor = profile.overlayClusterPositions.getValue(cluster)
                clusterPositions.put(
                    cluster.name,
                    JSONObject()
                        .put("x", anchor.xFraction.toDouble())
                        .put("y", anchor.yFraction.toDouble()),
                )
            }
            return JSONObject()
                .put("version", profile.version)
                .put("deadZone", profile.deadZone.toDouble())
                .put("aspectIdentity", profile.aspectIdentity)
                .put("cameraSensitivity", profile.cameraSensitivity.toDouble())
                .put("rightStickDeadZone", profile.rightStickDeadZone.toDouble())
                .put("overlayOpacity", profile.overlayOpacity.toDouble())
                .put("overlayEnabled", profile.overlayEnabled)
                .put("overlayMode", profile.overlayMode.name)
                .put("overlayScale", profile.overlayScale.toDouble())
                .put("overlayClusterPositions", clusterPositions)
                .put("cameraRegionWidth", profile.cameraRegionWidth.toDouble())
                .put("touchCameraSensitivity", profile.touchCameraSensitivity.toDouble())
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
                .put("layerFaceBindings", layerFaceBindings)
        }

        private fun JSONObject.matchesRp6(expected: Map<Rp6Control, String>): Boolean =
            expected.all { (control, action) -> optString(control.name, "") == action }

        private fun JSONObject?.matchesOverlay(expected: Map<OverlayControl, String>): Boolean =
            this != null && expected.all { (control, action) -> optString(control.name, "") == action }

        /** Exact generated schema-v10 map; never use this to rewrite a partial/custom map. */
        private fun v10ClassicBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "KEY_9",
            Rp6Control.L1 to "KEY_0",
            Rp6Control.L2 to "SHIFT",
            Rp6Control.R2 to "POINTER_RIGHT",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "MAP",
            Rp6Control.L3 to "TARGET",
            Rp6Control.R3 to "JUMP",
            Rp6Control.REAR_LEFT to "AUTO_RUN",
            Rp6Control.REAR_RIGHT to "POINTER_LEFT",
        )

        private fun v10OverlayBindingNames(): Map<OverlayControl, String> = linkedMapOf(
            OverlayControl.MOVE_UP to "MOVE_W",
            OverlayControl.MOVE_DOWN to "MOVE_S",
            OverlayControl.MOVE_LEFT to "STRAFE_Q",
            OverlayControl.MOVE_RIGHT to "STRAFE_E",
            OverlayControl.JUMP to "JUMP",
            OverlayControl.MENU to "ESCAPE",
            OverlayControl.TARGET to "TARGET",
            OverlayControl.USE_LOOT to "POINTER_RIGHT",
            OverlayControl.AUTO_RUN to "AUTO_RUN",
            OverlayControl.ACTION_1 to "KEY_1",
            OverlayControl.ACTION_2 to "KEY_2",
            OverlayControl.ACTION_3 to "KEY_3",
            OverlayControl.ACTION_4 to "KEY_4",
            OverlayControl.ACTION_5 to "KEY_5",
            OverlayControl.ACTION_6 to "KEY_6",
            OverlayControl.ACTION_7 to "KEY_7",
            OverlayControl.ACTION_8 to "KEY_8",
        )

        /** Exact generated default written by the first schema-v11 development build. */
        private fun v11PreRefinementBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "KEY_5",
            Rp6Control.DPAD_LEFT to "KEY_6",
            Rp6Control.DPAD_UP to "KEY_7",
            Rp6Control.DPAD_RIGHT to "KEY_8",
            Rp6Control.R1 to "TARGET_PULSE",
            Rp6Control.L1 to "LAYER_L1",
            Rp6Control.L2 to "LAYER_L2",
            Rp6Control.R2 to "USE_LOOT_CLICK",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "MAP",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "JUMP",
            Rp6Control.REAR_LEFT to "CAMERA_LOCK",
            Rp6Control.REAR_RIGHT to "POINTER_LEFT",
        )

        /** Exact schema-v11 default immediately before unreliable rear buttons were retired. */
        private fun v11RearButtonBindingNames(): Map<Rp6Control, String> = linkedMapOf(
            Rp6Control.LEFT_STICK_UP to "MOVE_W",
            Rp6Control.LEFT_STICK_DOWN to "MOVE_S",
            Rp6Control.LEFT_STICK_LEFT to "STRAFE_Q",
            Rp6Control.LEFT_STICK_RIGHT to "STRAFE_E",
            Rp6Control.FACE_BOTTOM to "KEY_1",
            Rp6Control.FACE_LEFT to "KEY_2",
            Rp6Control.FACE_TOP to "KEY_3",
            Rp6Control.FACE_RIGHT to "KEY_4",
            Rp6Control.DPAD_DOWN to "F1",
            Rp6Control.DPAD_LEFT to "INVENTORY",
            Rp6Control.DPAD_UP to "KEY_G",
            Rp6Control.DPAD_RIGHT to "KEY_L",
            Rp6Control.R1 to "TARGET_PULSE",
            Rp6Control.L1 to "LAYER_L1",
            Rp6Control.L2 to "LAYER_L2",
            Rp6Control.R2 to "USE_LOOT_CLICK",
            Rp6Control.START to "ESCAPE",
            Rp6Control.SELECT to "MAP",
            Rp6Control.L3 to "AUTO_RUN",
            Rp6Control.R3 to "JUMP",
            Rp6Control.REAR_LEFT to "CAMERA_LOCK",
            Rp6Control.REAR_RIGHT to "POINTER_LEFT",
        )

        private fun JSONObject?.matchesRetiredGeneratedProfile(): Boolean {
            if (this == null) return false
            val expected = legacyV7ClassicBindingNames(cameraLock = false)
            return Rp6Control.values().all { control ->
                val actual = optString(control.name, "")
                if (control == Rp6Control.START) {
                    actual == "RADIAL_MENU" || actual == ControllerAction.ESCAPE.name
                } else {
                    actual == expected.getValue(control)
                }
            }
        }

        private val RETIRED_ADDON_SCHEMES = setOf(
            "POCKET_REALM_PAD",
            "POCKET_REALM_PAD_CAMERA",
        )

        private val FACE_CONTROLS = listOf(
            Rp6Control.FACE_BOTTOM,
            Rp6Control.FACE_LEFT,
            Rp6Control.FACE_TOP,
            Rp6Control.FACE_RIGHT,
        )

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
        val stored = storedProfile()
        return if (stored != null && stored.aspectIdentity == aspectIdentity) {
            LoadResult(stored, resetForAspect = false)
        } else if (stored != null) {
            LoadResult(InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity), resetForAspect = true)
        } else {
            LoadResult(InputProfile.DEFAULT.copy(aspectIdentity = aspectIdentity), resetForAspect = false)
        }
    }

    /** Current persisted profile without applying an aspect-reset policy. */
    fun loadStoredOrDefault(): InputProfile = storedProfile() ?: InputProfile.DEFAULT

    fun hasManagedAndroidPort(): Boolean =
        preferences.contains(VANILLA_CONSOLE_APPLIED_KEY) ||
            preferences.contains(VANILLA_CONSOLE_PRIOR_KEY)

    /**
     * Select the optional Android Port preset after its addon installs.
     * The prior and exact applied profiles share the same committed preferences
     * file, allowing removal to restore only an untouched managed preset.
     */
    fun enableAndroidPort(): InputProfile {
        val current = loadStoredOrDefault()
        val currentRaw = InputProfile.toJson(current).toString()
        val priorApplied = preferences.getString(VANILLA_CONSOLE_APPLIED_KEY, null)
        val canonicalPriorApplied = canonicalProfileJson(priorApplied)
        if (priorApplied != null && currentRaw != canonicalPriorApplied) {
            // The player customized controls after installation. Updating the
            // addon must not silently overwrite that newer choice.
            return current
        }
        val desired = InputProfile.profileForScheme(
            ControlScheme.ANDROID_PORT,
            current.aspectIdentity,
            current,
        )
        val desiredRaw = InputProfile.toJson(desired).toString()
        val edit = preferences.edit()
            .putString(KEY, desiredRaw)
            .putString(VANILLA_CONSOLE_APPLIED_KEY, desiredRaw)
        if (preferences.getString(VANILLA_CONSOLE_PRIOR_KEY, null) == null) {
            edit.putString(VANILLA_CONSOLE_PRIOR_KEY, currentRaw)
        }
        check(edit.commit()) { "Android Port profile could not be persisted" }
        return desired
    }

    /** Restore the pre-install profile only when the managed preset is intact. */
    fun disableAndroidPort(): InputProfile {
        val current = loadStoredOrDefault()
        val currentRaw = InputProfile.toJson(current).toString()
        val appliedRaw = canonicalProfileJson(preferences.getString(VANILLA_CONSOLE_APPLIED_KEY, null))
        val prior = preferences.getString(VANILLA_CONSOLE_PRIOR_KEY, null)
            ?.let { raw -> runCatching { InputProfile.fromJson(JSONObject(raw)) }.getOrNull() }
        val restored = if (appliedRaw != null && currentRaw == appliedRaw && prior != null) prior else current
        check(preferences.edit()
            .putString(KEY, InputProfile.toJson(restored).toString())
            .remove(VANILLA_CONSOLE_APPLIED_KEY)
            .remove(VANILLA_CONSOLE_PRIOR_KEY)
            .commit()) { "Android Port profile removal could not be persisted" }
        return restored
    }

    private fun storedProfile(): InputProfile? = (listOf(KEY) + LEGACY_KEYS).asSequence()
            .mapNotNull { preferences.getString(it, null) }
            .mapNotNull { raw ->
                runCatching { InputProfile.fromJson(JSONObject(raw)) }.getOrNull()
            }
            .firstOrNull()

    private fun canonicalProfileJson(raw: String?): String? = raw?.let { stored ->
        runCatching { InputProfile.toJson(InputProfile.fromJson(JSONObject(stored))).toString() }.getOrNull()
    }

    fun save(profile: InputProfile) {
        check(preferences.edit().putString(KEY, InputProfile.toJson(profile).toString()).commit()) {
            "input profile could not be persisted"
        }
    }

    private companion object {
        const val NAME = "pocket_input_profile"
        const val KEY = "profile_v12"
        const val VANILLA_CONSOLE_PRIOR_KEY = "vanilla_console_port_prior_v1"
        const val VANILLA_CONSOLE_APPLIED_KEY = "vanilla_console_port_applied_v1"
        val LEGACY_KEYS = listOf(
            "profile_v11", "profile_v10", "profile_v9", "profile_v8", "profile_v7", "profile_v6", "profile_v5", "profile_v4", "profile_v3", "profile_v2",
        )
    }
}
