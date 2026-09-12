"""Honest-READY pins for the boot-race login crash.

A live desktop-harness session found that logging a player in while the
world was between the listener bind and the world loop's first completed
tick fail-fasted the whole process (0xC0000409, three reproductions),
each crash inside a deferred world-thread boot leg. The old contract
reported READY right after StartNetworkEmbedded - before the loop had
ticked once - so every consumer that obeys READY (the app play flow,
the harness) could put a player session into that window, and the
listener itself opened before any tick, so even a non-obeying connector
could.

The contract now has two layers on one flag (m_first_tick_done, set by
record_tick - the world thread's own per-tick hook - after the first
completed World::Update):

1. Master::StartNetworkEmbedded (the honest-LISTENER gate) waits for the
   first tick BEFORE flipping the realmlist row online, starting the
   queue threads, or binding the socket - a client that never looks at
   READY cannot even connect into the window.
2. run() waits again before transitioning READY (a boot whose loop never
   ticks fails honestly at the deadline instead of sitting STARTING or
   lying READY), re-checks STARTING immediately before the transition,
   and stop()'s FAILED branches join the worker bounded (a worker hung
   inside cleanup()'s world-thread join must not seize m_lifecycle
   forever).

The behavioral regression is tools/rp_harness/desktop_boots.py: N
consecutive boots that drive the full protocol login the moment READY
flips. These pins hold the native contract textually (the
test_rp_harness.py pattern - the host cannot drive the native boot).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORLD_CPP = ROOT / "native" / "realm-runtime" / "src" / "world_runtime.cpp"
MASTER_DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
BOOTS_PY = ROOT / "tools" / "rp_harness" / "desktop_boots.py"
PLAY_PY = ROOT / "tools" / "rp_harness" / "desktop_play.py"


def cpp_run_body() -> str:
    source = WORLD_CPP.read_text(encoding="utf-8")
    assert source, f"{WORLD_CPP} missing"
    start = source.index("void run(const std::string& config)")
    return source[start:]


def cpp_record_tick_body() -> str:
    source = WORLD_CPP.read_text(encoding="utf-8")
    start = source.index("void record_tick(uint32_t duration)")
    return source[start:]


def test_ready_transition_waits_for_the_first_completed_tick():
    body = cpp_run_body()
    # the wait: bounded, world-thread-fed, and breakable by stop/FAILED
    assert "first_tick_wait(FIRST_TICK_TIMEOUT_MS)" in body
    # READY fires only after the wait proved a tick (the !done early-exit
    # path returns without transitioning READY)
    ready_at = body.index("m_state.transition(POCKET_SERVER_READY);")
    wait_at = body.index("first_tick_wait(")
    assert wait_at < ready_at
    early_exit = body.index("if (!first_tick_wait(")
    assert early_exit < ready_at
    # the early-exit path cleans up and never reports READY
    between = body[early_exit:ready_at]
    assert "fail(POCKET_SERVER_INTERNAL" in between
    assert "cleanup();" in between
    assert "POCKET_SERVER_READY" not in between


def test_ready_never_publishes_over_a_stopping_world():
    body = cpp_run_body()
    # StateRecord::transition validates nothing, so the run() worker must
    # re-check STARTING immediately before publishing READY - stop() may
    # land between the wait's last predicate and the transition
    ready_at = body.index("m_state.transition(POCKET_SERVER_READY);")
    guard = "if (m_state.state() == POCKET_SERVER_STARTING)"
    guard_at = body.rindex(guard, 0, ready_at)
    assert body.index("first_tick_wait(") < guard_at
    between = body[guard_at:ready_at]
    assert "return" not in between


def test_record_tick_is_the_world_thread_flag_source():
    body = cpp_record_tick_body()
    assert "m_first_tick_done.store(true, std::memory_order_release);" in body
    # the store happens on the world thread inside the per-tick hook - the
    # only writer (run() only loads, start() only resets)
    source = WORLD_CPP.read_text(encoding="utf-8")
    assert source.count("m_first_tick_done.store(true") == 1


def test_first_tick_flag_is_reset_per_world_lifetime():
    source = WORLD_CPP.read_text(encoding="utf-8")
    start_body = source[source.index("int start(const std::string& config)"):]
    assert "m_first_tick_done.store(false, std::memory_order_release);" in start_body


def test_first_tick_deadline_is_bounded_and_clear_of_legit_boots():
    source = WORLD_CPP.read_text(encoding="utf-8")
    # 5 minutes: tens-of-seconds first ticks are legitimate, a wedged boot
    # (dead world thread, zero ticks) must still land in FAILED
    assert "static constexpr uint64_t FIRST_TICK_TIMEOUT_MS = 300'000;" in source


def test_failed_worker_joins_are_bounded_not_seizing():
    body = WORLD_CPP.read_text(encoding="utf-8")
    stop_body = body[body.index("int stop(uint64_t timeout_ms)"):]
    # both FAILED branches wait for the worker's own STOPPED record with a
    # deadline and detach loudly instead of joining a worker that is hung
    # inside cleanup()'s world-thread join while holding m_lifecycle
    assert stop_body.count("settle_deadline") >= 4
    assert stop_body.count("did not settle within") == 2
    assert stop_body.count("m_worker.detach()") == 3  # two FAILED + one wedge path


def test_start_retry_join_is_bounded_like_stop():
    body = WORLD_CPP.read_text(encoding="utf-8")
    start_body = body[body.index("int start(const std::string& config)"):]
    # start() permits retry from FAILED - its worker reclamation must use
    # the same bounded settle-or-detach law (a hung-in-cleanup worker
    # joined unbounded here would hold m_lifecycle forever)
    assert "settle_deadline" in start_body
    assert "previous worker did not settle" in start_body
    join_at = start_body.index("m_worker.join();")
    detach_at = start_body.index("m_worker.detach();")
    assert start_body.rindex("if (m_state.state() == POCKET_SERVER_STOPPED)", 0, join_at)


def test_stop_during_the_gate_is_never_labeled_a_failure():
    body = cpp_run_body()
    # the StartNetworkEmbedded-false branch must route a user stop() (or
    # any non-STARTING state) to the clean STOPPED path before it can
    # reach either fail() classification
    false_at = body.index("if (!sMaster.StartNetworkEmbedded(1))")
    stop_check = "if (m_stop.load(std::memory_order_acquire) ||"
    check_at = body.index(stop_check, false_at)
    fail_at = body.index("fail(POCKET_SERVER_PORT_IN_USE", false_at)
    assert check_at < fail_at
    between = body[check_at:fail_at]
    assert "POCKET_SERVER_STOPPED" in between


def test_the_built_dll_carries_the_listener_gate():
    dll = ROOT / "native" / ".build-win-x86_64" / "pocket-runtime-build" / \
        "pocket_world_runtime.dll"
    if not dll.is_file():
        return  # source-only checkout; the lane lockfile pins artifacts
    # artifact-level pin (round-2 review: textual pins alone cannot catch
    # evidence produced by a stale binary): the gate's loud failure literal
    # must exist in the SHIPPED dll, not just in driver anchor text (the
    # extern call itself is same-DLL internal and leaves no name string)
    assert "listener not opened".encode() in dll.read_bytes()


def test_the_listener_gate_lives_inside_start_network_embedded():
    driver = MASTER_DRIVER.read_text(encoding="utf-8")
    # Master.h: the exported gate declaration (namespace scope)
    assert 'MASTER_GATE_DECL_ANDROID' in driver
    assert 'extern "C" int pocket_world_first_tick_wait(unsigned int timeout_ms);' in driver
    # Master.cpp: the gate sits between the world-thread launch and the
    # realmlist ONLINE flip - no realmlist row, no queue threads, no
    # listening socket before the first tick survived
    android = driver[driver.index('MASTER_LISTENER_GATE_ANDROID = """'):]
    android = android[:android.index('"""', android.index('"""') + 3)]
    assert "pocket_world_first_tick_wait(FIRST_TICK_WAIT_MS)" in android
    assert "return false;" in android
    assert android.index("pocket_world_first_tick_wait") < android.index("Mark the realm online")
    # the wiring order pins the listener anchor against the WORLD_THREAD
    # overlap (same file; the Master.h decl anchor is file-independent)
    assert driver.index("WORLD_THREAD_UPSTREAM") < driver.index("MASTER_LISTENER_GATE_UPSTREAM")


