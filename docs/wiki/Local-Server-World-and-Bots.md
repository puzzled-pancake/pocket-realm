# Local Server, World, and Bots

Pocket Realm runs a private game server on the Android device. This is what makes characters, creatures, quests, loot, accounts, and a continuing world possible without a separate computer.

## The three server-side parts

### Login server

The CMaNGOS login server checks the local account and gives the game its realm list. In local mode it listens on the device's loopback address, which means the connection stays inside the handheld.

### World server

The CMaNGOS world server runs gameplay. It handles characters, maps, creatures, quests, combat, chat, saving, account administration, and the connection from the game client.

### Database

MariaDB stores the parts that must survive a restart. These include accounts, characters, realm records, logs, and world state used by the server.

The database listens through a private local socket instead of an ordinary network port. Only the database service owns the live data folder. The app's private control interface offers fixed maintenance operations and does not let another screen send arbitrary database instructions.

## How the server is built

Pocket Realm compiles the CMaNGOS login and world servers as native Android libraries. Small Pocket Realm wrappers give them fixed start, status, save, account, bot, and stop controls. The MariaDB Connector/C library is linked into the server so it can use the private database.

The Android login and world services live in separate processes. A failure in one therefore has a clearer boundary than it would if the complete server lived inside the launcher screen.

## World data

Classic-DB supplies the starting classic world database. CMaNGOS database updates and Playerbots database updates are applied in a fixed, recorded order.

Pocket Realm keeps a migration ledger. A migration is a known database change with a recorded identity and hash. The database checks that the expected set was applied. It refuses a normal start if the database generation is incomplete, unexpectedly changed, or waiting on unfinished maintenance.

The client import also prepares DBC, map, VMap, and MMap files. These give the world server the game records, terrain, geometry, and pathfinding data that match the supported client.

## Local accounts

Account creation goes through one fixed world-server action. Usernames and passwords are limited to 1 to 16 ASCII letters or numbers. Player is the normal account level. Higher local administrator levels are available for maintenance but are not the recommended default.

The account is a local realm account only. It is not sent to Blizzard and it is not a Battle.net account.

## Playerbots

CMaNGOS Playerbots supplies computer-controlled characters. Pocket Realm does not simply turn on an unlimited upstream configuration. It creates named profiles with bounded population, login batches, background update timing, activity rules, and nearby behaviour.

Current everyday choices include Quiet 25, Typical 50, Balanced 100, Populated 250, Crowded 400, and Busy 600. Launch day 700 is clearly marked experimental. The app also has internal qualification profiles that are not shown as ordinary player choices.

## Gradual population startup

A selected target is not admitted all at once. The world starts with a smaller group and moves toward the target in stages. Login and maintenance work is also split into batches.

This reduces sudden processor, memory, database, and storage pressure during startup.

## Automatic load protection

While bots are active, Pocket Realm samples:

- The world server's normal and worst tick times.
- Newly observed hard stalls.
- Available device memory.
- Available storage.
- The world process memory use.
- Android's thermal status.

If safety limits are crossed, the admission controller pauses growth or lowers the effective bot target. Severe heat, low memory, low storage, repeated stalls, or slow world ticks take priority over reaching the selected number.

When the device becomes healthy again, the target can recover gradually. A cooldown stops the population from jumping up and down after every single sample.

## Bot behaviour boundaries

The current mobile configuration disables features that would create outside network or language-model traffic. It also keeps automated guild growth, arena teams, battleground joining, auction-house automation, and unrestricted background chat out of the normal profile.

Advanced settings can change allowed behaviour such as questing, nearby grouping, invitations, local chatter, wandering, and off-spec strategies. Values remain bounded and are converted into a reviewed server configuration. The app never accepts arbitrary Playerbots configuration text from the Settings screen.

## Saving the world

When the player chooses Save & Exit, the supervisor first closes the game client, then asks the world server to save. Only after the save completes does it stop the world, login server, and database.

If a normal save fails, the session is not falsely recorded as clean. Diagnostics and recovery retain the fact that attention is needed.

## LAN play

LAN host mode lets the login and world services bind to one exact private IPv4 address. The database still remains private. A LAN-join device starts only its client stack and connects to the host.

LAN mode does not add public discovery, router configuration, or Internet hosting.

## Related pages

- [Runtime supervision and recovery](Runtime-Supervision-and-Recovery.md)
- [Accounts, security, and network boundaries](Accounts-Security-and-Networking.md)
- [Data, storage, and privacy](Data-Storage-and-Privacy.md)
- [Settings](Settings.md)
