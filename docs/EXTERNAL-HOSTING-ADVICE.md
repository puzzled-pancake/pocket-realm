Agent A:
I dug into the current state rather than relying on the older “use Playit/ngrok/Cloudflare” advice. The key conclusion is:

> **There is no mature, accountless, origin-IP-hiding, stable-address, stock-client-only service that cleanly satisfies all four constraints today.**
>
> For your particular app, the strongest architecture is therefore **a tiny application-owned relay layer + a serverless discovery layer**, with direct/NAT-traversed connections used opportunistically underneath it.

That sounds more complicated than it is. Your 1–10-player scale makes this unusually tractable.

---

# 1. The fundamental constraint

Your protocol is TCP and the stock client expects:

```text
realm.example:3724
```

It cannot:

* perform ICE/STUN,
* participate in NAT hole punching,
* speak a VPN protocol,
* understand a relay protocol,
* reconnect through a changed endpoint by itself.

So you have exactly three ways to make it work:

| Architecture                |       Stock client | Origin IP hidden | CGNAT |               Stable reconnect |
| --------------------------- | -----------------: | ---------------: | ----: | -----------------------------: |
| Port forwarding             |                  ✅ |                ❌ |     ❌ |               ⚠️ IP may change |
| Relay → public `host:port`  |                  ✅ |                ✅ |     ✅ | ✅ if relay allocation persists |
| Companion forwarder → relay | ❌, but tiny helper |                ✅ |     ✅ |                              ✅ |
| P2P TCP hole punch          |   ❌ without helper |                ❌ |    ⚠️ |                             ⚠️ |
| VPN/mesh                    |                  ❌ |          Usually |     ✅ |                              ✅ |

The relay is the important primitive.

A relay does not have to be a giant commercial service. For your scale, a **single $3–10/month VPS can support a surprisingly large number of these realms** because WoW traffic is relatively modest. `rathole`, for example, explicitly uses exactly this architecture: an outbound connection from the NATed client to a server with a public IP, with TCP/UDP traffic forwarded through it. ([GitHub][1])

---

# 2. Current prior art

## Playit.gg

This is probably the first thing people will recommend to you, and historically it was a very good answer.

But **its current state matters enormously**.

Playit currently describes itself as a global proxy that allows game hosting without port forwarding or exposing the home IP. It supports custom TCP/UDP in principle. ([Playit][2])

However, its current product has changed:

* Playit now has an account/claim system.
* The current agent has explicit agent claiming and secret provisioning.
* The setup flow generates a browser claim URL.
* Its public site currently says **“Create Free Account.”**
* More importantly for your protocol, **TCP tunnels are currently a Premium feature**.

The latter change happened around late 2025 and is still reflected in current Playit documentation in 2026. Their September 2026 documentation explicitly says TCP/UDP tunneling requires Premium for the affected Minecraft use case, and current game-server documentation likewise says TCP is Premium. ([Playit][3])

The current agent source also makes the account/agent relationship explicit: agent setup involves a claim URL and secret, and the code includes account login/guest-account handling. ([GitHub][4])

### Verdict

| Requirement               | Playit today                       |
| ------------------------- | ---------------------------------- |
| No signup                 | ❌                                  |
| Hide home IP              | ✅                                  |
| CGNAT                     | ✅                                  |
| Stable endpoint           | ✅/⚠️                               |
| Stock WoW client          | ✅ for TCP when available           |
| Free TCP                  | ❌ currently                        |
| Friend installs anything  | ❌                                  |
| TOS suitability for games | ✅ — games are explicitly supported |

Their TOS is actually unusually friendly to your use case: normal multiplayer game servers are explicitly listed as allowed. ([Playit][5])

**But it fails your highest-priority requirement.**

I would therefore *not* build your architecture around Playit.

---

# 3. e4mc is a very good architectural reference

e4mc is probably one of the most relevant pieces of prior art.

It does essentially this:

```text
Minecraft LAN
     │
     ▼
 e4mc mod
     │
     │ outbound tunnel
     ▼
 public relay
     │
     ▼
 temporary public hostname
     │
     ▼
 vanilla Minecraft client
```

The important UX is:

> Host installs one thing.
> Friend installs nothing.
> Host presses “Open to LAN.”
> A public domain appears.
> Friend enters the domain.

That is extremely close to what you want.

And e4mc remains active in 2026: its current project is still being updated and supports Fabric, Forge, NeoForge and Quilt. ([CurseForge][6])

It explicitly says the joining client does **not** need e4mc; it simply receives a public domain. ([CurseForge][6])

### The important limitation

It's a **temporary reverse tunnel**, not a permanent identity layer.

So the useful thing to copy is:

> **“Integrate the relay into the game rather than asking the user to understand networking.”**

Not necessarily its exact tunnel infrastructure.

---

# 4. Essential Mod

Essential Mod is another very useful reference.

Essential's current player-hosting system lets a host select a world, select friends, and host it without running a conventional dedicated server. ([Essential Mod][7])

It has historically used regional proxies when direct connections aren't possible. ([Essential Mod][8])

This gives you another important UX lesson:

```text
Don't expose networking terminology to the player.

"Invite Friend"
        ↓
Connection Manager
        ↓
direct / relay / NAT traversal
```

That is exactly the abstraction your app wants.

---

# 5. Cloudflare Quick Tunnels

This one is frequently suggested incorrectly.

Current Cloudflare documentation is extremely clear:

> Quick Tunnels are **temporary**.

They generate:

```text
random-name.trycloudflare.com
```

and:

* don't require a Cloudflare account,
* don't require a domain,
* stop working when `cloudflared` stops,
* generate a different hostname each time,
* have no uptime guarantee,
* are intended for testing/development. ([Cloudflare Docs][9])

So:

### Quick Tunnel

```text
start
 ↓
abc.trycloudflare.com

stop
 ↓
dead

start again
 ↓
xyz.trycloudflare.com
```

That fails your reconnect requirement.

### Named Cloudflare Tunnel

A proper Cloudflare Tunnel gives you the stable hostname:

```text
realm.example.com
```

and survives connector restarts.

But:

* requires an account,
* requires a configured domain/zone,
* is therefore outside your default architecture.

Cloudflare's current docs also say non-HTTP TCP services require a client-side `cloudflared` for the TCP access model. ([Cloudflare Docs][10])

There is another subtle issue: Cloudflare distinguishes its public HTTP proxy from TCP Tunnel functionality. Ordinary Cloudflare DNS proxying does **not** proxy game-server TCP traffic; Cloudflare explicitly says game servers/non-HTTP services must be DNS-only unless using a product such as Spectrum. ([Cloudflare Docs][11])

### TOS / risk

I wouldn't characterize Cloudflare as having a blanket “game servers are banned” policy.

The more relevant problem is **product suitability**:

* Quick Tunnels are documented as development/testing infrastructure.
* They aren't the stable production TCP product.
* A WoW realm is a long-lived arbitrary TCP service, exactly the sort of thing you shouldn't build on an explicitly temporary testing feature.

So:

**Good development tool. Bad foundation for your production architecture.**

---

# 6. LocalTunnel

LocalTunnel is still alive.

Its current client documentation says:

```bash
lt --port 8000
```

and gives a public `*.localtunnel.me` address. The URL lasts for the duration of the tunnel session. It can reconnect to a restarted **local server**, but that is not the same thing as the tunnel surviving a client/agent disconnect. ([GitHub][12])

You can request a subdomain:

```text
--subdomain myrealm
```

but the documentation explicitly warns that you may not receive the requested name. ([GitHub][13])

More importantly, the project has a nontrivial history of operational issues. Current GitHub issues include open reports involving LocalTunnel availability and behavior, including an issue from September 2026. ([GitHub][14])

### Verdict

**Interesting for experimentation, not something I'd make a core dependency for a game realm.**

It also isn't designed specifically around arbitrary persistent TCP game servers.

---

# 7. Serveo

I would remove Serveo from your candidate list.

The historical “just SSH to serveo.net” advice is obsolete. It isn't a serious current foundation for a new application.

---

# 8. bore / bore.pub

This one is much more interesting.

bore is explicitly designed as:

> a simple TCP tunnel that exposes local ports through a remote server.

Its public server is:

```text
bore.pub
```

and the basic operation is:

```text
bore local 3724 --to bore.pub
```

The public endpoint becomes:

```text
bore.pub:<assigned-port>
```

The project is actively maintained; current release is v0.6.0. It has Windows binaries, ARM binaries, etc. ([GitHub][15])

### This is very close to your requirements

* no user account,
* home IP hidden,
* arbitrary TCP,
* tiny client,
* stock client can connect,
* CGNAT works because the host initiates the tunnel.

But there are two major problems.

### Problem 1: stability

The public port is allocated by the server.

There are current open feature requests for:

* automatic reconnection,
* retaining the same port,
* handling reconnects more gracefully. ([GitHub][16])

There have also been public-server outages reported in 2026. ([GitHub][17])

So:

**Excellent proof-of-concept. Not sufficiently robust as your production connectivity substrate.**

