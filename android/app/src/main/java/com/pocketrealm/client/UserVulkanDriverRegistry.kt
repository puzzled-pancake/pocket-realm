package com.pocketrealm.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** One user-imported Mesa Turnip ICD pair stored under the app's private files. */
data class UserVulkanDriver(
    val id: String,
    val label: String,
    val libraryFileName: String,
    val sha256: String,
    val vulkanApiVersion: String?,
    val addedAt: Long,
    val earlyCrashStreak: Int = 0,
    val quarantined: Boolean = false,
    val quarantineReason: String? = null,
) {
    init {
        require(ID.matches(id)) { "invalid user Vulkan driver id: $id" }
        require(label.isNotBlank()) { "user Vulkan driver label is absent" }
        require(label.length <= 64) { "user Vulkan driver label is too long" }
        require(SHA256.matches(sha256)) { "invalid user Vulkan driver sha256" }
        require(libraryFileName == LIBRARY_FILE_NAME) {
            "user Vulkan driver storage name must be $LIBRARY_FILE_NAME"
        }
        require(earlyCrashStreak >= 0) { "crash streak must be non-negative" }
        if (quarantined) require(!quarantineReason.isNullOrBlank()) {
            "a quarantined user driver must carry its reason"
        } else require(quarantineReason == null) {
            "an unquarantined user driver cannot carry a quarantine reason"
        }
    }

    val slug: String get() = id.removePrefix(ID_PREFIX)

    companion object {
        const val ID_PREFIX = "user-"
        const val LIBRARY_FILE_NAME = "driver.so"
        const val ICD_FILE_NAME = "icd.json"
        const val META_FILE_NAME = "meta.json"
        private val ID = Regex("user-[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** User-lane ids live in their own namespace and can never collide with catalog ids. */
        fun isUserId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)
    }
}

sealed class UserVulkanDriverImport {
    data class Imported(val driver: UserVulkanDriver, val warning: String?) : UserVulkanDriverImport()
    data class Rejected(val reason: String) : UserVulkanDriverImport()
}

/**
 * App-private registry of user-imported Vulkan drivers.
 *
 * Layout under [root] (normally `<filesDir>/drivers`):
 *  - `registry.json` — versioned schema, written temp + atomic rename;
 *  - `<slug>/driver.so` + `<slug>/icd.json` — the imported payload.
 *
 * Imports are staged into a `.incoming-<uuid>` sibling directory and only
 * renamed into place after validation, so a cancelled or failed import can
 * never leave a partial registry entry or a half-written payload directory.
 */
class UserVulkanDriverRegistry(private val root: File) {

    fun registryFile(): File = File(root, "registry.json")

    fun driverDirectory(id: String): File {
        require(UserVulkanDriver.isUserId(id)) { "not a user driver id: $id" }
        val slug = id.removePrefix(UserVulkanDriver.ID_PREFIX)
        return File(root, slug).canonicalFile.also {
            check(it.parentFile?.canonicalFile == root.canonicalFile) {
                "user driver directory escaped the registry root"
            }
        }
    }

    fun libraryFile(id: String): File = File(driverDirectory(id), UserVulkanDriver.LIBRARY_FILE_NAME)

    fun icdFile(id: String): File = File(driverDirectory(id), UserVulkanDriver.ICD_FILE_NAME)

    /** Sorted by import date, newest first — the order the Settings list shows. */
    fun list(): List<UserVulkanDriver> =
        readRegistry().sortedWith(compareByDescending<UserVulkanDriver> { it.addedAt }.thenBy { it.id })

    fun find(id: String?): UserVulkanDriver? =
        id?.takeIf(UserVulkanDriver::isUserId)?.let { wanted -> readRegistry().firstOrNull { it.id == wanted } }

