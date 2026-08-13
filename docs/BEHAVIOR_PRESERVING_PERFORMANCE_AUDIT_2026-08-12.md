# Behavior-Preserving Performance Audit

Date: 2026-08-12  
Baseline reviewed: `22410a5` plus the current working tree  
Scope: whole-project, read-only performance review. No product behavior was changed by the audit.

Evidence labels used below:

- **Measured**: supported by recorded project or device evidence.
- **Source-proven**: follows deterministically from the current code.
- **Inferred**: plausible impact that still requires a controlled benchmark.

## Executive summary

The highest-confidence performance problem is not a MariaDB tuning variable. The native process runner can busy-spin after MariaDB closes its output pipes. Historical RP6 evidence showing the database service near 100% CPU while `mariadbd` itself was nearly idle is a strong causal match. Fix and measure that before changing database durability or caches.

The next largest startup costs are redundant integrity work: the 2.28 GB prepared world is fully hashed twice, and warm x86 client starts re-extract and re-hash hundreds of megabytes. Both can be reduced while keeping one authoritative content verification and atomic repair.

## Ranked findings

| Rank | Finding | Evidence | Expected impact | Confidence | Risk |
|---:|---|---|---|---|---|
| 1 | Native MariaDB supervisor can busy-spin after capture pipes close | Measured + source-proven causal match | Very high CPU/battery | High | Low–medium |
| 2 | Prepared world data is fully SHA-256 verified twice per production start | Source-proven; 4.56 GB read total | High startup I/O/latency | High | Medium |
| 3 | Warm x86 client preparation re-extracts about 600 MiB and hashes caches twice | Source-proven | High x86 startup/storage I/O | High | Medium |
| 4 | OpenGL fallback copies every frame GPU → CPU → GPU | Source-proven; bandwidth inferred | Very high in that renderer | High | High |
| 5 | MariaDB provider assets are recopied and native libraries rehashed/relinked on every stopped start | Source-proven | Medium startup I/O | High | Low–medium |
| 6 | UI and supervisor use several fixed-rate Binder/filesystem polling loops | Source-proven | Medium idle CPU/wakeups | High | Medium |
| 7 | Playerbot manager copies the complete bot list and submits a DB ping every 1.25–2 seconds | Source-proven | Medium at high populations | High | Low–medium |
| 8 | ARM MariaDB retains generic caches that should be device-profiled | Official docs + source | Medium memory/I/O potential | Medium | Medium |
| 9 | Debug logging and unbounded diagnostic reads add avoidable I/O/allocation pressure | Source-proven | Low–medium | High | Low |
| 10 | Audio-off still starts a blocked connector; release shrinking is disabled | Source-proven | Low/conditional | High | Low–medium |

## 1. Native database runner busy-spin

Historical RP6 evidence records `com.pocketrealm:database` at approximately 100% CPU, with roughly 80% kernel time, while `mariadbd` itself used about 0.3%. The current source explains that signature:

- `DatabaseEngine.startDaemon()` launches MariaDB without a runtime timeout and keeps daemon ownership.
- Both runner loops in `native/wine-spike/src/glibc_program_run.c` poll only while at least one stdout/stderr file descriptor remains.
- Once MariaDB closes both capture pipes, `nfds == 0`; the loop repeatedly calls non-blocking `waitpid(..., WNOHANG)` with no wait or sleep.

Bounded fix:

- Preserve output-tail capture, finite timeout, process-tree cancellation, descendant drain, daemon ownership, exit status, and `EINTR` behavior.
- When no capture descriptors remain and no timeout is configured, use blocking `waitpid()` with `EINTR` handling.
- For finite timeouts, sleep or poll for a bounded slice of the remaining duration.
- Apply the same behavior to glibc and Bionic paths.

Acceptance: a ten-minute idle database + world run should keep the database-service CPU below 1–2%, or reduce it by at least 90%, without changing startup, recovery, cancellation, or clean-stop behavior.

## 2. Duplicate prepared-world verification

`PreparedDataStore.requireActive()` hashes all 9,204 files in the authoritative 2,280,526,960-byte generation. Production currently invokes it in supervisor preflight and again while the world process builds its runtime configuration. A normal launch therefore reads approximately 4.56 GB before gameplay.

Bounded fix:

- Keep exactly one complete hash verification immediately before native world start.
- Supervisor preflight should validate the pointer/manifest envelope and identity only.
- A corrupt file, corrupt manifest, unsafe path, or pointer swap before world start must still fail closed.

Acceptance: process I/O shows one generation read per start, and native world startup is never entered with invalid prepared data.

## 3. Warm x86 cache extraction and duplicate hashing

