/*
 * native/wine-spike/src/jni_shim.cpp
 *
 * JNI exports for com.pocketrealm.wine.WineSpikeNative. Bridges the Kotlin spike
 * runner to the C launcher/probe/cache functions.
 *
 * This library runs in the Android/Bionic namespace. Wine runs in the Linux/glibc
 * namespace after execve — the two never share a loader.
 */
#include <jni.h>
#include <string>
#include <android/log.h>
#include "wine_spike.h"

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_buildSymlinkTreeNative(
        JNIEnv *env, jobject /*this*/,
        jstring jTreeDir, jstring jNativeDir, jstring jManifest) {
    const char *tree_dir = env->GetStringUTFChars(jTreeDir, nullptr);
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *manifest = env->GetStringUTFChars(jManifest, nullptr);
    int rc = wine_spike_build_symlink_tree(tree_dir, native_dir, manifest);
    env->ReleaseStringUTFChars(jTreeDir, tree_dir);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jManifest, manifest);
    return rc;
}

JNIEXPORT jlong JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_launchWineNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir, jstring jDisplay,
        jstring jWineArgs) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine(native_dir, wine_target, prefix_dir, display, wine_args, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (rc != WINE_SPIKE_OK) return -1;
    return (jlong)pid;
}

/* S-5 extended launch: accepts an extra_env string for GLIBC_TUNABLES etc. */
JNIEXPORT jlong JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_launchWineExNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir, jstring jDisplay,
        jstring jWineArgs, jstring jExtraEnv) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    const char *extra_env = jExtraEnv ? env->GetStringUTFChars(jExtraEnv, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine_ex(native_dir, wine_target, prefix_dir,
                                       display, wine_args, extra_env, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (jExtraEnv) env->ReleaseStringUTFChars(jExtraEnv, extra_env);
    if (rc != WINE_SPIKE_OK) return -1;
    return (jlong)pid;
}

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_probeLoaderNative(
        JNIEnv *env, jobject /*this*/,
        jlong jPid, jstring jExpectedNativeDir) {
    int64_t pid = (int64_t)jPid;
    const char *native_dir = env->GetStringUTFChars(jExpectedNativeDir, nullptr);
    char loader_path[WINE_SPIKE_PATH_MAX] = {0};
    char interp[1024] = {0};
    int rc = wine_spike_probe_loader(pid, native_dir, loader_path, sizeof(loader_path),
                                     interp, sizeof(interp));
    int apk_count = wine_spike_count_apk_mappings(pid, native_dir);
    env->ReleaseStringUTFChars(jExpectedNativeDir, native_dir);
    /* Return a structured result: "OK|<loader_path>|<interp>|<apk_count>" or "FAIL|<reason>". */
    char result[3072];
    if (rc == WINE_SPIKE_OK) {
        snprintf(result, sizeof(result), "OK|%s|%s|%d", loader_path, interp, apk_count);
    } else {
        snprintf(result, sizeof(result), "FAIL|rc=%d|loader=%s|interp=%s|apk=%d",
                 rc, loader_path, interp, apk_count);
    }
    return env->NewStringUTF(result);
}

JNIEXPORT jintArray JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_enumChildrenNative(
        JNIEnv *env, jobject /*this*/, jlong jPid) {
    int64_t pids[WINE_SPIKE_CHILDREN_MAX];
    int count = wine_spike_enum_children((int64_t)jPid, pids, WINE_SPIKE_CHILDREN_MAX);
    if (count < 0) count = 0;
    jintArray result = env->NewIntArray(count);
    if (count > 0) {
        env->SetIntArrayRegion(result, 0, count, (jint *)pids);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_materializePeCacheNative(
        JNIEnv *env, jobject /*this*/,
        jstring jCacheDir, jstring jManifest, jstring jAssetsDir) {
    const char *cache_dir = env->GetStringUTFChars(jCacheDir, nullptr);
    const char *manifest = env->GetStringUTFChars(jManifest, nullptr);
    const char *assets_dir = env->GetStringUTFChars(jAssetsDir, nullptr);
    int rc = wine_spike_materialize_pe_cache(cache_dir, manifest, assets_dir);
    env->ReleaseStringUTFChars(jCacheDir, cache_dir);
    env->ReleaseStringUTFChars(jManifest, manifest);
    env->ReleaseStringUTFChars(jAssetsDir, assets_dir);
    return rc;
}

/* S-2 tree-aware materialize: also symlinks logical_path entries into the wine
 * tree so Wine can find cached PE modules. */
JNIEXPORT jint JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_materializePeCacheIntoTreeNative(
        JNIEnv *env, jobject /*this*/,
        jstring jCacheDir, jstring jManifest, jstring jAssetsDir, jstring jTreeDir) {
    const char *cache_dir = env->GetStringUTFChars(jCacheDir, nullptr);
    const char *manifest = env->GetStringUTFChars(jManifest, nullptr);
    const char *assets_dir = env->GetStringUTFChars(jAssetsDir, nullptr);
    const char *tree_dir = jTreeDir ? env->GetStringUTFChars(jTreeDir, nullptr) : nullptr;
    int rc = wine_spike_materialize_pe_cache_into_tree(cache_dir, manifest, assets_dir, tree_dir);
    env->ReleaseStringUTFChars(jCacheDir, cache_dir);
    env->ReleaseStringUTFChars(jManifest, manifest);
    env->ReleaseStringUTFChars(jAssetsDir, assets_dir);
    if (jTreeDir) env->ReleaseStringUTFChars(jTreeDir, tree_dir);
    return rc;
}

/* S-2 mismatch-repair: resolve the cache path for a PE module asset basename. */
JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_resolveCachePathNative(
        JNIEnv *env, jobject /*this*/,
        jstring jCacheDir, jstring jManifest, jstring jAssetName) {
    const char *cache_dir = env->GetStringUTFChars(jCacheDir, nullptr);
    const char *manifest = env->GetStringUTFChars(jManifest, nullptr);
    const char *asset_name = env->GetStringUTFChars(jAssetName, nullptr);
    char out[WINE_SPIKE_PATH_MAX] = {0};
    int rc = wine_spike_resolve_cache_path(cache_dir, manifest, asset_name, out, sizeof(out));
    env->ReleaseStringUTFChars(jCacheDir, cache_dir);
    env->ReleaseStringUTFChars(jManifest, manifest);
    env->ReleaseStringUTFChars(jAssetName, asset_name);
    if (rc != WINE_SPIKE_OK) return env->NewStringUTF("");
    return env->NewStringUTF(out);
}

JNIEXPORT jint JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_verifyPeCacheNative(
        JNIEnv *env, jobject /*this*/,
        jstring jCacheDir, jstring jManifest) {
    const char *cache_dir = env->GetStringUTFChars(jCacheDir, nullptr);
    const char *manifest = env->GetStringUTFChars(jManifest, nullptr);
    int rc = wine_spike_verify_pe_cache(cache_dir, manifest);
    env->ReleaseStringUTFChars(jCacheDir, cache_dir);
    env->ReleaseStringUTFChars(jManifest, manifest);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_errStrNative(
        JNIEnv *env, jobject /*this*/, jint jCode) {
    return env->NewStringUTF(wine_spike_err_str(jCode));
}

/* S-5(0): SIGSYS diagnostic. Returns a structured string capturing the ptrace
 * result so the Kotlin runner can classify the cause without assuming SELinux.
 * Format:
 *   "OK|exit=N|sig=N|si_code=N|syscall=M|name=rseq|arch=0xc|cause=C"
 *   "OK|exit=N|cause=NONE"            (clean exit, no signal)
 *   "FAIL|rc=N|err=..."               (tracer setup failure)
 * cause codes: 0=UNRESOLVED 1=SECCOMP 2=USER 3=KERNEL 4=NONE
 */
JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_diagSigsysNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir,
        jstring jDisplay, jstring jWineArgs) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";

    struct wine_spike_sigsys_result r;
    int rc = wine_spike_diag_sigsys(native_dir, wine_target, prefix_dir,
                                    display, wine_args, &r);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);

    char out[1024];
    if (rc != WINE_SPIKE_OK) {
        snprintf(out, sizeof(out), "FAIL|rc=%d|err=%s", rc, wine_spike_err_str(rc));
    } else {
        snprintf(out, sizeof(out),
                 "OK|exit=%d|sig=%d|si_code=%d|syscall=%lld|name=%s|arch=0x%x|cause=%d",
                 r.exit_status, r.terminated_by_signo, r.sig_code,
                 r.syscall_nr, r.syscall_name[0] ? r.syscall_name : "?",
                 r.arch, r.cause);
    }
    return env->NewStringUTF(out);
}

/* S-5(a): launch Wine via the APK-packaged Bionic trampoline PIE. Returns the
 * trampoline-launched child PID, or -1 on failure. */
JNIEXPORT jlong JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_launchWineViaTrampolineNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir,
        jstring jDisplay, jstring jWineArgs) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine_via_trampoline(native_dir, wine_target, prefix_dir,
                                                    display, wine_args, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (rc != WINE_SPIKE_OK) return -1;
    return (jlong)pid;
}

