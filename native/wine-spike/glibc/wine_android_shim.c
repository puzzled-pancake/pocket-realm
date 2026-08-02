/*
 * Narrow glibc-side Android compatibility shim for the O06 x86_64 spike.
 *
 * Wine's Linux build assumes an FHS installation and a writable /tmp.  The
 * APK deliberately keeps executable ELFs immutable in nativeLibraryDir while
 * PE/data files live in verified app-private caches.  This LD_PRELOAD library
 * rewrites only those known provider paths and implements access() through
 * faccessat(), whose syscall is allowed by Android's app seccomp policy.
 *
 * It is compiled against glibc (not the NDK/Bionic) and loaded only into the
 * Wine/glibc namespace.  All wrappers use raw allowed syscalls, avoiding
 * recursion through libc's pathname functions.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <sys/epoll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif

#define PATH_CAP 4096

static __thread char path_slots[2][PATH_CAP];
static __thread unsigned int next_slot;

static const char *rewrite_path(const char *path)
{
    if (!path || path[0] != '/') return path;

    const char *native_dir = getenv("POCKET_WINE_NATIVE_DIR");
    const char *pe64 = getenv("POCKET_WINE_PE64");
    const char *pe32 = getenv("POCKET_WINE_PE32");
    const char *data = getenv("POCKET_WINE_DATA");
    const char *tmp = getenv("POCKET_WINE_TMP");
    const char *replacement = NULL;
    const char *suffix = NULL;
    char prefix[PATH_CAP];

    /* Wine derives unixlib paths relative to the loaded ntdll.so. Android's
     * nativeLibraryDir is flat and AGP requires lib*.so names, so translate
     * native/x86_64-unix/foo.so to the immutable extracted
     * native/libwine_unix_foo.so artifact. */
    if (native_dir) {
        int n = snprintf(prefix, sizeof(prefix), "%s/x86_64-unix/", native_dir);
        if (n > 0 && (size_t)n < sizeof(prefix) && !strncmp(path, prefix, (size_t)n)) {
            char *out = path_slots[next_slot++ & 1u];
            if (snprintf(out, PATH_CAP, "%s/libwine_unix_%s", native_dir, path + n) >= PATH_CAP) {
                errno = ENAMETOOLONG;
                return path;
            }
            return out;
        }
    }

    if (native_dir && pe64) {
        int n = snprintf(prefix, sizeof(prefix), "%s/x86_64-windows", native_dir);
        if (n > 0 && (size_t)n < sizeof(prefix) && !strncmp(path, prefix, (size_t)n) &&
            (path[n] == '/' || path[n] == '\0')) {
            replacement = pe64;
            suffix = path + n;
        }
    }
    if (!replacement && native_dir && pe32) {
        int n = snprintf(prefix, sizeof(prefix), "%s/i386-windows", native_dir);
        if (n > 0 && (size_t)n < sizeof(prefix) && !strncmp(path, prefix, (size_t)n) &&
            (path[n] == '/' || path[n] == '\0')) {
            replacement = pe32;
            suffix = path + n;
        }
    }
    if (!replacement && native_dir && data) {
        int n = snprintf(prefix, sizeof(prefix), "%s/../../share/wine", native_dir);
        if (n > 0 && (size_t)n < sizeof(prefix) && !strncmp(path, prefix, (size_t)n) &&
            (path[n] == '/' || path[n] == '\0')) {
            replacement = data;
            suffix = path + n;
        }
    }
    /* Prebuilt providers retain their build-machine DATADIR (the pinned
     * Kron4ek binary uses /home/runner/.../share/wine). Relocate any absolute
     * Wine share directory by its unambiguous suffix instead of baking one
     * provider's build host into this replaceable runtime adapter. */
    if (!replacement && data) {
        const char *share = strstr(path, "/share/wine");
        if (share && (share[11] == '/' || share[11] == '\0')) {
            replacement = data;
            suffix = share + 11;
        }
    }
    /* Termux-built libX11/libxcb embeds its package prefix before the X11
     * socket directory. Match the protocol-specific suffix so both that path
     * and the conventional /tmp path resolve to the app-private X server. */
    if (!replacement && tmp) {
        const char *x11_socket = strstr(path, "/.X11-unix/");
        if (x11_socket) {
            replacement = tmp;
            suffix = x11_socket;
        }
    }
    if (!replacement && tmp && !strncmp(path, "/tmp", 4) &&
        (path[4] == '/' || path[4] == '\0')) {
        replacement = tmp;
        suffix = path + 4;
    }
    if (!replacement) return path;

    char *out = path_slots[next_slot++ & 1u];
    if (snprintf(out, PATH_CAP, "%s%s", replacement, suffix) >= PATH_CAP) {
        errno = ENAMETOOLONG;
        return path;
    }
    return out;
}

