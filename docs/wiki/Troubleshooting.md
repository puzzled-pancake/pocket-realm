# Troubleshooting

Start with the visible status message. Pocket Realm tries to say which part needs attention instead of showing one general failure.

## The Realm + game button is unavailable

Common reasons are:

- No verified managed client is ready.
- A first local account still needs to be created.
- Import or server-data preparation is incomplete.
- The realm is already starting, saving, or stopping.
- A previous client launch failed and needs a settings correction.

Open **Game files and import** and check its current phase. If the client is ready, start the realm by itself and check the Local account card.

## The import was rejected as unsupported

The selection is checked before anything is copied. A `VAL-01` rejection means the picked folder is not the game client itself — typical causes:

- An installer or setup program was selected instead of the installed client.
- A downloader/launcher folder was selected (the message says `launcher-only` because a direct `WoW.exe` is absent).
- A `.zip`/`.7z`/`.rar` archive or a shortcut was selected instead of a folder.

Pocket Realm cannot run installers and cannot open archives. Extract the client first (on a PC or with a file manager app), then choose the folder again and select the extracted client itself — the folder that directly contains `WoW.exe` and a `Data` folder. A `VAL-03` rejection instead means the client is the wrong version: exactly WoW 1.12.1 build 5875 is required. Replay the guide with **Settings → Setup → Show first-run setup guide** if you want the full requirements again.

## Import stopped or looks frozen

Look for a changing file, byte count, checkpoint, worker state, or journal age. Some verification steps do not change the main progress bar every second.

If no activity changes:

1. Keep the source folder available.
2. Return to the import screen.
3. Use **Resume** if offered.
4. Check free internal storage.
5. Read the exact error before choosing the folder again.

## The realm is online but the game did not open

Do not restart the realm immediately. The server side may still be healthy.

Read the Home message, then check:

- The selected Vulkan driver and DXVK combination.
- Whether the display choice is supported.
- Whether the managed client is still verified.
- Whether Input safe mode lets the client open without project add-ons and the touch overlay.
- Diagnostics for a recent client preparation error.

## The controller does nothing

1. Open **Controls**.
2. Check the Input device choice.
3. Use Automatic or the exact controller family.
4. Try Built-in leveling controls to separate an add-on problem from an Android input problem.
5. Restore recommended defaults if the layout is Custom and unclear.

Home, Back, app switching, volume, and power are protected Android actions and cannot become gameplay buttons.

## The character or camera drifts

Increase the dead zone for the affected stick in Controls. Change one value at a time.

For an analogue trigger that flickers, raise its press point or lower its release point so there is a clearer gap between the two.

## R2 does not talk to or loot the target

R2 right-clicks at the current pointer. It does not automatically interact with the selected target.

Place the pointer over the container, NPC, object, or loot first, then press R2 (**Use / Open**). Use touch for precise placement.

## The add-on interface is broken or too much is on screen

Turn on **Input safe mode** in Settings and restart the game. This disables project add-ons and the touch overlay without changing realm or character data.

If the client works in safe mode, return to Add-ons and Controls. Check that the installed add-on and selected layout match.

## Automatic login stopped working

Check that Home or Settings still shows a remembered local account. Automatic login uses only that device-local saved account.

If the account cannot be verified:

1. Start the realm by itself.
2. Clear the remembered account.
3. Enter the correct local account name and password.
4. Create or verify it again.
5. Re-enable automatic login if needed.

Clearing remembered login details does not erase characters.

## LAN join fails

Check that:

- The host enabled **Allow LAN players** before starting the realm.
- Both devices are on the same private network.
- The entered value is the host's exact private IPv4 address.
- The address is not a hostname or public Internet address.
- The host did not change Wi-Fi networks after the realm started.

LAN play does not use automatic discovery.

## Audio is missing

Check **Sound** in Settings and restart the game. The setting applies on the next client launch.

If the sound control says the current runtime does not support audio, changing the switch will not add audio to that validation route.

## A session was interrupted

Open Pocket Realm and let the Home screen report the recovered state. Do not repeatedly force-stop it while it says Recovering, Saving, or Stopping.

If recovery ends at Needs attention, record the visible message and create a support bundle from Diagnostics.

## When asking for help

Provide the smallest clear report:

- Device model and Android version.
- The screen and button used.
- Expected result and actual result.
- Exact status or error text.
- A screenshot with no password visible.
- Whether Input safe mode changed the result.
- A redacted support bundle if a maintainer requests one.
