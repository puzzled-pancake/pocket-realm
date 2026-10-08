# Pocket Realm — Total Completion Plan (v1.0, 2026-10-08)

**Mission:** finish the project. That means (1) TBC 2.4.3 and WotLK 3.3.5a as fully playable,
qualified, coexisting flavors beside Vanilla 1.12.1 with zero vanilla regression; (2) the full
internet-multiplayer stack — the existing LAN / friend-provision / tunnel arc finished and
verified, plus the Nostr discovery browser and the Iroh join gateway per
`docs/NOSTR-IROH-BUILD-PLAN.md`; (3) everything living on **E:**, nothing written to **C:**.

**Status:** awaiting owner approval before the execution workflow is created.

**Sources this plan supersedes-or-anchors on:** `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md`
(stage model S0–S10; its invariants and severity model are adopted verbatim where cited),
`docs/NOSTR-IROH-BUILD-PLAN.md` (frozen listing schema + stage gates N1–N5),
`out/completeness/census-lanes-flavors.md` (22-item lanes ledger), `docs/DECISIONS-PORT.md`
(D1–D37 + S0 exit evidence). Where those documents and this one differ, **this one wins**.

---

## 0. Definition of Done (program level)

1. `schemas/flavor.json`: all three flavors `qualified: true`, `single_qualified_lane: false`;
   check_flavor/check_repo/leak_sweep green in multi-qualified mode.
2. Desktop lane (Windows, E:) proves end-to-end for each flavor: import genuine client →
   data prep → realm boot to READY → auto-login to character screen → play → Save & Exit.
3. One RP6 device checkpoint (owner-assisted, ~30 min per flavor) repeating import → prep →
   boot → play → Save & Exit; evidence archived under `out/completion/`.
4. Internet multiplayer: Nostr browser lists real public realms (mock-relay suite green +
   one live listing published/found against default public relays); Iroh join works
   desktop-to-desktop across two different networks (one hotspot/CGNAT leg); reconnect after
   host IP change proven; privacy gates green (no IP in any payload, relay-only default ON).
5. Code-red gauntlet converged program-wide: **zero open Serious (S0/S1) and zero open
   Medium (S2) findings**; minors (S3) logged and batched.
6. All suites green: repo pytest, Android unit (Gradle), desktop suite, focused per-wave
   suites; leak sweep extended and green.
7. Docs/wiki truthful for multi-flavor + internet play; disclosures (relay IP visibility,
   direct-join IP learning) present in UI copy and docs.
8. Release: version bump to the multi-flavor milestone (0.110.0 per port plan S10),
   release notes, tag; C: drive carries no project artifacts (§7 gate proves it).

---

## 1. Hard rules (every wave, every agent)

