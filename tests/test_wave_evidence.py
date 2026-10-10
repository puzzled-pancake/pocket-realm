"""Wave-evidence provenance gate (R4-2, desktop-twin round 1).

The W0 evidence pack drifted from its own narrative: the desktop counts
artifact cited a log whose only content was an ANDROID run summary, the
android artifact claimed a "fresh clean full re-execution" while its own
cited log replayed a module FROM-CACHE, three different log names were
cited for the same android run, and WAVE-EXIT's delta arithmetic was
wrong. The numbers were measured; the provenance narrative was not.

This gate holds the pack to its own claims — whenever a counts artifact
exists in out/completion/w0 (a fresh clone without one skips silently,
the same posture as the counts-matching pin):
  1. the cited log EXISTS under w0/, ends BUILD SUCCESSFUL, and carries
     the SUITE'S OWN task markers — an android summary can never stand in
     for a desktop log again (and vice versa);
  2. when the artifact's note claims fresh/clean/re-executed runs, every
     test task the command names is a REAL EXECUTION line in that log —
     no FROM-CACHE, no UP-TO-DATE, or the wording changes.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
W0 = ROOT / "out/completion/w0"

# artifact name -> (module marker task lines the log must carry, forbidden
# foreign markers) — the (a) leg: a log must be the SUITE'S OWN run.
SUITE_MARKERS = {
    "android-unit-counts.json": (
        ("> Task :app:testDebugUnitTest",
         "> Task :pocketrealm-llm:testDebugUnitTest",
         "> Task :pocketrealm-llm:test"),
        ("> Task :test ", "desktop"),  # an android log must not be the desktop run
    ),
    "desktop-test-counts.json": (
        ("> Task :test",),
        (":app:testDebugUnitTest",),  # a desktop log must not be the android run
    ),
}

# the words that trigger the (b) leg
_RE_EXECUTION_CLAIM = re.compile(r"re-execut|fresh|clean", re.IGNORECASE)
_TEST_TASKS = {
    "android-unit-counts.json": (
        ":app:testDebugUnitTest",
        ":pocketrealm-llm:testDebugUnitTest",
        ":pocketrealm-llm:test",
    ),
    "desktop-test-counts.json": (":test",),
}


def _artifact(name: str) -> dict | None:
    path = W0 / name
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _task_status(log_text: str, task: str) -> str:
    """The execution status the log records for [task] ('' = executed)."""
    match = re.search(rf"^> Task {re.escape(task)}(?: (\S+))?\s*$", log_text, re.MULTILINE)
    return match.group(1) or "" if match else "<absent>"


def test_each_counts_artifact_cites_its_own_suite_log():
    for name, (required, forbidden) in SUITE_MARKERS.items():
        artifact = _artifact(name)
        if artifact is None:
            continue  # no artifact in this tree: nothing to hold accountable
        log_name = artifact.get("log", "")
        log_path = W0 / log_name
        assert log_path.is_file(), (
            f"{name} cites log {log_name!r} which does not exist under "
            "out/completion/w0 — archive the run the numbers came from")
        text = log_path.read_text(encoding="utf-8", errors="replace")
        assert "BUILD SUCCESSFUL" in text, (
            f"{name} cites {log_name!r} which records no BUILD SUCCESSFUL")
        for marker in required:
            assert marker in text, (
                f"{name} cites {log_name!r} which never runs this suite's "
                f"own task ({marker}) — the cited log is not this suite's run")
        for foreign in forbidden:
            assert foreign not in text, (
                f"{name} cites {log_name!r} which carries another suite's "
                f"marker ({foreign!r}) — an android summary must never stand "
                "in for a desktop log (or vice versa)")


def test_a_re_execution_claim_requires_actually_executed_test_tasks():
    for name, tasks in _TEST_TASKS.items():
        artifact = _artifact(name)
        if artifact is None:
            continue
        note = " ".join(
            str(artifact.get(field, "")) for field in ("generated", "suite")
        )
        if not _RE_EXECUTION_CLAIM.search(note):
            continue  # the artifact makes no re-execution claim
        log_name = artifact.get("log", "")
        log_path = W0 / log_name
        if not log_path.is_file():
            continue  # leg 1 already failed loudly on the missing log
        text = log_path.read_text(encoding="utf-8", errors="replace")
        for task in tasks:
            status = _task_status(text, task)
            assert status == "", (
                f"{name} claims a fresh/clean re-execution but its cited log "
                f"records {task} as {status or 'executed'!r} — re-run the "
                "suite with --no-build-cache (and the clean legs) or word "
                "the claim to match the run")
