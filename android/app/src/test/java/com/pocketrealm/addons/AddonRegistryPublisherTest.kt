package com.pocketrealm.addons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AddonRegistryPublisherTest {
    @Test fun `failure between rollback and current publication restores every pre-operation file`() {
        listOf("install", "remove", "rollback").forEach { operation ->
            val root = Files.createTempDirectory("addon-registry-$operation-").toFile()
            try {
                val registry = File(root, "registry.json").apply { writeText("current-$operation") }
                val previous = File(root, "registry.previous.json").apply { writeText("previous-$operation") }
                val journal = File(root, "registry.transaction.json")
                val oldPackage = File(root, "packages/old/marker").apply {
                    parentFile!!.mkdirs()
                    writeText("old-package")
                }
                val finalNewPackage = File(root, "packages/new")
                var failCurrentOnce = true
                val publisher = AddonRegistryPublisher(registry, previous, journal, atomicWrite = { file, content ->
                    if (file == registry && failCurrentOnce) {
                        failCurrentOnce = false
                        throw IOException("injected current-registry failure")
                    }
                    atomicWrite(file, content)
                })

                var visibleState = "current-$operation"
                var publishedNewPackage = false
                assertThrows(IOException::class.java) {
                    try {
                        if (operation == "install") {
                            check(finalNewPackage.mkdirs())
                            File(finalNewPackage, "new").writeText("new-package")
                            publishedNewPackage = true
                        }
                        publisher.publish("next-$operation")
                        visibleState = "next-$operation"
                    } catch (failure: Throwable) {
                        if (publishedNewPackage) finalNewPackage.deleteRecursively()
                        throw failure
                    }
                }

                assertEquals("current-$operation", registry.readText())
                assertEquals("previous-$operation", previous.readText())
                assertEquals("old-package", oldPackage.readText())
                assertFalse(finalNewPackage.exists())
                assertEquals("current-$operation", visibleState)
                assertFalse(journal.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun `startup recovery restores exact existence and content snapshots`() {
        val root = Files.createTempDirectory("addon-registry-recovery-").toFile()
        try {
            val registry = File(root, "registry.json").apply { writeText("partially-published") }
            val previous = File(root, "registry.previous.json").apply { writeText("overwritten-history") }
            val journal = File(root, "registry.transaction.json")
            journal.writeText(JSONObject()
                .put("schema", 1)
                .put("registry", JSONObject().put("existed", true).put("content", "original-current"))
                .put("previous", JSONObject().put("existed", false).put("content", JSONObject.NULL))
                .toString())

            val publisher = AddonRegistryPublisher(registry, previous, journal, ::atomicWrite)
            publisher.recoverIfNeeded()

            assertEquals("original-current", registry.readText())
            assertFalse(previous.exists())
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `successful first publication creates an empty rollback generation`() {
        val root = Files.createTempDirectory("addon-registry-first-").toFile()
        try {
            val registry = File(root, "registry.json")
            val previous = File(root, "registry.previous.json")
            val journal = File(root, "registry.transaction.json")
            AddonRegistryPublisher(registry, previous, journal, ::atomicWrite).publish("new-current")
            assertEquals("new-current", registry.readText())
            assertTrue(previous.readText().contains("\"installed\":[]"))
            assertFalse(journal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        fun atomicWrite(destination: File, content: String) {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, ".${destination.name}.test.tmp")
            temp.writeText(content)
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
