/*
 * native/wine-spike/src/proot_run.c
 *
 * Synchronous proot run with logical argv[0] preservation, recursive descendant
 * enumeration + /proc/<pid>/maps snapshotting, and recursive tree kill.
 *
 * This is the corrected S-1/S-2 launcher. The earlier launch_wine_via_proot()
 * (in proot_launcher.c) returned a bare PID and lost the logical Wine command
 * name: bin/wineboot resolved to libwine_preloader.so, so argv[0] was the
 * preloader and Wine could not dispatch wineboot vs wine. The run path here:
 *
 *   - keeps the immutable real APK ELF path (libwine_preloader.so in
 *     nativeLibraryDir) as the program the loader runs, but inserts the glibc
 *     loader's --argv0=<logical> so Wine's argv[0] is "wineboot"/"wine"/...
 *   - runs proot to completion (or timeout), capturing stdout + stderr via pipes
 *   - snapshots every descendant PID/PPID/cmdline/comm + /proc/<pid>/maps proof
 *     while the tree is alive (full S-1 acceptance: wine + wineserver + every
 *     native child must map the APK-managed loader)
 *   - on timeout, recursively SIGTERM/SIGKILL + reap the entire tree (proot +
 *     wine + wineserver), not just the top proot PID
 *
 * proot argv form (loader-as-guest-command with --argv0):
 *
 *   proot -v 5 -b <tmp_dir>:/tmp -r / --link2symlink \
 *     <apk-loader> --argv0 <logical> --library-path <tree-libs> <wine_real> [args...]
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <errno.h>
#include <ctype.h>
#include <poll.h>
#include <signal.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <elf.h>
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

static int read_file(const char *path, char *buf, size_t cap);

static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

/* Capture the exact x86_64 syscall at a SIGSYS delivery stop. This is kept in
 * the direct launcher (behind POCKET_TRACE_SIGSYS) because a signal-only wait
 * status merely says that seccomp fired; it does not identify what Wine still
 * needs adapted for Android. */
static long long direct_trace_syscall_nr(pid_t pid, unsigned long long *out_rip,
                                         unsigned long long *out_rsp) {
#if defined(__x86_64__) && defined(NT_PRSTATUS)
    struct {
        unsigned long long r15, r14, r13, r12, rbp, rbx, r11, r10, r9, r8;
        unsigned long long rax, rcx, rdx, rsi, rdi, orig_rax, rip, cs;
        unsigned long long eflags, rsp, ss, fs_base, gs_base, ds, es, fs, gs;
    } regs;
    memset(&regs, 0, sizeof(regs));
    struct iovec iov = {&regs, sizeof(regs)};
    if (ptrace(PTRACE_GETREGSET, pid, (void *)(intptr_t)NT_PRSTATUS, &iov) == 0) {
        if (out_rip) *out_rip = regs.rip;
        if (out_rsp) *out_rsp = regs.rsp;
        return (long long)regs.orig_rax;
    }
#else
    (void)pid; (void)out_rip; (void)out_rsp;
#endif
    return -1;
}

static void direct_log_stack_candidates(pid_t pid, unsigned long long rsp) {
    struct executable_map {
        unsigned long long start, end, file_offset;
        char path[256];
    } executable[128];
    int executable_count = 0;
    char maps_path[64], maps[65536];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    if (read_file(maps_path, maps, sizeof(maps)) < 0) return;
    char *save = NULL;
    for (char *line = strtok_r(maps, "\n", &save); line && executable_count < 128;
         line = strtok_r(NULL, "\n", &save)) {
        unsigned long long start = 0, end = 0, file_offset = 0;
        char perms[8] = {0}, path[256] = {0};
        int fields = sscanf(line, "%llx-%llx %7s %llx %*s %*s %255[^\n]",
                            &start, &end, perms, &file_offset, path);
        if (fields >= 4 && strchr(perms, 'x')) {
            executable[executable_count].start = start;
            executable[executable_count].end = end;
            executable[executable_count].file_offset = file_offset;
            snprintf(executable[executable_count].path,
                     sizeof(executable[executable_count].path), "%s",
                     fields >= 5 ? path : "[anonymous]");
            executable_count++;
        }
    }
    int logged = 0;
    for (int i = 0; i < 128 && logged < 24; i++) {
        errno = 0;
        unsigned long long value = (unsigned long long)(unsigned long)
            ptrace(PTRACE_PEEKDATA, pid, (void *)(uintptr_t)(rsp + (unsigned long long)i * 8), 0);
        if (errno) continue;
        for (int m = 0; m < executable_count; m++) {
            if (value < executable[m].start || value >= executable[m].end) continue;
            unsigned long long object_offset = executable[m].file_offset +
                                               value - executable[m].start;
            LOGE("direct_run: abort stack+0x%x return=0x%llx object+0x%llx %s",
                 i * 8, value, object_offset, executable[m].path);
            logged++;
            break;
        }
    }
}

static void direct_log_mapping_for_address(pid_t pid, unsigned long long address) {
    char path[64], maps[65536];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    if (read_file(path, maps, sizeof(maps)) < 0) return;
    char previous[512] = {0};
    char *save = NULL;
    for (char *line = strtok_r(maps, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        unsigned long long start = 0, end = 0;
        if (sscanf(line, "%llx-%llx", &start, &end) == 2 && address >= start && address < end) {
            LOGE("direct_run: fault mapping=%s offset=0x%llx", line, address - start);
            return;
        }
        if (start > address) {
            LOGE("direct_run: fault unmapped; below=%s", previous[0] ? previous : "<none>");
            LOGE("direct_run: fault unmapped; above=%s", line);
            return;
        }
        snprintf(previous, sizeof(previous), "%s", line);
    }
    LOGE("direct_run: fault unmapped; below=%s above=<none>",
         previous[0] ? previous : "<none>");
}

/* ---- small helpers shared with the older launcher ------------------------- */

static int read_file(const char *path, char *buf, size_t cap) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = 0;
    size_t total = 0;
    while (total < cap - 1 && (n = read(fd, buf + total, cap - 1 - total)) > 0) {
        total += (size_t)n;
    }
    close(fd);
    buf[total] = '\0';
    return (int)total;
}

static void append_capture_tail(char *dst, size_t *len, size_t cap,
                                const char *src, size_t count) {
    if (!dst || !len || cap <= 1 || !src || !count) return;
    size_t usable = cap - 1;
    if (count >= usable) {
        memcpy(dst, src + count - usable, usable);
        *len = usable;
    } else {
        if (*len + count > usable) {
            size_t drop = *len + count - usable;
            memmove(dst, dst + drop, *len - drop);
            *len -= drop;
        }
        memcpy(dst + *len, src, count);
        *len += count;
    }
    dst[*len] = '\0';
}

static int mkdir_p_local(const char *path) {
    char tmp[WINE_SPIKE_PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
        *p = '/';
    }
    return (mkdir(tmp, 0755) == 0 || errno == EEXIST) ? 0 : -1;
}

/* Resolve wine_target (possibly a symlink) to its real APK-managed path. */
static void resolve_wine_real(const char *wine_target, char *out, size_t cap) {
    char linkbuf[WINE_SPIKE_PATH_MAX];
    ssize_t rl = readlink(wine_target, linkbuf, sizeof(linkbuf) - 1);
    if (rl > 0) {
        linkbuf[rl] = '\0';
        if (linkbuf[0] != '/') {
            /* Relative — resolve against the symlink's directory. */
            char dir[WINE_SPIKE_PATH_MAX];
            snprintf(dir, sizeof(dir), "%s", wine_target);
            char *slash = strrchr(dir, '/');
            if (slash) {
                slash[1] = '\0';
                snprintf(out, cap, "%s%s", dir, linkbuf);
            } else {
                snprintf(out, cap, "%s", linkbuf);
            }
        } else {
            snprintf(out, cap, "%s", linkbuf);
        }
    } else {
        snprintf(out, cap, "%s", wine_target);
    }
}

/* Derive the logical argv[0] from a wine_target path basename, e.g.
 *   ".../wine-tree/bin/wineboot" -> "wineboot"
 * Caller may pass an explicit override which takes precedence. */
