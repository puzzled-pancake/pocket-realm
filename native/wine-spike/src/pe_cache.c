/*
 * native/wine-spike/src/pe_cache.c
 *
 * Materialize and verify the Wine-owned PE module cache.
 *
 * Wine-owned builtin PE modules (.dll/.exe under lib/wine/-windows dirs) are
 * canonical APK assets (immutable, signed with the APK). At launch time they
 * are materialized into filesDir/runtime/wine-pe-cache/ as a hash-verified
 * guest-code cache:
 *   - The APK ASSETS are immutable. The materialized copies in filesDir are
 *     WRITABLE (even when hash-verified) and are NOT called immutable — they
 *     are a verified cache.
 *   - They are NEVER passed to Android execve(). Wine loads them via its own
 *     PE loader.
 *   - Reverification (every file's SHA-256 against the manifest) runs before
 *     each Wine launch; mismatch -> materialize a fresh atomically-replaced copy.
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>
#include <android/log.h>

/* SHA-256 via OpenSSL is not available in the NDK by default; use a compact
 * public-domain SHA-256 implementation. */
#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

/* ---- compact SHA-256 (Brad Conte's public-domain impl) -------------------- */
typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint32_t datalen;
    uint8_t data[64];
} sha256_ctx;

#define ROTR(x,n) (((x) >> (n)) | ((x) << (32-(n))))
#define EP0(x) (ROTR(x,2) ^ ROTR(x,13) ^ ROTR(x,22))
#define EP1(x) (ROTR(x,6) ^ ROTR(x,11) ^ ROTR(x,25))
#define SIG0(x) (ROTR(x,7) ^ ROTR(x,18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x,17) ^ ROTR(x,19) ^ ((x) >> 10))

static const uint32_t SHA256_K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
};

static void sha256_transform(sha256_ctx *ctx, const uint8_t *data) {
    uint32_t a,b,c,d,e,f,g,h,t1,t2,m[64];
    int i,j;
    for (i=0,j=0; i<16; i++,j+=4)
        m[i] = ((uint32_t)data[j]<<24)|((uint32_t)data[j+1]<<16)|((uint32_t)data[j+2]<<8)|data[j+3];
    for (; i<64; i++)
        m[i] = SIG1(m[i-2]) + m[i-7] + SIG0(m[i-15]) + m[i-16];
    a=ctx->state[0];b=ctx->state[1];c=ctx->state[2];d=ctx->state[3];
    e=ctx->state[4];f=ctx->state[5];g=ctx->state[6];h=ctx->state[7];
    for (i=0;i<64;i++) {
        t1=h+EP1(e)+((e&f)^((~e)&g))+SHA256_K[i]+m[i];
        t2=EP0(a)+((a&b)^(a&c)^(b&c));
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    ctx->state[0]+=a;ctx->state[1]+=b;ctx->state[2]+=c;ctx->state[3]+=d;
    ctx->state[4]+=e;ctx->state[5]+=f;ctx->state[6]+=g;ctx->state[7]+=h;
}

static void sha256_init(sha256_ctx *ctx) {
    ctx->datalen=0; ctx->bitlen=0;
    ctx->state[0]=0x6a09e667;ctx->state[1]=0xbb67ae85;ctx->state[2]=0x3c6ef372;
    ctx->state[3]=0xa54ff53a;ctx->state[4]=0x510e527f;ctx->state[5]=0x9b05688c;
    ctx->state[6]=0x1f83d9ab;ctx->state[7]=0x5be0cd19;
}

static void sha256_update(sha256_ctx *ctx, const uint8_t *data, size_t len) {
    for (size_t i=0;i<len;i++) {
        ctx->data[ctx->datalen++]=data[i];
        if (ctx->datalen==64){sha256_transform(ctx,ctx->data);
            ctx->bitlen+=512;ctx->datalen=0;}
    }
}

static void sha256_final(sha256_ctx *ctx, uint8_t *hash) {
    uint32_t i=ctx->datalen;
    ctx->data[i++]=0x80;
    if (ctx->datalen<56){while(i<56)ctx->data[i++]=0;}
    else{while(i<64)ctx->data[i++]=0;sha256_transform(ctx,ctx->data);
        memset(ctx->data,0,56);}
    ctx->bitlen += (uint64_t)ctx->datalen * 8;
    ctx->data[63]=ctx->bitlen;ctx->data[62]=ctx->bitlen>>8;
    ctx->data[61]=ctx->bitlen>>16;ctx->data[60]=ctx->bitlen>>24;
    ctx->data[59]=ctx->bitlen>>32;ctx->data[58]=ctx->bitlen>>40;
    ctx->data[57]=ctx->bitlen>>48;ctx->data[56]=ctx->bitlen>>56;
    sha256_transform(ctx,ctx->data);
    for (i=0;i<4;i++){
        hash[i]   =(ctx->state[0]>>(24-i*8))&0xff;
        hash[i+4] =(ctx->state[1]>>(24-i*8))&0xff;
        hash[i+8] =(ctx->state[2]>>(24-i*8))&0xff;
        hash[i+12]=(ctx->state[3]>>(24-i*8))&0xff;
        hash[i+16]=(ctx->state[4]>>(24-i*8))&0xff;
        hash[i+20]=(ctx->state[5]>>(24-i*8))&0xff;
        hash[i+24]=(ctx->state[6]>>(24-i*8))&0xff;
        hash[i+28]=(ctx->state[7]>>(24-i*8))&0xff;
    }
}

/* Compute SHA-256 of a file. Returns 0 on success, fills out_hash (hex, 65 chars). */
static int sha256_file(const char *path, char *out_hash) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    sha256_ctx ctx;
    sha256_init(&ctx);
    uint8_t buf[65536];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        sha256_update(&ctx, buf, (size_t)n);
    }
    close(fd);
    if (n < 0) return -1;
    uint8_t hash[32];
    sha256_final(&ctx, hash);
    for (int i = 0; i < 32; i++) {
        sprintf(out_hash + i * 2, "%02x", hash[i]);
    }
    out_hash[64] = '\0';
    return 0;
}

