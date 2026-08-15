# Data, Storage, and Privacy

Pocket Realm separates files by purpose. The player normally does not see these folders, but the separation explains why imports, backups, resets, and support bundles behave differently.

## Main storage areas

| Area | What it contains |
| --- | --- |
| Realm area | Runtime generations, supervisor recovery state, and realm metadata. |
| Database area | The live MariaDB data, its local socket, clean-stop records, transactions, and snapshots. |
| Content area | Prepared DBC, maps, VMaps, and MMaps made from the supported client. |
| Runtime area | Wine environments, graphics generations, add-on generations, shader caches, and translation caches. |
| Client area | Verified private generations of the player-supplied game client. |
| Scratch area | Temporary files that can be rebuilt. |
| Export area | Files the player deliberately exports, such as a support bundle. |

The live database and managed runtime stay in app-private internal storage. The external app folder is for exports, not for live character or world data.

## Android backup is disabled

The application disables Android's automatic app backup. A partially restored database, client generation, or runtime environment could look present while its matching seals and files were missing. Pocket Realm therefore uses its own controlled snapshot and restore process for durable realm data.

## Managed client generations

During import, Pocket Realm writes to a staging generation. It hashes files, synchronises them to storage, writes a manifest, and only then changes the active pointer. The previous complete generation can remain available while the new one is being prepared.

The same general pattern is used for prepared server data, add-ons, graphics packages, and other generated runtime material. A generation is a complete set, not a loose collection of files being changed in place.

## Database generations and clean-stop markers

The database area records whether its current generation was created and stopped cleanly. It also records unfinished snapshot or restore transactions.

A normal start checks these markers and the migration ledger. If they do not agree, the app enters recovery or refuses the start. It does not guess that a database is safe because the folder exists.

## Backups

A named backup is a database snapshot created through the database service. It is meant to preserve local accounts, characters, and durable realm state. It is not another copy of the player's original game folder.

Backups are created from a controlled runtime state. The snapshot store records metadata and verifies the snapshot files before presenting them as restorable.

## Restore verification

Restore is transactional. In plain language, Pocket Realm keeps enough information to undo the attempted replacement until the restored candidate proves it can work.

The supervisor starts the restored database, login server, and world server and waits for the world-ready state. It then shuts them down cleanly. Only after that complete test does it commit the restored generation. If the test fails, it rolls back to the prior data.

## Settings

Settings use Android's multi-process DataStore so the launcher and private services see one coordinated set of choices. Settings include display, graphics, bot profile, controls, automatic login preferences, timing, tweaks, sound, and runtime mode.

Settings are kept separate from client, add-on, and database generations. Changing a display choice does not rewrite character data.

## Credentials

Remembered local credentials are stored in the app's no-backup private area with owner-only file permissions. Writes use a temporary file, storage synchronisation, and an atomic rename so an interrupted write is less likely to leave half a record.

Passwords are excluded from supervisor journals, status messages, normal logs, and support bundles. They are read into memory only when needed for account verification or the optional login step.

The current store relies on Android app-private storage and file permissions. This wiki does not describe it as encrypted because the current implementation does not apply a separate encryption layer to that record.

## Support bundles

A support bundle is created only after the player asks for one. It has a fixed size limit and can include build details, device capability information, recent redacted logs, supervisor state, and generation metadata.

It excludes the live database and client files. The redactor removes known secrets and patterns such as credentials, document addresses, email addresses, Windows paths, Android paths, and non-local network addresses. Players should still review an exported bundle before sharing it.

## Network use

Normal local gameplay uses the device's own loopback network connection. Internet permission is also needed for a player-started GitHub add-on download. The custom add-on path accepts approved GitHub hosts and verifies a fixed commit and archive before installation.

Optional LAN mode binds gameplay to one private address. It does not expose MariaDB or an arbitrary administration service.

## Removing the app

Because the managed client, database, accounts, and prepared world data live in app-private storage, uninstalling the app can remove them. Make a named backup and export anything the current build allows before uninstalling or clearing app storage.

## Related pages

- [Game files and import](Game-Files-and-Import.md)
- [Backups, diagnostics, and support](Backups-and-Diagnostics.md)
- [Accounts, security, and network boundaries](Accounts-Security-and-Networking.md)
- [Add-ons](Add-ons.md)
