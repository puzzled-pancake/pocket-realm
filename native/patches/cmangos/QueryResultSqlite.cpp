/*
 * Pocket Realm hardened SQLite backend (P2 of the MariaDB replacement plan).
 *
 * Replaces native/cmangos/src/shared/Database/QueryResultSqlite.cpp under
 * DO_SQLITE builds only. Fixes (digest F26 + the P2 review addendum):
 *  - no double scan: the upstream constructor stepped every row once to
 *    count them, reset the statement, then NextRow() stepped them again.
 *  - no post-reset column reads: upstream initialized the first row's
 *    Fields from a reset statement (bogus values, overwritten only if a
 *    real row followed).
 *  - no lazy stepping outside the connection lock: rows are materialized
 *    entirely inside the constructor, which the caller invokes while
 *    holding the SqlConnection lock (SQLITE_THREADSAFE=2 forbids sharing
 *    one connection across threads, and the 1-connection query pool can
 *    hand the same connection to two threads).
 *  - no per-query leak: the statement is finalized here and no heap
 *    stmt-wrapper is allocated (upstream leaked one per query).
 */

#ifdef DO_SQLITE

#include "DatabaseEnv.h"
#include "Util/Errors.h"
#include "sqlite3.h"
#include "QueryResultSqlite.h"

QueryResultSqlite::QueryResultSqlite(sqlite3_stmt* stmt) :
    QueryResult(0, 0), m_nextRowIndex(0), m_scanComplete(false)
{
    if (!stmt)
        return;

    mFieldCount = sqlite3_column_count(stmt);

    int rc;
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW)
    {
        std::vector<std::string> row(static_cast<size_t>(mFieldCount));
        std::vector<int> types(static_cast<size_t>(mFieldCount));
        for (uint32 i = 0; i < mFieldCount; ++i)
        {
            const unsigned char* value = sqlite3_column_text(stmt, i);
            const int bytes = sqlite3_column_bytes(stmt, i);
            if (value && bytes > 0)
                row[static_cast<size_t>(i)].assign(
                    reinterpret_cast<const char*>(value), static_cast<size_t>(bytes));
            // else: NULL or empty stays the empty string; the type matrix
            // below still distinguishes NULL for ConvertNativeType.
            types[static_cast<size_t>(i)] = sqlite3_column_type(stmt, i);
        }
        m_rows.push_back(std::move(row));
        m_rowTypes.push_back(std::move(types));
        ++mRowCount;
    }

    if (rc == SQLITE_DONE)
    {
        m_scanComplete = true;
    }
    else
    {
        // A mid-scan failure (IOERR/CORRUPT/INTERRUPT...) means the rows
        // read so far are a silently TRUNCATED set. Report it and mark the
        // result incomplete: the caller fails the query instead of handing
        // back a partial table (MySQL parity - a mid-read failure was never
        // a success on either engine).
        sLog.outErrorDb("query ERROR: sqlite3_step stopped early: %s",
                        sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }

    sqlite3_finalize(stmt);

    mCurrentRow = new Field[mFieldCount];
    MANGOS_ASSERT(mCurrentRow);
}

QueryResultSqlite::~QueryResultSqlite()
{
    EndQuery();
}

bool QueryResultSqlite::NextRow()
{
    if (m_nextRowIndex >= m_rows.size())
    {
        EndQuery();
        return false;
    }

    const std::vector<std::string>& row = m_rows[m_nextRowIndex];
    const std::vector<int>& types = m_rowTypes[m_nextRowIndex];
    ++m_nextRowIndex;
    for (uint32 i = 0; i < mFieldCount; ++i)
    {
        const size_t index = static_cast<size_t>(i);
        mCurrentRow[i].SetValue(row[index].empty() && types[index] == SQLITE_NULL
            ? nullptr : row[index].c_str());
        mCurrentRow[i].SetType(ConvertNativeType(types[index]));
    }

    return true;
}

void QueryResultSqlite::EndQuery()
{
    delete[] mCurrentRow;
    mCurrentRow = nullptr;
    m_rows.clear();
    m_rowTypes.clear();
}

enum Field::DataTypes QueryResultSqlite::ConvertNativeType(int sqliteType) const
{
    switch (sqliteType)
    {
        case SQLITE_INTEGER:
            return Field::DB_TYPE_INTEGER;
        case SQLITE_FLOAT:
            return Field::DB_TYPE_FLOAT;
        case SQLITE_TEXT:
        case SQLITE_BLOB:
        case SQLITE_NULL:
            return Field::DB_TYPE_STRING;
        default:
            return Field::DB_TYPE_UNKNOWN;
    }
}
#endif
