package com.pocketrealm.addons

import org.json.JSONObject
import java.io.File

/**
 * Two-file registry transaction with crash recovery.
 *
 * The journal contains the exact pre-operation bytes and existence bits for
 * both current and rollback registries. A crash or failed second publication
 * therefore restores both files, rather than silently consuming rollback
 * history while leaving the current registry unchanged.
 */
internal class AddonRegistryPublisher(
    private val registry: File,
    private val previousRegistry: File,
    private val journal: File,
    private val atomicWrite: (File, String) -> Unit,
    private val deleteFile: (File) -> Boolean = { file -> !file.exists() || file.delete() },
) {
    @Synchronized
    fun recoverIfNeeded() {
        if (!journal.isFile) return
        val transaction = JSONObject(journal.readText())
        require(transaction.getInt("schema") == JOURNAL_SCHEMA) {
            "Unsupported add-on registry transaction journal"
        }
        restore(registry, Snapshot.from(transaction.getJSONObject("registry")))
        restore(previousRegistry, Snapshot.from(transaction.getJSONObject("previous")))
        check(deleteFile(journal)) { "Recovered add-on transaction journal could not be cleared" }
    }

    @Synchronized
    fun publish(newRegistry: String) {
        recoverIfNeeded()
        val beforeRegistry = Snapshot.capture(registry)
        val beforePrevious = Snapshot.capture(previousRegistry)
        val transaction = JSONObject()
            .put("schema", JOURNAL_SCHEMA)
            .put("registry", beforeRegistry.toJson())
            .put("previous", beforePrevious.toJson())
        atomicWrite(journal, transaction.toString())

        try {
            atomicWrite(previousRegistry, beforeRegistry.content ?: EMPTY_REGISTRY)
            atomicWrite(registry, newRegistry)
            check(deleteFile(journal)) { "Add-on registry transaction journal could not be cleared" }
        } catch (failure: Throwable) {
            try {
                restore(registry, beforeRegistry)
                restore(previousRegistry, beforePrevious)
                check(deleteFile(journal)) { "Failed add-on registry journal could not be cleared" }
            } catch (restoreFailure: Throwable) {
                failure.addSuppressed(restoreFailure)
                throw IllegalStateException(
                    "Add-on registry publication failed and recovery remains pending",
                    failure,
                )
            }
            throw failure
        }
    }

    private fun restore(file: File, snapshot: Snapshot) {
        if (snapshot.existed) {
            atomicWrite(file, checkNotNull(snapshot.content))
        } else {
            check(deleteFile(file)) { "Add-on registry snapshot could not be removed" }
        }
    }

    private data class Snapshot(val existed: Boolean, val content: String?) {
        fun toJson(): JSONObject = JSONObject()
            .put("existed", existed)
            .put("content", content ?: JSONObject.NULL)

        companion object {
            fun capture(file: File): Snapshot = if (file.isFile) {
                Snapshot(existed = true, content = file.readText())
            } else {
                Snapshot(existed = false, content = null)
            }

            fun from(json: JSONObject): Snapshot {
                val existed = json.getBoolean("existed")
                val content = if (json.isNull("content")) null else json.getString("content")
                require(existed == (content != null)) { "Invalid add-on registry transaction snapshot" }
                return Snapshot(existed, content)
            }
        }
    }

    private companion object {
        const val JOURNAL_SCHEMA = 1
        const val EMPTY_REGISTRY = "{\"schema\":1,\"installed\":[]}"
    }
}
