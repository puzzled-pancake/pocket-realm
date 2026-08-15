# O14 Retroid Pocket 6 live-device completion plan

Status: implementation foundation landed; ARM MariaDB conversion is staged and
ready for RP6 execution; RP6 acceptance remains blocked on live-device proof
and the translated-Wine closure  
Target: Retroid Pocket 6 connected through wireless ADB  
Scope: finish O14 on a real ARM device, including full-screen landscape play,
touch UX-T01 through UX-T08, and named physical input qualification

Implementation checkpoint (2026-08-09): the ABI-isolated build selector,
redacted RP6 capability capture, fail-closed `armTranslatedWine` provider
boundary, generation-gated immersive landscape shell, and 1280x720/1920x1080
display profiles are implemented and host-validated. The pinned x86_64 lane
still builds with `-PpocketAbi=x86_64`. ARM64 OpenSSL/Boost/SQLite, core
realm/runtime, packaging, X-server, O09 realm, and O11 extractor source builds
now pass in isolated roots with 16 KiB ELF alignment and ARM provenance/lockfiles.
The official Termux aarch64 MariaDB package (12.3.2) is now hash-pinned,
converted into an APK-native Bionic executable/private-library closure, and
staged under `native/.build-arm64/mariadb-staging`; `validateDatabaseRuntime`
and the ARM ELF checks pass. `DatabaseEngine` now selects a direct Bionic
runner and ARM provider identity on `arm64-v8a`. A database-only ARM APK is
now reproducibly assembled with `-PpocketAbi=arm64-v8a -PpocketLane=database`;
it contains only the Bionic MariaDB/provider closure needed for the database
service and is recorded at `native/.build-arm64/mariadb-staging/
databaseRuntime-apk-manifest.json` (APK SHA-256
`225ea45ede4338b9fd3867adc1f0a82f62294a730514d49368835f31e2e09fc3`). The
full client APK still stops at the separate Box64+Wine closure gate. O14
remains pending until MariaDB is exercised on the RP6, the client provider
exists, and real UX/peripheral evidence exists.
The checked-in build record is
`tests/devices/retroid-pocket-6/arm-mariadb-qualification-build-20260809.json`;
its device execution field remains `not_evaluated`.
The final wireless-ADB attempt timed out; `adb devices -l` exposed only the
x86 emulator, so no install or MariaDB result is attributed to the RP6.

## 1. Objective

Close O14 with evidence from the real Retroid Pocket 6 rather than treating
emulator-generated input as physical-device acceptance.

The finished gameplay surface must:

- occupy the full physical 1920x1080 display in landscape;
- use a 16:9 virtual desktop with 1280x720 as the default balanced profile;
- provide a separately qualified 1920x1080 quality profile;
- scale 1280x720 to 1920x1080 exactly, without stretch, crop, or a surrounding
  Compose card;
- keep setup, recovery, and settings available in the Android UI while the
  active game uses a dedicated immersive landscape activity;
- route touch, controller, keyboard, mouse, pointer capture, and IME through
  the existing generation-gated `InputContract`.

O14 is complete only after the real build-5875 client passes UX-T01 through
UX-T08 and the physical input checks below. An APK install, a Wine self-test,
a visible login screen, emulator input, or a synthetic Win32 probe is not O14
completion by itself.

## 2. Authority and reading order

Before changing code, read:

1. `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.pdf`, especially sections
   16.1-16.10, 21, 22, 25.2, 25.6, 25.7, and Appendix C.4-C.5.
2. `PLAN.md`, Gates G4 and G5.
3. `DECISIONS.md`, especially decisions 22-29, 34-35, and 46.
4. `FEATURES.json`, features O14-O17 and O21.
5. `PROGRESS.md`, the current O14 handoff and limitations.

The report remains the offline reference authority. This plan only sequences
the work needed to obtain its O14 evidence on the RP6.

## 3. Current verified baseline

Treat these as retained evidence, not work to repeat without cause:

- The x86_64 client, server, importer, supervisor, and persistence contracts
  have already reached their earlier gates on the fixed AVD lane.
