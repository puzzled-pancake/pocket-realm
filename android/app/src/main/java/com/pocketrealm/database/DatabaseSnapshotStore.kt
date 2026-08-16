package com.pocketrealm.database

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Stopped-state, hash-verified datadir snapshots. Never accepts a live flag. */
internal class DatabaseSnapshotStore(
    private val snapshotsRoot: File,
    private val syncDirectory: (File) -> Unit = DatabaseDurability::syncDirectory,
) {
    data class Snapshot(val id: String, val root: File, val manifest: File, val digest: String)

    fun create(
        datadir: File,
        id: String,
        databaseStopped: Boolean,
        compatibility: JSONObject = JSONObject(),
    ): Snapshot {
        check(databaseStopped) { "DB-SNAPSHOT: refusing to copy a live datadir" }
        check(validId(id)) { "invalid snapshot id" }
        snapshotsRoot.mkdirs()
        val target = File(snapshotsRoot, id)
        check(!target.exists()) { "snapshot already exists: $id" }
        val staging = File(snapshotsRoot, ".$id.${UUID.randomUUID()}.partial")
        check(staging.mkdir()) { "DB-SNAPSHOT: could not create publication staging directory" }
        val files = JSONArray()
        try {
            datadir.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(datadir).invariantSeparatorsPath }
                .forEach { source ->
                    val relative = source.relativeTo(datadir).invariantSeparatorsPath
                    check(safeRelative(relative)) { "unsafe snapshot path" }
                    val destination = File(staging, "data/$relative")
                    copyFsync(source, destination)
                    files.put(JSONObject().put("path", relative).put("size", destination.length())
                        .put("sha256", sha256(destination)))
                }
            val body = JSONObject().put("schema", 2).put("snapshotId", id)
                .put("createdAt", System.currentTimeMillis()).put("files", files)
                .put("compatibility", compatibility)
            val manifest = File(staging, "manifest.json")
            writeFsync(manifest, body.toString())
            val manifestDigest = sha256(manifest)
            writeFsync(File(staging, "manifest.sha256"), "$manifestDigest\n")
            syncTreeDirectories(staging)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(snapshotsRoot)
            val published = Snapshot(id, target, File(target, "manifest.json"), manifestDigest)
            validatePublished(published)
            return published
        } finally {
            staging.deleteRecursively()
        }
    }

    fun restore(snapshot: Snapshot, destination: File, databaseStopped: Boolean) {
        check(databaseStopped) { "DB-SNAPSHOT: refusing to restore over a live datadir" }
        check(!destination.exists() || destination.listFiles().isNullOrEmpty()) {
            "DB-SNAPSHOT: restore destination is not empty"
        }
        val manifest = validatePublished(snapshot)
        val createdDestination = !destination.exists()
        destination.mkdirs()
        val files = manifest.getJSONArray("files")
        try {
            for (i in 0 until files.length()) {
                val record = files.getJSONObject(i)
                val relative = record.getString("path")
                check(safeRelative(relative)) { "unsafe snapshot path" }
                val source = File(snapshot.root, "data/$relative")
                check(source.length() == record.getLong("size") && sha256(source) == record.getString("sha256")) {
                    "DB-SNAPSHOT: snapshot hash mismatch for $relative"
                }
                copyFsync(source, File(destination, relative))
            }
            syncTreeDirectories(destination)
            syncDirectory(requireNotNull(destination.parentFile))
            verify(destination, snapshot)
        } catch (failure: Throwable) {
            if (createdDestination) {
                destination.deleteRecursively()
                syncDirectory(requireNotNull(destination.parentFile))
            }
            throw failure
        }
    }

    fun verify(datadir: File, snapshot: Snapshot) {
        val files = validatePublished(snapshot).getJSONArray("files")
        for (i in 0 until files.length()) {
            val record = files.getJSONObject(i)
            val file = File(datadir, record.getString("path"))
            check(file.length() == record.getLong("size") && sha256(file) == record.getString("sha256")) {
                "DB-SNAPSHOT: restored hash mismatch for ${record.getString("path")}" }
        }
    }

    fun load(id: String): Snapshot {
        check(validId(id))
        val root = File(snapshotsRoot, id)
        check(root.parentFile == snapshotsRoot && root.isDirectory) { "snapshot root missing: $id" }
        val manifest = File(root, "manifest.json")
        check(manifest.isFile) { "snapshot manifest missing: $id" }
        val digestFile = File(root, "manifest.sha256")
        check(digestFile.isFile) { "snapshot manifest digest missing: $id" }
        val digest = digestFile.readText().trim()
        return Snapshot(id, root, manifest, digest).also(::validatePublished)
    }

    fun list(): List<Snapshot> = snapshotsRoot.listFiles().orEmpty()
        .filter { validId(it.name) && File(it, "manifest.json").isFile }
        .map { load(it.name) }
        .sortedByDescending { it.root.lastModified() }

    fun retainNewest(count: Int) {
        snapshotsRoot.listFiles()?.filter { File(it, "manifest.json").isFile }
            ?.sortedByDescending { it.lastModified() }?.drop(count)?.forEach {
                check(it.deleteRecursively()) { "DB-SNAPSHOT: could not retire ${it.name}" }
            }
        syncDirectory(snapshotsRoot)
    }

    private fun copyFsync(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
        }
    }

    private fun writeFsync(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output -> output.write(value.toByteArray()); output.fd.sync() }
    }

    private fun validatePublished(snapshot: Snapshot): JSONObject {
        check(validId(snapshot.id) && snapshot.root == File(snapshotsRoot, snapshot.id)) {
            "DB-SNAPSHOT: invalid snapshot identity"
        }
        check(snapshot.root.isDirectory && snapshot.manifest == File(snapshot.root, "manifest.json") &&
            snapshot.manifest.isFile) { "DB-SNAPSHOT: snapshot publication is incomplete" }
        val digestFile = File(snapshot.root, "manifest.sha256")
        check(digestFile.isFile) { "DB-SNAPSHOT: manifest digest publication is incomplete" }
        val actualDigest = sha256(snapshot.manifest)
        val publishedDigest = digestFile.readText().trim()
        check(SHA256.matches(snapshot.digest) && snapshot.digest == publishedDigest &&
            publishedDigest == actualDigest) {
            "DB-SNAPSHOT: manifest digest mismatch"
        }
        val manifest = JSONObject(snapshot.manifest.readText())
        check(manifest.length() == 5 && manifest.getInt("schema") == 2 &&
            manifest.getString("snapshotId") == snapshot.id && manifest.getLong("createdAt") > 0 &&
            manifest.get("compatibility") is JSONObject) { "DB-SNAPSHOT: invalid manifest schema or identity" }
        val files = manifest.getJSONArray("files")
        val seen = mutableSetOf<String>()
        for (index in 0 until files.length()) {
            val record = files.getJSONObject(index)
            check(record.length() == 3)
            val relative = record.getString("path")
            check(safeRelative(relative) && seen.add(relative)) { "DB-SNAPSHOT: unsafe or duplicate path" }
            val source = File(snapshot.root, "data/$relative")
            check(source.isFile && source.length() == record.getLong("size") &&
                SHA256.matches(record.getString("sha256")) && sha256(source) == record.getString("sha256")) {
                "DB-SNAPSHOT: snapshot hash mismatch for $relative"
            }
        }
        return manifest
    }

    private fun syncTreeDirectories(root: File) {
        root.walkBottomUp().filter(File::isDirectory).forEach(syncDirectory)
    }

    private fun safeRelative(relative: String): Boolean = relative.isNotEmpty() &&
        !relative.startsWith('/') && !relative.startsWith('\\') &&
        relative.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    companion object {
        private val ID = Regex("[a-zA-Z0-9._-]{1,96}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private fun validId(id: String): Boolean = ID.matches(id) && !id.startsWith('.')
    }
}
