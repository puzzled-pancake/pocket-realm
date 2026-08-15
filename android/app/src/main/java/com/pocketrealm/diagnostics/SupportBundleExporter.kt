package com.pocketrealm.diagnostics

import android.content.Context
import android.os.Build
import com.pocketrealm.BuildConfig
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.database.DatabaseRuntimeContract
import com.pocketrealm.log.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Bounded, consent-triggered support ZIP. Never contains databases or client files. */
class SupportBundleExporter(private val context: Context) {
    data class Result(val file: File, val manifestSha256: String, val entries: Int)

    fun export(
        explicitCanaries: Collection<String> = emptyList(),
        testEntries: Map<String, String> = emptyMap(),
    ): Result {
        val redactor = SecretRedactor(explicitCanaries)
        val entries = linkedMapOf<String, String>()
        entries["runtime-builds.json"] = JSONObject()
            .put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuild", BuildConfig.VERSION_CODE)
            .put("database", if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                DatabaseRuntimeContract.ARM_PROVIDER_ID
            } else {
                DatabaseRuntimeContract.X86_PROVIDER_ID
            })
            .put("server", "o09-cmangos-c096bada-nobots-v1")
            .put("wine", ClientRuntimeContract.RUNTIME_BUILD_ID)
            .put("renderer", ClientRuntimeContract.RENDERER_BUILD_ID).toString(2)
        entries["device.json"] = JSONObject().put("schema", 1)
            .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("model", Build.MODEL).put("build", Build.DISPLAY).toString(2)
        entries["logs.json"] = AppLog.exportJson()
        fixedFile(context.noBackupFilesDir, "runtime-supervisor/journal.json")
            ?.let { entries["supervisor-journal.json"] = it }
        fixedFile("content/o11-server/active.json")?.let { entries["data-active.json"] = it }
        fixedFile("clients/active.json")?.let { entries["client-active.json"] = it }
        entries.putAll(testEntries)

        val redacted = entries.mapValues { (_, value) -> redactor.redact(value).take(MAX_ENTRY_CHARS) }
        val records = JSONArray()
        redacted.forEach { (name, value) ->
            require(SAFE_ENTRY.matches(name) && ".." !in name.split('/')) { "unsafe support entry" }
            records.put(JSONObject().put("name", name).put("bytes", value.toByteArray().size)
                .put("sha256", sha256(value.toByteArray())))
        }
        val manifest = JSONObject().put("schema", 1).put("createdAt", System.currentTimeMillis())
            .put("redaction", "structured-plus-regex-v1").put("entries", records).toString(2)
        val outputDir = File(context.cacheDir, "support").apply { mkdirs() }
        outputDir.listFiles()?.forEach { it.delete() }
        val output = File(outputDir, "pocket-realm-support.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            (redacted + ("manifest.json" to manifest)).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        verify(output, explicitCanaries)
        return Result(output, sha256(manifest.toByteArray()), redacted.size + 1)
    }

    private fun fixedFile(relative: String): String? = fixedFile(context.filesDir, relative)

    private fun fixedFile(base: File, relative: String): String? {
        val root = base.canonicalFile
        val candidate = File(root, relative).canonicalFile
        check(candidate.toPath().startsWith(root.toPath()))
        return candidate.takeIf { it.isFile && it.length() <= MAX_ENTRY_CHARS }?.readText()
    }

    private fun verify(file: File, canaries: Collection<String>) {
        check(file.isFile && file.length() in 1..MAX_BUNDLE_BYTES)
        ZipFile(file).use { zip ->
            val values = zip.entries().asSequence().map { entry ->
                check(!entry.isDirectory && SAFE_ENTRY.matches(entry.name) && ".." !in entry.name.split('/'))
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.joinToString("\n")
            canaries.filter { it.isNotEmpty() }.forEach { canary ->
                check(!values.contains(canary, ignoreCase = true)) { "support export leaked a redaction canary" }
            }
            check(!values.contains("content://", ignoreCase = true))
            check(!Regex("[A-Za-z]:\\\\").containsMatchIn(values))
            check(!Regex("/(?:data|storage|sdcard|mnt)/").containsMatchIn(values))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ENTRY_CHARS = 512 * 1024
        private const val MAX_BUNDLE_BYTES = 4L * 1024 * 1024
        private val SAFE_ENTRY = Regex("[A-Za-z0-9._/-]{1,96}")
    }
}
