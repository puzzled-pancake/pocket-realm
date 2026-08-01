# Pocket Realm — Claude Code instructions

## Mission
Build one product in this fixed order:

1. **Offline foundation:** a polished, controller-native Android Vanilla WoW simulator for Retroid Pocket 6 using a user-supplied client, native ARM64 CMaNGOS/Playerbots, safe local persistence, and a simple launcher.
2. **Connected expansion:** a permanently separate PvE realm with P2P hosting, one canonical economy, dormancy, anti-cheat, failover, and seamless backend migration.

Do not start connected production code until offline feature `O22` is done.

## Sources of truth
- `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx` is the canonical offline engineering reference; the adjacent PDF is its fixed-layout reading copy.
- `PLAN.md` is the repository execution overlay and connected-realm extension.
- `DECISIONS.md` summarizes adopted decisions and explicit evidence-backed deltas from the report.
- `FEATURES.json` defines order, dependencies, acceptance, report section pointers, and status.
- `PROGRESS.md` is the current handoff; Git is the durable history.

For ordinary feature work, read the report's document-control guidance and only the report sections named by the feature, then its plan section, matching `.claude/rules/`, decisions, and relevant code. Read report Sections 0-6 in full before changing architecture. Do not otherwise preload all 80 report pages.

## Start a coding session
1. Run `pwd`, `git status --short`, and `git log --oneline -8`.
2. Read `PROGRESS.md`; preserve any pre-existing user changes.
3. Run `python3 scripts/next_feature.py --activate`. Continue the active feature or use the returned feature; the script marks a new selection `active`.
4. Read that feature's printed record, named report sections, and referenced plan section. Open `DECISIONS.md` only for decisions that affect it.
5. Run the smallest existing smoke check for the affected subsystem.
6. Use Plan mode for cross-subsystem, persisted-data, protocol, security, or uncertain work. Implement clear local changes directly.

## Work rules
- Complete one feature at a time. Fix a discovered prerequisite before its dependent feature.
- Inspect upstream code and tests before replacing behavior.
- Implement the smallest complete production solution. No stubs, fake success, placeholder state, or silent fallback.
- Avoid unrelated rewrites. Never discard or overwrite pre-existing work.
- Prove the feature with the narrowest useful test, build, recovery drill, benchmark, screenshot, or device scenario. Run broader suites at milestone boundaries.
- Fix causes, not signals: do not weaken assertions, skip failures, reduce durability, or empty the visible world to obtain green output.
- Use a read-only subagent for broad research or a focused high-risk review; ordinary changes do not need multi-agent ceremony.
- Commit only coherent passing work with a descriptive message.

## Non-negotiable product rules
- Never redistribute proprietary WoW client files or game data. Users import a supported client they are entitled to use.
- Offline play has no Internet dependency. Realm services bind to loopback and MariaDB uses an app-private Unix socket where possible. Server, bots, and database are native for each Android ABI; only the Windows client uses Wine and, on ARM, CPU translation.
- Prove current-target native/Wine packaging, direct x86 Wine, and native x86 MariaDB/zero-bot realm gates before ARM client translation or bots.
- Android termination is normal. Correctness cannot depend on `onDestroy()`, a console window, or Save & Exit.
- Mutable realm data defaults to internal storage. Realm, runtime, addon, and visual changes use verified, rollbackable generations.
- The basic UI remains simple. Advanced runtime, renderer, bot, and diagnostic controls are bounded and clearly labeled.
- Controller input is handled by native Android normalization and a bundled Wine input bridge; do not require root or Accessibility services.
- Optimize invisible/coarse work before correctness, current gameplay, visible residents, party bots, combat bots, or instance bots.
- Offline and connected characters, IDs, keys, databases, and backups never merge. Connected play is PvE-only and keeps one canonical durable history.

## Implementation boundaries
- Android app and UI: Kotlin, Compose, structured coroutines, foreground service.
- Existing server/runtime integration: C++20 with RAII; supervised fault-isolated components selected by the G0 packaging evidence.
- Offline database: pinned native MariaDB with an app-private datadir/socket, migration ledger, and consistent backup/restore.
- Connected transport, Realm Kernel, replication, and migration control: stable Rust after `O22`.
- Cross-language calls: narrow versioned C ABI; opaque handles, explicit ownership, bounded buffers, error codes; no exception or panic crosses it.
- Build/import/validation tools: Python 3.12+. Shell only for short wrappers.
- Pin dependencies and runtime components by version/commit and hash. “Latest” is not a release policy.

Persistence, ownership/economy, client import, native loading, authentication, anti-cheat, replication, and migration need an explicit invariant, a failure/adversarial test, and one focused review before their milestone is accepted.

## Feature completion
Mark a feature `done` only when its acceptance criteria are genuinely implemented, relevant checks pass, hardware evidence is recorded where required, and no fallback hides a regression. Update only its `status`, `notes`, and `evidence` in `FEATURES.json`; replace `PROGRESS.md` with the verified commit, checks, blockers, and next action; then leave a coherent commit.

If legal assets, hardware, credentials, or an irreversible external action are unavailable, mark the feature `blocked` with the exact missing input, update `PROGRESS.md`, and stop that feature. A later session may select another eligible feature. Never lower the requirement.

## Context discipline
Use `/clear` between unrelated workstreams and `/compact` before context pressure becomes disruptive. Preserve the active feature ID, decisions made, modified files, commands/checks, failures, and next action in `PROGRESS.md` before compacting or ending a session.
