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


def default_sources(manifest: dict | None = None) -> dict:
    """A sibling sources.json consistent with the manifest under test: every
    registered server_core/db_content commit mirrors the manifest exactly,
    which is precisely the binding _check_source_pins enforces (mirroring the
    REAL manifest keeps the unmodified manifest clean; mirroring the promoted
    copy keeps promote_to_qualified's HEX40 pins bound). Groups whose pin is
    still unresolved (tbc/wotlk db_content) carry the HEX40 placeholder
    _check_source_pins skips."""
    # Vanilla mirrors the REAL registry so a tampered pin (the drift the
    # binding exists to catch) is detectable; tbc/wotlk mirror the manifest
    # under test so promote_to_qualified's synthetic qualification pins stay
    # bound exactly as a real registration would bind them.
    m = manifest if manifest is not None else REAL_MANIFEST
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
        flavor = flavor_by_id(m, fid)
        entries.append(
            {
                "id": f"cmangos-core-{fid}",
                "upstream_url": flavor["server_core"]["upstream"],
                "commit": flavor["server_core"].get("commit") or HEX40,
            }
        )
        entries.append(
            {
                "id": f"cmangos-db-{fid}",
                "upstream_url": flavor["db_content"]["upstream"],
                "commit": flavor["db_content"].get("commit") or HEX40,
            }
        )
        entries.append(
            {
                "id": f"playerbots-{fid}",
                "upstream_url": flavor["bots"]["upstream"],
                "commit": flavor["bots"].get("commit") or VANILLA_BOTS_PIN,
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
        sources = default_sources(manifest)
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
    flavor["storage"] = {
        "extracted_estimate_bytes": 1, "max_total_bytes": 2,
        "prepared_estimate_bytes": 1,
    }
    flavor["mpq_set"]["required"] = ["base.mpq"]


def test_current_manifest_is_valid():
    assert check_flavor.validate(MANIFEST) == []


def test_two_qualified_flavors_error_under_default_single_lane(tmp_path):
 # Flip: the shipped manifest carries the explicit
    # single_qualified_lane=false opt-out, so the DEFAULT (key absent)
    # contract is exercised by popping it - two qualified flavors without
    # the opt-out still fail loud (D9's gate never silently retires).
    manifest = load_manifest()
    manifest.pop("single_qualified_lane", None)
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
 # Lane's pins are placeholders until its qualification. The
    # live manifest qualifies tbc, so the UNQUALIFIED branch is exercised
    # by explicitly de-qualifying it here (storage back to null - the
    # unqualified rule).
    manifest = load_manifest()
    tbc = flavor_by_id(manifest, "tbc")
    tbc["qualified"] = False
    tbc["storage"] = None
    tbc["extractors"]["patch"] = (
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
    # a newly qualified flavor must not declare the GL/VirGL lanes.
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
    # restricts only non-vanilla flavors; the shipped lane legitimately
    # declares all three routes.
    manifest = load_manifest()
    assert "opengl" in flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"]
    assert "virgl" in flavor_by_id(manifest, "vanilla")["wine"]["graphics_routes"]
    assert validate_manifest(manifest, tmp_path) == []


def test_s51_registration_is_complete_for_all_three_qualified_flavors():
    # Lanes + the flip (completion-plan) the
    # staged genuine clients' identity is a COMPLETE tuple on every
    # qualified flavor — hashes, size, build list, and MPQ exact set —
    # with the build list naming exactly the flavor's own build, and the
    # storage budgets are the -MEASURED blocks (no null remains).
    manifest = load_manifest()
    for fid, build in (("vanilla", 5875), ("tbc", 8606), ("wotlk", 12340)):
        flavor = flavor_by_id(manifest, fid)
        assert flavor["qualified"] is True
        assert flavor["client_binary"]["known_exe_size_bytes"] > 0
        assert flavor["wine"]["known_exe_sha256"] is not None
        # vanilla's list predates and stays [] with its explanatory
        # note (the qualified branch only requires a note for the empty
        # list); the registered lanes carry exactly their own build.
        if fid != "vanilla":
            assert flavor["supported_client_builds"] == [build]
        assert flavor["mpq_set"]["required"], f"{fid} MPQ exact set is empty"
        storage = flavor["storage"]
        assert isinstance(storage, dict), f"{fid} storage must be the measured block"
        assert storage["extracted_estimate_bytes"] > 0
        assert storage["max_total_bytes"] > 0
        # the -measured tbc/wotlk blocks carry the measurement
        # provenance; vanilla's earlier block predates the note convention.
        if fid != "vanilla":
            assert "measured" in storage.get("note", ""), (
                f"{fid} storage note must carry the measurement provenance")


def test_partial_s51_registration_errors(tmp_path):
    # The earlier rule banned any identity datum on an unqualified flavor;
    # the measurements exist now, so the surviving lie is the PARTIAL
    # registration. Stripping one member of the tuple (the build list) must
    # fail loud, naming the completeness rule.
    manifest = load_manifest()
    wotlk = flavor_by_id(manifest, "wotlk")
 # Flip: the live manifest qualifies wotlk, so the UNQUALIFIED
    # branch under test is reached by explicitly de-qualifying it.
    wotlk["qualified"] = False
    wotlk["storage"] = None
    wotlk["supported_client_builds"] = []
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "partial S5.1 identity registration" in error
        and "mpq_set.required" in error
        for error in errors
    ), errors


def test_unqualified_supported_client_builds_must_name_only_the_own_build(tmp_path):
    # A registered build list naming anything but the flavor's own build
    # claims a client that was never measured — the exact drift the
    # registration rule refuses.
    manifest = load_manifest()
    tbc = flavor_by_id(manifest, "tbc")
 # Flip: the live manifest qualifies tbc, so the UNQUALIFIED branch
    # under test is reached by explicitly de-qualifying it.
    tbc["qualified"] = False
    tbc["storage"] = None
    tbc["supported_client_builds"] = [8606, 10505]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "must be exactly [8606]" in error and "[8606, 10505]" in error
        for error in errors
    ), errors


def test_wine_known_exe_sha256_shape_is_checked(tmp_path):
    # The sha256 telltale is null-or-64-lowercase-hex; a 32-hex MD5
    # pasted into the sha256 slot must fail loud.
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["wine"]["known_exe_sha256"] = (
        "57c5c03097103e15f9abe2803aebdc3c"
    )
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "wine.known_exe_sha256 must be 64 lowercase hex or null" in error
        for error in errors
    ), errors


