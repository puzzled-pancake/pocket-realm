"""The S10 world-chatter battery (E6/§4.6b host gate).

Compiles the SHIPPED pure core (native/patches/playerbots/
PlayerbotLlmChatterCore.h) on the host with -std=c++11 and pins the
power-ladder policy table, the world repetition ring, the fatigue +
legend ledger (retirement, credence, the content-hop cap), the authored
event-grounded floor, the frozen murmur/composer prompt wording (the
P52 wording lock) and the 6-hour soak invariants - silence default,
world-ring zero-repeat, retirement ceilings, interruption suppression,
per-rung cadence ceilings. The world-side glue (the scheduler tick, the
batch workers, the delivery ledger, the driver anchors, the conf keys)
is pinned by source-contract assertions below, following
test_llm_truth.py / test_llm_recall.py's pattern: the host cannot drive
PlayerbotAI or the DB layer, so the contract the world thread implements
is pinned instead.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "PlayerbotLlmChatterCore.h"
HARNESS = ROOT / "tools" / "test_llm_chatter.cpp"
CHATTER_CPP = PATCHES / "PlayerbotLlmChatter.cpp"
CHATTER_H = PATCHES / "PlayerbotLlmChatter.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
POLICY_KT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "server" / "LlmRuntimePolicy.kt"
MONITOR_KT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "server" / "ChatterPowerMonitor.kt"


@pytest.fixture(scope="session")
def chatter_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("chatter core not staged")
    tmp = tmp_path_factory.mktemp("chatter")
    exe = shutil.copy(HARNESS, tmp / "harness.cpp")
    out = tmp / "chatter_host.exe"
    compile = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall",
         "-I", str(PATCHES), "-o", str(out), str(exe)],
        capture_output=True, text=True, cwd=str(ROOT))
    assert compile.returncode == 0, compile.stderr[:2000]
    return out


def test_host_battery_legs(chatter_binary):
    result = subprocess.run([str(chatter_binary)], capture_output=True,
                            text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "chatter core battery" in result.stdout and "OK" in result.stdout


# ---- source-contract pins (the in-tree glue the host cannot drive) --------

def test_overlay_manifest_carries_chatter_files():
    driver = DRIVER.read_text(encoding="utf-8")
    for rel in ("playerbot/PlayerbotLlmChatter.h",
                "playerbot/PlayerbotLlmChatterCore.h",
                "playerbot/PlayerbotLlmChatter.cpp"):
        assert f'"{rel}"' in driver, rel
    # the copy step mirrors the manifest (whole-file overlays)
    assert '(bot_root / "PlayerbotLlmChatter.cpp").write_bytes' in driver
    assert '(bot_root / "PlayerbotLlmChatterCore.h").write_bytes' in driver
    assert '(bot_root / "PlayerbotLlmChatter.h").write_bytes' in driver


def test_conf_keys_wired():
    driver = DRIVER.read_text(encoding="utf-8")
    # the five conf reads exist with the silence-default 0 for the switch
    assert 'config.GetIntDefault("AiPlayerbot.LLMChatterEnabled", 0)' in driver
    assert 'config.GetStringDefault("AiPlayerbot.LLMChatterPowerFile", "")' in driver
    assert 'config.GetStringDefault("AiPlayerbot.LLMChatterComposerUrl", "")' in driver
    assert 'config.GetStringDefault("AiPlayerbot.LLMChatterComposerModel", "local")' in driver
    assert 'config.GetStringDefault("AiPlayerbot.LLMChatterComposerKey", "")' in driver
    # the composer url is parsed once like the main endpoint
    assert "llmChatterComposerUrlParsed = parseUrl(llmChatterComposerUrl);" in driver
    # the conf.dist.in doc block documents the layer
    assert "S10/E6 world chatter" in driver


def test_scheduler_tick_anchor():
    driver = DRIVER.read_text(encoding="utf-8")
    # the tick rides the telemetry 10 s gate on the world thread. Pinned
    # WITH the indentation + closing brace: a commented-out call
    # (";// PlayerbotLlmChatter::Tick();") keeps a bare-name pin alive
    # (round-3 mutation finding)
    assert "        PlayerbotLlmChatter::Tick();\n    }\n" in driver
    assert '#include "PlayerbotLlmChatter.h"' in driver


def test_interruption_stamp_at_the_gate():
    driver = DRIVER.read_text(encoding="utf-8")
    assert "PlayerbotLlmChatter::NotePlayerInteraction(gateSpeaker->GetGUIDLow());" in driver
    # stamped ONLY for real-player conversational triggers
    assert "if (hardTriggerAllowed && gateSpeaker && gateSpeaker->isRealPlayer())" in driver


def test_duel_event_note_hooked():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    assert "PlayerbotLlmChatter::OnDuelCompleted(participant, opponent);" in memory


def test_governor_extraction_and_raw_post():
    driver = DRIVER.read_text(encoding="utf-8")
    # Generate admits through the shared governor (no inline statics remain)
    assert "bool allowed = GovernorAdmit(botGuid);" in driver
    assert "static std::map<uint32, std::deque<time_t>> perBot;\n        std::deque<time_t> globalWindow;" not in driver
    # the ambient surfaces are public and defined
    assert "static bool GovernorAdmit(uint32 botGuid);" in driver
    assert "static std::string PostChatHttp(" in driver
    assert "static bool InteractiveGenerationInFlight();" in driver
    assert "bool PlayerbotLLMInterface::GovernorAdmit(uint32 botGuid)" in driver
    assert "std::string PlayerbotLLMInterface::PostChatHttp(" in driver
    assert "bool PlayerbotLLMInterface::InteractiveGenerationInFlight()" in driver
    # the in-flight probe's BODY reads the real counter (a stubbed body
    # keeps the signature pins alive - round-3 mutation finding)
    assert "return sPlayerbotLLMInterface.generationCount.load() > 0;" in driver
    # the governor's budget legs consume the conf values (either leg
    # gutted is a bypass; round-3 mutation finding)
    assert "botWindow.size() < std::max<uint32>(1, sPlayerbotAIConfig.llmGovernorBotMax) &&" in driver
    assert "globalWindow.size() < std::max<uint32>(1, sPlayerbotAIConfig.llmGovernorGlobalMax);" in driver
    # GenerateHttp carries the endpoint/key overrides (declaration + definition)
    assert "ParsedUrl const* endpointOverride = nullptr" in driver
    assert "std::string const* apiKeyOverride = nullptr)" in driver
    assert "ParsedUrl const* endpointOverride, std::string const* apiKeyOverride) {" in driver
    # the override legs inside the HTTP client
    assert "endpointOverride ? *endpointOverride : sPlayerbotAIConfig.llmEndPointUrl" in driver
    assert "apiKeyOverride ? *apiKeyOverride : sPlayerbotAIConfig.llmApiKey" in driver
    # parseUrl throws on non-URL text: the composer URL parse is guarded
    # exactly like the main endpoint (an unguarded empty-default parse
    # would abort every world boot - round-1 R6 P0)
    assert 'if (!llmChatterComposerUrl.empty())' in driver
    assert "catch (const std::exception& e)" in driver
    assert 'sLog.outError("Unable to parse LLMChatterComposerUrl: %s", e.what());' in driver


def test_scheduler_discipline():
    source = CHATTER_CPP.read_text(encoding="utf-8")
    # silence default: every picker failure stops the layer, never rolls
    assert "silence default: no event, no line, no floor" in source
    assert "silence default: no topic, no banter" in source
    # the master toggle kills the queue too (mid-session via the power
    # file) - pinned on the CALL-SITE slice, not the comment above it
    assert "s.queue.clear();  // the master toggle kill: drop pending lines too" in source
    # the rung bounds guard the file's rung value (a garbage rung = OFF)
    assert "if (!enabled || rung < pocketllm::RUNG_EMERGENCY || rung > pocketllm::RUNG_NORMAL)" in source
    # a stale power file (or one stamped from the future) degrades to the
    # authored floor
    assert "bool const stale = at <= 0 || at > now + kPowerFreshSec ||" in source
    assert "        s.rung = pocketllm::RUNG_EMERGENCY;" in source
    # device batches pay the governor and yield the interactive lane
    assert "PlayerbotLLMInterface::InteractiveGenerationInFlight()" in source
    assert "PlayerbotLLMInterface::GovernorAdmit(job.speakerGuid)" in source
    # ambient dispatch waits for a quiet channel (player chat outranks
    # the batch lane, not just the delivery) - pinned on the ASSIGNMENT
    # slices, not the bare name (round-3: the name-only pin survived a
    # disabled gate)
    assert "quiet = pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now);" in source
    assert ("                    if (fire && !pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now))\n"
            "                        fire = false;" in source)
    assert ("                    if ((windowAt == 0 || now - windowAt >= 60) &&\n"
            "                        pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now))" in source)
    # the murmur path never queues tools: the pure scanner previews, and
    # the queueing wrapper is never CALLED (its name only appears in the
    # comment that states this law)
    assert "pocketllm::ExtractToolCalls(rawLine, &cleaned)" in source
    assert "ExtractAndQueue(" not in source
    # the chatter line-safety law (newlines/pipes/protocol/emote leads)
    assert "if (!pocketllm::ChatterLineSafe(line))" in source
    # the register gate at enqueue (a speech must never queue)
    assert "if (!RegisterAdmits(line))\n        return;" in source
    # delivery-time ledger: the ring + fatigue RE-VETS guard the entry,
    # then the records + credence marks land
    assert "if (!pocketllm::RingAdmits(s.ring, entry.text))\n        return true;" in source
    assert "if (!pocketllm::FatigueAdmits(s.fatigue, entry.factKey))\n        return true;" in source
    assert "pocketllm::RingRemember(s.ring, entry.text);" in source
    assert "pocketllm::FatigueRecordTelling(s.fatigue, entry.factKey);" in source
    assert "pocketllm::MarkHeard(s.fatigue, other->GetGUIDLow(), entry.factKey);" in source
    assert "PlayerbotLlmFilters::RememberReply(entry.speakerGuid, entry.text);" in source
    # the interruption deferral requeues instead of dropping
    assert "entry.notBefore = now + 10;  // the player holds the channel" in source
    # ambient lines join the shared channel histories
    assert "PlayerbotLlmMemory::AppendTurn(entry.speakerGuid, channelKey, true," in source
    # one batch in flight at a time (serialized device + composer load),
    # and a spawn failure or a worker-body throw must not strand the
    # flag nor kill the world
    assert "BatchInFlight().store(true);" in source
    assert source.count("BatchInFlight().load()") >= 2
    assert "std::thread(RunDeviceBatch, job).detach();" in source
    assert source.count("catch (...)") == 4  # 2 spawns + 2 worker bodies
    # the drain cap (a deep queue never machine-guns the channel) and the
    # head-of-line scan (a deferred murmur head cannot block a due global)
    assert "for (auto itr = s.queue.begin(); itr != s.queue.end() && taken < 2;)" in source
    assert ("                if (itr->notBefore > now)\n"
            "                {\n"
            "                    ++itr;\n"
            "                    continue;\n"
            "                }" in source)
    # the party cadence law: the window stamps on the ROLL (not the win),
    # and the duel event note is CONSUMED only when its bark will really
    # dispatch (a held channel leaves it armed to retry - round-2 R1)
    assert "windowAt = now;  // the roll consumes the window, win or lose" in source
    assert "s.partyDuelNoteAt.erase(master->GetGUIDLow());" in source
    assert "if (duelNote && now - duelNote >= 60 && duelNote < now)" in source
    assert "if ((windowAt == 0 || now - windowAt >= 60) &&" in source
    # the composer script's speaker mapping (each turn voices its OWN
    # persona, never the wrong bot) and the multi-turn delivery cap
    assert "job.speakerGuids[script[i].speakerIdx]" in source
    assert "size_t const turnCap = std::min(script.size()," in source
    assert "(size_t)pocketllm::ChatterFatigue::kMaxFactTellings));" in source
    # turns of ONE exchange deliver IN ORDER (independent draws reorder
    # a reply ahead of its setup line ~1/3 of the time - round-3)
    assert "uint32 const perTurn = std::max<uint32>(6u," in source
    assert "base + (uint32)i * perTurn, base + (uint32)i * perTurn);" in source
    # a job with no fact rows never reaches the enqueue loop (the
    # factKeys clamp would underflow on an empty vector)
    assert "!job.factKeys.empty())" in source
    # murmur at NORMAL with a composer is a CLOUD script batch over the
    # nearby personas (per-bot device calls are the fallback)
    assert "job.layer = pocketllm::LAYER_MURMUR;" in source
    # the line-safety law covers the FLOOR paths too (the DB event text
    # can carry pipes/newlines past the write chain - round-2 R1/R6)
    assert "if (pocketllm::ChatterLineSafe(floorText))" in source
    assert "if (!pocketllm::ChatterLineSafe(headline))" in source
    # a stale power file flushes already-generated queue entries (the
    # plan's "generation stops; authored texture floor only")
    assert "if (!itr->floor)\n                itr = s.queue.erase(itr);" in source


def test_pure_core_doctrine_pins():
    core = CORE.read_text(encoding="utf-8")
    # the doctrine is stated where the next maintainer will read it
    assert "SILENCE IS THE DEFAULT STATE" in core
    # the wording lock is stated for the S11 P52 bank
    assert "FROZEN device-path prompt wording" in core
    # no game globals/DB/wall clock in the pure core (the purity comment
    # names them; the REAL signatures must not)
    for banned in ("PlayerbotAI.h", "WorldDatabase.", "sPlayerbotAIConfig",
                   "time(nullptr)", "#include \"playerbot"):
        assert banned not in core


def test_kotlin_emission_surface():
    policy = POLICY_KT.read_text(encoding="utf-8")
    assert 'AiPlayerbot.LLMChatterEnabled = 1' in policy
    assert 'AiPlayerbot.LLMChatterPowerFile = \\"$chatterPowerFile\\"' in policy
    assert "AiPlayerbot.LLMChatterComposerUrl = $composerEndpoint" in policy
    assert 'AiPlayerbot.LLMChatterEnabled = 0' in policy  # off is explicit
    # the two-directional toggle's heart: the power file is staged
    # whenever the LLM subsystem is enabled, carrying the live ambience
    # flag - the staging condition itself is load-bearing (round-2 R2/R3)
    runtime_files = (ROOT / "android" / "app" / "src" / "main" / "java" /
                     "com" / "pocketrealm" / "server" / "ServerRuntimeFiles.kt").read_text(encoding="utf-8")
    assert "val chatterPower = if (snapshot.llmEnabled)" in runtime_files
    assert "ChatterPowerMonitor.refreshOnce(appContext, enabled = snapshot.llmAmbience).absolutePath" in runtime_files
    monitor = MONITOR_KT.read_text(encoding="utf-8")
    # the rung constants mirror the native enum
    for line in ("RUNG_OFF = 0", "RUNG_EMERGENCY = 1", "RUNG_CRITICAL = 2",
                 "RUNG_CONSTRAINED = 3", "RUNG_NORMAL = 4"):
        assert line in monitor
    # the plan's named thermal gate is used
    assert "getThermalHeadroom" in monitor


def test_p52_wording_lock_module_is_fresh():
    """S11 binding condition (S10-ledger): the P52 bank trains the FROZEN
    S10 wording byte-exactly. banklib-adjacent bridge_wording.py is
    GENERATED from the C++ cores; this leg regenerates and diffs so any
    drift between the shipped wording and the authoring tree fails loud.
    Skips (with reason) on a machine without the G: authoring tree - the
    same hermeticity the suite gives every other banklib-dependent leg."""
    extractor = ROOT / "tools" / "llm_lab" / "extract_bridge_wording.py"
    if not extractor.is_file():
        pytest.skip("extractor not staged")
    if not Path(r"G:\NPU LLM\scripts\finetune").is_dir():
        pytest.skip("authoring tree (G:\\NPU LLM) not present on this machine")
    out = Path(r"G:\NPU LLM\scripts\finetune\bridge_wording.py")
    if not out.is_file():
        pytest.fail("bridge_wording.py missing - run the extractor once")
    r = subprocess.run([sys.executable, str(extractor), "--check"],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"wording-lock drift:\n{r.stdout}\n{r.stderr}"
