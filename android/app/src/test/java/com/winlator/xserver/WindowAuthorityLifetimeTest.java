package com.winlator.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public final class WindowAuthorityLifetimeTest {
    @Test
    public void allocationsAreMonotonicAndNonzero() {
        AtomicLong source = new AtomicLong(17L);
        assertEquals(17L, WindowAuthorityLifetime.allocateFrom(source));
        assertEquals(18L, WindowAuthorityLifetime.allocateFrom(source));
        assertEquals(19L, source.get());
    }

    @Test
    public void exhaustionPermanentlyLatchesAtZero() {
        AtomicLong source = new AtomicLong(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, WindowAuthorityLifetime.allocateFrom(source));
        assertEquals(0L, source.get());
        assertThrows(IllegalStateException.class,
                () -> WindowAuthorityLifetime.allocateFrom(source));
        assertEquals(0L, source.get());
    }

    @Test
    public void concurrentAllocationsNeverCollide() throws Exception {
        final int threadCount = 8;
        final int allocationsPerThread = 1000;
        AtomicLong source = new AtomicLong(1L);
        Set<Long> values = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];
        for (int thread = 0; thread < threadCount; ++thread) {
            threads[thread] = new Thread(() -> {
                try {
                    start.await();
                    for (int allocation = 0;
                            allocation < allocationsPerThread; ++allocation) {
                        long value = WindowAuthorityLifetime.allocateFrom(source);
                        assertTrue(value > 0L);
                        values.add(value);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });
            threads[thread].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(threadCount * allocationsPerThread, values.size());
        assertEquals((long)threadCount * allocationsPerThread + 1L, source.get());
    }
}