### Problem 2: you don't control the relay

Your entire public realm network would depend on:

```text
bore.pub
```

remaining available and appropriate.

That's a bad dependency for an application whose goal is to be self-hosted.

---

# 9. rathole

This is probably the most useful open-source relay implementation for your architecture.

rathole is specifically designed for NAT traversal using a server with a public IP.

Architecture:

```text
                 Internet
                    │
             ┌──────▼──────┐
             │  rathole VPS │
             │ public IP    │
             └──────┬──────┘
                    │
             persistent TCP
                    │
             ┌──────▼──────┐
             │ home host   │
             │ behind NAT  │
             └─────────────┘
```

It supports:

* TCP,
* UDP,
* TLS,
* Noise,
* authentication tokens,
* heartbeats,
* reconnect intervals.

Its default client retry interval is only 1 second. ([GitHub][1])

It also explicitly recommends TCP_NODELAY for interactive applications. ([GitHub][1])

### The downside

You need:

```text
your VPS
```

This is not a hosted service.

But that may actually be **the right answer for your project**.

You could operate:

```text
relay1.yourproject.net
relay2.yourproject.net
relay3.yourproject.net
```

and your application silently chooses one.

Users never sign up.

The application developer bears the infrastructure cost.

For 1–10 players per realm, this is entirely reasonable.

---

# 10. frp

frp is the larger, more feature-rich equivalent.

Current frp supports TCP, UDP and multiple transport mechanisms. ([GitHub][18])

It also has an interesting **XTCP P2P mode**:

```text
             frps
              │
         rendezvous/STUN
          /           \
         /             \
       host             friend
         \             /
          \___________/
             direct
```

But crucially:

> **frps is still required.**

The relay is used for rendezvous/NAT traversal even when the actual data path becomes direct. ([GitHub][18])

That's an excellent model for your application.

The relay doesn't necessarily have to carry the whole WoW session.

---

# 11. TCP hole punching is real

There's a common misconception that TCP hole punching is impossible.

It isn't.

RFC 5382 explicitly describes TCP simultaneous-open NAT traversal, where both endpoints send SYNs and establish a TCP connection through their NATs. 

There is also real empirical evidence: the classic Ford/Srisuresh/Kegel study measured approximately **64% TCP hole-punching success** across the NATs they tested. ([arXiv][19])

More interestingly, modern libp2p research is substantially better.

A 2026 large-scale study of **4.4+ million** IPFS DCUtR traversal attempts found approximately:

**70% ± 7.1%**

conditional success for the hole-punching phase, with TCP and QUIC showing statistically similar success in that system. ([arXiv][20])

So TCP P2P is absolutely worth implementing as an optimization.

But:

> **It is not a complete connectivity strategy.**

You need a fallback.

---

# 12. STUN isn't a relay

This distinction is critical.

STUN can tell:

```text
my local socket:
192.168.1.50:3724

STUN sees:
203.0.113.72:51423
```

The public address is called the reflexive transport address. That's exactly what STUN is designed to reveal. ([RFC Editor][21])

But STUN does **not**:

```text
203.0.113.72:51423
        │
        ▼
    Internet
        │
        ▼
    your PC
```

It doesn't forward the traffic.

So:

```text
STUN = "what address does the Internet see me as?"

TURN/relay = "move my traffic for me"
```

This makes public STUN servers useful because they're normally accountless.

They do **not**, however, satisfy your privacy requirement by themselves.

If you publish the STUN-discovered address to strangers:

> you've just published the home public IP.

---

# 13. CGNAT makes this even more important

With ordinary NAT:

```text
PC → home router → ISP → Internet
```

you may be able to punch a hole.

With CGNAT:

```text
PC
 ↓
home router NAT
 ↓
ISP CGNAT
 ↓
Internet
```

you have another translation layer you don't control.

Incoming connections are generally impossible unless the ISP's CGNAT happens to support the necessary behavior.

That is exactly why systems such as libp2p combine:

```text
AutoNAT
+
relay
+
hole punching
```

rather than betting everything on hole punching. ([libp2p Docs][22])

---

# 14. The modern libp2p architecture is extremely relevant

This is probably the most interesting technical prior art for your project.

libp2p's current architecture is roughly:

```text
             Discovery
                 │
             Kademlia
                 │
          ┌──────┴──────┐
          │             │
       AutoNAT       Relay
          │             │
          └──────┬──────┘
                 │
             DCUtR
                 │
          TCP/QUIC direct
```

If direct connectivity fails:

```text
host ───────── relay ───────── friend
```

If hole punching succeeds:

```text
host ─────────────────────── friend
```

libp2p explicitly describes this workflow. ([libp2p Docs][22])

And Circuit Relay v2 is deliberately designed so that the relay does not need to understand the application protocol. Traffic remains end-to-end encrypted at the libp2p layer. ([libp2p][23])

---

# 15. But don't put libp2p directly in front of the WoW client

This is an important architectural distinction.

Your game client speaks:

```text
TCP
SRP6
WoW protocol
```

libp2p speaks things such as:

```text
TCP/QUIC
Noise/TLS
multistream
yamux
libp2p protocols
```

You can't simply tell a stock WoW client:

```text
realm = /p2p/12D3...
```

Instead use libp2p-like networking **underneath your own forwarding layer**.

For example:

```text
Stock WoW client
      │
      │ TCP localhost:3724
      ▼
Tiny companion
      │
      │ relay/direct transport
      ▼
Realm host
      │
      │ TCP localhost:3724
      ▼
WoW server
```

The WoW client doesn't know any of this exists.

---

# 16. JVM / Android availability

This is where libp2p gets interesting but also dangerous.

The JVM implementation exists:

jvm-libp2p

It has implementations/prototypes for:

* TCP,
* QUIC,
* Noise,
* TLS,
* circuit-relay-v2,
* AutoNAT,
* Kademlia,
* gossipsub,
* mDNS.

([GitHub][24])

And it explicitly has an Android example.

However, its own documentation says:

> Android compatibility is a development goal, but they aren't aware of anyone using it in production.

That's a major distinction. ([GitHub][24])

Current release history also shows that the JVM project is moving, but it's not nearly as mature as go-libp2p. ([GitHub][25])

### Therefore

For your actual app:

**Don't make JVM libp2p a hard dependency for the first implementation.**

It is excellent research material.

For Android production, I would seriously consider:

```text
Rust networking core
        │
        ├── Windows
        └── Android/JNI
```

or a purpose-built small Kotlin networking implementation.

---

# 17. Mainline DHT has a better JVM story

For a classic Mainline DHT, you have real Java implementations.

For example:

mldht is an actual Java implementation of BEP 5 Kademlia DHT. ([GitHub][26])

There is also the Java `Bt` project, which implements BEP 5 and provides a DHT module. Its latest Maven Central version is 1.10. ([GitHub][27])

And BiglyBT has an Android application with a Mainline DHT plugin. ([GitHub][28])

So if your question is:

> “Can I actually run a Mainline DHT implementation on Android/JVM?”

**Yes.**

That part is not a research project.

---

# 18. But Mainline DHT is NOT a public realm directory

This is one of the biggest traps in the proposed architecture.

BitTorrent DHT fundamentally does:

```text
known key
   ↓
find peers associated with key
```

For example:

```text
realm-ID → providers
```

BEP 5's `get_peers` operation takes an `info_hash` and returns peers associated with that hash. ([BitTorrent][29])

It does **not** naturally provide:

```text
give me every public realm in the world
```

Kademlia is a lookup mechanism, not a search engine.

There is even a separate BEP 51 proposal specifically dealing with sampling infohashes because ordinary DHT clients aren't supposed to enumerate the network. ([BitTorrent][30])

So:

### Excellent

```text
I know Realm ID = ABC123
       ↓
DHT
       ↓
where is ABC123?
```

### Bad

```text
I want:
all public WoW realms
```

That's not what the DHT naturally gives you.

---

# 19. Mainline DHT has another privacy problem

BEP 5's `announce_peer` explicitly tells the DHT node to store:

```text
IP address
+
port
```

of the announcing peer. ([BitTorrent][29])

So if your host does:

```text
DHT announce
    ↓
Realm 1234
    ↓
203.0.113.72:3724
```

you have defeated your own privacy design.

A stranger querying the DHT can eventually discover the origin address.

### The solution

Never announce the host.

Announce the **relay**.

For example:

```text
DHT record:

realm_id:
    256-bit public key

name:
    Azeroth Test Realm

relay:
    relay3.example.net:41271

realm public key:
    ABC...

signature:
    ...
```

Then:

```text
DHT
 │
 └── relay address
          │
          ▼
      relay
          │
          ▼
      home host
```

The DHT never needs to know the home IP.

---

# 20. BEP 44 makes this substantially better

Mainline DHT's BEP 44 supports arbitrary signed data.

A mutable record is controlled by an Ed25519 public key and signature, and values can be updated with sequence numbers. ([BitTorrent][31])

That gives you a very nice realm record:

