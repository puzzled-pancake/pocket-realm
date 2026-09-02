/*
 * ODKU-threshold + benign-DDL fixture.
 *
 * Executes the EXACT rewritten runtime SQL shapes against the PINNED
 * amalgamation:
 *  - the increment-folded ON CONFLICT DO UPDATE rewrite of
 *    PlayerbotLlmMemory::AddRelationshipPoints at the 10/30/60 tier
 *    boundaries (9->10, 29->30, 59->60), plus negative deltas, the
 *    fresh-insert path, and the INSERT OR IGNORE backstory shape;
 *  - the negative control: the MECHANICAL (unfolded) rewrite a naive
 *    port would produce, proving it lags the thresholds - the
 *    fold is load-bearing;
 *  - TravelMgr's CREATE TABLE IF NOT EXISTS (the one benign runtime DDL)
 *    parses and round-trips;
 *  - the registered rowid-IN-subquery anticheat prune rewrite with its
 *    exactly-two positional binds.
 *
 * Compiled for the HOST with the production define set by
 * tests/test_sqlite_dialect.py; exit 0 = pass.
 */
#include <sqlite3.h>

#include <stdio.h>
#include <string.h>

static char const* ODKU_FOLDED =
    "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`) "
    "VALUES ('%u', '%u', 'stranger', %d, NULL) "
    "ON CONFLICT(`bot`, `player`) DO UPDATE SET `points` = `points` + excluded.`points`, "
    "`tier` = CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted' "
    "WHEN `points` + excluded.`points` >= 30 THEN 'ally' "
    "WHEN `points` + excluded.`points` >= 10 THEN 'acquaintance' ELSE 'stranger' END, "
    "`last_interaction_at` = CURRENT_TIMESTAMP";

/* The mechanical rewrite (thresholds read the PRE-update points) - kept
 * here as the negative control proving why the fold exists. */
static char const* ODKU_UNFOLDED =
    "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`) "
    "VALUES ('%u', '%u', 'stranger', %d, NULL) "
    "ON CONFLICT(`bot`, `player`) DO UPDATE SET `points` = `points` + excluded.`points`, "
    "`tier` = CASE WHEN `points` >= 60 THEN 'trusted' "
    "WHEN `points` >= 30 THEN 'ally' "
    "WHEN `points` >= 10 THEN 'acquaintance' ELSE 'stranger' END, "
    "`last_interaction_at` = CURRENT_TIMESTAMP";

static char const* ANTICHEAT_ROWID_PRUNE =
    "DELETE FROM system_fingerprint_usage WHERE rowid IN "
    "(SELECT rowid FROM system_fingerprint_usage WHERE fingerprint = ? ORDER BY `time` ASC LIMIT ?)";

static char const* TRAVELMGR_DDL =
    "CREATE TABLE IF NOT EXISTS `ai_playerbot_zone_level` (`id` bigint(20) NOT NULL ,`level` bigint(20) NOT NULL,PRIMARY KEY(`id`))";

static int g_failures;

#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "FAIL %s (line %d)\n", msg, __LINE__); ++g_failures; } \
} while (0)

static int exec(sqlite3* db, char const* sql)
{
    char* err = NULL;
    int rc = sqlite3_exec(db, sql, 0, 0, &err);
    if (err) sqlite3_free(err);
    return rc;
}

static void apply_points(sqlite3* db, char const* tmpl, unsigned bot, unsigned player, int delta)
{
    char sql[1024];
    snprintf(sql, sizeof sql, tmpl, bot, player, delta);
    CHECK(exec(db, sql) == SQLITE_OK, "apply_points exec");
}

/* Points + tier of a row, assuming it exists. */
static void read_row(sqlite3* db, unsigned bot, unsigned player, int* points, char* tier, size_t tier_sz)
{
    sqlite3_stmt* stmt = NULL;
    char sql[256];
    snprintf(sql, sizeof sql,
             "SELECT `points`, `tier` FROM `bot_player_relationship` WHERE `bot` = %u AND `player` = %u;",
             bot, player);
    CHECK(sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK, "read_row prepare");
    CHECK(sqlite3_step(stmt) == SQLITE_ROW, "read_row step (row must exist)");
    if (points) *points = sqlite3_column_int(stmt, 0);
    if (tier) snprintf(tier, tier_sz, "%s", sqlite3_column_text(stmt, 1) ? (char const*)sqlite3_column_text(stmt, 1) : "");
    sqlite3_finalize(stmt);
}

