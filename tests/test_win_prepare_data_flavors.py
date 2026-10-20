"""Per-flavor contract for tools/win_prepare_data.py (port plan S2/S5,
census lanes item 6 / F4 + item 8): the manifest identities, per-flavor
extractor/workspace roots, client-shape checks, and publication floors are
all keyed by flavor — a tbc run must resolve the tbc lane end to end and
refuse a wrong-shaped client BEFORE any extractor runs. The un-namespaced
legacy contract (vanilla defaults on plain import) is pinned by
tests/test_win_prepare_data.py and must keep passing unmodified.
"""
from __future__ import annotations

import json
import os
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
    resolve to no flavor at boot even though the manifest published.

    R3-2 (review round 3): this pin used to assert only the tool's OWN
    literals while claiming to mirror the manifest — a flavor.json re-pin
    left the prep tool stamping a stale clientBuild into published
    generations. The mirror is now mechanical: every FLAVOR_IDENTITIES
    entry must equal the manifest's build + the cmangosFamily its branch
    carries, and the id sets must agree exactly."""
    from tools import flavor_layout
    manifest = flavor_layout.load_flavor_manifest()
    manifest_ids = {f["id"] for f in manifest["flavors"]}
    assert set(wpd.FLAVOR_IDENTITIES) == manifest_ids, (
        "FLAVOR_IDENTITIES ids drifted from schemas/flavor.json - the prep "
        "lane must cover exactly the registered flavors")
    # the cmangosFamily each flavor's pinned core branch carries, via the
    # naming schema of record (flavor_layout's submodule paths)
    family_by_id = {
        "vanilla": "classic", "tbc": "tbc", "wotlk": "wotlk",
    }
    for block in manifest["flavors"]:
        fid = block["id"]
        identity = wpd.FLAVOR_IDENTITIES[fid]
        assert identity["clientBuild"] == block["build"], (
            f"{fid}: FLAVOR_IDENTITIES clientBuild "
            f"{identity['clientBuild']} != manifest build {block['build']} "
            "- the prep tool would stamp a stale client build into "
            "published data-manifest generations")
        assert identity["cmangosFamily"] == family_by_id[fid], (
            f"{fid}: cmangosFamily drifted from the pinned branch family")
    # and the historical literals stay exact (a registry-side re-pin fails
    # the manifest leg above; this keeps the known-good values named)
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


def test_wotlk_mmtile_contract_accepts_the_alt_form_and_the_others_refuse_it() -> None:
    """W2: wotlk's extractor legitimately emits the alt tile form
    %03u%02i%02i_NN.mmtile (cmangos-wotlk MapBuilder.cpp:1090 — the
    per-building-permutation navmesh tiles its own runtime loads via
    TILE_ALT_FILE_NAME_FORMAT, MoveMap.cpp:28), so the wotlk lane accepts
    exactly one _NN suffix while vanilla/tbc keep the strict 7-digit
    contract: their generators have no alt branch, so an alt tile in their
    workspaces is cross-flavor contamination and must still refuse. Garbage
    names (a go%04u gameobject tile, an over-long suffix) refuse on every
    lane."""
    import importlib
    importlib.reload(wpd)
    assert wpd.FLAVOR_MMTILE_NAME["wotlk"].match("6313035_01")
    assert wpd.FLAVOR_MMTILE_NAME["wotlk"].match("6163029_24")
    for flavor in ("vanilla", "tbc"):
        assert not wpd.FLAVOR_MMTILE_NAME[flavor].match("6313035_01"), (
            f"{flavor}: the alt tile form must stay a wotlk-only shape")
    for flavor in ("vanilla", "tbc", "wotlk"):
        assert wpd.FLAVOR_MMTILE_NAME[flavor].match("0302731")
        assert not wpd.FLAVOR_MMTILE_NAME[flavor].match("go0042")
        assert not wpd.FLAVOR_MMTILE_NAME[flavor].match("6313035_011")
        assert not wpd.FLAVOR_MMTILE_NAME[flavor].match("6313035_x1")
    # select_flavor rebinds the ACTIVE contract; plain import keeps vanilla.
    wpd.select_flavor("wotlk")
    assert wpd.MMTILE_NAME is wpd.FLAVOR_MMTILE_NAME["wotlk"]
    importlib.reload(wpd)
    assert wpd.MMTILE_NAME is wpd.FLAVOR_MMTILE_NAME["vanilla"]


def test_wotlk_tileless_map_waived_only_on_a_failure_clean_stdout(
        tmp_path: Path, capsys) -> None:
    """The wotlk generator silently yields zero tiles for some shipped maps
    (maps 605/606 measured 2026-10-11: tiles built, nothing written, exit 0 —
    the no-vertices skip branch at cmangos-wotlk MapBuilder.cpp:1042-1050),
    so a tile-less map is accepted ONLY on the wotlk lane AND only when the
    producing run's MoveMapGen stdout scanned clean of per-map failures. The
    resume-skip context (no run stdout to trust) and the vanilla/tbc lanes
    stay strict: an interrupted run can never skip as complete, and a
    tile-less map in their workspaces still refuses."""
    import importlib
    importlib.reload(wpd)

    def workspace_with_a_tileless_map(flavor: str) -> Path:
        wpd.select_flavor(flavor)
        ws = tmp_path / flavor
        (ws / "maps").mkdir(parents=True, exist_ok=True)
        (ws / "maps" / "5711024.map").write_bytes(b"m")
        (ws / "maps" / "6055956.map").write_bytes(b"m")
        (ws / "mmaps").mkdir(parents=True, exist_ok=True)
        (ws / "mmaps" / "571.mmap").write_bytes(b"h")
        (ws / "mmaps" / "571.mmap").write_bytes(b"h")
        (ws / "mmaps" / "5711024.mmtile").write_bytes(b"m")
        return ws

    # strict contexts: the resume-skip call (stdout None) on every flavor,
    # and the post-run clean-stdout call on vanilla/tbc.
    for flavor in ("vanilla", "tbc", "wotlk"):
        ws = workspace_with_a_tileless_map(flavor)
        problems = wpd.validate_mmaps(ws)
        assert problems and "605" in problems[0], flavor
    for flavor in ("vanilla", "tbc"):
        ws = workspace_with_a_tileless_map(flavor)
        problems = wpd.validate_mmaps(ws, movemap_stdout_clean=True)
        assert problems and "605" in problems[0], flavor
    # the waiver: wotlk + failure-clean stdout accepts exactly the empty map
    # (loudly), while a covered map stays covered.
    ws = workspace_with_a_tileless_map("wotlk")
    assert wpd.validate_mmaps(ws, movemap_stdout_clean=True) == []
    assert "legitimate empty maps" in capsys.readouterr().out
    importlib.reload(wpd)


def test_movemap_failure_oracle_matches_the_generator_alloc_failures(
        tmp_path: Path) -> None:
    """R5-1 (W2 code-red round 1): the wotlk generator's allocation failures
    print the core's own misspelling "FIALED" (native/cmangos-wotlk
    contrib/mmap/src/MapBuilder.cpp:950 "alloc iv.polyMesh FIALED!" and :961
    "alloc m_dmesh FIALED!"), so the failure oracle must match both spellings
    — an alloc-failed map whose stdout scanned 'clean' under the old regex
    would have its tile-less coverage failure waived as a legitimate empty
    map. The oracle matching also means extract() aborts the step outright
    (MOVEMAP_FAILURE scan → StepFailure) before any waiver could apply."""
    import importlib
    importlib.reload(wpd)
    for line in (
        "[Map 615] [30,30] [00] alloc iv.polyMesh FIALED!",
        "[Map 615] [30,30] [00] alloc m_dmesh FIALED!",
        "[Map 615] Failed creating navmesh!",
        "[Map 605] [12,34] [00] Failed adding tile to navmesh!",
    ):
        assert wpd.MOVEMAP_FAILURE.search(line), line
    assert not wpd.MOVEMAP_FAILURE.search("[Map 615] [30,30] [00] Building navmesh tile!")
    # the validation side refuses the same shape when the run's stdout was
    # NOT clean (movemap_stdout_clean=False — what extract() now computes
    # for a FIALED-carrying stdout): the alloc-failed 615 stays a refusal,
    # never waived as a legitimate empty map.
    importlib.reload(wpd)
    wpd.select_flavor("wotlk")
    ws = tmp_path / "fialed"
    (ws / "maps").mkdir(parents=True)
    (ws / "maps" / "5711024.map").write_bytes(b"m")
    (ws / "maps" / "6151024.map").write_bytes(b"m")
    (ws / "mmaps").mkdir()
    (ws / "mmaps" / "5711024.mmtile").write_bytes(b"m")
    problems = wpd.validate_mmaps(ws, movemap_stdout_clean=False)
    assert problems and "615" in problems[0], problems
    importlib.reload(wpd)


def test_wotlk_tileless_waiver_is_bounded_to_the_measured_set(tmp_path: Path) -> None:
    """R3-r1-1 (W2 code-red round 1): the wotlk clean-stdout waiver is PINNED
    to the measured set {605, 606} (the only maps the archived full-stdout
    probe justifies) — exactly that set waives, anything wider refuses even
    on a failure-clean stdout, so a future generator defect that silently
    skips maps can never ride the exception beyond its evidence."""
    import importlib
    importlib.reload(wpd)
    wpd.select_flavor("wotlk")

    def workspace_with_tileless(missing_ids: tuple[str, ...], unique: str) -> Path:
        ws = tmp_path / f"tileless-{unique}"
        (ws / "maps").mkdir(parents=True)
        (ws / "mmaps").mkdir()
        for mid in ("571",) + missing_ids:
            (ws / "maps" / f"{mid}1024.map").write_bytes(b"m")
        (ws / "mmaps" / "571.mmap").write_bytes(b"h")
        (ws / "mmaps" / "571.mmap").write_bytes(b"h")
        (ws / "mmaps" / "5711024.mmtile").write_bytes(b"m")
        return ws

    # exactly the measured set waives on a failure-clean stdout
    assert wpd.validate_mmaps(
        workspace_with_tileless(("605", "606"), "waived"),
        movemap_stdout_clean=True) == []
    # one map beyond the measurement refuses on the SAME clean stdout
    problems = wpd.validate_mmaps(
        workspace_with_tileless(("605", "606", "649"), "wider"),
        movemap_stdout_clean=True)
    assert problems and "3 maps have no mmtiles" in problems[0]
    # and the strict (resume-skip) context still refuses the measured set too
    problems = wpd.validate_mmaps(workspace_with_tileless(("605", "606"), "strict"))
    assert problems and "2 maps have no mmtiles" in problems[0]
    importlib.reload(wpd)


def test_mmtile_refusal_copy_names_the_lane_contract(tmp_path: Path) -> None:
    """R6-1 (W2 code-red round 1): the mmtile refusal copy states the ACTIVE
    lane's accepted shape — wotlk names the alt form (its gate accepts
    7 digits + one _NN suffix), vanilla/tbc keep the strict 7-digit copy."""
    import importlib
    importlib.reload(wpd)

    def workspace_with_bad_tile(flavor: str, name: str) -> Path:
        wpd.select_flavor(flavor)
        ws = tmp_path / f"copy-{flavor}"
        (ws / "maps").mkdir(parents=True, exist_ok=True)
        (ws / "maps" / "5711024.map").write_bytes(b"m")
        (ws / "mmaps").mkdir(exist_ok=True)
        (ws / "mmaps" / name).write_bytes(b"m")
        return ws

    problems = wpd.validate_mmaps(workspace_with_bad_tile("wotlk", "6313035_1.mmtile"))
    assert any("_NN" in problem for problem in problems), problems
    for flavor in ("vanilla", "tbc"):
        problems = wpd.validate_mmaps(
            workspace_with_bad_tile(flavor, "631303_1.mmtile"))
        assert any(
            "expected 7 digits" in problem and "_NN" not in problem
            for problem in problems), (flavor, problems)
    importlib.reload(wpd)


def test_publish_only_refuses_a_coverage_truncated_workspace(
        tmp_path: Path, monkeypatch, capsys) -> None:
    """R2-1 (W2 code-red round 1): --publish-only is not a trust shortcut.
    A MoveMapGen-truncated wotlk workspace — two maps tile-less beyond the
    measured {605,606} set — must refuse with the completeness failure and
    never flip a pointer, where the pre-wave behavior published a
    complete:true manifest from exactly this shape (the reviewer's scratch
    repro). The strict validation context applies: no run stdout exists."""
    import importlib
    importlib.reload(wpd)
    lane_root = tmp_path / "lane"
    monkeypatch.setitem(wpd.WIN_BUILD_ROOTS, "wotlk", lane_root)
    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setattr(
        sys, "argv", ["win_prepare_data.py", "--publish-only", "--flavor", "wotlk"])
    ws = lane_root / "data-prep"
    (ws / "dbc").mkdir(parents=True)
    (ws / "maps").mkdir()
    (ws / "mmaps").mkdir()
    for index in range(200):  # validate_ad's wotlk dbc floor
        (ws / "dbc" / f"{index:05d}.dbc").write_bytes(b"d")
    covered = [f"{index:03d}" for index in range(48)]
    for bucket in covered + ["048", "049"]:  # 50 ids, 101 files each >= 5000
        for tile in range(101):
            (ws / "maps" / f"{bucket}{tile:02d}{tile:02d}.map").write_bytes(b"m")
    for bucket in covered:  # 48 ids tiled; 048/049 tile-less beyond the set
        (ws / "mmaps" / f"{bucket}.mmap").write_bytes(b"h")
        (ws / "mmaps" / f"{bucket}1024.mmtile").write_bytes(b"t")
    assert wpd.main() == 3
    err = capsys.readouterr().err
    assert "workspace fails completeness validation" in err
    assert "2 maps have no mmtiles" in err
    o11 = tmp_path / "data" / "content" / "o11-server"
    assert not (o11 / "active-wotlk.json").exists(), "a pointer flipped"
    assert not (o11 / "active.json").exists(), "the legacy mirror flipped"
    assert not (o11 / "generations").exists(), "a generation was published"
    importlib.reload(wpd)


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


def test_wotlk_waiver_provenance_resolves_into_the_archived_evidence() -> None:
    """R4-r2-1 + R3-r2-1 (W2 code-red round 2) + R5-r3-1 (round 3) +
    R6-w2r5-1/R2-r5-2 (round 5): the waiver's measured set is held to the
    archived evidence's ACTUAL shape, segment-matched (the generator mixes
    \\r progress prints with \\n lines — a line-regex bridges segments and a
    \\r-only split hides whole legs). The TRUE shape for 605/606: per-tile
    BUILD ATTEMPTS are printed (9 for 605, 2 for 606 — MapBuilder.cpp:673's
    "Building tile [x,y] (i / n)") and every attempt silently skips on the
    no-vertices branch; ZERO segments show "Building navmesh tile" (:1066)
    or "Writing to file" (:1101) for either map, and both maps complete
    cleanly. The tool's own provenance comment cites the probe's byte size
    and byte-level LF/CR shape, and both citations are held to the artifact's
    RAW BYTES (R2-r5-2 + R2-6-1: a decoded read_text() newline-translates —
    the CR-inflated "
 count" is not the LF count, so the pin counts
    bytes). The archived prep runs record the 616
    nondeterminism the measured set deliberately excludes: run 1 refused
    605/606/616 on a completed clean-stdout MoveMapGen, the official rerun
    waived exactly {605,606}. A 616-empty future run refuses BY DESIGN and a
    re-run recovers it. Skips (never errors) on trees without the archived
    evidence — out/ is gitignored, so a fresh clone has none (R6-w2r5-1)."""
    import importlib
    evidence = [ROOT / "out/completion/w2/w2_1_movemapgen_probe_stdout.log",
                ROOT / "out/completion/w2/w2_1_prep_run.log",
                ROOT / "out/completion/w2/w2_1_prep_run2.log"]
    if not all(p.is_file() for p in evidence):
        pytest.skip("archived W2 prep evidence not present in this tree "
                    "(out/ is gitignored) — nothing to hold the waiver to")
    importlib.reload(wpd)
    tool_source = (ROOT / "tools/win_prepare_data.py").read_text(encoding="utf-8")
    # count on BYTES. A decoded read_text
    # applies universal-newline translation — every CR becomes \n — so the
    # translated "\n count" (2946) is not the artifact's LF count (2407),
    # and a citation checked against it passes for the wrong reason. The
    # comment cites bytes and the byte-level LF/CR shape; both are held to
    # the artifact's raw bytes.
    probe_bytes = evidence[0].read_bytes()
    probe = probe_bytes.decode("utf-8", errors="replace")
    cited_bytes = re.search(r"movemapgen_probe_stdout\.log, ([\d,]+) bytes", tool_source)
    assert cited_bytes, "the provenance comment no longer cites a byte size"
    assert int(cited_bytes.group(1).replace(",", "")) == len(probe_bytes), (
        "the comment's cited byte size drifted from the archived probe")
    cited_shape = re.search(r"([\d,]+) LF \+ ([\d,]+) CR", tool_source)
    assert cited_shape, "the provenance comment no longer cites the LF/CR shape"
    assert int(cited_shape.group(1).replace(",", "")) == probe_bytes.count(b"\n"), (
        "the comment's cited LF count is not the artifact's byte LF count")
    assert int(cited_shape.group(2).replace(",", "")) == probe_bytes.count(b"\r"), (
        "the comment's cited CR count is not the artifact's byte CR count")
    assert not wpd.MOVEMAP_FAILURE.search(probe), "the probe was not failure-clean"
    segments = [s.strip() for s in re.split(r"[\r\n]+", probe) if s.strip()]
    for mid, attempts in (("605", 9), ("606", 2)):
        built = [s for s in segments if s.startswith(f"[Map {mid}] Building tile")]
        assert len(built) == attempts, (
            f"{mid}: {len(built)} per-tile build-attempt segments, expected "
            f"{attempts} — the archived probe drifted from the waiver's "
            "measured-set evidence")
        assert not [s for s in segments
                    if s.startswith(f"[Map {mid}]")
                    and ("Building navmesh tile" in s or "Writing to file" in s)], (
            f"{mid}: the probe shows a tile actually built/written")
        assert f"Map [{mid}] is done!" in probe
    run1 = evidence[1].read_text(encoding="utf-8", errors="replace")
    assert "605, 606, 616" in run1, "run 1's archived refusal lost its 616 leg"
    run2 = evidence[2].read_text(encoding="utf-8", errors="replace")
    assert "605, 606" in run2, "the official run's waived set is not recorded"
    waived = re.search(r"yield no navmesh tiles[^\n]*: ([^\n]*)", run2)
    assert waived and set(waived.group(1).split(", ")) <= wpd.ALLOWED_TILELESS_WOTLK
    importlib.reload(wpd)


def test_publish_only_refuses_a_wrong_lane_workspace(
        tmp_path: Path, monkeypatch, capsys) -> None:
    """R2-r2-1 (W2 code-red round 2, the reviewer's P5 probe): a
    tbc-shaped workspace (tbc's measured yield) staged in the VANILLA lane's
    data-prep must refuse with the lane-identity failure — completeness
    alone passes it (185 dbc / 3586 maps clear vanilla's floors), and the
    old behavior published it under a vanilla manifest identity. No pointer
    may flip. A marker naming ANOTHER lane refuses the same way."""
    import importlib
    importlib.reload(wpd)
    lane_root = tmp_path / "lane"
    monkeypatch.setitem(wpd.WIN_BUILD_ROOTS, "vanilla", lane_root)
    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setattr(
        sys, "argv", ["win_prepare_data.py", "--publish-only", "--flavor", "vanilla"])
    ws = lane_root / "data-prep"
    (ws / "dbc").mkdir(parents=True)
    (ws / "maps").mkdir()
    (ws / "vmaps").mkdir()
    (ws / "mmaps").mkdir()
    for index in range(185):  # tbc's measured dbc yield
        (ws / "dbc" / f"{index:05d}.dbc").write_bytes(b"d")
    for index in range(3586):  # tbc's measured map yield
        (ws / "maps" / f"{index:07d}.map").write_bytes(b"m")
    (ws / "vmaps" / "000.vmtree").write_bytes(b"t")
    for index in range(72):  # tbc's measured tile yield, over 72 ids
        (ws / "mmaps" / f"{index:03d}.mmap").write_bytes(b"h")
        (ws / "mmaps" / f"{index:03d}0000.mmtile").write_bytes(b"t")
    # NO lane-identity.json: the workspace never came from this tool's
    # verified extract.
    assert wpd.main() == 3
    err = capsys.readouterr().err
    assert "workspace lane identity missing" in err
    assert "VAL-WORKSPACE-IDENTITY" in err
    # a marker naming ANOTHER lane refuses identically
    (ws / "lane-identity.json").write_text(
        json.dumps({"schema": 1, "flavor": "wotlk", "exeSha256": None}),
        encoding="utf-8")
    assert wpd.main() == 3
    err = capsys.readouterr().err
    assert "workspace lane identity mismatch" in err
    assert "'wotlk'" in err and "vanilla" in err
    # w2r3-1: the sha leg FAILS
    # CLOSED. A same-lane marker attesting a DIFFERENT digest, no digest at
    # all, a falsy digest, or omitting the key entirely refuses — a
    # digest-less marker attests nothing about the client it was extracted
    # from. extract() can never legitimately write one (every flavor's pin is
    # registered), so these markers are never legitimate products.
    registered_sha = wpd.registered_exe_identity("vanilla")[1]
    assert registered_sha, "the vanilla lane must carry a registered pin"
    for label, marker in (
        ("wrong-digest", {"schema": 1, "flavor": "vanilla", "exeSha256": "f" * 64}),
        ("null-digest", {"schema": 1, "flavor": "vanilla", "exeSha256": None}),
        ("digest-omitted", {"schema": 1, "flavor": "vanilla"}),
        ("falsy-digest", {"schema": 1, "flavor": "vanilla", "exeSha256": 0}),
    ):
        (ws / "lane-identity.json").write_text(json.dumps(marker), encoding="utf-8")
        assert wpd.main() == 3, label
        err = capsys.readouterr().err
        assert "workspace lane identity mismatch" in err, (label, err)
        assert "VAL-WORKSPACE-IDENTITY" in err, label
    o11 = tmp_path / "data" / "content" / "o11-server"
    assert not (o11 / "active-vanilla.json").exists() and not (o11 / "generations").exists()
    importlib.reload(wpd)


def test_stderr_printed_failure_reaches_the_movemap_oracle(tmp_path: Path) -> None:
    """R2-r2-2 (W2 code-red round 2, the reviewer's P3 probe): the generator's
    per-map write failures go through perror() → stderr with rc=0
    (cmangos-wotlk MapBuilder.cpp:1095-1096), so run() must return BOTH
    streams — the identical line is oracle-caught from stderr exactly as from
    stdout, and extract()'s scan therefore aborts the mmaps step."""
    import importlib
    importlib.reload(wpd)
    line = "[Map 571] Failed to open mmaps/5711024.mmtile for writing!"
    for stream in ("stdout", "stderr"):
        output = wpd.run(
            "probe", tmp_path,
            [sys.executable, "-c",
             f"import sys; print({line!r}, file=sys.{stream})"])
        assert wpd.MOVEMAP_FAILURE.search(output), (stream, output)
    importlib.reload(wpd)


def test_validate_mmaps_requires_a_navmesh_header_per_tiled_map(
        tmp_path: Path) -> None:
    """R2-r2-2 (W2 code-red round 2): every tile-bearing map requires its
    %03u.mmap header (the runtime loads tiles through it — the desktop twin's
    requireCount(mmaps, '.mmap', ...) leg); a header lost to a stderr-printed
    write failure must refuse, never publish an unloadable map."""
    import importlib
    importlib.reload(wpd)
    wpd.select_flavor("wotlk")
    ws = tmp_path / "headerless"
    (ws / "maps").mkdir(parents=True)
    (ws / "mmaps").mkdir()
    (ws / "maps" / "5711024.map").write_bytes(b"m")
    (ws / "mmaps" / "5711024.mmtile").write_bytes(b"m")  # tile WITHOUT header
    problems = wpd.validate_mmaps(ws, movemap_stdout_clean=True)
    assert problems and "no .mmap navmesh header" in problems[-1], problems
    (ws / "mmaps" / "571.mmap").write_bytes(b"h")
    assert wpd.validate_mmaps(ws, movemap_stdout_clean=True) == []
    importlib.reload(wpd)


def test_publish_only_refuses_non_object_lane_identity_markers(
        tmp_path: Path, monkeypatch, capsys) -> None:
    """R2-r4-1 (W2 code-red round 4): valid-but-non-object JSON in
    lane-identity.json (a bare string, array, number) must refuse with the
    gate's VAL copy — the old path crashed with a raw AttributeError
    (marker.get on a non-dict), an unactionable traceback instead of the
    fail-closed refusal."""
    import importlib
    importlib.reload(wpd)
    lane_root = tmp_path / "lane"
    monkeypatch.setitem(wpd.WIN_BUILD_ROOTS, "vanilla", lane_root)
    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setattr(
        sys, "argv", ["win_prepare_data.py", "--publish-only", "--flavor", "vanilla"])
    ws = lane_root / "data-prep"
    (ws / "dbc").mkdir(parents=True)
    (ws / "maps").mkdir()
    (ws / "vmaps").mkdir()
    (ws / "mmaps").mkdir()
    for index in range(100):
        (ws / "dbc" / f"{index:05d}.dbc").write_bytes(b"d")
        (ws / "maps" / f"{index:07d}.map").write_bytes(b"m")
    (ws / "vmaps" / "000.vmtree").write_bytes(b"t")
    (ws / "mmaps" / "000.mmap").write_bytes(b"h")
    (ws / "mmaps" / "0000000.mmtile").write_bytes(b"t")
    for body in ('"CORRUPTED"', "[1, 2]", "42"):
        (ws / "lane-identity.json").write_text(body, encoding="utf-8")
        assert wpd.main() == 3, body
        err = capsys.readouterr().err
        assert "workspace lane identity unreadable" in err, (body, err)
        assert "JSON object" in err, body
        assert "VAL-WORKSPACE-IDENTITY" in err, body
    o11 = tmp_path / "data" / "content" / "o11-server"
    assert not (o11 / "active-vanilla.json").exists() and not (o11 / "generations").exists()
    importlib.reload(wpd)


def test_prune_refuses_and_deletes_nothing_when_a_pointer_is_unreadable(
        tmp_path: Path, monkeypatch) -> None:
    """R2-r4-2 (W2 code-red round 4): a corrupt active-<flavor>.json pointer
    used to leave its generation UNPROTECTED — the prune silently rmtree'd
    the lane's only copy while the corrupt pointer survived dangling. The
    prune now refuses BEFORE deleting anything when any pointer is unreadable
    or malformed (no string 'generation' field), naming the pointer and the
    remedy; a well-formed pointer set still prunes."""
    import importlib
    importlib.reload(wpd)
    datadir = tmp_path / "data"
    gens = datadir / "generations"
    gens.mkdir(parents=True)
    for name in ("tbcgen", "vangen", "newgen", "old1", "old2", "old3"):
        (gens / name).mkdir()
    # w2r5-1: the prune keeps the NEWEST `keep` unprotected dirs by
    # st_mtime — the fixture must PIN the ordering (NTFS mtime resolution made
    # the unpinned version flaky) and the assertions below hold the prune to
    # exactly that ordering.
    for name, epoch in (("old1", 1_000_000), ("old2", 2_000_000), ("old3", 3_000_000)):
        os.utime(gens / name, (epoch, epoch))
    (datadir / "active-tbc.json").write_text('{"schema": 1, "gene', encoding="utf-8")
    (datadir / "active-vanilla.json").write_text(
        json.dumps({"schema": 1, "generation": "vangen"}), encoding="utf-8")
    monkeypatch.setattr(wpd, "DATADIR", datadir)
    with pytest.raises(wpd.StepFailure, match="VAL-POINTER-UNREADABLE"):
        wpd.prune_stale_generations("newgen", keep=2)
    assert {p.name for p in gens.iterdir()} == {
        "tbcgen", "vangen", "newgen", "old1", "old2", "old3"}, "the prune deleted"
    # a malformed-but-parseable pointer (generation not a string) refuses too
    (datadir / "active-tbc.json").write_text('{"schema": 1}', encoding="utf-8")
    with pytest.raises(wpd.StepFailure, match="malformed"):
        wpd.prune_stale_generations("newgen", keep=2)
    assert {p.name for p in gens.iterdir()} == {
        "tbcgen", "vangen", "newgen", "old1", "old2", "old3"}
    # repairing the pointer restores the prune: with the pinned ordering the
    # prune removes EXACTLY the oldest unprotected dir (old1) — keep=2 keeps
    # old2/old3 — and both pointers' generations survive.
    (datadir / "active-tbc.json").write_text(
        json.dumps({"schema": 1, "generation": "tbcgen"}), encoding="utf-8")
    wpd.prune_stale_generations("newgen", keep=2)
    surviving = {p.name for p in gens.iterdir()}
    assert surviving == {"tbcgen", "vangen", "newgen", "old2", "old3"}, surviving
    # the tie-leg: with identical mtimes the prune's order is arbitrary — the
    # code's actual guarantee is that protected generations survive and no
    # more than `keep` unprotected dirs remain, never WHICH junk dirs.
    for name, epoch in (("old2", 5_000_000), ("old3", 5_000_000)):
        os.utime(gens / name, (epoch, epoch))
    wpd.prune_stale_generations("newgen", keep=2)
    surviving = {p.name for p in gens.iterdir()}
    assert {"tbcgen", "vangen", "newgen"} <= surviving
    assert len(surviving - {"tbcgen", "vangen", "newgen"}) <= 2
    importlib.reload(wpd)


def test_wave_exit_seed_floor_numbers_resolve_into_the_archived_recount() -> None:
    """R4-r5-1 (W2 code-red round 5): WAVE-EXIT row 2b quotes per-table
    seed-floor exactness numbers (29919/44613/9086/262) — they must resolve
    into the archived recount transcript (w2_2_floor_tables_recount.log), the
    measured-provenance discipline the floors themselves follow. Skips on
    trees without the gitignored evidence (R6-w2r5-1's convention)."""
    wave_exit = ROOT / "out/completion/w2/WAVE-EXIT.md"
    recount = ROOT / "out/completion/w2/w2_2_floor_tables_recount.log"
    if not wave_exit.is_file() or not recount.is_file():
        pytest.skip("archived W2 exit record / recount not present in this tree")
    text = " ".join(wave_exit.read_text(encoding="utf-8", errors="replace").split())
    log = recount.read_text(encoding="utf-8", errors="replace")
    quoted = re.findall(
        r"(creature_template|quest_template|item_template|areatrigger_teleport)"
        r"\s+(\d+)", text)
    assert len(quoted) >= 4, f"WAVE-EXIT row 2b no longer quotes the four tables: {quoted}"
    for table, number in quoted:
        assert re.search(rf"{table}: {number}\b", log), (
            f"WAVE-EXIT quotes {table} {number} but the archived recount "
            "records no such count — sync the record to the artifact")


def test_every_registered_flavor_carries_exe_identity_pins() -> None:
    """R2-6-2 (W2 code-red round 6): the exe-identity gate is fail-closed BY
    CODE only when every registered flavor actually carries BOTH pins —
    client_binary.known_exe_size_bytes AND wine.known_exe_sha256 in
    schemas/flavor.json, id-set-equal to FLAVOR_IDENTITIES. A future flavor
    added without them would silently have no identity gate (the round-3
    fix evaporating with the data)."""
    import importlib
    importlib.reload(wpd)
    manifest = json.loads((ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    pins = {f["id"]: f for f in manifest["flavors"]}
    assert set(wpd.FLAVOR_IDENTITIES) == set(pins), (
        "FLAVOR_IDENTITIES and schemas/flavor.json disagree on the flavor set")
    for flavor in wpd.FLAVOR_IDENTITIES:
        block = pins[flavor]
        size = (block.get("client_binary") or {}).get("known_exe_size_bytes")
        sha = (block.get("wine") or {}).get("known_exe_sha256")
        assert isinstance(size, int) and size > 0, (
            f"{flavor}: no registered known_exe_size_bytes — the identity "
            "gate would skip its size leg")
        assert isinstance(sha, str) and len(sha) == 64, (
            f"{flavor}: no registered known_exe_sha256 — the identity gate "
            "would skip its digest leg")
    importlib.reload(wpd)


def test_publish_refuses_when_the_lane_has_no_registered_pin(
        tmp_path: Path, monkeypatch, capsys) -> None:
    """R2-6-2 (W2 code-red round 6, the reviewer's LATENT probe): with
    registered_exe_identity simulated to (None, None), a shape-complete
    client and a complete workspace + marker attesting an ARBITRARY digest
    ('f'*64) must REFUSE — the old gate accepted it and flipped both
    pointers (the reviewer measured exit 0 + both pointers written). The
    sha leg is fail-closed by code: no registered pin, no verifiable
    provenance, no publication."""
    import importlib
    importlib.reload(wpd)
    lane_root = tmp_path / "lane"
    monkeypatch.setitem(wpd.WIN_BUILD_ROOTS, "vanilla", lane_root)
    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setattr(wpd, "registered_exe_identity", lambda fid: (None, None))
    monkeypatch.setattr(
        sys, "argv", ["win_prepare_data.py", "--publish-only", "--flavor", "vanilla"])
    ws = lane_root / "data-prep"
    (ws / "dbc").mkdir(parents=True)
    (ws / "maps").mkdir()
    (ws / "vmaps").mkdir()
    (ws / "mmaps").mkdir()
    for index in range(100):
        (ws / "dbc" / f"{index:05d}.dbc").write_bytes(b"d")
        (ws / "maps" / f"{index:07d}.map").write_bytes(b"m")
    (ws / "vmaps" / "000.vmtree").write_bytes(b"t")
    (ws / "mmaps" / "000.mmap").write_bytes(b"h")
    (ws / "mmaps" / "0000000.mmtile").write_bytes(b"t")
    (ws / "lane-identity.json").write_text(
        json.dumps({"schema": 1, "flavor": "vanilla", "exeSha256": "f" * 64}),
        encoding="utf-8")
    assert wpd.main() == 3
    err = capsys.readouterr().err
    assert "workspace lane identity unverifiable" in err
    assert "no registered exe identity pin" in err
    assert "VAL-WORKSPACE-IDENTITY" in err
    o11 = tmp_path / "data" / "content" / "o11-server"
    assert not (o11 / "active-vanilla.json").exists() and not (o11 / "active.json").exists()
    assert not (o11 / "generations").exists()
    importlib.reload(wpd)
