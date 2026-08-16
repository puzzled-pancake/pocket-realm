package com.pocketrealm.ingame

/**
 * Versioned, integrity-hashed manifest of the stock WoW 1.12.1 (build 5875)
 * fixed settings — the sibling of `WowVanillaBindingCatalog`.
 *
 * Every entry carries a provenance pin (docs/INGAME_SETTINGS_GROUND_TRUTH.md):
 * interface and sound definitions are pinned to the MOUZU mirror of
 * Blizzard's 1.12.1 FrameXML (commit 776d64e…, `UIOptionsFrame.lua`,
 * `SoundOptionsFrame.lua`, `GlobalStrings.lua`); video names/values and all
 * capture-sourced defaults are pinned to the live build-5875 WTF tree pulled
 * from the Retroid Pocket 6 on 2026-08-16. Labels are the exact 1.12.1
 * English strings from `GlobalStrings.lua` where the panel is FrameXML;
 * video labels are descriptive (the 1.12.1 video panel is native and its
 * string table is not in any pinned source).
 *
 * Verified fixed-settings count: 108 (11 sound, 24 graphics, 37 interface,
 * 36 advanced interface) of which 8 are function-backed rows that render
 * disabled; farclip is the single user-editable graphics CVar this drop.
 */
object WowVanillaSettingsCatalog {
    const val CATALOG_VERSION: Int = 1

    const val FIXED_REASON_MANAGED_DISPLAY = "Managed by Pocket Realm display settings"
    const val FIXED_REASON_RENDERER = "Not supported by the current renderer"
    const val FIXED_REASON_IN_GAME = "Change this in the game's own menus"

