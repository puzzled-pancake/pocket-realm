# Pocket Realm implementation plan

This is the repository-specific execution overlay. The canonical offline engineering reference is
[`docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.pdf`](docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.pdf); the adjacent DOCX is its editable source. `FEATURES.json` defines executable order and acceptance.

## 0. Authority and reading order

For the offline Android port, use this precedence:

1. The research report's ADR-001 through ADR-012, component contracts, gates G0 through G6, test matrices, and stop conditions are normative.
2. `PLAN.md` maps that reference to this repository and records the connected-realm extension.
3. `DECISIONS.md` records project decisions and explicit, evidence-backed deltas. It may not silently contradict the report.
4. `FEATURES.json` provides dependencies, acceptance, report section pointers, and status.
5. `PROGRESS.md` records the current verified handoff; Git remains durable history.

If implementation evidence requires a different offline architecture, add a superseding decision with the affected report ADR/section, rationale, migration impact, rollback, and tests before changing the plan. During ordinary feature work, read the report's document-control guidance plus only the sections named by the feature record.

## 1. Product definition

Pocket Realm is one installed Android application and coherent UX that imports a supported, user-supplied WoW 1.12.1 build 5875 client, supervises a native local realm, launches only the Windows client through a replaceable Wine backend, and owns setup, accounts, bots, controls, diagnostics, recovery, and updates.

"One application" does not mean one Linux process. The production baseline is an Android control plane supervising fault-isolated app processes or library-backed services for MariaDB, `realmd`, `mangosd`, and the Wine client. The server and database are native for the Android ABI; only `WoW.exe` uses Wine and, on ARM, CPU translation.

The same repository may later add a permanently separate connected PvE realm. Connected and offline storage, identities, databases, backups, services, and release artifacts never merge.

### Non-goals

- Redistributing the proprietary client, client-derived data, or unlicensed third-party assets.
- Running the Windows SPP server stack under Wine or packaging SPP Classics itself.
- Recompiling or rewriting the proprietary client.
- Basing production on target-28 writable-directory execution, stock Winlator, arbitrary DLL injection, or arbitrary native payload downloads.
- Enabling playerbots before a zero-bot realm passes persistence and forced-recovery gates.
- Promising support for an unmeasured client build, device, GPU, ABI, RAM tier, or bot population.
- Starting connected implementation before offline feature O22 is done.

## 2. Repository and implementation layout

```text
android/app/                 Kotlin/Compose UI, supervisor, services, import, input
native/cmangos/              pinned CMaNGOS Classic source
native/playerbots/           pinned playerbots source
native/classic-db/           pinned Classic-DB source and migration inputs
native/mariadb/              Android MariaDB build/launcher integration
native/pocket-runtime/       reusable C ABI/control/logging experiments
runtime/providers/           ClientRuntime contract and tuple manifests
runtime/wine-x86/            direct x86/x86-64 Wine development backend
runtime/box64/               ARM translated-Wine backend
addons/                      project-owned controller/status/bot UI addons
schemas/                     compatibility, control, config, journal, and ABI schemas
tools/                       deterministic build/import/validation tooling
tests/                       component, contract, integration, fault, and soak tests
connected/                   Rust connected expansion after O22
docs/                        canonical report, patches, user and developer docs
```

Pin every upstream source, SQL revision, toolchain, runtime tuple, and generated input. Keep executable native code in signed APK-managed locations and mutable data in app-private storage. Kotlin owns UI and supervision; C++ and upstream native code own the local realm; MariaDB remains the reference database; Rust is reserved for the connected expansion.

The app communicates with native components through narrow Binder/JNI/C ABI or versioned app-private control sockets. A C ABI is valuable for a library-backed component but is not evidence that all components belong in the UI or supervisor process.

## 3. Reconciliation with completed work

The report changes the route, not the validity of already-proven artifacts:

