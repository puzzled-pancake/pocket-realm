# cMaNGOS Vanilla Playerbot CPU Optimisation & Local-Liveliness Report

**Research date:** 2026-08-10  
**Target:** cMaNGOS Classic/Vanilla (1.12) with the current `cmangos/playerbots` module  
**Audience:** implementation/coding agent  
**Objective:** reduce CPU and database load on lower-power CPUs while preserving the illusion of a populated world around real players, and provide a second fresh-realm configuration that concentrates more bots near the real-player level band.

> **Important:** The two configuration profiles below are evidence-based starting points derived from the current source, not benchmarked performance guarantees for a particular machine. The implementation agent should pin the exact source commits, apply one profile at a time, then run the benchmark protocol in this document before raising the bot count.

---

## 1. Executive summary

The best way to make cMaNGOS Vanilla playerbots work on a low-power CPU is **not** to make every bot fully active, and it is **not** simply to reduce the population until the server stops lagging. Current cMaNGOS already contains two layers of load shedding that can create a much better illusion:

1. **Core/map-level throttling** keeps real-player zones hot while bots on unrelated maps/zones receive minimal AI updates and do not drive the same nearby-cell work as a real player.
2. **Playerbot activity priorities** make bots visible to or near a real player much more active than bots in an inactive map/zone. The activity percentage is dynamically controlled against a target server tick/diff.

The correct low-power strategy is therefore:

- keep `DisableBotOptimizations = 0`;
- keep `DisableActivityPriorities = 0`;
- turn on `ForceActiveWhenNearPlayer = 1` so visible bots react properly;
- keep minimal movement enabled;
- explicitly set the action-iteration cap to 10;
- make expensive background systems optional rather than default (AH scanning, BG/LFG population, recurring gear upgrades, pre-quests);
- log bots in gradually and limit random-bot manager maintenance work per interval;
- prefer active-zone teleporting so the bots that *are* online are more likely to appear where the player is;
- for a private/small realm, use `RandomBotLoginWithPlayer = 1` so the random population is not burning CPU while nobody is playing.

### Recommended starting populations

These are **operator starting points**, not upstream defaults:

| CPU class | Starting online bots | Expected use |
|---|---:|---|
| 2 slow cores / very small VPS | 60–100 | solo/light questing |
| 4 low-power threads / mini-PC / low-end x86 | 120–180 | good local-world illusion |
| modern modest 4C/8T | 200–300 | denser local world; measure before adding BG/LFG |

SPP Classics publicly ships/recommends a default of **1000 random bots** and tells users to reduce that if lag develops after 30+ minutes. That is a repack-oriented baseline, not a sensible target for a low-power CPU. The useful lesson to borrow from SPP is the **population illusion + throttling model**, not the literal 1000-bot count.

For a newly launched realm where one player or a small cohort is levelling together, cMaNGOS can also synchronize random bots against the **highest real-player level**. A configuration-only profile can keep a large fraction of bots within roughly a few levels of that progression. However, there are two architectural limits:

- level sync is **global to the highest real-player level**, not per nearby player;
- generic random teleporting returns immediately for bots below level 5, so configuration alone cannot reliably cluster level 1–4 bots around a brand-new character.

For a true “bots around *my* level and *my* area” system once multiple real players spread across the world, implement the small locality/demand patch specified in Section 9.

---

## 2. Source/lineage findings: cMaNGOS vs SPP Classics

### 2.1 Current cMaNGOS lineage

The current `cmangos/playerbots` README describes the project as the ike3 playerbot AI core for Classic/TBC/WotLK and explicitly says the module is forked from `celguar/mangosbot-bots`.

Primary source:
- <https://github.com/cmangos/playerbots>

The current `PlayerbotAIConfig.cpp` still groups several important switches under a source comment named `//SPP switches`, including:

- `DisableBotOptimizations`
- `DisableActivityPriorities`
- `ForceActiveWhenNearPlayer`
- `LimitCombatActivity`
- `GuildOrderAlwaysActive`
- `botActiveAlone`
- `DiffWithPlayer`
- `DiffEmpty`

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L502-L522>

### 2.2 SPP Classics relationship

SPP Classics describes itself as:

- a cMaNGOS-based Vanilla/TBC/WotLK repack;
- using ike3 playerbots;
- defaulting to `MinRandomBots = 1000` and `MaxRandomBots = 1000`;
- advising reduction of the bot count if lag appears after the server has been running for 30+ minutes.

Primary source:
- <https://github.com/celguar/spp-classics-cmangos>
- <https://github.com/celguar/spp-classics-cmangos/releases>

### 2.3 Practical conclusion

Do **not** model the implementation as “copy SPP settings into current cMaNGOS.” The projects share lineage and design ideas, but current cMaNGOS contains more explicit load-aware behavior, active-zone logic, PID-controlled activity, and optional async login criteria.

The useful design principle is:

> **Create the impression of population by concentrating expensive AI where a real player can perceive it, while allowing a larger logged-in background pool to run with minimal AI or reduced activity.**

This is more CPU-efficient than forcing a smaller population to run full AI everywhere.

---

## 3. How current cMaNGOS actually saves CPU

This section matters because several tempting settings defeat the exact optimizations we want to use.

## 3.1 Layer 1: map/core active-zone throttling

In the current Classic core, `Map::Update` recalculates “active zones” every **10 seconds**. A zone is considered active when it contains a real, non-AFK, visible player; playerbots do not make a zone active themselves.

Primary source:
- <https://github.com/cmangos/mangos-classic/blob/master/src/game/Maps/Map.cpp#L700-L737>

For playerbots, the map update code then:

- always updates real players;
- preferentially updates bots in a zone containing a real player;
- forces updates for bots with a real-player master, bots in/queued for battlegrounds, and bots in combat;
- passes `minimal = true` into bot AI when a bot is not selected for a full update and optimizations are enabled.

