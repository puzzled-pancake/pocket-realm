# Settings

Settings holds choices that do not belong on the everyday Home screen. Many of them apply only when the realm or game starts again.

## ARM client runtime

On an ARM device such as the Retroid Pocket 6, Pocket Realm runs the Windows game client through Box64 and Wine. DXVK translates the game's Direct3D graphics into Vulkan for Android.

The screen keeps the two graphics choices separate:

- **Vulkan driver** decides how the app reaches the device GPU.
- **DXVK version** decides which Direct3D-to-Vulkan package is used.

Unavailable combinations stay visible with an explanation. The app does not silently replace the player's selection during launch.

Most players should leave these values at a known working choice. Change them when testing a device problem or following a specific troubleshooting step.

## Display

The current app offers two clear display goals:

- **1280 x 720, Performance** reduces the 3D workload and scales the final image to the handheld screen.
- **1920 x 1080, Sharp** uses the native landscape size for a clearer image with more work for the device.

The frame-rate limit sets a maximum for both the game and the renderer. It does not guarantee that the device will reach that rate in every area.

## LAN play

**Allow LAN players** is off by default. When enabled, the realm can listen on the active private network address so another local device can join.

LAN mode is experimental. Database and administrative control channels remain private or disabled. The app does not use public discovery or automatically change router settings.

## World population

Population presets control how many computer-controlled residents can enter the world and how quickly they appear.

| Preset | Plain meaning |
| --- | --- |
| Quiet, 25 bots | The lightest everyday world for battery life and solo play. |
| Typical, 50 bots | A quiet world that fills gradually around the player. |
| Balanced, 100 bots | A busier everyday world with conservative background work. |
| Populated, 250 bots | A fuller leveling world that still grows in measured steps. |
| Crowded, 400 bots | A high-population world. Runs well on the development handheld. |
| Busy, 600 bots | A very busy world with conservative startup and local activity limits. |
| Launch day, 700 bots | An experimental high-density option with automatic load reduction. |

The app starts with a smaller group and moves towards the selected target in stages. This avoids making the handheld process every resident at once.

Advanced bot tuning can change the population target, nearby density and radius, login and maintenance batches, background update timing, nearby movement cadence, activity behaviour, and automatic load reduction. Safety floors and load shedding stay active.

## Input safe mode

Input safe mode disables project add-ons and the Pocket Realm touch overlay on the next launch. It does not change characters or realm data.

Use it when the game starts but custom controls or an add-on appear to be causing a problem.

## Auto-login

Automatic login uses only the local account that the player chose to remember on this device. If no account is stored, the original game login screen remains visible.

The stored account can be cleared from Settings or Home without deleting realm characters.

## Advanced timing

Timing controls cover low-level delays used by the game bridge, generated keyboard input, pointer actions, startup, and shutdown. They exist for device qualification and difficult compatibility problems.

Most players should keep the defaults. Changing several timing values at once can make a problem harder to identify.

## Client tweaks

Optional quality-of-life patches are applied on the next client launch. **Vanilla, all off** disables them. **Common tweaks** selects the normal project preset.

If the exact client file layout is not qualified for a patch, Pocket Realm leaves that launch on the pristine Vanilla behaviour instead of forcing an unsafe change.

## Sound

Game audio is supported through the Android audio bridge on the ARM64 route. The choice applies on the next client launch.

## Setup shortcuts

The final Settings card keeps the maintenance screens together.

- **Game files and import** manages the private client copy and server-data preparation.
- **Device capability report** shows hardware and process details used during device testing.
- **Diagnostics and logs** provides backups, restore, storage checks, support bundles, and recent logs.