# ---- the provenance binding is fail-closed against
# doctored manifests (upstream-rerouting and unknown-flavor attacks).


def test_foreign_upstream_rerouting_cannot_satisfy_the_binding(tmp_path):
    """R2-1b (round 2): pointing tbc's server_core at the VANILLA source's
    URL with vanilla's commit must FAIL - the old URL-first resolution bound
    the doctored group to the vanilla entry and reported OK on a lying
    manifest. The binding must land on the flavor's OWN entry (the legacy
    vanilla tail is not a binding candidate while cmangos-core-tbc exists),
    so the foreign commit fails the comparison."""
    manifest = load_manifest()
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    tbc = flavor_by_id(manifest, "tbc")
    tbc["server_core"]["upstream"] = vanilla["server_core"]["upstream"]
    tbc["server_core"]["commit"] = vanilla["server_core"]["commit"]
    # the REAL sources manifest (not the mirrored fixture): the attack binds
    # only against the real per-flavor entries
    errors = validate_manifest(
        manifest, tmp_path,
        sources=json.loads((ROOT / "schemas" / "sources.json").read_text(encoding="utf-8")),
    )
    assert any(
        "server_core.commit" in error and "does not match sources.json" in error
        for error in errors
    ), errors


def test_unknown_flavor_id_fails_loud_not_silent_unbound(tmp_path):
    """R2-2 (round 2): a flavor id outside FALLBACK_SOURCE_IDS used to skip
    the whole pin binding silently - a doctored flavor with pins bound to
    nothing passed. The gate must name the unknown id and the known set."""
    manifest = load_manifest()
    template = flavor_by_id(manifest, "tbc")
    import copy
    cata = copy.deepcopy(template)
    cata["id"] = "cata"
    cata["client_id"] = "wow-4.0.1-99999"
    cata["build"] = 99999
    cata["version_resource"] = "4.0.1.99999"
    manifest["flavors"].append(cata)
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "has no sources.json binding table" in error and "'cata'" in error
        for error in errors
    ), errors


# ---- identity + provenance rejections.


def test_accepted_builds_containing_a_foreign_registered_build_fails(tmp_path):
    """R3-3: the accepted set is the flavor's OWN builds — tbc advertising
    vanilla's 5875 would configure a wrong-flavor client/server pair (the
    exact rejection W4.2's 3x3 auth matrix assumes exists at the gate)."""
    manifest = load_manifest()
    tbc = flavor_by_id(manifest, "tbc")
    tbc["realmd"]["accepted_builds"] = "8606 5875"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "accepted_builds" in error and "foreign registered build" in error
        and "5875" in error
        for error in errors
    ), errors


