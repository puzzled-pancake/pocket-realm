package com.pocketrealm.storage

import android.content.Context
import com.pocketrealm.realm.RealmSupervisor
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

    /** The live character database directory (a protected generation lives here). */
    val realmDatabase: File = File(realmData, "db").apply { mkdirs() }

    /** Verified recovery generations (>=2 retained per PLAN.md A3). */
    val generations: File = File(realmData, "generations").apply { mkdirs() }

    /** Dirty-generation marker + metadata for abrupt-stop recovery (O08). */
    val recoveryState: File = File(realmData, "recovery.json")

    /** Immutable imported content generations (client build, extracted maps/DBC). */
    val content: File = File(appContext.filesDir, "content").apply { mkdirs() }

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
            "realm/db" to realmDatabase,
            "realm/generations" to generations,
            "content" to content,
            "runtime" to runtime,
            "scratch" to scratch,
            "exports" to exports,
        )
        return Report(
            roots = roots.map { (name, dir) ->
                RootStatus(name, dir.absolutePath, dir.exists(), usableBytes(dir))
            },
            mutableRootIsInternal = realmData.startsWith(appContext.filesDir),
        )
    }

    private fun usableBytes(dir: File): Long = dir.usableSpace

    data class RootStatus(val name: String, val path: String, val exists: Boolean, val usableBytes: Long)
    data class Report(val roots: List<RootStatus>, val mutableRootIsInternal: Boolean) {
        val ok: Boolean get() = roots.all { it.exists } && mutableRootIsInternal
    }

    companion object {
        private const val TAG = "PR/StorageRoots"
        @Volatile private var instance: StorageRoots? = null
        fun get(context: Context): StorageRoots =
            instance ?: synchronized(this) {
                instance ?: StorageRoots(context.applicationContext).also { instance = it }
            }
    }
}
