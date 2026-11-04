package com.pocketrealm.desktop

import com.pocketrealm.BuildConfig
import com.pocketrealm.server.ServerRuntimeFiles
import com.pocketrealm.storage.Settings
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * the support bundle's manifest header names the
 * producing build, the app-log member is a bounded tail, the typed log
 * ring rides along as logs.json, and the client crash evidence (Errors/
 * crash texts, the last client exit record, the WER dump index, the
 * Application event log) ships — redacted wherever it is text. The .dmp
 * memory images themselves NEVER ship (metadata only): they are the
 * auto-login process's memory and can carry the stored credential.
 */
class DesktopSupportBundleTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val canary = "pr-sk-bundle-test-canary"

    private fun newRoots(name: String): DesktopStorageRoots =
        DesktopStorageRoots(folder.newFolder(name)).apply { ensureDirectories() }

    private fun exportToZip(
        roots: DesktopStorageRoots,
        clientDir: File? = null,
        dumpsDir: File? = null,
        eventLog: String = "",
    ): ZipFile {
        // The retired bot-speech API key as an older build left it on disk.
        DesktopSettingsStore(roots.settingsFile).save(Settings.Snapshot())
        roots.settingsFile.writeText(
            JSONObject(roots.settingsFile.readText()).put("llmExternalApiKey", canary).toString(),
        )
        val evidence = DesktopCrashEvidence(
            localDumpsDir = dumpsDir ?: File(folder.root, "no-dumps-dir"),
            eventLog = { eventLog },
        )
        val status = DesktopSupportBundle(roots, clientDir, evidence).export()
        assertTrue(status, status.startsWith("Created "))
        val zipPath = roots.logs
            .listFiles { file -> file.name.startsWith("support-bundle-") }!!
            .single()
        return ZipFile(zipPath)
    }

    private fun ZipFile.manifest(): JSONObject = JSONObject(
        getInputStream(getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8),
    )

    private fun ZipFile.entryText(name: String): String =
        getInputStream(getEntry(name)).readBytes().toString(Charsets.UTF_8)

    @Test
    fun manifestHeaderNamesTheProducingBuild() {
        exportToZip(newRoots("header")).use { zip ->
            val manifest = zip.manifest()
            assertEquals(APP_VERSION, manifest.getString("appVersion"))
            assertEquals("vanilla", manifest.getString("flavorId"))
            assertEquals(BuildConfig.NATIVE_RUNTIME_BUILD_ID, manifest.getString("nativeRuntimeBuildId"))
            assertEquals(1, manifest.getInt("schema"))
            assertEquals("structured-plus-regex-v1", manifest.getString("redaction"))
        }
    }

    @Test
    fun appLogMemberIsABoundedTailNotTheWholeFile() {
        val roots = newRoots("bounded")
        val log = File(roots.logs, DesktopSupportBundle.APP_LOG_FILE_NAME)
        log.parentFile.mkdirs()
        // An oversized app log: an early marker that must fall out of the
        // bounded 256 KiB tail, a late marker that must stay.
        val builder = StringBuilder("EARLY-MARKER\n")
        while (builder.length < DesktopSupportBundle.APP_LOG_TAIL_BYTES + 128L * 1024L) {
            builder.append("x".repeat(1024)).append('\n')
        }
        builder.append("LATE-MARKER\n")
        log.writeText(builder.toString())
        exportToZip(roots).use { zip ->
            val appLog = zip.entryText(DesktopSupportBundle.APP_LOG_ENTRY_NAME)
            assertFalse("the whole file must not ship", appLog.contains("EARLY-MARKER"))
            assertTrue(appLog.contains("LATE-MARKER"))
            val row = zip.manifest().getJSONObject(DesktopSupportBundle.APP_LOG_ENTRY_NAME)
            assertTrue(
                "the tail must stay under the cap: ${row.getInt("bytes")}",
                row.getInt("bytes") <= DesktopSupportBundle.APP_LOG_TAIL_BYTES,
            )
        }
    }

    @Test
    fun typedLogRingShipsAsLogsJsonAndIsRedacted() {
        val roots = newRoots("ring")
        DesktopLog.i("BundleTest", "ring-marker-XYZ")
        DesktopLog.w("BundleTest", "the canary $canary must not survive export")
        exportToZip(roots).use { zip ->
            val logsJson = zip.entryText(DesktopSupportBundle.LOG_RING_ENTRY_NAME)
            val parsed = JSONArray(logsJson)
            assertTrue(parsed.length() >= 2)
            val messages = (0 until parsed.length()).map { index ->
                parsed.getJSONObject(index).getString("msg")
            }
            assertTrue(messages.contains("ring-marker-XYZ"))
            assertFalse("the ring is redacted like every member", logsJson.contains(canary))
        }
    }

    @Test
    fun databaseCredentialsNeverSurviveIntoTheConfMembers() {
        val roots = newRoots("db-secret")
        // The MariaDB engine lane's durable credential record.
        val coreSecret = "a".repeat(48)
        val adminSecret = "b".repeat(48)
        File(roots.database, "secrets.json")
            .writeText("""{"core": "$coreSecret", "admin": "$adminSecret"}""")
        // The conf members the exporter ships: the realmd + world confs
        // carry the credential inside LoginDatabaseInfo exactly as
        // ServerRuntimeFiles.databaseInfo writes it on the MariaDB lane.
        val run = File(roots.runtime, "server/run").apply { mkdirs() }
        val loginLine = """LoginDatabaseInfo = ".;mariadb.sock;pocket_core;$coreSecret;pocket_core_realmd""""
        File(run, "realmd.conf").writeText("$loginLine\nRealmServerPort = 3724\n")
        File(run, "mangosd.conf").writeText(
            "$loginLine\nWorldDatabaseInfo = \".;mariadb.sock;pocket_core;$coreSecret;pocket_core_world\"\n",
        )
        // The admin secret rides the same lane's other records; both must
        // be canaried.
        DesktopLog.w("BundleTest", "logged admin secret: $adminSecret")
        exportToZip(roots).use { zip ->
            val realmd = zip.entryText("runtime/realmd.conf")
            val mangosd = zip.entryText("runtime/mangosd.conf")
            assertFalse("core secret must not survive in realmd.conf", realmd.contains(coreSecret))
            assertFalse("core secret must not survive in mangosd.conf", mangosd.contains(coreSecret))
            assertFalse("admin secret must not survive in the ring", zip.entryText("logs.json").contains(adminSecret))
            // The conf line's diagnostic shape stays readable.
            assertTrue(
                "realmd member was:\n$realmd",
                realmd.contains("""LoginDatabaseInfo = ".;mariadb.sock;pocket_core;<redacted>;pocket_core_realmd""""),
            )
        }
    }

    /** the realm's Nostr
     * identity key (nostrPrivateKeyHex) must never survive the bundle —
     * parsed member AND corrupt-raw member, mirroring the API-key
     * precedent. */
    @Test
    fun nostrPrivateKeyNeverSurvivesIntoTheSettingsMember() {
        val roots = newRoots("nostr-key")
        val nostrCanary = "4f5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091"
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            """{"schema": 2, "nostrDiscoveryEnabled": true, "nostrPrivateKeyHex": "$nostrCanary"}""",
        )
        val evidence = DesktopCrashEvidence(
            localDumpsDir = File(folder.root, "no-dumps-dir"),
            eventLog = { "" },
        )
        DesktopSupportBundle(roots, null, evidence).export()
        val zipPath = roots.logs
            .listFiles { file -> file.name.startsWith("support-bundle-") }!!
            .single()
        java.util.zip.ZipFile(zipPath).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val text = zip.entryText(entry.name)
                assertFalse(
                    "the Nostr identity key leaked into ${entry.name}",
                    text.contains(nostrCanary),
                )
            }
            assertTrue(
                "the settings member must carry the redacted marker",
                zip.entryText("settings.json").contains("<redacted>"),
            )
        }
    }

    @Test
    fun corruptSettingsFileStillRedactsItsApiKeyFromTheRawMember() {
        val roots = newRoots("corrupt-settings")
        // A partially written settings.json: unparseable, so load() yields
        // a fresh keyless snapshot — but the raw bytes (shipped as the
        // settings.json member) still carry the key.
        val truncated = """{"schema": 2, "llmExternalApiKey": "$canary", "lanAddr"""
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(truncated)
        assertTrue(
            "precondition: the truncated file must not parse",
            runCatching { org.json.JSONObject(truncated) }.isFailure,
        )
        val evidence = DesktopCrashEvidence(
            localDumpsDir = File(folder.root, "no-dumps-dir"),
            eventLog = { "" },
        )
        DesktopSupportBundle(roots, null, evidence).export()
        val zipPath = roots.logs
            .listFiles { file -> file.name.startsWith("support-bundle-") }!!
            .single()
        java.util.zip.ZipFile(zipPath).use { zip ->
            val settings = zip.entryText("settings.json")
            assertFalse("the key must be redacted from the raw corrupt member", settings.contains(canary))
            assertTrue(settings.contains("llmExternalApiKey"))
        }
    }

    /** the realm's iroh
     * transport secret (irohGatewaySecretHex) must never survive the
     * bundle — parsed member AND corrupt-raw member, mirroring the
     * Nostr key precedent above. */
    @Test
    fun irohGatewaySecretNeverSurvivesIntoTheSettingsMember() {
        val roots = newRoots("iroh-secret")
        val secret = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12"
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            """{"schema": 2, "nostrDiscoveryEnabled": true, "irohGatewaySecretHex": "$secret"}""",
        )
        val evidence = DesktopCrashEvidence(
            localDumpsDir = File(folder.root, "no-dumps-dir"),
            eventLog = { "" },
        )
        DesktopSupportBundle(roots, null, evidence).export()
        val zipPath = roots.logs
            .listFiles { file -> file.name.startsWith("support-bundle-") }!!
            .single()
        java.util.zip.ZipFile(zipPath).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val text = zip.entryText(entry.name)
                assertFalse(
                    "the iroh transport secret leaked into ${entry.name}",
                    text.contains(secret),
                )
            }
            assertTrue(
                "the settings member must carry the redacted marker",
                zip.entryText("settings.json").contains("<redacted>"),
            )
        }
    }

    /** its corrupt-file leg: a TRUNCATED settings.json (unparseable)
     * whose raw bytes carry the secret must still redact it — the
     * key-name scrub only fires on parseable JSON, so the raw-extraction
     * canary is the only guard on this lane. */
    @Test
    fun corruptSettingsFileStillRedactsTheIrohSecretFromTheRawMember() {
        val roots = newRoots("corrupt-iroh-secret")
        val secret = "fedcba98".repeat(8)
        val truncated = """{"schema": 2, "irohGatewaySecretHex": "$secret", "lanAddr"""
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(truncated)
        assertTrue(
            "precondition: the truncated file must not parse",
            runCatching { org.json.JSONObject(truncated) }.isFailure,
        )
        val evidence = DesktopCrashEvidence(
            localDumpsDir = File(folder.root, "no-dumps-dir"),
            eventLog = { "" },
        )
        DesktopSupportBundle(roots, null, evidence).export()
        val zipPath = roots.logs
            .listFiles { file -> file.name.startsWith("support-bundle-") }!!
            .single()
        java.util.zip.ZipFile(zipPath).use { zip ->
            val settings = zip.entryText("settings.json")
            assertFalse(
                "the secret must be redacted from the raw corrupt member",
                settings.contains(secret),
            )
        }
    }

    @Test
    fun clientCrashEvidenceShipsCappedRedactedAndRawWhereBinary() {
        val roots = newRoots("crash")
        // The client's own crash texts: 6 of them, cap 5, newest wins.
        val clientDir = folder.newFolder("client")
        val errors = File(clientDir, "Errors").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (index in 1..6) {
            val file = File(errors, "crash$index.txt").apply { writeText("crash text $index") }
            assertTrue(file.setLastModified(now - index * 60_000L))
        }
        // WER minidumps: three candidate captures — the game client's dump,
        // a foreign process's (never listed), and one with a garbage mtime.
        // NONE of the memory images ship; the bundle carries a metadata row.
        val dumps = folder.newFolder("dumps")
        val dumpBytes = "WER tail-pad dump bytes 0123456789".toByteArray()
        val clientDump = File(dumps, "WoW.exe.4242.dmp").apply { writeBytes(dumpBytes) }
        File(dumps, "notepad.exe.999.dmp").writeBytes(dumpBytes)
        val dumpMtime = System.currentTimeMillis()
        assertTrue(clientDump.setLastModified(dumpMtime))
        // The journalled client exit.
        ServerRuntimeFiles(roots).writeClientExit(
            pid = 4242,
            exitCode = 255,
            clean = false,
            detail = "client died abnormally (exit code 255)",
        )
        exportToZip(
            roots,
            clientDir = clientDir,
            dumpsDir = dumps,
            eventLog = "Faulting application name: WoW.exe, version 1.12.1",
        ).use { zip ->
            val manifest = zip.manifest()
            // Crash texts: the five newest as redacted text members.
            (1..5).forEach { index -> assertTrue(manifest.has("client-crash/Errors/crash$index.txt")) }
            assertFalse(
                "the oldest crash text must not ship",
                manifest.has("client-crash/Errors/crash6.txt"),
            )
            assertTrue(zip.entryText("client-crash/Errors/crash1.txt").contains("crash text 1"))
            // The exit record.
            val exit = JSONObject(zip.entryText(DesktopSupportBundle.CLIENT_EXIT_ENTRY_NAME))
            assertEquals(4242, exit.getInt("pid"))
            assertEquals(255, exit.getInt("exitCode"))
            assertFalse(exit.getBoolean("clean"))
            // The event log, redacted like every text member.
            assertTrue(
                zip.entryText(DesktopSupportBundle.EVENT_LOG_ENTRY_NAME)
                    .contains("Faulting application name: WoW.exe"),
            )
            // Minidumps are process memory images (the app types the stored
            // login password into that exact process), so the raw .dmp bytes
            // must NEVER ship — only the metadata row does.
            assertNull("a raw client minidump must never ship", zip.getEntry("crash/WoW.exe.4242.dmp"))
            assertFalse(manifest.has("crash/notepad.exe.999.dmp"))
            val dumpIndex = JSONObject(zip.entryText(DesktopSupportBundle.WER_DUMP_INDEX_ENTRY_NAME))
            val rows = dumpIndex.getJSONArray("dumps")
            assertEquals(1, rows.length())
            assertEquals("WoW.exe.4242.dmp", rows.getJSONObject(0).getString("name"))
            assertEquals(dumpBytes.size.toLong(), rows.getJSONObject(0).getLong("bytes"))
            assertTrue(dumpIndex.getString("note").contains("never"))
            // Every member row carries sha256 + bytes (the header fields
            // and manifest.json excluded).
            val header = setOf(
                "schema", "redaction", "appVersion", "flavorId", "nativeRuntimeBuildId",
                // design-lan: the session's own topology rides the
                // manifest header (a stuck LAN session reports its mode +
                // address); it is header data, not a member row.
                "topology",
            )
            manifest.keys().asSequence()
                .filter { key -> key !in header }
                .forEach { key ->
                    val row = manifest.getJSONObject(key)
                    assertEquals("member row $key", 64, row.getString("sha256").length)
                    assertTrue("member row $key", row.getInt("bytes") > 0)
                }
        }
    }
}
