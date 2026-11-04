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

Relay-contract pins: every relay-reachable
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
DESKTOP_BACKEND_KT = (ROOT / "desktop" / "src" / "main" / "kotlin" /
                      "com" / "pocketrealm" / "desktop" / "DesktopRuntimeBackend.kt")
CLIENT_FLAVOR_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
                    "com" / "pocketrealm" / "client" / "ClientFlavor.kt")
WORLD_NATIVE_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
                   "com" / "pocketrealm" / "server" / "WorldNative.kt")
# The stack-up-bot composite core (sequence, per-stage windows, total
# deadline, named answers, default profile) was extracted from the relay
# into main sources as StackUpComposite (pure Kotlin + org.json) so the
# host JVM unit battery can pin the deadline/timing logic (defect
# stackup-wedge / LVF-server-db-014); the console relay (androidTest)
# supplies the real Binder legs and the bounded-call abandonment. The
# composite-honesty pins below read BOTH: the core for the contract, the
# relay for the wiring.
STACKUP_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
              "com" / "pocketrealm" / "database" / "StackUpComposite.kt")


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
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    # all-legs-success shape unchanged: the same leg keys are fired, and ok
    # stays the AND of exactly those leg booleans - now in the extracted
    # composite core (StackUpComposite) the relay drives
    run_body = stackup.split("fun run(profileIdArg: String): JSONObject {")[1]
    for leg in ('"dbInit"', '"dbMigrations"', '"dbStart"',
                '"dbHealth"', '"realmStart"', '"worldStartBotProfile"'):
        assert f"fire(out, {leg}," in run_body, f"{leg} missing from the stack-up-bot composite"
    assert ('out.put("ok", out.optBoolean("dbInit") && out.optBoolean("dbMigrations")\n'
            "            && out.optBoolean(\"dbStart\") && out.optBoolean(\"dbHealth\")\n"
            "            && out.optBoolean(\"realmStart\") && out.optBoolean(\"worldStartBotProfile\"))"
            ) in stackup
    # the relay wires the composite over the REAL binder legs and runs it
    # under the composite-level abandonment net
    wiring = relay.split("private fun stackUpBot")[1].split("\n    }")[0]
    assert "StackUpComposite(" in wiring
    assert "composite.run(profileIdArg)" in wiring
    assert 'override fun worldStartBotProfile(profileId: String): String =' in wiring
    # the world leg still rides the real binder start (whose rc is the honest
    # verdict), now guarded by the DB-lane precondition and bounded like
    # every composite stage
    assert ('"world-start-bot" -> worldBootRefusal("world-start-bot", databaseStatus())\n'
            "                ?: boundWorldStart(\"world-start-bot\") { worldApi().startBotProfile(arg1) }"
            ) in relay
    assert ('"world-start" -> worldBootRefusal("world-start", databaseStatus())\n'
            "                ?: boundWorldStart(\"world-start\") { worldApi().start() }") in relay


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