def test_boot_battery_attempts_login_immediately_at_ready():
    source = BOOTS_PY.read_text(encoding="utf-8")
    # the regression battery: READY -> login with no settling waits
    ready_at = source.index("host.wait_ready(")
    login_at = source.index('"op": "proto-login"')
    assert ready_at < login_at
    between = source[ready_at:login_at]
    assert "time.sleep" not in between
    assert "POCKET_WORLD_LOOP" not in between
    assert "Avg Diff" not in between


def test_desktop_battery_carries_no_stale_workaround_gates():
    source = PLAY_PY.read_text(encoding="utf-8")
    # the old workarounds are gone from the conversational battery; the
    # boot-race regression lives in desktop_boots.py (pinned above)
    assert "POCKET_WORLD_LOOP" not in source
    assert "Avg Diff" not in source


def test_a_whisper_to_an_inactive_bot_reaches_the_trigger_evaluation():
    driver = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    # round-2 live-battery finding: the second bot never answered because
    # the SMSG_MESSAGECHAT activity gate dropped whispers from inactive
    # bots BEFORE the trigger evaluation - contradicting the A3 law that
    # a whisper is direct address. The gate must exempt whispers.
    android = driver[driver.index('PB_AI_CHATCASE_ANDROID = """'):]
    android = android[:android.index('"""', android.index('"""') + 3)]
    assert "llmWhisperDirect" in android
    assert "llmPeek.contents()[0] == CHAT_MSG_WHISPER" in android
    # the gate itself still runs for every other channel
    assert "if (!llmWhisperDirect && !AllowActivity())" in android
    assert "activity gate drop" in android