```text
realm_key = SHA1(host_public_key + "realm")

value:

{
    version: 42,
    name: "Azeroth",
    protocol: "wow-3724",
    relay: "r3.example.net:41271",
    capabilities: ["tcp"],
    expires: 3600,
    ...
}

signature: Ed25519(host_private_key)
```

Now a malicious DHT node can say:

> “Realm X is at evil.example:3724”

but it cannot forge the host's signature.

That is much better than blindly trusting DHT announcements.

---

# 21. The catch: DHT discovery still needs an index

You now have:

```text
Realm ID → DHT → relay endpoint
```

Excellent.

But public discovery requires:

```text
"show me interesting realms"
```

That's fundamentally different.

I would solve this using **two levels of discovery**.

### Level 1 — serverless identity lookup

DHT:

```text
realm-id → signed realm descriptor
```

### Level 2 — opt-in public beacons

Optional community sites:

```text
realm beacon
     │
     ├── realm ID
     ├── name
     ├── description
     ├── version
     └── DHT key
```

The beacon does **not** own the realm.

It is merely an index.

This is similar to the distinction between:

```text
DNS
```

and:

```text
search engine
```

You can have multiple independent community directories without making your networking protocol centralized.

---

# 22. Friend-graph gossip is actually better for friends

For private realms, don't use the global DHT at all.

Suppose Alice has Bob as a friend.

Alice starts:

```text
Realm X
```

She announces to Bob:

```text
Realm X
relay endpoint
realm public key
signature
```

Bob then tells Charlie.

You get:

```text
Alice
 ├── Bob
 │    ├── Charlie
 │    └── Dave
 └── Eve
      └── Frank
```

This is **peer exchange / gossip**.

It has a very nice property:

> private realm announcements naturally follow the social graph.

You can make the announcement:

```text
encrypted to friend
```

or simply:

```text
signed + access-controlled
```

No global directory.

No DHT.

No public visibility.

---

# 23. mDNS/NSD should absolutely be your LAN solution

For LAN:

```text
_mygame._tcp.local
```

or similar.

Windows can use mDNS/Bonjour-compatible discovery; Android provides `NsdManager`.

Android's current NSD APIs support service advertising and discovery, and Android's newer local-network permission model now matters on newer Android versions. ([Android Developers][32])

So the LAN UX can simply be:

```text
Realm Browser

LOCAL
────────────────
Azeroth        192.168.1.42:3724
Molten Core    192.168.1.83:3724
```

No account.

No Internet.

No relay.

No IP disclosure beyond the LAN.

---

# 24. Steam Datagram Relay is excellent prior art — but unusable directly

Valve's Steam Datagram Relay architecture is almost exactly what you want conceptually.

Valve says SDR:

* routes game traffic through Valve's network,
* protects servers/players by hiding IP addresses,
* authenticates traffic,
* encrypts traffic,
* rate-limits it,
* can improve routing,
* works for both P2P and dedicated servers. ([Steamworks][33])

For P2P games, Valve has a signaling system used to negotiate the connection.

So conceptually:

```text
Game A
   │
   ├── signaling
   ▼
Valve infrastructure
   │
   ├── direct if possible
   └── relay otherwise
```

This is almost precisely the design I would build.

### Why you can't reuse it

Because SDR is part of Steamworks.

Your standalone client doesn't get to use Valve's relay network as an arbitrary third-party transport.

So:

> **Copy the architecture, not the infrastructure.**

---

# 25. Recommended architecture

Here's what I'd actually build.

## Tier 0 — Solo

Nothing.

```text
WoW client
   ↓
127.0.0.1:3724
   ↓
local realm
```

No networking whatsoever.

---

# 26. Tier 1 — LAN

Use mDNS/NSD.

```text
Host
 │
 ├── advertises _realm._tcp.local
 │
 ▼
LAN
 │
 └── friends discover it
```

Stock client gets:

```text
192.168.1.42:3724
```

### Friend friction

**Zero.**

They select:

> LAN Realm → Azeroth

### Reconnect

Very good.

If the host's DHCP address changes:

```text
192.168.1.42
      ↓
192.168.1.77
```

mDNS updates the record.

Don't cache the raw IP as the identity.

---

# 27. Tier 2 — Friends over Internet

This should be your main feature.

Use:

```text
tiny companion networking layer
+
relay pool
+
optional hole punching
```

Architecture:

```text
                    ┌───────────────┐
                    │ Relay         │
                    │ public IP     │
                    │ realm slots   │
                    └───────┬───────┘
                            │
             outbound       │       outbound
               tunnel       │
                 │          │
          ┌──────▼─────┐    │
          │ Host       │    │
          │ NAT/CGNAT  │    │
          └────────────┘    │
                            │
                       Friend helper
                            │
                            ▼
                       localhost:3724
```

The helper creates:

```text
127.0.0.1:random
```

and forwards it to the relay.

The stock client connects to:

```text
127.0.0.1:xxxxx
```

### But you can make this almost invisible

Your app can launch the helper automatically.

The friend sees:

```text
Azeroth
[ Join ]
```

Internally:

```text
spawn helper
bind localhost
launch WoW with realmlist
```

So the actual friend friction is:

> **Install your app once.**

Not:

> Install Tailscale
> Create account
> Join network
> Accept invitation
> Install driver
> Reconfigure WoW

That's a massive UX improvement.

---

# 28. Don't actually require the relay for every connection

Make the connection state machine:

```text
                    ┌─────────────┐
                    │ Realm found │
                    └──────┬──────┘
                           │
                           ▼
                  Can direct-connect?
                     /          \
                   yes           no
                   │              │
                   ▼              ▼
               TCP direct      Relay
```

More specifically:

```text
1. LAN
2. IPv6 direct
3. TCP hole punch
4. relay
```

For Internet friends, you can also use STUN/AutoNAT-style probing to determine whether direct TCP is plausible.

The user never sees this.

---

# 29. Tier 3 — Public realm

Here I would **not** use the friend companion as a requirement.

The public endpoint should be:

```text
realm-id.example-relay.net:41271
```

or simply:

```text
relay3.example.net:41271
```

The stock WoW client can connect directly.

Architecture:

```text
Stranger
   │
   │ TCP
   ▼
public relay
   │
   │ private outbound connection
   ▼
host
```

Home IP:

**never exposed.**

This is exactly the property you're after.

---

# 30. Stable addressing is the most important relay feature

Don't make:

```text
host reconnect
     ↓
new random port
     ↓
new address
```

Instead give every realm a cryptographic identity:

```text
realm_id = SHA-256(public_key)
```

Then your relay system maintains:

```text
realm_id
    ↓
virtual endpoint
    ↓
current host connection
```

Example:

```text
realm ID:
7F4B...A921

public endpoint:
r7.example.net:41271
```

The host can disappear:

```text
12:31 host disconnects
```

and return:

```text
12:35 host reconnects
```

The relay reconnects the existing virtual realm:

```text
41271 → new host tunnel
```

The public address never changes.

---

# 31. This is where rathole/frp are useful but insufficient by themselves

`rathole` gives you:

```text
persistent client → server connection
```

and good reconnect behavior.

But it doesn't give you your entire product:

```text
Realm identity
Realm ownership
Public realm discovery
Friend graph
Access control
Stable virtual realm allocation
DHT records
```

So don't expose rathole's configuration directly to users.

Embed the concept.

Your app should have something like:

```text
RealmNetworkManager

    createRealm()
    publishRealm()
    connectToRealm()
    stopRealm()
```

Underneath:

```text
direct TCP
STUN
hole punch
rathole/custom relay
```

---

# 32. Public discovery: my recommendation

I'd use **three independent mechanisms**.

## A. LAN

```text
mDNS / NSD
```

Excellent.

---

## B. Friends

```text
signed friend-graph gossip
```

Excellent.

No public listing.

---

## C. Public

```text
signed DHT realm records
+
optional beacon/index sites
```

Good.

But don't pretend the DHT itself is a global searchable realm list.

---

# 33. A practical DHT record

I'd define something like:

```json
{
  "protocol": "wow-realm/1",
  "realm_id": "b3:...",
  "name": "Azeroth",
  "description": "Casual vanilla realm",
  "game_version": "1.12.1",
  "login_port": 3724,
  "relay": {
    "host": "r3.example.net",
    "port": 41271
  },
  "capabilities": [
    "tcp-relay",
    "stock-client"
  ],
  "expires": 1770000000,
  "seq": 19
}
```

Then:

```text
Ed25519 signature
```

covers the entire record.

---

# 34. Do NOT put the home address in that record

Bad:

```json
{
  "host": "203.0.113.72",
  "port": 3724
}
```

Good:

```json
{
  "relay": "r3.example.net:41271"
}
```

That is the single most important privacy rule in the system.

---

# 35. DHT privacy isn't perfect even with a relay

Even if the DHT record contains only:

```text
relay3:41271
```

the DHT itself sees:

```text
publisher → DHT nodes
```

So someone can potentially correlate:

```text
realm announcement
+
timing
+
network behavior
```

You shouldn't market this as anonymity.