static int path_has_dir_prefix(const char *path, const char *dir)
{
    if (!path || !dir || !*dir) return 0;
    size_t len = strlen(dir);
    return !strncmp(path, dir, len) && (path[len] == '/' || path[len] == '\0');
}

static const char *android_data_suffix(const char *path)
{
    if (!path) return NULL;
    if (!strncmp(path, "/data/data/", 11)) return path + 11;
    if (!strncmp(path, "/data/user/0/", 13)) return path + 13;
    return NULL;
}

static int android_paths_equal(const char *left, const char *right)
{
    if (!left || !right) return 0;
    if (!strcmp(left, right)) return 1;
    const char *left_suffix = android_data_suffix(left);
    const char *right_suffix = android_data_suffix(right);
    return left_suffix && right_suffix && !strcmp(left_suffix, right_suffix);
}

static int android_path_has_dir_prefix(const char *path, const char *dir)
{
    if (path_has_dir_prefix(path, dir)) return 1;
    const char *path_suffix = android_data_suffix(path);
    const char *dir_suffix = android_data_suffix(dir);
    return path_suffix && dir_suffix && path_has_dir_prefix(path_suffix, dir_suffix);
}

static int is_verified_pe_path(const char *path)
{
    const char *target = getenv("POCKET_WINE_PE_TARGET");
    return android_path_has_dir_prefix(path, getenv("POCKET_WINE_PE64")) ||
           android_path_has_dir_prefix(path, getenv("POCKET_WINE_PE32")) ||
           android_path_has_dir_prefix(path, getenv("POCKET_WINE_PE_CACHE")) ||
           android_paths_equal(path, target);
}

static int fd_is_verified_pe(int fd)
{
    static volatile unsigned int trace_count;
    char proc_path[64];
    char target[PATH_CAP];
    int n = snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    if (n <= 0 || (size_t)n >= sizeof(proc_path)) return 0;
    ssize_t count = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, proc_path,
                                     target, sizeof(target) - 1);
    if (count <= 0 || (size_t)count >= sizeof(target)) return 0;
    target[count] = '\0';
    int verified = is_verified_pe_path(target);
    if (!verified && getenv("POCKET_WINE_SHIM_TRACE") &&
        __sync_fetch_and_add(&trace_count, 1) < 32) {
        char line[PATH_CAP + 64];
        int line_length = snprintf(line, sizeof(line),
                                   "POCKET_SHIM_PE_FD_UNVERIFIED fd=%d path=%s\n",
                                   fd, target);
        if (line_length > 0) {
            size_t output_length = (size_t)line_length < sizeof(line) ?
                (size_t)line_length : sizeof(line) - 1;
            (void)syscall(SYS_write, STDERR_FILENO, line, output_length);
        }
    }
    return verified;
}

static int open_runtime_path(const char *path, int flags, mode_t mode)
{
    const char *rewritten = rewrite_path(path);
    int rc = (int)syscall(SYS_openat, AT_FDCWD, rewritten, flags, mode);
    if (getenv("POCKET_WINE_SHIM_TRACE") && strstr(path, "kernel32")) {
        char line[PATH_CAP * 2 + 96];
        int count = snprintf(line, sizeof(line),
                             "POCKET_SHIM_KERNEL32_OPEN path=%s rewritten=%s rc=%d err=%d\n",
                             path, rewritten, rc, rc < 0 ? errno : 0);
        if (count > 0) (void)syscall(SYS_write, STDERR_FILENO, line,
                                     (size_t)count < sizeof(line) ? (size_t)count : sizeof(line) - 1);
    }
    return rc;
}

