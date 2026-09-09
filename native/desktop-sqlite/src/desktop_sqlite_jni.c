/*
 * desktop_sqlite_jni.c — the Windows desktop SQLite execution seam.
 *
 * A deliberately tiny JNI surface over the repo-pinned SQLite
 * amalgamation (3.46.1), compiled with the same PRODUCTION define set
 * as the fidelity harness (tools/sqlite_exec_file.c; the set is pinned
 * by tests/test_sqlite_seeding.py). The desktop engine twin uses it to
 * run the pure DatabaseSqliteControlPlane legs — seed replay, integrity
 * gate, revision probes, ledger DDL — against the exact engine build
 * the realm DLLs link, the role android.database.sqlite plays on
 * Android. The realm DLLs keep owning the LIVE databases once booted;
 * this seam is the engine-side executor that creates, seals, and
 * repairs them around the servers.
 *
 * Semantics that mirror android.database.sqlite deliberately:
 *  - execNative takes EXACTLY ONE statement: trailing SQL, bind
 *    parameters, and row-returning statements are rejected (the
 *    framework execSQL posture; row-returning PRAGMAs such as
 *    journal_mode / wal_checkpoint go through the query entry points);
 *  - every failure raises IllegalStateException carrying sqlite3's own
 *    message (sqlite3_errmsg16) plus the extended result code; the
 *    engine layers statement locality (index + byte offset) on top,
 *    exactly like the Android executeSeedStatement wrapper.
 *
 * All text crosses the boundary as UTF-16 — sqlite3_open16 for paths,
 * sqlite3_prepare16_v2 for SQL, sqlite3_column_text16 and errmsg16 for
 * results — because Windows usernames put non-ASCII into
 * %LOCALAPPDATA% paths and JNI's modified-UTF-8 string helpers would
 * pass CESU-8 (not UTF-8) to SQLite for astral-plane characters.
 * sqlite3_open16 defaults NEW databases to UTF-16 storage, so open
 * forces "PRAGMA encoding = 'UTF-8'" while the database is still
 * empty: the desktop lane then produces byte-identical database files
 * to the Android lane (SQLiteDatabase opens through the UTF-8 entry
 * point).
 *
 * SQLITE_THREADSAFE=2 in the production set means connections are not
 * safe for concurrent use from multiple threads; the desktop engine
 * drives every database from its single database thread — the same
 * discipline the Android engine keeps.
 */

#include <jni.h>
#include <sqlite3.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Raise IllegalStateException("sqlite rc=<rc> <note>: <errmsg16>").
 * `note` is static ASCII from this file; sqlite's own message carries
 * all dynamic detail. No-op when an exception is already pending. */
static void raise_sqlite(JNIEnv* env, sqlite3* db, int rc, char const* note)
{
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    char prefix[160];
    _snprintf(prefix, sizeof(prefix), "sqlite rc=%d %s: ", rc, note);
    jsize plen = (jsize)strlen(prefix);
    jchar const* emsg = db ? (jchar const*)sqlite3_errmsg16(db) : NULL;
    jsize mlen = 0;
    if (emsg != NULL) {
        while (emsg[mlen] != 0) {
            mlen++;
        }
    }
    jchar* joined = (jchar*)malloc(sizeof(jchar) * (size_t)(plen + mlen));
    if (joined == NULL) {
        return;
    }
    for (jsize i = 0; i < plen; i++) {
        joined[i] = (jchar)(unsigned char)prefix[i];
    }
    for (jsize i = 0; i < mlen; i++) {
        joined[plen + i] = emsg[i];
    }
    jstring message = (*env)->NewString(env, joined, plen + mlen);
    free(joined);
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls == NULL || message == NULL) {
        return;
    }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
    if (ctor != NULL) {
        (*env)->Throw(env, (*env)->NewObject(env, cls, ctor, message));
    }
}