def test_exe_identity_hashes_are_flavor_unique(tmp_path):
    """R3-5: registering another flavor's measured exe identity must fail —
    a qualified lane whose published telltale is byte-identical to a
    DIFFERENT lane's genuine client is exactly the half-truth the S5.1
    registration rule prevents."""
    manifest = load_manifest()
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    wotlk = flavor_by_id(manifest, "wotlk")
    wotlk["wine"]["known_exe_sha256"] = vanilla["wine"]["known_exe_sha256"]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate exe identity" in error and "known_exe_sha256" in error
        for error in errors
    ), errors


def test_md5_exe_identity_duplicate_also_fails(tmp_path):
    manifest = load_manifest()
    tbc = flavor_by_id(REAL_MANIFEST, "tbc")
    wotlk = flavor_by_id(manifest, "wotlk")
    wotlk["client_binary"]["known_exe_md5"] = tbc["client_binary"]["known_exe_md5"]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate exe identity" in error and "known_exe_md5" in error
        for error in errors
    ), errors


def test_sources_json_deletion_plus_foreign_repoint_fails(tmp_path):
    """R3-4: the coordinated two-file lie — delete cmangos-core-tbc from
    sources.json and point tbc's server_core at the vanilla URL/commit —
    must fail (the legacy tail is not a binding candidate while the
    flavor's own entry is missing)."""
    manifest = load_manifest()
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    tbc = flavor_by_id(manifest, "tbc")
    tbc["server_core"]["upstream"] = vanilla["server_core"]["upstream"]
    tbc["server_core"]["commit"] = vanilla["server_core"]["commit"]
    sources = json.loads(
        (ROOT / "schemas" / "sources.json").read_text(encoding="utf-8"))
    sources["sources"] = [
        s for s in sources["sources"] if s.get("id") != "cmangos-core-tbc"
    ]
    errors = validate_manifest(manifest, tmp_path, sources=sources)
    assert any(
        "no sources.json entry pins upstream" in error
        for error in errors
    ), errors


def test_first_listed_flavor_claiming_a_foreign_registered_build_fails(tmp_path):
    """Round-4 R2-2: the registry is now a PRE-PASS over all flavors — the
    FIRST-listed flavor's foreign-build claim must fail exactly like a
    later flavor's (the old inside-the-loop population made the check
    order-dependent: vanilla claiming wotlk's 12340 passed clean)."""
    manifest = load_manifest()
    vanilla = flavor_by_id(manifest, "vanilla")
    vanilla["realmd"]["accepted_builds"] = "5875 12340"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "accepted_builds" in error and "foreign registered build" in error
        and "12340" in error
        for error in errors
    ), errors


def test_validate_is_deterministic_across_calls_in_one_process(tmp_path):
    """Round-4 R2-2: the foreign-build registry leaked across validate()
    calls (module global) — the same doctored file returned 0 errors then
    1 error depending on call order. Identical bytes must produce
    identical error sets, every time."""
    import copy

    manifest = load_manifest()
    vanilla = flavor_by_id(manifest, "vanilla")
    vanilla["realmd"]["accepted_builds"] = "5875 12340"
    first = validate_manifest(copy.deepcopy(manifest), tmp_path)
    second = validate_manifest(copy.deepcopy(manifest), tmp_path)
    third = validate_manifest(copy.deepcopy(manifest), tmp_path)
    assert first == second == third, (first, second, third)
    assert any("foreign registered build" in e for e in third), third


# ---- the clientId is CONSISTENT with the flavor's own
# identity (build suffix, version components), not just shape-valid.


def test_client_id_with_wrong_build_suffix_fails(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["client_id"] = "wow-1.12.1-9999"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "client_id" in error and "contradicts" in error and "9999" in error
        for error in errors
    ), errors


def test_client_id_with_foreign_version_component_fails(tmp_path):
    manifest = load_manifest()
    flavor_by_id(manifest, "vanilla")["client_id"] = "wow-2.4.3-5875"
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "client_id" in error and "contradicts" in error for error in errors
    ), errors


def test_cross_flavor_client_id_swap_fails(tmp_path):
    manifest = load_manifest()
    tbc = flavor_by_id(REAL_MANIFEST, "tbc")
    flavor_by_id(manifest, "vanilla")["client_id"] = tbc["client_id"]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "client_id" in error and "contradicts" in error
        and "8606" in error
        for error in errors
    ), errors


