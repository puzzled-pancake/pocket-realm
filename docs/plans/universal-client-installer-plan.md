# Pocket Realm — Universal Client Installer: in-app archive import (ZIP / 7z / RAR)

Written 2026-08-27 against `feature/community-turnip-list @ aa8bb79`
(0.102.0-alpha). Branch `feature/universal-client-installer`. Reviewed by four
independent plan reviews (architecture, risk/licensing, product/UX against the
real `C:\Wow clients` bytes, test strategy) plus a dedicated RAR
licensing/integration research pass; all findings folded in.

## 0. Mission (fixed)

Client import accepts an **archive file** — `.zip`, `.7z`, `.rar` (RAR4 and
RAR5) — picked once in the app. Pocket Realm **detects** what the archive
contains (version, variant, locale, realm target, contamination), shows that
information, and installs the client through the existing hardened import
pipeline (journal, resume, verify, publish as immutable generation). Runs once
per import. Rejected with format-aware messages: encrypted archives,
multi-volume archives, Blizzard installer payloads, ISO images.

A Windows companion script (`tools/install_client_windows.ps1`) verifies and
extracts the `C:\Wow clients` staging archives for the existing folder-import
lane and as the fallback for archives the app cannot open (encrypted,
multi-volume, RAR4-solid).

## 1. Non-goals

No downloading or bundling Blizzard assets (repo rule); no executing Windows
installers; no ISO extraction; no encrypted or multi-volume RAR (libarchive
cannot decrypt); no torrent support; no Rust/Go RAR codecs.

## 2. Ground truth (verified 2026-08-27 — cheap to re-verify, do not re-derive)

- The importer copy loop is SAF-clean: per-entry URIs are built only in
  `SafTreeSource.open`; `ManagedClientImporter` consumes an entry + stream
  shape. `<noBackup>/client/incoming/` collides with no existing cleanup path
  (`ClientGenerationStore.prepare` wipes only `generations/.staging-*`).
- The journal is typed on `SafSourceEntry` (`document_id TEXT NOT NULL`,
  mtime-based `sourceMatches`, mimeType inside the source fingerprint) —
  archive sources need a unified entry type, a stable fingerprint
  (name + size), and schema v4.
- The scanner runs before any journal row exists today; new phases must be
  registered in three places (`ImportModel` enum, `phaseTitle`/`phaseExplanation`,
  `ACTIVE_IMPORT_PHASES`) or the double-start gate in `ClientScreen` breaks.
- 7z LZMA needs `org.tukaani:xz` — not currently a dependency.
- Real `C:\Wow clients` archives (verified with `7z l`):
  - Stonetavern-Classic/Enhanced zips: **Zip64**, **backslash** entry
    separators, wrapper root, `Data/Interface/Cinematics` subdirs, root
    extras (`launch.bat`, `detect-display.ps1`, `dxvk/`); Enhanced adds root
    `nampower.dll`/`UnitXP_SP3.dll` + `Interface/AddOns/UnitXP_SP3_Addon`.
  - ENG zip: `WoW.exe` (4,775,986 B) at wrapper root **and**
    `!1.8 Hack/wow.exe` (1,546,240 B) + `!1.8 Hack/patch-4.MPQ`.
  - RU zip: 11 base MPQs **plus** `patch.MPQ` (2.05 GB), `patch-2/-3/-m/-s/-z`,
    `backup.MPQ`; uncompressed total ≈ 7.5 GB+.
  - Nelthorya + torrents clients: **RAR4, `Solid = -`** (libarchive RAR4
    reader compatible), backslash separators, wrapper roots.
  - `WoW-1.12.1_install.rar`: **RAR5**, `setup.exe` + `setup-1..4.bin`, no
    WoW.exe → VAL-12. ISOs are 1.0.1 / 1.10.0 installers → VAL-11.
- libarchive is streaming-only; its RAR readers verify CRC/BLAKE2sp; encrypted
  input is a hard FATAL; a missing volume surfaces as premature EOF (caught by
  the extracted-bytes == declared-size assertion).

## 3. RAR decision (research-resolved, licensing-gated)