JNIEXPORT jlong JNICALL
Java_com_pocketrealm_database_DesktopSqlite_openNative(JNIEnv* env, jclass cls, jstring path)
{
    (void)cls;
    /* GetStringChars is NOT guaranteed NUL-terminated, but
     * sqlite3_open16 takes a terminated UTF-16 path — copy with an
     * explicit terminator (non-ASCII %LOCALAPPDATA% usernames ride
     * this path). */
    jsize len = (*env)->GetStringLength(env, path);
    jchar* terminated = (jchar*)malloc(sizeof(jchar) * (size_t)(len + 1));
    if (terminated == NULL) {
        return 0;
    }
    (*env)->GetStringRegion(env, path, 0, len, terminated);
    if ((*env)->ExceptionCheck(env)) {
        free(terminated);
        return 0;
    }
    terminated[len] = 0;
    sqlite3* db = NULL;
    int rc = sqlite3_open16(terminated, &db);
    free(terminated);
    if (rc != SQLITE_OK) {
        raise_sqlite(env, db, rc, "at open");
        sqlite3_close(db);
        return 0;
    }
    sqlite3_extended_result_codes(db, 1);
    /* open16 would create NEW databases with UTF-16 storage; force the
     * encoding while the file is still empty so the desktop lane's
     * files are byte-identical to the Android lane's. */
    char const* encoding_err = NULL;
    rc = sqlite3_exec(db, "PRAGMA encoding = 'UTF-8';", NULL, NULL, &encoding_err);
    if (rc != SQLITE_OK) {
        raise_sqlite(env, db, rc, "forcing UTF-8 encoding at open");
        sqlite3_close(db);
        return 0;
    }
    return (jlong)(intptr_t)db;
}

JNIEXPORT jint JNICALL
Java_com_pocketrealm_database_DesktopSqlite_closeNative(JNIEnv* env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    sqlite3* db = (sqlite3*)(intptr_t)handle;
    if (db == NULL) {
        return SQLITE_MISUSE;
    }
    return sqlite3_close(db);
}

/* Shared prepare for the exec/query entry points: returns SQLITE_OK
 * with *out_stmt (or raises); the tail and bind checks belong to the
 * callers. The SQL text is passed with its EXPLICIT byte length —
 * GetStringChars is not guaranteed NUL-terminated, and prepare16_v2
 * with nByte=-1 would walk off the buffer (a latent crash the unit
 * suite cannot catch: freshly-allocated test heaps happen to supply
 * the missing terminator). The tail is inspected BEFORE the chars are
 * released, bounded by the buffer end. */
static int prepare_one(JNIEnv* env, sqlite3* db, jstring sql, sqlite3_stmt** out_stmt)
{
    jsize len = (*env)->GetStringLength(env, sql);
    jchar const* chars = (*env)->GetStringChars(env, sql, NULL);
    if (chars == NULL) {
        return SQLITE_NOMEM;
    }
    void const* tail = NULL;
    int rc = sqlite3_prepare16_v2(db, chars, (int)(len * (jsize)sizeof(jchar)),
                                  out_stmt, &tail);
    int tail_has_more = 0;
    if (rc == SQLITE_OK && tail != NULL) {
        jchar const* t = (jchar const*)tail;
        jchar const* end = chars + len;
        while (t < end) {
            if (*t != ' ' && *t != '\t' && *t != '\n' && *t != '\r' &&
                *t != '\f' && *t != '\v') {
                tail_has_more = 1;
                break;
            }
            t++;
        }
    }
    (*env)->ReleaseStringChars(env, sql, chars);
    if (rc != SQLITE_OK) {
        raise_sqlite(env, db, rc, "at prepare");
        return rc;
    }
    if (*out_stmt == NULL) {
        raise_sqlite(env, db, SQLITE_MISUSE, "not a statement (empty or comment-only SQL)");
        return SQLITE_MISUSE;
    }
    if (sqlite3_bind_parameter_count(*out_stmt) != 0) {
        sqlite3_finalize(*out_stmt);
        *out_stmt = NULL;
        raise_sqlite(env, db, SQLITE_MISUSE, "bind parameters are not supported by the seam");
        return SQLITE_MISUSE;
    }
    if (tail_has_more) {
        sqlite3_finalize(*out_stmt);
        *out_stmt = NULL;
        raise_sqlite(env, db, SQLITE_MISUSE, "exec/query take exactly one statement");
        return SQLITE_MISUSE;
    }
    return SQLITE_OK;
}

