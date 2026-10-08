#!/usr/bin/env python3
"""Repository hygiene gate.

Fails (exit 1) when tracked content regresses into known problem classes:
forbidden build-output paths, committed oversized blobs outside the allowlist,
personal identifiers (loaded from a gitignored local markers file)
reappearing in tracked text, the flavor manifest drifting from its
multi-flavor contract (tools/check_flavor.py), banned flavor-identity
literals leaking outside their owning tables (tools/leak_sweep.py), the
vanilla migration-ledger bytes drifting from the G3-3 golden seal
(tools/check_ledger_golden.py), the vanilla addon catalog being edited
off its G3-6 golden sha, or the section 7 E-drive contract regressing
(tools/check_e_only.py: toolchain env redirections, C: path literals in
tracked files, cache roots configured onto C:).

Safe in CI or as a pre-commit gate (same contract as tools/check_sources.py).
Personal-identifier scanning reads tools/pii_markers.local.json when present
(copy it from pii_markers.local.json.example and fill in your own values).
When the markers file is absent — CI, fresh clones — that specific scan is
SKIPPED with a loud note; all other checks still run.
"""
from __future__ import annotations

import fnmatch
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUBMODULE_PREFIXES = ("native/cmangos", "native/playerbots", "native/classic-db")
MARKERS_FILE = ROOT / "tools" / "pii_markers.local.json"
FLAVOR_MANIFEST = ROOT / "schemas" / "flavor.json"

# G3-6 golden pin: the shipping vanilla addon catalog, frozen byte-for-byte
# (measured 2026-09-30: 270,121 bytes). Any content change fails this gate;
# a genuine content fix is a Decision Log entry + sha rotation (G3-6 item 1).
ADDON_CATALOG_VANILLA_SHA256 = (
    "1741a486e919696be6569c5f28b2ef53841737cd339f40d7e85be2ee569d1dba"
)


def check_flavor_manifest(errors: list[str]) -> None:
    """Gate schemas/flavor.json through tools/check_flavor.py (multi-flavor contract)."""
    import check_flavor

    for error in check_flavor.validate(FLAVOR_MANIFEST):
        errors.append(f"flavor.json: {error}")


def check_timeframes_gate(errors: list[str]) -> None:
    """Gate schemas/timeframes + the era/overlay chain through
    tools/check_timeframes.py (design §8: the manifest/overlay validation
    joins the repo Python gates)."""
    import check_timeframes

    for error in check_timeframes.validate():
        errors.append(f"timeframes: {error}")


def check_flavor_source_structure(errors: list[str]) -> None:
    """Round-3 R3-4/R3-6: the flavor-registry provenance chain is pinned
    structurally, not just content-wise —

    (a) every flavor source id the binding table expects EXISTS in
        schemas/sources.json (a coordinated deletion + re-point can no
        longer bind a foreign group to the vanilla entry);
    (b) every git-submodule source id maps 1:1 to a .gitmodules path and
        the superproject's committed gitlink equals sources.json's pin
        (the committed provenance cannot silently diverge from the
        verified working tree — the drift marker check_sources strips)."""
    import json

    sources_path = ROOT / "schemas" / "sources.json"
    manifest_path = ROOT / "schemas" / "flavor.json"
    gitmodules_path = ROOT / ".gitmodules"
    for required in (sources_path, manifest_path, gitmodules_path):
        if not required.is_file():
            errors.append(f"flavor-source structure: {required.name} is missing")
            return
    sources = json.loads(sources_path.read_text(encoding="utf-8"))["sources"]
    by_id = {
        s.get("id"): s
        for s in sources
        if isinstance(s, dict) and s.get("kind") == "git-submodule"
    }
    expected_ids = {
        "cmangos-core", "cmangos-db", "playerbots",
        "cmangos-core-tbc", "cmangos-db-tbc", "playerbots-tbc",
        "cmangos-core-wotlk", "cmangos-db-wotlk", "playerbots-wotlk",
    }
    for source_id in sorted(expected_ids):
        entry = by_id.get(source_id)
        if entry is None:
            errors.append(
                f"flavor-source structure: sources.json lost the git-submodule "
                f"entry {source_id!r} (the flavor binding table requires it — a "
                "deleted entry lets a foreign group bind to the vanilla source)"
            )
            continue
        path = entry.get("path")
        commit = entry.get("commit")
        if not path or not commit:
            errors.append(
                f"flavor-source structure: sources.json entry {source_id!r} "
                "lost its path or commit pin"
            )
            continue
        # .gitmodules 1:1 mapping
        gitmodules = gitmodules_path.read_text(encoding="utf-8")
        if f'path = {path}' not in gitmodules:
            errors.append(
                f"flavor-source structure: sources.json {source_id!r} names path "
                f"{path!r} which .gitmodules does not register"
            )
        # committed gitlink == sources.json pin (R3-6: the drift marker
        # check_sources strips is asserted HERE, against the INDEX bytes)
        out = subprocess.run(
            ["git", "ls-files", "-s", "--", path],
            cwd=ROOT, capture_output=True, text=True,
        )
        line = out.stdout.strip().splitlines()[0] if out.stdout.strip() else ""
        fields = line.split()
        gitlink = fields[1] if len(fields) >= 2 and fields[0] == "160000" else None
        if gitlink is None:
            errors.append(
                f"flavor-source structure: {path} has no committed gitlink in "
                "the index (the submodule pins are not recorded)"
            )
        elif gitlink != commit:
            errors.append(
                f"flavor-source structure: {path} gitlink {gitlink[:12]} != "
                f"sources.json commit {commit[:12]} — the committed provenance "
                "diverged from the pinned source"
            )


