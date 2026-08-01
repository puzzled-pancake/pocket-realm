/*
 * pocket_pkg.h — Pocket Realm G0 packaging-experiment C ABI (O05).
 *
 * This is the narrow versioned boundary exercised by the report's PKG-01/02/06
 * experiments (report §8.4). It is deliberately separate from the realm
 * lifecycle ABI (schemas/abi/pocket_realm.h): packaging experiments must not
 * grow into a second control channel. Two consumers:
 *
 *   libpocket_pkg_launcher.so  — a PIE executable renamed to a .so so AGP
 *       extracts it into nativeLibraryDir under the experiment variant
 *       (useLegacyPackaging=true). PKG-01 executes it there. It dlopens the
 *       real libpocketrealm.so by SONAME and reports the dladdr-resolved path.
 *
 *   libpocketpkgtest.so        — a JNI native library used by PKG-02/06 from
 *       the isolated :pkg child process. It dlopens libpocketrealm.so by
 *       SONAME, exposes a deterministic abort() crash, and probes page size.
 *
 * Invariants (native.md): opaque/explicit-ownership where stateful, error codes
 * not errno, no C++ exception crosses the boundary, bounded buffers.
 */
#ifndef POCKET_PKG_ABI_H
#define POCKET_PKG_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Bump on any ABI-breaking layout change. */
#define POCKET_PKG_ABI_VERSION 1

/* Result of probing the loaded realm shared object. Returned by value; the
 * caller owns path/soname storage (fixed-size, NUL-terminated). */
typedef struct {
    int32_t loaded;            /* 1 if dlopen succeeded, 0 otherwise */
    int32_t err;               /* 0 = ok; otherwise a platform errno-ish code */
    char    path[1024];        /* dladdr dli_fname of the realm symbol */
    char    soname[256];       /* the SONAME requested */
    char    symbol[128];       /* the dlsym symbol resolved for dladdr */
    uint64_t base_addr;        /* dladdr dli_fbase */
} pkg_realm_so_info;

/* Probed at runtime via sysconf(_SC_PAGE_SIZE). */
int32_t pkg_probe_page_size(void);

/* PKG-02: open the real realm shared object BY SONAME (RTLD_NOW), resolve a
 * known exported symbol (realm_err_str), and fill *info with the dladdr result.
 * Returns 0 on success, non-zero on dlopen/dlsym failure (info->err set). */
int32_t pkg_load_realm_so_by_soname(pkg_realm_so_info* info);

/* PKG-06: probe ONE native library by SONAME (RTLD_NOW|RTLD_NOLOAD|RTLD_LOCAL).
 * RTLD_NOLOAD means: if the linker already has it resident, return its handle
 * without side effects; if not, attempt a real dlopen (some libs, e.g.
 * libc++_shared.so, are already pulled in transitively). Fills *info with
 * soname + the dladdr-resolved path/base of ANY exported symbol discovered via
 * dlopen's handle (no prior knowledge of a specific symbol required). Returns
 * 0 on success (info->loaded=1), non-zero otherwise. Used by PKG-06 to prove
 * every library packaged in the APK loads under production packaging. */
int32_t pkg_probe_so_by_soname(const char* soname, pkg_realm_so_info* info);

/* PKG-02 deterministic crash. kind: 0=abort(), 1=NULL-deref, 2=stack-guard. */
void pkg_crash(int32_t kind);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* POCKET_PKG_ABI_H */