int access(const char *path, int mode)
{
    return (int)syscall(SYS_faccessat, AT_FDCWD, rewrite_path(path), mode, 0);
}

void *dlopen(const char *filename, int flags)
{
    static void *(*next_dlopen)(const char *, int);
    if (!next_dlopen) next_dlopen = dlsym(RTLD_NEXT, "dlopen");
    if (!next_dlopen) return NULL;
    return next_dlopen(rewrite_path(filename), flags);
}

int poll(struct pollfd *fds, nfds_t nfds, int timeout_ms)
{
    struct timespec timeout;
    struct timespec *timeout_ptr = NULL;
    if (timeout_ms >= 0) {
        timeout.tv_sec = timeout_ms / 1000;
        timeout.tv_nsec = (long)(timeout_ms % 1000) * 1000000L;
        timeout_ptr = &timeout;
    }
    /* Android's x86_64 app seccomp policy traps legacy poll(2), but permits
     * ppoll(2). A null signal mask makes this exactly poll's behavior. */
    return (int)syscall(SYS_ppoll, fds, nfds, timeout_ptr, NULL, 0);
}

int epoll_create(int size)
{
    if (size <= 0) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_epoll_create1, 0);
}

int epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout_ms)
{
    return (int)syscall(SYS_epoll_pwait, epfd, events, maxevents, timeout_ms, NULL, 0);
}

int dup2(int oldfd, int newfd)
{
    if (oldfd == newfd) {
        if (syscall(SYS_fcntl, oldfd, F_GETFD) < 0) return -1;
        return newfd;
    }
    return (int)syscall(SYS_dup3, oldfd, newfd, 0);
}

int execv(const char *path, char *const argv[])
{
    const char *native_dir = getenv("POCKET_WINE_NATIVE_DIR");
    if (native_dir && path_has_dir_prefix(path, native_dir)) {
        const char *base = strrchr(path, '/');
        base = base ? base + 1 : path;
        if (!strcmp(base, "wine-preloader")) {
            char preloader[PATH_CAP];
            char loader[PATH_CAP];
            if (snprintf(preloader, sizeof(preloader), "%s/libwine_loader_preloader.so",
                         native_dir) >= (int)sizeof(preloader) ||
                snprintf(loader, sizeof(loader), "%s/libwine_loader.so", native_dir) >=
                         (int)sizeof(loader)) {
                errno = ENAMETOOLONG;
                return -1;
            }

            const char *rtld = getenv("POCKET_GLIBC_LOADER");
            int rtld_fd = rtld ? (int)syscall(SYS_openat, AT_FDCWD, rtld,
                                              O_RDONLY | O_CLOEXEC, 0) : -1;
            if (rtld_fd < 0 || syscall(SYS_dup3, rtld_fd, 100, 0) < 0) {
                int saved = errno;
                if (rtld_fd >= 0) syscall(SYS_close, rtld_fd);
                errno = saved;
                return -1;
            }
            if (rtld_fd != 100) syscall(SYS_close, rtld_fd);

            /* loader_exec() reserves argv[0]/argv[1] for the preloader and
             * loader. Replace only those two paths; PE arguments, socket fd,
             * environment, and WINELOADERNOEXEC stay exactly as Wine built
             * them. The stack strings remain valid for the execve syscall. */
            char **mutable_argv = (char **)argv;
            mutable_argv[0] = preloader;
            mutable_argv[1] = loader;
            return (int)syscall(SYS_execve, preloader, mutable_argv, environ);
        }
    }
    return (int)syscall(SYS_execve, path, argv, environ);
}

