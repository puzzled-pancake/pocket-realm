/*
 * native/wine-spike/src/wine_probe.c
 *
 * Probe /proc/<pid>/maps to PROVE the effective dynamic loader for a Wine
 * process. Direct loader invocation BYPASSES Wine's embedded PT_INTERP — it
 * does not make that path resolve; it substitutes our APK-managed loader. The
 * effective loader is then proven here by reading /proc/<pid>/maps.
 *
 * Also counts how many mapped libraries come from nativeLibraryDir (the APK-
 * managed glibc closure) vs system/elsewhere.
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>
#include <ctype.h>
#include <errno.h>
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

/* Read a whole file into a buffer. Returns bytes read, or -1 on error. */
static int read_file(const char *path, char *buf, size_t cap) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = 0;
    size_t total = 0;
    while (total < cap - 1 &&
           (n = read(fd, buf + total, cap - 1 - total)) > 0) {
        total += (size_t)n;
    }
    close(fd);
    buf[total] = '\0';
    return (int)total;
}

int wine_spike_probe_loader(int64_t pid,
                            const char *expected_native_dir,
                            char *out_loader_path, size_t loader_path_cap,
                            char *out_interp, size_t interp_cap) {
    if (out_loader_path && loader_path_cap > 0) out_loader_path[0] = '\0';
    if (out_interp && interp_cap > 0) out_interp[0] = '\0';

    /* Read /proc/<pid>/maps. */
    char maps_path[256];
    snprintf(maps_path, sizeof(maps_path), "/proc/%lld/maps", (long long)pid);

    char maps[65536];
    int maps_len = read_file(maps_path, maps, sizeof(maps));
    if (maps_len < 0) {
        LOGE("cannot read %s: %s", maps_path, strerror(errno));
        return WINE_SPIKE_ERR_IO;
    }

    /* Try /proc/<pid>/interp (may not be readable if the process used direct
     * loader invocation — the interp field reflects PT_INTERP, not the
     * effective loader). This is informational; the maps proof is authoritative. */
    char interp_path[256];
    snprintf(interp_path, sizeof(interp_path), "/proc/%lld/interp", (long long)pid);
    if (out_interp && interp_cap > 0) {
        int interp_len = read_file(interp_path, out_interp, interp_cap);
        if (interp_len < 0) {
            snprintf(out_interp, interp_cap, "(unreadable: %s)", strerror(errno));
        }
    }

    /* Scan maps lines for the loader. A loader entry's pathname contains
     * "ld-linux" (the dynamic linker). We find the FIRST such entry (the
     * initial loader mapping) and check its path starts with expected_native_dir. */
    const char *line = maps;
    int found_apk_loader = 0;
    size_t native_len = strlen(expected_native_dir);

    while (*line) {
        const char *eol = strchr(line, '\n');
        if (!eol) eol = line + strlen(line);

        /* Each maps line: addr perms offset dev inode    pathname
         * We look for lines where the pathname contains "ld-linux". */
        if (eol - line > 6) {
            /* Find the pathname (after the last whitespace-separated field). */
            const char *p = line;
            int spaces = 0;
            const char *pathname = NULL;
            for (const char *q = line; q < eol; q++) {
                if (*q == ' ') {
                    spaces++;
                    while (q < eol && *(q+1) == ' ') q++; /* collapse spaces */
                    if (spaces >= 5) {
                        /* The rest is the pathname (skip leading space). */
                        pathname = q + 1;
                        while (pathname < eol && *pathname == ' ') pathname++;
                    }
                }
            }
            if (pathname && pathname < eol) {
                size_t plen = eol - pathname;
                /* Check if this is the loader. The canonical glibc loader is
                 * ld-linux-x86-64.so.2 (substring "ld-linux"), but the APK-
                 * managed copy is renamed to libld_linux_x86_64.so (AGP requires
                 * a lib*.so name) — substring "ld_linux" (underscore). Match
                 * either so the APK-managed loader is correctly identified. */
                if (plen >= 8 && (strstr(pathname, "ld-linux") ||
                                  strstr(pathname, "ld_linux"))) {
                    /* Copy the loader path. */
                    if (out_loader_path && loader_path_cap > 0) {
                        size_t copy_len = plen < loader_path_cap - 1 ? plen : loader_path_cap - 1;
                        memcpy(out_loader_path, pathname, copy_len);
                        out_loader_path[copy_len] = '\0';
                    }
                    /* Check if it starts with expected_native_dir. */
                    if (plen >= native_len &&
                        strncmp(pathname, expected_native_dir, native_len) == 0) {
                        found_apk_loader = 1;
                        LOGI("probe_loader: APK-managed loader found: %.*s",
                             (int)plen, pathname);
                    } else {
                        LOGW("probe_loader: loader NOT APK-managed: %.*s", (int)plen, pathname);
                    }
                    break; /* Only check the first loader entry. */
                }
            }
        }
        line = (*eol == '\n') ? eol + 1 : eol;
        if (*line == '\0') break;
    }

    if (!found_apk_loader) {
        LOGE("probe_loader: no APK-managed loader found for pid %lld", (long long)pid);
        return WINE_SPIKE_ERR_VERIFY;
    }
    return WINE_SPIKE_OK;
}

