package com.pocketrealm.pkg

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Persists a per-process **test-run ID** (a generated UUID) under the app's
 * runtime-state directory. This is explicitly a test-run identifier, NOT a boot
 * ID — it is regenerated only when the runtime-state file is absent or invalid,
 * so a single process lifetime maps to one run id across PKG experiments.
 *
 * Uses [Context.getDir] ("runtime-state", MODE_PRIVATE) so the path is resolved
 * from the actual Context, not a hard-coded /data/data path (which is wrong for
 * instrumented test processes and for backup/restore).
 *
 * Threading: read-once then cached; safe for single-process use from :main.
 */
object PkgRunIds {

    @Volatile private var cached: String? = null
    @Volatile private var cachedDir: File? = null

    /**
     * Returns the run id, resolving the state dir from [context]. The first
     * caller's context wins the cached dir; subsequent callers reuse it. The id
     * itself is stable within a process.
     */
    fun current(context: Context): String {
        cached?.let { return it }
        val dir = cachedDir ?: context.getDir("runtime-state", android.content.Context.MODE_PRIVATE)
            .apply { mkdirs() }
        cachedDir = dir
        val idFile = File(dir, "pkg-test-run-id")
        val id = if (idFile.isFile) {
            idFile.readText().trim().ifEmpty { newUuid() }
        } else newUuid()
        runCatching {
            val tmp = File(idFile.parentFile, "pkg-test-run-id.tmp")
            tmp.writeText(id)
            tmp.renameTo(idFile)
        }
        cached = id
        return id
    }

    /**
     * Context-less overload for host-JVM unit tests only: backs the id with the
     * java.io.tmpdir. Production callers must use [current] with a Context so the
     * state dir resolves to the app's private storage, not a hard-coded path.
     */
    fun current(): String {
        cached?.let { return it }
        val dir = cachedDir ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "pocket-runtime-state")
            .apply { mkdirs() }
        cachedDir = dir
        val idFile = File(dir, "pkg-test-run-id")
        val id = if (idFile.isFile) idFile.readText().trim().ifEmpty { newUuid() } else newUuid()
        runCatching {
            val tmp = File(idFile.parentFile, "pkg-test-run-id.tmp")
            tmp.writeText(id); tmp.renameTo(idFile)
        }
        cached = id
        return id
    }

    /** The resolved runtime-state dir (for diagnostics); null until [current]. */
    fun dir(): File? = cachedDir

    private fun newUuid() = UUID.randomUUID().toString()
}
