"""R2-W4-1/R1-R2-1/R4-r2-1/R1-R2-3 (code-red round 2): the auth-matrix
instrument's REAL run_lane is pinned end-to-end against a stub host —
this is the test that caught the undefined `skip_world` NameError and the
liveness call's arity drift (4 args into a 5-param world_cell). The
world CELL itself is monkeypatched (a real socket needs a booted lane);
everything above it — boot, account, logon, frame pins, mismatched-cell
refusals, cell bookkeeping, per-lanes-run exit accounting — runs the
production code."""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools" / "rp_harness" / "probe_world_auth.py"


def load_probe():
    spec = importlib.util.spec_from_file_location("pwa_pin", HARNESS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StubHost:
    def __init__(self):
        self.lane = "vanilla"

    def wait_ready(self, timeout_s=900):
        return {"world": "READY", "event": "ready"}

    def send_raw(self, request):
        op = request.get("op")
        if op == "account":
            return {"ok": True, "created": 10}
        if op == "proto-logon":
            build = request["build"]
            pin = probe_pins()[self.lane]
            if build == pin["build"]:
                return {"ok": True, "frameBytes": pin["frame_bytes"],
                        "sessionKey": "ab" * 40}
            return {"ok": False, "reason": "frame drift"}
        raise AssertionError(f"unexpected op {op}")


def probe_pins():
    import json
    data = json.loads((ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    return {
        f["id"]: {
            "build": int(f["build"]),
            "realmd_db": f["databases"]["realmd"],
            "frame_bytes": f["auth_frame"]["proof_response_bytes"],
        }
        for f in data["flavors"]
    }


def test_run_lane_records_three_cells_with_no_exception(monkeypatch, tmp_path):
    """R2-W4-1 regression: the REAL run_lane (not a stub) must record all
    three client cells for one lane — this is the test that caught the
    undefined skip_world NameError."""
    probe = load_probe()
    monkeypatch.setattr(probe, "DesktopHost", StubHost)
    monkeypatch.setattr(
        probe, "world_cell",
        lambda label, client_flavor, build, key_hex, expect_ok,
               server_flavor="vanilla": {
            "cell": label, "build": build,
            "world": "ok" if expect_ok else "refused(connection-closed)",
            "challenge": "0x1EC",
        },
    )
    results = {"cells": []}
    lane = probe.run_lane("vanilla", probe.manifest_pins(), StubHost(), results)
    assert lane is not None
    assert len(results["cells"]) == 3, results["cells"]
    vanilla_build = probe_pins()["vanilla"]["build"]
    matching = [c for c in results["cells"] if c["build"] == vanilla_build]
    assert matching and matching[0]["logon"] == "ok"
    assert matching[0]["frameBytes"] == probe_pins()["vanilla"]["frame_bytes"]
    assert matching[0]["world"] == "ok"


def test_run_lane_mismatched_liveness_after_close_is_recorded(monkeypatch, tmp_path):
    """R4-r2-1 regression: the liveness re-probe after a mismatched
    connection-closed verdict must bind all five world_cell parameters
    (the round-1 code passed 4 args and crashed with a TypeError)."""
    probe = load_probe()
    monkeypatch.setattr(probe, "DesktopHost", StubHost)

    seen = []

    def fake_world_cell(label, client_flavor, build, key_hex, expect_ok,
                    server_flavor="vanilla"):
        seen.append((label, client_flavor, build, key_hex, expect_ok))
        if expect_ok:
            return {"cell": label, "build": build, "world": "ok",
                    "challenge": "0x1EC"}
        return {"cell": label, "build": build,
                "world": "refused(connection-closed)", "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "world_cell", fake_world_cell)
    results = {"cells": []}
    probe.run_lane("vanilla", probe.manifest_pins(), StubHost(), results)
    # every mismatched world cell was followed by a liveness re-probe
    liveness = [s for s in seen if "liveness" in s[0]]
    assert liveness, "the liveness re-probe never ran"
    for (label, client_flavor, build, key_hex, expect_ok) in liveness:
        assert expect_ok is True, "the liveness re-probe must use the matching build"
        assert key_hex is not None


def test_world_cell_signature_is_stable():
    """Shape pin (R4-r2-1): every world_cell call site in run_lane binds
    exactly the five parameters of the production signature."""
    import inspect
    probe = load_probe()
    sig = inspect.signature(probe.world_cell)
    assert list(sig.parameters) == [
        "label", "client_flavor", "build", "key_hex", "expect_ok",
        "server_flavor",
    ]
    src = HARNESS.read_text(encoding="utf-8")
    run_start = src.index("def run_lane")
    run_end = src.index("def main(")
    body = src[run_start:run_end]
    assert body.count("world_cell(") >= 2, (
        "the mismatched-liveness recheck disappeared from run_lane")


def test_run_lane_without_lane_reader_never_names_skips(monkeypatch, tmp_path):
    """R2-W4-1: the module must not reference an undefined skip_world (the
    NameError that killed every lane in the first instrument revision)."""
    src = HARNESS.read_text(encoding="utf-8")
    assert "skip_world" not in src, (
        "skip_world returned — the draft stub branch must stay deleted"
    )

def test_a_frame_report_drifting_from_the_pin_fails_the_lane(monkeypatch):
    """R2-R4-1 (W4 code-red round 4): the probe's frame check must be
    REACHABLE — a logon whose measured proof-response frame differs from
    the manifest pin fails the lane loud (the check itself is real
    production code in run_lane; this pin proves it fires)."""
    probe = load_probe()

    class DriftingFrameHost(StubHost):
        def send_raw(self, request):
            if request.get("op") == "proto-logon":
                pin = probe_pins()[self.lane]
                if request["build"] == pin["build"]:
                    return {"ok": True,
                            "frameBytes": pin["frame_bytes"] + 1,
                            "sessionKey": "ab" * 40}
            return super().send_raw(request)

    def clean_world_cell(label, client_flavor, build, key_hex, expect_ok,
                         server_flavor="vanilla"):
        return {"cell": label, "build": build, "world": "ok",
                "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "DesktopHost", lambda *a, **k: DriftingFrameHost())
    monkeypatch.setattr(probe, "world_cell", clean_world_cell)
    results = {"cells": []}
    try:
        probe.run_lane("vanilla", probe.manifest_pins(), DriftingFrameHost(),
                       results)
    except SystemExit:
        return  # the lane failed loud on the drifted frame report
    raise AssertionError(
        "run_lane accepted a frame report off the manifest pin by 1 byte - "
        "the 'drift is a loud failure' invariant cannot fire")


class RebootCountingHost(StubHost):
    """Counts constructions and wait_ready calls (R2-r3-2): a lane reboot
    in the instrument is a NEW rpHost JVM, so the stub class replacement
    must observe one construction + one wait_ready per fresh world."""

    constructed = 0
    ready_calls = 0

    def __init__(self, *args, **kwargs):
        type(self).constructed += 1
        super().__init__()

    def wait_ready(self, timeout_s=900):
        type(self).ready_calls += 1
        return {"world": "READY", "event": "ready"}

    def close(self):
        pass


def test_run_lane_liveness_failure_fails_the_lane(monkeypatch):
    """R2-r3-1 (W4 code-red round 3): a world that CRASHES on the
    wrong-build connection must fail the lane, not certify it. The old
    liveness helper swallowed the SystemExit into a recorded string, so a
    crashed world recorded 3 cells and main() exited 0. The REAL run_lane
    must propagate the liveness failure (main() counts it, exit 1)."""
    probe = load_probe()
    monkeypatch.setattr(probe, "DesktopHost", StubHost)

    def crashing_world_cell(label, client_flavor, build, key_hex, expect_ok,
                        server_flavor="vanilla"):
        if "liveness" in label:
            raise SystemExit(
                f"AUTH-MATRIX-DRIFT [{label}]: the MATCHING world closed "
                "mid-auth: the close was a world CRASH")
        return {"cell": label, "build": build,
                "world": "ok" if expect_ok else "refused(connection-closed)",
                "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "world_cell", crashing_world_cell)
    results = {"cells": []}
    try:
        probe.run_lane("vanilla", probe.manifest_pins(), StubHost(), results)
    except SystemExit:
        return  # the lane failed loud: the swallow is gone
    raise AssertionError(
        "run_lane returned normally although the liveness re-probe proved "
        "the world CRASHED on the wrong-build connection - the refusal was "
        "certified green on a dead world (the R2-r3-1 swallow is live)")


def test_main_counts_a_failed_liveness_lane_and_exits_1(monkeypatch, tmp_path):
    """R2-r3-1 end-to-end at main() level: one lane whose liveness re-probe
    crashes must make main() return 1 (the certification exit), with the
    drift recorded - never the green 3-cells exit 0."""
    import json
    probe = load_probe()
    monkeypatch.setattr(probe, "DesktopHost", lambda *a, **k: StubHost())

    def crashing_world_cell(label, client_flavor, build, key_hex, expect_ok,
                        server_flavor="vanilla"):
        if "liveness" in label:
            raise SystemExit("the close was a world CRASH")
        return {"cell": label, "build": build,
                "world": "ok" if expect_ok else "refused(connection-closed)",
                "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "world_cell", crashing_world_cell)
    out = tmp_path / "auth-matrix.json"
    rc = probe.main(["--flavor", "vanilla", "--out", str(out)])
    assert rc == 1, (
        f"main() returned {rc} with a crashed-world liveness re-probe - "
        "the certification exit must be 1")
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["drift"], "the crash drift was not recorded"


def test_non_vanilla_lane_reboots_between_world_cells(monkeypatch):
    """R2-r3-2 (W4 code-red round 3): the anticheat accepts the addon
    fingerprint ONCE per world boot (AddonHandler.cpp:92), so a non-vanilla
    lane must boot a FRESH world per world cell - three cells, three boots
    (the initial boot + a reboot before each later cell). The vanilla lane
    keeps its single boot (no fingerprint law)."""
    probe = load_probe()
    RebootCountingHost.constructed = 0
    RebootCountingHost.ready_calls = 0
    monkeypatch.setattr(probe, "DesktopHost", RebootCountingHost)

    def clean_world_cell(label, client_flavor, build, key_hex, expect_ok,
                     server_flavor="vanilla"):
        return {"cell": label, "build": build,
                "world": "ok" if expect_ok else "version-mismatch",
                "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "world_cell", clean_world_cell)
    results = {"cells": []}
    lane_host = RebootCountingHost()
    lane_host.lane = "tbc"
    probe.run_lane("tbc", probe.manifest_pins(), lane_host, results)
    assert len(results["cells"]) == 3
    assert RebootCountingHost.ready_calls == 3, (
        f"a non-vanilla lane must boot a fresh world per world cell "
        f"(initial + 2 reboots); got {RebootCountingHost.ready_calls} "
        "wait_ready calls - the addon fingerprint law is not honored per "
        "cell")
    assert RebootCountingHost.constructed >= 3


def test_vanilla_lane_keeps_its_single_boot(monkeypatch):
    """The vanilla lane has no anticheat fingerprint law; its proven
    single-boot 3-cell drive must stay single-boot (no gratuitous
    reboots)."""
    probe = load_probe()
    RebootCountingHost.constructed = 0
    RebootCountingHost.ready_calls = 0
    monkeypatch.setattr(probe, "DesktopHost", RebootCountingHost)

    def clean_world_cell(label, client_flavor, build, key_hex, expect_ok,
                     server_flavor="vanilla"):
        return {"cell": label, "build": build,
                "world": "ok" if expect_ok else "version-mismatch",
                "challenge": "0x1EC"}

    monkeypatch.setattr(probe, "world_cell", clean_world_cell)
    results = {"cells": []}
    probe.run_lane("vanilla", probe.manifest_pins(), RebootCountingHost(),
                   results)
    assert len(results["cells"]) == 3
    assert RebootCountingHost.ready_calls == 1, (
        f"the vanilla lane must keep its single boot; got "
        f"{RebootCountingHost.ready_calls} wait_ready calls")

def test_the_instrument_fails_closed_at_zero_flavors(monkeypatch, tmp_path):
    """R2-5-1 (W4 code-red round 5): a manifest with an EMPTY flavors array
    must not produce a green exit-0 'matrix run' with zero lanes and zero
    cell verdicts — the fail-loud-on-drift contract covers the boundary.
    Today manifest_pins() accepts it and main() certifies 0/0 cells as
    green; the pin fails until manifest_pins refuses <1 registered flavor
    (or main exits non-zero)."""
    import json
    probe = load_probe()
    manifest = json.loads(
        (ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    manifest["flavors"] = []
    broken = tmp_path / "flavor-empty.json"
    broken.write_text(json.dumps(manifest), encoding="utf-8")
    real_manifest = probe.MANIFEST
    monkeypatch.setattr(probe, "MANIFEST", broken)
    try:
        probe.manifest_pins()
    except SystemExit:
        return  # fail-loud at the pins layer: accepted
    # (pre-fix the pins layer accepted it, so prove main() cannot certify
    # the empty registry green either)
    monkeypatch.setattr(probe, "DesktopHost", lambda *a, **k: StubHost())
    out = tmp_path / "auth-matrix.json"
    rc = probe.main(["--matrix", "--out", str(out)])
    raise AssertionError(
        f"the instrument certified a ZERO-flavor manifest green (rc={rc}) - "
        "manifest_pins must refuse <1 registered flavor (R2-5-1)")
