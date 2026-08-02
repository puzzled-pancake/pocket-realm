# ADR-015: O08 x86_64 MariaDB ownership and recovery boundary

Status: accepted and qualified (2026-08-03)

## Decision

Pocket Realm runs the pinned MariaDB 11.5.2 x86_64 Linux/glibc build in the
non-exported Android `:database` process. `DatabaseService` exclusively owns
`noBackupFilesDir/database/data`; no other component copies or edits the live
datadir. Executable ELFs remain immutable APK native libraries and use O06's
qualified PRoot/glibc syscall adapter. This is native x86_64 CPU execution, not
CPU translation.

The Binder contract exposes only fixed lifecycle and acceptance verbs. It does
not accept paths, executables, environment, SQL, or credentials. MariaDB binds
only an app-private Unix socket. `skip-networking=1` and `skip-name-resolve=1`
are specified both in the generated config and the fixed daemon argv.

Initialization uses MariaDB's own ordered system-table SQL with
`@auth_root_socket=NULL`, matching `mysql_install_db`'s
`--auth-root-authentication-method=normal` path. It then generates independent
random `pocket_admin` and `pocket_core` secrets in private storage. The core
user receives per-schema DML/DDL grants needed by CMaNGOS, but no global account
administration; O08 proves an authenticated query and a denied `CREATE USER`.

## Migration and persistence invariants

- `schemas/database-migrations.json` is the reviewable ordered ledger input.
  Every SQL byte stream is tied to the pinned CMaNGOS, Classic-DB, or Playerbots
  commit and has a SHA-256. Android assets are deterministic gzip encodings and
  are reverified after decompression immediately before execution.
- The in-database ledger records `PENDING` before execution and `APPLIED` or
  `FAILED` afterward, including source/target commits, SQL hash, snapshot ID,
  app build ID, timestamps, and result digest.
- Migrations require a clean stopped generation. A hash-verified stopped-state
  snapshot is created first. Any failure kills the recorded database tree,
  quarantines the failed datadir, and restores the snapshot. A live datadir is
  never copied.
- A clean marker is removed before each daemon start and written only after an
  authenticated `SHUTDOWN`, clean process exit, and socket removal.
- Dirty-kill qualification requires a missing clean marker, MariaDB recovery
  output, a successful authenticated query, and a subsequent clean stop.
- Storage-full qualification is a deterministic zero-byte fault injection at
  the preflight boundary and proves refusal before any datadir write.

## Failure classification

The bounded service responses use the report's categories: `DB-INIT`,
`DB-LINK`, `DB-SOCKET`, `DB-REVISION`, `DB-RECOVERY`, `DB-FULL`, and
`DB-SNAPSHOT`. Secrets are never returned or logged.

## Consequences

O09 can depend on a structured MariaDB-ready event and the private socket, but
cannot own or manipulate database files. A future ARM64 provider may replace
the ELF package behind the same service contract and ledger; it must repeat the
same init/auth/migration/recovery qualification.

## Qualification

The fixed API-35 x86_64 4 KB lane passed
`DatabaseLifecycleTest.o08FullAcceptance` in 139.399 seconds. MariaDB 11.5.2
initialized from the pinned bootstrap, proved authenticated least-privilege
access and denied account administration, applied all 399 ordered migrations,
rejected a deliberate revision mismatch, and clean-stopped. The same run
proved preflight storage-full refusal, stopped-state snapshot/restore, dirty
kill, observed recovery output, post-recovery query, and a final clean stop
with no socket remaining. The authoritative structured result is
`tests/avd/AVD-Modern-x86_64-v1/evidence/databaseRuntime-o08-acceptance-20260802-1915Z.PASS.json`.
