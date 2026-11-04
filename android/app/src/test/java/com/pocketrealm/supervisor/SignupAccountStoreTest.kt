package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignupAccountStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = SignupAccountStore(temp.root.resolve(SignupAccountStore.FILE_NAME))

    @Test
    fun accountsPersistAcrossInstancesAndAddIsCaseBlindIdempotent() {
        store().add("Wanderer", 10L)
        store().add("WANDERER", 20L)
        store().add("Second", 30L)
        assertEquals(listOf("Wanderer", "Second"), store().names())
        assertEquals(10L, store().list().first().createdAtMs)
    }

    @Test
    fun removeDropsTheNameAndReportsWhetherItWasListed() {
        store().add("Wanderer", 10L)
        assertTrue(store().remove("wanderer"))
        assertFalse(store().remove("wanderer"))
        assertTrue(store().names().isEmpty())
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndTheNextWriteHealsIt() {
        temp.root.resolve(SignupAccountStore.FILE_NAME).writeText("{not json")
        assertTrue(store().names().isEmpty())
        store().add("Fresh", 1L)
        assertEquals(listOf("Fresh"), store().names())
    }

    @Test
    fun theFileHoldsNamesAndTimesOnly() {
        store().add("Wanderer", 10L)
        val text = temp.root.resolve(SignupAccountStore.FILE_NAME).readText()
        assertFalse(text.contains("vHex") || text.contains("sHex") || text.contains("password"))
    }
}
