---
name: work-feature
description: Implement exactly one eligible Pocket Realm feature from FEATURES.json, verify it, update progress, and commit a clean result. Use when asked to continue the project or implement the next feature.
---

1. Run the startup sequence in `agent.md`.
2. Use `python3 scripts/next_feature.py --activate`; keep that feature active until done or genuinely blocked.
3. Read only the referenced plan section, decisions, matching rules, and relevant code/tests.
4. If the work crosses subsystems or persisted/protocol state, create a short implementation plan before editing.
5. Run the current subsystem smoke check.
6. Implement the smallest complete solution and add/adjust tests that prove the feature’s acceptance criteria.
7. Run focused checks, then the feature’s integration check. For UI/device work, capture the required screenshot or physical-device result.
8. Use a focused review subagent only for high-risk changes or a broad unfamiliar upstream patch.
9. Update only the selected feature’s `status`, `notes`, and `evidence` fields in `FEATURES.json`.
10. Replace `PROGRESS.md` with the current commit/checks/blockers/next action.
11. Commit a coherent passing result. If blocked, preserve the working baseline, record the exact missing input, mark it blocked, and stop that feature.