| Work | Retained evidence | Status under the reference architecture |
|---|---|---|
| O01 repository, pins, licenses | Source locks, legal boundary, flavor manifest, build scripts | Retained; contributes to G0/A-001. |
| O02 Android shell | Compose UI, foreground service, storage roots, supervisor state tests | Retained; the simulated runtime is replaced by report-aligned component states. |
| O03 CMaNGOS/playerbots builds | Reproducible x86_64 and ARM64 builds, 16 KB ELF alignment, device smoke | Retained as downstream-port evidence; G2 must still prove x86 native MariaDB/realm with bots off. |
| O04 `libpocketrealm.so` lifecycle | Versioned C ABI, fatal-path containment, re-entry tests, documented patches | Retained as PKG-02/library-lane evidence and reusable control code; it is not yet the chosen production topology. |
| SQLite translation lane | Useful translation diagnostics and schema-gap evidence | Superseded for production. Do not continue SQLite parity unless a future explicit ADR overturns report section 12. |

No completed source is deleted merely because the critical path changed. Production integration must not depend on the in-process/SQLite combination until a superseding evidence-backed decision exists.

The project is currently at **G0 architecture proof**, not at a completed native-runtime milestone. The next feature is O05: production packaging, process-isolation, and capability experiments.

## 4. Track A - offline delivery gates

### Gate G0: architecture and production packaging - O05

Adopt report ADR-001 through ADR-012 and capture exact AVD/device capabilities. Run PKG-01, PKG-02, and PKG-06 first on the legacy research, current-target x86_64, and 16 KB lanes: prove APK-owned native execution, library-backed isolation with a deliberate crash, and page-size compatibility. Record whether `realmd`/`mangosd` use APK-native launchers or isolated library entry points; do not assume the O04 in-process facade is the answer.

Also define the signed-code/mutable-data split required by PKG-03 through PKG-05, even though Wine and update implementation lands later.

**Exit:** a current-target, production-compliant native/Wine packaging route is technically viable, component crashes are contained, and the ADR/compatibility record is checked in. If this fails, stop feature work and resolve packaging.

### Gate G1: direct x86 client proof - O06-O07

O06 completed on 2 August 2026 after Phase-1 recorded **Outcome B**. S-1
(effective loader/process tree), S-2 (verified/repaired PE cache plus successful
`wineboot --init` and prefix creation), and S-3 (in-app X11/GDI self-test
window) pass on both API-35 x86_64 4 KB and 16 KB AVD lanes. The spike retains
pinned PRoot for its seccomp-safe loader/process proof; production sessions use
the APK-managed static preloader and source-built glibc adapter/closure, plus a
paired Wine 11.14 dispatcher patch at `0x7ffe4000` for 16 KB safety. The full implementation adds `ClientRuntime`, a
non-exported `:client` service, an app-owned X surface/input host, versioned
app-private prefix/cache state, audio-off, clean close, process-group forced
stop, and bounded diagnostics. The lifecycle passes unchanged on both lanes.

O07 completed on 2 August 2026. The read-only SAF/host validators identify PE32
i386 build 5875 and reject wrong-build, launcher-only, and corrupt-MPQ inputs.
The source tree remains unchanged; its hash-verified managed debug copy is the
only runtime input. Direct `WoW.exe` reaches the visible login screen through
the pinned WineD3D/Gladio path at 800x600 (within the 1280x720 ceiling), 30 FPS,
audio off, and a loopback realmlist. Strict renderer-framebuffer evidence passes
on first launch and after a clean stop/relaunch.

**Exit: PASS.** The unmodified user-supplied client reaches a repeatable login
screen on the fixed API-35 x86_64 4 KB AVD. G2 may begin; ARM translation remains
deferred to G5 as planned.

### Gate G2: native x86 realm and MariaDB baseline - O08-O09

Cross-build MariaDB for x86_64 Android and run it as an app-private service with a Unix socket, strong durability defaults, an exclusive datadir owner, clean-shutdown marker, dirty recovery, and least-privilege core credentials. Import pinned realm/characters/logs/world/playerbot SQL through an ordered migration ledger with verified pre-migration snapshots.

Run `realmd` and then `mangosd` natively with playerbots disabled. Add structured logging and a versioned app-private control channel for readiness, account creation, save, shutdown, world-tick metrics, and stable error codes. Corroborate ready events with loopback socket probes on 3724 and 8085.

O08 and O09 completed on 3 August 2026. MariaDB initializes, queries,
clean-stops, and recovers after a kill. The no-bot native realm reaches
structured world-ready from verified app-private build-5875 DBC/maps, saves,
retires its isolated process after clean shutdown, and recovers correctly from
controlled world/database deaths. Twenty realm cycles, bounded control fuzzing,
and concurrent host proof of loopback-only 3724/8085 listeners pass.