Primary source:
- <https://github.com/cmangos/mangos-classic/blob/master/src/game/Maps/Map.cpp#L756-L811>

The same optimization also prevents ordinary playerbots from causing the full nearby-cell visit that real players do. For a non-real playerbot with optimizations enabled, the core ensures its own grid is loaded and then continues, rather than calling the full `VisitNearbyCellsOf(player, ...)` path.

Primary source:
- <https://github.com/cmangos/mangos-classic/blob/master/src/game/Maps/Map.cpp#L822-L852>

When the world is lagging (`avgDiff > 100`), active non-player objects in areas away from real players can also be probabilistically skipped.

Primary source:
- <https://github.com/cmangos/mangos-classic/blob/master/src/game/Maps/Map.cpp#L853-L888>

### Consequence

`AiPlayerbot.DisableBotOptimizations = 1` is exactly the wrong direction for a low-power server. It causes all bots to receive full AI instead of the core deciding which bots can use the minimal path.

**Low-power rule: keep `DisableBotOptimizations = 0`.**

---

## 3.2 Minimal AI is materially cheaper by design

`PlayerbotAIBase::YieldAIInternalThread(bool minimal)` applies a larger scheduling delay to minimal AI:

- normal: at least `ReactDelay`;
- minimal: at least `ReactDelay * 10`.

With `ReactDelay = 100`, that means the minimal path has an approximately **1000 ms scheduler floor** versus **100 ms** for a normal update when no longer delay is already set.

This is a scheduling effect, not a promise that minimal mode consumes exactly one tenth of the CPU.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIBase.cpp#L49-L53>

---

## 3.3 Layer 2: playerbot activity priorities

`PlayerbotAI::GetPriorityType()` gives high priority to bots which are directly useful to a real player. Important cases are ordered roughly as follows:

1. real-player master / real player;
2. group with real player;
3. battleground/test/instance;
4. **visible to a player**;
5. always-active guild/travel cases;
6. combat;
7. **nearby player**;
8. BG queue / LFG / friends / player guild;
9. active area;
10. active map but different zone;
11. inactive map.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAI.cpp#L5834-L5960>

The current priority brackets are particularly useful for low-power operation:

| Priority | Activity bracket |
|---|---:|
| real/master/group/visible/BG/test | `{0,0}` = always active for normal activity |
| instance | `{0,5}` |
| combat | `{0,10}` normally |
| BG queue | `{0,20}` |
| LFG | `{0,30}` |
| nearby player | `{0,40}` |
| friend / real guild | `{0,50}` |
| active area / empty server | `{50,100}` |
| active map, wrong zone | `{70,100}` |
| inactive map | `{80,100}` |

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAI.cpp#L5962-L6008>

For bracket upper bounds of 100, the calculated active proportion is multiplied by `botActiveAlone`. This is why `botActiveAlone` can be kept low while nearby/visible bots remain much more active.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAI.cpp#L6074-L6091>

### `ForceActiveWhenNearPlayer` is safe and targeted

Visible bots are already in the highest-priority class. For `REACT_ACTIVITY`, `ForceActiveWhenNearPlayer = 1` explicitly returns true for `VISIBLE_FOR_PLAYER` bots. It does **not** globally force the entire bot population to be active.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAI.cpp#L6043-L6071>

That makes this switch ideal for the desired effect: **lively when seen, cheap when not seen**.

---

## 3.4 Dynamic load control: `DiffWithPlayer` and `DiffEmpty`

The random bot manager includes a PID controller. It compares the world’s average diff against:

- `DiffWithPlayer` when real players are present;
- `DiffEmpty` when there are no real players.

It converts the PID output into an activity percentage clamped to 0–100.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L744-L814>

The sample config documents these as desired server tick speeds in milliseconds and gives:

```ini
# AiPlayerbot.DiffWithPlayer = 100
# AiPlayerbot.DiffEmpty = 200
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L1000-L1002>

For this report, leave those values at **100/200 initially**. Tuning them is less useful than first ensuring the optimization layers are enabled and background work is bounded.

---

## 4. Configuration/source mismatches the agent must not miss

The current source and the distributed comments disagree in several places. Always explicitly set these low-power-sensitive values.

### 4.1 `IterationsPerTick`: distributed intent = 10, C++ fallback = 100

The distributed config says:

```ini
# Max AI iterations per tick
#AiPlayerbot.IterationsPerTick = 10
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L761-L763>

But C++ currently loads a fallback of **100** if the key is absent:

```cpp
iterationsPerTick = config.GetIntDefault("AiPlayerbot.IterationsPerTick", 100);
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L186-L190>

In `Engine::DoNextAction`, the cap is multiplied by the current queue size; minimal mode uses half the configured value. This is a cap on action evaluation attempts, not a guaranteed linear CPU ratio.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/strategy/Engine.cpp#L118-L145>

**Recommendation: explicitly set `AiPlayerbot.IterationsPerTick = 10`.**

---

### 4.2 AH-outside-AH setting: documentation says disabled, C++ fallback is enabled

The sample config says the outside-AH query is “Disabled by default for performance concerns,” yet the line shown is commented:

```ini
# AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 1
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L883-L888>

C++ loads the fallback as `true`:

```cpp
shouldQueryAHListingsOutsideOfAH = config.GetBoolDefault(
    "AiPlayerbot.ShouldQueryAHListingsOutsideOfAH", true);
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L236-L240>

**Recommendation: explicitly set `AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0` on low-power servers.**

---

### 4.3 Async login documentation typo

The distributed config currently shows:

```ini
# AiPlayerbot.DefaultLoginCriteria1 = maxbots,spareroom,offline
```

But the C++ key actually read is:

```cpp
AiPlayerbot.DefaultLoginCriteria
```

Primary sources:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L1072-L1114>
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L276-L303>

**Use `AiPlayerbot.DefaultLoginCriteria`, not `DefaultLoginCriteria1`.**

---

### 4.4 `RandomBotsPerInterval` is source-supported but currently not documented near the ordinary interval block

C++ reads:

```cpp
randomBotsPerInterval = config.GetIntDefault("AiPlayerbot.RandomBotsPerInterval", 0);
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L230-L234>

