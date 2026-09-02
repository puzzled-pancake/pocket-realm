/*
 * Fidelity-harness executor: run a seed transcript
 * through the PINNED amalgamation.
 *
 * The seeder itself executes via Python's stdlib sqlite3, which may be a
 * different engine build than the one the runtime links. This executor
 * closes that gap: it reads each transcript file whole and executes it
 * statement-by-statement on the pinned amalgamation (same define set
 * the runtime and the other fixtures compile with), so every
 * translated statement is proven parseable AND executable by the exact
 * engine the APK ships.
 *
 * Statement boundaries come from sqlite3_complete() - the ENGINE's own
 * literal-aware detector, the same one sqlite3_exec uses internally -
 * which is also a deliberate cross-check of the translator's splitter:
 * a boundary mis-placed inside a string literal fails HERE, loudly,
 * with the exact statement index and byte offset.
 *
 * Usage: sqlite_exec_file <db.sqlite> <transcript.sql> [...more.sql]
 * The db file must NOT exist beforehand (fresh-seed discipline).
 * Exit 0 = all statements executed cleanly.
 */
#include <sqlite3.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32) || defined(WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <unistd.h>
#endif

static char* read_file_whole(char const* path, long* out_len)
{
    FILE* fh = fopen(path, "rb");
    if (!fh) {
        fprintf(stderr, "exec_file: cannot open %s\n", path);
        return NULL;
    }
    if (fseek(fh, 0, SEEK_END) != 0) {
        fclose(fh);
        return NULL;
    }
    long len = ftell(fh);
    if (len < 0 || fseek(fh, 0, SEEK_SET) != 0) {
        fclose(fh);
        return NULL;
    }
    char* buf = (char*)malloc((size_t)len + 1);
    if (!buf) {
        fclose(fh);
        return NULL;
    }
    if (fread(buf, 1, (size_t)len, fh) != (size_t)len) {
        free(buf);
        fclose(fh);
        return NULL;
    }
    fclose(fh);
    buf[len] = '\0';
    /* transcripts are NUL-free UTF-8; a stray NUL would truncate the
     * tail silently - treat it as corruption. */
    if (memchr(buf, '\0', (size_t)len) != NULL) {
        fprintf(stderr, "exec_file: %s contains an embedded NUL\n", path);
        free(buf);
        return NULL;
    }
    *out_len = len;
    return buf;
}

/*
 * sqlite3_exec's literal-aware boundary logic, applied per statement:
 * sqlite3_complete() is the ENGINE's own statement-end detector (the
 * same one sqlite3_exec uses), and executing each completed statement
 * via prepare/step gives exact error locality - which statement, at
 * which byte offset - instead of sqlite3_exec's region-less failure.
 */
static int exec_transcript(sqlite3* db, char const* path)
{
    long len = 0;
    char* sql = read_file_whole(path, &len);
    if (!sql) {
        return 1;
    }
    /* One transaction per transcript: the transcripts are replay-order
     * units by construction, and journal_mode=DELETE + autocommit made
     * the ~1.1M-statement augmented characters transcript cost one
     * journal cycle PER INSERT (the 2026-08-28 augmented-seed window
     * blew a 600 s replay budget before this). Boundary validation
     * (sqlite3_complete per statement) is unchanged; failures roll the
     * transcript back so the caller sees the database exactly as the
     * kernel's single-transaction replay would leave it. */
    char* txn_err = NULL;
    if (sqlite3_exec(db, "BEGIN", NULL, NULL, &txn_err) != SQLITE_OK) {
        fprintf(stderr, "exec_file: BEGIN failed: %s\n",
                txn_err ? txn_err : "?");
        sqlite3_free(txn_err);
        free(sql);
        return 1;
    }
    char const* p = sql;
    char const* end = sql + len;
    long stmt_index = 0;
    int failures = 0;
    while (p < end && failures == 0) {
        /* skip whitespace/line breaks between statements (the
         * transcripts end with ";\n") */
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\r'
                           || *p == '\n')) {
            ++p;
        }
        if (p >= end) {
            break;
        }
        /* find the next statement end (sqlite3_complete needs the
         * candidate to be NUL-terminated) */
        char const* q = p;
        while (q < end) {
            if (*q == ';') {
                size_t cap = (size_t)(q - p) + 2;
                char* cand = (char*)malloc(cap);
                if (!cand) {
                    fprintf(stderr, "exec_file: oom\n");
                    free(sql);
                    return 1;
                }
                memcpy(cand, p, (size_t)(q - p) + 1);
                cand[q - p + 1] = '\0';
                int done = sqlite3_complete(cand);
                free(cand);
                if (done) {
                    break;
                }
            }
            ++q;
        }
        if (q >= end) {
            /* trailing fragment without a completing ';' */
            if (p < end) {
                fprintf(stderr, "exec_file: %s: trailing incomplete "
                        "statement at offset %ld\n", path,
                        (long)(p - sql));
                ++failures;
            }
            break;
        }
        size_t stmt_len = (size_t)(q - p) + 1;
        char* stmt = (char*)malloc(stmt_len + 1);
        if (!stmt) {
            fprintf(stderr, "exec_file: oom\n");
            free(sql);
            return 1;
        }
        memcpy(stmt, p, stmt_len);
        stmt[stmt_len] = '\0';
        sqlite3_stmt* handle = NULL;
        int rc = sqlite3_prepare_v2(db, stmt, -1, &handle, NULL);
        if (rc != SQLITE_OK) {
            fprintf(stderr, "exec_file: %s: statement #%ld (offset %ld) "
                    "failed to prepare: %s\n    %.160s\n", path,
                    stmt_index, (long)(p - sql), sqlite3_errmsg(db),
                    stmt);
            ++failures;
        } else {
            rc = sqlite3_step(handle);
            if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
                fprintf(stderr, "exec_file: %s: statement #%ld (offset "
                        "%ld) failed: %s\n    %.160s\n", path,
                        stmt_index, (long)(p - sql), sqlite3_errmsg(db),
                        stmt);
                ++failures;
            }
            sqlite3_finalize(handle);
        }
        free(stmt);
        p = q + 1;
        ++stmt_index;
    }
    if (failures == 0) {
        if (sqlite3_exec(db, "COMMIT", NULL, NULL, &txn_err) != SQLITE_OK) {
            fprintf(stderr, "exec_file: COMMIT failed: %s\n",
                    txn_err ? txn_err : "?");
            sqlite3_free(txn_err);
            free(sql);
            return 1;
        }
        printf("exec_file: %s ok (%ld statements)\n", path, stmt_index);
    } else {
        sqlite3_exec(db, "ROLLBACK", NULL, NULL, NULL);
    }
    free(sql);
    return failures;
}

