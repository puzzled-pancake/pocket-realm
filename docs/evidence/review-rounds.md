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

## Round 4

**Diffstat re-reviewed**: `git diff 6045eeb..f27b02e` — 117 files,
+18782/−323 (the round-3 fix batch plus docs; same submodule ranges).
All 8 reviewers dispatched fresh in one foreground message.

**Result: 7/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 1 MAJOR: on an ADDRESSED
  party/raid line the named bot dispatches via the addressed arm
  (claim bypassed) while every non-named bot's deterministic claim
  pick — highest tier among the OTHERS — also claims and dispatches:
  TWO generations per addressed line once `LLMPartyReplyEnabled`
  flips 1 (the plan's A3.5 pins "exactly ONE ... per player party
  line" and names the 2-responder case a failure; the plan's own A3.2
  sketch signature `SelectResponder(candidates, addressedGuid, seed)`
  carried the missing preference; verified by a compiled probe of the
  real header transcribing the payload control flow). All three
  round-3 fixes verified holding (raid claim shared/no nesting/device
  byte-identity; refusal stamp lazy-last; rpgchat order with the
  extended UPSTREAM byte-verified). +1 MINOR: a quoted name
  (`'Varleigh'`) never matches ContainsNameIgnoreCase (the closing
  apostrophe reads as a bad continuation; asymmetric with the left
  bound).
- **R2 (transport/security): PASS** — round-2 fixes mutation-verified
  again; anchors 153 ops (the extended RPG span byte-proven); the new
  refusal literal fixed-field; the allowlist extension holds; the
  unscanned BotLLM carriers enumerated harmlessly. 1 MINOR: run_suite
  never computes the plan-A8 "p50/p95 from durMs".
- **R3 (authored corpus/persona): PASS** — golden recompiled =
  de4bd8227a3ab0d1; corpus batteries 70+14 green; lint teeth
  repr-verified; E0/E1/E2/E3 + A5 all re-verified independently (own
  parsers). 1 MINOR: a dead `tags =` assignment in the renamed
  seasoning test.
- **R4 (schema/persistence): PASS** — full replay 414 inputs zero
  mismatches; texts.sql blob-identical; every column's writer/reader
  re-verified; PROVENANCE LF-normalization mechanically demonstrated
  (mutation flips, EOL does not); sqlite batteries 97 green. 1 MINOR:
  an orphaned pre-run `.build-arm64-v8a` staging tree (gitignored,
  unreferenced; deletion suggested at the next restage).
- **R5 (app conf/emission): PASS** — no emission drift from f27b02e
  (byte-check on all five emission sources); detekt baseline untouched
  and consistent; the 9/9 device-pin fix confirmed; key-parity gate
  mutation-tested live in 4 directions. 1 MINOR: the external-OFF pin
  sampled 2 of 8 economics keys and the debug lane had no
  family-absence pin (defense-in-depth class).
- **R6 (app UX/supervisor): PASS** — gradle forced-fresh (138 classes,
  1096/0/1) + detekt zero findings; F2/F3/B5/F1/B7 all re-verified
  with pins named. No new findings.
- **R7 (harness/tests): FINDINGS→PASS-shape content but listed
  findings were MINOR-only** — the round-3 A1 pin mutation-tested (8
  mutants, all killed); README/smoke fixes verified at every exit
  path; the f27b02e weakening audit confirmed equal-or-stronger (the
  old split key now matches 2 sites, the new key exactly 1 — the pins
  got MORE specific); full suite re-run clean; vacuous-pattern grep
  zero. 3 MINOR: the rpgchat quota-reorder landed unpinned (asymmetric
  with the street ladder); run_suite's exit-2 path discards the
  mid-suite record/transcript (diagnostic fidelity, device-gated);
  harness polish nits (bot_reply first-match strictness, a stale
  from_log_lines docstring claim, a non-RelayError parse escape).
- **R8 (whole-plan conformance): PASS** — all 13 constraints
  mechanically verified; 5/5 NEW spot-checks TRUE (the round-3
  entry's own claims); all 12 interpretations SOUND + the two new
  round-3 readings judged SOUND + the three residue rationales judged
  HONEST. No new findings.

**Fixes applied between Round 4 and Round 5** (one commit):

- [R1 MAJOR] `SelectResponder` gained the plan's own A3.2 sketch
  parameter: `addressedGuid` (defaulted 0 = unaddressed, pure
  ordering) with immediate resolution to the named candidate. The
  payload claim leg computes the addressed guid by iterating the
  group's members through the canonical `ContainsNameIgnoreCase`
  (every bystander computes the same value from the same msg + group,
  so the fan-out is stable), and the pick resolves to the named bot —
  the bystanders all lose the claim and the addressed bot's bypass is
  the ONE generation. Battery cases added (preference, out-of-set,
  default); a new payload pin
  (test_addressed_line_resolves_the_pick_to_the_named_bot_round4).
- [R1 MINOR] Quoted names address: the right boundary admits an
  apostrophe tail when the next char is 's' (possessive, unchanged) or
  non-alphanumeric/absent (closing quote); the left boundary admits a
  preceding apostrophe when the char before it is non-alnum/absent
  (opening quote) while "O'Varleigh"-style word-internal apostrophes
  stay blocked. Battery cases added both directions.
- [R7 MINOR] The rpgchat cheap-before-expensive order pinned
  (packets/futPackets/chatLine all before the CloudQuotaAdmits spend).
- [R2 MINOR] run_suite now computes nearest-rank p50/p95 over end-line
  durMs values into the report's `latencyMs` block (+ README + a
  pinned test with hand-checked ranks).
