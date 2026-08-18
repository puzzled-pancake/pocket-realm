package com.winlator.xenvironment.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public final class WindowAuthorityBindingsTest {
    @Test
    public void reusedXidRejectsChangedLifetimeWithinInstance() {
        WindowAuthorityBindings bindings = new WindowAuthorityBindings();
        assertTrue(bindings.registerGeneration(7L));
        assertEquals(101L, bindings.bind(7L, 11L, 42, 101L));
        assertEquals(101L, bindings.bind(7L, 11L, 42, 101L));
        assertEquals(0L, bindings.bind(7L, 11L, 42, 102L));

        assertEquals(201L, bindings.bind(7L, 12L, 42, 201L));
        assertTrue(bindings.releaseInstance(7L, 11L));
        assertEquals(102L, bindings.bind(7L, 11L, 42, 102L));
    }

    @Test
    public void generationAndTeardownFailClosed() {
        WindowAuthorityBindings bindings = new WindowAuthorityBindings();
        assertEquals(0L, bindings.bind(8L, 11L, 42, 101L));
        assertTrue(bindings.registerGeneration(8L));
        assertFalse(bindings.registerGeneration(8L));
        assertEquals(0L, bindings.bind(9L, 11L, 42, 101L));
        assertEquals(101L, bindings.bind(8L, 11L, 42, 101L));
        assertTrue(bindings.unregisterGeneration(8L));
        assertEquals(0L, bindings.bind(8L, 11L, 42, 101L));
        assertTrue(bindings.registerGeneration(9L));
        bindings.close();
        assertEquals(0L, bindings.bind(9L, 11L, 42, 101L));
        assertFalse(bindings.registerGeneration(10L));
    }

    @Test
    public void concurrentFirstBindingSelectsExactlyOneLifetime() throws Exception {
        WindowAuthorityBindings bindings = new WindowAuthorityBindings();
        assertTrue(bindings.registerGeneration(17L));
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> accepted = ConcurrentHashMap.newKeySet();
        Thread[] threads = new Thread[8];
        for (int index = 0; index < threads.length; ++index) {
            final long lifetime = 1000L + index;
            threads[index] = new Thread(() -> {
                try {
                    start.await();
                    long value = bindings.bind(17L, 23L, 77, lifetime);
                    if (value != 0L) accepted.add(value);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });
            threads[index].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, accepted.size());
        long winner = accepted.iterator().next();
        assertEquals(winner, bindings.bind(17L, 23L, 77, winner));
    }
}