    /**
     * Import a payload already copied to app-private storage (the SAF copy is
     * done by the caller — no document URI is ever retained here). [payload]
     * is either a bare `.so` or a `.zip` archive containing exactly one `.so`,
     * optionally one ICD JSON, and optionally an AdrenoTools `meta.json`
     * (the Eden/K11MCH1 pack format) whose `name`/`driverVersion` become the
     * label and the Vulkan version when the zip carries no ICD api_version.
     */
    fun import(
        displayName: String,
        payload: File,
        maxImportBytes: Long = UserVulkanDriverValidator.DEFAULT_MAX_IMPORT_BYTES,
    ): UserVulkanDriverImport {
        val fallbackLabel = displayName.trim().take(64).ifBlank { "Imported driver" }
        val incoming = File(root, ".incoming-${UUID.randomUUID()}")
        try {
            root.mkdirs()
            check(incoming.mkdir() && incoming.isDirectory) {
                "could not stage the import directory"
            }
            unpack(payload, incoming, maxImportBytes)?.let {
                return UserVulkanDriverImport.Rejected(it)
            }
            val library = File(incoming, UserVulkanDriver.LIBRARY_FILE_NAME)
            val icd = File(incoming, UserVulkanDriver.ICD_FILE_NAME)
            val elf = UserVulkanDriverValidator.validateElf(library, maxImportBytes)
            val sha256 = (elf as? UserVulkanDriverValidator.ElfOutcome.Accepted)?.sha256
                ?: return UserVulkanDriverImport.Rejected(
                    (elf as UserVulkanDriverValidator.ElfOutcome.Rejected).reason)
            var apiVersion: String? = null
            if (icd.isFile) {
                if (icd.length() > MAX_ICD_BYTES) {
                    return UserVulkanDriverImport.Rejected(
                        "The ICD manifest is ${icd.length()} bytes; manifests are tiny — " +
                            "this file is not an ICD.",
                    )
                }
                when (val outcome = UserVulkanDriverValidator.validateIcd(icd.readText())) {
                    is UserVulkanDriverValidator.IcdOutcome.Accepted -> apiVersion = outcome.apiVersion
                    is UserVulkanDriverValidator.IcdOutcome.Rejected ->
                        return UserVulkanDriverImport.Rejected(outcome.reason)
                }
            } else {
                writeSyntheticIcd(icd)
            }
            var metaLabel: String? = null
            val meta = File(incoming, UserVulkanDriver.META_FILE_NAME)
            if (meta.isFile) {
                if (meta.length() > MAX_ICD_BYTES) {
                    return UserVulkanDriverImport.Rejected(
                        "The AdrenoTools meta.json is ${meta.length()} bytes; driver metadata " +
                            "is tiny — this file is not a meta.json.",
                    )
                }
                when (val outcome = UserVulkanDriverValidator.validateMeta(meta.readText())) {
                    is UserVulkanDriverValidator.MetaOutcome.Accepted -> {
                        metaLabel = outcome.label
                        // A real ICD api_version stays authoritative; meta only fills the gap.
                        if (apiVersion == null) apiVersion = outcome.apiVersion
                    }
                    is UserVulkanDriverValidator.MetaOutcome.Rejected ->
                        return UserVulkanDriverImport.Rejected(outcome.reason)
                }
                // Display metadata only — it never lingers in the stored layout.
                meta.delete()
            }
            val label = metaLabel ?: fallbackLabel
            val warning = apiVersion?.let(UserVulkanDriverValidator::apiVersionWarning)
            val driver = withRegistryMutationLock {
                val current = readRegistry()
                val candidate = UserVulkanDriver(
                    id = uniqueIdFor(label, current.map { it.id }.toSet()),
                    label = label,
                    libraryFileName = UserVulkanDriver.LIBRARY_FILE_NAME,
                    sha256 = sha256,
                    vulkanApiVersion = apiVersion,
                    addedAt = System.currentTimeMillis(),
                )
                val destination = driverDirectory(candidate.id)
                check(!destination.exists()) { "user driver directory already exists" }
                java.nio.file.Files.move(
                    incoming.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                try {
                    saveRegistry(current + candidate)
                } catch (error: Throwable) {
                    // Never leave a payload directory no registry entry points at.
                    destination.deleteRecursively()
                    throw error
                }
                candidate
            }
            return UserVulkanDriverImport.Imported(driver, warning)
        } finally {
            if (incoming.exists()) incoming.deleteRecursively()
        }
    }

    fun remove(id: String) {
        withRegistryMutationLock {
            val current = readRegistry()
            val driver = current.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown user Vulkan driver: $id")
            driverDirectory(id).deleteRecursively()
            saveRegistry(current - driver)
        }
    }

    /**
     * Atomically replace one entry (crash-streak and quarantine updates).
     * The whole read-modify-write runs under the registry mutation lock so a
     * concurrent import/remove in another process cannot interleave.
     */
    fun update(driver: UserVulkanDriver) {
        withRegistryMutationLock {
            val current = readRegistry()
            val index = current.indexOfFirst { it.id == driver.id }
            if (index < 0) throw IllegalArgumentException("unknown user Vulkan driver: ${driver.id}")
            saveRegistry(current.toMutableList().apply { set(index, driver) })
        }
    }

    /** Unpacks the payload into [incoming]; returns the rejection reason, or null on success. */
    private fun unpack(payload: File, incoming: File, maxImportBytes: Long): String? {
        if (payload.length() > maxImportBytes) {
            return sizeRejection(payload.length(), maxImportBytes)
        }
        return if (isZip(payload)) {
            unpackZip(payload, incoming, maxImportBytes)
        } else {
            val target = File(incoming, UserVulkanDriver.LIBRARY_FILE_NAME)
            payload.inputStream().use { stream -> copyCapped(stream, target, maxImportBytes) }
        }
    }

    private fun unpackZip(payload: File, incoming: File, maxImportBytes: Long): String? {
        ZipInputStream(payload.inputStream().buffered()).use { zip ->
            val libraries = mutableListOf<String>()
            val manifests = mutableListOf<String>()
            val metas = mutableListOf<String>()
            val others = mutableListOf<String>()
            var unsafePath: String? = null
            // nextEntry() must inflate each entry to find the next header, so
            // drain with a cumulative cap — a hostile archive otherwise burns
            // unbounded CPU before the write-side caps are ever consulted.
            var inflatedBytes = 0L
            val drain = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                when {
                    base.endsWith(".so", ignoreCase = true) -> libraries += base
                    base.equals(UserVulkanDriver.META_FILE_NAME, ignoreCase = true) -> metas += base
                    base.endsWith(".json", ignoreCase = true) -> manifests += base
                    else -> others += base
                }
                if (unsafePath == null && (entry.name.contains("..") || entry.name.startsWith("/"))) {
                    unsafePath = entry.name
                }
                while (true) {
                    val read = zip.read(drain)
                    if (read < 0) break
                    inflatedBytes += read
                    if (inflatedBytes > maxImportBytes) {
                        return sizeRejection(inflatedBytes, maxImportBytes)
                    }
                }
            }
            unsafePath?.let { return "The archive contains an unsafe entry path ($it)." }
            if (libraries.isEmpty()) {
                return "The archive contains no .so Vulkan driver library."
            }
            if (libraries.size > 1) {
                return "The archive must contain exactly one .so library " +
                    "(found ${libraries.size}: ${libraries.joinToString(", ")})."
            }
            if (manifests.size > 1) {
                return "The archive may contain at most one ICD JSON manifest (found ${manifests.size})."
            }
            if (metas.size > 1) {
                return "The archive may contain at most one AdrenoTools meta.json " +
                    "(found ${metas.size})."
            }
            if (others.isNotEmpty()) {
                return "The archive must contain only the driver library, its optional ICD " +
                    "manifest, and an optional AdrenoTools meta.json " +
                    "(unexpected: ${others.joinToString(", ")})."
            }
        }
        // Second pass writes the accepted entries under their canonical names.
        ZipInputStream(payload.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                val target = when {
                    base.endsWith(".so", ignoreCase = true) -> File(incoming, UserVulkanDriver.LIBRARY_FILE_NAME)
                    base.equals(UserVulkanDriver.META_FILE_NAME, ignoreCase = true) ->
                        File(incoming, UserVulkanDriver.META_FILE_NAME)
                    base.endsWith(".json", ignoreCase = true) -> File(incoming, UserVulkanDriver.ICD_FILE_NAME)
                    else -> null
                } ?: continue
                copyCapped(zip, target, maxImportBytes)?.let { return it }
            }
        }
        return null
    }

    /** Copies without closing [source] — it may be the shared zip stream. */
    private fun copyCapped(source: InputStream, target: File, maxImportBytes: Long): String? {
        target.outputStream().use { output -> return transferCapped(source, output, maxImportBytes) }
    }

    private fun transferCapped(input: InputStream, output: java.io.OutputStream, cap: Long): String? {
        var copied = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > cap) return sizeRejection(copied, cap)
            output.write(buffer, 0, read)
        }
        return null
    }

    private fun sizeRejection(actual: Long, cap: Long): String =
        UserVulkanDriverValidator.sizeRejection(actual, cap)

    private fun isZip(payload: File): Boolean {
        if (payload.length() < 4) return false
        payload.inputStream().use { input ->
            val magic = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(magic, read, 4 - read)
                if (n < 0) break
                read += n
            }
            return read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() && magic[3] == 4.toByte()
        }
    }

    private fun writeSyntheticIcd(target: File) {
        // Mirrors the packaged manifests' shape; the rootfs install rewrites
        // library_path to the installed library path at prepare time.
        target.writeText(
            JSONObject()
                .put("ICD", JSONObject().put("library_path", UserVulkanDriver.LIBRARY_FILE_NAME))
                .put("file_format_version", "1.0.0")
                .toString(),
        )
    }

    private fun uniqueIdFor(label: String, taken: Set<String>): String {
        val base = slugify(label)
        var candidate = "${UserVulkanDriver.ID_PREFIX}$base"
        var suffix = 2
        while (candidate in taken || driverDirectory(candidate).exists()) {
            candidate = "${UserVulkanDriver.ID_PREFIX}$base-$suffix"
            suffix++
        }
        return candidate
    }

    /**
     * Serialize read-modify-write mutations. The UI process (import/remove)
     * and the :client process (crash-guard update) both take the same
     * OS file lock on `<root>/.registry.lock`; a per-root JVM monitor keeps
     * same-process instances from overlapping on the channel.
     */
    private fun <T> withRegistryMutationLock(block: () -> T): T {
        root.mkdirs()
        val monitor = MUTATION_MONITORS.computeIfAbsent(root.canonicalFile.absolutePath) { Any() }
        synchronized(monitor) {
            RandomAccessFile(File(root, REGISTRY_LOCK_FILE_NAME), "rw").use { lockFile ->
                lockFile.channel.lock().use { _ -> return block() }
            }
        }
    }

    private fun readRegistry(): List<UserVulkanDriver> {
        val file = registryFile()
        if (!file.isFile) return emptyList()
        val document = try {
            JSONObject(file.readText())
        } catch (error: org.json.JSONException) {
            throw IllegalStateException(
                "The imported-driver registry is unreadable (registry.json is corrupt); " +
                    "delete and re-import the driver.",
                error,
            )
        }
        val schema = document.optInt("schema", 0)
        check(schema == SCHEMA) {
            "user Vulkan driver registry schema $schema is not supported (expected $SCHEMA)"
        }
        val drivers = mutableListOf<UserVulkanDriver>()
        val entries = document.optJSONArray("drivers") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            drivers += UserVulkanDriver(
                id = entry.getString("id"),
                label = entry.getString("label"),
                libraryFileName = entry.getString("libraryFileName"),
                sha256 = entry.getString("sha256"),
                vulkanApiVersion = entry.optString("vulkanApiVersion").takeIf { it.isNotBlank() },
                addedAt = entry.getLong("addedAt"),
                earlyCrashStreak = entry.optInt("earlyCrashStreak", 0),
                quarantined = entry.optBoolean("quarantined", false),
                quarantineReason = entry.optString("quarantineReason").takeIf { it.isNotBlank() },
            )
        }
        return drivers
    }

    private fun saveRegistry(drivers: List<UserVulkanDriver>) {
        root.mkdirs()
        val document = JSONObject()
            .put("schema", SCHEMA)
            .put("drivers", JSONArray().apply {
                drivers.sortedBy { it.addedAt }.forEach { driver ->
                    put(
                        JSONObject()
                            .put("id", driver.id)
                            .put("label", driver.label)
                            .put("libraryFileName", driver.libraryFileName)
                            .put("sha256", driver.sha256)
                            .putOpt("vulkanApiVersion", driver.vulkanApiVersion ?: JSONObject.NULL)
                            .put("addedAt", driver.addedAt)
                            .put("earlyCrashStreak", driver.earlyCrashStreak)
                            .put("quarantined", driver.quarantined)
                            .putOpt(
                                "quarantineReason",
                                driver.quarantineReason ?: JSONObject.NULL,
                            ),
                    )
                }
            })
        val temp = File(root, ".registry.json.tmp-${UUID.randomUUID()}")
        temp.writeText(document.toString())
        try {
            java.nio.file.Files.move(
                temp.toPath(), registryFile().toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        const val SCHEMA = 1

        /** Real ICD manifests are a few hundred bytes; anything large is not one. */
        const val MAX_ICD_BYTES: Long = 64L * 1024

        /** App-private storage root for the registry (normally `<filesDir>/drivers`). */
        fun registryRoot(filesDir: File): File = File(filesDir, "drivers")

        private const val REGISTRY_LOCK_FILE_NAME = ".registry.lock"
        private val MUTATION_MONITORS = ConcurrentHashMap<String, Any>()

        /**
         * Charset `[a-z0-9-]` only: the slug becomes a directory name and an
         * id fragment, so path traversal and catalog-id shapes are unreachable
         * by construction.
         */
        fun slugify(label: String): String =
            label.lowercase(Locale.ROOT)
                .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '-' }
                .joinToString("")
                .split('-').filter { it.isNotBlank() }.joinToString("-")
                .take(48)
                .trimEnd('-')
                .ifBlank { "driver" }
    }
}
