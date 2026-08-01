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
        jstring jNativeDir, jstring jWineTarget, jstring jPrefixDir, jstring jDisplay) {
    const char *native_dir = env->GetStringUTFChars(jNativeDir, nullptr);
    const char *wine_target = env->GetStringUTFChars(jWineTarget, nullptr);
    const char *prefix_dir = env->GetStringUTFChars(jPrefixDir, nullptr);
    const char *display = jDisplay ? env->GetStringUTFChars(jDisplay, nullptr) : "";
    int64_t pid = -1;
    int rc = wine_spike_launch_wine(native_dir, wine_target, prefix_dir, display, &pid);
    env->ReleaseStringUTFChars(jNativeDir, native_dir);
    env->ReleaseStringUTFChars(jWineTarget, wine_target);
    env->ReleaseStringUTFChars(jPrefixDir, prefix_dir);
    if (jDisplay) env->ReleaseStringUTFChars(jDisplay, display);
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

} /* extern "C" */
