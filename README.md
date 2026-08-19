# Pocket Realm

Pocket Realm turns one Android handheld into
both a World of Warcraft 1.12.1 "private realm" server and its game client,
fully offline. It builds CMaNGOS (realmd + mangosd) and Playerbots as Android-native
libraries and MariaDB as a supervised native executable, all in fault-isolated
processes bound to loopback,
and runs your own copy of the Windows WoW 1.12.1 client under Wine with a
Winlator-derived X server and pinned graphics stacks (DXVK / Turnip / VirGL /
Gladio). One app: import your client, press play, and a local world with
computer-controlled companions comes up and shuts down safely.

**Status: alpha (0.100.1-alpha).** Screens and wording may change. See the
[wiki](docs/wiki/README.md) for what works today.

## Devices

Pocket Realm is developed and tested on the Retroid Pocket 6 (Adreno 740
GPU, 12 GB RAM). Nothing in its design is specific to that device: the
realm, the client runtime, and the local server all run as ordinary Android
processes bound to loopback. Whether a given handheld runs it well depends
mainly on the SoC and its GPU, not on RAM:

- **Qualcomm / Adreno** handhelds are the best bet. The game draws through
  DXVK over the device's Vulkan driver (the normal system route), and a
  packaged Turnip driver is available as an alternative, qualified so far
  only on the Retroid Pocket 6's Adreno 740.
- **Mali GPUs** (MediaTek, Exynos, and similar SoCs) have not been tested.
  The system Vulkan route applies to them too (DXVK needs Vulkan 1.3, or
  1.1 with the compatibility package), but whether a particular Mali
  device passes the capability check and holds a stable frame rate is
  exactly what reports are needed for.
- **Phones** may work in landscape with a controller, but they have not been
  heavily tested.

