# Compact handoff sanity check

> Historical audit: this predates adoption of `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx` as the canonical offline reference. The compact-harness conclusions still apply; current features use report section pointers and the report's MariaDB/multi-process/x86-first gates.

## Result

The previous handoff was structurally sound but unsuitable as an always-available Claude Code prompt. It mixed product requirements, implementation detail, process enforcement, evidence schemas, threat catalogs, and hundreds of micro-packages into one large control system. That increased startup/context cost, repeated the same decisions, and made ordinary coding work carry release-audit overhead.

The replacement keeps the product scope while changing how Claude receives it:

- one small always-loaded instruction file;
- one concise roadmap read by section;
- 36 end-to-end features instead of 721 micro-packages;
- path-scoped rules for Android, native code, persistence, runtime translation, and connected code;
- three on-demand skills;
- one narrow deterministic safety hook;
- Git plus one replaceable progress note instead of an append-only evidence bureaucracy.

## Measured reduction

| Metric | Previous handoff | Compact handoff | Reduction |
|---|---:|---:|---:|
| Files | 110 | 20 | 81.8% |
| Uncompressed bytes | 6,770,225 | 83,677 | 98.76% |
| Approximate words | 739,063 | 10,482 | 98.58% |
| Work units | 721 | 36 | 95.0% |
| Always-loaded project instruction | Large imported control package | 63 lines / 754 words | Under Claude Code’s 200-line target |

The canonical report, `PLAN.md`, `DECISIONS.md`, and `FEATURES.json` are not imported at startup. Claude reads one feature record, its named report sections, and one plan section when needed.

## Claude Code design audit

1. **Correct memory entry point:** Claude Code loads `CLAUDE.md`; the one-line file imports the requested `agent.md`.
2. **Import cost is controlled:** imports still consume context, so only the 63-line `agent.md` is imported. The roadmap is referenced, not imported.
3. **Progressive disclosure:** `.claude/rules/` applies domain rules only to matching paths; skills load multi-step procedures only when invoked.
4. **Bounded autonomy:** one session works on one dependency-eligible feature with explicit acceptance criteria.
5. **State continuity:** `PROGRESS.md` contains only current state; Git retains history. The selector resumes an already-active feature before choosing a new one.
6. **Verification without ceremony:** focused checks happen per feature, integration checks at milestones, and physical-device evidence at release. Ordinary changes do not require multiple reviewer agents.
7. **Deterministic guard only where useful:** the sole hook blocks a small set of irreversible repository/database commands. It does not run broad tests after every tool call.
8. **Goal condition is measurable:** the recommended `/goal` ends after one feature is implemented, checked, recorded, and committed.

## Product and architecture audit

### Offline foundation

- Proprietary client/game assets are never part of the repository or release.
- The server, MariaDB, and bots are native per Android ABI; direct x86 is the development gate and only the Windows client needs CPU translation on ARM.
- The Android app assumes process death can occur at any instruction; Save & Exit is not the sole correctness boundary.
- Mutable realm data stays on internal storage by default and uses dirty-state recovery, verified backups, and rollback generations.
- Direct x86 Wine is proven first. Box64/64-bit Wine WoW64 is the first ARM candidate; FEX is outside the release critical path until a separate laboratory feature qualifies it.
- Automatic mode never selects an unqualified runtime tuple or exposes an unimplemented laboratory backend as supported.
- Controller input uses Android game-controller APIs plus a bundled Wine input bridge; it does not assume root, `/dev/uinput`, or Accessibility services.
- Bot optimization protects visible, resident, grouped, combat, and instance bots before reducing distant or invisible work.

### Connected expansion

- Every connected feature transitively depends on offline acceptance feature `O22`.
- Offline and connected realm data cannot merge after connected genesis.
- P2P removes a permanently designated machine, not the need for one canonical ordering of shared economic state.
- Combat remains single-authority and does not wait for consensus.
- When all peers are offline, the realm is dormant; no device means no simulation or economy ticks.
- Ordinary cities and zones stay within whole-continent authority units. Instance/continent transitions may use the normal loading screen.
- A local Session Anchor preserves the legacy client connection during backend handoff; loss of the local client process is honestly treated as reconnect rather than called seamless.

## Implementation-language audit

| Area | Choice | Reason |
|---|---|---|
| Android UI/lifecycle | Kotlin + Compose | Native Android lifecycle, controller, storage, service, and UI integration |
| Existing server/runtime | C++20 | CMaNGOS and Playerbots are C++; a lifecycle facade is smaller than a rewrite |
| Connected trust/network state | Stable Rust after `O22` | New untrusted protocol/state-machine surface benefits from memory safety |
| Cross-language boundary | Versioned C ABI | Keeps JNI/C++/Rust ownership and failure behavior explicit |
| Build/import/validation tooling | Python 3.12+ | Portable deterministic tooling without shipping Python in gameplay runtime |

This split avoids rewriting CMaNGOS, avoids forcing Rust into the offline critical path, and prevents Kotlin/JNI from depending on C++ or Rust implementation types.

## Dependency and consistency validation

The final validation checks confirm:

- 36 unique features: 22 offline and 14 connected;
- no unknown dependencies;
- no dependency cycles;
- no more than one active feature;
- every connected feature depends transitively on `O22`;
- every feature references an existing plan section;
- every feature has acceptance criteria;
- `CLAUDE.md` contains only `@agent.md`;
- `agent.md` remains below 200 lines;
- settings, feature JSON, rule frontmatter, and skill frontmatter parse structurally;
- the feature selector chooses and resumes `O01` correctly in a clean copy;
- the hook permits ordinary build commands and blocks the tested destructive commands;
- both Python scripts compile;
- no stale “version/series” naming or old 721-package claims remain.

## Remaining uncertainties that must stay evidence-based

These are implementation risks, not reasons to enlarge the prompt:

1. Actual RP6 firmware behavior: 32-bit userspace availability, Vulkan/driver behavior, 16 KiB page-size compatibility, controller identifiers, thermal policy, and background-process behavior.
2. Native MariaDB packaging, migrations, recovery, and CMaNGOS supervision may be larger than expected.
3. Direct x86 Wine and the later Box64/Wine WoW64 tuple must be qualified end to end; component documentation alone cannot establish client support.
4. Runtime, addon, and optional visual-pack licenses must be reviewed before redistribution.
5. Hardware attestation limits admission but cannot prove an authoritative peer simulated honestly.
6. Seamless backend migration requires typed state capture, pre-copy, fencing, and visibility continuity; it cannot be replaced by logout/relogin.

The plan deliberately records these as measured feature outcomes instead of pretending they are solved or adding speculative checks to every coding task.