/* Copy a file atomically: write to <dest>.tmp, fsync, rename to <dest>. */
static int atomic_copy(const char *src, const char *dest) {
    char tmp[WINE_SPIKE_PATH_MAX + 4];
    snprintf(tmp, sizeof(tmp), "%s.tmp", dest);

    int in_fd = open(src, O_RDONLY | O_CLOEXEC);
    if (in_fd < 0) return -1;
    int out_fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (out_fd < 0) { close(in_fd); return -1; }

    char buf[65536];
    ssize_t n;
    while ((n = read(in_fd, buf, sizeof(buf))) > 0) {
        ssize_t w = 0;
        while (w < n) {
            ssize_t r = write(out_fd, buf + w, n - w);
            if (r < 0) { close(in_fd); close(out_fd); unlink(tmp); return -1; }
            w += r;
        }
    }
    close(in_fd);
    if (n < 0) { close(out_fd); unlink(tmp); return -1; }
    fsync(out_fd);
    close(out_fd);

    if (rename(tmp, dest) != 0) { unlink(tmp); return -1; }
    return 0;
}

static int mkdir_p(const char *path) {
    char tmp[WINE_SPIKE_PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

/* Find "entries" array in the manifest and iterate. Each entry has:
 *   "asset_path": "wine-pe/x86_64-windows/foo.dll"
 *   "sha256": "abcdef..."
 *   "logical_path": "lib/wine/x86_64-windows/foo.dll"
 *
 * We parse the entries array manually (tiny JSON parser). */
static const char *find_entries_array(const char *json) {
    const char *p = strstr(json, "\"entries\"");
    if (!p) return NULL;
    p = strchr(p, '[');
    return p;
}

/* Extract a string field value from a JSON object substring. */
static int extract_field(const char *obj, const char *key, char *out, size_t cap) {
    char pat[128];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(obj, pat);
    if (!p) return -1;
    p += strlen(pat);
    while (*p && *p != ':') p++;
    if (*p != ':') return -1;
    p++;
    while (*p && (*p == ' ' || *p == '\t' || *p == '\n')) p++;
    if (*p != '"') return -1;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i < cap - 1) {
        out[i++] = *p++;
    }
    out[i] = '\0';
    return 0;
}

/* Create a symlink link_path -> target, removing any existing entry first, and
 * mkdir_p the parent. Idempotent. Returns 0 on success. */
static int install_tree_symlink(const char *target, const char *link_path) {
    char parent[WINE_SPIKE_PATH_MAX];
    snprintf(parent, sizeof(parent), "%s", link_path);
    char *slash = strrchr(parent, '/');
    if (slash) { *slash = '\0'; mkdir_p(parent); }
    unlink(link_path);   /* ignore "not found" */
    if (symlink(target, link_path) != 0) {
        LOGE("pe tree symlink %s -> %s: %s", link_path, target, strerror(errno));
        return -1;
    }
    return 0;
}

int wine_spike_materialize_pe_cache(const char *cache_dir,
                                    const char *manifest_json,
                                    const char *assets_dir) {
    return wine_spike_materialize_pe_cache_into_tree(cache_dir, manifest_json,
                                                     assets_dir, NULL);
}

/*
 * Materialize PE modules AND connect them to the logical Wine tree.
 *
 * For each manifest entry we now:
 *   1. copy/verify the canonical asset to <cache_dir>/<asset_path> (the
 *      hash-verified guest-code cache), exactly as before; AND
 *   2. if tree_dir is non-NULL and the entry has a "logical_path" (e.g.
 *      "lib/wine/x86_64-windows/foo.dll"), create a symlink at
 *      <tree_dir>/<logical_path> -> <cache_dir>/<asset_path>.
 *
 * This fixes the S-2 path bug: the old code ignored logical_path and
 * materialized files under wine-pe/... with nothing connecting them to the
 * logical Wine tree, so Wine could not find a single cached PE module. The
 * symlink-only tree keeps the property that no ELF regular file lives in
 * writable storage — these are PE guest-code files (authorized), never passed
 * to Android execve(), loaded only by Wine's own PE loader.
 */
int wine_spike_materialize_pe_cache_into_tree(const char *cache_dir,
                                              const char *manifest_json,
                                              const char *assets_dir,
                                              const char *tree_dir) {
    if (!cache_dir || !manifest_json || !assets_dir) return WINE_SPIKE_ERR_ARGS;

    LOGI("materialize_pe_cache: cache=%s assets=%s tree=%s",
         cache_dir, assets_dir, tree_dir ? tree_dir : "(none)");

    const char *arr = find_entries_array(manifest_json);
    if (!arr) {
        LOGE("no entries array in manifest");
        return WINE_SPIKE_ERR_IO;
    }

    mkdir_p(cache_dir);

    int verified = 0, materialized = 0, linked = 0, failed = 0;
    const char *p = arr + 1; /* skip '[' */

    while (*p && *p != ']') {
        /* Skip whitespace + commas. */
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == ',') p++;
        if (*p == ']') break;
        if (*p != '{') { p++; continue; }

        /* Find the matching '}' for this object. */
        const char *obj_start = p;
        int depth = 0;
        const char *obj_end = p;
        while (*obj_end) {
            if (*obj_end == '{') depth++;
            else if (*obj_end == '}') { depth--; if (depth == 0) break; }
            obj_end++;
        }
        if (depth != 0) break;

        /* Extract fields. logical_path is OPTIONAL (the guest-pe manifest may
         * omit it or set it to a bare filename). */
        char asset_path[512], expected_sha[128], logical_path[512];
        if (extract_field(obj_start, "asset_path", asset_path, sizeof(asset_path)) != 0 ||
            extract_field(obj_start, "sha256", expected_sha, sizeof(expected_sha)) != 0) {
            p = obj_end + 1;
            continue;
        }
        logical_path[0] = '\0';
        extract_field(obj_start, "logical_path", logical_path, sizeof(logical_path));

        /* Source = assets_dir/asset_path, Dest = cache_dir/asset_path. */
        char src[WINE_SPIKE_PATH_MAX], dest[WINE_SPIKE_PATH_MAX];
        snprintf(src, sizeof(src), "%s/%s", assets_dir, asset_path);
        snprintf(dest, sizeof(dest), "%s/%s", cache_dir, asset_path);

        /* Create dest's parent dir. */
        char parent[WINE_SPIKE_PATH_MAX];
        snprintf(parent, sizeof(parent), "%s", dest);
        char *slash = strrchr(parent, '/');
        if (slash) { *slash = '\0'; mkdir_p(parent); }

        /* Check if dest already exists + matches hash (skip re-materialize). */
        char existing_hash[65];
        int existing_ok = (access(dest, R_OK) == 0 && sha256_file(dest, existing_hash) == 0 &&
                          strcmp(existing_hash, expected_sha) == 0);

        if (existing_ok) {
            verified++;
        } else {
            /* Materialize via atomic copy. */
            if (atomic_copy(src, dest) != 0) {
                LOGE("materialize failed: %s -> %s", src, dest);
                failed++;
                p = obj_end + 1;
                continue;
            }
            /* Verify the copy. */
            char actual_hash[65];
            if (sha256_file(dest, actual_hash) != 0 ||
                strcmp(actual_hash, expected_sha) != 0) {
                LOGE("hash mismatch after materialize: %s (expected %s, got %s)",
                     dest, expected_sha, actual_hash);
                failed++;
                p = obj_end + 1;
                continue;
            } else {
                materialized++;
                verified++;
            }
        }

        /* Connect to the logical Wine tree via symlink (if a logical_path is
         * present and a tree_dir was supplied). This is the S-2 fix. */
        if (tree_dir && logical_path[0]) {
            /* Bare-filename logical paths (e.g. the self-test PE) map under the
             * tree root as-is. Path-bearing logical paths (lib/wine/...) map
             * under the tree root too. */
            char link_path[WINE_SPIKE_PATH_MAX];
            snprintf(link_path, sizeof(link_path), "%s/%s", tree_dir, logical_path);
            if (install_tree_symlink(dest, link_path) == 0) linked++;
        }

        p = obj_end + 1;
    }

    LOGI("materialize_pe_cache: %d verified (%d newly materialized), %d linked, %d failed",
         verified, materialized, linked, failed);
    if (failed > 0) return WINE_SPIKE_ERR_VERIFY;
    return WINE_SPIKE_OK;
}

