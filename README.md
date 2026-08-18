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
[wiki](docs/wiki/README.md) for what works today, with screenshots.

## What you need

- A supported Android handheld (Retroid Pocket 6 class; ARM64, 12 GB RAM
  recommended). An x86_64 emulator lane is used for development.
- **Your own copy of the WoW 1.12.1 client (build 5875).** Pocket Realm never
  bundles or distributes any Blizzard asset — no executables, MPQs, DBCs, maps,
  models, or textures. You import a client you are entitled to use; Pocket
  Realm makes a private managed copy and prepares its data on-device.
- Pocket Realm is a fan project and is not affiliated with, endorsed by, or
  sponsored by Blizzard Entertainment. World of Warcraft is a trademark of
  Blizzard Entertainment, Inc.

## Documentation

The [project wiki](docs/wiki/README.md) is the human-facing guide: getting
started, game file import, add-ons, controller and touch controls, settings,
the local server and bots, backups, troubleshooting, and a plain-language
explanation of [how the pieces work together](docs/wiki/How-It-Works.md).

## Building from source

```bash
git clone --recursive <this repository>
cd <repo-directory>
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
