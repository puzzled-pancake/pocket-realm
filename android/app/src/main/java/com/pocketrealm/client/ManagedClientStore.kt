package com.pocketrealm.client

import android.content.Context
import com.pocketrealm.importer.GenerationPointers
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Fail-closed reader for the app-private client generation. */
internal class ManagedClientStore(context: Context) {

    private companion object {
        const val MPQ_WALK_MAX_DEPTH = 4
        const val BACKSLASH = '\\'
        const val FORWARD_SLASH = '/'
    }
    data class ManagedClient(
        val id: String,
        val generation: String,
        val manifestSha256: String,
        val root: File,
        val executable: File,
        val executableSize: Long,
        val executableSha256: String,
        val manifest: JSONObject,
    )

    class LeasedManagedClient internal constructor(
        val client: ManagedClient,
        val lease: ClientGenerationLease,
    ) : AutoCloseable {
        override fun close() = lease.close()
    }

    private val clientRoot = File(context.noBackupFilesDir, "client")

    fun load(clientId: String): ManagedClient =
        acquireRuntime(clientId).use { it.client }

    fun acquireRuntime(clientId: String): LeasedManagedClient {
        val lease = ClientGenerationLease.acquireRuntime(clientRoot)
        try {
            return LeasedManagedClient(loadUnlocked(clientId), lease)
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    fun attestUnderLease(expected: ManagedClient, lease: ClientGenerationLease): ManagedClient {
        check(lease.isHeld) { "managed client generation lease is not held" }
        val actual = loadUnlocked(expected.id)
        check(actual.generation == expected.generation &&
            actual.manifestSha256 == expected.manifestSha256 &&
            actual.root == expected.root &&
            actual.executable == expected.executable &&
            actual.executableSize == expected.executableSize &&
            actual.executableSha256 == expected.executableSha256) {
            "managed client generation changed after preparation"
        }
        return actual
    }

    private fun loadUnlocked(clientId: String): ManagedClient {
        require(ClientFlavors.isManagedClientId(clientId)) { "unsupported managed client id" }
        val pointer = activePointerFor(clientId)
        var expectedManifestHash: String? = null
        var generationIdentity = "legacy-active"
        val expected = if (pointer.isFile) {
            val value = JSONObject(pointer.readText())
            check(value.getInt("schema") == 1 && value.getString("clientId") == clientId)
            expectedManifestHash = value.getString("manifestSha256").lowercase()
            check(expectedManifestHash.matches(Regex("[0-9a-f]{64}")))
            val generation = value.getString("generation")
            check(generation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            generationIdentity = generation
            File(clientRoot, "generations/$generation")
        } else File(clientRoot, "active") // debug-generation compatibility
        val expectedParent = checkNotNull(expected.absoluteFile.parentFile).canonicalFile
        val root = expected.canonicalFile
        check(root.parentFile == expectedParent && root.isDirectory &&
            !Files.isSymbolicLink(expected.toPath())) {
            "managed client generation is absent or unsafe"
        }
        val manifestFile = File(root, "client-manifest.json")
        check(manifestFile.isFile && !Files.isSymbolicLink(manifestFile.toPath())) {
            "managed client manifest is absent or unsafe"
        }
        val actualManifestHash = sha256(manifestFile)
        expectedManifestHash?.let { check(actualManifestHash == it) { "active-pointer manifest hash mismatch" } }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.optInt("schema") in 1..2 && manifest.optBoolean("complete")) {
            "managed client generation is incomplete"
        }
        check(manifest.getString("clientId") == clientId && manifest.optBoolean("directLaunch")) {
            "managed client identity/launch policy mismatch"
        }
        val identity = manifest.getJSONObject("identity")
        check(identity.getInt("machine") == 0x14c && identity.getInt("optionalMagic") == 0x10b) {
            "managed client is not 32-bit x86 PE32"
        }
        val flavor = checkNotNull(ClientFlavors.byClientId(clientId)) { "unsupported managed client id" }
        check(identity.getInt("build") == flavor.build && identity.getString("version") == flavor.versionResource) {
            "managed client is not WoW ${flavor.versionResource.substringBeforeLast('.')} build ${flavor.build}"
        }
        val relativeExe = manifest.getString("executable")
        check(relativeExe == "WoW.exe") { "direct WoW.exe launch is required" }
        val executable = File(root, relativeExe).canonicalFile
        check(executable.parentFile == root && executable.isFile &&
            !Files.isSymbolicLink(executable.toPath())) { "managed WoW.exe is absent or unsafe" }
        val expectedExecutableSize = identity.getLong("size")
        val expectedExecutableSha = identity.getString("sha256").lowercase()
        check(executable.length() == expectedExecutableSize) { "managed WoW.exe size mismatch" }
        check(sha256(executable) == expectedExecutableSha) {
            "managed WoW.exe hash mismatch"
        }
        // W4 (defect 3's sibling, the tbc E2E surfaced it): the base-data
        // gate named VANILLA's base.MPQ/interface.MPQ literals — a tbc/
        // wotlk generation (locale MPQs, no base/interface) refused its
        // own boot. The gate resolves the generation's OWN flavor's
        // required set over a bounded-depth basename walk.
        // R2-R4-2: match each registry key by its own form — a slash key
        // at that path RELATIVE to Data (a basename-only match accepted a
        // path-doctored generation whose client cannot load its data), a
        // bare key by basename.
        val dataDir = java.io.File(root, "Data")
        val presentMpqRelPaths = dataDir.walkTopDown()
            .maxDepth(MPQ_WALK_MAX_DEPTH)
            .filter { it.isFile && it.name.endsWith(".mpq", ignoreCase = true) }
            .map { it.relativeTo(dataDir).path.replace(BACKSLASH, FORWARD_SLASH).lowercase() }
            .toSet()
        check(
            flavor.requiredMpqs.all { key ->
                val lowered = key.lowercase()
                if ('/' in lowered) presentMpqRelPaths.contains(lowered)
                else presentMpqRelPaths.any {
                    it.substringAfterLast('/') == lowered
                }
            }
        ) { "managed client base data is incomplete" }
        val realmlistFile = File(root, "realmlist.wtf")
        check(realmlistFile.isFile && realmlistFile.canonicalFile.parentFile == root &&
            !Files.isSymbolicLink(realmlistFile.toPath()) && realmlistFile.length() in 1..64) {
            "managed client endpoint file is absent or unsafe"
        }
        val realmlist = realmlistFile.readText().trim()
        check(realmlist.startsWith("set realmlist ") &&
            runCatching { RealmEndpoint.parseStored(realmlist.removePrefix("set realmlist ")) }.isSuccess) {
            "managed client endpoint is not a canonical local/private IPv4 address"
        }
        return ManagedClient(
            clientId,
            generationIdentity,
            actualManifestHash,
            root,
            executable,
            expectedExecutableSize,
            expectedExecutableSha,
            manifest,
        )
    }

    /**
     * G3-5: the runtime reads [clientId]'s per-flavor pointer namespace; on
     * first read after the multi-flavor update, an un-namespaced `active.json`
     * is adopted under the flavor's canonical name (atomic move, with the
     * plain-replace fallback the store's own write discipline carries) so the
     * pre-flavor managed client is never orphaned by the rename. The
     * canonical name wins the moment it exists — never both as authoritative.
     */
    private fun activePointerFor(clientId: String): File {
        val flavor = requireNotNull(ClientFlavors.byClientId(clientId)) { "unsupported managed client id" }
        val canonical = File(clientRoot, GenerationPointers.activePointerName(flavor.flavorId))
        if (!canonical.isFile) {
            val legacy = File(clientRoot, GenerationPointers.LEGACY_ACTIVE)
            if (legacy.isFile && legacyAdoptionTargetsThisFlavor(legacy, clientId)) {
                try {
                    Files.move(
                        legacy.toPath(), canonical.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(legacy.toPath(), canonical.toPath())
                }
            }
        }
        return canonical
    }

    /**
     * Round-5 R5-R2-2: legacy adoption may only fire when the legacy
     * pointer's BODY belongs to the requesting flavor — the body stamps
     * the owning clientId (the same field loadUnlocked verifies). On a
     * mismatch the legacy file is LEFT IN PLACE: a stored selection on a
     * second qualified flavor must not consume flavor A's sole managed
     * client into flavor B's namespace (the never-orphaned rule the
     * rename exists for). An unreadable body keeps the single-qualified
     * legacy behavior (adopt), which loadUnlocked then verifies.
     */
    private fun legacyAdoptionTargetsThisFlavor(legacy: File, clientId: String): Boolean =
        runCatching {
            JSONObject(legacy.readText()).getString("clientId")
        }.getOrNull()?.let { it == clientId } ?: true

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)
}