    private val definitions: List<WowSettingDefinition> = listOf(

        // ---------------- Graphics: Display (managed / fixed rows) ----------------
        WowSettingDefinition(
            id = "graphics.resolution", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Resolution", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "gxResolution",
            choices = listOf(WowSettingChoice("panel", "Panel resolution", "panel")),
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_MANAGED_DISPLAY,
        ),
        WowSettingDefinition(
            id = "graphics.windowed", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Windowed Mode", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "gxWindow",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_MANAGED_DISPLAY,
        ),
        WowSettingDefinition(
            id = "graphics.maximized", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Maximized", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "gxMaximize",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_MANAGED_DISPLAY,
        ),
        WowSettingDefinition(
            id = "graphics.frameCap", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Frame Cap", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "maxFPS", min = 30f, max = 60f, step = 1f,
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_MANAGED_DISPLAY,
        ),
        WowSettingDefinition(
            id = "graphics.vsync", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Vertical Sync", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "gxVSync",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.multisample", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Multisampling", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "gxMultisample",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.colorDepth", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Color Depth", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "gxColorBits",
            choices = listOf(WowSettingChoice("24", "24 bit", "24")),
            defaultValue = "24", provenance = WowSettingProvenance.DEVICE_CAPTURE,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.depthBits", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Depth Buffer", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "gxDepthBits",
            choices = listOf(WowSettingChoice("24", "24 bit", "24")),
            defaultValue = "24", provenance = WowSettingProvenance.DEVICE_CAPTURE,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.refreshRate", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Refresh Rate", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "gxRefresh",
            choices = listOf(WowSettingChoice("60", "60 Hz", "60")),
            defaultValue = "60", provenance = WowSettingProvenance.DEVICE_CAPTURE,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.inputLagCompensation", section = WowSettingSection.GRAPHICS,
            group = "Display", label = "Fix Lag", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "gxFixLag",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.hardwareDetection", section = WowSettingSection.GRAPHICS, group = "Display",
            label = "Hardware Detection at Startup", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "hwDetect",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),

        // ---------------- Graphics: World ----------------
        WowSettingDefinition(
            id = "graphics.terrainDistance", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Terrain Distance", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "farclip",
            min = 177f, max = 777f, step = 1f,
            defaultValue = "500.000000",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
        ),
        WowSettingDefinition(
            id = "graphics.environmentDetail", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Environment Detail", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "lodDist",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.distanceCull", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Distance Cull Scale", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "DistCull",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.smallCull", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Small Object Cull", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "SmallCull",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.textureFiltering", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Texture Filtering", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "trilinear",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.specularLighting", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Specular Lighting", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "specular",
            capability = WowSettingCapabilityRequirement.PIXEL_SHADERS,
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.pixelShaders", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Pixel Shaders", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "pixelShaders",
            capability = WowSettingCapabilityRequirement.PIXEL_SHADERS,
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.alphaBlending", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Smooth Shading", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "fullAlpha",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.frillDensity", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Wilderness Frill Density", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "frillDensity",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.particleDensity", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Particle Density", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "particleDensity",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.unitDrawDistance", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Unit Draw Distance", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "unitDrawDist",
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.glowEffects", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Full-Screen Glow Effects", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "ffxGlow",
            capability = WowSettingCapabilityRequirement.GLOW_EFFECTS,
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),
        WowSettingDefinition(
            id = "graphics.deathEffects", section = WowSettingSection.GRAPHICS, group = "World",
            label = "Death Effect", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "ffxDeath",
            capability = WowSettingCapabilityRequirement.GLOW_EFFECTS,
            provenance = WowSettingProvenance.DEVICE_CAPTURE,
            fixedReason = FIXED_REASON_RENDERER,
        ),

        // ---------------- Sound: Toggles (SoundOptionsFrame.lua) ----------------
        WowSettingDefinition(
            id = "sound.master", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable All Sound", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "MasterSoundEffects",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.music", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable Music", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "EnableMusic",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.ambience", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable Ambience", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "EnableAmbience",
            requires = listOf(WowSettingRequirement("sound.master", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.errorSpeech", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable Error Speech", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "EnableErrorSpeech",
            requires = listOf(WowSettingRequirement("sound.master", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.listenerAtCharacter", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable Sound at Character", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "SoundListenerAtCharacter",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.emoteSounds", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Enable Emote Sounds", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "EmoteSounds",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.loopMusic", section = WowSettingSection.SOUND, group = "Toggles",
            label = "Loop Music", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "SoundZoneMusicNoDelay",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Sound: Volumes ----------------
        WowSettingDefinition(
            id = "sound.masterVolume", section = WowSettingSection.SOUND, group = "Volumes",
            label = "Master Volume", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "MasterVolume",
            min = 0f, max = 1f, step = 0.1f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.soundVolume", section = WowSettingSection.SOUND, group = "Volumes",
            label = "Sound Volume", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "SoundVolume",
            min = 0f, max = 1f, step = 0.1f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.musicVolume", section = WowSettingSection.SOUND, group = "Volumes",
            label = "Music Volume", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "MusicVolume",
            min = 0f, max = 1f, step = 0.1f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "sound.ambienceVolume", section = WowSettingSection.SOUND, group = "Volumes",
            label = "Ambience Volume", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "AmbienceVolume",
            min = 0f, max = 1f, step = 0.1f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Interface: Controls ----------------
        WowSettingDefinition(
            id = "interface.invertMouse", section = WowSettingSection.INTERFACE, group = "Controls",
            label = "Invert Mouse", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "mouseInvertPitch",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.clickToMove", section = WowSettingSection.INTERFACE, group = "Controls",
            label = "Click-to-Move", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "autointeract",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.mouseSensitivity", section = WowSettingSection.INTERFACE,
            group = "Controls", label = "Mouse Sensitivity", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "mousespeed",
            min = 0.5f, max = 1.5f, step = 0.05f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Interface: General ----------------
        WowSettingDefinition(
            id = "interface.assistAttack", section = WowSettingSection.INTERFACE, group = "General",
            label = "Attack on assist", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "assistAttack",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.autoClearAfk", section = WowSettingSection.INTERFACE, group = "General",
            label = "Auto Clear AFK", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "autoClearAFK",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.blockTrades", section = WowSettingSection.INTERFACE, group = "General",
            label = "Block Trades", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "BlockTrades",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.autoSelfCast", section = WowSettingSection.INTERFACE, group = "General",
            label = "Auto Self Cast", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "autoSelfCast",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.stickyTargeting", section = WowSettingSection.INTERFACE, group = "General",
            label = "Sticky Targeting", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "deselectOnClick", inverse = true,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.disableSpamFilter", section = WowSettingSection.INTERFACE,
            group = "General", label = "Disable Spam Filter", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "spamFilter", inverse = true,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.autoJoinGuildRecruitment", section = WowSettingSection.INTERFACE,
            group = "General", label = "Auto-join Guild Recruitment Channel",
            control = WowSettingControl.TOGGLE, backend = WowSettingBackend.FUNCTION,
            key = "SetGuildRecruitmentMode",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "interface.showTutorials", section = WowSettingSection.INTERFACE, group = "General",
            label = "Show Tutorials", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "TutorialsEnabled",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),

        // ---------------- Interface: Display ----------------
        WowSettingDefinition(
            id = "interface.enhancedTooltips", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Enhanced Tooltips", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UberTooltips",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.statusBarText", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Status Bar Text", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "statusBarText",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.profanityFilter", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Profanity Filter", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "profanityFilter",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.showHelm", section = WowSettingSection.INTERFACE, group = "Display",
            label = "Show Helm", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "ShowHelm",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "interface.showCloak", section = WowSettingSection.INTERFACE, group = "Display",
            label = "Show Cloak", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "ShowCloak",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "interface.instantQuestText", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Instant Quest Text", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "QUEST_FADING_DISABLE",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.buffDurations", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Buff Durations", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_BUFF_DURATIONS",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.playerNames", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Player Names", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UnitNamePlayer",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.guildNames", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Player Guild Names", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UnitNamePlayerGuild",
            requires = listOf(WowSettingRequirement("interface.playerNames", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.playerTitles", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Player Titles", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UnitNamePlayerPVPTitle",
            requires = listOf(WowSettingRequirement("interface.playerNames", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.npcNames", section = WowSettingSection.INTERFACE,
            group = "Display", label = "NPC Names", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UnitNameNPC",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.ownName", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Show Own Name", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "UnitNameOwn",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.partyBackground", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Show Party Background", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_PARTY_BACKGROUND",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.hideZoneObjectiveTracker", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Hide Zone Objective Tracker",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "HIDE_OUTDOOR_WORLD_STATE",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.automaticQuestTracking", section = WowSettingSection.INTERFACE,
            group = "Display", label = "Automatic Quest Tracking",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "AUTO_QUEST_WATCH",
            defaultValue = "1", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
            uvarValueForm = WowUvarValueForm.NUMBER,
        ),

        // ---------------- Interface: Camera ----------------
        WowSettingDefinition(
            id = "interface.followTerrain", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Follow Terrain", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "cameraTerrainTilt",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.headBob", section = WowSettingSection.INTERFACE, group = "Camera",
            label = "Head Bob", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "cameraBobbing",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.waterCollision", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Water Collision", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "cameraWaterCollision",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.smartPivot", section = WowSettingSection.INTERFACE, group = "Camera",
            label = "Smart Pivot", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "cameraPivot",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.cameraStyle", section = WowSettingSection.INTERFACE, group = "Camera",
            label = "Camera Following Style", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "cameraSmoothStyle",
            choices = listOf(
                WowSettingChoice("smart", "Smart", "1"),
                WowSettingChoice("always", "Always", "2"),
                WowSettingChoice("never", "Never", "0"),
            ),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.clickCameraStyle", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Click-to-Move Camera Style", control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.CVAR, key = "cameraSmoothTrackingStyle",
            choices = listOf(
                WowSettingChoice("smart", "Smart", "1"),
                WowSettingChoice("locked", "Locked", "2"),
                WowSettingChoice("never", "Never", "0"),
            ),
            requires = listOf(WowSettingRequirement("interface.clickToMove", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.autoFollowSpeed", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Auto-Follow Speed", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "cameraYawSmoothSpeed",
            min = 90f, max = 270f, step = 10f,
            pairedWrites = listOf("cameraPitchSmoothSpeed" to 0.25f),
            requires = listOf(WowSettingRequirement("interface.cameraStyle", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.mouseLookSpeed", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Mouse Look Speed", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "cameraYawMoveSpeed",
            min = 90f, max = 270f, step = 10f,
            pairedWrites = listOf("cameraPitchMoveSpeed" to 0.5f),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.maxCameraDistance", section = WowSettingSection.INTERFACE,
            group = "Camera", label = "Max Camera Distance", control = WowSettingControl.SLIDER,
            backend = WowSettingBackend.CVAR, key = "cameraDistanceMaxFactor",
            min = 1f, max = 2f, step = 0.1f,
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Interface: Help ----------------
        WowSettingDefinition(
            id = "interface.detailedTooltips", section = WowSettingSection.INTERFACE,
            group = "Help", label = "Show Detailed Tooltips", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_NEWBIE_TIPS",
            defaultValue = "1", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "interface.loadingScreenTips", section = WowSettingSection.INTERFACE,
            group = "Help", label = "Show Loading Screen Tips", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "showGameTips",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Advanced: Action Bars ----------------
        WowSettingDefinition(
            id = "advanced.lockActionBars", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Lock ActionBars", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "LOCK_ACTIONBAR",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.bottomLeftActionBar", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Show Bottom Left ActionBar",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "SetActionBarToggles:1",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "advanced.bottomRightActionBar", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Show Bottom Right ActionBar",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "SetActionBarToggles:2",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "advanced.rightActionBar", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Show Right ActionBar",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "SetActionBarToggles:3",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "advanced.rightActionBar2", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Show Right ActionBar 2",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.FUNCTION, key = "SetActionBarToggles:4",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            fixedReason = FIXED_REASON_IN_GAME,
        ),
        WowSettingDefinition(
            id = "advanced.alwaysShowActionBars", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Action Bars", label = "Always Show ActionBars",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "ALWAYS_SHOW_MULTIBARS",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Advanced: Chat ----------------
        WowSettingDefinition(
            id = "advanced.simpleChat", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Simple Chat", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SIMPLE_CHAT",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
        ),
        WowSettingDefinition(
            id = "advanced.lockChatSettings", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Lock Chat Settings", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "CHAT_LOCKED",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.DEVICE_CAPTURE,
        ),
        WowSettingDefinition(
            id = "advanced.guildMemberAlert", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Guild Member Alert", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "guildMemberNotify",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.removeChatHoverDelay", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Remove Chat Hover Delay", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "REMOVE_CHAT_DELAY",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.chatBubbles", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Show Chat Bubbles", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "ChatBubbles",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.partyChatBubbles", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Show Party Chat Bubbles", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "ChatBubblesParty",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.detailedLootInformation", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Chat", label = "Detailed Loot Information", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "showLootSpam",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Advanced: Raid & Party ----------------
        WowSettingDefinition(
            id = "advanced.hidePartyInterfaceInRaid", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Hide Party Interface in Raid",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "HIDE_PARTY_INTERFACE",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.showDispellableDebuffs", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Show Dispellable Debuffs",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_DISPELLABLE_DEBUFFS",
            defaultValue = "1", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.showCastableBuffs", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Show Castable Buffs",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_CASTABLE_BUFFS",
            defaultValue = "1", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.showPartyPets", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Show Party Members' Pets",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_PARTY_PETS",
            defaultValue = "1", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.showTargetOfTarget", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Show Target of Target",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_TARGET_OF_TARGET",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.targetOfTargetMode", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Raid & Party", label = "Target of Target Mode",
            control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.UVAR, key = "SHOW_TARGET_OF_TARGET_STATE",
            choices = listOf(
                WowSettingChoice("raid", "Raid", "1"),
                WowSettingChoice("party", "Party", "2"),
                WowSettingChoice("solo", "Solo", "3"),
                WowSettingChoice("raidAndParty", "Raid & Party", "4"),
                WowSettingChoice("always", "Always", "5"),
            ),
            defaultValue = "5",
            requires = listOf(WowSettingRequirement("advanced.showTargetOfTarget", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),

        // ---------------- Advanced: Floating Combat Text ----------------
        WowSettingDefinition(
            id = "advanced.floatingCombatText", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Enable Floating Combat Text",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "SHOW_COMBAT_TEXT",
            defaultValue = "0", provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextMode", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Combat Text Float Mode",
            control = WowSettingControl.CHOICE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_FLOAT_MODE",
            choices = listOf(
                WowSettingChoice("scrollUp", "Scroll Up", "1"),
                WowSettingChoice("scrollDown", "Scroll Down", "2"),
                WowSettingChoice("arc", "Arc", "3"),
            ),
            defaultValue = "1",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextLowHealthMana", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Low Mana and Health",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_LOW_HEALTH_MANA",
            defaultValue = "1",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextAuras", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Auras", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_AURAS",
            defaultValue = "1",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextAuraFade", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Fading Auras", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_AURA_FADE",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextCombatState", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Combat State",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_COMBAT_STATE",
            defaultValue = "1",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextDodgeParryMiss", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Dodge/Parries/Misses",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_DODGE_PARRY_MISS",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextResistances", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Damage Reduction",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_RESISTANCES",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextReputation", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Reputation", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_REPUTATION",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextReactives", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Reactive Spells and Abilities",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_REACTIVES",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextFriendlyNames", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Friendly Healer Names",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_FRIENDLY_NAMES",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextComboPoints", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Combo Points", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_COMBO_POINTS",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextMana", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Energy Gains",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_MANA",
            defaultValue = "0",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.combatTextHonorGained", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Honor Gained", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.UVAR, key = "COMBAT_TEXT_SHOW_HONOR_GAINED",
            defaultValue = "1",
            requires = listOf(WowSettingRequirement("advanced.floatingCombatText", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
            defaultProvenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.targetDamage", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Show Target Damage",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "CombatDamage",
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.periodicDamage", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Periodic Damage",
            control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "CombatLogPeriodicSpells",
            requires = listOf(WowSettingRequirement("advanced.targetDamage", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
        WowSettingDefinition(
            id = "advanced.petDamage", section = WowSettingSection.INTERFACE_ADVANCED,
            group = "Combat Text", label = "Pet Damage", control = WowSettingControl.TOGGLE,
            backend = WowSettingBackend.CVAR, key = "PetMeleeDamage",
            pairedWrites = listOf("PetSpellDamage" to 1f),
            requires = listOf(WowSettingRequirement("advanced.targetDamage", "0")),
            provenance = WowSettingProvenance.FRAMEXML_PIN,
        ),
    )

    val all: List<WowSettingDefinition> = definitions

    fun byId(id: String): WowSettingDefinition? = definitions.firstOrNull { it.id == id }

    fun forSection(section: WowSettingSection): List<WowSettingDefinition> =
        definitions.filter { it.section == section }

    fun groups(section: WowSettingSection): List<String> =
        forSection(section).map { it.group }.distinct()

    fun byKey(key: String): WowSettingDefinition? = definitions.firstOrNull { it.key == key }

    /** Definition ids as the hash input, mirroring the binding catalog's pin. */
    const val ID_ORDER_SHA256: String = "bf5f8f72b15a8db471d847d09c57ad87ea0c9546d3249191efcf0b1524fcad1a"
    const val SETTING_COUNT: Int = 108

    /** IDs whose backing key is user-editable in this drop (not fixed/function/enforced). */
    val userEditable: List<WowSettingDefinition> =
        definitions.filter { it.fixedReason == null && it.backend != WowSettingBackend.FUNCTION }
}
