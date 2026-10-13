"""W5 packaging-gate coverage pins (R6-2, code-red round 1) + the per-flavor
failure-remedy pin (R6-1).

The per-flavor native-runtime freshness fences
(validateNativeRuntimeFreshnessTbc/Wotlk) and the per-flavor o11 extractor
staged-bytes gates (validateFlavorExtractorStagingTbc/Wotlk) exist only in
android/app/build.gradle.kts; these static pins hold their registration,
wiring, lockfile naming, additive-skip posture, and remedies so a future edit
that breaks any of it (or regresses the remedy to the vanilla-only constant)
fails a suite instead of shipping green. House style: text pins on the build
script (test_repo_hygiene_pins.py, test_gladio_client_unpack_transport.py).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "android" / "app" / "build.gradle.kts"


def _gradle_text() -> str:
    return BUILD_GRADLE.read_text(encoding="utf-8")


def test_per_flavor_gate_set_is_derived_from_the_owning_table():
    """R1-r3-1: the per-flavor set must be DERIVED from schemas/flavor.json
    (the owning table), never hand-copied — a future qualified flavor gains
    both gate families and a retirement drops them without a build-script
    edit. The trackedJsonMap read keeps the manifest a configuration-
    cache-tracked input; the registrations stay unconditionally top-level."""
    import json

    text = _gradle_text()
    manifest = json.loads(
        (ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    expected = sorted(
        flavor["id"] for flavor in manifest["flavors"]
        if flavor.get("qualified") and flavor["id"] != "vanilla"
    )
    assert expected, "flavor.json pins no qualified non-vanilla flavors"

    # The derivation chain, not a literal list.
    assert 'trackedJsonMap("schemas/flavor.json")' in text, (
        "the per-flavor gate set must be read from schemas/flavor.json via "
        "trackedJsonMap (configuration-cache-tracked), not hand-copied"
    )
    assert 'it["qualified"] == true && it["id"] != "vanilla"' in text, (
        "the derivation must select qualified non-vanilla flavors"
    )
    for registration in (
        "val perFlavorRuntimeFreshnessTasks = qualifiedPerFlavorIds.map { flavor ->",
        "val perFlavorExtractorStagingTasks = qualifiedPerFlavorIds.map { flavorId ->",
    ):
        assert registration in text, f"missing unconditional registration: {registration}"
        prefix = text[: text.index(registration)].rstrip()
        last_line = prefix.splitlines()[-1].strip() if prefix.splitlines() else ""
        # The line above a top-level registration may be a comment, a closing
        # brace, or a derivation-chain continuation (".map { ... }") — never
        # an open `if (...)` head.
        guarded = last_line.startswith("if (") or (not last_line.startswith("}") and not last_line.startswith("//") and not last_line.startswith("."))
        assert not guarded, (
            f"registration appears guarded by its preceding line {last_line!r}: "
            f"{registration}"
        )
    assert 'listOf("tbc", "wotlk")' not in text, (
        "the hand-copied per-flavor literal must stay gone — the manifest is "
        "the single owner of the set"
    )


def test_fence_tasks_fail_loud_on_malformed_lockfile_rows():
    """R3-r3-1: a lockfile row missing path/size/sha256 must produce a
    failure reason, never a silent narrowing to the intact rows (the
    dropped-row + absent-staged-file combination passed 'OK' before)."""
    text = _gradle_text()
    # One explicit malformed-row reason branch per fence task class:
    # ValidateNativeRuntimeFreshnessTask and ValidateFlavorExtractorStagingTask.
    assert text.count('"artifact row(s) (missing path/size/sha256)') == 2, (
        "both fence task classes must carry the explicit malformed-row "
        "failure branch (bare mapNotNull drops are the R3-r3-1 fail-open)"
    )


def test_per_flavor_gates_ride_every_native_runtime_freshness_edge():
    text = _gradle_text()
    # stageNativeLibs + the full-lane shipping variant edge + the realmRuntime
    # variant edge = the three places validateNativeRuntimeFreshness is wired;
    # both per-flavor families must ride each one.
    for family in (
        "dependsOn(perFlavorRuntimeFreshnessTasks)",
        "dependsOn(perFlavorExtractorStagingTasks)",
    ):
        assert text.count(family) == 3, (
            f"{family} must ride exactly the three edges the vanilla freshness "
            f"fence rides (stageNativeLibs, full-lane variants, realmRuntime); "
            f"found {text.count(family)}"
        )


def test_lockfile_name_templates_match_the_naming_schema():
    text = _gradle_text()
    # docs/FLAVOR-ARTIFACT-NAMING.md §2 via tools/flavor_layout._suffixed_name:
    # x86_64 keeps the historical no-abi-suffix form, arm64 carries the abi
    # segment AFTER the flavor segment. Both families must branch identically.
    expected_branches = (
        '"schemas/realm-runtime-lockfile-$flavor.json"',
        '"schemas/realm-runtime-lockfile-$flavor-$pocketAbi.json"',
        '"schemas/o11-extractor-lockfile-$flavorId.json"',
        '"schemas/o11-extractor-lockfile-$flavorId-$pocketAbi.json"',
    )
    for branch in expected_branches:
        assert branch in text, f"missing lockfile-name branch: {branch}"
    # And the committed arm64 lockfiles those templates resolve to exist.
    for rel in (
        "schemas/realm-runtime-lockfile-tbc-arm64-v8a.json",
        "schemas/realm-runtime-lockfile-wotlk-arm64-v8a.json",
        "schemas/o11-extractor-lockfile-tbc-arm64-v8a.json",
        "schemas/o11-extractor-lockfile-wotlk-arm64-v8a.json",
    ):
        assert (ROOT / rel).is_file(), f"missing committed per-flavor lockfile: {rel}"


def test_additive_skip_posture_vanilla_off_per_flavor_on():
    text = _gradle_text()
    # Exactly the two vanilla fence instances run unconditionally (their
    # staging is required); exactly the per-flavor map opts into the
    # execution-time skip-when-absent mode.
    assert text.count("skipWhenStagingAbsent.set(false)") == 2, (
        "the vanilla fences must keep skipWhenStagingAbsent=false (absent "
        "vanilla staging is a hard failure, not a skip)"
    )
    assert text.count("skipWhenStagingAbsent.set(true)") == 1, (
        "the per-flavor registrations must keep the single additive-skip opt-in"
    )


def test_per_flavor_remedy_names_the_flavor_lane():
    text = _gradle_text()
    # R6-1: the vanilla instances keep the lane-generic remedy; the per-flavor
    # registration interpolates its own flavor so the printed command rebuilds
    # the STALE tree instead of silently rebuilding vanilla.
    assert text.count(
        '"rerun the full lane build: python tools/build_o09_realm_runtime.py "',
    ) == 2, "the vanilla remedy constant moved — keep it on both vanilla fences"
    assert (
        '"rerun the $flavor lane build: python tools/build_o09_realm_runtime.py "'
        in text
    ), "the per-flavor registration lost its flavor-interpolated remedy"
    assert '"--flavor $flavor --abi $pocketAbi (matching -PpocketLane)"' in text, (
        "the per-flavor remedy must name --flavor and --abi (the tool's "
        "--flavor default is vanilla; a vanilla-only remedy can never fix a "
        "stale tbc/wotlk tree)"
    )
    # The fail() path consumes the per-instance property, not a constant.
    assert '"\\n  Remedy: " + remedy.get()' in text, (
        "the fence failure must print the per-instance remedy"
    )
    # R6-r2-2/R1-r2-2: the sibling extractor gate must interpolate BOTH flags —
    # tools/build_o11_extractors.py defaults --abi to x86_64, so a remedy that
    # omits it rebuilds the wrong-abi tree and the arm64 gate keeps failing.
    assert (
        '"python tools/build_o11_extractors.py "'
        in text
    ), "the extractor remedy lost its command line"
    assert '"--flavor ${flavor.get()} --abi ${selectedAbi.get()}"' in text, (
        "the extractor remedy must interpolate the selected abi "
        "(--abi defaults to x86_64 in tools/build_o11_extractors.py)"
    )
    assert '"(matching -PpocketAbi)"' not in text, (
        "the parenthetical-only form names a gradle property, not a tool flag "
        "— the remedy must carry the real --abi/--flavor arguments"
    )


def test_server_runtime_files_claims_match_the_w5_tree():
    """R4-r2-1 + R6-r4-1: the boot/lane-scope gates' comments and refusal
    copies must not carry claims W5 falsified. The per-flavor android
    lockfiles EXIST now; the packaging follow-up is recorded as D90, not
    as 'W5 work'. Both in-repo twins are held: ServerRuntimeFiles.kt and
    DatabaseEngine.kt."""
    expected_lockfiles = (
        "schemas/realm-runtime-lockfile-tbc-arm64-v8a.json",
        "schemas/realm-runtime-lockfile-wotlk-arm64-v8a.json",
    )
    for rel in expected_lockfiles:
        assert (ROOT / rel).is_file(), (
            "the comment's corrected premise requires these to exist"
        )
    for source_rel in (
        "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt",
        "android/app/src/main/java/com/pocketrealm/database/DatabaseEngine.kt",
    ):
        source = (ROOT / source_rel).read_text(encoding="utf-8")
        assert "W5 work" not in source, (
            f"{source_rel}: the refusal copy names W5 as the pending owner — "
            "W5 is the wave being exited; it must point at the recorded "
            "follow-up (D90)"
        )
        assert "D90" in source, (
            f"{source_rel}: the refusal copy must cite the recorded "
            "follow-up decision"
        )
    server = (
        ROOT / "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt"
    ).read_text(encoding="utf-8")
    assert "schemas/ carries no Android per-flavor lockfiles" not in server, (
        "the comment denies the per-flavor lockfiles W5 committed "
        "(schemas/realm-runtime-lockfile-{tbc,wotlk}-arm64-v8a.json)"
    )
    assert "per-flavor Android runtimes are W5 work" not in server


def test_gate_messages_resolve_their_properties():
    """R6-r4-3: failure reasons and OK lines must print the LOCKFILE PATH,
    never Gradle's opaque "task ':app:X' property 'lockfileRelativePath'".
    Both fence task classes must interpolate .get() — zero bare Property
    interpolations of the path/staging properties may remain."""
    text = _gradle_text()
    assert "$lockfileRelativePath" not in text, (
        "a bare $lockfileRelativePath message interpolation prints the "
        "Property reference, not the path — use ${lockfileRelativePath.get()}"
    )
    assert "$stagingRelativeDir" not in text, (
        "a bare $stagingRelativeDir message interpolation prints the "
        "Property reference, not the path — use ${stagingRelativeDir.get()}"
    )


def test_per_flavor_fences_record_the_mysql_only_posture():
    """R1-r4-1: the per-flavor fences are deliberately the MariaDB lane
    only; a per-flavor SQLITE lane would stage ungated. The registration
    must carry the recorded posture comment naming D90 as the owner of the
    backend-parity extension."""
    text = _gradle_text()
    assert "R1-r4-1: these fences are deliberately the MARIADB lane only" in text, (
        "the per-flavor registration lost its mysql-only posture comment"
    )
    assert "D90-owned packaging work" in text, (
        "the posture comment must name D90 as the owner of the sqlite "
        "backend-parity extension"
    )


def test_realm_packaging_carries_no_flavor_segment_the_d90_gap():
    """R1-r6-1: the APK stages only the VANILLA realm staging root — the
    per-flavor o09 runtimes W5 built are verified in-graph but never
    packaged, and the routing is the recorded D90 open work. This pin
    DOCUMENTS the gap: when the D90 -PpocketFlavor routing lands, these
    assertions flip (and D90's status retires) — the gap cannot close
    silently in either direction."""
    text = _gradle_text()
    # stageNativeLibs' realm source dirs: no flavor segment, deliberately.
    assert '"native/.build-o09-$pocketAbi/realm-staging/jniLibs/$pocketAbi"' in text, (
        "the vanilla realm staging source moved — if this is the D90 "
        "flavor routing landing, update D90 and this pin together"
    )
    # The per-flavor STAGING roots exist for the fences but are never
    # staged into the APK: no -$flavor realm source in stageNativeLibs.
    assert "native/.build-o09-$pocketAbi-$flavor/realm-staging/jniLibs" not in text, (
        "a per-flavor realm staging source appeared in the packaging graph "
        "without the D90 decision being retired — close D90 deliberately"
    )


def test_d90_stays_active_until_the_flavor_routing_exists():
    """R1-r6-1: D90 records the per-flavor packaging follow-up; its status
    must stay 'active' while no -PpocketFlavor routing exists in the build
    script — and the ServerRuntimeFiles refusal (its enforcement) must
    still be present."""
    decisions = (
        ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8", errors="replace")
    d90_start = decisions.index("| D90 |")
    d90_row = decisions[d90_start:decisions.index(chr(10), d90_start)]
    routing_exists = "-PpocketFlavor" in _gradle_text()
    if not routing_exists:
        assert d90_row.rstrip().endswith("| active |"), (
            "D90 was retired while no -PpocketFlavor routing exists — the "
            "packaging follow-up is still open"
        )
    refusal = (
        ROOT / "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt"
    ).read_text(encoding="utf-8")
    assert "RUNTIME-LANE MISMATCH" in refusal, (
        "the fail-closed boot refusal must stay while D90 is active"
    )
