# Settings

Settings holds choices that do not belong on the everyday Home screen. Many of them apply only when the realm or game starts again.

Settings opens from the top-bar gear or from the navigation menu — the side rail on wide screens, the bottom bar on narrow ones (such as split-screen).

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

## LAN play

**Allow LAN players** is off by default. When enabled, the realm can listen on the active private network address so another local device can join.

LAN mode is experimental. Database and administrative control channels remain private or disabled. The app does not use public discovery or automatically change router settings.

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

Advanced bot tuning can change the population target, nearby density and radius, login and maintenance batches, background update timing, nearby movement cadence, activity behaviour, and automatic load reduction. Safety floors and load shedding stay active.

## AI bot LLM

The **AI bot LLM** card configures the optional speech engine for computer-controlled residents. It is off by default; with it off, bots keep their regular scripted behaviour. The card links to a dedicated **AI bot LLM** destination on the navigation menu, which holds the Runtime, Accelerator, Model, and Connection cards described below.

When enabled, bots speak through a language model. The **Source** choice picks where that model lives:

- **On-device** — an embedded language model runs in its own isolated process on the handset. The screen offers:
    - **Compute mode** — Auto uses the device neural processor (Hexagon NPU) when it is detected and healthy, and falls back to the CPU otherwise. CPU only and NPU are also available as explicit choices.
    - **Decode cores and threads** — which processor cores carry the bot speech while the game is running. The listed profiles are measured presets; mids are the everyday choice, the low-draw and min-power options trade speed for battery.
    - **NPU offload layers** — how much of the model the neural processor holds. Larger models need partial offload; the offload choices note the 2.9 GB ceiling, and the Model card shows the staged file's size.
    - **Model staging** — the screen shows whether the model file is present and can download it (resumable) when it is not. The **Bot brain model** picker offers a small 0.8B model to try first, an untuned 2B fallback, and a tuned 2B model as the default full experience.
- **External server** — the realm talks to any OpenAI-compatible chat endpoint instead of running a model on the device. Enter the endpoint URL (a bare origin gets the standard `/v1/chat/completions` path appended), the model name, and optionally an API key (sent as a Bearer header and kept on this device). This works with hosted APIs, a home server running llama.cpp or LM Studio, or ollama. Nothing is downloaded and the on-device runtime stays off; an unusable endpoint means no speech conf is written at all.

With on-device mode, the runtime starts automatically just before the realm starts, so its memory is reserved before the game claims its own. If the neural processor fails to load the model twice in a row, the runtime keeps working on the CPU and the screen explains how to reset the block.

**Authored banter** is on by default and costs nothing: bots occasionally contribute free flavor lines — a rare quip after the party defeats a monster, a greeting that warms with the relationship when a friend says hi after a long absence, an idle remark on long trips — minutes apart, never in combat, and never more than one every quarter hour per bot. Turn it off to keep bots strictly reply-only.

**World chatter (beta)** is off by default and lets bots talk among themselves: party companions banter while you quest, townsfolk murmur nearby, and rare news reaches General chat. Every line is grounded in something that really happened, stories retire after being told a few times, and on-device lines pause when battery or thermals run low. The switch takes effect within about a minute, in both directions.

Most players can leave this feature off. On-device mode costs storage, memory, and battery while it runs; external mode needs a reachable endpoint whenever the realm is up.

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
- **Show first-run setup guide** replays the tutorial that opens on a fresh install, including the rules for selecting an already-extracted WoW 1.12.1 client folder.
- **Device capability report** shows hardware and process details used during device testing.
- **Diagnostics and logs** provides backups, restore, storage checks, support bundles, and recent logs.
