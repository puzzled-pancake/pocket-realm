---
paths:
  - "connected/**"
  - "android/**/connected/**"
  - "native/**/connected/**"
---

# Connected realm rules

- Do not begin connected production implementation until O22 is done.
- Use stable Rust and explicit bounded state machines. Network, persistence, and authority paths must not use unchecked `unwrap()`/`expect()`.
- One canonical durable history; no writable multi-master SQL and no automatic fork merge.
- One real-time authority per continent or instance; no consensus in the gameplay tick.
- Every durable command is authenticated, idempotent, version checked, epoch fenced, and bounded.
- Offline and connected IDs, keys, databases, backups, and characters remain separate.
- Session migration cannot use logout/relogin as a substitute. Preserve the local client session and fence the old authority.
