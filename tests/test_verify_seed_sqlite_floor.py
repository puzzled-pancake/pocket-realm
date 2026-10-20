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


# ---------------------------------------------------------------------------
# Review round 2:
# replays the production seed SQL), so the download path must enforce the
# repo's hash-pin discipline BEFORE any extract or execute — the banner
# check alone is not provenance.
# the lockfile leg is only ever skipped EXPLICITLY — an
# unknown reported ABI refuses loud, and the no-aapt arm marks the summary
# (and the banner) lockfile-leg-SKIPPED instead of claiming a bare pass.
# ---------------------------------------------------------------------------

def _fake_apk(tmp_path: Path, raws: dict[str, bytes]) -> tuple[Path, Path]:
    apk_path = tmp_path / "tiny-server.apk"
    with zipfile.ZipFile(apk_path, "w") as zf:
        for db, raw in raws.items():
            zf.writestr(f"{floor.SQLZ_PREFIX}{db}.sqlz", gzip.compress(raw))
    baseline = tmp_path / "baseline.json"
    baseline.write_text(json.dumps({
        "transcript_digests": {db: hashlib.sha256(raw).hexdigest()
                               for db, raw in raws.items()},
    }), encoding="utf-8")
    return apk_path, baseline


def test_shell_archive_digest_enforced_before_extract_or_execute(
        tmp_path, monkeypatch):
    """A cached archive whose digest mismatches the SHELL_URLS pin is
    deleted and refused — the error is the hash mismatch, raised BEFORE the
    zip is extracted or any exe runs (the store holds no sqlite3.exe
    afterwards)."""
    store = tmp_path / "shells"
    store.mkdir()
    archive = store / "sqlite-3.18.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("sqlite3.exe", b"tampered")  # valid zip, wrong digest
    monkeypatch.setattr(floor, "SHELL_STORE", store)
    monkeypatch.setattr(
        floor, "SHELL_URLS",
        {"3.18": ("https://sqlite.org.example/not-fetched.zip",
                  "b" * 64, "3.18.0")})
    with pytest.raises(RuntimeError, match="!= pinned"):
        floor.shell_for("3.18")
    assert not archive.exists(), "the mismatched archive must be deleted"
    assert not (store / "3.18").exists(), (
        "nothing may be extracted before the digest verifies")


def test_matching_hash_proceeds_past_the_digest_leg(tmp_path, monkeypatch):
    """A hash-honest archive gets past the digest leg (the failure that
    fires next is the extraction stage's no-sqlite3.exe complaint — never
    the hash mismatch). No executable is shipped in the fixture, so the
    banner/execute stage is never reached here."""
    import io
    store = tmp_path / "shells"
    store.mkdir()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zf:
        zf.writestr("README.txt", "hash-honest but exe-less")
    archive = store / "sqlite-3.18.zip"
    archive.write_bytes(buffer.getvalue())
    honest_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    monkeypatch.setattr(floor, "SHELL_STORE", store)
    monkeypatch.setattr(
        floor, "SHELL_URLS",
        {"3.18": ("https://sqlite.org.example/not-fetched.zip",
                  honest_digest, "3.18.0")})
    with pytest.raises(RuntimeError) as excinfo:
        floor.shell_for("3.18")
    assert "no sqlite3.exe" in str(excinfo.value)
    assert "!= pinned" not in str(excinfo.value)
    assert (store / "3.18").is_dir(), "the honest archive was extracted"


def test_shell_urls_all_carry_pinned_digests():
    """The pin shape itself: every SHELL_URLS entry carries a 64-hex digest
    (the R3-2 fix) — a future floor addition cannot reintroduce the
    unpinned-download class."""
    for version, entry in floor.SHELL_URLS.items():
        assert len(entry) == 3, (version, entry)
        url, digest, banner = entry
        assert len(digest) == 64 and all(c in "0123456789abcdef" for c in digest), (
            version, digest)
        assert url.startswith("https://"), url


def test_unknown_reported_abi_refuses_loud(tmp_path, monkeypatch):
    """R2-4: an APK reporting an ABI outside LOCKFILES is a lane this tool
    has no sqlite lockfile pin for — refuse loud naming the ABI and the
    pinned set, never baseline-only with a pass banner."""
    raws = {db: CLEAN_TX for db in floor.DATABASES}
    apk_path, baseline = _fake_apk(tmp_path, raws)
    monkeypatch.setattr(floor, "BASELINE", baseline)
    monkeypatch.setattr(
        floor, "apk_statics",
        lambda apk: {"abi": ["armeabi-v7a"], "minSdk": "26",
                     "package": "com.pocketrealm.test"})
    with pytest.raises(RuntimeError) as excinfo:
        floor.verify_apk(apk_path, ("3.18",))
    message = str(excinfo.value)
    assert "armeabi-v7a" in message
    assert "x86_64" in message and "arm64-v8a" in message, (
        "the pinned ABI set is named in the refusal")


