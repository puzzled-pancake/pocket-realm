/*
 * native/wine-spike/include/wine_spike.h
 *
 * O06 Phase-1 Wine feasibility spike — C interface.
 *
 * This library runs in the Android/Bionic namespace. It builds the symlink-only
 * logical Wine tree in filesDir (pointing at APK-managed ELFs in nativeLibraryDir),
 * launches Wine by directly invoking the APK-managed glibc loader, probes
 * /proc/<pid>/maps to prove the effective loader, and materializes the PE cache
 * with SHA-256 verification.
 *
 * Two namespaces are deliberately separate:
 *   - Android/Bionic: this library + the app (libc/libm/libdl/liblog from Android)
 *   - Linux/glibc: Wine + wineserver + the glibc closure (never mixed)
 */
#ifndef POCKET_WINE_SPIKE_H
#define POCKET_WINE_SPIKE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Result codes for spike operations. */
#define WINE_SPIKE_OK           0
#define WINE_SPIKE_ERR_ARGS     1   /* bad argument (null, empty, too long) */
#define WINE_SPIKE_ERR_IO       2   /* filesystem I/O failure */
#define WINE_SPIKE_ERR_LAUNCH   3   /* fork/execve failure */
#define WINE_SPIKE_ERR_TIMEOUT  4   /* child did not produce expected output in time */
#define WINE_SPIKE_ERR_VERIFY   5   /* /proc maps proof failed (loader not the APK one) */

/* Maximum sizes for path/buffer fields. */
#define WINE_SPIKE_PATH_MAX     1024
#define WINE_SPIKE_LINE_MAX     2048
#define WINE_SPIKE_CHILDREN_MAX 16   /* max native children to probe (wine + wineserver + ...) */

/*
 * Build the symlink-only logical Wine tree in <tree_dir>.
 *
 * For each entry in the staging manifest, creates a symlink:
 *   <tree_dir>/<logical_path> -> <native_dir>/<jniLib_name>
 *
 * Idempotent: removes existing symlinks/files before creating new ones.
 * The tree contains NO ELF regular files — every target is APK-managed.
 *
 * Args:
 *   tree_dir    — absolute path to filesDir/runtime/wine-tree/
 *   native_dir  — absolute path to nativeLibraryDir
 *   manifest    — the staging-manifest.json content (JSON string)
 *
 * Returns WINE_SPIKE_OK on success.
 */
int wine_spike_build_symlink_tree(const char *tree_dir,
                                  const char *native_dir,
                                  const char *manifest_json);

/*
 * Launch Wine via the APK-managed glibc loader (direct invocation, bypassing
 * Wine's embedded PT_INTERP).
 *
 *   execve(<native_dir>/libld_linux_x86_64.so,
 *          ["--library-path", <native_dir>, <wine_target>],
 *          env)
 *
 * The wine_target is resolved via the symlink tree (e.g. tree/bin/wine).
 * WINEPREFIX is set to <prefix_dir>. The child's PID is returned.
 *
 * Args:
 *   native_dir  — nativeLibraryDir (where libld_linux_x86_64.so lives)
 *   wine_target — absolute path to the wine binary (via symlink tree)
 *   prefix_dir  — WINEPREFIX (filesDir/runtime/wine-prefix/)
 *   display     — DISPLAY env (e.g. "127.0.0.1:0" or empty for no display)
 *   out_pid     — receives the child PID
 *
 * Returns WINE_SPIKE_OK on success, out_pid set.
 */
int wine_spike_launch_wine(const char *native_dir,
                           const char *wine_target,
                           const char *prefix_dir,
                           const char *display,
                           const char *wine_args,   /* extra args after wine_target (e.g. "--version") */
                           int64_t *out_pid);

/*
 * Extended launch with an optional extra_env string ("KEY=VAL;KEY=VAL;...").
 * Used by the S-5 fallback to inject GLIBC_TUNABLES (e.g. to disable rseq or
 * force clone→clone3 fallback) without widening the per-call env. The entries
 * are copied into a stable child-stack buffer before execve. May be NULL/empty.
 */
int wine_spike_launch_wine_ex(const char *native_dir,
                              const char *wine_target,
                              const char *prefix_dir,
                              const char *display,
                              const char *wine_args,
                              const char *extra_env,
                              int64_t *out_pid);

/*
 * Probe /proc/<pid>/maps to extract the effective dynamic loader path and
 * verify it is the APK-managed loader.
 *
 * Reads /proc/<pid>/maps, finds the loader mapping (the entry whose pathname
 * contains "ld-linux" or "ld-linux-x86-64"), and checks it starts with
 * <expected_native_dir>.
 *
 * Args:
 *   pid                  — the process to probe
 *   expected_native_dir  — nativeLibraryDir (the APK-managed dir)
 *   out_loader_path      — receives the actual loader path from maps
 *   loader_path_cap      — capacity of out_loader_path
 *   out_interp           — receives /proc/<pid>/interp content (if readable)
 *   interp_cap           — capacity of out_interp
 *
 * Returns WINE_SPIKE_OK if the effective loader is APK-managed.
 */