def test_wotlk_patch_letter_client_id_stays_consistent():
    """The naming convention keeps the patch letter in the clientId
    (wow-3.3.5a-12340) while the version resource drops it (3.3.5.12340)
    — the consistency rule must keep accepting that pair."""
    assert check_flavor.validate(MANIFEST) == []


# ---------------------------------------------------------------------------
# a duplicated sources.json id defeats the by-id
# binding — check_flavor's URL-first match binds whichever duplicate carries
# the doctored URL, check_repo's by-id dict collapses duplicates (last
# wins), and check_sources verifies each duplicate independently. All three
# gates now refuse duplicate ids; the adversarial breaker case
# (cmangos-core-tbc twice: honest first, vanilla URL+commit second, with a
# flavor.json pinning tbc's server_core at the vanilla commit) must fail.
# ---------------------------------------------------------------------------

def _doctored_tbc_manifest() -> dict:
    """The real manifest with tbc's server_core re-pointed at the VANILLA
    fork URL + vanilla commit (the coordinated two-file lie)."""
    doctored = load_manifest()
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    tbc = flavor_by_id(doctored, "tbc")
    tbc["server_core"]["upstream"] = vanilla["server_core"]["upstream"]
    tbc["server_core"]["commit"] = vanilla["server_core"]["commit"]
    return doctored


def test_duplicate_source_id_refused_before_the_url_first_match(tmp_path):
    """The doctored duplicate must produce a binding error naming the
    duplicate id — never a clean pass through the URL-first match."""
    sources = default_sources(_doctored_tbc_manifest())
    vanilla = flavor_by_id(REAL_MANIFEST, "vanilla")
    sources["sources"].append({
        "id": "cmangos-core-tbc",
        "upstream_url": vanilla["server_core"]["upstream"],
        "commit": vanilla["server_core"]["commit"],
    })
    errors = validate_manifest(_doctored_tbc_manifest(), tmp_path,
                               sources=sources)
    assert any(
        "DUPLICATE id" in error and "cmangos-core-tbc" in error
        for error in errors
    ), errors


def test_doctored_pin_with_clean_single_entry_sources_still_refused(tmp_path):
    """Control (the D48-R2-1b behavior intact): with NO duplicate to bind,
    the same doctored tbc pin fails the commit comparison against the
    honest tbc entry — and no duplicate complaint fires."""
    sources = default_sources(REAL_MANIFEST)  # honest tbc entry
    errors = validate_manifest(_doctored_tbc_manifest(), tmp_path,
                               sources=sources)
    assert errors, "the doctored pin must fail the commit comparison"
    assert not any("DUPLICATE" in error for error in errors), errors
    assert any("commit" in error.lower() for error in errors), errors


def test_current_manifest_and_single_entry_sources_stay_green():
    """The honest tree: unique ids, real manifest — validate stays clean."""
    errors = check_flavor.validate(MANIFEST)
    assert not errors, errors


def test_check_repo_structure_leg_refuses_duplicate_ids(tmp_path, monkeypatch):
    import check_repo

    root = tmp_path / "repo"
    (root / "schemas").mkdir(parents=True)
    honest = {"id": "cmangos-core-tbc", "kind": "git-submodule",
              "path": "native/cmangos-tbc", "commit": "b2" * 20}
    doctored = {"id": "cmangos-core-tbc", "kind": "git-submodule",
                "path": "native/cmangos-tbc", "commit": "a1" * 20}
    (root / "schemas" / "flavor.json").write_text("{}", encoding="utf-8")
    (root / ".gitmodules").write_text("", encoding="utf-8")
    sources_path = root / "schemas" / "sources.json"

    sources_path.write_text(
        json.dumps({"schema": 1, "sources": [doctored, honest]}),
        encoding="utf-8")
    monkeypatch.setattr(check_repo, "ROOT", root)
    errors: list[str] = []
    check_repo.check_flavor_source_structure(errors)
    assert any(
        "DUPLICATE id" in error and "cmangos-core-tbc" in error
        for error in errors
    ), errors

    # control: unique ids -> no duplicate complaint (the bare synthetic
    # tree may still carry unrelated structural errors; filter them)
    sources_path.write_text(
        json.dumps({"schema": 1, "sources": [honest]}), encoding="utf-8")
    errors = []
    check_repo.check_flavor_source_structure(errors)
    assert not any("DUPLICATE" in error for error in errors), errors


