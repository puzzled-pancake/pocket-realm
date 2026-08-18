# The Home Screen

Home is the everyday launcher. It answers three questions: Is the realm running, what setup will be used, and is a local game account ready?

## Realm card

The large card at the top shows the current state.

| State | What it means |
| --- | --- |
| Ready to start | The local realm is stopped and its saved data is available. |
| Starting | Pocket Realm is preparing the database, world, and other services. |
| Realm online | The local world is ready. The account and game can be used. |
| Saving | The app is waiting for important writes to finish. |
| Stopping | The game and local services are closing in order. |
| Recovering | The app found an interrupted earlier session and is checking it. |
| Needs attention | Something failed and the message explains the next useful action. |

The card also shows the selected bot profile and its starting and target population.

## Main buttons

**Start realm** starts the private world without opening the game. This is useful for first-time account creation and maintenance.

**Realm + game** starts the world and prepares the game client in one flow. It is the normal shortcut after setup is complete.

When the realm is already running, the available actions change to match the current state. The app does not offer actions that would conflict with starting, saving, or stopping work.

## Join LAN

The LAN host box accepts a private or link-local IPv4 address. **Join LAN** opens a client-only session that connects to a Pocket Realm host on the same private network.

LAN hosting is experimental and off by default. It does not search the network automatically, open router ports, or publish the realm on the Internet.

## Active setup

The Active setup card gives a plain summary of the choices that will affect the next session. It can include:

- Bot population and behaviour.
- How many bots are kept near the player.
- How quickly the population grows.
- Resolution and frame-rate limit.
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
