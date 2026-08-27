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

a plain, already-extracted (uncompressed) WoW 1.12.1 build 5875 client folder — or a single `.zip`, `.7z` or `.rar` archive (RAR4/RAR5) holding exactly one client (optionally inside one wrapper folder) also works: Pocket Realm stages it, detects the client inside, extracts and verifies it, then deletes the staged copy — plan for roughly twice the archive size in free space during the install. The original installer archive (setup.exe plus its setup-*.bin files, zipped or re-packed) is supported too: Pocket Realm unpacks it on device through the same verified pipeline — plan for roughly three times the archive size in free space for that lane. Never select Windows installers you must run yourself (`VAL-12`), disc images (`VAL-11`), password-protected / multi-volume / solid-RAR4 archives (`VAL-13` — the `tools/install_client_windows.ps1` PC helper extracts those for the folder lane), downloaders/launchers, or a parent folder around the client. One ~5 GB archive copied over USB beats ~20 000 loose files.

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
