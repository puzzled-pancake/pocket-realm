# Pocket Realm 0.110.0 — the multi-flavor milestone

Release candidate notes (drafted at wave W7; the owner tags the release).
Evidence references point at the archived wave exits under `out/completion/`
and the decision log in `docs/DECISIONS-PORT.md`.

## The headline: three qualified flavors

TBC 2.4.3 (build 8606) and WotLK 3.3.5a (build 12340) join Vanilla 1.12.1
(build 5875) as **qualified flavors** (`schemas/flavor.json`: all three
`qualified: true`, `single_qualified_lane: false`, decision D86). One app,
three private realms, no cross-flavor data flow — per-flavor databases,
journals, backups, add-on generations, and Wine prefixes stay separate, and
the leak sweep enforces it.

Where each flavor actually plays today (the honest posture):

- **Windows desktop (repo checkout): all three flavors, end to end.**
  Import a genuine client (folder or archive) → data prep → realm boot to
  READY → auto-login → play → Save & Exit, with the negative matrix
  fail-closed (wrong-build, doctored-MPQ, oversized → actionable `VAL-*`
  refusals). The 3×3 wire-level auth matrix proves only matching
  client/server pairs authenticate. Installed-image honesty: a packaged
  desktop install carries the Vanilla runtime only and refuses a TBC/WotLK
  boot with the same RUNTIME-LANE MISMATCH message as Android (the
  per-flavor lane runtimes resolve from the repo checkout today); the
  packaged per-flavor runtime lane is the same open D90 work.
- **Android: Vanilla plays today; TBC/WotLK import and prepare, boot is
  Vanilla-only for now.** The per-flavor native lanes and lockfiles are
  built and pinned, but the shipped APK does not route them yet (open
  packaging work, decision D90); picking TBC/WotLK refuses fail-closed
  with copy naming the remedy, while imports and prepared data can be
  staged ahead of that landing.

## Per-flavor substance (not just a flag flip)

- Per-flavor storage budgets from measured genuine clients (TBC tree
  8.0 GB, WotLK 16.6 GB, with prepared-server-data and import-cap numbers
  in the wiki's import page and enforced in `schemas/flavor.json`).
- Era lint per flavor: TBC bans WotLK+ concepts and allows Outland/flying;
  WotLK allows through WotLK and bans Cata+ — corpus-tested both directions
  (no anachronisms and no false bans).
- Per-flavor add-on catalogs (Vanilla's frozen 154-project catalog plus
  researched TBC and WotLK sets; three curated recommendations per flavor;
  install-time `## Interface:` acceptance is per flavor, so cross-era
  add-ons refuse with per-flavor copy).
- Graphics honesty: DXVK is the only route validated for every flavor;
  the Gladio/VirGL lanes are vanilla-only and say so instead of
  misrendering. The client-tweaks patcher is a Vanilla-lane feature and
  says so on the other lanes.
- WotLK's official preparation story is the desktop lane (minutes on a PC
  versus hours on device); on-device prep stays best-effort (decision D77).

## Internet multiplayer, first-party

- **Public realms browser (Nostr):** signed kind-30388 listings (BIP-340),
  bots labeled and never padded, 10-minute freshness, signature verified
  locally. Defaults are community relays (damus.io, nos.lol, primal,
  nostr.band), user-editable on the desktop app (up to eight, `wss://` or
  `ws://`); running your own relay is the escape hatch for groups that
  want discovery without public relays.
- **Join through the iroh gateway (desktop):** a listing that carries a
  join descriptor can be joined from the browser — the gateway muxes the
  game's two ports over one iroh connection, the joiner's realm list is
  rebuilt per flavor, and the relay-only switch (default ON) pins the dial
  to the listing's home relay. Reconnect after a host network change is
  proven: redial the same listing, no republish needed.
- **Privacy posture:** no IP, hostname, or geo in any published payload;
  peer addresses never displayed; the disclosures (relays see your IP when
  browsing; direct connections may reveal it to a host) ship in the UI copy
  and the wiki.
- **Honest limitation:** the gateway transport currently ships with the
  developer checkout only — a packaged install that does not bundle it
  refuses the Join action with a message that says exactly that, and
  hosting publishes the listing without a join descriptor. Personal-tunnel
  hosting (Tailscale/ZeroTier) remains fully supported.

## Friend & LAN arc (unchanged in kind, hardened in detail)

LAN host/join with address re-verification, friend provisioning where the
password never crosses the wire (SRP6), guest accounts with generated
credentials and login-time bans, brute-force lockout on non-loopback
binds, and the personal-tunnel lane with its honesty notes (plaintext game
traffic; tailnet plan limits).

## Engineering gates this release stands on

- Android unit canonical suite: 1848 tests / 0 failed / 1 skipped
  (arm64-v8a, clean-forced run; the final-tree canonical run archived at
  `out/completion/w0/w7-canonical-android.log`).
- Desktop JVM suite: green (`cleanTest test`).
- Gateway lane: 52 tests / 0 failed (clean, no-cache); APK assembles
  (arm64-v8a); spikes lane compiles (JDK 21).
- `check_e_only` green: no C: path literals outside the recorded
  allowlist; the whole toolchain lives on E:.
- Leak sweep, flavor gates, and repo gates green at the qualification
  flip (multi-qualified mode).

## Known limitations & open work (recorded, not hidden)

- Android TBC/WotLK boot packaging (D90) — open, owner-scheduled. On-device
  play for the extra flavors is desktop-evidenced until it lands.
- Packaged gateway transport (Join) — open, developer-checkout posture.
- WotLK archive-lane full E2E — the D87 storage-bound waiver's remedy ran
  GREEN this wave with the storage bound cleared (launch-time free-space
  observation captured with provenance in
  `out/completion/w7/disk-free-measurements.log`):
  import="archive" → boot READY → auto-login to character screen →
  Save & Exit clean (`out/completion/w7/e2e-probe-wotlk-archive.log`);
  D87 retires.
- Device qualification checkpoint (RP6, D100): PREPARED-PENDING-OWNER-SESSION,
  re-scoped to today's capability — vanilla full chain plus TBC/WotLK
  import/prep and the fail-closed refusal copy on device, TBC/WotLK play
  stays desktop-evidenced. The prepared package (both APKs sha-pinned, the
  client archives, the echo host, the runbook) ships with this release
  under `out/completion/w7/`; session evidence lands in
  `out/completion/w7/rp6/` when the owner runs it.

## Upgrade notes

- Android `versionCode` 9 (`versionName` 0.110.0); the desktop package
  and the native win-deps manifest move to 0.110.0 in lockstep
  (`AppVersion.kt`).
- The two-APK distribution is unchanged: the stable MariaDB-provider APK
  and the experimental SQLite-provider APK (`-sqlite-experimental`
  suffix) keep one `applicationId`.
