/*
 * launcher.c — packaging-experiment native launcher.
 *
 * This is a real PIE executable, output as libpocket_pkg_launcher.so so AGP
 * ships it under lib/<abi>/. Under the experiment variant
 * (useLegacyPackaging=true) it is extracted into nativeLibraryDir with the
 * executable bit; PKG-01 runs it there via its absolute path.
 *
 * It is NOT a shared library — it has a main(). stdout/stderr/exit-status are
 * captured by the host driver. Behavior:
 *   - prints its own resolved exe path (/proc/self/exe) and the page size
 *   - dlopen("libpocketrealm.so", RTLD_NOW) BY SONAME
 *   - prints the dladdr-resolved path/base of a known realm symbol
 *   - exits 0 on success, non-zero with a structured stderr line on failure
 */
#include "pocket_pkg.h"

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static const char* kRealmSoname = "libpocketrealm.so";
static const char* kRealmSymbol = "realm_err_str";

static void fail(const char* why, const char* detail)
{
    fprintf(stderr, "PKG_LAUNCHER_ERROR\t%s\t%s\n",
            why ? why : "", detail ? detail : "");
    fflush(stderr);
    _exit(3);
}

int main(void)
{
    char exe[1024] = {0};
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n < 0) n = 0;
    exe[n] = '\0';

    long ps = sysconf(_SC_PAGE_SIZE);

    /* Structured, machine-parseable stdout (host driver captures it). */
    printf("PKG_LAUNCHER_HELLO\n");
    printf("self_exe_path\t%s\n", exe);
    printf("page_size\t%ld\n", ps);
    fflush(stdout);

    void* handle = dlopen(kRealmSoname, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        fail("dlopen_failed", dlerror());
    }
    void* sym = dlsym(handle, kRealmSymbol);
    const char* dl_err = dlerror();
    if (!sym) {
        fail("dlsym_failed", dl_err ? dl_err : "symbol null");
    }
    Dl_info di;
    memset(&di, 0, sizeof(di));
    const char* fname = "";
    unsigned long base = 0;
    if (dladdr(sym, &di) && di.dli_fname) {
        fname = di.dli_fname;
        base = (unsigned long)(uintptr_t)di.dli_fbase;
    }
    printf("realm_soname\t%s\n", kRealmSoname);
    printf("realm_symbol\t%s\n", kRealmSymbol);
    printf("realm_path\t%s\n", fname);
    printf("realm_base\t0x%lx\n", base);
    printf("PKG_LAUNCHER_OK\n");
    fflush(stdout);

    /* Leave the handle open until exit; the process is about to terminate. */
    return 0;
}
