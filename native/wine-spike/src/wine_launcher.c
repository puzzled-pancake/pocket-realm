/*
 * native/wine-spike/src/wine_launcher.c
 *
 * Launch Wine by directly invoking the APK-managed glibc loader.
 *
 *   execve(<native_dir>/libld_linux_x86_64.so,
 *          ["--library-path", <native_dir>, <wine_target>],
 *          env)
 *
 * This BYPASSES Wine's embedded PT_INTERP — direct loader invocation does not
 * make the embedded interpreter path resolve; it substitutes our APK-managed
 * loader. The effective loader is then PROVEN via /proc/<pid>/maps (wine_probe.c).
 *
 * The child runs in the Linux/glibc namespace (separate from Android/Bionic).
 * This library (libwine_spike.so) stays in the Bionic namespace — it only
 * fork/execve's the child.
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

int wine_spike_launch_wine(const char *native_dir,
                           const char *wine_target,
                           const char *prefix_dir,
                           const char *display,
                           int64_t *out_pid) {
    if (!native_dir || !wine_target || !prefix_dir || !out_pid)
        return WINE_SPIKE_ERR_ARGS;

    char loader_path[WINE_SPIKE_PATH_MAX];
    snprintf(loader_path, sizeof(loader_path), "%s/libld_linux_x86_64.so", native_dir);

    /* Verify the loader exists (it's APK-managed, so it should be in nativeLibraryDir). */
    if (access(loader_path, X_OK) != 0) {
        LOGE("loader not found/executable: %s: %s", loader_path, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    /* Build the execve args. */
    const char *argv[] = {
        loader_path,                           /* argv[0] = the loader itself */
        "--library-path",
        native_dir,                            /* where the glibc closure lives */
        wine_target,                           /* the wine binary (via symlink tree) */
        NULL,
    };

    LOGI("launch_wine: %s --library-path %s %s", loader_path, native_dir, wine_target);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        /* Child: set up the environment for the glibc namespace. */
        char env_prefix[WINE_SPIKE_PATH_MAX + 32];
        snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);

        /* Build a minimal environment. We do NOT inherit the Android environment
         * (Bionic paths would confuse glibc). Wine needs WINEPREFIX + PATH + HOME
         * + DISPLAY (if a display is configured). */
        const char *envp[16];
        int ei = 0;
        envp[ei++] = env_prefix;
        /* WINEDEBUG helps diagnose loader/PE resolution during the spike. */
        envp[ei++] = "WINEDEBUG=+loaddll,+module,+relay";
        /* Set HOME to the prefix's parent (filesDir) so Wine can find user data. */
        char env_home[WINE_SPIKE_PATH_MAX + 16];
        snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
        envp[ei++] = env_home;
        /* PATH: only the glibc bin dir (where the loader can find wine tools). */
        char env_path[WINE_SPIKE_PATH_MAX + 32];
        snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);
        envp[ei++] = env_path;
        /* DISPLAY: if provided (for the X-server harness in S-3). */
        if (display && *display) {
            static char env_display[256];
            snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);
            envp[ei++] = env_display;
        }
        /* WINEDLLOVERRIDES: force builtin modules (proves the PE cache is used). */
        envp[ei++] = "WINEDLLOVERRIDES=msvcrt,b=n;kernelbase=b";
        envp[ei] = NULL;

        /* Redirect child stdout/stderr to a log file for structured output. */
        /* (The parent reads these for POCKET_SELFTEST_* markers.) */

        execve(loader_path, (char *const *)argv, (char *const *)envp);
        /* If execve returns, it failed. */
        LOGE("execve failed: %s", strerror(errno));
        _exit(127);
    }

    /* Parent: child is running with PID = pid. */
    *out_pid = pid;
    LOGI("launch_wine: child pid=%lld", (long long)pid);
    return WINE_SPIKE_OK;
}
