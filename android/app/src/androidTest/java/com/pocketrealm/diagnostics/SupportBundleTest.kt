package com.pocketrealm.diagnostics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.client.ClientRuntimeContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SupportBundleTest {
    @Test fun exportPreservesBuildIdsAndRejectsEverySecretCanary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val canaries = listOf("UPPERUSER", "lowerpass", "DbSecret99", "SourceFolderCanary")
        val raw = """{"username":"UPPERUSER","password":"lowerpass","dbSecret":"DbSecret99",
          "message":"SourceFolderCanary content://fixture/tree 10.0.2.15 C:\\Users\\Alice\\WoW"}"""
        val result = SupportBundleExporter(context).export(canaries, mapOf("canaries.json" to raw))
        val contents = ZipFile(result.file).use { zip ->
            zip.entries().asSequence().joinToString("\n") { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }
        canaries.forEach { assertFalse(contents.contains(it, ignoreCase = true)) }
        assertFalse(contents.contains("content://"))
        assertFalse(contents.contains("10.0.2.15"))
        assertTrue(contents.contains(ClientRuntimeContract.RUNTIME_BUILD_ID))
        assertTrue(contents.contains(ClientRuntimeContract.RENDERER_BUILD_ID))
    }
}
