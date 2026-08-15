# Agent Implementation Brief — Playerbots, LAN Navigation, Presets, Advanced Control & Landscape UI

## Objective

Refactor the Android WoW 1.12.1 client/server UI and Playerbots configuration around these principles:

1. **Default to a genuinely populated, alive realm.**
2. Make **320 bots the recommended default**.
3. Keep built-in presets focused on sensible combinations up to **600 bots**.
4. **Do not make 600 the custom limit.**
5. Custom users must be able to specify populations beyond the built-in presets up to the **actual limits supported by the pinned CMaNGOS/Playerbots implementation and valid server/account/database constraints**.
6. Allow detailed control over bot activity, responsiveness, behaviour, population, scheduling, grouping and related Playerbots settings.
7. Allow the user to create and save **as many custom bot presets as they want**.
8. Move bot configuration completely out of general **Settings** into a dedicated **Bots** side-navigation destination.
9. Move LAN hosting/joining completely out of Home into a dedicated **LAN** side-navigation destination.
10. Design primarily for **landscape**.
11. Make vertical space considerably more efficient, especially on Home.
12. Prioritise **fast AI around humans and groups** before reducing total population when the device experiences load.

The project already runs CMaNGOS Playerbots on-device and has a Kotlin admission controller layered over it.

---

# 1. Navigation restructure

Change the permanent landscape side navigation to:

```text
Home
Bots
LAN
Add-ons
Controls
Settings
```

Recommended order:

```text
🏠 Home
🤖 Bots
🌐 LAN
🧩 Add-ons
🎚 Controls
⚙ Settings
```

## Bots

All Playerbots configuration belongs here.

Remove bot population/preset/advanced configuration from general Settings.

## LAN

All LAN hosting, joining and future multiplayer-management features belong here.

Remove:

```text
LAN host IPv4
Join LAN
```

from the Home realm card.

The existing Home screen currently mixes realm launching, LAN joining and bot setup information. The current UI also obtains its bot setup through Settings and resolves the selected profile on Home.

This refactor should produce clear ownership:

```text
Home
Current realm + launch + account

Bots
Bot world configuration

LAN
Hosting/joining/multiplayer

Settings
General application/server settings
```

---

# 2. Fix the Home screen landscape layout

Use the attached screenshot as the baseline.

The current screen spends too much vertical height on:

```text
        Home
```

above the actual content.

The side rail already communicates that the user is on Home.

## Replace the existing large header

Either remove the large standalone Home header completely or replace it with a compact landscape top bar.

Preferred:

```text
System status bar
──────────────────────────────────────
Home                         Realm stopped
──────────────────────────────────────
content starts immediately
```

Maximum app header height:

```text
48–56 dp
```

Do not retain the current large blank title area.

---

# 3. Compact Home realm card

Once LAN controls are removed, reduce the realm card substantially.

Current information can become:

```text
Realm
Ready to start

Alive Realm · 320 bots · Classic
Starts at 50 → target 320

[ Start realm ] [ Realm + game ]
```

Optional small status information:

```text
Adaptive AI on
```

Do not dedicate several rows to setup data already available in Bots.

## Target Home card height

Aim for roughly:

```text
120–150 dp
```

rather than allowing it to dominate the upper screen.

---

# 4. Compact Active Setup

The screenshot currently uses a large card containing several vertically stacked lines such as:

```text
Balanced · 100 bots · Natural behavior · 12 nearby
Ramp 25 → 100 · 2 logins per 2.0s
1920x1080 · 30 FPS · Turnip...
Auto-login...
Vanilla client...
```

Replace this with a dense summary.

Example:

```text
Active setup

Alive Realm · 320 bots · Active AI
Classic World · Adaptive AI
1080p · 30 FPS · Turnip · DXVK

[ Bots ] [ Graphics ] [ Details ]
```

Use compact chips or two-column metadata where appropriate.

Do not turn every value into its own vertical line.

---

# 5. Home layout after refactor

Landscape structure:

```text
┌──────────┬────────────────────────────────────────────────────────┐
│          │ Home                              Realm stopped         │
│ Home     ├────────────────────────────────────────────────────────┤
│ Bots     │ Realm                                                  │
│ LAN      │ Ready to start     Alive Realm · 320 bots             │
│ Add-ons  │                    [Start realm] [Realm + game]        │
│ Controls ├─────────────────────────────┬──────────────────────────┤
│ Settings │ Active setup                │ Local account            │
│          │ compact summary             │ account controls         │
│          │                             │                          │
└──────────┴─────────────────────────────┴──────────────────────────┘
```

Keep the screen useful without requiring unnecessary vertical scrolling at normal 16:9 landscape sizes.

---

# 6. New Bots destination

The Bots page becomes a major top-level feature rather than a subsection of Settings.

Use landscape space properly.

