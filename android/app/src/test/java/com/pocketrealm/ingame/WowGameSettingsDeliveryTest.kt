package com.pocketrealm.ingame

import com.pocketrealm.client.WowVanillaBindingCatalog
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject

class WowVanillaSettingsCatalogTest {

    private fun idOrderSha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(
            WowVanillaSettingsCatalog.all.joinToString("\n") { it.id }
                .toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `id order hash pins the catalog contents`() {
        assertEquals(WowVanillaSettingsCatalog.ID_ORDER_SHA256, idOrderSha256())
        assertEquals(WowVanillaSettingsCatalog.SETTING_COUNT, WowVanillaSettingsCatalog.all.size)
    }

    @Test
    fun `ids are unique and section counts match the ground-truth capture`() {
        val ids = WowVanillaSettingsCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val bySection = WowVanillaSettingsCatalog.all.groupBy { it.section }
        assertEquals(24, bySection[WowSettingSection.GRAPHICS]!!.size)
        assertEquals(11, bySection[WowSettingSection.SOUND]!!.size)
        assertEquals(37, bySection[WowSettingSection.INTERFACE]!!.size)
        assertEquals(36, bySection[WowSettingSection.INTERFACE_ADVANCED]!!.size)
    }

    @Test
    fun `sound definitions are FrameXML-pinned with capture-pinned or unknown defaults`() {
        val sound = WowVanillaSettingsCatalog.forSection(WowSettingSection.SOUND)
        assertEquals(11, sound.size)
        assertTrue(sound.all { it.provenance == WowSettingProvenance.FRAMEXML_PIN })
        assertTrue(sound.all { it.defaultProvenance == null && it.defaultValue == null })
        // The master CVar is the verified 1.12.1 name, not the inert
        // Sound_Enable* family the old template wrote.
        assertEquals("MasterSoundEffects", WowVanillaSettingsCatalog.byId("sound.master")!!.key)
    }

    @Test
    fun `video definitions are capture-pinned`() {
        val graphics = WowVanillaSettingsCatalog.forSection(WowSettingSection.GRAPHICS)
        assertTrue(graphics.all { it.provenance == WowSettingProvenance.DEVICE_CAPTURE })
        val farclip = WowVanillaSettingsCatalog.byId("graphics.terrainDistance")!!
        assertEquals("farclip", farclip.key)
        assertEquals(177f, farclip.min)
        assertEquals(777f, farclip.max)
        assertNull(farclip.fixedReason)
    }

    @Test
    fun `every definition carries a label, group, and unique key within its backend`() {
        WowVanillaSettingsCatalog.all.forEach { definition ->
            assertTrue(definition.id, definition.label.isNotBlank())
            assertTrue(definition.id, definition.group.isNotBlank())
        }
        // File keys are unique per backend so queue ids and delivery keys
        // cannot alias.
        WowVanillaSettingsCatalog.all.filter { it.backend == WowSettingBackend.CVAR }
            .let { cvars -> assertEquals(cvars.size, cvars.map { it.key }.toSet().size) }
        WowVanillaSettingsCatalog.all.filter { it.backend == WowSettingBackend.UVAR }
            .let { uvars -> assertEquals(uvars.size, uvars.map { it.key }.toSet().size) }
    }

    @Test
    fun `function-backed rows are exactly the eight verified ones and render disabled`() {
        val functions = WowVanillaSettingsCatalog.all
            .filter { it.backend == WowSettingBackend.FUNCTION }
        assertEquals(8, functions.size)
        assertTrue(functions.all { it.fixedReason == WowVanillaSettingsCatalog.FIXED_REASON_IN_GAME })
    }
}

class WowGameSettingsConfigTest {

    @Test
    fun `round-trips all three queues with null removals`() {
        val config = WowGameSettingsConfig(
            cvar = mapOf("sound.masterVolume" to QueuedOverride("0.500000", 7, "config")),
            uvar = mapOf(
                "interface.instantQuestText" to QueuedOverride(null, 8, "HI"),
            ),
            bindings = mapOf(
                "JUMP" to BindingOverride("G", null, 9, "HI"),
            ),
        )
        val parsed = WowGameSettingsConfig.fromJson(config.toJson())
        assertEquals(config, parsed)
        assertEquals(3, config.totalQueued)
    }