- [R5 MINOR] The external-OFF absence pin enumerates all 8 economics
  keys; the debug lane gains the full 9-key family-absence pin.
- [R3 MINOR] The seasoning test's dead assignment removed.

MINORs accepted as recorded residue (rationale): the orphaned
`.build-arm64-v8a` tree (pre-dates the run, gitignored, unreferenced —
delete at the next restage; not this gate's state to destroy);
run_suite's exit-2 transcript discard (exit 2 still fails the gate;
recovered hiccups already fail via relay-stability; the fix belongs to
the harness lane's next touch); the three harness polish nits
(first-match strictness is a recorded choice pending the realmd-auth
text transport; the docstring claim and the parse-escape are
device-gated-lane polish).

## Round 5

**Diffstat re-reviewed**: `git diff 6045eeb..aced79b` — 117 files,
+19093/−323 (the round-4 fix batch plus docs; same submodule ranges).
All 8 reviewers dispatched fresh in one foreground message.

**Result: 7/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 1 MAJOR: the dead-addressee
  hole. A line naming a DEAD bot computes that bot's guid on every
  bystander (the addressedGuid loop has no alive check) but dead bots
  are excluded from the candidates, so the round-4 pick fell through
  to ordering — an alive bystander claimed AND dispatched while the
  dead addressee's own addressed turn also dispatched (no death filter
  on the chain): TWO generations, the same exactly-one violation and
  dormancy profile as the round-3/4 MAJORs; reproduced by a compiled
  probe; the shipped battery even enshrined the fallthrough. All
  round-4 fixes otherwise verified with an extended probe (addressed
  → 1, unaddressed → 1, quoted → 1, fan-out stable, flood coalescing,
  device byte-identity). +1 MINOR: the word-boundary law is byte-wise
  (non-ASCII continuations and possessive run-ons like
  "Varleigh'ssword" still match) — pre-existing, log-only, recorded as
  residue.
- **R2 (transport/security): PASS** — the p50/p95 fix verified with
  the math independently re-derived against a reference across
  n=1..100; both round-2 fixes mutation-verified again; anchors 153
  ops; the CA bundle re-verified byte-identical to live curl.se
  upstream TODAY; aced79b's driver delta proven transport-neutral
  (zero BotLLM literal changes). 2 MINOR: two G3 "Adjacent, recorded"
  notes never shipped (no cleartext warning for http:// endpoints
  though the conf.dist parenthetical claimed one; no IPv4-required
  note for the AF_INET pinning).
- **R3 (authored corpus/persona): PASS** — golden de4bd8227a3ab0d1;
  batteries 70 green; the E3 wiring mutation-tested live (guard
  removal fails the pin, tree restored); target vector independently
  recomputed (1,090 exactly); zero register violations under the
  reviewer's own extended token list. No new findings.
- **R4 (schema/persistence): PASS** — full replay + live hashing
  again; the aced79b lockfile deltas proven to be exactly the gates
  header sha re-pin; 97 sqlite tests green. No new findings.
- **R5 (app conf/emission): PASS** — footprint claims confirmed
  (one test file since round-3 review; production emission sources
  byte-identical; baseline path verified at android/app/detekt-
  baseline.xml, empty diff since b3bef5f); key-parity mutation-tested
  live (5 mutants, all caught); the round-4 OFF/debug enumerations
  confirmed and executed under gradle (32/32). 2 MINOR: the ON-block
  gradle assert covers 8 of 9 (LLMPartyReplyEnabled = 0 not
  value-asserted; host-side parity leg covers it); _driver_keys
  matches comments in both directions (carried, currently matching
  zero keys).
- **R6 (app UX/supervisor): PASS** — gradle forced-fresh again
  (BUILD SUCCESSFUL 48/48 executed; 138 classes, 1096/0/1; detekt 0
  findings); all F2/F3/B5/F1/B7 items re-verified with pins. No new
  findings.
- **R7 (harness/tests): FINDINGS content MINOR-only** — every aced79b
  pin addition mutation-tested on scratch copies (rpgchat order pin:
  both reorder mutants killed; p50/p95: three math mutants killed;
  quoted-name battery: header mutation → 4 failures; addressed-
  resolution cases: resolution-removal → 2 failures; payload pins
  fail against the pre-fix driver — the exact regression they guard);
  vacuous-pattern grep zero; full suite re-run clean. 2 MINOR:
  check_a8_log's unreadable-file return omitted latencyMs;
  latencyMs aggregates all end-line classes (fast denials deflate it —
  an ok-class subset would be the honest SLA cross-check).
- **R8 (whole-plan conformance): PASS** — all 13 constraints
  mechanically green; no hard §11 inversions; 5/5 NEW spot-checks
  TRUE (25 across rounds 3-5); all 15 judgments SOUND (the 12
  standing interpretations + raid-claims/party-recording + the
  round-4 addressedGuid reading + the residue rationales re-confirmed
  with no material change). 1 MINOR: T0.5's banned-token CI grep never
  authored as a standing gate.