Recommended layout:

```text
┌──────────────┬──────────────────────────────┬──────────────────────┐
│ PRESETS      │ WORLD CONFIGURATION          │ CURRENT RESULT       │
│              │                              │                      │
│ Built-in     │ World size                   │ 320 selected         │
│ User presets │ Activity                     │ ~38 active           │
│              │ Playstyle                    │ 1 sec nearby         │
│ + New        │                              │ 20 sec remote        │
│              │ Advanced sections            │                      │
│              │                              │ Save / Apply         │
└──────────────┴──────────────────────────────┴──────────────────────┘
```

For narrower landscape devices, use two panes:

```text
Presets | Configuration
```

and display the summary as a collapsible panel.

Do not design this primarily as a portrait-style single vertical column.

---

# 7. Built-in preset philosophy

Built-in presets should describe **how the realm feels**, not merely expose increasingly large numbers.

The normal built-in range peaks at **600**.

Recommended presets:

| Preset            |    Bots | AI emphasis      | Purpose                          |
| ----------------- | ------: | ---------------- | -------------------------------- |
| **Low Power**     |      80 | Light            | Passive cooling / weaker devices |
| **Lively**        |     160 | Very Active      | Faster bots, smaller realm       |
| **Busy World**    |     240 | Active           | Responsive populated realm       |
| **Alive Realm**   | **320** | **Active**       | **Recommended default**          |
| **Crowded Realm** |     400 | Balanced/Active  | Larger persistent population     |
| **Full Realm**    |     500 | Balanced         | High population                  |
| **Massive Realm** |     600 | Locality-focused | Largest built-in setup           |

The current built-in profiles span 25 through 700, while the current app default is `BALANCED_100`.

Do not simply replace one numerical ladder with another.

Each preset must configure a meaningful combination of:

```text
population
AI responsiveness
active percentage
nearby priority
background cadence
questing
grouping
wandering
social behaviour
level policy
adaptive behaviour
```

---

# 8. Recommended default — Alive Realm

Create a built-in configuration approximately equivalent to:

```text
Name                       Alive Realm
Recommended                Yes

Population                 320
Initial target             50
Target                     320

AI activity                Active
Base AI update             2 sec
Foreground/party target    ~1 sec
Iterations/tick            14–16
Active percentage          ~12%

Expected configured active ~38

Nearby priority            High
Party priority             Maximum

Quests                     On
Autonomous groups          On
Wandering                  On
Off-spec activity          On

Stable identities          On
Chat                       Rare

Accept human invitations   On
Bot → human invitations    Off
Bot → bot invitations      On

Level matching             Off in Classic
Adaptive AI                On
Adaptive population        On
```

Exact values should still be validated against the actual pinned Playerbots behaviour.

The goal is:

> A populated 320-bot Vanilla realm where bots around humans and group content receive substantially more AI attention than distant characters.

---

# 9. Built-in preset examples

## Lively — 160

```text
160 bots
Very Active

AI update             1 sec
Iterations/tick       18–20
Active share          15–20%

Very high foreground priority
Fast group behaviour
Normal background world
```

Use for players who prefer smarter/faster bots over population.

---

## Busy World — 240

```text
240 bots
Active

AI update             1–2 sec
Iterations/tick       ~16
Active share          ~15%

Strong nearby activity
Strong quest/group behaviour
Moderate background simulation
```

---

## Alive Realm — 320

```text
320 bots
Active

High population
Strong nearby AI
Fast human-group AI
Normal background simulation
```

This is the recommended configuration.

---

## Crowded Realm — 400

```text
400 bots
Balanced/Active

Nearby bots           Fast
Same-zone bots        Moderate
Remote bots           Background
```

---

## Full Realm — 500

```text
500 bots
Balanced

Foreground            Fast
Nearby                 Responsive
Background             Reduced frequency
```

---

## Massive Realm — 600

```text
600 bots
Population/locality focused

Human party            Highest priority
Human combat           Highest priority
Nearby                 High priority
Remote world           Low-frequency simulation
```

600 is the **built-in preset ceiling**, not the engine/custom ceiling.

---

# 10. Custom population must NOT be capped at 600

This is important.

Built-in presets stop at:

```text
600
```

Custom configurations do not.

The current Android `BotProfile` validation permits `maximumOnline` values up to 1,500, while the current advanced UI only allows 25–700. Those are application-side design limits, not evidence that either number is necessarily the actual upstream CMaNGOS/Playerbots ceiling.
Before changing the custom maximum:

1. Inspect the pinned `cmangos/playerbots` source.
2. Inspect CMaNGOS random-playerbot count types and bounds.
3. Inspect configuration parsing.
4. Inspect account/character generation limits.
5. Inspect database/index limitations.
6. Inspect all integer types involved.
7. Inspect JNI/Binder/Kotlin conversions.
8. Determine whether upstream has an explicit hard maximum.
9. Determine any practical invariant required for a valid normal Playerbots server.

