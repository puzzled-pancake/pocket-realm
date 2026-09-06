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

## 7. Community driver list on-device pass (community-turnip-list plan §9)

Written by the community-list run (branch `feature/community-turnip-list`,
0.102.0-alpha). Exercises the pinned-download path end to end on the RP6:

- [ ] Toggle "Allow imported drivers" ON → "Community drivers…" button is
      visible and opens the dialog with both seed entries (label, version,
      MiB size, source repo, MIT, disclaimer).
- [ ] Import `Mesa Turnip 26.0.0 R8 (K11MCH1)`: progress bar + status line
      advance, the AdrenoTools zip imports via meta.json (label from
      `meta.name`, Vulkan 1.4.335 shown on the chip), a normal user chip
      `vulkan-driver-user-mesa-turnip-driver-v26-0-0-r8` appears, and the
      dialog row now shows "Imported".
- [ ] Select it, launch: session reaches RUNNING with the DXVK log naming
      the driver; crash-guard semantics unchanged (a healthy launch resets
      any streak).
- [ ] Import `Mesa Turnip 25.1.0 R2 (K11MCH1)` (bare `.so`): imports and is
      selectable; switching between the two and back to Auto works per
      section 4's smoke.
- [ ] Failure honesty: with networking off, tapping an entry shows the exact
      download-failure line and no partial file remains (re-enable, retry,
      succeed).
- [ ] With the toggle OFF: no "Community drivers…" UI at all; suite of
      section 5's toggle-OFF rows still holds.
- [ ] Release note for 0.102.0-alpha: "Community Turnip driver list —
      pinned downloads in Settings; AdrenoTools meta.json packs import
      cleanly."

## 8. rp-depth-fix-plan v2.3 device gates

Written by the rp-depth-fix v2.3 continuation run (see
`docs/plans/rp-depth-fix-plan-v2.3.md` §10 and PLAN-LOG.md). The
terminal gates T3/T4/T5 plus the device-gated follow-ups from B4/B5/B6,
G1, and A6. Nothing in this section ran in the authoring sessions (no
device / no emulator there); the harness rail that drives them lives at
`tools/rp_harness/` (protocol/session/assertions/auto_reply/run_suite +
`suites/smoke.py`, the per-change gate; the `suites/battery.py` and
`suites/realmd_auth.py` suites named by plan §10 are authored by the
harness lane before T3 first runs — the relay ops `world-chat`,
`reset-state`, and `llm-memory-state` already exist). Every T3/T4 run
begins with the state-reset step (fresh character via harness
CHAR_CREATE + the `reset-state` relay op clearing facts/tiers/quotas).

### T3 — emulator functional battery [EMULATOR-GATED]

Profiles LOW_POWER_80 @ 6 GB and ALIVE_REALM_320 @ 8 GB AVD; step −1 =
state reset; step 0 = `realmd_auth` suite green (protocol-client
stretch goal per §9 H2). Per-step pass criteria (plan §10 T3):

