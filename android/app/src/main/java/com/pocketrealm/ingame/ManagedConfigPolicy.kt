package com.pocketrealm.ingame

/**
 * The app-enforced Config.wtf overlay, shared by the prepare
 * path (which writes it through the merge engine) and the editor UI (which
 * labels queued entries blocked when their key is enforced this launch).
 *
 * Every entry is *written or deleted* — conditional keys whose condition is
 * false this launch carry a null value and are removed from the merged
 * output, so stale lines cannot survive audio on→off, loopback→LAN, or
 * renderer flips. `farclip` is deliberately absent: it is user-editable in
 * the seed data. The UI scale pair is
 * conditionally owned: enforced while the app manages the value, entirely
 * absent from this set (user-owned, like master sound with audio on) while
 * unmanaged — a one-time transition delete ([uiScaleTransitionDelete])
 * removes the stale enforced pair when management is turned off again.
 */
object ManagedConfigPolicy {

    /** The master sound CVar verified against SoundOptionsFrame.lua. */
    const val MASTER_SOUND_CVAR: String = "MasterSoundEffects"

    data class LaunchConditions(
        val renderer: String,
        val resolution: String,
        val gameMaximized: Boolean,
        val frameCap: Int,
        /** App-managed UI scale (already per-profile clamped); null = user-owned. */
        val uiScale: Float?,
        val audioMode: String,
        val realmLoopback: Boolean,
        val soundChannelsEnabled: Boolean,
        val soundChannels: Int,
    )

    fun enforcedKeys(conditions: LaunchConditions): List<ConfigWtfCodec.EnforcedLine> {
        val soundOn = conditions.audioMode == "on"
        val graphicsApi = if (conditions.renderer == "opengl") "opengl" else "d3d"
        return buildList {
            add(ConfigWtfCodec.EnforcedLine("readTOS", "1"))
            add(ConfigWtfCodec.EnforcedLine("readEULA", "1"))
            add(ConfigWtfCodec.EnforcedLine("readScanning", "1"))
            add(ConfigWtfCodec.EnforcedLine("movie", "0"))
            add(ConfigWtfCodec.EnforcedLine("gxApi", graphicsApi))
            add(ConfigWtfCodec.EnforcedLine("gxResolution", conditions.resolution))
            add(ConfigWtfCodec.EnforcedLine("gxWindowedResolution", conditions.resolution))
            add(ConfigWtfCodec.EnforcedLine("gxWindow", "1"))
            add(ConfigWtfCodec.EnforcedLine("gxMaximize", if (conditions.gameMaximized) "1" else "0"))
            // F4: gxVSync/gxMultisample/gxMultisampleQuality are user-owned.
            // No cleanup entry is needed for their previously enforced lines:
            // the 1.12 client itself drops gxVSync/gxMultisample lines at
            // clean exit (ground truth capture), and the editor can change
            // them at any time now that they are absent from this set.
            add(ConfigWtfCodec.EnforcedLine("maxFPS", conditions.frameCap.toString()))
            // UI scale: while managed, both CVars are enforced every prepare
            // (self-healing against the client's exit rewrite of Config.wtf).
            // While unmanaged both keys are user-owned — absent from this set
            // entirely so an in-game Advanced Options choice survives.
            conditions.uiScale?.let { scale ->
                add(ConfigWtfCodec.EnforcedLine("useUiScale", "1"))
                add(ConfigWtfCodec.EnforcedLine("uiScale", ConfigWtfCodec.formatValue(scale)))
            }
            add(ConfigWtfCodec.EnforcedLine("scriptMemory", "0"))
            // Audio-off enforcement is the only sound line the app owns. While
            // audio is ON the master CVar is NOT in this list at all — it is
            // user-owned and preserved from the base like any user CVar, so a
            // player's in-game master-off survives relaunches. The one-time
            // off→on transition cleanup (below) is the only audio-on delete.
            if (!soundOn) {
                add(ConfigWtfCodec.EnforcedLine(MASTER_SOUND_CVAR, "0"))
            }
            add(ConfigWtfCodec.EnforcedLine("SoundMixRate", if (soundOn) "48000" else null))
            add(ConfigWtfCodec.EnforcedLine("SoundBufferSize", if (soundOn) "100" else null))
            add(
                ConfigWtfCodec.EnforcedLine(
                    "SoundSoftwareChannels",
                    if (soundOn && conditions.soundChannelsEnabled) {
                        conditions.soundChannels.toString()
                    } else null,
                ),
            )
            add(ConfigWtfCodec.EnforcedLine("M2UseShaders", if (conditions.renderer == "opengl") "0" else null))
            // F4: ffxGlow/ffxDeath are user-owned under the DXVK lane (the
            // Legacy GL lanes keep the rows fixed in the editor).
            add(
                ConfigWtfCodec.EnforcedLine(
                    "realmName",
                    if (conditions.realmLoopback) "MaNGOS" else null,
                ),
            )
        }
    }

    /**
     * The one-time audio off→on transition cleanup: delete the stale
     * enforced "0" exactly once — but never when the user edited the master
     * key after the audio-off launch (a user-chosen master-off is
     * byte-identical to the stale enforced value; only the direct-edit
     * journal can tell them apart).
     */
    fun masterSoundTransitionDelete(
        previousAudioMode: String?,
        currentAudioMode: String,
        previousPreparedAtRevision: Long,
        directEditRevisions: Map<String, Long>,
    ): ConfigWtfCodec.EnforcedLine? {
        if (previousAudioMode != "off" || currentAudioMode != "on") return null
        val lastUserEdit = directEditRevisions[MASTER_SOUND_CVAR]
        if (lastUserEdit != null && lastUserEdit > previousPreparedAtRevision) return null
        return ConfigWtfCodec.EnforcedLine(MASTER_SOUND_CVAR, null)
    }

    /**
     * The managed→unmanaged UI-scale transition cleanup: delete the stale
     * enforced pair exactly once — but only when both lines still carry the
     * values the app itself wrote (uiScale matches the recorded last-enforced
     * value; useUiScale is still "1"). In-game edits are never journaled, so
     * the recorded value is the only discriminator between "stale enforced
     * lines" and "the user changed the scale in game during the final managed
     * session" (the same never-delete-a-user-choice rule as
     * [masterSoundTransitionDelete]). On any mismatch the pair stays in the
     * file, fully user-owned.
     */
    fun uiScaleTransitionDelete(
        previousUiScale: String?,
        currentUiScale: Float?,
        currentFileValues: Map<String, String>,
    ): List<ConfigWtfCodec.EnforcedLine> {
        if (previousUiScale == null || currentUiScale != null) return emptyList()
        val recorded = previousUiScale.toFloatOrNull() ?: return emptyList()
        val fileScale = currentFileValues["uiScale"]?.toFloatOrNull()
        if (fileScale == null || recorded.compareTo(fileScale) != 0) return emptyList()
        if (currentFileValues["useUiScale"] != "1") return emptyList()
        return listOf(
            ConfigWtfCodec.EnforcedLine("uiScale", null),
            ConfigWtfCodec.EnforcedLine("useUiScale", null),
        )
    }
}