int main(int argc, char** argv)
{
    if (argc < 3) {
        fprintf(stderr,
                "usage: %s <db.sqlite> <transcript.sql> [...more.sql]\n",
                argv[0]);
        return 2;
    }
#if defined(_WIN32) || defined(WIN32)
    if (GetFileAttributesA(argv[1]) != INVALID_FILE_ATTRIBUTES) {
#else
    if (access(argv[1], F_OK) == 0) {
#endif
        fprintf(stderr, "exec_file: %s already exists (fresh-seed "
                "discipline: remove it first)\n", argv[1]);
        return 2;
    }

    sqlite3* db = NULL;
    int rc = sqlite3_open_v2(argv[1], &db,
                             SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE,
                             NULL);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "exec_file: open %s failed: %s\n", argv[1],
                db ? sqlite3_errmsg(db) : "oom");
        sqlite3_close(db);
        return 1;
    }
    sqlite3_busy_timeout(db, 5000);
    char const* pragmas[] = {
        "PRAGMA journal_mode=DELETE",
        "PRAGMA foreign_keys=OFF",
    };
    for (size_t i = 0; i < sizeof(pragmas) / sizeof(pragmas[0]); ++i) {
        char* err = NULL;
        rc = sqlite3_exec(db, pragmas[i], NULL, NULL, &err);
        if (rc != SQLITE_OK) {
            fprintf(stderr, "exec_file: %s failed: %s\n", pragmas[i],
                    err ? err : "?");
            sqlite3_free(err);
            sqlite3_close(db);
            return 1;
        }
        sqlite3_free(err);
    }

    int failures = 0;
    for (int a = 2; a < argc && failures == 0; ++a) {
        failures += exec_transcript(db, argv[a]);
    }

    if (failures == 0) {
        /* Post-replay corruption gate: the same
         * integrity contract first boot enforces, closing
         * the harness with a whole-database consistency proof for
         * near-zero cost (the replay itself dominates this leg). */
        sqlite3_stmt* chk = NULL;
        rc = sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1,
                                &chk, NULL);
        if (rc != SQLITE_OK) {
            fprintf(stderr, "exec_file: integrity_check prepare failed: "
                    "%s\n", sqlite3_errmsg(db));
            sqlite3_close(db);
            return 1;
        }
        int bad = 0;
        while ((rc = sqlite3_step(chk)) == SQLITE_ROW) {
            char const* txt = (char const*)sqlite3_column_text(chk, 0);
            if (txt && strcmp(txt, "ok") != 0) {
                fprintf(stderr, "exec_file: integrity_check: %s\n", txt);
                bad = 1;
            }
        }
        sqlite3_finalize(chk);
        if (bad || (rc != SQLITE_DONE)) {
            fprintf(stderr, "exec_file: integrity_check failed (rc=%d)\n",
                    rc);
            sqlite3_close(db);
            return 1;
        }
        long long total_changes = sqlite3_total_changes64(db);
        rc = sqlite3_close(db);
        if (rc != SQLITE_OK) {
            fprintf(stderr, "exec_file: close failed: %s\n",
                    sqlite3_errstr(rc));
            return 1;
        }
        printf("exec_file: all transcripts applied cleanly "
               "(total changes: %lld)\n", total_changes);
        return 0;
    }
    sqlite3_close(db);
    return 1;
}
