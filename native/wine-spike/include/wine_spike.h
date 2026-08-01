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
 * Verify the PE cache: re-check every file's SHA-256 against the manifest.
 * Called before each Wine launch. Returns WINE_SPIKE_OK if all match,
 * WINE_SPIKE_ERR_VERIFY if any mismatch (caller should re-materialize).
 */
int wine_spike_verify_pe_cache(const char *cache_dir, const char *manifest_json);

/* Get a human-readable string for a result code. */
const char *wine_spike_err_str(int code);

#ifdef __cplusplus
}
#endif

#endif /* POCKET_WINE_SPIKE_H */
