# Current project state

Last verified implementation commit: `O05 — G0 production packaging, process isolation, and capability record` (this commit, evidence-pipeline-fixed)

Active feature: `O06 — G1 direct x86 Wine self-test, prefix, surface, and input bridge` (Phase-1 Wine feasibility spike, in progress)

Current gate: `G1 — direct x86 Wine spike (O06 Phase 1, in progress)`. G0 production packaging is complete (O05).

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

O06 Phase 1 has an **active item**: full S-2 acceptance (`wineboot --init` exit 0
+ prefix artifacts) is blocked on a Wine-runtime investigation. S-1 genuinely
PASSES on Modern 4KB; S-2's PE cache + repair + loader→wineboot transfer are
proven, but wineboot exits 1 before initializing the prefix. S-3 is wired but
not yet run on-device. **Outcome is NOT yet determined** (not A, not B, not C).
A user-owned build-5875 client becomes required at O07; named AVD/device and
physical qualification inputs are required at their later gates. **Downstream
O06 implementation is not authorized** until S-1, S-2, and S-3 all genuinely
pass on 4KB and the 16K repeat completes.

## O06 Phase 1 — Wine feasibility spike status (in progress)

The S-1/S-2/S-3 on-device spike runs on the Modern lane (API 35, 4KB).

### What is genuinely proven (narrower than full acceptance)

| Item | Status |
|---|---|
| SIGSYS cause | Proven: Android app-domain seccomp traps legacy `access(2)` (si_code=1, syscall=21) |
| Direct loader / trampoline | Proven blocked by the same seccomp rule |
| PRoot from production app domain | Proven operational (`proot --version` → 5.1.107.89) |
| APK-managed loader running Wine bootstrap | **PASS** Modern 4KB: `wine --version` exit 0 + wine-11 |
| **Full S-1 acceptance (process-tree loader proof)** | **PASS** Modern 4KB: wineserver (via `wineserver -p0` under proot) maps the APK-managed loader (17 APK mappings, `/proc/<pid>/maps` OK). `LOADER_PROVEN_VIA_PROOT_FULL_TREE`. |
| PE cache materialization + logical_path tree symlinks | **PASS** Modern 4KB: 1626 modules + core builtins reachable via cache-backed tree |
| PE cache verify + mismatch repair | **PASS** Modern 4KB: corrupt kernel32.dll → detected → rematerialized → canonical SHA re-matches |
| Loader transfers control to wineboot.exe | **PASS** Modern 4KB: `initialize program: wineboot` → `transferring control: wineboot` |
| **Full S-2 acceptance (wineboot --init exit 0 + prefix artifacts)** | **NOT yet proven** Modern 4KB: `wineboot --init` exits 1 with NO prefix artifacts (empty WINEPREFIX). The loader chain + PE cache are proven; the remaining failure is a Wine-runtime issue (wineboot.exe runs but errors before initializing the prefix). |
| Winlator Java sources + native transport | **Built + packaged**: libwinlator.so (413864B) in the APK; X-server compiles GDI-only |
| **S-3 X11/GDI window** | **NOT yet run** on-device (harness wired, libwinlator.so packaged) |
| **16 KB qualification** | **Not run** |

**S-1 genuinely PASSES on Modern 4KB.** Two structured runs: (a) `wine --version`
bootstrap (exit 0 + wine-11), (b) persistent `wineserver -p0` for the process-
tree proof — the wineserver process (glibc namespace, `--argv0 wineserver`) maps
the APK-managed `libld_linux_x86_64.so` (17 APK mappings, `/proc/<pid>/maps`
OK). The Bionic proot process is correctly excluded (acceptance applies to the
glibc/Wine tree).

**S-2 is partially proven; wineboot --init exit 0 is the open blocker.** The PE
cache (materialize + verify + mismatch repair), logical_path tree symlinks
(core builtins reachable), and loader→wineboot.exe transfer are all proven. The
remaining failure: `wineboot --init` exits 1 producing NO prefix artifacts (the
WINEPREFIX dir stays empty). The loader successfully initializes + transfers
control to wineboot.exe (LD_DEBUG shows both stages), but wineboot then errors
with no Wine debug-channel output before the prefix is created. This is a Wine-
runtime investigation (likely a missing env/path, a registry/zoneinfo need, or
an early wineboot path resolution under proot's namespace), not a loader/cache
chain defect.

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
  process_vm=yes, seccomp_filter=yes; runs `/system/bin/id` fine). The key to the
  app-process pass was PROOT_LOADER pinning: staging proot's in-tracee helper
  loader as APK-managed `libproot_loader.so` (nativeLibraryDir, immutable +x) and
  pointing PROOT_LOADER at it. Without this, proot extracted its embedded loader
  to PROOT_TMP_DIR (a noexec writable mount in the app domain) and failed. With
  it, the **bootstrap** (`wine --version`) resolves via the loader-as-guest-
  command form (`proot -v 5 -b <tmp>:/tmp -r / --link2symlink <apk-loader>
  --library-path <tree-libs> <wine> --version`): proot ptrace-intercepts the
  child's syscalls, translates access(2)→faccessat(2), and the `LD_DEBUG=libs`
  output shows `libld_linux_x86_64.so` / `libc.so.6` / `libdl.so.2` resolving via
  the symlink tree. Default proot mode sufficed (PROOT_NO_SECCOMP fallback was
  not needed). The `-b <app_tmp>:/tmp` bind handles wineserver's hardcoded
  `/tmp/.wine-<uid>` path. Direct + trampoline paths still fail
  (SIGSYS_SECCOMP_ACCESS / EACCES). The corrected `proot_run.c` preserves logical
  argv[0] via the glibc-loader space-form `--argv0` and snapshots the process
  tree (recursive descendants + global /proc scan for APK-loader mappings).