    @Test
    fun `malformed payload reads as an empty queue`() {
        assertEquals(WowGameSettingsConfig(), WowGameSettingsConfig.fromJson("not json"))
        assertEquals(WowGameSettingsConfig(), WowGameSettingsConfig.fromJson(null))
        assertEquals(
            WowGameSettingsConfig(),
            WowGameSettingsConfig.fromJson(JSONObject().put("schema", 99).toString()),
        )
    }

    @Test
    fun `full-catalog staging stays under the payload cap`() {
        val everything = WowGameSettingsConfig(
            cvar = WowVanillaSettingsCatalog.all
                .filter { it.backend == WowSettingBackend.CVAR }
                .associate { it.id to QueuedOverride("777.000000", 1234567890L, "config") },
            uvar = WowVanillaSettingsCatalog.all
                .filter { it.backend == WowSettingBackend.UVAR }
                .associate { it.id to QueuedOverride("1", 1234567890L, "AnAccountName") },
            bindings = WowVanillaBindingCatalog.allSupported.associate {
                it.id to BindingOverride(
                    "CTRL-SHIFT-NUMPAD8", "ALT-SHIFT-MOUSEWHEELDOWN", 1234567890L,
                    "AnAccountName/SomeServer/CharacterName",
                )
            },
        )
        assertTrue(everything.toJson().toByteArray(Charsets.UTF_8).size <= WowGameSettingsConfig.MAX_JSON_BYTES)
    }
}

class GameSettingsDeliveryPlannerTest {

    private fun plan(
        config: WowGameSettingsConfig,
        enforced: Set<String> = emptySet(),
        previous: List<GameSettingsDeliveryEntry> = emptyList(),
        uvarScopes: Set<String> = setOf("HI"),
        bindingScopes: Set<String> = setOf("HI"),
    ) = GameSettingsDeliveryPlanner.plan(
        config = config,
        enforcedCvarKeys = enforced,
        uvarScopeExists = { it in uvarScopes },
        bindingScopeExists = { it in bindingScopes },
        previousDelivered = previous,
    )

    @Test
    fun `delivers fresh cvar overrides with paired writes`() {
        val config = WowGameSettingsConfig(
            cvar = mapOf(
                "interface.autoFollowSpeed" to QueuedOverride("180", 5, "config"),
            ),
        )
        val plan = plan(config)
        assertEquals(
            listOf(
                ConfigWtfCodec.UserOverride("cameraYawSmoothSpeed", "180"),
                ConfigWtfCodec.UserOverride("cameraPitchSmoothSpeed", "45.000000"),
            ),
            plan.cvarWrites,
        )
        assertEquals(1, plan.delivered.size)
        assertTrue(plan.delivered[0].value == "180")
    }

    @Test
    fun `apply-once never re-delivers an equal or older revision`() {
        val config = WowGameSettingsConfig(
            cvar = mapOf("sound.masterVolume" to QueuedOverride("0.5", 10, "config")),
        )
        val previous = listOf(
            GameSettingsDeliveryEntry("sound.masterVolume", "config", "0.5", 10),
        )
        val plan = plan(config, previous = previous)
        assertTrue(plan.cvarWrites.isEmpty())
        assertTrue(plan.delivered.isEmpty())
        // A newer re-staged entry delivers again.
        val restaged = WowGameSettingsConfig(
            cvar = mapOf("sound.masterVolume" to QueuedOverride("0.9", 11, "config")),
        )
        val second = plan(restaged, previous = previous)
        assertEquals(1, second.delivered.size)
    }

    @Test
    fun `staged master sound while audio off is blocked and retained`() {
        val config = WowGameSettingsConfig(
            cvar = mapOf("sound.master" to QueuedOverride("1", 4, "config")),
        )
        val enforced = ManagedConfigPolicy.enforcedKeys(
            ManagedConfigPolicy.LaunchConditions(
                renderer = "dxvk", resolution = "1920x1080", gameMaximized = true,
                frameCap = 30, audioMode = "off", realmLoopback = true,
                soundChannelsEnabled = false, soundChannels = 32,
            ),
        ).map { it.key }.toSet()
        val plan = plan(config, enforced = enforced)
        assertEquals(setOf("sound.master"), plan.blockedKeys)
        assertTrue(plan.cvarWrites.isEmpty())
        assertTrue(plan.delivered.isEmpty())
    }

