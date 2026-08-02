package com.pocketrealm.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Fail-closed reader for the app-private O07 client generation. */
internal class ManagedClientStore(context: Context) {
    data class ManagedClient(
        val id: String,
        val root: File,
        val executable: File,
        val manifest: JSONObject,
    )

    private val expectedRoot = File(context.noBackupFilesDir, "client/active")

    fun load(clientId: String): ManagedClient {
        require(clientId == ClientRuntimeContract.WOW_5875_ID) { "unsupported managed client id" }
        val expected = expectedRoot.absoluteFile
        val expectedParent = checkNotNull(expected.parentFile).canonicalFile
        val root = expectedRoot.canonicalFile
        check(root.parentFile == expectedParent && root.name == "active" && root.isDirectory &&
            !Files.isSymbolicLink(expected.toPath())) {
            "managed client generation is absent or unsafe"
        }
        val manifestFile = File(root, "client-manifest.json")
        check(manifestFile.isFile && !Files.isSymbolicLink(manifestFile.toPath())) {
            "managed client manifest is absent or unsafe"
        }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.optInt("schema") == 1 && manifest.optBoolean("complete")) {
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
        check(executable.length() == identity.getLong("size")) { "managed WoW.exe size mismatch" }
        check(sha256(executable) == identity.getString("sha256").lowercase()) {
            "managed WoW.exe hash mismatch"
        }
        check(File(root, "Data/base.MPQ").isFile && File(root, "Data/interface.MPQ").isFile) {
            "managed client base data is incomplete"
        }
        check(File(root, "realmlist.wtf").readText().trim() == "set realmlist 127.0.0.1") {
            "managed client endpoint is not loopback"
        }
        return ManagedClient(clientId, root, executable, manifest)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
