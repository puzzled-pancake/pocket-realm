-- GENERATED FILE - from schemas/timeframes/vanilla.json by
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
