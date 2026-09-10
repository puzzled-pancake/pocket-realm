#!/usr/bin/env python3
"""Windows MSVC smoke gate for the playerbot LLM pure cores.

Windows-port Phase 2a evidence lane: compiles every shipped pure-core host
battery with MSVC cl.exe (located via vswhere, wrapped with vcvars64) and
runs it. The banter battery's golden FNV-1a64 hash is checked against the
pinned value — the same behavioral pin the g++/NDK lanes carry — proving the
deterministic cores produce bit-identical output under MSVC.

Exit 0 = all batteries compiled and passed; nonzero = failure with detail.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
TOOLS = ROOT / "tools"
VSWHERE = Path(
    os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"

GOLDEN_BANTER_FNV1A64 = "de4bd8227a3ab0d1"

# battery -> (harness, expected stdout substring, argv beyond the exe)
BATTERIES = {
    "banter_core": ("test_llm_banter_core.cpp", "banter invariants passed", ()),
    "chatter": ("test_llm_chatter.cpp", "chatter core battery", ()),
    "gates": ("test_llm_gates.cpp", "llm gates battery: OK", ()),
    "truth": ("test_llm_truth.cpp", "llm truth host battery", ()),
    "recall": ("test_llm_recall.cpp", "llm recall host battery", ()),
    "act_tools": ("test_llm_act_tools.cpp", "act tools battery passed", ()),
    "json_client": ("test_llm_json_client.cpp", "json client invariants passed", ()),
    "prompt_format": (
        "test_llm_prompt_format.cpp",
        "prompt-format byte-diff gate passed",
        ("bytediff", str(ROOT / "tests" / "llm_prompt_vectors.json")),
    ),
}


def find_vcvars() -> Path:
    # No -requires filter: Build Tools installs often carry cl.exe without
    # registering the workload component metadata vswhere keys on; the
    # vcvars64.bat existence check below is the honest verification.
    found = subprocess.run(
        [str(VSWHERE), "-latest", "-products", "*",
         "-property", "installationPath"],
        capture_output=True, text=True, check=True, timeout=30)
    install = (found.stdout or "").strip().splitlines()
    if not install:
        raise SystemExit("vswhere found no Visual Studio installation")
    vcvars = Path(install[0]) / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    if not vcvars.is_file():
        raise SystemExit(f"vcvars64.bat missing under {install[0]}")
    return vcvars


def compile_with_msvc(vcvars: Path, source: Path, exe: Path, log: Path) -> bool:
    script = log.with_suffix(".bat")
    # write_bytes (text mode would double the line endings) and every
    # interpolated path is quoted — %TEMP% profiles can contain spaces.
    script.write_bytes(
        (
            "@echo off\r\n"
            f'call "{vcvars}" >nul 2>&1\r\n'
            'cd /d "%s"\r\n'
            'cl /nologo /EHsc /std:c++17 /utf-8 /O2 /W3 '
            '/I"%s" "%s" /Fe:"%s"\r\n'
            % (source.parent, PATCHES, source, exe)
        ).encode("ascii")
    )
    result = subprocess.run(
        ["cmd", "/c", str(script)], capture_output=True, text=True, timeout=300)
    log.write_text(result.stdout + (result.stderr or ""), encoding="utf-8")
    return result.returncode == 0 and exe.is_file()


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    vcvars = find_vcvars()
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketrealm-msvc-smoke-") as tmp:
        work = Path(tmp)
        for name, (harness, expected, argv) in BATTERIES.items():
            source = TOOLS / harness
            if not source.is_file():
                print(f"SKIP {name}: harness {harness} not staged")
                continue
            exe = work / f"test_llm_{name}.exe"
            log = work / f"{name}.log"
            if not compile_with_msvc(vcvars, source, exe, log):
                failures += 1
                print(f"FAIL {name}: MSVC compile failed")
                print(log.read_text(encoding="utf-8", errors="replace")[-2000:])
                continue
            run = subprocess.run(
                [str(exe), *argv], capture_output=True, text=True, timeout=300,
                cwd=str(ROOT))
            if run.returncode != 0 or expected not in run.stdout:
                failures += 1
                print(f"FAIL {name}: battery did not pass")
                print((run.stdout + run.stderr)[-2000:])
                continue
            if name == "banter_core":
                match = re.search(r"golden_fnv1a64=([0-9a-f]{16})", run.stdout)
                if match is None or match.group(1) != GOLDEN_BANTER_FNV1A64:
                    failures += 1
                    print(f"FAIL {name}: golden hash mismatch "
                          f"(got {match.group(1) if match else 'none'}, "
                          f"want {GOLDEN_BANTER_FNV1A64})")
                    continue
            print(f"PASS {name}")
    if failures:
        print(f"{failures} failure(s)")
        return 1
    print("msvc pure-core smoke: all batteries passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