- The O14 production input suite passed the Win32 probe for touch overlay,
  paced IME, mouse, pointer capture, gamepad mapping, generation replacement,
  and deterministic release behavior.
- Real build-5875 credentials have reached Wine and authenticated on the x86
  lane.
- A later bounded five-cycle x86_64 renderer run reached mapped 800x600
  non-black client frames on all five generations after the test-only exact
  hardware-change modal acknowledgement; the clean O07 relaunch also passed.
- The ARM64 `pocket_lifecycle_test` now executes on the RP6 for two cycles via
  wireless ADB; its expected world-database schema gap is classified honestly
  in `tests/devices/retroid-pocket-6/arm-o04-control-probe-20260809.json`.
- The emulator can display the first-run realm-language wizard, but the
  English control did not respond to verified live X button events. Coordinate
  guessing did not establish a production fix; the RP6 run must settle this
  with a human touchscreen action and an optional Android-input replay.
- O14 remains pending because real WoW UX-T01 through UX-T08 and named
  physical peripheral qualification do not yet exist.

Do not rewrite historical PASS evidence. New evidence must identify the exact
device, APKs, runtime tuple, renderer, resolution, and input device.

## 4. RP6 capability baseline

The device observed on 9 August 2026 reported:

- model: Retroid Pocket 6;
- Android 13 / API 33;
- ABI lists: `arm64-v8a` and `armeabi-v7a,armeabi`;
- page size: 4096 bytes;
- SoC/GPU: QCS8550 / Adreno 740;
- physical display: 1080x1920 portrait, therefore 1920x1080 landscape;
- RAM: approximately 11.5 GiB;
- free data storage at capture: approximately 38 GiB;
- built-in `Retroid Pocket Controller` with gamepad/joystick sources;
- built-in `Retroid Pocket Virtual Mouse` mouse source.

Do not hardcode the wireless ADB serial. Select the single device from
`adb devices -l` whose model is `Retroid_Pocket_6`, then pass its current serial
explicitly to every command.

Create a checked-in device record under `tests/devices/` containing at least:

- Android build fingerprint and security patch;
- API and ABI lists;
- 32-bit and 64-bit ABI lists separately;
- page size, kernel, RAM, zram/swap, and allocatable storage;
- physical size/density and supported refresh modes;
- GLES/Vulkan vendor, renderer, driver, and extensions;
- battery/charging/thermal state at each qualification run;
- every Android `InputDevice` name, descriptor hash, sources, axes, and button
  capabilities used in acceptance.

The device record is evidence, not a model-name assumption. Runtime and
renderer selection must still come from self-tests.

## 5. Dependency re-sequencing

The current feature graph makes O15 wait for final O14, but final O14 now needs
an ARM client lane. Resolve this without falsely marking O14 done:

1. Keep O14 `active` or `pending` overall.
2. Record the existing O14 input-contract/probe increments as the prerequisite
   implementation sub-gate.
3. Permit the bounded O15 native-ARM and O16 translated-client work required to
   make the RP6 executable.
4. Run final O14 UX and physical input acceptance on that RP6 lane.
5. Only then close O14 and resume the remainder of O17/G5 qualification.

Represent this in `FEATURES.json` without a circular dependency. The simplest
acceptable representation is:

- remove final O14 from O15's hard dependency while documenting that the O14
  implementation sub-gate is already qualified;
- keep O16 dependent on O07 and O15;
- document that O14 final qualification consumes the O16 RP6 provider;
- keep O17 dependent on final O14 and O16.

Do not describe the bounded O15/O16 enablement as full G5 completion.

## 6. Worktree and safety rules

Before editing:

1. Run `git status --short` and inspect every existing diff.
2. Preserve user and earlier-agent changes. Do not reset, checkout, or rewrite
   them to obtain a clean tree.
3. The retired controller add-on archive is not part of the product or build;
   do not recreate, redistribute, or project it into a managed client.
4. Do not clear app data, remove the managed client, replace the database, or
   delete a prefix without explicit user approval and a verified backup.
