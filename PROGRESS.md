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

O06 Phase 1 has **active items**. Review (post-S-5(b)) found that S-1 and S-2
were marked passed prematurely and must be corrected before the spike proceeds.
What is genuinely proven is narrower than "S-1/S-2 acceptance met" (see below).
**Outcome is NOT yet determined** (not A, not B, not C). A user-owned build-5875
client becomes required at O07; named AVD/device and physical qualification
inputs are required at their later gates. **Downstream O06 implementation is not
authorized** until S-1, S-2, and S-3 all genuinely pass on 4KB and the 16K repeat
completes.

## O06 Phase 1 — Wine feasibility spike status (in progress)

The S-1/S-2/S-3 on-device spike runs on the Modern lane (API 35, 4KB).

### What is genuinely proven (narrower than full acceptance)

| Item | Status |
|---|---|
| SIGSYS cause | Proven: Android app-domain seccomp traps legacy `access(2)` (si_code=1, syscall=21) |
| Direct loader / trampoline | Proven blocked by the same seccomp rule |
| PRoot from production app domain | Proven operational (`proot --version` → 5.1.107.89) |
| APK-managed loader running Wine bootstrap | Proven for `wine --version` only, on Modern 4KB |
| PE cache materialization | Proven: 1626 files copied and hash-verified |
| Winlator Java sources | Compile successfully |
| **Full S-1 acceptance** | **NOT yet proven** — process-tree `/proc/<pid>/maps` proof for wine + wineserver + every native child is unmet |
| **S-2 wineboot resolution** | **NOT yet proven** — `wineboot --init` did not reach a clean exit; no PE-from-cache resolution proof |
| **S-3 X11/GDI window** | **NOT yet implemented** (native transport not built) |
| **16 KB qualification** | **Not run** |

**The current S-1 result is bootstrap-only.** The recorded `wine --version`
proof shows the loader/libc/libdl strings in `LD_DEBUG=libs` output, which is
valuable bootstrap evidence, but the per-process `/proc/<pid>/maps` proof the
approved plan requires (for wine **and** wineserver **and every native child**)
was `FAIL|rc=5` and the success condition did not require it, the command's exit
code, or the `wine-11.14` marker. Correct framing: **"Wine loader bootstrap
proven via PRoot on Modern 4KB; full process-tree loader acceptance pending."**

**The current S-2 result is a false positive.** `winebootStillRunning=true`,
`winebootExit=-1`, and the success condition fired because cache verification
succeeded and a PID was returned — without proving wineboot initialized the
prefix. Two deeper path bugs are open: (1) `bin/wineboot` resolves to
`libwine_preloader.so`, losing the logical `argv[0]` that distinguishes wineboot
from wine (must use glibc-loader `--argv0`); (2) `pe_cache.c` ignores each
manifest entry's `logical_path` and materializes files under `wine-pe/...` with
nothing connecting them to the logical Wine tree, so Wine cannot find the cached
modules. S-2 must be reworked (see "Pending corrections" below).

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
  `/tmp/.wine-<uid>` path. **This proves the Wine loader bootstrap from the
  production app process via PRoot, NOT full S-1 acceptance** — the per-process
  process-tree `/proc/<pid>/maps` proof for wine + wineserver + every native
  child is still pending (see "What is genuinely proven" above). Direct +
  trampoline paths still fail (SIGSYS_SECCOMP_ACCESS / EACCES).
- **S-3 (in progress):** the Winlator X-server (ca3d735) Java sources are
  vendored, trimmed, and COMPILE. Per the corrected scope, the spike will vendor
  and build the **minimum native Winlator transport** (`libwinlator.so`: the
  pinned `xconnector_epoll.c`, `xinput_stream.c`, `xoutput_stream.c` + required
  helpers), NOT a Java reimplementation — the pinned native code correctly
  handles epoll + SCM_RIGHTS + buffered X11 I/O + JNI callbacks. S-3 is strictly
  GDI/X11: GLX/DRI3/MIT-SHM advertisements whose native support is not built are
  removed (no no-op extension advertisements).

### Pending corrections (before any outcome is recorded)

The following are in-flight corrections to the prematurely-passed S-1/S-2:

1. **Shared PRoot launcher** (`proot_launcher.c`): separate the immutable real
   APK executable path from the logical Wine command name; preserve logical
   `argv[0]` for wine/wineboot/winecfg via glibc-loader `--argv0`; return a
   structured result (exit status, captured stdout/stderr, timeout state,
   recursively enumerated descendants); kill/reap the complete process tree on
   timeout (not just the top proot PID).
2. **Full S-1** via the real wineboot/self-test process tree: enumerate proot
   descendants recursively while alive; record PID/PPID/cmdline/classification +
   `/proc/<pid>/maps` proof for wine + wineserver + every native Wine child;
   treat Bionic proot/helper processes separately (the APK-glibc-loader
   requirement applies to the glibc/Wine tree); fail if any Wine native child is
   unclassified or lacks APK-managed loader/closure proof; require the bootstrap
   command's exit zero + expected output.
3. **Full S-2** truthfully: materialize PE entries per manifest `logical_path`
   (symlink mapping `wine-tree/lib/wine/{x86_64,i386}-windows` → verified cache);
   add a mismatch-repair test (corrupt one cached PE → prelaunch verification
   detects → atomically rematerializes → reverify canonical SHA-256); launch
   real `wineboot --init`, capture all output, bounded-time wait, require exit
   zero; wait for wineserver cleanly at shutdown; verify prefix artifacts
   (system.reg, user.reg, userdef.reg, dosdevices, drive_c/windows/system32);
   capture Wine loader evidence showing wineboot.exe + core builtin PE modules
   resolving from the materialized cache.
4. **S-3**: build the pinned native Winlator transport (`libwinlator.so`); make
   the X-server GDI-only; build the S-3 harness (`<appTmp>/.X11-unix/X0`, project-
   owned 32-bit self-test PE via the same PRoot/prefix/cache path, DISPLAY=:0,
   screenshot/PixelCopy of non-background pixels, `POCKET_SELFTEST_OK` + exit
   zero, clean shutdown of wineserver/X-server threads/proot).
5. **Rerun** corrected S-1/S-2/S-3 on Modern 4KB, then 16K. Record Outcome B only
   if both lanes pass via PRoot; otherwise Outcome C with the exact remaining
   failure. Outcome A is not available (direct + trampoline already disproven).
6. **Evidence driver** (`run_wine_spike.py`): determine PASS/FAIL first, then
   write exactly one artifact (PASS or FAIL), parse + require the matching
   `WINE_SPIKE_*_RESULT ok=true` marker, and record serial/AVD/API/ABI/page
   size/variant/exact test.
7. **Provenance/source records**: correct `wine-provider-provenance.md` and
   `schemas/sources.json` to describe only what is actually vendored + packaged
   (Java sources compile; native transport not yet built/committed; counts).
**Spike outcome: NOT YET DETERMINED (not A, not B, not C).** Only the Wine loader
bootstrap is proven (via PRoot on Modern 4KB). Full S-1 acceptance, S-2
wineboot/PE-resolution, S-3 X11/GDI, and the 16K repeat all remain. The
corrections above are in-flight before any outcome (A/B/C) is recorded. O06 stays
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