int wine_spike_count_apk_mappings(int64_t pid, const char *expected_native_dir) {
    char maps_path[256];
    snprintf(maps_path, sizeof(maps_path), "/proc/%lld/maps", (long long)pid);

    char maps[65536];
    int maps_len = read_file(maps_path, maps, sizeof(maps));
    if (maps_len < 0) return -1;

    size_t native_len = strlen(expected_native_dir);
    int count = 0;
    int total_mapped = 0;

    const char *line = maps;
    while (*line) {
        const char *eol = strchr(line, '\n');
        if (!eol) eol = line + strlen(line);

        /* Extract pathname (same logic as probe_loader). */
        const char *p = line;
        int spaces = 0;
        const char *pathname = NULL;
        for (const char *q = line; q < eol; q++) {
            if (*q == ' ') {
                spaces++;
                while (q < eol && *(q+1) == ' ') q++;
                if (spaces >= 5) {
                    pathname = q + 1;
                    while (pathname < eol && *pathname == ' ') pathname++;
                }
            }
        }
        if (pathname && pathname < eol) {
            size_t plen = eol - pathname;
            if (plen >= native_len &&
                strncmp(pathname, expected_native_dir, native_len) == 0) {
                count++;
            }
            /* Count any mapped file with a pathname (not [anon]/[stack]/etc). */
            if (plen > 0 && pathname[0] == '/') {
                total_mapped++;
            }
        }
        line = (*eol == '\n') ? eol + 1 : eol;
        if (*line == '\0') break;
    }

    LOGI("count_apk_mappings pid=%lld: %d/%d mapped files from APK dir",
         (long long)pid, count, total_mapped);
    return count;
}

int wine_spike_enum_children(int64_t parent_pid, int64_t *out_pids, int cap) {
    /* Enumerate /proc to find children of parent_pid. The most portable
     * approach: scan /proc and check each process's stat (field 4 = ppid). */
    DIR *proc = opendir("/proc");
    if (!proc) return -1;

    int count = 0;
    struct dirent *de;
    while ((de = readdir(proc)) != NULL && count < cap) {
        /* Only numeric dir names are PIDs. */
        if (!isdigit((unsigned char)de->d_name[0])) continue;

        char stat_path[256];
        snprintf(stat_path, sizeof(stat_path), "/proc/%s/stat", de->d_name);
        char stat_buf[1024];
        int n = read_file(stat_path, stat_buf, sizeof(stat_buf));
        if (n <= 0) continue;

        /* /proc/<pid>/stat: pid (comm) state ppid ...
         * comm can contain spaces + parens, so parse from the LAST ')'. */
        char *last_paren = strrchr(stat_buf, ')');
        if (!last_paren) continue;
        /* After ')', the next fields are: state ppid ... */
        char state;
        long ppid = 0;
        if (sscanf(last_paren + 2, "%c %ld", &state, &ppid) >= 2) {
            if (ppid == parent_pid) {
                out_pids[count++] = atoi(de->d_name);
            }
        }
    }
    closedir(proc);
    return count;
}
