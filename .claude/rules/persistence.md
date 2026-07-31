---
paths:
  - "**/*sqlite*"
  - "**/storage/**"
  - "**/database/**"
  - "**/backup/**"
  - "**/migration/**"
---

# Persistence rules

- Assume termination at every state transition. A graceful stop is an optimization, not the correctness boundary.
- Keep one item owner/location, nonnegative checked balances, one terminal claim/auction/mail outcome, and idempotent retry semantics.
- Migrations operate on a copy or protected generation and keep a verified rollback point.
- Never label a raw file copy as a healthy backup. Verify schema, integrity, game invariants, restore, and disposable start.
- Durability/performance changes need physical power-loss evidence before weakening defaults.
