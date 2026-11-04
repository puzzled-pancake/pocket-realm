"""Pins for the playerbots chat-path overlays that outlived the bot-LLM strip.

The LLM layer (in-process llama backend, companion memory/persona/tools,
TLS/observability transport work) is retired; what stays are the hardening
anchors on upstream code that runs with the bot LLM disabled, plus the
overlays that put the pinned fork's own bot-LLM call sites back to
upstream. Each pin applies the driver anchor to a scratch copy of the
pristine submodule file - the same replace_anchor code the build runs - so
anchor drift fails here, not mid-build.
"""
from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
DRIVER_PATH = ROOT / "tools" / "build_o09_realm_runtime.py"
BOTS = ROOT / "native" / "playerbots" / "playerbot"
PATCHED_CONFIG = ROOT / "native" / "patches" / "playerbots" / "PlayerbotAIConfig.cpp"
PRISTINE_DEBUG = BOTS / "strategy" / "actions" / "DebugAction.cpp"
PRISTINE_SAY = BOTS / "strategy" / "actions" / "SayAction.cpp"
PRISTINE_AI = BOTS / "PlayerbotAI.cpp"
PRISTINE_AIFACTORY = BOTS / "AiFactory.cpp"
PRISTINE_RPG_TRIGGERS = BOTS / "strategy" / "triggers" / "RpgTriggers.cpp"
PRISTINE_SECURITY = BOTS / "PlayerbotSecurity.cpp"


@pytest.fixture(scope="module")
def driver():
    if not BOTS.is_dir():
        pytest.skip("playerbots submodule absent")
    spec = importlib.util.spec_from_file_location("driver_chat_path", DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["driver_chat_path"] = module
    spec.loader.exec_module(module)
    return module


def apply_anchors(module, source: Path, *names: str) -> str:
    """Apply driver anchors (by constant stem) to a scratch copy the way the
    build does; returns the patched text with LF line endings."""
    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp) / source.name
        shutil.copy2(source, copy)
        for name in names:
            module.replace_anchor(copy, getattr(module, name + "_UPSTREAM"),
                                  getattr(module, name + "_ANDROID"))
        return copy.read_text(encoding="utf-8").replace("\r\n", "\n")


def test_debug_ismod_requires_real_moderator_session(driver):
    patched = apply_anchors(driver, PRISTINE_DEBUG, "PB_DEBUG_MODGATE")
    assert 'event.getSource() == ".bot" ||' not in patched
    assert "!event.getOwner() ||" in patched  # console keeps powers
    assert "GetSecurity() >= SEC_MODERATOR" in patched


def test_endpoint_parse_catches_std_exception_and_fails_closed(driver):
    patched = apply_anchors(driver, PATCHED_CONFIG, "PB_LLM_EP_CATCH")
    assert "catch (const std::invalid_argument& e)" not in patched
    assert "catch (const std::exception& e)" in patched
    # fail closed: the endpoint is left empty, never a default port
    assert "llmEndPointUrl = ParsedUrl();" in patched


def test_raid_mentions_reply_in_raid_chat(driver):
    patched = apply_anchors(driver, PRISTINE_SAY, "PB_SAY_RAID_CASE")
    assert "case ChatChannelSource::SRC_RAID:" in patched
    assert "type = CHAT_MSG_RAID;" in patched


def test_whisper_counts_as_direct_address(driver):
    patched = apply_anchors(driver, PRISTINE_AI, "PB_AI_WHISPER_MENTION")
    assert "if (msgtype == CHAT_MSG_WHISPER)\n                    isMentioned = true;" in patched


def test_fork_llm_call_sites_restore_upstream(driver):
    aifactory = apply_anchors(driver, PRISTINE_AIFACTORY,
                              "PB_FORK_AIFACTORY_INCLUDE", "PB_FORK_AIFACTORY_GRANT")
    triggers = apply_anchors(driver, PRISTINE_RPG_TRIGGERS,
                             "PB_FORK_RPG_INCLUDE", "PB_FORK_RPG_CHANCE")
    security = apply_anchors(driver, PRISTINE_SECURITY,
                             "PB_FORK_SECURITY_INCLUDE", "PB_FORK_SECURITY_REFUSAL")
    for text in (aifactory, triggers, security):
        assert "PlayerbotLlm" not in text
        assert "CloudLaneOpen" not in text
    assert "    if(sPlayerbotAIConfig.llmEnabled == 2)\n" in aifactory
    assert "urand(0, 99) < sPlayerbotAIConfig.llmRpgAIChatChance" in triggers
    assert "llmBanterEnabled" not in security
    # the per-reason refusal dedupe from the same fork commit stays
    assert "dedupeKey << (uint32)reason;" in security


def test_no_retired_llm_overlay_survives_in_the_driver(driver):
    text = DRIVER_PATH.read_text(encoding="utf-8")
    for retired in ("PlayerbotLlamaRuntime", "PlayerbotLlmChatter", "llm_banter_core",
                    "POCKETREALM_LLAMA", "native/llm/"):
        assert retired not in text, retired
    patches = ROOT / "native" / "patches" / "playerbots"
    assert sorted(p.name for p in patches.iterdir()) == [
        "PlayerbotAIConfig.cpp", "RandomPlayerbotMgr.cpp"]