static void derive_argv0(const char *wine_target, const char *override,
                         char *out, size_t cap) {
    if (override && *override) { snprintf(out, cap, "%s", override); return; }
    const char *base = strrchr(wine_target, '/');
    base = base ? base + 1 : wine_target;
    snprintf(out, cap, "%s", base);
}

/* Derive tree_dir from wine_target by stripping the trailing "/bin/..." part. */
static void derive_tree_dir(const char *wine_target, char *out, size_t cap) {
    snprintf(out, cap, "%s", wine_target);
    char *bin_pos = strstr(out, "/bin/");
    if (bin_pos) *bin_pos = '\0';
    else snprintf(out, cap, ".");
}

/* Canonicalize the app's writable tmp dir. prefix_dir is filesDir/runtime/
 * wine-prefix; tmp is filesDir/runtime/tmp. We strip the trailing
 * "/wine-prefix" and append "/tmp" (proot does NOT resolve '..' in
 * PROOT_TMP_DIR before its writability check). */
static void derive_tmp_dir(const char *prefix_dir, char *out, size_t cap) {
    snprintf(out, cap, "%s", prefix_dir);
    char *wp = strstr(out, "/wine-prefix");
    if (wp) *wp = '\0';
    size_t len = strlen(out);
    snprintf(out + len, cap - len, "/tmp");
}

/* ---- process-tree enumeration --------------------------------------------- */

/* Read /proc/<pid>/cmdline (NUL-separated) and render NULs as spaces. */
static void read_cmdline(int64_t pid, char *out, size_t cap) {
    out[0] = '\0';
    char path[128];
    snprintf(path, sizeof(path), "/proc/%lld/cmdline", (long long)pid);
    char raw[WINE_SPIKE_LINE_MAX];
    int n = read_file(path, raw, sizeof(raw));
    if (n <= 0) { snprintf(out, cap, "(unreadable)"); return; }
    /* Collapse NULs to spaces; trim trailing. */
    size_t out_i = 0;
    for (int i = 0; i < n && out_i < cap - 2; i++) {
        char c = raw[i];
        if (c == '\0') {
            /* Separator between argv entries. Use a space unless the previous
             * char is already a space or the start. */
            if (out_i > 0 && out[out_i - 1] != ' ') out[out_i++] = ' ';
        } else {
            out[out_i++] = c;
        }
    }
    /* Trim trailing space. */
    while (out_i > 0 && out[out_i - 1] == ' ') out_i--;
    out[out_i] = '\0';
}

static void read_comm(int64_t pid, char *out, size_t cap) {
    out[0] = '\0';
    char path[128];
    snprintf(path, sizeof(path), "/proc/%lld/comm", (long long)pid);
    int n = read_file(path, out, cap);
    if (n <= 0) { snprintf(out, cap, "?"); return; }
    /* Strip trailing newline. */
    size_t len = strlen(out);
    while (len > 0 && (out[len-1] == '\n' || out[len-1] == '\r')) out[--len] = '\0';
}

/* Read ppid for a pid from /proc/<pid>/stat. Returns 0 on failure. */
static int64_t read_ppid(int64_t pid) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/%lld/stat", (long long)pid);
    char buf[1024];
    int n = read_file(path, buf, sizeof(buf));
    if (n <= 0) return 0;
    /* stat: pid (comm) state ppid ... — comm may contain parens/spaces, so
     * parse from the LAST ')'. */
    char *lp = strrchr(buf, ')');
    if (!lp) return 0;
    char state;
    long ppid = 0;
    if (sscanf(lp + 2, "%c %ld", &state, &ppid) >= 2) return (int64_t)ppid;
    return 0;
}

int wine_spike_enum_descendants_recursive(int64_t root_pid,
                                          int64_t *out_pids, int cap) {
    if (!out_pids || cap <= 0) return -1;
    /* Walk /proc, build a child map by reading each process's ppid. Then BFS
     * from root_pid collecting descendants. */
    DIR *proc = opendir("/proc");
    if (!proc) return -1;

    /* Collect (pid, ppid) pairs. */
    struct pair { int64_t pid, ppid; };
    static struct pair pairs[512];
    int npairs = 0;
    struct dirent *de;
    while ((de = readdir(proc)) != NULL && npairs < 512) {
        if (!isdigit((unsigned char)de->d_name[0])) continue;
        int64_t pid = (int64_t)atoll(de->d_name);
        int64_t ppid = read_ppid(pid);
        pairs[npairs].pid = pid;
        pairs[npairs].ppid = ppid;
        npairs++;
    }
    closedir(proc);

    /* BFS from root_pid. */
    int count = 0;
    int64_t frontier[512];
    int fhead = 0, ftail = 0;
    frontier[ftail++] = root_pid;
    /* Track visited to defend against any cycle (PPID loops shouldn't happen
     * but be defensive). */
    static int64_t visited[512];
    int nvisited = 0;
    visited[nvisited++] = root_pid;

    while (fhead < ftail && count < cap) {
        int64_t cur = frontier[fhead++];
        for (int i = 0; i < npairs; i++) {
            if (pairs[i].ppid == cur) {
                int64_t child = pairs[i].pid;
                /* Check visited. */
                int seen = 0;
                for (int v = 0; v < nvisited; v++) {
                    if (visited[v] == child) { seen = 1; break; }
                }
                if (seen) continue;
                if (ftail < 512) frontier[ftail++] = child;
                if (nvisited < 512) visited[nvisited++] = child;
                if (count < cap) out_pids[count++] = child;
            }
        }
    }
    return count;
}

int wine_spike_kill_tree_recursive(int64_t root_pid) {
    if (root_pid <= 0) return WINE_SPIKE_ERR_ARGS;
    int64_t pids[WINE_SPIKE_DESCENDANTS_MAX];
    int n = wine_spike_enum_descendants_recursive(root_pid, pids,
                                                  WINE_SPIKE_DESCENDANTS_MAX);
    /* SIGTERM leaves first (depth-first feel), then SIGKILL after a beat. */
    for (int i = 0; i < n; i++) {
        kill((pid_t)pids[i], SIGTERM);
    }
    kill((pid_t)root_pid, SIGTERM);
    usleep(100000);  /* 100ms grace */
    for (int i = 0; i < n; i++) {
        kill((pid_t)pids[i], SIGKILL);
    }
    kill((pid_t)root_pid, SIGKILL);
    /* Reap what we can (avoid zombies). */
    int reaped = 0;
    for (int t = 0; t < 50; t++) {
        int status;
        pid_t w = waitpid(-1, &status, WNOHANG);
        if (w > 0) { reaped++; }
        else if (w == 0) { usleep(10000); continue; }
        else { break; }  /* ECHILD */
        if (reaped >= n + 1) break;
    }
    LOGI("kill_tree_recursive root=%lld: killed %d descendants + root, reaped %d",
         (long long)root_pid, n, reaped);
    return WINE_SPIKE_OK;
}

/* Record a PID into out->descendants[] if not already present. Returns 1 if
 * recorded, 0 if skipped (already seen or full). */
static int record_pid(int64_t pid, struct wine_spike_proot_run_result *out,
                      const char *expected_native_dir) {
    for (int k = 0; k < out->descendant_count; k++) {
        if (out->descendants[k].pid == pid) return 0;  /* already present */
    }
    if (out->descendant_count >= WINE_SPIKE_DESCENDANTS_MAX) return 0;
    struct wine_spike_proc_info *info = &out->descendants[out->descendant_count];
    info->pid = pid;
    info->ppid = read_ppid(pid);
    read_cmdline(pid, info->cmdline, sizeof(info->cmdline));
    read_comm(pid, info->comm, sizeof(info->comm));
    info->classification[0] = '\0';
    char loader_path[WINE_SPIKE_PATH_MAX] = {0};
    char interp[256] = {0};
    int rc = wine_spike_probe_loader(pid, expected_native_dir,
                                     loader_path, sizeof(loader_path),
                                     interp, sizeof(interp));
    int apk_count = wine_spike_count_apk_mappings(pid, expected_native_dir);
    if (rc == WINE_SPIKE_OK) {
        snprintf(info->maps_proof, sizeof(info->maps_proof),
                 "OK|%s|%d", loader_path, apk_count);
    } else if (rc == WINE_SPIKE_ERR_IO) {
        snprintf(info->maps_proof, sizeof(info->maps_proof), "GONE");
    } else {
        snprintf(info->maps_proof, sizeof(info->maps_proof),
                 "FAIL|rc=%d|apk=%d", rc, apk_count);
    }
    out->descendant_count++;
    return 1;
}

