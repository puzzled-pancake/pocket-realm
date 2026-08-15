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

The game uses the classic realm login port 3724 and world port 8085. These are fixed gameplay ports. MariaDB uses a private file socket and does not listen on a public TCP port.

## LAN host and join

LAN hosting is off by default. When enabled, Pocket Realm accepts one exact numeric private or link-local IPv4 address from the active network interface. It rejects public addresses and hostnames.

A LAN joining device runs its client stack and points it at that private host. There is no automatic public discovery, router port forwarding, or Internet server mode.

If the host's active interface or address changes, the session is no longer considered the same safe binding. The player must restart it on the intended network.

## Failure boundaries

Major runtime parts live in separate Android processes and are claimed by one supervisor session. Owner tokens stop a new session from treating an unrelated or stale component as its own. Binder death tracking tells a child service when its owner has disappeared.

These measures reduce accidental cross-session control. They do not turn an actively modified or compromised Android device into a trusted platform.

## Practical player advice

- Use a normal Player account for everyday play.
- Do not reuse an important Internet password for a local realm account.
- Keep LAN hosting off unless another device needs it.
- Install custom add-ons only from repositories you trust.
- Review a support bundle before sharing it.
- Use Save & Exit so the full local stack closes cleanly.

## Related pages

- [Local server, world, and bots](Local-Server-World-and-Bots.md)
- [Runtime supervision and recovery](Runtime-Supervision-and-Recovery.md)
- [Data, storage, and privacy](Data-Storage-and-Privacy.md)
- [Add-ons](Add-ons.md)