junrar and every 7-Zip-derived RAR codec are **out**: they embed Roshal's
UnRAR-licensed code (field-of-use restriction, GPL-3.0-incompatible) — verified
from junrar's LICENSE and 7-zip.org's license.txt. **libarchive is the route**:
`archive_read_support_format_rar.c` (Kientzle/Mejia) and
`archive_read_support_format_rar5.c` (Antoniak) are plain BSD-2 clean-room
readers (upstream bars unRAR readers — libarchive issue #151), GPL-compatible
per FSF, shipped in Debian/Fedora main, used by Windows 11; Material Files
(GPL-3.0) ships this exact AAR. Integration:
`me.zhanghai.android.libarchive:library:1.1.6` (Maven Central; Apache-2.0
bindings; bundles libarchive 3.8.1 for all four ABIs, ~1 MB/ABI compressed),
consumed exactly like the existing zstd-jni AAR.

## 4. Global invariants

1. The folder-import lane stays behavior-preserving through Phase A; all
   pre-existing tests stay green at every phase boundary.
2. Headers are never trusted for space safety: extraction counts actual bytes
   written per entry and cumulatively (`Math.addExact`), rejecting VAL-08 on
   mismatch; per-entry `copied == declaredSize` doubles as the multi-volume
   premature-EOF tripwire.
3. Every entry name (directories included) passes `ImportPathPolicy` after
   decoding (strict UTF-8, cp866 retry when the EFS flag is absent) and joins a
   single case-fold namespace that rejects collisions and dir/file conflicts.
4. Resume anchors on the **staged file + digest**, never on re-reading the
   picked SAF URI; the staged `.pkg` is deleted on every terminal journal
   state and immediately after publish (before data preparation).
5. No Blizzard bytes in any committed fixture — synthetic PE/MPQ stubs and
   libarchive's BSD test corpus only.
6. `docs/plans/` plan doc + `PLAN-LOG.md` entry per phase; commits tagged
   `(M1)…(M5)`; final phase bumps `versionName` 0.103.0-alpha /
   `versionCode` 8 and stages `.tmp/release-0.103.0/`.

## 5. Phases

- **Phase 0 — Baseline & fixtures.** Branch off `aa8bb79`; baseline
  `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full`
  green; record real-archive inventory (§2); `SyntheticClientArchives` JVM
  fixture factory porting `syntheticPe` from `ImportFixtureProvider.java`
  verbatim (valid/flat/wrongBuild/launcherOnly/contaminated/installer/
  encrypted/cp866/zip64/7z/ISO-stub) + RAR fixtures from libarchive's BSD test
  corpus (< 1 MiB, committed under `test/resources/fixtures/`) + hand-crafted
  RAR4/RAR5 signature stubs.
- **Phase A (M1) — `ImportSource` generalization.** Unified entry type,
  journal schema v4 (`source_kind`, staged-file state, entry markers;
  `onUpgrade` v3→4), STAGING/EXTRACTING phases registered in all three places.
  Folder lane behavior-preserving.
- **Phase B (M2) — Quick checks + staging + detection (JVM).** Magic sniff
  (ZIP/7z/RAR4/RAR5/ISO→VAL-11), ZIP central-directory and 7z header
  pre-checks (VAL-01/VAL-12/VAL-13), staging copy with resume, `incoming/` GC,
  `ArchiveClientScanner` on the `Access` seam (root allow-list exclusion
  **before** WoW.exe resolution — fixes the ENG double-exe), shared
  `ClientPeIdentity`, `ImportExtractionPolicy` extending `ImportPathPolicy`.
- **Phase C (M3) — ZIP/7z end-to-end + UI.** Add `org.tukaani:xz`;
  `ZipArchiveSource`/`SevenZArchiveSource`; `readStatus` schema bump;
  `ClientScreen` second picker lane + confirm copy + ~5 s countdown
  auto-continue + sticky notices; `ImportFixtureProvider` `archives` root +
  `ACTION_IMPORT_ARCHIVE`; interrupt points `AFTER_STAGING`/`AFTER_DETECTION`;
  the pinned resume/cancel suite.
- **Phase D (M4) — RAR via libarchive.** AAR dependency; 16 KB alignment
  check; `RarArchiveSource` (streaming single-pass, entry-journaled,
  resume-by-skip) with checkpoint cancellation; VAL-13 variants
  (encrypted/multi-volume/RAR4-solid with actionable copy);
  `THIRD_PARTY_NOTICES.md` entries; corpus-fixture tests.
- **Phase E (M5) — Windows companion + docs + release.**
  `tools/install_client_windows.ps1` (hash-anchored SHA256SUMS parsing,
  slugified install dirs, array-argument 7z, refuse-or-wipe
  `C:\Wow clients\Installed\<slug>`, identity/MPQ/realmlist checks, ranked
  verdict, detects already-extracted clients);
  `scripts/smoke_archive_import.py`; update every no-archives site
  (Game-Files-and-Import.md, Getting-Started.md, Troubleshooting.md VAL
  catalogue, README.md, ClientScreen/FirstRunTutorial strings + the tests that
  pin them); version bump; `.tmp/release-0.103.0/` staging.
- **Phase F — Device qualification.**
  `./gradlew :app:connectedDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full`;
  O11-style archive import with process-kill checkpoints; real-archive runbook
  (Stonetavern → RU → ENG → both RAR4s → expected rejections).

## 6. Risk register

- RAR4 solid archives are unsupported by libarchive → actionable message
  pointing at the PC companion (both real RAR4s verified non-solid).
- Encrypted RAR cannot be decrypted by anything license-clean → reject.
- 7z random-access probing is O(archive) on solid folders → ZIP-only fast
  path; 7z/RAR identity checks run post-extraction.
- junrar/7-Zip codecs are GPL-incompatible → never bundle (§3).
- SAF stream flakiness over long reads → staging copy is short, resumable, and
  journaled; extraction works on the staged `File`.
- APK size +~1 MB/ABI (libarchive AAR) — trivial vs the Wine closure.