In `RandomPlayerbotMgr`, 0 is converted to `UINT32_MAX`; a non-zero value bounds the number of successful `ProcessBot` operations during a manager pass.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L678-L721>

This is useful for smoothing random-manager work on weak CPUs. It is **not** a global limit on AI ticks.

Because this key is source-supported but not prominently documented in the distributed interval block, the agent should verify it still exists at the pinned revision before relying on it.

---

## 5. Profile A — low-power CPU, lively around real players

### 5.1 Design target

Use this when the goal is:

- private/small Vanilla realm;
- weak or efficiency-oriented CPU;
- real players should regularly see bots questing/grinding/RPGing around them;
- bots elsewhere can be heavily throttled;
- BG/LFG simulation is secondary to open-world liveliness.

### 5.2 Copy/paste starting configuration

Merge these values into the existing Vanilla `aiplayerbot.conf`; do not replace unrelated required settings blindly.

```ini
#####################################################################
# PROFILE A: LOW-POWER / LIVELY NEARBY
# Evidence-based starting profile; benchmark before raising bot count.
#####################################################################

AiPlayerbot.Enabled = 1
AiPlayerbot.RandomBotAutologin = 1
AiPlayerbot.RandomBotAutoCreate = 1

# --- Population ---
AiPlayerbot.MinRandomBots = 120
AiPlayerbot.MaxRandomBots = 160
AiPlayerbot.RandomBotMinLevel = 1
AiPlayerbot.RandomBotMaxLevel = 60

# Vanilla continents only. Avoid carrying expansion map IDs into Classic.
AiPlayerbot.RandomBotMaps = 0,1

# --- Login/load smoothing ---
# Do not flood weak hardware with all logins during startup.
AiPlayerbot.RandomBotLoginAtStartup = 0

# Good for a private/small realm: don't keep the random population active
# when nobody is actually playing.
AiPlayerbot.RandomBotLoginWithPlayer = 1

# Slow login bursts and random-manager maintenance work.
AiPlayerbot.RandomBotsMaxLoginsPerInterval = 3
AiPlayerbot.RandomBotUpdateInterval = 1500
AiPlayerbot.RandomBotsPerInterval = 12

# --- Critical CPU optimisation switches ---
# NEVER flip these two to 1 for this profile.
AiPlayerbot.DisableBotOptimizations = 0
AiPlayerbot.DisableActivityPriorities = 0

# Make bots that a real player can actually see react immediately.
AiPlayerbot.ForceActiveWhenNearPlayer = 1

# Avoid guild orders accidentally promoting many distant bots to always-active.
# If your gameplay depends heavily on guild orders, restore this to 1 and test.
AiPlayerbot.GuildOrderAlwaysActive = 0

# Do not use the emergency combat throttle as a normal setting.
AiPlayerbot.LimitCombatActivity = 0

# Keep background percentage low; visible/nearby bots have higher-priority rules.
AiPlayerbot.botActiveAlone = 5
AiPlayerbot.DiffWithPlayer = 100
AiPlayerbot.DiffEmpty = 200

# Retain cheap movement/progression illusion off-screen.
AiPlayerbot.EnableMinimalMove = 1

# --- AI action cost ---
# Explicitly set due config-vs-C++ fallback mismatch.
AiPlayerbot.IterationsPerTick = 10
AiPlayerbot.ReactDelay = 100
AiPlayerbot.PassiveDelay = 10000
AiPlayerbot.RpgDelay = 10000

# --- Put online bots where they can be perceived ---
AiPlayerbot.EnableRandomTeleports = 1
AiPlayerbot.RandomBotTeleportNearPlayer = 1

# Prevent a single teleport point/cluster being flooded.
AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount = 12
AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = 250

# Upstream sample is 2h-48h. This profile uses 1h-4h so a small online pool
# gradually migrates to real-player active zones without constant churn.
AiPlayerbot.RandomBotTeleportTeleportMinInterval = 3600
AiPlayerbot.RandomBotTeleportTeleportMaxInterval = 14400

# --- Disable optional expensive background simulation initially ---
AiPlayerbot.RandomBotJoinLfg = 0
AiPlayerbot.RandomBotJoinBG = 0
AiPlayerbot.RandomBotAutoJoinBG = 0
AiPlayerbot.PreQuests = 0
AiPlayerbot.RandomGearUpgradeEnabled = 0

# Explicit 0 required: current C++ fallback is true.
AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0
AiPlayerbot.BotCheckAllAuctionListings = 0

# Keep profiling instrumentation off in normal play; enable temporarily during tests.
AiPlayerbot.PerfMonEnabled = 0
```

### 5.3 Why this profile should feel livelier than “just use fewer bots”

A player in an active zone receives several overlapping advantages:

- the map makes that zone active;
- bots in that zone are selected for full rather than minimal AI more often;
- bots directly visible to the player are placed in the `VISIBLE_FOR_PLAYER` activity class;
- `ForceActiveWhenNearPlayer = 1` forces their react activity;
- `RandomBotTeleportNearPlayer = 1` filters eligible periodic teleport destinations toward active zones;
- distant/inactive-map bots remain in high throttle brackets.

This uses CPU **where perception is highest**.

### 5.4 Important semantic detail: “near player teleport” means active zone, not exact player radius

Current teleport code does not simply teleport a bot to “within N yards of the real player.” When `RandomBotTeleportNearPlayer` is enabled and the teleport is `activeOnly`, destination nodes are filtered to zones that the map marks active.

The `MaxAmount` / `MaxAmountRadius` pair controls how many bots can collect around a candidate teleport point; the radius is around that candidate point, not the real player.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L2246-L2293>

