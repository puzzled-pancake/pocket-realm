package com.pocketrealm.ingame

import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File

/**
 * DESK-50: the app-owned Config.wtf overlay at the native client launch —
 * the desktop twin of the Android prepare path's enforcement
 * (ManagedConfigPolicy over the user's `WTF/Config.wtf` through the shared
 * [ConfigWtfCodec]).
 *
 * A fresh 1.12.1 install opens on the EULA/TOS + intro-movie gates while the
 * desktop auto-login fires seconds after launch and types into whatever
 * window is up — the exact collision the Android overlay exists to prevent.
 * Every launch merges the enforced keys over the user's file: the gates are
 * killed, the client comes up windowed, and — per the DESK-139
 * cross-reference (hunt-r6-lens4) — the AUDIO leg rides the same conditions,
 * so audio-off launches enforce the master-sound CVar while audio-on
 * launches leave it user-owned.
 *
 * The desktop launch conditions are the portable subset: windowed (not
 * maximized) at the current primary display's size, movie off, TOS/EULA
 * read, d3d (the native client's default renderer), no app UI-scale
 * management (user-owned), no frame cap beyond the uncapped "0" the engine
 * treats as off. `farclip` stays deliberately absent (user-editable), and
 * the merge engine preserves every line the app does not own verbatim.
 *
 * Public because the in-game settings editor's launch-conditions seam
 * carries [DesktopLaunchConditions] in its constructor contract (the
 * editor's drain resolves the same overlay the next launch enforces).
 */
object DesktopConfigWtf {

    private const val CONFIG_RELATIVE_PATH = "WTF/Config.wtf"

    /**
     * The desktop launch conditions for one launch. [audioMode] is the
     * persisted [com.pocketrealm.storage.Settings.AudioMode] name ("on"/
     * "off"); [soundChannels]/[soundChannelsEnabled] come from the launch's
     * effective tweak config (the Android managedSoundChannelsConfigLine
     * contract: the SoundSoftwareChannels line only rides an audio-ON
     * launch of a channel-patched client).
     */
    data class DesktopLaunchConditions(
        val audioMode: String,
        val screenResolution: String,
        val realmLoopback: Boolean,
        val soundChannelsEnabled: Boolean,
        val soundChannels: Int,
    )

    /** The enforced overlay for the desktop's portable subset. */
    fun enforcedKeys(conditions: DesktopLaunchConditions): List<ConfigWtfCodec.EnforcedLine> =
        ManagedConfigPolicy.enforcedKeys(
            ManagedConfigPolicy.LaunchConditions(
                renderer = RENDERER_D3D,
                resolution = conditions.screenResolution,
                gameMaximized = false,
                frameCap = FRAME_CAP_UNCAPPED,
                uiScale = null,
                audioMode = conditions.audioMode,
                realmLoopback = conditions.realmLoopback,
                soundChannelsEnabled = conditions.soundChannelsEnabled,
                soundChannels = conditions.soundChannels,
            ),
        )

    /**
     * Merges the enforced overlay over the user's `WTF/Config.wtf` (creating
     * it when absent) and writes the result back atomically. Returns the
     * merged text so callers/tests can pin the exact file content. A missing
     * WTF directory is created — a fresh install has no Config.wtf at all.
     */
    fun enforceAtLaunch(
        clientDir: File,
        conditions: DesktopLaunchConditions,
    ): String {
        val target = File(clientDir, CONFIG_RELATIVE_PATH)
        val base = if (target.isFile) {
            runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val merged = ConfigWtfCodec.merge(
            base,
            enforcedKeys(conditions),
        ).text
        target.parentFile?.mkdirs()
        writeAtomic(target, merged.toByteArray(Charsets.UTF_8))
        return merged
    }

    /** The launch conditions resolved from the session's facts. */
    fun conditions(
        audioMode: String,
        endpoint: RealmEndpoint,
        effectiveTweaks: ClientTweaksConfig,
        screenSize: Pair<Int, Int> = primaryScreenSize(),
    ): DesktopLaunchConditions = DesktopLaunchConditions(
        audioMode = audioMode,
        screenResolution = "${screenSize.first}x${screenSize.second}",
        realmLoopback = endpoint.isLoopback,
        soundChannelsEnabled = effectiveTweaks.soundChannelsEnabled,
        soundChannels = effectiveTweaks.soundChannels,
    )

    /**
     * The current primary display size, pure JVM (AWT Toolkit — the desktop
     * lane's resolution source; the headless edge falls back to the 1.12
     * client's own fallback 800x600 rather than throwing into the launch).
     */
    internal fun primaryScreenSize(): Pair<Int, Int> = runCatching {
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        size.width to size.height
    }.getOrDefault(DEFAULT_SCREEN_WIDTH to DEFAULT_SCREEN_HEIGHT)

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        java.io.FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private const val RENDERER_D3D = "d3d"

    /** maxFPS "0": the 1.12 engine's "no cap" — the desktop has no app frame cap. */
    private const val FRAME_CAP_UNCAPPED = 0

    private const val DEFAULT_SCREEN_WIDTH = 800
    private const val DEFAULT_SCREEN_HEIGHT = 600
}
