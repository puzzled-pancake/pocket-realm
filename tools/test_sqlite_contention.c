/*
 * Two-handle SQLite contention test.
 *
 * Reproduces the :realm + :world cross-writer reality on the LoginDatabase
 * pattern and the same-connection
 * Query-iteration requirement. Two kinds of handles:
 *   - independent connections (cross-process pattern), and
 *   - ONE connection shared by reader+writer threads through a mutex that
 *     emulates CMaNGOS's SqlConnection::Lock (the query-pool pattern the
 *     hardened materializing QueryResult relies on).
 *
 * Compiled for the HOST against the pinned amalgamation with the PRODUCTION
 * define set (-DSQLITE_THREADSAFE=2, -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=2) by
 * tests/test_sqlite_hardening.py; exit code 0 = pass.
 */
#include <sqlite3.h>

#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <time.h>
#endif

static char const* g_dbfile;
static atomic_int g_failures;
/* Stand-in for SqlConnection::Lock around the SHARED connection (T6). */
static pthread_mutex_t g_conn_lock = PTHREAD_MUTEX_INITIALIZER;

#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "FAIL %s (line %d)\n", msg, __LINE__); atomic_fetch_add(&g_failures, 1); } \
} while (0)

static sqlite3* open_policy_connection(void)
{
    sqlite3* db = NULL;
    if (sqlite3_open(g_dbfile, &db) != SQLITE_OK) return NULL;
    sqlite3_exec(db, "PRAGMA journal_mode=WAL;", 0, 0, 0);
    sqlite3_exec(db, "PRAGMA synchronous=FULL;", 0, 0, 0);
    sqlite3_exec(db, "PRAGMA foreign_keys=OFF;", 0, 0, 0);
    sqlite3_busy_timeout(db, 500);
    return db;
}

/* Forces COMMIT failure on the installing connection: a non-zero return
 * converts the COMMIT into a ROLLBACK (T7's deterministic injection). */
static int reject_commit(void* ctx)
{
    (void)ctx;
    return 1;
}

/* Aborts a running statement after `limit` VDBE ops: the stepping SELECT
 * then ends with SQLITE_INTERRUPT mid-scan (T8's deterministic injection
 * of the state the ScanComplete guard exists for). */
struct progress_abort { int ops; int limit; };

static int abort_progress(void* raw)
{
    struct progress_abort* p = (struct progress_abort*)raw;
    return ++p->ops > p->limit;
}

static int exec_retry(sqlite3* db, char const* sql)
{
    /* The hardened connection layer's Execute semantics: busy_timeout does
     * the waiting; a small retry loop absorbs a second writer arriving in
     * the same window. */
    for (int attempt = 0; attempt < 4; ++attempt)
    {
        char* err = NULL;
        int rc = sqlite3_exec(db, sql, 0, 0, &err);
        if (rc == SQLITE_OK) { sqlite3_free(err); return 0; }
        sqlite3_free(err);
        if (rc != SQLITE_BUSY && rc != SQLITE_LOCKED) return rc;
    }
    return SQLITE_BUSY;
}

static void nap_ms(int ms)
{
#ifdef _WIN32
    Sleep(ms);
#else
    struct timespec t = {ms / 1000, (ms % 1000) * 1000 * 1000};
    nanosleep(&t, NULL);
#endif
}

struct writer_arg { sqlite3* db; int handle; int count; };

static void* thread_writer(void* raw)
{
    struct writer_arg* arg = (struct writer_arg*)raw;
    char sql[128];
    for (int i = 0; i < arg->count; ++i)
    {
        snprintf(sql, sizeof sql,
                 "INSERT INTO t_writes(handle, seq) VALUES(%d, %d);",
                 arg->handle, i);
        int rc = exec_retry(arg->db, sql);
        if (rc != SQLITE_OK) { fprintf(stderr, "FAIL threaded write rc=%d\n", rc); atomic_fetch_add(&g_failures, 1); }
    }
    return NULL;
}

struct hold_arg { sqlite3* db; atomic_int* locked; int hold_ms; atomic_int* release; };

/* Holds the write lock, signaling once BEGIN IMMEDIATE won. With `release`
 * set, holds deterministically until the flag is set (the main thread
 * releases after OBSERVING the contention it needs - no timed window a
 * descheduled main thread can outlast); else holds hold_ms. */