/* PRoot tracees can be reparented to init while remaining part of the logical
 * launch. Those PIDs are no longer discoverable by walking the PRoot PID's
 * children, but snapshot_tree has already admitted them only after observing
 * the APK-managed loader in /proc/<pid>/maps. Kill that recorded set on a
 * bounded-run timeout so a persistent wineserver cannot retain capture pipes. */
static void kill_recorded_processes(
        const struct wine_spike_proot_run_result *out, int64_t root_pid) {
    int killed = 0;
    for (int i = 0; i < out->descendant_count; i++) {
        int64_t pid = out->descendants[i].pid;
        if (pid > 1 && pid != root_pid && kill((pid_t)pid, SIGKILL) == 0) killed++;
    }
    if (killed > 0) LOGI("killed %d recorded loader-mapped processes", killed);
}

/* Snapshot a proot run's process tree into out->descendants[]. Two strategies
 * are combined, because proot's traced children do not always show proot as
 * their PPID in /proc (ptrace reparenting + the loader execs wine in-place):
 *
 *   1. Recursive descendants of the proot PID (children, grandchildren, ...).
 *   2. A GLOBAL /proc scan for any process whose /proc/<pid>/maps contains the
 *      APK-managed loader (libld_linux_x86_64.so in expected_native_dir). This
 *      catches the glibc-namespace loader/wine process even if its PPID is not
 *      proot's PID.
 *
 * Both sets are de-duplicated by PID. Called while the processes are alive. */
static void snapshot_tree(int64_t root_pid,
                          struct wine_spike_proot_run_result *out,
                          const char *expected_native_dir) {
    /* NOTE: does NOT reset descendant_count — snapshots ACCUMULATE across
     * calls during the run, so a process observed briefly (e.g. the loader
     * during a fast `wine --version`) is retained even if later snapshots
     * (after exit) find nothing. De-dup is by PID in record_pid(). The run
     * loop resets descendant_count to 0 ONCE before the loop starts. */

    /* Strategy 1: recursive descendants of the proot PID. */
    int64_t pids[WINE_SPIKE_DESCENDANTS_MAX];
    int n = wine_spike_enum_descendants_recursive(root_pid, pids,
                                                  WINE_SPIKE_DESCENDANTS_MAX);
    int recursive_hits = 0;
    for (int i = 0; i < n; i++) {
        recursive_hits += record_pid(pids[i], out, expected_native_dir);
    }
    if (recursive_hits > 0) {
        LOGI("snapshot_tree root=%lld: recorded %d new recursive descendants",
             (long long)root_pid, recursive_hits);
    }

    /* Strategy 2: global /proc scan for any process mapping the APK loader. */
    DIR *proc = opendir("/proc");
    int global_hits = 0;
    if (proc) {
        struct dirent *de;
        while ((de = readdir(proc)) != NULL) {
            if (!isdigit((unsigned char)de->d_name[0])) continue;
            int64_t pid = (int64_t)atoll(de->d_name);
            if (pid <= 0) continue;
            char maps_path[128];
            snprintf(maps_path, sizeof(maps_path), "/proc/%lld/maps", (long long)pid);
            char maps[65536];
            int ml = read_file(maps_path, maps, sizeof(maps));
            if (ml <= 0) continue;
            if (strstr(maps, "libld_linux_x86_64.so") != NULL) {
                if (record_pid(pid, out, expected_native_dir)) global_hits++;
            }
        }
        closedir(proc);
    }
    if (global_hits > 0) {
        LOGI("snapshot_tree: recorded %d new processes mapping the APK loader",
             global_hits);
    }
}

/* ---- the synchronous run -------------------------------------------------- */