**Exit: PASS.** G3 supervisor/integration work may begin; playerbots remain
disabled until the later measured bot gate.

### Gate G3: integrated x86 application - O10-O12

Replace the simulated supervisor with the report state machine and a durable atomic journal. Startup is dependency-gated: preflight -> database ready/schema compatible -> realmd ready -> world ready/data loaded -> client window ready. Component ownership uses session/instance tokens rather than stale PIDs. A client-only crash permits bounded relaunch; database/world failures are realm-fatal and preserve evidence.

O10 completed on 3 August 2026. The foreground `:supervisor` process now owns
the dependency state machine, schema-2 fsync/atomic-rename journal, bounded
operations, 256-bit per-component generation tokens, and live Binder owner
leases. Structured health plus exact ownership gates every promotion and
signal. The API-35 x86_64 4 KB lane passes clean lifecycle, supervisor-death
recovery, client-failure isolation, owned world-failure teardown, restart, and
final clean shutdown. The qualified O07 client/display attachment remains an
O12 integration step; O10's backend exposes that absence as `CLIENT_FAILED`
without taking down the ready local realm.

O11 completed on 3 August 2026. The dedicated `:import` worker now performs a
bounded read-only SAF scan, report-formula storage preflight, schema-2 resumable
managed copy, corruption repair, and immutable generation publication. Ten
copy/publication deaths pass on both 4 KiB and 16 KiB API-35 lanes. The real
149-file build-5875 client produced and atomically activated 158 DBCs, 2,429
maps, 43 VMAP trees, 1,249 VMAP tiles, 22 MMAP maps, and 1,815 MMAP tiles. The
normal-play reader verifies the active manifest and every file hash; incomplete
or damaged data cannot silently enable VMAP/MMAP. Extractor commit, patch, PIE
hashes, dependencies, and 16 KiB alignment are pinned.

O12 completed on 3 August 2026 on the API-35 x86_64 4 KiB strategic lane. The
supervisor provisions accounts through core command/control without logging
secrets, attaches the qualified client/display, and performs the report's exact
shutdown order. One 2,918.166-second instrumented qualification reached login,
character creation, world entry, and 30 minutes of active zero-bot play; then
proved database-consistent restore into a fresh datadir, exact durable state,
20 clean cycles, forced world-death recovery, and redacted diagnostics. Five
focused relaunches additionally proved renderer lifecycle and non-black
WineD3D presentation. O11 separately supplies the importer interruption proof.

**Exit: PASS on API-35 x86_64 4 KiB.** G4 may begin. This result does not claim
an O12 integrated pass on the 16 KiB AVD; that repeat remains an explicit
O20/G6 release-qualification item.

### Gate G4: bots and mobile input UX - O13-O14

Enable playerbots only after G3. Begin at 25 bots; keep auction-house automation disabled until the base bot tier is stable. Instrument tick time, queues, memory, thermal state, login/generation storms, and an admission controller. Add 50/100 only as qualified profiles, never universal defaults. Project bot classes and human-first scarcity rules are allowed only when they preserve the report's measured-tier and persistence constraints.

Implement touch, gamepad, keyboard/mouse, IME, focus, pointer capture, hot-plug, calibration, and stuck-key release through one logical input contract and Wine bridge. A minimal project-owned addon may provide action layers and bot UI, but it is not the input driver.

The Retroid Pocket 6 live-device completion sequence is specified in
[`docs/O14_RP6_LIVE_DEVICE_COMPLETION_PLAN.md`](docs/O14_RP6_LIVE_DEVICE_COMPLETION_PLAN.md).
It permits the bounded ARM-native and translated-client enablement needed to
run final O14 acceptance without pre-claiming G5. Active gameplay uses a
dedicated immersive 1920x1080 landscape surface, with 1280x720 Balanced and a
separately measured 1920x1080 Quality profile.

**Exit:** the 25-bot two-hour soak passes and UX-T01 through UX-T08 are completable without physical peripherals; supported controllers reconnect without stuck inputs.

