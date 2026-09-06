# Review rounds — rp-depth-fix-plan v2.3 §15 round-robin gate

Protocol (plan §15, verbatim rules summarized; the plan section governs):

- **Panel: 8 independent reviewer agents, one fixed scope each** —
  R1 Native cloud lane (§2 A1/A3/A5/A7 + PlayerbotLlmGates.h +
  SayAction/AiFactory/RpgTriggers anchors), R2 Native transport +
  security (§0.c riders, G3, A8, A9, driver anchor byte-exactness),
  R3 Authored corpus + persona (E0 pools, E3 wiring, A5 floor wording),
  R4 Schema + memory persistence (C2/C8 migration law + seed re-pins),
  R5 App conf/emission surface (CloudLaneConf, appended-block-only law,
  detekt baseline legitimacy, B2/B8 pins), R6 App UX/supervisor
  (F2/F3 copy truthfulness, Cloud toggle disclosure, B7, B5/F1 seams),
  R7 Harness + tests (tools/rp_harness correctness, the full T1/T2 pin
  matrix vs §10), R8 Whole-plan conformance (§0 standing constraints,
  §11 ordering, PLAN-LOG claim honesty with 5 random spot-verifies).
  Each reviews the full run diff (baseline commit to HEAD) against the
  named plan sections plus a general bug hunt in its scope.
- **All-or-again rounds**: a round PASSES only if ALL 8 reviewers
  return zero BLOCKER/MAJOR findings. MINOR/nit findings are recorded
  but do not fail the round. If ANY reviewer reports a BLOCKER/MAJOR or
  errors out (infra failure, timeout, non-verdict), the fixes or re-run
  are applied and the ENTIRE 8-reviewer round runs again from scratch —
  no partial credit, no carried verdicts. Loop until one full round
  records 8/8 PASS.
- **Verify, not vibe**: every BLOCKER/MAJOR cites file:line evidence
  from actually reading the tree (or a failing command actually run).
  A reviewer that cannot run what it needs (no device, no compiler)
  marks the item UNVERIFIED; an UNVERIFIED potential-BLOCKER still
  fails the round and escalates to a scope that can verify it.
- **Per-round entry**: round number, per-reviewer verdict
  (PASS / findings list with severities+evidence / ERROR), the fixes
  applied between rounds, and the diffstat re-reviewed.
- **Escalation honesty cap**: if two consecutive post-fix rounds surface
  no NEW findings but a stale finding cannot be resolved without device
  access, record it as a device-gated residue in
  DEVICE_QUALIFICATION_CHECKLIST.md — do not loop forever on an
  unverifiable.

The gate is closed when this log ends with a round recording 8/8 PASS.

---

## Round 1

**Diffstat re-reviewed**: `git diff 6045eeb..b3bef5f` — 114 files,
+17150/−296, plus submodule commits `3b77c5f4..7e2cd2fb` (4 commits:
A9, A1/A1b, E3, E2-inline) and the cmangos submodule G1/G2 commit
`ce83805d`. Dispatched 8 reviewers in one foreground message.

**Result: 6/8 PASS required → 2/8 PASS. ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 3 MAJOR: (1) A7 Tier I
  interactive budget `InteractiveBudgetAdmits` is dead code (definition
  PlayerbotLlmMemory.cpp:3424 + header decl only; zero call sites; the
  §0.a row "0 = interactive cloud replies off" does nothing; only
  limiter is the governor burst cap ≈ 7.5-12× the intended 240/hr
  ceiling); (2) A7.3 bot2bot containment missing — `LLMBotToBotPerDay`
  fully plumbed but zero consumers, no autonomous-exchange depth cap,
  while the app emits the raised chance 25 (`LlmRuntimePolicy.kt:382`);
  (3) T1 consume-not-copy violated — 4 of 9 PlayerbotLlmGates.h helpers
  never called (`ContainsNameIgnoreCase`, `ReplyGateAllowed`,
  `ClassifyGeneration`, `StreetAdmissionOrder`); the payload still
  computes `boost::algorithm::icontains` substring matching at driver
  :2060, so "Varleigha" mentions arm hard triggers (stray-mention
  spend A3.3 exists to prevent; the host pin is vacuous). +6 MINOR
  (raid fallback SayToParty subgroup visibility; C4 digest N-bot
  double-count; A2 zone-cap eviction dormant; A3 recording not widened
  to SRC_RAID; worker-thread sync DB read in greet fallback; drawn
  fallback droppable by the cap-2 deque).