int connect(int socket_fd, const struct sockaddr *address, socklen_t address_len)
{
    if (address && address->sa_family == AF_UNIX &&
        address_len > offsetof(struct sockaddr_un, sun_path)) {
        const struct sockaddr_un *unix_address =
            (const struct sockaddr_un *)address;
        const char *candidate = NULL;
        if (unix_address->sun_path[0] == '/') {
            candidate = unix_address->sun_path;
        } else if (unix_address->sun_path[0] == '\0' &&
                   address_len > offsetof(struct sockaddr_un, sun_path) + 1 &&
                   unix_address->sun_path[1] == '/') {
            /* XCB probes the Linux abstract X11 name first. Winlator exposes a
             * filesystem socket, so translate that exact path-shaped abstract
             * name through the same app-private /tmp contract. */
            candidate = unix_address->sun_path + 1;
        }
        if (candidate) {
            const char *rewritten = rewrite_path(candidate);
            if (rewritten != candidate) {
                struct sockaddr_un local;
                size_t path_len = strlen(rewritten);
                if (path_len >= sizeof(local.sun_path)) {
                    errno = ENAMETOOLONG;
                    return -1;
                }
                memset(&local, 0, sizeof(local));
                local.sun_family = AF_UNIX;
                memcpy(local.sun_path, rewritten, path_len + 1);
                socklen_t local_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                                                  path_len + 1);
                return (int)syscall(SYS_connect, socket_fd, &local, local_len);
            }
        }
    }
    return (int)syscall(SYS_connect, socket_fd, address, address_len);
}