### Gate G5: ARM64 parity and device profiles - O15-O17

Rebuild the same MariaDB/CMaNGOS/control contracts for `arm64-v8a`; world data, migrations, accounts, backups, and supervisor semantics remain ABI-independent. The MariaDB provider may use the pinned official Termux aarch64 package converted into the Bionic APK closure; this is the current RP6 bring-up route and is qualified separately with `-PpocketLane=database` before integrated server acceptance. Prove the translated client with a non-proprietary self-test, then implement ARM-B (Box64 + 64-bit Wine WoW64 running 32-bit `WoW.exe`) first; add ARM-A/Box86 only if a qualified device path justifies it.

Qualify renderer/driver/input/audio/display tuples by self-test and measured device profile. Start at safe mode, then test DXVK/Turnip on Adreno and a qualified system-Vulkan or client-OpenGL path on Mali. Keep prefixes and translator/shader caches isolated, bounded, and invalidated by compatibility IDs. The laboratory matrix now packages independently selectable Box64/FEXCore translators and DXVK/Client-OpenGL renderers. FEXCore uses native Bionic ARM64EC Proton/Wine plus its WoW64 DLL backend; Client OpenGL uses the source-matched Bionic Gladio GLX-to-GLES bridge. This built matrix remains outside the release critical path until named-device lifecycle and sustained-performance evidence exist.

**Exit:** the same managed client and MariaDB backup move between x86 development and ARM64 without destructive data migration; zero/25-bot sessions, lifecycle/recovery, and the required Adreno/Mali/64-bit-only matrix pass.

### Gate G6: product and release qualification - O18-O22

Finish the resumable first-run wizard, local account flow, curated addons, main/recovery/settings screens, backups, safe mode, and bounded Advanced profiles. Implement signed APK/data updates with staged migrations and rollback, redacted diagnostics, SBOM/notices, native hardening, import/control fuzzing, and modern target/16 KB compliance.

Run the report's FUN, FLT, SOAK, compatibility, storage, audio, input, and security matrices. On Retroid Pocket 6 retain raw frame/world timing, PSS, cache, battery, thermal, audio, input, two-hour and long-session results. Firmware/runtime changes invalidate affected qualification.

**Offline acceptance:** fresh install through play requires no shell, SQL, manual config, console window, or external network; Save & Exit, client-only relaunch, force-stop recovery, backup/restore, update rollback, touch/controller UX, 0/25-bot profiles, and sustained RP6 performance pass. The release APK contains no connected permissions, transport, authority, or hidden online switch.

## 5. Track B - connected realm expansion

Connected work begins only after feature O22 is `done`. The research report is normative for the offline appliance and local component contracts; this section is the project-specific extension for connected play.

### Milestone B1: mode separation, transport, and membership - C01-C03

Create separate storage roots, databases, realm IDs, keys, accounts, characters, backups, services, and UI states. Never mount an offline database as connected. A user may create a new connected genesis from a chosen offline snapshot once; all future progress is separate.

Add an embedded authenticated QUIC transport. Prefer direct LAN/IPv6/hole-punched routes and use relays when necessary. Show Direct, Relayed, and Relay-only Privacy states. Bound streams, queues, reconnect/backoff, and snapshot bandwidth. Bootstrap and relay services are replaceable connectivity aids only; they do not choose realm history or own the economy.

Support private invitations first. Stricter community roles may bind a short-lived membership certificate to an Android hardware-backed key, app identity, flavor/policy hash, mesh identity, role, expiry, and revocation epoch.

**Exit:** two supported Android devices discover/invite, connect directly or by relay, authenticate, and tunnel a local test session with clear route status and bounded failures.

### Milestone B2: Realm Kernel and canonical economy - C04-C06

Implement the Realm Kernel as a small Rust service/API, not unrestricted remote SQL. It owns global IDs, names, character-session leases, item/gold ownership transitions, auction escrow/settlement, mail/COD, trade, loot entitlement, progression claims, lockouts, authority leases, epochs, and checkpoint commit order.

Every durable command carries an idempotency key, actor/session identity, current authority epoch, expected entity versions, flavor/policy hashes, bounded payload, and authentication. Maintain one owner/location per item, checked balances, one terminal outcome per auction/mail/trade/loot claim, one active character session, one current authority epoch, and no accepted committed-event loss.

