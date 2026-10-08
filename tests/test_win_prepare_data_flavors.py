"""Per-flavor contract for tools/win_prepare_data.py (port plan S2/S5,
census lanes item 6 / F4 + item 8): the manifest identities, per-flavor
extractor/workspace roots, client-shape checks, and publication floors are
all keyed by flavor — a tbc run must resolve the tbc lane end to end and
refuse a wrong-shaped client BEFORE any extractor runs. The un-namespaced
legacy contract (vanilla defaults on plain import) is pinned by
tests/test_win_prepare_data.py and must keep passing unmodified.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import win_prepare_data as wpd  # noqa: E402
from pathlib import Path as _P
WPD = _P(wpd.__file__)  # noqa: E402


def test_every_identity_carries_a_full_lane() -> None:
    """A FLAVOR_IDENTITIES entry is the contract that the flavor's whole
    lane exists: identity + extractor/workspace root + client shape +
    publication floors. A partial row would publish an unbootable
    generation (the refusal the old vanilla-only table made loud)."""
    for flavor, identity in wpd.FLAVOR_IDENTITIES.items():
        assert wpd.WIN_BUILD_ROOTS[flavor].name.endswith(flavor) or flavor == "vanilla", \
            f"{flavor}: build root must carry the flavor suffix"
        assert flavor in wpd.CLIENT_SHAPE, f"{flavor}: no client shape"
        assert set(wpd.FLAVOR_MIN_COUNTS[flavor]) == {
            "dbc", "maps", "vmapTrees", "mmapMaps", "mmapTiles"}, \
            f"{flavor}: publication floors must cover every count"
        assert identity["clientBuild"] > 0 and identity["cmangosFamily"], flavor


def test_flavor_identities_pin_the_registry_builds() -> None:
    """The manifest identity must match ClientFlavor's registry (the
    envelope resolver maps cmangosFamily -> ClientFlavor.serverFamily:
    classic/tbc/wotlk) — a family string the registry does not know would
    resolve to no flavor at boot even though the manifest published."""
    assert wpd.FLAVOR_IDENTITIES["vanilla"] == {"clientBuild": 5875, "cmangosFamily": "classic"}
    assert wpd.FLAVOR_IDENTITIES["tbc"] == {"clientBuild": 8606, "cmangosFamily": "tbc"}
    assert wpd.FLAVOR_IDENTITIES["wotlk"] == {"clientBuild": 12340, "cmangosFamily": "wotlk"}


def test_select_flavor_rebinds_lane_paths_and_floors() -> None:
    """select_flavor binds extractor home + workspace + floors to the
    flavor's build root: the workspace MUST be flavor-scoped (a shared
    workspace would let vanilla's complete outputs satisfy a tbc run's
    validation-based resume skip and publish vanilla bytes as tbc)."""
    import importlib
    for flavor in ("vanilla", "tbc", "wotlk"):
        importlib.reload(wpd)  # reset the module-level vanilla defaults
        wpd.select_flavor(flavor)
        root = wpd.WIN_BUILD_ROOTS[flavor]
        assert wpd.FLAVOR == flavor
        assert wpd.EXTRACTORS == root / "bin/x64_Release/Extractors"
        assert wpd.WORKSPACE == root / "data-prep"
        assert wpd.MIN_COUNTS == wpd.FLAVOR_MIN_COUNTS[flavor]
    importlib.reload(wpd)
    with pytest.raises(wpd.StepFailure, match="no qualified manifest identity"):
        wpd.select_flavor("cataclysm")


def test_default_lane_stays_vanilla_on_plain_import() -> None:
    """Direct importers (tests/test_win_prepare_data.py, publish-only
    callers) keep the historical unsuffixed vanilla paths until
    select_flavor rebinds them."""
    import importlib
    importlib.reload(wpd)
    assert wpd.FLAVOR == "vanilla"
    assert wpd.EXTRACTORS == wpd.WIN_BUILD_ROOTS["vanilla"] / "bin/x64_Release/Extractors"
    assert wpd.WORKSPACE == wpd.WIN_BUILD_ROOTS["vanilla"] / "data-prep"
    assert wpd.MIN_COUNTS == wpd.FLAVOR_MIN_COUNTS["vanilla"]


def make_client(tmp_path: Path, flavor: str) -> Path:
    client = tmp_path / f"client-{flavor}"
    (client / "Data").mkdir(parents=True)
    (client / "WoW.exe").write_bytes(b"MZ")
    for entry in wpd.CLIENT_SHAPE[flavor]["data_entries"]:
        if entry == wpd.LOCALE_ENTRY:
            (client / "Data" / "enUS").mkdir()
            (client / "Data" / "enUS" / "locale-enUS.MPQ").write_bytes(b"mpq")
        else:
            (client / "Data" / entry).write_bytes(b"mpq")
    return client


def match_fixture_identity(monkeypatch, client: Path) -> None:
    """Point registered_exe_identity at the FIXTURE exe (the gate's real
    hashing path runs; only the pin source is overridden)."""
    import hashlib

    exe = client / "WoW.exe"

    def _identity(fid: str):
        return (exe.stat().st_size, hashlib.sha256(exe.read_bytes()).hexdigest())

    monkeypatch.setattr(wpd, "registered_exe_identity", _identity)


@pytest.mark.parametrize("flavor", ["vanilla", "tbc", "wotlk"])
def test_client_shape_accepts_its_own_flavor(tmp_path: Path, flavor: str,
                                             monkeypatch) -> None:
    client = make_client(tmp_path, flavor)
    match_fixture_identity(monkeypatch, client)
    wpd.assert_client_shape(client, flavor)  # no raise


@pytest.mark.parametrize("flavor,foreign", [
    ("tbc", "vanilla"), ("wotlk", "vanilla"), ("wotlk", "tbc"), ("tbc", "wotlk")])
def test_client_shape_refuses_foreign_clients(tmp_path: Path, flavor: str,
                                              foreign: str) -> None:
    """The exact wrong-client hazards the shape check exists for: a vanilla
    client (base.MPQ only) handed to a tbc/wotlk lane, and the two
    expansion clients confused with each other (wotlk adds lichking.MPQ;
    tbc does not carry it)."""
    client = make_client(tmp_path, foreign)
    with pytest.raises(wpd.StepFailure, match="does not look like a " + flavor):
        wpd.assert_client_shape(client, flavor)


def test_client_shape_accepts_any_known_locale(tmp_path: Path, monkeypatch) -> None:
    """The locale entry matches any KNOWN locale dir, not just enUS — a
    deDE client is as genuine as the staged enUS one."""
    client = make_client(tmp_path, "tbc")
    (client / "Data" / "enUS").rename(client / "Data" / "deDE")
    match_fixture_identity(monkeypatch, client)
    wpd.assert_client_shape(client, "tbc")  # no raise


def test_client_shape_names_the_missing_entries(tmp_path: Path) -> None:
    client = tmp_path / "empty"
    (client / "Data").mkdir(parents=True)
    (client / "WoW.exe").write_bytes(b"MZ")
    with pytest.raises(wpd.StepFailure, match="common.MPQ missing.*expansion.MPQ missing"):
        wpd.assert_client_shape(client, "tbc")


def test_extract_refuses_wrong_flavor_client_before_any_extractor(tmp_path,
                                                                  monkeypatch):
    """extract() must die on the shape check BEFORE running any step or
    touching the client: no extractor dir may be created, no step run."""
    import importlib
    importlib.reload(wpd)
    wpd.select_flavor("tbc")
    extractors = tmp_path / "extractors"
    extractors.mkdir()
    for name in ("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe",
                 "MoveMapGen.exe"):
        (extractors / name).write_bytes(b"")
    monkeypatch.setattr(wpd, "EXTRACTORS", extractors)
    client = make_client(tmp_path, "vanilla")
    calls: list[str] = []
    monkeypatch.setattr(wpd, "run", lambda *a, **k: calls.append(a[0]) or "")
    with pytest.raises(wpd.StepFailure, match="does not look like a tbc client root"):
        wpd.extract(client)
    assert calls == []
    assert not (client / "dbc").exists()
    importlib.reload(wpd)


def test_main_refuses_unknown_flavor_loud(monkeypatch, capsys, tmp_path: Path) -> None:
    """--flavor cataclysm (well-formed slug, no lane) still dies loud with
    the qualified list — the refusal contract the vanilla-only era pinned,
    now against the three-lane table."""
    import importlib
    importlib.reload(wpd)
    monkeypatch.setattr(wpd.os, "name", "nt")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setattr(sys, "argv",
                        ["win_prepare_data.py", "--flavor", "cataclysm"])
    assert wpd.main() == 2
    err = capsys.readouterr().err
    assert "no qualified manifest identity" in err
    assert "tbc, vanilla, wotlk" in err  # sorted, like the refusal prints
    importlib.reload(wpd)


def test_min_count_floors_carry_measured_provenance_or_a_named_placeholder() -> None:
    """R4-1 (code-red round 1): every FLAVOR_MIN_COUNTS row must prove its
    provenance in the source comments — either a measured annotation whose
    recorded counts are >= that row's floors (so the floor is honestly
    BELOW the measured yield), or an explicit PLACEHOLDER marker naming the
    retiring wave. The wotlk row fails this pin the moment W2 lands its
    measured annotation is still missing — and a placeholder that stops
    naming W2 fails too."""
    import importlib
    importlib.reload(wpd)
    source = WPD.read_text(encoding="utf-8")
    block = source.split("FLAVOR_MIN_COUNTS = {")[0]
    floors = wpd.FLAVOR_MIN_COUNTS
    for flavor in floors:
        measured = re.search(
            rf"# {flavor} measured \(generation ([0-9a-f-]+)[^)]*\):"
            rf"[\s\S]*?dbc=(\d+), maps=(\d+), vmapTrees=(\d+),"
            rf" mmapMaps=(\d+), mmapTiles=(\d+)",
            block,
        )
        if measured is not None:
            measured = measured.groups()
        if measured is None and flavor == "vanilla":
            # the historical-measured form (vanilla ONLY): "the historical
            # round numbers below its measured first generation (dbc=..., ...)"
            measured = re.search(
                rf"historical round numbers below its measured[^（]*?"
                rf"dbc=(\d+), maps=(\d+), vmapTrees=(\d+), mmapMaps=(\d+),"
                rf" mmapTiles=(\d+)",
                block,
            )
            if measured is not None:
                measured = (None, *measured.groups())
        placeholder = re.search(
            rf"# {flavor} PLACEHOLDER pending (W\d+) re-derive", block)
        assert measured or placeholder, (
            f"{flavor} floors carry neither a measured annotation nor a "
            "PLACEHOLDER marker naming the retiring wave"
        )
        if measured:
            keys = ("dbc", "maps", "vmapTrees", "mmapMaps", "mmapTiles")
            recorded = dict(zip(keys, (int(g) for g in measured[1:])))
            for key, measured_value in recorded.items():
                assert floors[flavor][key] <= measured_value, (
                    f"{flavor} floor {key}={floors[flavor][key]} exceeds its "
                    f"measured {measured_value} — a floor above the real "
                    "yield bricks the lane"
                )
        elif flavor == "wotlk":
            # the placeholder must name the wave that owns the re-derive
            assert placeholder and placeholder.group(1) == "W2", (
                "the wotlk placeholder must name W2 (the plan's WotLK "
                "data-prep completion wave) as the retiring wave"
            )


def test_doctored_rename_attack_is_refused_by_the_identity_gate(
        tmp_path: Path) -> None:
    """Round-6 R6-R2-1: a tbc client set doctored into vanilla's SHAPE
    (common.MPQ renamed to base.MPQ, expansion entries removed) must be
    REFUSED — the shape check is name-presence only, so the exe identity
    gate (sha256+size vs the flavor's S5.1 registration) is what stops a
    wrong-flavor set publishing a self-declared vanilla-5875 manifest."""
    import pytest

    client = make_client(tmp_path, "tbc")
    # doctor: rename the tbc-set entries into vanilla's shape — tbc's
    # common.MPQ becomes vanilla's base.MPQ; the locale DIR carries the
    # locale MPQ so the whole dir renames with it (tbc's forbidden markers
    # for the vanilla lane — expansion.MPQ — are deleted, exactly the
    # rename attack the name-presence gate cannot see).
    (client / "Data" / "common.MPQ").rename(client / "Data" / "base.MPQ")
    (client / "Data" / "expansion.MPQ").unlink()
    # real registered pins (NOT the fixture override): the fixture exe
    # (2 bytes) cannot match vanilla's registered 5875 identity
    with pytest.raises(wpd.StepFailure, match="exe identity mismatch"):
        wpd.assert_client_shape(client, "vanilla")
