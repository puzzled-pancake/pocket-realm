# Pocket Realm desktop — multiplayer and per-flavor timeframe design

**Assembled document** (2026-10-01). This file is the four desktop-parity section designs
assembled verbatim behind one executive summary and one consolidated decision list. The
sections are the authoritative text; the summary adds nothing to them and changes nothing
in them.

| § | Source (included byte-for-byte below) | What it covers |
|---|---|---|
| 1 | `out/desktop-parity/design-lan.md` | LAN multiplayer — desktop host design (revision 3) |
| 2 | `out/desktop-parity/design-tunnel.md` | Friend-over-internet connectivity for the desktop host (revision 3) |
| 3 | `out/desktop-parity/design-remote-accounts.md` | Remote account creation for friends over LAN/tunnel (revision 3) |
| 4 | `out/desktop-parity/design-timeframe.md` | Per-flavor content-patch timeframe control |

---

## Executive summary

### The four features

**1. LAN multiplayer for the desktop host (§1).** The desktop already ships the LAN lane —
host/join modes, launch-gate authorization (`allowLanPlayers`), realmlist projection on the
host DB, guest `realmlist.wtf` merge/restore, and the client-only join plan (§1 §1). The
design closes the remaining gaps: mode-keyed preflight/interface-liveness checks and a 10 s
interface-loss monitor that graceful-stops hosting (D1/D2), a desktop virtual-adapter filter
so a Hyper-V address is never offered as the host address (D3), an invite card that copies
the *projected* advertisement rather than the stored setting (D4), a Guests card that
generates 16-character ≈80-bit passwords at `gmLevel` 0 for in-person handoff (D5a),
revocation verbs — ban/unban/kick over the existing SEC_CONSOLE relay (D5b), a pre-join
reachability probe with honest address-class copy (D6), a bots×players budget display (D7),
a stop-with-guests confirm (D8), shared LAN failure copy in `RuntimeFailureCopy` (D9), and
conditional realmd brute-force lockout (D11). Joins are client-only; guest characters live
in the host's database (§1 §4–§6).

**2. Friend-over-internet connectivity (§2).** Option analysis with a verdict per option:
external tunnels are the primary lane — **Tailscale recommended first**, ZeroTier second,
cloudflared a documented recipe without UI (§2 §4); UPnP/NAT-PMP is a best-effort, default-off
P3 behind explicit consent, gated on the lockout emission (§2 §3); a built-in relay is
explicitly **not** built (§2 §7). Tunnel support is the bind/advertise split — one shared
`realmlist` row must carry the tunnel address in tunnel mode (§2 §5) — plus endpoint-model v2
behind one shared security review (the CGNAT `100.64/10` rejection is test-pinned; §2 §4.4.1),
a tunnel-adapter lane in interface selection, and mode-aware invite/probe/monitor copy
(§2 §5, §10). Grounding honesty the doc insists on: the world protocol's payloads are
plaintext — packet encryption is header-only, with no per-packet MAC — so the tunnel is not
hardening polish, it is the only confidentiality layer this protocol will ever have (§2 §2).

**3. Remote account creation for friends (§3).** Two provisioning paths, never three
(§3 §2, §4.1): app-equipped friends use the **verifier path** — the friend types their own
username and password on their own device, `(s, v)` is derived locally, and only
`{username, s, v}` crosses a token-authenticated channel via a new `create_account_verifier`
relay op; the host never holds the password (§3 §3). Stock-client friends get host-generated
credentials delivered as a separate step on a channel the host treats as sensitive. Around
that: single-use invite tokens (≥128-bit, 15-minute expiry from first submission, ≤8 OPEN),
an `OPEN → SUBMITTED → APPROVED → PROVISIONED` state machine (§3 §5), a `PROVISION_PORT`
listener whose lifetime is the hosting session with three rate-limit layers (§3 §6), a host
approval UI on the Home card plus a Players route (§3 §7), and the shared moderation surface —
ban (1h/24h/permanent presets), unban, kick — plus audit that reads the success trail from
`account_logons` and failures from the `failed_logins` counter (§3 §9, §12).

**4. Per-flavor content-patch timeframe control (§4).** The client patch is FIXED per flavor —
a vanilla install always runs the 1.12.1 build-5875 client; the knob gates world **content**
only, and the UI must say exactly that (§4 §0). One pinned seed per flavor (per-stage seeds
rejected — the 414-migration hash chain stays untouched), with each stage as a reversible,
flag-set-only SQL overlay over the world DB plus runtime gates — never DELETE — keyed on a
`PocketRealm.TimeframeStage` conf key applied at world start (§4 §2, §4). The gating
mechanisms are honest per content kind: events via `game_event` windows, raid/zone entry via
the existing conditions system on areatrigger teleports, vendor/loot/quests via
`condition_id` demotion — stage markers ride `CONDITION_ACTIVE_GAME_EVENT` marker events, so
no core change is needed beyond a small door-only-entry fallback (§4 §3). No mid-session
switch; forward moves are additive; backward moves close availability but never roll back
items/levels/spells on characters — the realm-data archive restore is the reset tool
(§4 §7). Chat era-policy becomes per-stage term classes generated from the stage manifests
(§4 §5). v1 ships the vanilla lane end-to-end; TBC/WotLK coverage grows only with measured
DB pins (§4 §9).

### Recommended connectivity option, with tradeoffs

**Recommendation (§2 §4, §11): external tunnels as the primary lane, Tailscale first.**

- **Why it wins:** strongest reachability (WireGuard with DERP relay fallback works behind
  hard NAT), the stock client's hostname support fits the advertise path, single-binary
  Windows install, an honest failure mode (offline tailnet ⇒ visible connect failure, not
  half-works), zero operated infrastructure, and the tunnel supplies the confidentiality the
  protocol lacks (§2 §2, §4.1).
- **Costs and limits, as the doc states them:** every friend installs Tailscale and joins the
  tailnet — the account-plane invite stays in Tailscale, deliberately not reimplemented
  in-app; the free tier's "3 users / 100 devices" counting versus a 10-player realm is an
  open product question with plan terms not re-verified (§2 §12.1); and tunnel-membership
  removal is a manual, out-of-app revocation with unbounded lag — so the in-app account ban
  + kick pair (§1 D5b, §2 §9.3) remains the only prompt revocation on any lane.
- **Rejected as primary:** UPnP/NAT-PMP — the only option that exposes the realm to the whole
  internet (readable chat, scannable ports, a DoS target) on plaintext payloads; it also
  fails on exactly CGNAT/double-NAT networks and churns its advertised address ⇒ P3,
  default-off, consent-gated, hard-gated on the lockout emission (§2 §3). cloudflared — the
  single global advertisement row forces one fixed local world port on every joiner and
  breaks plain-LAN play while tunnel mode is on ⇒ documented recipe only (§2 §4.3). Built-in
  relay — an operated service whose only unserved niche the tunnel lane already serves ⇒
  hosted-component follow-up, decision gate recorded, not built (§2 §7).

### Consolidated decision list

**Set-level decisions the three network docs converge on:**

1. **realmd brute-force lockout: conditional emission.** `realmdConfig` emits
   `WrongPass.MaxCount = 10`, `WrongPass.BanTime = 900`, `WrongPass.BanType = 0` whenever
   `bindAddress != 127.0.0.1`; loopback-only binds omit the keys. The unconditional form is
   rejected in all three docs for the same verified reason: BanType 0 autobans the *source
   IP* into `ip_banned`, and the logon challenge's `ip_banned` gate matches loopback too —
   ten mistyped LOCAL passwords would lock the host's own client, the AuthGate bring-up
   gate, and BootRealmd out for 900 s with no in-app explanation (§1 D11; §2 §9.1, which
   adopts it as "the one decision for all three docs"; §3 §4.3).
2. **No credential in any invite artifact.** Invites carry the address block only;
   app-equipped friends route to the verifier path; stock-client credentials travel as a
   separate step on a sensitive channel (§1 §4.2 hard rule; §3 §4.1; §2 §6 implements the
   split). *Assembly note:* §2 revision 3's §6 now ships the address-only block and the
   verifier route, but §1 §4.2/§8 and §3 §13.6 still record the tunnel §6 gate as open,
   citing `design-tunnel.md:313-314` — in this set that line range is the column-width
   bullet of §2 §5, i.e. those gate rows were written against the previous tunnel revision.
   The substantive contradiction is closed by §2 rev 3; clear the stale gate rows on the
   siblings' next revision pass.
3. **`account_logons` is the success trail.** Its single write site is on the successful-
   logon path, so "last logon" reads `account_logons` and failed attempts key off the
   `failed_logins` counter plus `ip_banned`/`account_banned` rows — never off
   `account_logons` (§1 §0 item 6 correction; §3 §12 revision 3 implements exactly this).
   §1 §8's review-gate row for the remote-accounts audit is satisfied by §3's revision.
4. **Endpoint-model widening is one shared security review, not a per-design step.** Keep the
   launch/bind endpoint as strict as today; add an advertised-address type (numeric CGNAT
   `100.64/10` first, hostnames later); overturning the test-pinned `100.64.0.1` rejection is
   one coordinated review plus one shared test update landing on both platforms
   (§1 §2.4 governance note; §2 §4.4.1; §3 §13.5).
5. **One realm row, mode-aware surfaces.** CMaNGOS sends one realm entry to everyone, so
   tunnel mode must advertise the tunnel address, and every surface that renders or probes an
   address reads the *projected* advertisement and branches on address class — the invite
   card (§1 D4), the probe copy (§1 D6, tunnel branch v2-gated), the Home subtitle, and the
   tunnel card (§2 §5, §6).
