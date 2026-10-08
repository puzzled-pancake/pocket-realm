"""Baseline-world-sanity DRIVER harness tripwires — no emulator required.

Pins the harness facet of LV-server-db-22 / LVF-server-db-009: when the
instrument dies before writing files/baseline-world-sanity.json (the
DB-CONFIG refusal leg), the driver must

1. NOT crash: the result-file `run-as cat` is guarded (check=False) and
   unparseable output becomes a FAIL verdict naming instrument-<tag>.log —
   the evidence bundle collected before the read is already on disk;
2. tear the emulator down ONLY after a completed run: a leg's error path
   propagates its exception and leaves the headless emulator running for
   post-mortem (the old error path issued 'adb emu kill' as a side effect,
   leaving only the physical device in adb devices);
3. fail the run honestly on a FAIL verdict: exit 1, the verdict recorded
   in sanity-report.json.

The fake lane reproduces subprocess.run's exact `check` semantics
(check=True raises CalledProcessError on the nonzero exit; check=False
returns stdout+stderr combined), so the pre-fix driver fails these tests
with the recorded CalledProcessError.
"""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

_SPEC = importlib.util.spec_from_file_location(
    "run_baseline_world_sanity_under_test",
    ROOT / "tools" / "run_baseline_world_sanity.py")
DRV = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(DRV)

RESULT_FILE = "files/baseline-world-sanity.json"
CAT_MISS = f"cat: {RESULT_FILE}: No such file or directory"


class _FakeLane:
    """Stand-in for the run_differential_parity subprocess surface.

    Faithful to subprocess.run: check=True raises CalledProcessError on the
    simulated nonzero exit (exactly the pre-fix crash); check=False returns
    stdout+stderr combined (exactly what LANE.run hands back).
    """

    ADB = "adb.exe"
    EMULATOR = "emulator.exe"
    AVD = "PocketDiff_x86_64"

    def __init__(self, result_file=None, instrument_output="OK (1 test)"):
        self.result_file = result_file
        self.instrument_output = instrument_output
        self.run_cmds = []   # (cmd list, check flag)
        self.adb_cmds = []   # adb arg lists
        self.waits = []

    def run(self, cmd, cwd=None, check=True, timeout=None, capture=True):
        cmd = [str(c) for c in cmd]
        self.run_cmds.append((cmd, check))
        joined = " ".join(cmd)
        if "am" in cmd and "instrument" in cmd:
            return self.instrument_output
        if "cat" in cmd and RESULT_FILE in joined:
            if self.result_file is None:  # the file was never written
                if check:
                    raise subprocess.CalledProcessError(1, cmd)
                return CAT_MISS
            return self.result_file
        return ""

    def adb(self, *args, timeout=120, check=True):
        self.adb_cmds.append([str(a) for a in args])
        return self.run([self.ADB, *args], check=check, timeout=timeout)

    def wait_for_device(self, boot_timeout_s):
        self.waits.append(boot_timeout_s)

    def reuse_prior_apks(self):
        return {"server_a": b"a", "tests_a": b"ta",
                "server_b": b"b", "tests_b": b"tb",
                "gradle_commands": ["gradle"]}


class _FakeEmulatorProc:
    def __init__(self):
        self.waited = 0

    def wait(self, timeout=None):
        self.waited += 1
        return 0


def _install_lane(monkeypatch, lane):
    monkeypatch.setattr(DRV, "LANE", lane)
    monkeypatch.setattr(DRV, "stage_baseline_data", lambda: None)


def _wire_main(monkeypatch, tmp_path, drive_impl):
    """Redirect the run dir under tmp_path, fake the emulator process, and
    pin the driver to --skip-build --only b."""
    proc = _FakeEmulatorProc()
    monkeypatch.setattr(DRV, "ROOT", tmp_path)  # only main()'s out_dir reads it now
    monkeypatch.setattr(DRV.subprocess, "Popen", lambda *a, **k: proc)
    monkeypatch.setattr(DRV, "drive", drive_impl)
    monkeypatch.setattr(sys, "argv",
                        ["run_baseline_world_sanity.py", "--skip-build", "--only", "b"])
    return proc


def _run_dir(tmp_path):
    return next((tmp_path / "build" / "baseline-world-sanity").iterdir())


def _report(tmp_path):
    return json.loads((_run_dir(tmp_path) / "sanity-report.json").read_text(encoding="utf-8"))