Therefore Profile A produces **zone-local population**, which looks more natural and is less intrusive than spawning a crowd directly on top of the player.

### 5.5 When to re-enable BG/LFG

Only re-enable these after the open-world profile is stable with CPU headroom:

```ini
AiPlayerbot.RandomBotJoinLfg = 1
AiPlayerbot.RandomBotJoinBG = 1
```

Test each independently. BG bots have high activity priority and the map-level code also force-updates BG/queued bots, so battleground simulation can consume a disproportionate share of the budget.

---

## 6. Profile B — fresh realm, bots concentrated near player progression level

### 6.1 Intended use

Use this profile when:

- the realm has just launched;
- there is one real player or a small group levelling roughly together;
- seeing many level-appropriate bots matters more than simulating every level band equally;
- the population should follow the progression curve over time.

### 6.2 Critical limitation of built-in level sync

`RandomPlayerbotMgr::CheckPlayers()` checks real players every **60 seconds** and stores the **maximum** real-player level.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L1930-L1958>

When sync is enabled, randomization caps a bot at `playersLevel + SyncLevelMaxAbove`. Existing bots can be re-randomized if they are above the cap or sufficiently below the highest player.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L2808-L2841>

First/random level assignment samples between the minimum/start level and the capped max, with `RandomBotMaxLevelChance` optionally snapping some bots to the current cap.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L2864-L2904>

So the built-in mechanism is ideal for a **solo or same-level cohort** but is not truly per-player once people spread out.

### 6.3 Copy/paste fresh-realm configuration

This profile retains the low-power principles from Profile A but raises local density and accelerates level-band convergence moderately.

```ini
#####################################################################
# PROFILE B: FRESH REALM / LEVEL-BAND HEAVY
# Best for solo or a small cohort progressing at similar levels.
#####################################################################

AiPlayerbot.Enabled = 1
AiPlayerbot.RandomBotAutologin = 1
AiPlayerbot.RandomBotAutoCreate = 1

# --- Population ---
AiPlayerbot.MinRandomBots = 180
AiPlayerbot.MaxRandomBots = 240
AiPlayerbot.RandomBotMinLevel = 1
AiPlayerbot.RandomBotMaxLevel = 60
AiPlayerbot.RandomBotMaps = 0,1

# --- Login/load smoothing ---
AiPlayerbot.RandomBotLoginAtStartup = 0
AiPlayerbot.RandomBotLoginWithPlayer = 1
AiPlayerbot.RandomBotsMaxLoginsPerInterval = 4
AiPlayerbot.RandomBotUpdateInterval = 1250
AiPlayerbot.RandomBotsPerInterval = 16

# --- Core/activity optimisations ---
AiPlayerbot.DisableBotOptimizations = 0
AiPlayerbot.DisableActivityPriorities = 0
AiPlayerbot.ForceActiveWhenNearPlayer = 1
AiPlayerbot.GuildOrderAlwaysActive = 0
AiPlayerbot.LimitCombatActivity = 0
AiPlayerbot.botActiveAlone = 5
AiPlayerbot.DiffWithPlayer = 100
AiPlayerbot.DiffEmpty = 200
AiPlayerbot.EnableMinimalMove = 1

# --- AI action cost ---
AiPlayerbot.IterationsPerTick = 10
AiPlayerbot.ReactDelay = 100
AiPlayerbot.PassiveDelay = 10000
AiPlayerbot.RpgDelay = 10000

# --- Follow current player progression ---
AiPlayerbot.SyncLevelWithPlayers = 1
AiPlayerbot.SyncLevelMaxAbove = 3
AiPlayerbot.SyncLevelNoPlayer = 1

# Current C++ fallback is already true, but make the intended behavior explicit.
AiPlayerbot.InstantRandomize = 1

# Bias part of the random population to the current capped max level.
# Upstream sample default is 0.15; 0.35 is a deliberate fresh-realm bias.
AiPlayerbot.RandomBotMaxLevelChance = 0.35

# Upstream sample can leave randomization scheduled for 2 hours to 14 days.
# These values make bots follow a fresh realm's progression within hours,
# while RandomBotsPerInterval smooths manager work.
AiPlayerbot.MinRandomBotRandomizeTime = 3600
AiPlayerbot.MaxRandomRandomizeTime = 10800

# --- Concentrate eligible bots in active zones ---
AiPlayerbot.EnableRandomTeleports = 1
AiPlayerbot.RandomBotTeleportNearPlayer = 1
AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount = 18
AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = 250
AiPlayerbot.RandomBotTeleportTeleportMinInterval = 1800
AiPlayerbot.RandomBotTeleportTeleportMaxInterval = 7200

# --- Optional background systems off until headroom is proven ---
AiPlayerbot.RandomBotJoinLfg = 0
AiPlayerbot.RandomBotJoinBG = 0
AiPlayerbot.RandomBotAutoJoinBG = 0
AiPlayerbot.PreQuests = 0
AiPlayerbot.RandomGearUpgradeEnabled = 0
AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0
AiPlayerbot.BotCheckAllAuctionListings = 0
AiPlayerbot.PerfMonEnabled = 0
```

### 6.4 Expected level behavior

With `SyncLevelMaxAbove = 3`:

- highest real player = 10 → new/randomized bot cap = 13;
- highest real player = 20 → cap = 23;
- highest real player = 40 → cap = 43;
- highest real player = 60 → Vanilla cap remains 60.

Existing bots sufficiently below the highest player are candidates for re-randomization. The `RandomBotMaxLevelChance = 0.35` setting increases the chance that a newly randomized bot lands at the top of the permitted range, so the visible population does not remain disproportionately low-level.

This is intentionally more aggressive than the upstream sample’s 0.15; benchmark gear/randomization spikes before increasing it further.

### 6.5 Level 1–4 caveat: current generic random teleport does nothing

Current `RandomTeleport(...)` returns immediately when:

