package com.pocketrealm.client

/**
 * The sections exposed by the stock WoW 1.12.1 key-binding screen.
 *
 * Action bars are intentionally separate: a controller editor can present a compact main-bar
 * list without hiding the self-cast, stance, bonus, or four multi-bar namespaces.
 */
enum class WowBindingCategory(val displayName: String) {
    MOVEMENT("Movement"),
    CHAT("Chat"),
    MAIN_ACTION_BAR("Main action bar"),
    SELF_ACTION_BAR("Self-cast action bar"),
    SHAPESHIFT_BAR("Shapeshift bar"),
    BONUS_ACTION_BAR("Bonus action bar"),
    ACTION_PAGES("Action pages"),
    TARGETING("Targeting"),
    CHARACTER_AND_UI("Character and interface"),
    BAGS("Bags"),
    MISCELLANEOUS("Miscellaneous"),
    CAMERA("Camera"),
    MULTI_ACTION_BARS("Additional action bars"),
    RAID_MARKERS("Raid markers"),
    INTERNAL_POINTER("Internal pointer actions"),
}

/** How the original FrameXML binding consumes an assigned input. */
enum class WowBindingSemantics {
    /** A normal assignable binding. Some entries use both down and up in FrameXML. */
    KEY_LIKE,

    /** A hidden stock binding which models a held mouse-button gesture. */
    INTERNAL_POINTER_HOLD,
}

/** An immutable description of one stock WoW 1.12.1 Binding ID. */
data class WowBindingDefinition(
    val id: String,
    val label: String,
    val category: WowBindingCategory,
    val description: String,
    val advanced: Boolean,
    val semantics: WowBindingSemantics,
)

/**
 * Versioned manifest of supported stock key-binding IDs from WoW 1.12.1 (build 5875).
 *
 * Primary source: Blizzard's 1.12.1 `FrameXML/Bindings.xml`, preserved at
 * https://github.com/MOUZU/Blizzard-WoW-Interface/blob/776d64ecf708540969e34df9680ffdacb3e8b555/1.12.1/FrameXML/Bindings.xml
 *
 * The public list excludes commented-out MOVEVIEW entries, hidden debug bindings, Mac-only
 * iTunes bindings, and hidden mouse gestures. The three active hidden mouse gestures are retained
 * in [internalPointer] for runtime integration, but must not appear in the normal action picker.
 * In particular, 1.12.1 defines neither INTERACTTARGET nor LOOTALL; do not manufacture modern
 * binding IDs for this catalog.
 */
object WowVanillaBindingCatalog {
    const val CATALOG_VERSION: Int = 2
    const val WOW_CLIENT_VERSION: String = "1.12.1"
    const val WOW_CLIENT_BUILD: Int = 5875
    const val SOURCE_COMMIT: String = "776d64ecf708540969e34df9680ffdacb3e8b555"
    /** SHA-256 of the 211 public IDs joined in source order with `\n`. */
    const val PUBLIC_ID_ORDER_SHA256: String =
        "bd9a9baa0b636fc33bbec0cb3f032c8ebeb47ea1513d7a07671e7c31b99052e5"
    /** SHA-256 of the public IDs followed by the three supported hidden pointer IDs. */
    const val SUPPORTED_ID_ORDER_SHA256: String =
        "a58f4e31ad0295fb69be29bd4ddc0f96d827299bdda9a7f85e123d52c66c631a"

    private fun key(
        id: String,
        label: String,
        category: WowBindingCategory,
        description: String,
        advanced: Boolean = true,
    ) = WowBindingDefinition(
        id = id,
        label = label,
        category = category,
        description = description,
        advanced = advanced,
        semantics = WowBindingSemantics.KEY_LIKE,
    )

    private fun numbered(
        idPrefix: String,
        labelPrefix: String,
        descriptionPrefix: String,
        count: Int,
        category: WowBindingCategory,
        advanced: Boolean,
    ): List<WowBindingDefinition> = (1..count).map { number ->
        key(
            id = "$idPrefix$number",
            label = "$labelPrefix $number",
            category = category,
            description = "$descriptionPrefix $number.",
            advanced = advanced,
        )
    }


