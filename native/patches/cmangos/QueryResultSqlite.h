/*
 * Pocket Realm hardened SQLite backend.
 *
 * Replaces native/cmangos/src/shared/Database/QueryResultSqlite.h under
 * DO_SQLITE builds only. Upstream keeps a live sqlite3_stmt* and lazily
 * steps it in NextRow() after the connection lock is released, double-scans
 * every result set to count rows, reads columns after sqlite3_reset
 * (bogus initial values), and leaks the 8-byte stmt wrapper per query.
 * This replacement materializes the whole result
 * set inside the constructor - which runs while the caller still holds the
 * SqlConnection lock - and finalizes the statement immediately, so result
 * iteration never touches the connection again.
 */

#ifdef DO_SQLITE

#if !defined(QUERYRESULTSQLITE_H)
#define QUERYRESULTSQLITE_H

#include "Common.h"
#include "QueryResult.h"

#ifdef _WIN32
  #include <WinSock2.h>
#endif

#include <sqlite3.h>

#include <string>
#include <vector>

class QueryResultSqlite : public QueryResult
{
    public:
        // Takes ownership of a prepared, un-stepped statement and runs it to
        // completion. The whole result set is copied out and the statement
        // finalized before returning, so the caller may release the
        // connection lock immediately.
        QueryResultSqlite(sqlite3_stmt* stmt);

        ~QueryResultSqlite();

        bool NextRow() override;

        // False when the constructor's scan stopped on an error before
        // SQLITE_DONE: the result set is silently TRUNCATED, so callers
        // must fail the query (MySQL surfaces a mid-read failure as a
        // failed query, never a partial result).
        bool ScanComplete() const { return m_scanComplete; }

        operator bool() const
        {
          return (mRowCount * mFieldCount) > 0;
        }

    private:
        enum Field::DataTypes ConvertNativeType(int sqliteType) const;
        void EndQuery();

        // Fully materialized rows: values as text plus the per-column
        // sqlite storage type. Immutable after the constructor, so the
        // std::string buffers - and therefore the c_str() pointers handed
        // to Field - stay valid for the result's lifetime.
        std::vector<std::vector<std::string>> m_rows;
        std::vector<std::vector<int>> m_rowTypes;
        size_t m_nextRowIndex;
        bool m_scanComplete;
};
#endif
#endif
