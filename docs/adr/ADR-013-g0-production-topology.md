# ADR-013: G0 production topology — confirmed Lane A (library-backed, supervised, fault-isolated)

- **Status:** Accepted (evidence-confirmed at G0)
- **Date:** 2026-08-01
- **Feature:** O05 — G0 production packaging, process isolation, and capability record
- **Report reference:** §5.1 (executable-code packaging lanes), §6.7 (recommended
  package/process topology), §8.4 (PKG-01/02/06), §20.1–20.2 (X0/X1)
- **Supersedes:** none. This ADR *confirms* the report's expected recommendation
  (DECISIONS #7, #8, #12) from implementation evidence. It does not renumber or
  contradict ADR-001–012. If a future gate's evidence contradicts the report,
  a separate superseding ADR will be raised at that time.

## Context

Report §5.1 names three production lanes for executable code on modern Android:

- **Lane A** — long-running components as APK-packaged native libraries loaded by
  small supervised entry shims, in dedicated `android:process` fault domains.
- **Lane B** — PIE/ELF payloads in the APK native-library area executed from
  package-managed paths.
- **Lane C** — in-process behind JNI.

Modern Android (target API 29+) removed execute permission from the writable
app-home directory, so the legacy "unpack ELF to files/ and exec" model is not
production-safe. The report's recommended direction (§5.1) is Lane A, with a
small Lane B prototype kept only if it materially reduces integration
complexity. The O04 `libpocketrealm.so` in-process facade (Lane C) is retained
as reusable library/control evidence but was never selected as the production
world-server topology.

This ADR records the G0 packaging experiments that confirm the choice.

## Decision

**Adopt Production Lane A** for the offline realm's long-lived native components
(MariaDB, `realmd`, `mangosd`, and later the Wine client): compile each as an
APK-packaged native library, load it through a small supervised entry shim, and
run it in a dedicated `android:process` fault domain. The Kotlin
`RuntimeSupervisor` owns state; components publish structured readiness and
stable error codes over a narrow versioned C ABI / app-private control channel.

**Signed-code / mutable-data boundary:** executable native code ships **only**
through the signed APK in `nativeLibraryDir` (immutable). Mutable datadir,
Wine prefix/cache, journals, databases, and imported content stay app-private
under `noBackupFilesDir`/`filesDir` and are never executed. **target-28
unpack/exec is explicitly not the production answer** — it is a disposable
research lane only (report §5.1, DECISIONS #12).

**The O04 in-process `libpocketrealm.so` facade is retained** as reusable
library-lane and control-code evidence (DECISIONS #8 unchanged); it is not the
production world-server topology. Its versioned C ABI remains the boundary for
any future in-process consumer.

## Evidence (G0 PKG experiments, captured 2026-08-01)

Three AVD lanes; full evidence in `tests/avd/<lane>/pkg-experiments.md` and the
per-lane capability records in `tests/avd/<avd-id>.json`.

| Lane | API | Page | PKG-01 | PKG-02 | PKG-06 |
|---|---|---|---|---|---|
| AVD-Legacy-x86_64 | 28 | 4 KB | executed + documented | CONTAINMENT_PROVEN | (research lane; not a PKG-06 lane) |
| AVD-Modern-x86_64-v1 | 35 | 4 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |
| AVD-16K-x86_64-v1 | 35 | 16 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |

### PKG-01 — APK-owned native launcher execution
- **Experiment variant** (`useLegacyPackaging=true`, evidence
  `pkgExperiment-t2_*.log`): the PIE launcher (`libpocket_pkg_launcher.so`) is
  extracted with `rwxr-xr-x` into `nativeLibraryDir` and executes there on **all
  three lanes**. Its `/proc/self/exe` resolves to the nativeLibraryDir path; it
  reports the runtime page size (4096 / 16384) and, with `LD_LIBRARY_PATH`,
  dlopens `libpocketrealm.so` by SONAME and resolves `realm_err_str` via
  `dlsym`+`dladdr`.
- **Production variant** (`useLegacyPackaging=false`, evidence `debug-t2_*.log`):
  the launcher has **no executable filesystem path**
  (`code=NO_EXECUTABLE_FS_PATH`, `extractNativeLibs=false`). Native `.so` are
  stored uncompressed/page-aligned in the APK and may be loaded directly from it;
  a standalone exec is not the production model. This honest, observed behavior is
  central evidence for selecting Lane A over Lane B.

### PKG-02 — isolated library-backed crash containment (production variant)
The same `:pkg` child process (bound cross-process via AIDL, `android:process=":pkg"`)
`dlopen`s the **real** `libpocketrealm.so` by SONAME, confirms hello, then triggers
a deterministic `abort()`. On all lanes:
- the `:main` process PID stays alive;
- the child PID disappears and a Binder death notification fires;
- restarting the child yields a **new PID** that answers hello.
- The realm `.so` loads **from the APK**
  (`base.apk!/lib/x86_64/libpocketrealm.so`) — production packaging confirmed.
This proves the library-backed, process-isolated component model works and that a
component crash is contained without taking down the supervisor.

### PKG-06 — page-size compatibility + full APK closure (production variant)
Under the production variant (`useLegacyPackaging=false`), native libraries are
not extracted to `nativeLibraryDir`, so PKG-06 enumerates the APK's own
`lib/<abi>/*.so` entries directly and probes **each loadable library by SONAME**
(`RTLD_NOLOAD` then `RTLD_NOW`). All six packaged `.so` are enumerated
(`packagedLibCount=6`); the launcher (`libpocket_pkg_launcher.so`) is a PIE
executable with no `DT_SONAME` and is explicitly excluded
(`EXCLUDED_EXECUTABLE`); the remaining five — `libpocketrealm.so`,
`libpocketpkgtest.so`, `libc++_shared.so`, `libandroidx.graphics.path.so`, and
`libdatastore_shared_counter.so` — each load `OK` from `base.apk!/lib/x86_64/`
(`perLibProbeCount=5`). This proves the **entire** APK native closure loads on
both page sizes, not just the realm facade. All `.so` are 16 KB page-compatible
(ELF `PT_LOAD` alignment `0x4000`, `zipalign -c -P 16 -v 4` passes). The two
genuine 30-minute acceptance runs (4 KB and 16 KB) each logged 30 per-minute
ticks with a stable `:pkg` PID.

### Capability records (X0)
`tests/avd/AVD-Modern-x86_64-v1.json`, `AVD-16K-x86_64-v1.json`, and
`AVD-Legacy-x86_64-v1.json` match `adb` on equivalent fields (API, abilist,
page size, RAM). `StorageManager.getAllocatableBytes()` and `df` are recorded
separately; app and host GL strings are recorded separately (no forced equality
on non-equivalent fields).

## Consequences

- Long-lived native components will be built as libraries and loaded by
  supervised shims, not exec'd as standalone binaries. The standalone-exec
  experiment variant is retained only as G0 evidence and for any future tool
  that genuinely needs an executable filesystem path.
- The component process topology follows report §6.7 (`:supervisor`, `:database`,
  `:realm`, `:world`, `:client`, `:worker`). O10 implements the dependency-gated
  `RuntimeSupervisor` state machine on top of this topology.
- Native updates arrive **only** through signed APK updates. Mutable runtime state
  is versioned, bounded, app-private, and never treated as executable.
- The `:pkg` experiment service is **not** part of the production topology; it is
  the G0 fault-injection harness and may be removed or generalized into the real
  component shims at O08–O12.

## Rollback / supersession

If a later gate (e.g. G2 native MariaDB, G5 ARM client) finds Lane A unviable for
a specific component, raise a new superseding ADR identifying the conflicting
report ADR/section, the evidence, migration/rollback, and update PLAN.md,
FEATURES.json, and PROGRESS.md. This ADR does not preempt that path.