5. Do not place WoW files, extracted MPQ contents, database dumps containing
   user data, credentials, or proprietary runtime payloads in Git.
6. Preserve the x86_64 build and evidence lane while adding ARM64. Never reuse
   an x86 output directory for ARM artifacts.

## 7. Phase A - settle the current O14 diff

Objective: retain only justified implementation changes before adding ARM.

Required review:

- `android/app/src/main/java/com/pocketrealm/client/ClientDisplayHost.kt`
- `runtime/xserver-winlator/com/winlator/xserver/InputDeviceManager.java`
- `android/app/src/androidTest/java/com/pocketrealm/client/ClientBuild5875LoginTest.kt`
- `android/app/src/androidTest/java/com/pocketrealm/o12/O12IntegratedRuntimeTest.kt`

Keep fixes only when they have a stated invariant and test. In particular:

- retain generation-aware managed-client paths;
- retain prevention of close-key injection into a destroyed/unmapped window;
- retain null-safe input delivery during X focus/window teardown;
- remove temporary coordinate probes, fixed-time human pauses, verbose tap
  traces, and assertions that were introduced only for diagnosis;
- preserve the existing paced IME key FIFO and deterministic cancellation;
- keep O14 status pending.

Validation:

- host unit tests;
- Kotlin and Java compilation for the existing x86 realm/test variants;
- `git diff --check`;
- focused review of evidence/status claims against the actual diff.

## 8. Phase B - make ABI selection explicit

Objective: add ARM64 without weakening or silently changing x86_64.

Implementation requirements:

1. Replace the global hardcoded `abiFilters += "x86_64"` assumption with an
   explicit, validated build property or product dimension. A recommended
   interface is `-PpocketAbi=x86_64|arm64-v8a`.
2. Keep separate roots such as:
   - `native/.build-x86_64/`
   - `native/.build-arm64/` (the native builder's `arm64` triple; APK paths
     remain explicitly `arm64-v8a`)
   - `native/.build-o09-x86_64/`
   - `native/.build-o09-arm64-v8a/`
3. Parameterize staging, lockfiles, provenance, APK validation, and Gradle
   source directories by ABI.
4. Fail at configuration time for an unknown/missing ABI or an incomplete
   native closure.
5. Never create a universal APK accidentally. Each qualification APK must
   contain only its declared ABI until universal packaging is explicitly
   reviewed.
6. Record APK SHA-256, signing certificate, ABI contents, and native library
   hashes before install.

Acceptance:

- x86_64 assemble/validation remains unchanged and passes;
- arm64-v8a assemble selects only ARM artifacts;
- deliberate cross-ABI contamination fails the build;
- the ARM APK installs on the RP6 without `INSTALL_FAILED_NO_MATCHING_ABIS`.

## 9. Phase C - native ARM realm parity required for the RP6

Objective: supply the O15 subset needed for integrated O14 acceptance.

Current checkpoint: the ARM core/control library, packaging shim, X-server,
O09 realm runtime, O11 extractors, and the ARM MariaDB package conversion build
in isolated roots. The live RP6 has run the two-cycle O04 lifecycle/control
probe. This is not O15 parity: the probe uses seeded SQLite and intentionally
reports the known world-schema gap. The MariaDB provider is now ready for the
device run, while MariaDB initialize/query/recovery and device-level
realmd/mangosd acceptance remain outstanding. The database-only ARM packaging
lane is deliberately independent of translated Wine so this provider can be
installed and tested before the client lane exists.

Build the same pinned production components for `arm64-v8a`:

- MariaDB provider and client/control dependency closure (official Termux
  aarch64 package converted by `tools/stage_mariadb_android_arm.py`, with
  `schemas/mariadb-runtime-lockfile-arm64-v8a.json`);
- `realmd` runtime;
- `mangosd` runtime, first with bots disabled;
- JNI/Binder entry shims and control ABI;
- extractor/manifest readers needed by normal prepared-data startup;
- all transitive native libraries and `libc++_shared.so` where applicable.

Build the provider qualification APK with:

```text
cd android
./gradlew.bat -PpocketAbi=arm64-v8a -PpocketLane=database :app:assembleDatabaseRuntime
```