def test_missing_result_file_becomes_fail_verdict_naming_instrument_log(
        tmp_path, monkeypatch, capsys):
    """The recorded crash: instrument dies (DB-CONFIG refusal), the result
    file never appears — the driver reports a FAIL verdict naming the
    instrument log instead of raising CalledProcessError."""
    lane = _FakeLane(result_file=None,
                     instrument_output="INSTRUMENTATION_STATUS: FAILURES!!!")
    _install_lane(monkeypatch, lane)
    out_dir = tmp_path / "run"
    out_dir.mkdir()
    evidence = DRV.drive(b"apk", b"tests", "b", out_dir)
    assert evidence["verdict"] == "FAIL"
    assert Path(evidence["instrumentLog"]).name == "instrument-b.log"
    assert Path(evidence["instrumentLog"]).is_file()
    assert CAT_MISS in evidence["reason"]
    # the read is guarded: check=False, so the missing file surfaces as
    # text rather than CalledProcessError
    cats = [(cmd, chk) for cmd, chk in lane.run_cmds
            if "cat" in cmd and RESULT_FILE in " ".join(cmd)]
    assert cats and all(chk is False for _cmd, chk in cats)
    # the raw failing cat output is still captured as evidence, next to the
    # instrument log collected before the read
    assert CAT_MISS in (out_dir / "evidence-b.json").read_text(encoding="utf-8")
    assert "FAILURES!!!" in (out_dir / "instrument-b.log").read_text(encoding="utf-8")
    out = capsys.readouterr().out
    assert "left no readable files/baseline-world-sanity.json" in out
    assert "instrument-b.log" in out


def test_intact_result_file_still_returns_evidence(tmp_path, monkeypatch):
    """No regression of the happy path: a well-formed result file parses to
    the evidence dict exactly as before."""
    lane = _FakeLane(result_file=json.dumps({"providerMode": "SQLITE",
                                             "dbBootMs": 12}))
    _install_lane(monkeypatch, lane)
    evidence = DRV.drive(b"apk", b"tests", "b", tmp_path)
    assert evidence == {"providerMode": "SQLITE", "dbBootMs": 12}
    assert evidence.get("verdict") != "FAIL"


def test_fail_verdict_fails_run_honestly_and_still_tears_down(
        tmp_path, monkeypatch, capsys):
    """A FAIL verdict is honest: exit 1, verdict recorded in
    sanity-report.json — and the completed run still tears the emulator
    down (that is the normal path, not an error path)."""
    lane = _FakeLane()
    _install_lane(monkeypatch, lane)
    proc = _wire_main(monkeypatch, tmp_path,
                      lambda *a: {"verdict": "FAIL",
                                  "reason": "result file missing/unreadable",
                                  "instrumentLog": str(tmp_path / "instrument-b.log")})
    rc = DRV.main()
    assert rc == 1
    assert ["emu", "kill"] in lane.adb_cmds
    assert proc.waited == 1
    report = _report(tmp_path)
    assert report["servers"]["b"]["evidence"]["verdict"] == "FAIL"
    assert report["servers"]["b"]["providerMode"] is None
    out = capsys.readouterr().out
    assert "[b] FAIL: result file missing/unreadable" in out


def test_leg_error_path_never_kills_the_emulator(tmp_path, monkeypatch, capsys):
    """A leg's error path propagates its exception and leaves the emulator
    running for post-mortem — never 'adb emu kill' as a side effect."""
    lane = _FakeLane()
    _install_lane(monkeypatch, lane)

    def boom(*_a, **_k):
        raise RuntimeError("install blew up")

    proc = _wire_main(monkeypatch, tmp_path, boom)
    with pytest.raises(RuntimeError, match="install blew up"):
        DRV.main()
    assert ["emu", "kill"] not in lane.adb_cmds
    assert proc.waited == 0
    assert not (_run_dir(tmp_path) / "sanity-report.json").exists()
    out = capsys.readouterr().out
    assert "leaving the emulator running for post-mortem" in out


def test_pass_verdict_still_exits_zero(tmp_path, monkeypatch):
    """The verdict gate only adds FAIL detection — a clean leg still exits
    0 with the normal teardown."""
    lane = _FakeLane()
    _install_lane(monkeypatch, lane)
    proc = _wire_main(monkeypatch, tmp_path,
                      lambda *a: {"providerMode": "SQLITE", "dbBootMs": 5})
    rc = DRV.main()
    assert rc == 0
    assert ["emu", "kill"] in lane.adb_cmds
    assert proc.waited == 1