int wine_spike_run_wine_via_proot(const char *native_dir,
                                  const char *wine_target,
                                  const char *argv0_override,
                                  const char *prefix_dir,
                                  const char *display,
                                  const char *wine_args,
                                  const char *extra_env,
                                  int timeout_ms,
                                  struct wine_spike_proot_run_result *out) {
    if (!native_dir || !wine_target || !prefix_dir || !out)
        return WINE_SPIKE_ERR_ARGS;
    memset(out, 0, sizeof(*out));
    out->exit_status = -1;

    char proot_path[WINE_SPIKE_PATH_MAX];
    snprintf(proot_path, sizeof(proot_path), "%s/libproot.so", native_dir);
    if (access(proot_path, X_OK) != 0) {
        LOGE("proot_run: not found/executable: %s: %s", proot_path, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    char loader_path[WINE_SPIKE_PATH_MAX];
    snprintf(loader_path, sizeof(loader_path), "%s/libld_linux_x86_64.so", native_dir);

    char wine_real[WINE_SPIKE_PATH_MAX];
    resolve_wine_real(wine_target, wine_real, sizeof(wine_real));

    char argv0[WINE_SPIKE_PATH_MAX];
    derive_argv0(wine_target, argv0_override, argv0, sizeof(argv0));

    char tree_dir[WINE_SPIKE_PATH_MAX];
    derive_tree_dir(wine_target, tree_dir, sizeof(tree_dir));
    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path), "%s/lib:%s/lib/wine/x86_64-unix", tree_dir, tree_dir);

    char tmp_dir[WINE_SPIKE_PATH_MAX];
    derive_tmp_dir(prefix_dir, tmp_dir, sizeof(tmp_dir));
    mkdir(tmp_dir, 0755);  /* best-effort; ignore EEXIST */

    /* Do not use Android's real / as PRoot's guest root. untrusted_app is
     * denied even directory reads on the rootfs SELinux label, which Wine's
     * startup performs. Use an app-owned minimal root and bind only the paths
     * the provider needs. This is also closer to the glibc-rootfs architecture
     * used by Wine-on-Android projects. */
    char runtime_dir[WINE_SPIKE_PATH_MAX];
    snprintf(runtime_dir, sizeof(runtime_dir), "%s", prefix_dir);
    char *runtime_tail = strstr(runtime_dir, "/wine-prefix");
    if (runtime_tail) *runtime_tail = '\0';
    char rootfs_dir[WINE_SPIKE_PATH_MAX];
    snprintf(rootfs_dir, sizeof(rootfs_dir), "%s/rootfs", runtime_dir);
    if (mkdir_p_local(rootfs_dir) != 0) return WINE_SPIKE_ERR_IO;
    const char *root_dirs[] = {"tmp", "proc", "dev", "usr", "usr/share", "lib64", "data"};
    for (size_t i = 0; i < sizeof(root_dirs) / sizeof(root_dirs[0]); i++) {
        char dir[WINE_SPIKE_PATH_MAX];
        snprintf(dir, sizeof(dir), "%s/%s", rootfs_dir, root_dirs[i]);
        if (mkdir_p_local(dir) != 0) return WINE_SPIKE_ERR_IO;
    }

    /* Tokenize wine_args. */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *save = NULL;
        char *tok = strtok_r(args_copy, " ", &save);
        while (tok && n_args < 15) {
            arg_tokens[n_args++] = tok;
            tok = strtok_r(NULL, " ", &save);
        }
    }

    /* Build argv. KEY CHANGE vs the old launcher: --argv0 <logical> inserted
     * right after the loader, so Wine's argv[0] is the logical command name
     * ("wineboot"/"wine"/...) even though the real ELF is libwine_preloader.so.
     *
     * IMPORTANT: the glibc loader accepts the SPACE form `--argv0 NAME`, NOT
     * `--argv0=NAME` (the loader prints "unrecognized option '--argv0=wine'"
     * for the = form, verified on-device). So --argv0 and its value are two
     * separate argv entries. */
    const char *argv[96];
    int ai = 0;
    char bind_tmp[WINE_SPIKE_PATH_MAX * 2];
    snprintf(bind_tmp, sizeof(bind_tmp), "%s:/tmp", tmp_dir);
    char wine_loader_path[WINE_SPIKE_PATH_MAX];
    char wine_preloader_path[WINE_SPIKE_PATH_MAX];
    char wine_loader_guest[WINE_SPIKE_PATH_MAX];
    char wine_preloader_guest[WINE_SPIKE_PATH_MAX];
    char ntdll_path[WINE_SPIKE_PATH_MAX];
    char ntdll_guest[WINE_SPIKE_PATH_MAX];
    char wineserver_path[WINE_SPIKE_PATH_MAX];
    char wineserver_guest[WINE_SPIKE_PATH_MAX];
    char bind_wine_loader[WINE_SPIKE_PATH_MAX * 2];
    char bind_wine_preloader[WINE_SPIKE_PATH_MAX * 2];
    char bind_ntdll[WINE_SPIKE_PATH_MAX * 2];
    char bind_wineserver[WINE_SPIKE_PATH_MAX * 2];
    char bind_glibc_loader[WINE_SPIKE_PATH_MAX * 2];
    char wine_data_host[WINE_SPIKE_PATH_MAX];
    char wine_data_guest[WINE_SPIKE_PATH_MAX];
    char bind_wine_data[WINE_SPIKE_PATH_MAX * 2];
    char bind_wine_data_fallback[WINE_SPIKE_PATH_MAX * 2];
    char pe64_host[WINE_SPIKE_PATH_MAX];
    char pe64_guest[WINE_SPIKE_PATH_MAX];
    char bind_pe64[WINE_SPIKE_PATH_MAX * 2];
    char pe32_host[WINE_SPIKE_PATH_MAX];
    char pe32_guest[WINE_SPIKE_PATH_MAX];
    char bind_pe32[WINE_SPIKE_PATH_MAX * 2];
    char bind_native_dir[WINE_SPIKE_PATH_MAX * 2];
    char bind_runtime_dir[WINE_SPIKE_PATH_MAX * 2];
    snprintf(wine_loader_path, sizeof(wine_loader_path), "%s/libwine_loader.so", native_dir);
    snprintf(wine_preloader_path, sizeof(wine_preloader_path), "%s/libwine_loader_preloader.so", native_dir);
    snprintf(wine_loader_guest, sizeof(wine_loader_guest), "%s/wine", native_dir);
    snprintf(wine_preloader_guest, sizeof(wine_preloader_guest), "%s/wine-preloader", native_dir);
    snprintf(ntdll_path, sizeof(ntdll_path), "%s/libwine_unix_ntdll.so", native_dir);
    snprintf(ntdll_guest, sizeof(ntdll_guest), "%s/ntdll.so", native_dir);
    snprintf(wineserver_path, sizeof(wineserver_path), "%s/libwineserver.so", native_dir);
    snprintf(wineserver_guest, sizeof(wineserver_guest), "%s/wineserver", native_dir);
    snprintf(bind_wine_loader, sizeof(bind_wine_loader), "%s:%s", wine_loader_path, wine_loader_guest);
    snprintf(bind_wine_preloader, sizeof(bind_wine_preloader), "%s:%s", wine_preloader_path, wine_preloader_guest);
    snprintf(bind_ntdll, sizeof(bind_ntdll), "%s:%s", ntdll_path, ntdll_guest);
    snprintf(bind_wineserver, sizeof(bind_wineserver), "%s:%s", wineserver_path, wineserver_guest);
    snprintf(bind_glibc_loader, sizeof(bind_glibc_loader),
             "%s:/lib64/ld-linux-x86-64.so.2", loader_path);
    snprintf(wine_data_host, sizeof(wine_data_host),
             "%s/wine-data-cache/wine-data", runtime_dir);
    /* Installed ntdll derives DATADIR as nativeLibraryDir/../../share/wine.
     * Normalize that compile-time-relative location for PRoot's guest bind. */
    snprintf(wine_data_guest, sizeof(wine_data_guest), "%s", native_dir);
    char *data_slash = strrchr(wine_data_guest, '/');
    if (data_slash) *data_slash = '\0';
    data_slash = strrchr(wine_data_guest, '/');
    if (data_slash) *data_slash = '\0';
    size_t data_guest_len = strlen(wine_data_guest);
    snprintf(wine_data_guest + data_guest_len,
             sizeof(wine_data_guest) - data_guest_len, "/share/wine");
    snprintf(bind_wine_data, sizeof(bind_wine_data), "%s:%s", wine_data_host, wine_data_guest);
    /* When wineserver is launched through ld.so --argv0, argv[0] is logical
     * rather than a resolvable absolute pathname. Its first inferred NLS path
     * is therefore unavailable and it falls back to DATADIR (/usr/share/wine).
     * Map the identical verified data tree there as well. */
    snprintf(bind_wine_data_fallback, sizeof(bind_wine_data_fallback),
             "%s:/usr/share/wine", wine_data_host);
    snprintf(pe64_host, sizeof(pe64_host),
             "%s/wine-pe-cache/wine-pe/x86_64-windows", runtime_dir);
    snprintf(pe64_guest, sizeof(pe64_guest), "%s/x86_64-windows", native_dir);
    snprintf(bind_pe64, sizeof(bind_pe64), "%s:%s", pe64_host, pe64_guest);
    snprintf(pe32_host, sizeof(pe32_host),
             "%s/wine-pe-cache/wine-pe/i386-windows", runtime_dir);
    snprintf(pe32_guest, sizeof(pe32_guest), "%s/i386-windows", native_dir);
    snprintf(bind_pe32, sizeof(bind_pe32), "%s:%s", pe32_host, pe32_guest);
    snprintf(bind_native_dir, sizeof(bind_native_dir), "%s:%s", native_dir, native_dir);
    snprintf(bind_runtime_dir, sizeof(bind_runtime_dir), "%s:%s", runtime_dir, runtime_dir);

    argv[ai++] = proot_path;
    /* -v 0: keep routine PRoot translations out of the bounded stderr capture
     * so Wine's own diagnostics are retained. The full syscall evidence is
     * captured separately by S-5(0); this synchronous runner is for guest
     * completion/output. POCKET_PROOT_VERBOSE can still raise verbosity for a
     * focused diagnostic build. */
    const char *pverb = getenv("POCKET_PROOT_VERBOSE");
    argv[ai++] = "-v"; argv[ai++] = (pverb && *pverb) ? pverb : "0";
    argv[ai++] = "-b"; argv[ai++] = bind_native_dir;
    argv[ai++] = "-b"; argv[ai++] = bind_runtime_dir;
    argv[ai++] = "-b"; argv[ai++] = "/proc";
    argv[ai++] = "-b"; argv[ai++] = "/dev";
    argv[ai++] = "-b"; argv[ai++] = bind_tmp;
    /* APK packaging flattens Wine's Unix ELFs into nativeLibraryDir and renames
     * them lib*.so. Present their source-matched names in PRoot's guest
     * namespace without copying executable code to writable storage. */
    argv[ai++] = "-b"; argv[ai++] = bind_wine_loader;
    argv[ai++] = "-b"; argv[ai++] = bind_wine_preloader;
    /* The second-stage loader dlopens ntdll.so beside its own guest path. */
    argv[ai++] = "-b"; argv[ai++] = bind_ntdll;
    /* ntdll execs wineserver as a separate ELF.  WINESERVER below points at
     * this immutable APK-backed alias rather than a writable extracted file. */
    argv[ai++] = "-b"; argv[ai++] = bind_wineserver;
    argv[ai++] = "-b"; argv[ai++] = bind_glibc_loader;
    argv[ai++] = "-b"; argv[ai++] = bind_wine_data;
    argv[ai++] = "-b"; argv[ai++] = bind_wine_data_fallback;
    argv[ai++] = "-b"; argv[ai++] = bind_pe64;
    argv[ai++] = "-b"; argv[ai++] = bind_pe32;
    argv[ai++] = "-r"; argv[ai++] = rootfs_dir;
    /* Keep PRoot's native link(2) semantics.  Wine creates hard links inside
     * its app-private prefix, where Android's filesystem permits them.  The
     * link2symlink extension rewrites every guest link call and is unnecessary
     * for this rootfs; removing it also keeps that extension out of the PE
     * loader's early allocation/mapping path. */
    argv[ai++] = loader_path;
    argv[ai++] = "--argv0";
    argv[ai++] = argv0;               /* preserve logical argv[0] (space form) */
    argv[ai++] = "--library-path";
    argv[ai++] = lib_path;
    argv[ai++] = wine_real;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    /* Build envp (stable buffers — child reads these after fork). */
    char env_proot_loader[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_loader, sizeof(env_proot_loader),
             "PROOT_LOADER=%s/libproot_loader.so", native_dir);
    char env_proot_loader32[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_loader32, sizeof(env_proot_loader32),
             "PROOT_LOADER_32=%s/libproot_loader32.so", native_dir);
    char env_ldpath[WINE_SPIKE_PATH_MAX * 3];
    /* native_dir lets Bionic PRoot find libtalloc.so; lib_path lets the
     * implicitly re-execed glibc Wine loader resolve canonical SONAMEs. */
    snprintf(env_ldpath, sizeof(env_ldpath), "LD_LIBRARY_PATH=%s:%s", native_dir, lib_path);
    char env_proot_tmp[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_tmp, sizeof(env_proot_tmp), "PROOT_TMP_DIR=%s", tmp_dir);
    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    char env_dllpath[WINE_SPIKE_PATH_MAX * 3];
    snprintf(env_dllpath, sizeof(env_dllpath),
             "WINEDLLPATH=%s/lib/wine/x86_64-windows:%s/lib/wine/i386-windows:"
             "%s/lib/wine/x86_64-unix", tree_dir, tree_dir, tree_dir);
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s", tmp_dir);
    char env_path[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);
    char env_wineserver[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_wineserver, sizeof(env_wineserver), "WINESERVER=%s", wineserver_guest);
    char env_display[256] = {0};
    if (display && *display) snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);

    char extra_slots[1024] = {0};
    char *extra_ptrs[16] = {NULL};
    int n_extra = 0;
    if (extra_env && *extra_env) {
        snprintf(extra_slots, sizeof(extra_slots), "%s", extra_env);
        char *save = NULL;
        char *tok = strtok_r(extra_slots, ";", &save);
        while (tok && n_extra < 16) {
            extra_ptrs[n_extra++] = tok;
            tok = strtok_r(NULL, ";", &save);
        }
    }

    const char *envp[40];
    int ei = 0;
    envp[ei++] = env_proot_loader;
    envp[ei++] = env_proot_loader32;
    envp[ei++] = env_ldpath;
    envp[ei++] = env_proot_tmp;
    envp[ei++] = env_prefix;
    envp[ei++] = env_dllpath;
    envp[ei++] = env_home;
    envp[ei++] = env_tmpdir;
    envp[ei++] = env_path;
    envp[ei++] = env_wineserver;
    /* If the caller's extra_env already supplies WINEDEBUG, don't override it
     * with -all (the caller may want +module/+loaddll to prove PE resolution,
     * or no suppression to see wineboot errors). Scan extra_env for it. */
    int caller_has_winedebug = 0;
    for (int i = 0; i < n_extra; i++) {
        if (strncmp(extra_ptrs[i], "WINEDEBUG=", 10) == 0) { caller_has_winedebug = 1; break; }
    }
    if (!caller_has_winedebug) envp[ei++] = "WINEDEBUG=-all";
    /* LD_DEBUG=libs is the S-1 loader-chain proof. Allow the caller to override
     * (e.g. to disable it for S-2 so wineboot's own stderr isn't crowded out). */
    int caller_has_lddebug = 0;
    for (int i = 0; i < n_extra; i++) {
        if (strncmp(extra_ptrs[i], "LD_DEBUG=", 9) == 0) { caller_has_lddebug = 1; break; }
    }
    if (!caller_has_lddebug) envp[ei++] = "LD_DEBUG=libs";
    if (env_display[0]) envp[ei++] = env_display;
    for (int i = 0; i < n_extra && ei < 39; i++) envp[ei++] = extra_ptrs[i];
    envp[ei] = NULL;

    LOGI("proot_run: argv0=%s wine_real=%s lib_path=%s timeout=%dms",
         argv0, wine_real, lib_path, timeout_ms);

    /* Pipes for stdout/stderr capture. */
    int out_pipe[2] = {-1, -1}, err_pipe[2] = {-1, -1};
    if (pipe2(out_pipe, O_CLOEXEC) != 0 || pipe2(err_pipe, O_CLOEXEC) != 0) {
        LOGE("proot_run: pipe2: %s", strerror(errno));
        if (out_pipe[0] >= 0) close(out_pipe[0]);
        if (out_pipe[1] >= 0) close(out_pipe[1]);
        return WINE_SPIKE_ERR_LAUNCH;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("proot_run: fork: %s", strerror(errno));
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        /* Child: dup pipes onto stdout/stderr, then execve proot. */
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        execve(proot_path, (char *const *)argv, (char *const *)envp);
        fprintf(stderr, "proot_run: execve failed: %s\n", strerror(errno));
        _exit(127);
    }
    /* Parent. */
    close(out_pipe[1]); close(err_pipe[1]);
    set_nonblocking(out_pipe[0]);
    set_nonblocking(err_pipe[0]);
    int64_t child_pid = (int64_t)pid;

    /* Read stdout/stderr concurrently while waiting, with a deadline. Poll the
     * pipes and also waitpid(WNOHANG) so we notice exit promptly. Periodically
     * snapshot the descendant tree while it is alive. */
    size_t out_len = 0, err_len = 0;
    int status = 0;
    int got_status = 0;
    struct timespec exit_drain_deadline = {0};
    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    if (timeout_ms > 0) {
        deadline.tv_sec += timeout_ms / 1000;
        deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    }

    while (1) {
        /* Drain pipes with a short poll timeout. */
        struct pollfd pfds[2];
        int nfds = 0;
        if (out_pipe[0] >= 0) { pfds[nfds].fd = out_pipe[0]; pfds[nfds].events = POLLIN; nfds++; }
        if (err_pipe[0] >= 0) { pfds[nfds].fd = err_pipe[0]; pfds[nfds].events = POLLIN; nfds++; }
        int prc = (nfds > 0) ? poll(pfds, nfds, 100) : 100;
        if (prc > 0) {
            for (int i = 0; i < nfds; i++) {
                if (!(pfds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
                char buf[4096];
                ssize_t n = read(pfds[i].fd, buf, sizeof(buf));
                if (n > 0) {
                    char *dst = (pfds[i].fd == out_pipe[0]) ? out->stdout_buf : out->stderr_buf;
                    size_t *lenp = (pfds[i].fd == out_pipe[0]) ? &out_len : &err_len;
                    size_t cap = (pfds[i].fd == out_pipe[0]) ? sizeof(out->stdout_buf) : sizeof(out->stderr_buf);
                    size_t room = (cap > *lenp + 1) ? cap - 1 - *lenp : 0;
                    size_t cp = ((size_t)n < room) ? (size_t)n : room;
                    if (cp > 0) { memcpy(dst + *lenp, buf, cp); *lenp += cp; dst[*lenp] = '\0'; }
                } else {
                    /* EOF/error: close this pipe. */
                    close(pfds[i].fd);
                    if (pfds[i].fd == out_pipe[0]) out_pipe[0] = -1; else err_pipe[0] = -1;
                }
            }
        }

        /* Snapshot the process tree EVERY iteration while the child is alive.
         * Fast commands (wine --version) may exit in well under a second, so a
         * single delayed snapshot misses the loader/wine process. The global
         * /proc scan + de-dup accumulates every process that maps the APK
         * loader at any point during the run. Each iteration is ~the poll
         * interval (<=100ms), so we sample at ~10Hz. */
        if (!got_status) {
            snapshot_tree(child_pid, out, native_dir);
        }

        /* Re-check exit. */
        if (!got_status) {
            pid_t w = waitpid(pid, &status, WNOHANG);
            if (w == pid) {
                got_status = 1;
                clock_gettime(CLOCK_MONOTONIC, &exit_drain_deadline);
                exit_drain_deadline.tv_sec += 1;
            } else if (w < 0 && errno == ECHILD) {
                got_status = 1;  /* already reaped elsewhere */
                clock_gettime(CLOCK_MONOTONIC, &exit_drain_deadline);
                exit_drain_deadline.tv_sec += 1;
            }
        }

        /* Done if child exited AND pipes are drained. */
        int pipes_open = (out_pipe[0] >= 0) || (err_pipe[0] >= 0);
        if (got_status && !pipes_open) break;
        if (got_status && pipes_open) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (now.tv_sec > exit_drain_deadline.tv_sec ||
                (now.tv_sec == exit_drain_deadline.tv_sec &&
                 now.tv_nsec >= exit_drain_deadline.tv_nsec)) {
                LOGI("proot_run: root exited; closing pipes retained by background services");
                kill_recorded_processes(out, child_pid);
                wine_spike_kill_tree_recursive(child_pid);
                if (out_pipe[0] >= 0) { close(out_pipe[0]); out_pipe[0] = -1; }
                if (err_pipe[0] >= 0) { close(err_pipe[0]); err_pipe[0] = -1; }
                break;
            }
        }

        /* Timeout check. */
        if (timeout_ms > 0 && !got_status) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (now.tv_sec > deadline.tv_sec ||
                (now.tv_sec == deadline.tv_sec && now.tv_nsec >= deadline.tv_nsec)) {
                LOGW("proot_run: timeout after %dms; killing tree root=%lld",
                     timeout_ms, (long long)child_pid);
                out->timed_out = 1;
                /* Snapshot what we can before killing (best-effort). */
                snapshot_tree(child_pid, out, native_dir);
                kill_recorded_processes(out, child_pid);
                wine_spike_kill_tree_recursive(child_pid);
                /* Drain remaining pipe output briefly, then break. */
                for (int t = 0; t < 10; t++) {
                    if (out_pipe[0] >= 0) {
                        char b[1024];
                        ssize_t n = read(out_pipe[0], b, sizeof(b));
                        if (n <= 0) { close(out_pipe[0]); out_pipe[0] = -1; }
                        else {
                            size_t room = sizeof(out->stdout_buf) - 1 - out_len;
                            size_t cp = ((size_t)n < room) ? (size_t)n : room;
                            if (cp > 0) { memcpy(out->stdout_buf + out_len, b, cp); out_len += cp; out->stdout_buf[out_len] = '\0'; }
                        }
                    }
                    if (err_pipe[0] >= 0) {
                        char b[1024];
                        ssize_t n = read(err_pipe[0], b, sizeof(b));
                        if (n <= 0) { close(err_pipe[0]); err_pipe[0] = -1; }
                        else {
                            size_t room = sizeof(out->stderr_buf) - 1 - err_len;
                            size_t cp = ((size_t)n < room) ? (size_t)n : room;
                            if (cp > 0) { memcpy(out->stderr_buf + err_len, b, cp); err_len += cp; out->stderr_buf[err_len] = '\0'; }
                        }
                    }
                    if (out_pipe[0] < 0 && err_pipe[0] < 0) break;
                    usleep(10000);
                }
                waitpid(pid, &status, WNOHANG);
                break;
            }
        }
        /* If child is done but pipes still open (rare), keep draining but bound
         * the wait so we don't loop forever. */
        if (got_status && pipes_open) {
            /* A few more short polls to flush, then give up. */
        }
    }

    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (err_pipe[0] >= 0) close(err_pipe[0]);

    out->exit_status = got_status ? status : -1;
    out->proot_rc = WINE_SPIKE_OK;
    LOGI("proot_run: done exit_status=%d timed_out=%d descendants=%d stdout=%zuB stderr=%zuB",
         out->exit_status, out->timed_out, out->descendant_count, out_len, err_len);
    return WINE_SPIKE_OK;
}