Then:

```text
Custom max = actual validated engine/server limit
```

Do **not** substitute:

```text
600
700
1000
1500
```

merely because one of those numbers previously existed in the Android UI.

If the pinned upstream implementation has no sensible fixed maximum, implement validation around the real constraints rather than introducing an arbitrary small Android cap.

---

# 11. Custom population editor

Custom mode should support direct numerical entry.

Example:

```text
Population

Target bots
[ 725                         ]

Initial bots
[ 75                          ]

Minimum online
[ 25                          ]

Maximum configured
[ Auto                        ]
```

Provide quick buttons:

```text
80
160
240
320
400
500
600
```

but do not require them.

Users must be able to type a valid custom value.

---

# 12. Automatically size account capacity

The current profile validator has account-pool invariants and currently requires capacity using the existing characters-per-account rule.

Custom mode should support:

```text
Account pool
[ Automatic ] [ Manual ]
```

Automatic:

```text
required accounts =
ceil(targetBots / validatedCharactersPerBotAccount)
+ configurable headroom
```

Derive the actual characters-per-account assumption from the current generator/server implementation rather than duplicating a magic number unnecessarily.

Show:

```text
Target bots            725
Required accounts      81
Allocated accounts     96
Capacity               864 characters
```

where values reflect the real implementation.

---

# 13. Separate world size from bot intelligence

The user should be able to combine any supported population with any supported AI configuration.

Primary controls:

```text
WORLD SIZE
80 / 160 / 240 / 320 / 400 / 500 / 600 / Custom

BOT ACTIVITY
Smart
Active
Balanced
Light
Custom

PLAYSTYLE
Classic World
Solo Friendly
LAN Co-op Behaviour
Independent
Dungeon / Raid
Social
Custom
```

Do not lock:

```text
600 → Light only
```

For example, allow:

```text
600 bots
Smart AI
20% active
```

if the user explicitly chooses it and the values are valid.

Warnings are acceptable.

Artificial blocking is not.

---

# 14. Activity presets

Expose simple activity choices:

| Activity     | Typical behaviour                               |
| ------------ | ----------------------------------------------- |
| **Smart**    | Maximum foreground/nearby AI                    |
| **Active**   | Strong foreground + meaningful world simulation |
| **Balanced** | Balance of population and thinking              |
| **Light**    | Lower AI frequency                              |
| **Custom**   | Fully user-defined                              |

The existing advanced system already controls AI update interval, iterations per tick and active percentage within its current Android ranges.

The generated mobile configuration currently intentionally constrains `IterationsPerTick` to 1–20 rather than the upstream desktop default of 100.

For Expert mode, verify whether these Android ranges should remain policy limits or whether the actual upstream-supported range can safely be made available.

---

# 15. Locality-driven AI

This is central to the 320 default.

The native status layer already tracks locality:

```text
same zone
within 150 yards
within 500 yards
within 1500 yards
```

along with bot count and performance data.

Use those locality bands for AI scheduling.

## Priority 1 — Human group

```text
Human party bot
Human raid bot
Bot actively fighting alongside/against human
```

Highest priority.

Never downgrade these because hundreds of remote bots happen to exist.

---

## Priority 2 — Foreground

```text
0–150 yd
```

Suggested target:

```text
~1 sec
```

---

## Priority 3 — Nearby

```text
150–500 yd
```

Suggested:

```text
1–3 sec
```

depending on selected activity.

---

## Priority 4 — Local world

```text
500–1500 yd / same local area
```

Suggested:

```text
4–12 sec
```

---

## Priority 5 — Background

```text
same zone but distant
```

Suggested:

```text
10–30 sec
```

---

## Priority 6 — Remote world

```text
different distant zone/map
no human relevance
```

Suggested:

```text
20–60+ sec
```

depending on activity/performance mode.

---

# 16. Immediate promotion, delayed demotion

When a human approaches a bot:

```text
REMOTE
↓
LOCAL
↓
NEARBY
↓
FOREGROUND
```

Promote immediately.

When humans move away, delay demotion.

Example:

```text
Leave foreground
wait ~10 sec

Leave nearby
wait ~20 sec

Leave local area
then background
```

Avoid repeated scheduler oscillation.

---

# 17. Human party bots override active percentage

Do not interpret:

```text
12% active
```

as meaning that a grouped player may receive sluggish bots because the global active pool is full.

Human-party membership should force foreground priority.

For example:

```text
320 bots
12% normal active allocation
38 normally active

Human groups with 8 bots:
all 8 group bots receive foreground priority
```

Foreground/group activity is priority-driven rather than blocked by a simple percentage quota.

---

# 18. Runtime adaptation must sacrifice background AI first

