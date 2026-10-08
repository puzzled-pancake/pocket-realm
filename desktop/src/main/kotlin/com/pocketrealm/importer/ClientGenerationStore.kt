package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.ClientGenerationLease
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * DESK-67/97: immutable generation publication with an atomic active-pointer
 * switch — the desktop twin of the Android importer/ClientGenerationStore.
 * Same layout (content/client/generations/<uuid>, per-flavor
 * `active-<flavorId>.json` pointers with the legacy un-namespaced pair as a
 * one-time seed), same manifest schema, same prune keep-set through the
 * SHARED [GenerationPointers] plumbing, and the cross-process publication
 * fence through the SHARED [ClientGenerationLease] (both compiled straight
 * from the Android tree).
 *
 * Platform substitutions (the ask's register: Context→DesktopStorageRoots,
 * Os.fsync→FileChannel.force):
 *  - `android.system.Os.rename` → `Files.move` with ATOMIC_MOVE and a
 *    plain-replace fallback (same discipline as DesktopSettingsStore);
 *  - `android.system.Os.fsync(directory)` → no-op with an honest comment:
 *    Windows cannot open a directory through FileChannel and NTFS journals
 *    the rename (the seeder's and verifyOrRebuildSqlite's posture);
 *  - `android.os.Process.myPid()` → `ProcessHandle.current().pid()`;
 *  - the POSIX 0600 chmod is dropped: files inherit the per-user ACL of
 *    %LOCALAPPDATA% (no world-readable exposure exists to prevent).
 */
class ClientGenerationStore(roots: com.pocketrealm.desktop.DesktopStorageRoots) {
    private val root = File(roots.content, "client").apply { mkdirs() }
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
     * The active generation id for [flavor]'s pointer namespace (G3-5). The
     * read adopts a legacy un-namespaced pointer first, so an updated build
     * resolves the pre-flavor active generation on its first boot.
     *
     * GAP-3 (owner census UIUX-3): the default resolves the ACTIVE lane
     * through the [ActiveFlavor] machine — the switcher's selection drives
     * the pointer namespace when several flavors qualify, replacing the
     * former `qualified().single()` default that threw the moment a second
     * flavor qualified (dormant while desktop callers pass the flavor
     * explicitly; the Android twin's no-arg caller made it live).
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
        val journal = plan.journal
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
            journal = journal,
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
            atomicMove(staging, final)
            afterRenameBeforeActivate()
            activateLocked(importId, digest, clientId)
            PublishedGeneration(importId, final, digest)
        }
    }

    /**
     * Flavor-scoped activation (G3-5): the pointer switch and the prune run
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
    }

    /**
     * G3-5 legacy seed: on first read, when the canonical per-flavor pointer
     * is absent and the un-namespaced pointer exists, adopt it by atomic move
     * (never leave both readable as authoritative) and continue under the
     * canonical name.
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
            atomicMove(legacy, canonical)
        }
        return canonical
    }

    private fun writeManagedConfiguration(staging: File) {
        atomicWrite(File(staging, "realmlist.wtf"), managedRealmlistWtfBytes())
        atomicWrite(File(staging, "WTF/Config.wtf"), managedConfigWtfBytes())
    }

    /** fsync-before-rename durability over FileChannel (the ask's
     * Os.fsync→FileChannel.force substitution for the Android store's
     * `output.fd.sync()`). */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${ProcessHandle.current().pid()}.tmp")
        FileChannel.open(
            temp.toPath(),
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        atomicMove(temp, target)
    }

    /**
     * Os.rename twin: ATOMIC_MOVE where the filesystem supports it, with the
     * plain-replace fallback the Android store's own write discipline carries
     * (Files.move without ATOMIC_MOVE). Same-destination REPLACE_EXISTING
     * covers the publish rename onto an absent target and the pointer
     * replacement alike.
     */
    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * No directory fsync on this platform: Windows cannot open a directory
     * through FileChannel and NTFS journals the rename (the same posture the
     * seeder and verifyOrRebuildSqlite document). The Android twin's
     * `fsyncDirectory` calls therefore have no desktop counterpart; the
     * atomic-move ordering discipline (rename → pointer switch → prune) is
     * preserved exactly.
     */

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

    /**
     * The journal summary the manifest pins (the Android writer computes it
     * from ImportJournal rows; the desktop folder lane counts its per-file
     * outcomes — the durable manifest FORMAT is identical, ImportJournal
     * schema 4 included, so a manifest from either platform reads the same).
     */
    data class JournalSummary(val verified: Int, val skipped: Int, val maxAttempt: Int)

    /** Publish-time callbacks (onManifestFile drives PUBLISHING ticks). */
    data class PublishCallbacks(
        val afterRenameBeforeActivate: () -> Unit = {},
        val onManifestFile: (String) -> Unit = {},
    )

    data class PublishPlan(
        val identity: JSONObject,
        val sourceFingerprint: String,
        val journal: JournalSummary,
        val durationMs: Long,
    )

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** The importer journal schema the manifest stamps (ImportJournal.SCHEMA
         * on Android; the desktop has no journal twin yet — DESK-3/66 — so the
         * schema constant is pinned here to keep the manifest byte-shape). */
        internal const val IMPORT_JOURNAL_SCHEMA = 4

        /** GAP-3: the [ActiveFlavor] lane suffix naming the active-generation
         * default-arg gate. */
        private const val ACTIVE_GENERATION_LANE =
            "the active client generation resolves the qualified lane"

        /** G3-7 golden seam: the app-owned `realmlist.wtf` publish bytes. */
        internal fun managedRealmlistWtfBytes(): ByteArray =
            "set realmlist 127.0.0.1\r\n".toByteArray()

        /** G3-7 golden seam: the app-owned `WTF/Config.wtf` publish bytes (SAFE_CONFIG normalized). */
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
         * durable format is golden-fixture-testable on the JVM (G3-2): the
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
            journal: JournalSummary,
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
            .put("managedRoot", "content/client/generations/$importId")
            .put("safeMode", JSONObject()
                .put("renderer", "wined3d").put("resolution", "1280x720")
                .put("fpsCap", SAFE_MODE_FPS_CAP).put("audio", "off").put("realmEndpoint", "127.0.0.1")
                .put("addons", "preserved-disabled-until-reviewed"))
            .put("appOwnedFiles", JSONArray(listOf("realmlist.wtf", "WTF/Config.wtf")))
            .put("journal", JSONObject()
                .put("schema", IMPORT_JOURNAL_SCHEMA)
                .put("verified", journal.verified)
                .put("skipped", journal.skipped)
                .put("maxAttempt", journal.maxAttempt))
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