    @Test
    fun `missing scope files are skipped and retained`() {
        val config = WowGameSettingsConfig(
            uvar = mapOf("interface.instantQuestText" to QueuedOverride("1", 3, "NOBODY")),
            bindings = mapOf("JUMP" to BindingOverride("G", null, 3, "NOBODY")),
        )
        val plan = plan(config)
        assertEquals(setOf("interface.instantQuestText", "JUMP"), plan.missingScopeKeys)
        assertTrue(plan.delivered.isEmpty())
    }

    @Test
    fun `apply-once across successive prepares covers all three queues`() {
        val config = WowGameSettingsConfig(
            cvar = mapOf("sound.masterVolume" to QueuedOverride("0.5", 4, "config")),
            uvar = mapOf(
                "interface.instantQuestText" to QueuedOverride("1", 4, "HI"),
            ),
            bindings = mapOf("JUMP" to BindingOverride("G", null, 4, "HI")),
        )
        // Prepare 1: everything delivers.
        val first = plan(config)
        assertEquals(3, first.delivered.size)
        assertEquals(1, first.uvarWrites["HI"]!!.size)
        assertEquals(1, first.bindingWrites["HI"]!!.size)
        // Prepare 2 with the same queue: nothing re-delivers.
        val second = plan(config, previous = first.delivered)
        assertTrue(second.cvarWrites.isEmpty())
        assertTrue(second.uvarWrites.isEmpty())
        assertTrue(second.bindingWrites.isEmpty())
        assertTrue(second.delivered.isEmpty())
        // A later in-game change (uvar value differs) is not silently
        // reverted: the queue entry stays stale until the user re-stages.
        assertEquals(0, second.delivered.size)
    }

    @Test
    fun `scope-keyed delivery does not cross scopes`() {
        val config = WowGameSettingsConfig(
            bindings = mapOf("JUMP" to BindingOverride("G", null, 6, "HI")),
        )
        val previous = listOf(
            GameSettingsDeliveryEntry("JUMP", "OTHER/MaNGOS/Toon", "G", 9),
        )
        val plan = plan(config, previous = previous)
        assertEquals(1, plan.delivered.size)
        assertEquals("HI", plan.delivered[0].scope)
    }

    @Test
    fun `carry-forward prunes entries older than the oldest queued revision`() {
        val previous = listOf(
            GameSettingsDeliveryEntry("sound.masterVolume", "config", "0.5", 5),
            GameSettingsDeliveryEntry("JUMP", "HI", "G", 2),
        )
        val stillQueued = WowGameSettingsConfig(
            cvar = mapOf("sound.masterVolume" to QueuedOverride("0.7", 12, "config")),
        )
        val carried = GameSettingsDeliveryPlanner.carryForward(
            previous,
            freshlyDelivered = listOf(GameSettingsDeliveryEntry("A", "config", "1", 13)),
            config = stillQueued,
        )
        // A delivered entry superseded by a newer queued re-stage (5 < 12)
        // is pruned - the re-stage will re-record fresh evidence on delivery.
        assertTrue(carried.none { it.key == "sound.masterVolume" && it.revision == 5L })
        assertTrue(carried.any { it.key == "A" && it.revision == 13L })
        // JUMP (revision 2) is older than the oldest queued revision (12).
        assertTrue(carried.none { it.key == "JUMP" })
        // With an empty queue nothing is pruned.
        val kept = GameSettingsDeliveryPlanner.carryForward(previous, emptyList(), WowGameSettingsConfig())
        assertEquals(previous.size, kept.size)
    }
}

class ManagedConfigPolicyTest {

    private fun conditions(audio: String, renderer: String = "dxvk") =
        ManagedConfigPolicy.LaunchConditions(
            renderer = renderer, resolution = "1920x1080", gameMaximized = true,
            frameCap = 30, audioMode = audio, realmLoopback = true,
            soundChannelsEnabled = true, soundChannels = 48,
        )

    @Test
    fun `audio off enforces only the master sound CVar`() {
        val enforced = ManagedConfigPolicy.enforcedKeys(conditions("off"))
        assertEquals("0", enforced.first { it.key == "MasterSoundEffects" }.value)
        assertNull(enforced.first { it.key == "SoundMixRate" }.value)
        assertNull(enforced.first { it.key == "SoundBufferSize" }.value)
        assertNull(enforced.first { it.key == "SoundSoftwareChannels" }.value)
    }