- **R2 (transport/security): FINDINGS** — 2 MAJOR: (1) A8 cap class
  never reaches the log — the concurrency-cap branch returns `{}` and
  `genClass` is consulted only in the `httpBody == "error"` arm, so
  every cap rejection logs `class=empty` (contradicts the payload's own
  comment; PLAN-LOG claims the full class set shipped; the pin only
  asserts `NoteGenClass("cap")` exists); (2) §0.c.5's deliverable
  missing — nothing pins "the app never emits LLMPromptDumpFile"
  (grep-verified zero pins; riders are merge-blocking per §0.c). +3
  MINOR (run_suite false-flags busy turns — same root as R7#5; dead
  `or True` tautology — same as R7#4; TLS1.2 floor unconditional while
  the doc claims 0 restores today's handshake).
- **R3 (authored corpus/persona): FINDINGS** — 1 MAJOR: the E1
  battery's chat-acronym lint at tests/test_llm_e1_corpus.py:127 is a
  silent no-op — literal backspace bytes (0x08) where `\b` word-boundary
  escapes were intended (the documented git-bash backslash-mangling
  gotcha; `cat -A` verified), so the "no modern chat-speak" clause
  enforces nothing and a control byte sits in a committed file. The
  reviewer independently re-ran the intended check: the shipped corpus
  is clean (weakened pin, not bad wording). +2 MINOR (two rogue-voiced
  phrases in the NOBLE seasoning row; E0 street bank placeholder-free
  vs the plan's `{P}` suggestion — judged the correct call, recorded as
  interpretation).
- **R4 (schema/persistence): FINDINGS** — 1 MAJOR: C2's `voiced_at`
  column shipped with no writer and no reader (grep: comment mentions +
  schema pins only; TickInitiative still queries without it and seeds
  only the process-local set; LogFact never stamps it), so debt/goal
  initiations re-fire after every restart — the exact bug C2 exists to
  fix — while PLAN-LOG:1080 claims "the 0413 columns all have
  writers/readers now". +4 MINOR (C2 downgrade runbook/T2/release-note
  deliverables; manifest `source_commits.playerbots` one commit stale;
  test_sqlite_seeding.py:4 stale "413-entry" docstring; arm64 staging
  tree not restaged for 0414 — gitignored build state that gradle would
  package into an arm64 APK).
- **R5 (app conf/emission): FINDINGS** — 2 MAJOR: (1) A0.a key-parity
  8-of-9 — `LLMPartyReplyEnabled` registered driver-side (:1500,
  conf.dist :3708, consumers :2074/:2151) but absent from CloudLaneConf
  (grep-verified), and the §10 T0.5 key-parity gate was never authored
  (twelve driver keys absent from the Kotlin surface with no gate to
  detect the drift; the vice-versa direction holds); (2) A7.6's four
  device leak-proof gradle pins missing (tier ctx < 65536, device block
  never emits LLMProviderSafe, external block always emits = 1, debug
  block emits neither — grep-verified zero pins; the code is correct
  today but ExternalApiTierActive() is the §0.13 conjunction ground
  with no regression guard). +2 MINOR (WorldLogFileLevelTest pins the
  mapping not the emitted line; B8/G3 staging functions unpinned —
  device-gated).
- **R6 (app UX/supervisor): PASS** — 3 MINOR (first-promote/stop gate
  interleaving — device-gated residue candidate; "orphan self-healed:
  WORLD" enum token reaches the user; promoteToForeground comment vs
  BIND_AUTO_CREATE semantics). All in-scope items verified
  code-supported and pinned; gradle re-run by the reviewer: 1094 tests,
  0 failures.
