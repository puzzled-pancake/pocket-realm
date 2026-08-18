#!/usr/bin/env python3
"""Bootstrap verification for a clean checkout.

Confirms the host toolchain and pinned upstream sources are present and
consistent, so the Android + native build paths are reproducible. This is
the O01 acceptance check: a clean checkout can build the host/Android
bootstrap, and provenance is visible.

    python3 scripts/bootstrap.py            # check everything
    python3 scripts/bootstrap.py --json     # machine-readable report

Exits non-zero if any required tool or source is missing or drifted.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def find_sdk_root() -> str | None:
    for var in ("ANDROID_PLUGIN_SDK_PATH", "ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(var)
        if val and Path(val).is_dir():
            return val
    return None


def check_command(name: str) -> tuple[bool, str]:
    """Locate a command. Tries the Windows app-execution-alias trap for python."""
    # Guard: python3.exe under WindowsApps is a Store stub, not real Python.
    if name in ("python3", "python"):
        real = shutil.which(name)
        if real and "WindowsApps" in real:
            return False, "resolved to MS Store stub; install/alias real Python"
        return (real is not None), (real or "not found")
    found = shutil.which(name)
    return (found is not None), (found or "not found")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="emit JSON report")
    args = parser.parse_args()

    report: dict = {"ok": True, "checks": []}

    def record(name: str, ok: bool, detail: str) -> None:
        report["checks"].append({"name": name, "ok": ok, "detail": detail})
        if not ok:
            report["ok"] = False

    # --- Host commands ---
    for cmd in ("git", "python3", "cmake", "java"):
        ok, detail = check_command(cmd)
        record(cmd, ok, detail)

    # --- Android SDK ---
    sdk = find_sdk_root()
    record("android_sdk", sdk is not None, sdk or "ANDROID_HOME/SDK_ROOT not set or missing")
    if sdk:
        for sub in ("ndk", "build-tools", "cmdline-tools"):
            present = any((Path(sdk) / sub).iterdir()) if (Path(sdk) / sub).exists() else False
            record(f"sdk/{sub}", present, str(Path(sdk) / sub))

    # --- Pinned sources ---
    src_path = ROOT / "schemas" / "sources.json"
    if src_path.exists():
        record("sources_manifest", True, str(src_path.relative_to(ROOT)))
        verify = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "check_sources.py")],
            capture_output=True, text=True,
        )
        record("source_pins_match", verify.returncode == 0,
               verify.stdout.strip().splitlines()[-1] if verify.returncode == 0
               else verify.stderr.strip().splitlines()[-1] if verify.stderr else "drift detected")
    else:
        record("sources_manifest", False, "schemas/sources.json missing")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for c in report["checks"]:
            mark = "OK  " if c["ok"] else "FAIL"
            print(f"{mark} {c['name']:<22} {c['detail']}")
        print("\nBOOTSTRAP " + ("OK" if report["ok"] else "FAILED"))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
