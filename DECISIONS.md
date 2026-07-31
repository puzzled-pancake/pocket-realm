# Pocket Realm architecture decisions

These decisions summarize the prior research. Treat them as defaults that may change only when implementation evidence proves them infeasible. Record any replacement decision, reason, migration effect, and tests in this file and `PROGRESS.md`.

## Product and legal boundary

1. The product is a launcher/runtime manager, native server, controller layer, and configuration system. It does not distribute proprietary WoW executables, MPQs, DBCs, maps, models, textures, or copyrighted third-party addon assets without permission.
2. The initial game flavor is Vanilla/Classic 1.12 using a fully pinned CMaNGOS Classic, Playerbots, Classic-DB, client-build, locale, extraction-data, addon, and runtime manifest.
3. Offline is the first releasable product. Connected code cannot delay or destabilize it and is not included in the offline release artifact.
4. Offline and connected realms are different universes. There is no later merge of offline progression into the shared economy.

## Device and native architecture

5. The first required device profile is Retroid Pocket 6 12 GB. The 8 GB model, Retroid Pocket 5, AYN Thor, and later devices receive separate measured profiles.
6. Native binaries target portable Android `arm64-v8a` and current Android page-size requirements. Compiler flags do not assume one Snapdragon core type; Android scheduling and measured runtime policy handle heterogeneous cores.
7. Kotlin/Compose owns Android UI and lifecycle. C++20 owns CMaNGOS/Playerbots and the native realm lifecycle. Rust is introduced only for connected trust/network state machines. Boundaries use a versioned C ABI.
8. The native realm is refactored into a startable/stoppable component with explicit health, save, checkpoint, drain, and stop calls. Process exits, console input, and signal-only shutdown are removed from the embedded path.
9. Offline listeners bind only to loopback. The client runner is restricted to the local realm and approved update/import operations.

## Persistence and recovery

10. Correctness assumes Android can kill the process at any instruction. A durable dirty-generation marker is set before mutable simulation and cleared only after a verified checkpoint.
11. Mutable realm data defaults to internal UFS. Removable storage is import/export or optional immutable-content storage, not the live character database.
12. SQLite is the intended embedded database only after schema translation, differential tests against the reference database, transaction tests, WAL/version review, and physical power-loss qualification.
13. High-value operations—items, gold, loot, mail, auction, trade, quest rewards, and character saves—are transactional and idempotent where retries are possible.
14. A backup is healthy only after hash/schema/invariant checks and a disposable restore/start test. Keep at least two verified generations.
15. Save & Exit is a user-friendly fast path, not the only safety mechanism. Dirty-start recovery must handle force-stop, crash, low-memory kill, and power loss.

## Client translation and rendering

16. The server is never translated. The user-supplied 32-bit Windows client runs through a restricted provider tuple.
17. Runtime support belongs to the complete tuple: provider, Wine build/architecture, prefix generation, renderer, driver, audio/input backend, client build, addons, visual overlay, and device firmware.
18. Box64 with pinned Wine WoW64 is the first production candidate because it can suit a 64-bit-only Android environment, but it remains unsupported until long physical-device tests pass.
19. FEX is available only in Advanced/Laboratory mode until a non-root Android rootfs, Wine, graphics, lifecycle, page-size, and long-session qualification succeeds.
20. Box64 and FEX never share a writable Wine prefix, rootfs, shader cache, or dynamic-recompiler cache.
21. DXVK over a qualified Vulkan driver is the preferred D3D9 path; WineD3D is the compatibility fallback. Fallback is reported, never silent.
22. On a 120 Hz display, 30/40/60 FPS are natural cadence targets. Forty FPS is the initial balanced profile; 45 FPS is offered only if a verified 90 Hz/VRR mode makes it sensible.
23. Visual modernization begins with rendering, resolution, anisotropy, pacing, UI, and carefully tested 1.12 CVars. User-imported HD packs are optional, untrusted, reversible overlays.

## Controller, addons, and UX

24. Android gamepad input is normalized natively and passed to the guest through a bundled Wine input bridge; it does not require root, `/dev/uinput`, or Accessibility services. A project addon renders action layers, cursor/chat state, bot roster, and radial commands; the addon is not the input driver.
25. Setup is resumable: device check, storage, client import/validation, extraction, database install, account creation, addons, controller, graphics, bots, and first verified backup.
26. The ordinary home screen offers Start/Continue, Save & Exit, recovery, profile/status, controller, storage, backup age, and thermal state. Expert controls are bounded and staged under Advanced.
27. Addons, configuration, runtime prefixes, and visual packs are generation-managed. A bad change can be rolled back without touching realm data.
28. Auto-login uses a random local credential protected by Android facilities; it is not exposed as a reusable plaintext password.

## Bots, performance, and world quality

29. Bots are classified as protected, resident, elastic, and latent. Total configured bots are not presented as equivalent to active or visible bots.
30. Party, visible, combat, instance, and resident bots are protected before invisible elastic/latent work. Bots leave naturally off-screen rather than popping out.
31. Human players receive priority over scarce quest mobs, nodes, limited vendors, escort events, and rare encounters.
32. The offline market helper is bounded and transparent, cannot create unrestricted rare/raid items, and stops invisible economic activity when the app is not running.
33. Performance work is measured by sustained frame time, server update delay, battery power, thermal behavior, memory, audio, input latency, and world-quality metrics—not peak benchmark scores.
34. Degradation order removes diagnostics, deferred work, distant bot cadence, invisible elastic bots, and distant graphics before visible social population or correctness.

## Connected realm

35. Connected launch is PvE-only. World PvP, battlegrounds, arenas, honor progression, and PvP rewards are disabled and tested.
36. P2P transport may move physical hosting, but one Realm Kernel orders durable shared value. SQL is not writable multi-primary.
37. One real-time authority owns each complete continent or dungeon/raid instance. Ordinary combat never waits for quorum consensus.
38. Direct QUIC is preferred with relay fallback and visible route/privacy status. Iroh is the initial embedded candidate, subject to current qualification.
39. Private invitation mode is supported. Strict community roles may require short-lived Android hardware-bound membership and off-device attestation verification, but admission is not proof of honest simulation.
40. Anti-cheat is server-invariant and translated-client aware. Fixed Windows memory offsets, renderer hooks, Wine/Box/FEX presence, or client-clock timing are not primary enforcement signals.
41. Durable operations carry idempotency keys, expected entity versions, authority epochs, bounded payloads, and journal/checkpoint provenance.
42. If all peers are offline, the realm becomes dormant. Simulation, bots, and default market clocks stop; bounded calendar catch-up is applied once on certified recovery.
43. A partition minority cannot finalize shared value. Divergent certified roots trigger fork recovery; they are never merged into one economy.
44. The client-facing Android process owns a Legacy Session Anchor so the client TCP/header-crypt session survives backend handoff. Loss of the local client/anchor remains an honest reconnect case.
45. Whole-continent migration keeps cities and normal zone boundaries seamless. Instance/continent transitions may use the normal Vanilla loading screen.
46. Planned migration uses pre-copy, ordered deltas, input/output barriers, equal state roots, a higher authority epoch, atomic route switch, and source fencing. Crash failover promotes a current hot standby without a second independently simulated AI world.
47. Every queue, cache, snapshot, retry, buffer, and imported archive has an explicit bound and exhaustion behavior.