/* S-5(a) extended: trampoline launch with extra_env (GLIBC_TUNABLES etc.). */
JNIEXPORT jlong JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_launchWineViaTrampolineExNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir,
        jstring jDisplay, jstring jWineArgs, jstring jExtraEnv) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    const char *extra_env = jExtraEnv ? env->GetStringUTFChars(jExtraEnv, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine_via_trampoline_ex(native_dir, wine_target, prefix_dir,
                                                       display, wine_args, extra_env, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (jExtraEnv) env->ReleaseStringUTFChars(jExtraEnv, extra_env);
    if (rc != WINE_SPIKE_OK) return -1;
    return (jlong)pid;
}

/* S-5(b): launch Wine via proot (syscall interception). Returns the proot
 * process PID, or -1 on failure. Wine/wineserver run as proot's traced children. */
JNIEXPORT jlong JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_launchWineViaProotNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir,
        jstring jDisplay, jstring jWineArgs, jstring jExtraEnv) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    const char *extra_env = jExtraEnv ? env->GetStringUTFChars(jExtraEnv, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine_via_proot(native_dir, wine_target, prefix_dir,
                                              display, wine_args, extra_env, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (jExtraEnv) env->ReleaseStringUTFChars(jExtraEnv, extra_env);
    if (rc != WINE_SPIKE_OK) return -1;
    return (jlong)pid;
}

/* S-1/S-2 synchronous proot run with logical argv[0] + recursive descendant
 * /proc maps proof. Returns a structured result the Kotlin runner parses:
 *
 *   "EXIT=<int>|TIMED_OUT=<0|1>|DESCS=<n>\n<desc lines>\n@@@STDOUT@@@\n<stdout>\n@@@STDERR@@@\n<stderr>"
 *
 * Each desc line (one per descendant):
 *   "  pid=<ll>|ppid=<ll>|comm=<s>|maps=<proof>|cmdline=<s>"
 *
 * This is the corrected launcher: argv[0] is preserved via glibc-loader --argv0,
 * and the run waits for completion (or timeout_ms) capturing stdout/stderr +
 * every descendant's /proc/<pid>/maps proof. On timeout the whole tree is killed.
 */
JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_runWineViaProotNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jWineTarget, jstring jArgv0,
        jstring jPrefixDir, jstring jDisplay, jstring jWineArgs,
        jstring jExtraEnv, jint jTimeoutMs) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *argv0 = jArgv0 ? env->GetStringUTFChars(jArgv0, nullptr) : "";
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    const char *extra_env = jExtraEnv ? env->GetStringUTFChars(jExtraEnv, nullptr) : "";
    int timeout_ms = (int)jTimeoutMs;

    struct wine_spike_proot_run_result r;
    int rc = wine_spike_run_wine_via_proot(native_dir, wine_target, argv0, prefix_dir,
                                           display, wine_args, extra_env, timeout_ms, &r);

    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    if (jArgv0) env->ReleaseStringUTFChars(jArgv0, argv0);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (jExtraEnv) env->ReleaseStringUTFChars(jExtraEnv, extra_env);

    /* Build the structured result string. Bound the total size so it fits in a
     * JNI string comfortably; stdout/stderr are truncated per-side in C. */
    std::string out;
    char header[256];
    snprintf(header, sizeof(header), "RC=%d|EXIT=%d|TIMED_OUT=%d|DESCS=%d",
             rc, r.exit_status, r.timed_out, r.descendant_count);
    out += header;
    for (int i = 0; i < r.descendant_count; i++) {
        const struct wine_spike_proc_info *d = &r.descendants[i];
        out += "\n  pid=";
        out += std::to_string(d->pid);
        out += "|ppid=";
        out += std::to_string(d->ppid);
        out += "|comm=";
        out += d->comm;
        out += "|maps=";
        out += d->maps_proof;
        out += "|cmdline=";
        out += d->cmdline;
    }
    out += "\n@@@STDOUT@@@\n";
    out += r.stdout_buf;
    out += "\n@@@STDERR@@@\n";
    out += r.stderr_buf;
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_runWineDirectNative(
        JNIEnv *env, jobject /*this*/,
        jstring jNativeDir, jstring jPeTarget, jstring jPrefixDir,
        jstring jWorkingDir, jstring jDisplay, jstring jWineArgs, jstring jExtraEnv,
        jint jTimeoutMs) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *pe_target = env->GetStringUTFChars(jPeTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *working_dir = jWorkingDir ? env->GetStringUTFChars(jWorkingDir, nullptr) : "";
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    const char *wine_args = jWineArgs ? env->GetStringUTFChars(jWineArgs, nullptr) : "";
    const char *extra_env = jExtraEnv ? env->GetStringUTFChars(jExtraEnv, nullptr) : "";

    struct wine_spike_proot_run_result r;
    int rc = wine_spike_run_wine_direct(native_dir, pe_target, prefix_dir, working_dir,
                                         display, wine_args, extra_env,
                                         (int)jTimeoutMs, &r);

    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jPeTarget, pe_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jWorkingDir) env->ReleaseStringUTFChars(jWorkingDir, working_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
    if (jWineArgs) env->ReleaseStringUTFChars(jWineArgs, wine_args);
    if (jExtraEnv) env->ReleaseStringUTFChars(jExtraEnv, extra_env);

    std::string out;
    char header[256];
    snprintf(header, sizeof(header), "RC=%d|EXIT=%d|TIMED_OUT=%d|DESCS=%d",
             rc, r.exit_status, r.timed_out, r.descendant_count);
    out += header;
    for (int i = 0; i < r.descendant_count; i++) {
        const struct wine_spike_proc_info *d = &r.descendants[i];
        out += "\n  pid=" + std::to_string(d->pid);
        out += "|ppid=" + std::to_string(d->ppid);
        out += "|comm="; out += d->comm;
        out += "|maps="; out += d->maps_proof;
        out += "|cmdline="; out += d->cmdline;
    }
    out += "\n@@@STDOUT@@@\n";
    out += r.stdout_buf;
    out += "\n@@@STDERR@@@\n";
    out += r.stderr_buf;
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketrealm_wine_WineSpikeNative_cancelActiveDirectNative(
    JNIEnv *, jobject) {
    return wine_spike_cancel_active_direct() ? JNI_TRUE : JNI_FALSE;
}

/* O08: fixed-command MariaDB launcher. This JNI primitive is process-local to
 * DatabaseService; the Binder contract never accepts these path/argv fields. */
JNIEXPORT jstring JNICALL
Java_com_pocketrealm_database_DatabaseNative_runGlibcProgramNative(
        JNIEnv *env, jobject,
        jstring jNativeDir, jstring jExecutable, jstring jArgv0,
        jstring jWorkingDir, jstring jRuntimeRoot, jstring jLibraryPath,
        jstring jArgsBlob, jstring jEnvBlob, jstring jStdinPath,
        jint jTimeoutMs, jboolean jTrackAsDaemon) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *executable = env->GetStringUTFChars(jExecutable, nullptr);
    const char *argv0 = env->GetStringUTFChars(jArgv0, nullptr);
    const char *working_dir = env->GetStringUTFChars(jWorkingDir, nullptr);
    const char *runtime_root = env->GetStringUTFChars(jRuntimeRoot, nullptr);
    const char *library_path = env->GetStringUTFChars(jLibraryPath, nullptr);
    const char *args_blob = env->GetStringUTFChars(jArgsBlob, nullptr);
    const char *env_blob = env->GetStringUTFChars(jEnvBlob, nullptr);
    const char *stdin_path = env->GetStringUTFChars(jStdinPath, nullptr);

    struct wine_spike_proot_run_result result;
    int rc = wine_spike_run_glibc_program(
        native_dir, executable, argv0, working_dir, runtime_root, library_path,
        args_blob, env_blob, stdin_path, (int)jTimeoutMs,
        jTrackAsDaemon == JNI_TRUE, &result);

    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jExecutable, executable);
    env->ReleaseStringUTFChars(jArgv0, argv0);
    env->ReleaseStringUTFChars(jWorkingDir, working_dir);
    env->ReleaseStringUTFChars(jRuntimeRoot, runtime_root);
    env->ReleaseStringUTFChars(jLibraryPath, library_path);
    env->ReleaseStringUTFChars(jArgsBlob, args_blob);
    env->ReleaseStringUTFChars(jEnvBlob, env_blob);
    env->ReleaseStringUTFChars(jStdinPath, stdin_path);

    std::string wire;
    char header[160];
    snprintf(header, sizeof(header), "RC=%d|EXIT=%d|TIMED_OUT=%d|DESCS=0",
             rc, result.exit_status, result.timed_out);
    wire += header;
    wire += "\n@@@STDOUT@@@\n";
    wire += result.stdout_buf;
    wire += "\n@@@STDERR@@@\n";
    wire += result.stderr_buf;
    return env->NewStringUTF(wire.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_database_DatabaseNative_runBionicProgramNative(
        JNIEnv *env, jobject,
        jstring jNativeDir, jstring jExecutable, jstring jArgv0,
        jstring jWorkingDir, jstring jRuntimeRoot, jstring jLibraryPath,
        jstring jArgsBlob, jstring jEnvBlob, jstring jStdinPath,
        jint jTimeoutMs, jboolean jTrackAsDaemon) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *executable = env->GetStringUTFChars(jExecutable, nullptr);
    const char *argv0 = env->GetStringUTFChars(jArgv0, nullptr);
    const char *working_dir = env->GetStringUTFChars(jWorkingDir, nullptr);
    const char *runtime_root = env->GetStringUTFChars(jRuntimeRoot, nullptr);
    const char *library_path = env->GetStringUTFChars(jLibraryPath, nullptr);
    const char *args_blob = env->GetStringUTFChars(jArgsBlob, nullptr);
    const char *env_blob = env->GetStringUTFChars(jEnvBlob, nullptr);
    const char *stdin_path = env->GetStringUTFChars(jStdinPath, nullptr);

    struct wine_spike_proot_run_result result;
    int rc = wine_spike_run_bionic_program(
        native_dir, executable, argv0, working_dir, runtime_root, library_path,
        args_blob, env_blob, stdin_path, (int)jTimeoutMs,
        jTrackAsDaemon == JNI_TRUE, &result);

    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jExecutable, executable);
    env->ReleaseStringUTFChars(jArgv0, argv0);
    env->ReleaseStringUTFChars(jWorkingDir, working_dir);
    env->ReleaseStringUTFChars(jRuntimeRoot, runtime_root);
    env->ReleaseStringUTFChars(jLibraryPath, library_path);
    env->ReleaseStringUTFChars(jArgsBlob, args_blob);
    env->ReleaseStringUTFChars(jEnvBlob, env_blob);
    env->ReleaseStringUTFChars(jStdinPath, stdin_path);

    std::string wire;
    char header[160];
    snprintf(header, sizeof(header), "RC=%d|EXIT=%d|TIMED_OUT=%d|DESCS=0",
             rc, result.exit_status, result.timed_out);
    wire += header;
    wire += "\n@@@STDOUT@@@\n";
    wire += result.stdout_buf;
    wire += "\n@@@STDERR@@@\n";
    wire += result.stderr_buf;
    return env->NewStringUTF(wire.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_pocketrealm_database_DatabaseNative_cancelActiveGlibcProgramNative(
        JNIEnv *, jobject) {
    return wine_spike_cancel_active_glibc_program() ? JNI_TRUE : JNI_FALSE;
}

} /* extern "C" */
