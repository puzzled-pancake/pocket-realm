# Device test checklist — first-boot fixes build (2026-08-17)

Build: `devibe/cleanup` after F6; versionName **0.2.0**, versionCode **2**.
Install over the existing app with `adb install -r` (in-place; data kept) —
this is also a live test of the update-preservation requirement.

## Update path (F6)
1. Settings → App updates: shows "Installed: 0.2.0 (build 2)".
2. "Check for updates" → "Update channel unavailable: HTTP 404" is expected
   (the updates repo is not created yet — owner-gated).
3. "Release page" opens the browser at the placeholder repo URL.

## First boot after a data wipe or fresh install (F2/F3/F5)
4. Add-ons shows Android Port installed by default (seeding takes a few
   seconds after first launch; re-enter the tab if it was opened instantly).
5. Game setup: picking a folder shows the 30-minute confirmation dialog;
   Cancel leaves no permission behind (pick again → dialog again).
6. During import: no CPU% (Memory + process/thread lines only); device row
   reads "Retroid Pocket 6 • Snapdragon 8 Gen 2 • actively cooled".
7. If the system kills the import worker (leave the screen, heavy load): the
   card should say "restarting it automatically (n/4)" within ~1-2 min and
   the import continues without tapping Resume.
8. First realm start: warning dialog (databases + world prep, once-only);
   while starting, the realm card shows the "First start is building…"
   hint.
9. After login, in-game defaults should be: instant quest text, auto self
   cast, target-of-target, numeric status text, NPC names on, guild/PVP
   titles off, floating combat text on, newbie tips off, auto quest
   tracking on, camera follow "Never", sounds on, enemy nameplates on.
   (Friendly nameplates stay off and rotate-minimap does not exist in 1.12.)
10. Client tweaks card: widescreen FoV, auto-loot, camera-skip fix, and max
    camera distance all ON by default (fresh data only).

## World-entry crash bisection (F1a — the 09:59 ERROR #132)
11. After a fresh realm start, enter the world IMMEDIATELY (the old crash
    window). Note the outcome.
12. `/ap off bags` + `/ap radial` + `/ap off mover` + `/ap off hud`
    (or any subset), then `/console reloadui`, and retry immediate entry —
    one module at a time: bags → mover → bars → radial → hud. Report which
    configuration still crashes (Diagnostics → Client session evidence
    lists the WoW Errors/ files; timestamps are local time now).
13. Also note whether the client crashes stop once the world has been up
    for a few minutes (the pre-fix behavior).

## Stability/UX (F1/F4)
14. Client failure copy: if the game stops during preparation, the retry
    guidance mentions waiting a minute or two on first start.
15. Display card: "Classic 4:3 · 1280 × 960" chip appears; selecting it
    turns the Widescreen FoV tweak OFF (check Client tweaks) and the game
    pillarboxes; switching back to 16:9 leaves FoV off (re-enable by hand).
    Switching aspect resets customized control layouts (stated in the card).
16. In-Game Settings → Graphics: vsync, multisampling (0/1/2/4), color/depth
    bits (16/24), Fix Lag, hardware detection, environment detail, distance
    cull, small cull, texture filtering, smooth shading, frill/particle
    density, unit draw distance are all editable and survive a relaunch;
    resolution/window/maximize/frame-cap remain managed; refresh stays 60.
17. Bot basics: only ONE playstyle chip highlights (Independent no longer
    duplicates Classic World); each chip shows its summary; Activity and
    Playstyle have the new explanatory text.

## Notes
- Gladio GLX teardown fix (F1c) needs a Legacy-GL session end to exercise;
  the app should no longer crash when the guest disconnects.
- `:client` now runs as a foreground service while the game runs (a second
  notification appears) — backgrounding the app mid-session should no
  longer make the game process cache-killable.
