# Pocket Realm implementation plan

This document is the complete roadmap. It intentionally describes end states and important interfaces rather than thousands of micro-tasks. `FEATURES.json` is the execution order.

## 1. Product definition

Pocket Realm turns a supported, user-supplied Vanilla WoW 1.12 client into a handheld Android appliance. The user sees one polished application: import and configure once, press Start Adventure, play with a living bot-populated world, and use Save & Exit or automatic crash recovery.

The same repository later adds a separate connected PvE realm. Connected play pools Android devices without a permanently designated game server while preserving one canonical economy and seamless client continuity. Offline and connected data never mix.

### Non-goals

- Redistributing the proprietary game client or game assets.
- Supporting arbitrary Windows applications or arbitrary DLL injection.
- Translating the native server.
- Promising every Android device or every client build.
- Fine-grained zone sharding before whole continents and instances work.
- Per-tick consensus, blockchain gameplay, or merging divergent economies.
- Competitive PvP on participant-controlled hosts at launch.

## 2. Repository and language layout

The initializer should create a monorepo close to:

```text
android/app/                 Kotlin/Compose UI, wizard, service, input
native/cmangos/              pinned upstream or submodule
native/playerbots/           pinned upstream or submodule
native/classic-db/           pinned database source
native/pocket-runtime/       lifecycle facade, local gateway, persistence
runtime/providers/           provider interface and tuple manifests
runtime/box64/               Box64/Wine integration
runtime/fex/                 FEX laboratory integration
addons/pocket-core/          required Vanilla addon
addons/pocket-bots/          controller/bot UI
connected/kernel/            Rust Realm Kernel (after offline gate)
connected/mesh/              Rust P2P transport and replication
connected/session-anchor/    local legacy protocol anchor
schemas/                     stable manifests and C ABI schemas
tools/                       Python build/import/validation tools
tests/                       host, Android, recovery, runtime, and connected tests
docs/                        user and developer documentation
```

Pin every upstream source and generated input. Record license and redistribution status before packaging it. Keep proprietary client files outside source control and test artifacts.

## 3. Track A — offline product

### Milestone A1: reproducible source and Android shell — O01–O02

Create the repository, build scripts, source lock, license inventory, flavor manifest, and an Android application shell. The app should already have navigation, persistent settings, a foreground-service placeholder, structured logging, and a clear separation between immutable content, mutable realm data, runtime generations, and exports.

Use Kotlin/Compose for UI. Use a foreground service only while the realm or client is active. The notification must expose current state and Save & Exit. Do not implement durability through activity callbacks.

**Exit:** clean checkout can build the Android shell and host tools from documented commands; the app starts on RP6; source and artifact provenance are visible in diagnostics.

### Milestone A2: native ARM64 realm runtime — O03–O05

Cross-compile CMaNGOS Classic and Playerbots for portable Android ARM64. Do not tune the binary for only Cortex-X3 or A715: the process migrates across heterogeneous cores. Use release optimization, LTO only after correctness, and current Android page-size compatibility.

Refactor process-global startup into an embeddable lifecycle:

```c
realm_create(config, callbacks, *handle)
realm_start(handle)
realm_health(handle, *status)
realm_command(handle, command)
realm_save(handle, save_mode)
realm_checkpoint(handle)
realm_request_stop(handle, reason)
realm_join(handle, timeout)
realm_destroy(handle)
```

No `exit()`, console-only command, signal-only shutdown, global mutable singleton assumption, or C++ exception may cross the app boundary. `realmd` and `mangosd` functionality may initially remain separate internal components, but the Android supervisor controls them through typed calls.

Bind authentication and world traffic to loopback. Provide explicit health conditions: database open, schema compatible, auth ready, world loop running, local endpoints listening, bot subsystem initialized.

**Exit:** on RP6, a native test realm can start, accept a local test login, save, stop, and repeat without process restart or leaked resources.

### Milestone A3: persistence, safe stop, and abrupt recovery — O06–O09

