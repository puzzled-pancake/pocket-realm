/*
 * Fixed-command Linux/glibc launcher for O08.
 *
 * Android's app-domain seccomp profile rejects glibc rtld's legacy access(2)
 * probe. O06 qualified the pinned Termux PRoot loader as the narrow syscall
 * adapter. This runner reuses that immutable APK-managed substrate for native
 * x86_64 MariaDB without inheriting any Wine-specific paths or environment.
 */
#include "wine_spike.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TAG "glibc_program"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TOKEN_MAX 48
#define BLOB_MAX 8192

static volatile sig_atomic_t g_active_glibc_root = 0;

static int mkdir_p(const char *path) {
    char copy[WINE_SPIKE_PATH_MAX];
    if (!path || !*path || strlen(path) >= sizeof(copy)) return -1;
    snprintf(copy, sizeof(copy), "%s", path);
    for (char *p = copy + 1; *p; ++p) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(copy, 0700) != 0 && errno != EEXIST) return -1;
        *p = '/';
    }
    return mkdir(copy, 0700) == 0 || errno == EEXIST ? 0 : -1;
}

static int tokenize_lines(const char *blob, char *copy, size_t copy_cap,
                          char **tokens, int token_cap) {
    if (!blob || !*blob) return 0;
    if (strlen(blob) >= copy_cap) return -1;
    snprintf(copy, copy_cap, "%s", blob);
    int count = 0;
    char *save = NULL;
    for (char *token = strtok_r(copy, "\n", &save); token;
         token = strtok_r(NULL, "\n", &save)) {
        if (!*token) continue;
        if (count >= token_cap) return -1;
        tokens[count++] = token;
    }
    return count;
}

static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void append_tail(char *dst, size_t *length, size_t capacity,
                        const char *src, size_t count) {
    if (capacity < 2 || count == 0) return;
    if (count >= capacity) {
        src += count - (capacity - 1);
        count = capacity - 1;
        *length = 0;
    } else if (*length + count >= capacity) {
        size_t drop = *length + count - capacity + 1;
        memmove(dst, dst + drop, *length - drop);
        *length -= drop;
    }
    memcpy(dst + *length, src, count);
    *length += count;
    dst[*length] = '\0';
}

static long long elapsed_ms(const struct timespec *start) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - start->tv_sec) * 1000LL +
           (now.tv_nsec - start->tv_nsec) / 1000000LL;
}

int wine_spike_cancel_active_glibc_program(void) {
    pid_t root = (pid_t)g_active_glibc_root;
    if (root <= 0) return 0;
    wine_spike_kill_tree_recursive((int64_t)root);
    return 1;
}