The current admission controller primarily adjusts the effective population according to thermal, memory, storage and performance conditions.

Extend it into an **AI + population adaptation controller**.

Preferred degradation order:

```text
1. Slow remote AI
2. Slow distant-zone AI
3. Reduce autonomous background activity
4. Reduce background active percentage
5. Reduce same-zone update frequency
6. Reduce iterations per tick
7. Preserve human-party AI
8. Preserve human-combat AI
9. Preserve immediate nearby responsiveness
10. Reduce effective population only if pressure persists
```

Safety-critical memory, storage or thermal states can still shed bots sooner.

---

# 19. Example 320 adaptation

Normal:

```text
Selected population    320
Effective              320
Online                 316

Active allocation      ~38

Party AI               1 sec
Foreground             1 sec
Nearby                 2 sec
Local                  6 sec
Background             20 sec
Remote                 30 sec
```

Moderate load:

```text
Selected               320
Effective              320

Active                  32

Party                   1 sec
Foreground              1 sec
Nearby                  2 sec
Local                   10 sec
Background              30 sec
Remote                  45 sec
```

Heavier sustained load:

```text
Selected               320
Effective              320

Active                  25

Party                   1 sec
Foreground              1–2 sec
Nearby                  3 sec
Local                   15 sec
Background              45 sec
Remote                  60 sec
```

Only later:

```text
Selected               320
Effective              290

Reason
Sustained world-tick pressure
```

Always preserve:

```text
Selected target = 320
```

so recovery knows where to return.

---

# 20. Full Custom behaviour editor

Custom must not mean merely changing population.

Create a real configuration editor.

Use sections or internal tabs rather than one giant scrolling list.

Recommended sections:

```text
Overview
Population
AI & Scheduling
Nearby Activity
Behaviour
Groups & Raids
Combat
Questing
Movement
World Distribution
Social
Leveling
Teleporting
Generation
Performance
Adaptation
Expert
```

---

# 21. Population controls

Expose:

```text
Selected target
Minimum online
Maximum online
Initial target

Startup increase step
Startup interval
Activation batch

Login batch
Login interval
Maintenance batch

Account pool mode
Account count
Character generation batch
Character generation yield

Alternate-bot limits where supported
```

The current implementation already includes startup pacing, activation batching, account pools, login and maintenance controls, although not all are currently exposed in advanced UI.

---

# 22. AI & scheduling controls

Expose:

```text
AI update interval
Iterations per tick
React delay

Passive delay
RPG delay

Active bot percentage

Foreground interval
Nearby interval
Local interval
Background interval
Remote interval

Promotion radius
Demotion hysteresis
Foreground priority

Human-group priority
Human-combat priority
```

If locality scheduling requires new PocketRealm overlay settings, add them cleanly rather than abusing unrelated upstream values.

---

# 23. Nearby activity controls

Expose:

```text
Nearby bots per human
Global nearby limit

150 yd policy
500 yd policy
1500 yd policy

Same-zone target

Per-player fairness
Per-party fairness

Maximum bots attached to one human
Maximum bots attached to one human group
```

---

# 24. Behaviour controls

Do not reduce behaviour to the existing Efficient/Natural/Social chips.

Keep simple presets, but allow every underlying setting to be edited.

Expose:

```text
Auto quests
Autonomous grouping
Wandering
Off-spec activity
Combat limitations

Stable identities
Rerandomization
World roaming

Human interaction policy
Resource etiquette
Quest-object etiquette
Mob-tag etiquette
```

The existing behaviour presets currently expose only three bundled patterns: Efficient, Natural and Social.

Custom mode should allow the user to mix these independently.

---

# 25. Group & raid behaviour

Expose controls such as:

```text
Accept invitations from humans
Bots may invite humans
Bots may invite bots

Invite cooldown
Invite retry policy

Human leader priority
Bot leader allowed

Fill human party
Maximum party fill
Fill raid
Raid fill target

Tank preference
Healer preference
DPS preference

Role lock
Role reassignment policy

Follow distance
Formation
Assist target
Leader target priority
```

Where the pinned Playerbots version does not currently expose a required behaviour knob, clearly separate:

```text
Existing upstream option
```

from:

```text
PocketRealm extension
```

Do not pretend unsupported settings already exist.

---

# 26. Combat controls

Where supported, expose:

```text
Threat threshold
Tank wait behaviour

Assist delay
Target lock duration

Crowd-control respect
AOE around CC
Interrupt priority
Dispel priority

Emergency healing
Mana conservation
Recovery threshold

Pull policy
Chain-pull policy

Resurrection behaviour
```

Again, determine which are already supported by the pinned Playerbots AI and which require new implementation.

---

# 27. Level behaviour

Replace a single sync toggle with a mode:

```text
Off
Nearest human
Party leader
Party average
Raid average
Fixed level
Fixed level band
Custom
```