static int mprotect_with_execmod_fallback(void *address, size_t length, int prot)
{
    int rc = (int)syscall(SYS_mprotect, address, length, prot);
    if (rc == 0 || errno != EACCES || !(prot & PROT_EXEC) || !length) return rc;

    /* SELinux denies execmod when Wine changes a private PE file mapping from
     * writable/relocatable to executable. Preserve the relocated bytes, replace
     * that private range with anonymous RW pages at the identical address, then
     * make those pages RX. Android permits the W->X transition for anonymous
     * private memory while continuing to reject executable writable files. */
    void *backup = (void *)syscall(SYS_mmap, NULL, length,
                                   PROT_READ | PROT_WRITE,
                                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (backup == MAP_FAILED) return -1;
    memcpy(backup, address, length);
    void *replacement = (void *)syscall(SYS_mmap, address, length,
                                        PROT_READ | PROT_WRITE,
                                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                                        -1, 0);
    if (replacement == MAP_FAILED) {
        int saved = errno;
        syscall(SYS_munmap, backup, length);
        errno = saved;
        return -1;
    }
    memcpy(replacement, backup, length);
    syscall(SYS_munmap, backup, length);
    return (int)syscall(SYS_mprotect, replacement, length, prot);
}

static void trace_vm_failure(const char *operation, uintptr_t address,
                             size_t length, long rc, int saved_errno)
{
    if (!getenv("POCKET_WINE_SHIM_TRACE")) return;
    char line[192];
    int count = snprintf(line, sizeof(line),
                         "POCKET_SHIM_VM_FAIL op=%s addr=%lx len=%zx rc=%ld err=%d\n",
                         operation, (unsigned long)address, length, rc, saved_errno);
    if (count > 0) {
        size_t output_length = (size_t)count < sizeof(line) ?
            (size_t)count : sizeof(line) - 1;
        (void)syscall(SYS_write, STDERR_FILENO, line, output_length);
    }
}

int mprotect(void *address, size_t length, int prot)
{
    if (!length) return mprotect_with_execmod_fallback(address, length, prot);
    long queried_page_size = sysconf(_SC_PAGESIZE);
    size_t page_size = queried_page_size > 0 ? (size_t)queried_page_size : 4096u;
    uintptr_t begin = (uintptr_t)address;
    uintptr_t end;
    if (__builtin_add_overflow(begin, length, &end)) {
        errno = EINVAL;
        return -1;
    }
    if (!(begin % page_size) && !(length % page_size))
        return mprotect_with_execmod_fallback(address, length, prot);

    /* The source-matched ntdll tracks the effective protection of every 4 KB
     * Windows page and later applies their union at host-page granularity.
     * Its file-copy fallback still submits the 4 KB subrange while making a
     * section writable, so normalize only that syscall. Keeping a second,
     * monotonic protection table here corrupts remapped/reused Wine views. */
    uintptr_t aligned_begin = begin - begin % page_size;
    uintptr_t aligned_end = (end + page_size - 1) / page_size * page_size;
    return mprotect_with_execmod_fallback((void *)aligned_begin,
                                          aligned_end - aligned_begin, prot);
}

int munmap(void *address, size_t length)
{
    int rc = (int)syscall(SYS_munmap, address, length);
    int saved = errno;
    if (rc != 0) trace_vm_failure("munmap", (uintptr_t)address, length, rc, saved);
    errno = saved;
    return rc;
}

int madvise(void *address, size_t length, int advice)
{
    int rc = (int)syscall(SYS_madvise, address, length, advice);
    int saved = errno;
    if (rc != 0) trace_vm_failure("madvise", (uintptr_t)address, length, rc, saved);
    errno = saved;
    return rc;
}

void *mremap(void *old_address, size_t old_size, size_t new_size, int flags, ...)
{
    void *new_address = NULL;
    if (flags & MREMAP_FIXED) {
        va_list ap;
        va_start(ap, flags);
        new_address = va_arg(ap, void *);
        va_end(ap);
    }
    void *result = (void *)syscall(SYS_mremap, old_address, old_size, new_size,
                                   flags, new_address);
    int saved = errno;
    if (result == MAP_FAILED)
        trace_vm_failure("mremap", (uintptr_t)old_address, old_size, -1, saved);
    errno = saved;
    return result;
}

static void *mmap_runtime(void *address, size_t length, int prot, int flags,
                          int fd, off_t offset)
{
    int verified = fd >= 0 && fd_is_verified_pe(fd);
    if (!getenv("POCKET_WINE_PE_ANON_EXEC") || fd < 0 ||
        !(prot & PROT_EXEC) || !verified) {
        void *result = (void *)syscall(SYS_mmap, address, length, prot, flags, fd, offset);
        int saved = errno;
        if (result == MAP_FAILED)
            trace_vm_failure("mmap", (uintptr_t)address, length, -1, saved);
        errno = saved;
        return result;
    }

    /* app_data_file and appdomain_tmpfs are both non-executable under the
     * Android app SELinux domain.  The cache was hash-verified immediately
     * before launch, so materialize the requested private PE image range into
     * anonymous memory, then apply Wine's requested protection.  Preserve the
     * caller's fixed-address semantics; PE relocation still occurs in Wine. */
    int anon_flags = MAP_PRIVATE | MAP_ANONYMOUS;
    if (flags & MAP_FIXED) anon_flags |= MAP_FIXED;
#ifdef MAP_FIXED_NOREPLACE
    if (flags & MAP_FIXED_NOREPLACE) anon_flags |= MAP_FIXED_NOREPLACE;
#endif
    void *mapped = (void *)syscall(SYS_mmap, address, length,
                                   PROT_READ | PROT_WRITE,
                                   anon_flags, -1, 0);
    if (mapped == MAP_FAILED) return MAP_FAILED;

    size_t copied = 0;
    while (copied < length) {
        ssize_t count = (ssize_t)syscall(SYS_pread64, fd,
                                         (char *)mapped + copied,
                                         length - copied,
                                         offset + (off_t)copied);
        if (count == 0) break; /* anonymous tail is already zero-filled */
        if (count < 0) {
            int saved = errno;
            syscall(SYS_munmap, mapped, length);
            errno = saved;
            return MAP_FAILED;
        }
        copied += (size_t)count;
    }
    if (syscall(SYS_mprotect, mapped, length, prot) != 0) {
        int saved = errno;
        syscall(SYS_munmap, mapped, length);
        errno = saved;
        return MAP_FAILED;
    }
    return mapped;
}

void *mmap(void *address, size_t length, int prot, int flags, int fd, off_t offset)
{
    return mmap_runtime(address, length, prot, flags, fd, offset);
}

void *mmap64(void *address, size_t length, int prot, int flags, int fd, off64_t offset)
{
    return mmap_runtime(address, length, prot, flags, fd, (off_t)offset);
}

int open(const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return open_runtime_path(path, flags, mode);
}

int open64(const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return open_runtime_path(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    const char *rewritten = rewrite_path(path);
    int rc = (int)syscall(SYS_openat, dirfd, rewritten, flags, mode);
    if (getenv("POCKET_WINE_SHIM_TRACE") && strstr(path, "kernel32")) {
        char line[PATH_CAP * 2 + 96];
        int count = snprintf(line, sizeof(line),
                             "POCKET_SHIM_KERNEL32_OPENAT dirfd=%d path=%s rewritten=%s rc=%d err=%d\n",
                             dirfd, path, rewritten, rc, rc < 0 ? errno : 0);
        if (count > 0) (void)syscall(SYS_write, STDERR_FILENO, line,
                                     (size_t)count < sizeof(line) ? (size_t)count : sizeof(line) - 1);
    }
    return rc;
}

int openat64(int dirfd, const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    const char *rewritten = rewrite_path(path);
    return (int)syscall(SYS_openat, dirfd, rewritten, flags, mode);
}

int stat(const char *path, struct stat *buf)
{
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, 0);
}

int stat64(const char *path, struct stat64 *buf)
{
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, 0);
}