Every x86 preparation recreates temporary extraction trees for the Wine PE and guest PE assets even when the canonical cache is already valid. Native materialization hashes existing destinations, then Kotlin invokes a second full verifier.

Bounded fix:

- A valid warm cache gets one integrity pass, link audit, and zero asset extraction or canonical-cache writes.
- Extract only missing or corrupt entries, publish repairs atomically, then verify repaired bytes.
- Never trust a build marker without validating content.

Acceptance: warm-start bytes read/written and elapsed time fall materially; corrupt files, wrong links, interrupted repair, runtime changes, and APK replacement still recover or fail closed.

## 4. OpenGL presentation bandwidth

The compatibility renderer reads RGBA pixels into CPU memory and uploads them again on the Android renderer context. At 1920×1080×30 FPS, the two transfers alone are about 498 MB/s. This route exists to survive Android surface/context recreation and is not safe to replace casually. OpenGL work is intentionally out of the current implementation scope.

Any future GPU-only experiment must provide a persistent share-group owner, fences, generation and size metadata, context-loss recovery, and the current CPU route as a fallback.

## 5. MariaDB provider staging

Each stopped start recursively recopies the provider asset tree, rehashes native libraries/plugins, and recreates symlinks even when they are correct.

Use a manifest-addressed, atomically published provider generation. A warm start may retain a fully verified generation and correct links, but APK/native-provider replacement and mutable-file corruption must still be detected.

## 6. Fixed-rate polling

The current app includes 200–250 ms client/supervisor status polling, one-second realm/import/log polling, repeated Binder calls, and repeated SQLite journal construction during import polling.

Move long-running state to immutable, sequence-numbered Binder callbacks with death recipients. Retain a slow reconciliation poll for missed events. Stop import polling after terminal state and refresh on resume or user action.

Acceptance: at least 80% fewer Binder transactions and scheduled wakeups over 30 minutes, visible transitions within 250 ms, and component failure detection within two seconds.

## 7. Playerbot periodic work

`RandomPlayerbotMgr::GetBots()` returns the full cached bot list by value and the manager scans it every 1.25–2 seconds. The same interval unconditionally submits a `SELECT 1` health query.

Return a stable const view or reference and retain the existing bounded/fair iteration. Coalesce the database probe only while its last result is fresh; stale health must never authorize a login burst.

## 8. ARM MariaDB tuning candidates

The existing ARM policy is already conservative: Unix socket only, DNS disabled, 24 connections, performance schema disabled, a 128 MiB InnoDB pool, and durable `innodb-flush-log-at-trx-commit=1`.

Measure candidates independently:

- `innodb-flush-neighbors=0` for Android flash storage.
- `host-cache-size=0` because TCP and hostname resolution are disabled.
- A smaller thread cache only if `Threads_created / Connections` proves low churn.
- A smaller Aria page cache only after measuring Aria page-cache read/request ratios.

Do not relax redo/fsync, doublewrite, backup ordering, or recovery guarantees. A candidate ships only if it has a repeatable benefit without latency, memory, recovery, or durability regression.

## 9. Logging and bounded diagnostics

- Normal non-bot world mode currently requests debug-level console and file logging. Release play should use bounded basic logging with explicit opt-in diagnostics and rotation.
- Provider SHA calculation reads an entire file into memory instead of streaming.
- Database error-tail handling reads the entire remainder before truncating it. Seek and read only a bounded tail.

## 10. Lower-priority observations

- Audio-off should not start a connector whose only job is to wait for audio.
- Release shrinking is disabled, but native payloads and assets dominate size; R8/LTO/PGO are not first-line wins and have JNI/reflection risk.
- The Settings FPS value is not wired to the fixed 30 FPS display profiles and must not be used as a benchmark variable until that contract is real.

## Existing optimizations to preserve

- Bot status already consumes the admission monitor's ten-second cached sample.
- The current bot work stages population from 25, uses bounded activation/login batches, and exposes lower everyday presets.
- XServer rendering is already on-demand.
- Audio transport uses shared memory, blocking output, and epoll.
- Build caching, single-ABI packaging, staged JNI synchronization, DXVK state caches, and native release optimization are already present.

## Recommended implementation order

1. Fix and device-measure the native runner busy-spin.
2. Remove the duplicate prepared-world verification while retaining the world-side authoritative check.
3. Introduce a verified warm MariaDB provider generation.
4. Remove playerbot list copies and coalesce only fresh DB health probes.
5. A/B the three conservative ARM MariaDB candidates separately.
6. Convert high-frequency UI/service polling to sequence-driven callbacks.
7. Address the x86 warm-cache fast path when that lane returns to active qualification.
8. Treat GPU-only OpenGL presentation as a separate high-risk research project.