Use signed/hash-chained journals, state roots, certified checkpoints, and replicas appropriate to the declared trust profile. Do not describe crash-fault consensus as malicious-host resistance.

**Exit:** fault injection cannot duplicate or lose committed items/gold/claims; stale epochs and versions are rejected; snapshots restore the same state root.

### Milestone B3: dormancy, anti-cheat, and dynamic population - C07-C09

Define simulation, service, market, calendar, played-time, authority, and dormancy clocks. When the last peer leaves, drain durable work, save, commit, replicate as policy allows, sign a dormant record, release authority, and stop simulation. Recovery chooses the highest valid certified history, applies bounded catch-up once, issues new epochs, and stops in `FORK_DETECTED` for competing certified roots.

Keep movement, teleport, collision, transport, spell, inventory, economy, progression, and protocol validation server-authoritative. Treat client memory offsets, D3D hooks, Wine/Box presence, client clocks, and exact Warden timing as unreliable translated-client signals. Start observe-only before corrections or sanctions.

Humans replace elastic bots gradually while resident floors remain. Cap alts, use hysteresis, retire only safe off-screen bots, preserve party/raid bots, and reduce bot competition for scarce objectives.

**Exit:** all-offline/return and partition tests preserve one history; translated clients play without broad anti-cheat disablement; zones remain lively as human count changes.

### Milestone B4: Session Anchor and seamless migration - C10-C13

The unmodified client stays connected to a local Legacy Session Anchor that owns the client-side TCP connection, packet framing, header-cipher state, bounded sequencing, and backend route, but no gameplay/economy decisions.

Refactor backend sessions to an abstract transport and typed versioned migration capsules. Never transfer pointers, locks, threads, sockets, database connections, allocator state, or process images. Initial authority units are whole continents and individual dungeon/raid instances.

Planned handoff preconnects a target, copies a baseline, streams ordered deltas, establishes watermarks, quiesces at a tick boundary, resolves durable commands, compares semantic state roots, commits a higher epoch, atomically switches the Anchor route, activates the target, reconciles visibility, and fences the source. Crash failover promotes a current hot standby and replays unacknowledged input once; the standby does not independently simulate a second world.

**Exit:** planned handoff preserves the client session within a measured stall target; qualified crash failover avoids relog/character selection and loses no committed durable state.

### Milestone B5: connected release qualification - C14

Run multi-device LAN, Internet, CGNAT, relay, loss, partition, stale-peer, clock-shift, keeper-loss, malicious-input, host-crash, dormancy, restore, migration, power, and thermal tests. Require hard mode separation, one canonical economy, epoch fencing, explicit degraded behavior, translated-runtime-compatible anti-cheat, seamless qualified migration, bounded resource use, honest route/privacy labels, and independent backup recovery.

## 6. Verification strategy

Use the report's stable IDs in implementation notes, tests, error mappings, and support bundles.

1. **Feature checks:** unit, component, contract, build, and UI checks for one feature.
2. **Gate integration:** the applicable X/FUN/FLT/SOAK and exit-gate evidence.
3. **Physical qualification:** named device/runtime/profile measurements; emulator performance is regression evidence only.

Persistence, import, control protocols, native loading, authentication, updates, and connected economy/migration require explicit invariants, adversarial or abrupt-stop tests, and focused review. Do not weaken durability, empty the visible world, or hide fallback to make a gate green.

## 7. Upgrade and update policy

Updates are signed, pinned, staged, and rollbackable. Core source, MariaDB/schema ledger, client-derived data, runtime tuple, Wine prefix, addons, visual overlays, and device profiles carry compatibility IDs. The updater never mutates the only good generation in place.

```text
verify candidate and license
-> build/stage immutable code or data
-> create a database-consistent pre-migration snapshot
-> migrate a copy or stopped datadir with a ledger entry
-> run component and world-ready smoke tests
-> activate atomically
-> retain the previous accepted generation
```

Native executable updates arrive only through the signed Android packaging route selected at G0. Database migrations run with realm/world stopped and a verified rollback point. Runtime/driver changes invalidate affected tuple qualification. Connected protocol changes use version negotiation and prohibit mixed writers when durable semantics differ.