# relay-native readiness guards ------------
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
    # create_account no longer rides issue_command — the
    # credential must never enter the echoed console lane — so its first
    # world deref is the direct non-echoed AccountMgr write
    ("int create_account(const std::string& username, const std::string& password, uint64_t timeout_ms)",
     "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;",
     None, "sAccountMgr.CreateAccount"),
    ("int set_account_gmlevel(const std::string& username, int level, uint64_t timeout_ms)",
     "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;",
     None, "issue_command"),
    ("int set_account_addon(const std::string& username, int expansion, uint64_t timeout_ms)",
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


# death-recipient-backed binder cache --


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
    # call) is answered with the branchable errorClass
    # the services' guarded() convention uses, never a bare DeadObjectException string
    assert "if (failure is DeadObjectException && deadComponents.isNotEmpty())" in relay
    assert 'deadComponents.first() + "_NOT_READY"' in relay
    # every dead cache is closed and dropped so the next op rebinds
    assert "world?.close(); world = null; deadComponents.add(\"WORLD\")" in relay
    assert "remedy" in relay


# stack-up-bot DB-RECOVERY gate --------


def test_stack_up_bot_gates_its_db_legs_on_the_supervisor_recovery_lane():
    relay = RELAY_KT.read_text(encoding="utf-8")
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    # the composite reuses the supervisor's own DB-RECOVERY lane - the same
    # DurableRuntimeSupervisor.recover() an app-led Start realm runs -
    # over its Binder; the heal is never re-implemented in the relay
    assert '"com.pocketrealm.service.RealmService"' in relay
    assert "IRuntimeSupervisorControl.Stub.asInterface(it)" in relay
    assert "supervisor.api.recover()" in relay
    run_body = stackup.split("fun run(profileIdArg: String): JSONObject {")[1]
    # the gate runs BEFORE any db leg is driven... (the composite core owns
    # the ordering; the relay's Legs.dbRecoveryGate drives the heal)
    assert run_body.index('fire(out, "dbRecovery"') < run_body.index('fire(out, "dbInit"')
    wiring = relay.split("private fun stackUpBot")[1].split("\n    }")[0]
    assert "healDatabaseThroughSupervisor().toString()" in wiring
    # ...and a failed gate fails fast with the DB_REVISION errorClass and
    # the sanctioned remedy string instead of driving legs over an
    # unsealed generation (the dbMigrations/dbStart:false -> world
    # DB_REVISION failure QA hit)
    assert '.put("errorClass", "DB_REVISION")' in run_body
    assert "one app-led Start" in run_body


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
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    # 420 s of recovery budget leaves headroom for the db/realm/world legs
    # inside the 900 s the host console waits for stack-up-bot
    assert "SUPERVISOR_RECOVERY_TIMEOUT_MS = 420_000L" in stackup
    # the composite budget itself holds the WHOLE op inside that window
    # with reserve - and the designed stage windows sum WORSE than the
    # window, which is exactly why the budget (not the sum) must bind
    assert "STACKUP_OP_BUDGET_MS = 840_000L" in stackup
    assert "val designedWorstCaseMs: Long = SUPERVISOR_RECOVERY_TIMEOUT_MS +" in stackup
    console = (ROOT / "tools" / "world_console.py").read_text(encoding="utf-8")
    assert 'SLOW_OPS = {"stack-up-bot"' in console
    assert "timeout_s = 900 if op in SLOW_OPS else 120" in console


# stack-up-bot engine-marker gate + legs verdict ------------
#
# A power-loss/emulator kill DURING a relay composite leaves the supervisor
# journal STOPPED-clean (composites are not journaled) while the mariadb
# engine died unsealed: db-status answers cleanMarker:false and every driven
# db leg fails closed - yet the gate answered dbRecovery:true
# dbRecoveryDetail:"none-needed" with no errorClass or remedy. The gate now
# verifies the ENGINE's own marker as well, and a failed leg can never answer
# a bare ok:false.


def test_the_gate_verifies_the_engine_marker_not_only_the_journal():
    relay = RELAY_KT.read_text(encoding="utf-8")
    heal = relay.split("private fun healDatabaseThroughSupervisor")[1].split("\n    }")[0]
    # a clean settled journal alone no longer answers: the verdict routes
    # through the engine-marker check, and the "none-needed" label only
    # survives as that check's clean-journal argument
    assert "return verifyEngineSealedOrHeal(" in heal
    assert '"none-needed")' in heal
    engine = relay.split("private fun verifyEngineSealedOrHeal")[1].split("\n    }")[0]
    # the engine's own field (db-status cleanMarker) is the truth...
    assert 'engine.optBoolean("cleanMarker")' in engine
    # ...an unsealed generation is healed with the engine's recover verb -
    # the same RECOVER_DIRTY_GENERATION leg the supervisor's prepare lane
    # drives on every DATABASE start, never the init/migrations/start legs -
    # and the marker is re-verified afterwards
    assert "JSONObject(db().recover())" in engine
    assert 'JSONObject(db().status()).optBoolean("cleanMarker")' in engine


def test_a_failed_leg_carries_the_gate_error_class_and_remedy():
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    run_body = stackup.split("fun run(profileIdArg: String): JSONObject {")[1]
    # never a bare ok:false: the legs failure carries errorClass DB_REVISION
    # and the SAME remedy string as the gate path, with the error naming the
    # failed legs (the live QA miss: dbMigrations/dbStart false, no class)
    assert "if (!out.optBoolean(\"ok\"))" in run_body
    assert '.put("errorClass", "DB_REVISION")' in run_body
    # the gate-path refusal and the legs-failure refusal both carry the
    # sanctioned remedy (the exact phrase may split across Kotlin string
    # literals, so pin its distinctive halves)
    assert "remedy: one app-led Start " in run_body.replace('" +\n', '').replace('" + ', '')
    assert '"legs failed: ${failedLegs.joinToString(",")}; "' in run_body
    assert '"dbInit", "dbMigrations", "dbStart", "dbHealth",' in run_body
    assert "filterNot" in run_body


# bounded stages + the world-start DB precondition -----
#
# The recorded row LV-server-db-09 session: world-start drove a native boot
# into a STOPPED db lane and never answered its 900s window, and stack-up-bot
# sat >25 silent minutes on a stage whose synchronous binder call never
# returned - the composite's own fail-fast contract never fired. The relay
# now proves the world boot's DB-lane precondition before firing any native
# boot, drives every composite stage on a worker bounded by its designed
# window, and answers a typed <COMPONENT>_STAGE_TIMEOUT naming the refusing
# component instead of hanging.


def test_world_start_proves_the_db_lane_precondition_before_any_boot():
    relay = RELAY_KT.read_text(encoding="utf-8")
    # both world boot ops read the SAME bounded status read and refuse a
    # stopped/unanswering lane before any native boot is fired
    assert '"world-start" -> worldBootRefusal("world-start", databaseStatus())' in relay
    assert '"world-start-bot" -> worldBootRefusal("world-start-bot", databaseStatus())' in relay
    refusal = relay.split("internal fun worldBootRefusal")[1].split("\n    }")[0]
    # the running lane passes; a stopped lane is typed DB_NOT_RUNNING (the
    # engine's own "database is not running" refusal family), an unanswering
    # lane DATABASE_NOT_READY - and both name the sanctioned boot path
    assert 'if (status?.optString("state") == "RUNNING") return null' in refusal
    assert '"DB_NOT_RUNNING"' in refusal
    assert '"DATABASE_NOT_READY"' in refusal
    assert "run stack-up-bot" in refusal


def test_every_composite_stage_is_bounded_by_a_designed_window():
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    run_body = stackup.split("fun run(profileIdArg: String): JSONObject {")[1]
    # whitespace-normalized so a Kotlin line-wrap never breaks the pin
    norm = " ".join(run_body.split())
    # the supervisor gate stage rides the same bounded fire()...
    assert 'fire(out, "dbRecovery", "SUPERVISOR",' in norm
    # ...and every leg either answers its designed window (capped by the
    # budget remainder) or fails the op naming the refusing component - no
    # unbounded synchronous binder call remains
    for leg, component, window in (
            ("dbInit", "DATABASE", "DB_INIT_STAGE_WINDOW_MS"),
            ("dbMigrations", "DATABASE", "DB_MIGRATIONS_STAGE_WINDOW_MS"),
            ("dbStart", "DATABASE", "DB_START_STAGE_WINDOW_MS"),
            ("dbHealth", "DATABASE", "DB_HEALTH_STAGE_WINDOW_MS"),
            ("realmStart", "REALM", "REALM_START_STAGE_WINDOW_MS"),
            ("worldStartBotProfile", "WORLD", "WORLD_START_STAGE_WINDOW_MS")):
        assert f'fire(out, "{leg}", "{component}", {window}' in norm, \
            f"{leg} has no stage bound"
        assert f"const val {window}" in stackup, f"{window} window constant missing"
    # the design window is capped by the op budget's remainder inside fire()
    fire_body = stackup.split("private fun fire(")[1].split("\n    }")[0]
    assert "coerceAtMost((opDeadlineMs - nowMs()).coerceAtLeast(0L))" in fire_body
    # the composite budget holds the whole op inside the host console's 900s
    # slow-op window, with reserve for the answer's file round-trip
    assert "STACKUP_OP_BUDGET_MS = 840_000L" in stackup
    console = (ROOT / "tools" / "world_console.py").read_text(encoding="utf-8")
    assert "timeout_s = 900 if op in SLOW_OPS else 120" in console


def test_a_stage_timeout_answers_typed_naming_the_refusing_component():
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    timeout = stackup.split("fun stageTimeoutResponse(")[1].split(
        "): JSONObject = JSONObject()")[1]
    assert '"${component}_STAGE_TIMEOUT"' in timeout
    assert '"timedOutStage", leg' in timeout
    assert '"stageWindowMs", windowMs' in timeout
    # never a bare hang or a bare ok:false: the error names the leg, the
    # component, and the window that was missed
    assert '"$leg (component $component) did not answer within its "' in timeout
    # the relay shares the SAME shape (its delegate keeps one source of
    # truth for the world-boot guard and the instrumentation tests)
    relay = RELAY_KT.read_text(encoding="utf-8")
    assert "): JSONObject = StackUpComposite.stageTimeoutResponse(op, leg, component, windowMs)" \
        in relay


# unsealed-generation preparation routing ( the never/pre-partially-
# initialized install) ----
#
# The gate's re-run on the row -09 lane (install-after-uninstall, so the
# engine generation was NEVER initialized) fail-fasted honestly but still
# refused to boot: the gate saw a clean journal + cleanMarker:false and
# routed to the ENGINE's recover verb, which refuses a never-initialized
# generation (dirtyRecoveryPermitted requires initialized) AND an
# interrupted INIT/MIGRATION transaction (it refuses any pending
# transaction). Production routing (DatabaseStartPreparation) sends those
# states to INITIALIZE / transaction-resume / APPLY_PINNED_MIGRATIONS - and
# those are exactly the composite's own bounded db legs, whose engine-side
# guards own the resume. Only the unsealed-initialized-no-transaction state
# has a recover verb that will take it: that one alone goes to the engine
# half. A bare stack-up-bot also resolves to the console tool's documented
# default profile.


def test_the_gate_admits_an_unsealed_generation_to_the_legs_it_can_resume():
    relay = RELAY_KT.read_text(encoding="utf-8")
    heal = relay.split("private fun healDatabaseThroughSupervisor")[1].split("\n    }")[0]
    # the clean-journal branch reads the ENGINE half before answering...
    assert "val engine = JSONObject(db().status())" in heal
    # ...and routes by recover eligibility (the dirtyRecoveryPermitted
    # truth table): recover verb only for initialized+unpending; every
    # other unsealed state is admitted to the composite's bounded legs
    assert ('val recoverEligible = engine.optBoolean("initialized") &&\n'
            '                            !engine.optBoolean("databaseTransactionPending")') in heal
    assert "fresh-prepare (unsealed generation admitted" in heal
    # the initialized-but-unsealed dirty case still takes the engine's
    # own recover verb inside the engine half, unchanged
    engine = relay.split("private fun verifyEngineSealedOrHeal")[1].split("\n    }")[0]
    assert "JSONObject(db().recover())" in engine


def test_a_bare_stack_up_bot_resolves_the_documented_default_profile():
    stackup = STACKUP_KT.read_text(encoding="utf-8")
    run_body = stackup.split("fun run(profileIdArg: String): JSONObject {")[1]
    # the REPL substitutes mobile-balanced-b100-v1 for a bare stack-up-bot
    # (world_console.py:146-148); the composite owns the same default so the
    # evidence driver's bare op gets the documented profile too
    assert "profileIdArg.ifEmpty { DEFAULT_BOT_PROFILE }" in run_body
    assert 'DEFAULT_BOT_PROFILE = "mobile-balanced-b100-v1"' in stackup


# the account-expansion relay op -------------------
#
# tbc/wotlk account rows are born with the seed's expansion default (0)
# and the core evaluates expansion gating per account row, so a flavor
# account cannot use expansion content until the core's own
# `account set addon <name> <0|1|2>` verb runs (Chat.cpp:73 registers it
# SEC_ADMINISTRATOR with console allowed, so the SEC_CONSOLE
# issue_command lane reaches it; HandleAccountSetAddonCommand performs
# the UPDATE account SET expansion write). The op is typed exactly like
# set_account_gmlevel, and EVERY account-creation wiring (Android world
# service, desktop provisionAccount, RpHost's account op) chains it after
# a successful create with the ACTIVE flavor's registry-resolved level —
# never a per-callsite literal, and never silently skipped on failure.


def test_set_account_addon_is_gmlevel_shaped_with_the_expansion_domain():
    body = cpp_body(
        "int set_account_addon(const std::string& username, int expansion, uint64_t timeout_ms)")
    # wrong state: the sibling READY guard, before any command is issued
    assert "if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;" in body
    # invalid argument: the account-token rules plus the 0..2 expansion
    # domain (0 classic / 1 tbc / 2 wotlk) — anything else is refused
    # before a console command exists
    assert "username.empty() || username.size() > 16 || expansion < 0 || expansion > 2" in body
    assert 'return std::isalnum(c);' in body
    # the verb carries the RESOLVED numeric account id —
    # ExtractAccountId's numeric branch reinterprets a digit-string
    # argument as an ACCOUNT ID, so the raw username never enters the CLI
    # string (a digits-named guest was rejected forever on an expansion
    # lane, or another row received the write on an id collision)
    assert "account_info(username)" in body
    assert '"account set addon " + std::to_string(account.first) +' in body
    assert '" " + std::to_string(expansion);' in body
    assert "if (account.first == 0) return POCKET_SERVER_ACCOUNT_REJECTED" in body
    assert "issue_command(command, timeout_ms, nullptr)" in body
    # same verdict mapping as set_account_gmlevel: OK, TIMEOUT verbatim,
    # everything else ACCOUNT_REJECTED
    assert ("return result == POCKET_SERVER_OK ? result :\n"
            "            (result == POCKET_SERVER_TIMEOUT ? result : POCKET_SERVER_ACCOUNT_REJECTED);"
            ) in body
    # already-set is NOT an error case: unlike create_account there is no
    # already/exist output scan — the underlying verb is an idempotent
    # UPDATE, so re-running the op on an already-correct row stays OK
    assert "already" not in body and "exist" not in body


def test_set_account_addon_rides_the_jni_boundary_like_its_sibling():
    source = WORLD_CPP.read_text(encoding="utf-8")
    jni = source.split("Java_com_pocketrealm_server_WorldNative_setAccountAddonNative")[1]
    assert ("g_runtime.set_account_addon(from_jstring(env, username), "
            "static_cast<int>(expansion),") in jni
    assert "static_cast<uint64_t>(std::max<jlong>(0, timeout_ms))" in jni
    # the Kotlin shim declares the same surface (the build lane's
    # verify_jni_exports pins every external fun to the DLL's export
    # table, so the two can never drift silently)
    shim = WORLD_NATIVE_KT.read_text(encoding="utf-8")
    assert ("external fun setAccountAddonNative("
            "username: String, expansion: Int, timeoutMs: Long): Int") in shim


def test_the_flavor_registry_carries_the_account_expansion_levels():
    source = CLIENT_FLAVOR_KT.read_text(encoding="utf-8")
    prop = source.split("val accountExpansion: Int")[1].split("\n    }")[0]
    # the core's expansion domain, resolved from the server family so a
    # new flavor fails loud until registered — never a callsite literal
    for family, level in (('"classic" -> 0', 0), ('"tbc" -> 1', 1), ('"wotlk" -> 2', 2)):
        assert family in prop, f"{family} missing from accountExpansion"
    assert 'else -> error("server family' in prop
    # and the resolver is the registry's own per-family data
    assert "when (serverFamily)" in prop


def test_android_create_account_chains_the_flavor_expansion_write():
    service = SERVICE_KT.read_text(encoding="utf-8")
    create = service.split(
        "override fun createAccount(username: String, password: String) = guarded {",
    )[1].split("\n        }")[0]
    # the create still runs first, unchanged
    assert "WorldNative.createAccountNative(username, password," in create
    # the expansion write is chained on the accepted shapes: a successful
    # create AND (R2-1) the password-proven ACCOUNT_EXISTS
    # retry leg
    assert "ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven" in create
    assert "val addonRc = if (ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven) {" in create
    assert "WorldNative.setAccountAddonNative(" in create
    # R1-arch-5/R2-2 + R2-1 (android-core review rounds 1-2): the expansion
    # resolves through the :world persisted-Settings lane (the holder's
    # selection is :ui-local), and the ACCOUNT_EXISTS leg is gated by the
    # PASSWORD PROOF (the retry-vs-collision discriminator — a mismatched
    # credential is a collision and is refused without any addon write).
    # the expansion comes from the BOOT lane (pinned
    # by the start leg from its own snapshot) — a Home switch after start
    # must never re-lane the write.
    assert (
        "(bootLane\n                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion"
        in create
    )
    assert "ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven" in create
    assert "WorldNative.verifyAccountPasswordNative(username, password)" in create
    assert '"ACCOUNT_PASSWORD_MISMATCH"' in create
    assert 'private const val ACCOUNT_EXPANSION_LANE =' in service
    # fail-loud: a refused write flips the answer to the addon op's own
    # error code (never a silently classic tbc/wotlk account); a refused
    # or duplicate create skips the write and keeps the create verdict
    assert 'accountResult("create-account", username, addonRc ?: rc)' in create
    assert ('"addonCode", addonRc?.let { ServerRuntimeContract.errorName(it.toLong()) } '
            '?: "SKIPPED"') in create
    assert '.put("expansion", expansion)' in create


def test_desktop_provision_chains_the_flavor_expansion_write():
    backend = DESKTOP_BACKEND_KT.read_text(encoding="utf-8")
    # the fresh-create leg: between the rc != 0 refusal arm and the final
    # accountInfo read, before the gmlevel raise
    leg = backend.split(
        "rc != 0 -> AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(rc.toLong()))",
    )[1].split("val info = WorldNative.accountInfoNative(username)")[0]
    assert (
        "(bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion" in leg
    ), "the provision seam must consume the pinned BOOT lane (R1-r2-1)"
    assert "WorldNative.setAccountAddonNative(" in leg
    assert leg.index("setAccountAddonNative") < leg.index("setAccountGmLevelNative")
    assert "EXPANSION-SET-FAILED" in leg
    assert 'code = ServerRuntimeContract.errorName(addon.toLong())' in leg


def test_both_conf_emissions_resolve_through_the_active_flavor_seam():
    """R5-2 (code-red round 1): the PocketRealm.TimeframeStage conf of a
    booting world must resolve through TimeframeStages.confValue - the
    ACTIVE-flavor-aware entry (confValueFor) - in BOTH platform twins, so a
    multi-qualified world never reads a foreign lane's stage id."""
    android_twin = (
        ROOT / "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt"
    ).read_text(encoding="utf-8")
    desktop_twin = (
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt"
    ).read_text(encoding="utf-8")
    # the ANDROID twin runs in
    # :world, where the ActiveFlavor holder's selection never crosses the
    # process boundary — its conf must resolve through the PERSISTED
    # Settings machine (worldLaneFlavor, the D62 form), not the bare
    # confValue holder helper. The desktop twin is single-process: its
    # in-memory confValue use is legitimate.
    assert (
        "TimeframeStages.confValueFor(" in android_twin
        and "worldLaneFlavor(" in android_twin
    ), (
        "android twin's TimeframeStage conf must resolve through the "
        "persisted-selection machine (worldLaneFlavor + confValueFor)"
    )
    # the desktop start pins bootLane at
    # the transition and its conf resolves through confValueFor on THAT
    # lane — the single-process rationale held only while the holder could
    # never race a start; the stage leg now binds the boot lane like the
    # android twin (DesktopBootLaneBindingTest pins the backend legs).
    assert (
        "TimeframeStages.confValueFor(snapshot.timeframeStage, lane.flavorId)"
        in desktop_twin
        and "worldLaneFlavor(" in desktop_twin
    ), (
        "desktop twin's TimeframeStage conf must resolve through "
        "confValueFor on the PINNED start lane (worldLaneFlavor)"
    )
    # the active-aware core itself: confValue resolves the ACTIVE flavor,
    # never a hard-coded vanilla fallback
    registry = CLIENT_FLAVOR_KT.with_name("TimeframeStage.kt").read_text(encoding="utf-8")
    assert "fun confValue(stored: String): String =" in registry
    assert "confValueFor(stored, ActiveFlavor.resolve().flavorId)" in registry
    assert "defaultStageId(ClientFlavors.VANILLA.flavorId)" not in registry
