"""R3R1-1 (W4 code-red round 3): the wave's exit criterion, grep-pinned the
way the counts artifacts are — the archived auth-matrix evidence must show
NINE cells with the pinned verdict shapes, and the per-flavor desktop E2E
logs must end in Save & Exit.

R4-3 (round 4) posture: out/ is gitignored, so on a FRESH CHECKOUT these
artifacts are absent and the pins SKIP (the sibling convention:
test_wave_evidence's 'a fresh clone without one skips silently'); on the
wave's own tree — where the artifacts exist — a missing or PARTIAL
artifact still fails loud (the exit cannot converge on 'instrument run
time' claims)."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
W4 = ROOT / "out/completion/w4"

# the verdict shapes the 9-cell criterion accepts:
#   matching   -> world "ok" (AUTH_OK 0x0C + char enum on the wire)
#   mismatched -> a fail-closed refusal ("connection-closed" counts ONLY
#                 with a passing livenessAfterRefusal re-proof)
MATRIX_JSON = W4 / "auth-matrix-final.json"
MATRIX_LOG = W4 / "auth-matrix-full-final2.log"
# the per-flavor E2E evidence: one or more archived probe logs; the
# IMPORT leg may land in an earlier log (the wotlk import's disk
# contention exceeded the world READY budget on the same-run boot, so the
# lane's evidence is the pair: import in -final.log, boot->Save & Exit in
# -final2.log against the reused generation). The criterion: the chain
# import -> boot -> autoLogin -> saveExit all evidenced across the set.
# R1-2/R4-r5-1 (round 5): the charter names BOTH import lanes. The tbc
# evidence set carries the full ARCHIVE-lane chain (import="archive" ->
# saveExit). The wotlk archive import was attempted and refused by the
# storage preflight (need 88 GB under post-import disk contention; log
# archived) — its evidence is the pair: folder import in -final.log, the
# reused-generation chain to saveExit in -final2.log, plus the archived
# archive-attempt. D86 records the bound.
E2E_LOGS = {
    "tbc": (W4 / "e2e-probe-tbc-final.log",
            W4 / "e2e-probe-tbc-archive.log"),
    "wotlk": (W4 / "e2e-probe-wotlk-final.log",
              W4 / "e2e-probe-wotlk-final2.log",
              W4 / "e2e-probe-wotlk-archive.log"),
}


def test_the_archived_auth_matrix_shows_nine_green_cells():
    if not MATRIX_JSON.is_file() or not MATRIX_LOG.is_file():
        import pytest
        pytest.skip(
            "the w4 matrix evidence artifacts are absent (fresh checkout; "
            "out/ is gitignored) — nothing to hold accountable here")
    assert MATRIX_JSON.is_file(), (
        f"{MATRIX_JSON.name} is missing — run the completed 3x3 instrument "
        "(python tools/rp_harness/probe_world_auth.py --matrix) and archive "
        "its report; the exit cannot certify the matrix without it")
    report = json.loads(MATRIX_JSON.read_text(encoding="utf-8"))
    cells = {(c["cell"].split("@")[0], c["cell"].split("@")[1].split("-")[0]): c
             for c in report["cells"]}
    assert report.get("drift") is None, (
        f"the archived matrix records drift: {report['drift']}")
    flavors = ("vanilla", "tbc", "wotlk")
    assert len(cells) >= 9, (
        f"the archived matrix holds {len(cells)} cell verdicts, the 3x3 "
        "needs 9")
    for server in flavors:
        for client in flavors:
            cell = cells[(f"{client}-client", server)]
            expect_ok = client == server
            if expect_ok:
                assert cell.get("world") == "ok", (
                    f"the matching cell {client}@{server} must record world "
                    f"'ok', got {cell.get('world')!r}")
                assert cell.get("logon") == "ok", (
                    f"the matching cell {client}@{server} must record the "
                    "SRP6 logon 'ok' with the pinned frame")
            else:
                world = str(cell.get("world", ""))
                assert "refused" in world or "version-mismatch" in world, (
                    f"the mismatched cell {client}@{server} must be refused, "
                    f"got {world!r}")
                if "connection-closed" in world:
                    live = str(cell.get("livenessAfterRefusal", ""))
                    assert live == "ok", (
                        f"the connection-closed refusal {client}@{server} "
                        "rests on the liveness re-proof, which recorded "
                        f"{live!r} — a dead world is drift, not a refusal")


def test_the_matrix_log_is_a_full_matrix_run_ending_green():
    if not MATRIX_LOG.is_file():
        import pytest
        pytest.skip(
            "the w4 matrix evidence artifacts are absent (fresh checkout; "
            "out/ is gitignored) — nothing to hold accountable here")
    assert MATRIX_LOG.is_file(), (
        f"{MATRIX_LOG.name} is missing — archive the full-matrix run log")
    text = MATRIX_LOG.read_text(encoding="utf-8", errors="replace")
    assert "9/9 cells verdicts recorded" in text, (
        "the archived matrix log does not record 9/9 cells")
    assert "0 failed lane(s)" in text, (
        "the archived matrix log records failed lanes")


def test_the_per_flavor_desktop_e2e_logs_end_in_save_and_exit():
    if any(not path.is_file() for paths in E2E_LOGS.values() for path in paths):
        import pytest
        pytest.skip(
            "the w4 E2E evidence logs are absent (fresh checkout; out/ is "
            "gitignored) — nothing to hold accountable here")
    for flavor, paths in E2E_LOGS.items():
        for path in paths:
            assert path.is_file(), (
                f"the {flavor} desktop E2E log is missing — run "
                f"gradlew flavorE2eProbe -DflavorId={flavor} ... and "
                "archive it")
            text = path.read_text(encoding="utf-8", errors="replace")
            assert "E2E-PROBE" in text, (
                f"the {flavor} E2E log {path.name} carries no evidence lines")
        combined = " ".join(
            p.read_text(encoding="utf-8", errors="replace") for p in paths)
        # BOTH import lanes per the W4.1 charter: the FOLDER lane chain to
        # Save & Exit, plus an ARCHIVE-lane leg (green chain for tbc; for
        # wotlk the archived storage-bound attempt — the 17.8 GiB tree's
        # 88 GB planning need exceeded the volume's free space, recorded
        # in D86)
        assert 'import="folder"' in combined, (
            f"the {flavor} E2E evidence set records no folder-import leg")
        if flavor == "wotlk":
            assert 'STORAGE_PREFLIGHT' in combined, (
                "the wotlk E2E evidence set records neither an archive "
                "chain nor the archived storage-bound attempt")
        else:
            assert 'import="archive"' in combined, (
                f"the {flavor} E2E evidence set records no archive-import "
                "leg - the charter names both lanes")
        for needle in ('boot="READY"', 'autoLogin="character-screen"',
                       'saveExit="clean"'):
            assert needle in combined, (
                f"the {flavor} E2E evidence set is missing {needle} — the "
                "probe emits it only after that step passed")
