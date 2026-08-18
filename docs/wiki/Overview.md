# What Pocket Realm Is

Pocket Realm is a self-contained Android home for a classic local game world. Its main goal is to make a desktop-era game practical on a modern handheld without expecting the player to run server programs, edit configuration files, or understand networking.

## What the player gets

Pocket Realm brings these parts together:

- A simple Android launcher with Home, Add-ons, Controls, and Settings areas.
- A managed copy of a user-supplied World of Warcraft 1.12.1 build 5875 client.
- A private local realm that normally runs only on the handheld.
- Local player account creation and optional automatic login.
- Computer-controlled residents, usually called bots, with several population presets.
- Physical controller, keyboard, mouse, and editable touch controls.
- Optional add-on installation and rollback.
- Backup, restore, logs, and a redacted support bundle.
- Optional experimental play over a private LAN.

## What makes it different

The handheld is doing two jobs at once. It runs the game that the player sees, and it also runs the private world that the game connects to. There is no separate desktop server to manage during normal offline play.

The launcher is also the safety layer. It remembers the selected setup, starts each service in the right order, keeps the game pointed at the correct local realm, and saves and closes the system in the right order.

## Who it is for

The current design is centred on the Retroid Pocket 6, especially landscape play with its built-in controls. The app can also describe Xbox-style, PlayStation-style, generic Android, keyboard and mouse, and touch-only input.

The interface assumes that the player may be completely new to local game servers. Important choices use ordinary language, and advanced controls are kept in Settings.

## What the player must provide

Pocket Realm does not include proprietary World of Warcraft client files or game data. The player must select a supported client folder that they are entitled to use. Pocket Realm reads that folder and creates its own verified private copy.

## Current state

The project is an active work in progress. The launcher, local runtime, client import, account flow, controls, add-on manager, settings, diagnostics, and live handheld integration are present. Some areas are still being tuned and physically qualified.

For a full explanation of the combined software, see [Projects and technologies used](Projects-and-Technologies.md). For the complete runtime flow, see [How the pieces work together](How-It-Works.md).

## What is not claimed

- It is not an official Blizzard product.
- It does not include the original game client.
- It is not a public Internet realm service.
- It does not promise modern World of Warcraft features inside a 1.12.1 client.
- The experimental LAN option is not the same as the normal offline mode.
