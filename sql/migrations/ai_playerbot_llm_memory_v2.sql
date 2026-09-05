-- Pocket Realm: playerbot LLM memory v2 (characters DB) - the C2 voiced-fact
-- persistence + C8 conversation-memory tail (migration 0413).
-- Placed in sql/migrations/ (never globbed by tools/stage_database_migrations.py)
-- and appended at the manifest tail via an explicit select_inputs() entry after
-- the 0411/0412 LLM seeds, so no existing migration index or hash changes on
-- upgrade (append-only ledger invariant).
--
-- The seed DDL (native/llm/sql/ai_playerbot_llm_memory.sql) is NEVER edited:
-- both lanes replay this file AFTER the seed, so a fresh provision and an
-- upgraded database converge on the identical schema (pinned by the
-- fresh-vs-truncated table_info parity test in tests/test_sqlite_seeding.py).
--
-- NO BACKFILL, by law: NULL voiced_at / last_voiced_tier / last_greeted_at /
-- last_greet_line means "never voiced" - existing rows keep exactly today's
-- per-process behavior; backfilling would permanently silence historical
-- debt/goal initiations and re-voice stale greeting lines.
--
-- DOWNGRADE LAW: after this entry an APK downgrade fails closed - the ledger
-- check ('DB-REVISION: ledger drift', DatabaseEngine.kt) refuses a revision
-- set it cannot account for. The only paths are stay-on-new or a clean
-- reinstall (data loss). Documented and accepted per plan rp-depth-fix v2.3 C2.

ALTER TABLE `bot_player_facts` ADD COLUMN `voiced_at` bigint(20) unsigned NULL DEFAULT NULL;
ALTER TABLE `bot_player_relationship` ADD COLUMN `last_voiced_tier` tinyint(3) unsigned NULL DEFAULT NULL;
ALTER TABLE `bot_player_relationship` ADD COLUMN `last_greeted_at` timestamp NULL DEFAULT NULL;
ALTER TABLE `bot_player_relationship` ADD COLUMN `last_greet_line` varchar(255) NULL DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `bot_player_history` (
  `bot` bigint(20) unsigned NOT NULL,
  `player_or_channel` bigint(20) unsigned NOT NULL,
  `seq` int(10) unsigned NOT NULL,
  `speaker` varchar(12) NOT NULL DEFAULT '',
  `line` varchar(240) NOT NULL,
  `ts` bigint(20) unsigned NOT NULL,
  PRIMARY KEY (`bot`,`player_or_channel`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
