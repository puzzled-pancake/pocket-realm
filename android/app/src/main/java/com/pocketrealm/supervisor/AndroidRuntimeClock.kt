package com.pocketrealm.supervisor

import android.os.SystemClock

/**
 * Android [RuntimeClock]: wall clock plus the boot-relative monotonic clock
 * (elapsedRealtime keeps ticking through deep sleep, which nanoTime does not
 * reliably do on every Android kernel).
 *
 * Lives in its own file (not RuntimeContracts.kt) so the contract interfaces
 * stay pure JVM: the Windows desktop build compiles RuntimeContracts.kt from
 * this same tree and provides its own same-named clock object.
 */
object AndroidRuntimeClock : RuntimeClock {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
}
