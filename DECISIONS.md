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
46. Retroid Pocket 6 gameplay uses a dedicated immersive `sensorLandscape`
    activity whose physical surface fills 1920x1080. The balanced profile
    renders at 1280x720 and scales uniformly to the full surface; a 1920x1080
    quality profile is accepted only by named-device measurement. This extends
    decision #26 without changing its 1280x720/30-FPS deterministic safe
    default, and it does not permit a surrounding portrait Compose card during
    active play.
47. The ARM laboratory lane exposes the translator and renderer as independent
    tuple members. Its FEX choice is native Bionic ARM64EC Proton/Wine with the
    pinned FEXCore WoW64 DLLs; it is not the unsupported ordinary Linux FEX
    executable on Android. Its OpenGL choice is the client's `-opengl` mode
    through a source-matched Bionic Gladio GLX-to-GLES bridge, while DXVK uses
    the separately pinned ARM64EC DXVK/Turnip closure. All four Box64/FEXCore
    and DXVK/Client-OpenGL combinations retain separate prefix/cache identities
    and remain unqualified until measured on the named physical device.
48. The ARM client route is fixed to Box64 plus an explicitly identified,
    pinned DXVK/Turnip package. FEXCore and client OpenGL are retired from the
    APK, control protocol, settings, prefix/cache identities, and build closure;
    an unavailable DXVK package fails closed and cannot fall back to WineD3D.
    Persisted FEX/OpenGL choices resolve deterministically to Box64 plus the
    current pinned DXVK default. This supersedes decisions #27 and #47 for ARM
    production; the x86 WineD3D/Gladio lane remains historical validation only.
49. The experimental ARM renderers (Legacy OpenGL/Gladio, Mesa VirGL) are
    selectable for on-device qualification without a passing EGL capability
    probe. On the Retroid Pocket 6 the in-app probe can block indefinitely
    (a clean-process probe of the identical EGL sequence passes), so the probe
    now runs under a 3 s watchdog and its result is informational only:
    availability and launch identity checks keep failing closed on packaging,
    server-library SHA, generation-manifest, and live graphics-proof invariants,
    but never on the probe itself. The Gladio ARM client is additionally
    repinned at `gladio-eaa2a8d-arm64-glibc-gles-v4` (530432 bytes,
    sha256 f34d4f1aad7e9fba53c66db6fc838c95bd6c49794c2bdf4cd436ac185a680cff)
    applying the phase-2 WoW 1.12.1 research corrections (spec-exact integer
    color/normal normalization, client-active-texture selection for
    glMultiTexCoordPointerEXT and indexed client-state enables, clamped
    info-log replies, populated glAreTexturesResident) via
    `tools/patches/gladio-phase2-gl_calls.patch` inside
    `tools/build_gladio_client.py`; the paired server and the x86_64 lane stay
    byte-pinned and unchanged.

## G0 overlay (2026-08-01, feature O05)

The G0 packaging experiments (PKG-01/02/06) have run on the legacy API 28,
current-target API 35 (4 KB), and API 35 (16 KB) lanes; evidence is in
`tests/avd/` and `docs/adr/ADR-013-g0-production-topology.md`. This **confirms**
decisions #7 and #8 from evidence; it does not renumber or contradict them:

