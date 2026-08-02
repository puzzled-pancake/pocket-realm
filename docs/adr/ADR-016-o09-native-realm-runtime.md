# ADR-016: O09 native realm components and process-generation boundary

Status: accepted and qualified (2026-08-03)

## Decision

Pocket Realm packages `realmd` and the no-bot CMaNGOS world as separate
Android/Bionic x86_64 shared libraries. Non-exported Android services load them
only in the dedicated `:realm` and `:world` processes selected by ADR-013.
MariaDB Connector/C is built from commit
`de6305915f86bb33c83b1fe782a2b8a76920aec1` and linked statically; both
components connect only to O08's app-private Unix socket. Neither component
accepts a caller-selected path, executable, environment, SQL statement, bind
address, port, or credential.

The versioned Binder/JNI/C contract exposes fixed status, start, account,
save, stop, heartbeat, tick, and test-only fault operations. Configuration and
credentials remain mode-0600 app-private files. `realmd` binds
`127.0.0.1:3724`; world binds `127.0.0.1:8085`. Structured readiness is emitted
only after database revision/data initialization and listener startup.

## World process generations

CMaNGOS owns process-lifetime singleton registries and auxiliary queue threads.
A successful world stop therefore saves, performs native teardown, persists a
clean lifecycle record, acknowledges the Binder request, and then retires the
dedicated `:world` process. A later bind creates a fresh process generation.
This follows the report's component/fault-domain model and avoids treating
partial in-process singleton reset as a production persistence boundary.

An uncontrolled world death records a dirty classification and cannot be
silently promoted to clean. Database loss is realm-fatal: the world generation
is retired, O08 performs dirty recovery, and dependencies restart in database
-> realmd -> world order.

## Data and database assembly

User-owned build-5875 input is mounted read-only. The pinned CMaNGOS extractor
produces 158 DBC and 2,429 map files (178,365,278 bytes), hashes every output,
and publishes them atomically only in app-private storage. Client-derived data
is not committed or bundled in the APK.

The append-only database recipe adds the mandatory CMaNGOS DBC SQL,
CMaNGOS fixes, ScriptDev2, ACID, and custom-data layers after O08's original 399
migrations. This preserves shipped migration IDs while bringing the ledger to
410 entries. In particular, `original_data/Spell.sql` supplies the 159-field
`spell_template` required by the pinned core. A narrowly recorded build overlay
also makes the mmap tile loader honor `mmap.enabled=0`; O09 intentionally
qualifies DBC/maps while vmaps/mmaps remain disabled for this no-client gate.

## Qualification

The fixed API-35 x86_64 4 KB AVD passed the complete isolated-runtime test in
61.009 seconds. It proved 20 clean realm cycles, structured realmd/world ready,
11 rejected control-token fuzz cases without service loss, account creation,
save, clean process retirement, deliberate world death and recovery, deliberate
MariaDB death with observed dirty recovery, and a final dependency-ordered
clean stop. A concurrent host observation found only `0100007F:0E8C` and
`0100007F:1F95` in TCP LISTEN state. The authoritative structured result is
`tests/avd/AVD-Modern-x86_64-v1/evidence/realmRuntime-o09-acceptance-20260803.PASS.json`.