1. **E-drive only.** Nothing installs to, caches on, or writes to `C:`. Toolchains, caches,
   SDKs, SDK/NDK, Rust, Python venv, Maven/Gradle homes, genuine clients — all under
   `E:\` (§7). Reviewers treat any C: write as a Serious finding.
2. **Vanilla stays shippable** (port-plan invariant 1): every wave leaves the vanilla lane
   green; byte-identical alias contracts (D27/D28/D29/D30/D31 golden pins) must not move.
3. **Fail-closed** everywhere: unknown/mismatched input → actionable `VAL-*`/loud error.
4. **Provenance intact**: lockfiles/ledgers written by tools, never hand-edited;
   `check_sources`/`check_repo`/`check_flavor` extended, never bypassed.
5. **No cross-flavor data flow**; leak sweep grows each wave.
6. **Low-cost testing pyramid (§6)** is the default; device time is batched into three
   owner checkpoints only (§6 L4).
7. **Privacy (multiplayer):** never an IP/hostname/geo in any published payload; peer
   addresses never displayed; relay-only default ON for public realms; disclosures shipped.
8. **No Blizzard assets in repo**; clients are owner-imported; no client-download URLs.
9. **Decisions recorded** in `docs/DECISIONS-PORT.md` same-wave; waivers never silent.
10. **Kill-switches honored:** Iroh spike (N2) failure stops the Nostr campaign and reports
    measurements — no gateway gets built on a failed gate.

---

## 2. The code-red gauntlet (round-robin reviewer protocol — built into every wave)

Every wave ends with an adversarial review panel. The panel's job is to **kill** the wave
exit, not approve it. This is the "round robin until no serious or medium issues exist" loop.

### 2.1 Panel roles (one reviewer agent per role per round)

| Role | Charter |
|---|---|
| **R1 Architect/Consistency** | flavor leakage; bare build constants; contract drift between `flavor.json`, lockfiles, Kotlin registry; wave vs plan target architecture |
| **R2 Adversarial Breaker** | writes+runs negative tests: wrong-flavor client/server pairs, corrupted manifests, doctored MPQ sets, stale generations, malformed Nostr events, relay mischief |
| **R3 Security/Provenance/E-drive** | hash gates fail-closed; digests match; no unsigned code path; no new network surface beyond design; no PII; **no C: writes; no path literals targeting C:** |
| **R4 Performance/Resources** | storage planner math vs measured; boot/prep times; memory budgets; rate limiter behavior; relay/WS resource use |
| **R5 Content/Fidelity** | DB content sanity per flavor; era-lint correctness (no anachronisms AND no false bans); bot behavior; listing payload honesty (bots labeled, never padded) |
| **R6 UX/Docs/Tests** | per-flavor copy (no hard-coded "1.12"); actionable errors; wiki/README truth; tests meaningful and wired into suites |
| **R7 Privacy (multiplayer waves)** | no IP in payloads/logs/UI; relay-only default; disclosure copy; dedupe/verify actually enforced |

R1–R7 are **read-only with test-run rights** (cheap tests only, §2.4). Fixes land via the
wave's implementer; reviewers never edit production code and never mark their own findings
resolved.

### 2.2 Severity model (maps the owner's "serious or medium")

| Class | Meaning | Gate rule |
|---|---|---|
| **Serious = S0/S1** | breaks an invariant (vanilla regression, cross-flavor flow, fail-open, provenance bypass, data loss, C: write, privacy leak) or wrong-flavor/crash reachable by a user | must fix; blocks exit |
| **Medium = S2** | minor correctness, UX harm, budget miss, doc untruth, missing test | fix or recorded waiver **with reviewer sign-off**; blocks exit while open |
| **Minor = S3** | nit/style/wording/polish | fix or batch; never blocks |

### 2.3 Round algorithm + convergence

```
round n:
  1. Dispatch all applicable reviewers in parallel with: wave charter + End-State
     checklist, full diff set since wave start, evidence pack, rotation order
     (round 1: R1→R7; round 2: R7→R1; alternate).
  2. Each returns findings {id, severity, evidence, repro, proposed test}.
  3. Deduplicate; classify.
  4. Implementer fixes all Serious; fixes or waives-with-signoff all Medium; triages Minor.
  5. Re-run wave verification suite + every reviewer-proposed test.
  6. Convergence check → exit or next round.
