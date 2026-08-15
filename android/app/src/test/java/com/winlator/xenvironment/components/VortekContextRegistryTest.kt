package com.winlator.xenvironment.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VortekContextRegistryTest {
    @Test
    fun callbackThenCloseDestroysEachPointerExactlyOnce() {
        val destroyed = mutableListOf<Long>()
        val registry = VortekContextRegistry(destroyed::add)

        assertEquals(1, registry.register(11L))
        assertTrue(registry.destroy(11L))
        assertFalse(registry.destroy(11L))
        assertEquals(0, registry.drain())
        assertEquals(listOf(11L), destroyed)
    }

    @Test
    fun closeWithoutCallbackDrainsEveryRegisteredPointer() {
        val destroyed = mutableListOf<Long>()
        val registry = VortekContextRegistry(destroyed::add)

        registry.register(21L)
        registry.register(22L)
        registry.register(23L)

        assertEquals(3, registry.drain())
        assertEquals(setOf(21L, 22L, 23L), destroyed.toSet())
        assertEquals(0, registry.size())
        assertEquals(0, registry.drain())
    }

    @Test(expected = IllegalStateException::class)
    fun duplicatePointerCannotAcquireTwoOwners() {
        val registry = VortekContextRegistry { }
        registry.register(31L)
        registry.register(31L)
    }
}
