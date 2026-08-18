/*
 * native/wine-spike/src/trampoline_launcher.c
 *
 * S-5(a): APK-packaged Bionic trampoline launch path.
 *
 * wine_spike_launch_wine (wine_launcher.c) fork+execve's the glibc loader
 * directly from libwine_spike.so's own child. This file provides the
 * ALTERNATIVE path required by the S-5 fallback ordering: fork+execve a
 * SEPARATE APK-managed Bionic PIE (libwine_trampoline.so), which in turn
 * execs the glibc loader.
 *
 * Why a separate PIE:
 *   The trampoline is a normal Android/Bionic program compiled as PIE with the
 *   NDK. It runs as a freshly-execve'd process (not as a dlopen'd library),
 *   so its startup is a clean Bionic init rather than a fork out of an already
 *   running app process. If the SIGSYS on the direct path is due to something
 *   about exec'ing the glibc ELF from THIS process (e.g. an execve-target
 *   check that keys off the caller's address-space layout or loaded-lib set),
 *   the trampoline path will behave differently and we'll see it in the exit
 *   code + the diagnostic.
 *
 * The trampoline binary re-execs the SAME glibc loader with the SAME argv/env
 * as the direct path — so the effective loader, if this path succeeds, is still
 * the APK-managed libld_linux_x86_64.so. The trampoline is just a launch shim;
 * it never links glibc and never runs in the glibc namespace.
 *
 * Evidence from this path is recorded SEPARATELY from the packaging
 * control that does not exec Wine) and from the direct S-1 path.
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

int wine_spike_launch_wine_via_trampoline(const char *native_dir,
                                          const char *wine_target,
                                          const char *prefix_dir,
                                          const char *display,
                                          const char *wine_args,
                                          int64_t *out_pid) {
    return wine_spike_launch_wine_via_trampoline_ex(native_dir, wine_target, prefix_dir,
                                                    display, wine_args, NULL, out_pid);
}

int wine_spike_launch_wine_via_trampoline_ex(const char *native_dir,
                                             const char *wine_target,
                                             const char *prefix_dir,
                                             const char *display,
                                             const char *wine_args,
                                             const char *extra_env,
                                             int64_t *out_pid) {
    if (!native_dir || !wine_target || !prefix_dir || !out_pid)
        return WINE_SPIKE_ERR_ARGS;

    char tramp_path[WINE_SPIKE_PATH_MAX];
    snprintf(tramp_path, sizeof(tramp_path), "%s/libwine_trampoline.so", native_dir);
    if (access(tramp_path, X_OK) != 0) {
        LOGE("trampoline: not found/executable: %s: %s", tramp_path, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    char loader_path[WINE_SPIKE_PATH_MAX];
    snprintf(loader_path, sizeof(loader_path), "%s/libld_linux_x86_64.so", native_dir);

    /* Derive lib_path exactly as the direct path does. */
    char tree_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tree_dir, sizeof(tree_dir), "%s", wine_target);
    char *bin_pos = strstr(tree_dir, "/bin/");
    if (bin_pos) *bin_pos = '\0';
    else snprintf(tree_dir, sizeof(tree_dir), "%s", native_dir);
    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path), "%s/lib:%s/lib/wine/x86_64-unix", tree_dir, tree_dir);

    /* argv[0] = trampoline; it re-execvs argv[1..] = the loader invocation.
     * We pass the loader invocation as a fixed prefix so the trampoline code is
     * trivial and auditable. */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *tok = strtok(args_copy, " ");
        while (tok && n_args < 15) { arg_tokens[n_args++] = tok; tok = strtok(NULL, " "); }
    }

    const char *argv[28];
    int ai = 0;
    argv[ai++] = tramp_path;        /* argv[0] = the trampoline binary */
    argv[ai++] = loader_path;       /* argv[1] = loader to exec */
    argv[ai++] = "--library-path";  /* argv[2..] forwarded to loader */
    argv[ai++] = lib_path;
    argv[ai++] = wine_target;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    /* Environment: same as direct path. */
    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    char env_dllpath[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_dllpath, sizeof(env_dllpath), "WINEDLLPATH=%s/lib/wine/x86_64-unix", tree_dir);
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s/../tmp", prefix_dir);
    char env_path[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);
    const char *envp[24];
    int ei = 0;
    envp[ei++] = env_prefix;
    envp[ei++] = env_dllpath;
    envp[ei++] = env_home;
    envp[ei++] = env_tmpdir;
    envp[ei++] = env_path;
    envp[ei++] = "WINEDEBUG=-all";
    if (display && *display) {
        static char env_display[256];
        snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);
        envp[ei++] = env_display;
    }
    /* Optional extra env (S-5: GLIBC_TUNABLES for rseq/clone3). Copied into a
     * stable buffer so the pointers survive fork+execve. */
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

    LOGI("trampoline: exec %s %s --library-path %s %s ...",
         tramp_path, loader_path, lib_path, wine_target);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("trampoline: fork: %s", strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        execve(tramp_path, (char *const *)argv, (char *const *)envp);
        LOGE("trampoline: execve failed: %s", strerror(errno));
        _exit(127);
    }
    *out_pid = pid;
    LOGI("trampoline: child pid=%lld", (long long)pid);
    usleep(10000);
    return WINE_SPIKE_OK;
}
