"""Composite-honesty pins for the relay's stack-up-bot lane.

A live QA session found the composite reporting partial success over a
broken foundation: with the DB legs failed closed it still showed
realmStart/worldStartBotProfile true and a READY world with the bot lane
dark. world_runtime.cpp now (a) waits for the boot's early legs to settle
and returns the worker's real error code on FAILED instead of a blanket
spawn-OK, and (b) fails a bot-profile start whose conf did not arm the
playerbot lane instead of coming up READY bots-off. These pins hold that
contract textually (the test_rp_harness.py pattern - the host cannot
drive the native boot) and pin that the all-legs-success response shape
is unchanged: the relay still ANDs the same leg booleans, so only a leg
that actually failed can flip ok to false.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELAY_KT = (ROOT / "android" / "app" / "src" / "androidTest" / "java" /
            "com" / "pocketrealm" / "database" / "WorldConsoleRelay.kt")
SERVICE_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
              "com" / "pocketrealm" / "server" / "WorldRuntimeService.kt")
STATUS_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
             "com" / "pocketrealm" / "server" / "ServerStatusJson.kt")
CONTRACT_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
               "com" / "pocketrealm" / "server" / "ServerRuntimeContract.kt")
WORLD_CPP = ROOT / "native" / "realm-runtime" / "src" / "world_runtime.cpp"


def cpp_body(signature: str) -> str:
    source = WORLD_CPP.read_text(encoding="utf-8")
    assert signature in source, f"{signature} missing from world_runtime.cpp"
    return source.split(signature)[1].split("\n    }")[0]


# ---- the settle-aware start verdict ----------------------------------------


def test_start_waits_for_the_boot_verdict_outside_the_lifecycle_lock():
    body = cpp_body("int start(const std::string& config)")
    # the spawn itself stays under the lifecycle lock (unchanged reset path)
    assert "std::unique_lock<std::mutex> guard(m_lifecycle);" in body
    # ...but the wait releases it so stop() and a second start() stay live
    assert "guard.unlock();" in body
    assert body.index("m_worker = std::thread") < body.index("guard.unlock();")
    # the wait: bounded, polling STARTING only (stop()'s own deadline idiom)
    assert "START_VERDICT_TIMEOUT_MS" in body
    assert "while (m_state.state() == POCKET_SERVER_STARTING &&" in body
    assert "pocket_server::monotonic_ms() < deadline)" in body


def test_failed_boots_return_the_worker_error_and_success_is_unchanged():
    body = cpp_body("int start(const std::string& config)")
    # a settled failure reports the leg that failed (DB_REVISION, CONFIG...)
    assert ("if (m_state.state() == POCKET_SERVER_FAILED)\n"
            "            return m_state.error();") in body
    # every other outcome keeps the exact old contract: OK (READY, or still
    # STARTING at the deadline -> the caller's async status polling)
    assert body.count("return POCKET_SERVER_OK;") == 1
    assert body.index("return m_state.error();") < body.index("return POCKET_SERVER_OK;")


def test_settle_deadline_is_bounded_below_the_kotlin_control_timeout():
    source = WORLD_CPP.read_text(encoding="utf-8")
    assert "static constexpr uint64_t START_VERDICT_TIMEOUT_MS = 30'000;" in source


# ---- the bot-lane arming guard ---------------------------------------------


def test_bot_profile_start_fails_loud_when_the_lane_does_not_arm():
    run = cpp_body("void run(const std::string& config)")
    # the guard is the else-arm of the arming branch, inside the same
    # ENABLE_PLAYERBOTS block, keyed on the bot-profile discriminator:
    # only a bot-profile conf carries a nonzero PocketRealm.BotTarget
    arming = run.split("if (sPlayerbotAIConfig.enabled)")[1].split("#endif")[0]
    assert "else if (configured_bot_target > 0)" in arming
    assert 'fail(POCKET_SERVER_CONFIG,' in arming
    assert '"bot profile start did not arm the playerbot lane")' in arming
    # the early-fail path tears down like every other boot failure
    assert "cleanup();  // early fails skipped teardown" in arming
    # the enabled branch itself is unchanged (bounds check + lane arming)
    enabled = run.split("if (sPlayerbotAIConfig.enabled)")[1]
    assert '"bot target is outside the measured profile bounds"' in enabled
    assert "m_bot_enabled.store(true, std::memory_order_release);" in enabled


# ---- the truth fields still flow -------------------------------------------


def test_playerbots_enabled_and_bots_online_come_from_the_live_lane_state():
    bot = cpp_body("void bot_status(jlong* values)")
    assert "values[2] = m_bot_enabled.load(std::memory_order_acquire) ? 1 : 0;" in bot
    assert "values[4] = m_bots_online.load(std::memory_order_acquire);" in bot
    service = SERVICE_KT.read_text(encoding="utf-8")
    assert '.put("playerbotsEnabled", bot[2] != 0L)' in service
    assert '.put("botsOnline", bot[4])' in service


# ---- the composite shape: honest on failure, identical on success ----------


def test_relay_composite_ok_is_the_and_of_the_leg_verdicts():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # all-legs-success shape unchanged: the same leg keys, the same AND
    for leg in ('out.put("dbInit"', 'out.put("dbMigrations"', 'out.put("dbStart"',
                'out.put("dbHealth"', 'out.put("realmStart"'):
        assert leg in relay, f"{leg} missing from the stack-up-bot composite"
    assert 'out.put("worldStartBotProfile", worldStart.optBoolean("ok"))' in relay
    assert ('out.put("ok", out.optBoolean("dbStart") && out.optBoolean("realmStart")\n'
            "            && worldStart.optBoolean(\"ok\"))") in relay
    # the world leg rides the binder start whose rc is now the honest verdict
    assert '"world-start-bot" -> passthrough(op) { worldApi().startBotProfile(arg1) }' in relay


def test_a_failed_world_leg_names_the_leg_through_the_operation_error():
    service = SERVICE_KT.read_text(encoding="utf-8")
    assert 'ServerStatusJson.operation("world", "start-bot-profile", rc)' in service
    status = STATUS_KT.read_text(encoding="utf-8")
    assert '.put("ok", result == 0)' in status
    assert '.put("error", ServerRuntimeContract.errorName(result.toLong()))' in status
    contract = CONTRACT_KT.read_text(encoding="utf-8")
    # the settle verdict maps onto these names: a failed DB foundation
    # answers ok:false with error DB_CONNECT/DB_REVISION, an unarmed bot
    # profile with CONFIG - the errorClass names the failed leg
    for name in ('"DB_CONNECT"', '"DB_REVISION"', '"CONFIG"', '"DATA_MISSING"',
                 '"PORT_IN_USE"'):
        assert name in contract