    // ------------------------------------------------------------- v2 defaults

    /**
     * The stock default key slots for one command, ordered (primary first).
     */
    data class WowBindingDefaultKeys(val slots: List<String>) {
        constructor(vararg keys: String) : this(keys.toList())

        val primary: String? get() = slots.getOrNull(0)
        val secondary: String? get() = slots.getOrNull(1)
    }

    /**
     * Stock default keys per command, captured from the live build-5875
     * account `bindings-cache.wtf` on the Retroid Pocket 6 (2026-08-16)
     * with the controller overlay's claims filtered out: targets beginning
     * `VCP_`, the keys F7/F8/F12, and the legacy surrogate pairs F6/
     * TARGETNEARESTENEMY and F9/TOGGLEAUTORUN. ACTIONBUTTON1..8 are
     * reconstructed (the pre-0.6.0 VanillaConsolePort addon overlay claimed the digit
     * keys in the capture); the 9/0/-/= row continuing the pattern and
     * LegacyControllerBindingRepair's stock map corroborate them. Provenance
     * is DEVICE_CAPTURE, not the FrameXML pin - `Bindings.xml` carries no
     * default keys (docs/INGAME_SETTINGS_GROUND_TRUTH.md).
     */
    val stockKeyBindings: Map<String, WowBindingDefaultKeys> = linkedMapOf(
            "MOVEANDSTEER" to WowBindingDefaultKeys("BUTTON3"),
            "MOVEFORWARD" to WowBindingDefaultKeys("W", "UP"),
            "MOVEBACKWARD" to WowBindingDefaultKeys("S", "DOWN"),
            "TURNLEFT" to WowBindingDefaultKeys("A", "LEFT"),
            "TURNRIGHT" to WowBindingDefaultKeys("D", "RIGHT"),
            "STRAFELEFT" to WowBindingDefaultKeys("Q"),
            "STRAFERIGHT" to WowBindingDefaultKeys("E"),
            "JUMP" to WowBindingDefaultKeys("SPACE", "NUMPAD0"),
            "SITORSTAND" to WowBindingDefaultKeys("X"),
            "TOGGLESHEATH" to WowBindingDefaultKeys("Z"),
            "TOGGLEAUTORUN" to WowBindingDefaultKeys("NUMLOCK", "BUTTON4"),
            "PITCHUP" to WowBindingDefaultKeys("INSERT"),
            "PITCHDOWN" to WowBindingDefaultKeys("DELETE"),
            "TOGGLERUN" to WowBindingDefaultKeys("NUMPADDIVIDE"),
            "OPENCHAT" to WowBindingDefaultKeys("ENTER"),
            "OPENCHATSLASH" to WowBindingDefaultKeys("/"),
            "CHATPAGEUP" to WowBindingDefaultKeys("PAGEUP"),
            "CHATPAGEDOWN" to WowBindingDefaultKeys("PAGEDOWN"),
            "CHATBOTTOM" to WowBindingDefaultKeys("SHIFT-PAGEDOWN"),
            "COMBATLOGPAGEUP" to WowBindingDefaultKeys("CTRL-PAGEUP"),
            "COMBATLOGPAGEDOWN" to WowBindingDefaultKeys("CTRL-PAGEDOWN"),
            "COMBATLOGBOTTOM" to WowBindingDefaultKeys("CTRL-SHIFT-PAGEDOWN"),
            "REPLY" to WowBindingDefaultKeys("R"),
            "REPLY2" to WowBindingDefaultKeys("SHIFT-R"),
            "ACTIONBUTTON9" to WowBindingDefaultKeys("9"),
            "ACTIONBUTTON10" to WowBindingDefaultKeys("0"),
            "ACTIONBUTTON11" to WowBindingDefaultKeys("-"),
            "ACTIONBUTTON12" to WowBindingDefaultKeys("="),
            "SELFACTIONBUTTON1" to WowBindingDefaultKeys("ALT-1"),
            "SELFACTIONBUTTON2" to WowBindingDefaultKeys("ALT-2"),
            "SELFACTIONBUTTON3" to WowBindingDefaultKeys("ALT-3"),
            "SELFACTIONBUTTON4" to WowBindingDefaultKeys("ALT-4"),
            "SELFACTIONBUTTON5" to WowBindingDefaultKeys("ALT-5"),
            "SELFACTIONBUTTON6" to WowBindingDefaultKeys("ALT-6"),
            "SELFACTIONBUTTON7" to WowBindingDefaultKeys("ALT-7"),
            "SELFACTIONBUTTON8" to WowBindingDefaultKeys("ALT-8"),
            "SELFACTIONBUTTON9" to WowBindingDefaultKeys("ALT-9"),
            "SELFACTIONBUTTON10" to WowBindingDefaultKeys("ALT-0"),
            "SELFACTIONBUTTON11" to WowBindingDefaultKeys("ALT--"),
            "SELFACTIONBUTTON12" to WowBindingDefaultKeys("ALT-="),
            "SHAPESHIFTBUTTON1" to WowBindingDefaultKeys("CTRL-F1"),
            "SHAPESHIFTBUTTON2" to WowBindingDefaultKeys("CTRL-F2"),
            "SHAPESHIFTBUTTON3" to WowBindingDefaultKeys("CTRL-F3"),
            "SHAPESHIFTBUTTON4" to WowBindingDefaultKeys("CTRL-F4"),
            "SHAPESHIFTBUTTON5" to WowBindingDefaultKeys("CTRL-F5"),
            "SHAPESHIFTBUTTON6" to WowBindingDefaultKeys("CTRL-F6"),
            "SHAPESHIFTBUTTON7" to WowBindingDefaultKeys("CTRL-F7"),
            "SHAPESHIFTBUTTON8" to WowBindingDefaultKeys("CTRL-F8"),
            "SHAPESHIFTBUTTON9" to WowBindingDefaultKeys("CTRL-F9"),
            "SHAPESHIFTBUTTON10" to WowBindingDefaultKeys("CTRL-F10"),
            "BONUSACTIONBUTTON9" to WowBindingDefaultKeys("CTRL-9"),
            "BONUSACTIONBUTTON10" to WowBindingDefaultKeys("CTRL-0"),
            "PREVIOUSACTIONPAGE" to WowBindingDefaultKeys("SHIFT-UP", "SHIFT-MOUSEWHEELUP"),
            "NEXTACTIONPAGE" to WowBindingDefaultKeys("SHIFT-DOWN", "SHIFT-MOUSEWHEELDOWN"),
            "TARGETPREVIOUSENEMY" to WowBindingDefaultKeys("SHIFT-TAB"),
            "TARGETNEARESTFRIEND" to WowBindingDefaultKeys("CTRL-TAB"),
            "TARGETPREVIOUSFRIEND" to WowBindingDefaultKeys("CTRL-SHIFT-TAB"),
            "TARGETSELF" to WowBindingDefaultKeys("F1"),
            "TARGETPET" to WowBindingDefaultKeys("SHIFT-F1"),
            "TARGETPARTYMEMBER1" to WowBindingDefaultKeys("F2"),
            "TARGETPARTYMEMBER2" to WowBindingDefaultKeys("F3"),
            "TARGETPARTYMEMBER3" to WowBindingDefaultKeys("F4"),
            "TARGETPARTYMEMBER4" to WowBindingDefaultKeys("F5"),
            "TARGETPARTYPET1" to WowBindingDefaultKeys("SHIFT-F2"),
            "TARGETPARTYPET2" to WowBindingDefaultKeys("SHIFT-F3"),
            "TARGETPARTYPET3" to WowBindingDefaultKeys("SHIFT-F4"),
            "TARGETPARTYPET4" to WowBindingDefaultKeys("SHIFT-F5"),
            "TARGETLASTHOSTILE" to WowBindingDefaultKeys("G"),
            "ASSISTTARGET" to WowBindingDefaultKeys("F"),
            "NAMEPLATES" to WowBindingDefaultKeys("V"),
            "FRIENDNAMEPLATES" to WowBindingDefaultKeys("SHIFT-V"),
            "ALLNAMEPLATES" to WowBindingDefaultKeys("CTRL-V"),
            "ATTACKTARGET" to WowBindingDefaultKeys("T"),
            "PETATTACK" to WowBindingDefaultKeys("SHIFT-T"),
            "TOGGLECHARACTER0" to WowBindingDefaultKeys("C"),
            "OPENALLBAGS" to WowBindingDefaultKeys("SHIFT-B"),
            "TOGGLESPELLBOOK" to WowBindingDefaultKeys("P"),
            "TOGGLEPETBOOK" to WowBindingDefaultKeys("SHIFT-I"),
            "TOGGLECHARACTER3" to WowBindingDefaultKeys("SHIFT-P"),
            "TOGGLEWORLDSTATESCORES" to WowBindingDefaultKeys("SHIFT-SPACE"),
            "TOGGLEBATTLEFIELDMINIMAP" to WowBindingDefaultKeys("SHIFT-M"),
            "TOGGLECHARACTER4" to WowBindingDefaultKeys("H"),
            "TOGGLETALENTS" to WowBindingDefaultKeys("N"),
            "TOGGLECHARACTER2" to WowBindingDefaultKeys("U"),
            "TOGGLECHARACTER1" to WowBindingDefaultKeys("K"),
            "TOGGLESOCIAL" to WowBindingDefaultKeys("O"),
            "TOGGLEQUESTLOG" to WowBindingDefaultKeys("L"),
            "TOGGLECOMBATLOG" to WowBindingDefaultKeys("SHIFT-C"),
            "TOGGLEGAMEMENU" to WowBindingDefaultKeys("ESCAPE"),
            "TOGGLEWORLDMAP" to WowBindingDefaultKeys("M"),
            "SCREENSHOT" to WowBindingDefaultKeys("PRINTSCREEN"),
            "MINIMAPZOOMIN" to WowBindingDefaultKeys("NUMPADPLUS"),
            "MINIMAPZOOMOUT" to WowBindingDefaultKeys("NUMPADMINUS"),
            "TOGGLEMUSIC" to WowBindingDefaultKeys("CTRL-M"),
            "TOGGLESOUND" to WowBindingDefaultKeys("CTRL-S"),
            "MASTERVOLUMEUP" to WowBindingDefaultKeys("CTRL-="),
            "MASTERVOLUMEDOWN" to WowBindingDefaultKeys("CTRL--"),
            "TOGGLEUI" to WowBindingDefaultKeys("ALT-Z"),
            "CAMERAZOOMIN" to WowBindingDefaultKeys("MOUSEWHEELUP"),
            "CAMERAZOOMOUT" to WowBindingDefaultKeys("MOUSEWHEELDOWN"),
            "NEXTVIEW" to WowBindingDefaultKeys("END"),
            "PREVVIEW" to WowBindingDefaultKeys("HOME"),
            "TURNORACTION" to WowBindingDefaultKeys("BUTTON2"),
            "CAMERAORSELECTORMOVE" to WowBindingDefaultKeys("BUTTON1"),
            "CAMERAORSELECTORMOVESTICKY" to WowBindingDefaultKeys("CTRL-BUTTON1"),
            "TOGGLEBACKPACK" to WowBindingDefaultKeys("B"),
            "TARGETNEARESTENEMY" to WowBindingDefaultKeys("TAB"),
            "TOGGLEBAG4" to WowBindingDefaultKeys("F11"),
            "ACTIONBUTTON1" to WowBindingDefaultKeys("1"),
            "ACTIONBUTTON2" to WowBindingDefaultKeys("2"),
            "ACTIONBUTTON3" to WowBindingDefaultKeys("3"),
            "ACTIONBUTTON4" to WowBindingDefaultKeys("4"),
            "ACTIONBUTTON5" to WowBindingDefaultKeys("5"),
            "ACTIONBUTTON6" to WowBindingDefaultKeys("6"),
            "ACTIONBUTTON7" to WowBindingDefaultKeys("7"),
            "ACTIONBUTTON8" to WowBindingDefaultKeys("8"),
    )

