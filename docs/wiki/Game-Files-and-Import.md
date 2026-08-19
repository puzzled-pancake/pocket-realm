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

The selection must be a plain, already-extracted (uncompressed) WoW 1.12.1 build 5875 client folder — the folder that directly contains `WoW.exe` and a `Data` folder with the base `.MPQ` files. An installer or setup program, a downloader/launcher folder, or a `.zip`/`.7z`/`.rar` archive is not a valid selection: Pocket Realm cannot run installers or open archives. Extract a compressed copy first (on a PC or with a file manager app), then select the extracted folder itself — not the archive, and not a parent folder.

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
