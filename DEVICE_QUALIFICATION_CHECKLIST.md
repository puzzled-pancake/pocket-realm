# Device Qualification Checklist — User-Imported Vulkan (Turnip) Drivers

Written by the autonomous overnight run (branch `feature/user-vulkan-drivers`,
0.101.0-alpha). Everything below needs a real device; nothing here was
executable tonight (plan §1.2, §11). Work top to bottom on the target lane
(Retroid Pocket 6 / Adreno 740 first).

## 1. RP6 baseline (source P0.4)

- [ ] Boot realm + game stock (Auto → packaged Turnip 26.1.0 / DXVK 2.4.1).
- [ ] Capture the driver name/version from the DXVK log
      (`sessions/<id>/WoW_d3d9.log`: `DXVK: v…` + `Turnip Adreno …`).
- [ ] Confirm the session reaches RUNNING with the pinned DXVK proof.

## 2. Guest env proof (source P2.1 / P2.2)

- [ ] Enable "Allow imported drivers", import a known-good 16 KB Turnip
      build, select it, launch.
- [ ] In the guest, verify `/proc/<wine-pid>/environ` shows
      `VK_ICD_FILENAMES=<rootfs>/usr/share/vulkan/icd.d/icd.json` and
      `VK_DRIVER_FILES=<same>` (user lane only).
- [ ] Verify the staged `icd.json` `library_path` points at
      `<rootfs>/usr/lib/driver.so`, and that the file's sha256 equals the
      registry-recorded digest (Settings → driver row / support bundle).
- [ ] **Packaging immutability**: after N launches with the user driver,
      the packaged driver assets are unchanged (APK is immutable — verify
      no packaged file names exist under the shared rootfs `usr/lib` /
      `usr/share/vulkan/icd.d/` while a user driver is selected), and after
      switching back to a packaged driver the `driver.so`/`icd.json` staging
      files are retired.
- [ ] **Interrupted stops**: interrupt-stop the realm mid-session (force
      stop the app from recents/system), relaunch — confirm staging is
      re-derived per prepare (no stale-driver mismatch, session starts).
- [ ] Verify the identity-time and install-time ICD digests agree (the
      launch reaching RUNNING without "changed after preparation"
      attestations proves this — the B5 class).

## 3. Crash-guard field test (source P4.1)

- [ ] Import an intentionally broken but validation-passing driver (e.g. a
      16 KB-aligned aarch64 `.so` that exits immediately — a stub `so` with
      a valid header works).
- [ ] Launch twice: each session must FAIL within 10 s and count.
- [ ] After attempt 2: the driver is quarantined, the selection auto-reverts
      to Auto, the next launch runs the packaged default, and Settings
      shows "quarantined after 2 early crashes" on the row.
- [ ] A user forced stop inside 10 s must NOT count as a crash nor reset an
      ongoing streak (streak-neutral semantics).

## 4. Driver-switch smoke (source P4.3)

- [ ] After switching drivers, run a short client boot (or the self-test PE
      if/when the ARM lane authorizes it) before the real session and
      confirm the DXVK log names the newly selected driver.
- [ ] Note: the ARM lane currently authorizes only the build-5875 client,
      so the self-test-first option is not wired in-app; this item stays a
      manual smoke until that changes.

## 5. Community matrix (source QA matrix)

- [ ] Mali device: user lane rows disabled with the Adreno-only reason;
      Auto uses the system Vortek bridge; launch unaffected.
- [ ] Other Snapshot / untested Adreno devices: import + launch + quarantine
      reset exercised at least once each.
- [ ] Confirm zero behavior change with the toggle OFF on all of the above
      (catalog lanes byte-identical — Phase-0 characterization net holds).

## 6. GA decision input for 0.102.0-alpha (source P5.3)

- [ ] All rows above green on the RP6 lane.
- [ ] Decide flipping "Allow imported drivers" default ON (it ships OFF in
      0.101.0-alpha — the feature lands dark by design).
- [ ] Re-check the wiki page copy against any UI wording drift.
