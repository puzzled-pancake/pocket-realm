# ADR-017: O10 durable RuntimeSupervisor and generation ownership

Status: accepted and qualified (2026-08-03)

## Decision

Pocket Realm has one production lifecycle authority: a non-exported foreground
Android service in the dedicated `:supervisor` process. Its platform-independent
`DurableRuntimeSupervisor` core owns explicit database, realm, world, and client
states. The Android adapter may promote a component only after its structured
health response and ownership identity both match; process or PID existence is
never readiness.

Startup is dependency-gated in database -> realmd -> world -> client order.
Graceful shutdown is client close -> world save acknowledgement -> world stop ->
realmd stop -> authenticated database stop. Each operation has a component-
specific timeout. Escalation is legal only after a fresh observation proves the
same session and component token. Client failure retains the server stack and
permits bounded relaunch; database, realm, and world failures are realm-fatal.

## Durable journal and ownership

The mode-0600 schema-2 journal contains the requested profile, UUID session,
per-component state, independent 256-bit instance tokens, last durable action,
clean/dirty marker, timestamps, bounded error, and recoverability. Each update
uses a same-directory temporary file, file `fsync`, atomic `rename(2)`, and
directory `fsync`. A clean marker is committed only after the complete graceful
shutdown sequence succeeds.

Tokens prevent stale PID or listener reuse from granting authority, but a
persisted string alone does not define a live supervisor generation. Each
component claim therefore also receives a Binder owner lease. The component
links to its death and performs safe dirty teardown if the `:supervisor` process
dies. A clean stop unlinks the lease. On recovery, the replacement supervisor
observes every component before acting and withholds signals from any owner that
does not exactly match the journal. Database recovery occurs only after old
bindings and their owner-loss teardown have been allowed to retire.

Finite start, stop, and recovery operations hold a bounded partial wake lock.
The notification contains only bounded state/profile text and exposes Save &
Exit; it contains no paths, component tokens, credentials, or mutable command.

## Compatibility boundary

O10 qualifies the production database/realm/world lifecycle and the client
state-machine contract. Pure-core fault tests prove successful client-only
relaunch. On Android, the O10 adapter deliberately returns a structured client
failure because attaching the already-qualified O07 Wine/X11 display session is
part of O12. This preserves `WORLD_READY` and proves that client absence cannot
tear down the local realm; it does not weaken or pre-claim O12 integration.

The O02 `RealmSupervisor` remains only as a compatibility UI-transition model
for its original tests. It is not a production lifecycle owner.

## Qualification

The host JVM suite proves exact startup/shutdown ordering, rejection of
PID-only readiness, withholding on ownership mismatch, client-only failure and
relaunch, realm-fatal dependency failure, and timeout escalation only after
ownership proof.

The fixed API-35 x86_64 4 KB AVD passed the O10 acceptance run in 33.310
seconds. It proved structured server readiness and unique 256-bit ownership,
foreground supervisor state, client-failure isolation, a clean stop, deliberate
supervisor death followed by Binder-lease child teardown and fresh-session
recovery, an owned world failure classified realm-fatal and dirty, another
recovery, and a final clean schema-2 journal. The authoritative structured
result is
`tests/avd/AVD-Modern-x86_64-v1/evidence/runtimeSupervisor-o10-acceptance-20260803.PASS.json`.
