# Game Files and Import

Pocket Realm needs a supported game client, but it does not ship one. Import turns the player-selected client folder into a private, verified working copy for the app.

## Why a private copy is used

The selected source folder stays read-only. Pocket Realm works from private app storage so that it can:

- Verify that files were copied correctly.
- Keep one known client generation active at a time.
- Apply local configuration without editing the player's source folder.
- Project selected add-ons into the managed client.
- Resume interrupted work using a journal.
- Replace an incomplete generation without mixing old and new files.

## Opening the import screen

Go to **Settings**, find **Setup**, and choose **Game files and import**. On a fresh install, the first-run tutorial opens this path automatically and its final button opens the folder picker for you; replay it anytime with **Show first-run setup guide** in the same card.

Choose **Choose client folder** and select the folder that contains the supported client. Android controls which folders are visible and which one the app can read.

a plain, already-extracted (uncompressed) genuine client folder for your flavor — Vanilla 1.12.1 build 5875, TBC 2.4.3 build 8606, or WotLK 3.3.5a build 12340 (the same flow serves every qualified lane) — or a single `.zip`, `.7z` or `.rar` archive (RAR4/RAR5) holding exactly one client (optionally inside one wrapper folder) also works: Pocket Realm stages it, detects the client inside, extracts and verifies it, then deletes the staged copy — plan for roughly twice the archive size in free space during the install. The original installer archive (setup.exe plus its setup-*.bin files, zipped or re-packed) is supported too: Pocket Realm unpacks it on device through the same verified pipeline — plan for roughly three times the archive size in free space for that lane. Never select Windows installers you must run yourself (`VAL-12`), disc images (`VAL-11`), password-protected / multi-volume / solid-RAR4 archives (`VAL-13` — the `tools/install_client_windows.ps1` PC helper extracts those for the folder lane), downloaders/launchers, or a parent folder around the client. One ~5 GB archive copied over USB beats ~20 000 loose files.

## How big each flavor is

The three flavors need very different amounts of space. The numbers below are the measured genuine-client trees and prepared server data the storage planner budgets from (the same figures `schemas/flavor.json` enforces per flavor):

| Flavor | Genuine client tree | Prepared server data | Free space the import reserves |
|---|---|---|---|
| Vanilla 1.12.1 | 5.15 GB (5,526,986,723 bytes, 78 entries) | 2.34 GB (2,336,608,036 bytes, 10,674 files) | 6 GB managed-copy reserve (raised from the pre-measurement 4 GB round number); 20 GB import-source cap |
| TBC 2.4.3 | 8.0 GB (8,579,714,973 bytes, 976 files) | 3.0 GB (3,217,410,914 bytes, 15,199 files) | 12 GB managed-copy reserve; 24 GB import-source cap |
| WotLK 3.3.5a | 16.6 GB (17,856,619,157 bytes, 185 files) | 3.1 GB (3,309,122,582 bytes, 22,697 files) | 24 GB managed-copy reserve; 40 GB import-source cap |

The archive lanes need their staged-copy headroom on top (roughly twice the archive size during the install, three times for an installer archive). WotLK's official preparation story is the desktop lane (`tools/win_prepare_data.py --flavor wotlk` on the PC, then the device consumes the published generation); on-device preparation stays best-effort for that flavor.

If you pick the wrong kind of folder, the import rejects it with a `VAL-01` message (for example a launcher-only selection with no direct `WoW.exe`). Nothing is copied or changed in that case: choose the folder again and point at the extracted client itself.

## Reading the progress display

The main side of the screen explains the current phase and shows file and byte totals. The detail side shows the active preparation stage, resource use, and the age of the latest journal update.

Progress may pause briefly between stages while the app verifies a completed part or starts the next worker process. A changing file name, checkpoint, byte count, or journal time shows that work is continuing.

## Resume after an interruption

Android can stop background work, the device can restart, or the app can be closed. Pocket Realm records checkpoints so the import screen can offer **Resume** when the previous work is recoverable.

If Resume is unavailable, read the current phase and error message. A fresh folder selection may be required if the original permission has been removed or the source folder changed.

## What is prepared

The import flow handles two broad kinds of work:

1. Copying and verifying the game client for the managed private copy.
2. Preparing local server data that matches the supported client data.

The screen reports these as separate stages so a long preparation job does not look frozen.

## Storage advice

- Keep the device on power during the first import.
- Use internal storage for the managed runtime and realm data.
- Leave extra free space for temporary generations, updates, backups, and shader caches.
- Do not remove the source folder permission until the import is complete.
- Do not assume that deleting the source folder will free the app's private copy.

## Privacy and ownership

Pocket Realm does not upload the selected client as part of the normal offline flow. The player remains responsible for obtaining and using the client files lawfully.