**Fixes applied between Round 5 and Round 6** (one commit):

- [R1 MAJOR] Dead-addressee stand-down: the payload loop now records
  only named BOT members (a named PLAYER addresses no bot — the line
  stays unaddressed for the ordering pick), and `SelectResponder`
  returns 0 when an addressed guid resolves to no candidate —
  bystanders STAND DOWN, never a second generation beside the
  addressee's own turn. The battery's fallthrough case flipped to the
  stand-down expectation with the round-5 rationale; the payload pin
  extended (bot-only loop + the pure stand-down return).
- [R7 MINOR] check_a8_log's unreadable-file return carries
  `latencyMs: {}`; the latency block gains the ok-class subset
  (`okP50`/`okP95`/`okN`) beside the aggregate (README + the pinned
  test extended with a denial-mixing case).
- [R5 MINOR] The ON-block gradle assert value-asserts
  `AiPlayerbot.LLMPartyReplyEnabled = 0` (the staged-0 ninth key).
- [R2 MINORs] The conf.dist G3 doc block corrected: the false "the
  app's normalizer warns" parenthetical replaced with the honest
  https://-preference note, and the IPv4-only (AF_INET) resolution
  requirement documented.
- [R8 MINOR] T0.5's banned-token grep authored as a standing gate:
  tests/test_llm_no_boilerplate.py scans the authored pools, the
  persona/composer surfaces, both emitter-spliced prompt headers, and
  the whole driver for the assistant-register boilerplate phrases
  (§0.1's diegetic carve-out honored by scope, stated in the
  docstring).

MINORs accepted as recorded residue (rationale): the byte-wise
word-boundary law (non-ASCII continuations, possessive run-ons —
pre-existing, ≤1 stray generation per typo'd mention, fixing it means
a UTF-8-aware matcher late in the gate); the ON-block-vs-parity
defense-in-depth asymmetry is now closed and the _driver_keys comment
tolerance stays carried (matches zero keys today); R1's two-named-line
note (one generation per named bot is the plan's per-bot addressed
semantics — adjudicated SOUND by R8 in round 5).

## Round 6

**Diffstat re-reviewed**: `git diff 6045eeb..bec78fd` — 118 files,
+19350/−323. All 8 reviewers dispatched fresh in one foreground
message.

**Result: 7/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 1 MAJOR: the claim-window
  race, the fourth layer of the exactly-one surface. The 5 s claim
  window expired before reachable chat-drain staggers (each bot drains
  chatReplies inside UpdateAIInternal, whose delay the engine sets to
  3-7 s on teleport/cast chains — the "fan-out resolves within one
  tick" comment was false), and the winner's rotation stamp
  deterministically armed the next tie-order bot to re-claim the same
  line after expiry: a second generation for one line (compiled
  demonstration against the real header; sibling instance: an
  addressee who leaves mid-fan-out flips late bystanders from
  stand-down to a fresh ordering pick). The deterministic matrix
  itself was verified CLOSED by R1's own 42-check probe (addressed/
  unaddressed/named-player/named-dead/other-group/two-named/fan-out
  stability/flood/device byte-identity). +2 MINOR: party/raid share
  the claim key when text repeats across one raid's two group channels
  (conservative — a missed reply, never a double); the
  chatRepliesMutex→StateMutex lock order was implicit (verified safe).
- **R2 (transport/security): PASS — zero findings.** Both round-5
  MINOR fixes verified landed exactly as logged (conf.dist G3 wording
  matches the AF_INET code reality; ok-class latency subset with
  hand-checked math); bec78fd proven transport-neutral; CA bundle
  still byte-identical to live upstream.
- **R3 (authored corpus/persona): PASS — zero new findings.** Golden
  unchanged; the new boilerplate lint ran with zero false positives on
  the authored register; E0-E3 + A5 all re-verified.
- **R4 (schema/persistence): PASS — zero findings** (one below-MINOR
  observation logged in the reviewer report only: hydrate-marks-key
  before the LLMHistoryPersist gate — boot-loaded conf, default 1,
  bounded; explicitly not a finding).
- **R5 (app conf/emission): PASS — zero findings.** All three round-5
  fix claims confirmed; 5/5 parity mutants caught; baseline untouched.
- **R6 (app UX/supervisor): PASS** — gradle forced-fresh (1096/0/1,
  detekt 0). 1 MINOR: plan §12's "'generations today' line in
  Diagnostics" mitigation never shipped and is recorded nowhere (the
  row's other mitigations all shipped; ServerStatusJson's ABI is
  pinned at 8 fields and must not be touched) → recorded as accepted
  residue below.
- **R7 (harness/tests): PASS** — all bec78fd pins mutation-verified
  (stand-down battery killed by header mutation; payload pins fail
  against the pre-fix driver). 2 MINOR: the boilerplate lint's surface
  list narrower than T0.5's wording; the contraction form
  ("i can't assist") slipped the token grep.
- **R8 (whole-plan conformance): PASS** — all 13 constraints green;
  5/5 NEW spot-checks TRUE (30 across rounds 3-6); all 16 judgments
  SOUND (the round-5 stand-down reading judged SOUND with the full
  three-case trace). 1 MINOR: §0.b lane-3's file list never named
  GuildManagementActions.cpp (plan-text enumeration lag; the edit
  followed the lane-3 procedure and is pinned; plan frozen — no
  action).

