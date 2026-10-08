Low-Latency Android P2P, Anti-Cheat, and Android-Only Admission for WoWMesh
Research date: 29 July 2026  
Applies to: CMaNGOS Classic + Playerbots, native AArch64 Android server, external Vanilla WoW client runner, Retroid Pocket 6 reference hardware  
Status: Architecture recommendation and implementation specification  
Relationship to the main plan: This document refines and supersedes the transport, host-trust, anti-cheat, and Android-only admission portions of `CMANGOS_ANDROID_MESH_IMPLEMENTATION_PLAN.md`. It does not replace the build, database, content-preparation, or Android-UI sections of that plan.
---
1. Executive decision
Use the following architecture for the first production-capable release:
Iroh 1.x as the embedded P2P transport, wrapped by a project-owned Rust crate named `wowmesh-net`.
One reliable QUIC bidirectional stream per WoW TCP connection. The unmodified Vanilla client connects to loopback TCP; the Android gateway forwards the byte stream over QUIC to the authoritative realm host.
Separate QUIC application protocols and preferably separate connections for gameplay, replication, and bulk snapshot/content transfer so a large backup does not delay gameplay.
Direct peer-to-peer UDP/QUIC whenever possible, with self-hosted regional Iroh relays as connectivity fallback. Continue probing for a direct path after establishing a relayed connection.
Invite-only discovery in version 1, not a public DHT. A QR/copy invitation contains the realm identifier, endpoint identity, expiring one-use secret, flavor-manifest hash, and relay hints.
A single authoritative host per world shard or instance at any moment. Do not run consensus on game ticks and do not remotely execute individual Playerbot decisions.
CMaNGOS remains server-authoritative for client actions. Compile and enable the real anti-cheat module, add semantic validation around movement, spells, inventory, economy, and protocol rates, and begin in observe-only mode to calibrate for Winlator/Box64 behavior.
Use authority epochs, fencing tokens, signed event journals, signed checkpoints, and quorum-certified promotion to prevent stale hosts and split-brain after failover.
Witness only high-value state transitions, not movement or every combat calculation. Community-mode realms use two independent witnesses for loot, gold, item, trade, mail, auction, XP/level, quest-reward, reputation/honor, and lockout events.
Limit trusted participation to Android by issuing short-lived realm-membership certificates only after Android attestation. Bind the certificate to a hardware-backed Android Keystore key, the Iroh endpoint identity, package/signing identity, flavor hash, allowed role, and expiration.
Use Google Play Integrity plus Android hardware key attestation for the strongest public/community tier. This requires a small online attestation broker, but that broker is not in the gameplay path and holds no realm state.
Provide a lower-trust private mode for devices that cannot pass Play Integrity. Officially signed APK + hardware-backed key + owner invitation may join as a client or relay, but should not automatically become a public world/economy authority.
The central limitation must be stated plainly:
> A hostile phone that is currently authoritative can lie about simulation results. No transport library, APK signature, or Android attestation proves every game tick. A practical design limits the damage through server authority over clients, restricted host roles, witnessed high-value outcomes, deterministic reward generation, auditability, and fast revocation. A completely untrusted, entirely serverless, cheat-proof public MMO is not achievable without expensive redundant simulation or trusted validators.
---
2. What “low latency” means for this project
The Vanilla WoW client expects two reliable, ordered TCP conversations:
authentication/login on the realm/login port;
world gameplay on the world port.
The P2P layer must preserve those semantics. Replacing the game stream with unordered datagrams would require rewriting both the client protocol endpoint and substantial CMaNGOS networking behavior, and would not necessarily improve gameplay. The correct objective is:
keep the local client-facing interface as TCP on `127.0.0.1`;
terminate that TCP in the Android app;
forward application bytes over one reliable ordered QUIC stream;
isolate unrelated traffic in other QUIC streams or connections;
use unreliable datagrams only for expendable mesh telemetry such as path probes and heartbeat hints.
QUIC removes transport-level head-of-line blocking between different streams, but bytes within the single WoW world stream remain ordered. That is desirable because the application protocol expects order. Loss on a gameplay packet may temporarily delay that gameplay stream, but should not also stop replica acknowledgements, control RPC, or another player’s independent stream.
2.1 Latency budget
The mesh cannot make the physical Internet path faster. It can minimize additional software delay and avoid bad routing. Use these engineering gates during development; they are targets, not measured claims:
Path	Added p95 latency target above raw path	Notes
Same device, client to native realm	under 0.5 ms	Loopback proxy and framing only
Same LAN, direct QUIC	under 2 ms	Includes gateway, encryption, and scheduling
Direct Internet QUIC	under 5 ms	Above measured UDP path RTT
Regional relay fallback	under 25 ms	Depends heavily on relay placement and route
Host migration pause	under 3 s initially	Later target below 1.5 s for warm standby
Gameplay stream queueing	under 10 ms p95	Excludes physical network delay
A 30–60 ms direct Internet RTT can still feel good for Vanilla WoW. A relay that turns a 35 ms direct route into 100–150 ms should be treated as degraded and clearly shown in the UI.
2.2 Workload characteristics
WoW 1.12 traffic is modest in bandwidth but sensitive to:
latency variation;
packet loss and retransmission;
long scheduling pauses on a thermally throttled host;
bufferbloat caused by snapshots or content downloads;
host migration and stale authority;
mobile Wi-Fi/cellular path changes.
Therefore, low bandwidth does not justify an undifferentiated single queue. Gameplay, control, replication, and backups require separate limits.
---
3. Transport options considered
The following scores are architecture-fit judgments for this particular product, not laboratory performance measurements.
Option	Direct-path latency	NAT traversal	Relay fallback	Android integration	Fits byte-stream tunnel	Product effort	Architecture fit	Decision
Iroh 1.x	Excellent	Built in	Built in; current relay path uses TCP	Strong Rust/Kotlin path	Excellent via QUIC bi-stream	Medium	9/10	Production choice
Raw QUIC + custom ICE/STUN/TURN	Excellent	Must build	Can design UDP relay	Strong, but all custom	Excellent	Very high	8/10 ceiling, 4/10 delivery	Reserve option if Iroh misses measured gates
Tailscale/Headscale/WireGuard	Excellent when direct	Mature	DERP/peer relay	Mature app, but VpnService constraint	Transparent IP/TCP	Low for prototype	9/10 prototype, 6/10 embedded product	Benchmark/control baseline
GameNetworkingSockets	Excellent	Some P2P/relay ecosystem features	Deployment-specific	Requires more Android packaging work	Message-oriented, adapter required	Medium-high	7/10	Good alternative for a rewritten protocol, not first choice
WebRTC DataChannel	Good	Mature ICE/STUN/TURN	TURN	Mature Android support	SCTP message/stream adapter	High	6.5/10	Use only if team already has WebRTC expertise
libp2p	Good when direct	Hole punching/DCUtR	Circuit relay	Available, larger integration surface	Streams available	High	6/10 for private realm	Consider later for open discovery, not v1
Plain TCP relay/VPS	Relay-dependent	No P2P	Central relay only	Easy	Native fit	Low	4/10	Useful diagnostic fallback, not mesh objective
Blockchain/BFT per tick	Unacceptable	Irrelevant	Irrelevant	Complex	Does not fit real-time simulation	Extreme	0/10	Reject
3.1 Why Iroh is the preferred embedded transport
As of the research date, Iroh 1.0.3 exposes authenticated peer-to-peer QUIC endpoints, direct connectivity, hole punching, relay assistance/fallback, ALPN-based application protocols, and lightweight bidirectional or unidirectional streams. It also has a documented Kotlin/Android FFI path and builds for `aarch64-linux-android` through the Android NDK.
The strongest product advantages are:
direct QUIC rather than a full-device virtual network;
endpoint public keys are already intrinsic to connection authentication;
application-controlled authorization can be layered on top;
relay assistance solves reachability without exposing game protocol ports;
QUIC path migration is suitable for mobile network changes;
custom ALPNs make protocol separation explicit;
many independent streams can coexist without transport-level head-of-line blocking across streams;
Rust is a good boundary for cryptography, framing, rate limits, and Android FFI;
the app can expose only loopback ports and keep the native realm invisible to the LAN/Internet.
The main caveat is important: the current Iroh relay transport upgrades to a custom protocol over TCP. On a lossy path, a long-lived relay fallback can inherit TCP head-of-line behavior. The product should therefore:
treat direct QUIC as the normal operating state;
deploy regional relays close to expected users;
show `DIRECT`, `RELAYED`, or `RELAY-ONLY PRIVACY` status;
keep probing for direct connectivity;
benchmark relay behavior under 1%, 2%, and 5% loss;
keep the project transport interface abstract enough to add a UDP/QUIC relay backend later if measured relay performance is inadequate.
3.2 Raw QUIC with custom ICE/STUN/TURN
This gives maximum control and the best theoretical relay design. The team could implement:
candidate gathering for LAN, IPv6, server-reflexive, and relayed addresses;
ICE-like connectivity checks;
QUIC direct paths;
TURN/UDP relay allocation;
identity, discovery, invitation, migration, relay authorization, and abuse protection.
It is not recommended for version 1 because the team would be rebuilding years of edge-case networking work: symmetric NAT, CGNAT, IPv6 transitions, mobile handover, port mapping, path validation, consent freshness, relay capacity, credential rotation, and failure diagnosis. It should remain a measurable fallback behind `wowmesh-net`, not the starting point.
3.3 Tailscale/Headscale/WireGuard
Tailscale establishes direct UDP/WireGuard connections where possible and falls back to DERP or peer relays. Direct paths generally offer the best latency and throughput. Headscale can provide a self-hosted control plane for development/private deployments.
This is the fastest route to a working multi-device prototype:
install Tailscale on two Android handhelds;
place them in a private tailnet or Headscale deployment;
run the native realm on one device;
connect the remote local gateway to the host’s tailnet address;
record raw latency, thermal load, and WoW behavior.
It is not the preferred final embedded layer because Android permits only one active `VpnService` owner at a time. Requiring a device-wide VPN conflicts with users’ existing VPNs and grants a broader network role than this app needs. It also leaves product identity, invitations, role certificates, and realm-specific authorization outside the tunnel. Keep it as:
an implementation baseline;
a private developer mode;
a fallback for early testing;
a way to distinguish CMaNGOS/game issues from custom P2P transport issues.
3.4 GameNetworkingSockets
GameNetworkingSockets is a strong game-oriented library with connection-oriented messaging and reliable/unreliable channels. It is attractive for a new game protocol, but WoW’s existing client speaks a byte-stream protocol. An adapter would have to reproduce stream semantics, connection setup, and buffering. Android packaging and NAT/relay operations would also need more project work than Iroh’s documented Android path.
Use it only if later development replaces the transparent WoW TCP tunnel with a project-specific protocol between modified gateways or native clients.
3.5 WebRTC DataChannel
WebRTC provides mature ICE, STUN, TURN, path negotiation, encryption, and Android support. Its disadvantages here are:
a larger signaling and SDP surface;
DTLS/SCTP complexity that is unnecessary for a simple authenticated game tunnel;
message semantics that still need stream adaptation;
more difficult debugging and dependency size;
browser-oriented assumptions the product does not need.
It is credible but not the most direct fit.
3.6 libp2p
libp2p offers peer identity, multiple transports, DHT discovery, circuit relays, hole punching, and pub/sub. This is useful for a broad open network, but an invite-only realm does not initially need a DHT or public gossip layer. Those features also enlarge the Sybil, spam, and resource-exhaustion surface.
Use a public discovery system only after the private realm protocol is stable. Even then, discovery should be separable from authoritative gameplay connectivity.
---
4. Exact recommended network architecture
4.1 Components
```text
Unmodified WoW 1.12 client under external runner
              │
              │ TCP loopback
              ▼
Android local gateway
  - login listener 127.0.0.1:3724
  - world listener 127.0.0.1:8085
  - account/session correlation
  - bounded buffering and backpressure
              │
              │ wowmesh-net (Rust/Iroh)
              ▼
Direct QUIC peer path, or encrypted relay fallback
              │
              ▼
Authoritative host gateway
  - validates membership and role
  - opens loopback connection to realmd/mangosd
  - records peer/device/account association
              │
              ▼
Native AArch64 CMaNGOS + Playerbots
```
The native `realmd` and `mangosd` listeners remain bound to loopback or an app-private Unix-domain socket. They should never listen on `0.0.0.0` as part of normal mesh operation.
4.2 Project-owned transport boundary
Create:
```text
native/wowmesh-net/
  Cargo.toml
  src/
    endpoint.rs
    identity.rs
    invitation.rs
    membership.rs
    game_tunnel.rs
    control.rs
    replication.rs
    blob.rs
    relay.rs
    path_metrics.rs
    quotas.rs
    framing.rs
    errors.rs
    ffi.rs
  fuzz/
  tests/
```
Kotlin and C++ must not directly depend on Iroh-specific types. Expose stable project types such as:
```rust
pub struct MeshEndpointHandle { /* opaque */ }
pub struct EndpointId([u8; 32]);
pub struct RealmId([u8; 16]);
pub struct MembershipCertificate(Vec<u8>);
pub enum PathKind { DirectLan, DirectIpv6, DirectNat, Relay, RelayOnlyPrivacy }
pub enum TunnelKind { Login, World, AdminReadOnly }
pub enum PeerRole { Client, Relay, Standby, InstanceHost, WorldHost, Witness, Owner }
```
This abstraction preserves the ability to switch the direct/relay implementation after benchmarks without rewriting the Android application or CMaNGOS integration.
4.3 ALPN and connection separation
Recommended protocol identifiers:
```text
wowmesh/game/1
wowmesh/control/1
wowmesh/replica/1
wowmesh/blob/1
wowmesh/attest/1
```
Use at least two QUIC connections between heavily involved peers:
Latency connection — gameplay tunnels, heartbeats, leases, small control messages.
Bulk connection — snapshots, map/content validation artifacts, large diagnostics.
Reason: separate QUIC streams avoid per-stream head-of-line blocking, but all streams on one connection still share congestion control and packet scheduling. A multi-hundred-megabyte snapshot can create queueing even if it uses another stream. A separate connection and bandwidth cap make behavior predictable.
4.4 Game tunnel framing
The loopback client still sees normal TCP. Over QUIC, open one bidirectional stream and send a bounded header before forwarding bytes:
```text
OpenTunnelV1 {
  magic: "WMT1",
  protocol_version: u16,
  realm_id: [16],
  tunnel_id: [16],
  kind: LOGIN | WORLD,
  authority_epoch: u64,
  membership_cert_hash: [32],
  account_binding_hint: optional opaque [32],
  client_build: u32,
  flavor_manifest_hash: [32],
  issued_mono_ms: u64,
  nonce: [32],
  signature: [64]
}
```
Host response:
```text
TunnelDecisionV1 {
  accepted: bool,
  reason_code: u16,
  host_epoch: u64,
  max_idle_ms: u32,
  max_upstream_buffer: u32,
  max_downstream_buffer: u32,
  audit_session_id: [16],
  signature: [64]
}
```
After acceptance, the remainder of the stream is byte-transparent. Do not parse every WoW opcode in the mesh proxy. Protocol validation belongs in CMaNGOS; the proxy enforces framing, identity, rate, connection, and buffer limits.
4.5 Buffering and socket behavior
Starting values to benchmark:
set `TCP_NODELAY` on both loopback TCP sides;
64 KiB initial read buffer per direction;
256 KiB normal high-water mark;
1 MiB absolute hard cap per direction;
stop reading from the producer when the high-water mark is reached;
close the tunnel on sustained overflow rather than allocating without bound;
10-second login handshake timeout;
30-second unauthenticated tunnel timeout;
90-second authenticated idle timeout only where the WoW protocol permits it;
maximum two login and two world tunnels per membership by default;
per-realm configurable limits for multibox testing.
Avoid artificial coalescing delays. Write promptly and let QUIC packetization handle small frames. Measure syscall overhead before introducing batching.
4.6 Direct path and relay sequence
For an invited peer, attempt in this order:
known same-LAN direct addresses;
direct globally routable IPv6 addresses;
NAT-hole-punched direct QUIC path;
nearest configured project relay;
optional owner-approved community relay;
explicit failure with diagnostics.
A relayed connection should continue direct-path discovery. When a direct path validates, migrate without closing the QUIC connection where supported by the transport.
Maintain at least three geographically separated relay regions for a public/community release. A private-friends release may use one owner relay plus the default upstream network during development, subject to dependency and privacy review.
4.7 Relay placement
Select relay regions using actual user distribution. For an initial Australia/New Zealand-centered community, a reasonable test set is:
Auckland or Sydney;
Singapore;
US West.
This is a deployment hypothesis, not a fixed recommendation. Record peer-to-relay and end-to-end RTT before choosing.
A relay must:
forward only encrypted endpoint traffic;
require a valid realm or project relay token;
rate-limit bytes, connections, and failed identities;
expose no game protocol port;
log aggregate operational metrics, not payloads;
support rapid revocation of abusive membership certificates;
have no authority to mint realm state.
4.8 Privacy mode
Direct P2P reveals a peer’s public IP address to the other invited peer. The UI must say this clearly.
Offer:
`AUTO`: prefer direct, relay fallback;
`DIRECT ONLY`: lowest latency, fails when NAT traversal fails;
`RELAY ONLY`: hides peer IPs from one another at the cost of latency and relay dependence;
`LAN ONLY`: no Internet relay/discovery.
Do not market encrypted transport as anonymous networking.
4.9 Invitations and discovery
Version 1 should use invitation-derived discovery rather than a public DHT.
Invitation payload:
```text
RealmInvitationV1 {
  realm_id,
  inviter_membership_id,
  inviter_endpoint_id,
  direct_address_hints,
  relay_urls,
  one_time_secret_hash,
  max_uses,
  expires_at,
  required_flavor_hash,
  requested_role_ceiling,
  privacy_policy,
  issuer_signature
}
```
Rules:
default lifetime: 15 minutes for a QR invitation;
default use count: one;
never include WoW passwords/session keys;
consume invitations transactionally;
bind acceptance to the joining membership public key;
maintain a revocation and use ledger;
require explicit owner approval for host/witness roles;
joining as a normal client must not grant console or file access.
4.10 Heartbeats, suspicion, and failover timing
Use QUIC DATAGRAM or a small dedicated control stream for expendable liveness hints. Durable authority changes remain reliable signed messages.
Starting policy:
heartbeat every 500 ms while hosting an active shard;
mark path degraded after two missed heartbeats;
mark host suspected after three missed heartbeats or 1.5 seconds;
do not promote solely on one client’s suspicion;
standby/witness quorum grants a new epoch after lease expiry and evidence threshold;
initial promotion objective: 1.5–3 seconds;
old host traffic is rejected immediately once a higher fencing epoch is observed.
Mobile networks occasionally pause for seconds. Make thresholds configurable and distinguish:
path loss;
app process suspension;
thermal stall;
host shutdown/drain;
full device disappearance.
4.11 Host selection
Do not select hosts by CPU model alone. Continuously score:
path RTT to players;
jitter and packet loss;
server world-diff latency;
thermal headroom;
charging and battery state;
available RAM;
content/flavor compatibility;
attestation/trust tier;
storage health;
recent crash/failure history.
A starting score, lower is better:
```text
score =
  0.35 * normalized_p95_rtt
+ 0.20 * normalized_jitter
+ 0.15 * loss_penalty
+ 0.10 * normalized_server_diff
+ 0.10 * thermal_penalty
+ 0.05 * battery_penalty
+ 0.05 * trust_penalty
```
Migrate only when a candidate remains at least 20–25% better for 30–60 seconds, except for safety failures. This hysteresis prevents host flapping.
4.12 Do not triangle-route remote instances
When a phone hosts a dungeon/raid instance, each participating player gateway should establish a direct/relayed QUIC connection to that instance host. Traffic must not flow:
```text
player → main world host → instance host
```
unless no other route is possible. Triangle routing adds avoidable latency, CPU, bandwidth, and a second failure point.
The main world authority issues an instance-routing ticket that authorizes direct connection to the instance worker for a bounded time and specific character/session.
---
5. Cheating: separate the three problems
A useful anti-cheat plan starts by separating threats that are often conflated.
5.1 Malicious WoW client, honest authoritative host
Examples:
speed/fly/teleport manipulation;
invalid movement timestamps;
spell cast or cooldown manipulation;
inventory/gold packet fabrication;
malformed/opcode flood;
botting or automation;
modified addon/warden responses;
lag switching.
This is the most manageable category because CMaNGOS can remain authoritative.
5.2 Malicious authoritative Android host
Examples:
creating items or gold directly;
changing database rows;
fabricating boss kills or loot;
altering combat code;
lying in checkpoints;
rolling back after an unfavorable outcome;
assigning itself world authority during a partition;
simulating a modified Playerbot system.
This is the hard category. The host owns the memory and local database while it is authoritative. Attestation reduces risk but does not prove every computation.
5.3 Network and membership abuse
Examples:
replaying invitations;
replaying state-changing messages or stale leases;
opening excessive streams;
snapshot bombs or malformed frames;
Sybil memberships;
relay bandwidth denial;
exposing peer IPs;
stale host returning after failover;
colluding host and witnesses.
These require protocol and identity controls rather than game anti-cheat alone.
---
6. Controlling malicious clients
6.1 Enable the real CMaNGOS anti-cheat implementation
The current CMaNGOS interface has hooks for addon checks, movement, skipped time, speed acknowledgements, exploration, teleport, Warden packets, chat, and actions including logging, kick, account ban, IP ban, and silence. However, the public build options do not currently expose a straightforward anti-cheat option, and the interface falls back to `NullAnticheatLib` unless `USE_ANTICHEAT` is defined.
Implementation requirement:
```cmake
option(CMANGOS_USE_ANTICHEAT "Build and enable the real anticheat module" ON)

if(CMANGOS_USE_ANTICHEAT)
  target_compile_definitions(game PRIVATE USE_ANTICHEAT=1)
  # Link the existing module sources and required dependencies.
endif()
```
Add a startup metric/log field:
```text
anticheat.implementation = real | null
```
Protected/community release CI must fail if it detects `null`. Unit tests should instantiate a world session and verify that the real implementation receives movement and Warden calls.
6.2 Begin in observe-only mode
The client is likely to run under Wine/Box64/Winlator rather than native Windows. Timing behavior, module layout, Warden behavior, and input characteristics may differ from period hardware. Immediate automatic bans would produce avoidable false positives.
Rollout:
record-only;
correction/rejection of impossible actions;
manual review and temporary quarantine;
kick on high-confidence malformed or impossible behavior;
account/device sanctions only after calibrated evidence.
Do not rely on Warden alone. Treat it as one signal because compatibility layers and rooted Android environments complicate client-integrity assumptions.
6.3 Movement validation
The authoritative server should maintain a movement envelope using:
server monotonic time, not client wall clock;
last accepted position and movement state;
permitted movement speed from auras, forms, mounts, swimming, falling, knockback, transport, taxi, and GM state;
terrain/VMAP/MMAP constraints;
teleport and transfer acknowledgements;
plausible acceleration and vertical displacement;
latency allowance based on measured session RTT/jitter, capped to prevent abuse.
Response order:
reject impossible movement;
correct/rewind character position;
increment structured detector score;
temporarily constrain movement or quarantine;
kick/ban only for repeated high-confidence violations.
A lag switch must not create legal distance. It may delay inputs, but accepted displacement is bounded by elapsed server time and permitted state.
6.4 Combat and spell validation
For every action that changes authoritative state, recompute or verify:
caster identity and control rights;
target existence and visibility;
range and line of sight;
facing where relevant;
spell known and rank permitted;
cooldown, global cooldown, resource cost, reagents, charges, and item ownership;
aura/state prerequisites;
cast/channel time and interruption;
immunity and target-type rules;
instance/group/faction constraints.
The client proposes an action; it never decides its result.
6.5 Inventory, currency, loot, quests, and progression
The server must be the only code that:
creates or destroys an item;
changes stack counts or durability;
transfers ownership;
changes gold;
grants XP, levels, skills, reputation, honor, quest credit, or rewards;
assigns loot;
commits trade/mail/auction outcomes;
changes lockouts.
Use database transactions and unique event identifiers so retries cannot duplicate outcomes.
6.6 Protocol abuse controls
At the CMaNGOS and gateway boundaries:
maximum packet/frame sizes;
opcode-specific rate limits;
aggregate bytes/second and packets/second limits;
maximum outstanding requests;
malformed packet immediate disconnect where memory safety may be at risk;
fuzz tests for mesh framing and exposed CMaNGOS packet paths;
memory and stream quotas per membership;
account, device, and realm correlation.
Because remote clients connect through a host-side loopback gateway, CMaNGOS may see `127.0.0.1` rather than the real peer IP. IP bans are therefore weak and can accidentally affect all mesh users. Prefer:
WoW account ID;
realm membership certificate ID;
hardware-backed membership key;
Iroh endpoint identity;
invitation lineage;
optional owner-defined user identity.
6.7 Evidence and penalties
Create an append-only structured anti-cheat record:
```text
AntiCheatObservationV1 {
  realm_id,
  session_id,
  account_id,
  character_guid,
  membership_id,
  detector,
  server_mono_time,
  authority_epoch,
  severity,
  measured_values,
  allowed_values,
  action_taken,
  previous_record_hash,
  host_signature
}
```
Penalty ladder:
```text
reject/correct
→ evidence accumulation
→ warning or observation flag
→ rate limit / trade restriction / quarantine
→ kick
→ temporary account or membership suspension
→ owner/community ban after review or very high confidence
```
Avoid exposing detector thresholds in public client telemetry in a way that makes evasion trivial.
---
7. Controlling a cheating authoritative host
7.1 What cannot be proven cheaply
Neither of these proves honest simulation:
official APK signature;
matching native-library hash;
Android Play Integrity verdict;
a hardware-backed signing key;
an encrypted P2P connection;
a hash chain created solely by the host.
They prove useful facts—distribution identity, device integrity signals, key custody, peer identity, and tamper-evident ordering—but a compromised or intentionally modified authoritative process may still fabricate a validly signed false event.
Therefore, define trust modes and constrain authority.
7.2 Realm trust modes
Mode A — Private friends
owner explicitly trusts the host;
host signs an audit journal;
one standby receives checkpoints;
no witnesses required for ordinary outcomes;
fastest and simplest;
appropriate for the initial product.
Mode B — Community progression
only attested host-capable memberships may host;
high-value events require two witness receipts;
deterministic reward generation where practical;
signed checkpoints and authority epochs;
witnesses should be on different devices and preferably different owners/network paths;
moderate cheat resistance without adding consensus to combat ticks.
Mode C — Public competitive/economy
at least one owner/community-trusted validator for every authoritative shard;
stronger witness diversity and reputation;
stricter role attestation;
possibly redundant deterministic execution for selected encounter/reward logic;
delayed settlement for valuable cross-player transactions;
higher complexity and latency.
Do not claim Mode C is fully Byzantine-safe unless the project later implements and formally tests enough independent replication to support that claim.
7.3 Authority leases and fencing
Every authoritative shard or instance has:
```text
AuthorityLeaseV1 {
  realm_id,
  shard_id,
  holder_membership_id,
  holder_endpoint_id,
  epoch: u64,
  not_before,
  expires_at,
  flavor_hash,
  checkpoint_hash,
  quorum_members,
  quorum_signatures
}
```
Every state-changing event includes `shard_id` and `epoch`. Any event from an epoch lower than the highest accepted epoch is rejected even if otherwise validly signed.
Rules:
epochs only increase;
promotion requires lease expiration or explicit signed handoff;
a returning stale host cannot regain authority by replaying old state;
the database settlement layer refuses stale epochs;
clients disconnect or reroute on an epoch conflict;
a host must drain and obtain a final checkpoint certificate before planned handoff.
This addresses split-brain; it does not prove that the current holder is honest.
7.4 Hash-chained event journal
The authoritative host emits events:
```text
RealmEventV1 {
  realm_id,
  shard_id,
  epoch,
  sequence,
  event_type,
  actor_ids,
  entity_ids,
  precondition_hash,
  payload_hash,
  previous_event_hash,
  server_mono_time,
  host_membership_id,
  host_signature
}
```
Properties:
sequence numbers are monotonic within an epoch;
the previous hash prevents undetected deletion/reordering inside a published chain;
checkpoints commit to the latest event hash;
replicas acknowledge durable sequence numbers;
important events include enough precondition data for witnesses to validate semantics.
A hash chain is tamper-evident, not truth-proving. That distinction must be documented in code and UI.
7.5 Witnessed high-value events
Do not witness movement packets, auto-attacks, bot target choice, or every spell. Witness only state that creates durable advantage or crosses ownership boundaries.
Recommended witnessed event classes:
item create/destroy/modify/enchant;
gold create/destroy/transfer;
loot eligibility and assignment;
player trade;
mail attachments/currency;
auction listing, bid, buyout, expiry, and settlement;
XP and level change;
quest completion/reward;
skill/reputation/honor changes;
boss kill and instance lockout;
character transfer between shards;
admin/GM mutation;
membership/role and authority changes.
A witness verifies preconditions from a replicated read model or signed state proof and returns:
```text
WitnessReceiptV1 {
  event_hash,
  realm_id,
  shard_id,
  epoch,
  witness_membership_id,
  witness_role,
  decision: ACCEPT | REJECT | NEED_MORE_STATE,
  reason_code,
  observed_checkpoint_hash,
  issued_at,
  signature
}
```
Settlement policy for community mode:
```text
host signature + any 2 valid, non-revoked witness signatures
```
The two witnesses must not be the host and should not share the same installation identity. For stronger deployments, use three of five.
7.6 Deterministic loot commit-reveal
A host can cheat by rerolling a random result until it likes the outcome. Use a combined seed:
before an encounter reaches its loot decision point, host and witnesses each commit `H(seed_i || encounter_context)`;
after the kill is final, participants reveal seeds;
derive:
```text
loot_seed = H(
  realm_id || instance_id || boss_id || kill_sequence ||
  host_seed || witness_1_seed || witness_2_seed
)
```
run a versioned deterministic loot function against the locked flavor/content manifest;
witnesses verify eligibility and output;
settlement records commitments, reveals, function version, and result.
Fallback when a participant refuses to reveal must be defined before the encounter, for example using its committed hash plus a later public checkpoint value. The policy must not let the host choose whether to include a witness after seeing outcomes.
7.7 Item and currency provenance
Assign every durable creation a globally unique event-derived provenance ID:
```text
provenance_id = H(realm_id || shard_id || epoch || event_sequence || output_index)
```
For stackable items, preserve a set/range commitment or split/merge lineage. Database constraints prevent a provenance unit from being simultaneously owned by two characters. Cross-shard transfers use a two-phase escrow:
source locks entity/item/currency;
destination proves readiness;
quorum certifies transfer event;
destination commits;
source finalizes deletion/unlock;
retries are idempotent by transfer ID.
7.8 Signed checkpoints and state roots
A checkpoint includes:
realm/shard/epoch;
durable event sequence and event-head hash;
database snapshot hash or Merkle root over relevant tables/entities;
active instance metadata;
character and bot ownership map;
lockouts;
configuration/flavor hash;
host signature;
replica/witness acknowledgements.
Use full SQLite snapshots for recovery initially. Merkleized partial proofs are a later optimization for witness validation; they should not delay the first private-host release.
7.9 Remote instance settlement
A remote dungeon host should not write directly into the global economy database. It receives a scoped instance lease and starting character/bot state, runs the real-time instance locally, then submits an outcome bundle:
```text
InstanceOutcomeV1 {
  instance_id,
  instance_epoch,
  starting_checkpoint_hash,
  ending_checkpoint_hash,
  encounter_results,
  witnessed_reward_events,
  character_delta_hashes,
  lockout_updates,
  event_head_hash,
  host_signature,
  witness_receipts
}
```
The realm settlement authority validates and applies the bundle transactionally. Invalid or incomplete high-value results are quarantined rather than partially committed.
7.10 Collusion and witness diversity
Two witnesses controlled by the same person as the host provide little protection. Mitigations:
membership certificates identify installation and optional owner account;
do not select two witnesses from the same installation;
avoid same account/payment identity where available and privacy-compatible;
prefer different network prefixes/ASNs as a weak diversity signal;
maintain witness reliability history;
randomly select witnesses from an eligible pool;
require owner/community validator participation for very rare items or major transactions;
audit improbable reward distributions statistically.
These raise the cost of collusion but do not eliminate it.
---
8. Limiting the network to Android
8.1 What weak checks fail
Do not use any of these as proof:
`Build.MODEL` sent by the client;
CPU ABI string such as `arm64-v8a`;
user-agent text;
an APK hash reported by the same app;
a custom protocol secret embedded in the APK;
Iroh endpoint identity alone;
a device name containing “Retroid” or “AYN”.
A Linux or Windows client can reproduce those values. Protocol obscurity is not platform enforcement.
8.2 Strongest practical model
Issue a short-lived Android Realm Membership Certificate (ARMC) only after verifying:
a fresh Google Play Integrity token when the tier requires it;
a hardware-backed Android Keystore key attestation chain;
the expected application package and signing certificate;
the expected version/flavor manifest;
device integrity and verified boot policy;
an allowlisted or policy-accepted device model if desired;
proof that the same key authorizes the advertised Iroh endpoint identity.
The P2P connection authenticates the Iroh endpoint. The ARMC says whether that endpoint is allowed into a particular realm and what roles it may perform.
8.3 Why a small attestation broker is required
Google’s guidance requires Play Integrity responses and hardware key attestation to be verified on a separate trusted server rather than on the device being assessed. Therefore, strict Android-only admission is not literally infrastructure-free.
The broker is narrow:
no gameplay packets;
no WoW accounts/passwords;
no realm database;
no player positions or chat;
no authoritative world state;
only challenge issuance, attestation verification, certificate issuance, and revocation.
It can be:
project-operated;
realm-owner-operated;
federated across community issuers;
open-source and reproducibly deployed.
Gameplay remains peer-to-peer after a certificate is issued.
8.4 Enrollment flow
App generates a non-exportable ECDSA P-256 membership key in Android Keystore. Request StrongBox when available, otherwise TEE-backed storage.
App requests key attestation with a fresh broker challenge and includes attestation application identity/device properties where supported.
App generates or loads its Iroh endpoint key.
App requests a Play Integrity standard token. Bind the request hash to:
```text
realm_id ||
membership_public_key ||
iroh_endpoint_id ||
package_name ||
app_signing_cert_hash ||
app_version_code ||
flavor_manifest_hash ||
broker_nonce
```
App sends the token, key-attestation chain, public keys, requested role, and device metadata to the broker over TLS.
Broker verifies Play token freshness/binding and required verdicts.
Broker validates the key-attestation certificate signatures, Google root, revocation status, security level, challenge, application identity, boot state, OS/vendor patch data where available, and key properties.
Broker applies realm/device/role policy.
Broker issues the ARMC.
On mesh connection, the peer presents the ARMC and signs a fresh challenge using the hardware-backed membership key.
The peer verifies issuer, expiry, revocation epoch, realm/flavor binding, role, membership signature, and Iroh endpoint binding.
8.5 Membership certificate contents
Recommended canonical CBOR/COSE structure:
```text
AndroidRealmMembershipCertificateV1 {
  certificate_id,
  realm_id,
  membership_public_key,
  iroh_endpoint_id,
  package_name,
  app_signing_cert_sha256,
  app_version_code,
  flavor_manifest_hash,
  device_brand,
  device_model,
  device_product,
  android_sdk,
  integrity_tier,
  play_app_verdict,
  play_device_verdicts,
  play_account_verdict,
  key_security_level,
  verified_boot_state,
  os_patch_level,
  vendor_patch_level,
  issued_at,
  expires_at,
  revocation_epoch,
  allowed_roles,
  role_constraints,
  issuer_id,
  issuer_signature
}
```
Do not include hardware serial/IMEI unless a later legal/privacy review establishes a compelling need. The hardware-backed membership key is sufficient as the installation identity.
8.6 Suggested trust tiers
Tier	Admission evidence	Allowed default roles	Notes
0 — Owner-invited unverified	Realm-owner signature only	Private client	No claim of Android assurance
1 — Android key-attested	Official app identity in hardware key attestation; TEE/StrongBox where available	Client, relay; private host by owner choice	Useful for sideload/private mode
2 — Play recognized/device integrity	Tier 1 + genuine recognized app + `MEETS_DEVICE_INTEGRITY`	Client, relay, standby, instance host	Recommended public/community minimum
3 — Strong/fresh validator	Tier 2 + strong integrity/recent patches + clean history	World/economy host, witness, promotion quorum	May exclude handhelds with stale vendor patches
Owner override	Explicit realm-owner policy	Configurable	UI must show reduced trust and consequences
Do not use `MEETS_BASIC_INTEGRITY` as a strong host criterion. Google documents that it may include unlocked, unverified, or uncertified devices. `MEETS_STRONG_INTEGRITY` on Android 13+ includes device integrity and recent security updates, but this may be difficult for niche gaming handhelds with slower patch cadence.
8.7 Certificate lifetime and renewal
Starting policy:
normal client/relay: seven days;
standby/instance host: 24 hours;
world/economy host or witness: 12–24 hours;
require a fresh attestation before promotion to a stronger role;
cache certificate for offline joining within its lifetime;
publish compact signed revocation lists and a monotonically increasing revocation epoch;
emergency owner revoke takes effect immediately when peers reconnect.
A certificate must not silently upgrade its roles. New roles require a new signed certificate or explicit realm quorum grant within the certificate’s ceiling.
8.8 Sideloaded APK problem
A strict Play-recognized verdict generally assumes a version Google Play recognizes. A sideloaded development/stable APK may not receive the same app-integrity/account verdict as a Play-installed release.
Support two channels:
Certified community channel — distributed through Google Play testing/production tracks, eligible for Play Integrity policy.
Private sideload channel — relies on hardware key attestation, official signing identity, owner invitation, and lower role ceilings.
Never silently present private sideload mode as equivalent to Play-certified public mode.
8.9 Android-only cannot stop all proxying
Even a genuine attested Android app can potentially act as a gateway for a non-Android machine. Attestation proves that an Android-held key participates in the session, not that every human input or all client computation originates on that physical screen.
Mitigations:
bind each game tunnel to a current ARMC and hardware-key proof;
require the local gateway to terminate the WoW loopback session;
rate-limit sessions per device;
bind host roles to attested processes and short lifetimes;
prohibit generic remote-port forwarding in release builds;
monitor abnormal multi-session behavior;
accept that a determined owner of an Android device may relay input/traffic.
The enforceable statement is:
> Only an admitted Android membership can enter the realm protocol and hold trusted roles.
It is not possible to prove that no external computer influences that Android device.
---
9. Retroid Pocket 6, Retroid Pocket 5, and AYN Thor certification risk
The manufacturers’ current product pages establish hardware/OS facts:
Retroid Pocket 6: Snapdragon 8 Gen 2, 8/12 GB LPDDR5X, Android 13, active cooling, Wi-Fi 7;
Retroid Pocket 5: Snapdragon 865, 12 GB LPDDR4X, Android 13, active cooling, Wi-Fi 6;
AYN Thor Base/Pro/Max: Snapdragon 8 Gen 2 with 8/12/16 GB options and Android 13.
Those pages do not establish the Play Integrity verdict, hardware key-attestation chain, StrongBox availability, locked-boot state, or current security patch age of a retail unit. Do not create an Android-only policy that assumes these devices pass.
9.1 Mandatory physical-device probe
Before finalizing role policy, run a signed probe build on each retail SKU/firmware:
```text
Retroid Pocket 6 8 GB
Retroid Pocket 6 12 GB
Retroid Pocket 5
AYN Thor Base
AYN Thor Pro
AYN Thor Max
Certified Pixel/Samsung control device
```
Collect with user consent:
Play Store “Play Protect certification” status;
Play Integrity app recognition/account/device verdicts;
`MEETS_DEVICE_INTEGRITY` and `MEETS_STRONG_INTEGRITY` availability;
hardware key-attestation root and security level;
StrongBox support;
attestation application ID contents;
verified boot state;
OS, vendor, and boot patch levels;
bootloader state;
key-generation/attestation failure modes after OTA;
behavior under factory firmware versus any alternate firmware.
Store only the minimum diagnostic details and redact certificate identifiers in public bug reports.
9.2 Policy fallback
If the reference handhelds fail `MEETS_DEVICE_INTEGRITY`, do not weaken the public policy invisibly. Ship two explicit modes:
Certified Community Mesh: higher-trust roles require Play/device integrity and hardware key attestation.
Handheld Private Mesh: owner-invited, hardware-key/app-signature checks where possible, reduced trust, no automatic public economy/witness role.
This preserves support for the actual target devices without pretending they meet a certification level they do not.
---
10. Network-security controls
10.1 Connection authentication
Iroh authenticates endpoint identity, but the application must decide authorization. Require:
valid ARMC or owner-private membership certificate;
proof of possession of membership key;
certificate’s endpoint ID matches the connection endpoint;
realm/flavor/version compatibility;
role allows requested protocol/stream;
certificate not expired/revoked;
fresh challenge/nonce;
bounded clock skew handling;
current authority epoch where relevant.
10.2 Replay prevention
Use:
random 256-bit nonces;
monotonically increasing per-membership control sequence numbers;
event sequence and authority epoch;
short message expiration windows;
invitation one-use ledger;
idempotency keys for state-changing RPC;
persistent anti-replay state for leases, promotions, and settlements.
Do not use QUIC 0-RTT for:
joining a realm;
role changes;
invitation consumption;
leases/promotions;
economy or state-changing settlement;
admin operations.
Replay-safe telemetry may use it only after explicit review.
10.3 Resource limits
Per membership and per realm:
connection-attempt rate;
concurrent QUIC connections;
streams opened per minute;
gameplay tunnel count;
control request rate;
maximum frame size;
maximum snapshot size and expansion ratio;
bandwidth token bucket;
memory/buffer budget;
failed certificate/invitation attempts;
relay bandwidth/day where necessary.
Use separate rate buckets for gameplay so a diagnostic upload cannot consume its allowance.
10.4 Parser and archive hardening
canonical CBOR or a similarly bounded binary schema;
reject duplicate map keys and non-canonical encodings for signed objects;
integer overflow checks;
explicit maximum list/string/blob lengths;
property and coverage-guided fuzzing;
no arbitrary path extraction;
reject symlinks/hardlinks/device nodes in archives;
cap decompressed size and file count;
verify signed manifest before extracting content;
atomic import into a staging directory, then rename.
10.5 Update and flavor integrity
A node eligible to host must match:
app signing identity;
app/runtime version range;
exact CMaNGOS commit;
exact Playerbots commit;
database schema/content revision;
DBC/map/VMAP/MMAP content hashes;
configuration compatibility hash;
mesh protocol version;
anti-cheat policy version.
The signed flavor manifest is part of the membership and lease decision. A mismatched node may download/prepare compatible content but cannot receive authority.
---
11. Implementation work plan
Phase N0 — Device trust and transport spike
Goal: retire the two largest unknowns before integrating CMaNGOS.
Deliverables:
Android attestation probe app;
Iroh Android echo/tunnel prototype;
Tailscale/Headscale comparison path;
self-hosted Iroh relay in at least two regions;
automated path/thermal benchmark harness;
physical verdict matrix for RP6/RP5/Thor.
Exit gates:
Iroh runs reliably on `aarch64-linux-android` target devices;
direct and relay paths are observable;
Play/key-attestation capability is known for each device;
no policy assumes an untested verdict.
Phase N1 — `wowmesh-net` and local gateway
Deliverables:
Rust facade and UniFFI/Kotlin bindings;
endpoint lifecycle tied to Android foreground service;
loopback login/world listeners;
QUIC game tunnel framing;
invitation QR/text flow;
bounded buffers/backpressure;
path status UI;
direct/relay/privacy modes;
metrics and diagnostics export;
fuzz tests for all mesh frames.
Exit gates:
unmodified client can log in and play through two Android devices;
no native realm port is externally exposed;
4-hour direct and relay runs pass;
snapshots do not materially affect gameplay when placed on bulk connection.
Phase N2 — Membership and Android admission
Deliverables:
Android Keystore membership key;
key attestation request/parse pipeline;
Play Integrity request binding;
minimal attestation broker;
ARMC issuance and verification;
revocation list/epoch;
role policy engine;
owner-private certificate mode;
privacy/consent UI.
Exit gates:
Linux/non-Android implementation without a valid certificate cannot enter protected ALPNs;
copied certificates fail proof-of-possession;
endpoint substitution fails;
stale/revoked certificates fail;
private mode is visibly distinguished from certified mode.
Phase N3 — CMaNGOS anti-cheat and identity correlation
Deliverables:
`CMANGOS_USE_ANTICHEAT` build option;
test that protected build does not use null anti-cheat;
mesh session metadata associated with `WorldSession`;
structured anti-cheat observations;
movement/spell/inventory/economy invariants;
observe-only dashboard;
membership/account sanctions replacing IP-first policy.
Exit gates:
Winlator baseline produces known false-positive profile;
malformed and impossible actions are rejected without server corruption;
sanctions identify the actual membership rather than loopback IP.
Phase N4 — Audit journal and state settlement
Deliverables:
canonical signed event schema;
authority epochs and fencing;
signed checkpoint schema;
unique item/currency provenance IDs;
idempotent transfer IDs;
2-of-3 witness receipts for high-value events;
deterministic loot commit-reveal prototype;
audit viewer and export.
Exit gates:
stale host events cannot settle;
duplicate/replayed trade or loot cannot duplicate state;
a modified event breaks signatures/hash chain;
witnesses can reject an invalid precondition;
loss of one witness does not halt community settlement.
Phase N5 — Warm standby and automatic promotion
Deliverables:
checkpoint/event replication;
durable sequence acknowledgements;
lease renewal/expiry;
quorum-certified promotion;
client gateway reroute;
stale-host fencing;
partition and rollback tests.
Exit gates:
forced host death promotes within target window;
no two epochs can both settle durable events;
returning stale host is fenced;
character/economy state is not duplicated or silently rolled back.
Phase N6 — Remote dungeon/raid instance
Deliverables:
scoped instance lease;
two-phase player/bot transfer;
direct player-to-instance tunnel tickets;
starting/final checkpoint hashes;
outcome bundle settlement;
witnessed rewards and lockouts;
instance host failure recovery.
Exit gates:
measured CPU benefit exceeds transfer/network overhead;
no triangle routing in normal operation;
host failure cannot duplicate loot or strand characters permanently;
40-player/bot raid test meets server-diff and latency gates.
Phase N7 — Public hardening
Deliverables:
witness diversity selection;
membership reputation and abuse history;
relay authentication/quotas;
Sybil and invitation-abuse controls;
security review and protocol threat model;
reproducible build and SBOM;
external audit/fuzz campaign;
incident and key-rotation playbooks.
---
12. Suggested source-tree changes
```text
android-app/
  app/src/main/java/.../
    mesh/
      MeshService.kt
      MeshRepository.kt
      InvitationViewModel.kt
      MembershipViewModel.kt
      PathStatus.kt
      TrustTier.kt
    attestation/
      PlayIntegrityClient.kt
      KeyAttestationClient.kt
      MembershipEnrollment.kt
    gateway/
      LocalGatewayService.kt
    ui/
      MeshDashboard.kt
      PeerDetails.kt
      TrustDetails.kt
      RelayPrivacySettings.kt

native/
  wowmesh-net/                # Rust/Iroh facade
  wowmesh-protocol/           # canonical schemas and signing
  wowmesh-attestation/        # verifier shared by broker/tests
  wowmesh-bench/              # Android/Linux path benchmark
  cmangos-android-runtime/    # native realm lifecycle wrapper

services/
  attestation-broker/
  relay-config/
  optional-owner-coordinator/

tests/
  network-lab/
  nat-matrix/
  partition/
  anticheat/
  witness/
  deterministic-loot/
  fuzz/
```
Keep attestation verification logic in a reusable, testable library. The broker owns only policy and issuance; cryptographic parsing should have deterministic test vectors.
---
13. Benchmark and test matrix
13.1 Devices
RP6 8 GB and 12 GB;
RP5;
Thor Base/Pro/Max where available;
certified mainstream Android control;
Linux relay host;
Linux adversarial client without Android membership.
13.2 Networks
same Wi-Fi LAN;
different home NATs;
public IPv6;
full-cone/restricted/port-restricted/symmetric NAT;
carrier CGNAT;
phone hotspot;
cellular to home broadband;
forced relay;
1%, 2%, and 5% packet loss;
30, 80, and 150 ms injected RTT;
Wi-Fi-to-cellular and cellular-to-Wi-Fi handover;
relay failure during an active session.
13.3 Metrics
setup time p50/p95/p99;
direct connection success rate;
time from relay to direct migration;
added RTT, jitter, and loss;
CPU time in gateway/transport;
bytes and packets per tunnel;
battery power and thermal status;
client FPS/frame-time;
CMaNGOS average/max world diff;
bot online/active/detailed-movement counts;
disconnects and recovery time;
snapshot throughput and gameplay interference;
certificate enrollment/renewal failure rate.
13.4 Initial engineering gates
Gate	Target
Same-LAN direct success	100% in supported test environments
Mixed Internet direct success	over 80% across maintained NAT matrix
Same-LAN added p95 RTT	under 2 ms
Direct Internet added p95 RTT	under 5 ms above raw path
Regional relay added p95 RTT	under 25 ms where geography permits
Gateway+transport CPU	under 5% of one big core at normal WoW traffic
4-hour active session	zero unexplained tunnel disconnects
Buffer cap	never exceeded without controlled backpressure/close
Snapshot during play	less than 5 ms additional gameplay p95 queueing
Host promotion	under 3 s initial target
Duplicate durable outcomes after failover	zero
Stale epoch settlement	zero accepted
Protected ALPN without valid ARMC	zero accepted
Do not publish performance claims until these are measured on retail hardware.
---
14. Concrete security tests
Membership/Android admission
replay old Play Integrity token;
alter request hash binding;
substitute Iroh endpoint after certificate issuance;
copy certificate to another device;
use software-backed key instead of TEE/StrongBox;
revoked attestation chain;
expired certificate;
downgrade requested role;
forged model/ABI/build strings;
Linux client implementing the wire protocol;
rooted/unlocked test device where policy disallows it;
Play-recognized app but wrong flavor manifest.
Network protocol
frame length integer overflow;
duplicate canonical-CBOR keys;
stream flood;
half-open tunnel exhaustion;
slow-reader/slow-writer attack;
malformed signature and certificate chain;
invitation brute force/reuse;
0-RTT replay attempt;
relay token theft and quota exhaustion;
path change during authentication;
bulk stream congestion while gameplay active.
Authority/failover
two hosts claim same epoch;
stale host returns after promotion;
quorum member partitions;
host rewinds database but presents old checkpoint;
event sequence gap/duplication;
witness signs against wrong checkpoint;
host/witness collusion simulation;
shutdown during high-value transaction;
duplicate instance outcome submission;
delayed old packet after transfer.
Game anti-cheat
speed/fly/teleport;
movement timestamp manipulation;
impossible vertical movement;
cast without spell/resource/cooldown;
invalid target/range/LOS;
inventory duplication/replay;
trade/mail/auction retries;
malformed opcode sizes;
high-rate opcode flood;
Winlator timing and Warden compatibility baseline;
false-positive behavior under 150 ms RTT and 5% loss.
---
15. Decisions to avoid
Do not:
expose `mangosd` directly to the Internet;
rely on a public IP or manual port forwarding as the default;
run a blockchain or Raft/Paxos decision for every combat tick;
distribute individual Playerbot AI calls across phones;
put snapshots on the same unbounded queue as gameplay;
assume a signed APK makes a host honest;
use device model or ABI strings as Android proof;
use IP bans as the primary mesh identity sanction;
let a remote instance worker write arbitrary SQL to the global database;
let an expired/stale host settle events after a higher epoch exists;
allow arbitrary DHT peers into v1 realms;
require Tailscale’s Android VPN for the final embedded product;
claim the target handhelds pass Play Integrity before physical testing;
enforce automatic Warden bans before compatibility calibration;
promise a cheat-proof public P2P realm.
---
16. Final recommended product profiles
16.1 Private Handheld Mesh — first release
```text
Transport: Iroh direct QUIC + relay fallback
Discovery: one-use invitations
Admission: owner certificate; hardware Android key where available
Authority: one trusted host + one standby
Anti-cheat: server-authoritative, real CMaNGOS module in observe/correct mode
Durability: signed checkpoints and event journal
Witnessing: optional high-value audit, not required
Eligible devices: RP6/RP5/Thor after compatibility testing
```
This is achievable and useful without pretending peers are mutually hostile.
16.2 Certified Community Mesh — second security profile
```text
Transport: same
Admission: Play Integrity + hardware key attestation + ARMC
Authority: Tier 2/3 hosts only
Promotion: quorum lease and fencing epoch
High-value state: host + 2 witnesses
Loot: deterministic commit-reveal
Economy: provenance and transactional settlement
Public discovery: still optional; invitations remain safer
```
16.3 Public Competitive Mesh — research profile
Requires:
trusted/community validator participation;
stronger witness diversity;
delayed settlement and dispute handling;
selected redundant simulation or deterministic proofs;
professional security review;
clear moderation/governance.
It should not block the first two profiles.
---
17. Source references
Primary and official sources consulted for this addendum:
Iroh 1.0.3 Rust API documentation — peer-to-peer QUIC, hole punching, relays, endpoint identity, ALPN, and streams:  
https://docs.rs/iroh/1.0.3/iroh/
Iroh FFI Kotlin/Android build documentation:  
https://github.com/n0-computer/iroh-ffi/blob/main/README.kotlin.md
RFC 9000, QUIC:  
https://www.rfc-editor.org/rfc/rfc9000
RFC 9221, unreliable datagram extension to QUIC:  
https://www.rfc-editor.org/rfc/rfc9221
Tailscale connection types and direct/DERP/peer-relay behavior:  
https://tailscale.com/docs/reference/connection-types
Android `VpnService` API:  
https://developer.android.com/reference/android/net/VpnService
Google Play Integrity overview and setup/verdict policy:  
https://developer.android.com/google/play/integrity/overview  
https://developer.android.com/google/play/integrity/setup
Android hardware-backed key attestation:  
https://developer.android.com/privacy-and-security/security-key-attestation
Google Play Protect certification check:  
https://support.google.com/googleplay/answer/7165974
CMaNGOS Classic anti-cheat interface and build options:  
https://github.com/cmangos/mangos-classic/blob/master/src/game/Anticheat/Anticheat.hpp  
https://github.com/cmangos/mangos-classic/blob/master/cmake/options.cmake
Retroid Pocket 6 official specifications:  
https://www.goretroid.com/products/retroid-pocket-6-handheld
Retroid Pocket 5 official specifications:  
https://www.goretroid.com/products/retroid-pocket-5-handheld
AYN Thor official specifications:  
https://www.ayntec.com/products/ayn-thor
GameNetworkingSockets official repository:  
https://github.com/ValveSoftware/GameNetworkingSockets
libp2p connectivity/hole-punching documentation:  
https://docs.libp2p.io/concepts/nat/hole-punching/
---
18. One-sentence implementation directive for an agent
> Implement an invite-only Iroh/QUIC Android mesh with loopback WoW TCP gateways, short-lived hardware-bound Android membership certificates, one fenced authoritative host per shard, server-side CMaNGOS validation, and witnessed settlement only for durable high-value outcomes; prove performance and device-attestation compatibility on retail RP6/RP5/Thor hardware before adding automatic failover or distributed instances.