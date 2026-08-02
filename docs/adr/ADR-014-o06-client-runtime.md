# ADR-014: O06 direct-x86 ClientRuntime topology

Date: 2026-08-02
Status: accepted
Reference: canonical report §§8.4, 8.5, 15.3-15.7, 16.5-16.8, 20.2-20.3

## Decision

Adopt the qualified Wine 11.14 Outcome-B route behind `ClientRuntime` with two
owners:

- the non-exported `ClientRuntimeService` in `android:process=":client"` owns
  Wine/native children, the authorized executable, session tokens, prefix
  preparation, clean close, force stop, and diagnostics;
- the UI process owns the app-private X socket/server, `XServerView` rendering,
  focus, physical keyboard/mouse, and letterbox-aware touch translation.

The same-APK AIDL protocol is versioned and bounded. It accepts named O06
operations only; it cannot launch arbitrary executables or inject caller-owned
paths/environments. Only one active session is allowed. Clean close writes a
session-token sentinel consumed by the project-owned Win32 self-test and
converted to `WM_CLOSE`. Forced stop cancels the direct runner's dedicated
process group.

## Packaging and storage

`clientRuntime` is the selected G1 x86 lane and extracts APK-native libraries
because the qualified Wine route needs an executable `nativeLibraryDir` path.
The O05 `debug` and `release` packaging controls remain unchanged.

Production sessions enter through the APK-managed static Wine preloader and
the source-built glibc adapter. PRoot remains pinned for the Phase-1
seccomp-safe loader/process-tree proof; it is not the normal session launcher.

Executable code is APK-managed. Prefix, cache, temporary socket state, manifests,
and diagnostics are below `noBackupFilesDir/wine/`. The full build/client/schema
identity is recorded in `prefix-manifest.json`; the physical generation name is
compact enough for Linux's 108-byte AF_UNIX path limit. Quotas are explicit:
768 MiB active prefix, one 768 MiB rollback prefix, 768 MiB verified cache, and
4 MiB diagnostics, with eight session records retained.

## Consequences

- O06 uses only the redistributable project self-test; proprietary WoW content
  remains outside the repository and outside acceptance until O07.
- Window mapping is reported from the UI-owned X server before the session enters
  `RUNNING`.
- Pressed keys/buttons are tracked per Android input source and released on
  focus/lifecycle loss.
- Audio-off is observable: the self-test skips `waveOutOpen` and records the
  result, rather than inferring success from an environment variable.
- Provider/runtime changes invalidate both page-size qualifications.

## Qualification

The same app/test APK pair passes `ClientRuntimeLifecycleTest` on:

- AVD-Modern-x86_64-v1, API 35, 4,096-byte pages;
- AVD-16K-x86_64-v1, API 35, 16,384-byte pages.

The test proves immutable runtime discovery, first prefix creation, compatible
relaunch write preservation, manifest/quota ownership, an app-attached rendered
Win32 window, focus, transformed tap/mouse, keyboard down/up, audio-off, clean
close, diagnostics, and token-scoped process-group forced stop. Authoritative
logs and screenshots are linked from `FEATURES.json` and `PROGRESS.md`.
