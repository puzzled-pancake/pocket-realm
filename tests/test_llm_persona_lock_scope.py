"""Lock-scope pins for the authored persona layer.

A live tool-probe session crashed the whole world (SIGABRT via the host
JVM) when a player arrival-stormed across many bots: GreetingLine held
the s_stateMutex unique_lock (its StateRef) across the SeasonGreeting
call, and SeasonGreeting re-entered StateFor with its own lane key -
a same-thread re-lock of the NON-RECURSIVE state mutex. MSVC's STL
throws system_error(EDEADLK, "resource deadlock would occur") for it
instead of deadlocking; the throw escaped on the map-worker thread
(TickInitiative -> AuthoredArrivalGreeting) and std::terminated the
process. The caught-and-logged containment in AuthoredArrivalGreeting
is the safety net; these pins hold the lock-scope law itself (the
test_rp_harness.py textual pattern - the host cannot drive the native
boot).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PERSONA_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPersona.cpp"
MEMORY_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp"


def test_greeting_releases_the_lane_lock_before_seasoning():
    source = PERSONA_CPP.read_text(encoding="utf-8")
    start = source.index("std::string PlayerbotLlmPersona::GreetingLine(")
    end = source.index("\nbool PlayerbotLlmPersona::MaybeAmbientLine(")
    body = source[start:end]
    season_at = body.index("std::string line = SeasonGreeting(bot, player, drawn)")
    # the outer StateRef scope must CLOSE before the first SeasonGreeting
    # call (its StateFor re-enters the same non-recursive mutex)
    scope_close = body.rindex("}", 0, season_at)
    assert scope_close < season_at
    assert "StateRef stateRef" not in body[scope_close:season_at]


def test_arrival_greeting_contains_the_persona_layer():
    source = MEMORY_CPP.read_text(encoding="utf-8")
    start = source.index("std::string PlayerbotLlmMemory::AuthoredArrivalGreeting(")
    body = source[start:source.index("\nstd::string PlayerbotLlmMemory::AuthoredArrivalGreetingInner(")]
    # the map-worker-thread boundary: any throw from the persona layer is
    # caught, named through sLog, and answered with silence - never
    # allowed to std::terminate the process
    assert "try" in body
    assert "catch (std::exception const& e)" in body
    assert "authored arrival greeting threw" in body
