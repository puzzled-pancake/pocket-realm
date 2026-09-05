"""PlayerbotLlmGates.h - the WS-A gate battery (plan v2.3 T1).

Compiles the shipped pure header on the host (-std=c++11, the repo's
core convention) and runs the full decision matrix; then pins the world-
side contracts:
  - the header IS the 22nd overlay (driver copy list + registry + the
    patches_content pin covers the bytes)
  - SayAction bridges the mirror GateSrc enum with static_asserts so an
    upstream ChatChannelSource renumber fails the build
  - the live CloudLaneOpen() conjunction (key AND tier) lives beside
    ExternalApiTierActive; no widened site may key on the bare key
"""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
GATES_H = PATCHES / "PlayerbotLlmGates.h"
HARNESS = ROOT / "tools" / "test_llm_gates.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"


@pytest.fixture(scope="session")
def gates_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    tmp = tmp_path_factory.mktemp("gates")
    exe = tmp / "gates_host.exe"
    result = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(HARNESS)],
        capture_output=True, text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stderr[:2000]
    return exe


def test_host_battery(gates_binary):
    result = subprocess.run([str(gates_binary)], capture_output=True,
                            text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "llm gates battery: OK" in result.stdout


def test_header_is_pure_no_io_clock_config_or_state():
    text = GATES_H.read_text(encoding="utf-8")
    for banned in ("sLog", "sWorld", "time(", "GetTickCount", "std::cin",
                   "std::cout", "fopen", "sPlayerbotAIConfig", "static "
                   "std::map", "std::mutex"):
        assert banned not in text, f"scope fence breached: {banned}"


def test_overlay_registration_is_the_22nd_file():
    driver_text = DRIVER.read_text(encoding="utf-8")
    assert '(bot_root / "PlayerbotLlmGates.h").write_bytes' in driver_text
    assert '"playerbot/PlayerbotLlmGates.h",' in driver_text
    copies = [line for line in driver_text.splitlines()
              if line.strip().startswith("(bot_root /") and
              ".write_bytes((NATIVE / \"patches\" / \"playerbots\"" in line]
    assert len(copies) == 22, (
        f"expected 22 whole-file overlay copies, found {len(copies)}")


def test_sayaction_bridges_the_src_enum_with_static_asserts():
    driver_text = DRIVER.read_text(encoding="utf-8")
    # the bridge rides the SayAction include anchor payload
    assert "static_assert" in driver_text
    assert "PlayerbotLlmGates::GATE_SRC_WHISPER" in driver_text
    assert "SRC_WHISPER" in driver_text


def test_live_conjunction_beside_the_tier_helper():
    memory = MEMORY_H.read_text(encoding="utf-8")
    assert "inline bool CloudLaneOpen()" in memory
    # the conjunction: the key AND the live tier check, never the key alone
    assert "sPlayerbotAIConfig.llmCloudChatter != 0 &&" in memory
    assert "PlayerbotLlmMemory::ExternalApiTierActive()" in memory


def test_key_defaults_match_the_plan_rows():
    driver_text = DRIVER.read_text(encoding="utf-8")
    for key, default in (
        ("LLMCloudChatter", 1),
        ("LLMPartyReplyEnabled", 0),
        ("LLMCloudStreetSayPct", 25),
        ("LLMStreetSayPerDay", 200),
        ("LLMRpgChatPerDay", 300),
        ("LLMBotToBotPerDay", 300),
        ("LLMCloudLineBudgetPerHour", 90),
        ("LLMCloudInteractivePerPlayerHour", 240),
        ("LLMDialogueFastLane", 1),
    ):
        needle = f'"AiPlayerbot.{key}", {default}'
        assert needle in driver_text, needle
