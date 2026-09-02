/*
 * Pocket Realm hardened SQLite backend.
 *
 * Replaces native/cmangos/src/shared/Database/DatabaseSqlite.h under
 * DO_SQLITE builds only. Changes vs upstream:
 *  - prepared statements hold a sqlite3_stmt* directly (upstream heap-
 *    allocated an 8-byte stmt wrapper per statement and leaked it);
 *  - _Query hands out the sqlite3_stmt* for the materializing
 *    QueryResultSqlite (no wrapper allocation at all).
 */

#ifndef DO_POSTGRESQL

#ifndef _DatabaseSqlite_H
#define _DatabaseSqlite_H

//#include "Common.h"
#include "Database.h"
#include "Policies/Singleton.h"
#include "QueryResultSqlite.h"

#include <sqlite3.h>

// SQLite prepared statement class
class SqlitePreparedStatement : public SqlPreparedStatement
{
    public:
        SqlitePreparedStatement(const std::string& fmt, SqlConnection& conn, sqlite3* mysql);
        ~SqlitePreparedStatement();

        // prepare statement
        virtual bool prepare() override;

        // bind input parameters
        virtual void bind(const SqlStmtParameters& holder) override;

        // execute DML statement
        virtual bool execute() override;

    protected:
        // bind parameters
        void addParam(unsigned int nIndex, const SqlStmtFieldData& data);

    private:
        void RemoveBinds();

        sqlite3* m_pSqliteConn;
        sqlite3_stmt* m_stmt;
};

class SQLiteConnection : public SqlConnection
{
    public:
        SQLiteConnection(Database& db) : SqlConnection(db), mSqlite(nullptr) {}
        ~SQLiteConnection();

        //! Initializes sqlite and opens the database file.
        /*! infoString is the database file path (host;user;pass style is not used). */
        bool Initialize(const char* infoString) override;

        std::unique_ptr<QueryResult> Query(const char* sql) override;
        QueryNamedResult* QueryNamed(const char* sql) override;
        bool Execute(const char* sql) override;

        unsigned long escape_string(char* to, const char* from, unsigned long length) override;

        bool BeginTransaction() override;
        bool CommitTransaction() override;
        bool RollbackTransaction() override;

    protected:
        SqlPreparedStatement* CreateStatement(const std::string& fmt) override;

    private:
        bool _TransactionCmd(const char* sql);
        bool _Query(const char* sql, sqlite3_stmt** pStmt);
        // Busy retry wrapper around sqlite3_step for statements that cannot
        // return rows (Execute / transaction commands).
        bool _StepNoRows(sqlite3_stmt* stmt, char const* sql);

        sqlite3* mSqlite;
};

class DatabaseSqlite : public Database
{
        friend class MaNGOS::OperatorNew<DatabaseSqlite>;

    protected:
        virtual SqlConnection* CreateConnection() override;
};

#endif
#endif