Additional controls:

```text
Resync threshold
Assignment lock duration
Post-combat resync delay
Maximum change
Minimum level
Maximum level
```

---

# 28. Social controls

Expose:

```text
Chat
Off / Rare / Normal / Custom

Minimum message delay
Maximum message delay

Whispers
Party chat
Say
Guild-style behaviour where actually supported

Repeated-message suppression
Per-bot cooldown
Global cooldown

Human invitation behaviour
Bot invitation behaviour
```

---

# 29. Movement and teleporting

Expose:

```text
Minimal movement
Wandering

Teleport enabled
Minimum teleport time
Maximum teleport time

Visible teleport policy
Human proximity restriction

Stuck recovery thresholds
Path recalculation
Backtracking
Movement reset
Hidden relocation
```

---

# 30. World distribution

Expose controls for supported concepts such as:

```text
Allowed maps
Allowed level range

Zone distribution
Population weighting

Starter-zone weighting
High-level-zone weighting

Maximum local clustering
Maximum idle bots per location

World activity distribution
```

Do not hard-code map/zone controls beyond what the pinned Vanilla module actually supports unless adding a PocketRealm extension deliberately.

---

# 31. Performance controls

Expose:

```text
World p99 budget
Hard-stall threshold

Memory floor
Storage floor

Thermal policy

AI reduction thresholds
Population reduction thresholds

Reduction cooldown
Recovery cooldown

Healthy recovery duration

Increase step
Reduce step
```

---

# 32. Adaptive modes

Offer:

```text
Off
AI only
Full
Custom
```

## Off

Only unavoidable safety protection.

## AI only

Preserve selected population and alter AI workload.

## Full

Alter AI first, then effective population.

## Custom

Let user configure adaptation stages and thresholds.

---

# 33. Expert mode

Add an Expert section for users who want substantially deeper Playerbots control.

The current generated configuration deliberately emits an allowlisted subset of the many `AiPlayerbot.*` keys consumed by the module.

Agent task:

1. Enumerate every relevant `AiPlayerbot.*` option actually read by the pinned Playerbots build.
2. Classify its type:

   * Boolean
   * Integer
   * Float
   * Duration
   * String
   * Enum
3. Determine upstream default.
4. Determine actual valid range where available.
5. Add searchable metadata.
6. Map common settings to the normal UI.
7. Make remaining meaningful bot settings available in Expert.

Recommended UI:

```text
Expert settings

Search settings...
[ teleport                         ]

AiPlayerbot.Teleport...
AiPlayerbot.RandomBot...
...
```

Each setting should show:

```text
Setting name
Description
Upstream default
Current value
Inherited/overridden state
Reset
```

---

# 34. Raw override support

For maximum tinkering, optionally provide:

```text
Expert → Raw overrides
```

Only accept configuration keys from validated namespaces used by this bot implementation, for example:

```text
AiPlayerbot.*
PocketRealm bot-related keys
```

Do not permit newline/config-section injection through key or value entry.

Resolve raw overrides after normal structured settings.

Display conflicts clearly.

Example:

```text
Structured value:
IterationsPerTick = 16

Raw override:
IterationsPerTick = 20

Resolved:
20
```

---

# 35. Preserve deliberate application security boundaries

This refactor is about Playerbots behaviour, population and simulation.

Do not accidentally activate unrelated network-facing or unsupported subsystems merely because Expert control is expanded.

The existing generated configuration deliberately disables several network/automation systems.

If such systems are ever exposed later, implement them as explicit separately reviewed features.

---

# 36. Unlimited saved user presets

Users must be able to save **any number of custom presets**.

Do not impose:

```text
maximum 5
maximum 10
maximum 20
```

etc.

The practical limit should simply be available app storage.

Built-ins are read-only.

Users can duplicate any built-in.

Example:

```text
Built-in
  Alive Realm
  Crowded Realm
  Massive Realm

My presets
  320 Smart LAN
  420 Classic
  600 Fast Groups
  Dungeon Bots
  Battery Realm
  Testing
  Experimental 725
  ...
```

Use a virtualized list/grid so hundreds of presets do not cause UI problems.

---

# 37. User preset actions

Provide:

```text
New
Save
Save As
Duplicate
Rename
Delete
Reset
Favorite
```

Optional but useful:

```text
Export
Import
```

A modified built-in should show:

```text
Alive Realm
Modified
```

with actions:

```text
Save as new preset
Reset to Alive Realm
```

Do not overwrite built-in definitions.

---

# 38. User preset model

Suggested conceptual model:

```kotlin
data class SavedBotPreset(
    val id: String,
    val schemaVersion: Int,
    val name: String,

    val basePresetId: String?,

    val createdAt: Long,
    val updatedAt: Long,

    val favorite: Boolean,

    val configuration: BotCustomConfiguration
)
```