int wine_spike_verify_pe_cache(const char *cache_dir, const char *manifest_json) {
    if (!cache_dir || !manifest_json) return WINE_SPIKE_ERR_ARGS;

    const char *arr = find_entries_array(manifest_json);
    if (!arr) return WINE_SPIKE_ERR_IO;

    int checked = 0, mismatches = 0;
    const char *p = arr + 1;

    while (*p && *p != ']') {
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == ',') p++;
        if (*p == ']') break;
        if (*p != '{') { p++; continue; }

        const char *obj_start = p;
        int depth = 0;
        const char *obj_end = p;
        while (*obj_end) {
            if (*obj_end == '{') depth++;
            else if (*obj_end == '}') { depth--; if (depth == 0) break; }
            obj_end++;
        }
        if (depth != 0) break;

        char asset_path[512], expected_sha[128];
        if (extract_field(obj_start, "asset_path", asset_path, sizeof(asset_path)) != 0 ||
            extract_field(obj_start, "sha256", expected_sha, sizeof(expected_sha)) != 0) {
            p = obj_end + 1;
            continue;
        }

        char dest[WINE_SPIKE_PATH_MAX];
        snprintf(dest, sizeof(dest), "%s/%s", cache_dir, asset_path);

        char actual_hash[65];
        if (sha256_file(dest, actual_hash) != 0 || strcmp(actual_hash, expected_sha) != 0) {
            LOGW("verify mismatch: %s", dest);
            mismatches++;
        }
        checked++;
        p = obj_end + 1;
    }

    LOGI("verify_pe_cache: %d checked, %d mismatches", checked, mismatches);
    return (mismatches == 0) ? WINE_SPIKE_OK : WINE_SPIKE_ERR_VERIFY;
}

/*
 * Resolve the cache path for a given asset basename. Walks the manifest entries
 * and returns the first entry whose asset_path basename matches <asset_name>
 * (e.g. "kernel32.dll" → "wine-pe/x86_64-windows/kernel32.dll"), then formats
 * <cache_dir>/<asset_path> into out. Returns WINE_SPIKE_OK on match.
 *
 * Used by the S-2 mismatch-repair test: it resolves a known module, corrupts
 * that cache file, proves verify_pe_cache detects the mismatch, runs
 * materialize_pe_cache_into_tree to atomically rematerialize it, and re-verifies
 * the canonical SHA-256.
 */
int wine_spike_resolve_cache_path(const char *cache_dir,
                                  const char *manifest_json,
                                  const char *asset_name,
                                  char *out, size_t out_cap) {
    if (!cache_dir || !manifest_json || !asset_name || !out || out_cap == 0)
        return WINE_SPIKE_ERR_ARGS;
    out[0] = '\0';

    const char *arr = find_entries_array(manifest_json);
    if (!arr) return WINE_SPIKE_ERR_IO;

    /* Basename of asset_name (in case the caller passes a path). */
    const char *want_base = strrchr(asset_name, '/');
    want_base = want_base ? want_base + 1 : asset_name;

    const char *p = arr + 1;
    while (*p && *p != ']') {
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == ',') p++;
        if (*p == ']') break;
        if (*p != '{') { p++; continue; }

        const char *obj_start = p;
        int depth = 0;
        const char *obj_end = p;
        while (*obj_end) {
            if (*obj_end == '{') depth++;
            else if (*obj_end == '}') { depth--; if (depth == 0) break; }
            obj_end++;
        }
        if (depth != 0) break;

        char asset_path[512];
        if (extract_field(obj_start, "asset_path", asset_path, sizeof(asset_path)) != 0) {
            p = obj_end + 1;
            continue;
        }
        const char *base = strrchr(asset_path, '/');
        base = base ? base + 1 : asset_path;
        if (strcmp(base, want_base) == 0) {
            snprintf(out, out_cap, "%s/%s", cache_dir, asset_path);
            return WINE_SPIKE_OK;
        }
        p = obj_end + 1;
    }
    return WINE_SPIKE_ERR_IO;
}
