# Current project state

Last verified implementation commit: `O06 — Phase-1 direct x86 Wine feasibility, Outcome B` (this commit; paired 4 KB/16 KB evidence)

Active feature: `O06 — G1 direct x86 Wine self-test, prefix, surface, and input bridge` (Phase-1 feasibility spike complete; full implementation replanning authorized)

Current gate: `G1 — direct x86 Wine (O06 Phase 1 Outcome B; full O06 remains active)`. G0 production packaging is complete (O05).

Plan/reference alignment: `1 August 2026`

> Note: an earlier O05 commit (27eb1ad) was reopened after a review found the
> evidence pipeline had blocking gaps (variant-mistaken host driver, PKG-01 not
> setting LD_LIBRARY_PATH, null GL strings, non-reproducible API-28 page size,
> overstated PKG-06 coverage). This commit fixes all P1/P2 findings and
> regenerates every evidence artifact through one reproducible serial+variant-
> specific driver (tools/run_pkg_experiments.py).

## Source of truth

- `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx` is the canonical offline engineering reference; the adjacent PDF is the fixed-layout reading copy.
- `PLAN.md` is the repository execution overlay and connected-realm extension.
- `DECISIONS.md` contains adopted decisions, evidence-backed deltas, and the G0 overlay.
- `FEATURES.json` maps O05-O22 to report gates G0-G6 and named report sections.
- `docs/adr/ADR-013-g0-production-topology.md` records the G0 topology decision (Lane A).

## G0 result (O05, done)

The report's PKG-01/02/06 packaging experiments are complete on three AVD lanes:

| Lane | API | Page | PKG-01 | PKG-02 | PKG-06 |
|---|---|---|---|---|---|
| AVD-Legacy-x86_64 | 28 | 4 KB | executed + documented | CONTAINMENT_PROVEN | (research lane) |
| AVD-Modern-x86_64-v1 | 35 | 4 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |
| AVD-16K-x86_64-v1 | 35 | 16 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |

**ADR-013 confirms Production Lane A** (library-backed, supervised, fault-isolated native components in dedicated `android:process` fault domains) from this evidence. target-28 unpack/exec is explicitly not the production path. The O04 `libpocketrealm.so` in-process facade is retained as reusable library/control evidence; it is not the production world-server topology. The signed-code/mutable-data boundary: executable native code ships only via the signed APK; mutable datadir/prefix/cache/journal/database stays app-private and never executes.

## Verified work retained from O01-O05

### O01 - repository and provenance — done
Monorepo layout, pinned CMaNGOS/Playerbots/Classic-DB sources, licenses, flavor manifest, build bootstrap, proprietary-data exclusions.

### O02 - Android shell — done
Kotlin/Compose shell, navigation, settings, separated storage roots, structured logging, foreground service, notification Save & Exit action, supervisor state tests.

### O03 - native server build — done
CMaNGOS Classic and Playerbots build for x86_64 and `arm64-v8a`; 16 KB ELF alignment; x86_64 device smoke + ARM64 artifact checks pass.

### O04 - lifecycle/library experiment — done
Versioned C ABI + `libpocketrealm.so`; create/start/health/save/stop/destroy proven twice in-process; fatal-path containment. Retained as library-lane/control evidence, not the production topology (ADR-013).

### O05 - G0 production packaging — done
- **New native module `native/packaging`**: `libpocket_pkg_launcher.so` (PIE launcher, PKG-01) + `libpocketpkgtest.so` (JNI shim + dlopen-by-SONAME + deterministic abort, PKG-02/06). 16 KB-aligned; links only libc/libdl/liblog + libc++_shared.
- **Gradle packaging**: `stageNativeLibs` stages the full closure into the APK (no platform libs). Production variants `useLegacyPackaging=false` (.so stored uncompressed/page-aligned, loaded from APK); `pkgExperiment` variant `useLegacyPackaging=true` (extracted to nativeLibraryDir with +x). `aidl=true`; `INTERNET` permission added (loopback-only).
- **Kotlin**: `PkgNative` (real JNI glue), `CapabilityReport` (StorageManager.getAllocatableBytes + page size + abilist + real EGL-backed GL strings + host CPU/virt/GPU-mode), `IPkgIsolation` AIDL (cross-process :pkg binder), `PkgIsolationService` (:pkg process), `PackagingExperimentRunner` (PKG-01/02/06), `CapabilityScreen` + nav (4th destination).
- **Host tooling**: `tools/build_packaging.py`, `tools/capture_avd.py` (host-side capture+compare), `tools/run_pkg_experiments.py`.
- **Capability records**: `tests/avd/AVD-{Modern,16K,Legacy}*.json` match adb on equivalent fields (sdkInt, page size, full abilist/abilist32/abilist64, total RAM); allocatable/GL/host-CPU/virt/GPU-mode recorded separately.
- **ADR-013** confirms Lane A; DECISIONS.md G0 overlay; ABI header note updated.

## Last successful implementation checks