    /** SHA-256 over `COMMAND=KEY1,KEY2` lines in declaration order. */
    const val DEFAULTS_CAPTURE_SHA256: String =
        "30c92e4011fab164b7ac1f31e2176cd22882572c25f375442cf4643c5a7564f1"
    const val DEFAULTS_COUNT: Int = 116

    fun stockDefaultKeys(commandId: String): WowBindingDefaultKeys? =
        stockKeyBindings[commandId]

    /**
     * Keys the controller overlay owns and the editor must never unbind,
     * offer as assignment targets, or reset: the pre-0.6.0 VanillaConsolePort binding set
     * (digits 1-0 plus SHIFT-/CTRL-/CTRL-SHIFT- chords, F7, F8, F12) and the
     * legacy surrogates F6/F9, which the repair re-appends at every launch.
     */
    val reservedKeys: Set<String> = buildSet {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { digit ->
            add(digit); add("SHIFT-$digit"); add("CTRL-$digit"); add("CTRL-SHIFT-$digit")
        }
        addAll(listOf("F7", "F8", "F12", "F6", "F9"))
    }

    /** Active, non-debug, non-platform-specific bindings in stock source order. */
    val userFacing: List<WowBindingDefinition> = buildList {
        val movement = WowBindingCategory.MOVEMENT
        add(key("MOVEANDSTEER", "Move and steer", movement,
            "Move toward the pointer while steering with both stock mouse gestures."))
        add(key("MOVEFORWARD", "Move forward", movement, "Move forward while held.", false))
        add(key("MOVEBACKWARD", "Move backward", movement, "Move backward while held.", false))
        add(key("TURNLEFT", "Turn left", movement, "Turn left while held."))
        add(key("TURNRIGHT", "Turn right", movement, "Turn right while held."))
        add(key("STRAFELEFT", "Strafe left", movement, "Strafe left while held.", false))
        add(key("STRAFERIGHT", "Strafe right", movement, "Strafe right while held.", false))
        add(key("JUMP", "Jump", movement, "Jump once.", false))
        add(key("SITORSTAND", "Sit or stand", movement, "Toggle between sitting and standing."))
        add(key("TOGGLESHEATH", "Sheathe weapons", movement, "Sheathe or draw weapons."))
        add(key("TOGGLEAUTORUN", "Auto run", movement, "Toggle continuous forward movement.", false))
        add(key("PITCHUP", "Pitch up", movement, "Pitch the character upward while held."))
        add(key("PITCHDOWN", "Pitch down", movement, "Pitch the character downward while held."))
        add(key("TOGGLERUN", "Toggle run or walk", movement, "Toggle between running and walking."))
        add(key("FOLLOWTARGET", "Follow target", movement, "Begin following the current target."))

        val chat = WowBindingCategory.CHAT
        add(key("OPENCHAT", "Open chat", chat, "Open chat with an empty message.", false))
        add(key("OPENCHATSLASH", "Open slash command", chat, "Open chat starting with a slash.", false))
        add(key("CHATPAGEUP", "Chat page up", chat, "Scroll the chat history upward."))
        add(key("CHATPAGEDOWN", "Chat page down", chat, "Scroll the chat history downward."))
        add(key("CHATBOTTOM", "Chat bottom", chat, "Scroll chat to its newest message."))
        add(key("REPLY", "Reply", chat, "Reply to the most recent whisper.", false))
        add(key("REPLY2", "Previous reply", chat, "Cycle backward through whisper senders."))
        add(key("COMBATLOGPAGEUP", "Combat log page up", chat, "Scroll the combat log upward."))
        add(key("COMBATLOGPAGEDOWN", "Combat log page down", chat, "Scroll the combat log downward."))
        add(key("COMBATLOGBOTTOM", "Combat log bottom", chat, "Scroll the combat log to its newest entry."))
        add(key("TOGGLECOMBATLOG", "Toggle combat log", chat, "Show or hide the combat log."))

        addAll(numbered("ACTIONBUTTON", "Action", "Use main action-bar slot", 12,
            WowBindingCategory.MAIN_ACTION_BAR, advanced = false))
        addAll(numbered("SELFACTIONBUTTON", "Self-cast action", "Use main action-bar slot on yourself", 12,
            WowBindingCategory.SELF_ACTION_BAR, advanced = true))
        addAll(numbered("SHAPESHIFTBUTTON", "Shapeshift form", "Select shapeshift form", 10,
            WowBindingCategory.SHAPESHIFT_BAR, advanced = true))
        addAll(numbered("BONUSACTIONBUTTON", "Bonus action", "Use bonus action-bar slot", 10,
            WowBindingCategory.BONUS_ACTION_BAR, advanced = true))

        val pages = WowBindingCategory.ACTION_PAGES
        addAll(numbered("ACTIONPAGE", "Action page", "Switch directly to action-bar page", 6,
            pages, advanced = true))
        add(key("PREVIOUSACTIONPAGE", "Previous action page", pages,
            "Switch to the previous action-bar page.", false))
        add(key("NEXTACTIONPAGE", "Next action page", pages,
            "Switch to the next action-bar page.", false))
        add(key("TOGGLEACTIONBARLOCK", "Toggle action-bar lock", pages,
            "Lock or unlock action buttons against dragging."))
        add(key("TOGGLEAUTOSELFCAST", "Toggle automatic self-cast", pages,
            "Toggle automatic self-casting for eligible actions."))

        val target = WowBindingCategory.TARGETING
        add(key("TARGETNEARESTENEMY", "Target nearest enemy", target,
            "Select the next nearby living enemy.", false))
        add(key("TARGETPREVIOUSENEMY", "Target previous enemy", target,
            "Cycle backward through nearby living enemies."))
        add(key("TARGETNEARESTFRIEND", "Target nearest friend", target,
            "Select the next nearby friendly unit."))
        add(key("TARGETPREVIOUSFRIEND", "Target previous friend", target,
            "Cycle backward through nearby friendly units."))
        add(key("TARGETSELF", "Target self or pet", target,
            "Target yourself, or your pet when you are already targeted.", false))
        addAll(numbered("TARGETPARTYMEMBER", "Target party member", "Target party member (or their pet on the second press)", 4,
            target, advanced = true))
        add(key("TARGETPET", "Target pet", target, "Target your pet."))
        addAll(numbered("TARGETPARTYPET", "Target party pet", "Target the pet of party member", 4,
            target, advanced = true))
        add(key("TARGETLASTHOSTILE", "Target last hostile", target,
            "Return to the most recently targeted hostile unit.", false))
        add(key("ASSISTTARGET", "Assist target", target,
            "Target the unit that your current target is targeting.", false))
        add(key("NAMEPLATES", "Enemy nameplates", target, "Toggle enemy nameplates."))
        add(key("FRIENDNAMEPLATES", "Friendly nameplates", target, "Toggle friendly nameplates."))
        add(key("ALLNAMEPLATES", "All nameplates", target, "Toggle friendly and enemy nameplates together."))
        add(key("ATTACKTARGET", "Attack target", target, "Begin attacking the current target.", false))
        add(key("PETATTACK", "Pet attack", target, "Order your pet to attack the current target."))

        val ui = WowBindingCategory.CHARACTER_AND_UI
        add(key("TOGGLECHARACTER0", "Character", ui, "Open or close the character equipment panel.", false))

        val bags = WowBindingCategory.BAGS
        add(key("TOGGLEBACKPACK", "Backpack", bags, "Open or close the backpack.", false))
        addAll(numbered("TOGGLEBAG", "Bag", "Open or close equipped bag", 4, bags, advanced = true))
        add(key("OPENALLBAGS", "Open all bags", bags, "Open every equipped bag.", false))
        add(key("TOGGLEKEYRING", "Key ring", bags, "Open or close the key ring."))

        add(key("TOGGLESPELLBOOK", "Spellbook", ui, "Open or close the spellbook.", false))
        add(key("TOGGLEPETBOOK", "Pet spellbook", ui, "Open or close the pet spellbook."))
        add(key("TOGGLETALENTS", "Talents", ui, "Open or close the talent panel.", false))
        add(key("TOGGLECHARACTER4", "Honor", ui, "Open or close the honor panel."))
        add(key("TOGGLECHARACTER3", "Pet character", ui, "Open or close the pet character panel."))
        add(key("TOGGLECHARACTER2", "Reputation", ui, "Open or close the reputation panel."))
        add(key("TOGGLECHARACTER1", "Skills", ui, "Open or close the skills panel."))
        add(key("TOGGLEQUESTLOG", "Quest log", ui, "Open or close the quest log.", false))
        add(key("TOGGLEGAMEMENU", "Game menu", ui, "Open or close the game menu.", false))
        add(key("TOGGLEMINIMAP", "Minimap", ui, "Show or hide the minimap."))
        add(key("TOGGLEWORLDMAP", "World map", ui, "Open or close the world map.", false))
        add(key("TOGGLESOCIAL", "Social", ui, "Open or close the social panel."))
        add(key("TOGGLEFRIENDSTAB", "Friends", ui, "Open the Friends tab."))
        add(key("TOGGLEWHOTAB", "Who", ui, "Open the Who tab."))
        add(key("TOGGLEGUILDTAB", "Guild", ui, "Open the Guild tab."))
        add(key("TOGGLERAIDTAB", "Raid", ui, "Open the Raid tab."))
        add(key("TOGGLEWORLDSTATESCORES", "Battleground scores", ui,
            "Open or close the battleground score panel."))
        add(key("TOGGLEBATTLEFIELDMINIMAP", "Battleground minimap", ui,
            "Open or close the battleground minimap."))

        val misc = WowBindingCategory.MISCELLANEOUS
        add(key("MINIMAPZOOMIN", "Minimap zoom in", misc, "Zoom the minimap in."))
        add(key("MINIMAPZOOMOUT", "Minimap zoom out", misc, "Zoom the minimap out."))
        add(key("TOGGLEMUSIC", "Toggle music", misc, "Turn game music on or off."))
        add(key("TOGGLESOUND", "Toggle sound", misc, "Turn game sound on or off."))
        add(key("MASTERVOLUMEUP", "Master volume up", misc, "Increase master volume."))
        add(key("MASTERVOLUMEDOWN", "Master volume down", misc, "Decrease master volume."))
        add(key("TOGGLEUI", "Toggle game UI", misc, "Show or hide the entire game interface."))
        add(key("TOGGLEFPS", "Frame-rate display", misc, "Show or hide the frame-rate counter."))
        add(key("SCREENSHOT", "Screenshot", misc, "Capture a game screenshot."))

        val camera = WowBindingCategory.CAMERA
        add(key("NEXTVIEW", "Next camera view", camera, "Switch to the next saved camera view."))
        add(key("PREVVIEW", "Previous camera view", camera, "Switch to the previous saved camera view."))
        add(key("CAMERAZOOMIN", "Camera zoom in", camera, "Move the game camera closer.", false))
        add(key("CAMERAZOOMOUT", "Camera zoom out", camera, "Move the game camera farther away.", false))
        addAll(numbered("SETVIEW", "Set camera view", "Switch directly to saved camera view", 5,
            camera, advanced = true))
        addAll(numbered("SAVEVIEW", "Save camera view", "Save the current camera as view", 5,
            camera, advanced = true).drop(1))
        addAll(numbered("RESETVIEW", "Reset camera view", "Reset saved camera view", 5,
            camera, advanced = true).drop(1))
        add(key("FLIPCAMERAYAW", "Flip camera", camera, "Turn the camera yaw by 180 degrees."))

        val multiBars = WowBindingCategory.MULTI_ACTION_BARS
        (1..4).forEach { bar ->
            addAll(numbered(
                idPrefix = "MULTIACTIONBAR${bar}BUTTON",
                labelPrefix = "Additional bar $bar action",
                descriptionPrefix = "Use action on additional bar $bar, slot",
                count = 12,
                category = multiBars,
                advanced = true,
            ))
        }

        val raid = WowBindingCategory.RAID_MARKERS
        addAll(numbered("RAIDTARGET", "Raid marker", "Set the current target's raid marker to icon", 8,
            raid, advanced = true))
        add(key("RAIDTARGETNONE", "Clear raid marker", raid,
            "Remove the raid marker from the current target."))
    }