def check_e_drive_gate(errors: list[str]) -> None:
    """Gate the section 7 E-drive contract through tools/check_e_only.py
    (COMPLETION-PLAN W0): the toolchain env redirection table, the C:
    path-literal sweep over tracked files, and the cache-root-on-C: list.
    Notes from the gate (stale live-env daemons) print directly — like the
    personal-marker skip note — so a pre-redirection process tree is named
    without failing the gate the registry already satisfies."""
    import check_e_only

    report = check_e_only.collect_findings(ROOT)
    for note in report["notes"]:
        print(note)
    for finding in report["findings"]:
        code = finding.get("code", "E-?")
        where = finding.get("path", finding.get("var", "?"))
        line = finding.get("line")
        loc = f":{line}" if isinstance(line, int) and line else ""
        errors.append(f"e_only: {code} {where}{loc} {finding['detail']}")


def check_leaks(errors: list[str]) -> None:
    """Gate the §4.6 cross-flavor leak sweep (tools/leak_sweep.py).

    The sweep is the tripwire for banned flavor-identity literals outside
    their owning tables; any finding it reports outside the allowlist fails
    the gate here, so the sweep can never drift out of CI again.
    """
    import leak_sweep

    findings, _allowlisted, _scanned = leak_sweep.sweep(ROOT)
    for finding in findings:
        literals = ", ".join(finding["literals"])  # type: ignore[arg-type]
        errors.append(f"leak_sweep: {finding['path']}:{finding['line']} {literals}")


def check_ledger_golden(errors: list[str]) -> None:
    """Gate the G3-3 vanilla migration-ledger golden seal
    (tools/check_ledger_golden.py): the packaged vanilla ledger bytes are
    pinned so S1's per-flavor renames can never re-key what deployed vanilla
    datadirs' migration seals verify."""
    import check_ledger_golden

    for error in check_ledger_golden.validate(ROOT):
        errors.append(error)


def check_addon_catalog_golden(errors: list[str]) -> None:
    """Gate the G3-6 vanilla addon-catalog golden seal: the shipping
    addons/catalog-v1.json asset is frozen byte-for-byte; per-flavor catalogs
    are new assets (H4-3), never an edit of the vanilla file."""
    import hashlib

    catalog = ROOT / "android/app/src/main/assets/addons/catalog-v1.json"
    if not catalog.is_file():
        errors.append("addon catalog golden: android/app/src/main/assets/addons/catalog-v1.json is missing")
        return
    actual = hashlib.sha256(catalog.read_bytes()).hexdigest()
    if actual != ADDON_CATALOG_VANILLA_SHA256:
        errors.append(
            f"addon catalog golden: addons/catalog-v1.json sha256 {actual} != pinned "
            f"{ADDON_CATALOG_VANILLA_SHA256} (the vanilla catalog is never edited; "
            "H4-3 per-flavor catalogs are new assets — G3-6 item 1)"
        )


