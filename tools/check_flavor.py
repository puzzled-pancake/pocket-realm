#!/usr/bin/env python3
"""Validate schemas/flavor.json (schema 2, multi-flavor).

Checks the structural and consistency rules that the build and
release-acceptance gates rely on:

- the manifest is schema 2 and carries a non-empty `flavors` array;
- flavor ids and client ids are unique, lowercase slugs;
- at least one flavor is qualified (the app must always ship a lane);
- the optional top-level `single_qualified_lane` flag (default true) holds:
  unless the manifest opts out, exactly one flavor may be qualified (the D9
  runtime contract — one qualified lane until flavor threading retires it);
- database names, build numbers, version resources, and wine prefix segments
  are unique across flavors (two flavors must never share a datadir; the
  prefix-segment comparison is case-insensitive — the segment becomes a
  directory name);
- a qualified flavor has every pin the runtime needs (server core and DB
  commits, four database names, addon interfaces, storage budgets, MPQ set,
  wine prefix segment);
- a qualified flavor's server_core/db_content/bots commit pins are bound to
  the sibling `schemas/sources.json` manifest (the flavor pin and the source
  pin must be the same commit, mapped by upstream URL with an explicit
  fallback table — a drifted pin is a provenance lie);
- a qualified flavor's `extractors.patch` names a file that exists in the
  repository (the build applies it by that path);
- wine.graphics_routes values are renderer route names the runtime knows, and
  a qualified non-vanilla flavor is dxvk-only (decision D3);
- an unqualified flavor must not advertise client hashes or storage budgets
  (import rejects on the qualified flag; advertising data it cannot honor
  would make the gate lie).

Usage:
    python tools/check_flavor.py [--manifest PATH]

Exit codes: 0 = valid, 1 = invalid (errors printed as "ERROR ...").
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_MANIFEST = Path(__file__).resolve().parent.parent / "schemas" / "flavor.json"

SLUG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
CLIENT_ID_RE = re.compile(r"^wow-[0-9]+\.[0-9]+\.[0-9]+[a-z]?-[0-9]+$")
DATABASE_KEYS = ("realmd", "world", "characters", "logs")
VERSION_RESOURCE_RE = re.compile(r"^\d+\.\d+\.\d+(\.\d+)?\Z")
ALLOWED_GRAPHICS_ROUTES = frozenset({"dxvk", "opengl", "virgl"})
# D3: the GL/VirGL lanes are vanilla-qualified only; every other flavor ships
# DXVK until a parity stage proves otherwise.
VANILLA_ONLY_GRAPHICS_ROUTES = ("opengl", "virgl")
# Flavor commit groups bind to the sibling sources.json. Resolution matches the
# flavor group's upstream URL against a source's upstream_url first and falls
# back to this explicit id table (covers fork URLs like the playerbots mirror).
FALLBACK_SOURCE_IDS = {
    "server_core": "cmangos-core",
    "db_content": "cmangos-db",
    "bots": "playerbots",
}


def _err(errors: list[str], message: str) -> None:
    errors.append(message)


def _check_flavor(flavor: dict, index: int, errors: list[str]) -> None:
    where = f"flavors[{index}]"
    fid = flavor.get("id")
    if not isinstance(fid, str) or not SLUG_RE.match(fid):
        _err(errors, f"{where}.id must be a lowercase slug, got {fid!r}")
        fid = f"<index-{index}>"

    client_id = flavor.get("client_id")
    if not isinstance(client_id, str) or not CLIENT_ID_RE.match(client_id):
        _err(errors, f"{where}.client_id must look like 'wow-1.12.1-5875', got {client_id!r}")

    build = flavor.get("build")
    build_is_int = isinstance(build, int) and not isinstance(build, bool)
    if not build_is_int or build <= 0:
        _err(errors, f"{where}.build must be a positive integer, got {build!r}")

    version = flavor.get("version_resource")
    if not isinstance(version, str) or not VERSION_RESOURCE_RE.match(version):
        _err(errors, f"{where}.version_resource must be dotted numerals, got {version!r}")
    elif build_is_int and build > 0 and not version.endswith("." + str(build)):
        _err(errors, f"{where}.version_resource {version!r} does not end in build {build}")

    if not isinstance(flavor.get("qualified"), bool):
        _err(errors, f"{where}.qualified must be a boolean")

    for field in ("expansion", "client_version"):
        if not isinstance(flavor.get(field), str) or not flavor[field]:
            _err(errors, f"{where}.{field} must be a non-empty string")

    for group in ("server_core", "db_content", "bots", "databases", "wine", "mpq_set", "llm"):
        if not isinstance(flavor.get(group), dict):
            _err(errors, f"{where}.{group} must be an object")

    wine = flavor.get("wine")
    if isinstance(wine, dict):
        routes = wine.get("graphics_routes")
        if routes is not None:
            if not isinstance(routes, list) or not all(isinstance(route, str) for route in routes):
                _err(
                    errors,
                    f"{where}.wine.graphics_routes must be a list of route names, got {routes!r}",
                )
            else:
                unknown = sorted(set(routes) - ALLOWED_GRAPHICS_ROUTES)
                if unknown:
                    _err(
                        errors,
                        f"{where}.wine.graphics_routes must be within "
                        f"{sorted(ALLOWED_GRAPHICS_ROUTES)}, got {unknown!r}",
                    )
                # D3: the GL/VirGL lanes are qualified against vanilla only; a
                # new flavor declaring them must record a D-log exception.
                if flavor.get("qualified") is True and flavor.get("id") != "vanilla":
                    gl_routes = [route for route in routes if route in VANILLA_ONLY_GRAPHICS_ROUTES]
                    if gl_routes:
                        _err(
                            errors,
                            f"{where}.wine.graphics_routes {gl_routes!r}: D3 is dxvk-only for "
                            "non-vanilla flavors (record any exception in docs/DECISIONS-PORT.md)",
                        )
    # wine.vanilla_tweaks carries no rule here: vanilla-client UX parity is
    # owned by port plan S7.

    if isinstance(flavor.get("addon_interfaces"), list):
        if not flavor["addon_interfaces"] or not all(
            isinstance(v, int) and 10000 <= v <= 100000 for v in flavor["addon_interfaces"]
        ):
            _err(errors, f"{where}.addon_interfaces must be non-empty UI interface numbers")
    else:
        _err(errors, f"{where}.addon_interfaces must be a list")

    if not isinstance(flavor.get("supported_client_builds"), list):
        _err(errors, f"{where}.supported_client_builds must be a list")

    databases = flavor.get("databases")
    if isinstance(databases, dict):
        for key in DATABASE_KEYS:
            name = databases.get(key)
            if not isinstance(name, str) or not SLUG_RE.match(name):
                _err(errors, f"{where}.databases.{key} must be a slug, got {name!r}")

    qualified = flavor.get("qualified") is True
    if qualified:
        for group, key in (("server_core", "commit"), ("db_content", "commit")):
            value = (flavor.get(group) or {}).get(key)
            if not isinstance(value, str) or len(value) != 40:
                _err(errors, f"{where}.{group}.{key} must be a 40-hex commit for a qualified flavor")
        bots = flavor.get("bots") or {}
        if bots.get("status") != "pinned" or not isinstance(bots.get("commit"), str):
            _err(errors, f"{where}.bots must be pinned (status/commit) for a qualified flavor")
        storage = flavor.get("storage")
        if not isinstance(storage, dict) or not all(
            isinstance(storage.get(k), int) and storage[k] > 0
            for k in ("extracted_estimate_bytes", "max_total_bytes")
        ):
            _err(errors, f"{where}.storage budgets must be positive integers for a qualified flavor")
        mpq = flavor.get("mpq_set") or {}
        required = mpq.get("required")
        if not isinstance(required, list) or not required:
            _err(errors, f"{where}.mpq_set.required must list the client's base MPQ set")
        wine = flavor.get("wine") or {}
        for key in ("prefix_segment",):
            if not isinstance(wine.get(key), str) or not wine[key]:
                _err(errors, f"{where}.wine.{key} must be set for a qualified flavor")
        if flavor.get("supported_client_builds") == [] and not flavor.get("supported_client_builds_note"):
            _err(
                errors,
                f"{where}: empty supported_client_builds needs a note explaining the qualification state",
            )
    else:
        if flavor.get("supported_client_builds"):
            _err(
                errors,
                f"{where}: unqualified flavors must not advertise supported_client_builds "
                "(import rejects on qualified=false; advertising hashes would make the gate lie)",
            )
        if flavor.get("storage") is not None:
            _err(
                errors,
                f"{where}: unqualified flavors must keep storage null until budgets are measured",
            )


def _load_sources(sources_path: Path) -> tuple[list[dict] | None, str | None]:
    """Read the sibling sources manifest defensively (it carries its own schema
    version and prose fields; only the id/upstream_url/commit shape is relied
    on). Returns (sources, error) — exactly one is non-None."""
    try:
        data = json.loads(sources_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None, (
            f"sources manifest not found: {sources_path} "
            "(flavor commit pins cannot be cross-checked without it)"
        )
    except json.JSONDecodeError as exc:
        return None, f"sources manifest is not valid JSON: {sources_path}: {exc}"
    except OSError as exc:
        return None, f"sources manifest is unreadable: {sources_path}: {exc}"
    sources = data.get("sources") if isinstance(data, dict) else None
    if not isinstance(sources, list):
        return None, f"sources manifest has no sources array: {sources_path}"
    return [source for source in sources if isinstance(source, dict)], None


def _resolve_source(group: dict, fallback_id: str, sources: list[dict]) -> dict | None:
    """Map a flavor commit group to its sources.json entry: upstream URL match
    first, then the explicit fallback id (covers fork URLs)."""
    upstream = group.get("upstream")
    if isinstance(upstream, str) and upstream:
        for source in sources:
            if source.get("upstream_url") == upstream:
                return source
    for source in sources:
        if source.get("id") == fallback_id:
            return source
    return None


def _check_source_pins(
    flavor: dict, index: int, sources: list[dict], errors: list[str]
) -> None:
    """Bind every qualified flavor's commit pins to sources.json: the flavor
    pin and the source pin must be the very same commit."""
    where = f"flavors[{index}]"
    for group, fallback_id in FALLBACK_SOURCE_IDS.items():
        group_data = flavor.get(group)
        if not isinstance(group_data, dict):
            continue  # structural error already reported
        commit = group_data.get("commit")
        if not isinstance(commit, str) or not commit:
            continue  # pin-shape error already reported
        source = _resolve_source(group_data, fallback_id, sources)
        if source is None:
            _err(
                errors,
                f"{where}.{group}: no sources.json entry pins upstream "
                f"{group_data.get('upstream')!r} (fallback id {fallback_id!r} "
                "not found either) — add the source entry before qualifying",
            )
            continue
        source_id = source.get("id")
        source_commit = source.get("commit")
        if not isinstance(source_commit, str) or not source_commit:
            _err(
                errors,
                f"{where}.{group}: sources.json entry {source_id!r} has no commit pin",
            )
            continue
        if commit != source_commit:
            _err(
                errors,
                f"{where}.{group}.commit {commit!r} does not match sources.json "
                f"{source_id!r} commit {source_commit!r} (a qualified flavor's pin "
                "must be the pinned source commit, not a drift)",
            )


def _check_extractor_patch(
    flavor: dict, index: int, root: Path, errors: list[str]
) -> None:
    """A qualified flavor's extractor patch must be a real repository file:
    the build applies it by that path, and a dangling pin would surface only
    at packaging time (or never, for a lane nobody builds)."""
    where = f"flavors[{index}]"
    extractors = flavor.get("extractors")
    if not isinstance(extractors, dict):
        return  # structural error already reported
    patch = extractors.get("patch")
    if not isinstance(patch, str) or not patch:
        return  # null/empty carries no file claim
    if not (root / patch).is_file():
        _err(
            errors,
            f"{where}.extractors.patch {patch!r} does not exist in the repository "
            f"(resolved against {root})",
        )


def validate(manifest_path: Path, sources_path: Path | None = None) -> list[str]:
    errors: list[str] = []
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return [f"manifest not found: {manifest_path}"]
    except json.JSONDecodeError as exc:
        return [f"manifest is not valid JSON: {exc}"]

    if manifest.get("schema") != 2:
        _err(errors, f"schema must be 2 (multi-flavor), got {manifest.get('schema')!r}")
        return errors

    flavors = manifest.get("flavors")
    if not isinstance(flavors, list) or not flavors:
        _err(errors, "flavors must be a non-empty array")
        return errors

    ids: set[str] = set()
    client_ids: set[str] = set()
    qualified_count = 0
    qualified_ids: list[str] = []
    # Cross-flavor uniqueness registries (invariant 4: two flavors must never
    # share a datadir — the same holds for the build number, version resource,
    # and wine prefix segment, which pin the very same client identity; the
    # prefix segment is matched case-insensitively).
    db_owners: dict[str, tuple[str, str]] = {}
    build_owners: dict[int, str] = {}
    version_owners: dict[str, str] = {}
    prefix_owners: dict[str, tuple[str, str]] = {}
    for index, flavor in enumerate(flavors):
        if not isinstance(flavor, dict):
            _err(errors, f"flavors[{index}] must be an object")
            continue
        _check_flavor(flavor, index, errors)
        fid = flavor.get("id")
        if isinstance(fid, str):
            if fid in ids:
                _err(errors, f"duplicate flavor id: {fid!r}")
            ids.add(fid)
        cid = flavor.get("client_id")
        if isinstance(cid, str):
            if cid in client_ids:
                _err(errors, f"duplicate client_id: {cid!r}")
            client_ids.add(cid)
        if flavor.get("qualified") is True:
            qualified_count += 1
            if isinstance(fid, str):
                qualified_ids.append(fid)
        owner = fid if isinstance(fid, str) else f"<index-{index}>"
        databases = flavor.get("databases")
        if isinstance(databases, dict):
            for key in DATABASE_KEYS:
                name = databases.get(key)
                if not isinstance(name, str):
                    continue
                prior = db_owners.get(name)
                if prior is not None:
                    _err(
                        errors,
                        f"duplicate database name across flavors: {name!r} is "
                        f"databases.{prior[1]} of {prior[0]!r} and databases.{key} "
                        f"of {owner!r} (two flavors must never share a datadir)",
                    )
                else:
                    db_owners[name] = (owner, key)
        build = flavor.get("build")
        if isinstance(build, int) and not isinstance(build, bool):
            if build in build_owners:
                _err(
                    errors,
                    f"duplicate build across flavors: {build} is pinned by both "
                    f"{build_owners[build]!r} and {owner!r}",
                )
            else:
                build_owners[build] = owner
        version = flavor.get("version_resource")
        if isinstance(version, str):
            if version in version_owners:
                _err(
                    errors,
                    f"duplicate version_resource across flavors: {version!r} is "
                    f"pinned by both {version_owners[version]!r} and {owner!r}",
                )
            else:
                version_owners[version] = owner
        wine = flavor.get("wine")
        if isinstance(wine, dict):
            segment = wine.get("prefix_segment")
            if isinstance(segment, str) and segment:
                # Case-insensitive registry: the prefix segment becomes a
                # directory name, and 'WOW5875' vs 'wow5875' collide on a
                # case-insensitive filesystem even though Python strings
                # differ. Both original values stay in the error message.
                key = segment.lower()
                prior = prefix_owners.get(key)
                if prior is not None:
                    _err(
                        errors,
                        f"duplicate wine.prefix_segment across flavors: {segment!r} "
                        f"is pinned by both {prior[0]!r} ({prior[1]!r}) and {owner!r} "
                        "(two flavors must never share a wine prefix)",
                    )
                else:
                    prefix_owners[key] = (owner, segment)

    if qualified_count == 0:
        _err(errors, "at least one flavor must be qualified (the app must ship a lane)")

    # Single-lane contract (D9): the runtime qualifies exactly one flavor;
    # the manifest may opt out explicitly, but never silently.
    single_lane = manifest.get("single_qualified_lane", True)
    if not isinstance(single_lane, bool):
        _err(errors, f"single_qualified_lane must be a boolean, got {single_lane!r}")
        single_lane = True
    if single_lane and qualified_count != 1:
        _err(
            errors,
            f"single_qualified_lane requires exactly one qualified flavor, got "
            f"{qualified_count} (qualified ids: {', '.join(qualified_ids) or 'none'})",
        )

    # Provenance binding: a qualified flavor's commit pins must be the very
    # commits recorded in schemas/sources.json (R3-4). A drifted pin means the
    # manifest claims a source the repo does not pin.
    if qualified_count:
        if sources_path is None:
            sources_path = manifest_path.parent / "sources.json"
        sources, sources_error = _load_sources(sources_path)
        if sources_error is not None:
            _err(errors, sources_error)
        else:
            for index, flavor in enumerate(flavors):
                if not isinstance(flavor, dict) or flavor.get("qualified") is not True:
                    continue
                _check_source_pins(flavor, index, sources, errors)

        # Extractor provenance: the manifest lives at <repo>/schemas/, so the
        # repository root is two levels up from it.
        root = manifest_path.resolve().parent.parent
        for index, flavor in enumerate(flavors):
            if not isinstance(flavor, dict) or flavor.get("qualified") is not True:
                continue
            _check_extractor_patch(flavor, index, root, errors)

    # Cross-check against the vanilla DB names the runtime was seeded with:
    # the vanilla lane must never silently drift from its provenance.
    for flavor in flavors:
        if flavor.get("id") == "vanilla":
            dbs = flavor.get("databases") or {}
            expected = {
                "realmd": "classicrealmd",
                "world": "classicmangos",
                "characters": "classiccharacters",
                "logs": "classiclogs",
            }
            for key, name in expected.items():
                if dbs.get(key) != name:
                    _err(
                        errors,
                        f"vanilla databases.{key} must stay {name!r} (provenance lock); "
                        "rename only with a migration ledger change",
                    )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    errors = validate(args.manifest)
    if errors:
        for error in sorted(set(errors)):
            print(f"ERROR {error}")
        print(f"check_flavor: FAILED ({len(set(errors))} errors)")
        return 1
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    summary = ", ".join(
        f"{f['id']}={'qualified' if f.get('qualified') else 'unqualified'}"
        for f in manifest["flavors"]
    )
    print(f"check_flavor: OK ({len(manifest['flavors'])} flavors: {summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
