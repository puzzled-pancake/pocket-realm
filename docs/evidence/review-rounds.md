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

_(placeholder — the §15 review runner fills this in: per-reviewer
verdicts R1-R8 with severities and file:line evidence, the diffstat
re-reviewed, and any fixes applied before Round 2)_