```cpp
bot->GetLevel() < 5
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L2215-L2233>

This means `RandomBotTeleportNearPlayer = 1` cannot by itself create a dense level-1-to-4 starter-zone population. New level-1 bots may already be in starter locations because of character creation, but the active-zone teleport mechanism cannot be relied on to reposition them.

**If “fresh launch” specifically means level 1 and the starting village must look populated immediately, implement the starter-locality patch in Section 9.**

---

## 7. Optional advanced login bias after the bot pool exists

The current alternative login system can evaluate bots using criteria such as:

- `range`
- `map`
- `level`
- `group`
- `guild`
- `bg`
- `arena`
- `instance`
- `classrace`

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L1072-L1116>

The C++ currently reads the key as:

```ini
AiPlayerbot.DefaultLoginCriteria
```

and defaults to `maxbots,spareroom,offline` if not explicitly configured.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAIConfig.cpp#L276-L303>

For existing bots, `range` checks whether the bot is on the same map and within `LoginBotsNearPlayerRange`. For brand-new bots with `InstantRandomize`, the range check returns true because the final randomized location is not known yet.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotLoginMgr.cpp#L38-L83>

### Experimental Profile B add-on

Do **not** enable this until the ordinary profile is stable and a meaningful bot pool has been generated.

```ini
# EXPERIMENTAL: preferentially retain useful/local bots online.
AiPlayerbot.AsyncBotLogin = 1

# Keep this OFF on low-memory/low-power boxes.
AiPlayerbot.PreloadHolders = 0

# IMPORTANT: current C++ key has no trailing "1".
AiPlayerbot.DefaultLoginCriteria = maxbots,spareroom

# Criteria lines are AND internally; the lines are attempted as prioritized fallbacks.
AiPlayerbot.LoginCriteria.01 = group
AiPlayerbot.LoginCriteria.02 = range,level
AiPlayerbot.LoginCriteria.03 = map,level
AiPlayerbot.LoginCriteria.04 = level

AiPlayerbot.FreeRoomForNonSpareBots = 4
AiPlayerbot.LoginBotsNearPlayerRange = 1200
```

### Why this is optional rather than baseline

- Async login introduces its own DB/query-holder workflow.
- `PreloadHolders` explicitly warns of heavy DB usage and roughly **2 GB per 1000 accounts** in the distributed comments; leave it off for this goal.
- A brand-new bot with instant randomization passes the `range` test before its final location is known, so this is a *selection preference*, not a hard placement guarantee at first creation.
- The ordinary random manager already has useful login and activity throttles. Add async selection only if measurements show that the quality of the online population is the remaining problem.

---

## 8. High-impact setting matrix

| Setting | Low-power value | Why |
|---|---:|---|
| `DisableBotOptimizations` | `0` | preserves map/minimal-AI/core grid optimizations |
| `DisableActivityPriorities` | `0` | preserves visible/nearby > inactive-map priority hierarchy |
| `ForceActiveWhenNearPlayer` | `1` | visible bots receive react activity without globally waking bots |
| `botActiveAlone` | `5` initially | lowers activity for brackets whose upper bound is 100 |
| `LimitCombatActivity` | `0` | `1` changes combat bracket from `{0,10}` to `{99,100}`; too severe for normal play |
| `GuildOrderAlwaysActive` | `0` initially | avoids unrelated guild-order bots being permanently promoted |
| `EnableMinimalMove` | `1` | keeps minimal background movement illusion supported by the current priority logic |
| `IterationsPerTick` | `10` explicit | distributed config intent; avoids C++ fallback 100 |
| `RandomBotLoginAtStartup` | `0` | avoids large startup login spike |
| `RandomBotLoginWithPlayer` | `1` for private realm | no random population when nobody is playing |
| `RandomBotsMaxLoginsPerInterval` | `3–4` | smooths login bursts |
| `RandomBotsPerInterval` | `12–16` | bounds random-manager maintenance per pass; source-supported key |
| `RandomBotMaps` | `0,1` | Vanilla continents only |
| `RandomBotTeleportNearPlayer` | `1` | filters periodic eligible destinations to real-player active zones |
| `ShouldQueryAHListingsOutsideOfAH` | `0` explicit | current C++ fallback is true despite config comment |
| `RandomBotJoinBG` | `0` initially | BG/BG-queue paths stay high priority and add work |
| `RandomBotJoinLfg` | `0` initially | optional system; add after baseline stability |
| `PreQuests` | `0` | upstream notes quest marking slows bot creation |
| `RandomGearUpgradeEnabled` | `0` initially | avoids recurring random gear rebuild work |
| `PerfMonEnabled` | `0` normal operation | instrument only during diagnosis/benchmark |

---

## 9. Recommended code patch: true per-player level + locality demand

Configuration alone gets most of the way there for a solo fresh realm. If the actual desired behavior is:

> “wherever a real player goes, maintain a natural-looking set of bots around that player’s level and area, without globally waking or re-leveling the world,”

implement this patch.

## 9.1 New concept: `PlayerLocalBotDemand`

Compute a small demand object per real player every **10–15 seconds**, not every world tick.

Suggested structure:

```cpp
struct PlayerLocalBotDemand
{
    ObjectGuid playerGuid;
    uint32 mapId;
    uint32 zoneId;
    Team team;
    uint8 level;

    uint8 minLevel;       // max(1, level - 2)
    uint8 maxLevel;       // min(60, level + 3)

