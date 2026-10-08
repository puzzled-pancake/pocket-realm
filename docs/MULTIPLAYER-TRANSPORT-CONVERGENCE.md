# Multiplayer transport convergence — WoWMesh research × external advice

Date: 2026-10-02. Status: owner-approved synthesis.

**Inputs**

- `docs/RESEARCH-WOWMESH-P2P-2026-07.md` — owner's earlier WoWMesh research (29 Jul 2026), kept verbatim.
- `docs/EXTERNAL-HOSTING-ADVICE.md` — two external web agents consulted 2026-10-02 on the exact Pocket Realm constraints (reconnect resilience, no signups, host-IP privacy, serverless discovery, stock clients 1.12.1/2.4.3/3.3.5a).
- `docs/DESKTOP-MULTIPLAYER-AND-TIMEFRAME-DESIGN.md` — current in-repo multiplayer/timeframe design.

**Scope ruling by owner (2026-10-02):** anti-cheat, Android-only admission/attestation (ARMC, Play Integrity, device certification), and witness/economy settlement are **out of scope**. Only transport, invitations/discovery, reconnect resilience, privacy, and relay mechanics are adopted.

## 1. Converged verdict (all three sources agree)

1. **A relay fallback is structurally mandatory.** Direct TCP hole-punching succeeds only ~50-65% on residential NATs and <20-30% under CGNAT/hotspots (symmetric-NAT mapping). Direct P2P is an optimization, never the only path.
2. **Iroh 1.x (QUIC) is the embedded transport.** The old research chose it (§1, §3.1); external Agent B independently recommends it; Agent A's rathole/custom-relay alternative loses on integration cost (Iroh: endpoint Ed25519 identity intrinsic, hole-punch + relay built in, Kotlin/Android FFI, `aarch64-linux-android` supported). Keep the transport behind a project facade (`wowmesh-net`-style) so the backend stays swappable.
3. **Loopback gateway pattern solves the dual-port trap.** Classic WoW does login on 3724, then the auth server *hands the client a world IP:port inside the realmlist packet* (conventionally 8085) and the client reconnects there — with no SNI/hostname metadata, so a generic reverse proxy cannot multiplex one port. The converged answer: the companion/gateway listens on `127.0.0.1:3724` and `127.0.0.1:8085`, tunnels both over QUIC, and the realmlist advertises `127.0.0.1` — no dynamic DB rewriting, stock client untouched. (Old doc §4.1 already specified this; both external agents flagged the same trap.)
4. **Stable identity ≠ stable address.** The realm's public identity is a cryptographic key (Iroh endpoint/NodeID, Ed25519). Host IP changes, router reboots, Wi-Fi→hotspot handoff and CGNAT remaps do NOT change it; the host simply re-establishes its outbound connection and the realm is reachable again at the same identity. This is the primary answer to "friends can reconnect when the host comes back".
5. **Privacy by design.** The home IP is never part of the realm's public identity; discovery records name the relay, never the host. Direct P2P necessarily exposes peer IPs to each other → offer explicit modes: AUTO (direct preferred, relay fallback), DIRECT-ONLY, RELAY-ONLY (peers see only the relay), LAN-ONLY. Never market transport encryption as anonymity. Opt-in public visibility, fail-closed default OFF.

## 2. Adopted from the old research (not in the external advice)

- **Separate latency vs bulk QUIC connections.** All streams on one connection share congestion control; a multi-hundred-MB snapshot/content transfer must not sit in the gameplay path. One connection for gameplay+control, one for blobs/backups, separate ALPNs (`game/control/replica/blob`).
- **One reliable QUIC bi-stream per WoW TCP connection** — preserve the client's byte-stream semantics; do NOT parse WoW opcodes in the proxy (validation belongs to CMaNGOS); bounded buffers/high-water marks, no artificial coalescing.
- **Measured latency gates** (targets, not claims): same-device loopback <0.5 ms added; LAN direct <2 ms; direct Internet <5 ms above raw path; regional relay <25 ms; host-migration pause <3 s initial (<1.5 s warm); gameplay queueing <10 ms p95.
- **Iroh relay caveat:** current relay transport is TCP-based → a long-lived relayed path can inherit head-of-line behavior on lossy links. Mitigations: treat direct QUIC as normal state, keep probing direct after relayed connect, show DIRECT/RELAYED/RELAY-ONLY status in UI, benchmark at 1/2/5% loss, keep the facade abstract enough for a UDP/QUIC relay backend.
- **Invitation-derived discovery for v1 (no public DHT).** `RealmInvitationV1`: realm_id, inviter endpoint id, direct-address hints, relay URLs, one-time secret hash, max uses (default 1), expires (default 15 min for QR), required flavor-manifest hash, requested-role ceiling, issuer signature. Consumed transactionally; bound to the joiner's membership key; never contains WoW passwords/session keys.
- **Deep reconnect resilience — host failover, not just endpoint stability.** Heartbeats every 500 ms; host suspected after ~1.5 s of silence; authority leases with monotonically increasing epochs + fencing tokens; a stale host returning after failover is rejected once a higher epoch exists; warm standby promotion target <3 s. This covers the case the external advice only half-answers (it keeps the *address* stable; epochs+standby keep the *world* continuous).
- **No triangle routing for instances:** players connect directly/relayed to a dungeon-instance host, never player→world→instance; the world authority issues bounded routing tickets.
- **Relay requirements:** forward only encrypted endpoint traffic; require a realm/relay token; rate-limit connections/bytes/failed identities; expose no game-protocol port; log aggregate metrics, not payloads; support rapid revocation; never able to mint realm state. Deploy ≥3 regions for public use (ANZ-centered hypothesis: AU/NZ, Singapore, US-West).
- **Path attempt order:** known LAN addresses → global IPv6 → NAT-punched direct → nearest configured relay → owner-approved community relay → explicit failure with diagnostics. Relayed connections continue probing for direct migration.
- **Host selection by score** (p95 RTT 0.35, jitter 0.20, loss 0.15, world-diff 0.10, thermal 0.10, battery 0.05, trust 0.05) with 20-25%-better-for-30-60 s hysteresis to prevent flapping.