6. **One mode-aware liveness implementation.** Preflight and the interface-loss monitor
   predicate are keyed per hosting mode — LAN_HOST watches the private LAN endpoint, tunnel
   hosts watch their own adapter (a `0.0.0.0` bind has no interface to lose; its loss signal
   is the upstream tool's health) — with the same 10 s session-anchored grace and graceful
   save-and-stop; §1 D1/D2 and §2 §10 must land as one implementation, not two.
7. **Socket posture.** While hosting, the game sockets are exactly TCP 3724 + TCP 8085 — no
   database socket ever. Any additional listener (§3's `PROVISION_PORT`; the optional beacon)
   owns its own port constant, its own Windows-firewall copy, and a lifetime equal to the
   hosting session, because a blocked extra port fails silently on the guest side
   (§1 §2.1 listener rule; §3 §6 amends the invariant while friend-access is on).

**Per-feature decisions:**

- **LAN host (§1):** D1/D2 mode-keyed liveness (LAN_HOST only; join preflight early-return);
  D3 virtual-adapter filter with default-route preference; D4 projected-address invite card;
  D5a Guests card — generated 16-char ≈80-bit password (`MAX_ACCOUNT_STR` cap), `gmLevel` 0
  always, in-person handoff copy, players-online line; D5b `banGuest`/`unbanGuest`/
  `kickPlayer` verbs with Ban + "disconnect now" paired in the UI; D6 probe with the
  private-target copy in v1 and the tunnel branch gated on endpoint-model v2; D7 budget
  display feeding `realPlayers` into the admission sample; D8 stop-with-guests confirm;
  D9 shared LAN failure-copy entries; D10 wiki update. Deferred: the discovery beacon
  (opt-in, display-only, no authentication claims, UDP 36124). v1 human capacity stays
  `PlayerLimit = 10` (the host's own client is one slot); raising it is an explicit product
  decision (§1 §6, §9).
- **Connectivity (§2):** plan order — (1) lockout emission scoped to non-loopback binds;
  (2) endpoint-model v2 + the shared security review; (3) bind/advertise split +
  tunnel-adapter lane + advertised-length guard (≤ the column width, fail loud, never
  truncate); (4) the "Host over the internet" card — Tailscale guidance first, ZeroTier
  second, cloudflared as a documented recipe; (5) kick + ban host verbs; (6) the mode-aware
  preflight/monitor; (7) UPnP/NAT-PMP as P3 default-off behind consent; (8) relay not built
  (§2 §11).
- **Remote accounts (§3):** verifier path canonical for app-equipped friends; host-generated
  credentials only for stock-client friends; invites single-use, 15-minute expiry from first
  submission, ≤8 OPEN; `PROVISION_PORT` bound only while a friend-access session is WORLD_READY
  and stopped with the realm; rate limiting at listener, invite, and realmd layers; ban
  presets 1h/24h/permanent with `banned_by` recorded; guests default `gmLevel` 0; no
  `locked`/`lockedIp` pinning for friends; `account delete` avoided in favor of permanent ban
  (it orphans `characters` rows) (§3 §2–§12).
- **Timeframe (§4):** client fixed per flavor — content-only gating, stated verbatim in the UI;
  one pinned seed per flavor; overlays reversible and flag-set-only, never DELETE; stage
  markers via `CONDITION_ACTIVE_GAME_EVENT` events ⇒ zero core change (except the curated
  door-only-entry fallback); `PocketRealm.TimeframeStage` applied at world start; no
  mid-session switch (the card disables while the realm runs); backward moves close
  availability but never roll back character state; the archive restore is the only reset
  tool; v1 = vanilla lane with `vanilla-1.12.1` era lists byte-equivalent to today's; TBC/
  WotLK stage data lands only at measured S4 pins (§4 §0–§9).

---

<!-- Section 1 of 4 — verbatim from out/desktop-parity/design-lan.md -->

# LAN multiplayer — desktop host design (design-lan)

Status: design, **revision 3** (round-1 and round-2 critique applied — see §10 for the
change log). Companion to `docs/wiki/Accounts-Security-and-Networking.md`
("LAN host and join") and the Android LAN lane it mirrors. Sibling designs this
revision cross-references: `design-remote-accounts.md` (§4–§6, §8) and
`design-tunnel.md` (§4–§6, §9–§10) — both live with the desktop-parity design set.

## 0. Scope and grounding

Design local-LAN multiplayer for the desktop host app: host/join topology, advertisement,
firewall, per-join session handling next to the existing bot population, the joining
client's realmlist flow, and everything else LAN play needs that nobody listed.

**Tree note:** this doc is grounded on `E:\pocket_realm_complete` — the repo the ask
names. The `C:\pocket_realm_complete` mirror's desktop tree is stale (round-1 critique,
finding 1: `diff -rq` on `desktop/src` shows dozens of differing/only-in-E: files);
every line citation below resolves on E:. This doc made no claims from the C: tree.

Grounded by reading (E: paths abbreviated; all relative to repo root):

- Android LAN lane: `android/app/src/main/java/com/pocketrealm/ui/LanScreen.kt`,
  `service/RealmService.kt`, `supervisor/AndroidRuntimeBackend.kt`,
  `supervisor/RuntimeTopology.kt`, `supervisor/RuntimeContracts.kt`,
  `supervisor/DurableRuntimeSupervisor.kt`, `supervisor/AutoLoginPolicy.kt`,
  `supervisor/RuntimeFailureCopy.kt`, `server/ServerRuntimeFiles.kt`,
  `bots/BotPopulationPolicy.kt`, `client/ClientFlavor.kt`, `storage/Settings.kt`.
- Desktop LAN lane: `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt`,
  `DesktopRuntimeBackend.kt`, `Router.kt`, `DesktopSettingsStore.kt`, `SaveExitGate.kt`,
  `ui/SettingsScreen.kt`, `ui/HomeScreen.kt`, `client/DesktopRealmlist.kt`,
  `server/ServerRuntimeFiles.kt`, `server/ServerStatusJson.kt`,
  `server/DesktopBotAdmission.kt`, `database/DesktopMariaDbEngine.kt`,
  `storage/Settings.kt` (desktop twin), `desktop/shared-sources.json`.
- Engine (read this session, E: trees): `native/cmangos/src/realmd/AuthSocket.cpp`,
  `native/cmangos/src/game/Chat/Chat.cpp`, `native/cmangos/src/game/Accounts/AccountMgr.h`,
  `native/realm-runtime/src/world_runtime.cpp`,
  `native/.build-o11-x86_64/cmangos-patched/src/game/World/World.cpp`,
  `native/playerbots/playerbot/PlayerbotMgr.cpp`.
- Sibling designs: `out/desktop-parity/design-remote-accounts.md`,
  `out/desktop-parity/design-tunnel.md` (C: copy — the set's location).
- Docs: `docs/wiki/Accounts-Security-and-Networking.md`, `docs/FEATURE-PARITY-GAPS.md` (C3).

Ran during this ask:

1. (rev 1) `java NiProbe.java` (JDK 17 Temurin, this machine) — the active adapters are
   `wlan2` (MediaTek Wi-Fi 6E, `192.168.1.217`) and `eth14` = **"Hyper-V Virtual Ethernet
   Adapter", up, `isVirtual=false`, private `172.21.208.1`**. The shared sort
   (`RuntimeTopology.kt:195-200`) keys on the `wlan`/`eth` name prefix, so `eth14` sorts
   before `wlan2` and `selectForHosting()` returns the Hyper-V address first — an address
   LAN guests cannot reach (§2.5).
2. (rev 1) `cd E:/pocket_realm_complete/desktop && ./gradlew.bat test --tests
   "com.pocketrealm.desktop.DesktopAppModelLanLaunchTest" --console=plain` → BUILD
   SUCCESSFUL; JUnit XML `tests="5" skipped="0" failures="0" errors="0"`
   (`desktop/build/test-results/test/TEST-com.pocketrealm.desktop.DesktopAppModelLanLaunchTest.xml`).
   A targeted 5-test run of the launch-gate suite — **not** the full desktop suite.
3. (rev 2) `grep -n "WrongPass" desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt`
   → no matches (rc=1): the generated `realmd.conf` emits no lockout config, and the
   engine default is off — `native/cmangos/src/realmd/AuthSocket.cpp:664-665`:
   `MaxWrongPassCount = sConfig.GetIntDefault("WrongPass.MaxCount", 0)` guarded by
   `if (MaxWrongPassCount > 0)`; `:677-678` `WrongPass.BanTime` default 600,
   `WrongPass.BanType` default false = **IP autoban** (`:691-696` inserts into
   `ip_banned`; BanType true inserts into `account_banned`, `:680-686`).
4. (rev 2) Ban/moderation surface, E: greps: `AuthSocket.cpp:466-477` — an
   `account_banned` row (`active = 1`, unexpired) rejects the logon challenge with
   `AUTH_LOGON_FAILED_BANNED/_SUSPENDED`; `Chat.cpp:115` `ban account` at
   SEC_ADMINISTRATOR (`HandleBanAccountCommand`), `:825` `unban account`
   (`HandleUnBanAccountCommand`), `:133` banlist, `:975-976` `ban`/`unban` command
   tables; `Chat.cpp:974` `kick` at SEC_GAMEMASTER (`HandleKickPlayerCommand`);
   `AccountMgr.h:34` `#define MAX_ACCOUNT_STR 16`;
   `world_runtime.cpp:1130-1152` `issue_command` runs every relay op as
   `CliCommandHolder(0, SEC_CONSOLE, …)` — so console authority outranks all of the
   above; `world_runtime.cpp:271-282` `create_account` enforces 1..16 ASCII-alnum on
   username **and** password.
5. (rev 2) `grep -n "100.64"` on
   `android/app/src/test/java/com/pocketrealm/supervisor/RuntimeTopologyTest.kt` → line 23:
   `"100.64.0.1"` sits in the required-rejected input list — the shared endpoint-model
   pin that any CGNAT/tunnel widening must overturn by test change (§2.4, §9).
6. (rev 2; re-verified in rev 3) `grep -n "account_logons"` on
   `native/cmangos/src/realmd/AuthSocket.cpp` → exactly **one** site, `:1164`, inside
   `verifyVersionAndFinalizeAuthentication` — i.e. written on the **successful** logon
   path: two lines after `BASIC_LOG("User '%s' successfully authenticated")` (`:1154`)
   and immediately after the `UPDATE account SET sessionkey = …, failed_logins = 0 …`
   reset (`:1161`). Re-run this round on both trees —
   `grep -n "account_logons" native/cmangos/src/realmd/AuthSocket.cpp
   native/.build-o11-x86_64/cmangos-patched/src/realmd/AuthSocket.cpp` returns the same
   single `:1164` site in each. This **corrects** round-1 finding 7's audit statement
   ("account_logons written only in the failed-login branch"): the failed-login branch
   (`:660-700`) updates `failed_logins` and inserts bans but writes no `account_logons`
   row.
   **Cross-design consequence (round-2 finding):** `design-remote-accounts.md`'s
   audit design still reads the inverted way (its §1 "written only on failed logon",
   its Players-screen "last failure (from account_logons)" column, and its "success
   trail needs a cmangos patch" open question). Implemented as written, that column
   would render the friend's last *successful* logons mislabeled as security failures.
   The set-side correction this doc states: success trail = `account_logons` (exists
   today, no patch needed); failed attempts = `account.failed_logins`
   (`AuthSocket.cpp:668`) plus the `account_banned`/`ip_banned` inserts (`:686-696`) —
   any "last failure" UI must key off those, not `account_logons`.

Not run / not verified (stated, not guessed): no two-machine LAN join (single host
machine); the engine's bot-session registration path was not located (§6.1 open
question); no firewall prompt was triggered; the full desktop test suite was not run
(only the targeted LAN launch suite above).

---

## 1. Where the desktop already is

The desktop already ships most of the LAN lane — this section is the honest inventory
the design builds on. `RuntimeTopology.kt` (modes, endpoint parser, launch spec,
`LanInterfacePolicy`) is a **shared** file — `desktop/shared-sources.json` lists
`supervisor/RuntimeTopology.kt` — so the desktop compiles the Android contract verbatim.

| Piece | Desktop state | Citation |
|---|---|---|
| Modes `LOCAL / LAN_JOIN / LAN_HOST` | shared enum | `RuntimeTopology.kt:8` |
| Endpoint model: canonical numeric private/link-local IPv4 only, no DNS/hostnames/URIs, ports fixed 3724 (realm) + 8085 (world) | shared `RealmEndpoint` | `RuntimeTopology.kt:14-69` |
| Launch spec invariants (LOCAL=loopback+no exposure; LAN_JOIN=private endpoint, client forced, no exposure; LAN_HOST=exact private interface + explicit `allowLanPlayers`) | shared `RuntimeLaunchSpec` | `RuntimeTopology.kt:73-99` |
| Component plan (LAN_JOIN = CLIENT only; otherwise DB→REALM→WORLD→client?) | shared | `RuntimeTopology.kt:105-113` |
| Settings: `runtimeMode`, `allowLanPlayers`, `lanAddress` (desktop JSON store; Android DataStore) | both, desktop JSON keys | `desktop/.../storage/Settings.kt:236-242,361-365,499-501` |
| Topology selector UI (Local only / Host on LAN / Join a LAN host), allow toggle, firewall copy | present | `ui/SettingsScreen.kt:126-179` |
| Address field with live `parseLan` validation + quick-pick buttons from the shared interface scan | present | `ui/SettingsScreen.kt:622-646` |
| Launch gate: LAN_HOST refused without the stored toggle, before the supervisor | present, tested | `DesktopAppModel.kt:464-478`; test `hostingWithoutTheStoredAuthorizationIsRefused` |
| Join auto-stops a live local realm (graceful, world saved) then joins | present, tested | `DesktopAppModel.kt:406-413`; test `joinStopsAnActiveLocalRuntimeBeforeJoining` |
| Malformed LAN address refuses at the gate in every LAN mode | present, tested | test `aMalformedLanAddressIsRefusedAtTheGate` |
| Server bind: `BindIP = "<endpoint>"` in realmd.conf + mangosd.conf; Console/Ra/SOAP disabled; `PlayerLimit = 10`; **no `WrongPass.*` keys** | desktop twin of the Android template | `desktop/.../server/ServerRuntimeFiles.kt:81-88,143-148,157` |
| Host realmlist projection: `UPDATE realmlist SET address = ?` on the realmd DB | present | `DesktopRuntimeBackend.kt:535-559` |
| Guest client realmlist: `realmlist.wtf` backed up (sha256) then merged (`set realmlist <host>`), restored on teardown | present (DESK-51) | `client/DesktopRealmlist.kt:55-97,104-120` |
| Host/join card copy on Home | present | `ui/HomeScreen.kt:275-304,318-327,354-355` |
| Router: **no** dedicated LAN route — LAN lives on Home + Settings (Android has `LanScreen`) | deliberate divergence | `Router.kt:8-45` |

Divergences from Android that already exist (documented, not bugs): desktop routes LAN
through Settings + the Home realm card; Android has a dedicated `LanScreen` with two
panes. The Android notification labels LAN hosting "experimental"
(`RealmService.kt:567`); the desktop card copy does not (§9 open question).

---

## 2. Protocol: binding the realm to the LAN

### 2.1 What the host binds

`startRealm` builds the spec from settings (`DesktopAppModel.kt:464-478`) and the
supervisor walks `componentPlan()` — DATABASE, REALM, WORLD (supervisor
`DurableRuntimeSupervisor.kt:90-113` in the shared tree). Both natives receive the
endpoint:

- `RealmNative.startNative(files.realmdConfig(endpoint.address).absolutePath)`
  → conf `RealmServerPort = 3724`, `BindIP = "<address>"` (`desktop/.../ServerRuntimeFiles.kt:81-88`).
- `WorldNative.startNative(config.absolutePath)` → conf `WorldServerPort = 8085`,
  `BindIP = "<address>"`, `Console.Enable = 0`, `Ra.Enable = 0`, `SOAP.Enabled = 0`
  (`desktop/.../ServerRuntimeFiles.kt:143-148`).

Everything else stays private by construction: the SQLite provider's databases are
opened in-process (file paths as connection strings), and the MariaDB lane reports
`tcpDisabled: true` (`database/DesktopMariaDbEngine.kt:231`) with a named-pipe/socket
transport.

**Socket posture, scoped precisely (this doc owns the invariant):**

- While a hosting session runs, the realm's *game* sockets are exactly **TCP 3724 +
  TCP 8085**, bound to the chosen address. No database socket ever.
- Anything else that listens is a separate, explicitly designed listener with its own
  port constant, bind rule, firewall expectation, and **lifetime = the hosting session**
  (never an always-on service keyed to a settings toggle). This doc's only such extras
  are the optional beacon (§3.2, UDP, display-only). Sibling designs add more:
  `design-remote-accounts.md` §5 proposes a `PROVISION_PORT` TCP listener — that design
  must carry its own Windows-prompt copy, bind rule, and start/stop lifecycle (its §12.6
  leaves the port open; the lifecycle is currently unspecified there — this is a
  requirement this doc places on it as the invariant's owner, since a second blocked
  port fails *silently* on the guest side: the submission never arrives and the host
  sees nothing).

### 2.2 What guests see in their realm list

Two projections make the address flow end to end:

1. **Host side (server advertisement):** after DATABASE goes READY, the supervisor calls
   `projectRealmEndpoint`, which runs `UPDATE realmlist SET address = '<host-lan-ip>'
   WHERE id = 1` on the realmd database and re-reads it to prove it stuck
   (`DesktopRuntimeBackend.kt:535-559`; supervisor call `DurableRuntimeSupervisor.kt:103-113`).
   This is what a joining client's realm list *displays*: the host's realm entry carries
   the host's LAN IP, so the client's world traffic goes to `host:8085` and not to
   loopback. A host that skips this advertises `127.0.0.1` and every guest hangs at the
   realm list — this verb is already wired on both platforms and must stay in the start
   contract.
2. **Guest side (client steering):** the guest app launches WoW.exe with the host's
   endpoint merged into `realmlist.wtf` — backup of the original bytes + sha256 taken
   on first projection of a session, only the `set realmlist` line moves, restore on
   teardown (`client/DesktopRealmlist.kt:55-97`; launch call
   `DesktopRuntimeBackend.kt:1457-1460`). `DesktopConfigWtf` conditions also carry the
   endpoint at launch (`DesktopRuntimeBackend.kt:1449-1456`).

**One realm row, one advertisement — the cross-design constraint.** CMaNGOS sends a
single realm entry to everyone (`design-tunnel.md` §5 states the same consequence
independently: "CMaNGOS sends one realm entry to everyone"). So `realmlist.address` is
a single global advertisement: whatever a future tunnel/advertise split writes there
replaces what LAN guests see. Everything in this doc that *renders* an address (D4's
invite card, D6's probe copy) therefore reads the **projected advertisement**, never
the stored `lanAddress` — see §5.2.

Ports are deliberately not user input (`RuntimeTopology.kt:11-13`, schema pins them at
`RuntimeTopology.kt:156-159`) — the **invite is just the IPv4 literal**.

### 2.3 Authorization and admission today

- Hosting requires the stored `allowLanPlayers` toggle: the model refuses
  `launch refused … allowLanPlayers` before engaging the supervisor
  (`DesktopAppModel.kt:469-475` — built directly, not via the `lanHost()` factory,
  because the factory hard-codes authorization). Tested.
- The shared constructor double-checks `allowLanPlayers` for LAN_HOST
  (`RuntimeTopology.kt:92-97`).
- A live local runtime is refused for non-join starts ("runtime is already active");
  only LAN_JOIN owns the auto-stop (`DesktopAppModel.kt:400-413`, test
  `localStartOnAnActiveRuntimeIsStillRefused`).
- **Honest exposure statement:** once hosting starts, *any* device on the LAN segment
  can reach 3724/8085 — the toggle is the only pre-start gate, and realmd will accept a
  logon attempt from anyone (unknown accounts fail SRP6 with no verifier to prove
  against, but the attempt itself is unlimited today — §2.6 closes the lockout gap).

### 2.4 Gap G1 — the desktop never re-proves the interface (mode-keyed fix)

Android's preflight refuses hosting when the stored address is not **currently** an
active private interface (`AndroidRuntimeBackend.kt:180-188`), re-checks again
immediately before realmd start and before world start
(`AndroidRuntimeBackend.kt:240-243,249-252`), and a health monitor ends the session
after the interface stays lost for 10 s (`RealmService.kt:475-498,619`). The desktop:

- `DesktopRuntimeBackend.preflight` (`DesktopRuntimeBackend.kt:412-442`) checks only the
  datadir/provider — no mode branch at all.
- `grep -rn "isCurrentPrivateInterface|LanInterfacePolicy" desktop/src/main` hits only
  the Settings quick-picks (`ui/SettingsScreen.kt:643-646`).
- `grep -n -i "monitor|health" DesktopAppModel.kt Main.kt` → no hits. **The desktop has
  no component health loop and no interface-loss monitor.**

Consequences today: pick a quick-pick address, unplug Wi-Fi, plug into Ethernet with a
new address, press Start → the spec still carries the stale stored address and realmd
binds an address no interface owns (or the wrong one). And an in-session interface
change is never noticed.

**Design D1 (preflight + start-time liveness — LAN_HOST only, by predicate):**

- Scope: the liveness legs run **only when `spec.mode == RuntimeMode.LAN_HOST`** (and
  the endpoint is therefore, by the shared constructor's own invariant, a canonical
  private/link-local IPv4 — `RuntimeTopology.kt:92-97`). This predicate is *mode-keyed,
  not topology-assuming*: a future tunnel/relay hosting mode that reuses `LAN_HOST`
  with a non-private bind cannot exist without changing the shared constructor first —
  and it must then supply its own predicate (table below) rather than inheriting this
  one. `LOCAL` runs no liveness leg (loopback never disappears); `LAN_JOIN` runs no
  server legs at all — it gets an early return after client prerequisites, mirroring
  Android's early return at `AndroidRuntimeBackend.kt:177-179` (today the desktop join
  fails `DB-NOT-SEEDED` on a fresh machine — confirmed by the passing test
  `joinStopsAnActiveLocalRuntimeBeforeJoining`, which asserts exactly that failure).
- Legs: after the datadir gates pass, for LAN_HOST:
  `!settingsSnapshot.allowLanPlayers → refuse` (defensive twin of the model gate) and
  `!LanInterfacePolicy.isCurrentPrivateInterface(spec.endpoint) → refuse` with the
  Android copy "LAN host address is not an active private IPv4 interface". Mirror the
  start-time re-checks at the top of `startRealm`/`startWorld` (the desktop
  `start(component, …)` already receives `spec` — `DesktopRuntimeBackend.kt:514-532`).

**Design D2 (interface-loss monitor — same predicate, shared policy shape):** a 1 s
tick loop in the app model that, while `spec.mode == LAN_HOST` and the phase is
WORLD_READY/RUNNING/PAUSED/CLIENT_FAILED, checks
`LanInterfacePolicy.isCurrentPrivateInterface(snapshot.realmEndpoint)`, accumulates loss
per session id (the Android session-anchored grace, `RealmService.kt:450-452`), logs the
two Android log lines verbatim (`RealmService.kt:485-487,493-495`), and runs
`supervisor.stop(StopMode.GRACEFUL)` at 10 s. Never stops underneath a user operation.

**Mode/predicate reconciliation (this doc owns the table; sibling designs slot in):**

| Hosting kind | Mode key | Liveness predicate | Monitor policy |
|---|---|---|---|
| Local realm | `LOCAL` | none (loopback) | none |
| LAN host (this doc) | `LAN_HOST` + private `RealmEndpoint` | `isCurrentPrivateInterface(endpoint)`; refuse pre-start | 10 s grace → graceful stop (§2.4) |
| Tunnel host (`design-tunnel.md` §10) | must introduce its own key/bind representation (its bind is a config value, `0.0.0.0` or adapter IP — explicitly "not a `RealmEndpoint`", tunnel §4.2) | tunnel-adapter-present predicate ("only genuine adapter loss triggers the window", tunnel §10) | reuse the 10 s grace **shape** with the tunnel predicate |
| Any future mode | its own key | its own predicate | same grace shape |

Under this table the round-1 cross-design failure is closed by construction: D1/D2
cannot refuse a tunnel-hosted start or kill a healthy tunnel session, because tunnel
hosting never presents a `LAN_HOST`-with-private-endpoint spec — and if it ever tried,
the shared constructor (not this doc) refuses it first (`RuntimeTopology.kt:92-97`).

**Shared-invariant governance (recorded, not scheduled here):** `design-tunnel.md` §4.1
and `design-remote-accounts.md` §12.7 both need the endpoint model widened (hostnames,
CGNAT 100.64/10). The current rejection is **pinned**: `RuntimeTopologyTest.kt:23`
lists `"100.64.0.1"` among required-rejected inputs (verified this round). Widening a
shared, cross-platform, security-shaped parser with a pinned test is one coordinated
review + one shared test update (the file is in `shared-sources.json`, so one edit
lands on both platforms) — it is neither design's routine step. This doc's position:
keep the private-range-only contract for v1; the widening is its own review.

### 2.5 Gap G2 — virtual adapters pollute the interface list (measured)

The probe in §0.1 shows `eth14` "Hyper-V Virtual Ethernet Adapter" is up with private
`172.21.208.1` and `isVirtual == false`, so it passes every shared filter, and the
`wlan`/`eth`-prefix sort puts it **first**. A one-click host on this machine defaults to
an address no guest can reach, and D1's liveness check would happily accept it (it *is*
a live private interface).

**Design D3 (desktop interface filter seam):** do not retune the Android behavior of the
shared file. Follow the `DesktopRealmlist` precedent (desktop twin wrapping a shared
projection, `DesktopRealmlist.kt:9-31`): a desktop-side `DesktopLanInterfaces` that
filters `LanInterfacePolicy.available()` by display name before quick-picks and before
D1's liveness re-check — exclude display names matching
`Hyper-V | Virtual | vEthernet | Wi-Fi Direct | WAN Miniport | Loopback | WFP | LightWeight Filter`
and prefer the interface that owns the default route (resolved via a UDP `connect()` to
a private-range probe address, reading the local socket address — in-process, no
packets of consequence). The stored-address validation (`parseLan`) stays unchanged —
the filter only shapes which interfaces the app *offers* and re-proves.

### 2.6 Gap G3 — realmd's brute-force lockout is off (fix: emit it)

Verified this round: the desktop `realmd.conf` emits no `WrongPass.*` keys
(`grep` → no matches), and the engine default disables the lockout entirely
(`AuthSocket.cpp:664-665`, `if (MaxWrongPassCount > 0)`). So today a LAN device — or any
future more-exposed lane — can try passwords against a known guest username forever.
`design-remote-accounts.md` §5.3 already wrote the fix for its lane; this doc adopts it
as a **host-wide** invariant, because the rationale applies to every non-loopback bind
("protects approved accounts from online password guessing at 3724, which the invite
layer cannot see").

**Design D11 (lockout config — conditional emission, decided):** `realmdConfig` emits
the lockout keys **when and only when `bindAddress != 127.0.0.1`** (i.e. every
LAN/tunnel/exposed lane). The unqualified "emit always" form is **rejected** after the
round-2 self-lockout analysis, and this doc's own revision-2 "always is also acceptable
— loopback gains a harmless lockout" parenthetical was wrong; it is removed:

```
WrongPass.MaxCount = 10
WrongPass.BanTime  = 900
WrongPass.BanType  = 0        # IP autoban into ip_banned (AuthSocket.cpp:691-696)
```

Why conditional (verified this round):

- `BanType = 0` inserts the **source IP** into `ip_banned`
  (`AuthSocket.cpp:691-696`), and the logon challenge's `ip_banned` gate matches any
  source — including loopback: `SELECT … FROM ip_banned … AND ip =
  '<GetRemoteAddress()>'` at `AuthSocket.cpp:416-423`, rejecting with
  `AUTH_LOGON_FAILED_FAIL_NOACCESS`. So under unconditional emission, ten mistyped
  passwords at the user's own **LOCAL** login screen insert a 900-second `ip_banned`
  row for `127.0.0.1` — the host's own client, the AuthGate bring-up gate, and
  BootRealmd all then fail realmd logon with only the client's native
  "Unable to connect" as a symptom, and no in-app explanation.
- On a loopback-only bind the lockout buys nothing anyway: only local processes can
  connect, and a local process can read the database file directly. Security that only
  fires against the owner is pure downside.

Set-side requirement (one decision, stated here because this doc owns the socket
posture): `design-remote-accounts.md` §4.3's unconditional "applies to LOCAL, LAN_HOST,
and the tunnel/UPnP lane on every boot" and `design-tunnel.md`'s unqualified plan step
must adopt the same condition (or an explicit loopback exemption) — otherwise the set
ships the LOCAL self-lockout as a designed behavior.

Notes: `BanType = 0` (IP autoban, the engine default when enabled) is right for LAN —
each guest device has its own LAN IP, so a lockout hits only the offender; a shared-NAT
household on a *remote* lane should revisit this (account ban instead) — flag rides
with the tunnel design. The counter self-heals: a successful logon resets
`failed_logins = 0` (`AuthSocket.cpp:1161`). Conf is regenerated at every start
(`realmdConfig` writes `run/realmd.conf` fresh), so the keys take effect on the next
hosting start with no migration.

---

## 3. Advertisement: how a joining device gets the address

### 3.1 Parity baseline: the copyable address (v1)

Android's doctrine is explicit: "Discovery, hostnames, IPv6, UPnP, and mDNS are not
used" (`LanScreen.kt:375`), and the wiki repeats it
("no automatic public discovery, router port forwarding, or Internet server mode").
Both platforms agree the advertisement is the **numeric private IPv4 literal**, ports
fixed. The desktop already shows it on the Home card while hosting
(`HomeScreen.kt:291,296`: "The world is hosted on $endpointAddress; LAN players can
join.").

**Design D4 (invite card — reads the projected advertisement):** while
WORLD_READY/RUNNING in LAN_HOST, render the Home card's address line with a **Copy
address** button (plain `192.168.1.50` — ports are fixed, so the invite is the literal
alone; a second line spells out "TCP 3724 + 8085" for support conversations). The
address rendered is the **projected** `realmlist.address` (§2.2), not the stored
`lanAddress`, so the card cannot promise an address the realm no longer advertises.
Tunnel-awareness rule (cross-design, resolves round-1 finding 5; **dormant until a
tunnel mode can project a non-private address** — v1's projection is always the stored
private `lanAddress`, so the row below cannot fire before `design-tunnel.md` §4.1's
advertise/bind split exists; it is specified now so the tunnel design inherits a
defined behavior instead of inventing one): if the projected address is not a
private-range IPv4 (tunnel mode advertising 100.x / a hostname), the card adds one
warning row — "Your realm advertises a tunnel address; players on your physical LAN
(outside the tunnel) will see the realm but cannot reach it" — which is exactly the
failure `design-tunnel.md` §5 documents and tells the tunnel design's host card to
carry. Joining side needs no new UI: the Settings address field already
validates pasted input live (`ui/SettingsScreen.kt:622-641`) and the Home join card
launches it (`HomeScreen.kt:318-327`).

### 3.2 Optional stage: a LAN discovery beacon (explicitly opt-in, after parity)

If auto-discovery is wanted later, the shape — one honest story, no crypto claims:

- Host side: while hosting, every ~2 s broadcast a plain JSON datagram on UDP **36124**
  (new product constant, deliberately not 3724/8085) bounded to the bound interface:
  `{magic:"PREALM", schema:1, name, address, phase, flavorId, build}`.
- **The beacon carries no authority and no authentication — by design.** It is a
  display-only hint. Any device on the segment can broadcast the same datagram, so the
  worst case is a pre-filled text field in the join pane pointing at a wrong address;
  the client then proves the *real* host the only way that matters — completing the
  SRP6 logon at 3724 against an account the host provisioned. No signature, no HMAC,
  no per-install key: nothing in the payload is worth authenticating, and pretending
  otherwise would be security theater.
- Join side: the join pane listens only while it is open; a found host fills the address
  field and is **never auto-joined**.
- Scope honestly: Android has no beacon (`LanScreen.kt:375`). Shipping one desktop-only
  is a documented desktop affordance (Windows desktops sit on wired always-on LANs where
  broadcast is reliable); porting it to Android is a separate parity decision (§9).
  If built, it satisfies the §2.1 listener rule (port constant, lifetime = hosting
  session).

Not designed in this doc beyond this sketch: v1 ships D4 (copy/paste), which fully
unblocks LAN play and is what Android documents.

### 3.3 Firewall guidance (Windows)

One process owns realmd+world on the desktop ("the four components served by ONE
process", `DesktopRuntimeBackend.kt:105-122`), so Windows Firewall's first-host prompt
is **one prompt for the app's own executable** (the jpackage image's `PocketRealm.exe`)
covering both game ports. There is no separate realmd.exe/world.exe to authorize, and
no database port ever (`tcpDisabled: true`).

Copy design (extends the existing card at `ui/SettingsScreen.kt:154-160`):

- Keep: "Windows Firewall may ask to allow them on your private network the first time
  you host — allow access so LAN players can connect."
- Add (support line, also into `docs/wiki/Accounts-Security-and-Networking.md`):
  - The prompt must be answered **Allow** for **Private networks**. If the prompt was
    cancelled, or the LAN is marked Public in Windows, guests cannot connect even
    though the host sees its own realm online — fix: Windows Settings → the network →
    set **Private**, then host again (or run the netsh rule below once from an admin
    prompt).
  - Reference rule for the wiki/support bundle (covers the two game ports):
    `netsh advfirewall firewall add rule name="PocketRealm LAN" dir=in action=allow
    protocol=TCP localport=3724,8085 profile=private`
  - No UPnP, no port forwarding, no inbound rules beyond the app binary — matching the
    wiki's "no router port forwarding" posture.
- **Listener caveat (cross-design):** the netsh rule and the "one prompt" claim cover
  the two game ports only. Any sibling listener (`PROVISION_PORT` from
  `design-remote-accounts.md` §5; the UDP beacon if built) triggers **its own** prompt
  and must ship with its own firewall copy — a blocked extra port fails silently on the
  guest side (the request simply never arrives, no host-side signal), so the design
  that adds the port owns the copy that prevents that dead end. This doc requires that
  of them under §2.1's listener rule.
- A support-bundle note (Diagnostics screen) already carries the app version/roots;
  add the current `runtimeMode` + bound address so a stuck LAN session reports its own
  topology.

---

## 4. Per-join session handling (alongside the bots)

### 4.1 What a join is, mechanically

LAN_JOIN is **client-only**: `componentPlan()` is `[CLIENT]`
(`RuntimeTopology.kt:105-113`), the shared supervisor starts no local services, and the
supervisor's stop/recovery order knows the join shape (world stays null,
`DurableRuntimeSupervisor.kt:903-912`). On the desktop the join:

1. stops a live local runtime gracefully first (world save) — `DesktopAppModel.kt:406-413`;
2. launches the managed/folder client against the host endpoint: identity gate →
   add-on projection → tweaks patch → Config.wtf overlay → `realmlist.wtf` merge → spawn
   (`DesktopRuntimeBackend.kt:1399-1470`);
3. the client then does the normal 1.12 login against `host:3724` (realmd → SRP6 →
   realm list showing the host's projected realm → world at `host:8085`).

The guest app's own datadir/LLM/bots are untouched and unrequired (after D1's join
preflight early-return).

### 4.2 Guest accounts: one cross-design system, LAN is the local lane

Round-1 finding 2 stands: three provisioning stories must not all ship. The
reconciliation this doc commits to (the LAN side of it; the remote side is
`design-remote-accounts.md` §3–§6):

- **Remote/tunnel provisioning = the verifier path, exclusively.** A friend who is not
  physically with the host gets an account through `design-remote-accounts.md`'s
  single-use invite token + host approval + `create_account_verifier` flow, where the
  password never leaves the friend's device (only `(s, v)` crosses — its §3 decision).
  This doc does not duplicate or re-derive that path.
- **LAN provisioning = direct handoff, explicitly bounded.** For a friend in the room,
  the host-generated credential handoff (below) is acceptable *because it is proximate*:
  the secret travels by screen/verbal exchange, never through a paste channel.
- **Hard rule — no invite text ever embeds a working username+password.** A credential
  pasted into Discord/email is a permanent working credential travelling over an
  uncontrolled channel, and it contradicts the remote design's own trust model. Tunnel
  invites carry the *address block only*; accounts for tunnel friends go through the
  verifier path.
  **Set status (round 2, still live):** this rule is asserted here and seconded by
  `design-remote-accounts.md` §4.1, but **`design-tunnel.md` §6 has not been revised**
  — its invite block still renders "2) Your account (created by me, keep private):
  username … password …" inside the copied text (`design-tunnel.md:313-314` in the
  current set), with no verifier-path route for the app-equipped friends it addresses
  in the same block ("if you use the Pocket Realm app: paste … into the Join pane") —
  exactly the class for which remote-accounts §2/§4.1 make the verifier path
  canonical. Until design-tunnel lands a matching revision (remove the credential line
  from the invite block; add the verifier-path step), **the set is contradictory, and
  an implementer reading design-tunnel alone ships the anti-pattern the other two docs
  forbid.** This is recorded as a set-level review gate on the tunnel design (§8);
  "supersedes" is prose, the gate is what enforces it. What this doc controls
  regardless of tunnel's text: no LAN surface renders a credential inside invite text —
  D4's invite card is address-only, and D5a's generated credentials are displayed only
  in the Guests card with the "in person, don't paste into a chat" copy.

**Design D5a (host guest-account surface, LAN handoff):** a "Guests" section on the
host's Home card (visible only while hosting):

- **Create guest account** — username chosen by the host, password **generated**:
  16 characters (the engine cap — `MAX_ACCOUNT_STR = 16`, `AccountMgr.h:34`; the relay
  enforces 1..16 ASCII-alnum on both fields, `world_runtime.cpp:276-282`), uniform
  random over an unambiguous 32-symbol alphabet (no `0/O/1/l/I`) ≈ 80 bits. The
  generator lives next to the provision verb so both platforms share it. Result copy:
  "Give this to your friend, in person: username `<u>`, password `<p>` — they type it
  at the login screen. Don't paste it into a chat." Copyable as one line (the copy
  button is for typing convenience at the same machine, not for channel transport).
  `gmLevel = 0` always for guests (GM outranks the login queue — §6.1 — and grants
  console-class powers; elevating a guest is a deliberate, separately-confirmed act of
  the remote design's §9 surface, not this one).
- **Players online** — `activeSessions` from the world status
  (`server/ServerStatusJson.kt:42`, status index 7) plus `realPlayers` from
  `observeWorldPresence` (`DesktopRuntimeBackend.kt:657-668`) rendered as
  "N players in world (you + M guests)". This fills the exact placeholder Android
  already ships ("no remote player tracking yet; this section appears when the realm
  reports sessions", `LanScreen.kt:174-179`) — both platforms can share the same data
  contract.

### 4.3 Design D5b — revocation: ban, unban, kick (not deferred)

Round-1 finding 2/6 stand: the surface with the weakest admission control (any LAN
device can reach 3724/8085) cannot be the one with no revocation. The machinery is one
relay verb away — the console authority already exists in-process:

- `issue_command` runs any console command at SEC_CONSOLE
  (`world_runtime.cpp:1130-1152`, verified this round), which outranks the command
  levels below.
- `ban account <name> <duration> <reason>` — SEC_ADMINISTRATOR (`Chat.cpp:115`),
  handler writes an `account_banned` row; enforcement is at the **logon challenge**
  (`AuthSocket.cpp:466-477`, verified) — so a ban keeps an already-connected guest in
  world until their next logon.
- `unban account <name>` — SEC_ADMINISTRATOR (`Chat.cpp:825`); `banlist account`
  feeds a ban-state column (`Chat.cpp:133`).
- `kick <player>` — SEC_GAMEMASTER (`Chat.cpp:974`, `HandleKickPlayerCommand`) —
  disconnects the live session the ban alone cannot.

**Design:** three owner-guarded desktop world-control verbs, siblings of the existing
DESK-116/122 family (`setBotTarget`/`pauseWorld` guard shape,
`DesktopRuntimeBackend.kt:742-754,798-820`), each wrapping the console command through
the same relay path `create_account` uses (`world_runtime.cpp:283`):

- `banGuest(owner, username, duration, reason)` → `ban account …`; output scanned for
  the existing result-scan style (`create_account`'s "already/exist" mapping,
  `world_runtime.cpp:286-289`).
- `unbanGuest(owner, username)` → `unban account …`.
- `kickPlayer(owner, characterName)` → `kick …`; offered **together with** Ban in the
  guest row UI as the paired action ("Ban + disconnect now"), because a ban alone
  leaves the session alive until next logon (§7's residual-exposure note).

**Residual exposure, stated honestly:** a banned-but-connected guest keeps playing
until kicked or until they log out; with `PlayerLimit = 10`
(`desktop/.../ServerRuntimeFiles.kt:157`) one un-rotated credential or un-kicked
griefer occupies 1/10th of realm capacity until removed. Mute/teleport stay with the
host's console authority (`mutetime` column exists, per `design-remote-accounts.md`
§1/§9) and are not UI in this doc. Account *deletion* is deliberately absent (it
orphans `characters` rows — same reasoning as the remote design's §8).

### 4.4 Join-mode auto-login is already safe (verified, keep it)

`autoLoginIfEnabled` runs the shared gate (`AutoLoginPolicy.kt:14-23` — off without a
user-provisioned identity), then proves the stored credential against the **local
world's** owner before typing anything. In join mode there is no local world:
`provisionAccount` answers `WORLD_NOT_READY` (`DesktopRuntimeBackend.kt:955-959`), the
proof disposition is `AUTHORITY_UNAVAILABLE`, and the model logs "the saved account is
kept and login stays manual" and returns before a single keystroke
(`DesktopAppModel.kt:953-1001`; disposition contract `AutoLoginPolicy.kt:41-66`). So a
joiner's stored single-player credentials are **never typed at the host's login screen**
— the guest logs into the host-provisioned account manually. Keep this; add one line of
join-pane copy: "You'll log in with the account your host created for you — your local
account stays yours." (Per-host credential memory is a possible follow-up; the stored
account is a single slot today, and silently reusing it across realms is the bug we do
not want.)

### 4.5 Characters live on the host

Because a join is client-only, every guest character is created and persisted in the
**host's** characters database. Design copy (join pane + invite card): "Characters you
play here are saved on the host's realm — they appear wherever that host's realm runs,
and stop existing for you when the host saves and exits." Diagnostics hook:
`characterPersistence` (`DesktopRuntimeBackend.kt:871-882`) already answers per-character
persistence facts for exactly this conversation. Cross-device character transfer and
realm sync stay the Android *planned* cards (`LanScreen.kt:207-235`) — desktop parity for
them is "planned", not built, and should ship as the same honestly-labeled cards when it
ships at all.

---

## 5. The joining client's realmlist flow (end-to-end walk-through)

1. Guest sets topology "Join a LAN host", enters/pastes `192.168.1.50` (live-validated,
   private-range enforced — public IPs and hostnames refuse with the parser's own copy,
   `ui/SettingsScreen.kt:622-641`, `RuntimeTopology.kt:44-61`).
2. **(D6)** The join pane runs a one-shot reachability probe: TCP connect to
   `<addr>:3724` with a ~2 s timeout, plus `<addr>:8085` shown as the world port.
   Rationale: today a mis-typed address costs a full client launch + client-side
   "Unable to connect" with no app-level diagnosis. One copy branch ships in v1:

   - **Private-range target, probe fails:** "No realm answered at 192.168.1.50:3724.
     Check the host's realm is online and both devices are on the same network — if the
     host just enabled hosting, Windows Firewall on the host must have allowed Pocket
     Realm on private networks."

   **The tunnel-address branch is v2-gated, not v1 code.** Revision 2 specified a
   "non-private target" probe-copy branch; round 2 correctly flags it as dead code as
   the doc stands — the Settings field refuses any non-`parseLan` address at validation
   time (`ui/SettingsScreen.kt:622-641`; the accepted set is 10/8, 172.16/12,
   192.168/16, 169.254/16 — `RuntimeTopology.kt:64-68`, no 100.64/10), so a tunnel
   address never reaches a probe. Until `design-tunnel.md` §4.1's endpoint-model v2
   widens the join grammar, the honest surface for tunnel-shaped input is the field's
   own validation copy: "Pocket Realm joins LAN addresses only. A tunnel address
   (Tailscale/ZeroTier) needs the internet-play lane, which is not built yet." When
   the widening lands, the probe copy for a non-private target becomes live and reads:
   "This address is not a LAN address. You will be able to log in, but the realm list
   will offer a world you cannot reach unless you are inside the host's tunnel." (The
   pass-3724-hang-at-realm-list failure is `design-tunnel.md` §1's named failure mode;
   the probe cannot detect it — TCP 3724 accepts before the client ever reads the realm
   entry — so the honest fix is the address-class copy, not a deeper probe.)
3. Guest presses **Join LAN game** → local runtime (if any) saves and stops
   (`DesktopAppModel.kt:406-413`) → client launches with `set realmlist 192.168.1.50`
   merged (original backed up, DESK-51) → login screen.
4. Guest types the host-provisioned account (§4.2) → realm list shows the host's realm
   (host DB projection, §2.2) with its real address → enter world at `192.168.1.50:8085`.
5. Exit: closing the client tears the session down normally; the guest app restores the
   original `realmlist.wtf` on the teardown paths (`DesktopRealmlist.kt:78-97`,
   stop path `DesktopRuntimeBackend.kt:1606-1650`). "Save & exit returns you to your own
   realm" (Android copy `LanScreen.kt:343-345`) maps on desktop to switching topology
   back and starting the local realm; the DESK-25 spent-lifetime rule still applies to
   the *local* world only (the join never spent it — `HomeScreen.kt:328-334` already
   words this correctly).

Build gating of step 3→4: realmd advertises its accepted client builds from the flavor
registry (`ClientFlavor.realmdAcceptedBuilds`, `client/ClientFlavor.kt` — "Correction
C1": `realmbuilds varchar(64)`, never TrinityCore's `gamebuild`). A guest client of the
wrong build is rejected by realmd's own auth with the client's native "Unable to
connect" — there is nothing app-level to add on v1's single qualified vanilla lane
(`ServerRuntimeFiles.kt` `serverDatabases()` requires exactly one qualified flavor,
Android tree `server/ServerRuntimeFiles.kt:368-379`). The per-flavor LAN join lane is
already tracked as gap C3/P1-P3 in `docs/FEATURE-PARITY-GAPS.md:210` — when TBC/WotLK
qualify, both projections (`realmbuilds` seed + guest client build gate) must key off
`ClientFlavors`, not the vanilla lane.

---

## 6. N-human bot budget (humans + existing bot population)

### 6.1 What the engine actually enforces (verified in the pinned sources)

- The world conf ships `PlayerLimit = 10` (`desktop/.../ServerRuntimeFiles.kt:157`).
- The login queue engages only for low-security accounts:
  `if (pLimit > 0 && Sessions >= pLimit && s->GetSecurity() == SEC_PLAYER)
  { AddQueuedSession(s); … }`
  (`native/.build-o11-x86_64/cmangos-patched/src/game/World/World.cpp:275-279`).
  GM-level accounts never queue — one more reason guest accounts are always gmLevel 0.
- Bot sessions are minted `SEC_PLAYER` (`native/playerbots/playerbot/PlayerbotMgr.cpp:1966`,
  `RandomPlayerbotFactory.cpp:345`) but are **not world sessions**: bot login/teardown
  code tests `if (!sWorld.FindSession(botWorldSessionPtr->GetAccountId())) //Real player
  sessions will get removed later` (`PlayerbotMgr.cpp:282`) — the bot's `WorldSession`
  lives in the playerbot holder maps, not in `World::m_sessions` (consistent with the
  600-bot presets coexisting with `PlayerLimit = 10` today, and with
  `GetActiveSessionCount()` driving the queue/limit at `World.cpp:316,343`).

*Not verified:* the exact statement that adds a bot session to the world (no
`AddSession` call exists anywhere in the playerbot tree I grepped). The conclusion
"humans consume PlayerLimit, bots do not" is the only reading consistent with the code
above and with shipped 600-bot presets, but it should be confirmed once in a soak
before the copy below promises a guest count (§9 open question).

### 6.2 The budget rule

- **Human capacity v1 = `PlayerLimit` (10) real sessions; the host's own client is one
  of them**, so nine guests can sit in world. Keep 10 in v1 (Android parity, honest
  scale); if field sessions want more, raising it is a one-line LAN_HOST-only conf
  change in `worldConfig` — a decision for later, not hidden in this design (§9).
- **Bots are budgeted by the existing admission machinery, not by session slots.**
  `DesktopBotAdmission` samples world p99 / memory / CPU and lowers the effective
  `PocketRealm.BotTarget` (`DesktopBotAdmission.kt:24-55`, `DesktopRuntimeBackend.kt:328-331`);
  `setBotTarget`/`startBotProfile` retarget live (`DesktopRuntimeBackend.kt:798-864`).
  Humans add world-tick and loot/AI-interaction load, so they are *admission pressure*:
  **v1 does not subtract humans from the bot target** (the host's own client already
  coexists with bots today); the honest budget display is the change:
- **Design D7 (budget display + admission input):**
  - Home card (hosting): "Bots: `effectiveTarget` (admission
    `<adapted ? "lowered: " + reason : "steady">`) · Players: N (you + M guests)" — all
    four numbers already exist (`DesktopBotAdmissionVerdict` fields; `activeSessions`;
    `realPlayers`).
  - Feed `realPlayers` into `BotResourceSample`'s next schema as a plain observation
    field so the shared `BotAdmissionController` *may* tighten under guest load in a
    later tuning change — no behavior change rides on this doc.
  - The Android twin gets the same display contract from its world status lane, keeping
    the LanScreen placeholder fill (§4.2) symmetric.

### 6.3 Chat, LLM bots, and guests

Guests are ordinary world sessions, so: `/say`, `/yell`, `/party`, whispers and the
world-chat opcode path work with the host's bots unchanged; the bot LLM lane keys off
player identity, and the host's control verbs (`worldChat`, `resetState`,
`llmMemoryState`, `DesktopRuntimeBackend.kt:890-929`) remain host-app-only (owner-guarded
by `liveWorldRefusal`, `DesktopRuntimeBackend.kt:742-754`) — a guest can never steer the
host's bots through them. One design note: bot LLM speech is local to the host process;
guests simply *hear* the resulting chat. No per-guest LLM billing/privacy surface is
needed; the wiki's data-storage posture covers it.

---

## 7. Save/exit and lifecycle with guests present

- **Save & exit** = `supervisor.stop(StopMode.GRACEFUL)` (`DesktopAppModel.kt:487-497`):
  world save → client destroy (only the app's own client process —
  `DesktopRuntimeBackend.kt:1223-1242`) → world → realm → datadir at rest. Guests'
  processes are on their machines; they experience a disconnect the moment the world
  stops, after the save.
- **Design D8 (stop-with-guests confirm):** when `realPlayers > 1` (or
  `activeSessions > 1`), "Save & exit" becomes a two-step confirm:
  "3 players are in the world. Saving and exiting disconnects everyone and stops the
  realm." The shutdown-hook drain (logoff/power loss — `SaveExitGate.kt` documents the
  exact contract: save world → stop natives → client destroy, DESK-109/111) cannot
  confirm; that is accepted and documented — the hook prioritizes the save, which is
  the right order under power loss.