# S1 provenance-divergence check (port plan S1 item 1, decision D36): the o11
# extractor commit is recorded in four places that must name the very same
# commit — the o11 extractor lockfiles (both ABIs), the o09/o11 driver pin,
# the app constant, and the flavor manifest's vanilla server_core pin. The
# c096bada↔ce83805d split this check forever prevents was a real one: the
# staged extractors had been built from one commit while the lockfiles and
# the app recorded another.
O11_LOCKFILES = (
    "schemas/o11-extractor-lockfile.json",
    "schemas/o11-extractor-lockfile-arm64-v8a.json",
)
O09_DRIVER = "tools/build_o09_realm_runtime.py"
DATA_PREP_STORE = (
    "android/app/src/main/java/com/pocketrealm/importer/DataPreparationStore.kt"
)


def check_extractor_provenance(errors: list[str]) -> None:
    """Fail on any divergence between the o11 extractor lockfile commits, the
    o09 driver's CMANGOS_COMMIT pin, DataPreparationStore.CMANGOS_COMMIT, and
    flavor.json's vanilla server_core.commit (the S1 'can never diverge
    again' CI)."""
    import re as _re

    pins: dict[str, str] = {}
    for rel in O11_LOCKFILES:
        path = ROOT / rel
        if not path.is_file():
            errors.append(f"extractor provenance: {rel} is missing")
            continue
        pins[rel] = json.loads(path.read_text(encoding="utf-8")).get("cmangos_commit", "")
    driver = ROOT / O09_DRIVER
    match = _re.search(
        r'^CMANGOS_COMMIT\s*=\s*"([0-9a-f]{40})"',
        driver.read_text(encoding="utf-8"), _re.M,
    )
    if not match:
        errors.append(f"extractor provenance: {O09_DRIVER} lost its CMANGOS_COMMIT pin")
    else:
        pins[O09_DRIVER] = match.group(1)
    store = ROOT / DATA_PREP_STORE
    match = _re.search(
        r'CMANGOS_COMMIT\s*=\s*"([0-9a-f]{40})"',
        store.read_text(encoding="utf-8"),
    )
    if not match:
        errors.append(f"extractor provenance: {DATA_PREP_STORE} lost its CMANGOS_COMMIT constant")
    else:
        pins[DATA_PREP_STORE] = match.group(1)
    manifest = json.loads(FLAVOR_MANIFEST.read_text(encoding="utf-8"))
    vanilla = next(
        (f for f in manifest.get("flavors", []) if f.get("id") == "vanilla"), None
    )
    if vanilla is None:
        errors.append("extractor provenance: flavor.json has no vanilla flavor")
    else:
        pins["schemas/flavor.json (vanilla server_core)"] = (
            (vanilla.get("server_core") or {}).get("commit") or ""
        )
    distinct = sorted({pin for pin in pins.values() if pin})
    if len(distinct) > 1:
        detail = "; ".join(f"{name}={pin[:12]}" for name, pin in sorted(pins.items()))
        errors.append(
            "extractor provenance split detected — the o11 extractor commit "
            f"diverged across {detail}. Rebuild the extractors from the "
            "driver pin and update every record together (decision D36; the "
            "c096bada↔ce83805d split must never reopen)"
        )
    elif not distinct:
        errors.append("extractor provenance: no commit pin found in any of the four records")


FORBIDDEN_PATTERNS = [
    "native/.tmp-*",
    "CMakeFiles/*",
    "*/CMakeCache.txt",
    "CMakeCache.txt",
    "*/CMakeConfigureLog.yaml",
    "CMakeConfigureLog.yaml",
    "*.tlog",
    "*.pdb",
    "*.ilk",
    "*.lastbuildstate",
    "*.recipe",
]

# Scratch debris never belongs at the repo root, whatever the extension case.
ROOT_FORBIDDEN_SUFFIXES = (".png", ".jpg", ".jpeg", ".exe")

# Tracked blobs larger than this are errors outside the allowlist prefixes.
MAX_BLOB_BYTES = 1024 * 1024
BLOB_ALLOWLIST_PREFIXES = (
    "docs/",
    "native/xserver-winlator/",
    "runtime/xserver-winlator/",
    "tests/avd/",
    "android/app/src/main/assets/",
    "android/pocketrealm-llm/prebuilt/",
    "schemas/",
)


