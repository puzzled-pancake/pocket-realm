package com.pocketrealm.supervisor

/**
 * Desktop twin of the Android clock object referenced by the shared
 * [DurableRuntimeSupervisor] constructor default. Same package and name so the
 * supervisor source in the Android tree compiles on the desktop JVM without
 * modification; the Android app keeps its own SystemClock-backed object in
 * android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeClock.kt.
 *
 * elapsedMs is process-start-relative monotonic time; the desktop has no deep
 * sleep, so System.nanoTime is a faithful stand-in for elapsedRealtime().
 */
object AndroidRuntimeClock : RuntimeClock {
    private const val NANOS_PER_MILLI = 1_000_000L

    private val bootNanos = System.nanoTime()

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun elapsedMs(): Long = (System.nanoTime() - bootNanos) / NANOS_PER_MILLI
}