Built-ins may use the same resolved model but remain immutable.

---

# 39. Dedicated preset repository

The existing bot settings are currently stored as flat DataStore keys, with advanced values encoded into the profile identity.

That architecture becomes awkward for unlimited named custom presets.

Create a dedicated:

```text
BotPresetRepository
```

with versioned persistent storage.

Requirements:

```text
Atomic writes
Schema version
Migration
Validation
No arbitrary preset-count cap
Stable IDs
Crash-safe updates
```

A versioned app-private JSON/Proto/Room implementation is acceptable depending on the existing architecture, but do not store hundreds of presets as a collection of ad-hoc flat preference keys.

---

# 40. Launch snapshots

Changing a saved preset while a realm is already running must not mutate the running realm invisibly.

On launch:

```text
Saved preset
      ↓
Resolve configuration
      ↓
Validate
      ↓
Create immutable launch snapshot
      ↓
Digest
      ↓
Generate playerbot config
      ↓
Start realm
```

Display:

```text
Running:
320 Smart LAN — revision 4

Saved preset now:
revision 5
```

until restart/reapply.

---

# 41. New identity format

The current `adv4` identity encodes many advanced values directly into a base36 ID and hashes the resulting resolved profile/config.

With many more advanced/expert settings, do not create an absurdly long command/profile ID.

Introduce a new versioned identity concept such as:

```text
usr5-<preset-uuid>-<revision>-<digest>
```

or:

```text
custom5-<snapshot-id>-<digest>
```

The immutable launch snapshot contains the complete resolved data.

The digest should continue protecting:

```text
resolved configuration
generated playerbot config
launch-sensitive fields
```

World startup revalidates the digest before using the snapshot.

---

# 42. Backward compatibility

Continue decoding:

```text
built-in legacy profile IDs
adv4 IDs
```

Do not destroy existing user configurations.

On first use of the new Bots page:

```text
Existing advanced setup detected

[ Keep legacy ]
[ Save as custom preset ]
```

Ideally automatically create:

```text
Imported Advanced Setup
```

without altering the selected configuration.

---

# 43. Preserve existing 700 configurations

The new **recommended built-in list** peaks at 600.

However:

* Do not invalidate `LAUNCH_DAY_700`.
* Do not delete existing 700-bot users' configuration.
* Do not make 700 impossible.

Existing 700 should remain launchable and migratable.

It can become:

```text
Legacy 700
```

or a custom preset.

Since custom population is allowed above 600, the user can still intentionally create:

```text
700
725
800
...
```

up to the validated actual engine/server limit.

The distinction is:

```text
Recommended built-in ceiling = 600

Custom ceiling = actual CMaNGOS/Playerbots limit
```

---

# 44. LAN destination

Create a dedicated side-navigation route:

```text
LAN
```

Move the current:

```text
LAN host IPv4
Join LAN
```

controls here.

Landscape layout:

```text
┌──────────────────────────────┬──────────────────────────────┐
│ HOST                         │ JOIN                         │
│                              │                              │
│ Local realm                  │ Host IPv4                    │
│ Status                       │ [                           ]│
│ Local address                │                              │
│                              │ [ Join ]                     │
│ [ Start host ]               │                              │
└──────────────────────────────┴──────────────────────────────┘

Connected players / future LAN features
────────────────────────────────────────────────────────────
```

---

# 45. LAN future-development structure

Build the route so it can later contain:

```text
Host
Join

Connected players
Player list
Session status

Discovered LAN realms
Recent servers

Permissions
Host controls

LAN account management
Invitations

Session logs
Connection diagnostics
```

Do not implement fake functionality.

It is fine for future sections to be absent until supported.

The important work now is separating the architecture so LAN does not remain embedded in Home.

---

# 46. LAN bot behaviour versus LAN networking

Keep ownership clear.

## LAN tab

Controls:

```text
network connection
host/join
LAN players
LAN session
```

## Bots tab

Controls:

```text
how bots behave around multiple humans
party allocation
LAN Co-op behaviour
role filling
nearby fairness
```

The Bots playstyle selector may still include:

```text
LAN Co-op
```

because that is bot behaviour, not networking.

---

# 47. LAN Co-op bot behaviour

For multiple human players:

```text
Do not attach all nearby bots to the first player.

Allocate foreground AI fairly across:
individual humans
human parties
human raids
zones
```

Humans in the same party should count as one activity cluster where appropriate.

Example:

```text
4 humans in separate zones
→ four activity allocations

4 humans in same party
→ one group activity allocation
```

Human-group bots retain foreground scheduling.

---

# 48. Bots screen preset pane

Landscape left pane:

