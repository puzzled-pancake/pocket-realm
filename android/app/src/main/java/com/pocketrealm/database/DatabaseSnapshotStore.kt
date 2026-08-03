package com.pocketrealm.database

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Stopped-state, hash-verified datadir snapshots. Never accepts a live flag. */
internal class DatabaseSnapshotStore(private val snapshotsRoot: File) {
    data class Snapshot(val id: String, val root: File, val manifest: File, val digest: String)

    fun create(
        datadir: File,
        id: String,
        databaseStopped: Boolean,
        compatibility: JSONObject = JSONObject(),
    ): Snapshot {
        check(databaseStopped) { "DB-SNAPSHOT: refusing to copy a live datadir" }
        check(ID.matches(id)) { "invalid snapshot id" }
        val target = File(snapshotsRoot, id)
        check(!target.exists()) { "snapshot already exists: $id" }
        target.mkdirs()
        val files = JSONArray()
        datadir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(datadir).invariantSeparatorsPath }
            .forEach { source ->
                val relative = source.relativeTo(datadir).invariantSeparatorsPath
                check(!relative.startsWith("../") && !source.toPath().toFile().isDirectory)
                val destination = File(target, "data/$relative")
                copyFsync(source, destination)
                files.put(JSONObject().put("path", relative).put("size", destination.length())
                    .put("sha256", sha256(destination)))
            }
        val body = JSONObject().put("schema", 1).put("snapshotId", id)
            .put("createdAt", System.currentTimeMillis()).put("files", files)
            .put("compatibility", compatibility)
        val manifest = File(target, "manifest.json")
        writeFsync(manifest, body.toString())
        return Snapshot(id, target, manifest, sha256(manifest))
    }

    fun restore(snapshot: Snapshot, destination: File, databaseStopped: Boolean) {
        check(databaseStopped) { "DB-SNAPSHOT: refusing to restore over a live datadir" }
        check(!destination.exists() || destination.listFiles().isNullOrEmpty()) {
            "DB-SNAPSHOT: restore destination is not empty"
        }
        destination.mkdirs()
        val manifest = JSONObject(snapshot.manifest.readText())
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val record = files.getJSONObject(i)
            val relative = record.getString("path")
            check(!relative.startsWith('/') && ".." !in relative.split('/')) { "unsafe snapshot path" }
            val source = File(snapshot.root, "data/$relative")
            check(source.length() == record.getLong("size") && sha256(source) == record.getString("sha256")) {
                "DB-SNAPSHOT: snapshot hash mismatch for $relative"
            }
            copyFsync(source, File(destination, relative))
        }
        verify(destination, snapshot)
    }

    fun verify(datadir: File, snapshot: Snapshot) {
        val files = JSONObject(snapshot.manifest.readText()).getJSONArray("files")
        for (i in 0 until files.length()) {
            val record = files.getJSONObject(i)
            val file = File(datadir, record.getString("path"))
            check(file.length() == record.getLong("size") && sha256(file) == record.getString("sha256")) {
                "DB-SNAPSHOT: restored hash mismatch for ${record.getString("path")}" }
        }
    }

    fun load(id: String): Snapshot {
        check(ID.matches(id))
        val root = File(snapshotsRoot, id)
        val manifest = File(root, "manifest.json")
        check(manifest.isFile) { "snapshot manifest missing: $id" }
        return Snapshot(id, root, manifest, sha256(manifest))
    }

    fun list(): List<Snapshot> = snapshotsRoot.listFiles().orEmpty()
        .filter { File(it, "manifest.json").isFile }
        .map { load(it.name) }
        .sortedByDescending { it.root.lastModified() }

    fun retainNewest(count: Int) {
        snapshotsRoot.listFiles()?.filter { File(it, "manifest.json").isFile }
            ?.sortedByDescending { it.lastModified() }?.drop(count)?.forEach { it.deleteRecursively() }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val ID = Regex("[a-zA-Z0-9._-]{1,96}")
    }
}
