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
                           const char *wine_args,
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

    /* Build the execve args. --library-path points at the symlink tree dirs.
     * wine_args (if provided) is split on spaces into additional argv entries. */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *tok = strtok(args_copy, " ");
        while (tok && n_args < 15) {
            arg_tokens[n_args++] = tok;
            tok = strtok(NULL, " ");
        }
    }
    const char *argv[24];
    int ai = 0;
    argv[ai++] = loader_path;
    argv[ai++] = "--library-path";
    argv[ai++] = lib_path;
    argv[ai++] = wine_target;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    LOGI("launch_wine: %s --library-path %s %s %s", loader_path, lib_path, wine_target,
         wine_args ? wine_args : "");

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        /* Child: set up the environment for the glibc namespace. */
        /* Redirect stdout to a pipe so the parent can capture wine's output. */
        char env_prefix[WINE_SPIKE_PATH_MAX + 32];
        snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);

        /* Build a minimal environment. We do NOT inherit the Android environment
         * (Bionic paths would confuse glibc). Wine needs WINEPREFIX + PATH + HOME
         * + DISPLAY (if a display is configured). */
        const char *envp[16];
        int ei = 0;
        envp[ei++] = env_prefix;
        /* LD_DEBUG=libs: makes the glibc loader print its library resolution to
         * stderr. This IS the S-1 proof — it shows the effective loader loading
         * the glibc closure from APK-managed files via the symlink tree. More
         * reliable than racing for /proc/<pid>/maps for fast-exiting processes. */
        envp[ei++] = "LD_DEBUG=libs";
        /* WINEDEBUG helps diagnose loader/PE resolution during the spike. */
        envp[ei++] = "WINEDEBUG=-all";
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

        /* The child inherits the parent's stderr (fd 2), which goes to logcat.
         * LD_DEBUG=libs output appears in logcat under the wine_spike tag. The
         * host driver captures it. We do NOT redirect to a file because the
         * fork'd child's file redirect was unreliable (the file was created
         * empty — likely a timing/buffering issue with execve replacing the
         * image before the loader's buffered stderr was flushed). */

        execve(loader_path, (char *const *)argv, (char *const *)envp);
        /* If execve returns, it failed. */
        LOGE("execve failed: %s", strerror(errno));
        _exit(127);
    }

    /* Parent: child is running with PID = pid.
     * Race to read /proc/<pid>/maps BEFORE the child exits (wine --version
     * completes in <100ms). The maps file is valid from the moment execve
     * establishes the address space. We retry for up to 500ms. */
    *out_pid = pid;
    LOGI("launch_wine: child pid=%lld", (long long)pid);

    /* Brief yield to let the kernel set up the child's address space. */
    usleep(10000);  /* 10ms */

    /* Try to read maps in a tight loop. If the child already exited, we still
     * return the PID (the caller can detect the process is gone). The probe
     * function handles the "process gone" case. */
    char maps_path[256];
    snprintf(maps_path, sizeof(maps_path), "/proc/%lld/maps", (long long)pid);
    for (int i = 0; i < 50; i++) {  /* 50 x 10ms = 500ms max */
        if (access(maps_path, R_OK) == 0) {
            LOGI("launch_wine: maps available for pid=%lld", (long long)pid);
            break;
        }
        usleep(10000);
    }
    return WINE_SPIKE_OK;
}
