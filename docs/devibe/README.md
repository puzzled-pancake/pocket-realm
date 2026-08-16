# De-vibe-coding effort

Status: **audit complete, execution not started** (2026-08-16).

- `DEVIBE_PLAN.md` — the remediation plan (v2, verification-passed). One initial audit
  pass plus an eight-agent verification pass; every finding carries file:line references,
  and the round-2 corrections are listed in its Appendix C.
- Start with Phase 0 (baseline, worktree reconciliation, `git bundle` backup) before
  touching anything. The plan's own rules apply here too: no screenshots or binaries
  in this folder — evidence goes to `tests/**/evidence/` or stays in local `tmp/`.