- **Production Lane A is selected**: long-lived native realm components ship as
  APK-packaged libraries loaded by supervised entry shims in dedicated
  `android:process` fault domains (`:database`, `:realm`, `:world`, `:client`).
  The Kotlin `RuntimeSupervisor` owns state (decision #6).
- **target-28 unpack/exec is confirmed NOT the production path** (research lane
  only). A standalone-exec experiment variant is retained only as G0 evidence
  (decision #7).
- The O04 in-process `libpocketrealm.so` facade remains reusable library-lane
  /control evidence; it is not the production world-server topology (decision #8).
- **Signed-code/mutable-data boundary**: executable native code ships only via
  the signed APK in `nativeLibraryDir`; mutable datadir/prefix/cache/journal/
  database stays app-private and is never executed (decision #12).

No existing decision is superseded. If a later gate's evidence contradicts the
report, a separate superseding ADR will be raised then.

## G1 Phase-1 overlay (2026-08-02, feature O06)

The S-1/S-2/S-3 feasibility spike passes on both API-35 x86_64 page-size lanes
and records **Outcome B**. This refines decisions #22, #23, and #25 without
changing the report's Gate-G1 exit criteria:

- **The direct-x86 backend remains replaceable behind `ClientRuntime`.** Its
  qualified bootstrap uses pinned PRoot because Android app-domain seccomp kills
  the glibc loader's legacy `access(2)` probe; stock Winlator remains neither the
  application architecture nor the user experience.
- **Wine's 16 KB dispatcher modules are a single compatibility unit.** The Unix
  `ntdll.so` and x86_64 PE `ntdll.dll`/`win32u.dll` are source-rebuilt and staged
  together with the dispatcher on a private page at `0x7ffe4000`. Mixed provider
  and patched members are rejected.
- **The full O06 implementation adopts split ownership.** A non-exported
  `:client` service owns Wine, the authorized PE, prefixes, session tokens, and
  the exact process group. The UI process owns the X server, rendered
  `XServerView`, and input translation. Its bounded AIDL protocol accepts no
  arbitrary command, path, or environment.
- **`clientRuntime` is the explicit executable-native G1 lane.** It extracts
  APK native libraries as required by the qualified Wine route; O05's
  `debug`/`release` packaging controls remain unchanged.
- **O06 is complete.** Paired 4 KB/16 KB evidence proves prefix relaunch,
  mapped surface, focus, keyboard/mouse, audio-off, clean close, process-group
  forced stop, and diagnostics. O07 is the first gate allowed to consume the
  user's proprietary build-5875 client.

## G1 client overlay (2026-08-02, feature O07)

O07 completes Gate G1 without changing decisions #22, #23, or #25:

- **The user selection is an immutable source, not the runtime tree.** A
  read-only SAF fast scan classifies PE/build/layout. The report-authorized
  debug import copies into a hash-verified app-private generation and never
  writes endpoint or graphics settings back to the selected source.
- **Only direct build-5875 `WoW.exe` is authorized.** The service accepts the
  fixed `wow-1.12.1-5875` identity and obtains its canonical executable and
  working directory from `ManagedClientStore`; callers cannot supply a path,
  launcher, injected DLL, or environment.
- **The renderer capability record is provider-specific.** The pinned Gladio
  GLES bridge advertises only its qualified OpenGL 3.0 / GLSL 1.30 subset. It
  retains internal-format queries required for WineD3D render targets but
  withholds unsupported modern instancing/base-vertex paths. This is an
  explicit compatibility profile, not a claim of desktop OpenGL 3.3 parity.
- **800x600 is the qualified O07 effective mode.** The canonical requirement is
  1280x720 or lower. The managed ceiling remains 1280x720, while build 5875
  selects 800x600 on the fixed AVD. Both first launch and clean relaunch produce
  a visible, non-black login framebuffer with audio off and no server.
- **No proprietary client executables or data archives enter version control.**
  Only client identity/layout metadata, hashes, classification records, and
  rendered acceptance screenshots are retained as evidence.

## G3 supervisor overlay (2026-08-03, feature O10)

O10 implements decisions #6, #7, #9, #10, and #11 without superseding them:

- **The foreground `:supervisor` process is the sole lifecycle authority.**
  Database, realm, world, and client processes own their resources but cannot
  promote or coordinate themselves.
- **A component generation requires two forms of identity:** a durable UUID
  session plus independent 256-bit token, and a live Binder owner lease. The
  token prevents stale PID reuse; lease death makes supervisor loss immediately
  observable to each child and triggers safe dirty teardown.
- **Signals are ownership-gated.** Recovery observes structured component state
  first and never stops or kills an unknown generation. PID/listener existence
  alone is neither health nor authority.
- **Durability is explicit.** The schema-2 journal uses fsync plus atomic rename;
  clean is written only after client -> world save -> world -> realm -> database
  shutdown succeeds. Partial operations hold a bounded wake lock.
- **The O07 client remains qualified but unattached at O10.** Client failure is
  isolated and relaunchable in the supervisor contract; O12 connects the real
  Wine/display session to that contract.

The detailed rationale and device qualification are recorded in
`docs/adr/ADR-017-o10-durable-runtime-supervisor.md`.

## G3 managed-import overlay (2026-08-03, feature O11)

O11 implements decisions #12, #24, #26, #27, and #45 without superseding the
canonical report:

- **The SAF tree is an immutable input.** Only a persisted read grant is kept;
  Wine and extractors consume a verified app-private generation and app-owned
  settings are never written to the selected client.
- **Copy and data work have durable, bounded journals.** Per-file SHA-256/fsync
  state and per-stage DBC/maps/vmaps/MMAP checkpoints make process death a
  resume case, not an overlay or an unverified continuation.
- **Publication is generation based.** A manifest and directory rename become
  visible through a digest-bearing active pointer only after verification.
  Re-import creates a new UUID generation; current + previous retention bounds
  storage and preserves rollback.
- **Normal play is fail-closed on prepared data.** The O11 active manifest must
  identify build 5875/classic/NORMAL and every required file must match its
  size and hash before VMAP/MMAP can be enabled.
- **Extractor provenance is reproducible.** The clean pinned CMaNGOS commit,
  external MPQ parser patch, four artifact hashes, dependency closure, and
  16 KiB LOAD alignment are captured in the O11 lockfile. User-derived output
  remains app-private and outside version control.

The detailed rationale and real-client qualification are recorded in
`docs/adr/ADR-018-o11-managed-client-import.md`.

## O23 overlay (2026-08-11, features O23a toggleable vanilla-tweaks + O23b user-chosen account auto-login)

Two orthogonal features shipped together on the O14 continuation branch.

**O23a — toggleable vanilla-tweaks + (plumbed, default-off) audio:**
- **The pristine managed `WoW.exe` is never mutated.** Quality-of-life patches
  from the vendored MIT `brndd/vanilla-tweaks` v1.6.0 produce a root-level
  `WoW.exe.patched` sibling; the managed-import hash pin and manifest remain
  authoritative for the pristine binary.
- **Patching is locale-gated by byte-signature pre-verification.** The enUS-5875
  offset/byte table in `ClientTweaksConfig.expectedOriginalBytes()` is populated
  empirically from a real binary; while empty the patch step declines and falls
  back to pristine (logs `VAL-LOCALE-TWEAK-SKIP`). A mismatch logs
  `VAL-LOCALE-TWEAK-MISMATCH`. The patched artifact is cached by a `.signature`
  sidecar over `toFlags() + version + sha256(pristine)`.
- **Audio — re-implemented per plan A.7 (M3.1+M3.2); default OFF.** A first M3
  attempt was stripped (it diverged from report §16.5 and produced no sound). The
  re-implementation fixes the three root causes: stock `alsa.conf` + an
  `android_aserver` `.asoundrc` overlay with explicit `ALSA_CONFIG_PATH` (no
  hand-written config replacement); a matched ca3d735 `alsaserver` +
  upstream-protocol plugin (rebuilt via `tools/build_alsa_plugin.py`); and
  mandatory SHM (memfd via the vendored `SysVSharedMemory` + SCM_RIGHTS). The
  server runs in the display process (`ClientDisplayHost`) reusing
  `libwinlator.so`'s `XConnectorEpoll`. `audioMode` defaults to `OFF`; it flips
  to ON only after on-device qualification (M3.4). Buffer presets come via
  `ALSAClient.Options.latencyMillis` (not arbitrary user env). `AudioCaps`,
  audio-focus, and AAudio (3.3) remain deferred superseding decisions.
- **`tweaks: ClientTweaksConfig` is persisted as a single JSON-string DataStore
  key.** No JSON-string precedent existed in `Settings` before O23; introduced
  here for this composite value.

**O23b — user-chosen realm account + tunable auto-login:**
- **A user account wins over the random bot identity.** `UserAccountStore`
  mirrors `SinglePlayerCredentialStore` (schema-versioned JSON, atomic
  temp+`fd.sync`+`rename`+dir-fsync write, 0600 file / 0700 dir, redacted
  `toString`). It stores only on `ACCOUNT_CREATED` + `accountId > 0`; an
  `ACCOUNT_EXISTS` result never re-arms auto-login.
- **The supervisor checks existence only.** The password never enters logs, the
  journal, status JSON, evidence, or crosses the Binder boundary. The display
  process resolves credentials null-safely (user account → single-player → skip,
  never throw). The pure gate is `AutoLoginPolicy.resolveAutoLogin`.
- **Full timing set behind an Advanced toggle.** `AutoLoginTimings` defaults
  equal the historical companion constants so existing host-JVM tests stay valid;
  the four `InputContract` instance fields (`imeKeyDwellMs`, `imeKeyGapMs`,
  `fieldSettleMs`, `pointerDwellMs`) and the six `SinglePlayerAutoLogin`
  intervals are each overridable, with the companion constants retained as pins.
- **A third process reads the Settings DataStore.** `IntegratedClientDisplay`
  (display process) reads `Settings.flow` for timings; UI writes and `:supervisor`
  already reads. Usage is read-mostly at Binder-stub entry; recorded here as the
  known multi-process DataStore assumption.

The coordinated plan and verification notes live in
`fixed/PLAN-vanilla-tweaks-autologin.md`.