Begin with the upstream/reference database path to establish known-correct behavior. Port schemas and migrations deliberately rather than regex-rewriting SQL. Qualify SQLite only after differential tests cover account, character, inventory, item ownership, loot, quest rewards, mail, auction, guild, bot persistence, transactions, concurrency, and update migrations.

Use one controlled writer and bounded read connections. Review the exact bundled SQLite version and WAL behavior. Default to strong durability until physical power-loss tests justify a lower-power mode.

Before world mutation begins, persist:

```text
active_generation = N
dirty = true
last_clean_checkpoint = N-1
```

Graceful stop uses a world input barrier, saves human and protected bots, drains durable writes, stops bot maintenance, stops world/auth, checkpoints the database, builds and verifies a recovery generation, atomically activates it, then clears `dirty`.

Dirty startup inspects the database and WAL, runs structural checks and game invariants, and either resumes, repairs, or presents a restore choice with exact progress loss. Never copy an active database file as a backup. Use a consistent backup API and verify the restore in a disposable realm.

Test process kill and device power loss at every major start/save/stop transition. Keep at least two verified recovery generations and show their age in the UI.

**Exit:** repeated kill/power-loss campaigns produce no duplicated item/gold claims, no silently corrupted realm, and a deterministic recovery UX.

### Milestone A4: client import and game-data preparation — O10

The user selects a legal client directory or archive through Android storage access. Import is resumable and treats all files as untrusted data. Enforce path traversal, size, decompression-ratio, file-count, case-collision, and unexpected executable/DLL rules.

Identify the exact client build and locale by manifest/hash, not filename. Initially recognize only explicitly supported Classic-DB builds. Store imported client and extracted data as immutable generations.

CMaNGOS extractors may require desktop execution or ARM porting. Provide two supported paths:

1. a desktop companion creates a signed/hash-manifested data pack from the user’s client;
2. an on-device extractor path after ARM correctness and thermal/storage qualification.

Do not block first release on an unreliable on-device extractor if the companion path is polished.

**Exit:** supported clients import reproducibly; unsupported or modified layouts fail with actionable errors; a verified maps/VMAP/MMAP/DBC/content generation starts the native realm.

### Milestone A5: translated client runtime — O11–O13

Define `IClientRuntimeProvider` instead of hard-coding one emulator:

```text
probe(device, firmware)
install_or_verify_components()
create_generation(tuple)
launch(client, tuple, local_endpoints)
request_graceful_exit()
collect_diagnostics()
kill_after_timeout()
```

A runtime tuple includes provider, translator build, Wine architecture/build, prefix generation, renderer, Vulkan/OpenGL driver, audio, input, display backend, client build/locale, addon profile, visual overlay, frame profile, and settings hash.

#### Box64 candidate

Use a pinned Box64 and Wine WoW64 path as the first candidate for a 64-bit-only Android userspace. Prove 32-bit client launch, process creation, exceptions/signals, TLS, memory mapping, filesystem/registry, fonts, Winsock loopback, DirectSound, DirectInput, D3D9, clean exit, and long zoning sessions. Community flag lists are hypotheses, not defaults. Every non-default translator setting needs an A/B result and rollback.

#### FEX laboratory provider

FEX must remain Advanced/Laboratory until it works non-root on the actual Android environment with an integrity-checked guest rootfs, Wine, graphics thunking, page-size behavior, SELinux/namespaces, signals/futex/TLS, lifecycle, audio/input, and long-session stability. It uses an isolated rootfs, prefix, and caches. The UI may expose it, but Automatic never selects it before acceptance.

#### Renderers and pacing

Qualify DXVK with the exact Vulkan/driver tuple. Keep WineD3D as a visible compatibility fallback. Test cold/warm shader and dynarec caches, storage growth, invalidation after firmware/runtime update, and safe cache deletion.

Provide 30, 40, and 60 FPS profiles. On 120 Hz, Balanced begins at 40 FPS. Resolution begins at 900p or device-tested equivalent; 1080p/60 is Quality only if sustained thermals allow it.

