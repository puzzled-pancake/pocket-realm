package com.pocketrealm.client

import com.pocketrealm.fs.FileDigests
import com.pocketrealm.importer.GenerationPointers
import com.pocketrealm.importer.SyntheticClientArchives
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ManagedClientStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun loadsPublishedClientWithExecutableSpellingPreservedFromImport() {
        val (store, executable) = publishedClient("Wow.exe")
        val client = store.load(ClientFlavors.VANILLA.clientId)
        assertEquals(executable.canonicalFile, client.executable)
        assertEquals("Wow.exe", client.executable.name)
    }

    @Test fun stillChecksExecutableHashForMixedCaseName() {
        val (store, executable) = publishedClient("wow.exe")
        val bytes = executable.readBytes()
        bytes[bytes.lastIndex] = 1
        executable.writeBytes(bytes)
        val error = assertThrows(IllegalStateException::class.java) {
            store.load(ClientFlavors.VANILLA.clientId)
        }
        assertEquals("managed WoW.exe hash mismatch", error.message)
    }

    private fun publishedClient(executableName: String): Pair<ManagedClientStore, File> {
        val flavor = ClientFlavors.VANILLA
        val clientRoot = temp.newFolder()
        val generation = UUID.randomUUID().toString()
        val root = File(clientRoot, "generations/$generation").apply { mkdirs() }
        val executable = File(root, executableName).apply { writeBytes(SyntheticClientArchives.syntheticPe()) }
        val data = File(root, "Data").apply { mkdirs() }
        for (name in SyntheticClientArchives.BASE_MPQS) {
            File(data, name).writeBytes(SyntheticClientArchives.mpqStub(name))
        }
        File(root, "realmlist.wtf").writeText("set realmlist 127.0.0.1")
        val identity = JSONObject()
            .put("machine", 0x14c).put("optionalMagic", 0x10b)
            .put("version", flavor.versionResource).put("build", flavor.build)
            .put("size", executable.length()).put("sha256", FileDigests.sha256(executable))
        val manifest = File(root, "client-manifest.json").apply {
            writeText(JSONObject()
                .put("schema", 2).put("complete", true).put("clientId", flavor.clientId)
                .put("directLaunch", true).put("executable", "WoW.exe")
                .put("identity", identity).toString())
        }
        File(clientRoot, GenerationPointers.activePointerName(flavor.flavorId)).writeText(JSONObject()
            .put("schema", 1).put("clientId", flavor.clientId).put("generation", generation)
            .put("manifestSha256", FileDigests.sha256(manifest)).toString())
        return ManagedClientStore(clientRoot) to executable
    }
}
