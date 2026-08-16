# Why this handoff is smaller and more reliable in Claude Code

> Historical harness-design note: the size figures below predate the canonical 80-page offline engineering report. Current sessions use that report through feature-level section pointers rather than loading it in full.

The previous handoff put millions of characters, hundreds of work packages, and many repeated constraints in front of the agent. That conflicts with current Claude Code guidance: context is the limiting resource, long always-loaded instruction files reduce adherence, and imports do not reduce startup token usage.

## Applied Claude Code practices

1. **Use the file Claude actually loads.** Claude Code reads `CLAUDE.md`, not `AGENTS.md` or `agent.md` automatically. This package keeps the requested `agent.md` and imports it from a one-line `CLAUDE.md`.
2. **Keep always-on instructions concise.** Anthropic recommends specific, well-structured project instructions and targets fewer than 200 lines per `CLAUDE.md`. `agent.md` contains only rules needed in nearly every session.
3. **Use progressive disclosure.** Offline technical authority lives in `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx`; `FEATURES.json` points to only the relevant sections, while `PLAN.md` and `DECISIONS.md` provide the repository overlay. Path-specific instructions live in `.claude/rules/`; multi-step procedures live in `.claude/skills/`.
4. **Track one feature at a time.** Anthropic’s long-running-agent work found that agents fail by attempting too much at once and by declaring the project done early. `FEATURES.json` contains 36 end-to-end features, and the agent selects one eligible feature per run.
5. **Use Git plus a short current-state note.** `PROGRESS.md` stores only the active handoff; Git stores history. This avoids an ever-growing progress transcript.
6. **Give every feature a verifiable end state.** Claude performs better when it can run a test/build/screenshot/device scenario and iterate. The feature list contains acceptance outcomes, not paperwork about evidence schemas.
7. **Plan only when it pays.** Plan mode is required for cross-subsystem, persisted-data, protocol, or uncertain changes. Small clear changes proceed directly, avoiding unnecessary planning overhead.
8. **Use subagents to protect main context.** Broad upstream exploration and high-risk review can run in separate contexts. Ordinary work does not require a committee of agents.
9. **Use hooks only for deterministic policy.** One PreToolUse hook blocks destructive repository commands. Testing remains feature-driven rather than running an expensive global hook after every edit.
10. **Use `/goal` for bounded autonomous work.** The recommended goal is one eligible feature with explicit passing checks, updated state, and a clean Git tree. It avoids an unbounded “build the whole product” run.
11. **Compact deliberately.** `agent.md` tells Claude what facts must survive compaction. Unrelated work should begin after `/clear`.
12. **Prefer positive, testable instructions.** The handoff tells the agent what outcome and check to produce. It uses prohibitions only for dangerous shortcuts that would invalidate the product.

## Context budget

The former package contained 110 files, about 6.77 MB, roughly 739,000 words, and a 4.5 MB work-package file. This replacement contains 20 files, about 83 KB, and roughly 10,400 words: approximately 98.8% fewer bytes and 98.6% fewer words. The always-loaded instruction source is 63 lines; the full plan stays out of startup context.

## Official sources reviewed (31 July 2026)

- Claude Code memory and `CLAUDE.md`: https://code.claude.com/docs/en/memory
- Claude Code best practices: https://code.claude.com/docs/en/best-practices
- Claude Code skills: https://code.claude.com/docs/en/skills
- Claude Code hooks: https://code.claude.com/docs/en/hooks-guide
- Claude Code subagents: https://code.claude.com/docs/en/sub-agents
- Claude Code goals: https://code.claude.com/docs/en/goal
- Anthropic long-running-agent harness research: https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- Anthropic context engineering: https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- Anthropic prompting best practices: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices
