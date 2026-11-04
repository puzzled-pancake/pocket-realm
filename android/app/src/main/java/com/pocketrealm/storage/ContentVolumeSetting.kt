package com.pocketrealm.storage

import android.content.Context
import java.io.File

/**
 * Which physical volume hosts the immutable content roots (imported client
 * generations, staged archives, prepared server data). Plain SharedPreferences,
 * not the DataStore settings machine: [StorageRoots] resolves roots
 * synchronously at construction, before DataStore has a warm read, and every
 * consumer of the roots must see one stable answer per process.
 */
enum class ContentVolume { INTERNAL, REMOVABLE }

object ContentVolumeSetting {
    private const val PREFS = "storage_layout"
    private const val KEY = "content_volume"

    fun read(context: Context): ContentVolume {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return if (stored == ContentVolume.REMOVABLE.name) ContentVolume.REMOVABLE else ContentVolume.INTERNAL
    }

    fun write(context: Context, volume: ContentVolume) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, volume.name).apply()
    }

    /**
     * The app's private directory on the first removable volume, or null when
     * no removable storage is mounted. Android grants the owning app direct
     * path access to its own directory on a portable volume; no SAF grant is
     * involved.
     */
    fun removableDir(context: Context): File? =
        context.getExternalFilesDirs(null).filterNotNull()
            .firstOrNull { !it.absolutePath.startsWith("/storage/emulated") }
}
