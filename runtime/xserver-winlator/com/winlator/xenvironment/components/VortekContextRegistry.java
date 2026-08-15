package com.winlator.xenvironment.components;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Process-wide exact-once ownership for native Vortek context pointers. */
final class VortekContextRegistry {
    interface Destroyer {
        void destroy(long contextPtr);
    }

    private final Set<Long> contexts = new HashSet<>();
    private final Destroyer destroyer;

    VortekContextRegistry(Destroyer destroyer) {
        if (destroyer == null) throw new IllegalArgumentException("destroyer is required");
        this.destroyer = destroyer;
    }

    synchronized int register(long contextPtr) {
        if (contextPtr <= 0 || !contexts.add(contextPtr)) {
            throw new IllegalStateException("invalid or duplicate Vortek context pointer");
        }
        return contexts.size();
    }

    boolean destroy(long contextPtr) {
        synchronized (this) {
            if (!contexts.remove(contextPtr)) return false;
        }
        destroyer.destroy(contextPtr);
        return true;
    }

    int drain() {
        final ArrayList<Long> snapshot;
        synchronized (this) {
            if (contexts.isEmpty()) return 0;
            snapshot = new ArrayList<>(contexts);
            contexts.clear();
        }
        for (long contextPtr : snapshot) destroyer.destroy(contextPtr);
        return snapshot.size();
    }

    synchronized int size() {
        return contexts.size();
    }
}
