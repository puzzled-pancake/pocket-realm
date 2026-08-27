# Universal client installer — device qualification runbook (Phase F)

Execute on a connected device/emulator with a debug build installed. Each
step records a PLAN-LOG entry with pass/fail and timing.

## 1. Instrumented suites

```
cd android && ./gradlew :app:connectedDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full
```

- `O12ArchiveImportTest` — archive lane deaths/resume (staging → detection →
  mid-extraction → publish), junk-installer VAL-12, encrypted VAL-13, staged-file
  cleanup.
- `O13RarImportTest` — libarchive JNI: corpus RAR4 list+extract, RAR5
  listing, encrypted flag, lone multivolume part-1.
- Pre-existing `O11*` suites must stay green (folder lane untouched).

## 2. Real-archive import matrix (adb-push each archive to /sdcard/Download)

| Source (C:\Wow clients) | Lane | Expected |
|---|---|---|
| Stonetavern-Classic-1.12.1.zip | in-app | imports; detection card shows variant + realm target; wrapper rebased |
| WoW_Classic_RU_1.12.1.zip | in-app | imports; cp866 names decode; patch-3.mpq copied; locale from Config.wtf |
| WoW_Classic_ENG_1.12.1.zip | in-app | imports; `!1.8 Hack` excluded with warning |
| Nelthorya RAR4 (non-solid, verified) | in-app | imports via libarchive; identity pinned post-extraction |
| torrents RAR4 (non-solid, verified) | in-app | imports via libarchive |
| WoW-1.12.1_install.rar (RAR5) | in-app (installer lane) | imports: scratch-unpacked to incoming/<id>.pkg.d/, Inno 5.3.5 headers parsed, ~185 files streamed from the solid chunk, identity pinned post-extraction; staged pkg + scratch deleted after publish; expect the longest import of the set (LZMA over 5.3 GB — budget an hour class, watch the EXTRACTING notification) |
| Either ISO | in-app | VAL-11 rejection |

Record: total minutes per import, staged-copy deletion after publish
(`adb shell ls /data/user/0/com.pocketrealm/no_backup/client/incoming` via
run-as in debuggable builds), resume after `adb shell am kill` mid-extract.

## 3. Watchdog/LMK resume

Start an archive import, `adb shell am kill <pkg>:import` at 25 %, confirm
the UI auto-restarts via `resumeActive` and completes without re-staging
(staged bytes anchor the restart).

## 4. Companion fallback

On the PC: `tools/install_client_windows.ps1 -Source <solid/encrypted rar>`
extracts to `C:\Wow clients\Installed\<slug>` with identity verification;
folder-import that tree on device.

## 5. Gate

All rows green ⇒ mark Phase F complete in PLAN-LOG and stage
`.tmp/release-0.103.0/` (APK + RELEASE_NOTES + update-manifest) — the gradle
edits (xz, libarchive AAR, versionCode 8) ride the working tree until the
parent SQLite/LLM lane lands.

## 6. Installer-lane addendum (2026-08-27, I4)

The O12 `innoInstallerArchiveExtractsTheClientAndPublishes` case covers the
synthetic end-to-end (zipped Inno installer → published generation with the
call-filtered WoW.exe byte-exact). The real WoW-1.12.1_install.rar row above
is the device qualification for it. Also record: scratch reuse on resume
(kill mid-extract, relaunch — `hasScratch` skips re-extraction), and the
post-publish cleanliness of `incoming/` (both the `.pkg` and the `.pkg.d`).