- **Interface loss mid-session** (D2): the graceful stop path above runs; guests get
  disconnected; the Home card's ERROR/STOPPED copy must say why — the D9 copy below.
- **Restart hosting:** DESK-25's one-world-per-process lifetime means the host relaunches
  the app to host again (`HomeScreen.kt:328-343`). Copy for hosting: "The world ran
  once; a restart needs a fresh app process" is already there — add "your guests will
  need to log in again after you restart." Guests' characters are safe (host DB).
- **Revocation lag (cross-design note):** a ban binds at the next logon challenge
  (`AuthSocket.cpp:466-477`); a kick is the immediate lever (D5b). On the tunnel lanes,
  `design-tunnel.md` §9's "regenerate the tunnel membership" is a manual, out-of-app,
  async revocation with unbounded lag — until that design exposes it in-app, the
  account ban + kick pair (D5b) is the only prompt revocation on any lane, which is
  exactly why it cannot stay deferred.

**Design D9 (LAN-aware failure copy, shared file):** extend `RuntimeFailureCopy`
(`supervisor/RuntimeFailureCopy.kt` — in the shared manifest, so both platforms get it)
with:

| Raw detail | Humanized copy |
|---|---|
| `LAN_HOST_INTERFACE_LOST` (new: D2's stop reason detail) | "Your network connection changed, so hosting stopped and the world was saved. Pick your current address in Settings → LAN play and start hosting again." |
| `LAN host address is not an active private IPv4 interface` (Android preflight copy; D1 adopts it) | "The LAN address you saved isn't active on this PC right now. Pick a detected address in Settings → LAN play." |
| join preflight datadir refusal (before D1) / after D1 never occurs for join | (D1 makes join refuse earlier; copy: "Joining doesn't need a local realm, but the game client must be importable first — open Import.") |
| probe failure (D6, private target) | the §5.2 two-sentence copy (firewall mention included) |
| probe target is not a LAN address (D6, tunnel copy) | the §5.2 address-class copy |
| `AUTH_LOGON_FAILED_BANNED` surfaced after a host ban | "<name> is banned on this realm; they can't log in until you unban them." |

Raw details stay in the logs verbatim; only the UI decode changes (that is the file's
contract, `RuntimeFailureCopy.kt:1-12`).

---

## 8. Work list (each item cites its section)

| # | Item | Type | Where |
|---|---|---|---|
| D1 | LAN_HOST-only preflight+start liveness legs; LAN_JOIN preflight early-return; mode/predicate table | parity fix | `DesktopRuntimeBackend.preflight/start` (§2.4, §5) |
| D2 | 1 s interface-loss monitor, LAN_HOST predicate, session-anchored 10 s grace, graceful stop | parity fix | app model loop (§2.4) |
| D3 | Desktop interface filter seam (virtual-adapter exclusions + default-route preference) | desktop hazard fix | new `DesktopLanInterfaces` twin (§2.5) |
| D4 | Invite card reading the **projected** address + tunnel warning row | UX | `HomeScreen` realm card (§3.1) |
| D5a | Guests card: create guest account (gmLevel 0, 16-char ~80-bit generator, no-channel handoff), players online | feature | `provisionAccount` + `ServerStatusJson` (§4.2) |
| D5b | Revocation verbs: `banGuest` / `unbanGuest` / `kickPlayer` via the console relay; Ban+disconnect paired UI | feature (new in rev 2) | `DesktopRuntimeBackend` world-control family (§4.3) |
| D6 | Pre-join reachability probe with address-class copy (private vs tunnel) | feature | join pane (§5.2) |
| D7 | Budget display (bots admission × players online); realPlayers into the sample feed | UX/telemetry | Home card + `BotResourceSample` (§6.2) |
| D8 | Stop-with-guests confirm | UX | `saveAndExit` lane (§7) |
| D9 | `RuntimeFailureCopy` LAN entries incl. ban copy (shared) | copy | shared supervisor file (§7) |
| D11 | `WrongPass.MaxCount/BanTime/BanType` emitted by `realmdConfig` — **conditional, non-loopback binds only** (unconditional form rejected: LOCAL self-lockout) | security fix | `desktop/.../server/ServerRuntimeFiles.kt` (§2.6) |
| D10 | Wiki update: firewall rule, guest-account model (handoff rules + revocation), join flow, lockout | docs | `docs/wiki/Accounts-Security-and-Networking.md` (§3.3, §4, §2.6) |
| — | Discovery beacon (UDP 36124, opt-in, display-only, no auth claims) | deferred stage | §3.2 sketch only |
| — | Endpoint-model widening (hostnames/CGNAT) | owned by tunnel + remote-accounts designs; shared pinned-test review | §2.4 governance note |
| — | **design-tunnel §6 revision gate:** remove the embedded credential from the invite block (`design-tunnel.md:313-314` in the current set), route app-equipped friends to the verifier path | set-level review gate blocking the set's consistency | §4.2 hard-rule status |
| — | **design-remote-accounts audit correction:** "last failure" UI must key off `failed_logins`/ban rows, not `account_logons` (success-path write, `AuthSocket.cpp:1164`) | set-level review gate | §0 item 6 |

---

## 9. Open questions

1. **Bot vs `PlayerLimit` interplay at the cap:** the queue is `SEC_PLAYER`-only and bot
   sessions live outside `m_sessions` (§6.1 evidence), but the statement that registers
   a bot session with the world was not located in the repo. Soak-verify: two guests +
   a 600-bot preset at `PlayerLimit = 10` — do guests ever queue?
2. **Raise `PlayerLimit` for LAN_HOST?** 10 real sessions = 9 guests; a LAN_HOST-only
   conf override (e.g. 40) is one line in `worldConfig` but changes the soak/offer
   surface. Product decision.
3. **Discovery beacon:** desktop-only opt-in (my recommendation) or blocked until
   Android adopts it, so the "no discovery" doctrine stays cross-platform?
4. **"(experimental)" label parity:** Android's notification calls LAN hosting
   experimental (`RealmService.kt:567`); the desktop Home copy does not. Pick one.
5. **Per-host guest credentials:** the stored account is a single slot
   (`UserAccountStore`); should the join pane remember the last host's guest account
   (opt-in), or always manual typing (current, verified-safe)?
6. **Admission × humans tuning:** once `realPlayers` feeds the sample (D7), does any
   real session data justify subtracting humans from the bot target, or does admission
   alone already shed correctly under guest load? Needs a soak.
7. **Android LanScreen placeholder:** when D5a's players-online contract lands, does the
   Android screen fill from the same supervisor field (shared snapshot) to keep the
   twin honest?
8. **Ban scope knobs:** duration presets for `ban account` (the remote design's §8
   proposes 1h/24h/permanent) and whether a LAN ban should also offer `BanType = 1`
   (account ban) semantics for shared-NAT guests — needs the tunnel/remote lane's
   audience decided first.
9. **Kick surface:** `kick` is a player-command (`Chat.cpp:974`, SEC_GAMEMASTER); the
   console relay can run it, but confirm in a soak that the console-context kick
   handles a disconnected/ghost session the way the world session timeout does
   (`design-tunnel.md` §10 notes the ghost-ejection timing as unverified in-tree).

---

## 10. Revision log

### Revision 2 (round-1 critique)

- **Finding 1 (stale-tree siblings):** no change to this doc's claims — the critique
  confirmed this doc's ground truth on E: (its "checks that passed" list). Added the
  §0 tree note so the divergence is explicit, and now cross-reference both sibling
  designs by name.
- **Finding 2 (three account systems; no revocation):** §4.2 rewritten as the
  cross-design reconciliation (remote = verifier path only; LAN = bounded direct
  handoff; **hard rule superseding tunnel §6's embedded credentials**); D5 split into
  D5a (with generator strength: 16 chars, ~80 bits, `MAX_ACCOUNT_STR` cap verified) and
  **new D5b** (ban/unban/kick via the SEC_CONSOLE relay — engine surface verified this
  round: `Chat.cpp:115/825/974`, `AuthSocket.cpp:466-477`, `world_runtime.cpp:1130-1152`).
- **Finding 3 (WrongPass off):** **new D11 / §2.6** — verified the omission and the
  engine default, adopted the remote design's values host-wide.
- **Finding 4 (D1/D2 vs tunnel):** §2.4 rewritten — D1/D2 are mode-keyed
  (`LAN_HOST` + private `RealmEndpoint` only), with the mode/predicate reconciliation
  table this doc now owns, and the endpoint-widening governance note (pinned
  `RuntimeTopologyTest.kt:23`, verified).
- **Finding 5 (advertisement/.socket contradictions):** §2.1 restates the socket
  invariant scoped to the game ports + the sibling-listener rule (firewall copy,
  lifetime, silent-failure warning); §2.2 adds the one-realm-row constraint; D4/D6 now
  read the projected advertisement and branch copy on address class (§3.1, §5.2); the
  beacon prose is a single story — no authority, no signature (§3.2).
- **Finding 6 (revocation lag / live-session disconnect):** covered by D5b (ban + kick
  pair) and the §7 residual-exposure + cross-design lag note.
- **Finding 7 correction (rev 2):** the critique's "account_logons written only in
  the failed-login branch" does not hold — the single write site (`AuthSocket.cpp:1164`)
  is on the **success** path inside `verifyVersionAndFinalizeAuthentication` (checked in
  both native trees); recorded in §0 item 6 since remote-accounts' §11 audit claim
  builds on the opposite reading.

### Revision 3 (round-2 critique)

- **account_logons contradiction (finding 1, medium):** §0 item 6 extended with the
  revision-3 re-verification (same single `:1164` site in both trees, two lines after the
  "successfully authenticated" log) and the cross-design consequence: a "last failure"
  column fed from `account_logons` would display successful logons as failures; failed
  attempts live in `failed_logins`/`account_banned`/`ip_banned`. Added the §8
  set-level review-gate row so remote-accounts' audit correction is tracked, not
  merely noted.
- **Tunnel §6 invite block still live (finding 2, medium):** §4.2's hard rule converted
  from an asserted supersession into an enforceable set-level gate naming the
  contradiction precisely (`design-tunnel.md:313-314` unchanged; no verifier route for
  app-equipped friends) and stating what this doc controls regardless — no LAN surface
  renders credentials in invite text (D4 address-only; D5a Guests-card-only with the
  no-chat copy). New §8 gate row.
- **WrongPass LOCAL self-lockout (finding 4, low):** D11's "emitting it always is also
  acceptable" parenthetical withdrawn as wrong. Decision: conditional emission on
  non-loopback binds only, with the verified mechanics (source-IP autoban +
  loopback-matching `ip_banned` gate, `AuthSocket.cpp:416-423` and `:691-696` → ten
  mistyped LOCAL passwords lock the host's own client out for 900 s with no in-app
  explanation) and the no-security-on-loopback rationale. Set-side requirement placed
  on remote-accounts §4.3's unconditional form and tunnel's unqualified plan step.
- **D6 dead branch (finding 5, low):** the non-private probe-copy branch is
  v2-gated (`requires: design-tunnel §4.1 endpoint-model v2`); v1 ships the
  tunnel-shaped input copy at parse time in the Settings field validation, where the
  refusal actually happens (`ui/SettingsScreen.kt:622-641`).
- **Finding 3 (tunnel's stale LanScreen citations):** names design-tunnel, not this
  doc; confirmed this doc's `LanScreen.kt:375` citation matches E:
  (`grep -n "UPnP"` → single hit at `:375`, re-run this round). No change.


---

<!-- Section 2 of 4 — verbatim from out/desktop-parity/design-tunnel.md -->

# Design — friend-over-internet connectivity for the desktop host app

**Status:** design / recommendation, revision 3 (responds to design-critique round 2; no code
written in this pass). Revision 2 answered round 1 (stale-mirror grounding). Revision 3:
corrects the two remaining stale `LanScreen.kt` citations (E: positions, check C9), rewrites
§6 so no credential ever travels inside the invite artifact (accepting design-lan §4.2's
supersession and remote-accounts §4.1's split), and makes the set-decision on WrongPass
lockout scope (§9.1: conditional on a non-loopback bind — the unconditional form self-locks
LOCAL users out of their own realm).
**Revision 2 grounding change:** round 1 of this document was written against the
`C:\pocket_realm_complete\repo` mirror, which has **diverged from the repo this ask names**
(`E:/pocket_realm_complete`): the E: tree already ships the desktop LAN host/join lane this
revision's predecessor described as unbuilt, and it has `native/cmangos`, `native/playerbots`
and `native/classic-db` materialized, making previously "unverifiable" protocol claims
source-readable. Every citation below has been re-verified against E: in this revision
(checks C1–C9 at the end). The C:-mirror-only claims from round 1 that did not survive are
explicitly retired in §13. Round 3 corrected the last two stale citations that claim had
missed (both `LanScreen.kt` positions; check C9) and re-bounded every LanScreen reference in
the document.
**Scope:** how a friend outside the host's LAN plays on the host's realm: option analysis
(UPnP/NAT-PMP, external tunnels, built-in relay), the realm-advertisement address override,
invites, reconnect behavior, bandwidth, and the surrounding remote-play surface
(host controls, version gating, abuse limits, revocation).

---

## 0. Ground truth (all read or run on `E:/pocket_realm_complete` in this revision)

| Fact | Evidence |
|---|---|
| **Desktop LAN host/join already ships.** `startRealm` builds the launch spec from stored settings: LAN_JOIN auto-stops an active local runtime first (graceful save), LAN_HOST is refused unless the stored `allowLanPlayers` authorization is set, and the spec threads through the shared supervisor into bind + projection | `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt:406-476` (start + `launchSpec`), validated by `DesktopAppModelLanLaunchTest` — 5 tests, 0 failures (check C2 below) |
| LAN UI exists: mode selector (Local only / Host on LAN / Join a LAN host), address field, allow-LAN toggle, firewall copy, Home host/join cards | `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt:130-179`, `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt:275-354`; the interface picker uses the shared `LanInterfacePolicy` scan (`SettingsScreen.kt:643-646`) |
| The realm exposes exactly two fixed TCP ports: 3724 (realmd) and 8085 (world) | `RealmEndpoint.REALM_PORT/WORLD_PORT` (`android/app/src/main/java/com/pocketrealm/supervisor/RuntimeTopology.kt:29-30`); conf twin emits `RealmServerPort`/`WorldServerPort` (`desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:86,143`) |
| Bind address flows from the endpoint into both servers | `startRealm`/`startWorld` call `realmdConfig(endpoint.address)`/`worldConfig(...)` → `BindIP = "<endpoint.address>"` (`desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:1965-2014`; `ServerRuntimeFiles.kt:87,144`) |
| The realm **advertisement** is a database row | `projectRealmEndpoint` runs `UPDATE realmlist SET address = '<endpoint.address>' WHERE id = 1` and re-reads it (`DesktopRuntimeBackend.kt:535-561`) — the row realmd sends clients in REALM_LIST (protocol knowledge) |
| Advertisement guard: dotted-quad IPv4 only, because the exec seam has no parameters | `check(ENDPOINT_ADDRESS.matches(...))`, regex `^\d{1,3}(\.\d{1,3}){3}$`, comment "the desktop seam's exec carries no bind parameters … so the realmlist UPDATE interpolates" (`DesktopRuntimeBackend.kt:540,2205`) |
| Endpoint model: canonical private/link-local IPv4 only; no DNS/ports/URIs; **CGNAT 100.64/10 and hostnames explicitly rejected** | `RealmEndpoint.parseLan` + `isPrivateOrLinkLocal` = 10/8, 172.16/12, 192.168/16, 169.254/16 (`RuntimeTopology.kt:33-68`); `RuntimeTopologyTest` requires rejection of `100.64.0.1`, `8.8.8.8`, `realm.local`, `::1` (`android/app/src/test/java/com/pocketrealm/supervisor/RuntimeTopologyTest.kt:22-27`) — a **pinned, cross-platform security invariant** (both `RuntimeTopology.kt` and `RuntimeTopologyTest.kt` are in the desktop shared manifest, `desktop/shared-sources.json:102,162`) |
| Host-interface selection excludes tunnel adapters | `LanInterfacePolicy.available()` filters `!isPointToPoint && !isVirtual` and name-prefixes `wlan`/`eth` (`RuntimeTopology.kt:180-202`) — Tailscale/ZeroTier adapters are point-to-point/virtual with other names |
| **realmd's brute-force lockout is OFF by default and the app emits no override** | `AuthSocket.cpp`: `MaxWrongPassCount = sConfig.GetIntDefault("WrongPass.MaxCount", 0)`, gate `if (MaxWrongPassCount > 0)` (`native/cmangos/src/realmd/AuthSocket.cpp:663-665`); grep for `WrongPass` over `desktop/src/main` and `android/app/src/main` → **0 hits** (check C5). The generated `realmd.conf` therefore runs with lockout disabled on every lane today |
| **World-packet encryption is header-only (4/6 bytes), payloads plaintext, no MAC** | `AuthCrypt.h` `CRYPTED_SEND_LEN = 4`, `CRYPTED_RECV_LEN = 6` (`native/cmangos/src/game/Server/AuthCrypt.h:38-39`); `AuthCrypt.cpp:20-53` is a stream cipher over the header only, keyed from the session key (`Init(BigNumber* K)`); call sites: `WorldSocket.cpp:122` (`EncryptSend` on the send header), `:172` (`DecryptRecv` on the recv header), `:490` (`m_crypt.Init(&K)` at auth session) |
| SRP6 protects the logon conversation (password never crosses; M1/M2 mutual proof) | Desktop gate proving full 1.12.1 build-5875 SRP6 logon against real native realmd (`desktop/src/main/kotlin/com/pocketrealm/desktop/AuthGate.kt:16-24`; `docs/WINDOWS_QUALIFICATION.md:21`) |
| **Version gating is self-maintaining:** mangosd writes `realmbuilds` at startup; realmd enforces the client build and `allowedSecurityLevel` per realm entry | `UPDATE realmlist SET … realmbuilds = '<builds>' WHERE id = <realmID>` (`native/cmangos/src/mangosd/Master.cpp:146-150`, `:616`); per-client check `ok_build = find(realmbuilds, _build) != end()` and `if (!ok_build ‖ allowedSecurityLevel > _accountSecurityLevel)` → realm not offered (`AuthSocket.cpp:920,942`); column `realmbuilds varchar(64) DEFAULT ''` (`realmd.sql:187` in `native/.build-o11-x86_64/cmangos-patched/sql/base/`), space-split parser (`RealmList.cpp:136-142`) |
| Advertisement column width differs by provider lane: upstream DDL is `varchar(32)`; the shipped SQLite datadir has `TEXT` | `realmd.sql:180` `address varchar(32) NOT NULL DEFAULT '127.0.0.1'` (round-1 check, re-confirmed on E:); the SQLite datadir this machine actually runs was read (round-1 check C3): `address TEXT NOT NULL DEFAULT '127.0.0.1'` |
| Bans take effect at the **next logon challenge**, not mid-session; IP bans likewise | `ip_banned` check in AUTH_LOGON_CHALLENGE (`AuthSocket.cpp:415-423`); `account_banned` check rejecting with AUTH_LOGON_FAILED_BANNED/SUSPENDED (`AuthSocket.cpp:466-477`) |
| **A kick verb exists** and the app already has console authority into the world | `{ "kick", SEC_GAMEMASTER, … HandleKickPlayerCommand }` (`native/cmangos/src/game/Chat/Chat.cpp:974`; handler `Chat/Level2.cpp:2686`); the runtime drives world console ops via `CliCommandHolder(0, SEC_CONSOLE, …)` (`native/realm-runtime/src/world_runtime.cpp:1133-1134`), which outranks SEC_GAMEMASTER |
| PlayerLimit is a hard cap of 10; RA/SOAP/console stay off | `PlayerLimit = 10`, `Console.Enable = 0`, `Ra.Enable = 0`, `SOAP.Enabled = 0` (`ServerRuntimeFiles.kt:146-148,157`) |
| Host-side loss policy precedent (Android): sustained interface loss ⇒ graceful save & stop after a 10 s grace window | `RealmService.monitorComponents`, `LAN_HOST_INTERFACE_LOSS_GRACE_MS = 10_000` (`android/.../service/RealmService.kt:446-494,619`); the **desktop has no monitor loop yet** (grep: `LanInterfacePolicy` in desktop main appears only in the Settings picker, `SettingsScreen.kt:643-646`) — the 1 s monitor is design-lan's D2, not shipped code |
| Accounts are the auth gate; the host provisions them (1..16 ASCII alnum, gmLevel 0..3) | `DurableRuntimeSupervisor.provisionAccount` (`android/.../supervisor/DurableRuntimeSupervisor.kt:475-487`); desktop `createAccount` (`DesktopAppModel.kt`) |
| Android declares internet play "planned" and copy says no UPnP/hostnames today | `android/.../ui/LanScreen.kt`: join-pane note "Discovery, hostnames, IPv6, UPnP, and mDNS are not used." (`:375`, check C9 — round 2 corrected round 2's own stale `:346-349`), planned card `lan-planned-tunnel` "Play over the internet" (`:225-234`) |
| Flavor is pinned vanilla 1.12.1 build 5875 | `schemas/flavor.json:2,16`; friend-side identity gates are DESK-52/68 (identity parse before projection) |

**Checks run in this revision** (all on `E:/pocket_realm_complete`; details with commands and
outputs in §14):

- **C2 (the decisive one):** `cd E:/pocket_realm_complete/desktop && ./gradlew.bat test --tests
  "com.pocketrealm.desktop.DesktopAppModelLanLaunchTest" --console=plain -q` → exit 0; JUnit XML
  `desktop/build/test-results/test/TEST-com.pocketrealm.desktop.DesktopAppModelLanLaunchTest.xml`:
  `tests="5" failures="0" errors="0" skipped="0"` (hosting-with-authorization reaches the
  supervisor; join stops an active local runtime; malformed LAN address refused at the gate;
  local start on an active runtime refused; hosting without the authorization refused).
- C1: critique's LAN grep re-run on E: → 33 hits across `DesktopAppModel.kt`,
  `ui/HomeScreen.kt`, `ui/SettingsScreen.kt`, `storage/Settings.kt`.
- C3–C8: source reads of `AuthSocket.cpp` (WrongPass :663-699, IP/account bans :415-423/:466-477,
  build gate :920/:942), `AuthCrypt.*`/`WorldSocket.cpp` (header-only encryption),
  `Chat.cpp:974` + `Level2.cpp:2686` (kick), `world_runtime.cpp:1133-1134` (SEC_CONSOLE),
  `Master.cpp:146-150` (realmbuilds self-write), `realmd.sql:177-187` (DDL), plus the
  WrongPass-absent grep and the exact-line re-verification of `DesktopRuntimeBackend.kt`,
  `ServerRuntimeFiles.kt`, `DesktopAppModel.kt`, `SettingsScreen.kt`.

**Not run in this revision:** the full desktop test suite (only the named test class per the
critique's own check), any live two-machine connectivity test, a bandwidth measurement, and
the UPnP/tunnel tools themselves (none assumed installed).

---

## 1. How a friend connects — the mechanics that constrain every option

A 1.12 client session has two legs, both TCP:

1. **Logon leg.** The client reads `realmlist.wtf` (`set realmlist <addr>` —
   `ClientRealmEndpointProjection.text`, shared, `ClientRealmEndpointProjection.kt:10`; the
   desktop join launches through `DesktopClientLauncher.kt:43`) and connects to `<addr>:3724`.
   Authentication is SRP6: the password never crosses the wire and the logon conversation is
   encrypted with the negotiated session key (proven end-to-end by `AuthGate`,
   `AuthGate.kt:16-24`).
2. **World leg.** realmd answers REALM_LIST with the realm entry from the `realmlist` table —
   the `address` string and `port` this app writes in `projectRealmEndpoint`
   (`DesktopRuntimeBackend.kt:535-561`). The client resolves that address itself (stock clients
   have always accepted DNS names in both realmlist and realm entries — protocol knowledge) and
   opens the world session there.

Consequence that drives the whole design: **the friend must reach BOTH ports, and the
advertised address must be reachable from the friend's machine — not from the host's.** A
tunnel that forwards only 3724, or that forwards both but advertises the host's LAN IP, yields
"connected to logon, stuck at realm list" — the failure mode design-lan's D6 probe must not
be allowed to mislabel (see §5, mode-aware copy).

Second consequence: the shipped code couples bind and advertise to one endpoint
(`startRealm(endpoint)` binds `BindIP = endpoint.address`, `ServerRuntimeFiles.kt:87,144`, and
`projectRealmEndpoint` advertises the same address, `DesktopRuntimeBackend.kt:535-561`).
Tunnel support is the act of **splitting those two**: bind on a local interface (or all
interfaces), advertise whatever the friend can resolve.

## 2. Encryption honesty — payloads are plaintext (now source-grounded)

Round 1 labeled the world-traffic facts "protocol knowledge" because `native/cmangos` was
missing from the C: mirror. On E: it is materialized, so the claims are now source-grounded:

| Leg | Confidentiality | Integrity |
|---|---|---|
| Logon (3724, SRP6) | Encrypted with the SRP6 session key; password never transmitted | M1/M2 mutual proof (`AuthGate.kt:16-24`) |
| World (8085) | **Header-only obfuscation**: the first 4 bytes (server→client) / 6 bytes (client→server) of each packet are stream-encrypted with a key derived from the session key (`AuthCrypt.h:38-39`, `AuthCrypt.cpp:20-53`, init at `WorldSocket.cpp:490`, applied at `:122/:172`). **Everything after the header — movement, chat, world content — crosses in plaintext.** | **None.** There is no per-packet MAC anywhere in `AuthCrypt`/`WorldSocket` |

What this means for each option:

- **On-path readers (ISP, Wi-Fi snooper, UPnP-exposed public internet):** can read all game
  content and chat in the clear. They **cannot** steal the password (SRP6 never sends it) and
  **cannot** inject world packets: forging an encrypted header requires the session key. They
  **can** flip payload bytes undetected (no MAC) — in practice garbage-parses (a DoS nuisance),
  but honest phrasing is "no per-packet integrity," never "tamper-proof."
- **Therefore:** any tunnel providing transport confidentiality (WireGuard under
  Tailscale/ZeroTier, TLS under cloudflared, or a future relay) is not hardening polish — it
  is the only confidentiality layer this protocol will ever have. Direct UPnP exposure puts
  readable chat/movement on the public internet; that must be a labeled, opt-in posture (§3).
- The Android planned-card copy already promises the right thing — "Connections stay
  authenticated end to end" (`LanScreen.kt:232-233`) — authentication, not confidentiality.
  Keep that distinction in the desktop copy.

## 3. Option (a) — UPnP / NAT-PMP automatic port mapping

**Mechanics.** The app discovers the gateway (SSDP for UPnP IGD; the default gateway for
NAT-PMP/PCP), requests leases for TCP 3724 and 8085, and advertises the gateway's public IP
(or the user's DDNS name) as the realm address.

**What it would take.**
- JVM: `org.bitlet.weupnp` (LGPL) or `jupnp` (Apache-2) for UPnP IGD; NAT-PMP/PCP is a simple
  request/response UDP protocol that can be hand-rolled if we refuse a second dependency.
- A Windows Firewall inbound rule for 3724/8085 (public profile — the LAN-scoped rule from
  design-lan §3.3 does not cover internet exposure).
- The advertise-vs-bind split of §4/§5, with the advertised address = public IP/DDNS name.

**Honest limitations — why this cannot be the primary lane:**
1. **It fails on exactly the networks many players have.** CGNAT (no WAN IP on the router),
   double-NAT, and carrier hotspots expose no mappable path. How common CGNAT is per market is
   an estimate I did not verify; "a large minority of residential connections" is the safe
   phrasing.
2. **It is the only option that exposes the realm to the whole internet** (§2: plaintext
   payloads, scannable ports, stranger-readable chat, a DoS target). The other options gate
   reachability on *membership*; UPnP gates it on nothing.
3. **The advertised address churns** (residential DHCP); DDNS becomes a hard dependency.
4. `LanScreen.kt:375` currently promises "UPnP … not used" — it would ship as a new,
   explicitly-labeled opt-in.

**Verdict: build it, but as a best-effort, default-off P3 behind an explicit consent, and
ONLY after the realmd lockout emission (§9.1) lands.** Round 1's §9 deferred "protocol-level
connection-rate limiting" as out of scope for this lane while §2 conceded full internet
exposure — the critique is right that this combination is indefensible when the mitigation is
a three-line conf emission a sibling design already wrote down (`design-remote-accounts.md`
§5.3: `WrongPass.MaxCount = 10`, `WrongPass.BanTime = 900`, `WrongPass.BanType = 0`). I adopt
it as a hard prerequisite here, with one correction of its rationale verified against the
vendored source: `BanType` **true bans the account, false/0 bans the IP**
(`AuthSocket.cpp:677-696` — the `if (WrongPassBanType)` branch inserts `account_banned`, the
else inserts `ip_banned`), so remote-accounts' `BanType = 0` is correct *for IP autoban*; the
enforcement point is the next logon challenge (`AuthSocket.cpp:415-423`), which is exactly the
brute-force loop it needs to break. Never advertise a UPnP mapping as "secure"; the honest
claim is "reachable by anyone who scans."

## 4. Option (b) — external tunnels (recommendation: the primary lane)

The realm needs exactly two TCP ports and no UDP; every mainstream personal tunnel carries
that trivially. The integration is *guidance + config generation + address plumbing*, not
embedding anyone's SDK. The desktop LAN lane this rides on **already ships** (§0, check C2);
tunnel mode is an extension, not new plumbing. Ranked:

### 4.1 Tailscale — recommended default

- **Model:** host and each friend join a tailnet (free tier: 3 users / 100 devices — the
  10-player cap is account-limited on free, a constraint to print in the UI; plan terms not
  re-verified this revision). Friends reach the host at its tailnet address: `100.x.y.z`
  (numeric, always ≤15 chars — see the varchar(32) note in §5) or the MagicDNS name.
  NAT traversal is WireGuard-over-UDP with DERP relay fallback.
- **Host flow in-app ("Host over the internet" card), as an extension of shipped machinery:**
  1. Detect Tailscale: enumerate the `Tailscale` adapter / `tailscale ip -4` (probe, then
     guidance copy on failure).
  2. Show the realm address and the two fixed ports; offer "copy invite block" (§6).
  3. Start hosting with bind = tunnel-adapter IP (or 0.0.0.0) and advertisement = the tailnet
     address (§5).
  4. Friend onboarding stays outside the app: Tailscale's own invite/sharing link is the
     account-plane invite; the app does not reimplement it.
- **Why first:** strongest reachability (DERP means "works even behind hard NAT"), hostname
  support fits the stock client, single-binary install on Windows, honest failure mode
  (offline tailnet ⇒ visible connect failure, not half-works).

### 4.2 ZeroTier — equally workable, slightly more manual

- **Model:** hosted controller at my.zerotier.com; friends join a network ID and the host
  authorizes each node in the web console. Managed ranges are chosen at network creation —
  the defaults are RFC1918 (e.g. `10.147.x.x`), which `RealmEndpoint.parseLan` **already
  accepts** (`RuntimeTopology.kt:64-68`) — a numeric ZT address needs no endpoint-model change
  on the *value*.
- **What still blocks it:** `LanInterfacePolicy` excludes virtual adapters
  (`RuntimeTopology.kt:183-185`), and the advertise path has no tunnel story (§5 fixes both).
  Authorization-per-node is a tight abuse control.

### 4.3 cloudflared — document as an advanced path, do not build UI for it

- **Model:** host runs `cloudflared tunnel` forwarding two ingress names to
  `tcp://localhost:3724` and `tcp://localhost:8085`; each friend runs `cloudflared access tcp`
  twice, creating **local** listeners (e.g. `127.0.0.1:3724` and `127.0.0.1:18085`), and points
  `realmlist.wtf` at `127.0.0.1:3724`.
- **Why it ranks last:** the advertised address+port is one global row — every joiner needs
  the *same* local world port (fix 18085 by convention), and plain-LAN players break while
  tunnel mode advertises loopback. Requires a Cloudflare account + a domain. A documented
  recipe, not a screen.

### 4.4 What the app itself must build (transport-neutral)

1. **Endpoint model v2 (shared file, both platforms) — gated on a dedicated security review.**
   Round 1 scheduled this as a routine step; the critique is right that it is not one: the
   shared `RuntimeTopologyTest` *pins `100.64.0.1` as required-rejected*
   (`RuntimeTopologyTest.kt:22-27`) — overturning it weakens a deliberate, cross-platform,
   test-pinned invariant that both platforms consume via `desktop/shared-sources.json:102,162`.
   This must land as **one** shared review item, the same governance design-remote-accounts
   §12.7 demands for its own widening ("any widening needs its own review"), not two docs
   scheduling it differently. Concretely:
   - Keep the **launch/bind endpoint** exactly as strict as today (canonical private/link-local
     IPv4, fixed ports) — `RuntimeLaunchSpec` invariants unchanged (`RuntimeTopology.kt:92-97`).
   - Add an **advertised address** type: canonical IPv4 **or** CGNAT `100.64.0.0/10` first
     (numeric form only in v1 — remote-accounts §12.7's "invite payloads may have to speak
     literal tunnel IPs in v1"); RFC-1123 hostname acceptance is the second, separately
     reviewed step (it touches the SQL-interpolation guard, §4.4.2, and the varchar(32)
     MariaDB lane, §5).
   - Launch-spec JSON **schema 2**: adds optional `advertisedAddress` (default = endpoint
     address ⇒ byte-identical behavior for LOCAL/LAN). `fromJson` enforces an exact key set
     (`RuntimeTopology.kt:150-155`) — extend the allowed set and the schema gate together, and
     extend `RuntimeTopologyTest` (shared ⇒ one test update covers both platforms).
2. **Bind/advertise split in the desktop backend.**
   - Bind: `realmdConfig`/`worldConfig` keep `BindIP = <launch endpoint>` (`:87,:144`); loopback
     is fine for cloudflared; Tailscale can bind the 100.x adapter or 0.0.0.0 (`0.0.0.0` is a
     raw conf value, not a `RealmEndpoint` — keep it out of the typed path).
   - Advertise: grow `projectRealmEndpoint` (`DesktopRuntimeBackend.kt:535-561`) into
     `projectRealmAdvertisement(endpoint, advertised)`. The SQL seam has no parameter binding
     (comment at `:2200-2205`), so the dotted-quad regex is the injection guard — the numeric
     CGNAT form keeps that guard *trivially closed* (still pure digits and dots); hostname
     acceptance needs the strict RFC-1123 grammar (charset, length ≤ the column, no
     quotes/whitespace/controls) plus hostile-input tests. **This grammar review is part of the
     §4.4.1 security review, not a follow-up.**
3. **Join side.** The join address field validates through `parseLan` (live-validated in the
   Settings field per design-lan D6, `ui/SettingsScreen.kt:622-641`) and the launch spec
   re-parses it (`DesktopAppModel.launchSpec`, `DesktopAppModel.kt:462-476`). Accepting tunnel
   addresses on join needs the same v2 grammar; `ClientRealmEndpointProjection.text` writes any
   string verbatim (`ClientRealmEndpointProjection.kt:10`), and the stock client resolves
   hostnames — but keep projection and acceptance on the same validated type so nothing raw
   reaches `realmlist.wtf`. Update the join-pane supporting text ("UPnP … not used",
   `LanScreen.kt:375`) when the tunnel lane ships. Dependency note for design-lan: its D6
   "non-private target (a tunnel address pasted in)" probe-copy branch (`design-lan.md:560-565`)
   is **dead code in v1 as lan specifies it** — the join field still live-refuses everything
   outside the private ranges through `parseLan` (`design-lan.md:549-551`,
   `ui/SettingsScreen.kt:622-641`, `RuntimeTopology.kt:44-61`), so the branch becomes
   reachable only when this design's §4.4.1 endpoint-model v2 widens the join grammar; lan
   should mark it conditional on that landing.
4. **Interface selection for hosting on a tunnel adapter.** `LanInterfacePolicy` drops
   point-to-point/virtual adapters (`RuntimeTopology.kt:183-185`) by design for LAN; the
   Settings picker uses that same scan (`SettingsScreen.kt:643-646`). Add an explicit
   tunnel-adapter lane (name-prefix allowlist: `Tailscale`, `ZeroTier`) rather than relaxing
   the LAN filter, so LAN hosting keeps its existing guarantee.
5. **Windows Firewall.** Design-lan's reference rule scopes to `profile=private` with
   `localport=3724,8085` (`design-lan.md` §3.3) — correct for LAN. Tunnel mode needs the rule
   set evaluated per lane (private profile still works for tailnet adapters on most setups;
   the card must say "if Windows asks, allow on the network the tunnel adapter lives on").
   Coordination requirement owned here: when design-remote-accounts' third listener
   (PROVISION_PORT) lands it **falsifies design-lan's "the only sockets a LAN host exposes are
   TCP 3724 and TCP 8085" invariant** (`design-lan.md:104-105`) and is uncovered by its netsh
   rule and its "one prompt" claim — whichever design lands first must update the invariant,
   the firewall rule, and the listener lifecycle (bound only while hosting; dies with the
   session) together. This doc flags it; it does not re-specify their listener.

**Note on sequencing:** no LAN prerequisite remains — the lane ships and its launch-gate test
is green (C2). Tunnel mode is steps 4.4.1–4.4.5 on top.

## 5. The address override, concretely

For one running host, the invariants after this change:

```
realmd.conf  BindIP        = <bind endpoint>      # local interface, or 0.0.0.0 for cloudflared
mangosd.conf BindIP        = <bind endpoint>
classicrealmd.realmlist    address = <advertised> # LAN IP, 100.x, ts.net name, ZT IP, or DDNS
                           port    = 8085         # (cloudflared convention: 18085)
friend realmlist.wtf       set realmlist <advertised>[:3724 implied]
```

- LOCAL mode: advertised == `127.0.0.1` — today's behavior, byte-identical.
- LAN mode: advertised == the host's LAN IP — the shipped behavior (settings `lanAddress` →
  `launchSpec` → `projectRealmEndpoint`).
- Tunnel mode: advertised == the tunnel address, **regardless of** the host's LAN IP. This is
  the explicit "the server must advertise the tunnel address, not the LAN IP" requirement;
  `projectRealmAdvertisement` takes the advertised value from settings, never from interface
  discovery, and reports the projected row back in the result detail.
- **Conflict rule — now owned as UX, not prose.** CMaNGOS sends one realm entry to everyone;
  while tunnel mode is on, the single `realmlist` row resolves only inside the tunnel. That
  makes two pieces of design-lan wrong in that state, and this doc owns the correction:
  design-lan's D4 invite card must copy the **advertised** address, never the LAN IP
  (round 1 cited `design-lan.md:219-224` copying the plain LAN IP), and D6's one-shot probe
  copy ("both devices are on the same network", `design-lan.md:357-361`) must become
  mode-aware — in tunnel mode: "No realm answered at <advertised>:3724. Check the host's
  realm is online and that you have joined the host's <tailnet/zone> — the realm address only
  resolves inside it." Without this, a plain-LAN guest passes the 3724 reachability probe
  (the bind still accepts TCP), logs in, and hangs at the realm list — precisely the failure
  §1 names. The Home hosting subtitle (`HomeScreen.kt:291,296`) likewise displays the live
  advertised address, whichever mode is active.
- **Column width:** the upstream MariaDB DDL caps `address` at `varchar(32)`
  (`realmd.sql:180`); the shipped SQLite lane has `TEXT` (round-1 live read). Numeric tunnel
  addresses (`100.x.y.z`) always fit; MagicDNS names can exceed 32 chars. v1 should prefer the
  numeric form and validate advertised length ≤ 32 (or the column that will actually hold it)
  at projection time, failing with an honest detail — not truncating.

## 6. Invite generation for friends

**Revision 3 — credential split (accepting the sibling supersessions).** Round 1's §6 embedded
a working username+password inside the copied invite block; design-lan §4.2 now states the
hard rule that supersedes it ("no invite text ever embeds a working username+password …
Tunnel invites carry the address block only", `design-lan.md:450-454`) and design-remote-accounts
§4.1 demands the same split ("the invite text carries the tunnel/network join and the realmlist
line; the credential is delivered as its own step over a channel the host treats as sensitive",
`design-remote-accounts.md:314-323`). This revision accepts both: a pasted invite travels over
an uncontrolled channel (Discord/email) and would be a *permanent working credential* in
whatever archive that channel keeps. This section is the matching revision pass
remote-accounts §13.7 asked for.

The artifact the host hands over is text (no link shortener, no server). One card, one
"Copy invite" button, rendered from live state — **address block only**:

```
── Play on <host's realm name> ─────────────────────────
1) One-time setup: join my tailnet — <tailscale invite link>   (or: install Tailscale, then <tailnet-name>)
2) Client: WoW 1.12.1 build 5875 exactly (the realm rejects other builds)
3) Put this one line in realmlist.wtf:
     set realmlist <advertised>
   …or, if you use the Pocket Realm app: paste <advertised> into the Join pane.
```

The credential is **never** part of this artifact. Delivery has two routes, by friend type:

- **Friend has the Pocket Realm app (canonical):** the remote-accounts verifier/provisioning
  path (its §2 flow, `design-remote-accounts.md:238-257`): the friend types their own username
  and password on their own device, the SRP6 verifier `(s, v)` is derived locally
  (`VsrpMath.verifierFor`), and the host approves and seeds the verifier row — **the host never
  holds the password at all.** For this class there is no secret to deliver; the invite above
  plus an invite/approval code is the entire handoff.
- **Friend has only a stock client:** the host generates the account
  (`DurableRuntimeSupervisor.provisionAccount`, 1..16 ASCII alnum, `DurableRuntimeSupervisor.kt:475-487`)
  and hands username+password over as a **separate step on a channel the host treats as
  sensitive** — direct message at minimum, spoken/screen handoff preferred (design-lan D5a's
  "proximate" standard). Per-friend accounts remain the ban/audit unit — never share one.

The invite carries the *advertised* address, never the host's LAN IP, when tunnel mode is
on (§5). Regenerating is idempotent; nothing is sent anywhere. (A hosted "short code"
service belongs to option (c), §7.)

## 7. Option (c) — a built-in encrypted relay: follow-up, not v1

Unchanged from round 1 (the critique did not contest the verdict); the grounding improved —
the plaintext-payload claims it relies on are now source-cited (§2):

**What it would be:** the host app and the friend's app open outbound connections to a hosted
rendezvous/relay; the relay stitches two TCP streams after an introduction-code handshake,
wrapping the plaintext WoW streams in a Noise/TLS-style channel so the relay sees ciphertext
only. It delivers "invite code → friend clicks → connected" with zero installs.

**Why not build it into v1:**
1. It adds an operated service to a product that is otherwise self-contained (`flavor.json`
   identity: "offline … self-contained") — uptime expectations, abuse moderation, a trust
   anchor.
2. External tunnels deliver the same confidentiality today (§2) with zero infra and
   battle-tested NAT traversal.
3. **App-to-app constraint:** to encrypt plaintext payloads without touching the client, the
   friend must run our tunnel terminator — a relay only serves users who already installed the
   app, which is also exactly the population Tailscale serves. The remaining niche ("has the
   app, no patience for Tailscale") is real but not worth an operated service before the
   tunnel lane has usage data.
4. The Android card's promise — nothing routed "until you explicitly start a tunnel"
   (`LanScreen.kt:225-234`) — is satisfied by Tailscale/ZeroTier now.

**Verdict: document as a hosted-component follow-up** (an `out/fix-plan` entry, not desktop
code): relay protocol sketch, abuse limits (per-host quotas, code TTLs, report path), and the
§7.3 decision gate. Effort XL end-to-end.

## 8. Bandwidth expectations (estimates — no measurement was run; labeled as such)

Unchanged from round 1, with the same honesty label. Vanilla 1.12's world protocol is small
delta packets around ~1 Hz object updates plus movement/state bursts (protocol knowledge):

| Scenario | Downstream at the friend | Upstream at the friend |
|---|---|---|
| Quiet solo/questing | ~2–10 kB/s | ~1–5 kB/s |
| Town / group with several players | ~10–30 kB/s | ~5–15 kB/s |
| Worst case (10-player raid + bot population) | ~30–60 kB/s | ~10–30 kB/s |

- Host aggregate for N friends ≈ N × per-friend downstream plus their upstream relayed to
  others; at the `PlayerLimit = 10` cap (`ServerRuntimeFiles.kt:157`) that stays well under
  ~1 Mbit/s of realm traffic — any home uplink carries it. Treat these as provisioning
  numbers, not measurements; the `rpHost` harness (desktop console-ops lane, JSON stdin/stdout)
  can drive a scripted session while a packet counter samples the wire to replace this table.
- Tunnel overhead: WireGuard adds ~60 bytes/packet; DERP-relayed sessions add latency (extra
  RTT hop) but no throughput constraint at these rates. Latency, not bandwidth, is what a
  friend feels.

## 9. What else remote play needs

### 9.1 realmd lockout — adopted as a cross-lane prerequisite, with the scope decision (revision 3)

Every lane that accepts remote logons (LAN, tunnel, UPnP) inherits realmd's default-off
brute-force protection. Fix, in `ServerRuntimeFiles.realmdConfig` (currently emits no
`WrongPass.*` keys, `ServerRuntimeFiles.kt:79-90`; check C5): `WrongPass.MaxCount = 10`,
`WrongPass.BanTime = 900`, `WrongPass.BanType = 0` (IP autoban; semantics verified,
`AuthSocket.cpp:677-696`).

**The set-wide scope decision this revision makes: emit whenever `bindAddress != 127.0.0.1`**
— design-lan D11's primary form (`design-lan.md:309-311`), here adopted as the one decision
for all three docs. The alternative — design-remote-accounts §4.3's unconditional
"apply to LOCAL, LAN_HOST, and the tunnel/UPnP lane on every boot"
(`design-remote-accounts.md:343-347`) — is rejected, because the unconditional form lets a
user lock themselves out of their own single-player realm: the BanType=0 autoban inserts the
**source IP** into `ip_banned` (`AuthSocket.cpp:691-696`, `current_ip = GetRemoteAddress()`),
and the challenge gate matches any source including loopback
(`AuthSocket.cpp:415-423`, `ip = '<remote address>'`). So on a LOCAL realm, ten mistyped
passwords at the user's own login screen insert a 900-second `ip_banned` row for `127.0.0.1` —
the user's own client, the `AuthGate` bring-up gate, and the `BootRealmd` gate then all fail
realmd logon with only the client's native "Unable to connect" as an explanation, and nothing
in the app surfaces the ban. design-lan's "loopback gains a harmless lockout" parenthetical
understates exactly this. On a loopback-only bind the lockout also buys nothing: only local
processes can connect, and those can read the database file anyway.

Conditional emission is trivial to implement correctly: `realmdConfig(bindAddress)` already
receives the bind (`ServerRuntimeFiles.kt:79`), so the emission keys off the value it already
holds. This resolves the round-2 three-way divergence (remote-accounts unconditional vs
tunnel unqualified vs lan conditional) in lan's favor; remote-accounts §13.7's requested
matching revision pass is this paragraph plus the §6 rewrite. The UPnP lane keeps the emission
as a hard gate (§3). Guest accounts otherwise remain brute-forceable indefinitely on every
lane that accepts remote logons.

### 9.2 Version gating per flavor — now source-grounded, and mostly free

- *Server-side:* **self-maintaining.** mangosd writes `realmbuilds` into the realm row at
  startup (`Master.cpp:146-150,616`), and realmd refuses clients whose build is not listed and
  realms whose `allowedSecurityLevel` exceeds the account's (`AuthSocket.cpp:920,942`). With
  the world pinned to the 5875 protocol the advertised set is the build-5875 pin; nothing in
  the app needs to hand-maintain it. (Optionally assert the row post-boot for defense in
  depth.)
- *Friend-side (app join path):* the PE identity gates **ship on desktop E:**
  (`client/ClientPeIdentity.kt` is in the shared manifest, `desktop/shared-sources.json:38`;
  `DesktopClientScanner.kt` and `ArchiveClientScanner.kt` apply it — the former
  DESK-52/68 gap is closed on this tree), refusing non-5875 clients before projection.
- *Invite copy:* "build 5875 exactly" (§6), matching `schemas/flavor.json:16`. Cross-flavor
  auth stays fail-closed via the clientBuild gate (DESK-128's vanilla-only lane).

### 9.3 Host controls and revocation — including the gaps round 1 left open

- Per-friend accounts with `gmLevel` 0..3 (`DurableRuntimeSupervisor.kt:486`) are the trust
  gradient; "Stop hosting & save" stays the always-available kill switch.
- **Ban:** write an `account_banned` row (duration presets, `banned_by` = host) — enforced at
  the next logon challenge (`AuthSocket.cpp:466-477`).
- **Kick (new requirement this revision):** a ban does not touch a live session — a kicked
  friend already in world keeps playing. The verb already exists (`kick`, SEC_GAMEMASTER,
  `Chat/Chat.cpp:974`, `Level2.cpp:2686`) and the desktop already holds console authority into
  the world (`CliCommandHolder(0, SEC_CONSOLE, …)`, `world_runtime.cpp:1133-1134`, the same
  authority `rpHost` uses for chat/memory ops), so a **Kick button on the host card** is a
  thin verb over existing machinery. Schedule it with the tunnel card; without it, one
  griefer with a valid guest credential occupies 1/10th of realm capacity (`PlayerLimit = 10`,
  `ServerRuntimeFiles.kt:157`) until they choose to leave.
- **Residual exposure, stated honestly per lane:** UPnP has **no** revocation of
  reachability (a banned friend with the ports still mapped stays reachable — the account ban
  is the only wall, and it binds at next logon). Tunnel revocation ("remove from tailnet/zone")
  is the effective ban but is a manual out-of-app action with unbounded lag. The kick verb +
  account ban bound the damage in all lanes; the doc should not pretend reachability revocation
  is instant anywhere.
- **Connected players** list remains planned (Android honestly labels it absent,
  `LanScreen.kt:176,:191`); it is the natural home for the Kick/Ban buttons.
- **Abuse limits:** the reachability gate is the real control (tunnel membership bounds who
  can complete a TCP handshake). Keep `PlayerLimit = 10` (`:157`) and RA/SOAP/console disabled
  (`:146-148`); rely on realmd refusing unknown accounts (SRP6 verifier must exist) plus the
  §9.1 lockout; protocol-level connection rate limiting beyond that stays out of scope — but
  say so in the UPnP consent copy *after* the lockout ships, not instead of it.

### 9.4 Windows Firewall + first-run UX

Per §4.4.5: lane-appropriate rules/copy; the host card shows "reachability: listening on
<bind>, advertised as <advertised>" with the honest caveat that the app cannot probe its own
NAT reachability.

## 10. Reconnect behavior

- **Host side — and the mode-aware monitor this design owes the set.** Round 1 §10 said "only
  genuine adapter loss triggers the window" (of the tunnel adapter) while design-lan's D2
  specifies a monitor over `LanInterfacePolicy.isCurrentPrivateInterface` of the LAN endpoint —
  as written, D2 graceful-stops a healthy tunnel realm whenever the physical LAN interface
  churns, disconnecting friends whose path never touched the LAN. Resolution, owned here:
  **the monitor predicate is the interface that owns the BIND address, per mode** —
  LAN_HOST watches the LAN endpoint exactly as D2 specifies; TUNNEL_HOST watches the
  tunnel-adapter address (the §4.4.4 allowlist lane) and must ignore physical-LAN churn;
  binds to `0.0.0.0` (cloudflared) have no interface to lose — their loss signal is the
  upstream tool's health (cloudflared process liveness), not an interface scan. Same
  reconciliation for design-lan's D1 preflight: a tunnel-hosted start would be refused by
  `!isCurrentPrivateInterface(spec.endpoint)` as specified — the preflight must run the same
  mode-aware predicate (tunnel-adapters pass; `0.0.0.0` skips the interface check). Keep the
  10 s session-anchored grace and the graceful save-and-stop from the Android precedent
  (`RealmService.kt:446-494`); DERP↔direct switches never remove the tunnel adapter, so they
  correctly do not count as loss. The desktop has no monitor loop yet (§0); this design and
  design-lan's D2 must land as one mode-aware implementation, not two competing ones.
- **Joiner side.** The 1.12 client has no auto-reconnect (protocol knowledge): a dropped TCP
  session ends at the client's disconnect screen. The app's job is honest UX: surface
  "connection lost — Rejoin" (stop client session, project realmlist, relaunch; credentials
  are already in the session's account store). Character safety is server-side; CMaNGOS
  persists and ejects ghost sessions after its session timeout (config-dependent — the exact
  value is readable in the now-materialized `native/cmangos` tree but was not read this
  revision; labeled as such).
- **Host restart.** The advertisement row survives restarts, so a host restart with the same
  advertised address needs nothing from friends except a re-login. An address *change*
  invalidates invites — the host card marks the invite stale when the advertised value differs
  from the one last rendered.
- **Graceful-stop discipline.** "Stop hosting & save" goes through the graceful stop while
  friends are connected; force-stop remains the consented orphan path.

## 11. Recommended plan (ordered; revised for the shipped LAN lane)

1. **realmd lockout emission, scoped to non-loopback binds** (§9.1) — three conf lines in
   `realmdConfig` whenever `bindAddress != 127.0.0.1`; closes the brute-forceable-3724 hole on
   the LAN/tunnel/UPnP lanes without LOCAL self-lockout; smallest change, highest leverage,
   and the UPnP lane's gate.
2. **Endpoint model v2 + the shared security review** of the pinned 100.64/10 and
   advertised-grammar widening (§4.4.1) — one review item co-owned with
   design-remote-accounts §12.7, landing numeric tunnel IPs first.
3. **Bind/advertise split** (`projectRealmAdvertisement`, `DesktopRuntimeBackend.kt:535-561`)
   + tunnel-adapter lane in the interface picker (§4.4.2, §4.4.4) + length guard (§5).
4. **"Host over the internet" card** with Tailscale-first guidance + invite block (§6),
   ZeroTier second, cloudflared as a documented recipe; mode-aware invite/probe/home copy per
   §5 (the D4/D6 corrections), join-pane input widening (§4.4.3).
5. **Kick + ban host verbs** over the existing console authority (§9.3).
6. **Mode-aware preflight + interface-loss monitor** — one implementation reconciling this
   design's §10 with design-lan's D1/D2.
7. **UPnP/NAT-PMP (P3, default off)** behind consent, gated on step 1 (§3); skipping it
   entirely remains acceptable.
8. **Built-in relay: do not build.** Hosted-component follow-up with the §7 decision gate.

## 12. Open questions

1. Tailscale free-tier "3 users" vs a 10-player realm under the friend-as-shared-node invite
   model (nodes vs users counting; plan terms not re-verified this revision).
2. Product decision: must friends *without* the Pocket Realm app (raw WoW.exe + hand-edited
   realmlist) be first-class? The design supports it (stock clients accept DNS names), but
   invite copy and support burden differ; §6 assumes both paths.
3. If UPnP ships: dependency choice (weupnp vs jupnp vs hand-rolled NAT-PMP/PCP) and whether
   mapping ownership sits in the desktop app or the realm-native layer.
4. The ghost-session timeout value for the §10 joiner story (readable in the materialized
   `native/cmangos` tree; not read this revision).
5. Hostname advertisement timing: does it land in the same schema-v2 review as CGNAT, or
   strictly after (numeric-first v1)? Tied to the varchar(32) MariaDB-lane cap (§5).
6. Whether the interface picker should list tunnel adapters in LAN mode at all, or only in
   tunnel mode (§4.4.4).

## 13. Retired round-1 claims (stale C: mirror)

For the record, these round-1 statements were true of `C:\pocket_realm_complete\repo` and
**false of `E:/pocket_realm_complete`**, and this revision removes or inverts them:
- "every desktop entrypoint pins `RuntimeMode.LOCAL`; `allowLanPlayers` … no consumer; Router
  has no LAN destination" — the E: tree ships the full LAN lane (§0, C1, C2).
- "`native/cmangos` is an empty submodule … not checked out here" — materialized on E:, which
  upgraded §2, §9.1, and §9.2 from protocol knowledge to source citations.
- "DESK-15/24/42 are prerequisites — land them first" — already implemented on E:; retired in
  favor of §11's actual remaining work.
Round 1's C:-tree checks (manifest test exit 0; `RuntimeTopologyTest` 7/0/0/0; live datadir
`realmlist` DDL `TEXT`) were real runs but bind to the C: mirror; the datadir read additionally
reflects this machine's actual shipped artifact and is retained only for the column-width note
(§5). The authoritative checks for this revision are C1–C9 on E:, with round-3 additions
C9–C11 (§14).

## 14. Checks ledger (exact commands this revision)

```
C1  grep -rn "LAN_JOIN|LAN_HOST|allowLanPlayers|LanInterfacePolicy" \
      E:/pocket_realm_complete/desktop/src/main --include=*.kt
    → 33 hits in DesktopAppModel.kt, ui/HomeScreen.kt, ui/SettingsScreen.kt, storage/Settings.kt
C2  cd E:/pocket_realm_complete/desktop && ./gradlew.bat test \
      --tests "com.pocketrealm.desktop.DesktopAppModelLanLaunchTest" --console=plain -q
    → exit 0; XML tests="5" failures="0" errors="0" skipped="0"
C3  grep -n "WrongPass" AuthSocket.cpp (:663-699 block read: MaxCount default 0,
    gate `if (MaxWrongPassCount > 0)`, BanType true=account / false=IP autoban)
C4  grep -rn "WrongPass" E:/pocket_realm_complete/desktop/src/main \
      E:/pocket_realm_complete/android/app/src/main --include=*.kt  → 0 hits
C5  sed -n reads: AuthSocket.cpp:415-423 (ip_banned), :460-480 (account_banned at
    next logon), :916-942 (build/security gate); Chat/Chat.cpp:974 + Level2.cpp:2686
    (kick, SEC_GAMEMASTER); world_runtime.cpp:1133-1134 (CliCommandHolder SEC_CONSOLE);
    Master.cpp:146-150,616 (realmbuilds self-write); RealmList.cpp:136-142 (parser);
    AuthCrypt.h:38-39 + AuthCrypt.cpp:20-53 + WorldSocket.cpp:122,172,490 (header-only
    encryption); realmd.sql:177-187 (realmlist DDL, address varchar(32))
C6  grep -n on E: DesktopRuntimeBackend.kt → projectRealmEndpoint :535,
    ENDPOINT_ADDRESS :2205, startRealm/startWorld :1965-2014
C7  grep -n on E: ServerRuntimeFiles.kt → RealmServerPort :86 / BindIP :87,
    WorldServerPort :143 / BindIP :144, Console/Ra/SOAP :146-148, PlayerLimit :157;
    no WrongPass emission
C8  grep/sed on E: RuntimeTopology.kt (:64-68 range set, :180-202 interface policy),
    RuntimeTopologyTest.kt (:22-27 pinned rejections incl. 100.64.0.1),
    desktop/shared-sources.json (:102 RuntimeTopology.kt, :162 RuntimeTopologyTest.kt)
```

Round-3 additions (checks C9–C11, all run this revision on E:):

```
C9  grep -n "UPnP" E:/pocket_realm_complete/android/app/src/main/java/com/pocketrealm/ui/LanScreen.kt
    → single hit :375 ("Discovery, hostnames, IPv6, UPnP, and mDNS are not used.")
    — round 2's :346-349 was the stale C:-mirror position; all six LanScreen citations
    in this doc were re-bounded against E: (planned card :225-234; "Connections stay
    authenticated" :232-233; connected-players honest labels :176,:191)
C10 sed -n reads, AuthSocket.cpp: :686-699 re-read (BanType=0 else-branch inserts
    GetRemoteAddress() into ip_banned), :413-424 re-read (challenge gate matches
    ip = GetRemoteAddress(), i.e. any source including 127.0.0.1) — grounds the §9.1
    LOCAL self-lockout mechanics
C11 sibling-section reads this revision: design-lan.md:450-456 (supersession hard
    rule), :305-315 (D11 conditional lockout), :545-566 (D6 mode-aware probe +
    parseLan-pinned join input); design-remote-accounts.md:312-325 (§4.1 split
    demand + verifier flow), :340-352 (§4.3 unconditional lockout), :584-590 (§13.7
    alignment ownership), :238-257 (§2 verifier provisioning flow)
```


---

<!-- Section 3 of 4 — verbatim from out/desktop-parity/design-remote-accounts.md -->

# Design: remote account creation for friends over LAN/tunnel

Status: design (no code written in this pass). Revision 3 — round-2 fixes:
account_logons reading corrected (it is the success path), lockout emission
conditioned on the bind address, tunnel §6 contradiction made explicit with
this design's enforcement point.

Sources note: **all citations below were re-verified on
`E:\pocket_realm_complete` (2026-10-01).** Revision 1 was written against
the `C:\pocket_realm_complete\repo` mirror and its "desktop is LOCAL-only"
premise was stale; that premise is withdrawn (revision 2). Checks run in
revision 2, on E::

- `diff -rq C:/pocket_realm_complete/repo/desktop/src E:/pocket_realm_complete/desktop/src`
  → 102 differing/only-in entries (e.g. only-in-E: `DesktopBotAdmission.kt`,
  `addons/`, `client/`; differing `DesktopAppModel.kt`,
  `DesktopRuntimeBackend.kt`, `Router.kt`, `AuthGate.kt`, …). The mirror is
  not a valid citation source.
- `diff -rq C:/pocket_realm_complete/repo/android/app/src/main E:/pocket_realm_complete/android/app/src/main`
  → dozens of differing files (addons/, client/, …). Android citations were
  re-pinned on E:.
- `diff -q …/native/realm-runtime/src/world_runtime.cpp` (C: vs E:) →
  identical; `diff -q RuntimeTopology.kt` and `ServerRuntimeContract.kt`
  (C: vs E:) → identical. Those citations carry over unchanged.
- `grep -rn "LAN_JOIN|LAN_HOST|allowLanPlayers|LanInterfacePolicy"
  E:/pocket_realm_complete/desktop/src/main --include=*.kt` → 33 hits in
  DesktopAppModel.kt, ui/HomeScreen.kt, ui/SettingsScreen.kt,
  storage/Settings.kt — **the desktop LAN lane ships.**
- `cd E:/pocket_realm_complete/desktop && ./gradlew.bat cleanTest test
  --tests "com.pocketrealm.desktop.DesktopAppModelLanLaunchTest"
  --console=plain` → BUILD SUCCESSFUL (`:test` executed after `cleanTest`),
  JUnit XML `TEST-com.pocketrealm.desktop.DesktopAppModelLanLaunchTest.xml`
  reports `tests="5" skipped="0" failures="0" errors="0"`.

Additional checks run in **revision 3** (round 2), on E::

- `sed -n '1125,1185p' E:/pocket_realm_complete/native/cmangos/src/realmd/AuthSocket.cpp`
  → the `account_logons` INSERT (:1164) sits inside
  `AuthSocket::verifyVersionAndFinalizeAuthentication` (:1146), two lines
  after `BASIC_LOG("User '%s' successfully authenticated")` (:1155) and
  immediately after the `UPDATE account SET sessionkey = …, failed_logins
  = 0 …` reset — the **success** path. Revision 1-2's "only on failed
  logon" claim (grep without the enclosing function) is withdrawn;
  design-lan rev 2 §0 item 6's correction is confirmed by this read.
- `sed -n '685,698p' …/AuthSocket.cpp` → the `BanType = 0` autoban inserts
  the **source IP** into `ip_banned` (`current_ip = GetRemoteAddress()`,
  :692-694); combined with the challenge's `ip_banned` gate (:415-423,
  read in revision 1) this is the LOCAL self-lockout mechanics behind the
  §4.3 conditional emission.
- Sibling state read this round: `design-lan.md:445-454` (Hard rule, no
  credential in invite text) and `:305-311` (D11 conditional lockout);
  `design-tunnel.md:313-314` (§6 invite block still embeds
  username+password — unrevised; see §13.6).

cmangos citations use `E:\pocket_realm_complete\native\cmangos` (materialized
there; unchanged from revision 1, where they were read from E: directly).

---

## 1. What exists today (grounding, all on E:)

**The account relay (native, both platforms).** The world process exposes
account ops as JNI exports over the world-runtime relay
(`native/realm-runtime/src/world_runtime.cpp`, byte-identical across both
checkouts):

- `create_account` — `world_runtime.cpp:271-290`. Validates 1..16 chars and
  ASCII-alnum on **both** username and password (`is_account_token`, :276-281),
  then injects `account create <user> <pass>` into the world's CLI queue
  (:283). Maps "already/exist" in console output to `ACCOUNT_EXISTS` (:287).
- `set_account_gmlevel` — `world_runtime.cpp:292-304`. `account set gmlevel
  <user> <0..3>`; same token validation.
- `verify_account_password` — `world_runtime.cpp:306-318`. Delegates to
  `sAccountMgr.CheckPassword` (:317).
- `account_info` — `world_runtime.cpp:320-343`. `SELECT id,gmlevel FROM
  account WHERE username='%s'` via `LoginDatabase` (:338-339) — the world
  process and realmd share the login DB.
- `issue_command` — `world_runtime.cpp:1130-1152`. Every relay op runs at
  `SEC_CONSOLE` via `CliCommandHolder(0, SEC_CONSOLE, ...)` (:1132), i.e.
  highest console authority, with a bounded 2048-char output capture.
- JNI surface: `createAccountNative`, `verifyAccountPasswordNative`,
  `setAccountGmLevelNative`, `accountInfoNative`
  (`world_runtime.cpp:1395-1421`).

**Supervisor provision contract (Android; shared contract, desktop twin).**
`RealmService.createAccount` (AIDL entry, `android/.../service/
RealmService.kt:244-251`) → `DurableRuntimeSupervisor.provisionAccount`
(`android/.../supervisor/DurableRuntimeSupervisor.kt:475-503`): re-validates
1..16 ASCII-alnum + gmLevel 0..3, requires WORLD_READY/RUNNING/CLIENT_FAILED
phase and a live WORLD owner token; durable journal action
"account-provisioned-core-command" on success (:498-499).
`AndroidRuntimeBackend.provisionAccount`
(`android/.../supervisor/AndroidRuntimeBackend.kt:476-516`) calls
`world.api().createAccount(username, password)`; **on `ACCOUNT_EXISTS` it
never adopts the identity without proving the password first**
(:490-502, `ACCOUNT_PASSWORD_MISMATCH`). The desktop twin is
`desktop/.../DesktopRuntimeBackend.kt:950-1012` with the identical contract
(:963-974; `ERR_ACCOUNT_EXISTS = 10` at :2209). Codes come from the shared
contract table (`ServerRuntimeContract.kt:40-47`; `ACCOUNT_EXISTS` = 10).

**The desktop LAN lane already ships (this revision's core correction).**

- Mode + authorization live in settings: `storage/Settings.kt:236-242`
  (`runtimeMode`, `allowLanPlayers = false`, `lanAddress`).
- `SettingsScreen.kt:130-179`: "Local only / Host on LAN / Join a LAN host"
  mode buttons, the LAN address field, the "Allow LAN players (required to
  host)" switch, and the firewall copy ("Windows Firewall may ask to allow
  them … ports 3724/8085").
- `DesktopAppModel.kt:402-451` (`startRealm`): a LAN_JOIN while a local
  runtime is up stops it first (:406-413); `launchSpec` (:464-476) builds
  LOCAL loopback, LAN_HOST with the stored `allowLanPlayers` as the explicit
  authorization (the comment notes the `lanHost()` factory hard-codes
  `allowLanPlayers = true`, so the user toggle IS the authorization), or
  LAN_JOIN client-only.
- `ui/HomeScreen.kt:275-354`: the Realm card is mode-aware — "The world is
  hosted on $endpointAddress; LAN players can join." while hosting
  (:291-296), a dedicated "Join LAN game" button in join mode (:318-327).
- Binding: `ServerRuntimeFiles.realmdConfig` (`server/ServerRuntimeFiles.kt:81-94`)
  writes `BindIP` (:87) and `worldConfig` (:110+) the same (BindIP :144,
  `WorldServerPort` :143; `Console.Enable/Ra.Enable/SOAP.Enabled = 0` at
  :146-148). The
  bring-up gate still proves only `127.0.0.1:3724`
  (`desktop/.../BootRealmd.kt:60-69`).
- Advertisement: `DesktopRuntimeBackend.projectRealmEndpoint`
  (`DesktopRuntimeBackend.kt:535-563`) writes the single `realmlist` row
  (`UPDATE realmlist SET address = '<endpoint>' WHERE id = 1`, :551),
  IPv4-only guard (:540), result detail `realmlist -> <addr>` (:555). One
  realm entry reaches every client — the address-conflict rule between
  tunnel and LAN advertisement is design-tunnel §5's to own (it documents
  exactly that conflict); this design introduces no second advertisement.
- Android join UI: `android/.../ui/LanScreen.kt` — join copy "Log in with an
  account from that host" (:343) and the players placeholder "no remote
  player tracking yet; this section appears when the realm reports sessions"
  (:176, :191).
- Join-mode Android hides the local account card and disables creation
  (`android/.../ui/HomeScreen.kt:455, :470`) — a join-mode device still has
  **no path** to get an account on the remote host. That gap is what this
  design closes.

**Desktop shell.** `Router.kt` now has nine flat routes (Home, Import, Bots,
Llm, Settings, InGameSettings, Controls, Addons, Diagnostics —
`Router.kt:8-32`, `DESKTOP_ROUTES` at :35); a Players route joins this set.
The desktop account card ("Local account", validate-and-explain fields,
Administrator switch) is `ui/HomeScreen.kt:521+` (switch at :586), wired at
:202; persistence via `DesktopAppModel` (`storedAccount` :87-88,
`createAccount` :885-895).

**Existing JVM SRP6 (the proof it all works end-to-end).**
`desktop/.../AuthGate.kt` is a minimal WoW 1.12.1 SRP6 **client** run against
realmd as a bring-up gate. `VsrpMath` (:164+) holds the cmangos prime and
generator (:167-169; `N = 894B645E…9BB7`, g=7 — matching
`cmangos/src/shared/Auth/SRP6.cpp:22-24`); the random-salt
`verifierFor(account, password)` (:173-183) picks the 32-byte salt with
top/bottom byte nonzero (:176-181, rationale: the challenge packet carries
the unpadded minimal salt); the deterministic salt-pinned overload
`verifierFor(account, password, s)` (:191+) is the golden-vector-pinned
derivation (`x` over the salt's LE bytes and the raw identity digest,
`v = g^x mod N`). It seeds the verifier row directly into the flavor-routed
realmd database (:78-93): `INSERT INTO account (username, gmlevel, v, s,
joindate, lockedIp, last_module, locale, os, platform)` via
`DesktopSqliteConnection` (:83-92) — and realmd accepts the SRP6 logon
against that row. Working precedent that s/v can be computed off-process and
that an out-of-band-provisioned row authenticates.

**cmangos ground truth (E:\pocket_realm_complete\native\cmangos).**

- Schema `sql/base/realmd.sql:43-68`: `account` has `username` varchar(32)
  (UNIQUE, :45,66), `gmlevel`, `sessionkey`, **`v`** (verifier) and **`s`**
  (salt) longtext (:48-49), `lockedIp`/`failed_logins`/`locked` (:52-54),
  `mutetime` (:59). `MAX_ACCOUNT_STR` is 16
  (`src/game/Accounts/AccountMgr.h:34`) — the source of the 1..16 alnum rule
  everywhere above.
- `account_banned` (`sql/base/realmd.sql:89-100`): `account_id`, `banned_at`,
  `expires_at`, `banned_by`, `reason`, `active`.
- `account_logons` (`sql/base/realmd.sql:112-118`): accountId, ip, loginTime,
  loginSource.
- Password storage (`src/game/Accounts/AccountMgr.cpp`): `CreateAccount`
  normalizes (uppercases) both fields and stores **only** s/v —
  `SRP6::CalculateVerifier(CalculateShaPassHash(username, password))` then
  `INSERT INTO account(username,v,s,...)` (:54-60), plus the
  `realmcharacters` bootstrap INSERT (:67-68). `ChangePassword` /
  `ChangeUsername` recompute s/v in place (:120-175). `CheckPassword`
  (:232-257) recomputes the verifier from the stored salt and compares.
- Login handshake (`src/realmd/AuthSocket.cpp`): ip_banned gate (:415-423);
  challenge reads `locked, lockedIp, gmlevel, v, s, token` from `account`
  (:429); a mismatched lockedIp is rejected (`AUTH_LOGON_FAILED_SUSPENDED`,
  :433-446); unusable stored v/s → `AUTH_LOGON_FAILED_FAIL_NOACCESS` "Broken
  v/s values" (:456-459); an active `account_banned` row rejects the logon
  (:466-477).
- SRP6 math (`src/shared/Auth/SRP6.h:30-122`, `SRP6.cpp`): fixed prime and
  generator (SRP6.cpp:22-24); 32-byte random salt (`s_BYTE_SIZE = 32`,
  SRP6.h:33; `salt.SetRand(s_BYTE_SIZE * 8)`, SRP6.cpp:96);
  verifier-from-predefined-salt overload `CalculateVerifier(rI, salt)`
  (SRP6.h:66-72) — the core can compute v against a salt chosen elsewhere,
  which is what friend-side provisioning exploits.
- Brute-force gate (`src/realmd/AuthSocket.cpp:664-694`): runs only when
  `WrongPass.MaxCount > 0` (`sConfig.GetIntDefault("WrongPass.MaxCount", 0)`,
  :664 — **default 0 = disabled**); increments `failed_logins` (:668);
  `WrongPass.BanTime` (default 600 s), `WrongPass.BanType` (default false →
  IP ban into `ip_banned`, checked at :415-423). **The generated
  `realmd.conf` emits no `WrongPass.*` keys**
  (`ServerRuntimeFiles.kt:81-94`, verified by grep over the E: file — only
  `BindIP` at :87 for realmd; worldConfig's extra keys are :144-148, :157,
  :176-179), so login lockout is off on every lane that boots realmd today.
- Console commands available to the relay (all runnable via `issue_command`
  because SEC_CONSOLE outranks every level below): `account create/delete/
  onlinelist` at SEC_CONSOLE, `account characters` / `account set` at
  SEC_ADMINISTRATOR (`src/game/Chat/Chat.cpp:79-92`); `ban account` /
  `unban account` / `banlist account` (`Chat.cpp:115, :825, :133`; handlers
  `src/game/Chat/Level3.cpp:4939, :5026, :5212`). `ban account <name>
  <duration> <reason>` parses duration via `TimeStringToSecs`
  (`Level3.cpp:4954-4985`).
- Audit (corrected in revision 3; revision 1-2 had this inverted): the
  single `account_logons` write (`AuthSocket.cpp:1164`) sits on the
  **successful-logon** path — inside
  `AuthSocket::verifyVersionAndFinalizeAuthentication` (:1146), immediately
  after `BASIC_LOG("User '%s' successfully authenticated")` (:1155) and the
  `UPDATE account SET sessionkey = ..., failed_logins = 0 ...` reset
  (:1161) — recording accountId, source ip, time, and loginSource per
  success (schema `realmd.sql:112-118`; readers at
  `src/game/Chat/Level2.cpp:2762, 4278-4386`). **Failed attempts are
  recorded only via the `failed_logins` counter** (:668, and only when
  `WrongPass.MaxCount > 0`) **and `ip_banned` autoban rows** (:692-694) —
  there is no per-failure history table. (Revision 1-2 grepped the write
  site without reading the enclosing function and mislabeled it; design-lan
  rev 2 §0's correction is right, and the sed read of :1125-1185 this
  revision is the authoritative check.)

---

## 2. Trust model

- **Host = realm administrator.** Whoever controls the supervisor controls
  everything (the relay runs at SEC_CONSOLE, `world_runtime.cpp:1132`); no
  in-app control should pretend otherwise.
- **Friends = low-trust players.** gmlevel 0 by default, an account on a
  shared realm, nothing else.
- **Two admission classes, one moderation surface** (resolves the round-1
  sibling conflict; §4):
  1. *App-equipped friends* (Pocket Realm app on the join side): the full
     invite → friend-side SRP6 derivation → host approval flow of §3-§6.
  2. *Stock-client friends* (a plain WoW 1.12.1 install, no app — the case
     design-lan D5 and design-tunnel §6 target): there is no friend-side
     process to derive s/v, so the host generates username+password and
     provisions through the **existing host-local password relay**
     (`create_account`, `world_runtime.cpp:271-290`). The secret is born on
     the host and crosses only the human channel; it never crosses the
     provisioning protocol. Constraints in §4.1 keep that class honest.
- **Transport = LAN or a user-managed tunnel (Tailscale/WireGuard/SSH).** The
  design does not assume link security it did not establish: the provisioning
  channel authenticates with a single-use invite token and rate-limits hard,
  and treats "tunnel provides confidentiality" as the v1 assumption with TLS
  as the v2 upgrade (open question §13).
- **SRP6 caveat to design around:** a verifier `(s, v)` is *password-equivalent
  for offline guessing* — anyone holding `(username, s, v)` can brute-force
  the password offline (core SRP property; why cmangos stores them
  server-side only). Consequence: the invite channel carries s/v only over a
  token-authenticated request, tokens are single-use, and passwords — typed
  or generated — should push entropy within the 16-char alnum cap
  (`MAX_ACCOUNT_STR`, `AccountMgr.h:34`): passphrase-style over multiple
  words or maximum-length mixed case+digits. The leaked-s/v worst case is
  limited to *that friend's* account, never the host's or other accounts.

## 3. Credential handling: what the friend types, where the password goes

**Decision (app-equipped friends): the password never leaves the friend's
device.**

1. Friend types, **on their own device**, in the join flow (the Android join
   pane, `LanScreen.kt`, and the desktop join card are the surfaces): host
   address, invite code, desired username, password (typed twice).
   Validation reuses the shared rule —
   `ServerRuntimeContract.requireAccountToken` (`ServerRuntimeContract.kt:49-54`)
   / `UserAccountStore.isValidCredential` (`UserAccountStore.kt:124-125`):
   1..16 ASCII alnum, matching what `create_account` and
   `AccountMgr::CreateAccount` enforce. **Host-address caveat:** the current
   endpoint parser accepts only canonical private/link-local IPv4 —
   hostnames are refused (`parseCanonicalIpv4`, `RuntimeTopology.kt:44-62`),
   and `RuntimeTopologyTest.ipv6HostnamesUrisControlsNonCanonicalAndPublicIpv4AreRejected`
   (`android/app/src/test/.../RuntimeTopologyTest.kt:20-23`) pins
   `100.64.0.1` (Tailscale CGNAT) as a required rejection. A WireGuard LAN
   subnet (10/8) works as-is; a tunnel address or hostname does not. §13
   tracks the explicitly-scoped parser decision.
2. The friend's device derives `(s, v)` locally with `VsrpMath.verifierFor`
   (`AuthGate.kt:173-183`, deterministic overload :191+) — pure
   Kotlin/BigInteger/SHA-1, android-free, so it moves to
   `shared-sources.json` unchanged. Salt handling keeps the AuthGate
   constraint (32 bytes, top and bottom byte nonzero, :176-181) so the
   challenge packet's unpadded salt field stays exactly 32 bytes.
3. Only `{username, sHex, vHex}` crosses the provisioning channel, bound to
   the invite token.
4. The host provisions by inserting the row — **not** by relaying a password
   through the `account create` console path:

   New relay op `create_account_verifier(username, sHex, vHex, timeout_ms)` +
   JNI `createAccountVerifierNative` in `world_runtime.cpp`, sibling of
   `create_account` (:271-290): same READY-state gate, same alnum/length
   validation, plus `s`/`v` must be 64 lowercase hex chars (32-byte big
   numbers; realmd rejects "broken" values anyway — `AuthSocket.cpp:456-459`
   — but reject at the door). Implementation mirrors
   `AccountMgr::CreateAccount`'s write without the password input
   (`AccountMgr.cpp:51-60`): `INSERT INTO account(username,v,s,joindate)
   VALUES('%s','%s','%s',NOW)` through `LoginDatabase` (the world already
   owns this connection — `account_info` queries it at
   `world_runtime.cpp:337-339`), plus the `realmcharacters` bootstrap INSERT
   `AccountMgr.cpp:67-68` performs. Returns the new `accountId` from
   `LAST_INSERT_ID`/re-select via `account_info`.

   Why not direct sqlite writes like AuthGate? The AuthGate precedent
   (`AuthGate.kt:83-92`) is a bring-up gate poking the DB file from outside
   the runtime. A relay op keeps one provisioning path for desktop **and**
   Android, and keeps the write inside the world's DB-ownership/lifecycle
   guards (`account_info`'s tombstone_02 comment, `world_runtime.cpp:322-334`).

5. The existing `create_account`-with-password relay serves the **host's own**
   account card (desktop `ui/HomeScreen.kt:521+`, Android
   `ui/HomeScreen.kt:339-348`) and, per §2 class 2 / §4.1, host-generated
   stock-client credentials — always with the password confined to the host
   process. The
   "never adopt an existing identity without proving its password" rule
   (`AndroidRuntimeBackend.kt:490-502`, desktop twin
   `DesktopRuntimeBackend.kt:963-974`) likewise stays host-local; the
   verifier path never needs it because the host cannot even form a password
   to verify. Name-collision handling: the provisioning op returns
   `ACCOUNT_EXISTS` and the *host* rejects the request (friend picks another
   name); no verify-and-adopt shortcut exists or should exist for remote
   requests.

## 4. Relation to the sibling designs (round-1 conflict; lan aligned, tunnel open)

Round 1 shipped three mutually incompatible guest-account postures with no
cross-references: design-lan D5 (host generates username+password and
displays "Give this to your friend", with "No per-guest kick/ban/mute in v1
… social moderation"), design-tunnel §6 (username+password embedded in the
copied invite text), and this design ("the password never leaves the
friend's device"). **design-lan rev 2 has since aligned** with this design
(§4.2 Hard rule at :450-454, D5b revocation verbs, D11 conditional lockout,
and the §0 success-path correction adopted here); **design-tunnel §6 is
still unrevised** (:313-314 still embeds the credential) and is the one
live contradiction (§13.6). Decisions this design owns:

**4.1 Provisioning paths — two, not three, and never a password in an
invite.**

- App-equipped friends use this design's §3 flow (invite token + friend-side
  s/v + host approval). This is the canonical admission path.
- Stock-client friends use host-generated credentials through the existing
  host-local relay (§2 class 2). This is the honest form of what lan D5 and
  tunnel §6 describe — but both siblings must change two things:
  - **No credential in the invite artifact** — the same rule design-lan
    rev 2 §4.2 writes down ("Hard rule … no invite text ever embeds a
    working username+password", :450-454). Tunnel §6's step 2 ("username
    `<alnum>` password `<alnum>`" inside the copied invite text) puts a
    permanent working credential into whatever channel pastes the invite
    (Discord/email) and must be split out: the invite text carries the
    tunnel/network join and the realmlist line; the credential is delivered
    as its own step over a channel the host treats as sensitive (direct
    message at minimum). Lan's handoff display stays bounded the way lan
    rev 2 bounds it — proximate screen/verbal exchange, generated to the
    full 16-char cap (`AccountMgr.h:34`) per D5a — never a paste channel.
    **Enforcement on the surface this design owns, regardless of
    design-tunnel's doc state (still unrevised, §13.6):** the host invite
    composer never renders a credential inside the copied invite text, and
    its tunnel-friend flow routes app-equipped friends to the verifier path
    (§2 class 1) — which tunnel §6's app mention ("paste <advertised> into
    the Join pane") currently fails to do.
  - **No second implementation of provisioning.** D5's "Create guest
    account" and tunnel §6's "host creates one per friend" both call the
    existing `provisionAccount` contract (`DesktopRuntimeBackend.kt:950-1012`
    on desktop, `DurableRuntimeSupervisor.kt:475-503` on Android) — that is
    the same verb this design builds the approval surface on. What changes
    for them is only the UI placement and the copy, not a new backend path.

**4.2 Ban/revoke posture — uniform.** Round-1 lan's "no per-guest
kick/ban/mute in v1 … social moderation" is gone: lan rev 2's D5b now ships
the revocation verbs (`banGuest` / `unbanGuest` / `kickPlayer` via the
console relay). The verbs this design specifies (§9) are the same relay
calls (`ban account` SEC_ADMINISTRATOR, `Chat.cpp:115`; `unban account`,
:825; enforced by realmd at the challenge, `AuthSocket.cpp:466-477`) through
the same SEC_CONSOLE `issue_command` every account op already uses. Tunnel's
invite flow adopts the same ban button, duration presets, and unban when §6
is revised; "the host asks the friend to leave" is not a control. Tunnel
§9's "tunnel-membership removal is the effective ban" remains true *in
addition* for tunnel friends (defense in depth), not instead.

**4.3 Login lockout — every non-loopback lane; loopback binds omit it.**
The `WrongPass.*` emission (§6.3) belongs in `realmdConfig` itself, not in
any one lane's launch path, **conditioned on the bind address: emitted
whenever `bindAddress != 127.0.0.1` (LAN_HOST, tunnel/UPnP), omitted for
loopback-only LOCAL binds.** Revision 2 demanded the unconditional form;
that ships a concrete self-lockout failure mode, verified in the code this
round: with `BanType = 0` the autoban inserts the **source IP** into
`ip_banned` (`INSERT INTO ip_banned VALUES ('%s', …)` with
`current_ip = GetRemoteAddress()`, `AuthSocket.cpp:692-694`), and the
challenge's `ip_banned` gate (:415-423) matches any source **including
loopback** — so on a LOCAL realm, ten mistyped passwords at the user's own
login screen insert a 900-second ban for 127.0.0.1, after which the host's
own client, the AuthGate bring-up gate, and the BootRealmd gate all fail
realmd logon with only the client's generic "Unable to connect" as
explanation. On a loopback-only bind the lockout also adds nothing: only
local processes can connect, and they can read the DB file anyway
(design-lan D11's "harmless" parenthetical understates this; its primary
conditional form is the decision here). The conditioning still closes the
round-1 gap — tunnel §9's deferred rate-limiting and lan's earlier silence —
because every lane that exposes 3724 to anything but loopback gets the
emission, and realmd's own `ip_banned`/`account_banned` machinery does the
rest. Tunnel's unqualified lockout plan step must pick up the same
condition (§13.6).

**4.4 Advertisement stays single-owner.** CMaNGOS sends one realm entry to
everyone (`projectRealmEndpoint`, `DesktopRuntimeBackend.kt:535-563`, single
row :551). Which address that row carries in tunnel mode — and the
"LAN guest passes the reachability probe, logs in, hangs at realm list"
conflict — is design-tunnel §5's documented rule. This design introduces no
second advertisement and its UI copies whatever address the active mode
already advertises (the Home card's `endpointAddress` line,
`ui/HomeScreen.kt:291-296`).

## 5. Invite/approve flow

State machine, one row per invite, stored in the host app's own store (a
dedicated table/file — not the settings blob, which already carries mode
fields at `storage/Settings.kt:236-242`; on Android the supervisor's durable
store — secret-free by the `UserAccountStore.kt:17` convention):

```
OPEN → SUBMITTED → APPROVED → PROVISIONED
         │            └→ REJECTED
         └→ EXPIRED / BURNED (bad token, wrong key, cap hit)
```

- **Host generates** (host card / Players screen, §7): app creates
  `invite_id` (short, human-readable), `token = 16+ SecureRandom bytes`
  shown/copied as base64url, `token_hash` (SHA-256) persisted — the raw
  token is never stored, so a copied settings file grants nothing. Defaults:
  single-use, 15-minute expiry from first *submission* attempt, optional
  free-text note ("Dave"). Cap: ≤ 8 OPEN invites; generation refused beyond
  that.
- **Friend submits** (§3): one request over the provisioning listener (§6):
  `{invite_id, token, username, sHex, vHex}`. The listener hashes the token,
  matches OPEN+unexpired, and moves the row to SUBMITTED with the
  requester's IP. Response is **identical** for unknown token, expired,
  burned, and rate-locked (no oracle).
- **Host approves/rejects**: pending-request card shows note, requested
  username (re-checked against `account.username` UNIQUE, realmd.sql:66),
  source IP, time. Approve → `createAccountVerifierNative` (§3) → row
  PROVISIONED with `accountId`. Reject → row REJECTED, token burned. Only
  the PROVISIONED response tells the friend their `accountId`.
- **Friend side finishes**: on `PROVISIONED`, the friend's app saves the
  account for auto-login exactly like the host's account card does
  (`UserAccountStore` on Android; desktop via `DesktopAppModel`,
  `DesktopAppModel.kt:87-88, :885-895`) — the password remains only on the
  friend's device. On REJECTED, the friend's device discards the derived
  s/v immediately.
- **Android parity:** the join-mode device — where the account card is
  currently hidden/disabled (`android/.../ui/HomeScreen.kt:455, :470`) —
  gains a "Request access" panel producing the same payload, next to the
  existing join copy (`LanScreen.kt:343` "Log in with an account from that
  host"). The host may be a desktop LAN_HOST or an Android LAN_HOST device;
  the protocol and the relay op are identical because both consume the same
  `WorldNative` export.

## 6. Provisioning transport, lifecycle, and rate limiting

A small TCP listener in the host app process — **not** in realmd, **not** in
the world. New `PROVISION_PORT` constant next to `REALM_PORT`/`WORLD_PORT`
(`ServerRuntimeContract.kt:28-29`).

**Lifecycle (owns the round-1 gap):** the listener is a property of a
hosting session, not of the app. It starts when a start with friend-access
enabled reaches WORLD_READY and stops with the realm (bound to the same
supervisor start/stop path that owns realmd/world lifetimes). With
friend-access off it is never bound — the closed state is loopback-refusing,
not "listening on loopback".

**Exposure invariant (amends design-lan's):** design-lan §3 states "the only
sockets a LAN host exposes are TCP 3724 and TCP 8085". That holds while
friend-access is off; enabling it adds exactly one socket, this listener's.
To keep the posture honest: the Players/host-card UI shows the listener's
state (off / listening on <addr:port> / failed to bind) whenever it renders
invite affordances; the Settings LAN block's firewall copy
(`SettingsScreen.kt:153-159`, "(ports 3724/8085). Windows Firewall may ask
to allow them…" at :156) gains the provisioning port so the reference netsh
rule opens all three; and the friend's client gets an explicit
connect-timeout error
naming the host address — a blocked port can never present as "waiting for
the host to click Approve".

- **Framing:** length-prefixed UTF-8 JSON, one request per connection,
  hard cap on request size (~4 KiB, matching `RuntimeLaunchSpec.fromJson`'s
  4096 budget, `RuntimeTopology.kt:143-146`) and a short read deadline.
- **v1 auth:** bearer = invite token. v1 confidentiality = the LAN/tunnel
  (documented assumption, §2). v2 upgrade (recommended before tunnel-less
  LAN): TLS with the host displaying its self-signed cert fingerprint for
  the friend to pin — display-only, no CA machinery. Open question §13.
- **Rate limiting, three layers:**
  1. *Listener:* per source IP — 5 failed token/parse attempts → 10-minute
     lockout (in-memory + persisted row); global 10 concurrent connections;
     identical error blob for every rejection (§5).
  2. *Invite:* single-use, expiry, OPEN-cap (§5) — bounds valid-token
     guessing to the token's entropy (≥128 bits → brute force is not the
     attack; the caps exist for hygiene and audit noise).
  3. *realmd (login, post-approval), every non-loopback lane (§4.3):* when
     `bindAddress != 127.0.0.1`, `realmdConfig`
     (`ServerRuntimeFiles.kt:81-94`) additionally emits
     `WrongPass.MaxCount = 10`, `WrongPass.BanTime = 900`,
     `WrongPass.BanType = 0` (IP autoban) — keys it currently never emits,
     leaving realmd's own lockout **disabled**
     (`AuthSocket.cpp:664-694`, default `MaxCount` 0). This protects every
     account on an exposed bind — host's, app-friends', stock-client
     guests' — from online password guessing at 3724, which neither the
     invite layer nor tunnel membership can see. Loopback-only LOCAL binds
     omit the keys: the autoban's source-IP insert (:692-694) plus the
     unconditional challenge gate (:415-423) would otherwise ban 127.0.0.1
     itself after ten local mistypes (§4.3), with no security gained.

## 7. Host approval UI (desktop)

Two surfaces, matching where the shipped UI already makes these decisions:

- **On the Home Realm card while hosting** (where lan D4's copy-address and
  D5's Guests section already live, `ui/HomeScreen.kt:275-354`): a compact
  "N pending requests" affordance + "Invite" button; pending-request cards
  show note, requested username, source IP, age, with Approve / Reject.
- **A `Route.Players` destination** added to the nine-route set
  (`Router.kt:8-32`) for the full surface: open invites with copyable code,
  expiry countdown, Revoke; the friends table — username, accountId,
  gmlevel, online status, ban state, last logon (from `account_logons`,
  §12) —
  with per-row actions (§8-§10). Same coroutine + status-line pattern as the
  existing account card (`ui/HomeScreen.kt:521+`).
- The host's **own** account stays the Home "Local account" card; Players
  never creates or edits the host identity, keeping "an account the user
  typed into the Home screen so the integrated client can auto-login as that
  identity" (`UserAccountStore.kt:10-14`) as the host-local concept.

## 8. Per-friend characters

Nothing new is required for characters to be per-account — rows in
`characters` key on `account` id (`world_runtime.cpp:365-369`:
`SELECT … FROM characters WHERE account='%u' AND name='%s'`). New small relay
op `account_characters(username)` following the `character_persistence`
validation pattern (`world_runtime.cpp:345-370`, ASCII-alnum username, escaped
queries) returning guid/name/race/class/level per character; alternatively the
existing SEC_ADMINISTRATOR console `account characters <acc>` (`Chat.cpp:79`)
through `issue_command` for a text-listing v1. The Players screen renders the
list per friend. Character **deletion/moderation** stays out of scope for
invites; the host's console authority covers it.

## 9. Ban / revoke

- **Ban:** relay verb wrapping `issue_command("ban account <name> <duration>
  <reason>")` — `ban` runs at SEC_ADMINISTRATOR (`Chat.cpp:115`), reachable
  because the relay is SEC_CONSOLE (`world_runtime.cpp:1132`); handler
  `HandleBanAccountCommand` → `HandleBanHelper` (`Level3.cpp:4939-4985`)
  writes an `account_banned` row (realmd.sql:89-100) which realmd enforces on
  the next logon challenge (`AuthSocket.cpp:466-477`). UI: Ban button with
  duration presets (1h / 24h / permanent) + reason, `banned_by` recorded as
  the host. **This is the shared moderation surface** — lan's Guests card and
  tunnel's invite flow adopt it per §4.2.
- **Revoke (unban):** `unban account <name>` (`Chat.cpp:825`,
  `Level3.cpp:5026`).
- **Full removal:** `account delete` is SEC_CONSOLE (`Chat.cpp:84`) but
  orphans `characters` rows; prefer permanent ban, and treat delete as a
  separate, separately-confirmed maintenance action. Open question §13.
- **Do not** use `locked`/`lockedIp` account-IP pinning for friends
  (`AuthSocket.cpp:434-447`): tunnel/VPN exit addresses churn and would lock
  legitimate friends out. Keep `locked = 0`.
- The relay's existing output-scan style (`create_account`'s
  "already/exist" mapping, `world_runtime.cpp:286-289`) is the model for
  interpreting ban/unban console results; `banlist account` (`Chat.cpp:133`)
  feeds the Players table's ban-state column.

## 10. GM controls for the host

- Per-friend gmlevel 0..3 via the **existing** `set_account_gmlevel`
  (`world_runtime.cpp:292-304`) — a segmented control in the friend's detail
  view, defaulting 0 at provisioning time (the provision op inserts with
  gmlevel 0; no gm step in the happy path). Levels map to cmangos security
  classes (SEC_PLAYER..SEC_CONSOLE); the UI should label 3 as "full console"
  honestly, since the account then outranks `account`-table moderation tools.
- The host's own account keeps its Administrator switch
  (`ui/HomeScreen.kt:586` desktop; Android `ui/HomeScreen.kt` account card
  at :830-).
- Ad-hoc moderation beyond the UI (kick, mute, teleport) remains available to
  the host through the same console authority the relay already exercises;
  `mutetime` (realmd.sql:59) exists if a mute button is wanted later.

## 11. Account listing / management

- New relay op `account_list()` → `SELECT id,username,gmlevel FROM account
  ORDER BY id` (no user input) through `LoginDatabase`,
  mirroring `account_info`'s guard family (`world_runtime.cpp:320-343`).
  Row counts stay tiny by design (friends + bots + host; the bot accounts are
  already counted separately, `world_runtime.cpp:1007-1024`).
- Online status: `account onlinelist` (SEC_CONSOLE, `Chat.cpp:85`) via
  `issue_command`, parsed like `create_account` scans output; or a later
  structured op. This also fills the placeholder both platforms already
  ship (Android `LanScreen.kt:176/:191` "no remote player tracking yet").
- Ban state: join against `account_banned` where `active = 1`
  (realmd.sql:89-100, enforced `AuthSocket.cpp:466-477`).
- The Android LAN_HOST host gets the same screen through the shared
  supervisor contract (the AIDL surface already grew `createAccount` once —
  `RealmService.kt:244-251` — the same seam carries the new verbs).

## 12. Audit

- **Invites/provisioning/approvals/bans/GM changes** — the host app appends
  rows to its local audit store (invite_id, action, username, accountId,
  source IP, timestamp, outcome). Convention follows the existing rule that
  account *secrets* never reach journals (`UserAccountStore.kt:17`) —
  these rows carry no secrets (token hash, never the token; never s/v).
- **Logon attempts:** the success trail already exists — realmd writes one
  `account_logons` row per **successful** logon (`AuthSocket.cpp:1164`,
  success path per §1; schema realmd.sql:112-118), so the Players screen's
  column is **last logon** (accountId, ip, time) read from that table — no
  cmangos patch required. Failed attempts have no history table: they are
  visible only through the live `failed_logins` counter (:668, incremented
  when `WrongPass.MaxCount > 0`, reset to 0 on every success, :1161) and
  any `ip_banned` autoban rows (:692-694). The Players screen therefore
  shows "failed attempts since last logon" from `failed_logins` — a live
  counter, explicitly labeled as such, never mislabeled as a failure
  history. (Revision 2 planned a "last failure" column fed from
  `account_logons`, which would have displayed last *successes* mislabeled
  as failures; corrected here.)
- **Supervisor journal:** provision/ban/gm verbs publish durable actions the
  way `provisionAccount` already does
  (`DurableRuntimeSupervisor.kt:498-499`, "account-provisioned-core-command").

## 13. Open questions

1. **TLS stance for tunnel-less LAN.** v1 bearer-token-over-LAN is the
   decision above; when do we require pinned-cert TLS (host displays
   fingerprint) instead?
2. **Friend-side password retention** — keep the typed password on the
   friend's device for auto-login (mirrors `UserAccountStore`) or require
   retyping per session (strictest, matches "SRP never needs the stored
   password" but breaks the auto-login UX the app is built around)?
3. **`account delete` vs permanent ban** for "remove friend" — delete is
   cleaner DB-wise only if character rows are purged in the same action;
   needs a decision before the UI ships a destructive button.
4. **Provisioning port number**, and whether the reference firewall rule
   ships as three exact ports or a range; SettingsScreen firewall copy needs
   matching text.
5. **Endpoint parser vs tunnels** — `RuntimeTopologyTest.kt:20-23` pins
   `100.64.0.1` (Tailscale CGNAT) as a required rejection, and
   `parseCanonicalIpv4` (`RuntimeTopology.kt:44-62`) refuses hostnames by
   design. Widening either is a reviewed-invariant change (and a test
   change); until then invite payloads carry literal tunnel IPs, and
   `isPrivateOrLinkLocal`-shaped wireguard subnets (10/8) work as-is.
6. **Sibling alignment — tunnel §6 is still unrevised (live contradiction).**
   As of this revision, `design-tunnel.md:313-314` still renders "2) Your
   account (created by me, keep private): username … password …" inside the
   copied invite block, while design-lan rev 2 §4.2 asserts the Hard rule
   ("no invite text ever embeds a working username+password", :450-454) and
   this design's §4.1 demands the same split. A reader implementing from
   design-tunnel alone ships the anti-pattern the other two docs forbid —
   on the most exposed lane. Tunnel §6 must be revised before tunnel-mode
   invites ship; it must also route its app-equipped friends ("if you use
   the Pocket Realm app: paste <advertised> into the Join pane") to this
   design's verifier path (§2/§4.1), which it currently does not, and its
   lockout plan step must pick up the §4.3 bind condition. Until then, the
   rule in §4.1 is enforced at the surface this design owns: the host UI
   never renders a credential inside the copied invite text, whatever
   design-tunnel's block says.


---

<!-- Section 4 of 4 — verbatim from out/desktop-parity/design-timeframe.md -->

# Design — Per-flavor content-patch timeframe control

*Section of the desktop multiplayer + timeframe design document. Scope: the desktop app lane
(`repo/desktop`). Tree ownership (design-critique round 2): core/submodule facts below are
verified against the **canonical tree** `E:/pocket_realm_complete`, whose submodules are
materialized at exactly the pinned revisions this tree records (`git submodule status` on E: →
`be1a5206…` classic-db, `ce83805d…` cmangos, `7e2cd2fb…` playerbots — the same SHAs the C: mirror
pins). The two registers cited (`out/FEATURE-PARITY-FIX-PLAN.md`, `out/fix-plan/*.md`) exist only
on the C: mirror today (`ls E:/pocket_realm_complete/out/fix-plan` → empty, `wc -l` = 0); those
citations are marked as C:-only and port with the register.*

## 0. The one thing to say plainly first

**The client patch is FIXED per flavor. Only world CONTENT is gated.**

A vanilla install runs the 1.12.1 build-5875 client and nothing else (`schemas/flavor.json:16`
—"client_version": "1.12.1 build 5875"; the import/prepare lane refuses any other build:
`tools/win_prepare_data.py:71-74` carries exactly one qualified identity, and
`PreparedDataStore.kt:39-40` fail-closes the prepared-data envelope on
`clientBuild == 5875 && cmangosFamily == "classic"`). A TBC flavor will run the 2.4.3 client; a
WotLK flavor the 3.3.5a client (SRP6 frame, auth fixtures, realmlist: DESK-56/94 in
`out/desktop-parity/DESKTOP-PARITY-GAPS.md:2121-2195`). The timeframe knob never changes what
binary talks to the server, never changes DBCs, maps, vmaps, mmaps, or the client's own UI. Picking
"2.0.1" in a TBC world does not give you a 2.0.1 client; it gives you a 2.4.3 client looking at a
world whose *content availability* matches 2.0.1. The UI copy must say this sentence, nearly
verbatim, so nobody reads the knob as a client-version switch.

## 1. Model: flavor → ordered stages

The control adds a second dimension under the flavor:

- **flavor** — vanilla / tbc / wotlk. Already the app's identity unit
  (`ClientFlavor` registry shared to desktop; `ClientFlavors.qualified()` fail-loud
  "exactly one qualified" gate per register DESK-93, `DESKTOP-PARITY-GAPS.md:2153-2176`;
  the per-flavor prepared-data pointer `active-<flavorId>.json` from DESK-128 is implemented in
  this tree at `tools/win_prepare_data.py:64-74,295-303`).
- **stage** — one content patch of that flavor's timeline, canonical string ids:
  - vanilla: `vanilla-1.1` … `vanilla-1.12.1` (the ask's 1.1→1.12.1; each shipped stage is one
    registry entry — 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12.1)
  - tbc: `tbc-2.0.1`, `tbc-2.1`, `tbc-2.2`, `tbc-2.3`, `tbc-2.4.3`
  - wotlk: `wotlk-3.0.2`, `wotlk-3.1`, `wotlk-3.2`, `wotlk-3.3`, `wotlk-3.3.5a`
- **Default stage = the flavor's final patch** (1.12.1 / 2.4.3 / 3.3.5a) — the world as the pinned
  DB already ships it. Every stage above the floor is *more* restrictive than the seed, never
  inventive: the control can only make content the DB contains become unavailable earlier, and can
  only enable content the DB contains at or after the chosen point. It cannot resurrect
  pre-nerf data the seed never had (see §4.5).

Stage identity is one flat string. No "2.0.x" ranges, no arbitrary dates, no fractional patch
feeling — the stages are exactly the ids the user picked from, and everything downstream
(conf, DB layer, era policy, acceptance tests) keys off that string.

## 2. Where the data comes from: one seed per flavor + reversible stage overlays

**One pinned seed per flavor. Per-stage seeds are rejected.** The seed pipeline is a hash chain:
414 manifest-verified migrations (`schemas/database-migrations.json` — checked this session: 414
entries, 403 world / 10 playerbot-world / 9 characters / 1 realmd / 1 logs) translated to SQLite
transcripts whose bytes are pinned by the append-only baseline
(`schemas/sqlite-seed-baseline.json`: `entries: 414`, `manifest_sha256`, per-transcript digests)
and again by the device-floor preflight against the ABI lockfiles
(`tools/verify_seed_sqlite_floor.py:2-35`). Editing or forking transcripts per stage would break
that chain three times over. The seed stays the flavor's final-patch state.

The stage delta is a **layer applied over the seeded datadir**, not a different seed:

- **Manifest**: `schemas/timeframes/<flavor>.json` — the per-flavor stage registry. Each stage
  entry carries: canonical id, order, title/copy strings, the availability sets (event ids,
  quest ids or id ranges, npc/vendor entries, doors/zone-entry ids), feature flags (all
  flavor-level today — see §4.5), and the era term-class deltas for §5. Every id set is
  **validated against the pinned DB: no unknown ids** (the same rule the register already orders
  for the event matrix, `out/FEATURE-PARITY-FIX-PLAN.md:806`).
- **Overlay SQL**: `schemas/timeframes/<flavor>/overlays/<stage>.sql` — per-stage, idempotent,
  **flag-set statements only, never DELETE** (reversibility is the whole point: moving backward
  re-applies an earlier overlay over the same datadir, nothing must have been destroyed). Exact
  columns are curated and measured at the S4 DB pin (the register's event-dump step,
  `FEATURE-PARITY-FIX-PLAN.md:801` [C:-only] — recurring / absolute-date / always-off
  classification; 153 events tbc / 300 wotlk measured counts, `:1285` [C:-only]). The reversible
  form is now first-hand verified against the pinned schema on the canonical tree
  (`native/cmangos/sql/base/mangos.sql`, pin `ce83805d`): `condition_id` appears **19 times
  schema-wide**, including on `npc_vendor` (:4514) and `creature_loot_template` (:1057) for
  vendor/loot demotion, `areatrigger_teleport` (:97, with `status_failed_text` :98) for entry
  gating, and `quest_template.RequiredCondition` (:10141) for quest gating — all backed by the
  `conditions` table (:691-701) whose `ConditionType` enum
  (`src/game/Globals/Conditions.h:27+`) includes the compositors AND/OR/NOT and
  `CONDITION_ACTIVE_GAME_EVENT` (=12). That yields the **stage-marker mechanism**: the overlay owns
  one marker `game_event` row per stage boundary, active exactly when the world's stage ≥ that
  boundary (moved by the same game_event normalization that moves real events), and every demoted
  row points at a `conditions` row of type `CONDITION_ACTIVE_GAME_EVENT` naming its marker — so
  backward/forward moves are pure flag-set UPDATEs with zero core changes.
- **Provenance**: overlays are pinned like every other input. The precedent already exists:
  `schemas/seed-augment/PROVENANCE.json` binds a captured SQL artifact to the manifest sha and the
  seeder fails loud on mismatch (`tools/seed_sqlite_from_manifest.py:101-112`). Stage manifests and
  overlays carry the same binding to the migrations manifest they were measured against.
- **Application point**: the overlay runs against `classicmangos` (world DB only;
  `classiccharacters` is never touched) at world start when the active stage differs from the
  applied one, recorded in the engine-created `pocketrealm_meta` database
  (`DatabaseSqliteControlPlane.kt:31-33` — "the meta database is engine-created (not part of the
  seed transcripts)"), whose ledger statuses already include `ROLLED_BACK`
  (`DatabaseSqliteControlPlane.kt:27-29`) — the durable "this datadir is at stage X, was at Y"
  state. The datadir is runtime state; the pins protect the *transcripts*, and post-seed SQL
  layers are exactly how the MariaDB lane has always worked.

This answers the ask's "DB content per patch stage vs availability flags over one seed" with:
**availability flags over one seed, expressed as reversible SQL layers over the world DB plus
runtime gates — with the seed/hash-chain and the seed-augment provenance discipline untouched.**

## 3. Gating mechanisms, honest per kind

| Content kind | Mechanism | Honest limits |
|---|---|---|
| World/seasonal events | `game_event` windows via the per-stage overlay + stage activation matrix (extends register H1R2-2 from per-flavor to per-(flavor, stage): same dump-and-classify step, same `.event start <id>` acceptance shape, `FEATURE-PARITY-FIX-PLAN.md:794-807` [C:-only]), plus the **stage-marker events** of §2 — the same `CONDITION_ACTIVE_GAME_EVENT` gates that demote vendor/loot/quest rows key off marker events, so every mechanism shares one activation story | Event *windows* move; event *content* is whatever the DB has. Realm-time policy (W2-1: realm time is the device clock today, `FEATURE-PARITY-FIX-PLAN.md:3804` [C:-only] / `out/fix-plan/integration-coexistence.md:136`; UTC pin recommended) governs window edges — the timeframe control deliberately does not re-litigate W2-1, it consumes it |
| Raid/dungeon/zone unlock | **Resolved — data-only, no core patch.** Dungeon/raid entry is areatrigger-driven and the conditions system is already wired into that path: `WorldSession::HandleAreaTriggerOpcode` consults `at->conditionId` via `sObjectMgr.IsConditionSatisfied(..., CONDITION_FROM_AREATRIGGER_TELEPORT)` with the row's own `status_failed_text` on refusal (`src/game/Entities/MiscHandler.cpp:744-753`), backed by `areatrigger_teleport.condition_id` (:97) in the base schema. The overlay points entry triggers at stage-marker conditions; the refusal copy is per-trigger DB text | Zone-entry *doors* (gameobjects) are lock/spell-gated, not condition-gated — where an entry is door-only rather than trigger-only, that one case needs the patch fallback below; curated per stage at the pin. `DungeonMap::Add` still runs `CanEnter` (`Map.cpp:1789-1793`) as the second net |
| Vendor/loot availability | Flag-demotion overlay (§2) — reversible conditions UPDATEs on `npc_vendor.condition_id` (:4514) / `creature_loot_template.condition_id` (:1057), never row deletion | Loot *stats* are final-DB facts; a stage never rewrites item stats |
| Quest flags | **Resolved — data-only.** `quest_template.RequiredCondition` (INT, :10141) takes a `conditions` row: the overlay sets/unsets it per stage (pointing at stage-marker conditions), reversible UPDATE, no row deletion | Accepted/turned-in quests already in character logs stay (§7) |
| Talents/spells | **Mostly out of scope, stated plainly.** The DBCs are client-fixed per flavor and the DB is a final-state snapshot: intra-flavor historical balance (e.g. a 1.2-era talent tree) is *not recoverable* from one 1.12.1 seed and the control does not pretend to. What IS gated at stage level: cross-expansion feature availability that a final-state DB still expresses as learnable rows (expansion abilities/levels beyond the stage's frontier, glyph usage where a later-stage DB carries it, hero classes per flavor), via the same manifest → core-gate patch path | Anything the seed can't express is a documented limit, not a fake gate |
| Chat era-policy | Per-stage term classes — §5 | Exact-match vocabulary policy; cannot reason about content |

## 4. Runtime wiring on the desktop lane

1. **Settings** — one field in `Settings.Snapshot`: `timeframeStage: String = ""`
   (`""` = default/final stage of the active flavor). Parsing follows the snapshot's
   degrade-per-field posture (`desktop/src/main/kotlin/com/pocketrealm/storage/Settings.kt:63-102`):
   a value that is not a registered stage of the active flavor degrades to `""` with the same
   one-field discipline the provider selection uses (`Settings.kt:97-100`). Persisted per flavor via
   the existing JSON snapshot (`Settings.kt:105-132`); validation happens at read time against the
   qualified flavor so a stale stage can never survive a flavor change silently.
2. **Conf emission** — `ServerRuntimeFiles.worldConfig` reads the snapshot once at world start
   (`desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:96`) and gains one
   reviewed line in the golden-pinned body: `PocketRealm.TimeframeStage = "<stage-or-default>"`,
   next to the existing `PocketRealm.*` keys (`ServerRuntimeFiles.kt:154-157` — the key precedent).
   The golden pin test gains the line the same way every other contract line did
   (`desktop/src/test/kotlin/com/pocketrealm/server/DesktopServerRuntimeFilesTest.kt:90-145`).
   Applying at world start is the app's universal contract for realm-affecting toggles (the LLM
   switch copy already says "applies at the next realm start",
   `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt:70-71`).
3. **Stage overlay application** — at world start, before native boot: compare desired stage vs
   `pocketrealm_meta` state (§2); if different, apply the overlay with the ledger row, then boot.
   This rides the existing start sequence in `BootWorld`/`DesktopRuntimeBackend`; a failed overlay
   application fails world start loudly (never boots a world whose DB state contradicts the conf).
4. **Native consumer** — narrowed by the round-2 verification: entry/quest/vendor/event gating is
   **data-only** (conditions + game_event, §3), and the level cap is already a core conf key
   (`MaxPlayerLevel = 60`, `mangosd.conf.dist.in:826`, reloadable at
   `src/game/World/World.cpp:548-549` — flavor-level, not stage-level). The `native/patches/cmangos`
   gate patch shrinks to the door-only-entry fallback (§3, the one case conditions don't cover) and
   the playerbots patch keeps the era-policy work (§5); both read the one `PocketRealm.TimeframeStage`
   conf key.
5. **Era assets** — the lore-card asset stays a staged bundled asset
   (`ServerRuntimeFiles.kt:323,499` today: `lore_cards_v112.jsonl`); TBC/WotLK flavors stage their
   own corpus file (`lore_cards_<flavor>.jsonl`), and stage filtering is done by the native
   load-time lint (§5), not by shipping per-stage files.

## 5. The chat era-policy follows the chosen patch, not just the flavor

Today the entire policy is a hardcoded 1.12 verdict:
`native/patches/playerbots/PlayerbotLlmTruthCore.h:824-830` (`EraAlwaysBanTerms`:
shattrath / draenei / pandaren / acherus) and `:835-863` (`EraSensePhrases`: "flying mount",
"portal is open", "burning crusade", "wrath of the lich king", "hellfire peninsula", …), consumed by
three surfaces:

- `EraScan` — the reply filter (`:944-971`, negation-aware `EraHit` `:871-884`);
- `EraLintCards` — the **load-time lint** that drops contaminated lore cards before they can serve
  (`:976-990`, wired at card load `:635-641`);
- `ResolveEraBiasJson` — the embedded-llama token bias built from the same term list
  (`native/patches/playerbots/PlayerbotLlmFilters.cpp:198-244`, conf key
  `AiPlayerbot.LLMEraBias`, pinned by `tests/test_llm_truth.py:163-166`).

Design: the term classes become **per-stage classes compiled into the patch headers**, generated
from the stage manifests by a tools script (the manifests are the single source; the generated
header is hash-pinned like every other generated pin and `check_repo.py`/`check_sources.py` verify
sync). The world resolves its class set from `PocketRealm.TimeframeStage`:

- The rule stays *same-franchise-relative*: stage S bans the terms first introduced after S's
  point in the timeline, and keeps the existing never-ban discipline
  (`PlayerbotLlmTruthCore.h:857-862` — words like *northrend*, *outland*, *dalaran*, *lich king*
  collide with earlier legitimate vocabulary and only full later-era senses/paths are banned).
  Concretely: `vanilla-1.12.1` is today's list unchanged (the grounded baseline);
  `tbc-2.0.1` legitimizes the Outland/draenei vocabulary and bans WotLK-era senses
  (acherus/ebon blade/ "dalaran floats"); `wotlk-3.0.2` legitimizes those and bans
  post-3.0 senses (Argent Tournament, ICC-era terms) — the specific per-stage lists are curated at
  each DB pin, illustrative here, not asserted as data.
- Per-stage *lore cards*: one corpus per flavor, filtered per stage by `EraLintCards` at load with
  the stage's classes. Honest limit: term-level lint catches what terms can name; a 3.3-only card
  that mentions no bannable term would survive — so the prompt side also gets an explicit stage-era
  instruction line (the native prompt builder injects "the world is in patch X" into the system
  context; the user's editable prompt pack is untouched, `LlmPromptPack` stays as-is).
- The Python mirror keeps its contract: `tools/llm_lab/build_lore_cards.py` scrubs at build time
  with the same policy (its era-tell tables at `:54-92` and the mirrored era lint at `:325-350`),
  now parameterized per flavor corpus; the source-contract tests in `tests/test_llm_truth.py`
  gain per-stage pins.

## 6. The UI knob per flavor in the desktop app

One card in `SettingsScreen`, matching the established card anatomy
(title, control, honest `bodySmall` copy — `SettingsScreen.kt:45-96`):

- **Title:** "World timeframe".
- **Control:** the ordered stages of the *active qualified flavor* as a selectable list (radio-row
  style, the flavor's timeline top-to-bottom), default selection = final patch. Flavors come from
  the same `ClientFlavors.qualified()` resolution the rest of the desktop conf/auth paths are
  being routed through (DESK-93's plan, `DESKTOP-PARITY-GAPS.md:2166-2175`); **when no flavor is
  qualified beyond the shipped vanilla lane, the card shows exactly the vanilla stage list or an
  honest "final patch only" state — it never fabricates TBC/WotLK stages for a lane that cannot
  run them** (today `FLAVOR_IDENTITIES` is vanilla-only, `win_prepare_data.py:72-74`, and
  `supported_client_builds` is empty by contract, `schemas/flavor.json:18`).
- **Copy, mandatory sentences:** the client-version sentence from §0 ("The game client stays at
  its fixed version; this gates world content only"); the apply-time sentence ("applies the next
  time the realm starts"); and for a *backward* selection the consequence sentence from §7
  ("later content closes; items, levels, and spells your characters already have stay").
- Home-screen status line gains the active stage next to the realm status so "where in time is my
  world" is always answerable without opening Settings.

## 7. Resets and time-travel

- **No mid-session switch.** The conf is generated at world start and the world pins its
  generation for its lifetime (`ServerRuntimeFiles.kt:48-52`); the UI disables the card while the
  realm is running and directs the user to stop first — the same discipline as every conf-riding
  toggle. The spent-world/restart constraint (second in-process start refused, DESK-25,
  `DESKTOP-PARITY-GAPS.md:1069-1083`) means the stage change rides the app-relaunch path exactly
  like any other world-affecting change.
- **Forward (older → newer):** additive. Overlay applies, out-of-window events normalize in, entry
  gates open, vendors restock their later lists. Characters keep everything. Safe by construction.
- **Backward (newer → older):** availability closes (events out of window, entry gates shut,
  vendors/quests demoted by the earlier overlay). **What does NOT roll back, stated in the UI
  copy:** items looted, levels, spells, skills, attunements already on the character stay. A
  character standing in a zone whose entry is now stage-gated is not teleported out — the gate
  stops new entries, it does not evict (eviction would be new world-state surgery for zero
  product value; copy explains it).
- **The real reset tool already exists:** character/world state rollback is what the realm-data
  archive snapshot/restore is for (DESK-7/33/38, `DESKTOP-PARITY-GAPS.md:837-870` — stopped-state
  ZIP export/inspect/restore). The timeframe control recommends it in copy ("want the world's
  characters back at an earlier point too? restore a realm-data archive taken before that stage")
  and does not build a second, weaker rollback.
- **Event state hygiene:** backward moves that stop active events must despawn cleanly — this is
  register H1R2-2's "no orphan spawns after event end" acceptance test
  (`FEATURE-PARITY-FIX-PLAN.md:806`), re-run per stage transition, not just per flavor.

## 8. What else this control touches (the enumeration nobody asked for)

- **Realmd/login:** nothing — content-only. realmlist, SRP6, per-client-build auth are
  flavor-fixed (DESK-56/94) and the stage never appears there.
- **Client import/prepare:** unchanged (§0). `win_prepare_data` gains nothing per stage; the
  prepared generation is stage-agnostic (the overlay lives in the datadir/meta layer, deliberately
  NOT baked into the multi-GB generation — switching stages must not re-run the extractor).
- **Add-ons:** the catalog targets client builds, not patches
  (`android/app/src/main/assets/addons/catalog-v1.json:12` — "clientTarget": "1.12.1 + 2.4.3"), so
  add-on availability is already flavor-gated and must **not** re-filter per stage (an add-on the
  fixed client can run stays runnable).
- **Bots:** the bot equip/random-item caches are a captured final-state corpus with manifest-sha
  provenance (`schemas/seed-augment/PROVENANCE.json`; `seed_sqlite_from_manifest.py:75-112`). A
  stage-gated world will offer bots fewer of the captured items; that is degraded-but-functional.
  Per-stage cache re-capture is possible (same capture path) but deferred — recorded here so it is
  a decision, not an accident. Bot-carried attunement/keys for stage-gated raids is register
  H1R2-3's problem (`FEATURE-PARITY-FIX-PLAN.md` §H1R2-3, heroic keys policy) and this design
  defers to it.
- **World console / acceptance tooling:** `tools/world_console.py` today exposes `world-gm` but no
  generic command passthrough (`tools/world_console.py:120-127`); driving `.event start/stop`
  acceptance per stage needs one small relay op added there (the register already anticipated the
  console path, `FEATURE-PARITY-FIX-PLAN.md:803`).
- **Calendar UI (WotLK):** renders stage events only — the calendar work (H1-11, gated behind
  WotLK anyway) consumes the same activation matrix.
- **Support/diagnostics:** the active stage id rides the lifecycle/support-bundle JSON so a support
  trace states "stage tbc-2.1" (`ServerRuntimeFiles.writeLifecycle`,
  `ServerRuntimeFiles.kt:403-409`, is the existing pattern).
- **Gates:** the manifest/overlay validation joins the repo Python gates (`check_repo.py` /
  `check_sources.py` on the C: mirror; the canonical lane's `check_flavor.py` + `leak_sweep.py`
  **verified present on E:** this round — `python3` existence check → both True). Desktop
  golden-pin and settings tests extend as in §4; era-policy source-contract tests gain per-stage
  pins.

## 9. Sequencing (what is real when)

1. **v1 (this lane, vanilla-only):** the mechanism end-to-end on the shipped flavor — stage ids,
   manifest schema + validation gate, conf key, meta-ledger stage state, era-class generation with
   `vanilla-1.12.1` = today's lists (byte-equivalent behavior), UI card, forward/backward overlay
   machinery proven on one vanilla stage beyond the final (e.g. an events-and-BGs stage), golden
   pins updated.
2. **With the S4 DB pins (per register):** measured event/classification dumps (H1R2-2),
   per-stage id sets, vendor/loot/quest overlays, `.event` acceptance via the console op — stage
   coverage grows only as curated, measured manifests land, never before. This mirrors the
   register's evidence discipline for every seed-floor number
   (`FEATURE-PARITY-FIX-PLAN.md:1285`).
3. **With TBC/WotLK flavor qualification:** the same machinery reaches those flavors via the
   registry; nothing in this design is vanilla-specific except the initial curated data.

## 10. Grounding & checks run during this ask

**Tree ownership (owned per critique round 2):** app-lane facts are from the C: mirror
(`C:\pocket_realm_complete\repo`); core/submodule facts are verified against the **canonical tree**
`E:/pocket_realm_complete`, whose submodules are materialized at the same pinned SHAs the C: tree
records. The two registers cited live **only on C:** today —
`out/FEATURE-PARITY-FIX-PLAN.md` (H1R2-2 §:794-807, F2-9 §:1285, W2-1 §:3800-3815; the same
treatments exist C:-side at `out/fix-plan/server-systems.md:475` and
`out/fix-plan/integration-coexistence.md:136`) while `E:/pocket_realm_complete/out/fix-plan/` is
**empty** (`ls | wc -l` → 0). Those citations are marked [C:-only] in the text and port when the
register does; they are plan context, not code the design depends on.

Read this session: era policy and its three consumption surfaces
(`native/patches/playerbots/PlayerbotLlmTruthCore.h`, `PlayerbotLlmFilters.cpp`); conf emission +
staging + lifecycle (`desktop/.../ServerRuntimeFiles.kt`, golden pin
`DesktopServerRuntimeFilesTest.kt:90-145`); settings (`storage/Settings.kt`,
`ui/SettingsScreen.kt`); seed pipeline and pins (`tools/seed_sqlite_from_manifest.py`,
`schemas/seed-augment/PROVENANCE.json`, `schemas/sqlite-seed-baseline.json`,
`tools/verify_seed_sqlite_floor.py`, `schemas/database-migrations.json`,
`DatabaseSqliteControlPlane.kt`, `DesktopSeedLocator.kt`); prepared-data identity
(`android/.../PreparedDataStore.kt`, `tools/win_prepare_data.py`); register treatments
(`out/desktop-parity/DESKTOP-PARITY-GAPS.md` DESK-25/56/62/77/93/94/128); `schemas/flavor.json`;
native relay verbs (`native/realm-runtime/src/world_runtime.cpp`); `tools/world_console.py`.

Commands run this session and their outcomes:

*Design (initial pass, C: mirror):*
- `git submodule status` (C:) → `-` prefix on `native/classic-db`, `native/cmangos`,
  `native/playerbots` = not checked out on C:. Per-patch content facts are marked curated-at-pin;
  measured counts (153/300 events) cited from `out/FEATURE-PARITY-FIX-PLAN.md:799,1285`, not
  re-measured.
- `python3` scans (C:) → `schemas/database-migrations.json`: 414 entries, zero naming `game_event`
  (the table arrives in the base dump `0004-world-ClassicDB_1_12_1_z2815`);
  `schemas/sqlite-seed-baseline.json`: `entries: 414`, `manifest_sha256 3771fc35…`, per-transcript
  digests present.
- File-existence (C:) → `tools/check_flavor.py` **absent** (canonical-lane gate);
  `check_repo.py`/`check_sources.py` present. Grep sweeps → `PocketRealm.*` conf keys emitted at
  `ServerRuntimeFiles.kt:154-157`, consumed in `native/patches/cmangos/PocketRealmInteraction.cpp`;
  `AiPlayerbot.LLMEraBias` pinned at `tests/test_llm_truth.py:163-166`; add-on `clientTarget`
  strings in `android/app/src/main/assets/addons/catalog-v1.json`.

*Revision pass 1 (C: only — submodules still absent locally; `condition_id` incorporated as
critique-round-1 evidence, explicitly NOT re-verified then):* `ls -a repo/native/cmangos` → empty;
`find … -name mangos.sql` → no hits; `grep -rn condition_id …/native/cmangos` → no hits.

*Revision pass 2 (this round — re-based on the canonical E: tree):*
- `git submodule status` (E:) → materialized: `be1a5206…` classic-db, `ce83805d…` cmangos
  (`heads/master`), `7e2cd2fb…` playerbots (`heads/master`) — the same SHAs the C: tree pins, so
  every fact below holds for the revisions this design cites.
- `ls E:/pocket_realm_complete/native/cmangos/sql/base/mangos.sql` → present;
  `ls E:/pocket_realm_complete/native/patches/cmangos/` → `PocketRealmInteraction.cpp` +
  `DatabaseSqlite*`/`QueryResultSqlite*` present (§4's "same shape" precedent resolves on E:).
- `sed -n '10131,10155p' mangos.sql` → `quest_template` DDL with `RequiredCondition`
  INT (:10141), `QuestFlags` (:10152), `SpecialFlags` (:10153).
- `sed -n '691,710p' mangos.sql` → `conditions` table DDL (condition_entry PK, type/value1-4/
  flags/comments). `grep -c condition_id mangos.sql` → **19** (matches critique round 1).
- `grep -n 'CREATE TABLE `npc_vendor`' -A 8 mangos.sql` → `condition_id` at **:4514**;
  `sed -n '1051,1062p'` → `creature_loot_template` with `condition_id` at **:1057**;
  `sed -n '85,110p'` → `areatrigger_teleport` with `condition_id` **:97** and
  `status_failed_text` **:98**.
- ConditionType enum: `sed -n` over `src/game/Globals/Conditions.h:27+` → compositors
  `CONDITION_NOT/-3, OR/-2, AND/-1`, `CONDITION_ACTIVE_GAME_EVENT = 12`,
  `CONDITION_ACTIVE_HOLIDAY = 26` (stage-marker mechanism in §2).
- Entry path: `sed -n '660,775p' src/game/Entities/MiscHandler.cpp` →
  `HandleAreaTriggerOpcode` consults `at->conditionId` via
  `sObjectMgr.IsConditionSatisfied(..., CONDITION_FROM_AREATRIGGER_TELEPORT)` with
  `status_failed_text` on refusal at **:744-753**; `sed -n '1785,1795p' src/game/Maps/Map.cpp` →
  `DungeonMap::Add` runs `CanEnter` (:1793); `Map::CanEnter` base at :2153,
  `BattleGroundMap::CanEnter` at :2112; `DungeonMap` (Map.h:536) does **not** override — entry
  requirements live in the areatrigger condition leg.
- Level cap: `grep -n MaxPlayerLevel src/mangosd/mangosd.conf.dist.in` → **:826**
  (`MaxPlayerLevel = 60`), reloadable at `src/game/World/World.cpp:548-549`.
- Classic-db content dump: `ls native/classic-db/Full_DB/` → `ClassicDB_1_12_1_z2815.sql.gz`
  (73,289,738 bytes uncompressed via the gzip stream scan); `areatrigger_teleport` present in the
  dump's schema too. **Not read:** the dump's data rows — no per-patch `game_event`/vendor/quest
  rows were measured; those remain curated-at-pin (§9.2).
- Register locations: `ls E:/pocket_realm_complete/out/` → only `desktop-parity`, `fix-plan`;
  `ls E:/pocket_realm_complete/out/fix-plan | wc -l` → **0** (C:-only register, owned in the
  header). `grep -n "H1R2-2|W2-1"` over C: `out/fix-plan/*.md` → `server-systems.md:475`,
  `integration-coexistence.md:136`.

## 11. Open questions

1. **Door-only entry fallback** — which (if any) classic entries are door-gated rather than
   areatrigger-gated, needing the small patch fallback (§3 row 2); curated per stage at the S4 pin
   against the dump now readable on E:. The chokepoint itself is **resolved** this round
   (`MiscHandler.cpp:744-753` + `areatrigger_teleport.condition_id`).
2. **Stage-marker condition host** — confirmed workable with zero core change
   (`CONDITION_ACTIVE_GAME_EVENT` marker events, §2); alternative is a dedicated core condition
   type in the patch. Default: marker events; revisit only if marker-event bookkeeping at S4 pins
   proves noisier than a patch type.
3. **Per-stage bot equip cache** — accept degraded bot gearing on earlier stages in v1 (this
   design's default) or schedule the per-stage cache re-capture into the S4 pin work?
4. **Vanilla stage-list curation** — ship all twelve vanilla stages as selectable in v1 UI, or
   only the stages whose manifests have measured overlays (leaving the UI honest-but-sparse)? The
   design supports either; product copy should not offer stages that are not yet measured.
5. **Backward-move eviction** — confirmed out of scope above; flag for the design critic and the
   product-copy lens to confirm the "not evicted" reading is acceptable.
6. **Era-line injection point** — native prompt builder vs the staged default-prompts file for the
   stage-era instruction line; both are stubbed in the LLM conf plumbing today
   (`ServerRuntimeFiles.kt:319,329-333`) and either fits; pick one when implementing.
