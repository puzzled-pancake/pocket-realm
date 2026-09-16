package com.pocketrealm.desktop

import com.pocketrealm.server.RealmNative
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Co-load gate: the desktop architecture loads BOTH realm runtime
 * DLLs into the SAME JVM. On Android these live in separate processes; PE
 * semantics (no symbol interposition between DLLs) are the argument this
 * works — this test is the proof. Loading exercises every static
 * initializer in both images (OpenSSL providers, global singletons, the
 * playerbots registries) inside one process.
 *
 * Requires the native lane output: run tools/build_win_realm_runtime.py
 * first; the test SKIPS (does not fail) when the DLLs are not staged, so a
 * Kotlin-only checkout stays green (-PrequireNatives flips the skip into
 * a failure for the CI native lane).
 */
class NativeCoLoadTest {
    private val runtimeDir = File("..").resolve("native/.build-win-x86_64/pocket-runtime-build")

    private fun stagedDll(name: String): File {
        val dll = runtimeDir.resolve(name)
        requireNativeIfDemanded("native lane", dll)
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
        // proves the JNI binding resolves in both images in one process. The
        // status arrays carry the real contract: ABI version first, a
        // stopped-state component second — not just "did not throw".
        val realmStatus = RealmNative.statusNative()
        assertEquals("realm status ABI width", 5, realmStatus.size)
        assertEquals("realm ABI version", ServerRuntimeContract.ABI_VERSION, realmStatus[0])
        assertEquals("realm starts stopped", ServerRuntimeContract.STOPPED, realmStatus[1])

        val worldStatus = WorldNative.statusNative()
        assertEquals("world status ABI width", 8, worldStatus.size)
        assertEquals("world ABI version", ServerRuntimeContract.ABI_VERSION, worldStatus[0])
        assertEquals("world starts stopped", ServerRuntimeContract.STOPPED, worldStatus[1])
    }
}