def test_check_sources_refuses_duplicate_ids(tmp_path, monkeypatch, capsys):
    import check_sources

    entries = [
        # a path that does NOT exist on this host: an unresolved pin with
        # no checkout is the sanctioned PEND shape (a real checkout path
        # like native/cmangos-tbc would be its own refusal — an unpinned
        # tree is a provenance lie — independent of this test's subject)
        {"id": "cmangos-core-tbc", "kind": "git-submodule",
         "path": "native/.tmp-synthetic-cmangos-tbc", "commit": "b2" * 20,
         "status": "unresolved"},
        {"id": "cmangos-core-tbc", "kind": "git-submodule",
         "path": "native/.tmp-synthetic-cmangos-tbc", "commit": "a1" * 20,
         "status": "unresolved"},
    ]
    sources_path = tmp_path / "sources.json"
    sources_path.write_text(
        json.dumps({"schema": 1, "sources": entries}), encoding="utf-8")
    monkeypatch.setattr(check_sources, "SOURCES", sources_path)
    rc = check_sources.main()
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL duplicate id cmangos-core-tbc" in out

    # control: the single-entry tree stays green (unresolved pin = PEND)
    sources_path.write_text(
        json.dumps({"schema": 1, "sources": [entries[0]]}), encoding="utf-8")
    rc = check_sources.main()
    out = capsys.readouterr().out
    assert rc == 0
    assert "duplicate id" not in out


# ---------------------------------------------------------------------------
# the per-flavor compatibility-constant VALUES were
# unpinned — check_flavor only requires a non-empty mpq list and an
# interface number in range, so a qualified lane carrying another flavor's
# MPQ set or addon interface number validated clean. These are the port
# plan table constants (addon interfaces 11200/20400/30300) and the
# exact MPQ sets; a change here is a deliberate client-identity decision
# and must land together with this pin (and the Kotlin ClientFlavor
# mirrors), never as a silent manifest edit.
# ---------------------------------------------------------------------------

GOLDEN_ADDON_INTERFACES = {
    "vanilla": [11200],
    "tbc": [20400],
    "wotlk": [30300],
}

GOLDEN_MPQ_SETS = {
    "vanilla": [
        "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq",
        "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq",
        "wmo.mpq",
    ],
    "tbc": [
        "common.mpq", "expansion.mpq", "patch.mpq", "patch-2.mpq",
        "enus/locale-enus.mpq", "enus/speech-enus.mpq",
        "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
        "enus/patch-enus.mpq", "enus/patch-enus-2.mpq",
    ],
    "wotlk": [
        "common.mpq", "common-2.mpq", "expansion.mpq", "lichking.mpq",
        "patch.mpq", "patch-2.mpq", "patch-3.mpq",
        "enus/locale-enus.mpq", "enus/speech-enus.mpq",
        "enus/expansion-locale-enus.mpq", "enus/expansion-speech-enus.mpq",
        "enus/lichking-locale-enus.mpq", "enus/lichking-speech-enus.mpq",
        "enus/patch-enus.mpq", "enus/patch-enus-2.mpq", "enus/patch-enus-3.mpq",
    ],
}


def test_compatibility_constants_match_the_documented_goldens():
    """Every flavor's addon_interfaces and mpq_set.required equal the
    documented per-flavor constants — the reviewer's mutations (vanilla
    carrying wotlk's 16-entry MPQ list; vanilla addon_interfaces 30300)
    must be impossible to land without touching this pin."""
    manifest = load_manifest()
    for flavor in manifest["flavors"]:
        fid = flavor["id"]
        assert flavor["addon_interfaces"] == GOLDEN_ADDON_INTERFACES[fid], (
            f"{fid}: addon_interfaces drifted from the port-plan §3 constant"
        )
        assert flavor["mpq_set"]["required"] == GOLDEN_MPQ_SETS[fid], (
            f"{fid}: mpq_set.required drifted from the C2 exact set"
        )


def test_golden_tables_cover_every_manifest_flavor():
    """A new flavor must extend BOTH golden tables in the same change (an
    uncovered flavor fails here instead of silently escaping the pin)."""
    manifest = load_manifest()
    assert (set(GOLDEN_ADDON_INTERFACES) | set(GOLDEN_MPQ_SETS)
            == {f["id"] for f in manifest["flavors"]})

