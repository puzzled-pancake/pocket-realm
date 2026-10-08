# NOSTR-IROH-BUILD-PLAN — Tier 2/3 internet discovery & join (v1)

Status: **owner-approved 2026-10-08** (owner endorsed the external consult's "Option B with two
corrections"). Implementation contract for the campaign that closes internet discovery and join.
Sources: `docs/EXTERNAL-DISCOVERY-ADVICE-2026-10.md` (the consult, verbatim) over
`docs/MULTIPLAYER-TRANSPORT-CONVERGENCE.md` (the earlier convergence) — where they differ, THIS
document wins.

## Rulings adopted from the consult

1. **Ship the Nostr browser first** (Stage 1) — days of pure Kotlin, nothing throwaway; join stays
   on existing paths (LAN / manual tunnel) until the transport lands.
2. **N0's public relay fleet is a VENDOR dependency**, not community infrastructure: dev/hobby
   grade, no SLA, four regions (US-W, US-E, EU-C, SG — no Oceania), changeable rate limits, relays
   see IP/timing/byte-counts. The Iroh spike (Stage 2) is a **GATE, not a demo** — if it fails, the
   campaign stops and reports instead of building the gateway.
3. **Constraint 2 narrowed (owner-adopted):** public listings and discovery records carry **node id
   + home relay URL only — never an IP**; direct (hole-punched) connections remain allowed for
   friend joins; public realms get a **relay-only switch, default ON**; peer addresses are never
   displayed in the UI; disclosures state that N0 + Nostr relays see the publisher IP and that a
   directly-connected player may learn it.
4. **Escape hatch, documented not operated:** a host can point their listing at a self-hosted
   `iroh-relay` URL. That is a listing field, not new protocol.

## Corrections the consult made to our earlier design (supersede the convergence doc)

- **NIP-33 parameterized-replaceable events (kind 30000–39999), NOT ephemeral kinds (20000–29999)**
  — ephemeral events are not stored; a late joiner would see nothing. "Ephemeral" behavior comes
  from the heartbeat + client-side freshness, not the kind.
- **NIP-40 expiration is draft/optional** — client-side rule is the source of truth: a listing is
  fresh only if `created_at` is within **10 minutes**. `expiration` tag is a hint; check NIP-11
  `supported_nips` before relying on it. Send **NIP-09 deletion on graceful shutdown**.
- **Browse filter = kind + `t` tag + `since`** (NIP-50 search is the wrong tool).
- Accountless extras: handle **NIP-42 AUTH** challenges (a key, not a signup); NIP-11 capability
  checks; NIP-13 PoW only if a chosen relay demands it; NIP-66 later.
- **Dedup on `(pubkey, d)` by newest `created_at`; verify signatures ourselves.**
- **Iroh pinned to 1.x** (1.0 shipped 2026-06-15; 1.3.0 2026-09-28; noq QUIC multipath;
  path-switch = multi-second stall, not zero-gap). Kotlin via Maven Central `computer.iroh:iroh`
  is single-platform (Windows desktop OK); **Android = NDK build of iroh-ffi** (aarch64, armv7) —
  the .so must be proven to load inside the real app before Stage 3 starts.
- **Discovery defaults stay `relay_only`** (iroh#4009): home IP is not published by default; DHT
  discovery stays off. A test must assert our published record carries no `addr=`.
- **Joiner-side realm-list rebuild:** the world endpoint rides the auth packet as a C-string
  `"ip:port"` whose layout differs across 1.12 / 2.4.3 / 3.3.5 — the joiner gateway must **parse
  and rebuild** that message (length fields included; same-length byte swap is not enough), with
  one fixture per flavor. NEVER set the host's realmlist row to 127.0.0.1 (breaks the shipped LAN
  path); rewrite happens ONLY in the joiner gateway, to loopback.
- **v1 refuses simultaneous host+join** on one instance (3724/8085 clash) rather than inventing a
  second port map.

## Frozen listing schema (Stage 1 output; fields may be null until their stage lands)

- kind **30388**, `d` tag `pocketrealm-v1`, `t` tag `pocketrealm` (distinctive, indexed).
- Tags: `["expiration", "<now+600>"]` (hint only), `["flavor", ...]`, `["timeframe", ...]`.
- content = JSON: `name`, `motd`, `flavor` + client build, `timeframe stage`, `rates`,
  `players {humans, bots}` (bots labeled, never padded), `join {type: "iroh", node: <32-byte id,
  relay: <home relay url>}` (null until Stage 3), `statusPageUrl` (the existing showcase tunnel,
  optional). **Never:** IP addresses, hostnames, geo, client-download URLs.
- Heartbeat: republish every **5 min** while the realm is up and public; NIP-09 delete on clean
  shutdown; no listing is ever published while visibility is OFF/FRIENDS.
- Relay set: 3–5 default public relays (damus.io, nos.lol, +2), user-editable; write to ALL, read
  from ALL, merge by event id.

## Campaign stages (each gated; spike failure stops the run)

1. **Nostr browser (A).** Shared `discovery/nostr/` Kotlin module (shared-sources.json, both
   platforms): `RealmListingEvent` (NIP-01 serialization + BIP-340 Schnorr via the Acinq
   secp256k1 KMP library — the one real dependency), `RelayPool` (OkHttp WebSockets, NIP-11/NIP-42
   aware), `ListingStore` (dedupe `(pubkey,d)`, 10-min client freshness, signature verify).
   Browser gains a real "Public realms" section (honest empty state = empty network). Host-side
   heartbeat publisher reuses the realm-status page's payload builder (one source of truth).
   Gates: NIP-01 golden vectors, signature round-trip, freshness/dedupe tests, mock-relay WS suite,
   focused gradle both platforms.
2. **Iroh spike (GATE).** Windows desktop via Maven `computer.iroh:iroh` 1.3.x; Android NDK build
   of iroh-ffi (aarch64 + armv7) proven loading **inside the real app**. Two-device/host+emulator
   echo: multi-MB transfer, direct-vs-relay outcome, path-change stall duration, long-lived-flow
   rate-limit behavior, one CGNAT/hotspot leg. Assert published discovery record has no `addr=`.
   PASS requires: .so loads in-app, byte tunnel works both transports, no hard rate-limit kill,
   measured stall recorded. **FAIL → campaign ends here with the measurements as the deliverable.**
3. **Gateway + join.** Host: mangosd stays loopback; Iroh connection muxes both ports (port id +
   raw bytes). Joiner: loopback listeners 3724/8085, realm-list parse/rebuild with the three
   per-flavor fixtures (CI test asserts the rewritten list is what the client would dial), fail
   the join loudly on dial/rewrite failure, refuse simultaneous host+join. Listings now carry the
   iroh `join` descriptor. Relay-only switch default ON for public realms.
4. **Reconnect proof.** Host public IP changes (interface toggle / re-dial) with the realm key
   unchanged; joiner redials the same endpoint id and reconnects; no new listing required.
5. **Gates + verdict.** check_repo, full desktop + android suites, focused live joins; one
   independent fresh-eyes read (privacy postures: no IP in any payload, relay-only default, no
   peer-IP display, disclosures present).

## Latency budget note (consult addition)

N0's regions do not include Oceania; two same-country players who both miss the punch can hairpin
continents. Fine for social realms; the spike's direct-vs-relay numbers decide what we promise in
UI copy. Nothing is promised as "LAN-like" until measured.
