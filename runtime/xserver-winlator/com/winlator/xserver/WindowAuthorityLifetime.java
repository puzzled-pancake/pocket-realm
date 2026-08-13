package com.winlator.xserver;

import java.util.concurrent.atomic.AtomicLong;

/** Process-wide, non-repeating identity allocator for exact Window lifetimes. */
final class WindowAuthorityLifetime {
    private static final AtomicLong NEXT = new AtomicLong(1L);

    private WindowAuthorityLifetime() {}

    static long allocate() {
        return allocateFrom(NEXT);
    }

    /** Package-private seam for deterministic concurrency/exhaustion tests. */
    static long allocateFrom(AtomicLong source) {
        if (source == null) throw new IllegalArgumentException("source");
        long current = source.get();
        while (true) {
            if (current <= 0L) {
                throw new IllegalStateException(
                        "X window authority lifetime exhausted");
            }
            long next = current == Long.MAX_VALUE ? 0L : current + 1L;
            if (source.compareAndSet(current, next)) return current;
            current = source.get();
        }
    }
}
