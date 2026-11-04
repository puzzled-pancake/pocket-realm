# Settings

Settings holds choices that do not belong on the everyday Home screen. Many of them apply only when the realm or game starts again.

Settings opens from the top-bar gear or from the navigation menu — the side rail on wide screens, the bottom bar on narrow ones (such as split-screen).

The Settings screen is a short list of categories; each opens its own page, and the back arrow returns to the list:

| Category | What it holds |
| --- | --- |
| **Graphics & display** | ARM client runtime (Vulkan driver and DXVK version), display profile, frame-rate limit, interface scale. |
| **Game client** | In-game settings, sound, auto-login, client tweaks, nearby use/open, input safe mode, advanced timing. |
| **World & bots** | The shortcut to the Bots editor and the world timeframe. |
| **Storage & realm data** | Client storage, realm backup and import. |
| **App & support** | App updates, setup shortcuts, world debug logs. |

Rarely used options sit behind a switch on their card (for example the **Vulkan driver & DXVK options** switch on the ARM client runtime card); nothing was removed, only folded away.

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

### Interface scale

The interface scale grows or shrinks the whole game UI beyond what the game's own slider allows. The stock Advanced Options slider only goes down from 1.0; the app setting writes the UI scale directly, so values above 1.0 make buttons, text, and frames larger — useful when the screen makes the default UI feel too small.

The upper limit depends on the display profile, because the stock interface was designed around frames roughly 512 units tall: 1.40 at 1280 x 720, 1.85 at 1280 x 960 (Classic 4:3), and 2.0 at 1920 x 1080. A value saved on a larger profile is clamped to the current profile's limit and goes back into effect when the larger profile returns.

While a scale is set, the app re-applies it on every launch and it overrides the in-game Advanced Options slider. Choosing **Default (1.0)** hands the setting back to the game; if the scale was changed in game during the last managed session, that in-game choice is kept.

Notes for large scales: stock windows (character, talents) can extend past the screen edge near the limit, the all-in-one bag window can grow taller than the screen at 720p, and custom Move-UI layouts saved at a smaller scale can end up off-screen — recover with `/ap resetui` or the Move-UI resize controls.

## Multiplayer

LAN and internet play live in the **LAN** destination on the navigation menu, not in Settings. Its tabs are **Host**, **Join**, **Browse realms**, and **Transfer**.

**Allow LAN players** (Host tab) is off by default. When enabled, the realm can listen on the active private network address so another local device can join. Hosting over a VPN overlay (Tailscale or ZeroTier) sits behind its own switch on the same tab.

**Browse realms** lists public realms. A realm that publishes an internet join address shows a **Join** button: the app connects to it through the realm's relay and launches the game client against it — no realm of your own runs while you play there. Listings never carry an IP address. Browsing asks public Nostr relays for listings, so those relays see your device's internet address; a join is dialed through the realm's relay, but the transport may move onto a direct path, which shows your address to the host. Log in with an account the host created for you: automatic login stays off, and your saved account is never sent to another realm.

**Transfer** moves a single character to another realm (export a character file, import it on the other realm) and moves the whole realm to another device (backup and import).

LAN mode is experimental. Database and administrative control channels remain private or disabled. The app does not automatically change router settings.

## Bots

Bot population, presets, behaviour, scheduling, and custom realms are configured in the dedicated **Bots** destination on the navigation menu, not in Settings. Settings links to it with a **Configure in Bots →** shortcut.

Population presets control how many computer-controlled residents can enter the world and how quickly they appear. The featured presets are:

| Preset | Plain meaning |
| --- | --- |
| Low Power, 80 bots | Light AI load for passive cooling and weaker devices. |
| Lively, 160 bots | Fast, very active bots in a smaller realm. |
| Busy World, 240 bots | Responsive bots with strong quest and group behaviour. |
| Alive Realm, 320 bots | The recommended default: a populated realm with fast AI around players and groups. |
| Crowded Realm, 400 bots | A high-population world. |
| Full Realm, 500 bots | A fuller world for stronger devices. |
| Massive Realm, 600 bots | The highest built-in population. |
| Legacy Realm, 700 bots | A retained legacy option that remains launchable but is no longer featured. |

The app starts with a smaller group and moves towards the selected target in stages. This avoids making the handheld process every resident at once.

The editor's tabs are Basics, Population, and Behaviour. Deeper tuning (startup pacing, teleport limits, the account pool, adaptive load-shedding floors, login lifecycle, rerandomize cadence) sits behind each tab's **Advanced tuning** switch — every knob stays reachable, none is removed. **Bots speak on their own** (Behaviour tab) lets bots post their stock chat lines without being asked. Safety floors and load shedding stay active.

## Input safe mode

Input safe mode disables project add-ons and the Pocket Realm touch overlay on the next launch. It does not change characters or realm data.

Use it when the game starts but custom controls or an add-on appear to be causing a problem.

## Auto-login

Automatic login uses only the local account that the player chose to remember on this device. If no account is stored, the original game login screen remains visible.

The stored account can be cleared from Settings or Home without deleting realm characters.

## Advanced timing

Timing controls cover low-level delays used by the game bridge, generated keyboard input, pointer actions, startup, and shutdown. They exist for device testing and difficult compatibility problems.

Most players should keep the defaults. Changing several timing values at once can make a problem harder to identify.

## Client tweaks

Optional quality-of-life patches are applied on the next client launch. **Vanilla, all off** disables them. **Common tweaks** selects the normal project preset. The tweak set is validated against the vanilla 1.12.1 client only: on TBC and WotLK the card says the tweaks are not available for that game version and the toggles stay off.

If the exact client file layout does not support a patch, Pocket Realm leaves that launch on the original Vanilla behaviour instead of forcing an unsafe change.

## Sound

Game audio is supported through the Android audio bridge on the ARM64 route. The choice applies on the next client launch.

## Setup shortcuts

The final Settings card keeps the maintenance screens together.

- **Game files and import** manages the private client copy and server-data preparation.
- **Show first-run setup guide** replays the tutorial that opens on a fresh install, including the rules for selecting an already-extracted client folder for the selected game version (any of the three supported builds).
- **Device capability report** shows hardware and process details used during device testing.
- **Diagnostics and logs** provides backups, restore, storage checks, support bundles, and recent logs.
