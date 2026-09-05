"""WS-G pins (rp-depth-fix-plan v2.3 §8): realmd keep-alive + liveness (G1)
and the in-same-change net hygiene (G2).

The cmangos edits are §0.b lane-3 submodule single-tree files: they land as
one submodule commit and the host pins read the pristine tree (the
test_db_async_null_guard pristine-read precedent). realmd_runtime.cpp is the
own-tree runtime; its pins read the working file.

Pinned contracts:
  (a) G1 keep-alive: a self-rearming steady_timer on the realmd io_context
      whose completion handler re-arms UNCONDITIONALLY (the error_code is
      ignored by name) and bumps an atomic io-liveness counter; the run()
      heartbeat reads the counter and fail()s on >= 3 consecutive timer
      intervals of frozen count while !m_stop - the silent dead-listener
      mode becomes a visible FAILED.
  (b) G1 world-mirror: Master's m_context.run() network pump carries the
      try/catch(...) guard realmd_runtime already has (parity item).
  (c) G2: every AuthSocket async_write completion handler closes the socket
      on error (gated close) or unconditionally (reject paths) - none
      ignores the error code anymore.
  (d) G2: AsyncSocket's public close paths use the non-throwing close(ec)
      overload (Close() logs, the destructor swallows) - a completion
      handler can no longer throw a system_error out of the reactor.
  (e) G1 kill-switch: AiPlayerbot.RealmdTimerMs read with default 250
      through realmd_runtime's existing sConfig path; 0 = timer off (the
      documented workaround - no new conf plumbing; the app-staged
      realmd.conf carries no AiPlayerbot.* key today).
  B6  the pre-existing WorldRunnable purge hook rides the same submodule
      commit: __ANDROID__-guarded, device api-level >= 28, 60 s cadence.

Device-gated expectations (documented only; no protocol client on host):
  fd-count  across N logon cycles + idle, the :realm process fd count must
            stay flat (asio accept is RAII-safe; a rising count reopens
            the §8 G2 fd investigation with evidence).
  idle-close a pre-auth TCP peer that connects and sends nothing must be
            closed by the one-shot 30 s AuthSocket timer (AuthSocket.cpp
            OnOpen; the timer is cancelled at the first packet, so this
            window exists only before the first byte).
"""
from __future__ import annotations

import os
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
REALMD_RUNTIME = ROOT / "native" / "realm-runtime" / "src" / "realmd_runtime.cpp"
PRISTINE_MASTER = ROOT / "native" / "cmangos" / "src" / "mangosd" / "Master.cpp"
PRISTINE_AUTHSOCKET = ROOT / "native" / "cmangos" / "src" / "realmd" / "AuthSocket.cpp"
PRISTINE_ASOCKET = ROOT / "native" / "cmangos" / "src" / "shared" / "Network" / "AsyncSocket.hpp"
PRISTINE_WORLDRUNNABLE = ROOT / "native" / "cmangos" / "src" / "mangosd" / "WorldRunnable.cpp"

#: Device serial that unlocks the two on-device expectations (fd-count,
#: pre-auth idle-close). Absent on host/CI runs - the tests skip.
DEVICE_SERIAL_ENV = "POCKET_REALM_G1_DEVICE"


def _read(path: Path) -> str:
    assert path.is_file(), f"missing pinned source: {path}"
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# (a) + (e) realmd_runtime.cpp: keep-alive timer, liveness counter, fail path
# ---------------------------------------------------------------------------

def test_g1_keepalive_timer_self_rearms_unconditionally() -> None:
    src = _read(REALMD_RUNTIME)
    # The steady_timer lives on the realmd io_context and the pump exists.
    assert "boost::asio::steady_timer" in src, "the keep-alive is a steady_timer on m_io"
    assert "arm_keepalive" in src, "the keep-alive has a single (re)arm function"
    arm = src.split("void arm_keepalive(")[1].split("void cleanup(")[0]
    assert "async_wait" in arm, "the pump arms an async_wait"
    # Re-arm shape: the completion handler ignores the error code BY NAME -
    # an operation_aborted from a spurious cancel still re-arms; only
    # m_stop or io_context destruction ends the chain.
    assert "(const boost::system::error_code&)" in arm, \
        "the timer handler must not branch on the error code (unconditional re-arm)"
    assert "arm_keepalive(interval)" in arm, "the handler re-arms itself"
    # The dispatch evidence: every handler run bumps the thread-safe count.
    assert "m_io_liveness.fetch_add(1" in arm, \
        "each handler dispatch increments the io liveness counter"


