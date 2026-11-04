package com.pocketrealm.storage

import android.content.Context
import java.io.File

/**
 * Defines and creates the separated storage roots.
 *
 * Hard separation between:
 *  - **mutable realm data** (internal app storage, never user-visible),
 *  - **immutable imported content** (generations of verified client/extracted data),
 *  - **runtime generations** (prefixes, caches, addon/visual overlays),
 *  - **exports** (user-facing output via SAF; never the live DB).
 *
 * Per persistence rules: mutable realm data lives on internal UFS by default;
 * removable storage is for imports/exports and optional immutable content only.
 */
class StorageRoots(context: Context) {

    private val appContext = context.applicationContext

    /** Root of all mutable, internal, never-user-visible realm state. */
    val realmData: File = File(appContext.filesDir, "realm").apply { mkdirs() }

    /**
     * Database ownership boundary. It is excluded from Android backup and
     * owned exclusively by the `:database` service. No other component may
     * copy or edit files below [databaseDatadir] while MariaDB is running.
     */
    val databaseRoot: File = File(appContext.noBackupFilesDir, "database").apply { mkdirs() }
    val databaseDatadir: File = File(databaseRoot, "data").apply { mkdirs() }
    val databaseRun: File = File(databaseRoot, "run").apply { mkdirs() }
    val databaseSnapshots: File = File(databaseRoot, "snapshots").apply { mkdirs() }

    /** Compatibility name for legacy callers; the service remains sole owner. */
    val realmDatabase: File get() = databaseDatadir

    /** Verified recovery generations (>=2 retained). */
    val generations: File = File(realmData, "generations").apply { mkdirs() }

    /** Dirty-generation marker + metadata for abrupt-stop recovery. */
    val recoveryState: File = File(realmData, "recovery.json")

    /**
     * Imported client generations, staged archives, and prepared server data
     * ("content"). With [ContentVolume.REMOVABLE] these live in the app's
     * private directory on the SD card; the realm database, runtime prefixes,
     * and journal stay on internal UFS regardless.
     */
    val contentVolume: ContentVolume = resolveContentVolume()
    val content: File = contentDir(contentVolume).apply { mkdirs() }

    /** Canonical root for client generations + staged imports ([ManagedClientImporter]). */
    val client: File = clientDir(contentVolume).apply { mkdirs() }

    /** True when the user asked for the SD card but none was mounted. */
    val removableUnavailable: Boolean =
        contentVolume == ContentVolume.INTERNAL && ContentVolumeSetting.read(appContext) == ContentVolume.REMOVABLE

    private fun resolveContentVolume(): ContentVolume =
        if (ContentVolumeSetting.read(appContext) == ContentVolume.REMOVABLE &&
            ContentVolumeSetting.removableDir(appContext) != null
        ) ContentVolume.REMOVABLE else ContentVolume.INTERNAL

    private fun contentDir(volume: ContentVolume): File = when (volume) {
        ContentVolume.INTERNAL -> File(appContext.filesDir, "content")
        ContentVolume.REMOVABLE -> File(ContentVolumeSetting.removableDir(appContext), "content")
    }

    private fun clientDir(volume: ContentVolume): File = when (volume) {
        ContentVolume.INTERNAL -> File(appContext.noBackupFilesDir, "client")
        ContentVolume.REMOVABLE -> File(ContentVolumeSetting.removableDir(appContext), "client")
    }

    /** Runtime generations: Wine prefixes, shader/dynarec caches, addon/visual overlays. */
    val runtime: File = File(appContext.filesDir, "runtime").apply { mkdirs() }

    /**
     * App-private cache for transient build/extraction work. Cleared by OS under
     * pressure; never holds durable realm state.
     */
    val scratch: File = File(appContext.cacheDir, "scratch").apply { mkdirs() }

    /**
     * Exports directory on app-private external storage (accessible via SAF).
     * This is the ONLY place realm-derived data leaves internal storage, and it
     * never mirrors the live database (see persistence rules).
     */
    val exports: File = File(appContext.getExternalFilesDir(null), "exports").apply { mkdirs() }

    /** Verify the root layout is fully present; used in diagnostics + wizard. */
    fun verify(): Report {
        val roots = listOf(
            "realm" to realmData,
            "database" to databaseRoot,
            "database/data" to databaseDatadir,
            "database/run" to databaseRun,
            "database/snapshots" to databaseSnapshots,
            "realm/generations" to generations,
            "content" to content,
            "client" to client,
            "runtime" to runtime,
            "scratch" to scratch,
            "exports" to exports,
        )
        return Report(
            roots = roots.map { (name, dir) ->
                RootStatus(name, dir.absolutePath, dir.exists(), usableBytes(dir))
            },
            mutableRootIsInternal = realmData.startsWith(appContext.filesDir) &&
                databaseRoot.startsWith(appContext.noBackupFilesDir),
            contentVolume = contentVolume,
            removableUnavailable = removableUnavailable,
        )
    }

    private fun usableBytes(dir: File): Long = dir.usableSpace

    data class RootStatus(val name: String, val path: String, val exists: Boolean, val usableBytes: Long)
    data class Report(
        val roots: List<RootStatus>,
        val mutableRootIsInternal: Boolean,
        val contentVolume: ContentVolume,
        val removableUnavailable: Boolean,
    ) {
        val ok: Boolean get() = roots.all { it.exists } && mutableRootIsInternal
    }

    companion object {
        private const val TAG = "PR/StorageRoots"
        @Volatile private var instance: StorageRoots? = null
        fun get(context: Context): StorageRoots =
            instance ?: synchronized(this) {
                instance ?: StorageRoots(context.applicationContext).also { instance = it }
            }

        /** Drop the cached instance so the next [get] re-resolves the content volume. */
        fun reset() {
            synchronized(this) { instance = null }
        }
    }
}
