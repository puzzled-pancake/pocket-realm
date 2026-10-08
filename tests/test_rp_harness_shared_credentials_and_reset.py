"""Pin the desktop harness twins' shared RPTEST credential and the
reset_davos failure surface.

Round-1 review found the five desktop harness scripts drifting between
two credential pairs against the SAME account (boots/soak/toolprobe
logged in RPTEST/"rptest" while smoke/play provisioned and logged in
"rptest"/"rptestpass"; tools/create_davos.py + tools/reset_davos.py
bind Davos with WHERE username='RPTEST'), and both boot_once and
desktop_play.py discarding reset_davos.py's returncode so a failed
reset (locked sqlite from a prior run) surfaced later as an unrelated
"no Davos"/login failure.

These tests pin the fix: RPTEST_USER/RPTEST_PASS are defined once in
desktop_host.py and imported by every twin (no local redefinitions, no
inline login literals), the username still matches the davos tools'
binding SQL, and a non-zero reset_davos exit fails the boot iteration
(desktop_boots) / the suite (desktop_play) with the captured output
BEFORE any host process is spawned.
"""

import importlib.util
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools" / "rp_harness"

DESKTOP_TWINS = [
    "desktop_boots.py",
    "desktop_smoke.py",
    "desktop_play.py",
    "desktop_soak.py",
    "desktop_toolprobe.py",
]

RESET_STDERR = (b"Traceback (most recent call last):\n"
                b"sqlite3.OperationalError: database is locked\n")


def load_harness(name: str):
    # Module-level code only wires sys.path and imports desktop_host;
    # every twin guards its live work behind `if __name__ == "__main__"`.
    path = HARNESS / name
    spec = importlib.util.spec_from_file_location(path.stem + "_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def failing_reset(monkeypatch):
    """A reset_davos.py run that dies on a locked sqlite DB."""
    def fake_run(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 1, stdout=b"",
                                           stderr=RESET_STDERR)
    monkeypatch.setattr(subprocess, "run", fake_run)


def forbid_host(module, monkeypatch):
    def no_host(*args, **kwargs):
        raise AssertionError(
            "a failed reset_davos must fail before any host process starts")
    monkeypatch.setattr(module, "DesktopHost", no_host)


# ---- the shared credential -------------------------------------------------


def test_every_desktop_twin_uses_the_one_shared_rptest_credential():
    shared = load_harness("desktop_host.py")
    for name in DESKTOP_TWINS:
        module = load_harness(name)
        assert module.RPTEST_USER == shared.RPTEST_USER, (
            f"{name}: RPTEST_USER must come from desktop_host.py, not be "
            f"redefined (got {module.RPTEST_USER!r} != {shared.RPTEST_USER!r})")
        assert module.RPTEST_PASS == shared.RPTEST_PASS, (
            f"{name}: RPTEST_PASS must come from desktop_host.py, not be "
            f"redefined (got {module.RPTEST_PASS!r} != {shared.RPTEST_PASS!r})")
        # and no twin may inline the login literal back in either spelling
        source = (HARNESS / name).read_text(encoding="utf-8")
        for rogue in ('"user": "RPTEST"', '"user": "rptest"',
                      '"pass": "rptest"', '"pass": "rptestpass"'):
            assert rogue not in source, (
                f"{name}: inline login credential {rogue} - use the shared "
                f"RPTEST_USER/RPTEST_PASS names instead")


def test_shared_username_is_the_one_the_davos_tools_bind_to():
    shared = load_harness("desktop_host.py")
    needle = f"username='{shared.RPTEST_USER}'"
    for tool in ("create_davos.py", "reset_davos.py"):
        source = (ROOT / "tools" / tool).read_text(encoding="utf-8")
        assert needle in source, (
            f"tools/{tool} binds Davos to a different username than the "
            f"harness credential {shared.RPTEST_USER!r}")


# ---- reset_davos failure surfacing ----------------------------------------


def test_boot_once_fails_the_iteration_with_the_reset_error(tmp_path, monkeypatch):
    boots = load_harness("desktop_boots.py")
    failing_reset(monkeypatch)
    forbid_host(boots, monkeypatch)

    result = boots.boot_once(tmp_path, 1, 25)

    assert result["inWorld"] is False
    assert result["error"].startswith("reset_davos rc=1:")
    assert "database is locked" in result["error"], (
        "the reset's captured output must ride along in the error")
    assert "crashed" not in result, "a reset failure is not a host crash"


def test_boot_once_still_boots_and_logs_in_with_the_shared_credential(tmp_path, monkeypatch):
    boots = load_harness("desktop_boots.py")
    shared = load_harness("desktop_host.py")

    def healthy_reset(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 0, stdout=b"Davos reset: (7,)", stderr=b"")
    monkeypatch.setattr(subprocess, "run", healthy_reset)

    requests = []

    class FakeProc:
        @staticmethod
        def poll():
            return 0

    class FakeHost:
        def __init__(self, repo, *, bots=0, stderr_path=None):
            self.proc = FakeProc()

        def wait_ready(self, timeout_s=900.0):
            return {"event": "ready"}

        def send_raw(self, request, timeout_s=180.0):
            requests.append(request)
            op = request["op"]
            if op == "proto-login":
                return {"ok": True}
            if op == "proto-world":
                return {"authResult": "ok"}
            if op == "proto-chars":
                return {"chars": [{"guid": 7, "name": boots.RP_CHAR}]}
            if op in ("proto-pick", "proto-chat"):
                return {"ok": True, "lines": [{"text": "The realm wakes slowly."}]}
            raise AssertionError(f"unexpected op {op}")

        def alive(self):
            return True

        def close(self, timeout_s=120.0):
            pass

    monkeypatch.setattr(boots, "DesktopHost", FakeHost)

    result = boots.boot_once(tmp_path, 2, 0)

    login = next(r for r in requests if r["op"] == "proto-login")
    assert login["user"] == shared.RPTEST_USER
    assert login["pass"] == shared.RPTEST_PASS
    assert result["inWorld"] is True
    assert result["echoLines"] == 1
    assert result["crashed"] is False


def test_desktop_play_fails_the_suite_with_the_reset_error(monkeypatch, capsys, tmp_path):
    play = load_harness("desktop_play.py")
    failing_reset(monkeypatch)
    forbid_host(play, monkeypatch)

    rc = play.main(["--out", str(tmp_path / "play2.json")])

    assert rc == 1
    err = capsys.readouterr().err
    assert "reset_davos failed (rc=1)" in err
    assert "database is locked" in err, (
        "the reset's captured output must ride along in the failure")
