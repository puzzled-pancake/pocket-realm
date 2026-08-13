package com.pocketrealm.client

import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ClientRealmEndpointProjectionTest {
    @Test fun localLaunchAtomicallyOverwritesAStaleLanRealmlist() {
        val root = Files.createTempDirectory("pocket-realmlist").toFile()
        val file = root.resolve("realmlist.wtf")
        try {
            ClientRealmEndpointProjection.project(file, RealmEndpoint.parseLan("192.168.1.25"))
            assertEquals("set realmlist 192.168.1.25", file.readText().trim())

            ClientRealmEndpointProjection.project(file, RealmEndpoint.LOCAL)

            assertEquals("set realmlist 127.0.0.1", file.readText().trim())
            assertEquals(emptyList<String>(), root.listFiles().orEmpty()
                .filter { it.name.endsWith(".tmp") }.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }
}