- **S-1 (PASS on Modern 4KB):** `LOADER_PROVEN_VIA_PROOT_FULL_TREE`. The
  bootstrap run (`wine --version`, exit 0 + wine-11) plus a persistent
  `wineserver -p0` run whose traced process maps the APK-managed
  `libld_linux_x86_64.so` (17 APK mappings, `/proc/<pid>/maps` OK). The Bionic
  proot process is correctly excluded from the glibc/Wine acceptance.
- **S-3 (libwinlator.so built + packaged):** the Winlator X-server (ca3d735)
  Java sources compile GDI-only, AND the pinned native transport is vendored
  UNMODIFIED, built (libwinlator.so, 413864B, 16K-aligned), and packaged in the
  APK. S-3 is strictly GDI/X11 (GLX/DRI3/MIT-SHM/Present advertisements removed).
  The harness (runS3) is wired; the on-device run is pending.

### Remaining work (before any outcome is recorded)

The review-driven corrections to S-1/S-2 are complete; S-1 PASSES. The open items:

1. **S-2 wineboot --init exit 0** (the active blocker): wineboot.exe loads (LD_DEBUG
   shows initialize + transfer) but exits 1 producing NO prefix artifacts. The
   PE cache + repair + logical_path tree + loader→wineboot transfer are all
   proven. The remaining failure is a Wine-runtime investigation — wineboot
   errors before initializing the prefix, with no Wine debug-channel output.
   Next steps: isolate whether it is a missing env (e.g. USER/USERNAME, TZ,
   WINEDLLPATH), a namespace/path issue under proot's `/` rootfs bind, or an
   early wineboot code path that needs a pre-existing file. S-2 acceptance is
   NOT met until wineboot --init exits 0 + the prefix artifacts exist.
2. **S-3 on-device run** (libwinlator.so is built + packaged; the harness is
   wired): run runS3 on Modern 4KB — start the X-server, launch the self-test PE
   with DISPLAY=:0 via proot, require `POCKET_SELFTEST_WINDOW` +
   `POCKET_SELFTEST_OK` + exit zero + a mapped client window.
3. **16K lane repeat**: rerun S-1/S-2/S-3 on the 16K AVD with the identical
   staged artifacts.
4. **Outcome**: record B only if all three pass on both lanes via PRoot;
   otherwise C with the exact remaining failure. Outcome A is not available
   (direct + trampoline already disproven).

(Items previously listed here — the shared PRoot launcher rework, full S-1
process-tree proof, pe_cache logical_path fix, evidence-driver single-artifact
fix, and provenance/source corrections — are DONE. S-1 PASSES; S-2's cache/PE/
repair chain is proven, with wineboot --init exit 0 the only open S-2 item.)

**Spike outcome: NOT YET DETERMINED (not A, not B, not C).** S-1 PASSES on
Modern 4KB. S-2's loader/PE/cache chain is proven but wineboot --init exit 0 is
blocked on a Wine-runtime investigation. S-3 is wired (libwinlator.so packaged)
but not yet run on-device. The 16K repeat is pending. O06 stays
active; acceptance is NOT weakened.

## Next action

O06 Phase 1 is **active** (the feature was already activated). The next action is
the in-flight corrections above: fix the shared PRoot launcher (logical argv[0]
via `--argv0`, structured result, recursive descendant kill), rework S-1 to
require real process-tree `/proc/<pid>/maps` proof for wine + wineserver + every
native child, rework S-2 to require `wineboot --init` exit zero + prefix
artifacts + PE-from-cache resolution + a mismatch-repair test, fix `pe_cache.c`
to honor `logical_path`, build the pinned native Winlator transport for S-3, make
the X-server GDI-only, then rerun S-1/S-2/S-3 on Modern 4KB and only then on the
16K AVD. Do not record Outcome B until both lanes genuinely pass via PRoot.

## Session note

Replace this file with the current verified state; do not append a permanent diary. Git history is the durable record.
