# Current project state

Last verified implementation commit: `0525984 O04: embeddable realm lifecycle facade (C ABI + libpocketrealm.so)`

Active feature: `none`

Current gate: `G0 - architecture and production packaging` (`O05` next)

Plan/reference alignment: `1 August 2026`

## Source of truth

- `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx` is now the canonical offline engineering reference.
- The adjacent PDF is the fixed-layout reading copy.
- `PLAN.md` is the repository execution overlay and connected-realm extension.
- `DECISIONS.md` contains adopted decisions and evidence-backed deltas.
- `FEATURES.json` maps O05-O22 to report gates G0-G6 and named report sections.

## Reconciliation result

The report changes the production critical path:

1. Prove current-target Android native/Wine packaging and fault isolation.
2. Prove direct x86 Wine and build-5875 login independently.
3. Prove native x86_64 MariaDB, `realmd`, and no-bot `mangosd` independently.
4. Integrate the supervisor/import/data/account/backup flow on x86.
5. Add measured bots and mobile input UX.
6. Move unchanged server/database contracts to ARM64 and replace only the client backend.
7. Complete modern Android, 16 KB, security, update, and physical release gates.

Native MariaDB is the production database baseline. The SQLite translation and in-process realm work are retained as useful exploratory evidence, but no pending feature may continue them as the production route without an explicit superseding ADR and tests.

## Verified work retained from O01-O04

### O01 - repository and provenance

- Monorepo layout, pinned CMaNGOS/Playerbots/Classic-DB sources, licenses, flavor manifest, build bootstrap, and proprietary-data exclusions are complete.
- This contributes to report gate G0 and task A-001.

### O02 - Android shell

- Kotlin/Compose shell, navigation, settings, separated storage roots, structured logging, foreground service, notification Save & Exit action, and supervisor state tests are complete.
- The simulated bring-up remains UI scaffolding; G3 replaces it with the report state machine and component journal.

### O03 - native server build

- CMaNGOS Classic and Playerbots build for x86_64 and `arm64-v8a` with the pinned NDK.
- ELF LOAD segments are 16 KB aligned; dependencies are pinned; x86_64 device smoke and ARM64 artifact checks pass.
- This is reusable downstream-port evidence. It does not satisfy G2 until native MariaDB, no-bot world-ready, structured control, and recovery pass.

### O04 - lifecycle/library experiment

- Versioned C ABI and `libpocketrealm.so` prove create/start/health/save/stop/destroy contracts, fatal-path containment, and two in-process cycles.
- The focused review fixes for thread join, failed-path teardown, pointer lifetime, UB, reinitialization, and fake command success remain valid.
- `schemas/sources.json` now pins the actual O04 CMaNGOS gitlink `c096bada9e4e...`; the prior `edf89d3e...` entry was its parent and made the source check report drift.
- This contributes to the PKG-02/library lane and reusable control code. It does not select the production topology.

## Last successful implementation checks

- `scripts/build_native.py --abi x86_64 --runtime --runtime-tests cmangos` -> ALL STAGES OK
- `scripts/build_native.py --abi arm64-v8a --runtime --runtime-tests all` -> ALL STAGES OK
- `scripts/smoke_native.py --abi x86_64 --device --runtime` -> SMOKE OK
- `tools/run_realm_test.py --abi x86_64` -> NATIVE LIFECYCLE TEST PASS, two full cycles
- `:app:testDebugUnitTest RealmNativeTest` -> PASS
- Standalone `mangosd`/`realmd` remain unchanged when `POCKET_EMBEDDED` is off.

## Known gaps under the canonical report

- PKG-01/PKG-02/PKG-06 have not been completed across legacy/current/16 KB Android lanes, including deliberate cross-process crash isolation.
- No direct `x86DirectWine` self-test or build-5875 login-screen proof exists.
- No native Android MariaDB service, app-private Unix socket, migration ledger, dirty recovery, or consistent backup proof exists.
- `realmd`/`mangosd` have not reached structured ready with MariaDB, verified client data, bots off, and loopback probes.
- The full report X/FUN/FLT/SOAK gates remain pending.

## Do not continue from the old next action

- Do not wire O04 directly into the Android supervisor as the production realm merely because the JNI declarations exist.
- Do not continue SQLite world-schema parity as O06/O07 production work.
- Do not begin ARM client translation, bots, DXVK tuning, or UX polish before their report gates.

## Blockers

None for O05. A user-owned build-5875 client becomes required at O07; named AVD/device and physical qualification inputs are required at their later gates.

## Next action

Run `python3 scripts/next_feature.py --activate`. It must select **O05 - G0 production packaging, process isolation, and capability record**.

O05 should start by reading report sections 0-6, 8.4, 20.1-20.2, 25.7, and 27.4, then run PKG-01/PKG-02/PKG-06 without deleting the O04 experiment.

## Session note

Replace this file with the current verified state; do not append a permanent diary. Git history is the durable record.