It's:

> **origin-IP protection**

not:

> **publisher anonymity**

Those are very different properties.

---

# 36. The public relay itself can see the origin

The relay necessarily knows:

```text
host → relay
```

and therefore knows the host's network source IP.

That's unavoidable for a conventional relay.

The privacy property is:

```text
random Internet user
       ↓
relay
       ↓
host
```

not:

```text
nobody anywhere knows host IP
```

A commercial relay operator or your own relay operator can know it.

That's an important privacy disclosure for your users.

---

# 37. The plaintext WoW protocol is a much bigger privacy/security issue

You mentioned:

> world traffic is plaintext.

This means the relay doesn't need to decrypt anything.

But anyone able to observe the traffic path can potentially inspect:

* character/world actions,
* packet structure,
* potentially credentials after authentication depending on protocol phase,
* game state information.

SRP6 protects the password exchange from being sent as plaintext, but **SRP6 does not magically encrypt the subsequent WoW world protocol**.

So I'd strongly recommend adding:

```text
WoW TCP
   ↓
encrypted transport
   ↓
relay
```

where possible.

The stock client doesn't support that, which means the companion/launcher architecture becomes valuable.

For public stock-client-only realms, the relay is merely transporting the plaintext game protocol.

---

# 38. Comparison matrix

Here's how I would rate the realistic options for your requirements.

Scores:

* **5 = excellent**
* **4 = good**
* **3 = usable**
* **2 = poor**
* **1 = fails requirement**

| Option                      | Signup | Hide IP | Reconnect | Friend friction | TCP | Latency | Overall fit                |
| --------------------------- | -----: | ------: | --------: | --------------: | --: | ------- | -------------------------- |
| Port forwarding             |      5 |       1 |         2 |               5 |   5 | ⭐⭐⭐⭐⭐   | Poor privacy               |
| UPnP/NAT-PMP                |      5 |       1 |         2 |               5 |   5 | ⭐⭐⭐⭐⭐   | Opt-in only                |
| Playit                      |      1 |       5 |         4 |               5 |   2 | ⭐⭐⭐⭐    | Current constraints fail   |
| Cloudflare Quick Tunnel     |      5 |       5 |         1 |               3 | 1/2 | ⭐⭐⭐⭐    | Dev only                   |
| Named Cloudflare Tunnel     |      1 |       5 |         5 |             2–3 |   4 | ⭐⭐⭐⭐    | Account required           |
| LocalTunnel                 |      5 |       5 |         2 |               4 |   3 | ⭐⭐⭐     | Experimental               |
| bore.pub                    |      5 |       5 |         2 |               4 |   5 | ⭐⭐⭐⭐    | Excellent prototype        |
| self-hosted bore            |      5 |       5 |         4 |             2–3 |   5 | ⭐⭐⭐⭐⭐   | Good primitive             |
| rathole + VPS               |      5 |       5 |         5 |             2–3 |   5 | ⭐⭐⭐⭐⭐   | Excellent primitive        |
| frp + VPS                   |      5 |       5 |         5 |             2–3 |   5 | ⭐⭐⭐⭐    | Excellent primitive        |
| TCP hole punching           |      5 |       1 |       2–3 |               2 |   5 | ⭐⭐⭐⭐⭐   | Optimization, not solution |
| STUN alone                  |      5 |       1 |         2 |               2 |   5 | ⭐⭐⭐⭐⭐   | Discovery only             |
| libp2p relay + companion    |      5 |       5 |         5 |               3 |  5* | ⭐⭐⭐⭐    | Strong architecture        |
| Custom relay + stock client |      5 |       5 |         5 |               5 |   5 | ⭐⭐⭐⭐    | **Best fit**               |

* TCP forwarding is easy; implementing the full libp2p stack on Android/JVM is a different matter.

---

# 39. Friend friction tiers

I'd explicitly design three modes.

### Mode A — LAN

```text
Friend installs nothing
```

Discovery:

```text
mDNS
```

Connection:

```text
direct TCP
```

---

### Mode B — Friend

```text
Friend installs your tiny companion
```

Connection:

```text
direct TCP if possible
       ↓
relay fallback
```

The helper can expose:

```text
127.0.0.1:3724
```

so the stock client remains completely unchanged.

---

### Mode C — Public

```text
Friend installs nothing
```

Connection:

```text
stable-relay-host:port
```

Discovery:

```text
DHT / beacon
```

---

# 40. I would *not* make VPN mode part of the default UX

Tailscale/ZeroTier/NetBird-style solutions are technically excellent.

But they violate your core product philosophy:

```text
Install VPN
↓
create account
↓
join network
↓
authorize device
↓
connect
```

For a game where the desired UX is:

```text
Realm → Join
```

that's far too much friction.

VPN should be a developer/power-user fallback, not the product's connectivity model.

---

# 41. Your actual relay service can be extremely small

You don't need Steam-scale infrastructure.

Suppose:

```text
100 simultaneous realms
```

and:

```text
5 players/realm
```

= 500 players.

The relay is basically:

```text
TCP socket A
        │
        │ copy bytes
        ▼
TCP socket B
```

The hard parts aren't CPU.

They're:

* bandwidth,
* abuse,
* port management,
* authentication,
* connection lifecycle,
* DDoS handling,
* relay availability.

For your initial scale, one VPS can handle a lot.

---

# 42. Relay protocol I'd use

Something like:

```text
Host
 │
 │ TLS/QUIC control connection
 ▼
Relay
```

Host authenticates using:

```text
realm private key
```

Relay returns:

```text
realm endpoint
```

Example:

```text
realm:
    8b4c...

endpoint:
    r2.example.net:41872
```

The host then maintains:

```text
heartbeat every 15–30 seconds
```

If connection dies:

```text
reconnect
↓
authenticate realm key
↓
restore endpoint mapping
```

The endpoint stays the same.

---

# 43. Don't make the realm endpoint equal the host identity

Separate:

```text
Realm ID
```

from:

```text
Relay allocation
```

For example:

```text
Realm ID:
SHA256(public key)

Relay allocation:
r2.example.net:41872
```

If relay #2 dies:

```text
Realm ID unchanged

r2 → r4
```

The DHT record changes, but the realm identity doesn't.

This is extremely useful for reconnects and migration.

---

# 44. Multiple relays

Eventually:

```text
             Realm
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
     Relay A Relay B  Relay C
```

Host maintains 2 outbound connections:

```text
primary
backup
```

Then if:

```text
Wi-Fi disappears
```

and comes back on LTE:

```text
old connection dies
       ↓
new NAT mapping
       ↓
host reconnects
       ↓
relay restores realm
```

No new address.

That directly solves one of your biggest requirements.

---

# 45. The ideal reconnect flow

### Host IP changes

```text
203.0.113.10
       ↓
203.0.113.88
```

Nothing happens to the public address.

The host simply reconnects:

```text
Realm Key
   ↓
Relay
   ↓
same realm endpoint
```

### Host router reboots

Same.

### Wi-Fi → hotspot

Same.

### ISP changes IP

Same.

### CGNAT mapping changes

Same.

This is one of the biggest reasons I prefer an application-level stable relay allocation over trying to make raw NAT traversal carry the whole product.

---

# 46. Hole punching becomes an optimization

Now you can safely experiment with:

```text
STUN
+
TCP simultaneous-open
```

or eventually:

```text
libp2p DCUtR
```

But the user doesn't care whether it succeeds.

Your state machine is:

```text
              Realm
                │
        ┌───────┴────────┐
        │                │
     direct             relay
        │                │
        └───────┬────────┘
                │
             WoW TCP
```

If direct succeeds:

```text
lower latency
less relay bandwidth
```

If it fails:

```text
relay continues working
```

That is exactly how mature P2P systems are designed.

---

# 47. What I would *not* build

I would explicitly avoid making these the core design:

### ❌ “Let's use DHT to directly publish the host IP”

Violates privacy.

### ❌ “Let's use STUN and let players connect directly”

Fails under CGNAT and exposes the IP.

### ❌ “Let's use TCP hole punching everywhere”

Too unreliable as the only path.

### ❌ “Let's use Cloudflare Quick Tunnel”

Temporary URL and not the intended production TCP solution. ([Cloudflare Docs][9])

### ❌ “Let's make users install Tailscale”

Violates your no-account/zero-friction goal.

### ❌ “Let's make bore.pub our infrastructure”

Too much dependency on somebody else's public experimental relay.

### ❌ “Let's make JVM libp2p the foundation”

At least initially, because its Android implementation isn't established enough to justify that risk. ([GitHub][24])

---

# 48. What I would build instead

## Connectivity

```text
             ┌───────────────┐
             │ Connection    │
             │ Manager       │
             └───────┬───────┘
                     │
        ┌────────────┼─────────────┐
        ▼            ▼             ▼
       LAN         Direct         Relay
       mDNS       TCP/STUN       custom/rathole
```

## Discovery

```text
              Realm Discovery
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
      NSD         Friends       DHT
      mDNS        gossip       records
                                   │
                                   ▼
                              public beacon
                               (optional)
```