/* Direct-glibc execution. This intentionally shares the mature capture,
 * timeout and maps-evidence result shape with the PRoot runner, but does not
 * place a ptrace translator between Wine and the kernel. */
int wine_spike_run_wine_direct(const char *native_dir,
                               const char *pe_target,
                               const char *prefix_dir,
                               const char *display,
                               const char *wine_args,
                               const char *extra_env,
                               int timeout_ms,
                               struct wine_spike_proot_run_result *out) {
    if (!native_dir || !pe_target || !prefix_dir || !out)
        return WINE_SPIKE_ERR_ARGS;
    memset(out, 0, sizeof(*out));
    out->exit_status = -1;

    char loader[WINE_SPIKE_PATH_MAX];
    char wine_loader[WINE_SPIKE_PATH_MAX];
    char wine_preloader[WINE_SPIKE_PATH_MAX];
    char shim[WINE_SPIKE_PATH_MAX];
    char server_real[WINE_SPIKE_PATH_MAX];
    snprintf(loader, sizeof(loader), "%s/libld_linux_x86_64.so", native_dir);
    snprintf(wine_loader, sizeof(wine_loader), "%s/libwine_loader.so", native_dir);
    snprintf(wine_preloader, sizeof(wine_preloader), "%s/libwine_loader_preloader.so", native_dir);
    snprintf(shim, sizeof(shim), "%s/libwine_android_shim.so", native_dir);
    snprintf(server_real, sizeof(server_real), "%s/libwineserver.so", native_dir);
    const char *required[] = {loader, wine_loader, wine_preloader, shim, server_real};
    for (size_t i = 0; i < sizeof(required) / sizeof(required[0]); i++) {
        if (access(required[i], R_OK) != 0) {
            LOGE("direct_run: required APK artifact unavailable: %s: %s", required[i], strerror(errno));
            return WINE_SPIKE_ERR_LAUNCH;
        }
    }

    char runtime_dir[WINE_SPIKE_PATH_MAX];
    snprintf(runtime_dir, sizeof(runtime_dir), "%s", prefix_dir);
    char *prefix_tail = strstr(runtime_dir, "/wine-prefix");
    if (!prefix_tail) return WINE_SPIKE_ERR_ARGS;
    *prefix_tail = '\0';

    char tree_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tree_dir, sizeof(tree_dir), "%s/wine-tree", runtime_dir);

    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    char pe64[WINE_SPIKE_PATH_MAX], pe32[WINE_SPIKE_PATH_MAX];
    char data[WINE_SPIKE_PATH_MAX], tmp[WINE_SPIKE_PATH_MAX];
    snprintf(lib_path, sizeof(lib_path), "%s/lib:%s", tree_dir, native_dir);
    snprintf(pe64, sizeof(pe64), "%s/wine-pe-cache/wine-pe/x86_64-windows", runtime_dir);
    snprintf(pe32, sizeof(pe32), "%s/wine-pe-cache/wine-pe/i386-windows", runtime_dir);
    snprintf(data, sizeof(data), "%s/wine-data-cache/wine-data", runtime_dir);
    snprintf(tmp, sizeof(tmp), "%s/tmp", runtime_dir);
    mkdir(tmp, 0700);

    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *save = NULL;
        char *tok = strtok_r(args_copy, " ", &save);
        while (tok && n_args < 15) {
            arg_tokens[n_args++] = tok;
            tok = strtok_r(NULL, " ", &save);
        }
    }

    char logical_wine[WINE_SPIKE_PATH_MAX];
    snprintf(logical_wine, sizeof(logical_wine), "%s/lib/wine/x86_64-unix/wine", tree_dir);
    const char *argv[32];
    int ai = 0;
    argv[ai++] = wine_preloader;
    argv[ai++] = logical_wine;
    argv[ai++] = pe_target;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    char env_ldpath[WINE_SPIKE_PATH_MAX * 2 + 32];
    char env_preload[WINE_SPIKE_PATH_MAX + 32];
    char env_dllpath[WINE_SPIKE_PATH_MAX * 3];
    char env_wineserver[WINE_SPIKE_PATH_MAX + 32];
    char env_native[WINE_SPIKE_PATH_MAX + 40];
    char env_pe64[WINE_SPIKE_PATH_MAX + 32], env_pe32[WINE_SPIKE_PATH_MAX + 32];
    char env_pe_cache[WINE_SPIKE_PATH_MAX + 40];
    char env_pe_target[WINE_SPIKE_PATH_MAX + 40];
    char env_data[WINE_SPIKE_PATH_MAX + 32], env_tmp[WINE_SPIKE_PATH_MAX + 32];
    char env_loader[WINE_SPIKE_PATH_MAX + 32];
    char env_display[256] = {0};
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s", tmp);
    snprintf(env_ldpath, sizeof(env_ldpath), "LD_LIBRARY_PATH=%s", lib_path);
    snprintf(env_preload, sizeof(env_preload), "LD_PRELOAD=%s", shim);
    snprintf(env_dllpath, sizeof(env_dllpath),
             "WINEDLLPATH=%s:%s:%s/lib/wine/x86_64-unix", pe64, pe32, tree_dir);
    /* fd 100 survives exec and the staged wineserver PT_INTERP names it.
     * Launching the real glibc wineserver directly also keeps LD_PRELOAD in
     * the correct namespace; a Bionic trampoline cannot inherit glibc's
     * preload/library-path variables without Android's linker rejecting it. */
    snprintf(env_wineserver, sizeof(env_wineserver), "WINESERVER=%s", server_real);
    snprintf(env_native, sizeof(env_native), "POCKET_WINE_NATIVE_DIR=%s", native_dir);
    snprintf(env_pe64, sizeof(env_pe64), "POCKET_WINE_PE64=%s", pe64);
    snprintf(env_pe32, sizeof(env_pe32), "POCKET_WINE_PE32=%s", pe32);
    snprintf(env_pe_cache, sizeof(env_pe_cache),
             "POCKET_WINE_PE_CACHE=%s/wine-pe-cache", runtime_dir);
    snprintf(env_pe_target, sizeof(env_pe_target),
             "POCKET_WINE_PE_TARGET=%s", pe_target);
    snprintf(env_data, sizeof(env_data), "POCKET_WINE_DATA=%s", data);
    snprintf(env_tmp, sizeof(env_tmp), "POCKET_WINE_TMP=%s", tmp);
    snprintf(env_loader, sizeof(env_loader), "POCKET_GLIBC_LOADER=%s", loader);
    if (display && *display) snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);

    char extra_copy[1024] = {0};
    char *extra_ptrs[16] = {NULL};
    int n_extra = 0;
    if (extra_env && *extra_env) {
        snprintf(extra_copy, sizeof(extra_copy), "%s", extra_env);
        char *save = NULL;
        char *tok = strtok_r(extra_copy, ";", &save);
        while (tok && n_extra < 16) {
            extra_ptrs[n_extra++] = tok;
            tok = strtok_r(NULL, ";", &save);
        }
    }

    const char *envp[48];
    int ei = 0;
    envp[ei++] = env_prefix; envp[ei++] = env_home; envp[ei++] = env_tmpdir;
    envp[ei++] = env_ldpath; envp[ei++] = env_preload; envp[ei++] = env_dllpath;
    envp[ei++] = env_wineserver; envp[ei++] = env_native; envp[ei++] = env_pe64;
    envp[ei++] = env_pe32; envp[ei++] = env_pe_cache; envp[ei++] = env_pe_target;
    envp[ei++] = env_data; envp[ei++] = env_tmp;
    envp[ei++] = env_loader;
    envp[ei++] = "PATH=/system/bin";
    /* Android labels writable app-data files app_data_file and denies their
     * executable mappings (execmod). The PE cache remains the hash-verified
     * source of truth; the glibc shim copies requested executable image ranges
     * into anonymous private mappings before applying Wine's protections. */
    envp[ei++] = "POCKET_WINE_PE_ANON_EXEC=1";
    /* Normally loader_exec() sets this immediately before it invokes
     * wine-preloader. We enter the preloader directly because its interpreter
     * is supplied through inherited fd 100, so preserve that one-shot marker
     * and let __wine_main continue with the explicit PE argv. */
    envp[ei++] = "WINELOADERNOEXEC=1";
    int has_winedebug = 0, has_lddebug = 0;
    int trace_sigsys = 0;
    int use_strace = 0;
    for (int i = 0; i < n_extra; i++) {
        if (!strncmp(extra_ptrs[i], "WINEDEBUG=", 10)) has_winedebug = 1;
        if (!strncmp(extra_ptrs[i], "LD_DEBUG=", 9)) has_lddebug = 1;
        if (!strcmp(extra_ptrs[i], "POCKET_TRACE_SIGSYS=1")) trace_sigsys = 1;
        if (!strcmp(extra_ptrs[i], "POCKET_USE_STRACE=1")) use_strace = 1;
    }
    if (!has_winedebug) envp[ei++] = "WINEDEBUG=-all";
    if (!has_lddebug) envp[ei++] = "LD_DEBUG=";
    if (env_display[0]) envp[ei++] = env_display;
    for (int i = 0; i < n_extra && ei < 47; i++) envp[ei++] = extra_ptrs[i];
    envp[ei] = NULL;

    int out_pipe[2] = {-1, -1}, err_pipe[2] = {-1, -1};
    if (pipe2(out_pipe, O_CLOEXEC) != 0 || pipe2(err_pipe, O_CLOEXEC) != 0) {
        if (out_pipe[0] >= 0) close(out_pipe[0]);
        if (out_pipe[1] >= 0) close(out_pipe[1]);
        return WINE_SPIKE_ERR_LAUNCH;
    }
    pid_t pid = fork();
    if (pid < 0) {
        close(out_pipe[0]); close(out_pipe[1]); close(err_pipe[0]); close(err_pipe[1]);
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        dup2(out_pipe[1], STDOUT_FILENO); dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]); close(err_pipe[0]); close(err_pipe[1]);
        if (trace_sigsys) {
            if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) {
                fprintf(stderr, "direct_run: PTRACE_TRACEME failed: %s\n", strerror(errno));
                _exit(126);
            }
            raise(SIGSTOP);
        }
        /* Wine's static preloader maps the final loader and its PT_INTERP
         * without a kernel exec. The staged final loader names fd 100 as its
         * interpreter, so keep an immutable APK rtld handle there. */
        int loader_fd = open(loader, O_RDONLY | O_CLOEXEC);
        if (loader_fd < 0 || dup2(loader_fd, 100) < 0) {
            fprintf(stderr, "direct_run: reserve rtld fd 100 failed: %s\n", strerror(errno));
            _exit(127);
        }
        if (loader_fd != 100) close(loader_fd);
        if (use_strace) {
            /* The emulator's platform strace is a Bionic ELF, so it must not
             * receive the glibc shim itself.  strace's -E option installs the
             * preload only in the Wine child while all other runtime variables
             * remain inherited.  Writing the trace to fd 2 keeps it inside the
             * existing bounded capture/evidence path. */
            const char *trace_envp[48];
            int tei = 0;
            for (int i = 0; envp[i] && tei < 47; i++) {
                if (envp[i] == env_preload) continue;
                trace_envp[tei++] = envp[i];
            }
            trace_envp[tei] = NULL;
            const char *trace_argv[48];
            int ti = 0;
            trace_argv[ti++] = "/system/bin/strace";
            trace_argv[ti++] = "-f";
            trace_argv[ti++] = "-s";
            trace_argv[ti++] = "256";
            trace_argv[ti++] = "-o";
            trace_argv[ti++] = "/proc/self/fd/2";
            trace_argv[ti++] = "-e";
            trace_argv[ti++] = "trace=%memory,%process,%file,%signal";
            trace_argv[ti++] = "-E";
            trace_argv[ti++] = env_preload;
            for (int i = 0; argv[i] && ti < 47; i++) trace_argv[ti++] = argv[i];
            trace_argv[ti] = NULL;
            execve("/system/bin/strace", (char *const *)trace_argv,
                   (char *const *)trace_envp);
            fprintf(stderr, "direct_run: execve strace failed: %s\n", strerror(errno));
            _exit(127);
        }
        execve(wine_preloader, (char *const *)argv, (char *const *)envp);
        fprintf(stderr, "direct_run: execve preloader failed: %s\n", strerror(errno));
        _exit(127);
    }
    close(out_pipe[1]); close(err_pipe[1]);
    set_nonblocking(out_pipe[0]);
    set_nonblocking(err_pipe[0]);

    if (trace_sigsys) {
        int initial = 0;
        if (waitpid(pid, &initial, 0) != pid || !WIFSTOPPED(initial)) {
            LOGE("direct_run: diagnostic tracee did not reach initial stop (status=%d)", initial);
            close(out_pipe[0]); close(err_pipe[0]);
            return WINE_SPIKE_ERR_LAUNCH;
        }
        long trace_options = PTRACE_O_TRACEEXEC | PTRACE_O_TRACEFORK |
                             PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE;
        ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)(intptr_t)trace_options);
        ptrace(PTRACE_CONT, pid, 0, 0);
    }

    size_t out_len = 0, err_len = 0;
    int status = 0, got_status = 0;
    unsigned int fault_trace_count = 0;
    struct timespec exit_drain_deadline = {0};
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (1) {
        struct pollfd pfds[2];
        int nfds = 0;
        if (out_pipe[0] >= 0) { pfds[nfds].fd = out_pipe[0]; pfds[nfds].events = POLLIN; nfds++; }
        if (err_pipe[0] >= 0) { pfds[nfds].fd = err_pipe[0]; pfds[nfds].events = POLLIN; nfds++; }
        if (nfds) poll(pfds, nfds, 50);
        for (int i = 0; i < nfds; i++) {
            if (!(pfds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            char buf[4096];
            ssize_t n = read(pfds[i].fd, buf, sizeof(buf));
            if (n > 0) {
                char *dst = pfds[i].fd == out_pipe[0] ? out->stdout_buf : out->stderr_buf;
                size_t *lenp = pfds[i].fd == out_pipe[0] ? &out_len : &err_len;
                size_t cap = pfds[i].fd == out_pipe[0] ? sizeof(out->stdout_buf) : sizeof(out->stderr_buf);
                if (trace_sigsys) {
                    size_t room = cap - 1 - *lenp;
                    size_t copy = (size_t)n < room ? (size_t)n : room;
                    if (copy) {
                        memcpy(dst + *lenp, buf, copy);
                        *lenp += copy;
                        dst[*lenp] = '\0';
                    }
                } else {
                    append_capture_tail(dst, lenp, cap, buf, (size_t)n);
                }
            } else {
                int fd = pfds[i].fd;
                close(fd);
                if (fd == out_pipe[0]) out_pipe[0] = -1; else err_pipe[0] = -1;
            }
        }
        if (!got_status) {
            snapshot_tree((int64_t)pid, out, native_dir);
            int wstatus = 0;
            pid_t w;
            while ((w = waitpid(trace_sigsys ? -1 : pid, &wstatus,
                                WNOHANG
#ifdef __WALL
                                | (trace_sigsys ? __WALL : 0)
#endif
                                )) > 0) {
                if (WIFSTOPPED(wstatus) && trace_sigsys) {
                    int sig = WSTOPSIG(wstatus);
                    int event = (wstatus >> 16) & 0xffff;
                    if (sig == SIGSYS) {
                        siginfo_t si;
                        memset(&si, 0, sizeof(si));
                        int si_rc = ptrace(PTRACE_GETSIGINFO, w, 0, &si);
                        unsigned long long rip = 0;
                        long long nr = direct_trace_syscall_nr(w, &rip, NULL);
                        LOGE("direct_run: SIGSYS pid=%d si_code=%d syscall=%lld rip=0x%llx call_addr=%p",
                             w, si_rc == 0 ? si.si_code : -1, nr, rip,
                             si_rc == 0 ? si.si_call_addr : NULL);
                        direct_log_mapping_for_address(w, rip);
                        ptrace(PTRACE_CONT, w, 0, (void *)(intptr_t)SIGSYS);
                    } else if (sig == SIGABRT) {
                        unsigned long long rip = 0, rsp = 0;
                        direct_trace_syscall_nr(w, &rip, &rsp);
                        LOGE("direct_run: SIGABRT pid=%d rip=0x%llx rsp=0x%llx", w, rip, rsp);
                        direct_log_mapping_for_address(w, rip);
                        direct_log_stack_candidates(w, rsp);
                        ptrace(PTRACE_CONT, w, 0, (void *)(intptr_t)SIGABRT);
                    } else if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) {
                        if (fault_trace_count++ < 16) {
                            siginfo_t si;
                            unsigned long long rip = 0, rsp = 0;
                            memset(&si, 0, sizeof(si));
                            int si_rc = ptrace(PTRACE_GETSIGINFO, w, 0, &si);
                            direct_trace_syscall_nr(w, &rip, &rsp);
                            LOGE("direct_run: fault pid=%d sig=%d code=%d addr=%p rip=0x%llx rsp=0x%llx",
                                 w, sig, si_rc == 0 ? si.si_code : -1,
                                 si_rc == 0 ? si.si_addr : NULL, rip, rsp);
                            direct_log_mapping_for_address(w, rip);
                            if (fault_trace_count == 1)
                                direct_log_stack_candidates(w, rsp);
                            ptrace(PTRACE_CONT, w, 0, (void *)(intptr_t)sig);
                        } else {
                            LOGE("direct_run: terminating repeating fault pid=%d after diagnostic capture", w);
                            ptrace(PTRACE_KILL, w, 0, 0);
                        }
                    } else {
                        if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK ||
                            event == PTRACE_EVENT_CLONE) {
                            unsigned long child = 0;
                            ptrace(PTRACE_GETEVENTMSG, w, 0, &child);
                            LOGI("direct_run: ptrace spawn event=%d parent=%d child=%lu",
                                 event, w, child);
                        } else {
                            LOGI("direct_run: ptrace stop pid=%d signal=%d event=%d", w, sig, event);
                        }
                        /* SIGTRAP is a ptrace event and SIGSTOP is the
                         * synthetic first stop of an auto-attached child. */
                        int deliver = (sig == SIGTRAP || sig == SIGSTOP) ? 0 : sig;
                        ptrace(PTRACE_CONT, w, 0, (void *)(intptr_t)deliver);
                    }
                } else if (w == pid) {
                    status = wstatus;
                    got_status = 1;
                    clock_gettime(CLOCK_MONOTONIC, &exit_drain_deadline);
                    exit_drain_deadline.tv_sec += 5;
                } else {
                    LOGI("direct_run: traced child %d exited status=%d", w, wstatus);
                }
            }
            if (w < 0 && errno == ECHILD && !got_status) {
                got_status = 1;
                clock_gettime(CLOCK_MONOTONIC, &exit_drain_deadline);
                exit_drain_deadline.tv_sec += 5;
            }
        }
        if (got_status && out_pipe[0] < 0 && err_pipe[0] < 0) break;
        if (got_status && (out_pipe[0] >= 0 || err_pipe[0] >= 0)) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (now.tv_sec > exit_drain_deadline.tv_sec ||
                (now.tv_sec == exit_drain_deadline.tv_sec &&
                 now.tv_nsec >= exit_drain_deadline.tv_nsec)) {
                LOGI("direct_run: root exited; capture grace elapsed, closing retained pipes");
                if (out_pipe[0] >= 0) { close(out_pipe[0]); out_pipe[0] = -1; }
                if (err_pipe[0] >= 0) { close(err_pipe[0]); err_pipe[0] = -1; }
                break;
            }
        }
        if (!got_status && timeout_ms > 0) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            long long elapsed = (now.tv_sec - start.tv_sec) * 1000LL +
                                (now.tv_nsec - start.tv_nsec) / 1000000LL;
            if (elapsed >= timeout_ms) {
                out->timed_out = 1;
                snapshot_tree((int64_t)pid, out, native_dir);
                kill_recorded_processes(out, (int64_t)pid);
                wine_spike_kill_tree_recursive((int64_t)pid);
                waitpid(pid, &status, WNOHANG);
                break;
            }
        }
    }
    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (err_pipe[0] >= 0) close(err_pipe[0]);
    out->exit_status = got_status ? status : -1;
    out->proot_rc = WINE_SPIKE_OK;
    LOGI("direct_run: done status=%d timeout=%d desc=%d stdout=%zu stderr=%zu",
         out->exit_status, out->timed_out, out->descendant_count, out_len, err_len);
    return WINE_SPIKE_OK;
}
