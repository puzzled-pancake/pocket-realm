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

    /* Derive the symlink tree's lib/ dir from the wine_target path.
     * wine_target is <tree_dir>/bin/wine; the lib path is <tree_dir>/lib.
     * The glibc loader's --library-path must point here (not nativeLibraryDir)
     * because the APK-managed glibc libs are renamed (lib<soname>.so) and the
     * loader resolves DT_NEEDED by exact filename. The symlink tree restores
     * the real SONAME names: tree/lib/libc.so.6 -> native/liblibc.so.6.so.
     *
     * We ALSO include the Wine unix module dir (tree/lib/wine/x86_64-unix)
     * because Wine's loader (ntdll init) dlopen()s its unix .so modules by
     * bare SONAME (ntdll.so), and the glibc loader needs the directory
     * containing those symlinks in its search path. The --library-path arg
     * accepts a colon-separated list. */
    char tree_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tree_dir, sizeof(tree_dir), "%s", wine_target);
    char *bin_pos = strstr(tree_dir, "/bin/");
    if (bin_pos) {
        *bin_pos = '\0';  /* tree_dir is now <tree_dir> */
    } else {
        snprintf(tree_dir, sizeof(tree_dir), "%s", native_dir);
    }

    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path),
             "%s/lib:%s/lib/wine/x86_64-unix", tree_dir, tree_dir);

    /* Build the execve args. --library-path points at the symlink tree dirs. */
    const char *argv[] = {
        loader_path,                           /* argv[0] = the loader itself */
        "--library-path",
        lib_path,                              /* symlink tree lib dirs (real SONAME names) */
        wine_target,                           /* the wine binary (via symlink tree) */
        NULL,
    };

    LOGI("launch_wine: %s --library-path %s %s", loader_path, lib_path, wine_target);

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
        /* WINEDLLPATH: Wine finds its unix .so modules (ntdll.so etc.) by
         * computing a path relative to its own binary location — but since the
         * binary is a symlink to nativeLibraryDir, that path is wrong. WINEDLLPATH
         * overrides the search: Wine looks for <entry>/<arch>/ntdll.so, so we
         * point it at the symlink tree's lib/wine/x86_64-unix dir with the arch
         * stripped (it appends /x86_64 itself). Actually the format is
         * %s%s/ntdll.so where %s=entry, %s=arch — so entry should be the dir
         * CONTAINING the arch dir. We set it to tree/lib/wine so Wine looks for
         * tree/lib/wine/x86_64/ntdll.so... but our files are in x86_64-unix.
         * The simplest working form: set WINEDLLPATH to the exact dir and use
         * a trailing slash so the arch prefix is empty. */
        char env_dllpath[WINE_SPIKE_PATH_MAX + 32];
        snprintf(env_dllpath, sizeof(env_dllpath), "WINEDLLPATH=%s/lib/wine/x86_64-unix", tree_dir);
        envp[ei++] = env_dllpath;
        /* Set HOME to the prefix's parent (filesDir) so Wine can find user data. */
        char env_home[WINE_SPIKE_PATH_MAX + 16];
        snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
        envp[ei++] = env_home;
        /* TMPDIR: Wine needs a writable temp dir for its server socket (/tmp/.wine-<uid>
         * is not writable on Android). Point it at filesDir/runtime/tmp. */
        char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
        snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s/../tmp", prefix_dir);
        envp[ei++] = env_tmpdir;
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
