package com.pocketrealm.ingame

/**
 * Stock 1.12.1 settings-panel scope. One hub row per section except bindings,
 * which has its own editor backed by [com.pocketrealm.client.WowVanillaBindingCatalog].
 */
enum class WowSettingSection(val hubLabel: String, val hubDescription: String) {
    GRAPHICS("Graphics", "View distance and display options"),
    SOUND("Sound", "Volumes and sound effects"),
    INTERFACE("Interface", "Controls, names, camera, and help"),
    INTERFACE_ADVANCED("Advanced Interface", "Action bars, chat, raid, and combat text"),
}

/** The stock control type rendered by the editor. */
enum class WowSettingControl { TOGGLE, SLIDER, CHOICE }

/**
 * Where the setting's value lives. FUNCTION entries have no verified file
 * persistence and render visible-but-disabled ("Change this in the game's
 * own menus") per the plan's Section 5.5 ladder.
 */
enum class WowSettingBackend { CVAR, UVAR, FUNCTION }

/**
 * How each catalog fact was verified — the provenance split pinned by
 * docs/INGAME_SETTINGS_GROUND_TRUTH.md. FRAMEXML_PIN facts come from the
 * MOUZU mirror of Blizzard's 1.12.1 FrameXML (commit 776d64e); DEVICE_CAPTURE
 * facts come from the live build-5875 WTF tree pulled from the Retroid
 * Pocket 6 on 2026-08-16.
 */
enum class WowSettingProvenance { FRAMEXML_PIN, DEVICE_CAPTURE }

enum class WowSettingCapabilityRequirement { PIXEL_SHADERS, GLOW_EFFECTS }

/** One selectable value of a CHOICE setting; [stored] is the literal file value. */
data class WowSettingChoice(val id: String, val label: String, val stored: String)

/**
 * A dependency on another catalog entry: the row is disabled with a reason
 * unless the referenced setting's live value differs from [notValue]
 * (for uvar/CVar toggles the natural gate is value "1").
 */
data class WowSettingRequirement(val id: String, val notValue: String)

/** Scalar forms observed in build 5875's account SavedVariables.lua. */
enum class WowUvarValueForm { STRING, NUMBER }

data class WowSettingDefinition(
    val id: String,
    val section: WowSettingSection,
    /** Sub-tab inside the section screen. */
    val group: String,
    val label: String,
    val control: WowSettingControl,
    val backend: WowSettingBackend,
    /** CVar name, uvar name, or function id. */
    val key: String,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val choices: List<WowSettingChoice>? = null,
    /** True when a checked UI state maps to stored value "0". */
    val inverse: Boolean = false,
    val requires: List<WowSettingRequirement> = emptyList(),
    /** Companion CVar written as source value x multiplier (e.g. camera pitch). */
    val pairedWrites: List<Pair<String, Float>> = emptyList(),
    val capability: WowSettingCapabilityRequirement? = null,
    /**
     * Renderer-conditional lock: the row is user-editable only under the
     * DXVK lane; the Legacy GL lanes (gladio/virgl) keep it fixed. The
     * editor resolves this against the effective renderer selection.
     */
    val legacyRendererOnly: Boolean = false,
    /** Verified 1.12.1 default; null = the client default is unknown ("Default" row state). */
    val defaultValue: String? = null,
    val provenance: WowSettingProvenance,
    val defaultProvenance: WowSettingProvenance? = null,
    val uvarValueForm: WowUvarValueForm = WowUvarValueForm.STRING,
    /** Non-null renders the row disabled with this reason (managed/fixed/function rows). */
    val fixedReason: String? = null,
)
