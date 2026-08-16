package com.pocketrealm.client

import android.content.Context
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Fail-closed reader for the app-private O07 client generation. */
internal class ManagedClientStore(context: Context) {
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
        require(clientId == ClientRuntimeContract.WOW_5875_ID) { "unsupported managed client id" }
        val pointer = File(clientRoot, "active.json")
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
        } else File(clientRoot, "active") // O07 debug-generation compatibility
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
        check(identity.getInt("build") == 5875 && identity.getString("version") == "1.12.1.5875") {
            "managed client is not WoW 1.12.1 build 5875"
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
        check(File(root, "Data/base.MPQ").isFile && File(root, "Data/interface.MPQ").isFile) {
            "managed client base data is incomplete"
        }
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

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)
}