JNIEXPORT void JNICALL
Java_com_pocketrealm_database_DesktopSqlite_execNative(JNIEnv* env, jclass cls, jlong handle, jstring sql)
{
    (void)cls;
    sqlite3* db = (sqlite3*)(intptr_t)handle;
    if (db == NULL) {
        raise_sqlite(env, NULL, SQLITE_MISUSE, "connection handle is null");
        return;
    }
    sqlite3_stmt* stmt = NULL;
    if (prepare_one(env, db, sql, &stmt) != SQLITE_OK) {
        return;
    }
    int rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) {
        sqlite3_finalize(stmt);
        raise_sqlite(env, db, SQLITE_MISUSE,
                     "statement returns rows; use queryText/queryLong");
        return;
    }
    if (rc != SQLITE_DONE) {
        raise_sqlite(env, db, rc, "at step");
        sqlite3_finalize(stmt);
        return;
    }
    rc = sqlite3_finalize(stmt);
    if (rc != SQLITE_OK) {
        raise_sqlite(env, db, rc, "at finalize");
    }
}

/* Shared first-row reader: prepares, steps once, and hands the stmt to
 * the caller (which reads the column, finalizes). Returns 0 when a row
 * is ready, 1 when the statement produced no rows — including the
 * no-column policy PRAGMAs (busy_timeout and friends), matching the
 * Android execPragma posture of an empty cursor — and -1 on error. */
static int step_first_row(JNIEnv* env, sqlite3* db, jstring sql, sqlite3_stmt** out_stmt)
{
    if (prepare_one(env, db, sql, out_stmt) != SQLITE_OK) {
        return -1;
    }
    int rc = sqlite3_step(*out_stmt);
    if (rc == SQLITE_DONE) {
        sqlite3_finalize(*out_stmt);
        *out_stmt = NULL;
        return 1;
    }
    if (rc != SQLITE_ROW) {
        raise_sqlite(env, db, rc, "at step");
        sqlite3_finalize(*out_stmt);
        *out_stmt = NULL;
        return -1;
    }
    return 0;
}

JNIEXPORT jobject JNICALL
Java_com_pocketrealm_database_DesktopSqlite_queryTextNative(JNIEnv* env, jclass cls, jlong handle, jstring sql)
{
    (void)cls;
    sqlite3* db = (sqlite3*)(intptr_t)handle;
    if (db == NULL) {
        raise_sqlite(env, NULL, SQLITE_MISUSE, "connection handle is null");
        return NULL;
    }
    sqlite3_stmt* stmt = NULL;
    int stepped = step_first_row(env, db, sql, &stmt);
    if (stepped != 0) {
        return NULL; /* no rows, or the error already raised */
    }
    jobject result = NULL;
    if (sqlite3_column_type(stmt, 0) != SQLITE_NULL) {
        jchar const* text = (jchar const*)sqlite3_column_text16(stmt, 0);
        int bytes = sqlite3_column_bytes16(stmt, 0);
        if (text != NULL && bytes >= 0) {
            result = (*env)->NewString(env, text, (jsize)(bytes / 2));
        }
    }
    sqlite3_finalize(stmt);
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_pocketrealm_database_DesktopSqlite_queryLongNative(JNIEnv* env, jclass cls, jlong handle, jstring sql)
{
    (void)cls;
    sqlite3* db = (sqlite3*)(intptr_t)handle;
    if (db == NULL) {
        raise_sqlite(env, NULL, SQLITE_MISUSE, "connection handle is null");
        return NULL;
    }
    sqlite3_stmt* stmt = NULL;
    int stepped = step_first_row(env, db, sql, &stmt);
    if (stepped != 0) {
        return NULL; /* no rows, or the error already raised */
    }
    jobject result = NULL;
    if (sqlite3_column_type(stmt, 0) != SQLITE_NULL) {
        jlong value = (jlong)sqlite3_column_int64(stmt, 0);
        jclass box = (*env)->FindClass(env, "java/lang/Long");
        if (box != NULL) {
            jmethodID value_of = (*env)->GetStaticMethodID(env, box, "valueOf", "(J)Ljava/lang/Long;");
            if (value_of != NULL) {
                result = (*env)->CallStaticObjectMethod(env, box, value_of, value);
            }
        }
    }
    sqlite3_finalize(stmt);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_database_DesktopSqlite_versionNative(JNIEnv* env, jclass cls)
{
    (void)cls;
    /* sqlite3_libversion() is compile-time ASCII — NewStringUTF is safe
     * for exactly this string. */
    return (*env)->NewStringUTF(env, sqlite3_libversion());
}