/* Seed a row at an exact points value with a placeholder tier, then apply
 * delta through the given template and return the resulting tier/points. */
static void scenario(sqlite3* db, char const* tmpl, unsigned bot, int seed_points, int delta,
                     int* out_points, char* out_tier, size_t tier_sz)
{
    char sql[512];
    snprintf(sql, sizeof sql,
             "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`) "
             "VALUES (%u, 1, 'stranger', %d, NULL);", bot, seed_points);
    CHECK(exec(db, sql) == SQLITE_OK, "scenario seed");
    apply_points(db, tmpl, bot, 1, delta);
    read_row(db, bot, 1, out_points, out_tier, tier_sz);
}

int main(int argc, char** argv)
{
    if (argc != 2) { fprintf(stderr, "usage: %s <dbfile>\n", argv[0]); return 2; }
    sqlite3* db = NULL;
    if (sqlite3_open(argv[1], &db) != SQLITE_OK) { fprintf(stderr, "open failed\n"); return 1; }
    sqlite3_exec(db, "PRAGMA journal_mode=WAL;", 0, 0, 0);
    sqlite3_exec(db, "PRAGMA synchronous=FULL;", 0, 0, 0);

    CHECK(exec(db, "CREATE TABLE `bot_player_relationship` ("
              "`bot` INT NOT NULL, `player` INT NOT NULL, `tier` TEXT, `points` INT, "
              "`last_interaction_at` DATETIME, PRIMARY KEY (`bot`, `player`));") == SQLITE_OK,
          "relationship table");

    int pts;
    char tier[32];

    /* Fresh insert: the INSERT side of the upsert lands verbatim. */
    apply_points(db, ODKU_FOLDED, 100, 1, 3);
    read_row(db, 100, 1, &pts, tier, sizeof tier);
    CHECK(pts == 3 && strcmp(tier, "stranger") == 0, "fresh insert (points 3, stranger)");

    /* Threshold boundaries: the tier must reflect POST-increment points. */
    scenario(db, ODKU_FOLDED, 101, 9, 1, &pts, tier, sizeof tier);
    CHECK(pts == 10 && strcmp(tier, "acquaintance") == 0, "9+1 -> 10 acquaintance");
    scenario(db, ODKU_FOLDED, 102, 29, 1, &pts, tier, sizeof tier);
    CHECK(pts == 30 && strcmp(tier, "ally") == 0, "29+1 -> 30 ally");
    scenario(db, ODKU_FOLDED, 103, 59, 1, &pts, tier, sizeof tier);
    CHECK(pts == 60 && strcmp(tier, "trusted") == 0, "59+1 -> 60 trusted");

    /* Just-below boundaries stay in the lower tier. */
    scenario(db, ODKU_FOLDED, 104, 8, 1, &pts, tier, sizeof tier);
    CHECK(pts == 9 && strcmp(tier, "stranger") == 0, "8+1 -> 9 stranger");
    scenario(db, ODKU_FOLDED, 105, 28, 1, &pts, tier, sizeof tier);
    CHECK(pts == 29 && strcmp(tier, "acquaintance") == 0, "28+1 -> 29 acquaintance");
    scenario(db, ODKU_FOLDED, 106, 58, 1, &pts, tier, sizeof tier);
    CHECK(pts == 59 && strcmp(tier, "ally") == 0, "58+1 -> 59 ally");

    /* Negative delta demotes through the same folded thresholds. */
    scenario(db, ODKU_FOLDED, 107, 10, -1, &pts, tier, sizeof tier);
    CHECK(pts == 9 && strcmp(tier, "stranger") == 0, "10-1 -> 9 stranger");
    scenario(db, ODKU_FOLDED, 108, 30, -1, &pts, tier, sizeof tier);
    CHECK(pts == 29 && strcmp(tier, "acquaintance") == 0, "30-1 -> 29 acquaintance");
    scenario(db, ODKU_FOLDED, 109, 60, -1, &pts, tier, sizeof tier);
    CHECK(pts == 59 && strcmp(tier, "ally") == 0, "60-1 -> 59 ally");

    /* Negative control: the MECHANICAL unfold lags - at 59+1 the
     * pre-update points (59) pick 'ally' while points becomes 60. Prove
     * the trap is real so the fold can never be "simplified" away. */
    scenario(db, ODKU_UNFOLDED, 110, 59, 1, &pts, tier, sizeof tier);
    CHECK(pts == 60 && strcmp(tier, "ally") == 0,
          "unfolded control lags: 59+1 -> points 60 but tier ally (WRONG by contract)");

    /* The epoch read path (the strftime('%s') runtime literal). */
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT strftime('%s', `last_interaction_at`) "
                                 "FROM `bot_player_relationship` WHERE `bot` = 110;", -1,
                                 &stmt, NULL) == SQLITE_OK, "epoch prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "epoch step");
        CHECK(sqlite3_column_int64(stmt, 0) > 0, "epoch seconds > 0");
        sqlite3_finalize(stmt);
    }

    /* The gossip write/read/prune shapes: datetime('now', '+7 day') expiry
     * insert, CURRENT_TIMESTAMP read filter, and expired-row prune. */
    CHECK(exec(db, "CREATE TABLE `world_gossip` (`id` INTEGER PRIMARY KEY, `text` TEXT, "
              "`category` TEXT, `source_bot` INT, `expires_at` DATETIME);") == 0,
          "gossip table");
    CHECK(exec(db, "INSERT INTO `world_gossip` (`text`, `category`, `source_bot`, `expires_at`) "
              "VALUES ('hello', 'rumor', 300, datetime('now', '+7 day'));") == 0,
          "gossip expiry insert");
    {
        /* The id-omitting insert auto-assigned through the rowid alias
         * (the translation requirement, made explicit). */
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT `id` FROM `world_gossip` WHERE `source_bot` = 300 "
                                 "AND `text` = 'hello';", -1, &stmt, NULL) == SQLITE_OK,
              "gossip id prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "gossip id step");
        CHECK(sqlite3_column_int64(stmt, 0) == 1, "gossip auto-assigned id == 1");
        sqlite3_finalize(stmt);
    }
    CHECK(exec(db, "INSERT INTO `world_gossip` (`text`, `category`, `source_bot`, `expires_at`) "
              "VALUES ('stale', 'rumor', 300, datetime('now', '-1 day'));") == 0,
          "gossip expired insert");
    CHECK(exec(db, "DELETE FROM `world_gossip` WHERE `expires_at` IS NOT NULL "
              "AND `expires_at` < CURRENT_TIMESTAMP;") == 0,
          "gossip prune");
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM `world_gossip` WHERE "
                                 "(`expires_at` IS NULL OR `expires_at` > CURRENT_TIMESTAMP);",
                                 -1, &stmt, NULL) == SQLITE_OK, "gossip read prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "gossip read step");
        CHECK(sqlite3_column_int(stmt, 0) == 1, "gossip read filter keeps only unexpired");
        sqlite3_finalize(stmt);
    }

    /* INSERT OR IGNORE backstory shape: the second insert is ignored. */
    CHECK(exec(db, "CREATE TABLE `bot_backstory` (`bot` INT NOT NULL, `text` TEXT, PRIMARY KEY (`bot`));") == 0,
          "backstory table");
    CHECK(exec(db, "INSERT OR IGNORE INTO `bot_backstory` (`bot`, `text`) VALUES ('200', 'a');") == 0,
          "backstory first insert");
    CHECK(exec(db, "INSERT OR IGNORE INTO `bot_backstory` (`bot`, `text`) VALUES ('200', 'b');") == 0,
          "backstory duplicate insert must be ignored (not an error)");
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT COUNT(*), MAX(`text`) FROM `bot_backstory`;", -1, &stmt, NULL) == SQLITE_OK,
              "backstory count prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "backstory count step");
        CHECK(sqlite3_column_int(stmt, 0) == 1, "backstory exactly one row");
        CHECK(strcmp((char const*)sqlite3_column_text(stmt, 1), "a") == 0, "backstory kept the first text");
        sqlite3_finalize(stmt);
    }

    /* TravelMgr's benign runtime DDL parses and round-trips as-is. */
    CHECK(exec(db, TRAVELMGR_DDL) == SQLITE_OK, "travelmgr DDL parses");
    CHECK(exec(db, TRAVELMGR_DDL) == SQLITE_OK, "travelmgr DDL idempotent (IF NOT EXISTS)");
    CHECK(exec(db, "INSERT INTO `ai_playerbot_zone_level` VALUES (42, 17);") == SQLITE_OK, "travelmgr insert");
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT id, level FROM `ai_playerbot_zone_level` WHERE id = 42;", -1,
                                 &stmt, NULL) == SQLITE_OK, "travelmgr select");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "travelmgr row");
        CHECK(sqlite3_column_int64(stmt, 0) == 42 && sqlite3_column_int64(stmt, 1) == 17,
              "travelmgr round trip (bigint(20) affinity)");
        sqlite3_finalize(stmt);
    }

    /* The SQLite-correct text escape (quote-doubling ONLY - SQLite
     * literals have no backslash escapes): a backslash+quote string
     * round-trips byte-identical, the behavior EscapeSql's DO_SQLITE
     * branch must produce. Doubling the backslash would store
     * "path \\o/ and it's" (18 bytes) instead of 17. */
    CHECK(exec(db, "CREATE TABLE t_escape(id INTEGER PRIMARY KEY, text TEXT);") == 0,
          "escape table");
    CHECK(exec(db, "INSERT INTO t_escape(text) VALUES ('path \\o/ and it''s');") == 0,
          "escape insert");
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT text FROM t_escape WHERE id = 1;", -1,
                                 &stmt, NULL) == SQLITE_OK, "escape select prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "escape select step");
        CHECK(sqlite3_column_bytes(stmt, 0) == 17, "escape round trip is 17 bytes");
        CHECK(strcmp((char const*)sqlite3_column_text(stmt, 0), "path \\o/ and it's") == 0,
              "escape round trip is byte-identical (backslash single, '' -> ')");
        sqlite3_finalize(stmt);
    }

    /* The anticheat rowid rewrite with exactly two positional binds
     * (fingerprint, prune count - oldest N to delete) - same binds as the
     * MySQL statement. */
    CHECK(exec(db, "CREATE TABLE system_fingerprint_usage ("
              "id INTEGER PRIMARY KEY, fingerprint INT NOT NULL, `time` INT NOT NULL);") == 0,
          "fingerprint table");
    for (int i = 1; i <= 5; ++i)
    {
        char sql[128];
        snprintf(sql, sizeof sql, "INSERT INTO system_fingerprint_usage (fingerprint, `time`) VALUES (7, %d);", i);
        CHECK(exec(db, sql) == 0, "fingerprint seed 7");
    }
    CHECK(exec(db, "INSERT INTO system_fingerprint_usage (fingerprint, `time`) VALUES (8, 1);") == 0, "fingerprint seed 8");
    CHECK(exec(db, "INSERT INTO system_fingerprint_usage (fingerprint, `time`) VALUES (8, 2);") == 0, "fingerprint seed 8b");
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, ANTICHEAT_ROWID_PRUNE, -1, &stmt, NULL) == SQLITE_OK, "prune prepare");
        CHECK(sqlite3_bind_int(stmt, 1, 7) == SQLITE_OK, "prune bind fingerprint");
        CHECK(sqlite3_bind_int(stmt, 2, 3) == SQLITE_OK, "prune bind count");
        CHECK(sqlite3_step(stmt) == SQLITE_DONE, "prune step");
        sqlite3_finalize(stmt);
    }
    {
        sqlite3_stmt* stmt = NULL;
        CHECK(sqlite3_prepare_v2(db, "SELECT fingerprint, COUNT(*), MIN(`time`) FROM system_fingerprint_usage "
                                 "GROUP BY fingerprint ORDER BY fingerprint;", -1, &stmt, NULL) == SQLITE_OK,
              "prune verify prepare");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "prune verify row 1");
        CHECK(sqlite3_column_int(stmt, 0) == 7 && sqlite3_column_int(stmt, 1) == 2 && sqlite3_column_int(stmt, 2) == 4,
              "fingerprint 7 kept newest 2 (times 4,5)");
        CHECK(sqlite3_step(stmt) == SQLITE_ROW, "prune verify row 2");
        CHECK(sqlite3_column_int(stmt, 0) == 8 && sqlite3_column_int(stmt, 1) == 2,
              "fingerprint 8 untouched");
        sqlite3_finalize(stmt);
    }

    sqlite3_close(db);
    if (g_failures) { fprintf(stderr, "%d failure(s)\n", g_failures); return 1; }
    printf("odku fixture passed\n");
    return 0;
}
