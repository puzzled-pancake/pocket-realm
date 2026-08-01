# Current project state

Last verified implementation commit: `O05 — G0 production packaging, process isolation, and capability record` (this commit, evidence-pipeline-fixed)

Active feature: `none`

Current gate: `G0 — architecture and production packaging COMPLETE` (`O06` next — G1 direct x86 Wine)

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
- Wine, MariaDB, realmd/mangosd production integration, importer, bots, and mobile UX remain pending at their gates (O06-O22).
- The full report X/FUN/FLT/SOAK gates remain pending.

## Blockers

O06 Phase 1 has an **active blocker**: the production app-process execve of glibc
ELFs returns EACCES (see below). A user-owned build-5875 client becomes required
at O07; named AVD/device and physical qualification inputs are required at their
later gates. **Outcome B is NOT yet proven** — the loader chain is demonstrated
only under `run-as` (supporting evidence), not from the production app process.
Downstream O06 implementation (the rootfs-bind / namespace strategy to satisfy
S-1 from the app process) is **not authorized** until S-1 passes on 4KB.

## O06 Phase 1 — Wine feasibility spike status (in progress)

The S-1/S-2/S-3 on-device spike is running on the Modern lane (API 35, 4KB).
S-1 acceptance (effective APK-managed loader proven for wine + wineserver + every
native child, from the **production app process**) is NOT yet met. Findings:

- **S-5(0) corrected diagnosis (a512b71 → S-5 correction commit):** the earlier
  "SELinux blocks execve" finding was based only on exit 159. A ptrace+siginfo
  diagnostic captured the actual cause: `si_code=1 (SYS_SECCOMP)`,
  `syscall=21 (access)`, `arch=AUDIT_ARCH_X86_64`. Android's untrusted_app
  seccomp filter kills the glibc loader on its first `access()` probing call.
  This is NOT an SELinux execve denial.
- **S-5(a) Bionic trampoline:** a separate APK-managed Bionic PIE
  (`libwine_trampoline.so`) that re-execve's the glibc loader. Built + run; hits
  the identical access() seccomp trap once it execs the loader. Confirms the
  block is on the glibc loader's syscalls, not on how we arrive at it.
- **Narrow GLIBC_TUNABLES fallback:** does NOT apply — no tunable suppresses the
  loader's access() probing calls.
- **S-5(b) proot (termux/proot@a89b3732, Bionic PIE, 295KB):** built from source
  (Python reimplementation of the GNUmakefile — Windows has no make). proot
  starts from the production app domain (`proot --version` → 5.1.107.89,
  process_vm=yes, seccomp_filter=yes; runs `/system/bin/id` fine). Under `run-as`
  the full loader chain is PROVEN under proot: `calling init: libld_linux_x86_64.so
  / libc.so.6 / libdl.so.2 / libpthread.so.0 / libgcc_s.so.1` → `calling fini:
  ntdll.so` → `wine-11.14`. No SIGSYS — proot's syscall interception defeats the
  access() seccomp trap. The `-b <app_tmp>:/tmp` bind handles wineserver's
  hardcoded `/tmp/.wine-<uid>` path.
- **App-process exec restriction (remaining blocker):** in the production app
  process, proot's execve of a glibc ELF (libwine_preloader.so / the loader)
  returns `EACCES`. Bionic ELFs (libproot.so, libwine_trampoline.so) execve
  freely in the same directory; glibc ELFs (PT_INTERP=/lib64/ld-linux-x86-64.so.2)
  do not. proot cannot bypass this because it relies on the kernel's execve for
  the initial program (ptrace interception applies only to syscalls AFTER exec).
  The `run-as` path succeeds (different exec context) but is supporting evidence,
  not acceptance.

**Spike outcome: NOT YET DETERMINED (not A, not B, not C).** No outcome is
finalized. The Wine loader chain mechanism is demonstrated under proot via
`run-as`, but the production app-process has an additional exec restriction on
glibc ELFs that proot's ptrace model does not address. O06 stays active;
acceptance is NOT weakened. The remaining work (PROOT_LOADER pinning, default vs
PROOT_NO_SECCOMP, and if needed the glibc access→faccessat rebuild) is in-flight
before any outcome (A/B/C) is recorded. S-2/S-3 are gated on S-1 passing from
the production app process.

## Next action

Run `python3 scripts/next_feature.py --activate`. It must select **O06 - G1 direct x86 Wine self-test, prefix, surface, and input bridge**.

O06 should start by reading report sections 8.4 (PKG-03/PKG-04), 15.1-15.7, and 20.2-20.3 (X2), then implement `ClientRuntime` + an `x86DirectWine` backend on the fixed x86_64 AVD using the production packaging model (Lane A) now selected.

## Session note

Replace this file with the current verified state; do not append a permanent diary. Git history is the durable record.