def test_storage_extracted_estimate_covers_the_measured_client_tree():
    """R4-3 (code-red round 1): the extracted_estimate_bytes is the planner's
    managed-copy floor — it must be >= the genuine client tree bytes the
    same storage block's note records, and the Kotlin registry must mirror
    both numbers (a low estimate under-reserves the import's own copy)."""
    import re
    manifest = json.loads(
        (ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    tree_bytes = {
        "tbc": 8_579_714_973,
        "wotlk": 17_856_619_157,
    }
    for fid, tree in tree_bytes.items():
        flavor = flavor_by_id(manifest, fid)
        storage = flavor["storage"]
        assert storage["extracted_estimate_bytes"] >= tree, (
            f"{fid}: extracted_estimate_bytes "
            f"{storage['extracted_estimate_bytes']} is below the measured "
            f"client tree {tree} — the managed-copy floor is under-reserved")
        # the Kotlin registry mirrors the manifest numbers
        kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
                  "ClientFlavor.kt").read_text(encoding="utf-8")
        assert f"{storage['extracted_estimate_bytes'] // (1024**3)}L * 1024L * 1024L * 1024L" in kotlin, (
            f"{fid}: the Kotlin extractedEstimateBytes must mirror the "
            "manifest's GiB value")


def test_swapped_required_mpq_set_across_flavors_errors(tmp_path):
    """W7-R2-2: a doctored manifest swapping one flavor's required MPQ
    list for another's must refuse — the base MPQ set is client identity
    the same way the exe hash is."""
    manifest = load_manifest()
    wotlk_list = list(flavor_by_id(manifest, "wotlk")["mpq_set"]["required"])
    flavor_by_id(manifest, "tbc")["mpq_set"]["required"] = wotlk_list
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate required MPQ set across flavors" in error
        and "tbc" in error
        and "wotlk" in error
        for error in errors
    ), errors


def test_manifest_required_mpq_sets_match_the_kotlin_registry():
    """W7-R2-2 parity: flavor.json flavors[i].mpq_set.required must equal
    the ClientFlavors registry's requiredMpqs per flavor — the runtime
    import gate resolves the Kotlin set (ManagedClientStore), so a
    two-file drift here would wave a wrong-flavor client through with no
    detector. The same drift class sources.json refuses for commits."""
    import re

    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")
    # each registry object names its clientId, then its requiredMpqs setOf
    registry: dict[str, set[str]] = {}
    for m in re.finditer(
        r'clientId = "([^"]+)"', kotlin
    ):
        tail = kotlin[m.end():]
        rm = re.search(r"requiredMpqs = setOf\((.*?)\)", tail, re.S)
        assert rm, f"no requiredMpqs setOf after clientId {m.group(1)!r}"
        names = set(re.findall(r'"([^"]+)"', rm.group(1)))
        assert names, f"empty requiredMpqs for {m.group(1)!r}"
        registry[m.group(1)] = names
    assert registry, "the ClientFlavors registry parse found no flavors"

    manifest = load_manifest()
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        assert cid in registry, (
            f"flavor.json carries client_id {cid!r} with no Kotlin registry "
            "object — the parity test cannot bind it")
        required = set(flavor["mpq_set"]["required"])
        assert required == registry[cid], (
            f"{cid}: flavor.json mpq_set.required != the Kotlin registry "
            f"requiredMpqs (manifest-only: {sorted(required - registry[cid])}, "
            f"registry-only: {sorted(registry[cid] - required)})")


def test_qualified_foreign_supported_client_build_errors(tmp_path):
    """W7 code-red round 3 (R3-1): the registration-completeness rule
    applies to QUALIFIED flavors — a doctored qualified flavor naming a
    FOREIGN registered build (another lane's) must refuse; [] stays legal
    with its note."""
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["supported_client_builds"] = [8606, 12340]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "qualified supported_client_builds must be exactly" in error
        and "12340" in error
        for error in errors
    ), errors
    # foreign-only list refuses the same way
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["supported_client_builds"] = [12340]
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "qualified supported_client_builds must be exactly" in error
        for error in errors
    ), errors
    # listing exactly the flavor's own build stays legal
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["supported_client_builds"] = [8606]
    flavor_by_id(manifest, "tbc")["supported_client_builds_note"] = "hash-qualified"
    errors = validate_manifest(manifest, tmp_path)
    assert not any("supported_client_builds" in e for e in errors), errors


