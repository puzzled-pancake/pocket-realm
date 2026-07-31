---
name: milestone-review
description: Review a completed Pocket Realm milestone against PLAN.md, run its integration checks, and report only correctness, security, recovery, performance, or user-experience gaps.
context: fork
---

1. Identify the milestone and completed feature IDs.
2. Read only that milestone’s plan, decisions, changed files, and test results.
3. Run the milestone integration flow from a clean build/state.
4. For high-risk state, inject at least one crash/retry/adversarial case.
5. Check that performance optimization did not reduce persistence guarantees, hide fallback, or empty the visible world.
6. Report release-blocking gaps first. Do not request abstractions or tests for impossible scenarios solely to create findings.
7. Provide exact file/command evidence and a concise pass/fail recommendation.
