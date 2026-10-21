# Pocket Realm — Desktop vs Android Parity Register

## Method and convergence

- **Inputs.** Every desktop-vs-Android parity gap below was hunted from
  `out/desktop-parity/android-inventory.md` (45 shipped Android features, rows 1–45),
  `out/desktop-parity/desktop-inventory.md` (honest Windows-desktop state, incl. "compile-only",
  "absent by design", and honest-stub declarations), `desktop/shared-sources.json` (60 shared main
  sources compiled straight from the Android tree), and direct source reads/greps over both trees
  (`android/app/src/main/java/com/pocketrealm`, `desktop/src`). The full hunt narrative lives in
  the 36 lens files `out/desktop-parity/hunt-r{1..6}-lens{1..6}.md`; **each register entry cites
  the hunt file(s) it was assembled from**, and every entry carries those files' full gap and
  parity-plan detail, not an index summary.
- **Lenses.** Each round ran six lenses: Android-surface walk, desktop-capabilities walk,
  platform-fit, adversarial cross-feature interactions, Android row-walk (45 rows vs the
  register), and desktop walk. Android-freeness of every share-candidate file was decided by grep
  for `android.*|androidx.*|me.zhanghai` imports (the same rule `tests/test_desktop_shared_manifest.py`
  enforces); desktop wiring was decided by grep over `desktop/src/main/kotlin`.
- **Convergence (4 hunt rounds + 2 extension rounds).** Rounds 1–2 produced DESK-1…126; rounds 3–4
  added DESK-127…140 and returned **zero new gaps** under the android-walk and platform-fit lenses;
  rounds 5–6 re-walked all 45 rows against the final register and confirmed saturation — every row
  is either claimed by a register entry, present on desktop, or android-specific. Runtime
  verification executed during the hunt (quoted from the hunt files): desktop test suite
  `./gradlew.bat test --rerun` → 315 tests / 0 failures / 0 errors / 0 skipped (42 suites), plus
  green gates `authGate` (SRP6 M2 vs real native realmd), `supervisorStartGate` (db+realm+world →
  READY → clean stop), `bootRealmd` (READY, port 3724), `whisperGate` (bridge honest-failure
  contract). Still not run anywhere in the hunt: `bootWorld`, `realmdHold`, `launchClient`, `rpHost`,
  `seedRealmData`, `packageApp`, `detekt`, the python lane builders.
