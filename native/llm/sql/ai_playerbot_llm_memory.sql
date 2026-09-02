-- Pocket Realm: playerbot LLM memory (characters DB)
-- Placed in native/llm/sql/ (never globbed by tools/stage_database_migrations.py)
-- and appended at the manifest tail via explicit select_inputs() entries, so no
-- existing migration index or hash changes on upgrade.

CREATE TABLE IF NOT EXISTS `bot_backstory` (
  `bot` bigint(20) unsigned NOT NULL,
  `text` text NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`bot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `bot_player_facts` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `bot` bigint(20) unsigned NOT NULL,
  `player` bigint(20) unsigned NOT NULL,
  `fact_text` varchar(512) NOT NULL,
  `category` enum('preference','shared-event','opinion','player-identity') NOT NULL DEFAULT 'shared-event',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `bot_player` (`bot`,`player`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `bot_player_relationship` (
  `bot` bigint(20) unsigned NOT NULL,
  `player` bigint(20) unsigned NOT NULL,
  `tier` enum('stranger','acquaintance','ally','trusted') NOT NULL DEFAULT 'stranger',
  `points` int(11) NOT NULL DEFAULT 0,
  `tier_since` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_interaction_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`bot`,`player`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
