package com.pocketrealm.ingame

/**
 * The app-enforced Config.wtf overlay (plan §4.3), shared by the prepare
 * path (which writes it through the merge engine) and the editor UI (which
 * labels queued entries blocked when their key is enforced this launch).
 *
 * Every entry is *written or deleted* — conditional keys whose condition is
 * false this launch carry a null value and are removed from the merged
 * output, so stale lines cannot survive audio on→off, loopback→LAN, or
 * renderer flips. `farclip` is deliberately absent: it flipped to
 * user-editable with the 177 seed (plan §4.3, Phase 2).
 */
object ManagedConfigPolicy {

    /** The master sound CVar verified against SoundOptionsFrame.lua. */
    const val MASTER_SOUND_CVAR: String = "MasterSoundEffects"

    data class LaunchConditions(
        val renderer: String,
        val resolution: String,
        val gameMaximized: Boolean,
        val frameCap: Int,
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
            add(ConfigWtfCodec.EnforcedLine("gxVSync", "0"))
            add(ConfigWtfCodec.EnforcedLine("gxMultisample", "1"))
            add(ConfigWtfCodec.EnforcedLine("gxMultisampleQuality", "0.000000"))
            add(ConfigWtfCodec.EnforcedLine("maxFPS", conditions.frameCap.toString()))
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
            add(ConfigWtfCodec.EnforcedLine("ffxGlow", "0"))
            add(ConfigWtfCodec.EnforcedLine("ffxDeath", "0"))
            add(
                ConfigWtfCodec.EnforcedLine(
                    "realmName",
                    if (conditions.realmLoopback) "MaNGOS" else null,
                ),
            )
        }
    }

    /**
     * The one-time audio off→on transition cleanup (§4.3): delete the stale
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
}
