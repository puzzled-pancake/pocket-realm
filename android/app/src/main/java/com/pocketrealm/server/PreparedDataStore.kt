package com.pocketrealm.server

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Fail-closed reader for the immutable O11 normal-play data generation. */
internal class PreparedDataStore(private val root: File) {
    fun requireActive(): ActiveData {
        val pointerFile = File(root, "active.json")
        check(pointerFile.isFile) { "normal play requires a completed O11 data generation" }
        val pointer = JSONObject(pointerFile.readText())
        check(pointer.getInt("schema") == 1 && pointer.getString("mode") == "NORMAL") {
            "O11 active data pointer is incompatible"
        }
        val id = pointer.getString("generation")
        check(id.matches(Regex("[0-9a-f-]{36}"))) { "O11 data generation ID is invalid" }
        val generation = File(File(root, "generations"), id).canonicalFile
        val generations = File(root, "generations").canonicalFile
        check(generation.parentFile == generations && generation.isDirectory) {
            "O11 active data generation is missing"
        }
        val manifestFile = File(generation, "data-manifest.json")
        check(manifestFile.isFile && sha256(manifestFile) == pointer.getString("manifestSha256")) {
            "O11 data manifest does not match its active pointer"
        }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 1 && manifest.getBoolean("complete") &&
            manifest.getString("mode") == "NORMAL" && manifest.getInt("clientBuild") == 5875 &&
            manifest.getString("cmangosFamily") == "classic") {
            "O11 data manifest is incomplete or incompatible"
        }
        val counts = manifest.getJSONObject("counts")
        check(counts.getInt("dbc") >= 100 && counts.getInt("maps") >= 100 &&
            counts.getInt("vmapTrees") >= 1 && counts.getInt("mmapMaps") >= 1 &&
            counts.getInt("mmapTiles") >= 1) {
            "O11 data manifest lacks a required normal-play dataset"
        }
        val files = manifest.getJSONArray("files")
        check(files.length() > 0) { "O11 data manifest has no files" }
        for (index in 0 until files.length()) {
            val record = files.getJSONObject(index)
            val relative = record.getString("path")
            check(relative.substringBefore('/') in REQUIRED_DIRS) { "unexpected O11 data path: $relative" }
            val file = File(generation, relative).canonicalFile
            check(file.toPath().startsWith(generation.toPath()) && file != generation && file.isFile &&
                file.length() == record.getLong("size") && sha256(file) == record.getString("sha256")) {
                "O11 data file failed verification: $relative"
            }
        }
        return ActiveData(id, generation, pointer.getString("manifestSha256"))
    }

    data class ActiveData(val generation: String, val root: File, val manifestSha256: String)

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private val REQUIRED_DIRS = setOf("dbc", "maps", "vmaps", "mmaps") }
}