- [ ] **Step 1 Welcome**: fresh character per run; the authored welcome
      pattern arrives within `PassiveDelay×2 + 2 s`; the "hint" asserts
      its assigned F3 surface (or the welcome's remember-clause), never
      app UI (unobservable to a protocol client).
- [ ] **Step 2 Journal**: trigger is exact-match lowercase `journal`
      (no trim); first line ≤ 30 s, ≥ 3 lines ≤ 60 s; sequenced after
      the welcome consumed first contact; bounds assume B3's 3000 ms
      PassiveDelay (stated in the report).
- [ ] **Step 3 Novel sentence**: SLA = client-observed whisper-send →
      reply-arrival from the ms transcript (not "from dispatch" — the
      server clock is second-resolution); the novel sentence dodges all
      authored intercepts and runs as the SECOND whisper (the first is
      always the welcome); A8's `durMs` log line is the coarse
      cross-check.
- [ ] **Step 4 Invalid-name**: unchanged from the prior battery
      (green).
- [ ] **Step 5 Party**: bot-selection recipe = nearest ungrouped bot,
      invite verified accepted (random bots self-group and refuse);
      invite two bots; exactly ONE reply per unaddressed party line,
      ≤ SLA+10 s — the one-responder assert is explicit (A3's claim).
- [ ] **Step 6 Street**: see the A6 protocol entry below.
- [ ] **Step 7 Restart**: asserts what actually persists —
      session-standing sys line re-arms per process
      (`MaybeSessionStandingLine`), `standing` returns the stored tier,
      journal shows no duplicate facts, `gossip` persists, C8's
      last-session tail recalls. (The greeting-upgrade ≥ 6 h-absence leg
      ships with its future reader - `last_greeted_at` is a write-only
      data-capture stamp today; no host pin exists for that leg yet.)
- [ ] **Step 8 Drills**: dead endpoint = `127.0.0.1:<closed port>`
      (instant refusal, not a SYN burn); pass = fallback ≤ SLA+10 s AND
      ≥ 1 failure-class log AND exactly ONE delivered line (the A4/A5
      exclusivity proof). Saturation: ≥ 6 whispers in 2 s to distinct
      bots × 3 rounds; pass = ≥ 1 busy-or-fallback line and zero silent
      drops (duty-cycle saturation, not the concurrency cap — drill
      sized accordingly). A5 floor observation bounded ≤ 360 s or
      excluded.
- [ ] **Step 9 Background**: record the last `Max Diff:` before
      backgrounding; assert delta ≤ 5000 ms after return (the metric is
      monotonic-since-start); session alive; post-return whisper
      ≤ SLA+10 s. The adb driver (`input keyevent HOME`) joins the
      harness. Emulator-vs-device documented as approximation — B5's
      real-hardware leg is a device item (below), not an emulator gate.
- [ ] **Step 10 Tier-up**: forced via the relay's `llm relationship`
      command while the world is STOPPED, then boot (a live bump races
      the async stomp write); ceremony visible + rider per-player;
      persists across restart.

Single client throughout (one client sees all say/party traffic);
per-profile bounds stated per step.

### T4 — soak + perf at full bot counts [DEVICE-GATED]

Per profile; soak clock starts at steady state (`botsOnline ≥ 0.95×
target` sustained, or `rampCappedAt` recorded with reason — power-save
caps accepted, memory-floor/world-p99 caps are failures).

- [ ] **Liveness**: poll world status ≤ 10 s — zero FAILED
      transitions; post-soak clean stop + clean next boot (no
      dirty-journal recovery); grep the real wedge marker `world loop
      wedged` (world_runtime.cpp), not "FATAL"; harness-transcript
      `FATAL:` greps scoped to world.log only.
- [ ] **Log health**: pre-soak conf pin `LogFileLevel = 1`; ≤ 10 MB
      gate with a 50 MB flood backstop; measured on-device
      (`run-as stat`), not through the adb copy.
- [ ] **Tick p99**: `worldTickP99Ms` from world status (2048-tick
      window); max steady-state sample ≤ 250 ms AND
      `botTargetAdapted == false` AND final count ≥ 0.95× target.
- [ ] **No-shedding pin (§0.9 at runtime)**: `effectiveTarget ==
      selectedTarget` with reason `"selected-profile"`
      (`BotAdmissionController.kt:33` / `:87-96`) — p99 > 250 silently
      shedding bots is a failure, jointly implied with the tick bound
      above.
- [ ] **Conversational latency**: ≥ 30 paired turns, client-observed,
      nearest-rank p50 ≤ 4 s / p95 ≤ 8 s; flake policy: one outlier in
      (8 s, 60 s] allowed if p90 ≤ 8 s; any reply > 60 s fails
      outright.
- [ ] **Promise-kept, class-explicit**: fresh-character first-contact
      (authored ≤ 5 s), repeat greets (generated, counted under the SLA
      leg), post-restart-gap arrival greets (authored ≤ 5 s); ≥ 20
      class-b and ≥ 5 class-a events per soak so neither leg is
      vacuous.
- [ ] **B4 bench-twin soak matrix**: soak `preset-low-power-b80-v1`
      (LOW_POWER_80-v1) vs `bench-low-power-b80-v2`, and
      `preset-alive-realm-b320-v1` (ALIVE_REALM_320-v1) vs
      `bench-alive-realm-b320-v2` (`BotProfiles.kt:753/:797/:917/:962`),
      running `tools/run_bot_pressure_benchmark.py` on the actual T4
      lanes (physical-device comparables: p99 54-143 ms at 320-600 bots
      for hotter configs). LOW_POWER_80's ×10.7 worst-case work-rate
      needs its own 6 GB soak before the flip (split B4a/B4b if
      needed).
- [ ] **B4 value-flip gate**: the `-v1` preset tuples are NOT changed
      in code until the benchmark artifact from the twin soak above is
      attached to the value-flip commit (artifact-gated; deferred — see
      below).
- [ ] **500/600 presets note**: `preset-full-realm-b500-v1` and
      `preset-massive-realm-b600-v1` run the 3000 ms PassiveDelay
      UN-SOAKED — the T4 report carries one sentence acknowledging it.
- [ ] **B5 freezer-eligibility pre-step [DEVICE-GATED]**: during a T3
      background window, `dumpsys activity processes` to confirm the
      `:world`/`:database` processes are actually freezer-eligible.
      They are held bound by a persistent FGS supervisor — bound-FGS
      priority may already exempt them; if so, redirect the protection
      to the mariadbd child process and record it.
- [ ] **B6 RSS recorded-datum methodology**: RSS is a recorded datum
      only, never an asserted bound — population cut via the test-only
      admission override, anon-heap measured via `malloc_info` (not
      VmRSS), differential vs a disabled lane. The `mallopt(M_PURGE, 1)`
      hook (Android-gated, API ≥ 28, 60 s cadence) is host-pinned by
      `tests/test_g1_realmd_liveness.py`; the on-device leg only
      records the datum.

### T5 — regression sweep + before/after scorecard [EMULATOR-GATED]

- [ ] **Regression sweep**: full pytest host suite (~370 collected
      tests / 394 items, 7 CI deselects; the 8 pre-existing failures
      named in PLAN-LOG are never counted against a change), gradle
      suite + detekt (CI-wired), prompt-format pins, emitter `--check`
      (fixture-backed), lockfile pins × 3 lanes, `realmd_auth` suite.
- [ ] **Before/after transcript scorecard**: freeze a before-transcript
      by running the committed battery on the UNMODIFIED build; score
      both transcripts on the 3 fixed rubrics — engagement (unprompted
      follow-ups per 10 turns), pacing (latency percentiles +
      inter-line gaps), continuity (fact/tier/history persistence
      across restart) — with a human-read gate ≥ 4/5 per rubric
      (the `s8_beats_gates.py` precedent); commit the scorecard under
      `docs/evidence/` with versioned filenames. If no before-battery
      can be produced, drop the comparative framing and gate on T3/T4
      absolutes — no improvised judgment panels.

### B5 — FGS promote/demote on-device verification [DEVICE-GATED]

- [ ] With a real player in the realm: the supervisor promotes
      WorldRuntimeService/DatabaseService to specialUse FGS
      (`foregroundServiceType="specialUse"` +
      `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest; notification
      ids 3/4 on the reused RealmService channel); promote predicate =
      `stateCode == READY && (realPlayers > 0 || (!playerbotsEnabled &&
      onlinePlayers > 0))`; promote immediate, demote only after 3
      empty samples (asymmetric hysteresis).
- [ ] Background the app mid-session and confirm via the B5 dumpsys
      pre-step (above) that the world keeps ticking (the step-9
      `Max Diff` bound is the emulator approximation of this).
- [ ] DatabaseService promotes on its own engine-claim and demotes at
      engine stop/owner-loss (no transition gate — supervisor-side
      demote only); promote-then-demote fencing survives an in-flight
      promote intent on a freshly created service
      (`ForegroundServiceDidNotStartInTimeException` must not crash).
- [ ] Host pins already exist (pure `ForegroundPromotionPolicy` +
      `DurableRuntimeSupervisorTest` FakeBackend) — this entry is the
      on-device leg only.

### G1 — fd-count probe + pre-auth idle-close [DEVICE-GATED]

From `tests/test_g1_realmd_liveness.py` (the device-gated expectations;
unlocked by setting `POCKET_REALM_G1_DEVICE` to the device serial):

- [ ] **fd-count probe**: note the `:realm` pid; sample
      `ls /proc/<pid>/fd | wc -l` at idle; drive ≥ 10 full logon
      cycles (connect → auth → realm list → world entry → logout →
      disconnect); re-sample at idle, then hold 5 minutes of
      pre-auth-only connects (connect, no bytes, forced close) and
      re-sample. EXPECT the fd count to return to the idle baseline
      each time (asio accept is RAII-safe; the G2 write-completion
      closes bound the stalled peer). A monotonically rising count
      reopens the §8 G2 fd investigation with evidence — do not close
      it on theory.
- [ ] **Pre-auth idle-close**: adb-forward the device's realmd port,
      open a raw TCP connection, send nothing, read to EOF; EXPECT EOF
      within 25-40 s (the one-shot pre-auth timer in
      `AuthSocket::OnOpen`, cancelled at the first packet — post-auth
      idle is liveness-only and is NOT this case). An fd surviving past
      the close is the failure signature (cross-check the probe above).

### A6 — street-reaction protocol (T3 step 6) [EMULATOR-GATED]

- [ ] Scripted protocol: 6 unaddressed says, ≥ 13 s spacing, ≥ 5 bots
      within 25 yd of the speaker. Pass = ≥ 1 reaction ≤ 60 s AND
      ≤ 1 reaction per 12 s world window (the anti-spam law is the
      automatable part); "within quota" assertions are T1 host pins,
      demoted out of T3. Both outcome legs count: a generated street
      say (cloud lane) or the crowd emote on any rejection.

### Deferred to a later session (recorded, not silently dropped)

- **B4 value flip** — artifact-gated: the proposed LOW_POWER_80
  (1250/16/8%) and ALIVE_REALM_320 (1500/18/15%) tuples commit only
  with the on-device benchmark artifact attached; no device in the
  authoring session.

### Landed since the last refresh (2026-09-06, review round 1)

- **E1 pool targets** — landed host-side (commit 21d7614): the 1,090-line
  corpus (greet 330 / busy 132 / silence 104 / idle 190 / kill 142 /
  floor 120 / cheer 24 / seasoning 48) with the register-lint and
  FNV-golden pins. On-device verification of the authored voice quality
  rides the T3 welcome/journal steps below.
- **E2 texts.sql register audit** — landed host-side (commit b3bef5f):
  the 0414 append-only tail migration (30 idempotent row-content
  UPDATEs; texts.sql byte-identical to the shipped 0394 entry) plus six
  GuildManagement inline-literal fixes (submodule 7e2cd2fb).

### Device-gated residue (first device session must close these)

- **Seed-augment re-capture after 0414** — PROVENANCE.json carries the
  host-side revalidation (0414 is text-row DML only; the equip/rnditem
  capture is semantically unaffected), but a full re-capture from a
  fresh first-boot lane run remains the durable fix; re-pin
  `manifest_sha256` + `--write-baseline` when taken.
- **0413/0414 downgrade fail-close drill** — after 0413, APK downgrade
  fails closed (`DB-REVISION`); the only paths are stay-on-new or
  reinstall (data loss). Run the drill on-device once; the release
  notes must carry the string: "After this update, returning to an
  older app version requires a full reinstall (realm data is lost)."
- **First-promote/stop gate interleaving (round-1 R6 observation)** — a
  first-ever promote intent queued behind a stop/save verb holding the
  admission-transition gate could delay `startForeground()` past the
  5 s contract. The demanded fence (startForeground-first +
  stopAccepted) is implemented; observe one real promote/stop race on
  device before closing.