static void* hold_then_commit(void* raw)
{
    struct hold_arg* arg = (struct hold_arg*)raw;
    if (exec_retry(arg->db, "BEGIN IMMEDIATE;") != 0)
    {
        atomic_fetch_add(&g_failures, 1);
        atomic_store(arg->locked, -1);
        return NULL;
    }
    if (exec_retry(arg->db, "INSERT INTO t_writes(handle, seq) VALUES(9, 0);") != 0)
        atomic_fetch_add(&g_failures, 1);
    atomic_store(arg->locked, 1);
    if (arg->release)
    {
        while (!atomic_load(arg->release)) nap_ms(1);
    }
    else
    {
        nap_ms(arg->hold_ms);
    }
    if (exec_retry(arg->db, "COMMIT;") != 0) atomic_fetch_add(&g_failures, 1);
    return NULL;
}

/* Wait for the holder to actually own the lock (handshake, not sleeps). */
static int wait_locked(atomic_int* locked)
{
    for (int spin = 0; spin < 5000; ++spin)
    {
        int v = atomic_load(locked);
        if (v != 0) return v;
        nap_ms(1);
    }
    return 0;
}

/*
 * T6: concurrent same-connection Query iteration.
 * One connection is shared between a reader thread and a
 * writer thread exactly the way the 1-connection query pool shares it:
 * every sqlite call on the shared handle happens under the connection
 * mutex, and the reader MATERIALIZES the full result (steps to DONE)
 * before releasing it - the hardened QueryResultSqlite contract. The
 * writer's interleaved transactions must all land; the reader's snapshots
 * must be internally consistent (ascending seq, no torn reads).
 */
struct shared_arg { sqlite3* db; atomic_int writer_done; atomic_int max_rows; };

static void* shared_reader(void* raw)
{
    struct shared_arg* arg = (struct shared_arg*)raw;
    int max_rows = 0;
    for (;;)
    {
        pthread_mutex_lock(&g_conn_lock);
        sqlite3_stmt* stmt = NULL;
        if (sqlite3_prepare_v2(arg->db,
                "SELECT seq FROM t_writes WHERE handle=6 ORDER BY seq;", -1,
                &stmt, NULL) == SQLITE_OK)
        {
            long expected = 0;
            int inconsistent = 0, rows = 0;
            while (sqlite3_step(stmt) == SQLITE_ROW)
            {
                long seq = sqlite3_column_int64(stmt, 0);
                if (seq != expected) inconsistent = 1; /* torn snapshot */
                expected = seq + 1;
                ++rows;
            }
            sqlite3_finalize(stmt);
            CHECK(!inconsistent, "T6 reader saw a torn snapshot");
            if (rows > max_rows) max_rows = rows;
        }
        else
        {
            CHECK(0, "T6 reader prepare failed");
        }
        /* Done is checked INSIDE the lock and only AFTER a snapshot: the
         * writer sets it under the same lock after its final commit, so
         * observing it proves this snapshot was mutex-ordered after all 50
         * writes - the final snapshot must have seen every row. */
        int done = atomic_load(&arg->writer_done);
        pthread_mutex_unlock(&g_conn_lock);
        if (done) break;
        nap_ms(1);
    }
    atomic_store(&arg->max_rows, max_rows);
    return NULL;
}

static void* shared_writer(void* raw)
{
    struct shared_arg* arg = (struct shared_arg*)raw;
    for (int i = 0; i < 50; ++i)
    {
        pthread_mutex_lock(&g_conn_lock);
        int rc = exec_retry(arg->db, "BEGIN IMMEDIATE;");
        if (rc == 0)
        {
            char sql[128];
            snprintf(sql, sizeof sql,
                     "INSERT INTO t_writes(handle, seq) VALUES(6, %d);", i);
            rc = exec_retry(arg->db, sql);
            int rc2 = exec_retry(arg->db, "COMMIT;");
            CHECK(rc == 0 && rc2 == 0, "T6 shared-connection write");
        }
        else
        {
            CHECK(0, "T6 shared BEGIN failed");
        }
        if (i == 49)
            atomic_store(&arg->writer_done, 1); /* under the lock: see reader */
        pthread_mutex_unlock(&g_conn_lock);
        nap_ms(1);
    }
    return NULL;
}

