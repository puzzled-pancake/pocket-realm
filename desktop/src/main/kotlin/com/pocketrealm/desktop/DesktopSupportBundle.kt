package com.pocketrealm.desktop

import com.pocketrealm.diagnostics.SecretRedactor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Desktop twin of the Android SupportBundleExporter: one zip next to the
 * logs carrying the artifacts a bug report needs — the app log, the world
 * and realmd logs, the supervisor journal, the settings (secrets redacted
 * by the shared SecretRedactor), the prepared-data pointer and the staged
 * runtime conf. The manifest entry records each member's sha256 so a
 * bundle's contents are verifiable; redaction is structured-plus-regex
 * exactly like the Android twin.
 *
 * The shared redactor's key-pattern list predates `llmExternalApiKey`, so
 * the configured external API key is passed as an EXPLICIT canary — the
 * desktop is the only platform that bundles its settings file, and the
 * key must never survive into the exported zip.
 */
class DesktopSupportBundle(private val roots: DesktopStorageRoots) {

    data class Result(val file: File, val entries: Int)

    fun export(): String {
        val run = File(roots.runtime, "server/run")
        val members: List<Pair<String, File>> = listOf(
            "logs/pocket-realm.log" to File(roots.logs, APP_LOG_FILE_NAME),
            "logs/world.log" to File(roots.logs, "world.log"),
            "logs/realmd.log" to File(roots.logs, "realmd.log"),
            "supervisor-journal.json" to File(roots.supervisorJournalDir, "journal.json"),
            "settings.json" to roots.settingsFile,
            "data-active.json" to File(roots.content, "o11-server/active.json"),
            "runtime/mangosd.conf" to File(run, "mangosd.conf"),
            "runtime/realmd.conf" to File(run, "realmd.conf"),
        ).filter { (_, file) -> file.isFile }

        val canaries = listOf(
            DesktopSettingsStore(roots.settingsFile).load().llmExternalApiKey,
        ).filter { it.isNotBlank() }
        val stamp = SimpleDateFormatStamp.next()
        val out = File(roots.logs, "support-bundle-$stamp.zip")
        val temp = File(out.parentFile, ".${out.name}.${ProcessHandle.current().pid()}.tmp")
        var count = 0
        val manifest = JSONObject().put("schema", 1).put("redaction", "structured-plus-regex-v1")
        ZipOutputStream(FileOutputStream(temp).buffered()).use { zip ->
            for ((name, file) in members) {
                val redacted = SecretRedactor(canaries).redact(file.readText(Charsets.UTF_8))
                zip.putNextEntry(ZipEntry(name))
                zip.write(redacted.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                manifest.put(
                    name,
                    JSONObject()
                        .put("sha256", com.pocketrealm.fs.FileDigests.sha256(redacted))
                        .put("bytes", redacted.toByteArray(Charsets.UTF_8).size),
                )
                count++
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        java.nio.file.Files.move(
            temp.toPath(), out.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        return "Created ${count + 1} entries - ${out.name}"
    }

    private object SimpleDateFormatStamp {
        private val format = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        fun next(): String = synchronized(this) { format.format(java.util.Date()) }
    }

    companion object {
        /** The app log DesktopLog writes (single-sourced with its reader). */
        const val APP_LOG_FILE_NAME = "pocket-realm.log"
    }
}