def test_manifest_identity_fields_match_the_kotlin_registry():
    """W7 code-red round 3 (R3-3): the manifest<->registry parity extends
    to every identity-bearing ClientFlavor field — addonInterfaces,
    realmdAcceptedBuilds, databases, versionResource, and the exe
    sha256/md5/size — so a two-file drift in ANY of them (the class the
    mpq parity test was built for) has a detector."""
    import re

    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")

    def block_after(anchor: str) -> str:
        i = kotlin.find(anchor)
        assert i >= 0, f"ClientFlavor.kt lost {anchor!r}"
        return kotlin[i:]

    manifest = load_manifest()
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        block = block_after(f'clientId = "{cid}"')
        # addonInterfaces: the manifest carries NO mirror field (the
        # registry is the owning table), so the parity pin is the era
        # constants themselves — the port-plan master-table values
        m = re.search(r"addonInterfaces = setOf\((.*?)\)", block, re.S)
        assert m, f"{cid}: no addonInterfaces in registry"
        reg_ifaces = sorted(int(x) for x in re.findall(r"\d+", m.group(1)))
        era_floor = {"vanilla": 11200, "tbc": 20400, "wotlk": 30300}[flavor["id"]]
        assert reg_ifaces == [era_floor], (
            f"{cid}: registry addonInterfaces {reg_ifaces} drifted from the "
            f"era constant {era_floor}")
        # realmdAcceptedBuilds
        m = re.search(r'realmdAcceptedBuilds = "([^"]+)"', block)
        assert m, f"{cid}: no realmdAcceptedBuilds in registry"
        assert flavor["realmd"]["accepted_builds"] == m.group(1), (
            f"{cid}: realmd accepted_builds drift "
            f"{flavor['realmd']['accepted_builds']!r} vs registry {m.group(1)!r}")
        # databases
        m = re.search(
            r"databases = ClientFlavor\.DatabaseNames\((.*?)\)", block, re.S)
        assert m, f"{cid}: no databases in registry"
        reg_dbs = dict(re.findall(r"(\w+) = \"([^\"]+)\"", m.group(1)))
        assert flavor["databases"] == reg_dbs, (
            f"{cid}: databases drift {flavor['databases']} vs registry {reg_dbs}")
        # versionResource
        m = re.search(r'versionResource = "([^"]+)"', block)
        assert m, f"{cid}: no versionResource in registry"
        assert flavor["version_resource"] == m.group(1), (
            f"{cid}: version_resource drift")
        # exe identities
        m = re.search(r'knownExeSha256 = "([^"]+)"', block)
        assert m, f"{cid}: no knownExeSha256 in registry"
        assert flavor["wine"]["known_exe_sha256"] == m.group(1), (
            f"{cid}: wine.known_exe_sha256 drift")
        cb = flavor.get("client_binary") or {}
        m = re.search(r'knownExeMd5 = "([^"]+)"', block)
        if cb.get("known_exe_md5") is not None:
            assert m, f"{cid}: manifest has known_exe_md5 but registry does not"
            assert cb["known_exe_md5"] == m.group(1), f"{cid}: exe md5 drift"
        m = re.search(r"knownExeSizeBytes = (\d+)L", block)
        if cb.get("known_exe_size_bytes") is not None:
            assert m, f"{cid}: manifest has known_exe_size_bytes but registry does not"
            assert cb["known_exe_size_bytes"] == int(m.group(1)), (
                f"{cid}: exe size drift")


def test_wine_prefix_segment_win32_normalization_collisions_refused(tmp_path):
    """W7 code-red round 4 (R4-R2-1): prefix segments differing from
    another flavor's only by a trailing dot/space collapse to the SAME
    directory on Win32 — the slug rule refuses every such spelling."""
    for doctored in ("wow8606.", "wow8606 ", "Wow8606", "wow 8606"):
        manifest = load_manifest()
        flavor_by_id(manifest, "tbc")["wine"]["prefix_segment"] = doctored
        errors = validate_manifest(manifest, tmp_path)
        assert any(
            "must be a lowercase slug" in error
            for error in errors
        ), (doctored, errors)


def test_manifest_wine_prefix_segment_matches_the_kotlin_registry():
    """W7 code-red round 4 (R4-R2-1): the winePrefixSegment parity leg —
    the manifest's wine.prefix_segment must equal the registry's
    winePrefixSegment per client id (the two-file drift class)."""
    import re

    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")
    manifest = load_manifest()
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        i = kotlin.find(f'clientId = "{cid}"')
        assert i >= 0, f"{cid}: no registry object"
        m = re.search(r'winePrefixSegment = "([^"]+)"', kotlin[i:])
        assert m, f"{cid}: no winePrefixSegment in registry"
        assert flavor["wine"]["prefix_segment"] == m.group(1), (
            f"{cid}: wine.prefix_segment drift "
            f"{flavor['wine']['prefix_segment']!r} vs registry {m.group(1)!r}")


def test_manifest_prepared_estimate_matches_the_kotlin_registry():
    """W7 code-red round 4 (R4-2): the prepared_estimate_bytes parity leg —
    the planner's prepared row resolves the registry value, so a two-file
    drift would silently change the import preflight's arithmetic."""
    import re

    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")
    manifest = load_manifest()
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        i = kotlin.find(f'clientId = "{cid}"')
        assert i >= 0, f"{cid}: no registry object"
        m = re.search(r"preparedEstimateBytes = (\d+)L", kotlin[i:])
        assert m, f"{cid}: no preparedEstimateBytes in registry"
        assert flavor["storage"]["prepared_estimate_bytes"] == int(m.group(1)), (
            f"{cid}: storage.prepared_estimate_bytes drift")