def load_markers() -> tuple[list[str], list[str], bool]:
    """Load personal-identifier markers from the gitignored local file.

    Returns (error_markers, warn_markers, present). Absent file => empty
    lists and present=False, which downgrades ONLY the identifier scan.
    """
    try:
        data = json.loads(MARKERS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return [], [], False
    errors = [m for m in data.get("error_markers", []) if isinstance(m, str) and m]
    warns = [m for m in data.get("warn_markers", []) if isinstance(m, str) and m]
    return errors, warns, True


BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".docx", ".zip", ".7z",
    ".exe", ".dll", ".so", ".obj", ".o", ".a", ".bin", ".apk", ".mpq", ".tzst",
    ".tar", ".gz", ".xz", ".zst", ".jar", ".class", ".keystore", ".p12",
}


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True
    ).stdout.decode("utf-8", "replace")
    return [f for f in out.split("\0") if f and not f.startswith(SUBMODULE_PREFIXES)]


def is_binary(path: Path) -> bool:
    if path.suffix.lower() in BINARY_SUFFIXES:
        return True
    try:
        with path.open("rb") as handle:
            return b"\x00" in handle.read(8192)
    except OSError:
        return True


def check_forbidden_paths(files: list[str], errors: list[str]) -> None:
    for path in files:
        norm = path.replace("\\", "/")
        lowered = norm.lower()
        if "/" not in norm and lowered.endswith(ROOT_FORBIDDEN_SUFFIXES):
            errors.append(f"forbidden root-level artifact {norm!r} (root images/exe ban)")
            continue
        for pattern in FORBIDDEN_PATTERNS:
            if fnmatch.fnmatch(lowered, pattern.lower()) or fnmatch.fnmatch(
                lowered, ("**/" + pattern).lower()
            ):
                errors.append(f"forbidden tracked path {norm!r} (pattern {pattern})")
                break


def check_blob_sizes(files: list[str], errors: list[str]) -> None:
    for path in files:
        norm = path.replace("\\", "/")
        if norm.startswith(BLOB_ALLOWLIST_PREFIXES):
            continue
        try:
            size = os.lstat(ROOT / path).st_size
        except OSError:
            continue
        if size > MAX_BLOB_BYTES:
            errors.append(
                f"tracked blob {norm} is {size} bytes (> {MAX_BLOB_BYTES}); "
                f"vendored/report artifacts belong under the allowlist roots"
            )


def check_pii(
    files: list[str],
    errors: list[str],
    warnings: list[str],
    error_markers: list[str],
    warn_markers: list[str],
    markers_present: bool,
) -> None:
    if not markers_present:
        print(
            "note: personal-identifier scan SKIPPED — tools/pii_markers.local.json "
            "not present (copy it from pii_markers.local.json.example to enable)"
        )
        return
    for path in files:
        norm = path.replace("\\", "/")
        full = ROOT / path
        if is_binary(full):
            continue
        try:
            text = full.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        lowered_text = text.lower()
        for marker in error_markers:
            if marker.lower() in lowered_text:
                errors.append(f"personal identifier {marker!r} present in {norm}")
        for marker in warn_markers:
            if marker.lower() in lowered_text:
                warnings.append(f"personal-path literal {marker!r} present in {norm}")


def main() -> int:
    # Strict is the default: WARN-tier findings fail. --no-strict downgrades
    # personal-path literals to warnings for history work.
    strict = "--no-strict" not in sys.argv
    errors: list[str] = []
    warnings: list[str] = []

    error_markers, warn_markers, markers_present = load_markers()

    files = tracked_files()
    check_forbidden_paths(files, errors)
    check_blob_sizes(files, errors)
    check_pii(
        files, errors, warnings, error_markers, warn_markers, markers_present
    )
    check_flavor_manifest(errors)
    check_leaks(errors)
    check_ledger_golden(errors)
    check_addon_catalog_golden(errors)
    check_extractor_provenance(errors)
    check_timeframes_gate(errors)
    check_flavor_source_structure(errors)
    check_e_drive_gate(errors)

    for warning in sorted(set(warnings)):
        print(f"WARN {warning}")
    if errors:
        for error in sorted(set(errors)):
            print(f"ERROR {error}")
        print(f"check_repo: FAILED ({len(set(errors))} errors, {len(set(warnings))} warnings)")
        return 1
    if strict and warnings:
        print(f"check_repo: FAILED in strict mode ({len(set(warnings))} warnings)")
        return 1
    print("note: --no-strict downgrades personal-path literals to warnings")
    print(
        f"check_repo: OK ({len(files)} tracked files, "
        f"0 errors, {len(set(warnings))} warnings)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
