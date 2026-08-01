package com.pocketrealm.pkg

import java.io.File
import java.util.UUID

/**
 * Persists a per-process **test-run ID** (a generated UUID) under the app's
 * runtime-state directory. This is explicitly a test-run identifier, NOT a boot
 * ID — it is regenerated only when the runtime-state file is absent or invalid,
 * so a single process lifetime maps to one run id across PKG experiments.
 *
 * Threading: read-once then cached; safe for single-process use from :main.
 */
object PkgRunIds {
    @Volatile private var cached: String? = null

    fun current(): String {
        cached?.let { return it }
        val dir = File(System.getProperty("java.io.tmpdir", "/data/data/com.pocketrealm/files"))
        // Prefer the app runtime-state dir when available.
        val stateDir = File(runStateDir(), "runtime-state").apply { mkdirs() }
        val idFile = File(stateDir, "pkg-test-run-id")
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

    private fun newUuid() = UUID.randomUUID().toString()

    private fun runStateDir(): String =
        System.getenv("POCKET_RUNTIME_STATE")
            ?: System.getProperty("pocket.runtimeState")
            ?: "/data/data/com.pocketrealm/noBackupFilesDir"
}