int wine_spike_probe_loader(int64_t pid,
                            const char *expected_native_dir,
                            char *out_loader_path, size_t loader_path_cap,
                            char *out_interp, size_t interp_cap);

/*
 * Count how many mapped libraries in /proc/<pid>/maps come from
 * <expected_native_dir> vs elsewhere. Used to prove the glibc closure is
 * APK-managed (not leaking to system libs).
 *
 * Returns the count of APK-managed mappings, or -1 on error.
 */
int wine_spike_count_apk_mappings(int64_t pid, const char *expected_native_dir);

/*
 * Enumerate native child PIDs of a parent (for S-1: wine spawns wineserver).
 * Writes up to <cap> PIDs into <out_pids>. Returns the number written.
 */
int wine_spike_enum_children(int64_t parent_pid, int64_t *out_pids, int cap);

/*
 * Materialize the PE cache: extract Wine-owned PE modules from APK assets into
 * <cache_dir>, verifying each file's SHA-256 against the manifest. Atomically
 * replaces mismatched files. Idempotent (skips already-verified files).
 *
 * This is called via JNI with an AssetManager, but the C side operates on
 * already-extracted asset files (the JNI shim handles asset extraction).
 *
 * Args:
 *   cache_dir       — filesDir/runtime/wine-pe-cache/
 *   manifest_json   — the wine-pe-manifest.json content
 *   assets_dir      — the extracted assets directory (on the filesystem)
 *
 * Returns WINE_SPIKE_OK if all modules verify.
 */
int wine_spike_materialize_pe_cache(const char *cache_dir,
                                    const char *manifest_json,
                                    const char *assets_dir);

/*
 * Materialize + connect to the logical Wine tree (S-2 path fix). Same as
 * wine_spike_materialize_pe_cache, but additionally — for each manifest entry
 * that has a "logical_path" — installs a symlink at
 *   <tree_dir>/<logical_path> -> <cache_dir>/<asset_path>
 * so Wine can resolve builtin PE modules at their expected paths. Pass NULL for
 * tree_dir to get the legacy behavior (cache files only, no tree symlinks).
 *
 * This corrects the earlier bug where pe_cache.c ignored logical_path and
 * materialized files under wine-pe/... with nothing connecting them to the
 * logical Wine tree. The symlink-only tree property is preserved: no ELF
 * regular file lives in writable storage; these are PE guest-code files.
 */
int wine_spike_materialize_pe_cache_into_tree(const char *cache_dir,
                                              const char *manifest_json,
                                              const char *assets_dir,
                                              const char *tree_dir);

/*
 * Verify the PE cache: re-check every file's SHA-256 against the manifest.
 * Called before each Wine launch. Returns WINE_SPIKE_OK if all match,
 * WINE_SPIKE_ERR_VERIFY if any mismatch (caller should re-materialize).
 */
int wine_spike_verify_pe_cache(const char *cache_dir, const char *manifest_json);

/*
 * Resolve the cache path for a given PE module asset basename (S-2 mismatch
 * repair test). Fills <out> with "<cache_dir>/<asset_path>" for the first
 * manifest entry whose asset basename matches <asset_name>. Returns
 * WINE_SPIKE_OK on match, WINE_SPIKE_ERR_IO if no match.
 */
int wine_spike_resolve_cache_path(const char *cache_dir,
                                  const char *manifest_json,
                                  const char *asset_name,
                                  char *out, size_t out_cap);

/*
 * S-5(0) SIGSYS classification (see sigsys_diag.c).
 *
 * Exit code 159 (128 + SIGSYS) only proves the child was killed by signal 31.
 * It does NOT establish WHICH mechanism raised the signal (a seccomp kill, an
 * explicit tkill, or a bad syscall) or which syscall triggered it. The runner
 * records the failure as SIGSYS_CAUSE_UNRESOLVED until the si_code + syscall
 * number are captured via ptrace.
 */
#define WINE_SPIKE_SIGSYS_UNRESOLVED  0   /* not yet captured / ptrace unavailable */
#define WINE_SPIKE_SIGSYS_SECCOMP     1   /* si_code == SYS_SECCOMP (seccomp filter) */
#define WINE_SPIKE_SIGSYS_USER        2   /* si_code == SI_USER/SI_TKILL (explicit kill) */
#define WINE_SPIKE_SIGSYS_KERNEL      3   /* si_code == SI_KERNEL */
#define WINE_SPIKE_SIGSYS_NONE        4   /* child exited cleanly (no signal) */

