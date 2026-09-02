/*
 * Pocket Realm hardened SQLite backend (P2 of the MariaDB replacement plan).
 *
 * Replaces native/cmangos/src/shared/Database/DatabaseSqlite.cpp under
 * DO_SQLITE builds only. Fixes (digest F26/F30 + DEC-02):
 *  - connection policy: WAL + synchronous=NORMAL (DEC-02 as amended
 *    2026-08-27 for the on-device-play decision: WAL keeps crash
 *    consistency, power cut rolls back to the last WAL checkpoint =
 *    bounded progress loss, no corruption; per-commit fsync (FULL) was
 *    retired for its battery/latency cost on consumer flash - game state
 *    tolerates seconds of rollback, not DB corruption), busy_timeout=500
 *    ms (upstream 2 ms returned false silently under the bot save waves -
 *    dropped writes), foreign_keys=off, cache_size=64 MiB per connection
 *    (the read-heavy 1.36M-row corpus; the old 2 MB default page cache
 *    starved the equipment-cache build).
 *  - Execute/_TransactionCmd retry on SQLITE_BUSY/LOCKED instead of
 *    returning false silently.
 *  - BEGIN IMMEDIATE for write transactions: the write lock is taken up
 *    front, so a BUSY surfaces at BEGIN (retryable) instead of wedging
 *    mid-transaction.
 *  - Query/QueryNamed: results are materialized by the hardened
 *    QueryResultSqlite while this connection's lock is held; 0-row
 *    QueryNamed now returns nullptr (MySQL semantics - upstream returned a
 *    live result on zero rows).
 *  - prepared statements: no leaked stmt wrapper; text binds carry an
 *    explicit length + SQLITE_TRANSIENT (upstream passed -1 +
 *    SQLITE_STATIC: strings with embedded NULs truncated, and the bind
 *    aliased caller storage sqlite3 was told to keep).
 */

#include "QueryResultSqlite.h"
#include <cstdint>
#include <cstring>
#ifdef DO_SQLITE
#include <sqlite3.h>

#include "Util/Util.h"
#include "Policies/Singleton.h"
#include "Platform/Define.h"
#include "Multithreading/Threading.h"
#include "DatabaseEnv.h"
#include "Util/Timer.h"
#include "DatabaseSqlite.h"

namespace {
// Matches DatabaseSqliteConfigPolicy (the reviewed Kotlin-side source of
// the same contract); tests pin both to the same values.
constexpr int POCKET_SQLITE_BUSY_TIMEOUT_MS = 500;
constexpr int POCKET_SQLITE_BUSY_RETRIES = 3;
}

SqlConnection* DatabaseSqlite::CreateConnection()
{
    return new SQLiteConnection(*this);
}

SQLiteConnection::~SQLiteConnection()
{
    FreePreparedStatements();
    sqlite3_exec(mSqlite, "PRAGMA optimize;", 0, 0, 0);
    sqlite3_close(mSqlite);
}

bool SQLiteConnection::Initialize(const char* infoString)
{
    if (sqlite3_open(infoString, &mSqlite) != SQLITE_OK)
    {
        sLog.outError("Could not open SQLite database");
        return false;
    }
    // DEC-02 (amended) durability + F30 contention window: file header.
    sqlite3_exec(mSqlite, "PRAGMA journal_mode=WAL;", 0, 0, 0);
    sqlite3_exec(mSqlite, "PRAGMA synchronous=NORMAL;", 0, 0, 0);
    sqlite3_exec(mSqlite, "PRAGMA cache_size=-65536;", 0, 0, 0);
    sqlite3_exec(mSqlite, "PRAGMA foreign_keys=OFF;", 0, 0, 0);
    sqlite3_exec(mSqlite, "PRAGMA secure_delete=off;", 0, 0, 0);
    sqlite3_exec(mSqlite, "PRAGMA encoding='UTF-8';", 0, 0, 0);
    // The busy policy has exactly one source (the constant below, shared
    // with DatabaseSqliteConfigPolicy) - no duplicated pragma literal to
    // drift away from it.
    sqlite3_busy_timeout(mSqlite, POCKET_SQLITE_BUSY_TIMEOUT_MS);

    // A journal_mode=WAL failure (cold first-open race, busy peer) would
    // silently degrade concurrency to rollback-journal mode - synchronous
    // =FULL still holds, but say so loudly instead of degrading quietly.
    {
        sqlite3_stmt* probe = nullptr;
        bool walActive = false;
        if (sqlite3_prepare_v2(mSqlite, "PRAGMA journal_mode;", -1, &probe, nullptr) == SQLITE_OK)
        {
            if (sqlite3_step(probe) == SQLITE_ROW)
                walActive = strncmp(reinterpret_cast<const char*>(
                                        sqlite3_column_text(probe, 0)), "wal", 3) == 0;
            sqlite3_finalize(probe);
        }
        if (!walActive)
            sLog.outError("PocketRealm SQLite: journal_mode is not WAL after init "
                          "(first-open race or busy peer) - concurrency degraded; "
                          "durability contract unaffected (synchronous=NORMAL)");
    }

    DETAIL_LOG("Connected to SQLite database at %s", infoString);

    return true;
}