- **R7 (harness/tests): FINDINGS** — 5 MAJOR: (1) the T1
  "interactive-exempt-from-arbiter" pin is missing and its subject is
  unwired (InteractiveBudgetAdmits — cross-ref R1#1; PLAN-LOG:922
  claims it shipped); (2) the T1 pin for
  `LLMCloudLineBudgetPerHour = 0` blocks-only-non-exempt semantics
  (`!globalCap ⇒ return exempt` at PlayerbotLlmMemory.cpp:1849) does
  not exist anywhere; (3) the farm pin "N trades in 60 s ⇒ 1 deed" is
  contradicted by the implementation — the trade deed award sits after
  AddBoundedSentimentInput's admission, unconditional except the key,
  unbounded via AddRelationshipPoints, and the shipped test
  (test_llm_c_workstream.py:169-173) enshrines the opposite; (4)
  tautological A8 no-content pin — `... or True` at
  tests/test_g3_tls_a8_observability.py:195 cannot fail; (5) the A8
  post-pass check_a8_lines false-fails on busy/cap-class turns
  (dispatch+end with no begin is the pinned-correct shape; reviewer ran
  the check and got a false violation), so the canonical smoke command
  fails on correct behavior. +1 MINOR (reset-state op scope drift —
  documented, device-gated).
- **R8 (whole-plan conformance): PASS** — all 13 standing constraints +
  §0.a/§0.b/§0.c/§0.d mechanically verified (commands cited in the
  reviewer report); 5/5 PLAN-LOG spot-checks TRUE; all 11 queued
  interpretations judged SOUND (A6 street ladder; WS-C nine keys;
  C5 join-deed; F1 claim=adopt; G2 twelve sites; D1 keep-best at call
  site; D1 native-key kill-switch; D3 min()-only cap; E1 ~1,090
  vector; E2 0414 tail migration; E2 PROVENANCE host-side
  revalidation). 2 MINOR (PLAN-LOG Batch E2 misstates where the
  re-capture residue lives + DEVICE_QUALIFICATION_CHECKLIST still lists
  E1/E2 as deferred; §11 soft reorderings with no hard inversion).

**Fixes applied between Round 1 and Round 2**: see the Round 2 entry's
preamble (one fix batch, one commit; every fix cites its finding).
MINORs accepted as recorded residue (with rationale) or fixed where
trivial: the NOBLE seasoning row swap, the manifest source_commits
refresh + arm64 restage, the stale docstrings, the checklist refresh,
the TLS-floor doc wording.

## Round 2

**Diffstat re-reviewed**: `git diff 6045eeb..9272f2e` — 116 files,
+18038/−315 (the round-1 fix batch is commit 9272f2e; same submodule
ranges). Dispatched 8 reviewers fresh per §15.3 (4+4 in two concurrent
foreground waves against the same frozen tree; no tree changes during
the round).

**Result: 6/8 PASS → ROUND FAILS.**