**Exit:** at least one tuple completes login, character selection, world entry, twenty loading transitions, capital/outdoor scenes, audio/controller changes, Save & Exit, client crash recovery, and multi-hour RP6 sessions without corrupting realm or prefix state.

### Milestone A6: controller, addons, graphics, and polished UX — O14–O17, O19

Normalize Retroid controls through Android game-controller APIs. Maintain per-device calibration, dead zones, hot-plug state, vibration policy, and physical/virtual cursor support. Feed canonical actions into a bundled Wine input bridge (virtual keyboard, relative mouse, and optional virtual gamepad); do not require root, `/dev/uinput`, or Android Accessibility services.

Create a small project-owned addon suite:

- `PocketCore`: device/runtime status, safe UI hooks, profile/version reporting.
- `PocketController`: combat, cursor, chat, and menu modes; action layers; prompts.
- `PocketBots`: bot roster, party roles, radial commands, behavior presets, raid controls.

Use established Vanilla addons only when licensing, memory, controller usability, and conflict tests pass. Offer curated Minimal, Essential, Guided, Modern, and Safe Mode profiles. Keep locale-specific quest data slim rather than loading every language.

The setup wizard is resumable and covers device/storage, import, extraction, database, account, addons, controller, graphics/runtime profile, bots, and initial backup. Auto-create a local account with a random credential stored through Android Keystore-backed app storage. Inject it into the local login screen at launch; do not place the password in logs, exported diagnostics, or a shared client profile.

The main screen has one primary action and concise status. Advanced screens expose bounded options: provider, Wine tuple, DXVK/WineD3D, resolution/FPS, audio, cache budgets, server threads, bot population/activity/density, logging, backups, and diagnostics. Advanced changes create a candidate generation, smoke-test it, and keep the previous accepted generation.

Modernize graphics through the renderer, scaling, anisotropy, frame pacing, handheld UI, and a tested 1.12 CVar registry. Optional Vanilla fixes or HD packs are user-imported overlays with license/provenance checks, golden-scene tests, memory budgets, and Android-side rollback.

**Exit:** a new user can complete setup and begin play without seeing SQL, Wine prefixes, console windows, raw environment variables, or manual account commands; all play-critical flows work on the controller.

### Milestone A7: living bots, optimization, and offline release — O18, O20–O22

Enforce a PvE-focused offline world. Distinguish:

- protected bots: grouped, visible, combat, instance, durable interaction;
- resident bots: social anchors in towns, roads, taverns, banks, auction areas, hubs;
- elastic bots: ambient population that may change naturally;
- latent bots: distant persistent characters updated coarsely or event-driven.

Expose world population, active bots, local density, and party bots separately. Start RP6 calibration around hundreds of total bots rather than promising a fixed capacity. Preserve resident/local density while reducing distant activity. Bots should travel, hearth, mount, enter buildings, or log out off-screen rather than vanish.

Give the human priority over named quest targets, rare nodes, limited vendors, escorts, and world events. Bound the auction/market helper by item classes, gold flow, supply/demand, provenance, and human activity. The offline world freezes when the app stops.

Build one power/thermal controller from measured signals. It should reduce dashboard refresh, deferred compression, verbose logs, latent cadence, invisible elastic bots, distant clutter, optional visual quality, resolution, and FPS in that order. Protect correctness, party/visible/resident/combat bots, and current instance play.

Measure on physical RP6 12 GB:

- median/p95/p99 frame time and input latency;
- server update delay and hitch distribution;
- client/server PSS and cache growth;
- CPU/GPU utilization and frequencies;
- battery power and drain;
- thermal state/headroom and fan mode;
- audio underruns and renderer/device loss;
- visible/active bot quality by scene;
- two-hour and eight-hour stability;
- repeated cold start, recovery, backup, restore, addon/runtime rollback.

A firmware or driver change invalidates affected tuple qualification.

**Offline acceptance:** the complete first-run-to-play journey, normal sessions, Save & Exit, sudden termination, recovery, controller-only play, runtime rollback, and sustained performance all pass on the reference device. The offline APK contains no connected permissions, transport, authority, or hidden online switch.