struct wine_spike_sigsys_result {
    int exit_status;            /* raw waitpid exit (or 128+signo) */
    int terminated_by_signo;    /* signal that killed it, or -1 if exited */
    int sig_signo;              /* siginfo fields (best effort). NOTE: named
                                 * sig_* (not si_*) because <siginfo.h> defines
                                 * si_signo/si_code/si_call_addr as macros. */
    int sig_code;               /* SYS_SECCOMP=1, SI_USER=0, SI_KERNEL=0x80 */
    unsigned long long call_addr;
    long long syscall_nr;       /* orig_rax at the trap */
    unsigned int arch;          /* AUDIT_ARCH_* from GETREGSET */
    char syscall_name[24];      /* mapped name, or "" if unknown */
    int cause;                  /* one of WINE_SPIKE_SIGSYS_* */
};

/*
 * Trace the APK-managed glibc loader under PTRACE and capture the SIGSYS cause
 * (si_code + triggering syscall). Fills out->cause. This does NOT assume the
 * cause is SELinux — it records SIGSYS_CAUSE_UNRESOLVED until the data is in.
 */
int wine_spike_diag_sigsys(const char *native_dir,
                           const char *wine_target,
                           const char *prefix_dir,
                           const char *display,
                           const char *wine_args,
                           struct wine_spike_sigsys_result *out);

/*
 * S-5(a): APK-packaged Bionic trampoline launch.
 *
 * The direct execve path (wine_spike_launch_wine) execs the glibc loader from a
 * forked child of libwine_spike.so. The trampoline variant execs a SEPARATE
 * Bionic-compiled PIE (libwine_trampoline.so, also APK-managed) which then
 * execs the glibc loader. The purpose is to test whether the SIGSYS is specific
 * to exec'ing the glibc ELF directly from the app process (e.g. a W^X / execve
 * target restriction) or whether it fires regardless of how we arrive at the
 * glibc loader. Evidence from this path is kept SEPARATE from the PKG-01
 * control (which does not exec Wine at all).
 *
 * Returns the trampoline-launched child PID in *out_pid, or WINE_SPIKE_ERR_*
 * on failure. The trampoline execs:
 *   <native_dir>/libwine_trampoline.so <loader> --library-path <lib> <wine> ...
 */
int wine_spike_launch_wine_via_trampoline(const char *native_dir,
                                          const char *wine_target,
                                          const char *prefix_dir,
                                          const char *display,
                                          const char *wine_args,
                                          int64_t *out_pid);

/* Extended trampoline launch with optional extra_env (see launch_wine_ex). */
int wine_spike_launch_wine_via_trampoline_ex(const char *native_dir,
                                             const char *wine_target,
                                             const char *prefix_dir,
                                             const char *display,
                                             const char *wine_args,
                                             const char *extra_env,
                                             int64_t *out_pid);

/*
 * S-5(b): proot fallback launch.
 *
 * proot (termux/proot@a89b3732, APK-managed libproot.so, Bionic PIE) runs in
 * the Android/Bionic namespace and ptrace-traces the glibc-namespace child. It
 * translates the child's blocked syscalls (access->faccessat), working around
 * the untrusted_app seccomp filter that kills the direct glibc-loader path
 * (PROVEN: si_code=SYS_SECCOMP, syscall=21/access).
 *
 * proot does NOT replace the effective loader — the traced child still execve's
 * the APK-managed glibc loader as its effective loader, satisfying S-1. The
 * -b <app_tmp>:/tmp bind also handles wineserver's hardcoded /tmp/.wine-<uid>
 * server path (the namespace mechanism; TMPDIR alone does not change it).
 *
 * Returns the proot-launched child PID in *out_pid (the proot process itself;
 * Wine/wineserver run as proot's traced children).
 */
int wine_spike_launch_wine_via_proot(const char *native_dir,
                                     const char *wine_target,
                                     const char *prefix_dir,
                                     const char *display,
                                     const char *wine_args,
                                     const char *extra_env,
                                     int64_t *out_pid);

/* Maximum descendants captured by a proot run (proot + wine + wineserver +
 * any native children). Larger than WINE_SPIKE_CHILDREN_MAX because the
 * recursive tree is captured while alive, not just direct children. */
#define WINE_SPIKE_DESCENDANTS_MAX 64

