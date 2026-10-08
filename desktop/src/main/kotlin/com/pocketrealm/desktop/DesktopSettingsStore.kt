package com.pocketrealm.desktop

import com.pocketrealm.storage.Settings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file settings store (the Android app uses Jetpack DataStore; the
 * desktop has no DataStore, so this is the same contract over a plain
 * atomically-replaced file). Missing/corrupt file yields defaults, matching
 * the DataStore failure posture — and runs the DESK-13 schema-v2 defaults
 * migration over that empty store exactly like the Android DataStore runs
 * its migrations on first open of empty preferences (never-configured
 * widescreen installs gain the recommended tweak set). [resolvesWidescreen]
 * is injectable so that leg is testable.
 */
class DesktopSettingsStore(
    private val file: File,
    private val resolvesWidescreen: () -> Boolean = { Settings.resolvesWidescreenDisplay() },
) {
    fun load(): Settings.Snapshot {
        if (!file.isFile) return freshSnapshot()
        return try {
            Settings.Snapshot.fromJson(file.readText(StandardCharsets.UTF_8), resolvesWidescreen)
        } catch (_: Exception) {
            freshSnapshot()
        }
    }

    /** The never-configured store's snapshot: constructor defaults with the
     * DESK-13 tweaks defaults migration applied (schema 0, no stored JSON). */
    private fun freshSnapshot(): Settings.Snapshot = Settings.Snapshot(
        tweaks = Settings.clientTweaksDefaultsMigration(
            resolvesWidescreen = resolvesWidescreen,
            storedSchema = null,
            storedTweaksJson = null,
        ),
    )

    fun save(snapshot: Settings.Snapshot) {
        file.parentFile?.mkdirs()
        // nanoTime suffix: two writers in this process (UI thread + the
        // file-picker worker) must never share one temp path.
        val temp = File(
            file.parentFile,
            ".${file.name}.${ProcessHandle.current().pid()}.${System.nanoTime()}.tmp",
        )
        // fsync before the move (DataStore's durability posture): a plain
        // writeText can leave an empty/partial file persisted across a
        // power loss even after the rename lands.
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(snapshot.toJson().toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