def test_missing_statics_marks_the_lockfile_leg_skipped(
        tmp_path, monkeypatch):
    """R2-4: the no-aapt arm still works (the baseline digest chain runs)
    but the summary explicitly marks the lockfile leg SKIPPED — and main's
    banner branch keys on that marker, so the bare 'PREFLIGHT PASS' banner
    can never claim the lockfile leg ran."""
    raws = {db: CLEAN_TX for db in floor.DATABASES}
    apk_path, baseline = _fake_apk(tmp_path, raws)
    monkeypatch.setattr(floor, "BASELINE", baseline)
    monkeypatch.setattr(
        floor, "apk_statics",
        lambda apk: {"abi": None, "minSdk": None, "package": None})
    monkeypatch.setattr(floor, "replay", lambda seeds, floors, timeout:
                        {f"{floors[0]}/{db}": 0.1 for db in seeds})
    summary = floor.verify_apk(apk_path, ("3.18",))
    assert summary["lockfile_leg"].startswith("SKIPPED"), summary
    # and the main() banner branch is keyed on the same marker
    source = (ROOT / "tools" / "verify_seed_sqlite_floor.py").read_text(
        encoding="utf-8")
    assert 's.get("lockfile_leg") != "verified"' in source
    assert "lockfile leg SKIPPED" in source


# ------------------------------------------------- store trust

def _honest_store(tmp_path: Path, version: str) -> tuple[Path, Path, Path]:
    """A store shaped like the live one: extracted exe + OK marker + the
    retained pin-digest-honest archive. Skips when the real pinned store
    has not been fetched (tests never download)."""
    real_archive = floor.SHELL_STORE / f"sqlite-{version}.zip"
    real_exes = list((floor.SHELL_STORE / version).glob("**/sqlite3.exe"))
    if not real_archive.is_file() or not real_exes:
        pytest.skip(f"pinned shell store not fetched ({version})")
    store = tmp_path / "shells"
    dest = store / version / "sqlite-tools-win32-x86-3180000"
    dest.mkdir(parents=True)
    exe = dest / "sqlite3.exe"
    exe.write_bytes(real_exes[0].read_bytes())
    archive = store / f"sqlite-{version}.zip"
    archive.write_bytes(real_archive.read_bytes())
    (store / version / "OK").write_text("3.18.0\n", encoding="utf-8")
    return store, exe, archive


def test_marker_path_re_attests_and_returns_honest_exe(
        tmp_path, monkeypatch):
    """The OK marker no longer short-circuits the attestation: an honest
    store (pin-digest archive + banner-stating exe) still passes BOTH
    checks on the marker path and returns the exe."""
    version = floor.DEFAULT_FLOORS[0]
    store, exe, _archive = _honest_store(tmp_path, version)
    monkeypatch.setattr(floor, "SHELL_STORE", store)
    assert floor.shell_for(version) == exe


def test_tampered_extracted_exe_is_never_served(tmp_path, monkeypatch):
    """The reviewer's PoC, mirrored: OK marker + pin-digest-honest archive
    + a TAMPERED extracted sqlite3.exe. The marker path must re-run the
    banner attestation, refuse the exe, clear the marker, and never return
    the tampered path (the tampered exe's output is simulated by stubbing
    run() — no forged executable is executed)."""
    version = floor.DEFAULT_FLOORS[0]
    store, exe, _archive = _honest_store(tmp_path, version)
    exe.write_bytes(b"MZ tampered - not the pinned shell")
    monkeypatch.setattr(floor, "SHELL_STORE", store)
    monkeypatch.setattr(floor, "run", lambda cmd, **_: "9.99.0")
    with pytest.raises(RuntimeError,
                       match="no longer states the pinned banner"):
        floor.shell_for(version)
    assert not (store / version / "OK").exists(), (
        "the trust marker must be cleared on a failed attestation")
    assert exe.exists()  # refusal, not silent self-repair mid-call


def test_tampered_cached_archive_on_marker_path_refuses(
        tmp_path, monkeypatch):
    """A corrupted retained archive behind a live OK marker is caught by
    the marker path's re-hash: archive+marker deleted, loud refusal naming
    the pinned digest (the next run re-fetches)."""
    version = floor.DEFAULT_FLOORS[0]
    store, _exe, archive = _honest_store(tmp_path, version)
    archive.write_bytes(b"corrupted archive bytes")
    monkeypatch.setattr(floor, "SHELL_STORE", store)
    with pytest.raises(RuntimeError, match="!= pinned"):
        floor.shell_for(version)
    assert not archive.exists() and not (store / version / "OK").exists()
