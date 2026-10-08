package com.pocketrealm.supervisor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [UserAccountStore] file I/O: atomic write, owner-only
 * perms, save/load/clear, and schema validation. The pure validation/redaction
 * logic is covered on the host JVM by `UserAccountStoreValidationTest`.
 */
@RunWith(AndroidJUnit4::class)
class UserAccountStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = UserAccountStore(context)

    @Before fun wipe() = wipeAll()

    @After fun cleanup() = wipeAll()

    /**
     * The G3-1 tests exercise the quarantine lane too. [UserAccountStore.clear]
     * sweeps the forensic `account.invalid.*` copies itself (an explicit
     * removal must remove the plaintext secret, copies included); the manual
     * sweep here is belt-and-braces so JUnit's method ordering can never let
     * a no-quarantine assertion observe a previous test's forensic copy.
     */
    private fun wipeAll() {
        store.clear()
        store.fileForTest().parentFile
            ?.listFiles { file -> file.name.startsWith("account.invalid.") }
            ?.forEach { it.delete() }
    }

    @Test fun savedAccountLoadsBack() {
        assertNull(store.loadProvisioned())
        store.save("player1", "topsecret", 7L)
        val loaded = store.loadProvisioned()
        assertNotNull(loaded)
        assertEquals("player1", loaded!!.username)
        assertEquals("topsecret", loaded.password)
        assertEquals(7L, loaded.accountId)
    }

    @Test fun clearRemovesTheRecord() {
        store.save("player1", "topsecret", 7L)
        store.clear()
        assertNull(store.loadProvisioned())
    }

    @Test fun clearSweepsTheQuarantineCopiesToo() {
        // The explicit removal must remove the SECRET, not just the active
        // slot: each account.invalid.*.json copy carries the plaintext
        // username+password record.
        store.save("player1", "topsecret", 7L)
        store.fileForTest().writeText("{not json")
        assertNull("the corrupt load must quarantine", store.loadOrQuarantine())
        val copiesAfterQuarantine = store.fileForTest().parentFile
            ?.listFiles { file -> file.name.startsWith("account.invalid.") }
        assertTrue("a quarantine copy must exist for this pin", !copiesAfterQuarantine.isNullOrEmpty())

        store.clear()

        assertEquals(
            "an explicit removal leaves no plaintext copy behind",
            0,
            store.fileForTest().parentFile
                ?.listFiles { file -> file.name.startsWith("account.invalid.") }?.size,
        )
    }

    @Test fun reCreateSilentlyOverwrites() {
        store.save("first", "pwd1", 1L)
        store.save("second", "pwd2", 2L)
        val loaded = store.loadProvisioned()!!
        assertEquals("second", loaded.username)
        assertEquals(2L, loaded.accountId)
    }

    @Test fun recordFileIsOwnerOnly() {
        store.save("player1", "topsecret", 7L)
        val stat = android.system.Os.lstat(store.fileForTest().absolutePath)
        val mode = stat.st_mode and 0x1FF
        assertTrue("record perms too open: 0x${mode.toString(16)}", mode and 0x77 == 0)
    }

    /**
     * G3-1 deployed-byte pin: on the DEVICE, the platform org.json preserves
     * the writer's `put`-chain insertion order, so a vanilla save must encode
     * to the exact committed current-release fixture bytes — the shape the
     * shipping build writes (`{"schema":2,"username":…,"password":…,
     * "accountId":…,"gmLevel":…}`). This is the byte equality the JVM lane
     * cannot assert (its org.json artifact hash-scrambles key order).
     */
    @Test fun vanillaRecordEncodesToTheExactDeployedBytes() {
        val fixture = javaClass.getResourceAsStream("/fixtures/g3/user-account-current-release.json")!!
            .readBytes()
        val encoded = UserAccountStore.encodeRecord(
            UserAccountStore.UserAccount("player1", "hunter2", 42L, 0),
        )
        assertArrayEquals(
            "device serialization drifted from the committed fixture: " +
                "fixture=${String(fixture, Charsets.UTF_8)} actual=${String(encoded, Charsets.UTF_8)}",
            fixture,
            encoded,
        )
    }

    /**
     * G3-1 instrumented proof: a record written with the OLD build's exact
     * bytes (the committed current-release fixture — no flavor key) survives
     * the multi-flavor build untouched: `loadOrQuarantine()` returns the
     * account and no `account.invalid.*` quarantine copy appears.
     */
    @Test fun preFlavorFixtureRecordLoadsWithoutQuarantine() {
        val fixture = javaClass.getResourceAsStream("/fixtures/g3/user-account-current-release.json")!!
            .readBytes()
        val directory = store.fileForTest().parentFile!!
        directory.mkdirs()
        java.io.FileOutputStream(store.fileForTest()).use { it.write(fixture) }

        val loaded = store.loadOrQuarantine()
        assertNotNull("pre-flavor record must load, not quarantine", loaded)
        assertEquals("player1", loaded!!.username)
        assertEquals(42L, loaded.accountId)
        assertEquals(com.pocketrealm.client.ClientFlavors.VANILLA.flavorId, loaded.flavor)
        assertTrue(
            "quarantine copy must not appear",
            directory.listFiles { file -> file.name.startsWith("account.invalid.") }.isNullOrEmpty(),
        )
    }

    /** An unknown flavor VALUE is genuinely malformed: it quarantines (fail-closed). */
    @Test fun unknownFlavorRecordQuarantines() {
        val fixture = javaClass.getResourceAsStream("/fixtures/g3/user-account-current-release.json")!!
            .readBytes()
        val doctored = String(fixture, Charsets.UTF_8)
            .replaceFirst("{", "{\"flavor\":\"cata\",")
        val directory = store.fileForTest().parentFile!!
        directory.mkdirs()
        java.io.FileOutputStream(store.fileForTest()).use {
            it.write(doctored.toByteArray(Charsets.UTF_8))
        }

        assertNull("unknown flavor must quarantine", store.loadOrQuarantine())
        assertTrue(
            "quarantine copy must exist",
            directory.listFiles { file -> file.name.startsWith("account.invalid.") }?.size == 1,
        )
    }
}
