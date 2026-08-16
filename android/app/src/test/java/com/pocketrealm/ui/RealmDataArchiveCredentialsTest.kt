package com.pocketrealm.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Credential-exclusion contract for realm-data exports (de-vibe A2): the
 * account file must appear ONLY when the caller explicitly passes it; the
 * SettingsScreen default passes null.
 */
class RealmDataArchiveCredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun snapshot(dirName: String): File {
        val dir = tmp.newFolder(dirName)
        File(dir, "realm.sqlite").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(dir, "nested").mkdir()
        File(dir, "nested/manifest.json").writeText("{}")
        return dir
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) names.add(entry.name)
            }
        }
        return names
    }

    @Test fun `export without account file contains no credentials entry`() {
        val out = ByteArrayOutputStream()
        RealmDataArchive.writeArchive(out, snapshot("a"), null, RealmDataArchive.meta("s1", 1L, "v", "x86_64"))
        val names = entryNames(out.toByteArray())
        assertFalse("credentials must not ride a default export", names.contains("account.json"))
        assertEquals(listOf("realm-archive.json", "snapshot/nested/manifest.json", "snapshot/realm.sqlite"), names.sorted())
    }

    @Test fun `opt-in export includes the credentials entry verbatim`() {
        val account = tmp.newFile("account.json")
        account.writeText("""{"username":"player-1","password":"topsecret"}""")
        val out = ByteArrayOutputStream()
        RealmDataArchive.writeArchive(out, snapshot("b"), account, RealmDataArchive.meta("s2", 2L, "v", "x86_64"))
        val names = entryNames(out.toByteArray())
        assertTrue("explicit opt-in must embed the account file", names.contains("account.json"))
    }

    @Test fun `missing account file is not an error`() {
        val out = ByteArrayOutputStream()
        val ghost = File(tmp.root, "does-not-exist.json")
        RealmDataArchive.writeArchive(out, snapshot("c"), ghost, RealmDataArchive.meta("s3", 3L, "v", "x86_64"))
        assertFalse(entryNames(out.toByteArray()).contains("account.json"))
    }

    @Test fun `inspect reads the manifest`() {
        val out = ByteArrayOutputStream()
        RealmDataArchive.writeArchive(out, snapshot("d"), null, RealmDataArchive.meta("s4", 42L, "0.1.0", "arm64-v8a"))
        val info = RealmDataArchive.inspect(ByteArrayInputStream(out.toByteArray()))
        assertEquals("s4", info.snapshotId)
        assertEquals(42L, info.createdAtMs)
        assertEquals("0.1.0", info.appVersionName)
        assertEquals("arm64-v8a", info.abi)
    }
}