bool SQLiteConnection::_Query(const char* sql, sqlite3_stmt** pStmt)
{
    if (!mSqlite)
        return false;

    int result;
    result = sqlite3_prepare_v2(mSqlite, sql, -1, pStmt, NULL);
    if (result != SQLITE_OK) {
        sLog.outErrorDb("SQL: %s", sql);
        sLog.outErrorDb("query ERROR: %s", sqlite3_errmsg(mSqlite));
        return false;
    }

    return true;
}

std::unique_ptr<QueryResult> SQLiteConnection::Query(const char* sql)
{
    sqlite3_stmt* stmt = nullptr;
    if (!_Query(sql, &stmt))
        return nullptr;

    // QueryResultSqlite steps the statement to completion and finalizes it
    // here, while this connection's lock is held by the caller.
    auto queryResult = std::make_unique<QueryResultSqlite>(stmt);

    // MySQL parity: a mid-scan failure is a FAILED query, not a silently
    // truncated result set.
    if (!queryResult->ScanComplete())
        return nullptr;

    if (queryResult->NextRow())
        return queryResult;
    return nullptr;
}

QueryNamedResult* SQLiteConnection::QueryNamed(const char* sql)
{
    sqlite3_stmt* stmt = nullptr;
    if (!_Query(sql, &stmt))
        return nullptr;

    const uint32 fieldCount = sqlite3_column_count(stmt);

    QueryFieldNames names(fieldCount);
    for (uint32 i = 0; i < fieldCount; ++i)
        names[i] = sqlite3_column_name(stmt, i);

    QueryResultSqlite* queryResult = new QueryResultSqlite(stmt);

    // MySQL-parity: a mid-scan failure is a failed query (nullptr), and
    // zero rows returns nullptr, not a live empty result.
    if (!queryResult->ScanComplete() || !queryResult->NextRow())
    {
        delete queryResult;
        return nullptr;
    }
    return new QueryNamedResult(queryResult, names);
}

bool SQLiteConnection::_StepNoRows(sqlite3_stmt* stmt, char const* sql)
{
    // busy_timeout already blocks up to 500 ms inside sqlite3_step; the
    // retry loop covers a second writer arriving inside the same window
    // (the :realm/:world LoginDatabase pattern, F30) instead of silently
    // dropping the write like upstream did.
    for (int attempt = 0; ; ++attempt)
    {
        int result = sqlite3_step(stmt);
        if (result == SQLITE_DONE || result == SQLITE_OK)
            return true;
        if ((result == SQLITE_BUSY || result == SQLITE_LOCKED) &&
            attempt < POCKET_SQLITE_BUSY_RETRIES)
        {
            sqlite3_reset(stmt);
            continue;
        }
        sLog.outErrorDb("SQL: %s", sql);
        sLog.outErrorDb("SQL ERROR: %s", sqlite3_errmsg(mSqlite));
        return false;
    }
}

