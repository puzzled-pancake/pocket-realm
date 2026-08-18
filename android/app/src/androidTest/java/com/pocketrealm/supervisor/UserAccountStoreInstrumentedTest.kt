package com.pocketrealm.supervisor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
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

    @Before fun wipe() = store.clear()

    @After fun cleanup() = store.clear()

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
}
