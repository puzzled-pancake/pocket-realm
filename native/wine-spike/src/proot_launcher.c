/*
 * native/wine-spike/src/proot_launcher.c
 *
 * S-5(b): proot fallback launch path.
 *
 * The S-1 diagnostic PROVED the direct glibc-loader invocation is killed by
 * Android's untrusted_app seccomp filter on syscall 21 (access), si_code=1
 * (SYS_SECCOMP). The S-5(a) Bionic trampoline hit the identical trap. No
 * GLIBC_TUNABLES suppresses the loader's access() probing.
 *
 * proot (termux/proot@a89b3732, built Bionic/PIE) runs in the Android/Bionic
 * namespace — it does NOT call access(2) itself (Bionic uses faccessat). It
 * ptrace-traces the glibc-namespace child (the loader + Wine) and translates
 * the child's blocked syscalls: access(2) -> faccessat(2). This is the standard
 * Wine-on-Android solution.
 *
 * CRITICAL: proot does NOT replace the effective loader. The traced child still
 * execve's the APK-managed glibc loader as its effective loader — proot just
 * intercepts syscalls. So /proc/<pid>/maps still shows libld_linux_x86_64.so
 * from nativeLibraryDir as the loader, satisfying S-1's acceptance criterion.
 *
 * proot is itself an APK-managed ELF (libproot.so, a PIE program despite the
 * .so name). It depends on libtalloc.so (also APK-managed). We set
 * LD_LIBRARY_PATH=native_dir so proot's Bionic loader finds libtalloc.so.
 *
 * The /tmp blocker: wineserver hardcodes /tmp/.wine-<uid> for its socket, which
 * is not writable on Android. proot's -b (bind) flag maps a path inside the
 * traced child's namespace: `-b <app_tmp>:/tmp` makes the child's /tmp resolve
 * to the app's writable filesDir/runtime/tmp. This is the namespace mechanism
 * for the /tmp path (TMPDIR alone does not change wineserver's hardcoded path).
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <fcntl.h>
#include <stdio.h>
#include <errno.h>
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

int wine_spike_launch_wine_via_proot(const char *native_dir,
                                     const char *wine_target,
                                     const char *prefix_dir,
                                     const char *display,
                                     const char *wine_args,
                                     const char *extra_env,
                                     int64_t *out_pid) {
    if (!native_dir || !wine_target || !prefix_dir || !out_pid)
        return WINE_SPIKE_ERR_ARGS;

    char proot_path[WINE_SPIKE_PATH_MAX];
    snprintf(proot_path, sizeof(proot_path), "%s/libproot.so", native_dir);
    if (access(proot_path, X_OK) != 0) {
        LOGE("proot: not found/executable: %s: %s", proot_path, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    char loader_path[WINE_SPIKE_PATH_MAX];
    snprintf(loader_path, sizeof(loader_path), "%s/libld_linux_x86_64.so", native_dir);

    /* Resolve wine_target to its real APK-managed path. wine_target is typically
     * a symlink in filesDir (e.g. tree/bin/wine -> native_dir/libwine_preloader.so).
     * The app domain allows execve of nativeLibraryDir files but blocks execve
     * of filesDir symlinks (EACCES). proot execves the initial program via the
     * kernel, so it MUST receive the resolved nativeLibraryDir path, not the
     * symlink. */
    char wine_real[WINE_SPIKE_PATH_MAX];
    ssize_t rl = readlink(wine_target, wine_real, sizeof(wine_real) - 1);
    if (rl > 0) {
        wine_real[rl] = '\0';
        /* If the symlink target is relative, resolve against the symlink's dir. */
        if (wine_real[0] != '/') {
            char dir[WINE_SPIKE_PATH_MAX];
            snprintf(dir, sizeof(dir), "%s", wine_target);
            char *slash = strrchr(dir, '/');
            if (slash) {
                *(slash + 1) = '\0';
                char abs[WINE_SPIKE_PATH_MAX];
                snprintf(abs, sizeof(abs), "%s%s", dir, wine_real);
                snprintf(wine_real, sizeof(wine_real), "%s", abs);
            }
        }
        LOGI("proot: resolved wine_target %s -> %s", wine_target, wine_real);
    } else {
        /* Not a symlink (already a real path); use as-is. */
        snprintf(wine_real, sizeof(wine_real), "%s", wine_target);
    }

    /* Derive lib_path + tree_dir + tmp_dir as the other launchers do. */
    char tree_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tree_dir, sizeof(tree_dir), "%s", wine_target);
    char *bin_pos = strstr(tree_dir, "/bin/");
    if (bin_pos) *bin_pos = '\0';
    else snprintf(tree_dir, sizeof(tree_dir), "%s", native_dir);
    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path), "%s/lib:%s/lib/wine/x86_64-unix", tree_dir, tree_dir);

    /* The app's writable tmp dir (filesDir/runtime/tmp). We derive it from the
     * prefix dir's sibling (prefix is filesDir/runtime/wine-prefix, tmp is
     * filesDir/runtime/tmp). Resolve the .. to an absolute canonical path:
     * proot does NOT resolve '..' in PROOT_TMP_DIR before checking writability,
     * and it binds the path verbatim into the child namespace. */
    char tmp_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tmp_dir, sizeof(tmp_dir), "%s/../tmp", prefix_dir);
    /* Strip the "/wine-prefix/../tmp" → "<runtime>/tmp". Find the last
     * "/wine-prefix" and replace from there. */
    char *wp = strstr(tmp_dir, "/wine-prefix/../tmp");
    if (wp) {
        /* tmp_dir is "<runtime>/wine-prefix/../tmp"; we want "<runtime>/tmp". */
        char runtime[WINE_SPIKE_PATH_MAX];
        size_t rlen = wp - tmp_dir;
        if (rlen < sizeof(runtime)) {
            memcpy(runtime, tmp_dir, rlen);
            runtime[rlen] = '\0';
            snprintf(tmp_dir, sizeof(tmp_dir), "%s/tmp", runtime);
        }
    }

    /* Build wine_args tokens. */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *tok = strtok(args_copy, " ");
        while (tok && n_args < 15) { arg_tokens[n_args++] = tok; tok = strtok(NULL, " "); }
    }

    /* proot argv (loader-as-guest-command form):
     *
     *   proot -v 5 \
     *     -b <tmp_dir>:/tmp \                          # wineserver /tmp socket
     *     -r / --link2symlink \
     *     <apk-loader> --library-path <tree-libs> <wine> [wine_args...]
     *
     * The APK-managed glibc loader is the GUEST COMMAND (argv after proot's
     * options). proot injects it via its in-tracee helper (libproot_loader.so,
     * pinned via PROOT_LOADER). The --library-path points at the symlink tree
     * dirs (real SONAME names), so the loader resolves the glibc closure the
     * same way the direct path does. proot then intercepts the loader's
     * syscalls and translates access(2) -> faccessat(2), defeating the seccomp
     * trap that kills the direct path.
     *
     * This form is used (rather than running wine directly with a rootfs-bind)
     * because it matches the invocation PROVEN to work under run-as: the loader
     * is the explicit program, --library-path carries the closure path, and no
     * kernel PT_INTERP resolution is required.
     *
     * -v 5 enables verbose proot tracing so we can capture the exact syscall
     * evidence (which calls proot intercepts, any error before exec).
     */
    const char *argv[40];
    int ai = 0;
    argv[ai++] = proot_path;
    argv[ai++] = "-v"; argv[ai++] = "5";  /* verbose: capture interception evidence */

    /* -b <tmp_dir>:/tmp — wineserver hardcodes /tmp/.wine-<uid>; the bind makes
     * it resolve to the app's writable filesDir/runtime/tmp. This is the
     * namespace mechanism for the /tmp path (TMPDIR alone does not change it). */
    char bind_tmp[WINE_SPIKE_PATH_MAX * 2];
    snprintf(bind_tmp, sizeof(bind_tmp), "%s:/tmp", tmp_dir);
    argv[ai++] = "-b"; argv[ai++] = bind_tmp;

    argv[ai++] = "-r";
    argv[ai++] = "/";
    argv[ai++] = "--link2symlink";

    /* The guest command: the APK-managed glibc loader, invoked exactly as the
     * direct path does. --library-path points at the symlink tree (real SONAME
     * names): tree/lib + tree/lib/wine/x86_64-unix. */
    argv[ai++] = loader_path;
    argv[ai++] = "--library-path";
    argv[ai++] = lib_path;
    argv[ai++] = wine_real;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    /* Environment. PROOT_LOADER + PROOT_LOADER_32 are the critical additions:
     * they point proot at the APK-managed helper loader (libproot_loader.so in
     * nativeLibraryDir, immutable +x) so proot does NOT extract its embedded
     * loader to PROOT_TMP_DIR (a noexec writable mount in the app domain).
     * Without this, proot's extract_loader() writes loader.exe to PROOT_TMP_DIR,
     * fchmods it +x, then the access(X_OK) check fails — or worse, the exec of
     * a writable-storage file is denied. */
    char env_proot_loader[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_loader, sizeof(env_proot_loader),
             "PROOT_LOADER=%s/libproot_loader.so", native_dir);
    char env_proot_loader32[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_loader32, sizeof(env_proot_loader32),
             "PROOT_LOADER_32=%s/libproot_loader32.so", native_dir);
    char env_ldpath[WINE_SPIKE_PATH_MAX + 32];
    /* LD_LIBRARY_PATH: proot's Bionic loader needs native_dir to find
     * libtalloc.so. The traced glibc child uses --library-path (not
     * LD_LIBRARY_PATH) for its closure, so we don't pollute the child's search. */
    snprintf(env_ldpath, sizeof(env_ldpath), "LD_LIBRARY_PATH=%s", native_dir);
    char env_proot_tmp[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_proot_tmp, sizeof(env_proot_tmp), "PROOT_TMP_DIR=%s", tmp_dir);
    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    char env_dllpath[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_dllpath, sizeof(env_dllpath),
             "WINEDLLPATH=%s/lib/wine/x86_64-unix", tree_dir);
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s", tmp_dir);
    char env_path[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);

    const char *envp[24];
    int ei = 0;
    envp[ei++] = env_proot_loader;   /* APK-managed helper loader (64-bit) */
    envp[ei++] = env_proot_loader32; /* APK-managed helper loader (32-bit) */
    envp[ei++] = env_ldpath;         /* proot finds libtalloc.so via this */
    envp[ei++] = env_proot_tmp;      /* proot's own temp files (f2fs probe, etc.) */
    envp[ei++] = env_prefix;
    envp[ei++] = env_dllpath;
    envp[ei++] = env_home;
    envp[ei++] = env_tmpdir;
    envp[ei++] = env_path;
    envp[ei++] = "WINEDEBUG=-all";
    /* LD_DEBUG=libs inside the traced child: proot passes env through, so the
     * glibc loader's library-resolution output still appears on stderr. This is
     * the S-1 proof for the proot path. */
    envp[ei++] = "LD_DEBUG=libs";
    if (display && *display) {
        static char env_display[256];
        snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);
        envp[ei++] = env_display;
    }
    /* Optional extra env (S-5 tunables etc.). Copied into a stable buffer. */
    char extra_slots[1024];
    if (extra_env && *extra_env) {
        size_t len = strlen(extra_env);
        if (len >= sizeof(extra_slots)) len = sizeof(extra_slots) - 1;
        memcpy(extra_slots, extra_env, len);
        extra_slots[len] = '\0';
        char *tok = strtok(extra_slots, ";");
        while (tok && ei < (int)(sizeof(envp) / sizeof(envp[0])) - 1) {
            envp[ei++] = tok;
            tok = strtok(NULL, ";");
        }
    }
    envp[ei] = NULL;

    LOGI("proot: exec %s -v 5 -b %s -r / --link2symlink %s --library-path %s %s ... "
         "(PROOT_LOADER=%s/libproot_loader.so)",
         proot_path, bind_tmp, loader_path, lib_path, wine_real, native_dir);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("proot: fork: %s", strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        execve(proot_path, (char *const *)argv, (char *const *)envp);
        LOGE("proot: execve failed: %s", strerror(errno));
        _exit(127);
    }
    *out_pid = pid;
    LOGI("proot: child pid=%lld", (long long)pid);
    usleep(10000);
    return WINE_SPIKE_OK;
}
