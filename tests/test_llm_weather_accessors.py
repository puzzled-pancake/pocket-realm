"""Host pins for the read-only Weather accessors (plan v5 W7a/F5).

The PlayerBots LLM persona/memory weather context reads live weather
through Weather::GetWeatherType()/GetWeatherGrade() and
WeatherSystem::FindWeather(zoneId) (fail-on-miss, never creates). At this
HEAD those accessors are NOT in the pinned pristine cmangos submodule:
they are injected at build time by the CORE_WEATHER/CORE_WEATHERSYS
anchor pairs in tools/build_o09_realm_runtime.py (the s0.b lane-2
delivery vehicle for cmangos core files). These pins hold that contract:
the anchor payloads stay verbatim, the find-only lookup stays find-only,
both anchors stay registered for apply AND byte-pristine restore, the
pristine Weather.h keeps carrying the anchor UPSTREAM regions (a
submodule re-pin that restyles them must fail host-side, not
mid-build), and both overlay call sites keep consuming the accessors.
"""
from __future__ import annotations

from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
PRISTINE_WEATHER_H = ROOT / "native" / "cmangos" / "src" / "game" / "Weather" / "Weather.h"
PERSONA_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPersona.cpp"
MEMORY_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp"


def driver_text() -> str:
    return DRIVER.read_text(encoding="utf-8")


def anchor_text(name: str) -> str:
    """The text of a driver anchor constant (UPSTREAM or ANDROID)."""
    return driver_text().split(name + " = ")[1].split('"""')[1]


def read_normalized(path: Path) -> str:
    """Read with CRLF normalized so single-line pins survive either
    checkout mode (the submodules round-trip through autocrlf)."""
    return path.read_bytes().decode("utf-8").replace("\r\n", "\n")


def _submodule_available() -> bool:
    return (ROOT / "native" / "cmangos" / ".git").exists()


def test_driver_anchors_declare_the_read_only_weather_accessors() -> None:
    getters = anchor_text("CORE_WEATHER_ANDROID")
    assert "WeatherType GetWeatherType() const { return m_type; }" in getters, \
        "the Weather type getter rides the CORE_WEATHER anchor payload"
    assert "float GetWeatherGrade() const { return m_grade; }" in getters, \
        "the Weather grade getter rides the CORE_WEATHER anchor payload"
    lookup = anchor_text("CORE_WEATHERSYS_ANDROID")
    assert "Weather* FindWeather(uint32 zoneId) const" in lookup, \
        "WeatherSystem::FindWeather is declared const on the WeatherSystem anchor"


def test_find_weather_anchor_is_find_only() -> None:
    """The fail-on-miss law: FindWeather must be a pure lookup. The
    create-on-miss side effect belongs to FindOrCreateWeather alone
    (the persona call-site comment pins the same wording)."""
    lookup = anchor_text("CORE_WEATHERSYS_ANDROID")
    assert "m_weathers.find(zoneId)" in lookup, \
        "the lookup rides the zone map's find, nothing else"
    assert "nullptr" in lookup, \
        "a miss returns nullptr (fail-on-miss), never a fresh Weather"
    assert "new Weather" not in lookup, \
        "FindWeather must never construct weather state on a read path"
    assert "m_weathers[zoneId]" not in lookup, \
        "FindWeather must not insert into the zone map either"


def test_weather_anchors_are_registered_for_apply_and_restore() -> None:
    source = driver_text()
    prepare = source.split("def prepare_cmangos_source()")[1].split("\ndef ")[0]
    for name in ("CORE_WEATHER_UPSTREAM", "CORE_WEATHERSYS_UPSTREAM"):
        assert name in prepare, \
            f"{name} must be registered inside prepare_cmangos_source()"
    assert prepare.count(
        'replace_anchor(cmangos / "src" / "game" / "Weather" / "Weather.h"') == 2, \
        "both weather anchors apply to the pristine Weather.h"
    # Weather.h is a tracked cmangos core file: every build must leave it
    # byte-pristine or the next build refuses the dirty submodule.
    restore = source.split("def restore_cmangos_source()")[1].split("\ndef ")[0]
    assert "CORE_WEATHER_ANDROID" in restore and "CORE_WEATHER_UPSTREAM" in restore, \
        "the getter anchor is registered for byte-pristine restore"
    assert "CORE_WEATHERSYS_ANDROID" in restore and "CORE_WEATHERSYS_UPSTREAM" in restore, \
        "the lookup anchor is registered for byte-pristine restore"


def test_pristine_weather_header_still_carries_the_anchor_regions() -> None:
    """A submodule re-pin that restyles Weather.h must fail host-side
    (before the build's anchor-drift refusal), which is only possible if
    the pinned pristine bytes still contain both UPSTREAM regions."""
    if not _submodule_available():
        pytest.skip("cmangos submodule not initialized")
    pristine = read_normalized(PRISTINE_WEATHER_H)
    for name in ("CORE_WEATHER_UPSTREAM", "CORE_WEATHERSYS_UPSTREAM"):
        region = anchor_text(name)
        assert region in pristine, \
            f"{name} no longer byte-matches pristine Weather.h (anchor drift)"


def test_both_overlay_call_sites_consume_the_accessors() -> None:
    persona = read_normalized(PERSONA_CPP)
    assert "FindWeather(zone->ID)" in persona, \
        "the W7a ambient bias looks the zone weather up fail-on-miss"
    assert "weather->GetWeatherType()" in persona
    assert "weather->GetWeatherGrade() > 0.0f" in persona
    memory = read_normalized(MEMORY_CPP)
    assert "->FindWeather(" in memory, \
        "the whisper scene lines share the same fail-on-miss lookup"
    assert "weather->GetWeatherType()" in memory
    assert "weather->GetWeatherGrade() > 0.0f" in memory