bool SQLiteConnection::Execute(const char* sql)
{
    if (!mSqlite)
        return false;

    uint32 _s = WorldTimer::getMSTime();
    sqlite3_stmt* stmt = nullptr;

    int result;
    result = sqlite3_prepare_v2(mSqlite, sql, -1, &stmt, NULL);
    if (result != SQLITE_OK)
    {
        sLog.outErrorDb("SQL: %s", sql);
        sLog.outErrorDb("SQL ERROR: %s", sqlite3_errmsg(mSqlite));
        return false;
    }
    const bool ok = _StepNoRows(stmt, sql);
    sqlite3_finalize(stmt);
    if (ok)
        DEBUG_FILTER_LOG(LOG_FILTER_SQL_TEXT, "[%u ms] SQL: %s", WorldTimer::getMSTimeDiff(_s, WorldTimer::getMSTime()), sql);
    return ok;
}

bool SQLiteConnection::_TransactionCmd(const char* sql)
{
    sqlite3_stmt* stmt = nullptr;

    int result;
    result = sqlite3_prepare_v2(mSqlite, sql, -1, &stmt, NULL);
    if (result != SQLITE_OK)
    {
        sLog.outErrorDb("SQL: %s", sql);
        sLog.outErrorDb("SQL ERROR: %s", sqlite3_errmsg(mSqlite));
        return false;
    }
    const bool ok = _StepNoRows(stmt, sql);
    sqlite3_finalize(stmt);
    if (ok)
        DEBUG_FILTER_LOG(LOG_FILTER_SQL_TEXT, "SQL: %s", sql);
    return ok;
}

bool SQLiteConnection::BeginTransaction()
{
    // IMMEDIATE takes the write lock up front: contention surfaces here,
    // retryable, instead of as a mid-transaction BUSY at COMMIT.
    return _TransactionCmd("BEGIN IMMEDIATE");
}

bool SQLiteConnection::CommitTransaction()
{
    return _TransactionCmd("COMMIT");
}

bool SQLiteConnection::RollbackTransaction()
{
    return _TransactionCmd("ROLLBACK");
}

unsigned long SQLiteConnection::escape_string(char* to, const char* from, unsigned long length)
{
    if (!mSqlite || !to || !from || !length)
        return 0;
    std::string newFrom(from);
    std::string newTo;

    for (const char& c : newFrom)
    {
        switch (c)
        {
            [[unlikely]] case '\'': newTo += "''"; break;
            [[unlikely]] case '\\': newTo += "\\"; break;
            [[likely]]   default:   newTo += c;    break;
        }
    }
    strcpy(to, newTo.c_str());

    return newTo.length();
}

//////////////////////////////////////////////////////////////////////////
SqlPreparedStatement* SQLiteConnection::CreateStatement(const std::string& fmt)
{
    return new SqlitePreparedStatement(fmt, *this, mSqlite);
}

//////////////////////////////////////////////////////////////////////////
SqlitePreparedStatement::SqlitePreparedStatement(const std::string& fmt, SqlConnection& conn, sqlite3* mysql) : SqlPreparedStatement(fmt, conn),
    m_pSqliteConn(mysql), m_stmt(nullptr)
{
}

SqlitePreparedStatement::~SqlitePreparedStatement()
{
    RemoveBinds();
}

bool SqlitePreparedStatement::prepare()
{
    if (isPrepared())
        return true;

    // remove old binds
    RemoveBinds();

    // Create statement object
    int result = sqlite3_prepare_v2(m_pSqliteConn, m_szFmt.c_str(), m_szFmt.length(), &m_stmt, nullptr);
    if (result != SQLITE_OK) {
        sLog.outError("SQL: sqlite3_prepare_v2() failed: %s", sqlite3_errmsg(m_pSqliteConn));
        return false;
    }
    // Get the parameter count from the statement
    m_nParams = sqlite3_bind_parameter_count(m_stmt);

    // Check if we have a statement which returns result sets
    if (sqlite3_stmt_readonly(m_stmt) == 0) {
        // Our statement changes the database (readonly == 0)
        m_bIsQuery = false;
    }
    else
    {
        m_bIsQuery = true;
        m_nColumns = sqlite3_column_count(m_stmt);  // Get the number of columns in the result set
    }

    m_bPrepared = true;
    return true;
}

