package com.pocketrealm.client

/**
 * GENERATED FILE - from the schemas/timeframes overlay SQL files
 * (schemas/timeframes/vanilla/overlays/, per stage) by
 * tools/generate_timeframe_era.py overlays. DO NOT EDIT;
 * regenerate with `python tools/generate_timeframe_era.py overlays`.
 * schemas/timeframes/overlay-pins.json binds every overlay's
 * sha256 (and this catalog's) to the source SQL; tools/
 * check_timeframes.py fails loud on drift.
 *
 * The packaged-app form of the per-stage reversible overlay SQL
 * (schemas/ is not packaged): the desktop stage applier executes
 * these statements through the SQLite seam, statement policy
 * enforced (INSERT OR IGNORE only, never DELETE - design §2).
 */
object GeneratedTimeframeOverlayCatalog {

    /** The pinned sha256 of each overlay's source SQL. */
    val overlaySha256: Map<String, String> = mapOf(
        "vanilla-1.1" to "5d7bdbdd0f8217080efb6229b20685b477f39c2e30b922eff1477b6045448939",
        "vanilla-1.10" to "bc1eaf06625385f3987bec22c5f83fca2e5930320a98937d1517a513cadcc282",
        "vanilla-1.11" to "bbdd93a7f4298d2d547adf077671ce3463ac51b0df1165d6c2ab25f53f00224d",
        "vanilla-1.12.1" to "f57027ae36fb0a996939a2bc5bcd7117b4342ea52b10a4ff5858262ed953c50d",
        "vanilla-1.2" to "6aee53900b33364dac7cadaedf4f398091ef40ea8d3aaf22141de95a7e1f916a",
        "vanilla-1.3" to "2ec94c1b876df3cda36f5607bf12d7e746f6438b08b046f8e963dec5e24d9ada",
        "vanilla-1.4" to "4a5a30231ec21e73c82d45e4d8617b605db5f44e74ed10f9733799896dfd2abd",
        "vanilla-1.5" to "00f06c0240a3955badc36d7001cd270c6e988fe3f8ee5cbf1841b6e4ccd4f79a",
        "vanilla-1.6" to "4c1b9e4245ca91d93e8ab20b3f9f1c290e6aacc312aa9dfadcff0db3b61ead57",
        "vanilla-1.7" to "e74ad63a1eecd4efe33ea98b916ac288dd697be1b1afdd34cc2c48f414f536b4",
        "vanilla-1.8" to "551746d54358dcfbe76c407ac67d5e5ad9119954fcf9b5acb382f7a67fa8c379",
        "vanilla-1.9" to "1ed54e4b688114d3edadc9f0f5afb910c783ed6ef34a192f2582927596b8596f",
    )

    /** The overlay SQL for one stage, verbatim from the pinned
     * schemas file; null for an unregistered stage. The when is the
     * generated stage table - its length IS the staged stage count. */
    @Suppress("LongMethod")
    fun overlaySql(stageId: String): String? = when (stageId) {
        "vanilla-1.1" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.1 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.1
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000001 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000001, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.1');

-- Stage-marker condition: entry 8000001 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000001, 12, 8000001, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.1 active');
"""
        "vanilla-1.10" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.10 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.10
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000010 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000010, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.10');

-- Stage-marker condition: entry 8000010 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000010, 12, 8000010, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.10 active');
"""
        "vanilla-1.11" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.11 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.11
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000011 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000011, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.11');

-- Stage-marker condition: entry 8000011 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000011, 12, 8000011, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.11 active');
"""
        "vanilla-1.12.1" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.12.1 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.12.1 — current release content
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000012 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000012, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.12.1');

-- Stage-marker condition: entry 8000012 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000012, 12, 8000012, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.12.1 active');
"""
        "vanilla-1.2" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.2 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.2
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000002 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000002, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.2');

-- Stage-marker condition: entry 8000002 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000002, 12, 8000002, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.2 active');
"""
        "vanilla-1.3" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.3 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.3
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000003 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000003, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.3');

-- Stage-marker condition: entry 8000003 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000003, 12, 8000003, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.3 active');
"""
        "vanilla-1.4" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.4 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.4
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000004 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000004, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.4');

-- Stage-marker condition: entry 8000004 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000004, 12, 8000004, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.4 active');
"""
        "vanilla-1.5" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.5 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.5
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000005 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000005, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.5');

-- Stage-marker condition: entry 8000005 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000005, 12, 8000005, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.5 active');
"""
        "vanilla-1.6" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.6 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.6
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000006 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000006, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.6');

-- Stage-marker condition: entry 8000006 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000006, 12, 8000006, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.6 active');
"""
        "vanilla-1.7" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.7 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.7
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000007 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000007, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.7');

-- Stage-marker condition: entry 8000007 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000007, 12, 8000007, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.7 active');
"""
        "vanilla-1.8" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.8 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.8
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000008 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000008, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.8');

-- Stage-marker condition: entry 8000008 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000008, 12, 8000008, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.8 active');
"""
        "vanilla-1.9" -> """-- GENERATED FILE - from schemas/timeframes/vanilla.json by
-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.
-- Stage vanilla-1.9 overlay: REVERSIBLE, IDEMPOTENT,
-- flag-set statements only - NEVER DELETE (design section 2;
-- backward moves re-apply an earlier overlay over the same
-- datadir, so nothing may be destroyed).
-- Patch 1.9
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
--
-- v1 honesty: this overlay carries the stage-MARKER scaffolding
-- ONLY. The measured availability sets (event windows, vendor/
-- loot/quest/entry demotions) are 'pending-s4-pin' in the
-- manifest; until they land this overlay changes no gameplay
-- state - the markers are unscheduled and nothing references
-- their conditions yet.

-- Stage marker event: entry 8000009 (the reserved range
-- 8000001+, verified to hold zero rows in a real seeded datadir).
INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, holiday, linkedTo, description)
VALUES (8000009, 0, 0, 0, 0, 0, 'PocketRealm stage marker: vanilla-1.9');

-- Stage-marker condition: entry 8000009 (the same reserved
-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this
-- stage's marker.
INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, value3, value4, flags, comments)
VALUES (8000009, 12, 8000009, 0, 0, 0, 0, 'PocketRealm stage vanilla-1.9 active');
"""
        else -> null
    }
}