def test_g1_liveness_counter_and_fail_path() -> None:
    src = _read(REALMD_RUNTIME)
    assert "std::atomic<uint64_t> m_io_liveness{0}" in src, \
        "thread-safe io liveness counter member"
    heartbeat = src.split("m_state.transition(POCKET_SERVER_READY)")[1] \
                     .split("m_state.transition(POCKET_SERVER_STOPPED)")[0]
    assert "m_io_liveness.load(" in heartbeat, "the heartbeat reads the liveness count"
    # A sustained freeze (>= 3 consecutive timer intervals) while !m_stop
    # converts the silent dead-listener mode into a visible FAILED.
    assert "if (liveness == last_liveness)" in heartbeat, \
        "the heartbeat detects an unchanged dispatch count"
    assert re.search(r"stalled_heartbeats\s*\*\s*heartbeat_ms\s*>=\s*"
                     r"static_cast<uint32>\(timer_ms\)\s*\*\s*3", heartbeat), \
        "the stall threshold is 3 consecutive timer intervals"
    assert re.search(r'fail\(POCKET_SERVER_INTERNAL,\s*"realmd io reactor stalled', heartbeat), \
        "a frozen reactor fails the server instead of staying READY"
    # The loop-condition still exits on FAILED so cleanup() joins the io
    # threads; the stall fail leaves via the same path (break, not return).
    assert "m_state.state() != POCKET_SERVER_FAILED)" in src


def test_g1_realmd_timer_kill_switch_default_250() -> None:
    src = _read(REALMD_RUNTIME)
    # (e) kill-switch: read through the file's own sConfig path, default
    # 250 ms, 0 = timer off. The comment names where the key WOULD be
    # staged (the app's realmd.conf writer stages no AiPlayerbot.* key).
    assert 'sConfig.GetIntDefault("AiPlayerbot.RealmdTimerMs", 250)' in src, \
        "RealmdTimerMs is read with the documented 250 ms default"
    assert "if (timer_ms > 0)" in src, "0 disables both the timer and the liveness check"
    assert "ServerRuntimeFiles.realmdConfig" in src, \
        "the conf staging location is documented in the source"


# ---------------------------------------------------------------------------
# (b) Master.cpp: the world-mirror exception guard
# ---------------------------------------------------------------------------

def test_g1_world_mirror_pump_has_exception_guard() -> None:
    src = _read(PRISTINE_MASTER)
    network = src.split("StartNetworkEmbedded")[1].split("StopEmbedded")[0]
    assert "m_netThreads.emplace_back" in network
    # The guard mirrors realmd_runtime's io-thread guard: try { run() }
    # catch (...) { log } - an exception must not std::terminate the
    # embedded world process out of a completion handler.
    pump = re.search(r"try\s*\{\s*m_context\.run\(\);\s*\}\s*catch \(\.\.\.\)", network)
    assert pump, "the world m_context.run() pump is wrapped in try/catch(...)"
    catch_block = network[pump.end():network.find("return true", pump.end())]
    assert "sLog.outError" in catch_block, "the guard logs instead of terminating"


# ---------------------------------------------------------------------------
# (c) AuthSocket.cpp: every write completion closes on error
# ---------------------------------------------------------------------------

def test_g2_authsocket_write_completions_handle_the_error_code() -> None:
    src = _read(PRISTINE_AUTHSOCKET)
    write_sites = [line for line in src.splitlines() if re.search(r"\bWrite\(", line)]
    assert len(write_sites) >= 12, f"expected the audited write sites, found {len(write_sites)}"
    ignoring = [line for line in write_sites if "Close()" not in line]
    assert not ignoring, \
        f"write completions that neither close on error nor close: {ignoring}"
    # The gated shape is the file's own idiom; it must test the error code
    # (not close unconditionally on success paths the client retries on).
    gated = [line for line in write_sites if "if (error) self->Close();" in line]
    assert len(gated) == 12, \
        f"the 12 audited ignore-error sites are now gated closes, found {len(gated)}"
    # A completion may only comment the error name away on the reject
    # paths, which close unconditionally; an empty-body completion that
    # ignores the error code is exactly what G2 removed.
    for line in write_sites:
        if "/*error*/" in line:
            assert "self->Close();" in line, \
                f"a commented-away error code must still close: {line}"
    assert re.search(r"error_code&[^)]*\)\s*\{\s*\}", src) is None, \
        "no empty-body completion handler remains"