    @Test
    fun `audio on leaves master sound entirely user-owned and enables the audio lines`() {
        val enforced = ManagedConfigPolicy.enforcedKeys(conditions("on"))
        // The key is absent from the enforced set altogether while audio is
        // on - a null entry would DELETE the user's line at every prepare.
        assertTrue(enforced.none { it.key == "MasterSoundEffects" })
        assertEquals("48000", enforced.first { it.key == "SoundMixRate" }.value)
        assertEquals("100", enforced.first { it.key == "SoundBufferSize" }.value)
        assertEquals("48", enforced.first { it.key == "SoundSoftwareChannels" }.value)
        // A user-owned master line therefore survives an audio-on merge.
        val merged = ConfigWtfCodec.merge(
            "SET MasterSoundEffects \"0\"" + "\r\n" + "SET movie \"0\"" + "\r\n",
            enforced,
        )
        assertTrue(merged.text.contains("SET MasterSoundEffects \"0\""))
    }

    @Test
    fun `renderer flips delete stale M2UseShaders and realm lines follow loopback`() {
        val dxvk = ManagedConfigPolicy.enforcedKeys(
            conditions("on", renderer = "dxvk").copy(realmLoopback = false),
        )
        assertNull(dxvk.first { it.key == "M2UseShaders" }.value)
        assertNull(dxvk.first { it.key == "realmName" }.value)
        val gladio = ManagedConfigPolicy.enforcedKeys(conditions("on", renderer = "opengl"))
        assertEquals("0", gladio.first { it.key == "M2UseShaders" }.value)
        assertEquals("opengl", gladio.first { it.key == "gxApi" }.value)
    }

    @Test
    fun `farclip is no longer enforced - user values survive`() {
        val enforced = ManagedConfigPolicy.enforcedKeys(conditions("on"))
        assertTrue(enforced.none { it.key == "farclip" })
    }

    @Test
    fun `transition cleanup fires once off to on without a newer user edit`() {
        val delete = ManagedConfigPolicy.masterSoundTransitionDelete(
            previousAudioMode = "off",
            currentAudioMode = "on",
            previousPreparedAtRevision = 10,
            directEditRevisions = emptyMap(),
        )
        assertEquals(ConfigWtfCodec.EnforcedLine("MasterSoundEffects", null), delete)
        // A user edit after the audio-off launch outranks the prepare.
        assertNull(
            ManagedConfigPolicy.masterSoundTransitionDelete(
                previousAudioMode = "off",
                currentAudioMode = "on",
                previousPreparedAtRevision = 10,
                directEditRevisions = mapOf("MasterSoundEffects" to 11),
            ),
        )
        // Equal revision: the direct edit bumped the counter at edit time, so
        // equality means the edit predated the prepare — cleanup still fires.
        assertEquals(
            delete,
            ManagedConfigPolicy.masterSoundTransitionDelete(
                previousAudioMode = "off",
                currentAudioMode = "on",
                previousPreparedAtRevision = 10,
                directEditRevisions = mapOf("MasterSoundEffects" to 10),
            ),
        )
        // No prior audio-off launch: nothing to clean up.
        assertNull(
            ManagedConfigPolicy.masterSoundTransitionDelete(
                previousAudioMode = "on",
                currentAudioMode = "on",
                previousPreparedAtRevision = 10,
                directEditRevisions = emptyMap(),
            ),
        )
    }

    @Test
    fun `every enforced key is written or deleted - no preserve entries`() {
        val on = ManagedConfigPolicy.enforcedKeys(conditions("on"))
        assertEquals(16, on.size)
        assertTrue(on.all { it.value != null || it.key in setOf(
            "SoundMixRate", "SoundBufferSize", "SoundSoftwareChannels",
            "M2UseShaders", "realmName",
        ) })
        val off = ManagedConfigPolicy.enforcedKeys(conditions("off"))
        assertEquals(17, off.size)
        assertTrue(off.all { it.value != null || it.key in setOf(
            "SoundMixRate", "SoundBufferSize", "SoundSoftwareChannels",
            "M2UseShaders", "realmName",
        ) })
    }
}
