-- Pocket Realm: shared world gossip pool (world DB)
-- Server-global, not per-character: follows the ai_playerbot_texts precedent
-- (world DB / WorldDatabase). Tail-appended explicitly like the characters file.

CREATE TABLE IF NOT EXISTS `world_gossip` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `text` varchar(512) NOT NULL,
  `category` varchar(64) NOT NULL DEFAULT 'general',
  `source_bot` bigint(20) unsigned NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
