# The Home Screen

Home is the everyday launcher. It answers three questions: Is the realm running, what setup will be used, and is a local game account ready?

## Realm card

The large card at the top shows the current state.

| State | What it means |
| --- | --- |
| Ready to start | The local realm is stopped and its saved data is available. |
| Hosting stopped | The realm stopped with a reason (for example the network interface went away); the card explains what happened. |
| Starting | Pocket Realm is preparing the database, world, and other services. |
| Realm online | The local world is ready. The account and game can be used. |
| Realm online; game needs retry | The server side is up, but the game client did not start; read the message and retry the game. |
| LAN realm online | The world is up and hosting for LAN/tunnel players; the card shows the hosting address and, when a tunnel is active, the advertised tunnel address. |
| LAN client online | This device runs a client-only session against another host. |
| Saving | The app is waiting for important writes to finish. |
| Stopping | The game and local services are closing in order. |
| Recovering | The app found an interrupted earlier session and is checking it. |
| Needs attention | Something failed and the message explains the next useful action. |
| Supervisor unreachable | The status could not be read. The realm is NOT reported offline — the message names the reason and Start reconnects the supervisor. |

The card also shows the selected bot profile and its starting and target population.

## Game version

A **Game version** switcher card sits with the setup cards. Each chip
selects the version the app runs: Vanilla 1.12.1, The Burning
Crusade 2.4.3, or Wrath of the Lich King 3.3.5a — all three versions are
supported, so every chip is selectable. A version that is not yet
supported would render as a chip that is visible but unselectable.
The pick persists and drives imports, prepared world data, and bot
populations; switching versions re-targets all three on the next import
or session.

Which version actually boots depends on where you run the app. On a
Windows desktop from the full project, all three versions boot; a
development harness (`-PrpFlavor=`) loads a per-version runtime for
development boots, and each version keeps its own MariaDB database family
and migration ledger, so a switch applies the right data on first
boot. An installed desktop image ships the Vanilla runtime only:
picking TBC or WotLK there refuses with the RUNTIME-LANE MISMATCH
message. On Android, the app ships the Vanilla runtime and
migration ledger only: picking TBC or WotLK on a phone refuses at boot
(and at the server database gate) with a message naming the remedy —
re-pick Vanilla. TBC and WotLK can still import and prepare their data
on those devices; the packaging work that will let them boot is tracked
internally.

## Main buttons

**Start realm** starts the private world without opening the game. This is useful for first-time account creation and maintenance.

**Realm + game** starts the world and prepares the game client in one flow. It is the normal shortcut after setup is complete.

When the realm is already running, the available actions change to match the current state. The app does not offer actions that would conflict with starting, saving, or stopping work.

## Join LAN

The LAN host box accepts a private or link-local IPv4 address. **Join LAN** opens a client-only session that connects to a Pocket Realm host on the same private network.

LAN hosting is experimental and off by default. It does not search the network automatically, open router ports, or publish the realm on the Internet by itself. The one exception is explicit and opt-in: a desktop host can turn on **Public realms** (the Realms browser's publish switch, off by default), which publishes a small world-readable listing — realm name, description (MOTD), game version and client build, timeframe stage, rates and player counts, no IP address (the optional status-page link is a public trycloudflare hostname; while the host's internet gateway runs the listing also carries the join descriptor — the realm's iroh node id and its home-relay hostname) — to public Nostr relays every 5 minutes while the realm is up and the status page is Public. Nostr relays and the iroh home relay see your IP address, and a directly-connected player may learn it; the listing is retracted when any of the gates stops or the app closes.

## Active setup

The Active setup card gives a plain summary of the choices that will affect the next session. It can include:

- Bot population and behaviour.
- How many bots are kept near the player.
- How quickly the population grows.
- Resolution and frame-rate limit.
- Interface scale.
- Graphics driver and renderer choice.
- Sound state.
- Automatic login account.
- Whether the normal or advanced bot setup is active.

This summary is useful before starting a long session because many settings apply only on the next realm or game launch.

## Local account

The account card creates a game account inside the local realm. It is separate from an Android account, a Battle.net account, or any online service.

**Player** is the recommended type. It has normal gameplay permissions.

**Administrator** is for local maintenance. It can use privileged realm commands and should not be the default for ordinary play.

When an account has been remembered, the card shows its name and offers **Clear**. Clearing removes the saved automatic-login details from the app. It does not silently delete the corresponding account or characters from the realm database.

## If the game fails but the realm stays online

The status can report that the realm is online while the game needs another attempt. This means the server side is still available and does not need to be restarted just because client preparation failed. Read the message, correct the client or setting problem, and retry the game.