## Identity

```text
Ed25519 realm keypair
```

## Address

```text
stable relay virtual endpoint
```

## Stock client

```text
unchanged
```

## Friend helper

```text
tiny bundled forwarder
```

---

# 49. Concrete product UX

### Host

Click:

> **Open Realm**

The app says:

```text
Realm is online.

Friends:
  ● Alice
  ● Bob

Public:
  ○ Disabled

[Invite Friend]
[Make Public]
```

No IP addresses shown.

---

### Friend

Friend receives:

> **Azeroth wants to invite you**

Clicks:

> **Join**

Your helper starts automatically.

The game launches with:

```text
127.0.0.1:3724
```

The friend doesn't know whether the connection is:

```text
LAN
direct
hole-punched
relay
```

Nor should they.

---

# 50. Public realm

Host clicks:

> **Make Public**

Application generates:

```text
Realm ID:
8b6d7c...
```

DHT record:

```text
8b6d7c...
       ↓
signed realm descriptor
       ↓
relay3.example.net:41872
```

A community beacon may additionally list:

```text
Azeroth
Vanilla
1.12.1
5/50 players
Oceania
```

but the beacon is not authoritative.

---

# 51. One particularly good privacy feature

Give every realm two identifiers:

### Private realm ID

```text
random 256-bit ID
```

Used for:

```text
friends
direct invitations
```

### Public realm ID

```text
hash(public key)
```

Used for:

```text
DHT
public discovery
```

That prevents accidentally leaking:

```text
"Bob's realm"
```

to the global network just because Bob happens to be playing.

---

# 52. Public discovery should be opt-in

Default:

```text
realm visibility = private
```

Then:

```text
LAN
Friends
Public
```

are separate states.

Don't have:

```text
realm exists
→ automatically publish to DHT
```

because that turns every casual home game into a public Internet artifact.

---

# 53. Abuse considerations

Public relays change the threat model.

You need:

```text
connection rate limit
per-realm connection limit
bandwidth limit
handshake timeout
idle timeout
relay authentication
```

For example:

```text
max simultaneous clients:
50

max new TCP connections:
20/minute/IP

pre-login idle timeout:
10 seconds

realm relay bandwidth:
configurable
```

For your normal use:

```text
1–10 players
```

you can be extremely conservative.

---

# 54. DDoS protection

This is another advantage of putting public realms behind your relay.

Instead of:

```text
attacker
   ↓
home IP
```

you get:

```text
attacker
   ↓
relay
   ↓
home
```

You can kill the relay allocation without touching the home network.

Valve specifically highlights this IP-hiding/DDoS-protection property of SDR. ([Steamworks][33])

---

# 55. One important subtlety: the login server

Classic WoW normally has at least:

```text
3724 login
```

and then world-server connectivity.

Your architecture should treat the whole thing as one logical realm:

```text
public realm endpoint
        │
        ▼
connection router
        │
        ├── login
        └── world
```

If the stock client expects separate world-server IPs/ports, your relay needs to preserve that behavior or your server needs to advertise an address reachable through the same relay identity.

This is an implementation detail worth resolving **before** choosing the exact tunnel protocol.

---

# 56. Recommended technology choices

If I were starting the project today:

### Windows

```text
Rust relay/client core
```

### Android

```text
same Rust core via JNI/UniFFI
```

or Kotlin networking if you strongly prefer a JVM-only architecture.

### LAN

```text
mDNS
```

### NAT classification

```text
STUN
```

### Direct TCP

```text
custom simultaneous-open implementation
```

or eventually libp2p-inspired DCUtR.

### Relay

Initially:

```text
rathole-style protocol
```

Then replace it with your own tiny protocol once requirements stabilize.

### Discovery

```text
Ed25519 signed records
+
Mainline DHT / BEP44
```

### Friend discovery

```text
signed P2P gossip
```

### Public index

```text
optional independent beacon sites
```

---

# 57. What I would prototype first

Don't start with DHT.

Start here:

### Prototype 1

```text
WoW server
   ↓
custom relay
   ↓
stock WoW client
```

Verify:

* SRP6 login,
* long-lived TCP,
* world traffic,
* reconnect,
* 10 simultaneous clients,
* Windows firewall,
* Android client.

### Prototype 2

Add:

```text
stable realm allocation
```

Verify:

```text
host disconnect
↓
IP changes
↓
reconnect
↓
same public endpoint
```

### Prototype 3

Add:

```text
companion forwarder
```

Verify stock client stays untouched.

### Prototype 4

Add:

```text
STUN + direct TCP
```

Verify direct connection works where possible.

### Prototype 5

Add:

```text
DHT realm descriptor
```

### Prototype 6

Add:

```text
friend gossip
```

### Prototype 7

Add:

```text
public beacon ecosystem
```

That's a much safer development order than starting with a giant decentralized networking stack.

---

# 58. Bottom line

If I reduce the research to a few decisions:

| Decision                | Recommendation                                                   |
| ----------------------- | ---------------------------------------------------------------- |
| LAN discovery           | **mDNS/NSD**                                                     |
| Friend discovery        | **signed friend-graph gossip**                                   |
| Public discovery        | **DHT signed records + optional community indexes**              |
| DHT                     | **Mainline DHT/BEP44 is more practical than JVM libp2p today**   |
| DHT purpose             | **Realm lookup, not global realm search**                        |
| Origin privacy          | **relay mandatory for default public mode**                      |
| STUN                    | **useful diagnostic/NAT discovery, never sufficient alone**      |
| TCP hole punching       | **worth implementing as an optimization**                        |
| CGNAT                   | **relay fallback mandatory**                                     |
| Relay                   | **self-hosted application-controlled relay pool**                |
| Relay implementation    | **rathole/frp as initial prior art; custom protocol eventually** |
| Public stock client     | **relay exposes stable `host:port`**                             |
| Friends                 | **tiny companion that maps localhost → relay/direct**            |
| Stable identity         | **Ed25519 realm key, independent of IP**                         |
| Stable endpoint         | **relay virtual allocation bound to realm ID**                   |
| Cloudflare Quick Tunnel | **development only**                                             |
| Playit                  | **no longer satisfies your account + free TCP constraints**      |
| bore.pub                | **excellent prototype, not core infrastructure**                 |
| LocalTunnel             | **experimental only**                                            |
| Steam SDR               | **architectural inspiration, not reusable infrastructure**       |

The strongest design is therefore:

```text
                         ┌──────────────────────┐
                         │  Realm Identity      │
                         │  Ed25519 keypair      │
                         └──────────┬───────────┘
                                    │
                      ┌─────────────┼─────────────┐
                      │             │             │
                      ▼             ▼             ▼
                    mDNS          Gossip          DHT
                     LAN          Friends        Public
                      │             │             │
                      └─────────────┼─────────────┘
                                    │
                              Realm descriptor
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │ Connectivity Manager │
                         └──────────┬───────────┘
                                    │
                         ┌──────────┴──────────┐
                         ▼                     ▼
                  Direct TCP / P2P          Relay
                         │                     │
                         └──────────┬──────────┘
                                    ▼
                              WoW TCP 3724
```

And the crucial invariant is:

> **Discovery may identify the realm, but only the relay endpoint is ever advertised publicly. The home IP is never part of the realm's public identity.**

That gives you the combination that the existing commercial products don't currently provide cleanly: **accountless hosting, origin-IP protection, stable realm identity, CGNAT tolerance, stock-client compatibility, and decentralized discovery.**

The existing ecosystem strongly validates the individual pieces: e4mc validates the “host-only reverse tunnel + vanilla client” UX; Essential validates transparent proxy/direct-connect fallback; Steam validates relay/direct-path switching; libp2p validates AutoNAT + relay + hole-punching; BEP44 validates signed decentralized records; and rathole/frp validate the lightweight outbound-connection relay architecture. ([CurseForge][6])

### Sources worth bookmarking