This lane is not a game/client APK and must not be counted as O14/O15
completion. It is the installable MariaDB conversion artifact for the RP6
device test. The full lane continues to use `-PpocketLane=full` (the default)
and remains fail-closed until translated Wine is pinned.

When the RP6 is online, select the one `Retroid Pocket 6` row from
`adb devices -l` (do not hard-code its wireless serial), then install the
provider APK and the debug instrumentation APK produced by
`:app:assembleDebugAndroidTest`:

```powershell
adb -s <current-rp6-serial> install -r app/build/outputs/apk/databaseRuntime/app-databaseRuntime.apk
adb -s <current-rp6-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <current-rp6-serial> shell am instrument -w -r `
  -e class com.pocketrealm.database.DatabaseLifecycleTest#o08FullAcceptance `
  com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner
```

Pull `files/o08-database-acceptance.json` and logcat after the run. This is
the first device-level MariaDB proof; until that command completes on the RP6,
the checked-in build record remains `deviceExecution: not_evaluated`.

Preserve:

- source commits and patch set;
- schema/migration ledger;
- rendered configuration semantics;
- Binder/control protocol versions;
- process isolation and supervisor ownership tokens;
- database backup format and managed-client identity.

On the RP6 prove, in order:

1. package-native ARM hello/control self-test;
2. MariaDB initialize, query, clean stop;
3. MariaDB forced death and classified recovery;
4. `realmd` ready on `127.0.0.1:3724` and clean stop;
5. zero-bot `mangosd` ready on `127.0.0.1:8085` and clean save/stop;
6. one dependency-ordered supervisor start/stop;
7. restore of an architecture-independent x86-created backup into a fresh ARM
   datadir followed by world-ready and exact selected-character assertions.

Do not mark O15 complete from compile-only evidence. Device execution is
required for the subset claimed here.

## 10. Phase D - ARM translated-Wine provider

Objective: implement the O16 subset needed to run build 5875 on the RP6.

Current checkpoint: `armTranslatedWine` is present as a fail-closed provider
boundary and the Gradle lane refuses to assemble until its immutable Box64 plus
x86_64 WoW64 Wine closure is staged and self-tested. No ARM APK is produced by
copying the x86 direct-Wine lane.

Provider boundary:

- add `armTranslatedWine` behind the existing `ClientRuntime` interface;
- do not branch importer, account, database, backup, or supervisor behavior on
  the client execution engine;
- select providers from device/runtime self-tests, not the model name.

Primary branch:

- pinned Box64;
- pinned 64-bit x86_64 Wine with modern WoW64 capable of running the 32-bit PE;
- pinned glibc/rootfs/runtime closure compatible with the Box64/Wine tuple;
- native ARM64 X-server, renderer bridge, and Android service shims;
- the existing redistributable 32-bit Windows self-test.

The RP6 advertises 32-bit Android userspace, so ARM-A/Box86 is a legitimate
fallback experiment. It is not the first branch. Attempt it only if ARM-B has a
captured self-test failure and record it as a separate runtime tuple.

Packaging and state invariants:

- Box/Wine/renderer executable code is APK-managed and hash-verified;
- no executable update is downloaded to writable storage;
- translator, prefix, and shader caches are app-private, size-bounded, and
  keyed by the complete compatibility tuple;
- an incompatible runtime change stages a new prefix/cache generation;
- the old accepted prefix remains recoverable until the new tuple reaches
  character selection;
- diagnostics expose Box, Wine, renderer, rootfs, patch, and cache IDs;
- fallback is visible and never silently changes the renderer/runtime.

Bring-up order on the RP6:

1. Box64 native self-test.
2. Wine `--version` and persistent wineserver proof.
3. Redistributable Win32 console/window/input self-test.
4. WineD3D client window inside the Android surface.
5. Build-5875 login screen with server absent.
6. Integrated local authentication and character selection.

Begin with WineD3D, 1280x720, 30 FPS, and audio off. DXVK/Turnip and 1080p are
qualified later as explicit profiles, not silently enabled during bring-up.

## 11. Phase E - full-screen landscape gameplay contract

Objective: make gameplay native to the RP6 screen instead of embedding it in
the Home-screen card.

Create a dedicated non-exported gameplay activity, for example
`ClientActivity`, launched only for the current supervisor/client generation.
Do not force the setup/import/recovery UI to share its lifecycle.

Activity requirements:

- declare or request `sensorLandscape` so either landscape rotation works;
- use edge-to-edge content and immersive system-bar hiding;
- allow transient bars by swipe rather than permanently reserving insets;
- reapply immersive state after focus changes, IME close, resume, and activity
  recreation;
- keep the screen on only while a visible client session is active;
- contain one display host/container per client generation;
- never reuse a destroyed X window, renderer surface, or input generation;
- foreground/background transitions must release all keys/buttons, cancel
  pointer capture, close IME state, and restore a usable neutral state;
- if orientation is locked by policy, rotation must preserve the same visible
  1920x1080 landscape contract and not recreate the realm.

Layout requirements:

- the game surface fills the full 1920x1080 physical landscape area;
- overlays are drawn over the surface, not in a card above/below it;
- overlay visibility, opacity, size, and safe-area behavior remain adjustable;
- the overlay can be completely hidden for physical peripherals and evidence;
- no permanent navigation bar, bottom app navigation, setup card, or unused
  portrait space remains during play;
- rounded corners/cutouts must not hide required buttons; use bounded safe-area
  calibration without shrinking the whole game unnecessarily.

Resolution profiles:

### Balanced profile

- virtual desktop and WoW resolution: 1280x720;
- physical output: 1920x1080;
- exact uniform scale: 1.5;
- frame cap: 30 FPS initially;
- required first playable and O14 acceptance profile.

### Quality profile

- virtual desktop and WoW resolution: 1920x1080;
- physical output: 1920x1080;
- exact uniform scale: 1.0;
- frame cap selected from measured 30/45/60 FPS results;
- qualified only if Wine/renderer, memory, thermal, and touch precision pass.

Safe-mode fallback may use 1280x720 but must remain full-screen. A lower
internal render resolution may be added later only as an explicit scaled safe
profile; it must not change the physical full-screen landscape requirement.

Coordinate contract:

- maintain one tested matrix from Android physical pixels to surface pixels,
  virtual-desktop pixels, X root coordinates, and Wine client coordinates;
- use the actual laid-out surface bounds, not screenshot guesses;
- handle system-bar transitions and both landscape rotations;
- clip or reject events outside the surface deterministically;
- update the matrix atomically with the surface generation;
- include corner, center, overlay-edge, letterbox-free, pointer-capture, and
  1280x720/1920x1080 round-trip tests.

Acceptance:

- device screenshot reports 1920x1080 landscape while gameplay is active;
- renderer capture and Android surface cover the available game bounds;
- no persistent system/app bars surround the client;
- touch at all four corners and center maps to the expected X/Wine position;
- pause/resume and both landscape rotations produce a fresh valid surface and
  a neutral input state;
- Balanced passes before Quality is considered accepted.

## 12. Phase F - install, import, and integrated RP6 bring-up

The RP6 currently has enough observed storage for development, but run an exact
preflight for client, prepared data, database, prefix, translator/shader cache,
backup, and temporary staging before copying anything.

Required flow:

1. Install the exact ARM APK and record APK/test APK hashes and certificate.
2. Grant only declared permissions; no root, Accessibility, or uinput.
3. Place the user-owned build-5875 source in ordinary user-visible storage.
4. Import it through the app's SAF flow. Do not write directly into app-private
   managed-client storage with ADB for acceptance.
5. Verify build identity, source immutability, complete managed manifest, and
   storage accounting.
6. Prepare or import the required server data through the supported workflow.
7. Restore the known architecture-independent database backup through the app.
8. Start database, realm, world, display, translated Wine, and WoW through the
   supervisor.
9. Prove loopback-only listeners and airplane/offline local operation after
   setup.
10. Retain stage-specific logs and stop safely on the first unexplained failure.

Development-only ADB staging may be used to diagnose a layer, but it must be
labeled and cannot satisfy fresh-install or import acceptance.

## 13. Phase G - human touch record and faithful playback

Objective: diagnose the realm-language control using real touchscreen input and
turn a successful human action into repeatable acceptance without bypassing the
production input path.

Replace fixed sleeps with a state-driven manual checkpoint harness:

- launch the integrated client and authenticate with a generated test account;
- detect the actual realm-language wizard state;
- display a non-secret Android status saying `Manual touch ready`;
- wait for an explicit user Continue/Finish action or a generous documented
  manual-test timeout;
- do not inject the English or Suggest actions before the human attempt;
- preserve the running realm/client while evidence is captured.

For each human action record:

- monotonic down/up timestamps and dwell;
- Android device/source/tool type and physical coordinates;
- current surface generation and surface bounds;
- transformed virtual, X root, and client coordinates;
- focused/mapped X window ID and dimensions;
- delivered X button/key state and final neutral state;
- renderer frame immediately before and after;
- Android screenshot immediately before and after;
- relevant bounded Wine/X/input logs.

Never record the account password, committed chat contents outside the fixed
test phrase, client file paths beyond redacted relative identity, or unrelated
device input.

After a human touch succeeds:

1. Replay the same physical-screen action with Android touchscreen injection
   (`adb shell input touchscreen ...`) or UIAutomator against the real Android
   window.
2. Do not call `host.dispatchPointer` directly for the replay acceptance.
3. Require the same visible UI transition and server/client state change.
4. Repeat after clean client relaunch and after activity resume.
5. If human touch succeeds but Android injection fails, retain the manual pass
   and diagnose source/tool-type differences; do not weaken the pass condition.
6. If human touch also fails, capture the exact end-to-end event and stop
   coordinate guessing. Diagnose the first layer where state diverges.

## 14. Phase H - real-client O14 UX acceptance

Run UX-T01 through UX-T08 in one controlled RP6 profile. Each scenario needs a
before state, user action, observable client/world result, and final neutral
input state.

### UX-T01 - create character and enter world

- complete the realm wizard;
- create a uniquely named test character;
- enter the local world;
- prove the selected character row/session and rendered in-world frame.

### UX-T02 - movement and camera

- move forward/back and strafe;
- turn camera through the touch camera region;
- release, open/close IME or background/resume, and prove no continued motion.

### UX-T03 - quest precision

- target a real NPC;
- accept a quest and turn it in;
- show that default 720p touch scaling is accurate without hiding the overlay.

### UX-T04 - combat

- fight three mobs;
- use at least six distinct abilities through readable action controls;
- prove action buttons and camera input do not conflict.

### UX-T05 - loot and inventory

- loot a mob;
- open bags;
- select and equip an item through pointer/cursor mode.

### UX-T06 - chat/IME

- open chat using the production affordance;
- type one fixed sentence containing punctuation;
- send exactly once;
- prove movement/camera are released while IME is active and neutral afterward.

### UX-T07 - alt bot workflow

- issue the required slash/whisper/command workflow to invite or control the
  test alt bot;
- prove the command reached the intended bot without duplicate text/Enter.

### UX-T08 - interruption recovery

- background and resume the activity during play;
- exercise both supported landscape rotations or prove a deliberate landscape
  lock;
- prove the surface, input generation, pointer state, IME, and overlay recover
  without restarting or corrupting the realm.

Capture duration, resolution, renderer, input profile, client runtime tuple,
world status, and device thermal/battery state for each scenario.

## 15. Phase I - physical input qualification

The built-in `Retroid Pocket Controller` qualifies the RP6's integrated
controller profile. The report's broader compatibility matrix also requires a
Bluetooth gamepad and keyboard/mouse coverage before stable support.

Qualify these named devices:

1. Retroid Pocket Controller.
2. One explicitly identified Bluetooth gamepad.
3. One explicitly identified Bluetooth or USB keyboard.
4. One explicitly identified Bluetooth or USB mouse.

For every device:

- record Android name, descriptor hash, vendor/product IDs where available,
  sources, axes, buttons, and dead zones;
- map representative controls and verify actual Wine behavior;
- persist mappings, relaunch the client, and prove the same effective map;
- hold a key/button/axis, disconnect or suspend the source, and prove a bounded
  synthetic release;
- reconnect without duplicating the logical source or leaving stuck input;
- restart the Android activity and client separately;
- hide the touch overlay and verify unobstructed physical play;
- retain a final empty pressed-key/button set.

Do not claim the `Retroid Pocket Virtual Mouse` alone as external physical
mouse coverage. It may have its own supported profile, but a named real mouse
is still required for the report's keyboard/mouse lane.

## 16. Phase J - evidence, review, and closure

Create an RP6 evidence directory under `tests/devices/` with:

- device capability JSON;
- exact app/test APK SHA-256 and signing certificate digest;
- native/runtime/source/provenance manifest IDs;
- renderer/resolution/input profile IDs;
- per-scenario structured UX-T01 through UX-T08 results;
- physical peripheral identities and hot-plug/restart results;
- bounded screenshots/renderer frames and redacted logs;
- final process/socket, pressed-input, thermal, battery, memory, and storage
  state;
- clean shutdown result and any required dirty-recovery follow-up.

Evidence rules:

- write PASS only after teardown and final-state validation succeeds;
- do not write PASS before cleanup inside a `try` block;
- teardown errors are acceptance failures, not discarded `runCatching` data;
- a manual result must name the human checkpoint and captured state;
- an injected replay must be labeled separately from human touch;
- no emulator identity may appear in RP6 evidence;
- no proprietary client file or secret enters the repository;
- every claim must match the exact APK/runtime hashes that produced it.

Before closing O14:

1. Run host unit/static tests.
2. Run the exact ARM component and Android instrumentation suites.
3. Run `git diff --check` and repository provenance/source validators.
4. Have a focused reviewer inspect ABI separation, lifecycle/input races,
   evidence ordering, security boundaries, and acceptance claims.
5. Update `FEATURES.json`, `PROGRESS.md`, `PLAN.md`, and `DECISIONS.md` together.
6. Mark O14 done only when UX-T01 through UX-T08 and the named physical input
   checks are all represented by accepted RP6 artifacts.

## 17. Suggested command surface

The implementation may add or adjust exact task names, but it should converge
on a command surface equivalent to:

```powershell
adb devices -l

