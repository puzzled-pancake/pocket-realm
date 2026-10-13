package com.pocketrealm.desktop

import com.pocketrealm.BuildConfig
import com.pocketrealm.database.DesktopMariaDbEngine
import com.pocketrealm.diagnostics.SecretRedactor
import com.pocketrealm.fs.FileDigests
import com.pocketrealm.server.ServerRuntimeFiles
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
 * key must never survive into the exported zip. The database engine's
 * credentials ride the same explicit lane: the MariaDB lane writes them
 * into runtime/mangosd.conf and runtime/realmd.conf, plain text the
 * structured scrub cannot touch, and the key is ALSO re-extracted from the
 * raw settings.json bytes (a corrupt/partial write yields a keyless parsed
 * snapshot while the raw member still ships the file).
 *
 * DESK-119: the manifest header names the producing build (appVersion,
 * qualified flavor id, NATIVE_RUNTIME_BUILD_ID) and the app-log member is
 * a bounded tail, not the whole append-only file.
 * DESK-110: the typed log ring rides along as logs.json.
 * DESK-107/112/126: client crash evidence — the client's own Errors/ crash
 * texts, the last client exit record, WER LocalDumps minidumps (binary
 * copies; string redaction cannot apply to a memory image) and the
 * Application event log's WoW.exe faults (text, redacted like every
 * member).
 */
