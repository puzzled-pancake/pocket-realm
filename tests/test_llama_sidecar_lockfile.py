"""llama-server sidecar lockfile enforcement pins (R3-2, native-server
round 1).

tools/build_win_llama_server.py pins the built sidecar's size/SHA-256 in
native/llm/lockfile-win-x86_64.json, but the desktop image packaging
staged llama-server.exe WITHOUT consulting the pin — a stale, rebuilt or
foreign binary shipped silently (the provenance-that-can-rot class D57
named; the same discipline the vanilla-tweaks patcher and the arm64
vendored-llama libs already enforce at packaging). The packaging gate is
now fail-loud on a PRESENT-but-mismatched binary while ABSENT stays the
honest optional degrade (ERR_NO_SIDECAR_BINARY).
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE_KTS = ROOT / "desktop/build.gradle.kts"
LOCKFILE = ROOT / "native/llm/lockfile-win-x86_64.json"


def test_desktop_packaging_enforces_the_sidecar_lockfile():
    source = GRADLE_KTS.read_text(encoding="utf-8")
    assert "lockfile-win-x86_64.json" in source, (
        "packageApp no longer resolves the llama-server lockfile — a "
        "present-but-unverified sidecar would ship silently (R3-2)"
    )
    assert 'llamaArtifact["sha256"] == sha256Hex(llamaServerBinary)' in source, (
        "the sidecar size/SHA-256 check was dropped from packageApp"
    )
    # the absent-degrade survives the gate: the optional lane stays optional
    assert "embedded-LLM lane payload absent" in source


def test_sidecar_lockfile_carries_the_enforced_pins():
    record = json.loads(LOCKFILE.read_text(encoding="utf-8"))
    artifact = record["artifact"]
    assert artifact["name"] == "llama-server.exe"
    assert set(artifact) >= {"bytes", "sha256"}, (
        "the lockfile lost the size/SHA-256 pins the packaging gate enforces"
    )
    assert record["abi"] == "x86_64" and record["os"] == "windows"
