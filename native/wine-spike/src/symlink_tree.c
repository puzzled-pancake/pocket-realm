/*
 * native/wine-spike/src/symlink_tree.c
 *
 * Build the symlink-only logical Wine tree in filesDir, pointing at APK-managed
 * ELFs in nativeLibraryDir. The tree contains NO ELF regular files — every
 * symlink target is an immutable APK-managed file.
 *
 * The staging manifest (JSON) maps logical paths to renamed jniLib filenames:
 *   "glibc_soname_to_jnilib": {"libc.so.6": "libso_c.so.6.so", ...}
 *   "wine_logical_to_jnilib": {"bin/wine": "libwine_preloader.so", ...}
 *
 * We create symlinks for the Wine binary tree (bin/ + lib/wine/x86_64-unix/)
 * because those are the paths Wine expects. The glibc closure lives directly in
 * nativeLibraryDir (the loader finds them via --library-path by SONAME), so we
 * do NOT symlink those into the tree.
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Minimal JSON string-value extractor: finds "key": "value" and copies value
 * to out (unescaping \" and \\). Returns the byte offset after the value's
 * closing quote, or 0 if not found. This is a deliberately tiny parser — we
 * only need to extract string values for known keys from a known schema. */
static const char *find_json_key(const char *json, const char *key) {
    /* Search for "key" followed by optional whitespace and colon. */
    char pattern[256];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char *p = strstr(json, pattern);
    if (!p) return NULL;
    p += strlen(pattern);
    /* Skip whitespace + colon + whitespace. */
    while (*p && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
    if (*p != ':') return NULL;
    p++;
    while (*p && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
    return p;
}

/* Copy a JSON string value (between quotes) to out, unescaping. Returns length. */
static int copy_json_string(const char *p, char *out, size_t cap) {
    if (*p != '"') return -1;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i < cap - 1) {
        if (*p == '\\' && p[1]) {
            p++;
            if (*p == 'n') out[i++] = '\n';
            else if (*p == 't') out[i++] = '\t';
            else out[i++] = *p;
            p++;
        } else {
            out[i++] = *p++;
        }
    }
    out[i] = '\0';
    return (int)i;
}

/* Make a directory path (creating intermediate dirs). Like mkdir -p. */
static int mkdir_p(const char *path) {
    char tmp[WINE_SPIKE_PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
                LOGE("mkdir %s: %s", tmp, strerror(errno));
                return -1;
            }
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
        LOGE("mkdir %s: %s", tmp, strerror(errno));
        return -1;
    }
    return 0;
}

/* Create a symlink: target_path -> link_path. Removes existing entry first. */
static int make_symlink(const char *target, const char *link_path) {
    /* Remove existing file/symlink (ignore "not found"). */
    unlink(link_path);
    /* Also try rmdir in case a stale empty dir exists. */
    rmdir(link_path);
    if (symlink(target, link_path) != 0) {
        LOGE("symlink %s -> %s: %s", target, link_path, strerror(errno));
        return -1;
    }
    return 0;
}

int wine_spike_build_symlink_tree(const char *tree_dir,
                                  const char *native_dir,
                                  const char *manifest_json) {
    if (!tree_dir || !native_dir || !manifest_json) return WINE_SPIKE_ERR_ARGS;

    LOGI("build_symlink_tree: tree=%s native=%s", tree_dir, native_dir);

    /* Parse the wine_logical_to_jnilib object from the manifest.
     * The manifest has: "wine_logical_to_jnilib": { "bin/wine": "libwine_preloader.so", ... }
     * We iterate by finding the object, then parsing each key-value pair. */
    const char *obj = find_json_key(manifest_json, "wine_logical_to_jnilib");
    if (!obj || *obj != '{') {
        LOGE("wine_logical_to_jnilib not found in manifest");
        return WINE_SPIKE_ERR_IO;
    }
    obj++; /* skip '{' */

    int count = 0;
    while (*obj && *obj != '}') {
        /* Skip whitespace + commas. */
        while (*obj == ' ' || *obj == '\t' || *obj == '\n' || *obj == '\r' || *obj == ',')
            obj++;
        if (*obj == '}') break;
        if (*obj != '"') {
            LOGE("manifest parse error at offset (expected key)");
            return WINE_SPIKE_ERR_IO;
        }
        /* Extract logical path (key). */
        char logical[WINE_SPIKE_PATH_MAX];
        int logical_len = copy_json_string(obj, logical, sizeof(logical));
        if (logical_len < 0) {
            LOGE("manifest parse error: bad key");
            return WINE_SPIKE_ERR_IO;
        }
        /* Advance past the key string + closing quote. */
        obj = strchr(obj + 1, '"');
        if (!obj) return WINE_SPIKE_ERR_IO;
        obj++; /* past closing quote */
        /* Skip whitespace + colon. */
        while (*obj == ' ' || *obj == '\t') obj++;
        if (*obj != ':') return WINE_SPIKE_ERR_IO;
        obj++;
        while (*obj == ' ' || *obj == '\t') obj++;
        /* Extract jniLib name (value). */
        char jnilib[512];
        int jnilib_len = copy_json_string(obj, jnilib, sizeof(jnilib));
        if (jnilib_len < 0) return WINE_SPIKE_ERR_IO;
        obj = strchr(obj + 1, '"');
        if (!obj) return WINE_SPIKE_ERR_IO;
        obj++;

        /* Build paths: link = tree_dir/logical, target = native_dir/jnilib. */
        char link_path[WINE_SPIKE_PATH_MAX];
        char target_path[WINE_SPIKE_PATH_MAX];
        snprintf(link_path, sizeof(link_path), "%s/%s", tree_dir, logical);
        snprintf(target_path, sizeof(target_path), "%s/%s", native_dir, jnilib);

        /* Create the parent dir of link_path. */
        char parent[WINE_SPIKE_PATH_MAX];
        snprintf(parent, sizeof(parent), "%s", link_path);
        char *slash = strrchr(parent, '/');
        if (slash) {
            *slash = '\0';
            mkdir_p(parent);
        }

        if (make_symlink(target_path, link_path) != 0) {
            return WINE_SPIKE_ERR_IO;
        }
        count++;
    }

    LOGI("build_symlink_tree: created %d symlinks under %s", count, tree_dir);
    return WINE_SPIKE_OK;
}