```

**Exit = all of:** (a) two consecutive full rounds with **zero new Serious and zero open
Medium**; (b) every reviewer charter ran to completion ("not examined" is not a state);
(c) every finding fixed-and-verified or waived with sign-off in the Decision Log; (d) all
reviewer-proposed tests merged and green; (e) leak sweep clean for the wave's surfaces.

**Hard cap: 6 rounds.** Not converged by round 6 → the wave is **redesigned**, not waived;
round counter resets; the redesign is a Decision Log entry and the owner is told.

### 2.4 Reviewer cost rules (low-cost by construction)

Reviewers may run: static gates, focused pytest/Gradle subsets, greps, and read-only
inspection. Full suites run once per wave at exit by the implementer. No reviewer ever
launches a device build, a soak, or anything > ~10 min wall clock.

---

## 3. Baseline — where the tree actually is (2026-10-08)

Committed: S0 exited (b447ef3). Uncommitted (~72k lines): S1 substantively done; S2 Windows
lane built+locked for both flavors (Android lane missing); S3 partial (no per-flavor era
lint); S4 SQLite/dev lane done for both; S5 identities registered but `qualified: false` by
design; S6 TBC done, **WotLK prep cut off mid-mmap** (no published generation); S7 partial
(catalogs authored but unwired; WTF layouts missing; login topologies UNVERIFIED); S8 largely
done in code (expansion flag, auth frames, flavor-aware realmbuilds); S9 substrate done,
activation blocked on the flip; S10 untouched. Friend/LAN/tunnel/guest-provision multiplayer:
substantially built with tests + live-verification evidence (`FriendProvisionProtocol`,
`Srp6AccountMath`, `FriendInviteStore`, `ProvisionRateLimiter`, `GuestCredentials`,
`LanJoinProbe`, `TunnelInvite`, cloudflared page tunnel + orphan sweep, desktop twin tests).
Nostr/Iroh: **zero implementation**; plan approved 2026-10-08.

Known defects to fix: (a) `build_o09_realm_runtime.py` never stages
`PlayerbotLlmEraStages.generated.h` → any fresh native rebuild fails (ledger
`CROSS-PLATFORM-COMPLETION-LEDGER.md:430`); (b) vanilla literals in MariaDB bootstrap
`CREATE DATABASE` (`DatabaseEngine.kt`, `DesktopMariaDbEngine.kt`); (c) 4 new submodules
uninitialized in `git submodule status`; (d) root debris (`NUL`, `^`,
`.audit_manifest_untracked.py`, `docs/.s1`, `desktop/build-*` trees); (e) genuine clients
now live on **`E:\Wow clients\`** (TBC 2.4.3, WotLK 3.3.5a, vanilla 1.12.1 — relocated
off C: by the owner; exe SHA-256s verified 2026-10-08 against the capture-manifest pins)
but tool/config references still point at the dead `C:\Wow clients\` roots.

---

## 4. Waves

Legend: **[A]** agent-executable · **[U]** owner checkpoint (pauses, asks, minutes) ·
cost class: S (minutes), M (≤1 h wall), L (hours, backgroundable).

### W0 — Foundations: E-drive + hygiene + re-baseline [A, cost M]

1. **E-drive toolchain audit + redirection** (§7): create `E:\pocket_realm_toolchain\`;
   set/verify `GRADLE_USER_HOME`, `ANDROID_SDK_ROOT`/`ANDROID_HOME` (+NDK), `JAVA_HOME`,
   `PIP_CACHE_DIR`, Python venv, `RUSTUP_HOME`/`CARGO_HOME` (for N2). Relocate anything
   currently on C: (SDK move + `local.properties` re-point). Write `tools/check_e_only.py`
   (asserts env redirections; greps tracked configs for `C:` path literals with an
   allowlist; wired into `check_repo`).
2. **Genuine clients on E: — re-point references:** the owner already relocated the clients
   to `E:\Wow clients\` (exe SHA-256s verified against the capture-manifest pins:
   tbc `8f8d7f4c…`, wotlk `aa63a575…`). Sweep every tool/config/test that references the
   dead `C:\Wow clients\` roots and re-point to `E:\Wow clients\`; capture manifests keep
   their historical `clientRoot` but gain a relocated-path annotation.
3. **Repo hygiene:** `git submodule init` the six flavor submodules (verify pins match
   `sources.json`); delete build debris (`desktop/build-*`, `build-fixer-*`, stray
   `NUL`/`^`/`.audit_manifest_untracked.py`/`docs/.s1`) — anything ambiguous is listed for
   the owner, not deleted.
4. **Re-baseline gates:** run `check_repo`, `check_flavor`, `leak_sweep`, repo pytest,
   Android unit Gradle, desktop suite; archive to `out/completion/w0/`. Any pre-existing
   failures get triaged as wave findings or recorded pre-existing.
5. **Commit checkpoint [U]:** land the pending S1+ work as reviewable commit(s).
6. Gauntlet round(s) → exit.

### W1 — Build-breaking defect repairs [A, cost M–L]

1. Stage/generate `PlayerbotLlmEraStages.generated.h` in `build_o09_realm_runtime.py`;
   prove with a **compile-only** fresh Windows rebuild for vanilla (cheapest proof the
   class is dead).
2. Flavor-route the MariaDB bootstrap `CREATE DATABASE` literals through the resolver
   (both engine twins); tests pin per-flavor DB name sets.
3. Fresh Windows realm-runtime rebuild for **tbc + wotlk** (full lane, lockfiles
   re-asserted byte-consistent with the shipped ones or deliberately regenerated with a
   Decision Log entry).
4. Gauntlet → exit.

### W2 — WotLK data prep completion [A, cost L background]

1. Re-run/finish WotLK prep on the desktop lane (`tools/win_prepare_data.py --flavor wotlk`,
   E: paths, background; resumable where the tool supports it) → **published generation**
   with measured counts/time/size archived to `out/completion/w2/`.
2. Baseline + floors recorded (`sqlite-seed-baseline-wotlk` tripwires already exist —
   re-verify against the finished prep).
3. **Decision point [U]:** on-device vs desktop-assisted as the official WotLK prep story
   (recommendation: desktop-assisted official, on-device best-effort; record D-entry).
4. Gauntlet → exit (R4 leads on the measured budget).

### W3 — Port lane remainder (S3/S7) [A, cost M–L]

1. **Era lint per flavor:** per-stage era baselines in `TimeframeStage.kt` + timeframes
   schema data; TBC bans WotLK+ concepts, allows Outland/flying; WotLK allows through
   WotLK, bans Cata+. Corpus-driven tests both directions (no anachronisms, no false bans).
2. **LLM corpora [U decision embedded]:** recommendation = shared lore corpus + per-flavor
   era-lint filter (no new per-flavor jsonl to author); record decision; bind
   `llm.lore_cards` in `flavor.json` accordingly; prompt-pack identity strings per flavor.
3. **Wire TBC/WotLK settings + binding catalogs** into `InGameSettingsEditor` (replacing
   the four vanilla call sites); implement `InGameSettingsFiles.layoutFor()` 3-level WTF
   layouts for tbc/wotlk incl. the WotLK char-level cache set (H4-5); golden round-trip
   tests with synthetic WTF trees; corruption-recovery tests.
4. **Conf staging fail-loud:** unknown-keys-for-flavor aborts staging (test it); conf
   matrix consumers honor `excluded_keys` (VoiceChat).
5. **Login topology verification (cheap):** drive the staged genuine clients on the
   Windows desktop lane via `Win32AutoLogin` to verify `Build8606LoginTopology` /
   `Build12340LoginTopology` signatures against live windows; promote from UNVERIFIED;
   androidTest stays signature-pinned.
6. Graphics-route projection: `graphics_routes`/`vanilla_tweaks` manifest fields get their
   Kotlin projection (DXVK-only enforced for tbc/wotlk with honest copy) — retires D15(4).
7. **Per-flavor addon catalogs + addon-lane flavoring.** Baseline (measured 2026-10-08):
   the frozen vanilla catalog (`android/app/src/main/assets/addons/catalog-v1.json`,
   154 addons, 266 compatibility pairs) carries **5 TBC-capable projects** (pfUI, pfQuest,
   ShaguTweaks — all archived upstream 2026-06, so pins are frozen versions — plus WoW
   VoiceOver and FonzAppraiser) and **2 WotLK-capable** (VoiceOver, FonzAppraiser);
   149 are vanilla-only. Work items:
   - Author `catalog-tbc.json` / `catalog-wotlk.json` as **new assets** (D29: the vanilla
     catalog is byte-frozen; `matrixId` scheme continues; same schema incl.
     `handheldScore`/`handheldVerdict` curation fields + per-flavor `compatibilityPairs`).
   - Research the era-native ecosystems per flavor (TBC: DBM-TBC, Omen, Recount,
     AtlasLoot, Bartender, PitBull, Quartz, Prat, Postal-TBC, Bagnon, …; WotLK: DBM,
     Omen3, Recount, Bartender4, PitBull4, Grid, Quartz, Prat3, Bagnon, PowerAuras
     Classic, …) with the same evidence discipline (githubUrl, communitySignal,
     installSource pin, hash) — web-research agents, curated by R5/R6.
   - Carry-over set seeded from the 5/2 above; `VoiceOverReleaseResolver` selects
     per-expansion artifacts; archived-repo pins recorded as frozen with honest
     maintenance copy.
   - `AddonRepository` resolves the active flavor's catalog; `AddonsScreen` browses only
     that catalog; addon generations/projection namespaces stay per-flavor (S9 substrate).
   - Negative matrix: 11200-TOC into tbc/wotlk rejected, 20400 into vanilla/wotlk
     rejected, 30300 likewise — each with per-flavor copy (validator already routes
     `acceptedInterfaces` through `ActiveFlavor`; pin it with tests).
   - **AndroidPort addon [U decision]:** port to the flavor's UI API or exclude per
     flavor with honest copy (port-plan S7.6 decision point → §8 table).
8. Gauntlet → exit.

### W4 — Identity flip, coexistence, auth matrix, friend-arc closeout [A + U gate, cost M–L]

1. **Desktop-lane end-to-end per flavor:** genuine-client import (folder + archive lanes) →
   prep consumption → realm boot READY → auto-login → Save & Exit; negative matrix
   (wrong-build/doctored-MPQ/oversized → VAL codes) as automated tests.
2. **3×3 auth matrix (wire-level, no devices):** `{vanilla,tbc,wotlk}²` via the desktop
   harness — only matching pairs authenticate; shared `0x1EC` asserted; per-flavor frame
   sizes (26/32/32) pinned; `probe_world_auth` fails loud on drift.
3. **Friend/LAN/tunnel arc closeout:** audit the untracked cluster (already largely tested)
   for TODOs/stubs; finish seams; ensure `LanJoinProbe`, `TunnelInvite`, guest bans,
   provisioning rate limits, SRP6 math all have green tests on both platforms; wire any
   dangling UI; leak-sweep the copy.
4. **Qualification flip [U go/no-go]:** set `tbc` then `wotlk` `qualified: true` +
   `single_qualified_lane: false`; check_flavor multi-qualified mode; HomeScreen multi-flavor
   cards live; per-flavor storage accounting; coexistence tests (alternating sessions on the
   desktop lane, journals/backups/snapshots per-flavor, kill-mid-switch adversarial case).
5. Gauntlet → exit (R1+R2 lead).

### W5 — Android native builds (o09) for tbc/wotlk [A, cost L background]

1. Build the o09 Android realm runtime per flavor (arm64-v8a mandatory; x86_64 if vanilla
   parity requires) with NDK on E:; write/assert `realm-runtime-lockfile*-{flavor}` per
   D35 naming; packaging gate learns the set; APK assembles green (no device needed).
2. Extractor `.so` packaging verification per flavor (tbc/wotlk lockfiles already exist).
3. Gauntlet → exit (R3 leads on provenance).

### W6 — Nostr + Iroh campaign (per `NOSTR-IROH-BUILD-PLAN.md`) [A + U gates]

**N1 — Nostr browser [A, cost M–L].** Shared `discovery/nostr/` Kotlin module in
`shared-sources.json` (both platforms). Components: `RealmListingEvent` (NIP-01
serialization + BIP-340 Schnorr via Acinq `secp256k1-kmp` — the one real dependency),
`RelayPool` (OkHttp WebSockets; NIP-11 capability checks; NIP-42 AUTH; NIP-13 PoW only if
a chosen relay demands; write-ALL/read-ALL, merge by event id), `ListingStore` (dedupe on
`(pubkey,d)` newest-wins; **verify signatures ourselves**; 10-minute client freshness).
Listing = kind **30388**, `d=pocketrealm-v1`, `t=pocketrealm`; content JSON exactly per the
frozen schema (name, motd, flavor+build, timeframe stage, rates, players{humans,bots} —
bots labeled never padded, `join` null until N3, optional statusPageUrl; **never** IPs,
hostnames, geo, download URLs). Browser UI gains a real "Public realms" section (honest
empty state until listings exist). Host heartbeat: republish every 5 min while public+up,
reusing the realm-status payload builder (single source of truth); NIP-09 delete on
graceful shutdown; **never** published while visibility OFF/FRIENDS. Relay set: 3–5 defaults
(damus.io, nos.lol + 2), user-editable.
*Tests (all low-cost):* NIP-01 golden vectors; signature round-trip; freshness/dedupe
units; mock-relay WebSocket suite (MockWebServer playing good/bad/demanding relays);
heartbeat lifecycle (no publish when private; delete on shutdown); focused Gradle on both
platforms. Gauntlet (R7 leads) → exit.

**N2 — Iroh spike (GATE) [A + one U checkpoint, cost L].** Windows desktop via Maven
`computer.iroh:iroh` 1.3.x: two-endpoint echo — multi-MB transfer, direct-vs-relay
outcome, path-change stall duration, long-lived-flow rate-limit behavior. Android NDK
build of iroh-ffi (aarch64 + armv7) with Rust/Cargo on **E:**; `.so` proven loading
**inside the real app** (owner checkpoint: install debug APK on RP6, load+echo once —
minutes; an E-resident emulator is an acceptable substitute if one exists). Assert the
published discovery record carries **no `addr=`**. **PASS requires:** .so loads in-app;
byte tunnel works both transports; no hard rate-limit kill; stall measured. **FAIL →
campaign ends here; measurements become the deliverable** (Decision Log entry, owner
notified). One CGNAT/hotspot leg: owner-assisted or documented desktop approximation.
Gauntlet on the measurements → gate decision.

**N3 — Gateway + join [A, cost M–L].** Host: mangosd stays loopback; Iroh connection muxes
3724+8085 (port id + raw bytes). Joiner: loopback listeners 3724/8085; realm-list
**parse + rebuild** of the auth-packet C-string `ip:port` with the three per-flavor
fixtures (1.12/2.4.3/3.3.5 — length fields differ; same-length swap is not enough; CI test
asserts the rewritten list is what the client would dial); loud failure on dial/rewrite
error; **refuse simultaneous host+join** on one instance; listings carry the `join`
descriptor (node id + home relay URL only); relay-only switch default ON. Two-process
loopback join E2E on desktop (real client optional but cheap once W4.1 works).
*Tests:* mux framing units; fixture-driven rebuild tests; simultaneous-host+join refusal;
privacy greps (no IP/addr strings anywhere in payloads). Gauntlet (R2+R7 lead) → exit.

**N4 — Reconnect proof [A + optional U, cost S–M].** Host network change (desktop
interface toggle / re-dial) with realm key unchanged → joiner redials the same endpoint id
and reconnects; no new listing required. Test on desktop with two interfaces; one real
hotspot toggle owner-assisted if available. Gauntlet → exit.

**N5 — Campaign gates + verdict [A, cost M].** check_repo + leak_sweep extended (banned:
IP-literal patterns in published payloads; `addr=` in discovery records); full suites both
platforms; one independent fresh-eyes privacy read (R7 + one non-panel reviewer):
no IP in any payload, relay-only default, no peer-IP display, disclosures present. Verdict
recorded. Gauntlet → exit.

### W7 — Program qualification & release (port-plan S10) [A + U checkpoint, cost L]

1. Full suites everywhere; leak sweep final; `tools/check_e_only.py` green; C: spot-audit.
2. **Owner device checkpoint (RP6):** per flavor import → prep → play 30 min → Save & Exit;
   optional 2 h soaks; evidence archived. (This is the *only* device gate in the program
   besides the N2 `.so` load.)
3. Docs/wiki truth pass: multi-flavor pages, per-flavor import sizes, internet-play guide
   (Nostr browse → join flow, disclosures, relay editing, self-hosted relay escape hatch).
4. Release: `flavor.json` all-qualified shipped, version 0.110.0, release notes, tag.
5. **Final all-program code-red round** (whole diff vs b447ef3, not one wave) → converge →
   done per §0.

---

## 5. Sequencing & parallelism

```
W0 → W1 → W2 (background) ─┐
W1 → W3 ───────────────────┼→ W4 → W5 → W7
                            │   (W5 can overlap W6)
