/*
 * native/wine-spike/src/tmp_shim.c
 *
 * An LD_PRELOAD shim (for the glibc namespace) that redirects /tmp access to a
 * writable app-private directory. Wine's wineserver hardcodes its server socket
 * dir as /tmp/.wine-<uid>, which is not writable on Android. This shim
 * intercepts mkdir/open/access for /tmp paths and substitutes POCKET_REALM_TMP.
 *
 * Loaded via LD_PRELOAD by the glibc loader (it runs in the glibc namespace,
 * NOT the Android/Bionic namespace). The POCKET_REALM_TMP env var is set by the
 * launcher to filesDir/runtime/tmp.
 *
 * This is the lightest-weight fix for the /tmp blocker — no proot needed.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

static const char *get_tmp_dir(void) {
    const char *dir = getenv("POCKET_REALM_TMP");
    return dir ? dir : "/data/data/com.pocketrealm/files/runtime/tmp";
}

/* Redirect a /tmp path to the app's writable temp dir. Returns a pointer to
 * either the original path (if not /tmp) or a static buffer with the redirect. */
static const char *redirect_path(const char *path) {
    if (!path) return path;
    if (path[0] == '/' && path[1] == 't' && path[2] == 'm' && path[3] == 'p' &&
        (path[4] == '/' || path[4] == '\0')) {
        static __thread char buf[4096];
        const char *tmp = get_tmp_dir();
        snprintf(buf, sizeof(buf), "%s%s", tmp, path + 4);
        return buf;
    }
    return path;
}

/* Intercept mkdir. */
int mkdir(const char *path, mode_t mode) {
    static int (*real_mkdir)(const char *, mode_t) = NULL;
    if (!real_mkdir) real_mkdir = dlsym(RTLD_NEXT, "mkdir");
    return real_mkdir(redirect_path(path), mode);
}

/* Intercept open/open64/openat. */
int open(const char *path, int flags, ...) {
    static int (*real_open)(const char *, int, ...) = NULL;
    if (!real_open) real_open = dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) {
        __builtin_va_list ap;
        __builtin_va_start(ap, flags);
        mode = __builtin_va_arg(ap, int);
        __builtin_va_end(ap);
    }
    return real_open(redirect_path(path), flags, mode);
}

int open64(const char *path, int flags, ...) {
    static int (*real_open64)(const char *, int, ...) = NULL;
    if (!real_open64) real_open64 = dlsym(RTLD_NEXT, "open64");
    mode_t mode = 0;
    if (flags & O_CREAT) {
        __builtin_va_list ap;
        __builtin_va_start(ap, flags);
        mode = __builtin_va_arg(ap, int);
        __builtin_va_end(ap);
    }
    return real_open64(redirect_path(path), flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    static int (*real_openat)(int, const char *, int, ...) = NULL;
    if (!real_openat) real_openat = dlsym(RTLD_NEXT, "openat");
    mode_t mode = 0;
    if (flags & O_CREAT) {
        __builtin_va_list ap;
        __builtin_va_start(ap, flags);
        mode = __builtin_va_arg(ap, int);
        __builtin_va_end(ap);
    }
    return real_openat(dirfd, redirect_path(path), flags, mode);
}

/* Intercept access. */
int access(const char *path, int mode) {
    static int (*real_access)(const char *, int) = NULL;
    if (!real_access) real_access = dlsym(RTLD_NEXT, "access");
    return real_access(redirect_path(path), mode);
}

/* Intercept stat/lstat. */
int stat(const char *path, struct stat *buf) {
    static int (*real_stat)(const char *, struct stat *) = NULL;
    if (!real_stat) real_stat = dlsym(RTLD_NEXT, "stat");
    return real_stat(redirect_path(path), buf);
}

int lstat(const char *path, struct stat *buf) {
    static int (*real_lstat)(const char *, struct stat *) = NULL;
    if (!real_lstat) real_lstat = dlsym(RTLD_NEXT, "lstat");
    return real_lstat(redirect_path(path), buf);
}

/* Intercept unlink/rmdir. */
int unlink(const char *path) {
    static int (*real_unlink)(const char *) = NULL;
    if (!real_unlink) real_unlink = dlsym(RTLD_NEXT, "unlink");
    return real_unlink(redirect_path(path), path);
}

int rmdir(const char *path) {
    static int (*real_rmdir)(const char *) = NULL;
    if (!real_rmdir) real_rmdir = dlsym(RTLD_NEXT, "rmdir");
    return real_rmdir(redirect_path(path));
}

/* Constructor: log that the shim is active. */
__attribute__((constructor))
static void tmp_shim_init(void) {
    fprintf(stderr, "tmp_shim: active, redirecting /tmp -> %s\n", get_tmp_dir());
}