python scripts/build_native.py --abi arm64-v8a --runtime --runtime-tests all
python tools/run_realm_test.py --abi arm64-v8a --serial <rp6-serial>

.\gradlew :app:assembleRealmRuntime -PpocketAbi=arm64-v8a
.\gradlew :app:assembleDebugAndroidTest -PpocketAbi=arm64-v8a

adb -s <rp6-serial> install -r <arm64-app-apk>
adb -s <rp6-serial> install -r <arm64-test-apk>

adb -s <rp6-serial> shell am instrument -w -r \
  -e class <rp6-manual-or-acceptance-test> \
  com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner
```

Every runner must refuse an unexpected ABI, page size, model/profile ID, or
APK hash. The physical device serial is supplied at invocation time and is not
stored as the stable device identity.

## 18. Stop conditions

Stop the current experiment, preserve evidence, and classify the failure when:

- the wrong ABI or an unpinned native/runtime artifact is selected;
- the app would need root, Accessibility, uinput, a separate Winlator APK, or a
  shell-managed runtime to proceed;
- client/server traffic leaves loopback during offline acceptance;
- a proposed fix changes managed client or database data destructively;
- the human touch and injected replay diverge without a captured first failing
  layer;
- renderer fallback is silent;
- the quality profile overheats, OOMs, corrupts input, or fails its frame gate;
- teardown cannot reach a verified clean or explicitly classified dirty state;
- evidence would overclaim emulator, synthetic, or compile-only results as RP6
  physical acceptance.

The agent may continue with a lower, explicitly identified safe profile after
capturing a failure. It may not weaken O14 acceptance, silently change the
runtime tuple, or mark the phase complete to unlock later work.
