"""Unit tests for tools/check_flavor.py: the cross-flavor uniqueness rules
(datadir names, build numbers, version resources, wine prefix segments — the
prefix segment case-insensitively), the single_qualified_lane contract, the
sources.json provenance binding for qualified commit pins, the qualified
extractors.patch existence check, and the D3 dxvk-only graphics-route rule —
driven against temp manifests derived from schemas/flavor.json."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import check_flavor  # noqa: E402

MANIFEST = ROOT / "schemas" / "flavor.json"
HEX40 = "a" * 40

# Pristine manifest + the bots pin the real vanilla lane carries (also the
# commit the sources fixture's playerbots entry holds, resolved through the
# fallback table because the declared bot upstream is a fork URL).
REAL_MANIFEST = json.loads(MANIFEST.read_text(encoding="utf-8"))
VANILLA_BOTS_PIN = next(
    f for f in REAL_MANIFEST["flavors"] if f["id"] == "vanilla"
)["bots"]["commit"]
# The patch path the real (qualified) vanilla lane pins; staged as an existing
# file in the scratch trees so the extractor-patch existence check passes.
VANILLA_PATCH = next(
    f for f in REAL_MANIFEST["flavors"] if f["id"] == "vanilla"
)["extractors"]["patch"]


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def flavor_by_id(manifest: dict, fid: str) -> dict:
    return next(f for f in manifest["flavors"] if f["id"] == fid)


def default_sources() -> dict:
    """A sibling sources.json consistent with the manifest pins: the vanilla
    upstreams mirror the real commits; the tbc/wotlk upstreams mirror the
    HEX40 pins promote_to_qualified writes."""
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    entries = [
        {
            "id": "cmangos-core",
            "upstream_url": vanilla["server_core"]["upstream"],
            "commit": vanilla["server_core"]["commit"],
        },
        {
            "id": "cmangos-db",
            "upstream_url": vanilla["db_content"]["upstream"],
            "commit": vanilla["db_content"]["commit"],
        },
        {
            "id": "playerbots",
            "upstream_url": "https://github.com/cmangos/playerbots.git",
            "commit": VANILLA_BOTS_PIN,
        },
    ]
    for fid in ("tbc", "wotlk"):
        flavor = flavor_by_id(REAL_MANIFEST, fid)
        entries.append(
            {
                "id": f"cmangos-{fid}-core",
                "upstream_url": flavor["server_core"]["upstream"],
                "commit": HEX40,
            }
        )
        entries.append(
            {
                "id": f"cmangos-{fid}-db",
                "upstream_url": flavor["db_content"]["upstream"],
                "commit": HEX40,
            }
        )
    return {"schema": 1, "sources": entries}


_AUTO = object()


def validate_manifest(
    manifest: dict, tmp_path: Path, sources: object = _AUTO
) -> list[str]:
    """Write the manifest (plus, by default, a pin-matched sibling
    sources.json) to tmp_path and validate it. Pass sources=None to omit
    sources.json and exercise the missing-manifest error. The layout mirrors
    the repository (<root>/schemas/flavor.json), so the qualified-flavor
    extractors.patch existence check resolves <root> = tmp_path; the real
    vanilla patch path is staged as an existing file there."""
    if sources is _AUTO:
        sources = default_sources()
    schemas = tmp_path / "schemas"
    schemas.mkdir(exist_ok=True)
    if sources is not None:
        (schemas / "sources.json").write_text(json.dumps(sources), encoding="utf-8")
    patch = tmp_path / VANILLA_PATCH
    patch.parent.mkdir(parents=True, exist_ok=True)
    patch.write_text("patch\n", encoding="utf-8")
    path = schemas / "flavor.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return check_flavor.validate(path)


def promote_to_qualified(flavor: dict) -> None:
    """Fill every qualified-only pin so the only remaining failure mode under
    test is the cross-flavor rule itself. Core/db commits use HEX40, which the
    default sources fixture mirrors for the tbc/wotlk upstreams; the bots pin
    must equal the playerbots source entry (the fallback-table resolution)."""
    flavor["qualified"] = True
    for group, key in (("server_core", "commit"), ("db_content", "commit")):
        flavor[group][key] = HEX40
    flavor["bots"]["status"] = "pinned"
    flavor["bots"]["commit"] = VANILLA_BOTS_PIN
    flavor["storage"] = {"extracted_estimate_bytes": 1, "max_total_bytes": 2}
    flavor["mpq_set"]["required"] = ["base.mpq"]


def test_current_manifest_is_valid():
    assert check_flavor.validate(MANIFEST) == []


def test_two_qualified_flavors_error_under_default_single_lane(tmp_path):
    manifest = load_manifest()
    promote_to_qualified(flavor_by_id(manifest, "tbc"))
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "single_qualified_lane" in error and "vanilla" in error and "tbc" in error
        for error in errors
    ), errors


def test_single_qualified_lane_false_allows_two_qualified(tmp_path):
    manifest = load_manifest()
    promote_to_qualified(flavor_by_id(manifest, "tbc"))
    manifest["single_qualified_lane"] = False
    assert validate_manifest(manifest, tmp_path) == []


def test_single_qualified_lane_must_be_boolean(tmp_path):
    manifest = load_manifest()
    manifest["single_qualified_lane"] = "yes"
    errors = validate_manifest(manifest, tmp_path)
    assert any("single_qualified_lane must be a boolean" in error for error in errors), errors


def test_database_name_collision_across_flavors_errors(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "wotlk")["databases"]["realmd"] = "classicrealmd"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate database name across flavors" in error
        and "classicrealmd" in error
        and "two flavors must never share a datadir" in error
        for error in errors
    ), errors


def test_duplicate_build_and_version_resource_error(tmp_path):
    manifest = load_manifest()
    wotlk = flavor_by_id(manifest, "wotlk")
    wotlk["build"] = 8606
    wotlk["version_resource"] = "2.4.3.8606"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate build across flavors" in error and "8606" in error
        for error in errors
    ), errors
    assert any(
        "duplicate version_resource across flavors" in error
        for error in errors
    ), errors


def test_duplicate_wine_prefix_segment_across_flavors_errors(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["wine"]["prefix_segment"] = "wow5875"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate wine.prefix_segment across flavors" in error
        and "wow5875" in error
        and "two flavors must never share a wine prefix" in error
        for error in errors
    ), errors


def test_duplicate_wine_prefix_segment_differing_only_in_case_errors(tmp_path):
    # The prefix segment becomes a directory name: 'WOW5875' and 'wow5875'
    # collide on a case-insensitive filesystem, so the uniqueness comparison
    # is case-insensitive while the error names both original values.
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["wine"]["prefix_segment"] = "WOW5875"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate wine.prefix_segment across flavors" in error
        and "WOW5875" in error
        and "wow5875" in error
        and "two flavors must never share a wine prefix" in error
        for error in errors
    ), errors


def test_qualified_extractors_patch_must_exist(tmp_path):
    # A qualified flavor's patch pin must resolve to a real repo file: the
    # build applies it by that path, so a dangling pin fails here, naming it.
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["extractors"]["patch"] = (
        "native/patches/tampered.patch"
    )
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "extractors.patch" in error and "native/patches/tampered.patch" in error
        for error in errors
    ), errors


def test_qualified_extractors_patch_that_exists_passes(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["extractors"]["patch"] = (
        "native/patches/staged.patch"
    )
    staged = tmp_path / "native" / "patches" / "staged.patch"
    staged.parent.mkdir(parents=True, exist_ok=True)
    staged.write_text("patch\n", encoding="utf-8")
    assert validate_manifest(manifest, tmp_path) == []


def test_unqualified_extractors_patch_is_not_checked(tmp_path):
    # Only qualified flavors carry the file-existence bar: an unqualified
    # lane's pins are placeholders until its S5 qualification.
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["extractors"]["patch"] = (
        "native/patches/not-staged-yet.patch"
    )
    assert validate_manifest(manifest, tmp_path) == []


def test_tampered_core_commit_pin_mismatches_sources(tmp_path):
    # A qualified flavor's pin must be the very commit sources.json records:
    # the error names both values so the drift is actionable.
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["server_core"]["commit"] = "b" * 40
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "server_core.commit" in error
        and "sources.json" in error
        and "b" * 40 in error
        and flavor_by_id(REAL_MANIFEST, "vanilla")["server_core"]["commit"] in error
        for error in errors
    ), errors


def test_bots_pin_binds_via_fallback_to_playerbots(tmp_path):
    # The vanilla bots upstream is a fork URL, so resolution falls back to the
    # explicit playerbots id; a tampered pin still must not pass.
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["bots"]["commit"] = "c" * 40
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "bots.commit" in error
        and "playerbots" in error
        and "c" * 40 in error
        and VANILLA_BOTS_PIN in error
        for error in errors
    ), errors


def test_missing_sources_manifest_is_an_error(tmp_path):
    manifest = load_manifest()
    errors = validate_manifest(manifest, tmp_path, sources=None)
    assert any(
        "sources manifest not found" in error and "sources.json" in error
        for error in errors
    ), errors


def test_string_build_is_a_clean_error_not_a_crash(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["build"] = "5875"
    # Must return errors, never raise TypeError from the build comparison.
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "build must be a positive integer" in error and "'5875'" in error
        for error in errors
    ), errors


def test_version_resource_must_end_in_the_dotted_build(tmp_path):
    # The suffix check is dot-anchored: ...15875 must not pass for build 5875.
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["version_resource"] = "1.12.1.15875"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "does not end in build 5875" in error for error in errors
    ), errors


def test_version_resource_regex_is_trailing_anchored(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["version_resource"] = "1.12.1.5875\n"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "version_resource must be dotted numerals" in error for error in errors
    ), errors


def test_unknown_graphics_route_is_rejected(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"] = ["dxvk", "vulkan"]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "graphics_routes must be within" in error and "vulkan" in error
        for error in errors
    ), errors


def test_non_list_graphics_routes_is_rejected(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"] = "dxvk"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "graphics_routes must be a list of route names" in error
        for error in errors
    ), errors


def test_non_vanilla_qualified_flavor_is_dxvk_only(tmp_path):
    # D3: a newly qualified flavor must not declare the GL/VirGL lanes.
    manifest = load_manifest()
    manifest["single_qualified_lane"] = False
    tbc = flavor_by_id(manifest, "tbc")
    promote_to_qualified(tbc)
    tbc["wine"]["graphics_routes"] = ["dxvk", "opengl", "virgl"]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "D3 is dxvk-only" in error and "opengl" in error and "virgl" in error
        for error in errors
    ), errors


def test_vanilla_may_keep_its_gl_routes(tmp_path):
    # D3 restricts only non-vanilla flavors; the shipped lane legitimately
    # declares all three routes.
    manifest = load_manifest()
    assert "opengl" in flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"]
    assert "virgl" in flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"]
    assert validate_manifest(manifest, tmp_path) == []
