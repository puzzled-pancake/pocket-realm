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
 * the DataStore failure posture.
 */
class DesktopSettingsStore(private val file: File) {
    fun load(): Settings.Snapshot {
        if (!file.isFile) return Settings.Snapshot()
        return try {
            Settings.Snapshot.fromJson(file.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            Settings.Snapshot()
        }
    }

    fun save(snapshot: Settings.Snapshot) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${ProcessHandle.current().pid()}.tmp")
        temp.writeText(snapshot.toJson(), StandardCharsets.UTF_8)
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