    /** Active stock hidden bindings, kept separate from the normal user-facing catalog. */
    val internalPointer: List<WowBindingDefinition> = listOf(
        WowBindingDefinition(
            id = "TURNORACTION",
            label = "Turn or action (right mouse)",
            category = WowBindingCategory.INTERNAL_POINTER,
            description = "Hold the stock right-mouse turn-or-action gesture.",
            advanced = true,
            semantics = WowBindingSemantics.INTERNAL_POINTER_HOLD,
        ),
        WowBindingDefinition(
            id = "CAMERAORSELECTORMOVE",
            label = "Camera, select, or move (left mouse)",
            category = WowBindingCategory.INTERNAL_POINTER,
            description = "Hold the stock left-mouse camera, selection, or movement gesture.",
            advanced = true,
            semantics = WowBindingSemantics.INTERNAL_POINTER_HOLD,
        ),
        WowBindingDefinition(
            id = "CAMERAORSELECTORMOVESTICKY",
            label = "Sticky camera, select, or move",
            category = WowBindingCategory.INTERNAL_POINTER,
            description = "Hold the sticky form of the stock left-mouse gesture.",
            advanced = true,
            semantics = WowBindingSemantics.INTERNAL_POINTER_HOLD,
        ),
    )

    /** All supported active IDs. This adds the internal pointer bindings after source-order UI IDs. */
    val allSupported: List<WowBindingDefinition> = buildList {
        addAll(userFacing)
        addAll(internalPointer)
    }

