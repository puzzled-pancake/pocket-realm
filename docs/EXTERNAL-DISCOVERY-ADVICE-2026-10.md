# External agent advice — internet discovery & join (October 2026 consult)

Pasted verbatim from the owner's external web agent, 2026-10-08. Consult input:
`docs/` option-review MD (the "Pocket Realm — internet multiplayer discovery & join" brief).
Owner endorsement: Option B with the two corrections below (recorded in
`docs/NOSTR-IROH-BUILD-PLAN.md`).

---

Option B is still the right architecture, with two corrections that change how you should build it. Ship the Nostr browser first, and treat N0's relay fleet as a vendor dependency rather than community infrastructure.

## Fact-check (as of October 2026)

**What still holds**

- **playit.gg** requires an account. Generic TCP and TCP+UDP are on the paid tier (Premium is about $3/month). Game presets and plain UDP can stay free; WoW is not a preset. Same rejection applies to ngrok, Portwarp, Pinggy, and Localtonet: accounts, and in ngrok's case no UDP on any plan. Sources: [playit.gg](https://playit.gg/), [Portwarp's August 2026 comparison](https://portwarp.com/compare/playit-gg-vs-portwarp).
- **Cloudflare Quick Tunnels** are accountless, HTTP(S) only, a new `trycloudflare.com` name every run, no uptime guarantee, and covered by Cloudflare's site terms. That matches how you use them for the status page only. Named tunnels can carry TCP, but the visitor must run `cloudflared access tcp` unless you buy Spectrum. Either way it is a companion or a paid account, so it does not beat B. Sources: [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [Tunnel routing](https://developers.cloudflare.com/tunnel/routing/).
- **bore.pub** is a bad public dependency. The project is alive, but the public broker has a history of phishing abuse and brownouts, and production CI has been migrating off it (for example [twentyhq/twenty#20459](https://github.com/twentyhq/twenty/pull/20459) in May 2026). Self-hosting bore is just option C with extra steps.
- **localtunnel / serveo** are not a long-lived raw-TCP game path.
- **Relay fallback is mandatory.** The specific "50–65% residential, <30% under CGNAT" figures are not what the best current measurement shows. Trautwein et al. (preprint Oct 2025, IMC 2026 version) measured libp2p DCUtR in IPFS: about **70% ± 7% conditional** hole-punch success, statistically the same for TCP and QUIC, over 4.4 million attempts. Relay reservation and address discovery failed on roughly **29%** of attempts before a punch was even tried, and the study could not separate CGNAT from single NAT. End-to-end direct success is therefore well under 70%. [arXiv:2510.27500](https://arxiv.org/abs/2510.27500), [IMC writeup](https://arxiv.org/html/2604.12484v1). Iroh's own June 2026 post claims **95–99% direct** "depending on use case and region." Treat that as a vendor figure, not a guarantee for mobile CGNAT.

**Iroh is more mature than a pre-1.0 review, and less "community" than the writeup says**

- **1.0 shipped 15 June 2026** (wire-protocol stability; official Python, Node, Swift, and Kotlin). **1.3.0 was 28 September 2026.** [1.0 post](https://iroh.computer/blog/v1), [1.3 post](https://www.iroh.computer/blog/iroh-1-3-0), [crates.io](https://crates.io/crates/iroh).
- Since March 2026 the QUIC stack is **noq with QUIC multipath**, not the old "juggle paths under a single QUIC connection" design. A connection can hold relay, IPv4, and IPv6 together and hot-swap. That is the right mechanism for Wi-Fi→cellular, but it is not zero-gap migration: expect a multi-second stall while the new path validates. [noq announcement](https://www.iroh.computer/blog/noq-announcement).
- **Kotlin is official, Android is not a one-line Gradle dependency.** Maven Central has `computer.iroh:iroh` (docs still show `1.0.0`). The published artifact is single-platform and does **not** ship an Android multiplatform build. Android (`aarch64`, `armv7`) means an NDK build of [iroh-ffi](https://github.com/n0-computer/iroh-ffi). Windows x86_64 is a supported Kotlin target. The FFI surface is the stable 1.0 endpoint/connection/stream API, which is enough for a byte-stream tunnel. [Kotlin docs](https://docs.iroh.computer/languages/kotlin), [language matrix](https://docs.iroh.computer/languages).
- **Public relays are operated by N0, Inc., not by a community.** They are permissionless, unauthenticated, and end-to-end encrypted, in US West, US East, Europe Central, and Singapore. N0's own docs call them **development and hobby only**: no SLA, rate limits that can change, abuse blocking, only the latest stable release is officially supported, and relays see source/dest IP, timing, and byte counts. [Public relays](https://docs.iroh.computer/iroh-services/relays/public), [FAQ](https://docs.iroh.computer/about/faq). v0.35 public-relay support ends **31 Dec 2026**; 0.9x/rc support already ended 30 Sept 2026. Pin 1.x.
- **Default discovery does not publish the home IP.** After the fix for iroh#4009, `PkarrPublisher` defaults to `AddrFilter::relay_only` (home relay URL only). Publishing `addr=` is opt-in. DHT discovery is off by default. Keep both defaults and add a test that your own TXT record has no `addr=`. A **successful hole-punch still reveals the host IP to that joiner's kernel.** That is the remaining constraint-2 hole. [Commit](https://github.com/n0-computer/iroh/commit/b3da9402f48c876f0dd60c9014f7c3443de23b6b).
- **Nostr**
  - **NIP-33 parameterized-replaceable events (kinds 30000–39999) are the right primitive** and are widely implemented. One event per realm, same `d` tag, heartbeat replaces it. Do not use ephemeral kinds (20000–29999); they are not stored, so a joiner who connects a minute later sees nothing.
  - **NIP-40 is still `draft` / `optional`.** The spec says relays **may keep expired events forever**, and only *should* stop serving them. [strfry documents NIP-40](https://github.com/hoytech/strfry) (and damus, nos.lol, and primal run strfry), but plenty of relays do not advertise 40. Client-side rule is the source of truth: drop listings whose `created_at` is older than your TTL. Use the `expiration` tag only as a hint, and check NIP-11 `supported_nips` first. Also send a **NIP-09 deletion** on graceful shutdown.
  - **NIP-50 search is the wrong tool.** It is optional, ranking is relay-defined, and most relays don't implement it. Browse with a normal filter: kind + your app `t` tag + `since`. Single-letter tags are indexed.
  - **NIP-98 is HTTP `Authorization` signing.** Irrelevant unless you add an HTTP API.
  - Worth adding, still accountless: **NIP-42 AUTH** (some relays require a signed challenge to write; that is a key, not a signup) and **NIP-11** for capability checks. **NIP-13 PoW** only if a chosen relay demands it. **NIP-66** is a later improvement for picking live relays instead of a hardcoded list.
  - There is no hard global kind registry. Pick an unused 3xxxx kind **and** require a distinctive tag. Dedup on `(pubkey, d)` by newest `created_at`. Verify signatures yourself.
  - Latency and retention are fine for this: a publish/query against a healthy relay is typically well under a second; replaceable storage plus a 5-minute heartbeat does not depend on long retention. Partitioning is the real failure mode, so write and read **all** of a small relay set, not one.
  - Moderation risk is real but narrow. A JSON blob with a name, rates, patch string, and a node id is not copyrighted client data, and most relays will not understand the kind. A relay can still refuse a kind, require payment, or drop you if the listing looks like a warez pointer. Do not put client-download URLs in the event. Spread across several relays. A directory **you** operate (option F) is a much easier takedown target than five independent relays.

**Hole in the loopback-gateway sketch**

CMaNGOS/MaNGOS puts the world endpoint in the realm-list packet as a **C string** `"ip:port"`, and the layout differs across 1.12, 2.4.3, and 3.3.5. The stock client then dials that address. It will not dial your gateway unless you **parse and rebuild** that one auth message (length fields included; a same-length byte swap is not enough). Do this only in the joiner gateway, and only rewrite to loopback. Do not set the host `realmlist` row to `127.0.0.1` or you break the LAN path you already shipped. `realmlist.wtf` pointing at `127.0.0.1` is a config edit, not a client patch. Budget a simultaneous host+join port clash (3724/8085 already taken locally); v1 can refuse that case.

## Comparison

| Option | 1 No accounts | 2 No home IP in public data | 3 No infra we operate | 4 Identity survives IP change | 5 Stock client | 6 Win + Android | 7 Fail-closed | Stranger can actually play |
|---|---|---|---|---|---|---|---|---|
| A Nostr only | Yes | Yes | Yes | Key can be stable, but play still needs an address | Doesn't provide the join | Yes, pure Kotlin | Yes | No |
| **B Nostr + Iroh** | Yes | Listing yes if you keep relay-only discovery. **Direct path leaks IP to that joiner** | Yes at v1, but relays are **N0's**, not a community | Yes, endpoint key | Yes, with companion + realm-list rewrite | Yes; Android is an NDK build | Yes | Yes |
| C Nostr + your TCP relays | Yes | Better than direct B: joiner sees the VPS, not the house, if you do not send PROXY headers onward | **No** | Yes, if the public port is pinned to the realm key | Yes, even with no companion | Agents exist | Yes | Yes |
| D Friend gossip | Yes | Best | Yes | Yes, if paired with Iroh | Yes | Yes | Yes | **No** |
| E Volunteer directory | Yes, if the directory itself has no accounts | Yes, if it stores node ids only | Yes, once volunteers exist | Yes | Depends on the transport under it | Yes | Yes | Only after someone runs it |
| F Directory we operate | Can be accountless | Yes if careful | **No** | Yes | Depends on transport | Yes | Yes | Yes, until a complaint or a bill |
| Commercial tunnels (playit-class) | **No** | Yes (their IP is what's advertised) | Yes | Only while they keep your hostname | Yes, no companion | Agents vary | Their outage looks like your outage | Yes, if you pay and accept ToS |
| libp2p DCUtR + circuit relay | Yes | Same peer-IP issue as B | Only if you use public IPFS relays, which are not a game-traffic service | Yes, peer id | Same companion problem | Heavier than Iroh 1.0, which already did this integration | Yes | Yes, after you assemble it |

Constraint 5 already allows a companion, so C's "stock client dials a public port" advantage is real but small next to operating an open TCP proxy (abuse, bandwidth, a single legal target). D fails the product goal. E has no network effect yet; Nostr already is that directory. F is the simplest build and the worst takedown target for a public list of private realms.

## Constraints to change, and what was missing

- **Narrow constraint 2.** "Never in a public listing or discovery record" is achievable and worth keeping. "Never on the join path" forbids hole-punching, because the other player's OS sees the source address. Recommended policy: public listings and Pkarr/DNS carry **node id + home relay URL only**; direct connect is allowed for friend joins; public realms get a **relay-only switch**, default on if you want the strict reading; never print peer IPs in the UI.
- **Keep constraint 3 for the directory. Don't make it a religion for one relay process.** A dumb encrypted relay is legally and operationally colder than a directory of realm names. v1 should not run one. The escape hatch should be "host can point at an `iroh-relay` they run," not "we run a pool."
- **Keep constraint 1.** It is what correctly kills playit, Tailscale, ngrok, and Portwarp.
- **Add a latency budget.** N0's published regions are US-West, US-East, Europe-Central, and Singapore. Two players in the same country who both miss the punch can hairpin through another continent. Fine for a social realm, bad if you are promising LAN-like combat. Measure before you promise.
- **Nothing new in 2025–2026 beats this pair for these constraints.** Iroh 1.0 is the thing that showed up. Tailscale still wants accounts (Headscale means you operate the control plane) and still fights Android VPN mode. Mainline DHT/BEP44 is still lookup-not-search, and still the wrong place to put a home IP.

## Recommendation

**Build B, in this order. Do not start with the Rust embed.**

1. **Freeze the listing schema and ship A.** Addressable kind, fixed `d`, app tag, content = name, patch/flavor, rates, MOTD, bots-vs-humans, **Iroh endpoint id, home relay URL**. No IP, no direct `addr`. Freshness = `created_at` within 10 minutes, checked on the client. NIP-09 on clean shutdown. Query every relay in a set of 3–5, merge, verify signatures. Empty means empty. Join stays on the paths you already have (LAN, manual tunnel). This is days of Kotlin and it is not throwaway.
2. **Spike Iroh before you commit the roadmap.** Windows via the Maven artifact, Android via the NDK build. Two devices: echo a few MB, record direct-vs-relay, Wi-Fi→cellular stall, and whether a fat long-lived flow gets rate-limited. Include a CGNAT or phone-hotspot leg. Assert the published discovery record has no `addr=`.
3. **Gateway.** Host keeps mangosd on loopback; Iroh accepts and muxes both ports over one connection (port id, then bytes). Joiner listens on loopback and rewrites only the realm-list string, with fixtures for 1.12, 2.4.3, and 3.3.5. Fail the join if dial or rewrite fails. Do not invent a listing.
4. **Reconnect test.** Change the host's public IP without changing the key. Joiner redials the same endpoint id. No new listing required, because the listing never had the IP.
5. **Escape hatch, documented not operated.** If the spike shows throttling or unacceptable hairpin latency, a self-hosted `iroh-relay` URL in the listing is the fix. That is a config change, not a new discovery protocol.

**Top three risks**

1. **Casual doxx via the direct path, or a bad discovery default.** Mitigation: keep `AddrFilter::relay_only`, leave DHT discovery off, put only relay URL + endpoint id in Nostr, offer relay-only for public realms, and don't display peer addresses. Disclose that N0 and the Nostr relays see the publisher IP, and that a direct player may too.
2. **N0's public fleet is a hobby service with no Oceania region and changeable rate limits.** A world server is a long-lived flow, unlike the chat apps those relays are sized for. Mitigation: the spike in step 2 is a gate, not a demo. Ship the relay URL inside the listing so you can leave the fleet without renumbering realms. Budget an upgrade whenever they drop a 1.x minor from the public relays.
3. **Realm-list rewrite plus the Android native build.** Mitigation: three packet fixtures and a CI test that a rewritten list is what the client would dial; prove the Android `.so` loads inside the real app before you call B "in progress." v1 may refuse "host and join at the same time" rather than invent a second local port map.
