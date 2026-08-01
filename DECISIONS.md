# Pocket Realm architecture decisions

The canonical offline decisions are ADR-001 through ADR-012 in
[`docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx`](docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx). This file is a concise repository overlay. A replacement decision must identify the report ADR/section it supersedes, cite implementation evidence, describe migration/rollback, and update `PLAN.md`, `FEATURES.json`, and `PROGRESS.md`.

## Product and legal boundary

1. The product is one installed Android application and UX, not one process and not "SPP inside an APK."
2. The APK does not distribute the proprietary WoW executable, MPQs, client-derived DBC/maps/vmaps/mmaps, or unlicensed third-party assets. Users import a supported client they are entitled to use.
3. The initial compatibility invariant is Vanilla 1.12.1 build 5875 with pinned CMaNGOS Classic, Playerbots, Classic-DB, MariaDB, toolchain, runtime, locale, addon, and device-profile inputs.
4. Offline is the first release. Connected code cannot enter the offline artifact or start before O22 is done.
5. Offline and connected realms are permanently separate universes; offline progress is never merged into a connected economy.

## Android and component topology

6. Kotlin/Compose owns UI and an Android foreground `RuntimeSupervisor`. Native MariaDB, `realmd`, `mangosd`, and the Wine client run as supervised, fault-isolated app components where the G0 packaging experiments prove that model.
7. The production topology is chosen only after PKG-01/PKG-02/PKG-06 on current-target and 16 KB lanes. A target-28 unpack/exec result is research evidence, not a production path.
8. The existing `libpocketrealm.so` and versioned C ABI remain reusable library-lane/control evidence. They do not by themselves require database, auth, world, client, or UI to share a process.
9. Component boundaries use narrow Binder/JNI/C ABI calls or a versioned app-private control socket. No C++ exception, ambiguous callback lifetime, raw secret, or unbounded payload crosses a boundary.
10. Native components never daemonize, rely on stdin, or require signal-only shutdown. They publish structured readiness, metrics, exit reasons, and stable error codes.
11. Realm listeners bind only to 127.0.0.1 for the offline MVP; MariaDB prefers an app-private Unix socket with TCP disabled. LAN exposure is a separate feature and threat model.
12. Executable native code ships through signed APK-managed locations. Mutable prefixes, caches, databases, imported content, and journals stay app-private and are never treated as executable update payloads.

## Database, lifecycle, and recovery

13. MariaDB is the offline production database baseline because it matches upstream CMaNGOS/Playerbots schemas and migrations. The SQLite translation work is retained as diagnostic evidence but is not on the production critical path.
14. `DatabaseService` exclusively owns its datadir. Migrations run with realm/world stopped, use an ordered hash-verified ledger, and require a verified pre-migration snapshot.
15. Database credentials are random and least-privilege, stored through Android-protected facilities where practical, and excluded from UI, logs, rendered config, and support bundles.
16. The durable supervisor journal records session/instance tokens, exact phase, component states, last durable action, and clean/dirty state using atomic temp+fsync+rename or a transactional store.
17. Android may kill any component at any instruction. Save & Exit is the normal fast path, but correctness comes from MariaDB durability/recovery, the journal, consistent backups, and forced-stop tests.
18. Graceful shutdown is client close -> world save -> world shutdown -> `mangosd` exit -> `realmd` exit -> MariaDB checkpoint/clean stop -> journal clean. Any forced step leaves a dirty recovery requirement.
19. A live datadir copy is not a backup. Backups use a database-consistent dump/snapshot, include manifests/config, and are verified by restore into a fresh datadir followed by world-ready assertions.
20. Component ownership uses unguessable session/instance tokens, not stale PIDs. Unknown listeners are reported as `PORT_IN_USE`, never killed.

## Client import and runtime

21. Imported source is immutable. SAF copy is resumable, bounded, path-safe, storage-preflighted, and atomically publishes only complete managed-client and derived-data manifests.
22. `ClientRuntime` separates `x86DirectWine` from `armTranslatedWine`; import, accounts, database, supervisor, and backups do not depend on the client execution engine.
23. The first client gate is direct x86 Wine on a fixed x86/x86_64 AVD. Stock Winlator is not the x86 implementation.
24. The ARM release path proves ARM-B first: Box64 plus pinned 64-bit Wine WoW64 running the 32-bit client. ARM-A/Box86 is added only for a justified, measured 32-bit-capable device lane.
25. Prefixes and translator/shader caches are isolated by complete tuple and bounded. A changed Wine/translator/renderer/driver compatibility ID stages new mutable runtime state rather than mutating the only accepted copy.
26. WineD3D at 1280x720 or lower, 30 FPS, audio off, and minimal overlays is the deterministic initial safe mode. DXVK/Turnip and higher profiles are accepted only by per-device measurement; fallback is always visible.
27. Alternative runtimes such as FEX are outside the offline release critical path until a separate laboratory feature proves non-root Android, packaging, page-size, graphics, input/audio, lifecycle, and sustained-device behavior.
28. Imported client files are guest data, not permission to execute arbitrary Android binaries, unknown launchers, injected DLLs, or extra Windows applications.

## Input, bots, UX, and qualification

29. Touch, gamepad, keyboard/mouse, IME, focus, pointer capture, hot-plug, and stuck-key release share one logical input contract. Project addons present UI/actions but are not the input driver.
30. Playerbots stay disabled until zero-bot persistence and forced-recovery gates pass. The first supported tier is 25 bots; 50/100 are qualified profiles, not defaults.
31. Auction-house automation stays disabled until the base bot tier is stable. Any market helper remains bounded, attributable, and stops with the offline realm.
32. Protected/visible/party/combat bots and human-first scarce objectives may refine world quality only after measured admission and persistence controls exist.
33. Setup, account provisioning, Start/Stop, recovery, backup, and safe mode must require no shell, SQL, console window, or manual config. Account operations use the core command/control path and never expose plaintext secrets.
34. Emulator metrics support reproducible debugging and regression only. Release claims require named ARM devices, GPU/runtime tuples, 8/12 GB coverage as advertised, 16 KB compatibility, touch/controller UX, force-stop recovery, and SOAK evidence.
35. Automatic mode selects only accepted tuples and bot tiers. Effective renderer/runtime/profile and every fallback are visible in diagnostics.

## Connected realm extension

36. Connected launch is PvE-only. World PvP, battlegrounds, arenas, honor progression, and PvP rewards are disabled and tested.
37. P2P moves physical hosting but one Realm Kernel orders durable shared value; SQL is never writable multi-primary.
38. One real-time authority owns each complete continent or dungeon/raid instance. Ordinary combat does not wait for consensus.
39. Direct authenticated QUIC is preferred with relay fallback and visible route/privacy status. Connectivity services do not own realm history.
40. Durable operations carry idempotency keys, expected versions, authority epochs, bounded payloads, and journal/checkpoint provenance.
41. If all peers are offline, the realm becomes dormant. Competing certified roots trigger fork recovery and are never merged.
42. Anti-cheat is server-invariant and translated-client aware; client memory/renderer/timing signals are secondary evidence, not primary authority.
43. The local Legacy Session Anchor preserves the client TCP/header-crypt session during backend handoff but owns no gameplay/economy decisions.
44. Planned migration uses pre-copy, ordered deltas, barriers, equal semantic state roots, a higher epoch, atomic route switch, and source fencing. A standby applies authoritative deltas rather than independently simulating the world.
45. Every queue, cache, snapshot, retry, buffer, imported archive, and network stream has an explicit bound and exhaustion behavior.