```text
PRESETS

Recommended
★ Alive Realm

Built-in
Lively
Busy World
Alive Realm
Crowded Realm
Full Realm
Massive Realm
Low Power

MY PRESETS
★ 320 Smart LAN
420 Classic
600 Party Fast
725 Experiment
Dungeon Team

[ + New preset ]
```

Add:

```text
Search
Sort
Favorites
```

if many user presets exist.

---

# 49. Bots screen configuration pane

Middle pane:

```text
WORLD SIZE
320 bots

ACTIVITY
Active

PLAYSTYLE
Classic World

NEARBY
16 per human

ADAPTIVE
Full

[ Advanced ]
```

When Advanced is enabled, do not replace the whole page with one very long vertical form.

Use categories.

---

# 50. Bots screen result pane

Right pane:

```text
CURRENT RESULT

320 bots

~38 normally active
Human groups: highest priority

Foreground       ~1 sec
Nearby           ~2 sec
Local            ~6 sec
Remote           ~30 sec

Ramp
50 → 320

Accounts
Auto · 48

[ Apply ]
[ Save As ]
```

For a running realm:

```text
Selected          320
Effective         305
Online            301

Reason
Recovering from thermal load
```

---

# 51. Show tradeoffs clearly

Examples:

## 160 Very Active

```text
Bots             160
AI               Very fast
Nearby           Very high
Background       Active
Group quality    Excellent
```

## 320 Alive Realm

```text
Bots             320
AI               Fast
Nearby           Very high
Background       Normal
Group quality    Excellent
```

## 600 Massive

```text
Bots             600
AI               Moderate
Nearby           High
Background       Light
Group quality    High
```

Do not simply label presets:

```text
High
Extreme
Maximum
```

without explaining what changes.

---

# 52. Diagnostics

Add a dedicated section within Bots:

```text
Overview
Diagnostics
```

Show:

```text
Selected bots
Effective bots
Online bots

Foreground bots
Nearby bots
Local bots
Background bots
Remote bots

Human-group bots

Logins/min
Teleports/min
Rerandomizations/min

Tick median
Tick p95
Tick p99
Hard stalls

Memory
Storage
Thermal

Adaptive stage
Adaptive reason
```

Much of the required underlying status information already exists in the current native status interfaces.

---

# 53. Do not let opening Advanced alter settings

Fix the existing issue where a fresh advanced configuration does not exactly match the active built-in profile.

The source report notes that current bare advanced defaults differ from the normal preset values.

Required behaviour:

```text
Alive Realm selected
↓
Open Advanced
↓
Every field exactly represents Alive Realm
```

No configuration change occurs until the user edits something.

---

# 54. Dirty-state behaviour

If a setting changes:

```text
Alive Realm
Modified
```

Actions:

```text
Apply once
Save as preset
Reset changes
```

Do not silently mutate the built-in definition.

---

# 55. Landscape spacing rules

Prioritise usable vertical space.

General guidelines:

```text
Top app bar              48–56 dp
Minimum touch target     48 dp
Card vertical padding    12–16 dp
Section spacing          12–16 dp
```

Avoid:

```text
large decorative title bands
large empty card margins
one-value-per-line layouts
huge filter-chip rows
excessive nested cards
```

Prefer:

```text
two-column fields
three-column panes
compact summary rows
segmented controls
dense readable cards
```

---

# 56. Side rail

Keep the permanent landscape navigation compact enough to accommodate:

```text
Home
Bots
LAN
Add-ons
Controls
Settings
```

without wasting content width.

Keep proper 48 dp touch targets.

Do not shrink icons into tiny desktop controls merely to save space.

---

# 57. Settings cleanup

After migration, Settings should contain only general app/server concerns.

Remove:

```text
World population
Bot profile
Advanced bot tuning
Bot behaviour
Nearby bot tuning
```

Replace with, at most:

```text
Bots
Configure in Bots →
```

if a cross-link is useful.

---

# 58. Home cleanup

Home should no longer be a configuration screen.

It should answer:

```text
What will start?
Is it running?
How do I start it?
What account am I using?
```

Not:

```text
How do I configure 40 bot parameters?
How do I join another LAN host?
```

---

# 59. Proposed final Home

```text
Home                                       Realm stopped

┌─────────────────────────────────────────────────────────────┐
│ Realm                                                       │
│ Ready to start                                              │
│ Alive Realm · 320 bots · Classic · Adaptive                 │
│                                     [Start] [Realm + game] │
└─────────────────────────────────────────────────────────────┘

┌────────────────────────────┬────────────────────────────────┐
│ Active setup               │ Local account                  │
│                            │                                │
│ 320 bots · Active AI       │ hi                             │
│ 1080p · 30 FPS · DXVK      │ [account controls]             │
│                            │                                │
│ [Bots] [Graphics]          │                                │
└────────────────────────────┴────────────────────────────────┘
```

This should fit much more naturally into the landscape viewport shown in the supplied screenshot.