W0 → W6: N1 can start any time after W0 (independent of port waves);
         N2 needs W0's Rust-on-E; N3 needs W4.1 (desktop E2E lane) + N2 PASS;
         N4 after N3; N5 last.
```

Hard dependencies only; otherwise the workflow runs waves concurrently where safe.

---

## 6. Low-cost testing pyramid (default for everything)

| Level | What | Cost | Examples |
|---|---|---|---|
| L0 | static gates | seconds | check_repo, check_flavor, leak_sweep, check_sources, check_ledger_golden, check_e_only |
| L1 | unit + golden | minutes | pytest repo suite; Gradle unit android+desktop; NIP-01 vectors; fixture rebuild tests; parity tests |
| L2 | host builds | minutes–hours | compile-only lanes; APK assemble; fresh win rebuilds; iroh-ffi NDK build |
| L3 | desktop-lane runtime | minutes | realm boot READY smokes; wire probes (auth 3×3); auto-login vs real clients; two-process join E2E; mock-relay suites; loopback reconnect |
| L4 | owner checkpoints | batched, minutes each | exactly three: N2 `.so` load on device; W7 RP6 qualification; optional soak/hotspot legs |

Rule: if a verification can be expressed at L0–L3, it must be. L4 exists only where an
Android runtime or a real network path is irreducible. Every wave's End State lists its
evidence per level.

---

## 7. E-drive enforcement spec

| Concern | Location on E: |
|---|---|
| Repo | `E:\pocket_realm_complete` (existing) |
| Toolchain root | `E:\pocket_realm_toolchain\` |
| Gradle | `GRADLE_USER_HOME=E:\pocket_realm_toolchain\gradle-home` |
| Android SDK/NDK | `E:\pocket_realm_toolchain\android-sdk` (relocated if currently on C:, `local.properties` re-pointed) |
| JDK | `E:\pocket_realm_toolchain\jdk` (install if the current JVM is C:-resident) |
| Python | venv `E:\pocket_realm_toolchain\venv`; `PIP_CACHE_DIR` under it |
| Rust (iroh-ffi) | `RUSTUP_HOME`/`CARGO_HOME` under `E:\pocket_realm_toolchain\rust` |
| Genuine clients | `E:\Wow clients\` (already relocated off C: by the owner; hashes verified vs capture manifests) |

`tools/check_e_only.py` (new in W0): asserts the env table; scans tracked configs/scripts
for `C:\` literals (docs allowlisted for historical prose); lists any configured cache root
on C:. R3 re-checks every round; any C: write is Serious. C: is already clean of project
artifacts (owner wiped it 2026-10-08); the gate exists to keep it that way.

---

## 8. Owner decision points (answer at approval; defaults shown)

| # | Decision | Default recommendation |
|---|---|---|
| 1 | WotLK official prep story (W2.3) | desktop-assisted official; on-device best-effort |
| 2 | LLM lore corpora (W3.2) | shared corpus + per-flavor era-lint filter; no per-flavor jsonl |
| 3 | Qualification flip timing (W4.4) | flip tbc+wotlk together after W4.1–W4.3 pass |
| 4 | Default relay set (N1) | damus.io, nos.lol + two more from the consult; user-editable |
| 5 | N2 device proof | RP6 checkpoint (minutes); emulator on E: acceptable substitute |
| 6 | Commits | checkpoint commit at each wave exit as the gauntlet passes |
| 7 | WotLK x86_64 o09 build (W5.1) | arm64-v8a only, matching the shipped device lane |
| 8 | AndroidPort addon scope for tbc/wotlk (W3.7) | exclude per flavor with honest copy; port later if demand |

---

## 9. Evidence & journal protocol

Every wave archives to `out/completion/w<N>/`: gate outputs, test summaries, build logs,
measurement files, and a `WAVE-EXIT.md` (end-state checklist ticked, rounds count, findings
ledger, waivers). Decision Log entries append in the same wave. The workflow's final
artifact indexes all wave exits against §0.

---

## 10. Workflow mapping (what gets built after approval)

One dynamic workflow, structured as: per wave — implementer subagent(s) with this plan's
wave charter → verification run (pyramid levels for that wave) → code-red panel dispatch
(R1–R7 subset, rotating order) → finding-fix loop → convergence check (two clean rounds, 0
Serious/Medium open, cap 6 → halt + report for redesign) → wave exit record → next wave.
Owner checkpoints (W0.2 client move, W2.3/W4.4 decisions, N2 load, W7 device day) pause the
run and ask. Background-capable long jobs (W2 prep, W5 builds) run detached with polling.
No stage advances on schedule — only on convergence.
