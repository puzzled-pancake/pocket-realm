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

    int count = 0;

    /* --- 1. Wine logical tree (bin/ + lib/wine/x86_64-unix/) --- */
    const char *wine_obj = find_json_key(manifest_json, "wine_logical_to_jnilib");
    if (wine_obj && *wine_obj == '{') {
        wine_obj++;
        while (*wine_obj && *wine_obj != '}') {
            while (*wine_obj == ' ' || *wine_obj == '\t' || *wine_obj == '\n' ||
                   *wine_obj == '\r' || *wine_obj == ',') wine_obj++;
            if (*wine_obj == '}') break;
            if (*wine_obj != '"') {
                LOGE("wine_logical parse error (expected key)");
                return WINE_SPIKE_ERR_IO;
            }
            char logical[WINE_SPIKE_PATH_MAX];
            if (copy_json_string(wine_obj, logical, sizeof(logical)) < 0) return WINE_SPIKE_ERR_IO;
            wine_obj = strchr(wine_obj + 1, '"');
            if (!wine_obj) return WINE_SPIKE_ERR_IO;
            wine_obj++;
            while (*wine_obj == ' ' || *wine_obj == '\t') wine_obj++;
            if (*wine_obj != ':') return WINE_SPIKE_ERR_IO;
            wine_obj++;
            while (*wine_obj == ' ' || *wine_obj == '\t') wine_obj++;
            char jnilib[512];
            if (copy_json_string(wine_obj, jnilib, sizeof(jnilib)) < 0) return WINE_SPIKE_ERR_IO;
            wine_obj = strchr(wine_obj + 1, '"');
            if (!wine_obj) return WINE_SPIKE_ERR_IO;
            wine_obj++;

            char link_path[WINE_SPIKE_PATH_MAX], target_path[WINE_SPIKE_PATH_MAX];
            snprintf(link_path, sizeof(link_path), "%s/%s", tree_dir, logical);
            snprintf(target_path, sizeof(target_path), "%s/%s", native_dir, jnilib);
            char parent[WINE_SPIKE_PATH_MAX];
            snprintf(parent, sizeof(parent), "%s", link_path);
            char *slash = strrchr(parent, '/');
            if (slash) { *slash = '\0'; mkdir_p(parent); }
            if (make_symlink(target_path, link_path) != 0) return WINE_SPIKE_ERR_IO;
            count++;
        }
    }

    /* --- 2. glibc/X11 closure lib symlinks (lib/<soname> -> native_dir/<renamed>) ---
     * These are CRITICAL: the glibc loader resolves DT_NEEDED by exact filename.
     * The APK-managed files are renamed to lib<soname>.so (AGP requires .so final
     * extension), so the loader can't find 'libc.so.6' in nativeLibraryDir. The
     * symlink tree provides lib/libc.so.6 -> nativeLibraryDir/liblibc.so.6.so,
     * and --library-path points at tree_dir/lib. */
    const char *glibc_obj = find_json_key(manifest_json, "glibc_soname_to_jnilib");
    if (glibc_obj && *glibc_obj == '{') {
        glibc_obj++;
        while (*glibc_obj && *glibc_obj != '}') {
            while (*glibc_obj == ' ' || *glibc_obj == '\t' || *glibc_obj == '\n' ||
                   *glibc_obj == '\r' || *glibc_obj == ',') glibc_obj++;
            if (*glibc_obj == '}') break;
            if (*glibc_obj != '"') {
                LOGE("glibc_soname parse error (expected key)");
                return WINE_SPIKE_ERR_IO;
            }
            char soname[256];
            if (copy_json_string(glibc_obj, soname, sizeof(soname)) < 0) return WINE_SPIKE_ERR_IO;
            glibc_obj = strchr(glibc_obj + 1, '"');
            if (!glibc_obj) return WINE_SPIKE_ERR_IO;
            glibc_obj++;
            while (*glibc_obj == ' ' || *glibc_obj == '\t') glibc_obj++;
            if (*glibc_obj != ':') return WINE_SPIKE_ERR_IO;
            glibc_obj++;
            while (*glibc_obj == ' ' || *glibc_obj == '\t') glibc_obj++;
            char jnilib[512];
            if (copy_json_string(glibc_obj, jnilib, sizeof(jnilib)) < 0) return WINE_SPIKE_ERR_IO;
            glibc_obj = strchr(glibc_obj + 1, '"');
            if (!glibc_obj) return WINE_SPIKE_ERR_IO;
            glibc_obj++;

            /* Place the SONAME symlink under tree_dir/lib/ (the --library-path target). */
            char link_path[WINE_SPIKE_PATH_MAX], target_path[WINE_SPIKE_PATH_MAX];
            snprintf(link_path, sizeof(link_path), "%s/lib/%s", tree_dir, soname);
            snprintf(target_path, sizeof(target_path), "%s/%s", native_dir, jnilib);
            if (make_symlink(target_path, link_path) != 0) return WINE_SPIKE_ERR_IO;
            count++;
        }
    }

    LOGI("build_symlink_tree: created %d symlinks under %s", count, tree_dir);
    return WINE_SPIKE_OK;
}