/* A single descendant captured during a proot run. */
struct wine_spike_proc_info {
    int64_t pid;
    int64_t ppid;
    char cmdline[WINE_SPIKE_LINE_MAX];   /* /proc/<pid>/cmdline (NULs → spaces) */
    char comm[64];                        /* /proc/<pid>/comm */
    /* Classification (filled by the run caller, not the C side):
     *   "wine" / "wineserver" / "proot" / "loader" / "unknown"
     * The C side leaves this empty; the Kotlin runner classifies from cmdline. */
    char classification[24];
    /* Per-process /proc/<pid>/maps proof, captured while the process was alive.
     * "OK|<loader_path>|<apk_count>" or "FAIL|<reason>". Captured only if the
     * process was still alive at snapshot time; otherwise "GONE". */
    char maps_proof[WINE_SPIKE_LINE_MAX];
};

/* Structured result of a synchronous proot run. The run waits for the proot
 * process tree to finish (or the timeout), captures stdout/stderr, and
 * snapshots every descendant's PID/PPID/cmdline/comm + maps proof while the
 * processes are alive.
 *
 * On timeout, the run kills + reaps the ENTIRE process tree (recursive),
 * not just the top proot PID, and sets timed_out=1. */
struct wine_spike_proot_run_result {
    int exit_status;        /* raw waitpid exit of the top proot process (or -1) */
    int proot_rc;           /* WINE_SPIKE_OK or WINE_SPIKE_ERR_* */
    int timed_out;          /* 1 if the run hit the timeout */
    char stdout_buf[8192];  /* captured child stdout (truncated) */
    char stderr_buf[8192];  /* captured child stderr (truncated) */
    int descendant_count;   /* number of entries in descendants[] */
    struct wine_spike_proc_info descendants[WINE_SPIKE_DESCENDANTS_MAX];
};

/*
 * S-1/S-2 run path: run Wine synchronously via proot, with logical argv[0]
 * preservation. This is the corrected launcher that:
 *   - separates the immutable real APK executable path (libwine_preloader.so in
 *     nativeLibraryDir) from the LOGICAL Wine command name (argv[0], e.g.
 *     "wine", "wineboot", "winecfg"). glibc loader --argv0=<logical> preserves
 *     argv[0] so Wine dispatches correctly (wineboot != wine).
 *   - runs proot to completion (or timeout_ms), capturing stdout + stderr.
 *   - snapshots every descendant's PID/PPID/cmdline/comm + /proc/<pid>/maps
 *     proof while alive, so the caller can verify the APK-managed loader for
 *     wine + wineserver + every native child (full S-1 acceptance).
 *   - on timeout, recursively kills + reaps the whole tree.
 *
 * Args:
 *   native_dir    — nativeLibraryDir
 *   wine_target   — logical tree path (e.g. tree/bin/wineboot); resolved to the
 *                   real APK ELF for the exec, but argv[0] is taken from
 *                   argv0_override (or the basename of wine_target if NULL).
 *   argv0_override — logical command name ("wine", "wineboot", ...). If NULL/
 *                   empty, derived from wine_target's basename.
 *   prefix_dir    — WINEPREFIX
 *   display       — DISPLAY env (e.g. ":0") or empty
 *   wine_args     — extra args after the wine command (e.g. "--init", "--version")
 *   extra_env     — "KEY=VAL;KEY=VAL;..." or empty
 *   timeout_ms    — hard timeout; 0 = wait forever
 *   out           — filled with the structured result
 *
 * Returns WINE_SPIKE_OK on a successful run (regardless of child exit code;
 * check out->exit_status for the child's exit), or WINE_SPIKE_ERR_* on launch
 * failure. out->timed_out indicates the timeout fired.
 */
int wine_spike_run_wine_via_proot(const char *native_dir,
                                  const char *wine_target,
                                  const char *argv0_override,
                                  const char *prefix_dir,
                                  const char *display,
                                  const char *wine_args,
                                  const char *extra_env,
                                  int timeout_ms,
                                  struct wine_spike_proot_run_result *out);

/*
 * Recursively enumerate ALL descendants of <root_pid> (children, grandchildren,
 * ...) into out_pids (depth-first). Returns the count written, or -1 on error.
 * Used by the runner to enumerate the proot->wine->wineserver tree. Unlike
 * wine_spike_enum_children (direct children only), this walks the full tree.
 */
int wine_spike_enum_descendants_recursive(int64_t root_pid,
                                          int64_t *out_pids, int cap);

/*
 * Recursively kill + reap a process tree: SIGTERM then SIGKILL every descendant
 * of <root_pid> (depth-first), then <root_pid> itself. Best-effort; used on
 * timeout or shutdown so no proot/wine/wineserver orphans survive.
 */
int wine_spike_kill_tree_recursive(int64_t root_pid);

/* Get a human-readable string for a result code. */
const char *wine_spike_err_str(int code);

#ifdef __cplusplus
}
#endif

#endif /* POCKET_WINE_SPIKE_H */
