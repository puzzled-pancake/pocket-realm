#!/usr/bin/env python3
"""Smoke test for tools/install_client_windows.ps1 (Windows host only).

Builds two synthetic archives in a temp staging root — a client-shaped zip
(with a stub WoW.exe and Data MPQs; no Blizzard bytes) and a Blizzard-style
installer zip — then runs the companion script in -RankOnly mode and checks
the verdict table. Opt-in: `python scripts/smoke_archive_import.py`.
"""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "tools" / "install_client_windows.ps1"

MPQS = [
    "base.MPQ", "dbc.MPQ", "fonts.MPQ", "interface.MPQ", "misc.MPQ", "model.MPQ",
    "sound.MPQ", "speech.MPQ", "terrain.MPQ", "texture.MPQ", "wmo.MPQ",
]


def synthetic_exe() -> bytes:
    blob = bytearray(4096)
    blob[0] = ord("M")
    blob[1] = ord("Z")
    return bytes(blob)


def build_fixtures(root: pathlib.Path) -> None:
    with zipfile.ZipFile(root / "client-fixture.zip", "w") as zip_file:
        for mpq in MPQS:
            zip_file.writestr(f"Client/Data/{mpq}", b"MPQ\x1a" + b"x" * 16)
        zip_file.writestr("Client/WoW.exe", synthetic_exe())
        zip_file.writestr("Client/realmlist.wtf", "set realmlist us.logon.worldofwarcraft.com\n")
    with zipfile.ZipFile(root / "installer-fixture.zip", "w") as zip_file:
        zip_file.writestr("Wowinstall/setup.exe", b"setup")
        zip_file.writestr("Wowinstall/setup-1.bin", b"0" * 64)


def main() -> int:
    if sys.platform != "win32":
        print("windows-only smoke test; skipping")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        build_fixtures(root)
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", str(SCRIPT), "-StagingRoot", str(root), "-RankOnly",
            ],
            capture_output=True, text=True, check=True,
        )
        output = result.stdout
        assert "client-fixture.zip" in output, output
        assert "installer-fixture.zip" in output, output
        client_line = next(line for line in output.splitlines() if "client-fixture.zip" in line)
        installer_line = next(line for line in output.splitlines() if "installer-fixture.zip" in line)
        assert "OK" in client_line, client_line
        assert "REJECTED" in installer_line, installer_line
        print("smoke OK: client zip ranks OK, installer zip ranks REJECTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