---

# 60. Proposed final Bots page

```text
Bots

┌──────────────┬──────────────────────────┬────────────────────┐
│ PRESETS      │ ALIVE REALM              │ RESULT             │
│              │                          │                    │
│ ★ Alive 320  │ World size     320       │ 320 target         │
│ Lively 160   │ Activity       Active    │ ~38 active         │
│ Busy 240     │ Playstyle      Classic   │                    │
│ Crowded 400  │ Adaptive       Full      │ Party: highest     │
│ Full 500     │                          │ Nearby: ~1–2 sec    │
│ Massive 600  │ [Advanced]               │ Remote: ~30 sec    │
│              │                          │                    │
│ MY PRESETS   │                          │ [Apply] [Save As]  │
│ 320 LAN      │                          │                    │
│ 725 Test     │                          │                    │
│              │                          │                    │
│ [+ New]      │                          │                    │
└──────────────┴──────────────────────────┴────────────────────┘
```

---

# 61. Proposed final LAN page

```text
LAN

┌────────────────────────────┬────────────────────────────────┐
│ HOST                       │ JOIN                           │
│                            │                                │
│ Local realm stopped        │ Host IPv4                      │
│ Address: 192.168.x.x       │ [                            ] │
│                            │                                │
│ [Start realm]              │ [Join LAN]                     │
└────────────────────────────┴────────────────────────────────┘

Connected players
No remote players connected
```

---

# 62. Testing requirements

Add tests for:

## Navigation

```text
Home → Bots
Home → LAN
Bots no longer in Settings
LAN host entry no longer on Home
```

## Presets

```text
320 is recommended default
600 is highest recommended built-in
User may create custom >600
Existing 700 remains valid
```

## Custom limits

```text
No arbitrary 600/700/1000/1500 custom clamp
Actual engine constraints enforced
Invalid integer/overflow values rejected
Account capacity validated
```

## Saved presets

```text
Create
Save
Save As
Rename
Duplicate
Delete
Favorite

100+ presets without UI failure

Presets survive restart
Preset update is atomic
Running snapshot remains unchanged
```

## Migration

```text
Existing built-in profile IDs
Existing adv4 profile IDs
Existing 700 profile
Existing Advanced settings
```

## Advanced

```text
Opening Advanced produces zero config change
Per-setting reset works
Section reset works
Reset-all works
Raw overrides validate
Unknown keys rejected
```

## Runtime

```text
Foreground AI promotion
Delayed demotion
Human-party priority
Background-first degradation
Population reduction only after configured adaptation stages
Recovery toward selected target
```

## Landscape

Test at minimum:

```text
1280×720
1600×900
1920×1080
common Android tablet landscape sizes
```

Ensure the main Home screen does not waste a large top region merely displaying its route title.

---

# 63. Implementation order

Implement in this order:

1. Add Bots and LAN top-level navigation routes.
2. Move LAN controls out of Home.
3. Move bot controls out of Settings.
4. Compact the Home top/header area.
5. Compact Realm and Active Setup cards.
6. Create new landscape Bots three-pane layout.
7. Introduce new built-in preset model.
8. Make Alive Realm 320 the default.
9. Add 160/240/320/400/500/600 experience presets.
10. Keep legacy profile compatibility.
11. Determine actual upstream CMaNGOS/Playerbots population limits.
12. Remove arbitrary custom population ceiling.
13. Implement dedicated custom-preset repository.
14. Add unlimited named saved presets.
15. Add immutable launch snapshots and new identity version.
16. Add Basic/Advanced/Expert bot controls.
17. Enumerate upstream Playerbots settings.
18. Add searchable Expert settings.
19. Add validated raw bot-config overrides.
20. Implement locality-aware AI priority.
21. Force human group/combat bots to foreground priority.
22. Extend admission controller to degrade background AI before population.
23. Add richer diagnostics.
24. Add migration and UI/runtime tests.
25. Perform actual-device landscape soak testing.

---

# Final design rule

The product should no longer ask primarily:

```text
How many bots?
```

It should communicate:

```text
How alive should the realm feel?
```

The recommended answer is:

```text
Alive Realm
320 bots
Active AI
Fast AI around players and groups
Normal background simulation
Adaptive workload management
```

The built-in experiences stop at **600 bots** because that is a sensible curated preset range.

The **Custom** system does not stop at 600.

Advanced users can:

```text
choose their own bot population
choose their own activity level
configure behaviour independently
configure locality/scheduling
configure grouping
configure AI/performance
configure adaptation
configure supported Playerbots settings
save unlimited named presets
```

subject only to the **real constraints of the pinned CMaNGOS/Playerbots server implementation, database/account capacity, numeric correctness, and necessary PocketRealm runtime invariants**.

Do not replace one arbitrary UI cap with another.