void SqlitePreparedStatement::bind(const SqlStmtParameters& holder)
{
    if (!isPrepared())
    {
        MANGOS_ASSERT(false && "bind() called on an unprepared statement");
        return;
    }

    // verify if we bound all needed input parameters
    if (m_nParams != holder.boundParams())
    {
        MANGOS_ASSERT(false && "bound parameter count mismatch");
        return;
    }

    unsigned int nIndex = 0; // SQLite uses 1-based index for parameter binding
    SqlStmtParameters::ParameterContainer const& _args = holder.params();

    for (const auto& param : _args)
    {
        // Bind parameter
        addParam(nIndex, param);
        nIndex++;
    }
}

void SqlitePreparedStatement::addParam(unsigned int nIndex, const SqlStmtFieldData& data)
{
    MANGOS_ASSERT(nIndex < m_nParams);

    // SQLite uses different types and structures for binding parameters
    int result = SQLITE_OK;

    switch (data.type()) {
        case FIELD_BOOL:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toBool());
            break;
        case FIELD_UI8:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toUint8());
            break;
        case FIELD_UI16:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toUint16());
            break;
        case FIELD_UI32:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toUint32());
            break;
        case FIELD_UI64:
            result = sqlite3_bind_int64(m_stmt, nIndex + 1, data.toUint64());
            break;
        case FIELD_I8:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toInt8());
            break;
        case FIELD_I16:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toInt16());
            break;
        case FIELD_I32:
            result = sqlite3_bind_int(m_stmt, nIndex + 1, data.toInt32());
            break;
        case FIELD_I64:
            result = sqlite3_bind_int64(m_stmt, nIndex + 1, data.toInt64());
            break;
        case FIELD_FLOAT:
            result = sqlite3_bind_double(m_stmt, nIndex + 1, data.toFloat());
            break;
        case FIELD_DOUBLE:
            result = sqlite3_bind_double(m_stmt, nIndex + 1, data.toDouble());
            break;
        case FIELD_STRING:
            // Explicit length + SQLITE_TRANSIENT: embedded NULs survive
            // (upstream's -1 truncated at the first one) and sqlite copies
            // the value at bind time (upstream's SQLITE_STATIC aliased
            // caller storage the statement was told to keep forever).
            result = sqlite3_bind_text(m_stmt, nIndex + 1, data.toStr(),
                                       static_cast<int>(data.size()), SQLITE_TRANSIENT);
            break;
        case FIELD_NONE:
            result = sqlite3_bind_null(m_stmt, nIndex + 1);
            break;
        // Handle other data types as needed

        default:
            MANGOS_ASSERT(false && "Unsupported parameter type");
    }

    if (result != SQLITE_OK) {
        // stderr is not captured on Android; route bind failures through the
        // same log channel as every other DB error in this file.
        sLog.outErrorDb("SQL: error binding parameter at index %u: %s",
                        nIndex, sqlite3_errmsg(m_pSqliteConn));
    }
}

void SqlitePreparedStatement::RemoveBinds()
{
    if (!m_stmt)
        return;

    // Finalize the prepared statement
    sqlite3_finalize(m_stmt);
    m_stmt = nullptr;

    m_bPrepared = false;
}

bool SqlitePreparedStatement::execute()
{
    if (!isPrepared())
        return false;

    for (int attempt = 0; ; ++attempt)
    {
        int result = sqlite3_step(m_stmt);

        if (result == SQLITE_DONE)
            break;
        if ((result == SQLITE_BUSY || result == SQLITE_LOCKED) &&
            attempt < POCKET_SQLITE_BUSY_RETRIES)
        {
            sqlite3_reset(m_stmt);
            continue;
        }

        sLog.outErrorDb("SQL: cannot execute '%s'", m_szFmt.c_str());
        sLog.outErrorDb("SQL ERROR: %s", sqlite3_errmsg(m_pSqliteConn));
        sqlite3_reset(m_stmt);
        sqlite3_clear_bindings(m_stmt);
        return false;
    }

    // Reset the prepared statement to be executed again if needed
    sqlite3_reset(m_stmt);
    sqlite3_clear_bindings(m_stmt);

    return true;
}
#endif
