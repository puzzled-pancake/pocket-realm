package com.pocketrealm.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ClientGenerationLeaseTest {
    @Test fun `shared runtime leases exclude publication until final idempotent close`() {
        val root = Files.createTempDirectory("client-lease").toFile()
        try {
            val first = ClientGenerationLease.acquireRuntime(root)
            val second = ClientGenerationLease.acquireRuntime(root)
            assertNull(ClientGenerationLease.tryAcquirePublication(root))
            first.close(); first.close()
            assertNull(ClientGenerationLease.tryAcquirePublication(root))
            second.close()
            val publication = ClientGenerationLease.tryAcquirePublication(root)
            assertNotNull(publication)
            assertTrue(publication!!.isHeld)
            publication.close()
            assertFalse(publication.isHeld)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `import operation lease is exclusive`() {
        val root = Files.createTempDirectory("client-import-lease").toFile()
        try {
            val lease = ClientGenerationLease.acquireImportOperation(root)
            assertTrue(lease.isHeld)
            lease.close()
            ClientGenerationLease.acquireImportOperation(root).close()
        } finally {
            root.deleteRecursively()
        }
    }
}
