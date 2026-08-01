// realm_so.cpp — PKG-02/06: load the real realm shared object BY SONAME and
// record the APK-backed path actually resolved by the dynamic linker; plus the
// PKG-06 per-lib probe used to prove every APK-packaged .so loads.
//
// Per the report and DECISIONS.md: under the production packaging variant
// (useLegacyPackaging=false) the .so may be loaded directly from the APK with
// no nativeLibraryDir filesystem path; we therefore resolve by SONAME and
// report whatever dladdr returns rather than assuming a path.
#include "pocket_pkg.h"

#include <dlfcn.h>
#include <link.h>
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

/* dl_iterate_phdr callback context for pkg_probe_so_by_soname: find the loaded
 * object whose name ends with the requested soname and capture its base. */
struct probe_ctx {
    const char* soname;       /* needle (e.g. "libc++_shared.so") */
    void*       found_base;   /* out: dlpi_addr of the matching object */
    char        found_path[1024];
    int         found;
};

static int probe_cb(struct dl_phdr_info* info, size_t /*sz*/, void* data)
{
    auto* ctx = static_cast<probe_ctx*>(data);
    if (!info || !info->dlpi_name) return 0;
    /* Match on the basename of the loaded object's name. The APK-backed path is
     * like .../base.apk!/lib/x86_64/libc++_shared.so; the extracted path is
     * .../lib/x86_64/libc++_shared.so. Both end with the soname. */
    const char* slash = strrchr(info->dlpi_name, '/');
    const char* base = slash ? slash + 1 : info->dlpi_name;
    if (strcmp(base, ctx->soname) == 0) {
        ctx->found_base = (void*)(uintptr_t)info->dlpi_addr;
        strncpy(ctx->found_path, info->dlpi_name, sizeof(ctx->found_path) - 1);
        ctx->found = 1;
        return 1; /* stop iterating */
    }
    return 0;
}

int32_t pkg_probe_so_by_soname(const char* soname, pkg_realm_so_info* info)
{
    if (!info || !soname) return 1;
    memset(info, 0, sizeof(*info));
    strncpy(info->soname, soname, sizeof(info->soname) - 1);

    /* First see if it is already loaded (RTLD_NOLOAD): no side effects, and
     * catches transitive deps like libc++_shared.so that the realm facade
     * already pulled in. */
    void* handle = dlopen(soname, RTLD_NOLOAD | RTLD_LOCAL);
    if (!handle) {
        /* Not currently resident; do a real load so PKG-06 proves every lib can
         * load, not just the ones another lib already pulled in. */
        handle = dlopen(soname, RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            info->loaded = 0;
            info->err = 1;
            return info->err;
        }
    }

    /* Resolve the loaded object's base + path via dl_iterate_phdr (works even
     * when we don't know an exported symbol to dladdr). */
    probe_ctx ctx{soname, nullptr, {}, 0};
    dl_iterate_phdr(probe_cb, &ctx);
    if (ctx.found) {
        strncpy(info->path, ctx.found_path, sizeof(info->path) - 1);
        info->base_addr = (uint64_t)(uintptr_t)ctx.found_base;
        strncpy(info->symbol, "(dl_iterate_phdr)", sizeof(info->symbol) - 1);
    }
    info->loaded = 1;
    info->err = 0;
    /* Leave resident for the PKG-06 smoke; the :pkg child exits at the end. */
    return 0;
}
