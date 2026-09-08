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

Round-2 QA pins (2026-09-08 live re-run at 77b06e6): every relay-reachable
native op must guard world readiness (a world-account* op against a
FAILED/STARTING world used to SIGSEGV :world inside account_info's empty
LoginDatabase pool - tombstone_02), the relay's binder cache must be
death-recipient backed and revalidated per op (a :world crash used to
wedge every later op on a dead proxy until world-kill's unbind), and
stack-up-bot must gate its db legs on the supervisor's DB-RECOVERY lane
instead of driving init/migrations/start over an unsealed generation.
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


# ---- relay-native readiness guards (round 2: the :world SIGSEGV) ------------
#
# The crash (logcat null-deref at WorldNative_accountInfoNative+49,
# tombstone_02): account_info has NO state guard, so a FAILED or still
# STARTING world - where LoginDatabase's query pool is empty - walked into
# Database::escape_string's m_pQueryConnections[0] (and PQuery's
# nCount % m_nQueryConnPoolSize) and killed the :world process. The fix
# guards the RUNTIME method, the one layer every caller crosses (the JNI
# op, verify_account_password, character_persistence, and the service's
# accountResult, which asks even when the op itself was refused). These
# pins sweep EVERY relay-reachable op: an absent-guard mutant dies here.

# (signature fragment, readiness guard, typed not-ready verdict or None,
#  first DB/world deref the guard must precede)
RELAY_NATIVE_GUARDS = [
    ("std::pair<uint32_t, int32_t> account_info(const std::string& username)",
     "if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)",
     "return {0, -1};", "LoginDatabase.escape_string"),
    ("bool verify_account_password(const std::string& username, const std::string& password)",
     "if (m_state.state() != POCKET_SERVER_READY",
     "return false;", "account_info(username)"),
    ("int create_account(const std::string& username, const std::string& password, uint64_t timeout_ms)",
     "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;",
     None, "issue_command"),
    ("int set_account_gmlevel(const std::string& username, int level, uint64_t timeout_ms)",
     "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;",
     None, "issue_command"),
    ("int save(uint64_t timeout_ms)",
     "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;",
     None, "issue_command"),
    ("std::string character_persistence(const std::string& username, const std::string& character_name)",
     "if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)",
     '\\"found\\":false,\\"reason\\":\\"world-not-ready\\"', "account_info(username)"),
    ("std::string world_chat(const std::string& character_name, const std::string& channel,",
     "if (m_state.state() != POCKET_SERVER_READY)",
     '\\"ok\\":false,\\"reason\\":\\"world-not-ready\\"', "m_chat_slot"),
    ("std::string reset_state(const std::string& player_name)",
     "if (m_state.state() != POCKET_SERVER_READY)",
     '\\"ok\\":false,\\"reason\\":\\"world-not-ready\\"', "CharacterDatabase"),
    ("std::string llm_memory_state(const std::string& player_name)",
     "if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)",
     '\\"ok\\":false,\\"reason\\":\\"world-not-ready\\"', "HashMapHolder"),
    ("std::string realm_info()",
     "if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)",
     '\\"found\\":false,\\"reason\\":\\"world-not-ready\\"', "LoginDatabase.Query"),
    ("uint32_t online_players()",
     "if (state != POCKET_SERVER_READY && state != POCKET_SERVER_SAVING) return 0;",
     "return 0;", "HashMapHolder<Player>::ReadGuard"),
]


def test_every_relay_native_op_guards_readiness_before_the_first_deref():
    for signature, guard, verdict, deref in RELAY_NATIVE_GUARDS:
        body = cpp_body(signature)
        assert guard in body, f"readiness guard missing from {signature.split('(')[0]}"
        assert deref in body, f"expected deref {deref} moved out of {signature.split('(')[0]}"
        assert body.index(guard) < body.index(deref), (
            f"{signature.split('(')[0]}: the guard must precede {deref}")
        if verdict is not None:
            assert verdict in body, f"typed verdict {verdict} missing from {signature.split('(')[0]}"


def test_status_only_touches_the_world_object_under_ready_or_saving():
    body = cpp_body("void status(pocket_server_status* out)")
    guard = "if (out->state == POCKET_SERVER_READY || out->state == POCKET_SERVER_SAVING)"
    assert guard in body
    assert body.index(guard) < body.index("sWorld.GetActiveSessionCount()")


def test_account_info_guard_sits_in_the_runtime_method_not_the_jni_boundary():
    source = WORLD_CPP.read_text(encoding="utf-8")
    # accountInfoNative stays a thin marshaller; the guard lives in the
    # runtime method so accountResult (which asks after a REFUSED create/
    # gm-level op too), verify_account_password and character_persistence
    # are all covered by the same line
    jni = source.split("Java_com_pocketrealm_server_WorldNative_accountInfoNative")[1]
    assert "g_runtime.account_info(from_jstring(env, username))" in jni
    body = cpp_body("std::pair<uint32_t, int32_t> account_info(const std::string& username)")
    assert ("if (m_state.state() != POCKET_SERVER_READY && "
            "m_state.state() != POCKET_SERVER_SAVING)") in body


# ---- death-recipient-backed binder cache (round 2: the stale :world proxy) --


def test_cached_binders_are_death_recipient_backed_and_ping_revalidated():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # linkToDeath flips the liveness flag the moment the owning process
    # dies (crash, killForTest, clean-stop retire); close() unlinks so a
    # recycled connection can never fire into a dropped cache
    assert "binder.linkToDeath(deathRecipient, 0)" in relay
    assert "binder.unlinkToDeath(deathRecipient, 0)" in relay
    assert "IBinder.DeathRecipient { dead = true }" in relay
    # per-op revalidation is a real binder round trip, not a cached flag
    assert "fun alive(): Boolean = !dead && binder.pingBinder()" in relay
    # every accessor revalidates before use: a dead cache is closed and
    # rebound, so the op after a :world crash talks to the fresh process
    # instead of wedging on a dead proxy until world-kill's unbind
    for accessor in ("private fun db(): IDatabaseControl =",
                     "private fun realmApi(): IRealmControl =",
                     "private fun worldApi(): IWorldControl ="):
        segment = relay.split(accessor)[1].split("\n\n")[0]
        assert "rebind(" in segment, f"{accessor} no longer revalidates its cache"


def test_a_mid_op_component_death_answers_not_ready_and_drops_the_cache():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # the race pingBinder cannot close (death between the check and the
    # call) is answered with the branchable errorClass the services'
    # guarded() convention uses, never a bare DeadObjectException string
    assert "if (failure is DeadObjectException && deadComponents.isNotEmpty())" in relay
    assert 'deadComponents.first() + "_NOT_READY"' in relay
    # every dead cache is closed and dropped so the next op rebinds
    assert "world?.close(); world = null; deadComponents.add(\"WORLD\")" in relay
    assert "remedy" in relay


# ---- stack-up-bot DB-RECOVERY gate (round 2: the dirty-DB composite) --------


def test_stack_up_bot_gates_its_db_legs_on_the_supervisor_recovery_lane():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # the composite reuses the supervisor's own DB-RECOVERY lane - the same
    # DurableRuntimeSupervisor.recover() an app-led Start realm runs -
    # over its Binder; the heal is never re-implemented in the relay
    assert '"com.pocketrealm.service.RealmService"' in relay
    assert "IRuntimeSupervisorControl.Stub.asInterface(it)" in relay
    assert "supervisor.api.recover()" in relay
    stack = relay.split("private fun stackUpBot")[1].split("\n    }")[0]
    # the gate runs BEFORE any db leg is driven...
    assert stack.index("healDatabaseThroughSupervisor()") < stack.index("val database = db()")
    # ...and a failed gate fails fast with the DB_REVISION errorClass and
    # the sanctioned remedy string instead of driving legs over an
    # unsealed generation (the dbMigrations/dbStart:false -> world
    # DB_REVISION failure QA hit)
    assert '.put("errorClass", "DB_REVISION")' in stack
    assert "one app-led Start" in stack


def test_the_gate_reads_heal_verdicts_from_the_supervisor_journal():
    relay = RELAY_KT.read_text(encoding="utf-8")
    heal = relay.split("private fun healDatabaseThroughSupervisor")[1].split("\n    }")[0]
    # healed = clean journal on a settled phase (STOPPED after recovery,
    # UNCONFIGURED/ERROR on a never-started one); RECOVERING is polled,
    # a busy coordinator retried - the journal's own fields are the truth
    assert 'val settled = phase in setOf("STOPPED", "UNCONFIGURED", "ERROR")' in heal
    assert "if (settled && status.optBoolean(\"clean\"))" in heal
    assert 'status.optString("lastDurableAction")' in heal
    assert 'status.optString("lastError")' in heal


def test_the_gate_budget_fits_the_host_console_slow_op_window():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # 420 s of recovery budget leaves headroom for the db/realm/world legs
    # inside the 900 s the host console waits for stack-up-bot
    assert "SUPERVISOR_RECOVERY_TIMEOUT_MS = 420_000L" in relay
    console = (ROOT / "tools" / "world_console.py").read_text(encoding="utf-8")
    assert 'SLOW_OPS = {"stack-up-bot"' in console
    assert "timeout_s = 900 if op in SLOW_OPS else 120" in console
