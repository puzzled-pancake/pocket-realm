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
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

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

/* Snapshot a proot descendant tree into out->descendants[]: for each descendant
 * (and root), record pid/ppid/cmdline/comm + /proc/<pid>/maps proof. Called
 * while the tree is alive (between launch and wait completion). */
static void snapshot_tree(int64_t root_pid,
                          struct wine_spike_proot_run_result *out,
                          const char *expected_native_dir) {
    out->descendant_count = 0;
    int64_t pids[WINE_SPIKE_DESCENDANTS_MAX];
    int n = wine_spike_enum_descendants_recursive(root_pid, pids,
                                                  WINE_SPIKE_DESCENDANTS_MAX);
    LOGI("snapshot_tree root=%lld: %d descendants", (long long)root_pid, n);
    for (int i = 0; i < n && out->descendant_count < WINE_SPIKE_DESCENDANTS_MAX; i++) {
        int64_t pid = pids[i];
        struct wine_spike_proc_info *info = &out->descendants[out->descendant_count];
        info->pid = pid;
        info->ppid = read_ppid(pid);
        read_cmdline(pid, info->cmdline, sizeof(info->cmdline));
        read_comm(pid, info->comm, sizeof(info->comm));
        info->classification[0] = '\0';
        /* /proc/<pid>/maps proof: APK-managed loader present? */
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

    /* Tokenize wine_args. */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *tok = strtok(args_copy, " ");
        while (tok && n_args < 15) { arg_tokens[n_args++] = tok; tok = strtok(NULL, " "); }
    }

    /* Build argv. KEY CHANGE vs the old launcher: --argv0 <logical> inserted
     * right after the loader, so Wine's argv[0] is the logical command name
     * ("wineboot"/"wine"/...) even though the real ELF is libwine_preloader.so.
     * The glibc loader supports --argv0=NAME (added in glibc 2.33). */
    const char *argv[48];
    int ai = 0;
    char argv0_opt[WINE_SPIKE_PATH_MAX + 16];
    snprintf(argv0_opt, sizeof(argv0_opt), "--argv0=%s", argv0);
    char bind_tmp[WINE_SPIKE_PATH_MAX * 2];
    snprintf(bind_tmp, sizeof(bind_tmp), "%s:/tmp", tmp_dir);

    argv[ai++] = proot_path;
    argv[ai++] = "-v"; argv[ai++] = "5";
    argv[ai++] = "-b"; argv[ai++] = bind_tmp;
    argv[ai++] = "-r"; argv[ai++] = "/";
    argv[ai++] = "--link2symlink";
    argv[ai++] = loader_path;
    argv[ai++] = argv0_opt;            /* preserve logical argv[0] */
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
    char env_ldpath[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_ldpath, sizeof(env_ldpath), "LD_LIBRARY_PATH=%s", native_dir);
    char env_proot_tmp[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_tmp, sizeof(env_proot_tmp), "PROOT_TMP_DIR=%s", tmp_dir);
    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    char env_dllpath[WINE_SPIKE_PATH_MAX * 2];
    snprintf(env_dllpath, sizeof(env_dllpath),
             "WINEDLLPATH=%s/lib/wine/x86_64-unix:%s/lib/wine/i386-windows", tree_dir, tree_dir);
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s", tmp_dir);
    char env_path[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);
    char env_display[256] = {0};
    if (display && *display) snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);

    char extra_slots[1024] = {0};
    char *extra_ptrs[16] = {NULL};
    int n_extra = 0;
    if (extra_env && *extra_env) {
        snprintf(extra_slots, sizeof(extra_slots), "%s", extra_env);
        char *tok = strtok(extra_slots, ";");
        while (tok && n_extra < 16) { extra_ptrs[n_extra++] = tok; tok = strtok(NULL, ";"); }
    }

    const char *envp[32];
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
    envp[ei++] = "WINEDEBUG=-all";
    envp[ei++] = "LD_DEBUG=libs";
    if (env_display[0]) envp[ei++] = env_display;
    for (int i = 0; i < n_extra && ei < 31; i++) envp[ei++] = extra_ptrs[i];
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
    int64_t child_pid = (int64_t)pid;

    /* Read stdout/stderr concurrently while waiting, with a deadline. Poll the
     * pipes and also waitpid(WNOHANG) so we notice exit promptly. Periodically
     * snapshot the descendant tree while it is alive. */
    size_t out_len = 0, err_len = 0;
    int status = 0;
    int got_status = 0;
    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    if (timeout_ms > 0) {
        deadline.tv_sec += timeout_ms / 1000;
        deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    }

    int snapshotted = 0;
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

        /* Snapshot the descendant tree once, after a short settle so proot +
         * the loader have started. This captures wine/wineserver while alive. */
        if (!snapshotted) {
            usleep(300000);  /* 300ms settle for proot + loader + child exec */
            snapshot_tree(child_pid, out, native_dir);
            snapshotted = 1;
        }

        /* Re-check exit. */
        if (!got_status) {
            pid_t w = waitpid(pid, &status, WNOHANG);
            if (w == pid) {
                got_status = 1;
            } else if (w < 0 && errno == ECHILD) {
                got_status = 1;  /* already reaped elsewhere */
            }
        }

        /* Done if child exited AND pipes are drained. */
        int pipes_open = (out_pipe[0] >= 0) || (err_pipe[0] >= 0);
        if (got_status && !pipes_open) break;

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
                if (!snapshotted) snapshot_tree(child_pid, out, native_dir);
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