int wine_spike_run_glibc_program(const char *native_dir,
                                 const char *executable,
                                 const char *argv0_override,
                                 const char *working_dir,
                                 const char *runtime_root,
                                 const char *library_path,
                                 const char *args_blob,
                                 const char *env_blob,
                                 const char *stdin_path,
                                 int timeout_ms,
                                 int track_as_daemon,
                                 struct wine_spike_proot_run_result *out) {
    if (!native_dir || !executable || !working_dir || !runtime_root ||
        !library_path || !out || timeout_ms < 0) return WINE_SPIKE_ERR_ARGS;
    memset(out, 0, sizeof(*out));
    out->exit_status = -1;

    char proot[WINE_SPIKE_PATH_MAX], loader[WINE_SPIKE_PATH_MAX];
    char proot_loader[WINE_SPIKE_PATH_MAX], proot_loader32[WINE_SPIKE_PATH_MAX];
    snprintf(proot, sizeof(proot), "%s/libproot.so", native_dir);
    snprintf(loader, sizeof(loader), "%s/libld_linux_x86_64.so", native_dir);
    snprintf(proot_loader, sizeof(proot_loader), "%s/libproot_loader.so", native_dir);
    snprintf(proot_loader32, sizeof(proot_loader32), "%s/libproot_loader32.so", native_dir);
    const char *required[] = {proot, loader, proot_loader, executable};
    for (size_t i = 0; i < sizeof(required) / sizeof(required[0]); ++i) {
        if (access(required[i], i == 0 || i == 3 ? X_OK : R_OK) != 0) {
            LOGE("required APK artifact unavailable: %s (%s)", required[i], strerror(errno));
            return WINE_SPIKE_ERR_LAUNCH;
        }
    }

    char tmp[WINE_SPIKE_PATH_MAX], rootfs[WINE_SPIKE_PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s/tmp", runtime_root);
    snprintf(rootfs, sizeof(rootfs), "%s/rootfs", runtime_root);
    if (mkdir_p(tmp) != 0 || mkdir_p(rootfs) != 0) return WINE_SPIKE_ERR_IO;
    const char *root_dirs[] = {"tmp", "proc", "dev", "data", "lib64"};
    for (size_t i = 0; i < sizeof(root_dirs) / sizeof(root_dirs[0]); ++i) {
        char path[WINE_SPIKE_PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s", rootfs, root_dirs[i]);
        if (mkdir_p(path) != 0) return WINE_SPIKE_ERR_IO;
    }

    char args_copy[BLOB_MAX] = {0}, env_copy[BLOB_MAX] = {0};
    char *arg_tokens[TOKEN_MAX] = {0}, *env_tokens[TOKEN_MAX] = {0};
    int arg_count = tokenize_lines(args_blob, args_copy, sizeof(args_copy),
                                   arg_tokens, TOKEN_MAX);
    int env_count = tokenize_lines(env_blob, env_copy, sizeof(env_copy),
                                   env_tokens, TOKEN_MAX);
    if (arg_count < 0 || env_count < 0) return WINE_SPIKE_ERR_ARGS;

    const char *logical = argv0_override && *argv0_override ? argv0_override : executable;
    char bind_native[WINE_SPIKE_PATH_MAX * 2], bind_runtime[WINE_SPIKE_PATH_MAX * 2];
    char bind_tmp[WINE_SPIKE_PATH_MAX * 2], bind_loader[WINE_SPIKE_PATH_MAX * 2];
    snprintf(bind_native, sizeof(bind_native), "%s:%s", native_dir, native_dir);
    snprintf(bind_runtime, sizeof(bind_runtime), "%s:%s", runtime_root, runtime_root);
    snprintf(bind_tmp, sizeof(bind_tmp), "%s:/tmp", tmp);
    snprintf(bind_loader, sizeof(bind_loader), "%s:/lib64/ld-linux-x86-64.so.2", loader);

    const char *argv[128];
    int ai = 0;
    argv[ai++] = proot;
    argv[ai++] = "-v"; argv[ai++] = "0";
    argv[ai++] = "-b"; argv[ai++] = bind_native;
    argv[ai++] = "-b"; argv[ai++] = bind_runtime;
    argv[ai++] = "-b"; argv[ai++] = "/proc";
    argv[ai++] = "-b"; argv[ai++] = "/dev";
    argv[ai++] = "-b"; argv[ai++] = bind_tmp;
    argv[ai++] = "-b"; argv[ai++] = bind_loader;
    argv[ai++] = "-r"; argv[ai++] = rootfs;
    argv[ai++] = "-w"; argv[ai++] = working_dir;
    argv[ai++] = loader;
    argv[ai++] = "--argv0"; argv[ai++] = logical;
    argv[ai++] = "--library-path"; argv[ai++] = library_path;
    argv[ai++] = executable;
    for (int i = 0; i < arg_count; ++i) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    char e_proot_loader[WINE_SPIKE_PATH_MAX + 32];
    char e_proot_loader32[WINE_SPIKE_PATH_MAX + 32];
    char e_proot_tmp[WINE_SPIKE_PATH_MAX + 32];
    char e_ldpath[WINE_SPIKE_PATH_MAX * 3];
    char e_home[WINE_SPIKE_PATH_MAX + 16], e_tmp[WINE_SPIKE_PATH_MAX + 16];
    char e_path[WINE_SPIKE_PATH_MAX + 16];
    snprintf(e_proot_loader, sizeof(e_proot_loader), "PROOT_LOADER=%s", proot_loader);
    snprintf(e_proot_loader32, sizeof(e_proot_loader32), "PROOT_LOADER_32=%s", proot_loader32);
    snprintf(e_proot_tmp, sizeof(e_proot_tmp), "PROOT_TMP_DIR=%s", tmp);
    snprintf(e_ldpath, sizeof(e_ldpath), "LD_LIBRARY_PATH=%s:%s", native_dir, library_path);
    snprintf(e_home, sizeof(e_home), "HOME=%s", runtime_root);
    snprintf(e_tmp, sizeof(e_tmp), "TMPDIR=%s", tmp);
    snprintf(e_path, sizeof(e_path), "PATH=%s", native_dir);
    const char *envp[64];
    int ei = 0;
    envp[ei++] = e_proot_loader; envp[ei++] = e_proot_loader32;
    envp[ei++] = e_proot_tmp; envp[ei++] = e_ldpath;
    envp[ei++] = e_home; envp[ei++] = e_tmp; envp[ei++] = e_path;
    envp[ei++] = "LANG=C.UTF-8"; envp[ei++] = "LC_ALL=C.UTF-8";
    for (int i = 0; i < env_count && ei < 63; ++i) envp[ei++] = env_tokens[i];
    envp[ei] = NULL;

    int out_pipe[2] = {-1, -1}, err_pipe[2] = {-1, -1};
    if (pipe2(out_pipe, O_CLOEXEC) != 0 || pipe2(err_pipe, O_CLOEXEC) != 0) {
        return WINE_SPIKE_ERR_LAUNCH;
    }
    pid_t pid = fork();
    if (pid < 0) return WINE_SPIKE_ERR_LAUNCH;
    if (pid == 0) {
        setpgid(0, 0);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        if (stdin_path && *stdin_path) {
            int input = open(stdin_path, O_RDONLY | O_CLOEXEC);
            if (input < 0 || dup2(input, STDIN_FILENO) < 0) {
                fprintf(stderr, "glibc_program: stdin open failed: %s\n", strerror(errno));
                _exit(126);
            }
            if (input != STDIN_FILENO) close(input);
        } else {
            int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
            if (input >= 0) { dup2(input, STDIN_FILENO); if (input != STDIN_FILENO) close(input); }
        }
        execve(proot, (char *const *)argv, (char *const *)envp);
        fprintf(stderr, "glibc_program: execve proot failed: %s\n", strerror(errno));
        _exit(127);
    }
    setpgid(pid, pid);
    if (track_as_daemon) g_active_glibc_root = (sig_atomic_t)pid;
    close(out_pipe[1]); close(err_pipe[1]);
    set_nonblocking(out_pipe[0]); set_nonblocking(err_pipe[0]);

    size_t stdout_len = 0, stderr_len = 0;
    int status = 0, exited = 0;
    long long exited_at_ms = -1;
    struct timespec started;
    clock_gettime(CLOCK_MONOTONIC, &started);
    while (!exited || out_pipe[0] >= 0 || err_pipe[0] >= 0) {
        struct pollfd fds[2];
        int nfds = 0;
        if (out_pipe[0] >= 0) { fds[nfds].fd = out_pipe[0]; fds[nfds].events = POLLIN; ++nfds; }
        if (err_pipe[0] >= 0) { fds[nfds].fd = err_pipe[0]; fds[nfds].events = POLLIN; ++nfds; }
        if (nfds) poll(fds, nfds, 50);
        for (int i = 0; i < nfds; ++i) {
            if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            char buffer[4096];
            ssize_t n = read(fds[i].fd, buffer, sizeof(buffer));
            if (n > 0) {
                if (fds[i].fd == out_pipe[0])
                    append_tail(out->stdout_buf, &stdout_len, sizeof(out->stdout_buf), buffer, (size_t)n);
                else
                    append_tail(out->stderr_buf, &stderr_len, sizeof(out->stderr_buf), buffer, (size_t)n);
            } else {
                int fd = fds[i].fd;
                close(fd);
                if (fd == out_pipe[0]) out_pipe[0] = -1; else err_pipe[0] = -1;
            }
        }
        if (!exited) {
            pid_t waited = waitpid(pid, &status, WNOHANG);
            if (waited == pid || (waited < 0 && errno == ECHILD)) {
                exited = 1;
                exited_at_ms = elapsed_ms(&started);
            }
        }
        if (!exited && timeout_ms > 0 && elapsed_ms(&started) >= timeout_ms) {
            out->timed_out = 1;
            wine_spike_kill_tree_recursive((int64_t)pid);
            waitpid(pid, &status, 0);
            exited = 1;
            exited_at_ms = elapsed_ms(&started);
        }
        if (exited && exited_at_ms >= 0 && elapsed_ms(&started) - exited_at_ms >= 5000) {
            /* Descendants may briefly retain either descriptor. Bound draining. */
            if (out_pipe[0] >= 0) close(out_pipe[0]);
            if (err_pipe[0] >= 0) close(err_pipe[0]);
            out_pipe[0] = err_pipe[0] = -1;
        }
    }
    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (err_pipe[0] >= 0) close(err_pipe[0]);
    if (track_as_daemon && g_active_glibc_root == (sig_atomic_t)pid) g_active_glibc_root = 0;
    out->exit_status = status;
    out->proot_rc = WINE_SPIKE_OK;
    LOGI("done pid=%d status=%d timeout=%d stdout=%zu stderr=%zu", pid, status,
         out->timed_out, stdout_len, stderr_len);
    return WINE_SPIKE_OK;
}

int wine_spike_run_bionic_program(const char *native_dir,
                                  const char *executable,
                                  const char *argv0_override,
                                  const char *working_dir,
                                  const char *runtime_root,
                                  const char *library_path,
                                  const char *args_blob,
                                  const char *env_blob,
                                  const char *stdin_path,
                                  int timeout_ms,
                                  int track_as_daemon,
                                  struct wine_spike_proot_run_result *out) {
    if (!native_dir || !executable || !working_dir || !runtime_root ||
        !library_path || !out || timeout_ms < 0) return WINE_SPIKE_ERR_ARGS;
    memset(out, 0, sizeof(*out));
    out->exit_status = -1;
    if (access(executable, X_OK) != 0 || access(working_dir, F_OK) != 0) {
        LOGE("required Bionic artifact unavailable: %s (%s)", executable, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    char args_copy[BLOB_MAX] = {0}, env_copy[BLOB_MAX] = {0};
    char *arg_tokens[TOKEN_MAX] = {0}, *env_tokens[TOKEN_MAX] = {0};
    int arg_count = tokenize_lines(args_blob, args_copy, sizeof(args_copy), arg_tokens, TOKEN_MAX);
    int env_count = tokenize_lines(env_blob, env_copy, sizeof(env_copy), env_tokens, TOKEN_MAX);
    if (arg_count < 0 || env_count < 0) return WINE_SPIKE_ERR_ARGS;

    const char *logical = argv0_override && *argv0_override ? argv0_override : executable;
    const char *argv[128];
    int ai = 0;
    argv[ai++] = logical;
    for (int i = 0; i < arg_count; ++i) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    char e_ldpath[WINE_SPIKE_PATH_MAX * 3];
    char e_home[WINE_SPIKE_PATH_MAX + 16], e_tmp[WINE_SPIKE_PATH_MAX + 16];
    char e_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(e_ldpath, sizeof(e_ldpath), "LD_LIBRARY_PATH=%s:%s", library_path, native_dir);
    snprintf(e_home, sizeof(e_home), "HOME=%s", runtime_root);
    snprintf(e_tmp, sizeof(e_tmp), "TMPDIR=%s/run", runtime_root);
    snprintf(e_path, sizeof(e_path), "PATH=%s", native_dir);
    const char *envp[64];
    int ei = 0;
    envp[ei++] = e_ldpath; envp[ei++] = e_home; envp[ei++] = e_tmp;
    envp[ei++] = e_path; envp[ei++] = "LANG=C.UTF-8"; envp[ei++] = "LC_ALL=C.UTF-8";
    for (int i = 0; i < env_count && ei < 63; ++i) envp[ei++] = env_tokens[i];
    envp[ei] = NULL;

    int out_pipe[2] = {-1, -1}, err_pipe[2] = {-1, -1};
    if (pipe2(out_pipe, O_CLOEXEC) != 0 || pipe2(err_pipe, O_CLOEXEC) != 0) {
        if (out_pipe[0] >= 0) { close(out_pipe[0]); close(out_pipe[1]); }
        if (err_pipe[0] >= 0) { close(err_pipe[0]); close(err_pipe[1]); }
        return WINE_SPIKE_ERR_LAUNCH;
    }
    pid_t pid = fork();
    if (pid < 0) {
        close(out_pipe[0]); close(out_pipe[1]); close(err_pipe[0]); close(err_pipe[1]);
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        setpgid(0, 0);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);
        if (chdir(working_dir) != 0) {
            fprintf(stderr, "bionic_program: chdir failed: %s\n", strerror(errno));
            _exit(126);
        }
        if (stdin_path && *stdin_path) {
            int input = open(stdin_path, O_RDONLY | O_CLOEXEC);
            if (input < 0 || dup2(input, STDIN_FILENO) < 0) {
                fprintf(stderr, "bionic_program: stdin open failed: %s\n", strerror(errno));
                _exit(126);
            }
            if (input != STDIN_FILENO) close(input);
        } else {
            int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
            if (input >= 0) { dup2(input, STDIN_FILENO); if (input != STDIN_FILENO) close(input); }
        }
        execve(executable, (char *const *)argv, (char *const *)envp);
        fprintf(stderr, "bionic_program: execve failed: %s\n", strerror(errno));
        _exit(127);
    }
    setpgid(pid, pid);
    if (track_as_daemon) g_active_glibc_root = (sig_atomic_t)pid;
    close(out_pipe[1]); close(err_pipe[1]);
    set_nonblocking(out_pipe[0]); set_nonblocking(err_pipe[0]);

    size_t stdout_len = 0, stderr_len = 0;
    int status = 0, exited = 0;
    long long exited_at_ms = -1;
    struct timespec started;
    clock_gettime(CLOCK_MONOTONIC, &started);
    while (!exited || out_pipe[0] >= 0 || err_pipe[0] >= 0) {
        struct pollfd fds[2];
        int nfds = 0;
        if (out_pipe[0] >= 0) { fds[nfds].fd = out_pipe[0]; fds[nfds].events = POLLIN; ++nfds; }
        if (err_pipe[0] >= 0) { fds[nfds].fd = err_pipe[0]; fds[nfds].events = POLLIN; ++nfds; }
        if (nfds) poll(fds, nfds, 50);
        for (int i = 0; i < nfds; ++i) {
            if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            char buffer[4096];
            ssize_t n = read(fds[i].fd, buffer, sizeof(buffer));
            if (n > 0) {
                if (fds[i].fd == out_pipe[0])
                    append_tail(out->stdout_buf, &stdout_len, sizeof(out->stdout_buf), buffer, (size_t)n);
                else
                    append_tail(out->stderr_buf, &stderr_len, sizeof(out->stderr_buf), buffer, (size_t)n);
            } else {
                int fd = fds[i].fd;
                close(fd);
                if (fd == out_pipe[0]) out_pipe[0] = -1; else err_pipe[0] = -1;
            }
        }
        if (!exited) {
            pid_t waited = waitpid(pid, &status, WNOHANG);
            if (waited == pid || (waited < 0 && errno == ECHILD)) {
                exited = 1;
                exited_at_ms = elapsed_ms(&started);
            }
        }
        if (!exited && timeout_ms > 0 && elapsed_ms(&started) >= timeout_ms) {
            out->timed_out = 1;
            wine_spike_kill_tree_recursive((int64_t)pid);
            waitpid(pid, &status, 0);
            exited = 1;
            exited_at_ms = elapsed_ms(&started);
        }
        if (exited && exited_at_ms >= 0 && elapsed_ms(&started) - exited_at_ms >= 5000) {
            if (out_pipe[0] >= 0) close(out_pipe[0]);
            if (err_pipe[0] >= 0) close(err_pipe[0]);
            out_pipe[0] = err_pipe[0] = -1;
        }
    }
    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (err_pipe[0] >= 0) close(err_pipe[0]);
    if (track_as_daemon && g_active_glibc_root == (sig_atomic_t)pid) g_active_glibc_root = 0;
    out->exit_status = status;
    out->proot_rc = WINE_SPIKE_OK;
    LOGI("bionic done pid=%d status=%d timeout=%d stdout=%zu stderr=%zu", pid, status,
         out->timed_out, stdout_len, stderr_len);
    return WINE_SPIKE_OK;
}
