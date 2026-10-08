"""Pins the transactional seed-floor replay contract (LVF-server-db-016).

The 1.1M-statement classiccharacters transcript could not finish on
either pinned sqlite3 shell under per-statement autocommit (>1800s tool
cap on 3.18 and 3.32, >2700s uncapped diagnostic, CPU-verified). The
floor tool now replays every transcript inside ONE explicit
transaction - the same shape the device replay itself uses
(DatabaseEngine.replaySeedDatabase: beginTransaction..endTransaction
around the whole transcript) - and carries an honest, configurable
per-replay bound.

Pinned here:
  - the shell argv wraps the transcript in `.bail on` / `BEGIN;` /
    `COMMIT;`, in that order, with the forward-slash path the win32
    shells' dot-command escaping requires;
  - a transcript carrying its own transaction control fails LOUD (the
    wrapper contract), while transaction words in data position or in
    trigger bodies do not trip the guard;
  - against the REAL pinned shells (skipped where the immutable shell
    store has not been fetched): a clean transcript replays OK on both
    floors with measured durations, and a 3.33+ `UPDATE..FROM` gate
    statement is still REJECTED on 3.18 - the floor's version-gate
    purpose survives the transactional wrapper;
  - the per-replay bound is plumbed and recorded: DEFAULT_TIMEOUT,
    --timeout (as replay_bound_seconds in the verify_apk summary).
"""
from __future__ import annotations

import gzip
import hashlib
import json
import platform
import sys
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import verify_seed_sqlite_floor as floor  # noqa: E402


def shell_exe(version: str) -> Path | None:
    """The already-fetched pinned shell, or None (immutable store:
    tests never download)."""
    shells = list((floor.SHELL_STORE / version).glob("**/sqlite3.exe"))
    return shells[0] if shells else None


def needs_pinned_shells(fn):
    missing = [v for v in floor.DEFAULT_FLOORS if shell_exe(v) is None]
    return pytest.mark.skipif(
        platform.system() != "Windows" or bool(missing),
        reason=f"pinned win32 shell store not fetched "
               f"(build/seed-floor-shells; missing {missing})")(fn)


CLEAN_TX = (b"CREATE TABLE t(a);\n"
            b"INSERT INTO t VALUES (1);\n"
            b"INSERT INTO t VALUES (2);\n")


# ---------------------------------------------------------------- wrapper

def test_replay_shell_args_wrap_transcript_in_one_transaction(tmp_path):
    sql = tmp_path / "classiccharacters.sql"
    sql.write_bytes(CLEAN_TX)
    args = floor.replay_shell_args(sql)
    assert args[0] == ".bail on", "any error must abort the shell"
    assert args[1] == "BEGIN;", "the transaction must open before .read"
    assert args[-1] == "COMMIT;", "the transaction must commit at the end"
    assert args[2].startswith(".read ")
    read_path = args[2][len(".read "):]
    assert "\\" not in read_path, (
        "dot-command args eat backslashes on the win32 shells")
    assert read_path == sql.resolve().as_posix()


def test_default_bound_is_the_recorded_cap():
    assert floor.DEFAULT_TIMEOUT == 1800


# ------------------------------------------------- wrapper contract guard

@pytest.mark.parametrize("bad", [
    b"BEGIN;\n",
    b"COMMIT;\n",
    b"ROLLBACK;\n",
    b"  commit ;\n",
    b"\tBegin Transaction\n",
    b"CREATE TABLE t(a);\nCOMMIT;\n",
])
def test_guard_rejects_own_transaction_control(bad):
    with pytest.raises(RuntimeError, match="wrapper contract"):
        floor.assert_no_transaction_control("db", bad)


def test_guard_accepts_transaction_words_in_data_position():
    clean = (b"INSERT INTO log VALUES ('BEGIN x');\n"
             b"UPDATE t SET note = 'COMMIT; later' WHERE a = 1;\n")
    floor.assert_no_transaction_control("db", clean)


def test_guard_accepts_trigger_bodies():
    # trigger bodies use BEGIN..END blocks and may never contain
    # transaction control in SQLite - END alone is not a guard hit
    trigger = (b"CREATE TRIGGER tg AFTER INSERT ON t BEGIN\n"
               b"  UPDATE t SET a = 1;\n"
               b"END;\n")
    floor.assert_no_transaction_control("db", trigger)


# ------------------------------------------- real pinned-shell integrations

@needs_pinned_shells
def test_replay_runs_clean_transcript_on_both_pinned_floors(monkeypatch):
    monkeypatch.setattr(floor, "shell_for",
                        lambda version: shell_exe(version))
    durations = floor.replay({"tiny": CLEAN_TX}, floor.DEFAULT_FLOORS)
    assert set(durations) == {"3.18/tiny", "3.32/tiny"}
    assert all(d > 0.0 for d in durations.values()), (
        "per-replay duration must be measured, not asserted")


@needs_pinned_shells
def test_replay_still_rejects_version_gate_on_318(monkeypatch):
    # UPDATE..FROM needs sqlite 3.33+ (the tool docstring's own gate):
    # the 3.18 shell must reject it INSIDE the transaction wrapper too,
    # i.e. batching must not mask a floor violation
    monkeypatch.setattr(floor, "shell_for",
                        lambda version: shell_exe(version))
    gated = CLEAN_TX + b"UPDATE t SET a = o.a FROM (SELECT 2 AS a) o;\n"
    with pytest.raises(RuntimeError, match="REJECTED"):
        floor.replay({"gate": gated}, ("3.18",))


@needs_pinned_shells
def test_replay_bound_is_recorded_and_configurable(tmp_path, monkeypatch):
    # a fake-but-hash-honest APK: the four seed assets as tiny clean
    # transcripts, baseline pinned to their real digests, no aapt
    raws = {db: CLEAN_TX.replace(b"TABLE t(", f"TABLE {db}(".encode())
             .replace(b"INTO t ", f"INTO {db} ".encode())
            for db in floor.DATABASES}
    apk_path = tmp_path / "tiny-server.apk"
    with zipfile.ZipFile(apk_path, "w") as zf:
        for db, raw in raws.items():
            zf.writestr(f"{floor.SQLZ_PREFIX}{db}.sqlz", gzip.compress(raw))
    baseline = tmp_path / "baseline.json"
    baseline.write_text(json.dumps({
        "transcript_digests": {db: hashlib.sha256(raw).hexdigest()
                               for db, raw in raws.items()},
    }), encoding="utf-8")
    monkeypatch.setattr(floor, "BASELINE", baseline)
    monkeypatch.setattr(floor, "apk_statics",
                        lambda apk: {"abi": None, "minSdk": None,
                                     "package": None})
    monkeypatch.setattr(floor, "shell_for",
                        lambda version: shell_exe(version))
    summary = floor.verify_apk(apk_path, ("3.18",), timeout=1234)
    assert summary["replay_bound_seconds"] == 1234
    assert set(summary["replay_seconds"]) == {
        f"3.18/{db}" for db in floor.DATABASES}
    assert all(v > 0.0 for v in summary["replay_seconds"].values())