- `scripts/build_native.py --abi x86_64 --runtime --runtime-tests cmangos` -> ALL STAGES OK
- `tools/build_packaging.py --abi x86_64` -> libpocketpkgtest.so + libpocket_pkg_launcher.so (16 KB-aligned)
- `scripts/smoke_native.py --abi x86_64 --device --runtime` -> SMOKE OK (mangosd/realmd run, libpocketrealm.so lifecycle 2 cycles)
- `:app:assembleDebug` + `:app:assemblePkgExperiment` -> BUILD SUCCESSFUL (correct lib/x86_64/ closure, INTERNET present, zipalign -c -P 16 -v 4 passes)
- `:app:connectedDebugAndroidTest` on all 3 AVDs -> 16 tests, 0 failures (RealmSupervisor x10 + RealmServiceLifecycle x2 + PackagingExperiment x4)
- PKG-01 executed+documented on all 3 lanes (experiment variant); PKG-02 CONTAINMENT_PROVEN on all 3 lanes; PKG-06 two genuine 30-min runs (4 KB + 16 KB) captured
- Standalone `mangosd`/`realmd` remain unchanged when `POCKET_EMBEDDED` is off.

## Known gaps under the canonical report

- The `pkgExperiment` standalone-exec variant is G0 evidence only; production uses the library-backed Lane A model.
- Full Wine input/audio/lifecycle integration, MariaDB, realmd/mangosd production integration, importer, bots, and mobile UX remain pending at their gates (O06-O22).
- The full report X/FUN/FLT/SOAK gates remain pending.

## Blockers

The O06 Phase-1 feasibility spike has no remaining blocker. A user-owned,
unmodified build-5875 client becomes required at O07; named physical-device
inputs remain required at later release gates. O06 itself remains active: the
spike authorizes the full implementation plan, but does not claim the complete
input/audio/lifecycle acceptance of O06.

## O06 Phase 1 — Wine feasibility spike (complete: Outcome B)

All three spike measurements pass on both required API-35 x86_64 lanes using
the same APK-managed runtime and mutable app-private prefix/cache model:

| Measurement | Modern 4 KB | 16 KB |
|---|---|---|
| S-1 effective loader/process tree | **PASS** `LOADER_PROVEN_VIA_PROOT_FULL_TREE` | **PASS** `LOADER_PROVEN_VIA_PROOT_FULL_TREE` |
| S-2 PE cache, repair, `wineboot --init`, prefix | **PASS** `WINEBOOT_PE_RESOLUTION_PROVEN` | **PASS** `WINEBOOT_PE_RESOLUTION_PROVEN` |
| S-3 in-app X11/GDI window | **PASS** `X11_GDI_WINDOW_PROVEN` | **PASS** `X11_GDI_WINDOW_PROVEN` |

Authoritative full-run evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185902.PASS.log`
- `tests/avd/AVD-16K-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185645.PASS.log`

### Findings and retained adaptations

- Direct glibc-loader execution and the Bionic trampoline are blocked by the
  Android app-domain seccomp rule for legacy `access(2)` (`SYS_SECCOMP`, syscall
  21). PRoot 5.1.107.89 with its immutable APK-managed helper loader is therefore
  required for S-1 bootstrap/process-tree proof.
- S-2 and S-3 use the glibc adapter/shim after bootstrap. PE cache staging
  materializes and verifies 1,626 modules, detects deliberate corruption of
  `kernel32.dll`, repairs it, and re-matches the canonical SHA-256 before launch.
- The apparent PE/WoW64 exit-1 was traced to Wine's x86_64 syscall stubs calling
  a per-process dispatcher pointer at `0x7ffe1000`. On a 16 KB host that address
  shares the `0x7ffe0000` `MAP_SHARED` page used by `KUSER_SHARED_DATA`, allowing
  different Wine processes to overwrite one another's ASLR-relative pointer.
- The source patch moves the dispatcher to the next private 16 KB page at
  `0x7ffe4000`, makes x86_64 host-page handling dynamic, and rebuilds the paired
  Unix `ntdll.so`, PE `ntdll.dll`, and PE `win32u.dll`. The build script verifies
  that these are the only provider PE modules containing the original stubs and
  rejects mixed old/new output.
- The Winlator-derived X server is deliberately GDI-only; its pinned native
  epoll/SCM_RIGHTS transport is built as 16 KB-compatible `libwinlator.so`.

The spike outcome is **B**: the fixed AVD route is feasible, but a documented
fallback/adaptation path is required. Outcome A is unavailable because direct
loader/trampoline execution is disproven. Outcome C is disproven by the paired
4 KB/16 KB passes.

## Next action

Replan and implement the full O06 `ClientRuntime`/`x86DirectWine` feature using
the qualified fallback architecture. Retain the spike's immutable-code,
app-private-state, paired-runtime, process-isolation, and evidence requirements.
Do not mark O06 done until keyboard/mouse, focus, audio-off, clean close, forced
stop, diagnostics, bounded prefix/cache state, and the selected service/process
model meet the feature acceptance criteria.

## Session note

Replace this file with the current verified state; do not append a permanent diary. Git history is the durable record.