## 4. Track B — connected realm expansion

Connected work begins only after feature O22 is `done`.

### Milestone B1: mode separation, transport, and membership — C01–C03

Create separate storage roots, databases, realm IDs, keys, accounts, characters, backups, services, and UI states. Never mount an offline database as connected. A user may create a new connected genesis from a chosen offline snapshot once; all future progress is separate.

Add an embedded authenticated QUIC transport. Prefer direct LAN/IPv6/hole-punched routes and use relays when necessary. Show Direct, Relayed, and Relay-only Privacy states. Bound all streams and reconnect/backoff behavior. Keep gameplay, control, replication, and bulk snapshots on separate protocols/connections where congestion isolation matters.
Bootstrap and relay services are replaceable connectivity aids only: they do not store the canonical economy, choose realm history, or become a permanently designated game host.

Support private invitations first. For stricter community roles, bind a short-lived membership certificate to an Android hardware-backed key, app identity, flavor/policy hash, mesh identity, role, expiry, and revocation epoch. Off-device verification may be required; devices that cannot attest remain eligible for private trust profiles, not silently elevated roles.

**Exit:** two supported Android devices can discover/invite, connect directly or through relay, authenticate, and tunnel a local test session with clear route status and bounded failure behavior.

### Milestone B2: Realm Kernel and canonical economy — C04–C06

Implement the Realm Kernel as a small Rust service/API, not as unrestricted remote SQL. It owns global IDs, names, character-session leases, item/gold ownership transitions, auction escrow/settlement, mail/COD, trade, loot entitlement, progression claims, lockouts, authority leases, epochs, and checkpoint commit order.

Every durable command carries a unique idempotency key, actor/session identity, current authority epoch, expected entity versions, flavor/policy hashes, bounded payload, and signature/authentication. The response is stable on retry.

Maintain invariants:

- one item, one owner, one location;
- no double spend or negative/overflow balance;
- one terminal auction/mail/trade/loot outcome;
- one active character session;
- one current authority epoch per unit;
- no committed event loss across accepted failover.

Use signed/hash-chained journal entries, state roots, certified checkpoints, and replicas appropriate to the trust profile. A private realm may trust one owner authority; friends may use crash-fault keepers; public/community operation needs a reviewed Byzantine-aware policy. Do not mislabel Raft-style crash tolerance as malicious-host resistance.

**Exit:** fault-injection tests cannot duplicate or lose committed items/gold/claims; stale epochs and conflicting expected versions are rejected; snapshots restore and reproduce the same state root.

### Milestone B3: dormancy, translated-client anti-cheat, and dynamic population — C07–C09

Replace one host wall clock with explicit simulation, service, market, calendar, played-time, authority, and dormancy clocks. Default policy when the last peer leaves:

1. drain new durable operations;
2. save active state;
3. commit journal/checkpoint;
4. replicate as policy allows;
5. issue a signed dormant record;
6. release authority and stop all simulation.

When the first peer returns, gather reachable checkpoint headers, choose the highest valid certified history, verify hashes/invariants, establish reasonable time evidence, apply each bounded catch-up rule once, issue new epochs, then open login. Competing certified roots stop in `FORK_DETECTED`; never merge them. If all copies are lost, recovery requires an independent encrypted backup.

Build a clean behavior/invariant anti-cheat. Keep movement, teleport, collision, transport, spell range/LOS/cooldown/resource, inventory, economy, progression, and protocol validation server-authoritative. Treat fixed client-memory offsets, D3D hooks, module layout, Wine/Box/FEX presence, client clock, and exact Warden timing as unreliable translated-client signals. Begin observe-only and calibrate corrections/kicks before automated sanctions.

In connected zones, humans replace elastic bots gradually while resident floors remain. Count unique active memberships, cap alts, ramp arrivals, use hysteresis, and retire only safe off-screen bots. Protected party/raid bots are additional. Reduce bot competition for scarce objectives as humans increase.