Memory is secondary: 6 GB should be enough to run the realm and the client
at the same time, but that has not been measured with real play sessions.
Reports from any device, especially Mali or 6 GB ones, are welcome (see
[Contributing](#contributing)).

## What you need

- **Your own copy of the WoW 1.12.1 client (build 5875).** Pocket Realm never
  bundles or distributes any Blizzard asset: no executables, MPQs, DBCs,
  maps, models, or textures. You import a client you are entitled to use; Pocket
  Realm makes a private managed copy and prepares its data on-device. The
  client must be a plain, already-extracted (uncompressed) folder — the one
  that directly contains `WoW.exe` and a `Data` folder — **not** an installer,
  downloader/launcher, or `.zip`/`.7z`/`.rar` archive: Pocket Realm cannot run
  installers or open archives, so extract a compressed copy first and select
  the extracted folder itself.
- Pocket Realm is a fan project and is not affiliated with, endorsed by, or
  sponsored by Blizzard Entertainment. World of Warcraft is a trademark of
  Blizzard Entertainment, Inc.

## Get the app

Prebuilt APKs are published on the project's
[Releases](https://github.com/puzzled-pancake/pocket-realm/releases) page.
Download the newest APK and install it on your device. Release packages carry
a `THIRD_PARTY_NOTICES.md` with the complete third-party license attribution.
Building from source is fully supported as well; see below.

## First boot

On a fresh install the app opens with a short first-run tutorial. It explains
exactly what to select — a plain, already-extracted (uncompressed) WoW 1.12.1
client folder, never an installer or an archive — and its final button takes
you straight to the import screen with the folder picker already open. You
can replay it anytime from **Settings → Setup → Show first-run setup guide**.

1. Open **Settings → Setup → Game files and import** and pick your WoW 1.12.1
   (build 5875) client folder with Android's folder picker. The folder must
   be the extracted client itself (the one containing `WoW.exe` and `Data`),
   not an installer, launcher, or archive. The folder is
   treated as read-only; Pocket Realm copies and verifies what it needs into
   private app storage and then prepares the server data on the device.
2. **Expect the import to take a while.** Depending on the device, storage
   speed, and the size of the client folder, copying, verifying, and data
   preparation can run for a long time on slower devices. Keep the device on
   a charger; the import screen shows the current phase, file counts, and
   progress, and if Android interrupts the work you can resume from the same
   screen.
3. Back on Home, press **Start realm** and wait for *Realm online*, create a
   local account in the **Local account** card (**Create & remember**), then
   launch the game. The app automatically logs into that account when the
   game reaches its login screen, so you do not need to type the details in
   the game. Later runs can start both together with **Realm + game**.

**Known first-launch bug:** logging in straight away after making a character
can cause a crash. Stay on the character screen for a few minutes first;
this only happens once, on the first launch.

The [Getting started](docs/wiki/Getting-Started.md) page walks through the
whole path with more detail.

## Documentation

The [project wiki](docs/wiki/README.md) is the human-facing guide: getting
started, game file import, add-ons, controller and touch controls, settings,
the local server and bots, backups, troubleshooting, and a plain-language
explanation of [how the pieces work together](docs/wiki/How-It-Works.md).

## Building from source

```bash
git clone --recursive https://github.com/puzzled-pancake/pocket-realm.git
cd pocket-realm
```

The Android app is built with Gradle (JDK 17). The mandatory ABI/lane
properties select what the build verifies:

```bash
cd android
./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full
```

A full APK additionally needs the native providers (Wine, MariaDB, the X
server, renderer packages) fetched and staged by the Python 3 tooling under
`tools/` and `scripts/`; see [Build and packaging](docs/wiki/Build-and-Packaging.md).

### Developer setup

Repository hygiene gates run as a pre-commit hook:

```bash
git config core.hooksPath .githooks
```

Note: `tools/check_sources.py` (part of the hook) needs initialized submodules
and the local `native/.providers` cache. On a fresh clone without them, defer
installing the hook (or commit with `--no-verify`) until both are populated.

## Contributing

Pocket Realm is a young alpha. The most useful contributions right now are
real-world reports:

- **Device compatibility.** Open an issue with your device model, chipset,
  RAM, Android version, and what worked: whether the realm and the client ran
  at the same time, which graphics package you used, and roughly how long you
  played. 6 GB devices are especially interesting: there is no concrete data
  yet for running the realm and the client together at that size.
- **Bugs.** A clear issue with the steps to reproduce it, what you expected,
  and what happened instead. Logs from Settings → Diagnostics and logs help
  (see [Backups and diagnostics](docs/wiki/Backups-and-Diagnostics.md)).
- **Code.** Pull requests are welcome; by contributing you agree your work is
  licensed under the project's GPL-3.0 license. See
  [Building from source](#building-from-source) for the developer setup.

Please do not upload or link Blizzard assets (client files, MPQs, or extracted
game data) in issues or pull requests.

## Updates

The app checks this repository's [Releases](https://github.com/puzzled-pancake/pocket-realm/releases)
page for updates (Settings → App updates). Release packages carry a
`THIRD_PARTY_NOTICES.md` with the complete third-party license attribution.

## Release checklist (maintainers)

Every published release must: attach the notices and name the exact source
state (this repository's release tag plus the pinned submodule commits on the
recorded mirrors); confirm conditional components absent from the package are
actually absent (alsa-lib, proot/talloc) or add their license entries; and
have explicit owner approval before publishing.

## License

Pocket Realm's own code is licensed under the GNU General Public License
v3.0; see [LICENSE](LICENSE). The repository vendors and builds many third-party
components (CMaNGOS, Playerbots, MariaDB, Wine, Winlator, Box64, DXVK, Turnip,
VirGL, Gladio, vanilla-tweaks, and more) under their own licenses: see
`schemas/sources.json`, `docs/patches/`, the in-tree LICENSE files of the
vendored trees, and `THIRD_PARTY_NOTICES.md` in release packages.

## AI-coded notice

Pocket Realm was developed with heavy use of AI coding. The work was done
through the ZCode agent harness, using the open GLM models GLM 5.2 and
GLM 5.3, with human direction and review throughout the project.
