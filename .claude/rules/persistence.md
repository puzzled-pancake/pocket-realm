---
paths:
  - "**/*sqlite*"
  - "**/storage/**"
  - "**/database/**"
  - "**/backup/**"
  - "**/migration/**"
---

# Persistence rules

- MariaDB is the offline production baseline. Keep its datadir app-private and exclusively owned by `DatabaseService`; use an app-private Unix socket where supported.
- Assume termination at every state transition. A graceful stop is an optimization, not the correctness boundary.
- Keep one item owner/location, nonnegative checked balances, one terminal claim/auction/mail outcome, and idempotent retry semantics.
- Migrations operate on a copy or protected generation and keep a verified rollback point.
- Never copy a live MariaDB datadir or label a raw file copy as a healthy backup. Use a database-consistent dump/snapshot, then verify schema, integrity, game invariants, restore, and disposable world-ready start.
- Durability/performance changes need physical power-loss evidence before weakening defaults.
