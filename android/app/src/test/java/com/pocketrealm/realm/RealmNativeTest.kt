package com.pocketrealm.realm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM test for the RealmNative JNI shim (O04).
 *
 * The native libpocketrealm.so is NOT present in the host JVM (it's an Android
 * .so, cross-compiled for arm64-v8a/x86_64). So [RealmNative.load] must throw
 * UnsatisfiedLinkError here. This test asserts that the shim reports the
 * missing native layer honestly rather than stubbing or faking success — the
 * same "no fake success" contract the rest of the project holds.
 *
 * The real C-ABI round trip is exercised by the native `pocket_lifecycle_test`
 * binary on the emulator (tools/run_realm_test.py), which proves create/start/
 * health/save/stop/destroy twice in one process. This JVM test only covers the
 * Kotlin boundary's graceful-degradation behavior.
 */
class RealmNativeTest {

    @Test
    fun load_reports_missing_native_loudly() {
        // The host JVM has no libpocketrealm.so. We must NOT catch-and-stub:
        // a missing native layer is a real condition, surfaced as
        // UnsatisfiedLinkError. If this ever silently succeeds without the .so,
        // that would mean someone added a fake stub — a regression.
        var threw = false
        try {
            RealmNative.load()
        } catch (e: UnsatisfiedLinkError) {
            threw = true
        }
        assertTrue(
            "RealmNative.load() must throw UnsatisfiedLinkError when the .so is " +
                "absent (no fake stub); got threw=$threw",
            threw,
        )
    }

    @Test
    fun constants_match_c_abi() {
        // Pin the Kotlin constants to the C ABI values so a drift is caught at
        // host-test time (schemas/abi/pocket_realm.h is the source of truth).
        assertEquals(RealmNative.State.CREATED, 0)
        assertEquals(RealmNative.State.FAILED, 6)
        assertEquals(RealmNative.Err.OK, 0)
        assertEquals(RealmNative.Err.BLOCKED_ON_CLIENT_DATA, 5)
        assertEquals(RealmNative.Cond.COUNT, 6)
        assertEquals(RealmNative.CondState.BLOCKED_ON_CLIENT_DATA, 3)
        assertEquals(RealmNative.ABI_VERSION, 1)
    }
}
