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
- every flavor's server_core/db_content/bots commit pins are bound to the
  sibling `schemas/sources.json` manifest (the flavor pin and the source pin
  must be the same commit, mapped by upstream URL with an explicit
  per-flavor fallback table — a drifted pin is a provenance lie). Since
  correction C4 resolved the tbc/wotlk bots pins while those flavors stay
  unqualified, the binding applies to any group that carries a commit, not
  only to qualified flavors;
- correction C1: the realmd block names the `realmbuilds` varchar(64)
  column and the flavor's accepted build set (its own build included), and
  no `gamebuild` key may exist anywhere in the manifest (that column is
  TrinityCore's schema — a leak-sweep candidate enforced here by name);
- correction C3: the shared auth-challenge opcode is the single 0x1EC
  constant (top-level `auth` block; never per-flavor), and per-flavor
  `auth_frame` descriptors carry only verified frame facts;
- correction C6/C7: the `client_binary` block carries null-or-valid
  provenance fields (md5 hex, positive size, LAA boolean), and
  `wine.known_exe_sha256` is null-or-64-hex;
- correction C8: the `wtf` block names the layout; only wotlk may list the
  WotLK-era char-level cache files;
- correction C9: every `conf_matrix.excluded_keys` entry is a
  self-documenting exclusion (family + reason + register ref + enforcer);
- a qualified flavor's `extractors.patch` names a file that exists in the
  repository (the build applies it by that path);
- wine.graphics_routes values are renderer route names the runtime knows, and
  a qualified non-vanilla flavor is dxvk-only (decision D3);
- an unqualified flavor keeps `storage` null until its budgets are measured,
  and its S5.1 identity registration (lanes item 13: `client_binary` md5/size,
  `wine.known_exe_sha256`, `supported_client_builds`, `mpq_set.required`) is
  either entirely absent or entirely present and consistent — a PARTIAL
  registration (hashes without the build list, a build list without hashes,
  or a build list naming anything but the flavor's own build) is exactly the
  half-truth the pre-S5.1 all-null rule existed to prevent, and fails loud.
  Import still rejects on the qualified flag alone; the registration records
  measured identity, never permission.

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

try:
    from tools import common
except ImportError:
    import common

DEFAULT_MANIFEST = Path(__file__).resolve().parent.parent / "schemas" / "flavor.json"

SLUG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
CLIENT_ID_RE = re.compile(r"^wow-[0-9]+\.[0-9]+\.[0-9]+[a-z]?-[0-9]+$")
DATABASE_KEYS = ("realmd", "world", "characters", "logs")
VERSION_RESOURCE_RE = re.compile(r"^\d+\.\d+\.\d+(\.\d+)?\Z")
ALLOWED_GRAPHICS_ROUTES = frozenset({"dxvk", "opengl", "virgl"})
# the GL/VirGL lanes are vanilla-qualified only; every other flavor ships
# DXVK until a parity stage proves otherwise.
VANILLA_ONLY_GRAPHICS_ROUTES = ("opengl", "virgl")
# Correction: the numeric gamebuild column is TrinityCore's schema and must
# never appear in this manifest, under any group (leak-sweep candidate
# enforced here by key name).
FORBIDDEN_MANIFEST_KEYS = ("gamebuild",)
# Correction: the auth-challenge opcode is ONE shared constant across the
# three branches (register). Pinned so nobody reintroduces a per-flavor
# opcode "parameterization".
SHARED_AUTH_CHALLENGE_OPCODE = "0x1EC"
# Correction: the char-level cache-file set is WotLK-era; only the wotlk
# flavor may carry it in its wtf descriptor.
WOTLK_ONLY_WTF_CACHE_FILES = True
WTF_LAYOUTS = ("account-level", "3-level")
# Flavor commit groups bind to the sibling sources.json. Resolution matches the
# flavor group's upstream URL against a source's upstream_url first, then falls
# back to the flavor's per-flavor source ids and finally the legacy vanilla ids
# (covers fork URLs like the playerbots mirror; the tbc/wotlk per-flavor ids
# landed with the per-flavor sources.json entries).
FALLBACK_SOURCE_IDS = {
    "vanilla": {
        "server_core": ["cmangos-core"],
        "db_content": ["cmangos-db"],
        "bots": ["playerbots"],
    },
    "tbc": {
        "server_core": ["cmangos-core-tbc", "cmangos-core"],
        "db_content": ["cmangos-db-tbc", "cmangos-db"],
        "bots": ["playerbots-tbc", "playerbots"],
    },
    "wotlk": {
        "server_core": ["cmangos-core-wotlk", "cmangos-core"],
        "db_content": ["cmangos-db-wotlk", "cmangos-db"],
        "bots": ["playerbots-wotlk", "playerbots"],
    },
}


# the registered flavor identity table (build -> owning
# flavor id) is derived per validate() call and PRE-REGISTERED before any
# per-flavor check runs (populating it inside the consuming
# loop made the foreign-build rejection order-dependent — the FIRST
# flavor never saw the later flavors' builds — and the module global leaked
# registrations across calls, making the gate non-deterministic in-process).


def _err(errors: list[str], message: str) -> None:
    errors.append(message)


def _has_key(node: object, key: str) -> bool:
    """True if any dict reachable in the (flavor) subtree carries `key` —
    used to enforce the C1 'no gamebuild field anywhere' ban."""
    if isinstance(node, dict):
        if key in node:
            return True
        return any(_has_key(value, key) for value in node.values())
    if isinstance(node, list):
        return any(_has_key(item, key) for item in node)
    return False


def _check_flavor(flavor: dict, index: int, errors: list[str],
                  registered_builds: dict[int, str]) -> None:
    where = f"flavors[{index}]"
    fid = flavor.get("id")
    if not isinstance(fid, str) or not SLUG_RE.match(fid):
        _err(errors, f"{where}.id must be a lowercase slug, got {fid!r}")
        fid = f"<index-{index}>"

    client_id = flavor.get("client_id")
    if not isinstance(client_id, str) or not CLIENT_ID_RE.match(client_id):
        _err(errors, f"{where}.client_id must look like 'wow-1.12.1-5875', got {client_id!r}")
    # the clientId is the LOAD-BEARING managed identity
    # (stamped into every client-manifest.json), so it must be CONSISTENT
    # with the flavor's own build and version resource - the shape regex
    # alone accepted 'wow-1.12.1-9999' for build 5875, a vanilla<->tbc
    # swap, and a mismatched version component.
    build = flavor.get("build")
    version = flavor.get("version_resource")
    if isinstance(client_id, str) and isinstance(build, int) and not isinstance(build, bool)             and isinstance(version, str) and "." in version and "-" in client_id:
        # The naming convention keeps the patch letter in the clientId but
        # drops it from the version resource (wotlk: the clientId's 3.3.5a
        # carries a letter the resource omits), so consistency is per-component: the clientId's
        # version parts carry the resource's numeric parts in order (plus
        # at most a non-digit patch-letter tail), and its trailing build
        # equals `build`.
        cid_version, cid_build = client_id[len("wow-"):].rsplit("-", 1)
        version_parts = version.split(".")[:-1]
        cid_parts = cid_version.split(".")
        version_ok = len(cid_parts) == len(version_parts) and all(
            cid_part.startswith(v_part)
            and (len(cid_part) == len(v_part) or cid_part[len(v_part):].isalpha())
            for cid_part, v_part in zip(cid_parts, version_parts)
        )
        build_ok = cid_build.isdigit() and int(cid_build) == build
        if not (version_ok and build_ok):
            _err(
                errors,
                f"{where}.client_id {client_id!r} contradicts the flavor's own "
                f"identity (version_resource {version!r}, build {build}) - the "
                "managed identity stamps this string into every "
                "client-manifest.json and must carry the flavor's own "
                "version and build",
            )

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

    for group in ("server_core", "db_content", "bots", "databases", "wine", "mpq_set", "llm",
                  "realmd", "auth_frame", "wtf", "client_binary", "conf_matrix"):
        if not isinstance(flavor.get(group), dict):
            _err(errors, f"{where}.{group} must be an object")

    # Correction C1: realmbuilds varchar(64) is the realmlist column in every
    # branch, and the accepted set must include the flavor's own build.
    realmd = flavor.get("realmd")
    if isinstance(realmd, dict):
        if realmd.get("column") != "realmbuilds":
            _err(
                errors,
                f"{where}.realmd.column must be 'realmbuilds' (correction C1: the numeric "
                f"'gamebuild' column is TrinityCore's schema), got {realmd.get('column')!r}",
            )
        accepted = realmd.get("accepted_builds")
        tokens: list[str] = []
        if isinstance(accepted, str) and accepted and all(
            token.isdigit() for token in accepted.split()
        ):
            tokens = accepted.split()
        else:
            _err(
                errors,
                f"{where}.realmd.accepted_builds must be a space-separated build list "
                f"like '5875 6005 6141', got {accepted!r}",
            )
        if tokens and isinstance(build, int) and not isinstance(build, bool):
            if str(build) not in tokens:
                _err(
                    errors,
                    f"{where}.realmd.accepted_builds {accepted!r} does not include the "
                    f"flavor's own build {build}",
                )
        # the rule is the flavor's OWN accepted set — a
        # foreign REGISTERED build in the list would configure a wrong-flavor
        # client/server pair (vanilla's 5875 client authenticating against a
        # tbc realmlist). Manifest is the source of record the W4.2 3x3
        # auth matrix trusts, so a foreign build fails the gate here.
        if tokens and isinstance(build, int) and not isinstance(build, bool):
            foreign = sorted(
                token for token in tokens
                if token.isdigit() and int(token) != build
                and int(token) in registered_builds
            )
            if foreign:
                _err(
                    errors,
                    f"{where}.realmd.accepted_builds {accepted!r} contains foreign "
                    f"registered build(s) {', '.join(foreign)} — the accepted set "
                    f"is this flavor's own builds only (the other registered "
                    f"flavors {sorted(set(registered_builds.values()))} own "
                    "the other builds)",
                )
    for key in FORBIDDEN_MANIFEST_KEYS:
        if _has_key(flavor, key):
            _err(
                errors,
                f"{where}: forbidden key {key!r} (correction C1: realmlist uses "
                "'realmbuilds' in all three branches; 'gamebuild' is TrinityCore's schema)",
            )

    # Correction: frame descriptors carry only verified facts (vanilla's
    # 26-byte proof response today; null until S8 verifies the other branches).
    auth_frame = flavor.get("auth_frame")
    if isinstance(auth_frame, dict):
        proof = auth_frame.get("proof_response_bytes")
        if proof is not None and (not isinstance(proof, int) or isinstance(proof, bool) or proof <= 0):
            _err(
                errors,
                f"{where}.auth_frame.proof_response_bytes must be a positive byte count "
                f"or null (verified at S8), got {proof!r}",
            )

    # Correction: WTF layout descriptor.
    wtf = flavor.get("wtf")
    if isinstance(wtf, dict):
        if wtf.get("layout") not in WTF_LAYOUTS:
            _err(
                errors,
                f"{where}.wtf.layout must be one of {list(WTF_LAYOUTS)}, "
                f"got {wtf.get('layout')!r}",
            )
        cache_files = wtf.get("character_cache_files")
        if not isinstance(cache_files, list) or not all(isinstance(f, str) for f in cache_files):
            _err(errors, f"{where}.wtf.character_cache_files must be a list of file names")
        elif cache_files and WOTLK_ONLY_WTF_CACHE_FILES and fid != "wotlk":
            _err(
                errors,
                f"{where}.wtf.character_cache_files must be empty for {fid!r} — the "
                "char-level cache set is WotLK-era (correction C8)",
            )

    # Corrections: pristine-exe provenance + LAA fact.
    client_binary = flavor.get("client_binary")
    if isinstance(client_binary, dict):
        md5 = client_binary.get("known_exe_md5")
        if md5 is not None and (
            not isinstance(md5, str) or len(md5) != 32 or any(c not in "0123456789abcdef" for c in md5)
        ):
            _err(errors, f"{where}.client_binary.known_exe_md5 must be 32 lowercase hex or null")
        size = client_binary.get("known_exe_size_bytes")
        if size is not None and (not isinstance(size, int) or isinstance(size, bool) or size <= 0):
            _err(errors, f"{where}.client_binary.known_exe_size_bytes must be a positive int or null")
        laa = client_binary.get("large_address_aware")
        if laa is not None and not isinstance(laa, bool):
            _err(errors, f"{where}.client_binary.large_address_aware must be a boolean or null")

    # Correction C9: self-documenting exclusions (F2-8 pattern).
    conf_matrix = flavor.get("conf_matrix")
    if isinstance(conf_matrix, dict):
        excluded = conf_matrix.get("excluded_keys")
        if not isinstance(excluded, list):
            _err(errors, f"{where}.conf_matrix.excluded_keys must be a list")
        else:
            for entry in excluded:
                if not isinstance(entry, dict) or any(
                    not isinstance(entry.get(field), str) or not entry[field]
                    for field in ("key_family", "reason", "register_ref", "enforced_by")
                ):
                    _err(
                        errors,
                        f"{where}.conf_matrix.excluded_keys entries need non-empty "
                        "key_family/reason/register_ref/enforced_by strings",
                    )

    wine = flavor.get("wine")
    if isinstance(wine, dict):
        # C6/S5.1: the SHA-256 telltale is null-or-64-lowercase-hex — the
        # registration rule below then binds its presence to the rest of the
        # identity tuple.
        sha256 = wine.get("known_exe_sha256")
        if sha256 is not None and (
            not isinstance(sha256, str)
            or len(sha256) != 64
            or any(c not in "0123456789abcdef" for c in sha256)
        ):
            _err(
                errors,
                f"{where}.wine.known_exe_sha256 must be 64 lowercase hex or null, got {sha256!r}",
            )
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
                # the GL/VirGL lanes are qualified against vanilla only; a
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
    # owned by.

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
            for k in ("extracted_estimate_bytes", "max_total_bytes",
                      "prepared_estimate_bytes")
        ):
            _err(errors, f"{where}.storage budgets must be positive integers for a qualified flavor")
        mpq = flavor.get("mpq_set") or {}
        required = mpq.get("required")
        if not isinstance(required, list) or not required:
            _err(errors, f"{where}.mpq_set.required must list the client's base MPQ set")
        wine = flavor.get("wine") or {}
        for key in ("prefix_segment",):
            value = wine.get(key)
            if not isinstance(value, str) or not value:
                _err(errors, f"{where}.wine.{key} must be set for a qualified flavor")
            elif not SLUG_RE.match(value):
                # The segment becomes a
                # directory name — Win32 strips trailing dots/spaces, so
                # 'wow8606.' or 'wow8606 ' collapses onto another flavor's
                # prefix while this gate's lower() key calls them distinct.
                # The slug rule refuses every spelling that can normalize.
                _err(
                    errors,
                    f"{where}.wine.{key} must be a lowercase slug "
                    f"(lowercase letters, digits, dashes; no dots, spaces, "
                    f"or uppercase — Win32 directory normalization would "
                    f"collapse spellings onto another flavor's prefix), "
                    f"got {value!r}",
                )
        if flavor.get("supported_client_builds") == [] and not flavor.get("supported_client_builds_note"):
            _err(
                errors,
                f"{where}: empty supported_client_builds needs a note explaining the qualification state",
            )
        # The registration-completeness rule
        # applies to QUALIFIED flavors too — the list is the build registry
        # of record, so a doctored qualified flavor naming a FOREIGN
        # registered build (another lane's) must refuse exactly as the
        # unqualified branch does; [] stays legal with its note (the
        # qualified flag, not this list, gates import).
        if flavor.get("qualified") is True:
            scb = flavor.get("supported_client_builds")
            if isinstance(scb, list) and scb and build_is_int and scb != [build]:
                _err(
                    errors,
                    f"{where}: qualified supported_client_builds must be exactly "
                    f"[{build}] (the registered client's own build) or [] with a "
                    f"note, got {scb!r} — a foreign or padded build list on a "
                    "qualified flavor is a doctored registry",
                )
    else:
        # S5.1 identity registration: an unqualified flavor may
        # record the census-measured genuine-client identity, but only as a
        # COMPLETE registration — every identity datum present and mutually
        # consistent. The pre-S5.1 rule was an absolute null ban; the
        # measurements exist now, so the half-truth this rule still refuses is
        # the PARTIAL registration (hashes without the build list, a build
        # list without hashes, or a build list naming anything but the
        # flavor's own build). Import keeps rejecting on qualified=false;
        # this data records identity, never permission.
        client_binary = flavor.get("client_binary")
        md5 = client_binary.get("known_exe_md5") if isinstance(client_binary, dict) else None
        size = client_binary.get("known_exe_size_bytes") if isinstance(client_binary, dict) else None
        wine_block = flavor.get("wine")
        sha256 = wine_block.get("known_exe_sha256") if isinstance(wine_block, dict) else None
        builds = flavor.get("supported_client_builds")
        required = (flavor.get("mpq_set") or {}).get("required")
        identity_facts = (
            md5 is not None,
            size is not None,
            sha256 is not None,
            bool(builds),
            bool(required),
        )
        if any(identity_facts):
            if not all(identity_facts):
                _err(
                    errors,
                    f"{where}: partial S5.1 identity registration — client_binary md5/size, "
                    "wine.known_exe_sha256, supported_client_builds, and mpq_set.required must "
                    "be registered together (a half-registered identity would make the gate lie)",
                )
            elif build_is_int and builds != [build]:
                _err(
                    errors,
                    f"{where}: unqualified supported_client_builds must be exactly [{build}] "
                    f"(the registered client's own build), got {builds!r}",
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
    entries = [source for source in sources if isinstance(source, dict)]
    # The flavor->sources binding resolves by ID —
    # a second entry sharing cmangos-core-tbc's id but carrying the vanilla
    # upstream_url + commit would win the URL-first match and the commit
    # comparison would PASS on the doctored pin (the coordinated two-file
    # lie this gate exists to refuse). Duplicate ids are refused at load
    # time, before any resolution can bind a lie.
    duplicates = common.duplicate_source_ids(entries)
    if duplicates:
        return None, (
            f"sources manifest carries DUPLICATE id(s) {duplicates} — the "
            "flavor pin binding resolves by id, so a second entry under an "
            "existing id lets a doctored pin win the URL-first match and "
            "pass the commit comparison; source ids must be unique")
    return entries, None


def _resolve_source(group: dict, fallback_ids: list[str], sources: list[dict]) -> dict | None:
    """Map a flavor commit group to its sources.json entry, RESTRICTED to the
    flavor's expected source ids (round-2 R2-1b: the old URL-first form
    matched ANY source's upstream_url, so a doctored manifest pointing tbc's
    server_core at the vanilla source's URL + vanilla's commit bound to the
    VANILLA entry and passed the gate — a provenance lie reported OK).

    Resolution: the group's upstream URL may only select among the flavor's
    OWN expected entries (fork URLs that match nothing fall through), then
    the fallback ids in order (per-flavor entry before the legacy vanilla
    id). The legacy tail (the last id — the pre-per-flavor vanilla entry)
    is a candidate ONLY while the flavor's own entry does not exist in
    sources.json: once cmangos-core-tbc exists, a tbc pin may never bind to
    the vanilla entry, so a doctored manifest pointing a foreign group at
    the vanilla URL + vanilla commit fails the commit comparison instead of
    binding to the lie."""
    expected = [s for s in sources if s.get("id") in fallback_ids]
    if len(fallback_ids) > 1:
        own_ids = set(fallback_ids[:-1])  # everything but the legacy tail
        own_entries = [s for s in expected if s.get("id") in own_ids]
        if own_entries:
            expected = own_entries
        elif len(expected) == 1 and expected[0].get("id") == fallback_ids[-1]:
            # the flavor's OWN entries are ABSENT from
            # sources.json and only the legacy tail remains — binding the
            # group to the vanilla entry is exactly the coordinated
            # two-file lie (delete cmangos-core-tbc, point the group at
            # the vanilla URL/commit). Refuse: the per-flavor source
            # entry is REQUIRED once the flavor is registered.
            return None
    upstream = group.get("upstream")
    if isinstance(upstream, str) and upstream:
        for source in expected:
            if source.get("upstream_url") == upstream:
                return source
    for fallback_id in fallback_ids:
        for source in expected:
            if source.get("id") == fallback_id:
                return source
    return None


def _check_source_pins(
    flavor: dict, index: int, sources: list[dict], errors: list[str]
) -> None:
    """Bind every flavor's commit pins to sources.json: the flavor pin and the
    source pin must be the very same commit. Applies to any group that carries
    a commit — since correction C4 the tbc/wotlk bots pins exist while those
    flavors stay unqualified, and a pinned-but-unbound pin is exactly the
    provenance lie D16 guards against."""
    where = f"flavors[{index}]"
    fid = flavor.get("id")
    fallback_table = FALLBACK_SOURCE_IDS.get(fid if isinstance(fid, str) else "", {})
    if not fallback_table:
        # an unknown flavor id used to skip the whole binding
        # silently — a doctored flavor with 40-'f' commits bound to nothing
        # and passed. Unknown ids fail loud: every committed flavor must
        # bind its pins.
        _err(
            errors,
            f"{where}: flavor id {fid!r} has no sources.json binding table "
            f"(known flavors: {sorted(FALLBACK_SOURCE_IDS)}) — a flavor "
            "outside the binding table carries pins bound to nothing",
        )
        return
    for group, fallback_ids in fallback_table.items():
        group_data = flavor.get(group)
        if not isinstance(group_data, dict):
            continue  # structural error already reported
        commit = group_data.get("commit")
        if not isinstance(commit, str) or not commit:
            continue  # pin-shape error already reported (or status: unresolved)
        source = _resolve_source(group_data, fallback_ids, sources)
        if source is None:
            _err(
                errors,
                f"{where}.{group}: no sources.json entry pins upstream "
                f"{group_data.get('upstream')!r} (fallback ids {fallback_ids!r} "
                "not found either) — add the source entry before building this lane",
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
    # the measured exe identities are flavor-unique too — a
    # flavor registering another flavor's exe hash/size would qualify a
    # lane whose published telltale is byte-identical to a DIFFERENT lane's
    # genuine client.
    exe_md5_owners: dict[str, str] = {}
    exe_sha256_owners: dict[str, str] = {}
    exe_size_owners: dict[int, str] = {}
    # W7-R2-2: the base MPQ set is as identity-bearing as the exe hash —
    # two flavors sharing one required list means one of them was doctored.
    mpq_required_owners: dict[tuple, str] = {}
    # register EVERY flavor's build before any per-flavor
    # check runs — the foreign-build rejection must see all
    # registered builds regardless of manifest ordering.
    registered_builds: dict[int, str] = {}
    for flavor in flavors:
        if not isinstance(flavor, dict):
            continue
        build = flavor.get("build")
        fid = flavor.get("id")
        if isinstance(build, int) and not isinstance(build, bool) and isinstance(fid, str):
            registered_builds[build] = fid

    for index, flavor in enumerate(flavors):
        if not isinstance(flavor, dict):
            _err(errors, f"flavors[{index}] must be an object")
            continue
        _check_flavor(flavor, index, errors, registered_builds)
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

        # exe-identity uniqueness (mirrors build_owners).
        client_binary = flavor.get("client_binary") if isinstance(flavor, dict) else None
        wine = flavor.get("wine") if isinstance(flavor, dict) else None
        if isinstance(client_binary, dict):
            md5 = client_binary.get("known_exe_md5")
            if isinstance(md5, str):
                if md5 in exe_md5_owners:
                    _err(
                        errors,
                        f"duplicate exe identity across flavors: "
                        f"client_binary.known_exe_md5 {md5!r} is pinned by both "
                        f"{exe_md5_owners[md5]!r} and {owner!r} (two flavors must "
                        "never share a measured exe identity)",
                    )
                else:
                    exe_md5_owners[md5] = owner
            size = client_binary.get("known_exe_size_bytes")
            if isinstance(size, int) and not isinstance(size, bool):
                if size in exe_size_owners:
                    _err(
                        errors,
                        f"duplicate exe identity across flavors: "
                        f"client_binary.known_exe_size_bytes {size} is pinned by "
                        f"both {exe_size_owners[size]!r} and {owner!r}",
                    )
                else:
                    exe_size_owners[size] = owner
        if isinstance(wine, dict):
            sha = wine.get("known_exe_sha256")
            if isinstance(sha, str):
                if sha in exe_sha256_owners:
                    _err(
                        errors,
                        f"duplicate exe identity across flavors: "
                        f"wine.known_exe_sha256 {sha!r} is pinned by both "
                        f"{exe_sha256_owners[sha]!r} and {owner!r} (two flavors "
                        "must never share a measured exe identity)",
                    )
                else:
                    exe_sha256_owners[sha] = owner
        # W7-R2-2: the required MPQ set is client identity the same way the
        # exe hash is — a doctored manifest that swaps one flavor's list for
        # another's (or pastes a copy) must refuse, not qualify a lane whose
        # import gate would then wave through another flavor's client tree.
        mpq_required = (flavor.get("mpq_set") or {}).get("required")
        if isinstance(mpq_required, list) and mpq_required:
            mpq_key = tuple(sorted(str(name).strip().lower() for name in mpq_required))
            if mpq_key in mpq_required_owners:
                _err(
                    errors,
                    f"duplicate required MPQ set across flavors: "
                    f"mpq_set.required ({len(mpq_required)} entries) is pinned by "
                    f"both {mpq_required_owners[mpq_key]!r} and {owner!r} — two "
                    "flavors must never share a base MPQ identity (a swap or "
                    "copy-paste of another flavor's list is a doctored manifest)",
                )
            else:
                mpq_required_owners[mpq_key] = owner

    # The owner maps above fire only when
    # a key is SEEN TWICE — multi-flavor SHARING patterns that dodge the
    # insertion order (A copies B while B copies C; a copy between flavors
    # the map sorted past) slip through. The pairwise pass compares every
    # identity field across every flavor pair, so ANY sharing refuses
    # regardless of registration order. (A two-party MUTUAL EXCHANGE of
    # distinct values is equality-invisible by definition — each value
    # still appears exactly once — and is owned by the manifest<->registry
    # parity pins, which anchor every field to its client id.)
    identity_vectors: list[tuple[str, dict[str, object]]] = []
    for flavor in flavors:
        if not isinstance(flavor, dict):
            continue
        fid = flavor.get("id") if isinstance(flavor.get("id"), str) else f"<index-{flavors.index(flavor)}>"
        wine = flavor.get("wine") if isinstance(flavor.get("wine"), dict) else {}
        binary = flavor.get("client_binary") if isinstance(flavor.get("client_binary"), dict) else {}
        mpq = flavor.get("mpq_set") if isinstance(flavor.get("mpq_set"), dict) else {}
        mpq_required = mpq.get("required")
        identity_vectors.append((fid, {
            "build": flavor.get("build"),
            "version_resource": flavor.get("version_resource"),
            "prefix_segment": (
                str(wine.get("prefix_segment")).strip().lower()
                if isinstance(wine.get("prefix_segment"), str) else None
            ),
            "databases": tuple(
                (key, (flavor.get("databases") or {}).get(key))
                for key in DATABASE_KEYS
            ) if isinstance(flavor.get("databases"), dict) else None,
            "mpq_required": tuple(sorted(
                str(name).strip().lower() for name in mpq_required
            )) if isinstance(mpq_required, list) and mpq_required else None,
            "exe_sha256": wine.get("known_exe_sha256")
            if isinstance(wine.get("known_exe_sha256"), str) else None,
            "exe_md5": binary.get("known_exe_md5")
            if isinstance(binary.get("known_exe_md5"), str) else None,
            "exe_size": binary.get("known_exe_size_bytes")
            if isinstance(binary.get("known_exe_size_bytes"), int)
            and not isinstance(binary.get("known_exe_size_bytes"), bool) else None,
        }))
    field_labels = {
        "build": "build",
        "version_resource": "version_resource",
        "prefix_segment": "wine.prefix_segment",
        "databases": "databases",
        "mpq_required": "mpq_set.required",
        "exe_sha256": "wine.known_exe_sha256",
        "exe_md5": "client_binary.known_exe_md5",
        "exe_size": "client_binary.known_exe_size_bytes",
    }
    for i in range(len(identity_vectors)):
        for j in range(i + 1, len(identity_vectors)):
            fid_a, vec_a = identity_vectors[i]
            fid_b, vec_b = identity_vectors[j]
            for field, label in field_labels.items():
                va, vb = vec_a.get(field), vec_b.get(field)
                if va is None or vb is None or va != vb:
                    continue
                _err(
                    errors,
                    f"duplicate {label} across flavors: the identity is shared "
                    f"by both {fid_a!r} and {fid_b!r} (an exchange, rotation, "
                    "or copy between flavors is a doctored manifest — two "
                    "flavors must never share an identity field)",
                )

    if qualified_count == 0:
        _err(errors, "at least one flavor must be qualified (the app must ship a lane)")

    # Correction: the auth-challenge opcode is one shared constant — if the
    # manifest records it at all, it must be exactly 0x1EC, never per-flavor.
    auth = manifest.get("auth")
    if auth is not None:
        if not isinstance(auth, dict):
            _err(errors, "auth must be an object when present")
        elif auth.get("smsg_auth_challenge_opcode") != SHARED_AUTH_CHALLENGE_OPCODE:
            _err(
                errors,
                f"auth.smsg_auth_challenge_opcode must be {SHARED_AUTH_CHALLENGE_OPCODE!r} "
                "(correction C3: one shared constant in all three branches — the "
                "per-flavor frame descriptors live in each flavor's auth_frame)",
            )

    # Single-lane contract: the runtime qualifies exactly one flavor;
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

    # Provenance binding: every flavor's commit pins must be the very commits
    # recorded in schemas/sources.json; widened by correction —
    # the tbc/wotlk bots pins are pinned while their flavors stay unqualified).
    # A drifted pin means the manifest claims a source the repo does not pin.
    if any(
        isinstance(flavor, dict) and flavor.get("qualified") is True
        for flavor in flavors
    ) or any(
        isinstance(flavor, dict)
        and any(
            isinstance((flavor.get(group) or {}).get("commit"), str)
            for group in ("server_core", "db_content", "bots")
        )
        for flavor in flavors
    ):
        if sources_path is None:
            sources_path = manifest_path.parent / "sources.json"
        sources, sources_error = _load_sources(sources_path)
        if sources_error is not None:
            _err(errors, sources_error)
        else:
            for index, flavor in enumerate(flavors):
                if not isinstance(flavor, dict):
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
