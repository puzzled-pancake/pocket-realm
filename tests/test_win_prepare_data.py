"""Contract for tools/win_prepare_data.py: the module imports on hosts
without LOCALAPPDATA (the data dir is resolved lazily in main() after the
os.name check, never at import time — the import-time read made the
windows-only guard unreachable with a bare KeyError), a Windows host
missing LOCALAPPDATA fails loud and actionable instead, and the ad.exe
resume skip is VALIDATION-based like the docstring promises: partial
dbc/maps outputs below the publication minimums are wiped and
re-extracted, never skipped on existence, while a genuinely complete
workspace skips every extractor."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import win_prepare_data as wpd  # noqa: E402


# ---------------- import-time LOCALAPPDATA (finding: DATADIR at module level)

def test_import_never_reads_localappdata() -> None:
    """env -u LOCALAPPDATA python -c 'import win_prepare_data' used to die
    with KeyError at module level (DATADIR read os.environ at import)."""
    env = {k: v for k, v in os.environ.items() if k != "LOCALAPPDATA"}
    env["PYTHONPATH"] = str(ROOT / "tools")
    result = subprocess.run([sys.executable, "-c", "import win_prepare_data"],
                            capture_output=True, text=True, env=env)
    assert result.returncode == 0, result.stderr


def test_main_windows_only_guard_reachable_without_localappdata(
        monkeypatch, capsys) -> None:
    monkeypatch.setattr(wpd.os, "name", "posix")
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.setattr(sys, "argv", ["win_prepare_data.py"])
    assert wpd.main() == 2
    assert "windows-only lane" in capsys.readouterr().err


def test_main_nt_missing_localappdata_fails_loud(monkeypatch, capsys) -> None:
    """A Windows host without LOCALAPPDATA must get an actionable message,
    never a KeyError or a silently wrong relative data dir. Since the D50/D51
    data-root centralization the resolver names BOTH env vars it would accept
    (POCKET_REALM_DATA_ROOT first) instead of the old derived default path."""
    monkeypatch.setattr(wpd.os, "name", "nt")
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.delenv("POCKET_REALM_DATA_ROOT", raising=False)
    monkeypatch.setattr(sys, "argv", ["win_prepare_data.py"])
    assert wpd.main() == 2
    err = capsys.readouterr().err
    assert "LOCALAPPDATA" in err
    assert "POCKET_REALM_DATA_ROOT" in err


# ---------------- ad resume skip is validation-based (finding: line 215)

def test_validate_ad_enforces_publication_minimums(tmp_path: Path) -> None:
    assert wpd.validate_ad(tmp_path) == [
        f"dbc=0 < {wpd.MIN_COUNTS['dbc']}",
        f"maps=0 < {wpd.MIN_COUNTS['maps']}"]
    (tmp_path / "dbc").mkdir()
    for i in range(wpd.MIN_COUNTS["dbc"]):
        (tmp_path / "dbc" / f"{i}.dbc").write_bytes(b"d")
    (tmp_path / "maps").mkdir()
    for i in range(wpd.MIN_COUNTS["maps"] - 1):
        (tmp_path / "maps" / f"{i:03d}.map").write_bytes(b"m")
    assert wpd.validate_ad(tmp_path) == [
        f"maps={wpd.MIN_COUNTS['maps'] - 1} < {wpd.MIN_COUNTS['maps']}"]
    (tmp_path / "maps" / "999.map").write_bytes(b"m")
    assert wpd.validate_ad(tmp_path) == []


def make_client(tmp_path: Path) -> Path:
    client = tmp_path / "client"
    (client / "Data").mkdir(parents=True)
    (client / "WoW.exe").write_bytes(b"MZ")
    (client / "Data" / "base.MPQ").write_bytes(b"mpq")
    return client


def pin_fixture_identity(monkeypatch, client: Path) -> None:
    """Round-6 R6-R2-1 added the registered_exe_identity gate to
    assert_client_shape; these synthetic-fixture tests must override the
    pin source to the fixture's own bytes (the gate's real hashing path
    still runs — same seam contract as test_win_prepare_data_flavors)."""
    import hashlib

    exe = client / "WoW.exe"

    def _identity(fid: str):
        return (exe.stat().st_size, hashlib.sha256(exe.read_bytes()).hexdigest())

    monkeypatch.setattr(wpd, "registered_exe_identity", _identity)


def make_extractors(tmp_path: Path) -> Path:
    extractors = tmp_path / "extractors"
    extractors.mkdir()
    for name in ("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe",
                 "MoveMapGen.exe"):
        (extractors / name).write_bytes(b"")
    return extractors


def fake_run(calls: list[str]):
    """Materialize what each real extractor would leave behind, in the cwd
    the tool runs it from (ad/vmap_extractor: the client; assembler/
    MoveMapGen: the workspace)."""
    def run(step: str, cwd: Path, cmd: list[str]) -> str:
        calls.append(step)
        if step == "ad":
            for i in range(wpd.MIN_COUNTS["dbc"] + 5):
                (cwd / "dbc").mkdir(parents=True, exist_ok=True)
                (cwd / "dbc" / f"{i:03d}.dbc").write_bytes(b"dbc")
            for i in range(wpd.MIN_COUNTS["maps"] + 5):
                (cwd / "maps").mkdir(parents=True, exist_ok=True)
                (cwd / "maps" / f"{i:03d}.map").write_bytes(b"map")
        elif step == "vmap_extractor":
            (cwd / "buildings").mkdir(parents=True, exist_ok=True)
            (cwd / "buildings" / "000").write_bytes(b"b")
        elif step == "vmap_assembler":
            (cwd / "vmaps").mkdir(parents=True, exist_ok=True)
            (cwd / "vmaps" / "000.vmtree").write_bytes(b"t")
        elif step == "mmaps":
            tiles = cwd / "mmaps"
            tiles.mkdir(parents=True, exist_ok=True)
            for path in (cwd / "maps").glob("*.map"):
                (tiles / f"{path.name[:3]}.mmap").write_bytes(b"h")
                (tiles / f"{path.name[:3]}0000.mmtile").write_bytes(b"m")
        return ""
    return run


def test_extract_reextracts_partial_ad_outputs(tmp_path: Path,
                                               monkeypatch) -> None:
    """An interrupted ad.exe run leaves a few dbc/maps behind; the skip
    must be gated on the publication minimums so the partials are wiped
    and ad re-runs (existence-based skipping left every later publish
    failing MIN_COUNTS until the workspace was wiped by hand)."""
    client = make_client(tmp_path)
    workspace = tmp_path / "workspace"
    (workspace / "dbc").mkdir(parents=True)
    (workspace / "maps").mkdir(parents=True)
    (workspace / "dbc" / "stale-partial.dbc").write_bytes(b"x")
    (workspace / "maps" / "000.map").write_bytes(b"x")
    (workspace / "maps" / "001.map").write_bytes(b"x")
    calls: list[str] = []
    pin_fixture_identity(monkeypatch, client)
    monkeypatch.setattr(wpd, "EXTRACTORS", make_extractors(tmp_path))
    monkeypatch.setattr(wpd, "WORKSPACE", workspace)
    monkeypatch.setattr(wpd, "run", fake_run(calls))

    wpd.extract(client)

    assert "ad" in calls
    assert not (workspace / "dbc" / "stale-partial.dbc").exists()
    assert len(list((workspace / "dbc").glob("*.dbc"))) >= wpd.MIN_COUNTS["dbc"]
    assert len(list((workspace / "maps").glob("*.map"))) >= wpd.MIN_COUNTS["maps"]
    # The client is left byte-clean: extractor dirs moved out, nothing else.
    assert sorted(e.name.lower() for e in client.iterdir()) == ["data", "wow.exe"]


def test_extract_skips_all_steps_when_workspace_complete(tmp_path: Path,
                                                         monkeypatch) -> None:
    client = make_client(tmp_path)
    pin_fixture_identity(monkeypatch, client)
    workspace = tmp_path / "workspace"
    for i in range(wpd.MIN_COUNTS["dbc"]):
        (workspace / "dbc").mkdir(parents=True, exist_ok=True)
        (workspace / "dbc" / f"{i:03d}.dbc").write_bytes(b"dbc")
    map_ids = []
    for i in range(wpd.MIN_COUNTS["maps"]):
        (workspace / "maps").mkdir(parents=True, exist_ok=True)
        mid = f"{i:03d}"
        map_ids.append(mid)
        (workspace / "maps" / f"{mid}.map").write_bytes(b"map")
    (workspace / "vmaps").mkdir()
    (workspace / "vmaps" / "000.vmtree").write_bytes(b"t")
    (workspace / "mmaps").mkdir()
    for mid in map_ids:
        (workspace / "mmaps" / f"{mid}.mmap").write_bytes(b"h")
        (workspace / "mmaps" / f"{mid}0000.mmtile").write_bytes(b"m")
    calls: list[str] = []
    monkeypatch.setattr(wpd, "EXTRACTORS", make_extractors(tmp_path))
    monkeypatch.setattr(wpd, "WORKSPACE", workspace)
    monkeypatch.setattr(wpd, "run", fake_run(calls))

    wpd.extract(client)

    assert calls == []
