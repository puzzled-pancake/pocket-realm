# Pocket Realm compact Claude Code handoff

This package replaces the previous oversized handoff. Copy its contents into the root of a clean implementation repository, or remove the old generated handoff/control files first. Do not load both instruction systems: Claude concatenates applicable project memory files.

Claude Code reads `CLAUDE.md`, not `agent.md` directly. `CLAUDE.md` imports the requested `agent.md`, so there is one concise instruction source.

## First run

```bash
claude
/context
```

Confirm `CLAUDE.md` is listed under memory files. Then start one autonomous feature with:

```text
/goal Follow agent.md. If no feature is active, run python3 scripts/next_feature.py --activate. Fully implement exactly that feature and pass its stated checks, or record a precise external blocker. Update FEATURES.json and PROGRESS.md, commit the coherent result when applicable, and stop after that one feature.
```

Repeat for the next feature. `/goal` requires a current Claude Code release; use `/work-feature` for the same bounded procedure when `/goal` is unavailable or when you prefer manual turn control.

## File map

- `agent.md`: short always-on instructions.
- `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx`: canonical offline engineering reference; the adjacent PDF is the fixed-layout reading copy.
- `PLAN.md`: repository execution overlay and connected-realm extension; read by section.
- `DECISIONS.md`: concise adopted decisions and evidence-backed deltas from the report.
- `FEATURES.json`: dependency-ordered features with report section pointers.
- `PROGRESS.md`: current state for the next session.
- `.claude/rules/`: path-specific instructions loaded only for relevant files.
- `.claude/skills/`: on-demand workflows for a feature, runtime qualification, and milestone review.
- `.claude/hooks/`: one narrow guard against destructive repository commands.
- `PROMPTING_REDESIGN.md`: why this structure fits current Claude Code guidance.
- `SANITY_CHECK.md`: first-principles architecture and package validation review.

The repository now contains verified O01-O04 bootstrap, Android-shell, native-build, and lifecycle experiments. Production topology, Wine client, MariaDB realm, integration, and performance claims remain provisional until their report gates and physical-device checks pass.