**Fixes applied between Round 6 and Round 7** (one commit):

- [R1 MAJOR] `PARTY_CLAIM_WINDOW_SECONDS = 30` (named constant at the
  claim map, with the stagger rationale: near/far teleport chains set
  UpdateAIInternal delays of 3-7 s, so the window must exceed every
  reachable drain stagger; still lazily pruned and bounded) AND the
  sibling closed structurally: `TryStandDownPartyLine` — an ADDRESSED
  line's bystanders stamp a winner-0 MARKER in the same claim map
  (same window, same prune, first-writer-wins), so a staggered late
  drain can never re-open the line after the addressee leaves
  mid-fan-out; wired on the addressed leg beside the claim. Pins
  updated (window constant + the marker helper + the payload wiring).
- [R1 MINOR] The chatRepliesMutex→StateMutex lock-order CONTRACT is
  now stated at StateMutex (leaf-mutex rule) — the implicit order is
  explicit.
- [R7 MINORs] The boilerplate lint widened to 10 surfaces (Memory/
  Bridge/Filters/TruthCore/ToolsCore prose added) and the contraction
  regex (`i can(?:'|no)?t …`).

MINORs accepted as recorded residue (rationale): the party/raid shared
claim key (conservative direction — fewer generations, matching the
flood law's channel-agnostic coalescing); §12's "generations today"
Diagnostics line (the row's binding mitigations shipped and are
pinned; the ServerStatusJson ABI is pinned at 8 fields — late-in-gate
UI additions are risk without a pinning device run; a natural
follow-up after qualification); §0.b's GuildManagementActions.cpp
enumeration lag (plan frozen mid-gate, the edit is sanctioned and
pinned); R4's below-MINOR hydrate-order observation.

## Round 7

**Diffstat re-reviewed**: `git diff 6045eeb..13b769f` — 118 files,
+19745/−323. All 8 reviewers dispatched fresh in one foreground
message.

**Result: 7/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 2 MAJOR, both compiled-probe
  demonstrations on the exactly-one TIMING layer (the round-7 prompt's
  specific mandate): (1) the claim-window race at its next layer — the
  drain stagger is ADDITIVE (IncreaseAIInternalUpdateDelay accumulates;
  a master's repeated `wait` adds up to 20 s per invocation, teleport/
  cast chains stack on top), so a bot's first drain can land past ANY
  fixed window: the pruned claim let the deferred bot re-claim beside
  the original winner (2 gens; controls at +1…+29 s stayed 1-gen, so
  the round-6 fix holds inside its envelope); (2) the stand-down marker
  only existed once a bystander drained while the addressee was still a
  member — an addressee KICKED before any bystander drained left late
  bystanders a fresh ordering pick beside the addressee's still-queued
  own turn (2 gens; the leave-after-stamp interleaving verified
  holding). +1 MINOR (a refused claim still consumed the speaker's 2 s
  flood stamp). Verified green: the deterministic matrix CLOSED by the
  extended probe (addressed/unaddressed/named-player/named-dead/
  other-group/two-named/fan-out stability/flood/device byte-identity);
  §0.13 at all 9 sites; A7 quota math incl. depth-before-quota; A1
  grant + refusal stamp; rpgchat order; the stated lock-order contract
  honored at every audited scope.
- **R2 (transport/security): PASS** — 2 MINOR: the retry-leg transport
  failure logs class=empty (the fall-through logEnd is hardcoded;
  genClass was computed from the first call — log-only, durMs exposes
  reality); plan B2.3's PlayerbotAIBase.cpp:35 "lesser" wording
  deliverable never shipped and recorded nowhere (outDebug line,
  invisible at staged LogFileLevel 1). Verified: anchors 153 ops with
  the arithmetic independently re-derived (129+2+22); 13b769f
  transport-neutral; both round-5 fix families verified (conf.dist G3
  wording matches the AF_INET code; ok-class latency math exact); rider
  1 redaction + SEC_MODERATOR gate mutation-killed; A8 class truth
  compiled 10-shape battery; CA bundle byte-identical to live curl.se
  upstream TODAY; rider 3 + rider 5; A9.
- **R3 (authored corpus/persona): PASS** — 1 MINOR (adjacent
  soft-refusal variants — "i could not assist"/"i won't help with" —
  not covered by the contraction regex). Golden recompiled =
  de4bd8227a3ab0d1; the 10-surface lint mutation-matrix 121/121 caught;
  zero false positives; E0 word/state-key laws, E1 vector (1,090
  exactly, independently recounted), E2's 30 UPDATEs all byte-resolving
  + texts.sql blob-identical across the submodule range, E3
  mutation-killed, A5 latch/cloud-scope/exclusivity verified.
- **R4 (schema/persistence): PASS** — 1 MINOR (last_greeted_at is
  write-only; the checklist parenthetical implied an authored T1 host
  pin for the ≥6 h reader leg that does not exist). Full manifest
  replay 414/414 zero mismatches; first-412 byte-identity; both fix
  commits' lockfile deltas EXACTLY the expected sha re-pins; 0414
  idempotence mechanically proven (zero chain collisions); PROVENANCE
  LF-hash recomputed and matching; sqlite battery 120 green.
- **R5 (app conf/emission): PASS** — 1 MINOR (the debug lane's G3 CA
  emission line unpinned — device+external lanes are pinned). Baseline
  diff vs b3bef5f EMPTY with zero hand suppressions; CloudLaneConf
  9/9; the parity gate mutation-tested live with 6/6 mutants killed;
  55/55 emission-pin tests green under gradle; both fix batches
  emission-source-verified.
- **R6 (app UX/supervisor): PASS — zero findings.** Gradle fresh
  (BUILD SUCCESSFUL; 138 classes, 1096/0/1; detekt 0 findings); F2
  four edits, F3 string-by-string code support, §0.c.4 verbatim, B5/F1
  seams, B7 monotonicity all re-verified with pins.
- **R7 (harness/tests): PASS** — 1 MINOR (the unreadable-log
  `latencyMs: {}` return shape landed unpinned — a scratch mutation
  deleting the key survived). Full suite re-run "8 failed, 621 passed,
  4 skipped" with EXACTLY the pre-existing set; 9/9 mutants killed on
  the round-6 pins (window constant + consumption, marker helper,
  driver wiring, lint surfaces); bec78fd pins mutation-verified; the
  T1/T2 matrix walked with no missing/tautological/weakened pin;
  weakening audit of all run deletions equal-or-stronger; vacuous
  grep zero.
- **R8 (whole-plan conformance): PASS — zero findings.** All 13
  constraints mechanically verified (incl. kill-switch coverage of the
  round-6 window/marker behavior and §0.b lane law on the driver
  extension); §11 no hard inversions; 5/5 NEW spot-checks TRUE (35
  across rounds 3–7; the round-6 fix-batch claims + an early-batch
  E2 hash claim); all 16 interpretations re-derived SOUND; the
  round-6 residue rationales judged HONEST (incl. the ServerStatusJson
  8-field ABI pin confirmed real).

**Fixes applied between Round 7 and Round 8** (one commit; every fix
cites its finding; probe-verified by a 10-check compiled probe
replaying both R1 interleavings):

- [R1 MAJOR#1 — the additive-deferral re-open] The line itself now
  expires with the window: NEW `PlayerbotLlmMemory::
  PartyClaimWindowElapsed(time_t)` (the one window constant, pure time
  compare) + a NEW drain-loop anchor pair (PB_AI_DRAIN_STALE_*) that
  drops a queued party/raid line older than the claim window BEFORE
  ChatReplyDo — gated on the full claim-surface armament (llmEnabled —
  the queue path's noDelay condition, without which lines carry the
  legacy 10-30 s stagger and must stay byte-identical; CloudLaneOpen;
  the default-0 party-reply key) and only real-player lines (the
  channel classify + speaker lookup run only for already-stale
  entries). On the armed surface m_time IS the fan-out instant and
  entries are unprocessable before it, so the age compare is exact: a
  drainer within the window always sees the live claim, a drainer past
  it is dropped — expiry now means a missed reply, never a second
  generation. The anchor count moves 153 → 154 (the new pair).
- [R1 MAJOR#2 — leave-before-first-drain] The ADDRESSEE's own receive
  stamps the stand-down marker at FAN-OUT time (the PB_AI_QUEUE_CALL
  payload, before the QueueChatResponse push — world thread, strictly
  before any drain can run), gated on the same armament, decided by
  the CANONICAL matcher (not the raw substring isMentioned — it must
  agree with the drain gate's addressedToBot exactly, or case-variant
  mentions re-open the hole). The marker exists for every later
  interleaving (kick/leave/death, any drain order); the drain-time
  bystander stamp stays as the idempotent backstop.
- [R1 MINOR] `PlayerbotLlmMemory::PartyFloodRefund(speakerGuid,
  stampedAt)` — a lost claim refunds the speaker's 2 s flood slot
  (CAS-shaped: only the attempt that stamped the slot lifts it, so a
  concurrent winner's stamp survives); the SayAction leg captures the
  stamp before the admit and refunds only on claim loss.
- [R3 MINOR] The boilerplate lint's soft-refusal row widened: could
  not / won't / would not / will not / (am|'m) unable to × assist /
  comply / help with.
- [R5 MINOR] `stagedTlsCaLineReachesTheDebugLaneToo` pins the debug
  lane's CA line (presence when staged, absence when null).
- [R7 MINOR] The unreadable-log return's `latencyMs: {}` shape pinned.
- [R4 MINOR] The checklist's greeting-upgrade parenthetical reworded
  to the true state (last_greeted_at is a write-only capture stamp;
  the ≥6 h reader ships with its future leg; no host pin yet).
- Pins updated equal-or-stronger: three NEW tests (fan-out stamp,
  drain TTL, refund) + the partyResponderClaimed count re-enumerated
  5→6 (the refund's read documented); the lint row strengthened.

MINORs accepted as recorded residue (rationale): R2's retry-leg
class=empty (log-only classification nit in the same genre as the
adjudicated recv-phase-stall residue — the verified class-truth chains
stay untouched late in the gate; durMs exposes reality); R2's B2.3
"lesser" wording (a one-line outDebug polish that requires the full
lane-3 submodule-bump + manifest/PROVENANCE re-pin dance — the same
rationale two prior rounds accepted for BroadcastHelper/Security
one-liners; the run's lane-3 batches are done).

## Round 8

**Diffstat re-reviewed**: `git diff 6045eeb..5ba555e` — 118 files,
+20253/−323. All 8 reviewers dispatched fresh in one foreground
message.

**Result: 7/8 PASS → ROUND FAILS.**

- **R1 (native cloud lane): FINDINGS** — 2 MAJOR, both compiled-probe
  demonstrations attacking the round-7 fix design (the round-8
  mandate): (1) the +30 s boundary second — the prune kills a
  claim/marker at `expiresAt <= now` while the staleness oracle used a
  strict `>`, so a drainer at exactly age 30 saw neither a live claim
  nor a dropped line and re-claimed beside the original winner (2 gens;
  the straddle shape — a fan-out crossing a second boundary — made the
  hole two seconds wide); (2) the claim key carried the DRAIN-time
  group id — a listener kicked and re-invited to another group inside
  the window computed a FRESH key, claimed beside the original winner,
  and delivered the second generation to a group that never heard the
  line (no timing coincidence needed). +1 MINOR (the flood refund's CAS
  can miss across a second boundary — the capture before the admit vs
  the admit's internal clock tick; conservative direction). Verified
  green: §0.13 at all 10 sites incl. both round-7 legs; device
  byte-identity of every added leg (each disarming key tested); the
  queue-path edge (QueueChatResponse has exactly one call site in both
  the applied tree and pristine); A7 quota math; A1; rpgchat order;
  threading/lock-order (PartyClaimWindowElapsed mutex-free; no reverse
  acquisition); the deterministic matrix re-run (26 checks).
- **R2 (transport/security): PASS — zero findings.** Anchors 154 ops
  with the arithmetic re-derived (130+2+22; the 23rd write_bytes grep
  hit is not a bot_root overlay); 5ba555e transport-NEUTRAL (zero new
  BotLLM literals — both new payloads enumerated clean); rider 1
  mutation-killed; A8 class truth compiled 13-shape battery; the CA
  bundle byte-identical to live curl.se upstream TODAY; riders 3/5;
  A9; the anchor mechanism mutation-tested on a scratch copy (drift
  raises); 28/28 lockfile pins recomputed clean.
- **R3 (authored corpus/persona): PASS** — 1 MINOR (the widened row's
  `'m` arm structurally dead under the "i "+space prefix — "i'm unable
  to assist" not caught; plus a curly-apostrophe hole noted). Golden
  recompiled = de4bd8227a3ab0d1; the lint mutation matrix 99/99 on the
  round-6 row; 15/18 on the widened row (both round-7-named misses
  caught); 1,434-line independent register scan zero violations; E1
  1,090 exactly; E2 30/30 keys resolving; E3 4/4 mutants killed; A5
  all laws verified; 82/82 batteries.
- **R4 (schema/persistence): PASS** — 1 MINOR (RecordBotLine dead code
  — plan C6's "delete or wire the dead RecordBotLine" sub-item
  unaddressed and unrecorded). Full replay 414/414 zero mismatches;
  both fix commits' lockfile deltas exactly the expected sha re-pins;
  the two new helpers verified schema-free; 0414 idempotence
  mechanically proven (zero chain collisions); PROVENANCE recomputed;
  seeder SEED OK zero churn; sqlite family 128 green; the round-7
  checklist reword verified landed.
- **R5 (app conf/emission): PASS — zero findings.** Baseline empty
  since b3bef5f with zero hand suppressions; 9/9 parity; the parity
  gate mutation-tested live 6/6 (+2 fresh-eye extras, the survivor
  being the adjudicated comment-tolerance regex); appended-block law
  verified in code, archaeology, and pins; 62 emission-pin tests green
  under gradle; the round-7 debug-lane TLS pin verified live.
- **R6 (app UX/supervisor): PASS — zero findings.** Gradle fresh
  (BUILD SUCCESSFUL; 138 classes, 1097/0/1 — the new round-7 test
  present and passing; detekt 0); F2/F3/§0.c.4/B5/F1/B7 all re-verified
  with pins; both fix commits' android footprints exactly as logged.
- **R7 (harness/tests): PASS** — 2 MINOR (the same dead `'m` arm —
  mutation-verified surviving; an adb hang's subprocess.
  TimeoutExpired escapes send()'s RelayError-only catch and the
  suite's exit-2 contract). Full suite re-run "8 failed, 624 passed,
  4 skipped" exact set; 29/30 mutants killed across the round-7 pin
  family (the sole survivor = the lint row finding); the count
  re-enumeration 5→6 verified; both new anchors byte-match pristine
  at exactly one occurrence; weakening audit equal-or-stronger;
  vacuous grep zero; C++ batteries compiled fresh (gates OK; golden
  unchanged; fuzz clean).
- **R8 (whole-plan conformance): PASS — zero findings.** All 13
  constraints mechanically green (incl. kill-switch coverage of the
  round-7 legs and the §0.b lane law on the new pair); §11 no hard
  inversions; 5/5 NEW spot-checks TRUE (40 across rounds 1–8); all 16
  interpretations + both round-7 design readings (TTL exactness,
  fan-out completeness) re-derived SOUND; the round-7 residue
  rationales HONEST.

**Fixes applied between Round 8 and Round 9** (one commit; every fix
cites its finding; probe-verified by a 9-check compiled probe
replaying both R1 attack shapes plus the round-7 regressions):

- [R1 MAJOR#1 — the boundary second] BOTH sides aligned: the staleness
  oracle is `>=` (a line at exactly window age drops; with the `<=`
  prune a strict `>` left one live-line/dead-claim second), AND the
  fan-out stamp moved AFTER the queue push (program order makes the
  marker's stamp clock-read ≥ the entry's m_time — a pre-push stamp
  could land one second earlier when the clock ticks between them,
  which re-opened the straddle shape). Invariant, now airtight even
  under straddles: every processed drainer (age ≤ window−1) sits
  strictly inside every claim's/marker's life (each stamps at ≥ m_time,
  so expires ≥ m_time+30 > m_time+window−1).
- [R1 MAJOR#2 — the group-switch key] The claim key is GROUP-FREE:
  `PartyClaimKey(speakerGuid, msgHash)` and both helpers dropped the
  groupId parameter (a speaker stands in at most one group, so the
  pair cannot collide across two live groups; a switched listener now
  computes the key that already owns the line and is refused). The one
  cross-group shape — the speaker moves groups and repeats identical
  text inside the window — now refuses the repeat: conservative (a
  missed reply, never a double), the same direction as the adjudicated
  party/raid shared-key residue. Responder SELECTION stays
  group-scoped (CollectPartyCandidates keeps the group); only line
  OWNERSHIP is group-free.
- [R1 MINOR] Recorded as residue: the refund CAS's second-boundary
  miss (capture-before-admit vs the admit's internal tick) is
  conservative-direction only (a missed refund = today's pre-fix
  behavior, never a wrong erase); the exact fix needs the admit's
  stamp returned/out-paramed — an API reshuffle late in the gate
  disproportionate to a log-noise-class nit (R1's own note).
- [R3/R7 MINOR] The lint rows restructured: the soft-refusal row keeps
  the "i "+space prefix forms and the "i'm" prefix gets its OWN row;
  the apostrophe classes admit the curly U+2019 (the corpus is
  ASCII-only — no false positive is possible). All variants verified
  caught (13 phrase probe) with zero false positives; batteries green.
- [R4 MINOR] RecordBotLine deleted (plan C6's "delete or wire": the
  function had zero callers at baseline and through the run — the
  def-in-overlay + header decl removed).
- [R7 MINOR] send() normalizes a hung adb: subprocess.TimeoutExpired
  joins the RelayError catch, is re-raised as RelayError("adb round
  trip timed out …"), and rides the same retry/backoff/reconnect
  transcript path (pinned: two reconnect events + the RelayError
  contract).
- Pins updated equal-or-stronger: the group-free key pinned END TO END
  (key signature + body group-free, both helper decls, both payload
  call shapes, the collector-keeps-group contrast); the oracle `>=`
  with the boundary rationale; the stamp-after-push straddle law
  (push_at < stamp_at); +1 harness pin (the hung-adb normalization);
  the lint rows strengthened.

MINORs accepted as recorded residue (rationale): R1's refund-CAS
second-boundary miss (above); the round-7 residues carry (retry-leg
class; B2.3 wording).

## Round 9

**Diffstat re-reviewed**: `git diff 6045eeb..75739f0` — 118 files,
+20568/−345. All 8 reviewers dispatched fresh in one foreground
message.

**Result: 6/8 PASS → ROUND FAILS.** (Fix batch owed — see the
continuation-run-6 handoff in PLAN-LOG.md; the "Fixes applied between
Round 9 and Round 10" section lands with that batch.)

- **R1 (native cloud lane): FINDINGS** — 1 MAJOR: the round-8 boundary
  invariant is per-ENTRY, not per-LINE. Each group member's receive
  handler runs sequentially on the world thread, so a fan-out can
  straddle a second boundary: the addressee's handler push+stamps the
  marker at T while a later member's handler pushes its copy at T+1.
  The later entry's own m_time=T+1 lets it PROCESS at T+30 (age 29,
  oracle `>=` false) exactly when the marker (expiresAt=T+30) is
  pruned — with the addressee/winner gone from the group by then
  (kick/leave/logout), the late drainer takes a fresh ordering pick
  and claims: 2 generations. Compiled probe with an exhaustive sweep:
  29 double-generation interleavings, ALL in the (straddle s=1,
  owner-gone, drain=stamp+30) class; zero doubles without the straddle
  (the round-8 fix holds inside its per-entry envelope). The claim leg
  has the same cross-entry shape (a winner's map-thread drain
  interleaving the world-thread fan-out). Fix directions named by R1:
  give the oracle a one-second margin (process only age ≤ window−2),
  or anchor the drop to the line's earliest m_time. Verified green:
  the group-free key semantics introduce NO new double hazard (probe
  battery: two live groups cannot share a speaker; repeats refused
  conservatively; marker+claim share one key; selection stays
  group-scoped); the deterministic matrix; device byte-identity of
  every leg (each disarming key tested); §0.13 at all 10 sites; A7
  quota math; A1; rpgchat order; threading/lock-order.
- **R2 (transport/security): PASS — zero findings.** Anchors 154 ops
  (arithmetic re-derived; the chained PB_SAY_PROMPT_V2 anchor verified
  staged inside its parent); 75739f0 transport-neutral (zero new
  BotLLM literals; a whole-driver census found zero unsanctioned
  literals anywhere); the hung-adb normalization verified live and
  pinned; rider 1 mutation-killed on scratch; class truth re-compiled
  (15 cloud + 3 device shapes, the legacy arm carries the transport
  class; the sentinel survives HygienePass byte-intact); the CA bundle
  byte-identical to live curl.se upstream TODAY; riders 3/5; A9.
- **R3 (authored corpus/persona): PASS** — 1 MINOR (lint tail-variant
  residue: "i can't fulfill/provide/complete", "i am not able to
  assist", spaced "i can not", and the curly "i'm sorry, but i …" tail
  all miss — defense-in-depth only; every axis eight rounds adjudicated
  is covered; widening is false-positive-safe per the ASCII-only
  corpus argument). Golden recompiled = de4bd8227a3ab0d1; 42/42
  phrase matrix caught incl. all round-8 misses, 0 false positives on
  15 register probes; a fresh C++ E0 probe walked 1,218 pool-served
  lines through the real LineIsValid — clean; state-key lanes
  enumerated disjoint; E1 1,090 exactly; E2 30/30 keys resolving
  (zero-match=0); E3 verified; A5 all laws; 124 tests green.
- **R4 (schema/persistence): PASS** — 1 MINOR (the stale PRE-fix build
  mirror inside the cmangos submodule still contains the deleted
  RecordBotLine — gitignored by the submodule itself, wiped and
  recreated from the overlay at every build, inert to every pin; the
  same class as the adjudicated orphaned .build-arm64-v8a residue).
  Full replay 414/414 zero mismatches; first-412 byte-identity; both
  fix commits' lockfile deltas exactly the two sha re-pins; 0414
  idempotence mechanically proven fresh (zero chain collisions, zero
  no-ops); RecordBotLine zero-callers confirmed and the deletion
  schema-neutral (AppendTurn retains 10+ live sites); PROVENANCE
  recomputed + normalization re-demonstrated; baseline delta across
  the run reconciled statement-by-statement; sqlite family 128 green.
- **R5 (app conf/emission): PASS — zero findings.** Baseline empty
  since b3bef5f, zero hand suppressions, all 1828 entries sorted with
  every named file present; detekt forced-fresh clean; CloudLaneConf
  9/9; parity gate mutation-tested live 5/5 (+restored control);
  appended-block law verified in code, archaeology, and pins
  (production emission byte-identical since bec78fd); 62/62 emission
  tests green under gradle.
- **R6 (app UX/supervisor): PASS — zero findings.** Gradle fresh twice
  (138 classes, 1097/0/1, detekt 0 findings); 75739f0 touched zero
  android files; F2/F3/§0.c.4/B5/F1/B7 all re-verified with pins.
- **R7 (harness/tests): FINDINGS** — 2 MAJOR: (1) the plan §10
  T2-NAMED external-block governor pins were never authored — the
  constants live at LlmRuntimePolicy.kt:293-300 (governorBotMax=16,
  governorGlobalMax=48, maxSimultaneousGenerations=4) but only the
  embedded trio (2/8/8) and the 25 are pinned; a silent regression of
  EXTERNAL_TIER's governor constants passes the whole suite + gradle
  (the same class the round-1 R5 MAJOR#2 convicted); (2) `:app:detekt`
  was never added to the android-unit CI job — plan §10 T2 names it
  explicitly; `.github/workflows/ci.yml` has no detekt and the run
  never touched `.github/` (detekt is configured but manual — once the
  gate closes nothing re-runs it on push). +2 MINOR (the hung-adb
  normalization covers send() only — the connect/pull legs still
  escape the exit-2 contract; the lint's "i'm sorry, but i" row stayed
  straight-apostrophe-only while round 8 admitted U+2019 elsewhere).
  Verified green: full suite "8 failed, 626 passed, 4 skipped" exact
  set; the round-8 pins mutation-tested 7/7 (+ the hung-adb revert
  killed; lint plants 11/11 incl. both mandated forms, with the
  i'm-row and _APOS individually load-bearing); the round-7
  equal-or-stronger audit; weakening audit; vacuous grep zero; C++
  batteries compiled fresh; the T1 matrix walked (the
  test_llm_memory/persona filename note re-accepted).
- **R8 (whole-plan conformance): PASS** — 1 MINOR (the header's
  round-7 oracle summary comment at PlayerbotLlmMemory.h:254 still
  says the strict-`>` form — comment-only; the .cpp body comment and
  the pin carry the `>=` contract). All 13 constraints mechanically
  green; §11 no hard inversions; 5/5 NEW spot-checks TRUE (45 across
  rounds 1–9); all 16 interpretations plus both round-8 design
  readings re-derived SOUND (note honestly: R8's boundary-invariant
  derivation held per-ENTRY — the cross-entry skew R1 demonstrated was
  outside its premise; R1's empirical sweep governs); the round-8
  residue rationale judged HONEST.

MINORs pending triage in the round-10 fix batch: R3's lint tails
(cheap widening, false-positive-safe); R4's stale build mirror (record
as residue — regenerated at next build); R7's connect/pull legs
(1-line or residue — R7 itself graded it non-round-failing); R8's
header comment (1-line fix).
