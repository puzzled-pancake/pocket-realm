# Pocket Realm

Pocket Realm turns one Android handheld — primarily the Retroid Pocket 6 — into
both a World of Warcraft 1.12.1 "private realm" server and its game client,
fully offline. It builds CMaNGOS (realmd + mangosd) and Playerbots as Android-native
libraries and MariaDB as a supervised native executable, all in fault-isolated
processes bound to loopback,
and runs your own copy of the Windows WoW 1.12.1 client under Wine with a
Winlator-derived X server and pinned graphics stacks (DXVK / Turnip / VirGL /
Gladio). One app: import your client, press play, and a local world with
computer-controlled companions comes up and shuts down safely.

**Status: alpha (0.100.0-alpha).** Screens and wording may change. See the
[wiki](docs/wiki/README.md) for what works today.

## Devices

Pocket Realm is developed and tested on the Retroid Pocket 6. Nothing in its
design is specific to that device — the realm, the client runtime, and the
local server all run as ordinary Android processes bound to loopback — so any
ARM64 Android handheld with comparable specs should run it as-is; individual
models just have not each been verified. Phones may also work, but they have
not been heavily tested.

Memory is the main open question. 12 GB (the development device) is
comfortable; 6 GB may be enough to run the realm and the game client at the
same time, but that boundary has not been measured with real play sessions.
If you try Pocket Realm on a device not mentioned above — especially a 6 GB
phone — please report what worked (see [Contributing](#contributing)).

## What you need

- **Your own copy of the WoW 1.12.1 client (build 5875).** Pocket Realm never
  bundles or distributes any Blizzard asset — no executables, MPQs, DBCs, maps,
  models, or textures. You import a client you are entitled to use; Pocket
  Realm makes a private managed copy and prepares its data on-device.
- Pocket Realm is a fan project and is not affiliated with, endorsed by, or
  sponsored by Blizzard Entertainment. World of Warcraft is a trademark of
  Blizzard Entertainment, Inc.

## Get the app

Prebuilt APKs are published on the project's
[Releases](https://github.com/puzzled-pancake/pocket-realm/releases) page —
download the newest APK and install it on your device. Release packages carry
a `THIRD_PARTY_NOTICES.md` with the complete third-party license attribution.
Building from source is fully supported as well; see below.

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
`tools/` and `scripts/` — see [Build and packaging](docs/wiki/Build-and-Packaging.md).

### Developer setup

Repository hygiene gates run as a pre-commit hook:

```bash
git config core.hooksPath .githooks
```

Note: `tools/check_sources.py` (part of the hook) needs initialized submodules
and the local `native/.providers` cache. On a fresh clone without them, defer
installing the hook (or commit with `--no-verify`) until both are populated.

## Contributing

Pocket Realm is a young alpha — the most useful contributions right now are
real-world reports:

- **Device compatibility.** Open an issue with your device model, chipset,
  RAM, Android version, and what worked: whether the realm and the client ran
  at the same time, which graphics package you used, and roughly how long you
  played. 6 GB devices are especially interesting — there is no concrete data
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

The app checks for updates from a dedicated public GitHub "updates" repository
(Settings → App updates). Release packages carry a `THIRD_PARTY_NOTICES.md`
with the complete third-party license attribution.

## Release checklist (maintainers)

Every published release must: attach the notices and name the exact source
state (this repository's release tag plus the pinned submodule commits on the
recorded mirrors); confirm conditional components absent from the package are
actually absent (alsa-lib, proot/talloc) or add their license entries; and
have explicit owner approval before publishing.

## License

Pocket Realm's own code is licensed under the GNU General Public License v3.0 —
see [LICENSE](LICENSE). The repository vendors and builds many third-party
components (CMaNGOS, Playerbots, MariaDB, Wine, Winlator, Box64, DXVK, Turnip,
VirGL, Gladio, vanilla-tweaks, and more) under their own licenses — see
`schemas/sources.json`, `docs/patches/`, the in-tree LICENSE files of the
vendored trees, and `THIRD_PARTY_NOTICES.md` in release packages.