- **Register convention.** Each entry: ID, gap (with the hunters' file/line evidence), portability
  verdict — **portable** (implementable on Windows/JVM) or **android-specific** (with the reason) —
  parity plan, effort (S/M/L/XL), severity (P1…P3). IDs are the consolidated DESK-1…140 space; where
  hunt lenses numbered the same finding independently, the entry merges their detail.

---

## Register — all gaps grouped by area

### Area 1 — Client import & managed client generations

### DESK-1 — Client folder import, managed generations & identity VAL gates
- **gap:** Desktop has no import at all — the Home picker stores a raw folder path and `startClient`
  only checks `WoW.exe` exists (`desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:517-520`).
  No PE32 version-resource identity check (Android `client/ClientPeIdentity.kt` — android-free, not
  in shared-sources.json), no VAL-01 launcher constant, no managed copy of the client into app
  storage, no immutable generation + atomic `active-vanilla.json` pointer
  (`importer/ClientGenerationStore.kt`, `importer/GenerationPointers.kt`, `client/ManagedClientStore.kt`,
  `client/ClientGenerationLease.kt` — none shared, no twins). A wrong or partial folder fails late
  at launch instead of being rejected at selection. (Android rows 2, 6, 7.)
- **portability:** portable — copy/verify/publication semantics are plain filesystem work.
- **parity plan:** Add android-free `client/ClientPeIdentity.kt`, `importer/GenerationPointers.kt`,
  `client/ClientGenerationLease.kt` to `desktop/shared-sources.json`; JVM twins for
  `importer/ClientGenerationStore.kt` + `client/ManagedClientStore.kt` (fsync/atomic via the
  `fs/DurableFiles` pattern) under `desktop/src/main/kotlin/com/pocketrealm/importer/`; new
  ImportScreen route; `startClient` resolves the active generation and runs identity gates.
  `tests/test_desktop_shared_manifest.py` must stay green; new desktop tests pin generation
  publish/adopt and identity rejection. *(hunt-r1-lens1.md)*
- **effort:** L — **severity:** P1

### DESK-2 — Archive (.zip/.7z/.rar) and Inno Setup import lanes
- **gap:** Compile-only on desktop (desktop-inventory.md line 70): StagedArchive/Inno core is shared
  and its 28 tests run green, but no desktop main source references it and no screen offers
  importing a zip/7z/rar game archive or an Inno Setup installer. The RAR lane cannot be shared
  as-is — `importer/RarArchiveSource.kt` imports `me.zhanghai.android.libarchive` (Android-only).
  (Android rows 3, 4.)
- **portability:** portable (RAR reader needs a Windows-twin backend).
- **parity plan:** Add android-free `importer/ArchiveSources.kt`, `importer/ScratchArchiveExtractor.kt`,
  `importer/InnoArchiveSource.kt`, `importer/ArchiveClientScanner.kt` to shared-sources.json; JVM
  twin of `RarArchiveSource.kt` over a Windows unrar/7z.exe subprocess or pure-JVM RAR reader;
  DESK-1's ImportScreen gets source-kind routing (folder/zip/7z/rar/Inno) feeding StagedArchive →
  ArchiveClientScanner. Existing shared importer tests already cover the core; add desktop
  RAR-twin + screen-flow tests. *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-3 — Import journal, resume, storage planning & process watchdog
- **gap:** With DESK-1/DESK-2 built, desktop import would still lack the durability layer: no
  SQLite WAL journal of entries/checkpoints (`importer/ImportJournal.kt` — Android SQLite, needs a
  twin over the existing `DesktopSqlite` seam), no storage budget planner
  (`importer/ImportStoragePlanner.kt` — `android.os.storage.StorageManager`), no death-reason
  watchdog copy or process metrics (`importer/ImportProcessMetrics.kt` — `ApplicationExitInfo`/
  `ActivityManager`; `ImportWatchdogNotices.kt` and `ImportProgressPresentation.kt` are
  android-free and absent). (Android row 5.)
- **portability:** portable (watchdog OS-death copy + process metrics are the android-specific
  trim; the journal/planner/progress legs port).
- **parity plan:** JVM twins under `desktop/src/main/kotlin/com/pocketrealm/importer/`:
  `ImportJournal` on the DesktopSqlite seam (WAL, same schema/checkpoint semantics),
  `ImportStoragePlanner` on `java.io.File.getUsableSpace`, `ImportProcessMetrics` on
  `OperatingSystemMXBean`/ProcessHandle; share `ImportWatchdogNotices.kt` +
  `ImportProgressPresentation.kt` via shared-sources.json. Tests: desktop journal crash/resume
  round-trip mirroring the Android journal tests. *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-20 — Client import folder/archive/Inno lanes + managed generations (merged lane view)
- **gap:** The shared importer pieces compile on desktop and their 28+ tests run green, but **no**
  desktop main source references them (re-verified: `StagedArchive`/`InnoSetupReader`/
  `ArchiveFormatSniffer`/`ImportModel`/`ImportSource`/`ImportPathPolicy` = 0 refs in
  `desktop/src/main/kotlin`). There is no import screen — the desktop expects an already-installed
  WoW folder via the Home picker — and `ClientGenerationStore`/`GenerationPointers` (immutable
  generations + atomic active pointers) have no desktop story at all. (Android #2/#3/#4/#6.)
- **portability:** portable — the archive/Inno machinery is android-free (ArchiveClientScanner,
  ArchiveSources, ScratchArchiveExtractor, ImportExtractionPolicy, GenerationPointers); only
  `RarArchiveSource` (me.zhanghai.libarchive) needs a twin, and
  SafTreeSource/ImportWorkerService/ImportJournal are inherently Android.
- **parity plan:** Share the android-free importer files via `shared-sources.json`; desktop twin of
  `RarArchiveSource` (JVM reader or honest RAR refusal); add an import flow to Home that verifies +
  publishes a generation under `%LOCALAPPDATA%/PocketRealm` and points `startClient` at the active
  generation pointer instead of a raw folder. *(hunt-r1-lens2.md)*
- **effort:** L — **severity:** P2

### DESK-21 — Client identity verification & VAL gates
- **gap:** `startClient` only checks that a file named WoW.exe exists
  (`DesktopRuntimeBackend.kt:514-515`) before rewriting `realmlist.wtf` and spawning — any
  wrong-build client in the folder is launched as-is. `client/ClientPeIdentity.kt` is android-free
  (grep) but unshared and unused on desktop. (Android #7: PE32 version-resource parse, VAL-01.)
- **portability:** portable — the PE parse is pure byte work on the JVM.
- **parity plan:** Add `client/ClientPeIdentity.kt` to `shared-sources.json`; call it in
  `startClient` before `ClientRealmEndpointProjection.project` and refuse non-build-5875 folders
  with the VAL copy; cover with a fixture-PE unit test under `desktop/src/test`. *(hunt-r1-lens2.md)*
- **effort:** S — **severity:** P2

### DESK-34 — Managed client import & generation store
- **gap:** Desktop has no managed import at all: `DesktopRuntimeBackend.kt:511-547` points at the
  user's existing WoW folder (Home FileDialog picker / default `C:/Vanilla wow 1.12.1`) and launches
  it in place — no staging→hash→fsync→atomic-pointer publication, no per-flavor `active-<flavor>.json`
  pointers, no legacy-seed adoption, no cross-process publication lease. Desktop-inventory line 70
  confirms "no screen offers client import"; shared-sources.json contains none of the five files
  (`ManagedClientImporter`, `ClientGenerationStore`, `GenerationPointers`, `ManagedClientStore`,
  `ClientGenerationLease`). (Android #2+#6.)
- **portability:** portable — the SAF transport is Android-specific, but the import semantics (file
  copy, digest verify, immutable publication, atomic pointer switch) are plain filesystem work; the
  desktop already proves the pattern (`DesktopSqliteSeeder.kt` atomic publish).
- **parity plan:** Add android-free `GenerationPointers.kt`/`ClientGenerationLease.kt` to
  shared-sources.json (keep `tests/test_desktop_shared_manifest.py` green); JVM twins
  `desktop/src/main/kotlin/com/pocketrealm/importer/DesktopClientGenerationStore.kt` +
  `DesktopManagedClientImporter.kt` mirroring `ManagedClientImporter.kt` with java.nio copy
  (ClientGenerationStore has 4 android refs, ManagedClientStore 1, ManagedClientImporter 8,
  ImportJournal 5 — journal/resume twin can use the DesktopSqlite seam); Home import card; test via
  already-shared `shared_debug/SyntheticClientArchives.kt` + importer suites. *(hunt-r1-lens3.md)*
- **effort:** L — **severity:** P1

### DESK-35 — Archive import lanes .zip/.7z/.rar + Inno Setup installer lane
- **gap:** The formats engine is already shared and green (shared-sources.json lines 37-49 +
  `shared_debug` synthetic fixtures; 13+7+8 importer/inno tests pass), but it is compile-only — no
  desktop main source references it. Desktop users get `tools/install_client_windows.ps1`, a
  script, not app functionality. Unshared and android-free (0 android refs): `ArchiveSources.kt`,
  `ArchiveClientScanner.kt`, `ScratchArchiveExtractor.kt`, `ImportExtractionPolicy.kt`,
  `InnoArchiveSource.kt`. `RarArchiveSource.kt` has 5 android refs (Android binds a libarchive APK
  lib) so it needs a JVM twin. (Android #3+#4.)
- **portability:** portable — zip/7z have JVM-native decoders (commons-compress), RAR4/5 via
  junrar; Inno readers are already pure Kotlin shared code.
- **parity plan:** Share the five android-free files via shared-sources.json; JVM twin
  `desktop/.../importer/DesktopRarArchiveSource.kt` over commons-compress/junrar keeping the VAL-13
  encrypted/multi-volume/solid-RAR4 refusals; import dialog feeding DESK-1's generation store. The
  28 green importer/inno desktop suites already cover the engine. *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P1

### DESK-36 — Client identity verification & VAL gates (PE32 parse)
- **gap:** Desktop launch verifies nothing about the client binary: `DesktopRuntimeBackend.kt:514-515`
  is just `check(exe.isFile)` before projecting `realmlist.wtf`. Android refuses non-5875 clients
  via PE version-resource parsing (`ClientPeIdentity.kt:37-45`, VAL-02/VAL-03 copy); desktop would
  happily launch a wrong-build exe against a build-5875-pinned protocol (`AuthGate.kt` SRP6).
  `ClientPeIdentity.kt` is android-free (0 refs) and unshared. (Android #7.)
- **portability:** portable — PE/MPQ validation is explicitly a portable lane.
- **parity plan:** Add `client/ClientPeIdentity.kt` to shared-sources.json; call
  `ClientPeIdentity.parse(WoW.exe bytes)` in `DesktopRuntimeBackend.startClient` before the
  realmlist projection (:516 already documents the no-side-effects-before-refusal discipline),
  refusing non-1.12.1/5875 with the pinned VAL copy; SafClientScanner's folder checks become a
  small twin (its 3 refs are ContentResolver/Uri/DocumentsContract only); desktop test pins refusal
  on a synthetic non-5875 PE. *(hunt-r1-lens3.md)*
- **effort:** S — **severity:** P2

### DESK-52 — Client identity / VAL gates at folder adoption and launch
- **gap:** Android gates every client use on the PE32 version resource resolving to a qualified
  `ClientFlavor` (`client/ClientPeIdentity.kt:4-9`, registry-gated; folder/archive scanners per
  inventory row 7). Desktop checks only file existence: `DesktopRuntimeBackend.kt:514-515`
  (`check(exe.isFile)`) and `DesktopAppModel.resolveClientDir` (`DesktopAppModel.kt:63-69`) accept
  any folder containing a file named `WoW.exe`, then projections mutate it and launch it. The
  repo's own PC companion enforces more than the app does: `tools/install_client_windows.ps1:96-98`
  pins the exe byte size and `1.12.1.5875` version string. A wrong-flavor or renamed client is
  silently projected and launched.
- **portability:** portable.
- **parity plan:** Add `client/ClientPeIdentity.kt` to `shared-sources.json` (verified android-free:
  imports are `java.nio`/`java.security` only); JVM-twin the folder scan as
  `desktop/src/main/kotlin/com/pocketrealm/client/DesktopClientScanner.kt` applying the VAL-01
  identity + flavor resolution inside both `resolveClientDir` and `startClient`, refusing **before**
  realmlist projection with the Android VAL copy. Test with a synthetic PE version-resource fixture
  (pattern exists: `shared_debug` fixtures `SyntheticClientArchives.kt`). *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P1

### DESK-55 — Client import lanes & managed client generations on desktop
- **gap:** The importer files compile desktop-side with green tests (manifest: `importer/*`,
  `importer/inno/*`, `shared_debug` fixtures), but no desktop main source references them — no
  import screen, and no `ClientGenerationStore`/`GenerationPointers`/`ManagedClientStore` twins, so
  the desktop stores a raw `clientDir` string instead of immutable hash-verified generations with
  atomic per-flavor `active-<flavor>.json` pointers. The PC companion script does the verify/extract
  legs by hand. (Android rows 2, 6 + archive/Inno UI.)
- **portability:** portable.
- **parity plan:** Desktop `ImportScreen` route reusing the already-shared
  `StagedArchive`/`ArchiveFormatSniffer` + inno readers and the debug synthetic-archive fixtures
  for tests; JVM twins `ClientGenerationStore`/`GenerationPointers`/`ManagedClientStore` under
  `desktop/src/main/kotlin/com/pocketrealm/importer/` with generations under
  `DesktopStorageRoots.content` and `active-<flavor>.json` pointers; then route
  `resolveClientDir`/`startClient` through the fail-closed managed-store reader (DESK-52's scanner
  feeds it). *(hunt-r1-lens4.md)*
- **effort:** L — **severity:** P2

### DESK-63 — Managed folder-lane client import
- **gap:** Desktop has no import flow at all: the Home FileDialog picker points at an
  already-installed WoW folder used **in place** (`DesktopRuntimeBackend.kt:541-551`
  `resolveConfiguredClientDir`, default `C:/Vanilla wow 1.12.1`); nothing copies a client folder
  into managed storage with the VAL path/identity policy. `ManagedClientImporter.kt` (8 android
  refs), `ImportJournal.kt` (5), `GenerationPointers.kt` are unshared with no JVM twins;
  `ImportSource.kt`/`ImportPathPolicy.kt` are shared compile-only. (Android row 2.)
- **portability:** portable.
- **parity plan:** Twin ManagedClientImporter/ImportJournal/GenerationPointers in
  `desktop/src/main/kotlin/com/pocketrealm/importer/` (Context→DesktopStorageRoots, android
  sqlite→DesktopSqlite seam, Os.fsync→FileChannel.force); add an Import route to Router.kt;
  ImportSource/ImportPathPolicy already shared. *(hunt-r1-lens5.md)*
- **effort:** L — **severity:** P2

### DESK-64 — .zip/.7z/.rar archive import lanes in-app
- **gap:** Desktop cannot import a client from an archive. Shared `StagedArchive`/
  `ArchiveFormatSniffer`/`ImportSource` are compile-only on desktop, `ArchiveSources.kt`/
  `ArchiveClientScanner.kt`/`ScratchArchiveExtractor.kt`/`ImportExtractionPolicy.kt` are
  android-free (0 refs each) but unshared, `RarArchiveSource.kt` uses `me.zhanghai.libarchive`
  (5 refs), and no desktop screen offers import. `tools/install_client_windows.ps1` is a PC-side
  script, not app functionality. (Android row 3.)
- **portability:** portable (ZIP/7z via JVM/commons-compress; RAR needs a native helper).
- **parity plan:** Share android-free ArchiveSources/ArchiveClientScanner/ScratchArchiveExtractor/
  ImportExtractionPolicy via shared-sources.json; JVM-twin RarArchiveSource over
  commons-compress/7z.exe; add an archive lane to the DESK-63 Import screen. *(hunt-r1-lens5.md)*
- **effort:** L — **severity:** P2

### DESK-65 — Inno Setup installer lane wiring
- **gap:** The complete `inno/*` port (8 files) plus `StagedArchive` are shared and their tests run
  green on desktop (InnoSetupReaderTest, InnoVersionAndFilterTest, StagedArchiveTest inside the
  315), but **no desktop main source wires them into any flow** — an Inno installer cannot be
  imported from the desktop app. `InnoArchiveSource.kt` (the orchestrator) is itself unshared.
  (Android row 4.)
- **portability:** portable.
- **parity plan:** Add a thin desktop import-lane entry that stages via shared StagedArchive +
  inno/* (all in shared-sources.json) and publishes through the DESK-67 ClientGenerationStore twin;
  android `InnoArchiveSource.kt` is the mirror; existing shared inno tests + one wiring test cover
  it. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-66 — Import journal, resume, storage planning, progress
- **gap:** No desktop equivalents: `ImportJournal.kt` (5 android refs — android SQLite WAL journal
  of thousands of entries) and `ImportStoragePlanner.kt` (Context/StorageManager) have no JVM
  twins; android-free `ImportWatchdogNotices.kt`/`ImportProgressPresentation.kt` (0 refs) are
  unshared. `ImportWorkerService` (FGS), `ImportProcessMetrics` (8 refs) and `DeviceProfile` are
  the Android-specific part. Depends on DESK-63/64 existing first. (Android row 5.)
- **portability:** portable (journal/planner/progress; watchdog + process metrics are
  android-specific).
- **parity plan:** Twin ImportJournal on the DesktopSqlite seam and ImportStoragePlanner on
  `File.getUsableSpace` under `desktop/.../importer/`; share android-free
  ImportProgressPresentation via the manifest; wire into the DESK-63 import screen.
  ImportWorkerService/ProcessMetrics/DeviceProfile stay Android-only. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P3

### DESK-67 — Managed client generations & atomic active pointers
- **gap:** Desktop launches straight from the picked folder — no staging→hash→fsync→atomic-switch
  publication, no `active-vanilla.json` pointers, no cross-process lease.
  `importer/ClientGenerationStore.kt` (android.content.Context/Os/OsConstants refs at lines 3-5,202),
  `importer/GenerationPointers.kt`, `client/ManagedClientStore.kt` (Context, :3) and
  `client/ClientGenerationLease.kt` (android-free) are all unshared with no desktop twins.
  (Android row 6.)
- **portability:** portable.
- **parity plan:** Create `desktop/.../importer/ClientGenerationStore.kt` + GenerationPointers.kt
  twins (Context→DesktopStorageRoots.content, Os.fsync→FileChannel.force); make
  `DesktopRuntimeBackend.resolveConfiguredClientDir` read the active pointer; port android
  ClientGenerationStoreTest. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-68 — Client identity verification / VAL gates before use
- **gap:** Desktop verifies only that a `WoW.exe` file exists before launching
  (`DesktopRuntimeBackend.kt:541-551` refuse path); there is no PE32 version-resource identity
  parse, no launcher-only VAL-01 check, no archive-scanner identity re-verify — a wrong-build or
  tampered client starts and fails late. `client/ClientPeIdentity.kt` is android-free (0 refs) and
  unshared; `SafClientScanner.kt` (3 Context/Uri refs) has no JVM twin; `ArchiveClientScanner.kt`
  is android-free and unshared. (Android row 7.)
- **portability:** portable.
- **parity plan:** Share `client/ClientPeIdentity.kt` via shared-sources.json; twin
  SafClientScanner's folder checks as DesktopClientIdentity (java.io.File walk); call it in
  `DesktopRuntimeBackend.startClient` before side effects; port the VAL-01 test cases.
  *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-97 — Folder/archive/Inno import, generations, PE identity gate
- **gap:** The desktop inventory itself marks this "Compile-only on desktop": `importer/*` +
  `importer/inno/*` (shared-sources.json:37-49, 13+7+8 green tests) have **no desktop main-source
  references**, there is no Import screen (the only "Import" in desktop UI is the bot-preset
  button, `ui/BotsScreenSections.kt:113`), no managed-generation store, and no client identity
  gate — desktop expects an already-installed WoW.exe via the Home picker
  (`HomeScreen.kt:290` FileDialog; `DesktopRuntimeBackend.kt:511-515` only checks `WoW.exe`
  exists, never its version identity). Android's entire onboarding is import; desktop ships none
  of it in-app. (Android #2-#7.)
- **portability:** portable.
- **parity plan:** New Import route in `Router.kt` driving the already-shared
  `StagedArchive`/`ArchiveFormatSniffer`/`InnoSetupReader` stack from a `java.awt.FileDialog`
  source; desktop twins `ClientGenerationStore`/`GenerationPointers` under `DesktopStorageRoots`
  (`<root>/clients/generations`, per-flavor `active-vanilla.json`); add the verified android-free
  `client/ClientPeIdentity.kt` (imports only java.nio/java.security) to `shared-sources.json` and
  gate start on the resolved flavor identity (VAL-01/02/03 copy), not bare `WoW.exe` existence;
  then point `DesktopRuntimeBackend.resolveConfiguredClientDir` (`:541-547`) at the active
  generation first. *(hunt-r1-lens6.md)*
- **effort:** L — **severity:** P1

### Area 2 — Client runtime & launch boundary

### DESK-13 — Client tweaks config & nearby-loot enablement
- **gap:** Desktop has no tweak surface at all: grep `tweak` over `desktop/src` → 0 hits; desktop
  `Settings.kt:16-34` has no tweak fields; `client/ClientTweaks.kt` is android-free but unshared.
  The desktop conf twin DOES emit the `PocketRealm.NearbyInteract` knobs (desktop-inventory line 50)
  that nothing on the client side consumes — the client-side enablement (vanilla-tweaks config +
  the nearby-loot acceptance patch) exists only in Android published builds. No crash-guard fold
  either (Android `ClientRuntimeService.kt:69-73`). (Android rows 39, 10 tweak leg.)
- **portability:** portable (config lane fully; binary acceptance patch needs a Windows PE patch leg).
- **parity plan:** Share `client/ClientTweaks.kt`; add tweak fields + schema v2 migration to desktop
  `storage/Settings.kt` (mirroring android storage/Settings.kt:58-89) and a Controls card; desktop
  applier twin writes the effective vanilla-tweaks config into the client dir at launch (mirroring
  `WineRuntimeStore.resolveEffectiveClientTweaks`, WineRuntimeStore.kt:50); fold crash-guard
  timestamps into DesktopRuntimeBackend. Flag: the nearby-loot binary acceptance patch must be
  ported to patch the user's x86 WoW.exe or shipped as an optional installer step.
  *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-27 — Client tweaks — vanilla-tweaks patcher + nearby-loot patch
- **gap:** The Windows patch **producer** exists and is lockfile-pinned:
  `schemas/vanilla-tweaks-lockfile-win.json` pins
  `native/.build-vanilla-tweaks-win/staging/bin/vanilla-tweaks.exe` (799,232 bytes — verified
  staged on disk) with purpose "Host-side producer of WoW.exe.patched from a pristine managed
  WoW.exe" — but its only consumer is `tests/test_win_lockfile.py` (grep); desktop main has 0
  references to `ClientTweaks`/vanilla-tweaks. Meanwhile the desktop conf enables
  `PocketRealm.NearbyInteract` (`ServerRuntimeFiles.kt:146-147`) while the user's stock WoW.exe
  never receives the nearby-loot acceptance patch — L1 nearby-use can never open loot windows on
  desktop, and the upstream quality patches (FoV, farclip, large-address-aware, …) are unreachable.
  (Android #39.)
- **portability:** portable — the patcher is a native Windows exe; only the app wiring is missing.
- **parity plan:** Add android-free `client/ClientTweaks.kt` to `shared-sources.json`; add a
  "prepare client" step (Home `ActiveSetupCard`) that runs the staged vanilla-tweaks.exe with
  `toFlags()` + the nearby-loot patch to produce `WoW.exe.patched` and launches that binary; mirror
  the Android tweak toggles in desktop Settings. *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P2

### DESK-46 — vanilla-tweaks 1.6.0 flag config + Pocket-Realm nearby-loot acceptance patch
- **gap:** No desktop settings UI or code touches client tweaks: `client/ClientTweaks.kt` is
  android-free (0 refs) and unshared; grep of desktop main sources finds no `ClientTweaks`
  reference. The desktop carries the server-side `PocketRealm.NearbyInteract` conf knobs but
  nothing applies the client-side acceptance patch that enables nearby-use, and quality-of-life
  flags (fps/fov/no-paranoia) are user-manual. (Android #39.)
- **portability:** portable — vanilla-tweaks is a file patcher for Windows clients; config emission
  is pure data (only the "close other apps"/mobile framing is phone-specific).
- **parity plan:** Share `client/ClientTweaks.kt` via shared-sources.json; desktop Settings card
  mirroring `ClientTweakControls`, writing the vanilla-tweaks config + applying the nearby-loot
  acceptance patch into the picked client folder with consent (refuse while client runs, preserve
  originals); `ClientTweaksConfigTest`/`ClientTweaksNearbyLootAcceptTest` join shared_tests.
  *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P3

### DESK-50 — EULA/TOS/intro-movie skip + app-owned Config.wtf overlay at client launch
- **gap:** Android's prepare path enforces `readTOS`/`readEULA`/`readScanning`/`movie=0` plus
  renderer/resolution/audio lines into the client's Config.wtf at **every** launch
  (`ingame/ManagedConfigPolicy.kt:40-91`; consumed by `WineRuntimeStore`, `ClientGenerationStore`,
  `InGameSettingsEditor`). Desktop's only client-directory write is `realmlist.wtf`
  (`DesktopRuntimeBackend.kt:519`); grep of `desktop/src/main` for `readTOS|EULA|Config.wtf` is
  zero. A fresh 1.12.1 install on Windows therefore opens on the EULA/TOS + intro-movie gates,
  while auto-login fires ~3 s after launch and types into whatever window is up
  (`DesktopAppModel.kt:174-175`, `Win32AutoLogin.kt:116`) — the exact collision the Android overlay
  exists to prevent.
- **portability:** portable.
- **parity plan:** Add `ingame/ManagedConfigPolicy.kt` to `desktop/shared-sources.json` (pure
  Kotlin — verify with `tests/test_desktop_shared_manifest.py`); create
  `desktop/src/main/kotlin/com/pocketrealm/ingame/DesktopConfigWtf.kt` that merges
  `ManagedConfigPolicy.enforcedKeys(...)` over the user's `WTF/Config.wtf` through the
  already-shared `ConfigWtfCodec`, called from `DesktopRuntimeBackend.startClient` before
  `ProcessBuilder.start()`; desktop launch conditions = the portable subset (windowed, movie off,
  TOS/EULA read). Golden-pin the merged file against a desktop twin of `WtfCodecsTest`.
  *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P1

### DESK-51 — realmlist/patchlist handling on native Windows
- **gap:** `startClient` re-projects the **user's** `realmlist.wtf` wholesale through the shared
  `ClientRealmEndpointProjection` (`DesktopRuntimeBackend.kt:519`), whose `text()` emits exactly one
  line `set realmlist <addr>` (`client/ClientRealmEndpointProjection.kt:10-27`). On Android the
  target is app-owned (Wine prefix / managed client generation), so wholesale replacement is safe;
  on Windows the file is user property outside the app roots, and desktop keeps no backup, no
  restore-on-exit, and no preservation of any other directive the stock file carries
  (`tools/install_client_windows.ps1:81-84` shows realmlist.wtf with content beyond the one line).
  `grep patchlist` over the entire repo returns zero — the repo has no patchlist story at all, so
  desktop inherits an Android assumption that does not hold on native Windows.
- **portability:** portable (the fix is desktop-side file handling; the shared writer stays
  untouched for Android byte-parity).
- **parity plan:** New `desktop/src/main/kotlin/com/pocketrealm/client/DesktopRealmlist.kt` wrapping
  the shared projection: before the first projection of a session, copy `realmlist.wtf` + sha256
  into `DesktopStorageRoots.runtime`; merge instead of replace (preserve non-`set realmlist` lines,
  e.g. a `set patchlist` line); restore the original bytes on graceful teardown/force-stop. Wire
  into `startClient`/`close()`; pin merge+restore legs in `DesktopRuntimeBackendTest`.
  *(hunt-r1-lens4.md)*
- **effort:** S — **severity:** P1

### DESK-58 — vanilla-tweaks / nearby-loot client patch lane
- **gap:** Android patches the managed `WoW.exe` with vanilla-tweaks 1.6.0 flags plus the
  Pocket-Realm nearby-loot acceptance patch that enables L1 nearby-use (`client/ClientTweaks.kt:4-19`;
  `WineRuntimeStore.resolveEffectiveClientTweaks`). Desktop has no tweaks lane at all (grep
  `ClientTweaks`: zero) yet still emits `PocketRealm.NearbyInteract = 1` (`ServerRuntimeFiles.kt:146`)
  — server side on, client side structurally incapable; any portable nearby-use surface would
  silently no-op. Distinct from DESK-54 (the add-on manager/projector): this is the binary-patch/
  tweaks lane.
- **portability:** portable.
- **parity plan:** Desktop twin of `resolveEffectiveClientTweaks` deciding the conf knob — default
  `PocketRealm.NearbyInteract = 0` unless the user consented to patching their client; ship the
  vendored `native/vanilla-tweaks` patcher as a staged, lockfile-pinned Windows exe (same staging
  pattern as the three DLLs) applied to a user-consented copy of `WoW.exe`; pin the knob leg in
  `DesktopServerRuntimeFilesTest`. *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P2

### DESK-89 — vanilla-tweaks patch config + nearby-loot acceptance patch + launch gate
- **gap:** Desktop never patches or gates the client: `client/ClientTweaks.kt` is a **fully
  android-free** pure patch engine (verified 0 android refs; read in full — byte-exact
  vanilla-tweaks 1.6.0 output via `expectedPublishedPatchedBytes`, the Pocket-Realm nearby-loot
  patch at 0x1EB944, and the `AUTHORIZED_CLIENT_SHA256` launch gate) but it is unshared, no
  `WoW.exe.patched` is ever produced on desktop, and there is no tweak UI. The server-side
  NearbyInteract knobs ARE emitted (desktop `ServerRuntimeFiles.kt:146-147`) — so the L1 nearby-use
  feature cannot open loot windows client-side, and FOV/farclip/frill/nameplate patches are
  unavailable. (Android row 39.)
- **portability:** portable.
- **parity plan:** Share android-free `client/ClientTweaks.kt` via shared-sources.json; in
  `DesktopRuntimeBackend.startClient` publish WoW.exe.patched via `expectedPublishedPatchedBytes`
  gated on AUTHORIZED_CLIENT_SHA256; add Settings fields + ClientTweakControls to desktop
  SettingsScreen. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-132 — nearbyInteractTriggerGuardMs setting → PocketRealm.NearbyInteractCooldownMs
- **gap:** Android carries the nearby-use debounce as a persisted setting — `storage/Settings.kt:385`
  (`nearbyInteractTriggerGuardMs`, normalized on load :801 and write :1077) — and threads it
  through every conf entry point (`server/ServerRuntimeFiles.kt:67,79,86,91,98,104,112` →
  `NearbyInteractPolicy.normalizeTriggerGuardMs` at :139); the conf comment itself says "This
  bounded debounce can be raised for slower realm configurations" (`desktop/.../server/ServerRuntimeFiles.kt:144-145`).
  The desktop twin kept the parameter (`ServerRuntimeFiles.kt:84-85`, default only) but no caller
  ever passes it: `DesktopRuntimeBackend.kt:700` calls `worldConfig(endpoint.address, botProfile = ...)`
  with the guard left at DEFAULT, and desktop `storage/Settings.kt` has no field — the user-facing
  tuning surface from Android's nearby-use lane is absent.
- **portability:** portable.
- **parity plan:** Add `nearbyInteractTriggerGuardMs` (default
  `NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS`) to `desktop/.../storage/Settings.kt` Snapshot +
  JSON codec, normalize through the shared `NearbyInteractPolicy.normalizeTriggerGuardMs` exactly
  like Android's :801/:1077, pass it at `DesktopRuntimeBackend.kt:700`, and expose a stepper in
  `ui/SettingsScreen.kt` (world card). Extend `DesktopServerRuntimeFilesTest` conf pins for a
  non-default guard value. *(hunt-r3-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-139 — Game-audio mute toggle (audio mode in the client prepare request + Settings card)
- **gap:** Android ships a user-facing audio mode: `storage/Settings.kt:258`
  `enum class AudioMode { OFF, ON }`, default ON (:384), persisted (:799-800, :899, :1075), with a
  Settings toggle card (`ui/SettingsScreen.kt:1246-1251`, gated on `audioSupported`). It is part of
  the prepare contract (`client/ClientRuntimeService.kt:247-248` requires "off"|"on"; :301 pins
  audio identity across the session) and reaches the Wine session as `POCKET_AUDIO_MODE=off` (:555)
  or ALSA-routed env (:682-683). Desktop has **no audio surface at all**: grep
  `-i "audio|mute|volume"` over `desktop/src/main/kotlin` → 0 hits. The Wine/ALSA mechanism is
  android-specific, but the user-visible capability — mute/unmute the game from the app — is
  portable: Windows exposes per-process audio-session mute (Core Audio), reachable via the same
  JNA technique the app already uses for SendInput (Win32AutoLogin). (Android row 10 sub-feature;
  the tweak leg of the same request is DESK-13.)
- **portability:** portable.
- **parity plan:** Add `audioMode` (OFF/ON, default ON) to `desktop/.../storage/Settings.kt` + a
  SettingsScreen toggle card; `DesktopRuntimeBackend.startClient` applies the mode to the spawned
  WoW.exe via a JNA Core Audio per-session mute twin (mirrors `ClientRuntimeService.kt:247-266/682-683`;
  the toggle applies per launch, matching Android's prepare-time semantics). Tests: setting
  round-trips in the JSON store; the mute leg refuses honestly (result detail, no throw) when no
  audio session is found for the pid. *(hunt-r5-lens1.md)*
- **effort:** M — **severity:** P3

### DESK-140 — User-driver crash-guard fold timestamps in the client runtime service
- **gap:** Android's client runtime service folds crash-guard timestamps at launch
  (`ClientRuntimeService.kt:69-73`: `launchedAtElapsedMs`, `userVulkanOutcomeRecorded`,
  `UserVulkanCrashGuard`). Round-2 investigation (hunt-r2-lens1.md, `sed -n '55,110p'`
  `client/ClientRuntimeService.kt` this session) proved the crash-guard is entirely
  **user-Vulkan-scoped**: it feeds the two-consecutive-early-deaths → quarantine & reset-to-Auto
  loop of the user-imported aarch64 Vulkan driver lane (Android row 38).
- **portability:** android-specific — the mechanism exists to quarantine user-imported
  ARM/Android Vulkan drivers (ELF64 aarch64, 16 KB page alignment, Winlator session); Windows has
  no user-Vulkan lane (vendor driver), so there is nothing to fold timestamps for. Desktop
  ownership of that area is DESK-88's android-specific registration.
- **parity plan:** No port. Document the crash-guard as part of the Android-only user-Vulkan
  driver lane (row 38 / DESK-88); no desktop code path consumes it. *(hunt-r2-lens1.md)*
- **effort:** M — **severity:** P2

### Area 3 — Input

### DESK-4 — Gamepad/controller support & input profiles (portable subset of the input rows)
- **gap:** Desktop has zero controller support and no controls destination: Router exposes only
  Home/Bots/LLM/Settings/Diagnostics (`desktop/src/main/kotlin/com/pocketrealm/desktop/Router.kt:9-17`),
  and the searchable catalog of all 211 stock 1.12.1 binding IDs
  (`client/WowVanillaBindingCatalog.kt` — android-free) plus per-button profiles/dead zones/
  trigger tuning (`client/InputProfile.kt`) exist only on Android. (Android rows 12–13, portable
  subset; the touch overlay and X-server input bridge themselves are android-specific and excluded.)
- **portability:** portable.
- **parity plan:** Share `client/WowVanillaBindingCatalog.kt` (android-free); JVM twin of
  `client/InputProfile.kt` + an InputProfileStore over desktop settings (it imports
  `android.view.KeyEvent`/Context — map to raw key codes); new desktop ControlsScreen route with
  scheme picker + per-button editor; a Win32 gamepad reader (JNA, same technique as
  `Win32AutoLogin`'s SendInput) injects mapped keys while WoW.exe is foreground. Tests: profile
  (de)serialization + catalog count pinned. *(hunt-r1-lens1.md)*
- **effort:** L — **severity:** P2

### DESK-73 — Input injection contract (gamepad/key/mouse/touch → X events)
- **gap:** `client/InputContract.kt` sinks inject into the embedded X server; Windows delivers OS
  input to the native window. Nothing on desktop consumes the contract (no desktop reference).
- **portability:** android-specific — the contract's sink is the embedded Winlator X server;
  Windows delivers input to the native WoW.exe window.
- **parity plan:** No port: InputContract injects into the embedded X server; Windows delivers OS
  input natively. Nothing on desktop should consume it; desktop-inventory note only.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-74 — Controls screen & on-screen input profiles
- **gap:** No desktop counterpart — on-screen control layouts/dead zones/trigger points exist for
  touch/glass play; desktop uses physical devices. Portable residue: the android-free
  `WowVanillaBindingCatalog.kt` (211 binding IDs) that a future desktop bindings editor (DESK-4 /
  DESK-96) would want.
- **portability:** android-specific (residue portable — the binding catalog is DESK-4's share).
- **parity plan:** No controls screen on desktop. Residue: share android-free
  `client/WowVanillaBindingCatalog.kt` via shared-sources.json when the desktop bindings editor
  lands; document on-screen controls as Android-only. *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-75 — Touch overlay (on-screen controls, two layouts, move mode)
- **gap:** Pure touch UI (`ui/TouchOverlay.kt`: OFF/MINIMAL/FULL, Console vs Classic arrangements,
  draggable clusters); no desktop meaning.
- **portability:** android-specific.
- **parity plan:** No port: pure touch UI. Desktop-inventory note only. *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-76 — IME text entry into the game
- **gap:** The IME bridge (`ClientImeView`/`ImeCharMap`) exists for Android soft keyboards
  (`commitText` through the generation-gated input contract); desktop windows get native keyboard
  focus.
- **portability:** android-specific.
- **parity plan:** No port: desktop has native keyboard focus. Desktop-inventory note only.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### Area 4 — Database engine & durability

### DESK-5 — MariaDB database engine lane, datadir durability & migration ledger
- **gap:** Desktop is SQLite-only "by design" (desktop-inventory line 73):
  `database/DatabaseEngine.kt`/`DatabaseService.kt` (Android `Context`-bound) have no desktop
  twins, so the MariaDB provider, its datadir ownership/durability leg
  (`database/DatabaseDurability.kt`), and the durable init/migration transactions
  (`database/DatabaseDurableState.kt` — android-free, unshared) are absent. Consequence already
  admitted in code: recovery's revision re-proof is deferred —
  `DesktopRuntimeBackend.kt:430` returns "; revision re-proof arrives with the engine twin".
  (Android rows 21-23; row 22's migration window hangs off this.)
- **portability:** portable — MariaDB ships Windows builds; process management + datadir init are
  portable JVM work.
- **parity plan:** Desktop twin package `com.pocketrealm.database`: `DatabaseEngine` twin launching
  a staged MariaDB (native Windows build, staged like the realm DLLs with a lockfile), sharing the
  already-shared `DatabaseGenerationSeal`/`DatabaseMigrationSeal`; add android-free
  `database/DatabaseDurableState.kt` (+`DatabaseDurability.kt` twin for fsync legs) to complete the
  migration-ledger re-proof in `recoverDatabase`. Provider setting in desktop `storage/Settings.kt`;
  `ServerRuntimeFiles` twin already knows SQLite-only — extend for the MariaDB `databaseInfo` shape
  per the shared contract. *(hunt-r1-lens1.md)*
- **effort:** XL — **severity:** P2

### DESK-19 — Migration ledger & seals + durable init/migration transactions
- **gap:** Desktop preflight only checks that the four datadir files exist
  (`DesktopRuntimeBackend.kt:109-123`), so a datadir seeded by an older app version is never
  refused for pending migrations. `DatabaseMigrationSeal` is shared-compiled but referenced by **0**
  desktop main sources (grep; its only Android user is `database/DatabaseEngine.kt`);
  `database/DatabaseDurableState.kt` is unshared with no desktop twin; `recoverDatabaseLocked`
  itself admits the hole: `"revision re-proof arrives with the engine twin"`
  (`DesktopRuntimeBackend.kt:430`). (Android #21/#23; Android normal start refuses
  incomplete/changed/pending-maintenance databases.)
- **portability:** portable — the seals are already shared sources.
- **parity plan:** Desktop engine-twin leg: `DesktopSqliteSeeder` writes the shared
  `DatabaseMigrationSeal` over the `DatabaseSqliteControlPlane` schema list after replay;
  `DesktopRuntimeBackend.preflight` + `recoverDatabaseLocked` verify it and refuse with the Android
  pending-maintenance copy; pin both paths with new `DesktopRuntimeBackendTest` legs.
  *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P1

### DESK-47 — MariaDB provider lane & migration-ledger twin
- **gap:** Desktop is SQLite-only by declaration: `ServerRuntimeFiles.kt:19-23,278-287`
  ("databaseInfo is SQLite-ONLY… no MariaDB branch or active-provider marker"), `recoverDatabase`'s
  revision re-proof is explicitly deferred (":430 note"). Android's default provider is MariaDB
  (ABI-selected 11.5.2 x86-glibc). Neither MariaDB nor a ledger is Windows-impossible — this is a
  lane decision, not an Android constraint; recorded so parity tracking doesn't lose it.
  (Android #21/#23.)
- **portability:** portable (not P1: the desktop lane is coherent and declared).
- **parity plan:** `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopMariaDbEngine.kt` twin
  of `DatabaseEngine.kt` (process-managed mysqld, datadir init/migration, durable seals behind
  shared `DatabaseConfigPolicy`/`DatabaseGenerationSeal`); `ServerRuntimeFiles.databaseInfo` grows
  the MariaDB branch + provider marker; the revision re-proof lands with it; the seal/migration
  suites are already shared. *(hunt-r1-lens3.md)*
- **effort:** XL — **severity:** P3

### DESK-53 — Migration-ledger / durable-state proof at start
- **gap:** `DatabaseMigrationSeal` + `DatabaseGenerationSeal` compile into the desktop
  (shared-sources.json:28-29) but grep of `desktop/src/main` finds **zero usages** (only the
  comment `DesktopSqliteSeeder.kt:38` name-dropping the Android marker concept). Desktop
  `preflight`/`startDatabase` verify only that the four sqlite files exist
  (`DesktopRuntimeBackend.kt:109-123`, `:595-603`), and `recoverDatabase` itself defers the missing
  leg (`:430`). Android refuses normal start on incomplete/changed/pending-maintenance databases —
  desktop silently boots a stale or partially-migrated datadir, e.g. seeds from an older app
  version.
- **portability:** portable.
- **parity plan:** Have `DesktopSqliteSeeder` write the shared seal bytes next to its generation
  marker; verify `DatabaseMigrationSeal` in `preflight` and `startDatabase`, refusing with a
  distinct code (`DB-MIGRATION-PENDING` vs `DB-NOT-SEEDED`) before the DB component reports READY.
  Extend `DesktopSqliteSeederTest` + a `DesktopRuntimeBackendTest` preflight leg with a stale-seal
  fixture. *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P1

### DESK-78 — MariaDB engine lane — Android's primary provider
- **gap:** Android's shipped engine is MariaDB (provider ids arm64-bionic 12.3.2 / x86-glibc
  11.5.2, `DatabaseEngine.kt:13-52`); desktop is SQLite-only by construction — the Windows native
  lane is built `-DSQLITE=ON` fail-loud and ServerRuntimeFiles is SQLite-only
  (desktop-inventory lines 50, 73 declare it "absent by design"). The primary Android engine lane
  and its lifecycle (DatabaseService/DurableState twins) have no desktop counterpart.
  (Android row 21.)
- **portability:** portable (MariaDB runs natively on Windows) — but a deliberate lane decision.
- **parity plan:** Long lane: add a MariaDB backend variant to `tools/build_win_realm_runtime.py`,
  twin DatabaseEngine/DatabaseDurableState lifecycle in `desktop/.../database/`, add a
  DatabaseService twin. Until then keep the declared SQLite-only posture. *(hunt-r1-lens5.md)*
- **effort:** XL — **severity:** P3

### DESK-79 — Migration-ledger & generation-seal re-proof in recovery
- **gap:** `DatabaseMigrationSeal.kt`/`DatabaseGenerationSeal.kt` are shared but referenced by
  **zero** desktop main sources, and desktop recovery's own note admits the meta migration-ledger
  re-proof "arrives with the engine twin" (`DesktopRuntimeBackend.kt:430` detail; desktop-inventory
  line 49). A desktop datadir can pass recovery without proving it carries the APK's complete
  migration set — Android refuses such databases. (Android row 23.)
- **portability:** portable.
- **parity plan:** Wire shared DatabaseMigrationSeal/DatabaseGenerationSeal into
  `DesktopSqliteSeeder` (stamp at seed) and `recoverDatabaseLocked` (`:351`, re-proof before the
  integrity gate); extend seeder/recovery tests with a tampered-ledger refusal.
  *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-98 — Prepare-for-start state machine + migration seals
- **gap:** `supervisor/DatabaseStartPreparation.kt`, `database/DatabaseMigrationSeal.kt`,
  `database/DatabaseGenerationSeal.kt` are all compiled on desktop (shared-sources.json:27,28,64)
  with green tests, but grep over desktop main returns **0 references**. Desktop `startDatabase`
  (`DesktopRuntimeBackend.kt:595-603`) only checks the four `.sqlite3` files exist, and
  `recoverDatabaseLocked` ends with the confession `"revision re-proof arrives with the engine
  twin"` (`:430`). Android's `AndroidRuntimeBackend` drives the full
  `DatabaseStartPreparation.next(status)` machine (`:541-552`: ROLLBACK_PENDING_RESTORE /
  RESUME_INITIALIZATION / APPLY_PINNED_MIGRATIONS / RECOVER_DIRTY_GENERATION) and normal start
  refuses incomplete/changed/pending-maintenance databases. (Android #21/#23.)
- **portability:** portable.
- **parity plan:** Fold `DatabaseStartPreparation.next` into `startDatabase`/`recoverDatabaseLocked`:
  persist the seed revision + migration-seal digest in the generation marker `DesktopSqliteSeeder`
  already writes, and re-prove it at start (replace the `:430` revisionNote stub with the real leg);
  refuse INITIALIZATION/MIGRATIONS states with honest copy like Android. Add
  `DesktopRuntimeBackendTest` legs: stale-revision datadir refuses; matching revision starts.
  *(hunt-r1-lens6.md)*
- **effort:** M — **severity:** P1

### DESK-106 — MariaDB provider lane + dual-provider window
- **gap:** Desktop `ServerRuntimeFiles.kt:18-22,278-283` documents SQLite as "the only provider in
  the Windows v1 lane"; `DesktopRuntimeBackend` DATABASE is an in-process SQLite materialization.
  The desktop cannot own a MariaDB datadir, and the MariaDB→SQLite export leg of the shared
  `DatabaseUserStateBridge` (compiled on desktop, partially used by `DesktopSqliteSeeder.kt:9,108`)
  has no MariaDB side to export from. MariaDB ships native Windows binaries, so this is a lane
  decision, not a platform impossibility. (Android rows #21/#22.)
- **portability:** portable.
- **parity plan:** Long lane: stage `mariadbd.exe` (pinned version like the Android ABI-selected
  ids), add a DATABASE provider mode to `DesktopRuntimeBackend` (daemon process +
  `DatabaseConfigPolicy`/`DatabaseRuntimeContract` contracts, both already shared), a
  `databaseInfo` socket branch in `desktop/ServerRuntimeFiles` mirroring the Android form, and
  provider selection in `DesktopSettingsStore`; keep the SQLite seeder as the migration source via
  the shared bridge. Extend `DesktopServerRuntimeFilesTest` for the provider marker.
  *(hunt-r1-lens6.md)*
- **effort:** XL — **severity:** P3

### DESK-108 — In-app database initialization (the INITIALIZE leg)
- **gap:** The desktop datadir can only be created by a dev-checkout gradle task. Every unseeded
  refusal instructs it: "run gradlew seedRealmData from desktop/"
  (`DesktopRuntimeBackend.kt:118, 384, 600`), and the only seeding entry point resolves a
  repo-relative staging root — `SeedRealmData.kt:26` defaults to
  `../native/.build-o09-x86_64/realm-staging-sqlite`. Meanwhile the packaged jpackage image SHIPS
  the four seed transcripts and their pins file (`ls desktop/build/package/PocketRealm/app/`:
  `classiccharacters.sqlz`, `classiclogs.sqlz`, `classicmangos.sqlz`, `classicrealmd.sqlz`,
  `BUILD_PROVENANCE.json`), and no desktop main source reads them at runtime. A fresh packaged
  install can therefore never leave `DB-NOT-SEEDED` — the app is a brick for non-developers even
  though it carries everything it needs to bootstrap. (Android performs INITIALIZE in-app through
  the shared `DatabaseStartPreparation` (`supervisor/DatabaseStartPreparation.kt:10-19`) and the
  engine's first boot, Android #21/#22.)
- **portability:** portable — `DesktopSqliteSeeder` already enforces the pins + integrity gates;
  only the runtime path resolution and call site are missing.
- **parity plan:** Runtime bootstrap leg: when `DesktopRuntimeBackend.preflight` finds the datadir
  unseeded, resolve the four transcripts + BUILD_PROVENANCE.json from the app dir
  (java.library.path / `$APPDIR`, like `ServerRuntimeFiles.resolveBundledAsset`) and run
  `DesktopSqliteSeeder.seedAll`; rewrite the refusal copy for the truly-missing case; add an
  unseeded→bootstrap→start `DesktopRuntimeBackendTest` leg. *(hunt-r2-lens2.md)*
- **effort:** M — **severity:** P1

### Area 5 — Database backups & restore

### DESK-6 — Database backups & transactional restore verification
- **gap:** No user-facing backup/restore on desktop: `database/DatabaseSnapshotStore.kt` is
  android-free yet absent from shared-sources.json and has no desktop references; the desktop
  Diagnostics screen offers only storage health, journal line, log tail and support bundle
  (desktop-inventory line 63). No create/restore/verify/list/retainNewest, no flavor-lane snapshot
  pinning, no boot-the-stack-then-commit-or-rollback restore proof. (Android row 24.)
- **portability:** portable.
- **parity plan:** Add `database/DatabaseSnapshotStore.kt` to shared-sources.json; add
  create/restore/verify/list/retainNewest verbs to `DesktopRuntimeBackend` mirroring the
  AndroidRuntimeBackend maintenance ops (RealmService.kt:244-277 callers); restore drives
  db→realmd→world to world-ready before commit/rollback; add a Backups card to
  `desktop/.../ui/DiagnosticsScreen.kt`. Tests: snapshot round-trip + restore-rollback on the
  seeded datadir. *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P1

### DESK-18 — Backups & transactional restore verification
- **gap:** The desktop can only *repair* (`recoverDatabase`, `DesktopRuntimeBackend.kt:343-437`);
  it cannot create, list, or restore named snapshots and has no transactional restore. Greps found
  no `DatabaseSnapshotStore`/backup/restore reference anywhere in desktop main;
  `DesktopStorageRoots.kt:17-40` declares no snapshots root; `ui/DiagnosticsScreen.kt` offers only
  storage health, journal line, log tail, and support bundle. (Android #24.)
- **portability:** portable — `DatabaseSnapshotStore.kt` is JVM-pure (imports at :3-11 are
  org.json/java.io/java.nio only) and android-free; its `syncDirectory` default
  (`DatabaseDurability::syncDirectory`) is the one POSIX-flavored seam and is injectable.
- **parity plan:** Add `database/DatabaseSnapshotStore.kt` to `shared-sources.json` with a Windows
  `syncDirectory` injected at call sites; add a snapshots root under `database/` in
  `DesktopStorageRoots`; create/list/restore verbs in `DesktopAppModel` (reuse the stop order +
  `recover()`); restore/create buttons in `ui/DiagnosticsScreen.kt` mirroring Android
  DiagnosticsScreen:43-261; add a desktop store test. *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P1

### DESK-37 — Database snapshot store (create/restore/verify/list/retainNewest + flavor-lane pin)
- **gap:** Desktop has zero backup capability: grep of desktop main sources finds no
  snapshot/backup reference; desktop DiagnosticsScreen offers storage health, journal line, log
  tail, support bundle only; `recoverDatabase` (:343-487) repairs in place but never snapshots.
  `DatabaseSnapshotStore.kt` is android-free (0 refs) and NOT in shared-sources.json.
  (Android #24: `DatabaseSnapshotStore.kt:18-155`, `checkSnapshotFlavorMatchesLane` :152-155.)
- **portability:** portable — backups are named portable; fsync file copies work identically on
  NTFS (desktop already does `FileOutputStream.fd.sync()` in `ServerRuntimeFiles.secureWrite`).
- **parity plan:** Add `database/DatabaseSnapshotStore.kt` to shared-sources.json (its
  `DatabaseSnapshotStoreTest` + `DatabaseSnapshotFlavorShimTest` join shared_tests); backend verbs
  over the sqlite datadir with runtimes at rest (reuse recoverDatabase's at-rest guards); restore
  boots db+realmd+world to world-ready then commits or rolls back; DiagnosticsScreen backup card
  with named restore + retain-newest. *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P1

### DESK-100 — Backups & transactional restore
- **gap:** Desktop `ui/DiagnosticsScreen.kt` has exactly four cards — Storage roots (`:66`),
  Supervisor journal (`:89`), Support bundle (`:107`), Recent log (`:133`) — no backup/restore-newest
  anywhere, and there is no `DesktopSnapshotStore`. `recoverDatabase`
  (`DesktopRuntimeBackend.kt:343-487`) is repair, not snapshots; `DesktopSupportBundle` explicitly
  excludes databases. A corrupted datadir on desktop has no rollback point, where Android keeps
  named snapshots with retention. (Android #24.)
- **portability:** portable.
- **parity plan:** Create `desktop/src/main/kotlin/com/pocketrealm/database/DesktopSnapshotStore.kt`
  mirroring `DatabaseSnapshotStore` (fsync file copies via `FileChannel.force`, manifest + sha256
  like `DesktopSupportBundle`, `retainNewest`); verify a restored snapshot by reusing the existing
  `verifyOrRebuildSqlite`/`integrityOk` machinery (`DesktopRuntimeBackend.kt:441-487`); gate
  create/restore on "runtimes at rest" exactly like `recoverDatabaseLocked` (`:351-386`); add
  Named backup / Restore newest buttons to the Diagnostics journal card. Windows note: no directory
  fsync (same posture as `verifyOrRebuildSqlite:484-485`). *(hunt-r1-lens6.md)*
- **effort:** L — **severity:** P1

### Area 6 — Data preparation

### DESK-44 — In-app checkpointed CMaNGOS data preparation
- **gap:** Desktop data prep is a manual out-of-app step: `tools/win_prepare_data.py`
  (desktop-inventory line 51) — the app itself cannot prepare world data, it only refuses honestly
  until `active.json` exists. The Windows extractors are already staged
  (`native/bin/x64_Release/Extractors/{ad,vmap_extractor,vmap_assembler,MoveMapGen}.exe`) and
  publication already uses the shared `PreparedDataStore`. `DataPreparationStore.kt` has 7 android
  refs → twin, don't share. (Android #8: DataPreparationStore stages
  DBC_MAPS/VMAP_EXTRACT/VMAP_ASSEMBLE/MMAPS/MANIFEST.)
- **portability:** portable — data prep is named portable; the extractors are native Windows exes.
- **parity plan:** `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopDataPreparation.kt`
  driving the staged Extractors with win_prepare_data.py's validation-based resumability and
  per-map mmap coverage check, publishing generations + atomic `active.json` through shared
  `PreparedDataStore.kt`; Settings/Diagnostics progress card; JVM test with a stub-extractor seam
  pins stage order + resume. *(hunt-r1-lens3.md)*
- **effort:** L — **severity:** P2

### DESK-69 — Checkpointed in-app DBC/maps/vmaps/mmaps generation
- **gap:** On desktop, data preparation is an external tool (`tools/win_prepare_data.py`), not app
  functionality: `importer/DataPreparationStore.kt` (Context/Build/Os refs at lines 3-6,77,444,452)
  has no JVM twin and no desktop UI drives extraction; the app only consumes the shared
  `PreparedDataStore` `active.json` and refuses ("Server world data is not ready") until a
  tool-published generation exists. (Android row 8.)
- **portability:** portable (extractors ship as Windows exes).
- **parity plan:** Twin `importer/DataPreparationStore.kt` in `desktop/.../importer/`
  (Context→roots, Os.fsync→FileChannel.force) driving ProcessBuilder over
  `bin/x64_Release/Extractors/*.exe` with the same stage set; add a Settings 'Prepare world data'
  flow; ImportModel/PreparedDataStore already shared. *(hunt-r1-lens5.md)*
- **effort:** L — **severity:** P2

### DESK-102 — In-app data preparation
- **gap:** On Android, `importer/DataPreparationStore.kt` runs the extractors in-app with
  resumable checkpoints (`:69,107,126,207,244,293`). On desktop the app only *consumes* a published
  generation (`ServerRuntimeFiles.kt:38-44` `requireActive()`; world refuses honestly until
  active), and the actual preparation lives outside the app in `tools/win_prepare_data.py`. The
  Windows extractor EXEs are already staged, so the app could own the lane end-to-end.
  (Android #8.)
- **portability:** portable.
- **parity plan:** Create `desktop/src/main/kotlin/com/pocketrealm/desktop/DataPreparationHost.kt`:
  ProcessBuilder over the staged EXEs with the shared stage model (DBC_MAPS → VMAP_EXTRACT →
  VMAP_ASSEMBLE → MMAPS → MANIFEST), per-map mmap coverage check, validation-based resumability,
  then publish through the already-shared `PreparedDataStore` (`active.json` + sha256 manifest) so
  `ServerRuntimeFiles.requireActive()` lights up without python. Progress card on Home; identity
  gate mirrors Android's flavor-manifest check (DESK-62/93's registry). *(hunt-r1-lens6.md)*
- **effort:** L — **severity:** P2

### DESK-128 — Per-flavor prepared-data pointer namespace & manifest identity on Windows
- **gap:** Android's in-app publication is per-flavor: "per-flavor pointer namespace and manifest
  identity gate so vanilla can never authenticate onto a TBC generation" (inventory row 8), and
  shared `PreparedDataStore` resolves the canonical `active-<flavorId>.json` first, falling back to
  the un-namespaced `active.json` only as LEGACY adoption (`PreparedDataStore.kt:87-98`), with the
  manifest flavor an OPTIONAL alias resolved via `cmangosFamily`/`clientBuild`
  (`validateDataManifestEnvelope`, :131-140). The desktop tool permanently rides that legacy
  fallback: `win_prepare_data.py:333` writes ONLY `active.json` (never `active-<flavorId>.json`)
  and its manifest (:314-323) carries `clientBuild: 5875` / `cmangosFamily: "classic"` but **no
  flavor alias**. Today (vanilla-only) it works and the clientBuild gate still fail-closes
  cross-flavor auth — but the desktop can never hold two flavors' data lanes (one shared pointer,
  one publication overwrites the lane), and the per-flavor namespace discipline Android guarantees
  is structurally absent from the Windows lane. Distinct from the registered in-app-data-prep
  absences (DESK-44/69/102 = the missing app lane) and DESK-93 (conf/db-name routing): this is the
  publication FORMAT of the tool the desktop lane actually uses.
- **portability:** portable.
- **parity plan:** Extend `tools/win_prepare_data.py` with a `--flavor` argument (default
  `vanilla`) that writes the canonical `active-<flavorId>.json` (keep the legacy `active.json`
  mirror for one transition release) and adds the `"flavor"` alias key to `data-manifest.json`;
  the shared `PreparedDataStore` (already compiled desktop-side, ServerRuntimeFiles.kt:38) then
  resolves canonically with no Java changes. Test: desktop leg over the shared
  `PreparedDataStoreTest` covering canonical-pointer resolution + a two-flavor coexistence case.
  *(hunt-r3-lens4.md)*
- **effort:** M — **severity:** P2

### Area 7 — Realm data archive (move characters between installs)

### DESK-7 — Realm data archive (characters+credentials ZIP)
- **gap:** Absent on desktop: `ui/RealmDataArchive.kt` is android-free (`java.io` imports only)
  but unshared and unreferenced; desktop SettingsScreen has no export/inspect/restore section
  (no grep hits for archive/RealmDataArchive in `desktop/src`). (Android row 25.)
- **portability:** portable.
- **parity plan:** Add `ui/RealmDataArchive.kt` to shared-sources.json; add the export/inspect/
  restore card to `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt` mirroring
  Android SettingsScreen.kt:160-226/1475-1512; paths from `DesktopStorageRoots`. Tests: ZIP
  round-trip (export → inspect → restore) against the seeded datadir on a temp root.
  *(hunt-r1-lens1.md)*
- **effort:** S — **severity:** P2

### DESK-33 — Realm data archive export/inspect/restore
- **gap:** Android exports one stopped-state plain-ZIP (snapshot + paired credentials + manifest)
  to move characters between installs; the desktop has no equivalent. `RealmDataArchive.kt` is
  JVM-pure (imports at :3-9: org.json + java.util.zip) and android-free, but unshared with **0**
  desktop references — desktop installs cannot move characters to another install, including to an
  Android device. (Android #25.)
- **portability:** portable.
- **parity plan:** Add `ui/RealmDataArchive.kt` to `shared-sources.json` (android-free verified);
  export/import buttons in desktop Settings or Diagnostics wired through `DesktopAppModel`
  (graceful stop → export; stop → import → `recover()`); add a desktop round-trip test.
  *(hunt-r1-lens2.md)*
- **effort:** S — **severity:** P3

### DESK-38 — Realm data archive — export/inspect/restore single-file ZIP
- **gap:** Absent on desktop — `ui/RealmDataArchive.kt` is android-free (0 android/androidx refs,
  so the engine is shareable) and unshared; desktop SettingsScreen has no export/inspect/restore
  section (grep found no `RealmDataArchive` reference). (Android #25: `ui/RealmDataArchive.kt`.)
- **portability:** portable — plain-ZIP single file with manifest; ZIP + stopped-state snapshot
  are portable (depends on DESK-6/18/37/100's stop/snapshot leg).
- **parity plan:** Share `ui/RealmDataArchive.kt` via shared-sources.json; desktop SettingsScreen
  section driving export/inspect/restore against the stopped sqlite datadir through
  DesktopAppModel; `RealmDataArchiveTest` + `RealmDataArchiveCredentialsTest` join shared_tests.
  *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P2

### DESK-60 — Realm data archive
- **gap:** Android exports/inspects/restores a plain ZIP of one stopped-state snapshot + paired
  credentials + manifest (`ui/RealmDataArchive.kt:4-14`; Settings UI); desktop has no equivalent
  (grep backup/archive: none) and `DesktopStorageRoots` defines no export root. The feature's whole
  point — moving characters **between installs** — is impossible from desktop in either direction.
  (Android #25.)
- **portability:** portable.
- **parity plan:** Add an `export` root to `DesktopStorageRoots`; share `ui/RealmDataArchive.kt`
  via `shared-sources.json` (verified android-free: `org.json`/`java.util.zip` imports only) and
  surface export/inspect/restore in desktop Diagnostics with the supervisor stopped-state gate;
  run the Android archive tests desktop-side. *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P3

### DESK-101 — Realm data archive
- **gap:** Desktop `SettingsScreen.kt` has five cards (AI-bot chat `:58`, world debug logs `:80`,
  auto-login/account `:99`, updates `:124`, about `:148`) — no export/inspect/restore of realm
  data, and no `RealmDataArchive` twin anywhere in desktop main. Characters cannot move between a
  desktop install and another machine/install. (Android #25.)
- **portability:** portable.
- **parity plan:** Create `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRealmDataArchive.kt`
  as the JVM twin of `ui/RealmDataArchive.kt` (java.util.zip, same manifest shape so archives are
  cross-compatible with Android): export = require the at-rest proof from `recoverDatabaseLocked`
  (`:351-386`) then zip the 4 datadir files + user-account JSON; restore = reverse through
  `secureWrite`-style atomic replace + integrity gate. Surface Export/Inspect/Restore cards in
  `SettingsScreen.kt` (java.awt FileDialog save/load like `BotsScreen.kt:382`).
  *(hunt-r1-lens6.md)*
- **effort:** M — **severity:** P3

### Area 8 — Bots

### DESK-8 — Bot admission control & automatic load protection
- **gap:** `bots/BotAdmissionController.kt` is shared and compiled, but nothing on desktop ever
  constructs it (grep `BotAdmission` in `desktop/src` → 0 hits). Android runs the loop inside
  `server/WorldRuntimeService.kt:511-543` fed by `bots/BotResourceSampler.kt` (thermal, RSS,
  storage). The desktop world (in-process DLL) therefore grows bots unthrottled — no tick-p99/
  memory stall pause, no target lowering with cooldown recovery. (Android row 27.)
- **portability:** portable.
- **parity plan:** JVM twin `bots/BotResourceSampler` under `desktop/src/main/kotlin/com/pocketrealm/bots/`
  (process RSS/CPU via `OperatingSystemMXBean`, disk via `File.getUsableSpace`, tick p99 from the
  world status surface — no thermal, report NORMAL); run the shared controller loop in
  `DesktopRuntimeBackend`'s world supervision mirroring WorldRuntimeService.kt:511-543 and feed
  decisions into the bots conf / READY budget. Tests: sampler + decision cycle with synthetic
  samples (pattern of the shared BotPolicyTest). *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-23 — Bot admission control & automatic load protection
- **gap:** `BotAdmissionController` is shared-compiled but its only Android driver is
  `server/WorldRuntimeService.kt` (grep); the desktop backend starts world with a fixed target
  (`DesktopRuntimeBackend.kt:695-726`) and never samples anything, so a Busy-600 profile just
  grinds a weak PC with no automatic protection. `BotResourceSampler` is unshared (Android /proc
  RSS + thermal), so the loop has no desktop input side. (Android #27.)
- **portability:** portable — tick/memory/storage sampling has JVM equivalents; thermal is the
  android-specific input and can be a constant.
- **parity plan:** JVM sampler twin in `desktop/src/main/kotlin/com/pocketrealm/bots/`
  (OperatingSystemMXBean + `File.getUsableSpace`; tick p99 from world status); drive the shared
  controller from a `DesktopAppModel` loop while world is READY, adjusting the target through the
  existing `WorldNative` verbs; unit-test with a fake sampler. *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P2

### DESK-43 — Bot admission control & automatic load protection (sampling)
- **gap:** `BotAdmissionController` IS shared (shared-sources.json line 16) and its suite runs
  green on desktop, but nothing on desktop instantiates it: its only consumer is Android's
  `server/WorldRuntimeService.kt:511`. The desktop world lifecycle resolves the bot profile
  (`DesktopRuntimeBackend.kt:90-104`) with no admission feedback — sustained load protection
  (pause/lower-target/cooldown) is absent. (Android #27: samples world tick p99/stalls/memory/
  storage/RSS.)
- **portability:** portable — tick/memory/storage sampling are JVM-doable in-process; only the
  thermal-level input is a phone-ism (feed a constant/plugged-in level, as the desktop already
  does for `ChatterPowerMonitor`).
- **parity plan:** `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopBotAdmission.kt` running
  the shared controller during world lifetime: sample world status (`WorldNative` status verbs) +
  process memory + datadir free space into `BotResourceSample`; admission-lowered target feeds
  `resolveLaunchProfile` at next start; BotsScreen shows admission state. JVM test with synthetic
  samples. *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P2

### DESK-99 — Bot admission control (sampler leg)
- **gap:** `bots/BotAdmissionController.kt` is compiled on desktop (shared-sources.json:16) with
  green tests, but desktop main references it **zero** times. Desktop boots bots with the
  bot-aware 590s READY budget (`DesktopRuntimeBackend.kt:86-107`) and then never samples anything
  — bot population growth is unguarded, where Android pauses growth/lowers targets under load.
  (Android #27.)
- **portability:** portable.
- **parity plan:** Create
  `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopBotResourceSampler.kt` (JVM
  `OperatingSystemMXBean` process CPU/memory + `File.getUsableSpace` + world status JSON from
  `WorldNative.statusNative()`) feeding the shared `BotAdmissionController` on the observe cadence;
  surface the admission verdict in `BotsScreen` and stop issuing admission slots when the
  controller pauses growth (the world-side knob rides the next `PocketRealm.BotTarget` conf,
  `ServerRuntimeFiles.kt:149`). Unit-test with fixed samples like `BotPolicyTest`.
  *(hunt-r1-lens6.md)*
- **effort:** M — **severity:** P2

### DESK-116 — Live bot-profile application and bot-target retarget while the world runs
- **gap:** The shared `WorldNative` shim exposes `setBotTargetNative`, the admission-generation
  handshake (`beginAdmissionBotTargetGenerationNative`/`setAdmissionBotTargetNative`/
  `retireAdmissionBotTargetGenerationNative`), `botStatusNative`, `performanceStatusNative`,
  `pauseWorldNative`/`isWorldPausedNative` (server/WorldNative.kt:6-11, 32-33), and Android drives
  them as live AIDL ops `startBotProfile`/`setBotTarget` (inventory row 20). On desktop these are
  called ONLY by dev harnesses (`RpHost.kt:141-145, 285-289`, `WhisperGate.kt:93`) — the app path
  has no verb: the shared `RuntimeBackend` interface has no bot/pause members
  (RuntimeContracts.kt:11-48), `DesktopAppModel` has no wrapper, and the Bots UI applies only
  "into the NEXT world start". A bot-population change on Android can apply live; on desktop it
  always costs a full world restart (a 110–590 s READY wait). Distinct from the admission-control
  gaps (DESK-8/23/43/99 = the sampler/controller logic) and from DESK-77.
- **portability:** portable.
- **parity plan:** Add `applyBotProfile`/`setBotTarget` verbs to the desktop backend +
  DesktopAppModel mirroring WorldRuntimeService.startBotProfile/setBotTarget: the begin/set/retire
  admission-generation handshake already proven in RpHost.kt:285-289, guarded by world READY +
  owner check like provisionAccount (`DesktopRuntimeBackend.kt:273-330`); wire a Bots "Apply now"
  action enabled at world READY alongside the existing next-start path. Test: a NativeGate-gated
  retarget handshake leg (pattern: NativeCoLoadTest). *(hunt-r2-lens4.md)*
- **effort:** M — **severity:** P2

### DESK-118 — Advanced-tuning editing and the advanced (non-preset) resolution branch
- **gap:** `DesktopRuntimeBackend.resolveBotProfile` always resolves with `advancedEnabled = false`
  and default `BotAdvancedSettings` (`DesktopRuntimeBackend.kt:98-105`), and the desktop Bots UI
  has no advanced-tuning controls (`grep -c "dvanced"` in `ui/BotsScreen.kt` = 1 comment;
  `BotsScreenSections.kt` = 0). Android's editor carries per-tab "Advanced tuning" disclosures
  (inventory row 28, BotsScreen.kt:974-989) and the shared `BotSelection.resolve` has an explicit
  advanced branch — `BotSelection.kt:49-55` (`if (advancedEnabled) BotProfiles.advanced(...)`) —
  which desktop can never reach. Saved presets are unaffected (preset branch ignores the advanced
  args, BotSelection.kt:26-35), so the silent divergence is precisely the advanced-without-preset
  flow: an Android user's legacy adv4 tuning has no desktop path.
- **portability:** portable.
- **parity plan:** Port the advanced-tuning disclosures into `ui/BotsScreenSections.kt`
  (Population/Basics tabs) editing a `BotAdvancedSettings` draft, and change `resolveBotProfile` to
  pass the real `advancedEnabled`/`advanced` from settings/preset instead of the hard-coded false
  (`DesktopRuntimeBackend.kt:100-104`); the shared `BotPolicyTest`/`BotProfilesCatalogTest`
  (already in shared_tests) pin the resolution. *(hunt-r2-lens4.md)*
- **effort:** M — **severity:** P3

### DESK-122 — Pause/resume the running world
- **gap:** Android's WorldRuntimeService exposes `setWorldPaused(paused: Int)` as a fixed op —
  `WorldNative.pauseWorldNative(...)` with a lifecycle record ("pause"/"resume") and a status
  operation (`android/.../server/WorldRuntimeService.kt:262-267`), and checks
  `WorldNative.isWorldPausedNative()` at `:553`. The shared `server/WorldNative.kt` shim (compiled
  straight into desktop per shared-sources.json:62) carries both JNI functions
  (`WorldNative.kt:32-33`), but desktop main references `pauseWorld|isWorldPaused` **zero** times
  — no backend verb, no UI. The desktop can only stop (save & exit) or force-stop a world.
  (Android #20's `:world` service op set.)
- **portability:** portable.
- **parity plan:** Add `pauseWorld(owner)`/`resumeWorld(owner)` verbs to
  `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt` mirroring the Android
  op: transition-lock, call the already-compiled `WorldNative.pauseWorldNative`, write the
  "pause"/"resume" lifecycle record via `ServerRuntimeFiles.writeLifecycle` (`:270-276`), return
  honest rc results; expose a Pause/Resume button on the HomeScreen RealmControlCard
  (`ui/HomeScreen.kt:150-229`) gated on world READY. `DesktopRuntimeBackendTest` needs no DLLs for
  the refusal legs (world-not-ready); the happy path rides the existing native-lane gates.
  *(hunt-r2-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-123 — startBotProfile / setBotTarget ops on a running world
- **gap:** Android's WorldRuntimeService carries `startBotProfile(profileId)` /
  `startBotProfileAt(...)` as fixed ops (`WorldRuntimeService.kt:166-172`) plus `setBotTarget`, so
  a running world's bot population can be re-targeted mid-session through the admission-generation
  fence. On desktop the app's contract is "selection rides settings into the NEXT world start
  (apply never mutates a running world)" — `DesktopRuntimeBackend` resolves the profile only at
  conf time (`ServerRuntimeFiles.kt:148-149` `PocketRealm.BotTarget`). Yet the exact JNI mechanics
  already work in-process on desktop: `RpHost.kt:283-289` registers an admission generation and
  sets the target via `beginAdmissionBotTargetGenerationNative` + `setAdmissionBotTargetNative`.
  The app model/UI simply never call them. (Android #20.)
- **portability:** portable.
- **parity plan:** Add `setBotTarget(owner, target)` and `startBotProfile(owner, profileId)` to
  `DesktopRuntimeBackend` using the proven RpHost sequence (generation fence + native verbs, honest
  refusal when world not READY/owned), then wire an "Apply to running world" action in
  `ui/BotsScreen.kt` next to the existing selection card; record the operation via
  `writeLifecycle`. Refusal legs unit-test without DLLs; happy path covered by the rpHost task
  lane. *(hunt-r2-lens6.md)*
- **effort:** M — **severity:** P3

### DESK-124 — Launcher-level advanced bot tuning overrides
- **gap:** Android persists launcher-level advanced tuning in settings — `storage/Settings.kt:366-367`
  (`botAdvancedEnabled: Boolean`, `botAdvanced: BotAdvancedSettings`), loaded at `:779-780`,
  written at `:1036-1041` (nearby limit/radius, login/maintenance batches, update interval), and
  surfaces it as per-tab "Advanced tuning" disclosures in BotsScreen (android-inventory #28,
  `BotsScreen.kt:106-109,974-989`), which flow into bot resolution. Desktop: `storage/Settings.kt`
  (91 lines) has **no** botAdvanced fields (grep = 0); `DesktopRuntimeBackend.resolveBotProfile`
  hard-codes `advancedEnabled = false, advanced = BotAdvancedSettings.fromProfile(defaultProfile)`
  (`:100-104`); and `ui/BotsScreen.kt:36` imports `BotAdvancedSettings` without ever using it
  (dead import). The per-PRESET custom config editor IS present (`BotsScreenSections.kt:470-520`
  edits `BotCustomConfiguration`), so the gap is specifically the launch-time advanced override
  lane. (Android #26/#28.)
- **portability:** portable.
- **parity plan:** Add `botAdvancedEnabled`/`botAdvanced` to
  `desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt` Snapshot + JSON codec (default
  off/default profile, byte-stable for existing files like the account-flavor precedent), render
  the per-tab "Advanced tuning" disclosures in `ui/BotsScreenSections.kt` (reuse the shared
  `BotAdvancedSettings` model already imported at `BotsScreen.kt:36`), and pass the stored values
  through `DesktopRuntimeBackend.resolveBotProfile` (`:98-106`) instead of the hard-coded false.
  *(hunt-r2-lens6.md)*
- **effort:** M — **severity:** P2

### Area 9 — World runtime control

### DESK-25 — World runtime restart cycles
- **gap:** After Save & exit (or any world stop), a second world start in the same JVM is refused
  WRONG_STATE — pinned deliberately: "a SECOND world start in the same process must be REFUSED
  honestly with the WRONG_STATE in-process-restart error (the pinned v1 contract — the embedded
  cmangos lane cannot re-initialize in place)" (`desktop/.../BootWorld.kt:22-28`,
  `-DbootCycles=2` gate). Restarting the realm means quitting and relaunching the whole app, which
  Android never asks of the user. (Android #20: start/stop/start across a session via the `:world`
  process.)
- **portability:** portable (architectural) — the constraint is the in-process JNI-DLL choice, not
  Windows itself; a child-process lane reproduces Android's `:realm`/`:world` topology.
- **parity plan:** Short term: detect the spent-world state in `DesktopAppModel` and offer an
  app-relaunch action that respawns the jpackage image with the same roots. Structural: move
  realm/world natives into a supervised child JVM process so lifetimes are recyclable; keep the
  BootWorld in-process pin for the embedded lane. *(hunt-r1-lens2.md)*
- **effort:** L — **severity:** P2

### DESK-77 — characterPersistence / worldChat / resetState / llmMemoryState app verbs
- **gap:** Android's world service exposes these ops to the app (WorldRuntimeService.kt:227-247).
  On desktop the WorldNative externals exist and are called **only from gate/harness mains**
  (`WhisperGate.kt:98-118`, `RpHost.kt:125-134`); `characterPersistence` appears nowhere in desktop
  sources (grep), and none of these are reachable from the app UI/backend — DesktopRuntimeBackend
  implements only accounts/gm/companionMode/saveWorld (fun list :245-330). (Android row 20.)
- **portability:** portable.
- **parity plan:** Add DesktopRuntimeBackend verbs over existing WorldNative externals
  (characterPersistenceNative/resetStateNative/llmMemoryStateNative/worldChatNative) and surface
  them from DesktopAppModel like Android's Home/Bots UI; pin with DesktopRuntimeBackendTest.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-117 — Companion-mode enter/exit (RUNNING→PAUSED world pause)
- **gap:** The shared supervisor exposes `setCompanionMode(enabled)` as a real phase operation
  RUNNING→PAUSED with durable "companion-entered"/"companion-resumed" journal actions and a
  PAUSED-phase state machine (DurableRuntimeSupervisor.kt:184-219), the desktop backend implements
  the verb (`DesktopRuntimeBackend.kt:266-270` → `setCompanionModeNative`), but nothing on desktop
  can invoke it: `DesktopAppModel` has no wrapper (its verb set is
  startRealm/relaunchClient/saveAndExit/recover/forceStop, :79-109) and no UI references it (grep
  desktop = only the backend override). Android reaches it as a user-facing maintenance op
  ("companion-mode" → `supervisor.setCompanionMode`, RealmService.kt:241-242). So the entire
  PAUSED phase — and the world-pause capability behind it — is dead code on desktop.
- **portability:** portable.
- **parity plan:** Add `DesktopAppModel.setCompanionMode(enabled)` delegating to
  `supervisor.setCompanionMode` and a Home phase-card action (Pause world / Resume world) enabled
  at RUNNING/PAUSED exactly like the Android op's phase guards; the journal copy and phase machine
  come free from the shared supervisor. Test: DesktopSupervisorJournal-style leg asserting the
  PAUSED transition with the backend's `pauseWorldNative` stubbed via the existing native gate.
  *(hunt-r2-lens4.md)*
- **effort:** S — **severity:** P3

### Area 10 — LLM

### DESK-9 — Embedded on-device LLM lane (registry staging, download, engine, screen source)
- **gap:** Desktop LLM is external-endpoint only, explicitly documented:
  `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/LlmScreen.kt:29-30` ("the Android LlmScreen
  minus every embedded-model surface") and desktop-inventory line 72 ("absent by design"). Settings
  carry only external fields (`desktop/.../storage/Settings.kt:26-34`): no on-device source toggle,
  no model registry UI/staging, no resumable GGUF download, no embedded engine;
  `llm/LlmModelRegistry.kt`/`LlmPromptPack.kt` are shared but the registry has no desktop consumer
  for local models. (NPU/compute-mode placement is the android-specific part.) (Android rows 29, 31.)
- **portability:** portable.
- **parity plan:** Desktop model manager twin of `llm/LlmModelCoordinator.kt` + resumable
  sha256-pinned download twin of `LlmModelDownloadService` (plain HTTP, no FGS) under
  `desktop/src/main/kotlin/com/pocketrealm/llm/`; stage an embedded llama-server (or llama.cpp JNI
  twin of the Android runtime) like the realm DLLs; extend LlmScreen with source=on-device +
  advanced engine settings (decode threads/offload layers); `ServerRuntimeFiles.llmConfigOverrides`
  already emits the twin conf. CPU-only on Windows. *(hunt-r1-lens1.md)*
- **effort:** XL — **severity:** P1

### DESK-26 — LLM prompt pack — player-editable prompt blocks
- **gap:** Android's conf emission stages
  `LlmPromptPack.resolve(snapshot.llmPromptPackJson).serialize()` as `promptPackFile`
  (`android/.../server/ServerRuntimeFiles.kt:215-218`); the desktop twin's `llmConfigOverrides`
  never passes `promptPackFile` (`desktop/.../server/ServerRuntimeFiles.kt:189-204`), desktop
  `Settings` has no `llmPromptPackJson` field (`Settings.kt:26-34`), and the desktop
  `LlmRuntimePolicy.confBlockExternal` `promptPackFile` parameter is dead
  (`LlmRuntimePolicy.kt:124`). Shared `LlmPromptPack` compiles+tests green on desktop with **0**
  desktop main references — world LLM speech runs on native defaults with no player editing.
  (Android #29/#31.)
- **portability:** portable.
- **parity plan:** Add `llmPromptPackJson` to desktop `Settings` + `DesktopSettingsStore` mapping;
  stage the serialized pack in `ServerRuntimeFiles.llmConfigOverrides` and pass `promptPackFile`;
  add the pack editor (reuse shared `LlmPromptPack`) to `ui/LlmScreen.kt`; extend the
  `DesktopServerRuntimeFilesTest` golden body. *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P2

### DESK-28 — LLM on-device runtime — model registry, staging download, embedded server
- **gap:** Documented "absent by design" in the desktop inventory: no embedded llama-server, model
  manager, or NPU placement on Windows. Desktop `Settings` carries only external-endpoint fields
  (`Settings.kt:26-34`) and the shared `LlmModelRegistry` has **0** desktop main references —
  models can never run locally even though the registry's GGUF pins are platform-neutral.
  (Android #29.)
- **portability:** portable — model download/staging and a llama.cpp server are plain Windows
  work; only the NPU compute placement is android-specific.
- **parity plan:** Stage a Windows llama-server binary + GGUF dir in the jpackage image; desktop
  twin of the download service (resumable RANGE + sha256 from shared `LlmModelRegistry`) under
  `%LOCALAPPDATA%/PocketRealm/runtime/llm`; extend the desktop `LlmRuntimePolicy` twin with the
  embedded-lane conf block; keep compute-mode Android-only. *(hunt-r1-lens2.md)*
- **effort:** XL — **severity:** P2

### DESK-48 — Local embedded-model lane — GGUF staging + local inference sidecar
- **gap:** Desktop LLM is external-endpoint only (`LlmScreen.kt:29-30` documents the removal of
  every embedded-model surface). The shared `LlmModelRegistry.kt`/`LlmPromptPack.kt` (manifest
  lines 53-54, suites green) give model identities, but desktop can't stage a GGUF or serve it
  locally — a llama.cpp `llama-server.exe` sidecar exposing the OpenAI-compatible endpoint
  `ServerRuntimeFiles.llmConfigOverrides` already targets is feasible on Windows. (Android
  #29/#31; desktop is external-only by design.)
- **portability:** portable — CPU/GPU llama-server sidecar + digest-verified staging are portable;
  the NPU compute-mode option and battery-aware pacing are ANDROID-SPECIFIC and stay out of the
  desktop plan.
- **parity plan:** `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopLlmModelManager.kt`:
  stage the pinned GGUF from shared `LlmModelRegistry` (sha256 via `fs/FileDigests`), resumable
  download, then launch a bundled `llama-server.exe` sidecar whose URL feeds the existing
  external-endpoint settings; LlmScreen model card + "local sidecar (beta)" source; honest copy
  that NPU/compute-mode is Android-only. *(hunt-r1-lens3.md)*
- **effort:** L — **severity:** P3

### DESK-80 — On-device (embedded) LLM lane — coordinator, staging download, inference
- **gap:** Declared absent by design (desktop-inventory lines 58, 72): no embedded llama-server, no
  model coordinator, no staging download — `LlmScreen` is external-endpoint only. Only
  `LlmModelRegistry`/`LlmPromptPack` are shared; `LlmModelCoordinator.kt` (Context + okhttp refs)
  and `LlmModelDownloadService.kt` (14 refs, FGS) have no desktop twins. The CPU/inference part is
  portable — llama.cpp runs natively on Windows; only NPU placement is Android-specific.
  (Android row 29.)
- **portability:** portable (minus NPU compute-mode).
- **parity plan:** Twin `llm/LlmModelCoordinator.kt` (okhttp→java.net.http) and
  LlmModelDownloadService as a sha256-verified downloader; bundle/spawn a Windows llama-server and
  point the on-device lane of `LlmRuntimePolicy` at it. Keep NPU placement out (android-specific).
  *(hunt-r1-lens5.md)*
- **effort:** L — **severity:** P2

### DESK-81 — Battery-aware world-chatter power pacing
- **gap:** Reduced on desktop to a constant: "chatter power file always rung NORMAL (desktop =
  plugged in)" (desktop-inventory line 58). `ChatterPowerMonitor.kt` (8 android refs:
  BatteryManager/Connectivity/BroadcastReceiver) has no JVM twin — a Windows laptop on battery
  still gets NORMAL pacing. (Android row 30.)
- **portability:** portable (GetSystemPowerStatus).
- **parity plan:** Add `desktop/.../server/DesktopChatterPowerMonitor.kt` via JNA
  `GetSystemPowerStatus` emitting ChatterPowerMonitor's staged-file contract; wire from
  `ServerRuntimeFiles.llmConfigOverrides`; unit test with a fake power source. (Desktop
  `server/LlmRuntimePolicy.kt` twin verified present — the policy itself is covered.)
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-103 — Embedded on-device model lane + prompt packs
- **gap:** Desktop LLM is external-endpoint only (`LlmRuntimePolicy.kt` desktop twin imports only
  `LlmSamplingProfile`/`LlmTierProfile`; `LlmScreen.kt` exposes endpoint/model/API-key). The shared
  `llm/LlmModelRegistry.kt` and `llm/LlmPromptPack.kt` are compiled on desktop
  (shared-sources.json:53-54, green tests) with **zero desktop main references**. Consequence:
  Android can run bot banter fully offline; desktop banter requires a paid cloud endpoint.
  llama-server ships Windows binaries, so the embedded lane is not Android-specific hardware.
  (Android rows #29/#31.)
- **portability:** portable.
- **parity plan:** Desktop model-staging twin under `desktop/src/main/kotlin/com/pocketrealm/llm/`
  (download/resume + sha256 verify against `LlmModelRegistry` into `<root>/llm/models`), a
  llama-server.exe child-process lane (ProcessBuilder, health-probed endpoint) fed into
  `LlmRuntimePolicy.confBlockExternal`'s existing endpoint plumbing, and the prompt-pack file lane
  (`LlmPromptPack` is already compiled — only the staging/UI is missing); add the
  on-device/external source toggle to `ui/LlmScreen.kt` mirroring Android's `LlmScreen.kt:157-161`.
  *(hunt-r1-lens6.md)*
- **effort:** XL — **severity:** P2

### Area 11 — Add-ons

### DESK-10 — Add-on manager (recommended/my add-ons, browse catalog, GitHub-commit install, updates, rollback)
- **gap:** The whole addons package is absent on desktop: grep `Addon|addon` over
  `desktop/src/main/kotlin` hits only `RpWowClient.kt:153-174` (the CMaNGOS protocol addon block,
  not a manager). Eleven of fourteen `addons/*.kt` files are android-free (AddonModels,
  AddonArchiveValidator, AddonArchiveExtractor, AddonRegistryPublisher, AddonOperationToken,
  AddonPackageMaintenance, VoiceOverReleaseResolver, plus the AndroidPort set in DESK-11) yet none
  is shared; AddonRepository/AddonCatalog need Context twins; no Addons destination exists in
  Router.kt:9-17. (Android row 32.)
- **portability:** portable.
- **parity plan:** Share the seven android-free addons files via shared-sources.json; JVM twins of
  `AddonRepository.kt`/`AddonCatalog.kt` (Context → DesktopStorageRoots); new AddonsScreen route
  (recommended/my add-ons/browse/GitHub-commit install/rollback) as a CMP port of the Android
  screen; GitHub lane over java.net http. Build: manifest test stays green; port shared addon tests
  if present, else add validator/publisher round-trip tests. *(hunt-r1-lens1.md)*
- **effort:** L — **severity:** P1

### DESK-11 — Android Port built-in add-on, launch-boundary runtime projection, input safe mode, data migrations
- **gap:** No project-owned add-on ships on desktop and nothing projects add-ons into the client
  at the launch boundary: `startClient` (DesktopRuntimeBackend.kt:505-541) goes straight from
  realmlist projection to ProcessBuilder with no addon projection step; no `input_safe_mode`
  setting (desktop Settings.kt:16-34 has no such field); `AndroidPortBindingRepair.kt`/
  `LegacyControllerBindingRepair.kt`/`AndroidPortMigrator.kt` (all android-free) unshared — so the
  app-owned key surrogates and one-time data migrations never run on desktop. (Android rows 33-34.)
- **portability:** portable.
- **parity plan:** Share the five android-free AndroidPort files; JVM twin of
  `addons/AddonRuntimeProjector.kt` (Context → the configured client dir) staging the packaged
  `addons/android-port` asset (same pattern as the LLM asset staging in packageApp); call
  projection in `startClient` before spawn; add `inputSafeMode` to desktop Settings.kt +
  SettingsScreen card. Tests: projection/safe-mode + binding-repair codec round-trips (reuse
  Android codec tests by sharing the files). *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P1

### DESK-17 — Add-on manager + Android Port add-on + runtime projector & safe mode
- **gap:** Desktop has zero add-on capability. `desktop/src/main/kotlin/com/pocketrealm/desktop/Router.kt:8-27`
  defines exactly 5 routes (Home/Bots/LLM/Settings/Diagnostics) — no Addons destination; a
  desktop-wide grep for `AddonsScreen|AddonRepository` finds 0 references in `desktop/src/main/kotlin`;
  nothing projects any add-on into the client's `Interface/AddOns`. The nearby-use client leg
  (DESK-27/58) and any addon install/update/rollback must be done by hand. (Android #32/#33/#34.)
- **portability:** portable — 11 of 14 `addons/*` files are android-free (verified by grep:
  AddonModels, AddonArchiveValidator, AddonArchiveExtractor, AddonRegistryPublisher,
  AddonOperationToken, AddonPackageMaintenance, VoiceOverReleaseResolver, AndroidPortPackage,
  AndroidPortMigrator, AndroidPortBindingRepair, LegacyControllerBindingRepair); only
  AddonCatalog/AddonRepository/AddonRuntimeProjector need JVM twins.
- **parity plan:** Add the 11 android-free `addons/*` files to `desktop/shared-sources.json`;
  write desktop twins of AddonCatalog/AddonRepository/AddonRuntimeProjector targeting
  `<clientDir>/Interface/AddOns`; new `Route.Addons` +
  `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/AddonsScreen.kt` mirroring Android
  `ui/AddonsScreen.kt`; `tests/test_desktop_shared_manifest.py` must stay green.
  *(hunt-r1-lens2.md)*
- **effort:** L — **severity:** P1

### DESK-39 — Add-on manager: catalog, GitHub install, updates, rollback
- **gap:** No addon lane exists on desktop: Router.kt:20-26 defines exactly 5 routes — no Addons;
  grep shows no desktop reference to any `addons/*` type. Seven engine files are android-free and
  unshared: `AddonModels/AddonArchiveValidator/AddonArchiveExtractor/AddonRegistryPublisher/
  AddonOperationToken/AddonPackageMaintenance/VoiceOverReleaseResolver`; `AddonRepository.kt`
  (2 refs) and `AddonCatalog.kt` (1 ref) need JVM twins. Android ships 11 addon test suites, none
  shared yet. (Android #32: exact-40-char GitHub commit resolution, archive validation, two-file
  registry transaction with crash-recovered rollback, VoiceOver resolver.)
- **portability:** portable — HTTPS downloads and archive validation are JVM-native.
- **parity plan:** Share the seven android-free files via shared-sources.json; JVM twins
  `desktop/.../addons/DesktopAddonRepository.kt`/`AddonCatalog twin` on `java.net.http` (keep
  exact-40-char commit resolution + digest pins); new `Route.Addons` +
  `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/AddonsScreen.kt`; addon suites join
  shared_tests; targets the picked client's `Interface/AddOns`. *(hunt-r1-lens3.md)*
- **effort:** L — **severity:** P1

### DESK-40 — Android Port built-in add-on provisioning + runtime projector & input safe mode
- **gap:** Desktop never projects any add-on into the client folder: no projector reference in
  desktop main sources (grep); `RpWowClient.kt:153-174` fakes an addon block only at the SRP6
  protocol level. So the desktop client misses the project-owned add-on identity that Android
  re-verifies at every launch (`AddonRuntimeProjector.kt:246` toc re-verify), with no safe-mode
  equivalent. (Android #33 + #34: `AndroidPortPackage.kt`, `AddonRuntimeProjector.kt`,
  `input_safe_mode`.)
- **portability:** portable — projection is filesystem copy into the client dir; the safe-mode gate
  is a settings read. `AndroidPortPackage.kt` is android-free (0 refs); `AddonRuntimeProjector.kt`
  has 2 android refs (twin needed).
- **parity plan:** Share `addons/AndroidPortPackage.kt`; JVM twin
  `desktop/.../addons/DesktopAddonRuntimeProjector.kt` projecting the project-owned add-on into
  the picked client's `Interface/AddOns` in `startClient` before launch, honoring an
  `input_safe_mode` settings field with toc re-verification; test pins projection + safe-mode
  no-op + running-client refusal. *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P2

### DESK-54 — Add-on manager, Android Port package & runtime projector (flavor × addons)
- **gap:** `shared-sources.json` contains **zero** `addons/*` entries and `desktop/src/main` has
  zero `Addon` references: no catalog/GitHub installer, no built-in "Android Port" add-on, no
  launch-boundary `AddonRuntimeProjector`/input safe mode, no binding-repair codecs (inventory
  rows 32-34). Meanwhile desktop `mangosd.conf` pins `PocketRealm.NearbyInteract = 1` whose own
  comment says "The managed controller add-on may request one normal nearby loot/use action
  through its authenticated session" (`ServerRuntimeFiles.kt:141-147`) — the server knob is served
  to a client that can never have the add-on, so nearby-use is structurally dead on desktop while
  the conf claims it is enabled. When porting, the Android-side flavor seam must also be fixed:
  `AddonArchiveValidator.kt:18-32` hard-codes `acceptedInterfaces` 11200/100000 instead of
  `ClientFlavor.addonInterfaces` — a TBC/WotLK qualification would silently accept/reject the
  wrong add-ons.
- **portability:** portable.
- **parity plan:** Route `acceptedInterfaces` through `ClientFlavor.addonInterfaces` first, then
  share the android-free addon model/validator files via `shared-sources.json`; JVM twins for
  `AddonRegistryPublisher`/`AddonRuntimeProjector`/`AddonPackageMaintenance` over
  `DesktopStorageRoots.content`, with a consent-gated install/projection into the user's client
  `Interface\AddOns` at the `startClient` boundary (safe-mode setting included); re-verify the
  Android Port toc at projection per `AddonRuntimeProjector.kt:246`. New `Route.Addons` in
  `Router.kt`. Test: port the Android addon validator tests + a projector leg.
  *(hunt-r1-lens4.md)*
- **effort:** L — **severity:** P1

### DESK-82 — Add-on manager: catalog / GitHub install / validation / registry rollback
- **gap:** Entirely absent on desktop: grep `addon` over desktop main hits only RpWowClient's
  protocol anticheat block (RpWowClient.kt:153-174); no AddonsScreen, no install/update/rollback.
  Most of the stack is android-free and unshared (verified 0 refs each): AddonModels,
  AddonArchiveValidator, AddonArchiveExtractor, AddonRegistryPublisher, AddonOperationToken,
  AddonPackageMaintenance, VoiceOverReleaseResolver; `AddonRepository.kt` (Context + okhttp),
  `AddonCatalog.kt` (Context) need small twins. (Android row 32.)
- **portability:** portable.
- **parity plan:** Share the 7 android-free addons/* files via shared-sources.json; twin
  AddonRepository (okhttp→java.net.http) and AddonCatalog (Context→roots) in
  `desktop/.../addons/`; add an Addons route to Router.kt mirroring AddonsScreen; installs land
  via DESK-84 projection. *(hunt-r1-lens5.md)*
- **effort:** L — **severity:** P1

### DESK-83 — 'Android Port' built-in add-on & pre-0.6.0 device-data migration
- **gap:** No project-owned "Android Port" add-on ships on desktop and the one-time idempotent
  migration of pre-0.6.0 persisted data never runs there: `AndroidPortPackage`/
  `AndroidPortMigrator`/`AndroidPortBindingRepair`/`LegacyControllerBindingRepair` are unshared
  with no desktop twins, and nothing stages `assets/addons/android-port` into the client's
  `Interface/AddOns`. The add-on is the project-owned **controller** interface for Vanilla WoW
  1.12.1 (`AndroidPort.toc` line 3: "Project-owned controller interface for Vanilla WoW 1.12.1" —
  not touch), and its `Core.lua:879-886` wires `AP_NEARBY_INTERACT` on F7 — the client half of the
  nearby-use lane that desktop `ServerRuntimeFiles.kt:146-147` already emits
  (`PocketRealm.NearbyInteract = 1`) and that the portable entries DESK-27/58/89 plan to enable.
  The migrator handles the pre-0.6.0 `VanillaConsolePort` identity: registry remap + binding
  journal move, then saved-variable and bindings-cache rewrites under the client WTF tree, with an
  explicit ownership guard that never touches an unowned manual VanillaConsolePort install
  (`AndroidPortMigrator.kt:13-56`) — a manual install a Windows user can equally have.
  (Android row 33.)
- **portability:** portable — corrected by independent audit (was android-specific). The
  artifacts are android-free and the work is plain Windows JVM file/JSON handling:
  `grep -cE 'android\.|androidx\.|me\.zhanghai'` over the four files returns 0 each (run this
  session on `android/app/src/main/java/com/pocketrealm/addons/`); `AndroidPortMigrator.kt:3-11`
  imports only java.io/java.nio/org.json and operates on `File(root)` registry/journal JSON plus
  `File(clientRoot, "WTF")` saved variables and bindings-cache (`:151-159`, `:171-177`) — trees
  that exist identically on a Windows install; the add-on itself is a plain bundled Lua asset.
  This also resolves the register's earlier self-contradiction: DESK-11 already marked
  AndroidPortMigrator and the binding repairs portable, and DESK-40/95's plans say to share
  `AndroidPortPackage.kt` and bundle `assets/addons/android-port` on desktop.
- **parity plan:** Share the four android-free AndroidPort files via `shared-sources.json`; stage
  `android/app/src/main/assets/addons/android-port` like the LLM assets already staged by
  `ServerRuntimeFiles.stageBundledAsset`; run `AndroidPortMigrator`'s one-time migration and the
  binding-repair codecs as JVM twins over `%LOCALAPPDATA%\PocketRealm` and the configured client's
  WTF tree; project the add-on through DESK-40/84's runtime projector. *(hunt-r1-lens5.md;
  audit-corrected portability)*
- **effort:** S — **severity:** P3

### DESK-84 — Add-on runtime projector & input safe mode
- **gap:** No desktop twin: `AddonRuntimeProjector.kt` (Context/Os refs, verified :3-4) and the
  `input_safe_mode` setting (android storage/Settings.kt:880) have no desktop counterparts (desktop
  Settings has no safe-mode field — grep). Once addons exist on desktop (DESK-82), nothing controls
  which files enter the client folder at launch or can project none. (Android row 34:
  `AddonRuntimeProjector.kt:22-51,246` — applies only project-owned add-on folders at the launch
  boundary; safe mode projects none.)
- **portability:** portable.
- **parity plan:** Twin AddonRuntimeProjector in `desktop/.../addons/` (project into
  `<clientDir>/Interface/AddOns` before startClient, re-verify toc); add input_safe_mode to
  DesktopSettingsStore + a SettingsScreen card; test that safe mode projects none.
  *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P3

### DESK-95 — AddOn manager, project add-on, runtime projector
- **gap:** Desktop has no `addons` package at all (`find desktop/src -name "*.kt"`: packages are
  only `database, desktop, server, storage, supervisor`), no screen, and no Interface/AddOns
  staging. The server conf even references "the managed controller add-on may request one normal
  nearby loot/use action" (`desktop/.../server/ServerRuntimeFiles.kt:141-142`) and stages the
  `PocketRealm.NearbyInteract` knobs — but nothing on desktop ever projects that add-on into the
  client, so the nearby-use feature is configured server-side with no client-side counterpart. All
  of this is plain file work on Windows (a real `Interface/AddOns` folder). (Android #32-#34.)
- **portability:** portable.
- **parity plan:** Create `desktop/src/main/kotlin/com/pocketrealm/addons/` with JVM twins of
  `AddonRegistryPublisher`, `AddonArchiveValidator`, `AddonArchiveExtractor`,
  `AddonRuntimeProjector`, `AddonPackageMaintenance` against `DesktopStorageRoots` (publish under
  `<root>/client/Interface/AddOns`); add the android-free pure-data files (`AddonCatalog`,
  `AddonModels`, `AddonOperationToken`, `VoiceOverReleaseResolver`) to `desktop/shared-sources.json`
  (must keep `tests/test_desktop_shared_manifest.py` green); bundle the Android Port add-on from
  `android/app/src/main/assets/addons/android-port` like the LLM assets already staged by
  `ServerRuntimeFiles.stageBundledAsset` (`:244-268`); add an Addons route in
  `desktop/.../desktop/Router.kt` (6th rail entry) mirroring `ui/AddonsScreen.kt`.
  *(hunt-r1-lens6.md)*
- **effort:** L — **severity:** P1

### Area 12 — In-game settings

### DESK-12 — In-game settings engine & stock settings panels
- **gap:** Only the three WTF codecs are shared (`ingame/ConfigWtfCodec|SavedVariablesCodec|
  BindingsFileCodec` in shared-sources.json:50-52); the engine around them is absent —
  `InGameSettingsEditor` (Context), `InGameSettingsFiles`, `InGameSettingsEditLock`,
  `WowGameSettings`, `ManagedConfigPolicy` and the catalogs (`WowSettingModel`,
  `WowVanillaSettingsCatalog`, both android-free) are unshared, and grep
  `InGameSettings|WowGameSettings|ManagedConfigPolicy` over `desktop/src` → 0 hits. No desktop UI;
  the app-enforced Config.wtf overlay never applies. (Mitigation noted: desktop users can use
  WoW's real in-game UI, hence P2 not P1.) (Android rows 35-36.)
- **portability:** portable.
- **parity plan:** Share `ingame/InGameSettingsFiles.kt`, `InGameSettingsEditLock.kt`,
  `WowGameSettings.kt`, `ManagedConfigPolicy.kt`, `WowSettingModel.kt`,
  `WowVanillaSettingsCatalog.kt` (android-free); JVM twin of `InGameSettingsEditor.kt` (paths
  under the configured client dir/WTF; edit lock via file lock — desktop is single-process so the
  queue is trivial); CMP port of InGameSettingsScreens/InGameBindingsScreen as a Settings sub-flow;
  tests reuse the shared WtfCodecsTest patterns. *(hunt-r1-lens1.md)*
- **effort:** L — **severity:** P2

### DESK-22 — In-game settings engine + stock settings UI / bindings editor
- **gap:** `ConfigWtfCodec`/`SavedVariablesCodec`/`BindingsFileCodec` are shared-compiled but
  referenced by **0** desktop main sources (grep this session); `Router.kt:8-27` has no in-game
  settings route. Desktop users cannot edit graphics/sound/interface/bindings WTF state that
  Android edits through the engine. The android-free engine pieces (`InGameSettingsFiles`,
  `WowGameSettings`, `ManagedConfigPolicy`, `InGameSettingsEditLock`, `WowVanillaSettingsCatalog`,
  `WowVanillaBindingCatalog` — grep-verified) are unshared. (Android #35/#36.)
- **portability:** portable — WTF files are plain text; the desktop always edits-while-stopped,
  which is the engine's direct-edit lane.
- **parity plan:** Share the android-free ingame files via `shared-sources.json`; add a desktop
  route + `ui/InGameSettingsScreen.kt` doing direct-edit-while-stopped over the shared codecs,
  rooted at `<clientDir>/WTF`, with the bindings editor on the shared catalog.
  *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P2

### DESK-41 — WTF settings engine (edit lock, queued delivery, per-flavor layout) + stock panels & bindings editor
- **gap:** Desktop edits exactly one WTF file (`realmlist.wtf` via the SQLite row,
  `DesktopRuntimeBackend.kt:161-180`) — no Config.wtf overlay, no SavedVariables, no bindings
  editor. shared-sources.json carries only the three codecs (`ingame/BindingsFileCodec|
  ConfigWtfCodec|SavedVariablesCodec.kt`, lines 50-52); the engine (`InGameSettingsFiles.kt`,
  `InGameSettingsEditLock.kt`, `WowGameSettings.kt`, `ManagedConfigPolicy.kt`) and catalogs
  (`WowVanillaSettingsCatalog.kt`, `WowVanillaBindingCatalog.kt` — all android-free, 0 refs) are
  unshared; `InGameSettingsEditor.kt` (1 android ref, the DataStore queue) needs a twin; no desktop
  main source references `ingame` (grep). (Android #35 + #36; `WtfLayout` strategy seam selected
  per flavor — vanilla build-5875 layout frozen verbatim & byte-pinned, WotLK enters as a new
  strategy.)
- **portability:** portable — WTF files are plain text in the client folder.
- **parity plan:** Share the six android-free files via shared-sources.json; twin
  `desktop/.../ingame/DesktopInGameSettingsEditor.kt` (direct edit while stopped; while running,
  queue applied on clean stop, mirroring `InGameSettingsEditor.kt:4-10`);
  `desktop/ui/InGameSettingsScreen.kt` + bindings editor over the shared 211-entry binding catalog;
  `WtfCodecsTest` + `InGameSettingsFilesLayoutPinTest` ride shared_tests. *(hunt-r1-lens3.md)*
- **effort:** L — **severity:** P2

### DESK-59 — WTF settings engine & editor beyond the shared codecs
- **gap:** Only the three WTF codecs are shared (`ConfigWtfCodec`, `SavedVariablesCodec`,
  `BindingsFileCodec`); `InGameSettingsEditor`/`InGameSettingsFiles`/`InGameSettingsEditLock`/
  `WowGameSettings` and the per-flavor `WtfLayout` strategy have no desktop twins and no UI (grep
  `InGameSettings` in desktop main: zero). Android's contract — direct edit while stopped vs queued
  delivery while running, cross-process edit lock, stock build-5875 catalogs (inventory rows 35-36)
  — is only represented by DESK-50's overlay subset.
- **portability:** portable.
- **parity plan:** Share `ingame/WowSettingModel.kt`, `WowVanillaSettingsCatalog.kt`,
  `WowGameSettings.kt` + the vanilla `WtfLayout` strategy via the manifest (seam the file-IO bits
  if any are Android-bound — the manifest test will say); add a desktop Settings → In-game section
  driving the shared editor; the edit lock degenerates to the single-process case on desktop but
  keep the shared lock for contract parity. Catalogs are already JVM-tested on Android
  (integrity-hashed manifests). *(hunt-r1-lens4.md)*
- **effort:** L — **severity:** P2

### DESK-85 — In-game settings engine: WTF edit, lock, queued delivery
- **gap:** Desktop never edits game settings: grep `InGameSettings|ConfigWtf|WtfLayout|
  WowGameSettings|SavedVariables` over desktop main → **empty**. The codecs
  (ConfigWtfCodec/SavedVariablesCodec/BindingsFileCodec) are shared compile-only;
  `InGameSettingsFiles.kt`/`InGameSettingsEditLock.kt`/`WowGameSettings.kt`/
  `ManagedConfigPolicy.kt` are android-free (0 refs) and unshared; `InGameSettingsEditor.kt` has
  1 Context ref. No direct-edit/queued-delivery split, no cross-process edit lock, no app-enforced
  Config.wtf overlay. (Android row 35.)
- **portability:** portable.
- **parity plan:** Share android-free InGameSettingsFiles/EditLock/WowGameSettings/ManagedConfigPolicy
  via shared-sources.json; twin InGameSettingsEditor (Context→client dir) in
  `desktop/.../ingame/` with direct-edit when stopped, queued when running; WtfCodecsTest already
  green on desktop. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-86 — Stock settings panels + bindings editor
- **gap:** No desktop screens: Router.kt:8-25 has 5 routes, none for game settings/bindings;
  `WowSettingModel.kt`/`WowVanillaSettingsCatalog.kt` (frozen vanilla catalogs, android-free) are
  unshared. Depends on DESK-85's engine. (Android row 36: stock 1.12.1 settings-panel scope
  graphics/sound/interface/advanced/bindings hub, integrity-hashed manifest of stock build-5875
  fixed settings.)
- **portability:** portable.
- **parity plan:** Share WowSettingModel/WowVanillaSettingsCatalog/WowVanillaBindingCatalog via
  shared-sources.json; build desktop `ui/InGameSettingsScreen.kt`
  (graphics/sound/interface/advanced/bindings hub) over DESK-85; golden-test the catalog manifest
  hash. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P2

### DESK-96 — WTF/Config.wtf/SavedVariables engine + UI
- **gap:** The three codecs are compiled into desktop via `shared-sources.json:50-52`
  (`ingame/BindingsFileCodec.kt`, `ConfigWtfCodec.kt`, `SavedVariablesCodec.kt`) and their tests
  run green (WtfCodecsTest in the 33 shared suites), but grep over desktop main shows **zero
  references** to them, and no desktop twin exists for `InGameSettingsEditor`, `InGameSettingsFiles`,
  `InGameSettingsEditLock`, `WowGameSettings`, `ManagedConfigPolicy`, `WowSettingModel`,
  `WowVanillaSettingsCatalog`. The desktop user cannot change any in-game setting from the app; on
  Android this is a shipped engine+UI. (Android #35-#36.)
- **portability:** portable.
- **parity plan:** Create `desktop/src/main/kotlin/com/pocketrealm/ingame/` twins:
  `InGameSettingsFiles` (WTF layout under the picked client dir's `WTF/`), a single-JVM
  `InGameSettingsEditLock`, and the editor's direct-edit leg (the desktop world is in-process, so
  the DataStore-queue leg collapses to "stopped ⇒ direct edit"); share `WowSettingModel` +
  `WowVanillaSettingsCatalog` via `shared-sources.json` if the manifest test stays android-free;
  new screen wired through `DesktopAppModel` verbs (a "Game settings" route). The running-world
  posture: desktop stop = client destroy + world stop, so apply-on-next-launch copy matches
  Android's. *(hunt-r1-lens6.md)*
- **effort:** L — **severity:** P2

### Area 13 — Updates

### DESK-14 — In-app update download & install path
- **gap:** Desktop `UpdateCheck.kt` is display-only: opt-in feed file, version comparison, "never
  self-updates" (desktop-inventory line 67). Android downloads and installs
  (`update/AppUpdateCoordinator.kt:9-28`: resumable sha256-verified download + PackageInstaller
  session). The PackageInstaller/Activity mechanics are android-specific; the resumable verified
  download + apply path is not. (Android row 42.)
- **portability:** portable.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/desktop/UpdateCheck.kt`:
  resumable HTTP download twin of AppUpdateCoordinator (Range/206 + sha256 verify, same manifest
  feed), then apply = verify signature/launch the new jpackage installer (or swap the app-image
  dir) with explicit user consent; keep honest "unsigned build — review before install" copy until
  signing exists. Tests: feed parse + partial-download resume + digest mismatch refusal.
  *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-32 — In-app updates — resumable sha256-verified install
- **gap:** Desktop `UpdateCheck.kt` compares versions and shows copy ("never self-updates"); the
  user must manually replace the jpackage image. Beyond convenience, app/native version skew is
  exactly the stale-pin risk the Android updater closes (an old app against a rebuilt native lane).
  (Android #42: `AppUpdateCoordinator.kt:9-28`.)
- **portability:** portable — HTTPS + sha256 + atomic swap are plain JVM/Windows work.
- **parity plan:** Extend `UpdateCheck.kt`: opt-in resumable HTTPS download of the release image
  with sha256 verify into `%LOCALAPPDATA%/PocketRealm/update/staging`, then an "apply on next
  start" swap + relaunch of the jpackage image; keep the unsigned-port copy honest.
  *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P3

### DESK-45 — Update download & apply (beyond display-only check)
- **gap:** Desktop update lane stops at the check: `UpdateCheck.kt:21-31` reads an opt-in https
  feed URL and compares versions for display only ("never self-updates"). No download, no digest
  verification, no apply step. (Android #42.)
- **portability:** portable — resumable HTTP + sha256 (shared `fs/FileDigests.kt`) + "launch
  installer / swap app-image" are all Windows-doable; keep the honest unsigned-copy caveat.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/desktop/UpdateCheck.kt` (or
  sibling `UpdateDownload.kt`) with a resumable https download verified via shared
  `fs/FileDigests.kt`, then an explicit user-consented apply (run installer / replace jpackage
  image); keep opt-in feed; JVM test over a local HTTP fixture pins resume + hash-mismatch
  refusal. *(hunt-r1-lens3.md)*
- **effort:** M — **severity:** P3

### DESK-91 — Resumable verified download + install
- **gap:** Desktop update support is display-only: `UpdateCheck.kt:48` prints "download the new …"
  and the app "never self-updates (unsigned port; copy says so)" (desktop-inventory line 67).
  `AppUpdateCoordinator.kt` (10 android refs, PackageInstaller) has no desktop twin for the
  download/verify/install legs. (Android row 42.)
- **portability:** portable (JVM download+verify; install via setup exe / image replace).
- **parity plan:** Extend `desktop/.../UpdateCheck.kt` with a resumable Range download + sha256
  verify (shared `fs/FileDigests`) into the export root, then run the installer/replace the jpackage
  image with consent; keep the opt-in https feed; add verify/refusal tests. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P3

### DESK-104 — Resumable update download & install
- **gap:** Desktop `UpdateCheck.kt:30-58` fetches an opt-in feed and prints "Update available …
  this app does not self-update" (`:53-55`); Settings shows the string (`SettingsScreen.kt:124`
  card). No download, no sha256, no install — the user manually replaces the jpackage image.
  (Android row #42 — GitHub-manifest check + resumable sha256-verified download + install with
  data preservation.)
- **portability:** portable.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/desktop/UpdateCheck.kt` with the
  `AppUpdateCoordinator` twin: resumable HTTP Range download into `<root>/settings/downloads`,
  sha256 verify against the release manifest, then an explicit consent "Install" that swaps the
  jpackage app folder (atomic rename, preserve `%LOCALAPPDATA%` state — data lives outside the
  image, so preservation is structural). Keep https-only and the never-silent posture; progress in
  the Settings Updates card. *(hunt-r1-lens6.md)*
- **effort:** M — **severity:** P3

### Area 14 — LAN networking

### DESK-15 — LAN host & join (experimental)
- **gap:** `supervisor/RuntimeTopology.kt` and `client/ClientRealmEndpointProjection.kt` are shared,
  but every desktop entry point pins `RuntimeMode.LOCAL` (BootRealmd.kt:32, BootWorld.kt:41,
  LaunchClient.kt:38, AuthGate.kt:48, RealmdHold.kt) and the `allowLanPlayers` setting is dead —
  it exists only in desktop `storage/Settings.kt:17,46,71` with no consumer (grep). The conf twin
  binds loopback and no LAN destination exists in Router.kt:9-17. (Android row 43.)
- **portability:** portable.
- **parity plan:** New LAN route (CMP port of the host/join panes in `ui/LanScreen.kt:55-189`,
  minus Android-specific bits); thread mode+bindAddress from settings through DesktopAppModel into
  `ServerRuntimeFiles` twin (bind realmd 3724/world 8085 on a private/link-local IPv4 — the shared
  RuntimeTopology validation already enforces the shape) and into realmlist projection; honor
  `allowLanPlayers` as the gate. Extend DesktopServerRuntimeFilesTest with LAN bind cases.
  *(hunt-r1-lens1.md)*
- **effort:** M — **severity:** P2

### DESK-24 — LAN host & join
- **gap:** Desktop `Settings.Snapshot` declares `runtimeMode` and `allowLanPlayers`
  (`desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt:16-17`) but their only consumer is
  a log line (`Main.kt:51`); `DesktopAppModel.startRealm` always starts LOCAL
  (`DesktopAppModel.kt:84`); `ServerRuntimeFiles` defaults BindIP to loopback
  (`ServerRuntimeFiles.kt:60,83`); `Router.kt:8-27` has no LAN destination — while the shared
  supervisor fully implements LAN_HOST/LAN_JOIN (`DurableRuntimeSupervisor.kt:64,95,128`) and the
  backend's dotted-quad guard already admits LAN IPv4 addresses (`DesktopRuntimeBackend.kt:864`).
  A whole Android capability is compiled on desktop with no path to it. (Android #43.)
- **portability:** portable.
- **parity plan:** LAN section in `ui/SettingsScreen.kt` writing `runtimeMode`/`allowLanPlayers`;
  `DesktopAppModel` passes a launch spec carrying the chosen LAN IPv4 endpoint so
  `realmdConfig`/`worldConfig` bind non-loopback; join mode = stop realm/world + launch client
  against the remote IP; extend `DesktopServerRuntimeFilesTest` with a LAN-bind golden body.
  *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P2

### DESK-42 — LAN host & join over RuntimeMode LOCAL/LAN_HOST/LAN_JOIN
- **gap:** The shared supervisor fully supports LAN: `DurableRuntimeSupervisor.kt:64` branches on
  `LAN_JOIN`, `:1040-1042` builds `RuntimeLaunchSpec.local/lanJoin/lanHost`, and `RuntimeModel.kt:41`
  carries `realmEndpoint` in the durable snapshot. It is compiled on desktop
  (shared-sources.json:73) — but `DesktopAppModel.startRealm` (`:84`) always calls the LOCAL-only
  `supervisor.start(profileId, includeClient)` overload, `RpHost.kt:51-53` hard-codes
  `mode = RuntimeMode.LOCAL, endpoint = RealmEndpoint.LOCAL`, no desktop screen offers host/join,
  and `RuntimeTopology` has zero desktop main references (grep). Desktop LAN is unreachable code.
  Also `ServerRuntimeFiles.realmdConfig` defaults the bind to LOOPBACK (`:60`) and every desktop
  entrypoint pins `RuntimeMode.LOCAL`. (Android #43: `ui/LanScreen.kt`, `RuntimeTopology.kt`,
  `ClientRealmEndpointProjection.kt`.)
- **portability:** portable.
- **parity plan:** Add an endpoint/mode card (Home or Settings) that persists `realmEndpoint` +
  mode in `desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt` and calls
  `supervisor.start(RuntimeLaunchSpec.lanHost(...)/lanJoin(...))` through a new `DesktopAppModel`
  verb; the bind path already exists — `ServerRuntimeFiles.realmdConfig/worldConfig` take
  `bindAddress` (`:60,82-86`) and the IPv4 guard is in `projectRealmEndpoint`
  (`DesktopRuntimeBackend.kt:166-168`). Reuse the shared `RuntimeTopology` parsing for input
  validation (numeric private/link-local IPv4 only, fixed ports 3724/8085, no DNS/hostname);
  extend `DesktopRuntimeBackendTest` with a lanHost conf-emission leg; Windows firewall note copy.
  *(hunt-r1-lens6.md, hunt-r1-lens3.md)*
- **effort:** M — **severity:** P2

### Area 15 — Supervisor & runtime domain

### DESK-29 — World-presence sampling + foreground policy seam
- **gap:** `observeWorldPresence` always returns `WorldPresenceSample.EMPTY`
  (`DesktopRuntimeBackend.kt:245`) and `promoteToForeground`/`demoteToForeground` always fail
  "not implemented by the desktop WoW.exe launcher" (`:247-257`). The shared
  `ForegroundPromotionPolicy` — whose only driver is the shared `DurableRuntimeSupervisor` the
  desktop runs — therefore executes on permanently empty input with no-op verbs: a compiled,
  driven, dead code path. (Android #10/#18: specialUse FGS promotion while a player is present.)
- **portability:** portable — presence is derivable from process liveness; window promotion has
  Win32 equivalents.
- **parity plan:** Implement `observeWorldPresence` from what the backend already knows (client
  process alive + world READY); implement promote/demote via JNA `SetForegroundWindow` on the WoW
  window (or keep the honest failure while feeding real presence to the policy); add a
  `DesktopRuntimeBackendTest` leg asserting non-empty presence while a client runs.
  *(hunt-r1-lens2.md)*
- **effort:** M — **severity:** P3

### DESK-61 — World-presence sampling seam
- **gap:** `observeWorldPresence` always returns `WorldPresenceSample.EMPTY`
  (`DesktopRuntimeBackend.kt:245`), so the shared
  `DurableRuntimeSupervisor.reconcileForegroundPromotion` player-gated branches
  (`DurableRuntimeSupervisor.kt:394-437`) never fire desktop-side. Current consumers are
  Android-only, so no functional loss today — but the seam silently reports "nobody home" instead
  of an honest sample, and any future player-gated portable behavior inherits the lie.
- **portability:** portable.
- **parity plan:** Implement an honest sample behind the existing `NativeGate` skip pattern (online
  accounts from the world native status / characters DB, same shape the Android backend samples);
  keep EMPTY only for a stopped world (the supervisor already guards that direction); pin a sample
  leg in `DesktopRuntimeBackendTest`. *(hunt-r1-lens4.md)*
- **effort:** S — **severity:** P3

### DESK-71 — Foreground promotion & world-presence verbs
- **gap:** Declared honest stubs: `promoteToForeground`/`demoteToForeground` always return ok=false
  "not implemented by the desktop WoW.exe launcher" and `observeWorldPresence` always returns
  `WorldPresenceSample.EMPTY` (DesktopRuntimeBackend.kt:245-257; desktop-inventory lines 42, 71).
  The shared `ForegroundPromotionPolicy` therefore computes decisions the desktop backend refuses
  to act on, and world-presence consumers run blind. (Android rows 10/18.)
- **portability:** portable (JNA window focus; presence from world native status).
- **parity plan:** Implement `observeWorldPresence` in DesktopRuntimeBackend from WorldNative
  players-online; implement promote/demote via JNA SetForegroundWindow on the client pid's window;
  keep shared ForegroundPromotionPolicy unchanged; extend DesktopRuntimeBackendTest.
  *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P3

### DESK-109 — Save-exit on out-of-band session end
- **gap:** The desktop app's ONLY drain path is `Window.onCloseRequest`
  (`desktop/.../desktop/Main.kt:58-76` → `model.saveAndExit()` + `model.close()`). No shutdown
  hook or session-end handler exists in the app main — the pattern is used only by the dev gate
  mains (`LaunchClient.kt:101`, `RpHost.kt:57`; grep across desktop main finds no other). On
  Windows logoff/shutdown/restart (or `taskkill`), `onCloseRequest` never fires and the JVM is
  terminated without `supervisor.stop(StopMode.GRACEFUL)` — whose world leg performs the save
  (`DurableRuntimeSupervisor.kt:907 backend.saveWorld(world)`) — so the world stops ungracefully,
  the launched WoW.exe is orphaned, and durability falls back to the native autosave + next-start
  `recoverDatabase`. Android treats save-exit as an explicit out-of-band capability
  (`SaveExitReceiver`/`ACTION_SAVE_EXIT`, Android #18; `service/RealmService.kt:612,672-673`,
  notification action `:557-589`, `HomeScreen.kt:324`).
- **portability:** portable — a JVM shutdown hook is plain Java; no Windows-specific API required
  for the bounded best-effort form.
- **parity plan:** Register a bounded `Runtime.getRuntime().addShutdownHook` in `Main.kt` running
  the same saveAndExit+close drain, guarded by the same once-flag as `onCloseRequest` (idempotent
  both ways); order it save-world → stop natives → client destroy to fit the ~5s logoff budget;
  exercise via a gate main that registers the hook and exits mid-session. *(hunt-r2-lens2.md)*
- **effort:** S — **severity:** P2

### DESK-111 — Graceful world save on OS shutdown / logoff (SaveExitReceiver analog)
- **gap:** The desktop drains the stack (client→world→realm→database with a real world save) ONLY
  when the user closes the window (`Main.kt:58-75` `onCloseRequest`). If Windows shuts down,
  reboots, or logs the user off while a realm is running, the UI app has no `SaveExitReceiver`
  analog and no shutdown hook — `grep shutdownhook|ENDSESSION|QUERYENDSESSION|SESSIONEND|addShutdown`
  across `desktop/src/main/kotlin` hits only the headless `LaunchClient.kt:101` and `RpHost.kt:57`
  hooks. Result: unsaved world + orphaned WAL sidecars, exactly what Android's receiver exists to
  prevent. (Android row 18: `SaveExitReceiver` at `RealmService.kt:691-698`,
  `ACTION = "com.pocketrealm.action.SAVE_EXIT"`, wired at `:559`.)
- **portability:** portable — no Android primitive involved; a JVM `Runtime.addShutdownHook`
  (pattern already proven in `LaunchClient.kt:101`) plus re-entry guarding against the window-close
  drain covers Windows session end.
- **parity plan:** In `desktop/src/main/kotlin/com/pocketrealm/desktop/Main.kt`, register a
  shutdown hook that runs `model.saveAndExit()` (mirroring `SaveExitReceiver` →
  `RealmService.saveExit`), sharing the existing `closeRequested` flag so window-close and
  OS-shutdown never double-drain; bounded by the native stop timeouts already used. Desktop test
  implication: a JVM gate test (like `LaunchClient`'s) proves the hook drains a booted stack
  without a window. *(hunt-r2-lens3.md)*
- **effort:** S — **severity:** P3

### DESK-113 — Single-owner runtime domain (single-instance guard)
- **gap:** `Main.kt:36-57` constructs a `DesktopAppModel` unconditionally — no lock file, no
  socket, no PID guard. Android's whole ownership discipline assumes ONE owner domain: one app
  process owning the services (inventory row 18: owner-claim handshake, orphan self-heal), with
  `ComponentOwnership` enforced through binder identity. On desktop the ownership slots are
  per-process (`DesktopRuntimeBackend.kt:52-59`, `OwnershipSlot` :808-833 — "the owner's liveness
  is this JVM itself"), and both instances share `%LOCALAPPDATA%\PocketRealm`: two supervisors
  atomically replace the same journal (`DesktopSupervisorJournal(roots.supervisorJournalDir)`,
  `DesktopAppModel.kt:48-51` — last-writer-wins, dirty/clean flapping), and instance B's
  `recoverDatabase` guards check B's own per-process state, which reads STOPPED/null while A's
  natives actually run (`DesktopRuntimeBackend.kt:351-373`), so B proceeds into WAL-drain/
  VACUUM-INTO against a live datadir and only the 15 s sidecar deadline saves it (:390-414).
  A stale-DLL co-load or two launched exes become silent corruption windows.
- **portability:** portable.
- **parity plan:** In `DesktopAppModel.init`, acquire an exclusive `FileChannel.tryLock` on
  `<roots.realm>/app-instance.lock` (write pid, keep the channel for process lifetime, release in
  `close()`); a second instance refuses to start and shows a dialog naming the holder pid instead
  of constructing a second supervisor/backend. Test: a unit leg that locks the file and asserts the
  model's refusal path; no new shared sources. *(hunt-r2-lens4.md)*
- **effort:** S — **severity:** P2

### DESK-114 — Client observation, orphan adoption, save-exit reach
- **gap:** `observe(CLIENT)` reports READY only from the self-spawned `Process` field
  (`DesktopRuntimeBackend.kt:131-141`, field :61-62), the double-launch guard checks only that
  field (:518), and `stopClientProcess` stops only it (:557-570). A WoW.exe the user launched
  manually (or that survived a crashed previous app instance — the "survivor stays observable"
  comment at :549-551 only survives within the owning process) is INVISIBLE: observe says "client
  not running", Save & exit drains a stack that excludes it, force-stop can't list it, and a second
  Start-game launches a duplicate client. Android owns/observes the real session and the shared
  `OrphanSelfHealPolicy` (compiled into desktop) keys off ownerless RUNNING observations — which
  desktop can never produce for the client. Cross-feature: the orphan-heal × client × realmlist
  interaction — a user-launched client keeps playing against a world the app just stopped.
- **portability:** portable.
- **parity plan:** Extend `observe(CLIENT)` in DesktopRuntimeBackend.kt to scan
  `ProcessHandle.allProcesses()` for a `WoW.exe` whose command line resolves under the configured
  clientDir (plain JVM, no JNA); a found-but-unowned process reports RUNNING with `owner = null` so
  the shared supervisor's adoption/orphan semantics engage, and route the :518 double-launch check
  plus `close()`/`saveAndExit()` client drain through the same scan (destroy by pid). Pin in
  `DesktopRuntimeBackendTest` with a fake process-name probe seam. *(hunt-r2-lens4.md)*
- **effort:** M — **severity:** P2

### DESK-115 — Lockfile-pin verification before the runtime ships
- **gap:** `NativeRuntimeFreshness.kt` (shared-sources.json:57) is Android's staleness fence — its
  own docstring: "A stale packaged .so would otherwise ship silently with an exit-0 BUILD
  SUCCESSFUL", enforced by the Gradle tasks `validateNativeRuntimeFreshness`/
  `validateNativeRuntimePins`. On desktop it is compiled but **never used**: `grep -rn
  NativeRuntimeFreshness desktop/src` → zero; `desktop/build.gradle.kts` (packageApp at :253-418)
  contains no lockfile/sha256/freshness reference. `packageApp` jpackages whatever three DLLs are
  staged, and the app's only runtime check is the ABI status width + ABI_VERSION
  (`DesktopRuntimeBackend.kt:651`, :668) — which a stale-but-ABI-stable `pocket_world_runtime.dll`
  (the docstring's exact example: missing world-chat/reset-state JNI ops) passes. The freshness
  contract silently diverges: loud on Android, absent on Windows.
- **portability:** portable.
- **parity plan:** Add a `validateNativeRuntimeFreshness`-equivalent leg to `desktop/build.gradle.kts`
  `packageApp` (and `test`): read `schemas/realm-runtime-lockfile-sqlite-win.json` + the staging
  `BUILD_PROVENANCE.json`, feed the already-compiled `NativeRuntimeFreshness.evaluate(...)` with
  `requireStagedBytes = true` over the three staged DLLs, and fail the build on a non-empty Verdict
  with its REMEDY copy; unit-test via a mirror of the shared `NativeRuntimeFreshnessTest` (already
  in shared_tests). *(hunt-r2-lens4.md)*
- **effort:** M — **severity:** P2

### Area 16 — Auto-login & accounts

### DESK-30 — Auto-login policy gate
- **gap:** `DesktopAppModel.autoLoginIfEnabled` re-implements the gate inline
  (`if (!snapshot.autoLoginOnLaunch) return; val account = _storedAccount.value ?: return`,
  `DesktopAppModel.kt:152-155`) while the shared, tested `AutoLoginPolicy` — already in
  `shared-sources.json:63` — has **0** desktop main references (grep; Android's only driver is
  `AndroidRuntimeBackend.kt`). Two gate implementations can drift silently. (Android #16:
  `AutoLoginPolicy.kt:4-9` — master switch AND user-provisioned identity, no synthesized fallback.)
- **portability:** portable.
- **parity plan:** Route the desktop gate through `supervisor.AutoLoginPolicy.evaluate` (already on
  the desktop classpath) inside `autoLoginIfEnabled`; the shared `AutoLoginPolicyTest` keeps the
  contract pinned; no new files. *(hunt-r1-lens2.md)*
- **effort:** S — **severity:** P3

### DESK-57 — Auto-login gate & credential lifecycle
- **gap:** Desktop re-implements the gate inline (`autoLoginOnLaunch` + stored account,
  `DesktopAppModel.kt:152-155`) instead of resolving through the shared `AutoLoginPolicy`, and
  shared `AutoLoginCredentialProof` (owner-scoped ACCEPT/INVALID_CREDENTIAL/AUTHORITY_UNAVAILABLE
  dispositions, `supervisor/AutoLoginPolicy.kt:33-91`) has **no desktop consumer** — a stale
  password is re-typed every launch with no verdict and the stored account is never quarantined.
  Keystrokes go to the *first visible window of the pid* (`Win32AutoLogin.kt:51-65`) after fixed
  2.5 s/3 s settles (`Win32AutoLogin.kt:116`, `DesktopAppModel.kt:196`), versus Android's exact
  build-5875 login-window topology map per lane (inventory row 16, `SinglePlayerAutoLogin.kt:20-41`).
- **portability:** portable.
- **parity plan:** Resolve the decision through shared `AutoLoginPolicy.resolveAutoLogin`; feed a
  failed proof verdict (authGate/RpHost already compute SRP6 proofs — reuse that outcome) through
  the `AutoLoginCredentialProof` dispositions to clear/quarantine via `UserAccountStore` on
  INVALID_CREDENTIAL; before typing, verify the foreground window matches the qualified flavor's
  login window (class/title probe keyed off `ClientFlavor`), replacing the blind
  first-visible-window pick. Pin the disposition table in a desktop unit test (the shared policy
  tests already exist in the manifest). *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P2

### DESK-120 — Stable failure codes → plain user copy
- **gap:** Android maps stable codes to user copy via `ui/AccountStatusPresentation.kt` ("Stable
  machine codes stay in diagnostics/logs" — accountProvisionFailureMessage, imports only the shared
  UserAccountStore, i.e. android-free; inventory row 17). Desktop does the exact inverse:
  `ui/HomeScreen.kt:388` renders `"Account not provisioned: ${result.code} (${result.detail})"` —
  the raw code AND the internal detail string go straight into the UI (grep for
  ACCOUNT_PASSWORD_MISMATCH/AccountStatus in HomeScreen: zero, so no mapping exists anywhere
  desktop-side).
- **portability:** portable.
- **parity plan:** Share `ui/AccountStatusPresentation.kt` via shared-sources.json (android-free:
  imports `com.pocketrealm.supervisor.UserAccountStore` only — the manifest test will confirm) or
  add a JVM twin; call `accountProvisionFailureMessage(result.code, result.detail)` at
  HomeScreen.kt:388 instead of interpolating code+detail. No build impact beyond the manifest
  entry; the codes' wording is already pinned by the Android suite. *(hunt-r2-lens4.md)*
- **effort:** S — **severity:** P3

### DESK-125 — Stable failure code → plain-copy presentation
- **gap:** Android maps account-provision failures through `ui/AccountStatusPresentation.kt:6-21`
  — `accountProvisionFailureMessage(code, detail)` turns
  `ACCOUNT_INVALID`/`ACCOUNT_PASSWORD_MISMATCH`/`WORLD_NOT_READY`/`WORLD_NOT_OWNED`/
  `ACCOUNT_REJECTED`/`ACCOUNT_CONTROL_FAILED` into player-facing sentences ("stable machine codes
  stay in diagnostics/logs"). The desktop Home account card prints the raw pair instead:
  `ui/HomeScreen.kt:388` → `"Account not provisioned: ${result.code} (${result.detail})"`.
  (Android #17.)
- **portability:** portable.
- **parity plan:** The Android file is android-free (its only import is `supervisor.UserAccountStore`,
  shared) — first try adding `ui/AccountStatusPresentation.kt` to `desktop/shared-sources.json`
  (keep `tests/test_desktop_shared_manifest.py` green); if the ui package placement is awkward,
  drop a JVM twin at
  `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/AccountStatusPresentation.kt` and call it
  from the HomeScreen account card error branch (`HomeScreen.kt:385-388`). Same wording pins as
  the Android copy. *(hunt-r2-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-133 — Auto-login automation timing tuning
- **gap:** Android defines `Settings.AutoLoginTimings` (`storage/Settings.kt:266`, bounds-validated
  at :327-343 — "auto-login timings are outside supported bounds" — resolved via
  `effectiveAutoLoginTimings()` :524, persisted as `autoLoginTimings` :373) and feeds it into the
  login automation (`client/SinglePlayerAutoLogin.kt:112`, `client/ClientDisplayHost.kt:78`). The
  desktop equivalent pins three constants: `DesktopAppModel.kt:194-196`
  (`AUTO_LOGIN_WAIT_MS = 60_000`, `AUTO_LOGIN_POLL_MS = 1_000`, `AUTO_LOGIN_SETTLE_MS = 3_000`)
  with no settings surface — a user whose machine reaches the login window slower than 3 s
  silently degrades to manual login with no recourse. (Android #16 settings surface.)
- **portability:** portable.
- **parity plan:** JVM-twin `AutoLoginTimings` (pure data class + clamps — android-free) either by
  adding the Android `storage/Settings.kt` nested type to `shared-sources.json` (manifest test must
  stay green) or a desktop twin next to `storage/Settings.kt`; add `autoLoginTimings` to the
  desktop Snapshot + codec, replace the `DesktopAppModel.kt:194-196` constants, and thread through
  `Win32AutoLogin.tryLogin`. Distinct from DESK-30/57 (gate + credentials): this is the timing
  surface only. *(hunt-r3-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-134 — Shipped defaults of botProfileId and autoLoginOnLaunch
- **gap:** Android's fresh-install defaults select the default bot profile (`storage/Settings.kt:362`
  `botProfileId = BotProfiles.defaultProfile.id` — the Quiet-25 lane, so a first boot has bots) and
  default auto-login ON (`:371` `autoLoginOnLaunch = true`, safely inert until an account is
  provisioned per the AutoLoginPolicy gate). The desktop twin defaults `botProfileId = ""` (→
  `resolveBotProfile()` returns null → the bots-disabled conf) and `autoLoginOnLaunch = false`
  (`desktop/.../storage/Settings.kt:19,22`). The desktop header comments this as intent
  ("bots unselected until the user applies a profile in Bots", `Settings.kt:11-12`), but the result
  is a user-visible product divergence: out of the box the desktop realm runs empty of bots and
  never auto-logs-in, where the Android product does both.
- **portability:** portable.
- **parity plan:** Align the defaults in
  `desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt`
  (`botProfileId = BotProfiles.defaultProfile.id` — the shared profiles are already compiled;
  `autoLoginOnLaunch = true`) keeping fromJson fallbacks identical so existing settings files are
  unaffected (defaults only apply to absent keys), update the header comment, and re-pin
  `DesktopSettingsStore`/backend tests that assert the bots-disabled first boot.
  *(hunt-r3-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-136 — Account name/password entry: validation vs input mangling
- **gap:** Android's HomeScreen passes the raw field callbacks straight through
  (`HomeScreen.kt:938,955` — no character filtering) and validates against the realm rule with
  plain-copy messaging: `AccountStatusPresentation.kt:41` rejects a non-conforming value via
  `UserAccountStore.isValidCredential` ("Account name must be 1–16 letters or numbers.") and :49
  gates the submit button on the same rule. Desktop instead mangles input at the field:
  `HomeScreen.kt:346` / `:353` apply `take(16).filter { ch -> ch.isLetterOrDigit() }` — which (a)
  silently deletes characters the user typed (no rejection message), and (b) accepts non-ASCII
  letters (é, ß pass `isLetterOrDigit`) that the store rule rejects
  (`UserAccountStore.isValidCredential`, desktop twin :126-127, `it.code < 128`), so the field
  happily shows a value the button (:376-377) then refuses with no explanation — the inverse of
  the Android validate-and-explain contract.
- **portability:** portable.
- **parity plan:** Delete the `.filter { ... }` mangling from HomeScreen.kt:346/:353 (keep at most
  the 16-char cap), keep the button gate on the shared `UserAccountStore.isValidCredential`, and
  surface the rejection through the already-registered plain-copy path (DESK-120 /
  `accountStatus` line :395-402) with the `AccountStatusPresentation` wording. Test: a UI-state
  leg asserting "café" leaves the text intact, disables Create, and shows the copy.
  *(hunt-r4-lens4.md)*
- **effort:** S — **severity:** P3

### DESK-137 — Runtime failure copy (Android #18 "pure failure copy" leg)
- **gap:** Android humanizes supervisor failures at the UI decode boundary:
  `supervisor/RuntimeSupervisorClient.kt:137` maps the raw detail through
  `RuntimeFailureCopy.humanize` ("Raw details (UNVERIFIED_ORPHAN, timeout classes) go to logs; the
  UI sees human copy", :133-135), e.g. `UNVERIFIED_ORPHAN` → "A previous realm start left a world
  running that this device cannot verify as its own…" with the raw string kept for logs only
  (`RuntimeFailureCopy.kt:10-24`). The desktop renders the raw detail straight to the user:
  `ui/HomeScreen.kt:67` (`detail = snapshot.lastError ?: snapshot.lastDurableAction`) and
  `ui/DiagnosticsScreen.kt:97-104` (raw `lastError` line) — so a desktop user sees
  `UNVERIFIED_ORPHAN` or `TimeoutCancellationException` verbatim. The shared
  `supervisor/RuntimeFailureCopy.kt` is already compiled on desktop (`shared-sources.json:71`,
  RuntimeFailureCopyTest green) with **zero** desktop main references. Distinct from DESK-120/125
  (those map account-provision codes; this is the supervisor runtime-failure copy).
- **portability:** portable.
- **parity plan:** Apply `RuntimeFailureCopy.humanize` at the desktop's decode boundary — the same
  seam Android uses: render `humanize(snapshot.lastError)` in `ui/HomeScreen.kt:67` and
  `ui/DiagnosticsScreen.kt:97`, keep the raw string in `DesktopAppModel` logging (`:94`) exactly as
  Android keeps raw in logs; derive the `unverifiedOrphan`/`dbOwnedByDeadSession` booleans the same
  way (:130-132) so the Home Force-stop affordance highlights for those two states. No new files —
  the shared class is already on the desktop compile line; add a `DesktopRuntimeBackendTest`-adjacent
  copy pin if desired. *(hunt-r4-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-138 — Login-window detection & typed-entry targeting
- **gap:** Android's auto-login is driven by the "exact mapped topology of the build-5875 login
  window" (`client/SinglePlayerAutoLogin.kt:20-41` — the automation is bound to the actual login
  dialog structure, with configurable `AutoLoginTimings` at :112). The desktop twin
  (`desktop/.../desktop/Win32AutoLogin.kt`) has no login-screen proof at all: `findWindowForPid`
  (:51-65) accepts the FIRST visible top-level window of the pid — the 1.12 client's
  launcher/splash/loading window qualifies — then after a fixed 2.5 s settle (:42,:116) blind-types
  `username TAB password ENTER` (:43-46) and returns null (success) the moment the keystrokes are
  injected. If the client is past-login, still loading, or showing a modal, the credentials go to
  the wrong surface and `DesktopAppModel.autoLoginIfEnabled` logs "auto-login keystrokes delivered"
  (`:179`) with no way to detect the miss. (Android #16.)
- **portability:** portable.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/desktop/Win32AutoLogin.kt`:
  replace first-visible-window with a match on the 5875 login frame's window class/title signature
  (capture once from a real 1.12.1 client, pin it like RpWowClient pins protocol constants), poll
  for that window until the DESK-133-configured timeout instead of the fixed `SETTLE_MS` (:42,:116),
  return the honest reason "login window never appeared" on timeout, and keep the
  degrade-to-manual posture. Verify with the existing rpHost/authGate lanes.
  *(hunt-r4-lens6.md)*
- **effort:** M — **severity:** P3

### Area 17 — Diagnostics

### DESK-107 — Diagnostics: client crash-dump evidence for crash triage
- **gap:** Android's Diagnostics screen surfaces the WoW client's own crash dumps: it reads crash
  texts from the client generation's `Errors/` folder, capped at 5 rows
  (`android/.../ui/DiagnosticsScreen.kt:44` `MAX_CLIENT_CRASH_DUMP_ROWS = 5`, `:81-108`
  "Client-session evidence for crash triage", `:150-157`). Desktop **detects** a dead client — the
  backend's `observe` CLIENT branch reports READY iff `process.isAlive`, STOPPED otherwise
  (`desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:129-140`) — but
  surfaces no crash evidence at all: `DiagnosticsScreen.kt:38` explicitly defers ("crash dumps are
  Windows' own"), nothing scans the configured client folder, and the support bundle ships no
  client artifacts (`DesktopSupportBundle.kt:30-38`). When WoW.exe dies, the user sees "client not
  running" with zero triage material and support has nothing in the bundle. (Android row 40
  sub-feature; the Wine-session-record half is android-specific.)
- **portability:** portable.
- **parity plan:** Add a client-crash card to
  `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/DiagnosticsScreen.kt` that scans the
  configured client dir (`settings.clientDir`) for the client's crash texts (`Errors/` folder,
  mirroring DiagnosticsScreen.kt:81-108's 5-row cap); include those files in
  `DesktopSupportBundle.kt`'s member list. Test: fixture crash file under a temp client dir is
  listed by the card logic and capped at 5 rows; bundle gains the entry + sha256 manifest row.
  *(hunt-r2-lens1.md)*
- **effort:** S — **severity:** P3

### DESK-110 — Structured log — AppLog typed-event ring feeding Diagnostics + support bundle
- **gap:** Android's `log/AppLog.kt:6-19` keeps a bounded 800-entry in-memory ring of typed events
  (`Line(ts, level, kind, message)`) with `snapshot()` and `exportJson()`; it feeds the Diagnostics
  screen (`ui/DiagnosticsScreen.kt:68-69`, 2s cadence) and the support bundle
  (`diagnostics/SupportBundleExporter.kt:42` exports `logs.json`), making logs greppable by stable
  `kind` and cheaply redactable. The desktop `DesktopLog.kt:15-47` is plain `level/tag/message`
  text to console + file; the desktop Diagnostics "Recent log" card reads the raw file tail
  (`ui/DiagnosticsScreen.kt:133-152`) and `DesktopSupportBundle` embeds the unstructured log file.
  The typed-event contract — and the in-memory snapshot that survives even when the file write
  failed — is absent on desktop. (Android #40.)
- **portability:** portable.
- **parity plan:** Extend `DesktopLog` with a bounded `ArrayDeque` ring + typed `kind` parameter
  mirroring `AppLog.Line`, plus `snapshot()`/`exportJson()`; feed `DiagnosticsScreen`'s Recent log
  and a new `logs.json` member of `DesktopSupportBundle` from the snapshot; keep the file append
  as-is. *(hunt-r2-lens2.md)*
- **effort:** S — **severity:** P3

### DESK-112 — Client crash / abnormal-exit evidence in diagnostics
- **gap:** The desktop never records how a client session ended. `DesktopRuntimeBackend` holds the
  `Process` (`:528`, stop path `:557-570` reports only a "survived destroyForcibly" survivor), and
  `grep -rn "exitValue|exitCode|crash" desktop/.../desktop/` finds no exit-code capture.
  `DiagnosticsScreen.kt:33-38` defers explicitly: "crash dumps are Windows' own" — so after a
  WoW.exe crash the user gets nothing in-app: no last-exit line, no pointer to the WER dump
  location, and the support bundle carries no client-exit evidence. (Android row 40 client
  crash-dump evidence leg; the backups leg is covered by DESK-6/18/37/100.)
- **portability:** portable — "did the client die abnormally, with what code, where is the dump" is
  plain Windows evidence: the process exit code from the already-held `Process` handle plus
  `%LOCALAPPDATA%\Microsoft\Windows\WER\ReportArchive` / `LocalDumps` pointer.
- **parity plan:** Extend the client lifecycle in `DesktopRuntimeBackend.kt` (:511-570) to journal
  WoW.exe abnormal exit codes (non-zero / killed) into the existing lifecycle journal; surface a
  "last client exit" line in `DiagnosticsScreen.kt` + include it in `DesktopSupportBundle.kt`,
  replacing the :33-38 deferral with a WER dump-path pointer. Test implication: JVM test pins
  abnormal-exit journaling with a synthetic exiting process. *(hunt-r2-lens3.md)*
- **effort:** S — **severity:** P3

### DESK-119 — Bundle manifest header + bounded export
- **gap:** Redaction itself is sound — every member passes through `SecretRedactor(canaries)`
  (DesktopSupportBundle.kt:50-60), with the LLM key as explicit canary (:41-43). But the desktop
  `manifest.json` carries only schema/redaction/per-entry sha256 (:48, :55-60), while Android's
  bundle header prints the qualified flavor lane (DiagnosticsScreen.kt:255, inventory row 40) and
  Android's export is a bounded, consent-triggered ZIP (inventory row 40); the desktop app-log
  member is the whole append-only file with no size bound (`logs/pocket-realm.log`, :31). A triager
  of a desktop bundle cannot tell which flavor lane / native build produced it without reading conf
  bodies.
- **portability:** portable.
- **parity plan:** Extend the desktop manifest (DesktopSupportBundle.kt:48) with `appVersion` (from
  `AppVersion.kt`), the qualified flavor id (`ClientFlavors.VANILLA.flavorId` via the shared
  registry) and `NATIVE_RUNTIME_BUILD_ID` (BuildConfig.kt:12); bound the app-log member to a tail
  (last N KiB) like Android's bounded export. Test: assert the manifest fields in a bundle-export
  unit leg. *(hunt-r2-lens4.md)*
- **effort:** S — **severity:** P3

### DESK-121 — Client process-tree drain on stop + client session/crash evidence
- **gap:** Android enforces a hard drain contract — the whole client process tree must drain before
  teardown completes, and an undrained tree blocks the next launch (`ClientRuntimeService.kt:88-95`
  "owner loss could not prove client process-tree drain", :296 "a client runtime or undrained
  process tree is already active", :415/:505/:514 "client process tree did not drain before
  timeout"). Desktop `stopClientProcess` (DesktopRuntimeBackend.kt:557-570) destroys only the
  direct WoW.exe handle — destroy → destroyForcibly — reporting a survivor but never sweeping
  descendants. Separately, Android surfaces client crash evidence for triage:
  DiagnosticsScreen.kt:81-108 reads `wine/last-session.json` (session id, state, renderer,
  cleanExit) plus the newest `client/generations/*/Errors/` crash dumps. The desktop observe(CLIENT)
  leg (:131-140) only reports `isAlive` — when WoW.exe dies on its own no exit code is captured or
  persisted — and the support bundle (DesktopSupportBundle.kt:32-38) carries no client
  session/crash record. (Android rows 10 + 40.)
- **portability:** portable (Windows `taskkill /PID <pid> /T /F` gives tree kill; WER crash dumps
  and `Process.exitValue()` give the evidence).
- **parity plan:** Extend stopClientProcess (DesktopRuntimeBackend.kt:557) with a tree kill
  (taskkill /PID <pid> /T /F via ProcessBuilder); persist exitValue() on unexpected client death as
  a session record under runtime/logs; surface it in `ui/DiagnosticsScreen.kt` and
  `DesktopSupportBundle` (:32-38); mirrors ClientRuntimeService's drain contract.
  *(hunt-r2-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-126 — Client crash-dump evidence in Diagnostics + support bundle
- **gap:** Android's Diagnostics surfaces "Wine session record & client crash-dump evidence"
  (android-inventory #40). The desktop screen declares the divergence and ships nothing:
  `ui/DiagnosticsScreen.kt:36-38` — "The Android screen's Wine-session evidence does not exist on
  Windows (the client is native); crash dumps are Windows' own" — and the four cards (`:66-147`)
  plus `DesktopSupportBundle` (app log, world/realmd logs, journal, settings, data-active.json, run
  confs) carry no WoW.exe crash evidence. When the native client dies, the desktop gives
  diagnostics nothing to attach. (Android #40.)
- **portability:** portable.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopSupportBundle.kt`
  with a crash-evidence collector: copy recent `WoW.exe.*.<pid>.dmp` from
  `%LOCALAPPDATA%\CrashDumps` (Windows Error Reporting LocalDumps) plus the Application event-log
  lines for WoW.exe faults (redacted via the shared `SecretRedactor` like every other entry), and
  add a "client crashes" card to `ui/DiagnosticsScreen.kt` listing them. Note in the plan: enabling
  LocalDumps for WoW.exe (registry write at client-launch setup) mirrors Android's crash-guard
  fold-timestamps posture. *(hunt-r2-lens6.md)*
- **effort:** M — **severity:** P3

### DESK-127 — Server status rendering (ServerStatusJson twin)
- **gap:** The shared native ABI already delivers live telemetry: `statusNative` width 8 for world
  carries `heartbeatMs=values[3]`, `tickCount=values[4]`, `lastTickMs=values[5]`,
  `maxTickMs=values[6]`, `activeSessions=values[7]`, and width 5 for realm carries
  `heartbeatCount=values[4]` — Android renders every one of them into status/operation JSON via
  `ServerStatusJson.realm/world/operation` (`RealmRuntimeService.kt:37`, `WorldRuntimeService.kt:127`;
  base fields abi/state/error/detail/pid/build+commit ids). The desktop's observation path keeps
  only three of the fields: `DesktopRuntimeBackend.observeRealm/observeWorld` read `status[0]`
  (ABI), `status[1]` (state), `status[2]` (error) and discard indices 3-7
  (`DesktopRuntimeBackend.kt:649-681`), so Diagnostics shows a 512-char detail string with no
  world-load/session facts, and the registered bot-admission work (DESK-8/23/43/99) has no exposed
  tick-p99/session data source despite the data riding the same JNI array. Desktop has no
  `ServerStatusJson` counterpart (grep: absent from `desktop/src/main/kotlin`). (Android row 19.)
- **portability:** portable — the only Android-flavored bit in `ServerStatusJson` is
  `android.os.Process.myPid()`; everything else is org.json + the shared contract.
- **parity plan:** Create the JVM twin
  `desktop/src/main/kotlin/com/pocketrealm/server/ServerStatusJson.kt`
  (`ProcessHandle.current().pid()` for `android.os.Process.myPid()`) mirroring the Android field
  map; have `DesktopRuntimeBackend.observeRealm/observeWorld` render telemetry into the observation
  detail (or a typed side-channel); add a "World load" line (tick/lastTickMs/activeSessions) to
  `ui/DiagnosticsScreen.kt`; extend `DesktopRuntimeBackendTest` to assert the fields survive.
  *(hunt-r3-lens2.md)*
- **effort:** S — **severity:** P3

### Area 18 — Flavor routing & auth protocol

### DESK-56 — Per-client-build SRP6 routing & auth fixtures
- **gap:** The desktop auth stack is a hard-coded vanilla copy: `AuthGate.kt:265`
  (`BUILD_5875 = 5875`), `:357` (the 26-byte vanilla proof frame), `RpWowClient.kt:18` ("the pinned
  5875 lane"). The shared `ClientFlavor` registry carries per-flavor SRP6 frame layouts
  (`client/ClientFlavor.kt:55`, `:208`) that desktop never consumes. Fixture coverage:
  `grep -rln "Srp|srp"` over the Android test tree returns zero, AuthGate has no desktop unit test,
  and proof currently rides on the `authGate` bring-up task. A second qualified flavor (TBC/WotLK
  are already declared in the registry) breaks realmd auth on desktop silently.
- **portability:** portable.
- **parity plan:** Parameterize `VsrpClient`'s build/frame layout off `ClientFlavors` (already
  compiled into desktop via the manifest); add
  `desktop/src/test/kotlin/.../AuthGateTest.kt` holding the per-client-build auth fixtures —
  vanilla golden logon vectors plus frame-size pins derived from the `ClientFlavor` srp6 fields
  (the "auth fixtures per client build"); route `RpWowClient` per flavor. *(hunt-r1-lens4.md)*
- **effort:** M — **severity:** P2

### DESK-62 — Flavor-routed identity resolution in desktop consumers
- **gap:** `client/ClientFlavor.kt` is shared (shared-sources.json line 25) so the registry itself
  exists on desktop, but desktop consumers bypass it: the desktop `ServerRuntimeFiles.kt` twin
  hard-codes the vanilla DB names `classicrealmd`/`classicmangos`/`classiccharacters`/`classiclogs`
  (desktop ServerRuntimeFiles.kt:63,108-111) and has **zero** `ClientFlavor`/`flavor` matches
  (grep), where Android stages per-flavor DB names via `ClientFlavors` (android
  `server/ServerRuntimeFiles.kt:363-375`, inventory row 19). The "every identity gate must resolve
  through `ClientFlavors`" invariant (inventory row 1) does not hold on desktop. Equivalent today
  only because vanilla is the sole qualified flavor on both. (Android row 1.)
- **portability:** portable.
- **parity plan:** Extend desktop ServerRuntimeFiles.kt to resolve DB names through shared
  ClientFlavors (already in shared-sources.json) like android ServerRuntimeFiles.kt:363-375; add a
  flavor-routing case to DesktopServerRuntimeFilesTest. No new manifest entries.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-93 — Per-flavor conf emission & database-name resolution
- **gap:** Android's `ServerRuntimeFiles` resolves the four database names through the flavor
  registry — `serverDatabases()` at `android/.../server/ServerRuntimeFiles.kt:362-378` calls
  `ClientFlavors.qualified()`, fails loud unless exactly one flavor is qualified, and takes the
  names from `ClientFlavor.databases` (`client/ClientFlavor.kt:101-104`). The desktop twin
  (`desktop/.../server/ServerRuntimeFiles.kt:63` for realmd.conf; `:108-111` for mangosd.conf)
  hard-codes `classicrealmd`/`classicmangos`/`classiccharacters`/`classiclogs`, and
  `DesktopRuntimeBackend.kt:169` hard-codes `"classicrealmd"` again for realmlist projection. The
  shared `client/ClientFlavor.kt` is already compiled on desktop (shared-sources.json:25) but no
  conf/auth path calls it (grep: only `UserAccountStore.kt` references ClientFlavors). When a
  second flavor qualifies, desktop conf emission stays vanilla-named with no fail-loud gate.
  (Android rows #1, #19.)
- **portability:** portable.
- **parity plan:** Extend `desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt`
  with the Android `serverDatabases()` twin: resolve `ClientFlavors.qualified().single()` with the
  same fail-loud copy, thread `DatabaseNames` through `realmdConfig`/`worldConfig`, and take the
  realmlist-projection database from the same source in
  `DesktopRuntimeBackend.projectRealmEndpoint` (`:169`). Update the golden pins in
  `DesktopServerRuntimeFilesTest` (`worldConfigPinsTheFullConfBody`) and the
  `projectRealmEndpointUpdatesTheRealmlistRow` leg of `DesktopRuntimeBackendTest`. Note: re-seeding
  for a second flavor also needs re-derived seed transcripts
  (`tools/build_win_sqlite_seam.py` already fails loud for non-vanilla), so this lands as
  routing-now + gate-now, names-change-later. *(hunt-r1-lens6.md)*
- **effort:** M — **severity:** P1

### DESK-94 — SRP6 logon client identity
- **gap:** Desktop hard-codes the vanilla identity in three places: `AuthGate.kt:265`
  (`BUILD_5875 = 5875`), `:293-297` (hard-coded reversed platform/os/locale strings), `:357`
  (26-byte proof-response frame assumption); `RpWowClient.kt:46` (`BUILD_5875`) and `:168` (writes
  it into the logon challenge). Android routes all of this through the registry:
  `ClientFlavor.build` (`:27`), `versionResource` (`:29`), `realmdAcceptedBuilds` (`:52-57`), and
  `proofResponseBytes` (`:59-60`, "vanilla: 26 bytes … null until verified"), i.e. the frame length
  is per-flavor data, not a constant. Desktop ships the shared registry (shared-sources.json:25)
  but never consults it — the pins are stale the day any other build/flavor is qualified.
  (Android row #1 per-flavor build/version/SRP6 proof bytes.)
- **portability:** portable.
- **parity plan:** In `AuthGate.kt` and `RpWowClient.kt`, replace `BUILD_5875` and the hard-coded
  26 with values resolved from `ClientFlavors.qualified().single()` (same exactly-one-qualified
  fail-loud gate as DESK-93); keep the reversed platform strings as the vanilla constant only until
  the registry grows a per-flavor field for them (mirror the Android `AUTH_CHALLENGE_OPCODE`
  shared-constant pattern). `authGate`/`WhisperGate` gradle tasks re-verify against the live
  realmd; add a unit pin that the resolved build equals the lockfile's cmangos vanilla lane.
  *(hunt-r1-lens6.md)*
- **effort:** S — **severity:** P2

### Area 19 — Storage

### DESK-129 — Hard separation of storage roots (scratch staging / user-facing exports)
- **gap:** Android `StorageRoots.kt` separates six roots: realm/database/content/runtime plus
  `scratch` (:55, staging space) and `exports` (:62, "user-facing output via SAF; never the live
  DB") — inventory row 44. `DesktopStorageRoots.kt:13-34` defines only
  realm/database/content/runtime/settings — no scratch, no export. The registered import features
  (DESK-1/2/3/…) need a staging/scratch root for extraction+verification before publish, and the
  registered realm-archive features (DESK-7/33/38/60/101) need a user-facing export root; without
  the root policy each twin will invent its own location, and the "exports are never the live DB"
  separation invariant has no desktop home. The SAF locations are android-specific; the ROOT POLICY
  is not.
- **portability:** portable (locations differ — e.g. `%LOCALAPPDATA%\PocketRealm\scratch` and a
  Documents-facing export dir or a user-picked folder — but the separation policy ports).
- **parity plan:** Add `val scratch: File = File(root, "scratch")` and
  `val exports: File = File(root, "exports")` to `DesktopStorageRoots.kt` (+ `ensureDirectories`
  list), mirroring the Android root names; wire the future import/archive twins to stage in
  `scratch` and write user-facing ZIPs only to `exports`. Test: extend the roots assertions in the
  desktop suite (path-policy leg, no natives). *(hunt-r3-lens4.md)*
- **effort:** S — **severity:** P3

### DESK-130 — Detection of shareable Android files the desktop never picks up
- **gap:** `tests/test_desktop_shared_manifest.py` fences the manifest in one direction only: it
  fails when a LISTED file vanishes (`path.is_file()`, :80) or stops being android-free (:82-92),
  and that the build consumes the manifest (:108-114). Nothing alarms when a NEW android-free file
  appears in the Android tree and is never listed — the exact files the hunt needed to notice by
  hand: `ui/AccountStatusPresentation.kt`, `client/ClientPeIdentity.kt`, `ingame/ManagedConfigPolicy.kt`
  (all verified android-free; none in `shared-sources.json`). The shared set grows only by manual
  manifest edits, so every future pure-Kotlin Android file silently diverges on desktop until a
  human re-audits — the accumulation mechanism behind half the register.
- **portability:** portable.
- **parity plan:** Extend `tests/test_desktop_shared_manifest.py` with a drift scan: walk
  `android/app/src/main/java/com/pocketrealm` for `.kt` files that pass the existing android-free
  regexes but are NOT in `shared`, and assert each against an explicit allowlist in the test
  (entries documented as "needs desktop twin" — e.g. SafClientScanner, ManagedClientImporter,
  ClientDisplayHost — vs "shareable candidate"); a new shareable file then fails the test until it
  is either shared or allowlisted with a reason. No build changes; pytest-only.
  *(hunt-r3-lens4.md)*
- **effort:** M — **severity:** P3

### DESK-131 — Storage roots & durable-file primitives (StorageRoots.kt, fs/DurableFiles.kt)
- **gap:** Android hard-separates its roots: `StorageRoots.kt:31-34` splits the database root into
  `databaseDatadir`/`databaseRun`/`databaseSnapshots`, `:55` adds a transient `scratch` root
  ("cleared by OS under pressure; never holds durable realm state" — the importer/extraction
  workspace), `:62` adds an `exports` root ("the ONLY place realm-derived data leaves internal
  storage"), and `:64-91` provides `verify(): Report` (per-root exists + usableBytes) used by
  diagnostics and the wizard. The desktop twin (`desktop/.../desktop/DesktopStorageRoots.kt:13-45`)
  has root/realm/database/content/runtime/settings + logs + sqliteDatadir — **no scratch, no
  exports, no database run/snapshots split** — and `DiagnosticsScreen.kt:70-86` reports only
  exists/MISSING plus one root-level usable-space line. Every registered import/archive/backup/
  export gap (DESK-63-66, 100, 101) will need these roots, and the desktop currently stages
  transient work (seeder temp files, seeder's `partialFor` staging) ad hoc next to durable state.
  (Android row #44's root taxonomy; `fs/DurableFiles.kt`'s same-fs temp+fsync+rename primitive is
  currently reimplemented inline in three desktop places.)
- **portability:** portable.
- **parity plan:** Extend `DesktopStorageRoots.kt` with `scratch` (under
  `%LOCALAPPDATA%\PocketRealm\runtime\scratch`, purged on clean start — Windows has no OS
  cache-pressure clearing, so the app must own it), `exports` (`<root>\exports`, the only
  user-facing output root for realm archives/support bundles), and `database/snapshots` +
  `database/run` (consumed by the DESK-100 snapshot store); mirror `verify(): Report` (exists +
  usableBytes per root) into the Diagnostics storage card (`ui/DiagnosticsScreen.kt:66-88`).
  Optionally factor the three private atomic-write implementations (`ServerRuntimeFiles.secureWrite`,
  `DesktopSettingsStore`, seeder) onto one shared `fs/DurableFiles` twin. *(hunt-r3-lens6.md)*
- **effort:** S — **severity:** P3

### Area 20 — Onboarding & app shell

### DESK-16 — First-run tutorial & setup shortcuts
- **gap:** Absent on desktop: grep `tutorial|firstrun` over `desktop/src` → 0 hits. The desktop
  settings schema even carries `setupComplete` (Settings.kt:18,47,72) but nothing consumes it in
  the UI — no first-run flow, no final-step jump to import/picker, no replayable Settings → Setup
  entry. (Android row 45.)
- **portability:** portable.
- **parity plan:** New `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/FirstRunTutorial.kt`
  mirroring the Android flow (ui/FirstRunTutorial.kt:14-33, desktop copy adjusted: the final step
  lands on the client-folder picker since import parity is DESK-1/2); DesktopAppModel sets
  `setupComplete` on completion and gates the tutorial on first launch; replayable setup card on
  SettingsScreen. Test: model-level gating (tutorial shown iff !setupComplete).
  *(hunt-r1-lens1.md)*
- **effort:** S — **severity:** P3

### DESK-31 — First-run tutorial & setup shortcuts (setupComplete flow)
- **gap:** `Settings.Snapshot.setupComplete` exists (`desktop/.../storage/Settings.kt:18`) but
  nothing reads or writes it (grep: zero consumers outside the field declaration); the desktop
  shell (`Router.kt:8-27`) has no first-run flow shepherding folder pick → account → start, and no
  replayable setup entry the way Android's Settings → Setup does. (Android #45.)
- **portability:** portable.
- **parity plan:** Compose Desktop first-run dialog on Home shown when `!setupComplete`: pick client
  folder (existing FileDialog flow) → optional account card → persist `setupComplete` via
  `updateSettings`; add the replay entry to `ui/SettingsScreen.kt`. *(hunt-r1-lens2.md)*
- **effort:** S — **severity:** P3

### DESK-49 — First-run tutorial & replayable setup shortcuts
- **gap:** Absent on desktop: grep finds no first-run/tutorial code; Router lands on Home with no
  guided path to (1) pick/import the client, (2) prepare world data, (3) start the realm — the
  three steps a fresh desktop install actually needs (DESK-34/55 and DESK-44/69/102 make them
  in-app). (Android #45: `ui/FirstRunTutorial.kt:14-33`.)
- **portability:** portable — pure UI flow; no Android primitive involved.
- **parity plan:** `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/FirstRunTutorial.kt` overlay
  shown while the settings first-run flag is unset: pick client folder → import/verify (DESK-34) →
  prepare data (DESK-44) → start realm; replayable from a Settings setup card; compose-free step
  logic kept twin-able. *(hunt-r1-lens3.md)*
- **effort:** S — **severity:** P3

### DESK-92 — First-run tutorial & replayable setup
- **gap:** Desktop Home has a passive `ActiveSetupCard` (`HomeScreen.kt:232-252`, status +
  shortcuts) and a folder picker, but no guided first-run flow and no replay entry; `SettingsScreen.kt`
  has no Setup card. A fresh desktop user gets no ordered path (pick client → seed → account →
  start). (Android #45 — final button lands on import with picker open; replayable from
  Settings → Setup.)
- **portability:** portable.
- **parity plan:** New `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/FirstRunTutorial.kt`
  Compose dialog sequence over `DesktopAppModel` (detect unseeded datadir via
  `DesktopRuntimeBackend.preflight`, open `ClientDirPicker`, offer `seedRealmData` status, end on
  the account card), shown when no client dir is configured and no account exists; replay button in
  `SettingsScreen` next to About; copy mirrored from the Android tutorial minus SAF/Android
  wording. *(hunt-r1-lens6.md)*
- **effort:** S — **severity:** P3

### DESK-105 — First-run tutorial
- **gap:** No desktop tutorial: HomeScreen has an active-setup card and folder picker but no
  fresh-install flow whose final step opens the picker (android FirstRunTutorial.kt:14-33), and no
  Settings → Setup replay card (android SettingsScreen.kt:1271-1287). Router has no tutorial state.
  (Android #45.)
- **portability:** portable.
- **parity plan:** Add desktop `ui/FirstRunTutorial.kt` (pick client folder → prepare world data →
  create account) driven by DesktopAppModel setup state, final step opening the folder picker; add
  a Settings → Setup replay card mirroring android SettingsScreen.kt:1271-1287.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-135 — Client selection affordance (managed-generation switch analog)
- **gap:** The ONLY client-folder picker in the desktop app is `HomeScreen.ClientDirPicker`,
  rendered strictly when `resolveClientDir()` returns null (`HomeScreen.kt:62`, `:270-272` —
  "if (clientDir == null)") — i.e. only while NO stored folder and no default contains a WoW.exe.
  Once a valid folder is stored, the picker disappears, and `SettingsScreen` (read in full) has no
  client-folder control at all. A user who moves or reinstalls WoW elsewhere, or stored a folder
  whose WoW.exe is the wrong install, can never re-point the app except by hand-editing
  `%LOCALAPPDATA%\PocketRealm\settings\settings.json`. On Android the equivalent operation exists
  in-app: switching clients means importing a new managed generation that replaces the active
  pointer (inventory rows 2/6; the import lanes are DESK-1/2/34/55, but the selection/switch
  affordance itself was unnamed). It also hard-blocks DESK-52's wrong-folder case: the identity
  gate can refuse a folder, but the UI offers no way to replace it.
- **portability:** portable.
- **parity plan:** Add a "Change game folder…" `OutlinedButton` next to the "Game client: ready
  (path)" line in `ActiveSetupCard` (HomeScreen.kt:263-269) that opens the existing
  `ClientDirPicker` worker-thread dialog regardless of current state, writing through the same
  `model.updateSettings { it.copy(clientDir = ...) }` path (:107-109); optionally mirror it on
  SettingsScreen. When DESK-34/55's managed-generation import lands, this control becomes the
  generation switcher. Test: a UI-less leg asserting the picker affordance renders for both null
  and non-null resolved dirs (pure state check). *(hunt-r4-lens4.md)*
- **effort:** S — **severity:** P3

### Area 21 — Android-specific platform lanes (no desktop port)

### DESK-70 — Wine / Box64 ARM runtime provisioning, graphics generations & retirement
- **gap:** No desktop counterpart — and none is wanted: `WineRuntimeStore`/`ArmRootfsProvisioner`/
  `ArmTranslationBackend` exist to run x86 WoW on ARM Android (Winlator rootfs, Wine prefixes);
  Windows runs native WoW.exe via ProcessBuilder (DesktopRuntimeBackend.kt:503-527).
  (Android row 9.)
- **portability:** android-specific — the whole lane exists to run x86 WoW on ARM Android.
- **parity plan:** No port: Wine/Box64/Winlator provisioning exists to run x86 WoW on ARM Android;
  Windows launches native WoW.exe (DesktopRuntimeBackend.kt:503-570). Document the lane as
  Android-only in desktop-inventory. *(hunt-r1-lens5.md)*
- **effort:** M — **severity:** P3

### DESK-72 — Embedded display host (Winlator X server) & display profiles
- **gap:** No desktop counterpart: `ClientDisplayHost`/`IntegratedClientDisplay`/surface/input
  bridge exist to render the game inside the Android app; on Windows WoW.exe renders its own OS
  window (desktop CLIENT component, desktop-inventory line 45). Teardown ordering is already
  covered by Main.kt:58-99. (Android row 11.)
- **portability:** android-specific — an embedded X server exists only where the game must render
  inside the launcher app.
- **parity plan:** No port: the Winlator X-server embed exists to show the game inside the Android
  app; WoW.exe renders its own OS window on Windows. Desktop-inventory note only.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-87 — Packaged Vulkan driver / renderer catalog & auto selection
- **gap:** Android-GPU plumbing (Adreno drivers, APK-installed set, 16KB-aware probes); Windows
  renders through the vendor driver and desktop settings deliberately carry no renderer fields
  (desktop-inventory line 65). (Android row 37: closed APK-installed set generated from
  `schemas/vulkan-driver-catalog.json`, vendor-following Auto, fail-closed system-Vulkan probe.)
- **portability:** android-specific — Turnip/Vortek catalogs, vendor Auto and the system-Vulkan
  probe target Android GPUs.
- **parity plan:** No port: Windows uses the vendor driver. Keep renderer fields out of desktop
  settings (already true). *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-88 — User-imported Vulkan drivers: import gate, quarantine, community downloads
- **gap:** aarch64 ELF validation, 16 KB page alignment, Winlator crash quarantine and community
  driver downloads are Android-GPU plumbing with no Windows meaning. (Android row 38: ELF64
  aarch64 + 16 KB page alignment + one-driver-per-import + 256 MiB cap; two consecutive early
  deaths ⇒ quarantine & reset to Auto; pinned digest-verified community list. The row-10
  crash-guard fold timestamps — DESK-140 — are this lane's mechanism.)
- **portability:** android-specific — aarch64/16KB-page/Winlator quarantine mechanics.
- **parity plan:** No port: user driver validation/quarantine and community driver downloads are
  Android-GPU plumbing. Desktop-inventory note only. *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

### DESK-90 — Capability report & :pkg packaging-experiment console
- **gap:** No desktop counterpart: the experiment console drives the Android `:pkg` isolated
  process (PkgIsolationService, native-abort-survives-restart, IPC parcelables) — Android
  packaging validation. The hardware-facts half is minor and partly covered by the desktop
  DiagnosticsScreen storage health. (Android row 41.)
- **portability:** android-specific — the `:pkg` isolated-process lane validates Android APK
  packaging.
- **parity plan:** No port of the `:pkg` experiment lane. Optional P3: a DiagnosticsScreen
  capability card using shared BuildConfig/lockfile ids — only if a later round wants it.
  *(hunt-r1-lens5.md)*
- **effort:** S — **severity:** P3

---

## Explicitly checked and NOT gaps (from the hunt rounds)

Verifications recorded by the hunters that produced **no** register entry (so the ledger's
"present on desktop" rows rest on evidence, not omission):

- Flavor registry compiled byte-identical via the shared manifest; multi-flavor TBC/WotLK is
  `qualified=false` on Android and fails loud vanilla-only in the desktop seam tool — parity holds.
  (State as of the 2026-09-30 hunt — SUPERSEDED since: the W4 flip qualified all three lanes,
  decision D86, and the version sites moved to 0.110.0 at W7.)
- `ChatterPowerMonitor`: desktop stages the same rung constants pinned to NORMAL (plugged-in
  substitution); vanilla-tweaks lockfile pins identical cross-platform
  (`fbbe31add71b23602d981d70f1a58520fc349b47`, 1.6.0); world-DLL sha256 matches BuildConfig + win
  lockfile; seed/package provenance identical; `AppVersion` 0.103.0-alpha == Android versionName.
- NativeRuntimeFreshness has **no main consumer on either platform** (only Gradle enforcement on
  Android → registered as DESK-115's desktop leg, not a double-counted runtime gap).
- Local accounts (row 17), bot population/editor cores (rows 26/28), realmd/world components
  (rows 19/20 core), SQLite control plane (row 22), cloud LLM lane + per-preset speech overrides
  (row 30, verified end-to-end in hunt-r3-lens1.md), settings store/roots core (row 44, verified
  fsync-before-ATOMIC_MOVE at DesktopSettingsStore.kt:34,46), diagnostics core (row 40), and the
  RP/protocol harness are present on desktop; only the slivers named in this register are missing.
- Support-bundle redaction with the LLM-key canary verified sound; `Win32AutoLogin` input mapping
  via live keyboard layout verified honest (VkKeyScanW, non-echoing failures); graceful world stop
  saves first (`DurableRuntimeSupervisor.kt:907`); supervisor journal is a complete twin
  (corrupt→ERROR/dirty, fsync+ATOMIC_MOVE); the desktop SQLite JNI seam is a complete, deliberate
  twin (7/7 seam tests green).

---

## Feature coverage ledger

One line per Android-inventory feature (45 rows). Verdicts: **portable** (the gap/feature has a
desktop path — covered by the named DESK entries) or **android-specific** (with the reason).

- [x] Game-flavor foundation (vanilla / TBC / WotLK registry) — portable: covered by DESK-62, DESK-93, DESK-56, DESK-94, DESK-128
- [x] Client import — folder lane (read-only SAF copy & verify) — portable: covered by DESK-1, DESK-20, DESK-34, DESK-55, DESK-63, DESK-97
- [x] Client import — archive lanes (.zip / .7z / .rar staging, detection, extraction) — portable: covered by DESK-2, DESK-20, DESK-35, DESK-55, DESK-64, DESK-97
- [x] Client import — Inno Setup installer lane (on-device unpacking) — portable: covered by DESK-2, DESK-35, DESK-65, DESK-97
- [x] Import journal, resume, storage planning, watchdog & process metrics — portable: covered by DESK-3, DESK-66
- [x] Managed client generations (immutable publication + atomic pointers) — portable: covered by DESK-1, DESK-20, DESK-34, DESK-55, DESK-67, DESK-97, DESK-135
- [x] Client identity verification & VAL gates (folder + archive) — portable: covered by DESK-1, DESK-21, DESK-36, DESK-52, DESK-68, DESK-97
- [x] Server data preparation (DBC / maps / vmaps / mmaps) + prepared-data store — portable: covered by DESK-44, DESK-69, DESK-102, DESK-128
- [x] Wine / Box64 ARM runtime provisioning, graphics generations & retirement — android-specific: exists to run x86 WoW on ARM Android (Winlator rootfs/Wine prefixes); Windows launches native WoW.exe (DesktopRuntimeBackend.kt:503-527); registered as DESK-70
- [x] Client runtime service (:client) — Wine session lifecycle & launch proofs — portable: covered by DESK-13, DESK-29, DESK-61, DESK-71, DESK-107, DESK-112, DESK-114, DESK-121, DESK-126, DESK-139, DESK-140 (Wine/ALSA audio-env and FGS transport mechanics are the android-specific trim)
- [x] Embedded display host (Winlator X server) & display profiles — android-specific: the embedded X server exists to render the game inside the Android app; WoW.exe renders its own OS window on Windows; registered as DESK-72
- [x] Input injection contract (gamepad/keyboard/mouse/touch → X events) — android-specific: the contract's sink is the embedded Winlator X server (registered as DESK-73); the portable subset (gamepad profiles + 211-entry vanilla binding catalog) is covered by DESK-4
- [x] Controls screen & input profiles (layouts, per-button editing, tuning) — android-specific: on-screen control layouts/dead zones/trigger tuning exist for touch/glass play (registered as DESK-74); the portable residue (input profiles + WowVanillaBindingCatalog) is covered by DESK-4
- [x] Touch overlay (on-screen controls, two layouts, move mode) — android-specific: pure touch UI with no desktop meaning; registered as DESK-75
- [x] IME text entry into the game — android-specific: the IME bridge serves Android soft keyboards; desktop windows get native keyboard focus; registered as DESK-76
- [x] Auto-login (typed credentials at the login window) — portable: covered by DESK-30, DESK-57, DESK-133, DESK-136, DESK-138 (desktop also ships Win32AutoLogin + the shared AutoLoginPolicy today)
- [x] Local account management (create/verify/privilege/clear) — portable: covered by DESK-120, DESK-125, DESK-136 (the store/provisioning feature is present on desktop — UserAccountStore twin, provisionAccount at DesktopRuntimeBackend.kt:273-330; the gaps are failure-copy and input-validation legs)
- [x] Runtime supervisor (session phases, durable journal, recovery, foreground policy) — portable: covered by DESK-29, DESK-71, DESK-98, DESK-109, DESK-111, DESK-113, DESK-114, DESK-137 (shared DurableRuntimeSupervisor + journal twin present)
- [x] Realmd (login server) runtime service (:realm) — portable: covered by DESK-93, DESK-127 (the REALM component itself runs in-process via the shared RealmNative shim; gaps are flavor routing and status telemetry)
- [x] World (gameplay server) runtime service (:world) — portable: covered by DESK-25, DESK-77, DESK-116, DESK-117, DESK-122, DESK-123 (WORLD component runs in-process via the shared WorldNative shim)
- [x] Database service & engine (MariaDB datadir ownership, durability) — portable: covered by DESK-5, DESK-47, DESK-78, DESK-98, DESK-106, DESK-108, DESK-115 (desktop is the declared SQLite-only v1 lane)
- [x] SQLite provider & dual-provider migration window (experimental) — portable: covered by DESK-106, DESK-108 (the SQLite control plane itself is shared and used: DesktopSqliteSeeder.kt:3-107, AuthGate.kt:68)
- [x] Database migration ledger & seals — portable: covered by DESK-19, DESK-53, DESK-79, DESK-98, DESK-106
- [x] Backups & transactional restore verification — portable: covered by DESK-6, DESK-18, DESK-37, DESK-100
- [x] Realm data archive (move characters between installs) — portable: covered by DESK-7, DESK-33, DESK-38, DESK-60, DESK-101
- [x] Bot population system (built-in profiles, custom presets, selection) — portable: covered by DESK-116, DESK-118, DESK-123, DESK-124, DESK-134 (all bots/* profiles/policy/presets/selection files are shared and used)
- [x] Bot admission control & automatic load protection — portable: covered by DESK-8, DESK-23, DESK-43, DESK-99
- [x] Bots editor UI (Basics / Population / Behaviour / AI tabs, advanced tuning) — portable: covered by DESK-118, DESK-124 (4-tab editor + per-preset LLM speech overrides present; the advanced-tuning lane is the gap)
- [x] LLM on-device runtime (model registry, staging download, prompt pack) — portable: covered by DESK-9, DESK-26, DESK-28, DESK-48, DESK-80, DESK-103
- [x] LLM runtime policy, cloud lane & world-chatter power monitor — portable: covered by DESK-81 (LlmRuntimePolicy/CloudLaneConf/BotLlmSpeech shared with a verbatim conf twin; only battery pacing is missing)
- [x] AI bot LLM screen (simple tier / advanced engine settings) — portable: covered by DESK-9, DESK-48, DESK-80, DESK-103 (external lane present; the on-device source choice is the gap)
- [x] Add-on manager (recommended/my add-ons/browse catalog/GitHub install, updates, rollback) — portable: covered by DESK-10, DESK-17, DESK-39, DESK-54, DESK-82, DESK-95
- [x] Android Port built-in add-on & persisted-data migrations — portable: covered by DESK-83, DESK-11, DESK-40, DESK-54, DESK-95 (audit-corrected from android-specific: all four AndroidPort artifacts are android-free — census grep 0 hits — and the migration is plain JVM file/JSON work over the client WTF tree)
- [x] Add-on runtime projector & input safe mode — portable: covered by DESK-11, DESK-40, DESK-54, DESK-84, DESK-95
- [x] In-game settings engine (WTF codecs, edit lock, queued delivery) — portable: covered by DESK-12, DESK-22, DESK-41, DESK-50, DESK-59, DESK-85, DESK-96
- [x] In-game settings UI (stock settings panels + bindings editor) — portable: covered by DESK-12, DESK-22, DESK-41, DESK-86, DESK-96
- [x] Packaged graphics driver & renderer catalog, auto selection, capability probe — android-specific: Turnip/Vortek catalogs, vendor Auto and the system-Vulkan probe target Android GPUs; Windows uses the vendor driver; registered as DESK-87
- [x] User-imported Vulkan drivers (import gate, registry, crash quarantine, community downloads) — android-specific: aarch64 ELF/16KB-page validation, Winlator crash quarantine and community driver downloads are Android-GPU plumbing; registered as DESK-88 (its crash-guard mechanism is DESK-140)
- [x] Client tweaks (vanilla-tweaks patcher config + nearby-loot patch) — portable: covered by DESK-13, DESK-27, DESK-46, DESK-58, DESK-89, DESK-132
- [x] Diagnostics screen, structured log & redacted support bundle — portable: covered by DESK-107, DESK-110, DESK-112, DESK-119, DESK-120, DESK-121, DESK-126 (storage health/log tail/support bundle present; gaps are crash evidence, typed log ring, bundle header)
- [x] Device capability report & packaging experiments — android-specific: the :pkg isolated-process experiment console validates Android APK packaging; registered as DESK-90
- [x] In-app updates (check + resumable install) — portable: covered by DESK-14, DESK-32, DESK-45, DESK-91, DESK-104 (PackageInstaller/session mechanics are the android-specific trim)
- [x] LAN host & join (experimental) — portable: covered by DESK-15, DESK-24, DESK-42 (shared RuntimeTopology/ClientRealmEndpointProjection and supervisor LAN modes already compile on desktop)
- [x] Settings store, storage roots & durable file primitives — portable: covered by DESK-129, DESK-130, DESK-131 (the store/roots core is present and verified: fsync-before-ATOMIC_MOVE at DesktopSettingsStore.kt:34,46; roots at DesktopStorageRoots.kt:17-43; shared fs/FileDigests.kt)
- [x] First-run tutorial & setup shortcuts — portable: covered by DESK-16, DESK-31, DESK-49, DESK-92, DESK-105

---

*Register assembled from `out/desktop-parity/hunt-r1-lens1.md` … `hunt-r6-lens6.md` (36 lens
files), `out/desktop-parity/android-inventory.md`, and `out/desktop-parity/desktop-inventory.md`.
Convergence: hunt rounds 3–6 re-walked all 45 Android inventory rows against the growing register
and returned zero new gaps; every row in the ledger above is ticked. Post-hunt audit (2026-09-30):
DESK-83 reclassified android-specific → portable with source-verified evidence (census grep + line
reads recorded in the entry); the other ten android-specific entries (DESK-70/72/73/74/75/76/87/
88/90/140) were re-verified and stand.*
