# Controls

The Controls screen chooses how physical controls, touch buttons, the pointer, and camera movement are translated into actions the original game understands.

Saved choices are used on the next game launch. When the game is already open, compatible changes are applied live after held inputs have been released safely.

## Control layouts

### Built-in leveling controls

This is the complete add-on-free layout. It covers movement, camera or pointer control, targeting, jump, twelve action slots, interaction at the current pointer, and auto-run.

Choose this when you want the simplest setup or when diagnosing an add-on problem.

### Custom

Changing any individual physical or touch assignment turns the layout into Custom while preserving all the other settings.

### Android Port

The optional Android Port profile (the built-in Vanilla Console Port add-on) keeps the add-on's normal action-bar and radial-menu keys. L1 on its own chooses the nearest eligible corpse, chest, or ordinary usable object in range and opens it as one action; a lootable corpse opens the normal loot window (auto-loot then works as usual), the realm still applies its normal range, line-of-sight, lock, and loot rules, and nothing appears in chat. The window opening relies on the client-side nearby-loot patch that ships inside every tuned `WoW.exe.patched`; with every client tweak disabled the app launches the pristine client, and L1 then opens loot invisibly. Mashing it about ten times a second can briefly mute chat through the realm's flood filter. R3 left-clicks at the pointer and L3 right-clicks it, so precise manual interaction stays one stick click away. Essential leveling controls use Select chords so the unreliable RP6 M1/M2 buttons are not required: hold Select + R1 for stock G / last hostile, Select + L1 for Use / Open at the pointer, Select + R2 for a left mouse click, Select + L3 to jump, Select + Start for Move UI, or Select + R3 for camera/pointer mode. Tap Select by itself for the Android Port radial menu. Opening it unlocks and centres the pointer; move the right stick over an icon and press R3 to open it. Tap Select again to close it.

Move UI (Select + Start, or radial entry 8) puts green drag handles on stock frames and add-on icons. Drag a handle with the pointer (hold R3), D-pad Down/Up moves focus between handles, D-pad Left/Right or the mouse wheel scales the focused frame, and Select + Start or Escape saves and exits. Bag and bank frames are scale-only because the stock client re-anchors them when opened. Use `/vcp resetui` in chat to restore the untouched stock layout.

After a kill, L1 is the intended one-press way back into the corpse's loot: walk within interaction range and press it once. Select + R1 asks WoW for the last hostile, and Select + L1 opens what the pointer aims at, as manual fallbacks.

## Input device choices

| Choice | Use it for |
| --- | --- |
| Automatic | The built-in Retroid controller is recognised automatically. Other gamepads use normal Android positions. |
| Retroid Pocket 6 | The RP6 printed face labels, sticks, and analogue triggers. The unreliable M1/M2 rear buttons are disabled by default. |
| Xbox / Android | A common Android or Xbox-style gamepad. |
| PlayStation 4 / 5 | Cross, Circle, Square, and Triangle positions reported through Android. |
| Generic controller | A third-party pad with standard Android axes and buttons. |
| Keyboard & mouse | Ignore gamepads and use a physical keyboard and mouse, with touch still available. |
| On-screen controls only | Ignore physical gameplay controllers and use the touch overlay. |

Printed A, B, X, and Y labels are not always reported the same way by Android. The face-position setting lets Pocket Realm follow the physical right, bottom, left, and top positions instead of trusting a letter alone.

## On-screen controls

The touch overlay has Automatic, Controller utilities, Full touch controls, and Off modes. Automatic shows a compact camera/pointer and utility strip when a controller is connected, and the full layout for touch-only play. Full mode includes movement, three pages for actions 1-12, camera drag, target, use or loot, camera zoom, jump, menu, auto-run, and a keyboard button.

You can adjust:

- Button size.
- Opacity.
- The width of the camera-drag area.
- Touch camera speed.
- Horizontal and vertical look direction.
- Every touch button assignment.
- L1/L2 face-layer assignments and duplicate-output warnings.

Lower opacity reveals more of the game behind the controls. A larger camera-drag area makes more of the centre screen respond to camera movement.

## Physical controller tuning

The physical controller section includes:

- Movement-stick dead zone.
- Camera-stick dead zone.
- Right-stick pointer and camera speed.
- Separate press and release points for L2 and R2.
- An editable action for each stick direction, face button, D-pad direction, shoulder, trigger, Start, Select, stick click, and rear control.

Separate trigger press and release points stop a noisy analogue trigger from rapidly turning the same action on and off.

## Normal built-in leveling actions

The exact assignments can be changed, but the normal add-on-free idea is:

- Left stick moves and strafes.
- Right stick controls the pointer when unlocked and the camera when locked.
- R1 or Target selects the nearest living enemy once per press.
- R2 or **Use / Open** sends a normal right-click at the current pointer. Use it to open a container, talk to an NPC, activate an object, or open loot when aimed correctly.
- There is no dedicated corpse or Loot All controller action. The optional **Auto-loot opened corpses** client tweak or a compatible add-on can collect items after a loot window is opened; hold Shift for the original manual loot window.
- R3 jumps.
- Face buttons use action slots 1-4. Hold L2 for 5-8 or L1 for 9-12.
- L3 toggles auto-run.
- D-pad Up sends stock G (last hostile, not guaranteed to be a corpse), Down sends F1 (self, or pet on a repeated stock binding), Left sends B (backpack), and Right sends L (quest log). These semantic labels depend on retaining the corresponding stock WoW bindings.
- On RP6, Xbox, PlayStation, and generic pads using the built-in layout, hold Select then press R3 to toggle camera/pointer mode. Hold Select then R2 to select with the left mouse button. A Select tap opens the map.
- RP6 M1/M2 rear buttons are disabled by default because some units can leave them latched. They are not required for normal play.

Vanilla 1.12 does not expose the modern interact-with-target or loot-all bindings. Target selects an enemy; **Use / Open** is the separate general-purpose right-click for a container, NPC, object, or aimed loot. The app briefly releases and restores camera lock for that click. Automatic loot collection belongs to the optional client tweak or an add-on, not the control scheme.

The Customize bindings section exposes every safe keyboard and mouse output supported by Pocket Realm, plus a searchable reference containing all 211 stock WoW 1.12.1 binding IDs. The reference does not invent modern bindings that the client does not have.

## Android buttons that stay protected

Home, Back, app switching, volume, and power remain Android controls. Pocket Realm does not let a gameplay profile reassign them.

## Restore recommended defaults

Use **Restore recommended defaults** if a custom layout becomes confusing. This resets the Pocket Realm input profile for the current display shape. It does not reset realm data, characters, or general launcher settings.
