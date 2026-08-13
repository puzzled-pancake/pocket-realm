package com.winlator.xenvironment.components;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Thread-safe lifecycle binding for (context generation, instance, XID). */
final class WindowAuthorityBindings {
    private final Set<Long> activeGenerations = new HashSet<>();
    private final Map<Key, Long> bindings = new HashMap<>();
    private boolean closed;

    private static final class Key {
        final long generation;
        final long instanceToken;
        final int windowId;

        Key(long generation, long instanceToken, int windowId) {
            this.generation = generation;
            this.instanceToken = instanceToken;
            this.windowId = windowId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key)other;
            return generation == key.generation &&
                    instanceToken == key.instanceToken && windowId == key.windowId;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(generation);
            result = 31 * result + Long.hashCode(instanceToken);
            return 31 * result + windowId;
        }
    }

    synchronized boolean registerGeneration(long generation) {
        return !closed && generation != 0L && activeGenerations.add(generation);
    }

    synchronized boolean unregisterGeneration(long generation) {
        if (generation == 0L) return false;
        boolean removed = activeGenerations.remove(generation);
        Iterator<Key> iterator = bindings.keySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().generation == generation) iterator.remove();
        }
        return removed;
    }

    synchronized boolean releaseInstance(long generation, long instanceToken) {
        if (generation == 0L || instanceToken == 0L ||
                !activeGenerations.contains(generation)) return false;
        Iterator<Key> iterator = bindings.keySet().iterator();
        while (iterator.hasNext()) {
            Key key = iterator.next();
            if (key.generation == generation &&
                    key.instanceToken == instanceToken) iterator.remove();
        }
        return true;
    }

    synchronized long bind(
            long generation, long instanceToken, int windowId, long lifetime) {
        if (closed || generation == 0L || instanceToken == 0L ||
                windowId <= 0 || lifetime <= 0L ||
                !activeGenerations.contains(generation)) return 0L;
        Key key = new Key(generation, instanceToken, windowId);
        Long bound = bindings.get(key);
        if (bound == null) {
            bindings.put(key, lifetime);
            return lifetime;
        }
        return bound == lifetime ? bound : 0L;
    }

    synchronized void close() {
        closed = true;
        activeGenerations.clear();
        bindings.clear();
    }
}
