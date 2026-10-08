package com.pocketrealm.server

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardCopyOption

/** Fail-closed reader for the immutable normal-play data generation. */
internal class PreparedDataStore(private val root: File) {
    /**
     * Cheap supervisor boundary: authenticate the active pointer and manifest
     * identity/schema without reading every multi-gigabyte data file. The
     * lane's [ClientFlavor] parameterizes the pointer namespace and the
     * manifest identity gate (G3-5 defense-in-depth: the vanilla lane can
     * never authenticate onto a TBC generation even if a pointer were
     * hand-mangled); the default resolves the Home flavor switcher's ACTIVE
     * lane through [ActiveFlavor] — per-flavor generations, loud on zero or
     * multiple qualified flavors without an explicit selection.
     */
    fun requireActiveEnvelope(
        flavor: ClientFlavor = ActiveFlavor.resolve(SERVER_DATA_LANE),
    ): ActiveData {
        val pointerFile = activePointerFor(flavor)
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
        validateDataManifestEnvelope(manifest, flavor)
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
    fun requireActive(flavor: ClientFlavor = ActiveFlavor.resolve(SERVER_DATA_LANE)): ActiveData {
        val active = requireActiveEnvelope(flavor)
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

    /**
     * G3-5 legacy seed (data lane: copy-then-adopt). On first read, when the
     * canonical per-flavor pointer is absent and the un-namespaced
     * `active.json` exists, copy it under the canonical name and continue
     * there — the copy keeps the source intact for a concurrent reader that
     * already opened it, and the canonical name wins the moment it exists
     * (never both readable as authoritative).
     */
    private fun activePointerFor(flavor: ClientFlavor): File {
        val canonical = File(root, "active-${flavor.flavorId}.json")
        if (!canonical.isFile) {
            val legacy = File(root, "active.json")
            if (legacy.isFile) {
                val temp = File(root, ".active-${flavor.flavorId}.adopt.tmp")
                java.nio.file.Files.copy(legacy.toPath(), temp.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                java.nio.file.Files.move(temp.toPath(), canonical.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return canonical
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

        /** The [ActiveFlavor] lane suffix naming this store's gate. */
        private const val SERVER_DATA_LANE = "server world data resolves the qualified lane"

        /**
         * The pure data-manifest identity checks of [requireActiveEnvelope]
         * (schema 1 stays; G3-2): the flavor is an OPTIONAL alias key —
         * pre-flavor manifests (no key) are fine and resolve through
         * `cmangosFamily`/`clientBuild`, but a stored flavor that disagrees
         * with the authenticating lane is refused (fail-closed, no silent
         * coercion). The manifest schema number never increments to
         * introduce the key.
         */
        internal fun validateDataManifestEnvelope(manifest: JSONObject, flavor: ClientFlavor) {
            check(manifest.getInt("schema") == 1 && manifest.getBoolean("complete") &&
                manifest.getString("mode") == "NORMAL" && manifest.getInt("clientBuild") == flavor.build &&
                manifest.getString("cmangosFamily") == flavor.serverFamily) {
                "The prepared server world data is incomplete or incompatible. Rebuild it from Game files."
            }
            check(manifest.optString("flavor", flavor.flavorId) == flavor.flavorId) {
                "The prepared server world data was built for the \"${manifest.optString("flavor")}\" flavor, " +
                    "but this lane is \"${flavor.flavorId}\". Rebuild it from Game files."
            }
        }

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