**Exit:** all-offline/return and partition scenarios preserve one history; translated clients play without broad anti-cheat disablement; populated test zones remain lively as human count changes.

### Milestone B4: Session Anchor and seamless migration — C10–C13

The unmodified client remains connected to a local Legacy Session Anchor. The Anchor owns the client-side TCP connection, packet framing, header-cipher state, bounded input/output sequencing, and backend route. It does not decide gameplay or economy outcomes.

Refactor backend sessions to an abstract transport rather than direct `WorldSocket*` ownership. Define typed, versioned migration capsules; never transfer pointers, locks, threads, sockets, database connections, allocator state, or process images.

Initial authority units are complete Eastern Kingdoms, complete Kalimdor, and each dungeon/raid instance. This keeps ordinary cities and zones seamless. Map/instance changes use the existing Vanilla loading flow.

Planned handoff:

1. qualify/preconnect target;
2. copy baseline while source runs;
3. stream ordered deltas to a hot standby;
4. establish input/output watermarks;
5. quiesce at a tick boundary;
6. resolve in-flight durable commands;
7. compare semantic state roots;
8. commit a higher authority epoch;
9. atomically switch Anchor route;
10. activate target at the next tick;
11. reconcile bounded visibility/movement;
12. fence the source.

Crash failover buffers bounded client input, promotes the current standby through a higher epoch, replays unacknowledged input once, and reconciles. The standby applies authoritative deltas; it does not independently simulate a second AI world.

Migration state includes player and controlled entities, groups, bots, combat, auras/cooldowns, movement/transports, instances, spawns, timers/RNG where needed, visibility/known-object sets, pending output, and durable commit indexes. New mutable fields in migratable types require an explicit migration disposition and test.

**Exit:** planned continent/instance handoff keeps the client socket/session and meets an evidence-based short-stall target; accepted crash failover avoids relog/character selection and loses no committed durable state. Loss of the local client/Anchor is correctly reported as reconnect, not falsely called seamless.

### Milestone B5: connected release qualification — C14

Run multi-device LAN, Internet, CGNAT, relay, packet-loss, partition, stale-peer, clock-shift, keeper-loss, malicious-input, host-crash, all-offline, restore, migration, and battery/thermal tests. Validate private/friends/community trust labels against their actual threat tolerance.

Connected release requires:

- hard offline/connected separation;
- one canonical economy under all accepted faults;
- no stale-epoch writes or duplicate durable outcomes;
- explicit degraded/provisional behavior when quorum is unavailable;
- behavior anti-cheat compatible with qualified translated runtimes;
- seamless planned migration and qualified crash failover;
- dynamic bots that yield to humans without empty zones;
- bounded CPU, memory, storage, bandwidth, and battery use on every node role;
- clear privacy and relay/IP-exposure communication;
- recovery from total dormancy and independent backup loss drills.

## 5. Cross-cutting testing strategy

Use three levels rather than hundreds of ceremonial gates:

1. **Feature checks:** focused unit/property/build/UI checks while implementing one feature.
2. **Milestone integration:** complete user flows and failure cases across the milestone.
3. **Physical release qualification:** RP6 and multi-device tests with raw measurements retained.

High-risk state transitions use property/fuzz/fault tests. User-facing screens use screenshot or device automation plus controller walkthroughs. Runtime providers use immutable benchmark matrices. Do not claim support or capacity from desktop emulation or SoC specifications alone.

## 6. Upgrade and update policy

Updates are signed, pinned, staged, and rollbackable. Source/core, database schema, extracted data, runtime tuple, prefix, addons, visual overlays, and device profile have explicit compatibility IDs. The updater never mutates the only good generation in place.

A component update proceeds:

```text
download/import candidate
→ verify signature/hash/license
→ build candidate generation
→ migrate a copy
→ smoke test
→ activate atomically
→ retain previous accepted generation
```

Runtime and driver updates invalidate affected qualification. Database migrations are forward-tested and have a backup/restore path. Connected protocol changes use version negotiation and prohibit mixed writers when durable semantics differ.