- **R1: PASS** — all three round-1 fixes verified holding
  (InteractiveBudgetAdmits placement/semantics; BotToBotAdmits at all
  four sites with likePlayer un-gated and device byte-identity;
  consume-not-copy with truth-table-equivalence checks and a compiled
  harness run). 2 new MINOR: bot2bot quota burns on depth-capped
  denials (quota stage before depth stage — the street-ladder law pins
  the opposite discipline); dead disjunct in ContainsNameIgnoreCase's
  right-boundary test ("Varleigh'x" matches; isalnum('\'') is false so
  the 's clause never fires).
- **R2: FINDINGS** — 2 MAJOR, both in the round-1 fix family: (1) the
  A8 class truth is still broken on the WIDER surface — every transport
  failure returns the sentinel body "error", LooksLikeVoicableText
  ("error") is TRUE, so the legacy-prose-fallback arm runs and its
  logEnd("ok") is unconditional: timeout / http_%d / error classes are
  unreachable (a connect-refused failure logs as a fast success;
  reviewer compiled the real headers and traced all three); (2)
  run_suite's new class-aware invariant wrongly expects no-begin on CAP
  turns — the begin line is emitted BEFORE GenerateHttp (whose first
  check is the cap), so a real cap turn carries dispatch+begin+end and
  the canonical smoke gate false-fails it (reviewer ran the real shape
  through the checker; the round-1 harness test enshrined the wrong
  expectation). Busy is correct (both busy paths return before begin).
  +3 MINOR (vacuous allowlist entries; A9 reply-miss logs the constant
  name; no pin on the legacy arm's class — how finding 1 survived).
- **R3: PASS** — both round-1 fixes verified (lint byte-clean with
  teeth; NOBLE/ROGUEISH swap with the golden re-run UNCHANGED at
  de4bd8227a3ab0d1 by the reviewer's own compile). 1 minor note
  (uppercase-acronym hardening suggestion).
- **R4: PASS** — voiced_at verified end-to-end (reader/lazy-seed/stamp
  placement/IsNULL convention/sqlite parity; all five 0413 columns now
  have writers+readers); all four round-1 MINORs verified fixed
  (source_commits one-line diff vs b3bef5f, arm64 0414.sqlz on disk,
  PROVENANCE = recomputed LF-normalized sha, checklist entries; the
  un-authored T2 downgrade test judged acceptable — the marker-count
  laws are already pinned and the drill is device-gated); seeder
  re-run SEED OK with zero baseline churn; sqlite batteries 45/45. 1
  new MINOR: last_greet_line varchar(255) vs the writer's
  escape-aware worst case (theoretical; a future 0415 or a 120-truncate
  closes it).
- **R5: PASS** — both fixes verified WITH live mutation testing of the
  key-parity gate (dropped family member, new driver key, orphan
  Kotlin key, frozen-set rot in both directions — each fails the right
  leg; tree left clean). A7.6 pins green under gradle (32/32 class).
  detekt baseline diff vs b3bef5f EMPTY. +5 MINOR (char-literal parser
  edge, pin-completeness nits, port-segment cosmetics, upgrade-marker
  as plain default-off, B8 staged-payload pin device-gated).
- **R6: PASS** — the checklist residue entry verified honest; all
  round-1 clean areas re-verified (F1/B5/F2/F3/B7; gradle 1096/0/1);
  no fix-batch regression. +3 MINOR (carried enum-token polish;
  carried BIND_AUTO_CREATE comment; NEW: the toggle support copy
  mentions party answers while LLMPartyReplyEnabled is staged 0 —
  truthful at the documented T3 flip; reword if T3-party never goes
  green).
- **R7: FINDINGS** — 4 of 5 round-1 fixes verified sound with fresh
  eyes on every new pin (111 tests green; T1 matrix coverage
  re-walked, both round-1 gaps confirmed closed); the fifth (run_suite
  class-aware) has the same wrong-cap-expectation MAJOR as R2#2
  (independently re-derived from the payload emission order + an
  empirical checker run; busy-half correct). +3 MINOR
  (_driver_keys regexes driver comments in the orphan direction;
  stale run_suite docstring; allowlist scope note).
- **R8: PASS** — all 13 standing constraints re-verified mechanically
  on the current tree (including the new behaviors' kill-switch
  surface, §0.11 one-line manifest diff, §0.13 self-gating, edit
  lanes, 153-op anchor replay); 5/5 NEW PLAN-LOG spot-checks TRUE; all
  11 round-1 interpretations re-confirmed SOUND plus the NEW
  per-bot-vs-per-pair depth-cap interpretation judged SOUND (the four
  chance sites are broadcasts — no peer guid exists to key a pair on;
  per-bot strictly dominates for containment). 2 MINOR (the shared
  bot2bot quota-order note; plan cite drift on the silence pin).

**Fixes applied between Round 2 and Round 3**: the two MAJORs (A8
legacy-fallback arm logs the noted class; run_suite NO_BEGIN_CLASSES
narrowed to busy-only with the harness test and docstring corrected)
plus the repeatedly-flagged bot2bot quota/depth order MINOR and the
ContainsNameIgnoreCase dead-disjunct NIT. See Round 3's preamble.

## Round 3

**Diffstat re-reviewed**: `git diff 6045eeb..3ac8fcd` — 116 files,
+18312/−315 (the round-2 fix batch f8f3334 plus the PLAN-LOG handoff
commit; same submodule ranges). Dispatch note: the FIRST Round 3
dispatch died partially from an infra error — R1 and R2 agents expired
at DISPATCH with nothing landed, while R3–R8 returned PASS with zero
BLOCKER/MAJOR; per §15.3 a reviewer error invalidates the whole round
(no partial credit, no carried verdicts), so the round was re-run
FRESH with all 8 reviewers against the unchanged tree. This entry logs
that fresh full round.

**Result: 6/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 1 MAJOR: A3's exactly-one
  responder is party-only while the widened hard trigger admits
  unaddressed SRC_RAID on the cloud lane (`HardTriggerAllowed`'s
  party/raid arm at PlayerbotLlmGates.h:72-79 vs the claim block gated
  `chatChannelSource == SRC_PARTY` in the applied SayAction payload) —
  once `LLMPartyReplyEnabled` flips 0→1, one unaddressed raid line
  would dispatch N generations (N external calls, N Tier I budget
  burns, no 2 s coalescing); dormant only because the key defaults 0.
  +2 MINOR: A1's "refusal logs once per bot per session" deliverable
  absent (no stamp anywhere in the payload); the rpgchat
  CloudQuotaAdmits spend runs before the cheap `chatLine == -1`
  early-return (a reset-but-unrearmed trigger burns a realm-global
  admission without generating — contra the street-ladder
  cheap-before-expensive discipline). Verified green: §0.13 at all 9
  widened sites, device byte-identity (truth tables), quota math incl.
  the round-2 depth-before-quota fix, threading (no nested StateMutex;
  async boundary by-value only), consume-not-copy, plus an independent
  52-probe edge battery compiled against the real gates header.
- **R2 (transport/security): PASS** — anchors replay 153 ops no drift
  (completeness independently confirmed: 129+2+22 registrations);
  §0.c.1 redaction + SEC_MODERATOR debug-gate verified at payload
  level with a scratch mutation failing the pin; §0.c.3 port bounds;
  §0.c.5 prompt-dump absence; G3 TLS floor/verify/host-pin with the
  staged CA bundle byte-identical to the live curl.se upstream (121
  certs); A8 reqId threading at all three dispatch sites; BOTH round-2
  fixes behavior-verified by compiling the real transport headers (10
  failure shapes classify correctly; the legacy arm logs genClass) and
  mutation-tested. 1 MINOR (recv-phase stalls classify as empty/ok
  rather than timeout — durMs still exposes the stall; log-only).
- **R3 (authored corpus/persona): PASS** — golden recompiled and
  observed de4bd8227a3ab0d1 (the committed pin); 70-test corpus battery
  green; lint teeth re-verified by repr (real \b escapes, no control
  bytes); target vector, seasoning lane key arithmetic, pool order,
  floor laws, E2's 30 UPDATEs with WHERE keys resolving byte-for-byte,
  texts.sql byte-identity, E3 wiring all verified. 4 MINOR (one
  same-class "wanna" drift left in BroadcastHelper; the known
  test_seaoning typo; the known PlayerbotSecurity comment
  overstatement; a "rewored" CHECK-message typo in the banter harness).
- **R4 (schema/persistence): PASS** — migration law verified
  mechanically (manifest first 412 entries byte-identical; full
  select_inputs replay with zero sha mismatches; 0414 idempotence
  proven on both fresh-provision and upgraded-ledger paths; texts.sql
  blob-identical across the submodule range); all five 0413 columns +
  the 0414 corrections have their writers/readers; PROVENANCE
  LF-normalization mechanically demonstrated (mutation flips the hash,
  EOL-only does not); seeder re-run SEED OK; sqlite batteries 97 green.
  2 MINOR (the "533 dead rows" figure is wrong — 388 strict / 450
  loose, a documentation nit the machine pins don't depend on; the
  MySQL-dialect ODKU crossing semantics are pinned by ordering, not
  executed — impossible off-device without MariaDB).
- **R5 (app conf/emission): PASS** — CloudLaneConf 9/9 against the
  driver registrations; appended-block-only law; write-set/snapshot;
  the key-parity gate mutation-tested live in both directions (each
  mutation failed the right leg; tree left clean); A7.6 pins green
  under gradle; detekt baseline diff vs b3bef5f EMPTY and
  machine-generated; no emission drift from either fix batch. 4 MINOR
  (device-block pin enumerated 8 of 9 family keys — LLMPartyReplyEnabled
  missing from the redundant list; three carried nits).
- **R6 (app UX/supervisor): PASS** — gradle re-run fresh (1096 tests /
  0 failures / 1 skipped, 138 classes) plus detekt forced-execute;
  F2's four edits, F3 copy truthfulness (every string code-supported),
  the §0.c.4 disclosure verbatim, B5/F1 seams incl. the save&exit race
  and orphan self-heal adoption fencing, B7 scoped monotonicity — all
  verified with pins named. No new findings.
- **R7 (harness/tests): FINDINGS** — 1 MAJOR: the plan-mandated
  source-contract pin for AiFactory's A1 cloud-lane strategy grant was
  never authored (§0.b lane 3 "add the NEW host source-contract pin
  reading the pristine tree"; §11 "the T1 pins land in the same commit
  as their change"; §2 A1 "new pin") — the grant itself is correct
  today (read and verified) but a regression to the bare key compiles
  clean and passes the entire suite; the pin commit-bump half of the
  recipe IS done. +3 MINOR (rp_harness README's A8 invariants still
  describe the pre-f8f3334 shape; T0.1 compile gate and T0.2 vendored
  emitter fixtures never authored — the protective intent is
  substantially covered elsewhere; H2's "reconnect events fail smoke"
  implemented as record-and-continue). The f8f3334 fixes verified at
  three levels (payload emission order, checker, shipped test) with
  mutation tests; full suite re-run clean to the documented 8.
- **R8 (whole-plan conformance): PASS** — all 13 standing constraints
  mechanically verified (one command/line each, incl. prompt
  byte-freeze via empty git diff, edit lanes via the 153-op replay +
  4-commit submodule range, schema law via the 414-entry manifest);
  5/5 NEW PLAN-LOG spot-checks TRUE; all 12 logged interpretations
  SOUND. 2 MINOR (§0.a's keyless-row text superseded in the safe
  direction by interpretation 7; interpretation 4's "inside the
  AdmissionTransitionGate" phrase misplaces stopAccepted's file — the
  serialized-verb law holds).

**Fixes applied between Round 3 and Round 4** (one commit; every fix
cites its finding):

- [R1 MAJOR] The party block's outer condition widened to
  `(SRC_PARTY || SRC_RAID)` with the recording/digest legs nested
  under a party-only guard — the claim/selection/flood body is SHARED
  by both group channels (one clearing, one assignment; raid groups
  carry the same group ids), so an unaddressed raid line gets exactly
  one responder. Device lane byte-identical (the claim leg is
  CloudLaneOpen()-gated); recording stays party-only per the round-1
  adjudicated residue. Pins updated equal-or-stronger (split keys made
  MORE specific; counts re-enumerated) + a new raid-arm pin.
- [R7 MAJOR] tests/test_llm_a1_strategy_grant.py authored — the
  pristine-read AiFactory grant pin (exact conjunction expression, the
  == 2 arm, the module-convention include, single occurrence) plus the
  refusal-stamp pins.
- [R1 MINOR] A1's refusal-log deliverable landed:
  `PlayerbotLlmMemory::NoteGateRefusalOnce` (StateMutex,
  once-per-bot-per-session, process-local set) + the SayAction payload
  call firing only for a hard trigger the reply gate refused (never
  claim losers, never ambient non-triggers); pinned; the new
  fixed-field `BotLLM:` literal joined the A8 no-content allowlist AND
  that allowlist now scans PB_SAY_GATE_ANDROID too.
- [R1 MINOR] The rpgchat quota spend moved AFTER the cheap local
  guards (packets/futPackets/chatLine) — the PB_RPG_QUOTA anchor span
  extended (UPSTREAM byte-match verified; anchors still replay 153
  ops).
- [R5 MINOR] deviceLaneNeverCarriesCloudKeys enumerates all 9 family
  keys.
- [R7 MINOR] rp_harness README A8 invariants corrected (busy =
  dispatch+end only; cap DOES carry a begin).
- [R7 MINOR] Smoke now FAILS on reconnect/backoff transcript events
  (checked in `_report` so every exit path sees it) per H2; pinned in
  test_rp_harness.
- [R3/R4 MINORs] test_seasoning rename; "reworded" CHECK message; the
  wrong dead-row count dropped from the E2 docstring.

MINORs accepted as recorded residue (rationale): R2's recv-phase
timeout classification (durMs exposes stalls; the verified class-truth
chains stay untouched late in the gate); R3's BroadcastHelper "wanna"
+ PlayerbotSecurity comment (each requires the full submodule-bump +
manifest/PROVENANCE re-pin dance for a one-line polish; two prior
rounds accepted the Guild-scoping); the "533" figure inside the 0414
migration comment (the shipped entry is sha-pinned — §0.11 forbids
editing it; the machine pins are count-free) and in the plan text (the
spec is not edited mid-gate); R4's MySQL ODKU executed fixture (no
MariaDB host exists here — device-gated residue); R5's three carried
nits; R7's T0.1/T0.2 (Phase-0 rail debt whose protective intent is
covered by the lockfile pins, three-lane rebuild contract, anchor
replay and host batteries — authoring the NDK build.ninja edge walker
this late adds risk disproportionate to a MINOR); R8's two wording
nits.
