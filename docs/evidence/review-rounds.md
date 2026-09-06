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
