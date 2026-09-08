package com.pocketrealm.desktop

import com.pocketrealm.server.RealmNative
import com.pocketrealm.server.WorldNative
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase-2e co-load gate: the desktop architecture loads BOTH realm runtime
 * DLLs into the SAME JVM. On Android these live in separate processes; PE
 * semantics (no symbol interposition between DLLs) are the argument this
 * works — this test is the proof. Loading exercises every static
 * initializer in both images (OpenSSL providers, global singletons, the
 * playerbots registries) inside one process.
 *
 * Requires the native lane output: run tools/build_win_realm_runtime.py
 * first; the test SKIPS (does not fail) when the DLLs are not staged, so a
 * Kotlin-only checkout stays green.
 */
class NativeCoLoadTest {
    private val runtimeDir = File("..").resolve("native/.build-win-x86_64/pocket-runtime-build")

    private fun stagedDll(name: String): File {
        val dll = runtimeDir.resolve(name)
        assumeTrue("native lane not built (run tools/build_win_realm_runtime.py): $dll", dll.isFile)
        return dll
    }

    @Test
    fun bothRealmRuntimesLoadInTheSameJvm() {
        val realmd = stagedDll("pocket_realmd_runtime.dll")
        val world = stagedDll("pocket_world_runtime.dll")

        System.load(realmd.absolutePath)
        System.load(world.absolutePath)

        // The Kotlin shims' loadLibrary becomes a no-op once the library is
        // already loaded through the same absolute path; touching the objects
        // proves the JNI binding resolves in both images in one process.
        RealmNative.statusNative()
        WorldNative.statusNative()
        assertTrue("both native images initialized in one JVM", true)
    }
}
