package com.pocketrealm.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The first-join wizard's saved logins: one per game, atomic, never
 * leaking the password through toString. */
class JoinedRealmLoginsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = JoinedRealmLogins(File(temp.root, "joined-logins/joined.json"))

    @Test
    fun savesAndReadsBackPerGame() {
        val store = store()
        store.save("ab".repeat(32), "Wanderer", "sesame")
        store.save("lan:192.168.1.20", "Other", "open")
        assertEquals("Wanderer", store.savedLogin("ab".repeat(32))?.username)
        assertEquals("sesame", store.savedLogin("ab".repeat(32))?.password)
        assertTrue(store.savedLogin("ab".repeat(32))!!.complete)
        assertEquals("Other", store.savedLogin("lan:192.168.1.20")?.username)
        assertNull(store.savedLogin("cd".repeat(32)))
        assertEquals(setOf("ab".repeat(32), "lan:192.168.1.20"), store.keys())
    }

    @Test
    fun theFileSurvivesAReopen() {
        val file = File(temp.root, "joined-logins/joined.json")
        JoinedRealmLogins(file).save("ab".repeat(32), "Wanderer", "sesame")
        val reread = JoinedRealmLogins(file)
        assertEquals("sesame", reread.savedLogin("ab".repeat(32))?.password)
    }

    @Test
    fun theMigratedEntryMeansAnAccountWithoutASavedLogin() {
        val store = store()
        store.markAccountMade("ab".repeat(32))
        val login = store.savedLogin("ab".repeat(32))
        assertTrue("the account is known", login != null)
        assertFalse("no saved password", login!!.complete)
        assertNull(login.username)
        // A later real save replaces the migrated marker.
        store.save("ab".repeat(32), "Wanderer", "sesame")
        assertTrue(store.savedLogin("ab".repeat(32))!!.complete)
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndHeals() {
        val file = File(temp.root, "joined-logins/joined.json")
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val store = JoinedRealmLogins(file)
        assertNull(store.savedLogin("ab".repeat(32)))
        store.save("ab".repeat(32), "Wanderer", "sesame")
        assertEquals("sesame", JoinedRealmLogins(file).savedLogin("ab".repeat(32))?.password)
    }

    @Test
    fun thePasswordNeverAppearsInToString() {
        val store = store()
        store.save("ab".repeat(32), "Wanderer", "sesame")
        val rendered = store.savedLogin("ab".repeat(32)).toString()
        assertFalse("sesame" in rendered)
        assertFalse("Wanderer" in rendered)
    }

    @Test
    fun forgetRemovesOnlyThatGame() {
        val store = store()
        store.save("ab".repeat(32), "Wanderer", "sesame")
        store.save("cd".repeat(32), "Other", "open")
        store.forget("ab".repeat(32))
        assertNull(store.savedLogin("ab".repeat(32)))
        assertEquals("Other", store.savedLogin("cd".repeat(32))?.username)
    }
}
