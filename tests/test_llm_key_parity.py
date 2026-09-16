"""Conf key-parity gate.

The law: every ``AiPlayerbot.*`` conf key the driver registers/consumes
appears in the Kotlin emission surface, or is a DOCUMENTED conf-internal
key (engine-health knobs with safe native defaults;
the LLM screen exposes only the Cloud conversation toggle) - and every
Kotlin-emitted key of the LLM family has a native consumer
(driver payload or pristine submodule read), so no emission can orphan.

Three legs:
  1. the cloud-lane family (nine keys) is emitted WHOLE by
     CloudLaneConf - the group may not silently drop a member;
  2. the conf-internal exception set is FROZEN: a driver key missing
     from the Kotlin surface fails here until its author either emits
     it or documents it in CONF_INTERNAL_KEYS (a deliberate act, with
     the rationale recorded);
  3. no orphan LLM-family emission: every Kotlin ``AiPlayerbot.LLM*``
     literal resolves to a driver-registered key, a pristine submodule
     read, or a documented dynamic-prefix family.
"""
from __future__ import annotations

import io
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
KOTLIN_SRC = ROOT / "android" / "app" / "src" / "main" / "java"
CLOUD_LANE_CONF = KOTLIN_SRC / "com" / "pocketrealm" / "server" / "CloudLaneConf.kt"
PRISTINE_PLAYERBOTS = ROOT / "native" / "playerbots" / "playerbot"

# The cloud-lane emission family - the emission group carries all nine.
CLOUD_LANE_FAMILY = (
    "LLMCloudChatter",
    "LLMPartyReplyEnabled",
    "LLMCloudStreetSayPct",
    "LLMStreetSayPerDay",
    "LLMRpgChatPerDay",
    "LLMBotToBotPerDay",
    "LLMCloudLineBudgetPerHour",
    "LLMCloudInteractivePerPlayerHour",
    "LLMDialogueFastLane",
)

# The frozen conf-internal set (rationale per key class): these
# driver-registered keys are deliberately NOT app-emitted. Adding a key
# here is a deliberate act - cite the rationale. Everything else the
# driver registers must reach the Kotlin surface.
CONF_INTERNAL_KEYS = frozenset({
    # Memory knobs (facts/dossier/recap/saga/roundtable/truth) -
    # conf-internal, no UI surface by design (interpretation ii)
    "LLMDossierEnabled", "LLMDossierPerDay", "LLMRecapEnabled",
    "LLMRecapProse", "LLMRecapProsePerDay", "LLMSagaEnabled",
    "LLMSagaPerDay", "LLMRoundtablePerDay", "LLMWorldTruthAmbient",
    "LLMWorldTruthFurniture", "LLMHistoryPersist", "LLMGreetMemory",
    "LLMPartyDigestPerDay",
    # C6 economics knobs - operator economics, safe native defaults
    "LLMTurnAwardDailyCap", "LLMTurnAwardWeighting",
    "LLMDeedPointsFirstVisit", "LLMDeedPointsQuest",
    "LLMDeedPointsSharedKill", "LLMDeedPointsTrade",
    # authored-layer + engine-health switches (A5/A8/A9/E3 kin)
    "LLMAuthoredLinesPerHour", "LLMBusyReply", "LLMCuriosityEnabled",
    "LLMDramaEnabled", "LLMEraBias", "LLMEventReactionsEnabled",
    "LLMGrudgeRefusalEnabled", "LLMMoodSeasoning", "LLMPromptDumpFile",
    "LLMSceneReadEnabled", "LLMToolsEnabled",
    # G3's TLS kill-switch - operator conf (conf.dist documents it; the
    # app stages the CA bundle, never the switch)
    "LLMTLSVerify",
    # D1's spawn-spread switch - native key by design (interpretation vii)
    "RandomBotLoginSpread",
})

# Kotlin emission families that are dynamic prefixes, not fixed keys.
DYNAMIC_PREFIX_FAMILIES = ("LLMPromptBlock.",)

KEY_RE = re.compile(r'"?AiPlayerbot\.([A-Za-z0-9_]+)"?')


def _driver_keys() -> set[str]:
    text = io.open(DRIVER, encoding="utf-8").read()
    return set(KEY_RE.findall(text))


def _string_literals(text: str) -> list[str]:
    """Extract double-quoted string literals with a small state machine:
    Kotlin comments may carry stray quotes (which break naive regex
    pairing) and literals may carry // (which naive comment-stripping
    eats). Tracks normal strings, triple-quoted strings, and // and
    /* */ comments."""
    lits: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                stop = n if end < 0 else end
                lits.append(text[i + 3:stop])
                i = n if end < 0 else end + 3
                continue
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            lits.append(text[i + 1:min(j, n)])
            i = j + 1
            continue
        if c == "/" and text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
            continue
        if c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        i += 1
    return lits


def _kotlin_keys() -> set[str]:
    """Keys from actual Kotlin string literals only (comments carry
    wildcards like ``AiPlayerbot.LLM*`` that are not keys)."""
    keys: set[str] = set()
    for path in KOTLIN_SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for literal in _string_literals(text):
            keys |= set(re.findall(r"AiPlayerbot\.([A-Za-z0-9_]+)", literal))
    return keys


def _pristine_llm_keys() -> set[str]:
    keys: set[str] = set()
    for path in list(PRISTINE_PLAYERBOTS.rglob("*.cpp")) + list(PRISTINE_PLAYERBOTS.rglob("*.h")):
        text = path.read_text(encoding="utf-8", errors="replace")
        keys |= set(re.findall(r'"AiPlayerbot\.(LLM[A-Za-z0-9_]+)"', text))
    return keys


def test_cloud_lane_family_is_emitted_whole():
    text = io.open(CLOUD_LANE_CONF, encoding="utf-8").read()
    for key in CLOUD_LANE_FAMILY:
        # the toggle emits literal 0/1; the rest are templates
        assert (f"AiPlayerbot.{key} = $" in text
                or f"AiPlayerbot.{key} = 0" in text
                or f"AiPlayerbot.{key} = 1" in text), (
            f"CloudLaneConf dropped the A0.a family member {key}")


def test_driver_keys_reach_kotlin_or_the_frozen_conf_internal_set():
    driver = _driver_keys()
    kotlin = _kotlin_keys()
    missing = driver - kotlin - CONF_INTERNAL_KEYS
    assert not missing, (
        "driver-registered AiPlayerbot keys with neither a Kotlin emission "
        "nor a documented conf-internal rationale: " + ", ".join(sorted(missing)))
    # the frozen set must not rot: every documented key is still
    # driver-registered and still unemitted
    stale = (CONF_INTERNAL_KEYS - driver) | (CONF_INTERNAL_KEYS & kotlin)
    assert not stale, (
        "the conf-internal set is stale (key no longer driver-registered, "
        "or now emitted - update the documentation): " + ", ".join(sorted(stale)))


def test_no_orphan_llm_family_emission():
    driver = _driver_keys()
    pristine = _pristine_llm_keys()
    dynamic = {prefix.rstrip(".") for prefix in DYNAMIC_PREFIX_FAMILIES}
    for key in _kotlin_keys():
        if not key.startswith("LLM"):
            continue
        if key in driver or key in pristine:
            continue
        if any(key == p or key.startswith(p) for p in dynamic):
            continue
        raise AssertionError(
            f"Kotlin emits AiPlayerbot.{key} with no native consumer "
            "(no driver registration, no pristine read, no documented "
            "dynamic-prefix family)")