static long count_rows(sqlite3* db, char const* table, char const* where)
{
    char sql[160];
    snprintf(sql, sizeof sql, "SELECT COUNT(*) FROM %s WHERE %s;", table, where);
    sqlite3_stmt* stmt = NULL;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) != SQLITE_OK) return -1;
    long rows = -1;
    if (sqlite3_step(stmt) == SQLITE_ROW) rows = sqlite3_column_int64(stmt, 0);
    sqlite3_finalize(stmt);
    return rows;
}

int main(int argc, char** argv)
{
    if (argc != 2) { fprintf(stderr, "usage: %s <dbfile>\n", argv[0]); return 2; }
    g_dbfile = argv[1];
    sqlite3_enable_shared_cache(0);

    sqlite3* a = open_policy_connection();
    sqlite3* b = open_policy_connection();
    CHECK(a && b, "both policy connections opened");
    if (!a || !b) return 1;

    CHECK(exec_retry(a, "CREATE TABLE t_writes(id INTEGER PRIMARY KEY, handle INT, seq INT);") == 0, "create");
    CHECK(exec_retry(a, "CREATE TABLE t_blob(id INTEGER PRIMARY KEY, data TEXT);") == 0, "create blob table");

    /* T1: interleaved sequential writes from both handles, nothing dropped. */
    for (int i = 0; i < 50; ++i)
    {
        char sql[128];
        snprintf(sql, sizeof sql, "INSERT INTO t_writes(handle, seq) VALUES(%d, %d);", i % 2, i / 2);
        CHECK(exec_retry(i % 2 ? b : a, sql) == 0, "interleaved write");
    }
    CHECK(count_rows(a, "t_writes", "1=1") == 50, "T1 all sequential writes survived");

    /* T2: concurrent threaded writers on independent handles. */
    {
        pthread_t t1, t2;
        struct writer_arg args[2] = {{a, 3, 200}, {b, 4, 200}};
        pthread_create(&t1, NULL, thread_writer, &args[0]);
        pthread_create(&t2, NULL, thread_writer, &args[1]);
        pthread_join(t1, NULL);
        pthread_join(t2, NULL);
        CHECK(count_rows(a, "t_writes", "handle=3") == 200, "T2 handle3 writes");
        CHECK(count_rows(a, "t_writes", "handle=4") == 200, "T2 handle4 writes");
        CHECK(count_rows(a, "t_writes", "1=1") == 450, "T1+T2 all writes survived (450)");
    }

    /* T3: BEGIN IMMEDIATE contention resolves through busy_timeout. A
     * holds the write lock ~150 ms and commits on its own; B's BEGIN
     * IMMEDIATE must busy-wait through the window, not fail. */
    {
        atomic_int locked = 0;
        struct hold_arg hold = {a, &locked, 150, NULL};
        pthread_t holder;
        pthread_create(&holder, NULL, hold_then_commit, &hold);
        CHECK(wait_locked(&locked) == 1, "T3 holder acquired the write lock");
        CHECK(exec_retry(b, "BEGIN IMMEDIATE;") == 0, "T3 B begin immediate (busy_timeout)");
        pthread_join(holder, NULL);
        CHECK(count_rows(b, "t_writes", "handle=9") == 1, "T3 holder write committed");
        CHECK(exec_retry(b, "COMMIT;") == 0, "T3 B commit");
    }

    /* T4: the no-wedge contract the hardened SqlTransaction::Execute
     * implements: any failure inside the transaction is followed by
     * ROLLBACK, so the next BEGIN always succeeds - never "cannot start a
     * transaction within a transaction"; a refused BEGIN runs nothing at
     * all (no autocommit partial application). */
    {
        CHECK(exec_retry(a, "CREATE TABLE t_unique(id INTEGER PRIMARY KEY, v INT UNIQUE);") == 0, "T4 create unique");
        CHECK(exec_retry(a, "BEGIN IMMEDIATE;") == 0, "T4 begin");
        CHECK(exec_retry(a, "INSERT INTO t_unique(v) VALUES(1);") == 0, "T4 first insert");
        CHECK(exec_retry(a, "INSERT INTO t_unique(v) VALUES(1);") != 0, "T4 duplicate insert fails");
        CHECK(exec_retry(a, "ROLLBACK;") == 0, "T4 rollback after failure");
        CHECK(exec_retry(a, "BEGIN IMMEDIATE;") == 0, "T4 no wedge: next BEGIN succeeds");
        CHECK(exec_retry(a, "COMMIT;") == 0, "T4 final commit");

        /* The retryable-BUSY begin: with the holder owning the write lock
         * and busy_timeout disabled, BEGIN IMMEDIATE reports BUSY at once -
         * the retry loop (exec_retry) recovers once the lock frees. The
         * holder is released by the observed BUSY itself (deterministic
         * handshake - no timed window to race). */
        sqlite3* raw = NULL;
        CHECK(sqlite3_open(g_dbfile, &raw) == SQLITE_OK, "T4 open zero-timeout handle");
        sqlite3_busy_timeout(raw, 0);
        atomic_int locked = 0, release = 0;
        struct hold_arg hold = {b, &locked, 0, &release};
        pthread_t holder;
        pthread_create(&holder, NULL, hold_then_commit, &hold);
        CHECK(wait_locked(&locked) == 1, "T4 holder acquired the write lock");
        char* err = NULL;
        int rc = sqlite3_exec(raw, "BEGIN IMMEDIATE;", 0, 0, &err);
        CHECK(rc == SQLITE_BUSY, "T4 zero-timeout BEGIN reports BUSY at once");
        sqlite3_free(err);
        atomic_store(&release, 1);
        pthread_join(holder, NULL);
        CHECK(exec_retry(raw, "BEGIN IMMEDIATE;") == 0, "T4 retry succeeds after release");
        CHECK(exec_retry(raw, "COMMIT;") == 0, "T4 retry commit");
        sqlite3_close(raw);
    }

    /* T5: zero-row query + embedded-NUL string round trip + policy. */
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(a, "SELECT * FROM t_blob WHERE 1=0;", -1, &stmt, NULL) == SQLITE_OK, "T5 prepare");
        int rc = sqlite3_step(stmt);
        CHECK(rc == SQLITE_DONE, "T5 zero rows steps to DONE (empty result)");
        CHECK(sqlite3_column_count(stmt) == 2, "T5 column count");
        sqlite3_finalize(stmt);

        char const* tricky = "before\0after";
        CHECK(sqlite3_prepare_v2(a, "INSERT INTO t_blob(data) VALUES(?1);", -1, &stmt, NULL) == SQLITE_OK, "T5 bind prepare");
        CHECK(sqlite3_bind_text(stmt, 1, tricky, 11, SQLITE_TRANSIENT) == SQLITE_OK, "T5 explicit-length bind");
        CHECK(sqlite3_step(stmt) == SQLITE_DONE, "T5 insert step");
        sqlite3_finalize(stmt);

        CHECK(sqlite3_prepare_v2(a, "SELECT data FROM t_blob WHERE id=1;", -1, &stmt, NULL) == SQLITE_OK, "T5 select prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "T5 select row");
        CHECK(sqlite3_column_bytes(stmt, 0) == 11, "T5 embedded NUL survived (11 bytes)");
        sqlite3_finalize(stmt);

        CHECK(sqlite3_prepare_v2(a, "PRAGMA synchronous;", -1, &stmt, NULL) == SQLITE_OK, "T5 pragma prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "T5 pragma step");
        CHECK(sqlite3_column_int(stmt, 0) == 2, "T5 synchronous=FULL (2) on the policy connection");
        sqlite3_finalize(stmt);

        /* WAL must actually be ENGAGED on the policy connection: T1/T2 would
         * also pass in rollback-journal mode, so prove the mode took. */
        CHECK(sqlite3_prepare_v2(a, "PRAGMA journal_mode;", -1, &stmt, NULL) == SQLITE_OK, "T5 journal prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "T5 journal step");
        CHECK(sqlite3_column_text(stmt, 0) != NULL &&
              strcmp((char const*)sqlite3_column_text(stmt, 0), "wal") == 0,
              "T5 WAL active on the policy connection");
        sqlite3_finalize(stmt);
    }

    /* T6: concurrent same-connection Query iteration under the emulated
     * SqlConnection::Lock (see the shared_reader block comment). */
    {
        sqlite3* shared = open_policy_connection();
        CHECK(shared != NULL, "T6 shared connection");
        struct shared_arg arg = {shared, 0, 0};
        pthread_t reader, writer;
        pthread_create(&reader, NULL, shared_reader, &arg);
        pthread_create(&writer, NULL, shared_writer, &arg);
        pthread_join(writer, NULL);
        pthread_join(reader, NULL);
        CHECK(count_rows(shared, "t_writes", "handle=6") == 50, "T6 all shared writes survived");
        CHECK(atomic_load(&arg.max_rows) == 50,
              "T6 final snapshot saw every committed row (reader progressed)");
        sqlite3_close(shared);
    }

    /* T7: the commit-failure recovery contract, behaviorally forced. A COMMIT
     * that fails (forced here via sqlite3_commit_hook - the documented
     * non-zero return converts COMMIT into ROLLBACK - the one deterministic
     * host-side injection) must leave the connection usable: data rolled
     * back, fresh BEGIN succeeding. This is the engine-level twin of the
     * statically pinned SqlTransaction::Execute overlay. */
    {
        sqlite3* c = open_policy_connection();
        CHECK(c != NULL, "T7 connection");
        CHECK(exec_retry(c, "CREATE TABLE t_commit(id INTEGER PRIMARY KEY, v INT);") == 0, "T7 create");
        CHECK(exec_retry(c, "BEGIN IMMEDIATE;") == 0, "T7 begin");
        CHECK(exec_retry(c, "INSERT INTO t_commit(v) VALUES(7);") == 0, "T7 insert");
        sqlite3_commit_hook(c, reject_commit, NULL);
        CHECK(exec_retry(c, "COMMIT;") != 0, "T7 forced COMMIT failure surfaces");
        sqlite3_commit_hook(c, NULL, NULL);
        /* Overlay parity: SqlTransaction::Execute calls RollbackTransaction()
         * after a failed COMMIT and ignores its result (the failed COMMIT
         * already rolled back engine-side); mirror that exactly. */
        (void)exec_retry(c, "ROLLBACK;");
        CHECK(count_rows(c, "t_commit", "1=1") == 0, "T7 failed commit left no partial data");
        CHECK(exec_retry(c, "BEGIN IMMEDIATE;") == 0, "T7 no wedge: fresh BEGIN succeeds");
        CHECK(exec_retry(c, "INSERT INTO t_commit(v) VALUES(8);") == 0, "T7 post-recovery write");
        CHECK(exec_retry(c, "COMMIT;") == 0, "T7 post-recovery commit");
        CHECK(count_rows(c, "t_commit", "1=1") == 1, "T7 post-recovery data visible");
        sqlite3_close(c);
    }

    /* T8: a mid-scan step failure is real, injectable engine behavior -
     * the state the ScanComplete guard exists for. A progress handler
     * aborting the scan leaves a non-DONE rc; any rows already read are a
     * silently truncated set the caller must FAIL, never return. */
    {
        sqlite3* c = open_policy_connection();
        CHECK(c != NULL, "T8 connection");
        CHECK(exec_retry(c, "CREATE TABLE t_scan(id INTEGER PRIMARY KEY, v INT);") == 0, "T8 create");
        for (int i = 0; i < 5; ++i)
        {
            char sql[96];
            snprintf(sql, sizeof sql, "INSERT INTO t_scan(v) VALUES(%d);", i);
            CHECK(exec_retry(c, sql) == 0, "T8 seed insert");
        }
        struct progress_abort pa = {0, 3};
        sqlite3_progress_handler(c, 1, abort_progress, &pa);
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(c, "SELECT v FROM t_scan;", -1, &stmt, NULL) == SQLITE_OK, "T8 prepare");
        int rc;
        while ((rc = sqlite3_step(stmt)) == SQLITE_ROW)
        {
            /* rows read before the abort would be the truncated set */
        }
        sqlite3_progress_handler(c, 0, NULL, NULL);
        sqlite3_finalize(stmt);
        CHECK(rc == SQLITE_INTERRUPT, "T8 scan aborted mid-way (SQLITE_INTERRUPT, not DONE)");
        sqlite3_close(c);
    }

    sqlite3_close(a);
    sqlite3_close(b);

    if (atomic_load(&g_failures)) { fprintf(stderr, "%d failure(s)\n", atomic_load(&g_failures)); return 1; }
    printf("contention tests passed\n");
    return 0;
}
