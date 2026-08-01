package com.pocketrealm.pkg

/**
 * JNI shim for the G0 packaging-experiment native library (O05 / report §8.4).
 *
 * `libpocketpkgtest.so` (native/packaging) is a small JNI library driven from
 * the isolated `:pkg` child process. It is deliberately NOT the realm — it
 * exposes only what the PKG experiments need: a hello marker, a page-size
 * probe, a SONAME dlopen+dladdr probe of the real `libpocketrealm.so`, and a
 * deterministic abort() crash. The real realm facade stays loaded by SONAME.
 *
 * JNI glue is real: the native methods below are resolved by the JVM via
 * standard Java_-prefixed symbol names exported by `jni_shim.cpp` (no plain-C
 * symbol is invoked as a Kotlin `external`). If the library is absent, [load]
 * throws UnsatisfiedLinkError — no stub, matching RealmNative's no-fake-success
 * rule.
 *
 * Thread-affinity: callers run inside the `:pkg` process. The crash method
 * intentionally never returns.
 */
object PkgNative {

    /** Result of probing the real realm shared object by SONAME. */
    data class RealmSoInfo(
        @JvmField val loaded: Int,
        @JvmField val err: Int,
        @JvmField val path: String,
        @JvmField val soname: String,
        @JvmField val symbol: String,
        @JvmField val baseAddr: Long,
    ) {
        val isLoaded get() = loaded == 1
    }

    /** Load the native library. Call once in the `:pkg` process before use. */
    fun load() {
        System.loadLibrary("pocketpkgtest")
    }

    // ---- external methods (see jni_shim.cpp; Java_com_pocketrealm_pkg_PkgNative_*) ----

    /** A marker string the experiments assert on: "pocket-realm-pkg-ok". */
    external fun helloNative(): String

    /** Runtime page size via sysconf(_SC_PAGE_SIZE). */
    external fun probePageSizeNative(): Int

    /**
     * PKG-02/06: dlopen("libpocketrealm.so", RTLD_NOW) by SONAME and record the
     * dladdr-resolved path/base of a known realm symbol. Never assumes an
     * absolute nativeLibraryDir path (production variant may load from the APK).
     *
     * Returns a [RealmSoInfoParcelable] (a top-level Parcelable) because the JNI
     * shim constructs it by class name — a nested data class would need a `$`
     * in the JNI class name and is fragile.
     */
    external fun loadRealmSoBySonameNative(): RealmSoInfoParcelable

    /**
     * PKG-06: probe ONE native library by SONAME (RTLD_NOLOAD then RTLD_NOW) and
     * record the dl_iterate_phdr-resolved path/base. Used to prove every library
     * packaged in the APK loads under the production variant, not just the ones
     * another lib already pulled in transitively. [soname] e.g. "libc++_shared.so".
     */
    external fun probeSoBySonameNative(soname: String): RealmSoInfoParcelable

    /**
     * PKG-02 deterministic native fault. [kind]: 0=abort(), 1=NULL-deref,
     * 2=stack-guard. Never returns for kind 0 (SIGABRT).
     */
    external fun crashNative(kind: Int)

    const val CRASH_ABORT = 0
    const val CRASH_NULL_DEREF = 1
    const val CRASH_STACK_GUARD = 2
}