    uint32 targetSameZone;    // e.g. 10-16
    uint32 targetNear1500;    // e.g. 8-12
    uint32 targetVisible;     // soft target only, e.g. 3-6
};
```

The update frequency aligns well with the core’s existing **10-second active-zone recalculation** and is intentionally slow enough to avoid per-tick scans.

## 9.2 Candidate scoring instead of binary global sync

Score already-created bots against real-player demand. Suggested weights:

| Condition | Score |
|---|---:|
| exact player level | +100 |
| level delta ≤ 2 | +80 |
| level delta ≤ 4 | +40 |
| same zone | +80 |
| same map | +30 |
| within 1500 units | +50 scaled down by distance |
| starter/faction/race-safe region | +20 |
| in real-player group | +1000 / hard keep |
| in active BG/instance with real player | +1000 / hard keep |
| recently moved/promoted | hysteresis bonus |

Then fill local demand from highest-scoring eligible offline/low-priority bots.

### Why scoring is better

A score allows the server to choose the *best existing bot* rather than constantly randomizing or teleporting bots solely because a global max-level threshold changed. It minimizes DB, gear, and teleport churn.

## 9.3 Hard operation-rate limits

Start conservatively:

- login/promote: **1–2 bots/sec max globally**;
- teleport: **4–6 bots/min max globally**;
- level re-randomization: **2–4 bots/min max globally**;
- per-player same-zone target: **10–16**;
- per-player truly nearby target: **8–12**;
- do not deliberately force more than about **3–6** into immediate visibility at once.

These are implementation safety targets, not upstream defaults.

## 9.4 Add hysteresis to prevent thrashing

A real player crossing a zone border or using a flight path must not cause a login/teleport storm.

Recommended rules:

- retain a bot’s locality assignment for **30–60 seconds** after demand disappears;
- do not teleport the same bot again for at least **10 minutes** unless it is stuck/dead;
- do not re-randomize the same bot’s level more than once per **30–60 minutes** under the local-demand patch;
- merge overlapping demands from multiple real players before selecting bots.

## 9.5 Levels 1–4: add a starter-safe placement path

Do **not** remove the current `< 5` teleport guard and then teleport low-level characters to arbitrary grind nodes. The guard is protecting a set of assumptions around low-level placement.

Instead add a specific helper, conceptually:

```cpp
bool RandomPlayerbotMgr::PlaceLowLevelBotNearStarterDemand(
    Player* bot,
    Player const* realPlayer);