## 3. Adopted from the external advice (not in the old research)

- **2026 service disqualifications (verified):** playit.gg now requires accounts and paywalls raw TCP (Premium); Cloudflare quick tunnels are HTTP-only, ephemeral, ToS-unsafe for game TCP; bore.pub unstable (2026 outage, no port retention); localtunnel/serveo/pinggy unusable. Self-hosted rathole/frp work but are unnecessary given Iroh.
- **Nostr ephemeral signed events as the serverless PUBLIC discovery primitive** (NIP-01/NIP-40, kind 30078, `["t","wow-realm-public"]` tag, ~10-min expiry refreshed while online, published to 3-5 public relays, ~100 lines of Kotlin over OkHttp WebSockets, accountless, host publishes only the relay/connection token). Beats Mainline DHT on Android: no routing-table decay, no battery drain, no IP leak, tiny Sybil surface.
- **Mainline DHT BEP44** (signed mutable records) is the research alternative: JVM implementations exist (mldht, Bt) but DHT is lookup-not-search, and `announce_peer` stores your IP — never announce the host, only the relay. Also weak on CGNAT/mobile.
- **Public stock-client tier:** the relay exposes a stable `host:port` pair (auth+world) so a stranger with only a realmlist edit can join; SRP6 protects the password exchange but world traffic stays plaintext end-to-end — the relay transports it unencrypted; treat public realms as public.
- **Abuse/DDoS posture:** public ingress gets per-IP connection rate limits, per-realm client caps, pre-login idle timeouts, handshake timeouts; DDoS hits the relay and the allocation can be killed without touching the home network.
- **Two realm identifiers** (optional hardening): a random private realm ID for friends/invites and `hash(public key)` for public discovery, so casual play never leaks into the global namespace.
- **TCP simultaneous-open** (RFC 5382) is real: ~64% historically, ~70% (DCUtR/IPFS 2026 study) — worth implementing as an optimization behind the relay fallback.

## 4. Conflicts resolved

| Question | Old research | External A | External B | Resolution |
|---|---|---|---|---|
| Discovery v1 | Invite-only, no DHT | DHT BEP44 + beacons | Nostr ephemeral | **Invite-only for friends (v1)**; Nostr ephemeral events for opt-in public listing later; DHT = research track |
| Relay impl | Iroh built-in | rathole/custom pool | Iroh/DERP-style | **Iroh built-in**, self-hosted regional relays; facade keeps rathole-style swappable |
| Stable address | Endpoint key + relay hints | realm_id-bound relay allocation | Iroh NodeId | **Iroh NodeId is the stable identity**; relay URLs are hints (equivalent property, less machinery) |
| Tailscale/WireGuard | Dev baseline only | Reject for product | Not considered | **Dev/benchmark baseline only** (Android single-VpnService conflict) |
| Quick tunnels | Not considered | Dev only | Non-viable | **HTTP showcase page only — never the game TCP endpoint** (the page is disposable by design; 3724/8085 never touch quick tunnels) |

## 5. Target tiering (maps onto the in-repo design)

- **Tier 0 — Solo:** loopback only, no networking (exists).
- **Tier 1 — LAN:** mDNS/NSD advertisement (`_wowrealm._tcp`), direct TCP, zero friend friction, mDNS follows DHCP changes (in-repo LAN-play design).
- **Tier 2 — Friends over Internet (the main feature):** companion/gateway maps `127.0.0.1:3724/8085` → one-hop QUIC (direct punched where possible, relay fallback), invite tokens (QR/copy) carry realm identity + one-time secret + relay hints; RELAY-ONLY privacy mode available; survives host IP changes and reconnects; epochs+standby for host failure.
- **Tier 3 — Public showcase:** opt-in only. *Now:* the realm-status web page via cloudflared quick tunnel (HTTP only, disposable by design) with privacy payload rules (bots labeled, character names hidden by default, no IPs/secrets). *Later:* game ingress through the relay pool only, listed via Nostr-style ephemeral signed events; stock clients join through the stable relay `host:port` pair.

## 6. Open items neither source fully answers

- Session continuity of *logged-in* WoW clients across a host failover (gateway reroute exists in the old spec; client-visible behavior needs live testing).
- Operational burden of project-operated relays (bandwidth, abuse handling) — acceptable at 1-10 players/realm; revisit at scale.
- Windows-desktop host parity for the same gateway core (old research was Android-centric; the repo now has a desktop sibling that should share it).
