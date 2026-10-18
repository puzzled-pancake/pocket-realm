# Accounts, Security, and Network Boundaries

Pocket Realm is designed around a private local realm. Its boundaries try to keep gameplay simple without turning the app into a general server administration tool.

## Local account types

The Home screen can create and remember an account through the running local world.

- **Player** is the normal choice for gameplay.
- **Administrator** levels are for local maintenance and have more power inside the realm.

Account names and passwords are limited to 1 to 16 ASCII letters or numbers. The world service checks the account through fixed create, verify, status, and privilege actions.

These are accounts for the private Pocket Realm world. They are not Blizzard or Battle.net accounts.

## Remembered credentials

When the player chooses to remember an account, Pocket Realm stores the username, password, local account identity, and local privilege level in private no-backup storage. Only the app owner can read the file under normal Android permissions.

The secret is deliberately kept out of:

- Supervisor journals.
- Runtime status messages.
- Ordinary structured logs.
- Diagnostic evidence.
- Support bundles.

Clearing remembered details removes the local saved record. It does not delete the account or characters from the realm database.

## Automatic login

Automatic login is a one-time input action, not a background password service. Pocket Realm waits for the recognised build 5875 login window, a ready renderer, a stable window shape, and neutral input. It then types the remembered local details through the same bounded input bridge used by the display.

The attempt has a timeout. It is cancelled if a different window, a modal box, an unstable surface, or active input makes the target uncertain. Credentials are dropped from the login helper's memory when the attempt completes or is cancelled.

Before typing a remembered account, the app verifies that it still belongs to the current local realm.

## Private service controls

The database, login server, world server, client runtime, display, importer, and supervisor are not exported for other Android apps to call. Their internal interfaces offer named actions rather than general command execution.

For example, the world service can create a validated account or request a save. It cannot be given an arbitrary server console command. The database service can start, stop, snapshot, or recover its own data. It cannot be handed arbitrary SQL by a screen.

## File and generation checks

Managed client files, runtime packages, prepared server data, database migrations, graphics packages, and add-ons use manifests and SHA-256 hashes. A hash is a fingerprint used to notice a changed or incomplete file.

Publication normally uses a completed generation and an active pointer. This means an interrupted copy should not make a partly written generation active.

## Add-on downloads

Custom add-on installation is a player-started action for a public GitHub repository. Pocket Realm resolves the request to an exact 40-character Git commit, downloads through approved GitHub addresses, and checks the archive before publishing it.

The validator rejects unsafe paths, links, executable payloads, excessive expansion, duplicate paths, and unsupported Vanilla interface declarations. The current limits also bound archive size, individual file size, entry count, folder count, and nesting depth.

An add-on still runs inside the original game client's add-on system. Validation reduces packaging risk, but it does not promise that every Lua add-on is well designed or compatible.

## Local networking

In normal local mode, the client connects to 127.0.0.1. That address means this device only.

The game uses the classic realm login port 3724 and world port 8085. These are fixed gameplay ports. When friend access is enabled (below), one more fixed port — TCP 3725 — listens while a hosted session with friend access is up, and never otherwise. MariaDB uses a private file socket and does not listen on a public TCP port.

## LAN host and join

LAN hosting is off by default, and hosting additionally requires the explicit "Allow LAN players" toggle. When enabled, Pocket Realm accepts one exact numeric private or link-local IPv4 address from the active network interface. It rejects public addresses and hostnames. Virtual adapters (Hyper-V, vEthernet, WAN Miniports) are excluded from the offered addresses, and the address you pick is re-proved against the live interfaces before each host start and while hosting — if the network changes, the session saves the world and stops instead of advertising an unreachable address.

