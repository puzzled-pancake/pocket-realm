// realm_so.cpp — PKG-02/06: load the real realm shared object BY SONAME and
// record the APK-backed path actually resolved by the dynamic linker.
//
// Per the report and DECISIONS.md: under the production packaging variant
// (useLegacyPackaging=false) the .so may be loaded directly from the APK with
// no nativeLibraryDir filesystem path; we therefore resolve by SONAME and
// report whatever dladdr returns rather than assuming a path.
#include "pocket_pkg.h"

#include <dlfcn.h>
#include <string.h>

/* A known exported symbol from the realm C ABI (schemas/abi/pocket_realm.h). */
static const char* kRealmSymbol = "realm_err_str";
static const char* kRealmSoname = "libpocketrealm.so";

int32_t pkg_load_realm_so_by_soname(pkg_realm_so_info* info)
{
    if (!info) return 1;
    memset(info, 0, sizeof(*info));
    strncpy(info->soname, kRealmSoname, sizeof(info->soname) - 1);
    strncpy(info->symbol, kRealmSymbol, sizeof(info->symbol) - 1);

    void* handle = dlopen(kRealmSoname, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        info->loaded = 0;
        info->err = 1;
        return info->err;
    }
    void* sym = dlsym(handle, kRealmSymbol);
    if (!sym) {
        info->loaded = 0;
        info->err = 2;
        dlclose(handle);
        return info->err;
    }

    Dl_info di;
    memset(&di, 0, sizeof(di));
    if (dladdr(sym, &di) && di.dli_fname) {
        strncpy(info->path, di.dli_fname, sizeof(info->path) - 1);
        info->base_addr = (uint64_t)(uintptr_t)di.dli_fbase;
    }
    info->loaded = 1;
    info->err = 0;
    /* Intentionally keep the handle open: PKG-06 needs the realm .so to remain
     * resident for the 30-minute smoke. The :pkg child process exits when done,
     * which closes it. */
    return 0;
}