def test_mutual_identity_exchanges_refuse_regardless_of_order(tmp_path):
    """W7 code-red round 5 (W7-R5-R2-1): the identity gates' coverage,
    stated exactly. SHARING (a list/hash copied so two flavors carry the
    same identity) is refused by check_flavor regardless of registration
    order — the pairwise pass closes the insertion-order dodge the
    owner-maps had. A two-party MUTUAL EXCHANGE of distinct values is
    equality-invisible by definition (each value still appears once), so
    it is owned by the manifest<->registry parity pins, which anchor every
    field to its client id — asserted here at that layer."""
    # sharing: a THIRD flavor copying another's mpq list (the order-dodge
    # shape the owner-maps missed)
    manifest = load_manifest()
    wotlk_list = list(flavor_by_id(manifest, "wotlk")["mpq_set"]["required"])
    flavor_by_id(manifest, "vanilla")["mpq_set"]["required"] = wotlk_list
    errors = validate_manifest(manifest, tmp_path)
    assert any(
        "duplicate mpq_set.required across flavors" in e and "wotlk" in e
        for e in errors
    ), errors

    # mutual known_exe_sha256 swap: an equality gate CANNOT see it (two
    # distinct values exchange — each still appears once), so this drift
    # is owned by the manifest<->registry parity layer, which anchors each
    # hash to its client id. Assert THAT detector fires on the swap.
    import re as _re

    manifest = load_manifest()
    tbc_sha = flavor_by_id(manifest, "tbc")["wine"]["known_exe_sha256"]
    wotlk_sha = flavor_by_id(manifest, "wotlk")["wine"]["known_exe_sha256"]
    flavor_by_id(manifest, "tbc")["wine"]["known_exe_sha256"] = wotlk_sha
    flavor_by_id(manifest, "wotlk")["wine"]["known_exe_sha256"] = tbc_sha
    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")
    registry_drift = []
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        i = kotlin.find(f'clientId = "{cid}"')
        m = _re.search(r'knownExeSha256 = "([^"]+)"', kotlin[i:])
        assert m, f"{cid}: no registry sha"
        if flavor["wine"]["known_exe_sha256"] != m.group(1):
            registry_drift.append(cid)
    assert sorted(registry_drift) == ["wow-2.4.3-8606", "wow-3.3.5a-12340"], (
        "the mutual exe-sha exchange must drift BOTH flavors against the "
        f"registry (the parity layer's detector): {registry_drift}")

    # mutual databases swap: equality-invisible like the sha exchange —
    # owned by the manifest<->registry parity layer (asserted at that layer).
    import re as _re

    manifest = load_manifest()
    tbc_dbs = dict(flavor_by_id(manifest, "tbc")["databases"])
    wotlk_dbs = dict(flavor_by_id(manifest, "wotlk")["databases"])
    flavor_by_id(manifest, "tbc")["databases"] = wotlk_dbs
    flavor_by_id(manifest, "wotlk")["databases"] = tbc_dbs
    kotlin = (ROOT / "android/app/src/main/java/com/pocketrealm/client/"
              "ClientFlavor.kt").read_text(encoding="utf-8")
    registry_drift = []
    for flavor in manifest["flavors"]:
        cid = flavor["client_id"]
        i = kotlin.find(f'clientId = "{cid}"')
        m = _re.search(
            r"databases = ClientFlavor\.DatabaseNames\((.*?)\)", kotlin[i:], _re.S)
        assert m, f"{cid}: no registry databases"
        reg = dict(_re.findall(r"(\w+) = \"([^\"]+)\"", m.group(1)))
        if flavor["databases"] != reg:
            registry_drift.append(cid)
    assert sorted(registry_drift) == ["wow-2.4.3-8606", "wow-3.3.5a-12340"], (
        "the mutual databases exchange must drift BOTH flavors against the "
        f"registry (the parity layer's detector): {registry_drift}")

    # the one-way control stays refused (the owner-map's original shape)
    manifest = load_manifest()
    flavor_by_id(manifest, "tbc")["mpq_set"]["required"] = wotlk_list
    errors = validate_manifest(manifest, tmp_path)
    assert any("duplicate mpq_set.required across flavors" in e for e in errors), errors
