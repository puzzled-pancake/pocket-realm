package com.pocketrealm.server

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/** Fail-closed reader for the immutable O11 normal-play data generation. */
internal class PreparedDataStore(private val root: File) {
    /**
     * Cheap supervisor boundary: authenticate the active pointer and manifest
     * identity/schema without reading every multi-gigabyte data file.
     */
    fun requireActiveEnvelope(): ActiveData {
        val pointerFile = File(root, "active.json")
        check(pointerFile.isFile) {
            "Server world data is not ready yet. Open Game files, finish preparing server data, then try again."
        }
        val pointer = JSONObject(pointerFile.readText())
        check(pointer.getInt("schema") == 1 && pointer.getString("mode") == "NORMAL") {
            "The prepared server world data belongs to an incompatible app version. Rebuild it from Game files."
        }
        val id = pointer.getString("generation")
        check(id.matches(Regex("[0-9a-f-]{36}"))) {
            "The prepared server world data record is damaged. Resume preparation from Game files."
        }
        val generation = File(File(root, "generations"), id).canonicalFile
        val generations = File(root, "generations").canonicalFile
        check(generation.parentFile == generations && generation.isDirectory) {
            "The prepared server world data is missing. Resume preparation from Game files."
        }
        val manifestFile = File(generation, "data-manifest.json")
        check(manifestFile.isFile && sha256(manifestFile) == pointer.getString("manifestSha256")) {
            "The prepared server world data did not pass its integrity check. Resume preparation from Game files."
        }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 1 && manifest.getBoolean("complete") &&
            manifest.getString("mode") == "NORMAL" && manifest.getInt("clientBuild") == 5875 &&
            manifest.getString("cmangosFamily") == "classic") {
            "The prepared server world data is incomplete or incompatible. Rebuild it from Game files."
        }
        val counts = manifest.getJSONObject("counts")
        check(counts.getInt("dbc") >= 100 && counts.getInt("maps") >= 100 &&
            counts.getInt("vmapTrees") >= 1 && counts.getInt("mmapMaps") >= 1 &&
            counts.getInt("mmapTiles") >= 1) {
            "The prepared server world data is missing maps needed to play. Resume preparation from Game files."
        }
        val files = manifest.getJSONArray("files")
        check(files.length() > 0) { "The prepared server world data contains no verified files. Rebuild it." }
        for (index in 0 until files.length()) {
            val relative = files.getJSONObject(index).getString("path")
            check(relative.substringBefore('/') in REQUIRED_DIRS) {
                "The prepared server world data contains an unexpected file: $relative"
            }
            val file = File(generation, relative).canonicalFile
            check(file.toPath().startsWith(generation.toPath()) && file != generation) {
                "The prepared server world data contains an unsafe file path: $relative"
            }
        }
        return ActiveData(id, generation, pointer.getString("manifestSha256"))
    }

    /** Authoritative verification, called once in the world process before native start. */
    fun requireActive(): ActiveData {
        val active = requireActiveEnvelope()
        val manifest = JSONObject(File(active.root, "data-manifest.json").readText())
        val files = manifest.getJSONArray("files")
        for (index in 0 until files.length()) {
            val record = files.getJSONObject(index)
            val relative = record.getString("path")
            val file = File(active.root, relative).canonicalFile
            check(file.toPath().startsWith(active.root.toPath()) && file != active.root && file.isFile &&
                file.length() == record.getLong("size") && sha256(file) == record.getString("sha256")) {
                "A prepared server world-data file failed verification: $relative. Resume preparation to repair it."
            }
        }
        return active
    }

    fun acquireRuntimeLease(): GenerationLease = acquireLease(root, shared = true,
        failure = "normal-play data is being published")

    data class ActiveData(val generation: String, val root: File, val manifestSha256: String)

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    internal class GenerationLease(
        private val owner: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            owner.close()
        }
    }

    companion object {
        private val REQUIRED_DIRS = setOf("dbc", "maps", "vmaps", "mmaps")
        private const val LEASE_FILE = ".generation-lease"

        fun acquirePublicationLease(root: File): GenerationLease = acquireLease(
            root,
            shared = false,
            failure = "stop the realm before publishing normal-play data",
        )

        private fun acquireLease(root: File, shared: Boolean, failure: String): GenerationLease {
            check(root.isDirectory || root.mkdirs()) { "normal-play data root is unavailable" }
            val owner = RandomAccessFile(File(root, LEASE_FILE), "rw")
            val lock = try {
                owner.channel.tryLock(0L, Long.MAX_VALUE, shared)
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Throwable) {
                owner.close()
                throw error
            }
            if (lock == null) {
                owner.close()
                error(failure)
            }
            return GenerationLease(owner, lock)
        }
    }
}