def test_g2_authsocket_timeout_lambda_owns_the_socket() -> None:
    src = _read(PRISTINE_AUTHSOCKET)
    on_open = src.split("bool AuthSocket::OnOpen()")[1].split("bool AuthSocket::ProcessIncomingData")[0]
    assert "async_wait([self = shared_from_this()]" in on_open, \
        "the 30 s pre-auth timeout captures shared_from_this(), not raw this"
    assert "self->Close()" in on_open and "self->IsClosed()" in on_open


# ---------------------------------------------------------------------------
# (d) AsyncSocket.hpp: non-throwing public close paths
# ---------------------------------------------------------------------------

def test_g2_asyncsocket_close_paths_are_non_throwing() -> None:
    src = _read(PRISTINE_ASOCKET)
    close = src.split("void Close()")[1].split("GetAsioSocket")[0]
    assert "m_socket.close(ec);" in close, "Close() uses the close(ec) overload"
    assert "m_socket.shutdown(boost::asio::ip::tcp::socket::shutdown_both, ec);" in close
    assert "sLog.outError" in close, "a failed close is logged, not thrown"
    destructor = src.split("AsyncSocket<SocketType>::~AsyncSocket()")[1].split("void MaNGOS::AsyncSocket")[0]
    assert "m_socket.close(ec);" in destructor, "the destructor swallows close errors"
    # No throwing bare close() may remain anywhere in the header.
    assert "m_socket.close();" not in src


# ---------------------------------------------------------------------------
# B6 rider: the WorldRunnable purge hook rides the same submodule commit
# ---------------------------------------------------------------------------

def test_b6_worldrunnable_purge_hook_is_android_gated() -> None:
    src = _read(PRISTINE_WORLDRUNNABLE)
    assert "mallopt(M_PURGE, 1)" in src, "the purge hook survived into the pinned tree"
    assert "android_get_device_api_level() >= 28" in src, \
        "M_PURGE stays gated on device api level 28"
    assert "#if defined(__ANDROID__)" in src, "the hook is android-only"
    assert "nowSec - lastPurgeSec >= 60" in src, "the purge keeps its 60 s cadence"


# ---------------------------------------------------------------------------
# Device-gated expectations (§8 G2/G1 follow-ups): documented, no host leg.
# These run ONLY against a device named by POCKET_REALM_G1_DEVICE; on the
# host suite they record the procedure and skip.
# ---------------------------------------------------------------------------

@pytest.mark.skipif(not os.environ.get(DEVICE_SERIAL_ENV),
                    reason="device-gated expectation: set POCKET_REALM_G1_DEVICE to run")
def test_device_fd_count_flat_across_logon_cycles() -> None:
    """fd-count assertion (§8 G1): the only way the fd-coupling claim becomes
    verifiable. Procedure, on the device named by POCKET_REALM_G1_DEVICE:

    1. Start the realm, note the :realm pid (adb shell pidof).
    2. Sample `ls /proc/<pid>/fd | wc -l` at idle.
    3. Drive >= 10 full logon cycles (client connect -> auth -> realm list
       -> world entry -> logout -> client disconnect).
    4. Re-sample at idle; then hold 5 minutes pre-auth-only connects
       (connect, no bytes, forced client close) and re-sample.

    EXPECTATION: the fd count returns to the idle baseline each time (asio
    accept is RAII-safe; the G2 write-completion closes bound the stalled
    peer). A monotonically rising count across cycles reopens the §8 G2 fd
    investigation with evidence - do not close it on theory.
    """
    pytest.skip("device expectation not wired to a protocol client by design (WS-G scope)")


@pytest.mark.skipif(not os.environ.get(DEVICE_SERIAL_ENV),
                    reason="device-gated expectation: set POCKET_REALM_G1_DEVICE to run")
def test_device_pre_auth_idle_peer_closed_by_30s_timer() -> None:
    """Pre-auth idle-close case (§8 G1): connect to the realmd port and send
    nothing; EXPECT the server to close the connection ~30 s later (the
    one-shot pre-auth timer in AuthSocket::OnOpen, cancelled at the first
    packet - post-auth idle is liveness-only and is NOT this case). The
    close is the G2 hygiene made observable: with the timer's lambda now
    owning the socket via shared_from_this(), the expiry must close the
    idle peer and release its fd instead of leaking the object.

    Procedure: adb forward tcp:<port> to the device's realmd port, open a
    raw TCP connection, send nothing, read to EOF; EXPECT EOF within
    25-40 s. An fd that survives past the close (visible in the fd-count
    expectation above) is the failure signature.
    """
    pytest.skip("device expectation not wired to a protocol client by design (WS-G scope)")