A LAN joining device runs its client stack and points it at that private host. The Realms browser can list public realms from community Nostr relays (damus.io, nos.lol, primal, nostr.band — user-editable on desktop): browsing connects to those relays automatically, and the relays see your IP address. There is no router port forwarding. Internet play is first-party since the iroh gateway: a Public-realms listing that carries a join descriptor can be joined from the desktop app's browser (the Join action dials the realm's iroh node id through its home relay — your relay-only switch, on by default, pins the dial to that relay (iroh's transport may still move an established connection onto a direct path)), and a personal tunnel you set up yourself remains the other path (see "Hosting over the internet" below); the app never opens your router. Honest build posture: the gateway transport currently ships with the developer checkout only — a packaged install that does not bundle it refuses the Join action with a message that says exactly that (and hosting simply publishes the listing without a join descriptor), until the packaged-transport lane lands. Joining shows your IP to the relays and potentially to the host (direct connections may reveal it). A joining guest can test the host's address first (a connectivity check to port 3724); the invitation is just the address literal, because the game ports are fixed (3724 realm login, 8085 world).

If the host's active interface or address changes, the session is no longer considered the same safe binding. The player must restart it on the intended network.

### Guest accounts

A guest account is created by the host while the realm is hosting — for a friend physically in the room, or for a tunnel friend whom the invite directed to the Guests card. The host types the account name; the password is generated (16 characters, unambiguous alphabet). Guest accounts are always Player-level, and the credential pair is shown only on the host's screen with the instruction to hand it over privately — in person, or a one-to-one message — never pasted into a group chat. A pasted credential is a permanent working secret on an uncontrolled channel.

The host's own remembered account is never typed at another realm's login screen: when your app joins a LAN host, automatic login stays off for that session and you log in with the account the host created for you. Characters a guest creates live in the host's realm database — they exist wherever that host's realm runs.

Revocation today: while hosting, the app can ban a guest account — the ban takes effect at the guest's next login attempt — and lift the ban again from the Guests list. A guest who is online when banned stays in the world until they log out or the host saves and exits (disconnecting an online guest immediately is not available in this build). There is no in-app guest account deletion (deleting an account orphans its characters).

### Password guessing lockout

When the realm binds any address other than loopback, the login server enables a brute-force lockout: ten wrong passwords from one address bans that address for 15 minutes. The lockout deliberately does not apply to the local (loopback) realm, where a lockout could only ever lock you out of your own game. A successful login resets the counter.

### Windows Firewall

The first time you host, Windows Firewall may ask to allow Pocket Realm on your private network. The prompt must be answered **Allow** for **Private networks**. If the prompt was cancelled, or the LAN is marked **Public** in Windows, guests cannot connect even though the host sees its own realm online — set the network to Private in Windows Settings and host again, or run this once from an admin prompt:

```text
netsh advfirewall firewall add rule name="PocketRealm LAN" dir=in action=allow protocol=TCP localport=3724,8085,3725 profile=private
```

The three-port rule covers friend access's provisioning port (3725); if you never enable friend access, the two gameplay ports (3724, 8085) are all the realm uses. No UPnP, no port forwarding, and no inbound rules beyond the app's own executable. The realm list shows the host's LAN address; support bundles record the current LAN mode and address so a stuck session reports its own topology.

### Friend accounts from the app (friend access)

With "Allow friend access while hosting" enabled, one extra listener comes up with the realm: a provisioning port (TCP 3725) bound to the host's LAN address only — never 0.0.0.0, never a tunnel address, and only while a hosted session with friend access is up. A friend running the Pocket Realm app submits an invite id, an invite code, and a chosen name and password from the same LAN. The password never crosses the network: the app derives an SRP verifier and sends only that, so the host can approve a name without ever holding the secret.

Invite codes are shown once and stored hashed. Expired, used, unknown, and rate-locked requests all receive the same refusal, and submissions are rate-limited per address. Approval is the host's explicit per-request action, and invites, approvals, bans, and privilege changes are written to a local audit trail that carries no secrets.

This lane is LAN-only by design: the listener does not bind a tunnel address, so a friend joining through your tunnel cannot submit a request. For a tunnel friend, create the account on the Guests card instead.

### Hosting over the internet (tunnel)

Pocket Realm has no Internet server mode that opens your router. Hosting for friends outside your LAN works through a personal tunnel you install and control (Tailscale, ZeroTier, or a similar tool): the app advertises the tunnel address you configure, and the tunnel carries the traffic. Anyone inside your tunnel network can reach the realm; anyone outside it cannot.

Two honesty notes this lane owes you:

- Game payloads are not encrypted. Logins use SRP verification — the password itself never crosses the wire — but world chat and gameplay traffic are plaintext. The tunnel is the only confidentiality layer, so anyone in your tunnel network can read world chat.
- Tailscale's free tier allows 3 users / 100 devices per tailnet; a full realm of friends can exceed it. Check your plan (or use ZeroTier) before inviting.

## Failure boundaries

Major runtime parts live in separate Android processes and are claimed by one supervisor session. Owner tokens stop a new session from treating an unrelated or stale component as its own. Binder death tracking tells a child service when its owner has disappeared.

These measures reduce accidental cross-session control. They do not turn an actively modified or compromised Android device into a trusted platform.

## Practical player advice

- Use a normal Player account for everyday play.
- Do not reuse an important Internet password for a local realm account.
- Keep LAN hosting off unless another device needs it.
- Keep friend access off unless you plan to use it.
- Install custom add-ons only from repositories you trust.
- Review a support bundle before sharing it.
- Use Save & Exit so the full local stack closes cleanly.

## Related pages

- [Local server, world, and bots](Local-Server-World-and-Bots.md)
- [Runtime supervision and recovery](Runtime-Supervision-and-Recovery.md)
- [Data, storage, and privacy](Data-Storage-and-Privacy.md)
- [Add-ons](Add-ons.md)