```

Behavior:

1. only for levels 1–4;
2. reject bots in combat, taxi, BG, instance, or a real-player group;
3. match faction/race starter region;
4. choose from prevalidated starter-safe nodes/quest hubs;
5. choose a node roughly **100–400 yards** from the real player or within the same starter subzone rather than at the player’s exact coordinates;
6. apply a long teleport cooldown;
7. never exceed the local bot-density target.

This solves the most visible weakness of the configuration-only fresh-realm profile.

## 9.6 Do not “solve” locality by disabling optimizations

The patch must never use either of these as a shortcut:

```ini
AiPlayerbot.DisableBotOptimizations = 1
AiPlayerbot.DisableActivityPriorities = 1
```

That makes the whole population expensive and defeats the architectural advantage.

## 9.7 Optional new async login criteria

If the agent wants to integrate locality cleanly into `PlayerbotLoginMgr`, add criteria such as:

- `player_level_range`
- `player_zone`
- `player_local_score`

Prefer a score-based selector over increasingly complex binary criteria once multi-player demand must be handled.

---

## 10. Instrumentation and benchmark protocol

Do not tune this by “it feels smooth.” Measure it.

## 10.1 Built-in useful metrics

The random bot command list includes `diff`, documented as:

```text
Show server performance metrics.
Usage: diff [player_diff] [empty_diff]
```

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L3960-L3981>

The activity PID log can include:

- current world diff;
- average diff;
- max diff;
- activity percentage;
- active bots;
- total online playerbots;
- progression metrics.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L744-L814>

`player_location.csv` can log playerbot positions and is useful for validating whether local density actually improved.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L366-L430>

Enable logging temporarily, for example:

```ini
AiPlayerbot.AllowedLogFiles = activity_pid.csv,player_location.csv
```

Disable it after benchmark collection if disk/log overhead matters.

## 10.2 Test methodology

Use the **same server binary, DB snapshot, config except the tested variable, and client route** for A/B comparison.

Do not benchmark the first-ever character-generation phase. Both the current cMaNGOS README and SPP documentation warn that first startup is slower because bot characters/gear are being generated.

### Warm-up

- start server;
- allow desired random population to settle;
- log real player in;
- wait **10 minutes** before the measured run unless testing login/startup behavior itself.

### Workload A — idle hub/starter town

- **10 minutes**;
- stand in a normal populated quest hub or starter settlement;
- observe bot activity and density without player travel.

### Workload B — normal quest route

- **15 minutes**;
- run/ride a repeatable questing route with at least one zone/subzone transition;
- interact with mobs/NPCs as a normal player would.

### Workload C — sustained combat

- **10 minutes**;
- grind continuously in an appropriate mob area;
- this detects combat-AI/pathing pressure.

### Workload D — multi-player locality, if relevant

- **10 minutes**;
- two real players on separate zones or separate continents;
- compare Profile B config-only against the locality patch.

## 10.3 Record these metrics

At minimum record every 5 seconds:

- world current/average/max diff;
- process CPU %;
- process resident memory/RSS;
- Character DB ping/latency if available;
- total online bots;
- active bots;
- player’s current map/zone/level;
- bots in same zone;
- bots within 150 yards;
- bots within 500 yards;
- bots within 1500 yards;
- same-zone bots with level delta ≤2;
- same-zone bots with level delta ≤4;
- login operations/min;
- teleports/min;
- level randomizations/min.

## 10.4 Suggested acceptance targets

These are operational targets for a weak box, not official cMaNGOS specifications:

| Metric | Suggested target |
|---|---:|
| median/p50 world diff | ≤ 100 ms |
| p95 world diff | ≤ 125 ms |
| sustained diff | avoid > 200 ms for 5+ sec |
| process CPU | ≤ 75–80% of allocated capacity in steady state |
| CPU headroom before enabling BG/LFG | preferably ≥ 20% |
| local Character DB ping | target < 20 ms; investigate repeated >100 ms |
| same-zone level-appropriate bots | ≥ 6 minimum; target 8–16 |
| patched immediate/local visibility | usually 3–6 visible, 8–12 within local radius |

## 10.5 Bot-count tuning algorithm

After establishing Profile A or B:

1. if p95 diff < **90 ms** and CPU < **65%**, add **25 bots**;
2. retest all three primary workloads;
3. if p95 diff > **125 ms** or CPU > **80%**, remove **25 bots**;
4. if CPU is fine but local density is low, **improve clustering/selection before increasing total population**;
5. if local density is high but CPU is poor, reduce background activity / login churn before reducing visible bots;
6. once stable, test BG and LFG independently.

This avoids treating total population as the only control knob.

---

## 11. Diagnostic experiments if CPU is still high

Run one change at a time.

### Experiment 1 — validate the core optimizer is working

Confirm:

```ini
AiPlayerbot.DisableBotOptimizations = 0
AiPlayerbot.DisableActivityPriorities = 0
```

If either is 1, stop and fix that before other tuning.

### Experiment 2 — action iteration pressure

Compare:

```ini
AiPlayerbot.IterationsPerTick = 10
```

against 20 under the same bot count. If 10 materially reduces p95/max diff with little behavior loss, keep 10.

### Experiment 3 — background feature cost

Enable one at a time:

- BG;
- LFG;
- AH outside-AH price queries;
- recurring gear upgrades.

The baseline profile deliberately disables all four so their costs can be measured independently.

### Experiment 4 — emergency combat-throttle test

`LimitCombatActivity = 1` changes the combat bracket to `{99,100}`, compared with normal `{0,10}`.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/PlayerbotAI.cpp#L5979-L5985>

Use it only for a short diagnostic A/B test. If it dramatically fixes CPU, combat/pathing is the hot area. Do **not** leave it as the default fix because it can make combat bots feel unresponsive unless overall activity is nearly 100%.

### Experiment 5 — random-manager maintenance

Try:

- `RandomBotsPerInterval = 8`
- `12`
- `16`

while watching how quickly logouts/teleports/randomizations settle and whether periodic diff spikes disappear.

Remember: this setting limits successful random-manager `ProcessBot` operations per pass; it does not directly throttle the normal AI engine.

---

## 12. Known traps / implementation checklist

### Configuration traps

- [ ] Do not copy SPP’s 1000-bot default onto a low-power box without measurement.
- [ ] Explicitly set `RandomBotMaps = 0,1` for Vanilla.
- [ ] Explicitly set `IterationsPerTick = 10`; C++ fallback is currently 100.
- [ ] Explicitly set `ShouldQueryAHListingsOutsideOfAH = 0`; C++ fallback is currently true.
- [ ] Use `AiPlayerbot.DefaultLoginCriteria`; the distributed async-login example currently shows a misleading `DefaultLoginCriteria1` name.
- [ ] Verify `RandomBotsPerInterval` exists in the pinned source revision before deploying it.
- [ ] Keep `DisableBotOptimizations = 0`.
- [ ] Keep `DisableActivityPriorities = 0`.
- [ ] Leave `LimitCombatActivity = 0` except for diagnosis.
- [ ] Keep `PreloadHolders = 0` on low-memory/low-power hardware.

### Behavior traps

- [ ] `ForceActiveWhenNearPlayer` targets visible reaction behavior; it does not make every bot globally active.
- [ ] `RandomBotTeleportNearPlayer` filters toward **active zones**, not an exact radius around the player.
- [ ] `RandomBotTeleportNearPlayerMaxAmountRadius` is a radius around candidate teleport points/bots, not around the real player.
- [ ] generic random teleport is a no-op below level 5.
- [ ] built-in level sync follows the **highest** real-player level globally.
- [ ] first/instant randomization can teleport with `activeOnly = false`; do not assume every newly created bot is immediately placed near a real player.
- [ ] async `range` treats a brand-new instant-randomized bot as near because its final location is unknown.
- [ ] setting very short randomization intervals can create gear/DB spikes even if normal AI is cheap.

---

## 13. Agent implementation plan

Give the coding agent the following concrete sequence.

### Phase 0 — pin and inspect

1. record:
   - `cmangos/mangos-classic` commit SHA;
   - `cmangos/playerbots` commit SHA;
2. verify all config key spellings from `PlayerbotAIConfig.cpp`;
3. save the existing production `aiplayerbot.conf`;
4. record baseline bot count and world diff with current settings.

### Phase 1 — deploy Profile A

1. apply Profile A;
2. reset/rebuild random-bot state only if required by the current installation/repack workflow;
3. let the bot pool settle;
4. run benchmark workloads A/B/C;
5. capture p50/p95/max diff, CPU, RSS, active/online bots, and local density;
6. adjust bot count only in ±25 increments.

### Phase 2 — fresh-realm profile

1. branch the config from Profile A;
2. enable level sync and Profile B randomization/teleport timings;
3. simulate a level progression sequence, e.g. real player levels 1, 5, 10, 20, 30;
4. at each stage record the level histogram of online/same-zone bots;
5. confirm that randomization spikes remain bounded.

### Phase 3 — implement true locality only if needed

Implement Section 9 if either is true:

- real players spread into different level bands and global max-level sync becomes undesirable;
- the server needs a visibly populated level-1-to-4 starter experience immediately.

### Phase 4 — optional systems

After the primary open-world experience passes the target:

1. test BG join;
2. test LFG;
3. test async login criteria;
4. test AH outside-AH querying only if economy behavior requires it;
5. keep each feature only if its CPU cost fits the headroom budget.

### Phase 5 — deliver before/after report

Agent should return:

```text
Pinned mangos-classic SHA:
Pinned playerbots SHA:
Hardware / VM allocation:

Baseline:
- Min/Max bots:
- p50/p95/max diff:
- avg/max CPU:
- RSS:
- active/online bots:
- same-zone bots:
- same-zone level delta <= 4:

Optimized:
- Min/Max bots:
- p50/p95/max diff:
- avg/max CPU:
- RSS:
- active/online bots:
- same-zone bots:
- same-zone level delta <= 4:

Changes kept:
Changes rejected:
Known regressions:
Recommended final population:
```

---

## 14. Suggested code-level telemetry patch

If the agent is touching the source, add a lightweight periodic log once every **10 seconds** rather than trying to infer all locality metrics from raw positions later.

Suggested CSV columns:

```text
timestamp,
world_diff_current,
world_diff_avg,
world_diff_max,
activity_pct,
online_bots,
active_bots,
real_players,
bots_same_active_zone,
bots_within_150,
bots_within_500,
bots_within_1500,
bots_level_delta_2,
bots_level_delta_4,
logins_last_60s,
teleports_last_60s,
rerandomizes_last_60s
```

Do the spatial counts from already-available maps/active-zone structures where possible; do not introduce a global O(players × bots) scan every world tick.

---

## 15. Why `RandomBotLoginWithPlayer = 1` is attractive for private realms

The ordinary random manager prevents adding bots when this setting is enabled and no real players are present. `ProcessBot` also treats the population as not allowed in the world when the real-player set/active sessions are empty and marks bots invalid for logout.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L647-L658>
- <https://github.com/cmangos/playerbots/blob/master/playerbot/RandomPlayerbotMgr.cpp#L2013-L2037>

Combined with `RandomBotsPerInterval`, logout may be gradual rather than instantaneous, which is acceptable and avoids a large synchronized operation burst.

Do **not** use this setting on a realm whose goal is a persistent bot economy/world simulation even while no humans are logged in. It is specifically recommended here because the stated target is low-power hardware with a lively experience around actual players.

---

## 16. Why Profile B should use moderate, not ultra-fast, re-randomization

The upstream sample’s randomization schedule is very broad:

```ini
#AiPlayerbot.MinRandomBotRandomizeTime = 7200
#AiPlayerbot.MaxRandomRandomizeTime = 1209600
```

That is roughly **2 hours to 14 days**.

Primary source:
- <https://github.com/cmangos/playerbots/blob/master/playerbot/aiplayerbot.conf.dist.in#L869-L879>

For a fresh realm, 14-day tails make level-band convergence too slow. However, reducing this to a few minutes for hundreds of bots would cause frequent `PlayerbotFactory` randomization/refresh work and undermine CPU smoothing.

Profile B therefore proposes **1–3 hours**, bounded additionally by `RandomBotsPerInterval`. If level-following still feels too slow, the preferred next step is the demand/scoring patch rather than making every bot re-randomize constantly.

---

## 17. Direct recommendations by priority

### Priority 0 — must do

1. `DisableBotOptimizations = 0`
2. `DisableActivityPriorities = 0`
3. `IterationsPerTick = 10` explicitly
4. `ShouldQueryAHListingsOutsideOfAH = 0` explicitly
5. `RandomBotMaps = 0,1`
6. gradual login (`LoginAtStartup = 0`, 3–4 max logins/pass)
7. start far below SPP’s 1000-bot population

### Priority 1 — best “lively around me” return

1. `ForceActiveWhenNearPlayer = 1`
2. `RandomBotTeleportNearPlayer = 1`
3. active-zone-friendly teleport intervals
4. keep `EnableMinimalMove = 1`
5. keep a modest logged-in pool instead of forcing full AI globally

### Priority 2 — fresh realm

1. `SyncLevelWithPlayers = 1`
2. `SyncLevelMaxAbove = 3`
3. `RandomBotMaxLevelChance = 0.35`
4. moderate 1–3h re-randomization
5. add starter-locality patch for levels 1–4 if immediate density matters

### Priority 3 — optional sophistication

1. async login selection after the pool exists
2. per-player demand/scoring patch
3. telemetry patch
4. BG/LFG reintroduction once CPU headroom is verified

---

## 18. Primary-source reference index

All research here was based on project/repository sources rather than forum tuning folklore.

### cMaNGOS playerbots

- Repository / README  
  <https://github.com/cmangos/playerbots>

- Distributed AI playerbot configuration  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/aiplayerbot.conf.dist.in>

- Configuration loading/defaults  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/PlayerbotAIConfig.cpp>

- Activity priorities / visible and nearby behavior  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/PlayerbotAI.cpp>

- Minimal/full AI scheduler delay  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/PlayerbotAIBase.cpp>

- Random bot manager / PID / teleport / sync / commands  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/RandomPlayerbotMgr.cpp>

- Async bot login criteria  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/PlayerbotLoginMgr.cpp>

- AI action engine / iteration cap  
  <https://raw.githubusercontent.com/cmangos/playerbots/master/playerbot/strategy/Engine.cpp>

### cMaNGOS Classic core

- Map update / active zones / bot update throttling  
  <https://raw.githubusercontent.com/cmangos/mangos-classic/master/src/game/Maps/Map.cpp>

### SPP Classics

- Repository / public settings guidance  
  <https://github.com/celguar/spp-classics-cmangos>

- Releases  
  <https://github.com/celguar/spp-classics-cmangos/releases>

---

## 19. Final position

For a lower-power cMaNGOS Vanilla server, the strongest architecture is:

> **moderate online population + full behavior near real players + minimal/throttled behavior elsewhere + gradual login/maintenance + active-zone clustering.**

That is already broadly what the current cMaNGOS code is designed to support. The main failure modes are configuration choices that disable those optimizations, blindly inheriting SPP’s 1000-bot repack default, and expecting the existing global level-sync/active-zone teleport mechanisms to provide true per-player level-locality.

Start with Profile A at **120–160 bots** on a low-power 4-thread machine, measure, and scale in **25-bot increments**. For a fresh solo/small-cohort realm, Profile B at **180–240 bots** plus global level sync should create a much stronger progression-matched population. If the realm later has players at divergent levels—or if level 1–4 starter areas must be guaranteed lively—implement the per-player demand/locality patch instead of increasing global bot activity.