class DesktopSupportBundle(
    private val roots: DesktopStorageRoots,
    /** The resolved client folder — the crash-text lane's source; null
     * (nothing configured resolves) skips that lane. */
    private val clientDir: File? = null,
    private val crashEvidence: DesktopCrashEvidence = DesktopCrashEvidence(),
) {

    data class Result(val file: File, val entries: Int)

    private val serverFiles = ServerRuntimeFiles(roots)

    fun export(): String {
        val run = File(roots.runtime, "server/run")
        val canaries = explicitCanaries()
        val stamp = SimpleDateFormatStamp.next()
        val out = File(roots.logs, "support-bundle-$stamp.zip")
        val temp = File(out.parentFile, ".${out.name}.${ProcessHandle.current().pid()}.tmp")
        val redactor = SecretRedactor(canaries)
        // Design-lan §3.3: a stuck LAN session reports its own topology —
        // the stored mode and the bound (or to-bind) address ride the
        // manifest header.
        val topology = DesktopSettingsStore(roots.settingsFile).load()
        val manifest = JSONObject()
            .put("schema", 1)
            .put("redaction", "structured-plus-regex-v1")
            .put("appVersion", APP_VERSION)
            .put("flavorId", ServerRuntimeFiles.serverFlavor().flavorId)
            .put(
                "nativeRuntimeBuildId",
                BuildConfig.nativeRuntimeBuildIdFor(
                    ServerRuntimeFiles.serverFlavor().flavorId))
            .put(
                "topology",
                org.json.JSONObject()
                    .put("runtimeMode", topology.runtimeMode.name)
                    .put("lanAddress", topology.lanAddress),
            )
        ZipOutputStream(FileOutputStream(temp).buffered()).use { zip ->
            fileMembers(run).forEach { (name, file) ->
                val cap = if (name == APP_LOG_ENTRY_NAME) APP_LOG_TAIL_BYTES else MAX_TEXT_MEMBER_BYTES
                putTextEntry(zip, manifest, name, redactor.redact(readBoundedText(file, cap)))
            }
            // DESK-110: the typed ring (redacted — messages can quote conf
            // paths), the snapshot that survives even when the file write
            // failed.
            putTextEntry(zip, manifest, LOG_RING_ENTRY_NAME, redactor.redact(DesktopLog.exportJson()))
            // DESK-112/121: the last client exit record.
            serverFiles.readClientExit()?.let { record ->
                putTextEntry(zip, manifest, CLIENT_EXIT_ENTRY_NAME, redactor.redact(record.toString(2)))
            }
            // DESK-126: the Application event log's WoW.exe faults.
            crashEvidence.applicationErrorLog().takeIf { it.isNotEmpty() }?.let { log ->
                putTextEntry(zip, manifest, EVENT_LOG_ENTRY_NAME, redactor.redact(log))
            }
            // DESK-107: the client's own crash texts (newest first, capped).
            crashEvidence.clientCrashFiles(clientDir).forEach { file ->
                putTextEntry(
                    zip, manifest,
                    "$CLIENT_CRASH_ENTRY_PREFIX/${file.name}",
                    redactor.redact(readBoundedText(file, MAX_TEXT_MEMBER_BYTES)),
                )
            }
            // DESK-126: WER LocalDumps minidumps — METADATA ONLY (the raw
            // memory images never ship; see [putDumpMetadataEntry]).
            putDumpMetadataEntry(zip, manifest, crashEvidence.werDumpFiles())
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        java.nio.file.Files.move(
            temp.toPath(), out.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        val members = manifest.length() - MANIFEST_HEADER_FIELDS
        return "Created ${members + 1} entries - ${out.name}"
    }

    /**
     * The explicit canaries: secrets the shared redactor's pattern set can
     * never catch, gathered before any member is read.
     *
     *  - The external LLM API key, from the parsed settings snapshot AND
     *    re-extracted from the raw settings.json bytes: a corrupt/partially
     *    written settings file makes [DesktopSettingsStore.load] return a
     *    fresh (keyless) snapshot while the raw member still ships the
     *    file's bytes — the key must be redacted in that file too.
     *  - The database engine's credentials (database/secrets.json): the
     *    MariaDB engine lane writes them into runtime/mangosd.conf and
     *    runtime/realmd.conf (`LoginDatabaseInfo = …`), and the conf
     *    members are plain text — the structured scrub only fires on JSON,
     *    so without these canaries a live credential would leave the
     *    machine in the bundle.
     */
    private fun explicitCanaries(): List<String> = buildList {
        add(DesktopSettingsStore(roots.settingsFile).load().llmExternalApiKey)
        runCatching { roots.settingsFile.readText() }.getOrNull()?.let { raw ->
            LLM_KEY_JSON_VALUE.findAll(raw).forEach { match -> add(match.groupValues[1]) }
        }
        runCatching {
            val secretsFile = File(roots.database, DesktopMariaDbEngine.SECRETS_FILE_NAME)
            if (secretsFile.isFile) {
                val secrets = JSONObject(secretsFile.readText())
                secrets.keys().asSequence()
                    .filter { secrets.opt(it) is String }
                    .forEach { add(secrets.getString(it)) }
            }
        }
    }.filter { it.isNotBlank() }.distinct()

    /** The classic file members (skipped when absent) — the app log first,
     * bounded by [readBoundedText] to its tail cap. */
    private fun fileMembers(run: File): List<Pair<String, File>> = listOf(
        APP_LOG_ENTRY_NAME to File(roots.logs, APP_LOG_FILE_NAME),
        "logs/world.log" to File(roots.logs, "world.log"),
        "logs/realmd.log" to File(roots.logs, "realmd.log"),
        "supervisor-journal.json" to File(roots.supervisorJournalDir, "journal.json"),
        "settings.json" to roots.settingsFile,
        "data-active.json" to File(roots.content, "o11-server/active.json"),
        "runtime/mangosd.conf" to File(run, "mangosd.conf"),
        "runtime/realmd.conf" to File(run, "realmd.conf"),
    ).filter { (_, file) -> file.isFile }

    private fun putTextEntry(zip: ZipOutputStream, manifest: JSONObject, name: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        manifest.put(name, JSONObject().put("sha256", FileDigests.sha256(body)).put("bytes", bytes.size))
    }

    /**
     * DESK-126, revised (the raw-dump lane removed): WER minidumps of the
     * game client are PROCESS MEMORY IMAGES, and WoW.exe is the exact
     * process the app auto-logs-into — Win32AutoLogin types the stored
     * account's password into it via SendInput — so a dump captured after a
     * login-window crash can carry that credential. A memory image cannot
     * pass the string redactor, and this bundle's contract is "secrets
     * redacted"; shipping the raw bytes would break that contract at the
     * worst possible member. The bundle therefore ships METADATA ONLY
     * (per-dump name, size, mtime — the triage pointer), never the image;
     * the dump itself stays on this machine for local inspection.
     */
    private fun putDumpMetadataEntry(
        zip: ZipOutputStream,
        manifest: JSONObject,
        dumps: List<File>,
    ) {
        if (dumps.isEmpty()) return
        val body = JSONObject()
            .put(
                "note",
                "Client-process minidumps are memory images and can contain " +
                    "login credentials; they are never exported. Listed for triage only.",
            )
            .put(
                "dumps",
                org.json.JSONArray().apply {
                    dumps.forEach { file ->
                        put(
                            JSONObject()
                                .put("name", file.name)
                                .put("bytes", file.length())
                                .put("lastModifiedMs", runCatching { file.lastModified() }.getOrDefault(0L)),
                        )
                    }
                },
            )
            .toString(2)
        putTextEntry(zip, manifest, WER_DUMP_INDEX_ENTRY_NAME, body)
    }

    /** The last [capBytes] of [file], aligned to start on a line boundary
     * when the file was actually truncated (a tail cut mid-line would open
     * with a broken row). A whole-file read ships INTACT — aligning it too
     * would silently drop the file's first line (realmd.conf's
     * LoginDatabaseInfo row is the first line, so the old behavior hid the
     * very credential row this bundle must ship redacted, not chopped). */
    private fun readBoundedText(file: File, capBytes: Long): String {
        val bytes = readTailBytes(file, capBytes)
        if (bytes.size.toLong() >= file.length()) {
            return String(bytes, Charsets.UTF_8)
        }
        val firstNewline = bytes.indexOf('\n'.code.toByte())
        val start = if (firstNewline < 0) 0 else minOf(firstNewline + 1, bytes.size)
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /** The file's bytes, or exactly its last [capBytes] when larger. */
    private fun readTailBytes(file: File, capBytes: Long): ByteArray =
        if (file.length() <= capBytes) {
            file.readBytes()
        } else {
            RandomAccessFile(file, "r").use { random ->
                random.seek(file.length() - capBytes)
                val bytes = ByteArray(capBytes.toInt())
                random.readFully(bytes)
                bytes
            }
        }

    private object SimpleDateFormatStamp {
        private val format = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        fun next(): String = synchronized(this) { format.format(java.util.Date()) }
    }

    companion object {
        /** The app log DesktopLog writes (single-sourced with its reader). */
        const val APP_LOG_FILE_NAME = "pocket-realm.log"

        /** Bundle entry names single-sourced with the Diagnostics copy. */
        const val APP_LOG_ENTRY_NAME = "logs/pocket-realm.log"
        const val LOG_RING_ENTRY_NAME = "logs.json"
        const val CLIENT_EXIT_ENTRY_NAME = "crash/last-client-exit.json"
        const val EVENT_LOG_ENTRY_NAME = "crash/application-error-log.txt"
        const val CLIENT_CRASH_ENTRY_PREFIX = "client-crash/Errors"

        /** The WER-dump metadata member (names/sizes only — the .dmp memory
         * images themselves never ship, see [putDumpMetadataEntry]). */
        const val WER_DUMP_INDEX_ENTRY_NAME = "crash/wer-dumps.json"

        /** DESK-119: the app-log member is a bounded tail (last N KiB),
         * mirroring Android's bounded export. */
        const val APP_LOG_TAIL_BYTES = 256L * 1024L

        /** Every other text member's size cap (the Android export's
         * per-entry bound). */
        const val MAX_TEXT_MEMBER_BYTES = 512L * 1024L

        private const val MANIFEST_HEADER_FIELDS = 6

        /** The raw-settings canary extraction: the stored JSON value of the
         * external LLM API key, matched independently of the parse (a
         * truncated file must still redact its key). */
        private val LLM_KEY_JSON_VALUE = Regex(
            "\"llmExternalApiKey\"\\s*:\\s*\"([^\"]*)\"",
        )
    }
}