* [Cloudflare Quick Tunnels documentation](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/?utm_source=chatgpt.com)
* [Playit current setup/product page](https://playit.gg/account/setup/wizard/new-account/?utm_source=chatgpt.com)
* [Playit current prohibited/allowed uses](https://playit.gg/support/prohibited-uses/?utm_source=chatgpt.com)
* [e4mc current project page](https://modrinth.com/mod/e4mc?utm_source=chatgpt.com)
* [Essential current hosting documentation](https://essential.gg/en/wiki/start-hosting?utm_source=chatgpt.com)
* [bore current repository](https://github.com/ekzhang/bore/blob/main/README.md?utm_source=chatgpt.com)
* [rathole current repository/documentation](https://github.com/Passw/rapiz1-rathole?utm_source=chatgpt.com)
* [frp current repository](https://github.com/fatedier/frp?utm_source=chatgpt.com)
* [libp2p hole-punching documentation](https://docs.libp2p.io/concepts/hole-punching/?utm_source=chatgpt.com)
* [libp2p DHT documentation](https://libp2p.io/docs/dht/?utm_source=chatgpt.com)
* [Mainline DHT BEP 5](https://www.bittorrent.org/beps/bep_0005.html?utm_source=chatgpt.com)
* [BEP 44 signed DHT data](https://www.bittorrent.org/beps/bep_0044.html?utm_source=chatgpt.com)
* [Android NSD documentation](https://developer.android.com/reference/android/net/nsd/NsdManager?utm_source=chatgpt.com)
* [Steam Datagram Relay documentation](https://partner.steamgames.com/doc/features/multiplayer/steamdatagramrelay?l=japanese&language=english&utm_source=chatgpt.com)

**One thing I'd investigate next:** the exact classic-WoW login/world-server handoff. That determines whether your relay can expose a single stable `host:port`, or whether you need a small protocol-aware address-rewriting layer in addition to the generic TCP relay.

[1]: https://github.com/Passw/rapiz1-rathole?utm_source=chatgpt.com "GitHub - Passw/rapiz1-rathole: A lightweight and high-performance reverse proxy for NAT traversal, written in Rust. An alternative to frp and ngrok. · GitHub"
[2]: https://playit.gg/account/setup/wizard/new-account/?utm_source=chatgpt.com "Make Your Game Server Public - playit.gg"
[3]: https://playit.gg/support/minecraft-bedrock-nethernet/?utm_source=chatgpt.com "Minecraft Bedrock: Switch from NetherNet to RakNet | playit.gg - support pages"
[4]: https://github.com/playit-cloud/playit-agent/blob/master/README.md?plain=1&utm_source=chatgpt.com "playit-agent/README.md at master · playit-cloud/playit-agent · GitHub"
[5]: https://playit.gg/terms/?utm_source=chatgpt.com "Terms of Service - playit.gg"
[6]: https://www.curseforge.com/minecraft/mc-mods/e4mc?utm_source=chatgpt.com "e4mc - Minecraft Mods - CurseForge"
[7]: https://essential.gg/en/wiki/start-hosting?utm_source=chatgpt.com "Wiki - Essential Wiki | Essential Mod"
[8]: https://essential.gg/en/changelog?utm_source=chatgpt.com "Changelog | Essential Mod"
[9]: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/?utm_source=chatgpt.com "Quick Tunnels · Cloudflare Docs"
[10]: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/protocols/?utm_source=chatgpt.com "Protocols for published applications · Cloudflare One docs"
[11]: https://developers.cloudflare.com/dns/proxy-status/use-cases/?utm_source=chatgpt.com "Use cases · Cloudflare DNS docs"
[12]: https://github.com/localtunnel/localtunnel?utm_source=chatgpt.com "GitHub - localtunnel/localtunnel: expose yourself · GitHub"
[13]: https://github.com/localtunnel/localtunnel/blob/master/README.md?plain=1&utm_source=chatgpt.com "localtunnel/README.md at master · localtunnel/localtunnel · GitHub"
[14]: https://github.com/localtunnel/localtunnel/issues?utm_source=chatgpt.com "Issues · localtunnel/localtunnel · GitHub"
[15]: https://github.com/ekzhang/bore/blob/main/README.md?utm_source=chatgpt.com "bore/README.md at main · ekzhang/bore · GitHub"
[16]: https://github.com/ekzhang/bore/pulls?utm_source=chatgpt.com "Pull requests · ekzhang/bore · GitHub"
[17]: https://github.com/ekzhang/bore/issues/196?utm_source=chatgpt.com "bore.pub outage (may 2026) · Issue #196 · ekzhang/bore · GitHub"
[18]: https://github.com/fatedier/frp?utm_source=chatgpt.com "GitHub - fatedier/frp: A fast reverse proxy to help you expose a local server behind a NAT or firewall to the internet. · GitHub"
[19]: https://arxiv.org/abs/cs/0603074?utm_source=chatgpt.com "Peer-to-Peer Communication Across Network Address Translators"
[20]: https://arxiv.org/abs/2604.12484?utm_source=chatgpt.com "Large-Scale Measurement of NAT Traversal for the Decentralized Web: A Case Study of DCUtR in IPFS"
[21]: https://www.rfc-editor.org/info/rfc8489/?utm_source=chatgpt.com "RFC 8489: Session Traversal Utilities for NAT (STUN) | RFC Editor"
[22]: https://docs.libp2p.io/concepts/hole-punching/?utm_source=chatgpt.com "Hole Punching | libp2p"
[23]: https://libp2p.io/docs/circuit-relay/?utm_source=chatgpt.com "Circuit Relay | libp2p"
[24]: https://github.com/libp2p/jvm-libp2p?utm_source=chatgpt.com "GitHub - libp2p/jvm-libp2p: a libp2p implementation for the JVM, written in Kotlin 🔥 · GitHub"
[25]: https://github.com/libp2p/jvm-libp2p/releases?utm_source=chatgpt.com "Releases · libp2p/jvm-libp2p · GitHub"
[26]: https://github.com/the8472/mldht?utm_source=chatgpt.com "GitHub - the8472/mldht: Bittorrent Mainline DHT implementation in java · GitHub"
[27]: https://github.com/atomashpolskiy/bt/blob/master/README.md?utm_source=chatgpt.com "bt/README.md at master · atomashpolskiy/bt · GitHub"
[28]: https://github.com/BiglySoftware/BiglyBT-Android?utm_source=chatgpt.com "GitHub - BiglySoftware/BiglyBT-Android: BiglyBT for Android, torrent client and remote control app · GitHub"
[29]: https://www.bittorrent.org/beps/bep_0005.html?utm_source=chatgpt.com "bep_0005.rst_post"
[30]: https://www.bittorrent.org/beps/bep_0051.html?utm_source=chatgpt.com "bep_0051.rst_post"
[31]: https://www.bittorrent.org/beps/bep_0044.html?utm_source=chatgpt.com "bep_0044.rst_post"
[32]: https://developer.android.com/reference/android/net/nsd/NsdManager?utm_source=chatgpt.com "NsdManager  |  API reference  |  Android Developers"
[33]: https://partner.steamgames.com/doc/features/multiplayer/steamdatagramrelay?l=japanese&language=english&utm_source=chatgpt.com "Steam Datagram Relay（Steamworksドキュメント）"



Agemt b:
The Classic WoW Protocol Trap: Why Most Relays Fail Out of the Box

Before evaluating network tunnels, it is critical to address how the classic
World of Warcraft (1.12.1 / 2.4.3 / 3.3.5a) client handles networking:

[WoW Client] --- (1) TCP Connect (Port 3724) ---> [Auth Server (realmd)]
              <-- (2) SRP6 Auth + Realm List ---  (Contains Realm IP:Port)
[WoW Client] --- (3) Disconnects from Auth
[WoW Client] --- (4) TCP Connect (World Port) --> [World Server (mangosd/trinitycore)]

1.  Dual-Port Architecture: The client reads realmlist.wtf, resolves the
    address, and connects via TCP to the Auth Server on port 3724.
2.  Dynamic World Address Handoff: After SRP6 authentication succeeds, the Auth
    server returns a CMD_REALM_LIST_SERVER packet containing the list of
    available realms, which includes the World Server’s target IP/hostname and
    Port (conventionally port 8085).
3.  Disconnection and Re-connection: The client abruptly disconnects from
    port 3724 and initiates an independent TCP connection to the world IP:port
    received in that packet.
4.  No SNI / Virtual Hosting: Unlike HTTP (which sends Host: headers) or
    Minecraft (whose initial Handshake packet sends the host domain string), WoW
    client packets are raw binary with no destination hostname metadata. A
    reverse proxy cannot inspect the payload to route connections by subdomain
    on a single shared port.

Architectural Consequence:

  - Stock-Client Only (Zero Companion): Any public relay must allocate two
    separate public ports (one for Auth, one for World), and your backend
    database (realmlist table) must dynamically rewrite its world port to match
    the relay’s assigned external world port on startup.
  - Companion Forwarder (Friend Runs Your App): The companion forwarder listens
    locally on 127.0.0.1:3724 and 127.0.0.1:8085. The friend sets realmlist.wtf
    to 127.0.0.1. The companion encapsulates both local ports across a single
    multiplexed P2P tunnel (QUIC/Noise) to the host. This bypasses the dual-port
    and realmlist rewrites entirely.

1. Survey of Prior Art (Verified Current State)

playit.gg

  - Accountless Hosting: No longer fully headless or accountless. While the CLI
    still supports a temporary "guest claim link", running the agent prompts the
    host to click an interactive browser claim link to activate.
  - Raw TCP Support: Severely restricted. Due to widespread abuse (botnets,
    malware delivery, and DDoS attacks), raw TCP tunneling was removed from the
    free/guest tier. The free tier is locked almost exclusively to Minecraft
    (Java/Bedrock) and select supported games. Arbitrary TCP tunnels now require
    an account and Playit Premium ($3/month).
  - Address Stability: Dynamic high ports on the free tier; static custom
    hostnames require paid subscriptions.
  - Verdict: Unusable as an accountless default for arbitrary TCP.

e4mc and Essential Mod (The Minecraft LAN Pattern)

  - e4mc: A mod for Minecraft that connects via an outbound TLS WebSocket to a
    public relay (e4mc.link), giving the host a public subdomain.
      - Why it fails for WoW: Minecraft sends the exact domain the player dialed
        inside its initial 0x00 Handshake packet. The e4mc relay reads this
        header and multiplexes thousands of game sessions across a single public
        port (25565). Classic WoW does not transmit a hostname header, making
        e4mc’s single-port proxy architecture incompatible with WoW unless
        modified to allocate unique external TCP ports per host.
      - Address Stability: Ephemeral; a new domain is generated every session
        restart.
  - Essential Mod: Uses an ICE-like architecture (STUN for direct UDP
    hole-punching, falling back to TURN relays).
      - Tradeoffs: Tied strictly to Microsoft/Mojang account authentication,
        runs proprietary signaling servers, and requires client-side game
        modding. It cannot be used for independent protocols.

cloudflared Quick Tunnels (TryCloudflare)

  - Accountless Hosting: Yes. Running cloudflared tunnel --url ... requires no
    login or DNS configuration.
  - Raw TCP Support: Not supported. Quick Tunnels strictly proxy HTTP/HTTPS
    traffic. Cloudflare Tunnel does support arbitrary TCP via Cloudflare Access,
    but this requires a registered Cloudflare account, a custom domain, and the
    friend must run cloudflared access tcp locally.
  - Address Stability: Ephemeral. A new random *.trycloudflare.com subdomain is
    generated on every restart.
  - ToS Risk: High. Cloudflare Terms of Service explicitly disallow proxying
    non-HTTP media or heavy gaming streams without enterprise agreements, and
    Quick Tunnels are hard-throttled to 200 in-flight requests.
  - Verdict: Non-viable for raw TCP protocols.

Minimalist Public Relays (bore.pub, serveo, localtunnel)

  - bore.pub: A minimal (~400 lines of Rust) TCP tunnel. Exposes local ports via
    bore local <port> --to bore.pub without an account.
      - Reliability: bore.pub is a hobby instance operated by an individual
        developer. It experiences periodic downtime, lacks DDoS protection, and
        assigns a random public port unless explicitly specified (which often
        collides with existing users). Furthermore, you must run two concurrent
        bore instances (one for 3724, one for 8085).
  - serveo.net / localtunnel / pinggy.io:
      - serveo: Frequently blacklisted by ISPs due to phishing abuse; high
        downtime.
      - localtunnel: HTTP-only.
      - pinggy: Free TCP tunneling is restricted to 60-minute session timeouts.

Self-Hosted Relays (rathole, frp)

  - rathole (Rust) and frp (Go): The gold standard for multiplexed, secure TCP
    reverse proxies.
  - Tradeoff: Solves the entire technical requirement, but violates the core
    constraint of having zero infrastructure. The app developer (or host) must
    provision, pay for, and maintain a public Linux VPS.

Steam Datagram Relay (SDR)

  - Valve’s enterprise-grade virtual private network that proxies traffic across
    Valve's private backbone using anonymous "FakeIPs".
  - Why it cannot be used:
    1.  Requires the Steamworks SDK and a valid Steam AppID (abusing AppID 480 /
        Spacewar is heavily rate-limited and subject to bans).
    2.  Requires both host and players to have active Steam desktop client
        installations running.
    3.  Incompatible with Android handhelds: Valve does not provide a native
        Steamworks native runtime for Android ARM devices.

2. Comprehensive Comparison Table

| Solution                                  | Accountless?                | Casual-Dox Proof? (Hides Host IP)        | Reconnect Resilience                               | Friend Friction                          | Latency Impact        | ToS / Ban Risk                                       | Overall Viability for Project             |
| :---------------------------------------- | :-------------------------- | :--------------------------------------- | :------------------------------------------------- | :--------------------------------------- | :-------------------- | :--------------------------------------------------- | :---------------------------------------- |
| **Direct UPnP / NAT-PMP**                 | **Yes** (Zero setup)        | **No** (Direct public IP leak)           | Low (Breaks on dynamic IP/hotspot change)          | **Zero** (Stock WoW client)              | Lowest (+0 ms)        | Safe                                                 | **Fallback Only** (Violates Privacy)      |
| **Cloudflare Quick Tunnels**              | **Yes**                     | **Yes** (Proxied)                        | Terrible (New URL on every restart)                | High (Requires HTTP wrapping)            | Low                   | **Immediate Ban** (TCP not supported; ToS violation) | **Non-Viable**                            |
| **`playit.gg` (Free/Guest)**              | Marginal (Web claim needed) | **Yes** (Anycast IP)                     | Moderate (Ports fluctuate on reconnect)            | **Zero** (Stock WoW client)              | Low–Med (+20–60 ms)   | High (Raw TCP blocked on free tier)                  | **Non-Viable** (Free TCP deprecated)      |
| **`bore.pub` (Public Instance)**          | **Yes** (1-line CLI)        | **Yes** (Proxied)                        | Low (Random port re-assigned on reboot)            | **Zero** (Stock WoW client)              | Variable (+30–100 ms) | Low (Community tool, but unstable)                   | **Brittle Emergency Fallback**            |
| **Tor Onion Services**                    | **Yes** (100% local crypto) | **Absolute** (3-hop circuit)             | **High** (Deterministic `.onion` from private key) | Medium (Friend must run SOCKS proxy/app) | Severe (+250–500 ms)  | Safe                                                 | **Viable for Async/Chat, Poor for Raids** |
| **Self-Hosted `rathole` / `frp`**         | **Yes** (Token-based)       | **Yes** (VPS IP exposed)                 | **High** (Pre-assigned ports on domain)            | **Zero** (Stock WoW client)              | Lowest (+5–20 ms)     | Safe                                                 | **Production-Grade (Requires VPS)**       |
| **P2P Companion Overlay (`Iroh` / QUIC)** | **Yes** (Ed25519 node keys) | **Yes** (Relayed via DERP until punched) | **Exceptional** (QUIC connection migration)        | Low (Friend launches companion app)      | Low (+10–40 ms)       | Safe                                                 | **Recommended Default**                   |

3. NAT Traversal Reality Check for TCP

Can TCP hole-punching work in practice without a relay?

No, not reliably. While UDP hole-punching succeeds roughly 82–95% of the time
across general networks, direct TCP hole-punching succeeds only ~50–65% of the
time in residential environments and drops below 20–30% when cellular networks,
Wi-Fi hotspots, or Carrier-Grade NAT (CGNAT) are involved.

  - The TCP State Machine Barrier: TCP requires a three-way handshake. NAT
    traversal requires "TCP Simultaneous Open" (RFC 5382), where both sides send
    SYN packets concurrently. If NAT A receives Peer B’s SYN before Peer A’s
    outgoing SYN has crossed the boundary, NAT A drops the packet or issues a
    TCP RST.
  - Mapping Behavior (RFC 4787 / RFC 5382):
      - Endpoint-Independent Mapping (EIM): The NAT reuses the same public
        mapping for any outbound destination. Hole punching works here.
      - Endpoint-Dependent Mapping (EDM / Symmetric NAT): Standard across CGNAT
        (cellular hotspots, Starlink, T-Mobile Home Internet). Every time the
        host opens a connection to a different destination IP/port (e.g., from
        the STUN server to a friend), the NAT allocates a completely new,
        randomized public port. The peer cannot predict this port without port
        scanning, which is actively blocked by stateful firewalls.

What public STUN servers provide (and what they do not)

Public STUN servers (e.g., stun.l.google.com:19302, stun.cloudflare.com:3478)
are stateless, free, and require no accounts:

  - What they provide: STUN allows a client to query what external public IP and
    port the NAT assigned to its socket, and determine whether the local NAT is
    EIM or EDM (Symmetric).
  - What they do not provide: STUN does not keep connections open for third
    parties, cannot traverse Symmetric NATs, and does not relay traffic.

When is a relay strictly unavoidable?

A relay is mandatory whenever:

1.  Either the host or the friend is behind a Symmetric NAT / CGNAT.
2.  A strict stateful corporate/carrier firewall drops unsolicited inbound TCP
    SYNs unconditionally.
3.  Both peers are on the same local subnet behind an asymmetric NAT lacking NAT
    Hairpinning/Loopback support.

Conclusion: For an offline-first app running on Android or dynamic home
connections, a relay architecture is structurally mandatory.

4. Serverless Discovery for Public Realms

To allow strangers to discover a realm without running a centralized server,
several mechanisms can be evaluated:

1. BitTorrent Mainline DHT (BEP 44 / BEP 46)

  - How it works: BEP 44 allows storing arbitrary mutable data items on the
    Mainline DHT, signed by the host's Ed25519 keypair.
  - Feasibility on Android/JVM: Moderately complex. While Java wrappers exist
    (frostwire-jlibtorrent), they require bundling ~25MB of native .so binaries
    for ARM64/x86. Pure Kotlin/Java DHT implementations (such as TomP2P) suffer
    from routing table decay.
  - Privacy/CGNAT Reality: Major hazard.
      - If the host advertises their direct IP:port on the DHT, their home IP is
        leaked to anyone walking the DHT keyspace, violating Constraint #2.
      - If the host is behind CGNAT, other DHT nodes cannot route incoming
        lookups to them, meaning the host cannot easily participate in the DHT
        routing table without excessive background battery drain.
  - Verdict: Research-grade for Android mobile realms; prone to IP leakage.

2. libp2p Kademlia

  - Feasibility: Java implementations (jvm-libp2p) are designed for Ethereum
    consensus clients (Teku) and are heavyweight for mobile devices. libp2p
    requires persistent bootstrap nodes (e.g., Protocol Labs bootstrap servers).
  - Verdict: Too heavy for a background Android game wrapper.

3. Friend-Graph Gossip (Peer Exchange / PEX)

  - Feasibility: Practical only for 1-hop or 2-hop "Friends-of-Friends." If
    Friend A connects to the Host, Friend A can gossip the Host’s presence to
    Friend B. However, it cannot solve discovery for untrusted, disconnected
    strangers without an initial bootstrap point.

4. The Practical Winner: Nostr Relays as Dumb Ephemeral Message Boards

Rather than wrestling with DHT churn on mobile devices, use the Nostr protocol
(NIP-01 / NIP-40):

  - How it works: The Nostr network consists of hundreds of public,
    permissionless, accountless WebSocket relays (wss://relay.damus.io,
    wss://nos.lol, etc.). No signup or API token is required.
  - Publishing a Realm: The host generates an ephemeral keypair locally and
    publishes an event (e.g., kind: 30078 - Arbitrary App Data) tagged with
    #wow-realm-public. The payload contains the realm name, game version, and
    connection ticket (relay URL or P2P NodeId).
  - Expiration (NIP-40): The event is tagged with an expiration timestamp
    (e.g., 10 minutes). The host continuously refreshes it while online. If the
    host crashes or goes offline, the listing automatically vanishes from the
    network.
  - Implementation Footprint: ~100 lines of pure Kotlin/Java using standard
    OkHttp WebSockets. Zero native C++ bloat.
  - Privacy: The host publishes an encrypted connection token or relay proxy
    address, never their raw IP.

5. Recommended Tiered Architecture

To balance zero signups, IP privacy, reconnect stability, and friend friction,
the following three-tier networking stack is recommended:

+-----------------------------------------------------------------------------------+
|                                TIER 1: LAN ONLY                                   |
|  Local Wi-Fi / Hotspot Discovery (mDNS / NSD)                                     |
|  - Host: Registers _wowrealm._tcp via Android NsdManager / Windows DNS-SD         |
|  - Friend: Stock Client connects to <host>.local:3724                             |
|  - Friend Friction: Zero                                                          |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                           TIER 2: TRUSTED FRIENDS                                 |
|  P2P Overlay via Iroh (QUIC) or Companion Tunnel                                  |
|  - Host: Generates Ed25519 Realm Identity; dials public DERP relay                |
|  - Host shares invite string: realm://<Host-Node-ID>                              |
|  - Friend Companion App: Tunnels local 127.0.0.1:3724 & 8085 -> Host QUIC Stream   |
|  - Privacy: 100% Casual-Dox Proof (Direct punch only after Noise handshake)       |
|  - Resilience: QUIC connection migration survives Host IP changes / mobile handoff|
|  - Friend Friction: Low (Launch companion app -> click "Play")                   |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                          TIER 3: PUBLIC / STRANGERS                               |
|  Serverless Ephemeral Directory (Nostr) + Proxied Relay Ingress                   |
|  - Discovery: Host broadcasts NIP-40 ephemeral event to public Nostr relays       |
|  - Ingress: Relayed via dual-port reverse proxy pool or Iroh public circuit       |
|  - Friend Friction: Zero (If stock client) / Low (If using in-app server browser) |
|  - Privacy: Complete IP hiding; host IP never hits Nostr or public internet       |
+-----------------------------------------------------------------------------------+

Implementation Details by Tier

Tier 1: LAN / Offline Local Play

  - Addressing: Plain local IP or mDNS hostname (.local).
  - Discovery:
      - Android: android.net.nsd.NsdManager registers service type
        _wowrealm._tcp.
      - Windows: Native Windows DNS-SD / Bonjour or jmDNS.
  - Friend Action: The friend opens the bundled app on the same Wi-Fi, sees the
    realm populate automatically, clicks "Connect", and the app modifies
    realmlist.wtf to point to the host's LAN IP.
  - Reconnect Behavior: Automatic; mDNS resolves DHCP address changes.

Tier 2: Friends (Semi-Trusted, Maximum Resilience)

  - Technology: Iroh (iroh.computer) embedded as a native Rust background worker
    or dynamic library (accessible via JNI on Android and DLL on Windows).
  - Addressing & Setup:
    1.  The host clicks "Host Realm for Friends".
    2.  The app initializes an Iroh node, creating a cryptographic public key
        (NodeId).
    3.  The app exports an invite token: realm://<NodeID>.
    4.  The host sends this token to friends over Discord, Signal, or SMS.
  - The Connection Flow:
    1.  The friend opens the companion app and pastes the token.
    2.  The friend's companion connects to the host's NodeId.
    3.  Iroh handles NAT traversal: it uses public community DERP relays for
        signaling and fallback, while simultaneously attempting direct UDP
        hole-punching.
    4.  The friend companion listens locally on 127.0.0.1:3724 and
        127.0.0.1:8085.
    5.  The companion sets the friend’s realmlist.wtf to 127.0.0.1.
  - Addressing the WoW Dual-Port Requirement:
      - When the friend's client hits 127.0.0.1:3724, the companion pipes the
        stream through Iroh QUIC Stream 1.
      - When the Auth server responds, the companion intercepts the
        CMD_REALM_LIST_SERVER packet (or the Auth database is pre-configured to
        advertise 127.0.0.1:8085), so the client dials 127.0.0.1:8085.
      - The companion pipes that world traffic through Iroh QUIC Stream 2.
  - Privacy: The host’s real IP is never exposed to the public. If direct
    hole-punching occurs, only the authenticated friend receives peer IP
    packets; if either is behind symmetric CGNAT, traffic remains on the relay.
  - Reconnect Resilience: State of the Art. Because Iroh operates over QUIC, it
    supports Connection Migration. If the host switches from home Wi-Fi to a 5G
    mobile hotspot, the underlying IP change does not sever the QUIC session,
    and the TCP game session remains alive without kicking players.

Tier 3: Public (Untrusted Strangers)

To let strangers join without exposing the host's home network:

  - Discovery Layer:
      - The host app publishes an ephemeral, signed JSON event to 3–5 public
        Nostr relays (wss://relay.damus.io, wss://nos.lol,
        wss://relay.snort.social).
      - Tag: ["t", "wow-realm-public"], ["expiration", "<now + 600>"].
      - Content: Encrypted or public connection descriptor containing realm
        details (name, patch version, rate stats) and the connection endpoint.
  - Ingress / Connection Layer:
      - Option A (Companion-Driven / Recommended): The stranger's app has a
        "Public Realm Browser". It queries the Nostr relays, renders the list of
        active games, and connects through the Iroh P2P overlay just like
        Tier 2. The host's real IP is completely hidden behind relay boundaries.
      - Option B (Zero-Companion / Pure Stock Client): If the stranger insists
        on using an un-modded client with only a realmlist.wtf edit:
        1.  The host app connects out to a pool of community-operated or open
            reverse proxy relays (e.g., instances running bore or self-hosted
            rathole nodes).
        2.  The relay allocates two public ports (e.g., relay.net:10420 for
            Auth, relay.net:10421 for World).
        3.  The host’s internal Auth database updates its realmlist table to
            broadcast address: relay.net and port: 10421.
        4.  The Nostr event publishes relay.net:10420 as the public realmlist
            target.
        5.  The stranger enters set realmlist relay.net:10420 directly into
            their config.

Summary Checklist for Implementation

1.  Do not use Cloudflare Quick Tunnels or Free Playit.gg: Cloudflare quick
    tunnels do not support raw TCP, and playit.gg has paywalled/restricted free
    custom TCP tunnels.
2.  Never rely on raw TCP hole punching: Symmetric CGNAT on mobile hotspots
    guarantees a failure rate >70%. A relay fallback is required.
3.  Handle WoW's two-port handshake: Use the companion tool on the friend side
    to map ports 3724 and 8085 to 127.0.0.1. This eliminates the need to
    dynamically alter server database tables during tunnel initialization.
4.  Use Iroh for P2P: It provides accountless Ed25519 identity, automatic
    relay-to-direct hole-punch escalation, and QUIC connection migration across
    host IP drops.
5.  Use Nostr for Serverless Public Discovery: It gives you a free, distributed,
    accountless realmlist directory with built-in time-to-live event expiration,
    without the bloat and NAT routing challenges of running a BitTorrent DHT
    node on Android.
