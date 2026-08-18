package com.pocketrealm.server

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Generation fence preventing a retired admission thread from publishing into a new run. */
internal class AdmissionMonitorEpoch {
    private val lock = Any()
    private var value = 0L

    fun begin(): Long = synchronized(lock) { ++value }

    fun invalidate(): Long = synchronized(lock) { ++value }

    /** Retires native work while no current-generation action can enter. */
    fun invalidate(retire: () -> Unit): Long = synchronized(lock) {
        ++value
        retire()
        value
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        value == generation
    }

    fun publishIfCurrent(generation: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (value != generation) return@synchronized false
        publish()
        true
    }

    /** Holds the epoch gate through a bounded native side effect. */
    fun <T : Any> runIfCurrent(generation: Long, action: () -> T): T? = synchronized(lock) {
        if (value != generation) return@synchronized null
        action()
    }
}

/** Serializes Binder/native lifecycle transitions without using the monitor-reference lock. */
internal class AdmissionTransitionGate {
    private val lock = ReentrantLock()

    fun <T> run(transition: () -> T): T = lock.withLock(transition)
}