    /** Compact set appropriate for a first controller-layout screen. */
    val basicRecommended: List<WowBindingDefinition> =
        userFacing.filterNot(WowBindingDefinition::advanced)

    private val byId: Map<String, WowBindingDefinition> =
        allSupported.associateBy(WowBindingDefinition::id)

    fun find(id: String): WowBindingDefinition? = byId[id]

    fun inCategory(
        category: WowBindingCategory,
        includeAdvanced: Boolean = true,
    ): List<WowBindingDefinition> = userFacing.filter { binding ->
        binding.category == category && (includeAdvanced || !binding.advanced)
    }

    /** Case-insensitive search over the stable ID, label, and short explanation. */
    fun search(
        query: String,
        categories: Set<WowBindingCategory> = emptySet(),
        includeAdvanced: Boolean = true,
        includeInternal: Boolean = false,
    ): List<WowBindingDefinition> {
        val needle = query.trim()
        val source = if (includeInternal) allSupported else userFacing
        return source.filter { binding ->
            (categories.isEmpty() || binding.category in categories) &&
                (includeAdvanced || !binding.advanced) &&
                (needle.isEmpty() ||
                    binding.id.contains(needle, ignoreCase = true) ||
                    binding.label.contains(needle, ignoreCase = true) ||
                    binding.description.contains(needle, ignoreCase = true))
        }
    }
}