int lstat(const char *path, struct stat *buf)
{
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, AT_SYMLINK_NOFOLLOW);
}

int lstat64(const char *path, struct stat64 *buf)
{
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, AT_SYMLINK_NOFOLLOW);
}

/* Wine 11.14's prebuilt ELF imports glibc's legacy, versioned entry points
 * directly (__xstat/__lxstat/__fxstat at GLIBC_2.2.5). Exporting the exact
 * symbol names is necessary: wrapping only stat/lstat does not interpose those
 * relocations, and would leave hardcoded /tmp paths outside the app sandbox. */
int __xstat(int version, const char *path, struct stat *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, 0);
}

int __xstat64(int version, const char *path, struct stat64 *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, 0);
}

int __lxstat(int version, const char *path, struct stat *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, AT_SYMLINK_NOFOLLOW);
}

int __lxstat64(int version, const char *path, struct stat64 *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, rewrite_path(path), buf, AT_SYMLINK_NOFOLLOW);
}

int __fxstat(int version, int fd, struct stat *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_fstat, fd, buf);
}

int __fxstat64(int version, int fd, struct stat64 *buf)
{
    if ((unsigned int)version > 1u) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_fstat, fd, buf);
}

int chdir(const char *path)
{
    return (int)syscall(SYS_chdir, rewrite_path(path));
}

int mkdir(const char *path, mode_t mode)
{
    return (int)syscall(SYS_mkdirat, AT_FDCWD, rewrite_path(path), mode);
}

int chmod(const char *path, mode_t mode)
{
    return (int)syscall(SYS_fchmodat, AT_FDCWD, rewrite_path(path), mode, 0);
}

int unlink(const char *path)
{
    return (int)syscall(SYS_unlinkat, AT_FDCWD, rewrite_path(path), 0);
}

int rmdir(const char *path)
{
    return (int)syscall(SYS_unlinkat, AT_FDCWD, rewrite_path(path), AT_REMOVEDIR);
}

ssize_t readlink(const char *path, char *buf, size_t size)
{
    return (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, rewrite_path(path), buf, size);
}

int symlink(const char *target, const char *linkpath)
{
    return (int)syscall(SYS_symlinkat, target, AT_FDCWD, rewrite_path(linkpath));
}

int link(const char *oldpath, const char *newpath)
{
    const char *old_rewritten = rewrite_path(oldpath);
    char old_copy[PATH_CAP];
    snprintf(old_copy, sizeof(old_copy), "%s", old_rewritten);
    return (int)syscall(SYS_linkat, AT_FDCWD, old_copy, AT_FDCWD,
                        rewrite_path(newpath), 0);
}

int rename(const char *oldpath, const char *newpath)
{
    const char *old_rewritten = rewrite_path(oldpath);
    char old_copy[PATH_CAP];
    snprintf(old_copy, sizeof(old_copy), "%s", old_rewritten);
    return (int)syscall(SYS_renameat, AT_FDCWD, old_copy, AT_FDCWD,
                        rewrite_path(newpath));
}
