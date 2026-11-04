package com.pocketrealm.importer

import com.pocketrealm.storage.StorageRoots
import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientGenerationLease
import com.pocketrealm.client.ClientFlavors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Immutable generation publication with an atomic active-pointer switch. */
class ClientGenerationStore(context: Context) {
    private val root = StorageRoots.get(context).client
    private val generations = File(root, "generations").apply { mkdirs() }

    fun staging(importId: String): File = safeGeneration(".staging-$importId")
    fun generation(importId: String): File = safeGeneration(importId)

    fun prepare(importId: String): File {
        generations.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".staging-") && it.name != ".staging-$importId" }
            .forEach { check(it.deleteRecursively()) { "cannot retire abandoned client staging generation" } }
        val directory = staging(importId)
        directory.mkdirs()
        check(directory.isDirectory) { "cannot create managed-client staging generation" }
        return directory
    }

    /** Recover the narrow crash window after generation rename but before journal/pointer commit. */
    fun recoverPublished(importId: String): PublishedGeneration? =
        ClientGenerationLease.acquirePublication(root).use {
            recoverPublishedLocked(importId)
        }

    private fun recoverPublishedLocked(importId: String): PublishedGeneration? {
        val final = generation(importId)
        val manifest = File(final, "client-manifest.json")
        if (!final.isDirectory || !manifest.isFile) return null
        val manifestJson = JSONObject(manifest.readText())
        val digest = validateManifest(final, manifest, importId)
        activateLocked(importId, digest, manifestJson.getString("clientId"))
        return PublishedGeneration(importId, final, digest)
    }

    /**
     * The active generation id for [flavor]'s pointer namespace. The
     * read adopts a legacy un-namespaced pointer first, so an updated build
     * resolves the pre-flavor active generation on its first boot.
     *
     * the default resolves the ACTIVE lane
     * through the [ActiveFlavor] machine — the switcher's selection drives
     * the pointer namespace when several flavors qualify, replacing the
     * former `qualified().single()` default that threw the moment a second
     * flavor qualified (the desktop twin's no-arg callers pass the flavor
     * explicitly; this Android store's no-arg caller — the importer's
     * status read — made the default live).
     */
    fun activeGeneration(flavor: ClientFlavor = ActiveFlavor.resolve(ACTIVE_GENERATION_LANE)): String? =
        readPointer(activePointerFor(flavor))?.optString("generation")?.takeIf(UUID::matches)

    fun resolve(staging: File, relativePath: String): File {
        val target = File(staging, relativePath).canonicalFile
        val rootPath = staging.canonicalFile.toPath()
        check(target.toPath().startsWith(rootPath) && target != staging.canonicalFile) {
            "normalized import path escaped staging"
        }
        return target
    }

    fun partialLength(importId: String, relativePath: String, tempName: String?): Long {
        if (tempName.isNullOrBlank() || '/' in tempName || '\\' in tempName) return 0L
        val staging = staging(importId)
        if (!staging.isDirectory) return 0L
        val target = resolve(staging, relativePath)
        val partial = File(target.parentFile, tempName).canonicalFile
        val rootPath = staging.canonicalFile.toPath()
        if (!partial.toPath().startsWith(rootPath) || !partial.isFile) return 0L
        return partial.length().coerceAtLeast(0L)
    }

    fun publish(
        importId: String,
        plan: PublishPlan,
        callbacks: PublishCallbacks = PublishCallbacks(),
    ): PublishedGeneration {
        val identity = plan.identity
        val sourceFingerprint = plan.sourceFingerprint
        val journalEntries = plan.journalEntries
        val durationMs = plan.durationMs
        val afterRenameBeforeActivate = callbacks.afterRenameBeforeActivate
        val onManifestFile = callbacks.onManifestFile
        val staging = staging(importId)
        val final = generation(importId)
        recoverPublished(importId)?.let { return it }
        check(staging.isDirectory) { "managed-client staging generation is absent" }
        writeManagedConfiguration(staging)

        val files = staging.walkTopDown().filter { it.isFile && it.name != "client-manifest.json" }
            .onEach { file -> onManifestFile(file.relativeTo(staging).invariantSeparatorsPath) }
            .map { file ->
                val relative = file.relativeTo(staging).invariantSeparatorsPath
                JSONObject().put("path", relative).put("size", file.length()).put("sha256", sha256(file))
            }.sortedBy { it.getString("path").lowercase() }.toList()
        // Stamp the scanned identity's clientId, not the registry's current
        // qualified flavor: the verified PE version resource resolves the
        // lane this generation belongs to.
        val clientId = requireNotNull(
            ClientFlavors.qualifiedByVersionResource(identity.getString("version"))?.clientId,
        ) {
            "staged client identity \"${identity.optString("version")}\" does not resolve to a qualified flavor"
        }
        val manifest = buildManifestJson(
            identity = identity,
            clientId = clientId,
            sourceFingerprint = sourceFingerprint,
            journalEntries = journalEntries,
            files = files,
            importId = importId,
            durationMs = durationMs,
            publishedAtMs = System.currentTimeMillis(),
        )
        val manifestFile = File(staging, "client-manifest.json")
        atomicWrite(manifestFile, manifest.toString(2).toByteArray(Charsets.UTF_8))
        val digest = sha256(manifestFile)
        return ClientGenerationLease.acquirePublication(root).use {
            recoverPublishedLocked(importId)?.let { return@use it }
            check(!final.exists()) { "managed-client generation collision" }
            Os.rename(staging.absolutePath, final.absolutePath)
            fsyncDirectory(generations)
            afterRenameBeforeActivate()
            activateLocked(importId, digest, clientId)
            PublishedGeneration(importId, final, digest)
        }
    }

    /**
     * Flavor-scoped activation: the pointer switch and the prune run
     * inside [flavor]'s namespace. The keep-set never contains another
     * flavor's generation, so activating a TBC/WotLK client can never delete
     * the vanilla managed client (or vice versa); the pointer body carries
     * the flavor key so a doctored cross-flavor pointer is detectable.
     */
    private fun activateLocked(generation: String, manifestSha256: String, clientId: String) {
        val flavor = requireNotNull(ClientFlavors.byClientId(clientId)) { "unsupported managed client id" }
        val activePointer = activePointerFor(flavor)
        val previousPointer = previousPointerFor(flavor)
        val current = readPointer(activePointer)
        val priorGeneration = current?.getString("generation")
        if (priorGeneration == generation && current?.optString("manifestSha256") == manifestSha256) return
        if (priorGeneration != null && priorGeneration != generation) {
            atomicWrite(previousPointer, checkNotNull(activePointer.takeIf { it.isFile }).readBytes())
        }
        val pointer = JSONObject().put("schema", 1).put("generation", generation)
            .put("clientId", clientId)
            .put("flavor", flavor.flavorId)
            .put("manifestSha256", manifestSha256).put("activatedAtMs", System.currentTimeMillis())
        atomicWrite(activePointer, pointer.toString().toByteArray(Charsets.UTF_8))
        val pruneCandidates = generations.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".staging-") }
        val keep = GenerationPointers.scopedKeepSet(
            activatingGeneration = generation,
            priorGeneration = priorGeneration,
            generations = pruneCandidates,
            activatingFlavorId = flavor.flavorId,
            generationFlavor = GenerationPointers::clientGenerationFlavor,
        )
        pruneCandidates.filter { it.name !in keep }.forEach {
            check(it.deleteRecursively()) { "cannot retire old client generation" }
        }
        fsyncDirectory(generations)
    }

    /**
     * G3-5 legacy seed: on first read, when the canonical per-flavor pointer
     * is absent and the un-namespaced pointer exists, adopt it by atomic
     * rename (never leave both readable as authoritative) and continue under
     * the canonical name.
     */
    private fun activePointerFor(flavor: ClientFlavor): File =
        adoptLegacyPointer(
            File(root, GenerationPointers.activePointerName(flavor.flavorId)),
            File(root, GenerationPointers.LEGACY_ACTIVE),
        )

    private fun previousPointerFor(flavor: ClientFlavor): File =
        adoptLegacyPointer(
            File(root, GenerationPointers.previousPointerName(flavor.flavorId)),
            File(root, GenerationPointers.LEGACY_PREVIOUS),
        )

    private fun adoptLegacyPointer(canonical: File, legacy: File): File {
        if (!canonical.isFile && legacy.isFile) {
            Os.rename(legacy.absolutePath, canonical.absolutePath)
            fsyncDirectory(root)
        }
        return canonical
    }

    private fun writeManagedConfiguration(staging: File) {
        atomicWrite(File(staging, "realmlist.wtf"), managedRealmlistWtfBytes())
        // The locale realmlists too (WotLK reads Data/<locale>/realmlist.wtf):
        // an imported client never keeps a foreign server address.
        com.pocketrealm.client.ClientRealmEndpointProjection.localeRealmlists(staging)
            .forEach { atomicWrite(it, managedRealmlistWtfBytes()) }
        atomicWrite(File(staging, "WTF/Config.wtf"), managedConfigWtfBytes())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        Os.rename(temp.absolutePath, target.absolutePath)
        fsyncDirectory(checkNotNull(target.parentFile))
    }

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun readPointer(file: File): JSONObject? = if (!file.isFile) null else runCatching {
        JSONObject(file.readText()).also {
            check(it.getInt("schema") == 1 && UUID.matches(it.getString("generation")))
        }
    }.getOrNull()

    private fun validateManifest(root: File, manifestFile: File, importId: String): String {
        val manifest = JSONObject(manifestFile.readText())
        validateManifestShape(manifest, importId)
        for (index in 0 until manifest.getJSONArray("files").length()) {
            val record = manifest.getJSONArray("files").getJSONObject(index)
            val file = resolve(root, record.getString("path"))
            check(file.isFile && file.length() == record.getLong("size") &&
                sha256(file) == record.getString("sha256")) { "published generation failed integrity validation" }
        }
        return sha256(manifestFile)
    }

    private fun safeGeneration(name: String): File {
        require(name.matches(Regex("(?:\\.staging-)?[0-9a-f-]{36}"))) { "invalid generation identity" }
        val value = File(generations, name).absoluteFile
        check(value.parentFile == generations.absoluteFile) { "generation escaped root" }
        return value
    }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    data class PublishedGeneration(val id: String, val root: File, val manifestSha256: String)

    /** Publish-time callbacks (onManifestFile drives PUBLISHING ticks). */
    data class PublishCallbacks(
        val afterRenameBeforeActivate: () -> Unit = {},
        val onManifestFile: (String) -> Unit = {},
    )

    data class PublishPlan(
        val identity: JSONObject,
        val sourceFingerprint: String,
        val journalEntries: List<ImportJournal.JournalEntry>,
        val durationMs: Long,
    )

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** the [ActiveFlavor] lane suffix naming the active-generation
         * default-arg gate. */
        private const val ACTIVE_GENERATION_LANE =
            "the active client generation resolves the qualified lane"

        /** golden seam: the app-owned `realmlist.wtf` publish bytes. */
        internal fun managedRealmlistWtfBytes(): ByteArray =
            "set realmlist 127.0.0.1\r\n".toByteArray()

        /** golden seam: the app-owned `WTF/Config.wtf` publish bytes (SAFE_CONFIG normalized). */
        internal fun managedConfigWtfBytes(): ByteArray =
            SAFE_CONFIG.normalizeRawConfigLineEndings().toByteArray()

        /**
         * The raw SAFE_CONFIG string carries a literal backslash-r before each
         * real newline (Kotlin raw strings do not process escapes), so the
         * app-written Config.wtf must convert that sequence to a true CRLF.
         * The historical needle "\\r\\n" (four literal
         * characters) never matched the actual content (backslash-r + newline)
         * and was a no-op: every line shipped with junk trailing text until the
         * client's own exit-rewrite normalized the file.
         */
        private fun String.normalizeRawConfigLineEndings(): String =
            replace("\\r\n", "\r\n")

        /** Safe-mode fps cap stamped into every published manifest. */
        private const val SAFE_MODE_FPS_CAP = 30

        /**
         * Pure client-manifest composition (the [publish] writer body) so the
         * durable format is golden-fixture-testable on the JVM: the
         * fixture test pins these exact bytes as the current-release manifest,
         * and every later manifest change (S5 stamps `flavor` into it) must
         * rotate that fixture deliberately, never silently.
         */
        // The faithful template signature — one parameter per pinned manifest
        // field; splitting it would obscure the byte-pinned composition.
        @Suppress("LongParameterList")
        internal fun buildManifestJson(
            identity: JSONObject,
            clientId: String,
            sourceFingerprint: String,
            journalEntries: List<ImportJournal.JournalEntry>,
            files: List<JSONObject>,
            importId: String,
            durationMs: Long,
            publishedAtMs: Long,
        ): JSONObject = JSONObject()
            .put("schema", 2).put("complete", true)
            .put("clientId", clientId)
            .put("identity", identity)
            .put("executable", "WoW.exe").put("directLaunch", true)
            .put("sourceRuntimeDependency", false).put("sourceFingerprint", sourceFingerprint)
            .put("managedRoot", "no_backup/client/generations/$importId")
            .put("safeMode", JSONObject()
                .put("renderer", "wined3d").put("resolution", "1280x720")
                .put("fpsCap", SAFE_MODE_FPS_CAP).put("audio", "off").put("realmEndpoint", "127.0.0.1")
                .put("addons", "preserved-disabled-until-reviewed"))
            .put("appOwnedFiles", JSONArray(listOf("realmlist.wtf", "WTF/Config.wtf")))
            .put("journal", JSONObject()
                .put("schema", ImportJournal.SCHEMA)
                .put("verified", journalEntries.count { it.state == ImportFileState.VERIFIED })
                .put("skipped", journalEntries.count { it.state == ImportFileState.SKIPPED })
                .put("maxAttempt", journalEntries.maxOfOrNull { it.attempt } ?: 0))
            .put("files", JSONArray(files)).put("durationMs", durationMs)
            .put("publishedAtMs", publishedAtMs)

        /**
         * The pure manifest-identity checks of [validateManifest] (everything
         * before the per-file integrity walk), including G3-2's flavor
         * consistency gate: the flavor is an OPTIONAL key at schema 2 — a
         * manifest that predates it is fine, but a stored flavor that
         * disagrees with the stamped clientId is refused (fail-closed against
         * doctored manifests; the writer derives the value from the verified
         * PE identity, so disagreement means the record was tampered with).
         */
        internal fun validateManifestShape(manifest: JSONObject, importId: String) {
            check(manifest.getInt("schema") == 2 && manifest.getBoolean("complete"))
            check(ClientFlavors.isManagedClientId(manifest.getString("clientId")))
            val flavor = ClientFlavors.byClientId(manifest.getString("clientId"))!!
            check(manifest.optString("flavor", ClientFlavors.VANILLA.flavorId) == flavor.flavorId) {
                "stored client flavor \"${manifest.optString("flavor")}\" disagrees with the " +
                    "manifest clientId \"${manifest.getString("clientId")}\""
            }
            check(manifest.getString("managedRoot").endsWith("/$importId"))
        }

        /**
         * App-written Config.wtf for a fresh generation (this file is
         * app-owned and replaces any WTF/Config.wtf that arrived inside the
         * imported tree). The interface-default keys sit outside
         * ManagedConfigPolicy's enforced set, so the client owns them after
         * the first launch and rewrites them with the player's own values at
         * exit.
         * MasterSoundEffects "1" = all sounds on.
         */
        private const val SAFE_CONFIG = """SET readTOS "1"\r
SET readEULA "1"\r
SET readScanning "1"\r
SET movie "0"\r
SET gxResolution "1280x720"\r
SET gxWindow "1"\r
SET gxMaximize "0"\r
SET gxVSync "0"\r
SET gxMultisample "1"\r
SET gxMultisampleQuality "0.000000"\r
SET maxFPS "30"\r
SET MasterSoundEffects "1"\r
SET autoSelfCast "1"\r
SET statusBarText "1"\r
SET UnitNameNPC "1"\r
SET UnitNamePlayerGuild "0"\r
SET UnitNamePlayerPVPTitle "0"\r
SET cameraSmoothStyle "0"\r
SET ffxGlow "0"\r
SET ffxDeath "0"\r
SET farclip "177"\r
SET realmName "MaNGOS"\r
"""
    }
}
