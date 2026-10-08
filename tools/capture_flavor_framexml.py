#!/usr/bin/env python3
"""Capture the effective FrameXML option sources from a staged WoW client.

Lanes items 18/19 (package P11): the per-flavor settings/binding catalogs and
login topologies are capture-then-hash artifacts, exactly like the vanilla
109/211 precedent. The vanilla catalog's FrameXML-pin layer came from the
MOUZU mirror of Blizzard's 1.12.1 interface; for tbc/wotlk the SAME layer is
captured from the staged genuine clients themselves (S5.1 identity: Wow.exe
sha256 pinned in ClientFlavor.kt) by extracting the client's own
`Interface\\FrameXML` sources out of its locale MPQ chain.

MPQ load-order semantics this harness implements (the reason a naive
"newest archive wins" pass is not enough):

- the client opens the locale archives of its Data\\<locale> dir in a fixed
  order (locale, expansion-locale, lichking-locale, then the numbered
  patch-enUS-N archives, ascending). A file present in several archives
  resolves to the LAST archive that carries it;
- a patch archive may carry a TOMBSTONE for a file (a listfile entry whose
  block read yields no bytes). 3.3.5's patch-enUS.MPQ tombstones the classic
  UIOptionsFrame/SoundOptionsFrame/OptionsFrame trio — the file is DELETED
  by that patch, not "still the older version". The harness records the
  tombstone in the manifest and drops the file from the effective set;
- `backup-<locale>.MPQ` fallback archives are never part of the effective
  chain (the client only reads them if a primary fails) and are skipped.

The harness never launches the client (unattended-campaign rule): capture is
static archive + PE inspection only. The window-signature half of the login
topology pin is captured from the Wow.exe PE itself by scanning for the
null-terminated ASCII class-name candidates the engine registers
(`GxWindowClass*`) and the exact null-terminated UTF-16LE title string, and
is recorded in the same manifest so the Kotlin pins cite one artifact.

Usage:
    python tools/capture_flavor_framexml.py --flavor tbc \
        --client-root "E:\\Wow clients\\ExcaliburWoW Burning Crusade 2.4.3 - With Basic AddOns"

Output: out/flavor-capture/<flavor>/
    Interface/FrameXML/<file>   effective captured sources
    manifest.json               per-file sha256/size/source archive, exe
                                window-signature capture, tombstones
Exit codes: 0 = captured, 1 = usage/capture error.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import platform
import re
import sys
from pathlib import Path

try:
    from mpyq import MPQArchive
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("capture_flavor_framexml: the 'mpyq' package is required (pip install mpyq)\n")
    raise

REPO_ROOT = Path(__file__).resolve().parent.parent

# Locale-archive open order per family. FrameXML never ships in the speech
# archives (verified against the staged clients), so they are not listed.
LOCALE_CHAINS: dict[str, list[str]] = {
    "tbc": [
        r"{locale_dir}\locale-{locale}.MPQ",
        r"{locale_dir}\expansion-locale-{locale}.MPQ",
        r"{locale_dir}\patch-{locale}.MPQ",
        r"{locale_dir}\patch-{locale}-2.MPQ",
    ],
    "wotlk": [
        r"{locale_dir}\locale-{locale}.MPQ",
        r"{locale_dir}\expansion-locale-{locale}.MPQ",
        r"{locale_dir}\lichking-locale-{locale}.MPQ",
        r"{locale_dir}\patch-{locale}.MPQ",
        r"{locale_dir}\patch-{locale}-2.MPQ",
        r"{locale_dir}\patch-{locale}-3.MPQ",
    ],
}

# The FrameXML sources the settings/binding catalogs pin. Options-frame files
# that a family never shipped (tbc never had VideoOptionsPanels) simply do
# not resolve; the manifest records only what the client actually carries.
WANTED_FRAMEXML = [
    "Bindings.xml",
    "GlobalStrings.lua",
    "OptionsFrame.lua",
    "UIOptionsFrame.lua",
    "UIOptionsPanels.lua",
    "UIOptionsPanels.xml",
    "SoundOptionsFrame.lua",
    "AudioOptionsFrame.lua",
    "VideoOptionsPanels.lua",
    "VideoOptionsPanels.xml",
    "AudioOptionsPanels.lua",
    "AudioOptionsPanels.xml",
    "InterfaceOptionsFrame.lua",
    "InterfaceOptionsPanels.lua",
    "InterfaceOptionsPanels.xml",
]

DEFAULT_LOCALE = "enUS"

# Engine window-class name candidates. Every WoW client through 3.3.5a
# registers its single top-level frame under one of these ASCII names,
# chosen by the active Gx device at startup (OpenGL / Direct3D / Direct3D9Ex
# on Vista+). The login screen IS that frame; the title is the shared
# product string set through SetWindowTextW.
WINDOW_CLASS_CANDIDATES = [
    "GxWindowClassOpenGl",
    "GxWindowClassD3d",
    "GxWindowClassD3d9Ex",
]
WINDOW_TITLE = "World of Warcraft"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def capture_exe_window_signature(exe_path: Path) -> dict[str, object]:
    """Static PE scan for the login-frame window class/title strings.

    The engine passes these literals to RegisterClassA/CreateWindowExA, so
    their presence (as standalone null-terminated strings) is the binary
    capture of the login frame's identity. `WowWindow` — the value pinned for
    the vanilla lane in DESK-138 — appears NOWHERE in the 5875 image; this
    scan is what makes that discrepancy observable instead of folkloric.
    """
    data = exe_path.read_bytes()
    classes: dict[str, int] = {}
    for candidate in WINDOW_CLASS_CANDIDATES:
        needle = candidate.encode("ascii") + b"\x00"
        offsets: list[int] = []
        start = 0
        while True:
            index = data.find(needle, start)
            if index < 0:
                break
            offsets.append(index)
            start = index + 1
        if offsets:
            classes[candidate] = offsets[0]
    title_needle = WINDOW_TITLE.encode("utf-16-le") + b"\x00\x00"
    title_offsets: list[int] = []
    start = 0
    while True:
        index = data.find(title_needle, start)
        if index < 0:
            break
        title_offsets.append(index)
        start = index + 1
    return {
        "exe": exe_path.name,
        "exeSha256": sha256_hex(data),
        "exeBytes": len(data),
        "windowClassCandidates": {name: hex(offset) for name, offset in classes.items()},
        "windowTitleUtf16FirstOffsets": [hex(offset) for offset in title_offsets[:4]],
        "windowTitleUtf16OccurrenceCount": len(title_offsets),
        "windowTitle": WINDOW_TITLE if title_offsets else None,
        "notes": (
            "class strings are standalone null-terminated ASCII literals the "
            "engine registers; title verified as standalone null-terminated "
            "UTF-16LE (SetWindowTextW) literals; capture is static — the "
            "client was never launched"
        ),
    }


def capture_effective_framexml(client_root: Path, flavor: str, locale: str) -> tuple[dict[str, bytes], dict[str, object]]:
    data_dir = client_root / "Data"
    locale_dir = data_dir / locale
    if not locale_dir.is_dir():
        raise SystemExit(f"ERROR locale dir not found: {locale_dir}")

    chain: list[Path] = []
    for pattern in LOCALE_CHAINS[flavor]:
        path = Path(pattern.format(locale_dir=str(locale_dir), locale=locale))
        if not path.is_file():
            raise SystemExit(f"ERROR archive in the {flavor} load chain is missing: {path}")
        chain.append(path)

    effective: dict[str, bytes] = {}
    provenance: dict[str, dict[str, object]] = {}
    tombstones: dict[str, str] = {}
    for archive_path in chain:
        archive = MPQArchive(str(archive_path), listfile=True)
        names = [entry if isinstance(entry, bytes) else entry.get("filename") for entry in archive.files]
        for wanted in WANTED_FRAMEXML:
            key = f"Interface\\FrameXML\\{wanted}".encode("latin1")
            if key not in names:
                continue
            data = archive.read_file(key)
            if data is None:
                # Patch tombstone: the file is deleted from that patch on.
                tombstones[wanted] = archive_path.name
                effective.pop(wanted, None)
                provenance.pop(wanted, None)
                continue
            effective[wanted] = data
            provenance[wanted] = {
                "sourceMpq": archive_path.name,
                "bytes": len(data),
                "sha256": sha256_hex(data),
            }
    return effective, {"files": provenance, "tombstonedBy": tombstones}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--flavor", required=True, choices=sorted(LOCALE_CHAINS))
    parser.add_argument("--client-root", required=True, type=Path,
                        help="staged client root (the directory holding Wow.exe and Data/)")
    parser.add_argument("--locale", default=DEFAULT_LOCALE)
    parser.add_argument("--out-root", type=Path, default=REPO_ROOT / "out" / "flavor-capture")
    args = parser.parse_args()

    client_root = args.client_root.resolve()
    exe_path = client_root / "Wow.exe"
    if not exe_path.is_file():
        raise SystemExit(f"ERROR client exe not found: {exe_path}")

    effective, capture = capture_effective_framexml(client_root, args.flavor, args.locale)
    signature = capture_exe_window_signature(exe_path)

    out_dir = args.out_root / args.flavor
    framexml_dir = out_dir / "Interface" / "FrameXML"
    framexml_dir.mkdir(parents=True, exist_ok=True)
    for name, data in sorted(effective.items()):
        (framexml_dir / name).write_bytes(data)

    manifest = {
        "flavor": args.flavor,
        "locale": args.locale,
        "capturedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "capturedOn": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "clientRoot": str(client_root),
        "method": (
            "static capture: mpyq extraction of the client's own locale MPQ "
            "chain (effective file = last archive carrying it; patch "
            "tombstones delete) + PE string scan for the login window "
            "signature; the client was never launched"
        ),
        "exeWindowSignature": signature,
        "framexml": capture["files"],
        "tombstonedBy": capture["tombstonedBy"],
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(f"captured {len(effective)} effective FrameXML files for {args.flavor} -> {out_dir}")
    for name in sorted(effective):
        print(f"  {name:32s} {capture['files'][name]['bytes']:>7} B  <- {capture['files'][name]['sourceMpq']}")
    for name, archive in sorted(capture["tombstonedBy"].items()):  # type: ignore[arg-type]
        print(f"  {name:32s} TOMBSTONED by {archive}")
    print("exe window classes:", ", ".join(signature["windowClassCandidates"]) or "NONE FOUND")  # type: ignore[arg-type]
    print("exe window title:", signature["windowTitle"])  # type: ignore[index]
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
