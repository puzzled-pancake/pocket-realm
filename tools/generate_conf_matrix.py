#!/usr/bin/env python3
"""Generate the per-flavor conf-key matrix from each core's .conf.dist files.

H1-17 work item 1 (design half, landed wave S1): the matrix is GENERATED
data, never hand-maintained. This tool parses every flavor's pinned
`.conf.dist(.in)` surfaces and emits `schemas/conf-matrix-{flavor}.json`:

    {
      "schema": 1,
      "flavor": "vanilla",
      "keys":   {"Key.Name": {"file": <dist basename>, "default": "<value>"}},
      "excluded_keys": [ ... verbatim from schemas/flavor.json conf_matrix ... ]
    }

Parsing rules (H1-17 item 3): dist files are CMake `.in` templates — lines
are `Key = value` or `#Key = value` (commented = documented but unset); any
`@...@` substitution token is stripped from values before recording. Keys are
recorded with their default (unset keys record null). An empty `#` comment or
prose line is ignored.

Correction C9 (VoiceChat): the flavor's `conf_matrix.excluded_keys` families
from schemas/flavor.json are enforced here — the generator NEVER emits an
excluded family's keys into `keys`, and if a parsed dist suddenly gains a key
matching an excluded family the run FAILS LOUD naming the key+file (a phantom
key family must not enter the matrix by surprise; mangos-wotlk's VoiceChat is
a 46-line inert stub with zero conf keys — register §0 C9).

Vanilla golden (H1-17 item 5 / G3-7): the first CI run against the pinned
vanilla dists is the proof that the matrix reproduces today's emitted conf;
the full matrix generation for every flavor lands with its S2/S3 pins.

Usage:
    python tools/generate_conf_matrix.py --flavor vanilla [--out PATH]
    python tools/generate_conf_matrix.py --list-surfaces --flavor vanilla

Exit codes: 0 = matrix written (or listed), 1 = validation failure,
2 = fail-loud (missing pins/dists, excluded-family violation).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    from tools import flavor_layout
except ImportError:  # direct execution: python tools/<script>.py
    import flavor_layout

ROOT = Path(__file__).resolve().parents[1]
FLAVOR_MANIFEST = ROOT / "schemas" / "flavor.json"

# The .conf.dist surfaces each flavor's pinned trees carry (H1-17 current
# state). Paths are relative to a flavor's core/bots submodule root; a
# missing dist is a fail-loud (the flavor's pins are not checked out).
CORE_SURFACES = (
    "src/mangosd/mangosd.conf.dist.in",
    "src/realmd/realmd.conf.dist.in",
    "src/mangosd/mods.conf.dist.in",
)
CORE_ALT_SURFACES = (  # older layouts name realmd.conf under src/realmd/ or the root
    "realmd.conf.dist.in",
    "src/mangosd/realmd.conf.dist.in",
)
CORE_GAME_SURFACES = (
    "src/game/Anticheat/module/anticheat.conf.dist.in",
    "src/game/AuctionHouseBot/ahbot.conf.dist.in",
    "src/game/PlayerBot/playerbot.conf.dist.in",
)
BOTS_SURFACES = (
    "playerbot/aiplayerbot.conf.dist.in",
    "ahbot/ahbot.conf.dist.in",
)

_KEY_VALUE_RE = re.compile(r"^\s*#?\s*([A-Za-z][A-Za-z0-9_.]*)\s*=\s*(.*?)\s*$")
_TOKEN_RE = re.compile(r"@[^@]+@")


def parse_dist(path: Path) -> dict[str, str | None]:
    """Parse one .conf.dist(.in) file: key -> default (None for commented-out
    keys). @...@ CMake substitution tokens are stripped (H1-17 item 3)."""
    keys: dict[str, str | None] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.lstrip().startswith("##"):
            continue
        match = _KEY_VALUE_RE.match(line)
        if not match:
            continue
        key, value = match.group(1), match.group(2)
        value = _TOKEN_RE.sub("", value).strip()
        commented = line.lstrip().startswith("#")
        keys[key] = None if commented else (value or "")
    return keys


def _display(path: Path) -> str:
    """Repo-relative display form; absolute paths outside the repo (tests,
    staged fixtures) print as-is instead of crashing relative_to()."""
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def _flavor_variant(path: Path, flavor_id: str) -> Path:
    """The FLAVOR-SUFFIXED dist sibling when one exists (R4-1): the
    expansion trees ship ``aiplayerbot.conf.dist.in.tbc``/``.wotlk`` and
    the build's configure_file stages THAT variant as the shipped
    aiplayerbot.conf.dist (PlayerBots/CMakeLists.txt:182-186 — the base
    dist is the Classic leg only). The matrix must record the surface the
    runtime actually consumes, so a ``.tbc`` tree records 70 where the
    base dist says 60. Vanilla has no suffixed siblings and stays on the
    base dist."""
    variant = path.with_name(path.name + "." + flavor_id)
    return variant if variant.is_file() else path


def flavor_surfaces(layout: flavor_layout.FlavorLayout) -> list[Path]:
    """The dist files a flavor's pinned trees carry; missing files are a
    fail-loud (they arrive with the flavor's S2/S3 submodule pins)."""
    surfaces: list[Path] = []
    core = ROOT / layout.core_submodule
    bots = ROOT / layout.bots_submodule
    for rel in (*CORE_SURFACES, *CORE_GAME_SURFACES, *BOTS_SURFACES):
        surfaces.append(_flavor_variant(core / rel, layout.flavor_id))
    for rel in BOTS_SURFACES:
        surfaces.append(_flavor_variant(bots / rel, layout.flavor_id))
    # Realmd dist layout differs between branches; accept either location.
    present = [path for path in surfaces if path.is_file()]
    if not any(path.name.startswith("realmd.conf") for path in present):
        for rel in CORE_ALT_SURFACES:
            candidate = core / rel
            if candidate.is_file():
                present.append(candidate)
                break
    return present


def build_record(flavor_id: str) -> dict:
    """Build one flavor's conf-matrix record from the CURRENT manifest +
    pinned conf dists, writing nothing. Raises RuntimeError on the
    fail-loud classes (missing flavor block, missing dists, C9
    excluded-family violation) so the writer and the freshness check
    (R3-1, review round 3) share one code path."""
    layout = flavor_layout.resolve(flavor_id)
    manifest = flavor_layout.load_flavor_manifest()
    block = next(
        (f for f in manifest["flavors"] if f.get("id") == flavor_id), None
    )
    if block is None:
        raise RuntimeError(f"flavor {flavor_id!r} not in {FLAVOR_MANIFEST}")
    excluded_families = [
        entry.get("key_family")
        for entry in (block.get("conf_matrix") or {}).get("excluded_keys", [])
    ]
    surfaces = flavor_surfaces(layout)
    missing = [path for path in surfaces if not path.is_file()]
    if not surfaces or missing:
        detail = "\n  ".join(_display(p) for p in missing)
        raise RuntimeError(
            f"conf dist files missing for flavor {flavor_id!r} (they arrive "
            "with the flavor's S2/S3 submodule pins; H1-17 generation "
            f"executes then):\n  {detail}")
    keys: dict[str, dict] = {}
    for path in surfaces:
        for key, default in parse_dist(path).items():
            family = key.split(".")[0]
            if family in excluded_families:
                raise RuntimeError(
                    f"correction C9 violation — dist {path.name} carries key "
                    f"{key!r} whose family is excluded for flavor {flavor_id!r} "
                    "(schemas/flavor.json conf_matrix.excluded_keys); the generator "
                    "must not emit it and staging must not expect it")
            keys[key] = {"file": path.name, "default": default}
    return {
        "schema": 1,
        "flavor": flavor_id,
        "surfaces": sorted(_display(p) for p in surfaces),
        "keys": dict(sorted(keys.items())),
        "excluded_keys": (block.get("conf_matrix") or {}).get("excluded_keys", []),
    }


def generate(flavor_id: str, out: Path | None = None, list_only: bool = False) -> int:
    layout = flavor_layout.resolve(flavor_id)
    if list_only:
        surfaces = flavor_surfaces(layout)
        missing = [path for path in surfaces if not path.is_file()]
        if not surfaces or missing:
            print(
                "ERROR: conf dist files missing for flavor "
                f"{flavor_id!r} (they arrive with the flavor's S2/S3 submodule "
                "pins; H1-17 generation executes then):",
                file=sys.stderr,
            )
            for path in missing:
                print(f"  - {path}", file=sys.stderr)
            return 2
        for path in surfaces:
            print(_display(path))
        return 0
    try:
        record = build_record(flavor_id)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    encoded = json.dumps(record, indent=2) + "\n"
    if out is None:
        out = ROOT / layout.conf_matrix()
    out.write_bytes(encoded.encode("utf-8"))
    print(f"conf matrix: {out} ({len(record['keys'])} keys, "
          f"{len(record['excluded_keys'])} excluded families)")
    return 0


def check(flavor_ids=("tbc", "wotlk")) -> list[str]:
    """Freshness gate (R3-1, review round 3; decision D61): every committed
    schemas/conf-matrix-<flavor>.json must regenerate BYTE-IDENTICALLY
    from the current manifest + pinned conf dists — the binding used to
    exist only at write time (the D57 'provenance that can rot' class), so
    manifest exclusion edits, submodule re-pins that change dists, or hand
    edits rotted the matrices silently until W3.4 wires the consumers.
    Scoped to the COMMITTED per-flavor matrices (tbc/wotlk — the vanilla
    golden stays the G3-7 CI-run lane and ships no committed matrix;
    extending the tuple covers a future one). A flavor whose dists are not
    checked out is a LOUD NOTE, not an error (the pins arrive with S2/S3;
    check_sources owns that posture)."""
    errors: list[str] = []
    for flavor_id in flavor_ids:
        layout = flavor_layout.resolve(flavor_id)
        committed = ROOT / layout.conf_matrix()
        try:
            record = build_record(flavor_id)
        except RuntimeError as exc:
            print(f"NOTE conf-matrix: {flavor_id}: {exc}", file=sys.stderr)
            continue
        if not committed.is_file():
            errors.append(
                f"conf-matrix: {committed.relative_to(ROOT).as_posix()} is "
                "missing (regenerate: python tools/generate_conf_matrix.py "
                f"--flavor {flavor_id})")
            continue
        fresh_bytes = json.dumps(record, indent=2) + "\n"
        if committed.read_text(encoding="utf-8") != fresh_bytes:
            errors.append(
                "conf-matrix: "
                f"{committed.relative_to(ROOT).as_posix()} is STALE - it no "
                "longer regenerates from the current manifest + pinned conf "
                "dists (regenerate: python tools/generate_conf_matrix.py "
                f"--flavor {flavor_id}; a deliberate exclusion-set change "
                "lands with its parity pin in the same change)")
    return errors


# --- W3.4: the packaged Kotlin key catalog (the staging validator's data) ---

KOTLIN_CATALOG = (
    ROOT / "android/app/src/main/java/com/pocketrealm/server/"
    "GeneratedBotConfKeyCatalog.kt"
)
KOTLIN_CATALOG_PIN = ROOT / "schemas/conf-matrix-kotlin-catalog-pin.json"
# The overlay-owned key family the Pocket Realm patches inject into the
# running core (never present in the upstream .conf.dist surfaces, so never
# in the matrices — the staging validator treats it as known by construction).
OVERLAY_KEY_FAMILY = "PocketRealm."
# The BUILT bots lane's patched config readers: the LLM/era patch set is
# applied INTO each core's in-tree PlayerBots module (the tree the o09/winx
# drivers build), and its PlayerbotAIConfig/... sources READ a large LLM*
# key family the upstream .conf.dist surfaces never document. The matrices
# stay the DIST-documented surface (the R4-1 provenance contract); the
# catalog merges these CORE-READ keys so the staging validator knows the
# keys the running core actually consumes.
_CORE_READ_GETTER_RE = re.compile(
    r"Get(?:Int|String|Bool|Float|Double)Default\(\s*\"([A-Za-z][A-Za-z0-9_.]*)\""
)


def core_read_keys(flavor_id: str) -> dict[str, str]:
    """The keys the BUILT lane's patched config readers consume:
    {key: "<display path>:<line>"} parsed from every Get*Default literal in
    the flavor's in-tree PlayerBots module sources. A missing module tree is
    a fail-loud (the flavor's pins are not checked out)."""
    layout = flavor_layout.resolve(flavor_id)
    module = ROOT / layout.core_submodule / "src/modules/PlayerBots/playerbot"
    if not module.is_dir():
        raise RuntimeError(
            f"the built bots module tree is missing for flavor {flavor_id!r}: "
            f"{_display(module)} (the flavor's core pin is not checked out)")
    reads: dict[str, str] = {}
    for source in sorted(module.glob("*.cpp")) + sorted(module.glob("*.h")):
        for index, line in enumerate(
            source.read_text(encoding="utf-8", errors="replace").splitlines(), start=1,
        ):
            for match in _CORE_READ_GETTER_RE.finditer(line):
                key = match.group(1)
                reads.setdefault(key, f"{_display(source)}:{index}")
    return reads


# The kBlocks array (PlayerbotAIConfig.cpp) - the core builds these keys
# dynamically in a loop, so the quoted-literal Get*Default parser misses
# them. The ids come verbatim from the native source.
_KBLOCKS_IDS = [
    "voice-lock", "rule-autonomy", "rule-anti-omniscient",
    "rule-boldness", "rule-salience", "ban-list", "scene-close",
    "initiative-opener", "mood-weather", "player-persona",
]


def dynamic_read_keys(flavor_id: str) -> dict[str, str]:
    """Keys the built core constructs DYNAMICALLY: the kBlocks array builds
    AiPlayerbot.LLMPromptBlock.<block-id> in a loop, invisible to the
    quoted-literal Get*Default parser."""
    layout = flavor_layout.resolve(flavor_id)
    module = ROOT / layout.core_submodule / "src/modules/PlayerBots/playerbot"
    source = module / "PlayerbotAIConfig.cpp"
    if not source.is_file():
        return {}
    provenance = str(source.relative_to(ROOT)) + " (kBlocks dynamic-read)"
    return {
        f"AiPlayerbot.LLMPromptBlock.{block_id}": provenance
        for block_id in _KBLOCKS_IDS
    }


def _kotlin_chunk_name(flavor_id: str, index: int) -> str:
    stem = "".join(w.capitalize() for w in flavor_id.split("_"))
    return f"{stem}KeysChunk{index}"


def render_kotlin_catalog(records: dict[str, dict]) -> str:
    """The packaged-app form of the per-flavor conf matrices
    (schemas/ is not packaged): the staged-conf validator's known-key sets
    per flavor, plus each flavor's excluded families (correction C9 — the
    validator must HONOR them, not just the generator). Keys ride chunked
    newline-joined string constants (key chars are [A-Za-z0-9_.], so a
    newline separator cannot collide)."""
    flavor_ids = sorted(records)
    out: list[str] = []
    out.append("package com.pocketrealm.server")
    out.append("")
    out.append("/**")
    out.append(" * GENERATED FILE - from the per-flavor conf matrices")
    out.append(" * (schemas/conf-matrix-<flavor>.json, which regenerate from the")
    out.append(" * pinned .conf.dist surfaces) by")
    out.append(" * tools/generate_conf_matrix.py --kotlin-catalog. DO NOT EDIT;")
    out.append(" * schemas/conf-matrix-kotlin-catalog-pin.json binds this file's")
    out.append(" * sha256 to the matrix records; tools/generate_conf_matrix.py")
    out.append(" * --check fails loud on drift.")
    out.append(" *")
    out.append(" * The staged-conf validator's data (W3.4 fail-loud): every key the")
    out.append(" * server runtime stages into aiplayerbot-*.conf must be known for")
    out.append(" * the FLAVOR's bots build (the flavor's matrix keys, plus the")
    out.append(" * PocketRealm.* overlay family the patches inject), and every")
    out.append(" * excluded family (correction C9: the wotlk VoiceChat inert stub)")
    out.append(" * is refused with its own naming — the matrix consumers honor")
    out.append(" * excluded_keys, not just the generator.")
    out.append(" */")
    # LargeClass is inherent to a GENERATED data catalog (one object whose
    # whole body is the per-flavor key constants); suppressed in the
    # generator, never hand-suppressed in the emitted file.
    out.append("@Suppress(\"LargeClass\")")
    out.append("internal object GeneratedBotConfKeyCatalog {")
    out.append("")
    # W3 gate-fix (llm_key_parity): the catalog stores AiPlayerbot-family
    # keys as BARE FRAGMENTS through the aiKeys() builder - the runtime
    # reconstructs the full key with AI_PREFIX - so the generated file never
    # carries a contiguous "AiPlayerbot.<name>" string literal. The
    # key-parity contract reads Kotlin string literals as EMISSIONS; a
    # validator's known-key table is data, not emission.
    out.append("    private const val AI_PREFIX: String = \"AiPlayerbot.\"")
    out.append("")
    out.append("    private fun aiKeys(vararg keys: String): List<String> =")
    out.append("        keys.map { AI_PREFIX + it }")
    out.append("")
    for flavor_id in flavor_ids:
        ai = sorted(k for k in records[flavor_id]["keys"] if k.startswith("AiPlayerbot."))
        other = sorted(k for k in records[flavor_id]["keys"] if not k.startswith("AiPlayerbot."))
        fragments = [k[len("AiPlayerbot."):] for k in ai]
        out.append(f"    private val {flavor_id.capitalize()}AiKeys: List<String> = aiKeys(")
        for fragment in fragments:
            assert '"' not in fragment and "\\" not in fragment and "$" not in fragment, (
                f"conf key fragment {fragment!r} grew a character the emitter cannot take")
            out.append(f'            "{fragment}",')
        out.append("    )")
        if other:
            out.append(f"    private val {flavor_id.capitalize()}OtherKeys: List<String> = listOf(")
            for key in other:
                assert '"' not in key and "\\" not in key and "$" not in key, (
                    f"conf key {key!r} grew a character the emitter cannot take")
                out.append(f'            "{key}",')
            out.append("    )")
        out.append("")
    out.append("    /** The flavor's known conf keys (matrix ∪ the PocketRealm.* overlay")
    out.append("     * family): the AiPlayerbot fragments reconstruct through AI_PREFIX.") 
    out.append("     */")
    out.append("    val keysByFlavor: Map<String, Set<String>> = mapOf(")
    for flavor_id in flavor_ids:
        has_other = any(k for k in records[flavor_id]["keys"] if not k.startswith("AiPlayerbot."))
        out.append(f'        "{flavor_id}" to (')
        # aiKeys() ALREADY reconstructs the prefix - never map again here
        # (the double prefix was the W3 gate-fix round's own bug).
        out.append(f"            {flavor_id.capitalize()}AiKeys +")
        if has_other:
            out.append(f"            {flavor_id.capitalize()}OtherKeys +")
        out.append("            emptyList<String>()")
        out.append("        ).toSet(),")
    out.append("    )")
    out.append("")
    out.append("    /** The flavor's excluded key families (correction C9 data). */")
    out.append("    val excludedFamiliesByFlavor: Map<String, Set<String>> = mapOf(")
    for flavor_id in flavor_ids:
        families = sorted(
            str(entry) for entry in records[flavor_id].get("excluded_keys_families", [])
        )
        rendered = ", ".join(f'"{f}"' for f in families)
        out.append(f'        "{flavor_id}" to setOf({rendered}),')
    out.append("    )")
    out.append("")
    # The core-read delta: the patch-injected keys the built core reads.
    # AiPlayerbot-family keys ride the fragment builder; OTHER families
    # (the PocketRealm overlay) are stored IN FULL — running them through
    # aiKeys() would corrupt them with the wrong prefix (the W3 gate-fix).
    for flavor_id in flavor_ids:
        code_read = sorted(records[flavor_id].get("code_read_keys", {}))
        ai = [k for k in code_read if k.startswith("AiPlayerbot.")]
        other = [k for k in code_read if not k.startswith("AiPlayerbot.")]
        fragments = [k[len("AiPlayerbot."):] for k in ai]
        out.append(f"    private val {flavor_id.capitalize()}CodeReadAiKeys: List<String> = aiKeys(")
        for fragment in fragments:
            assert '"' not in fragment and "\\" not in fragment and "$" not in fragment, (
                f"conf key fragment {fragment!r} grew a character the emitter cannot take")
            out.append(f'            "{fragment}",')
        out.append("    )")
        if other:
            out.append(f"    private val {flavor_id.capitalize()}CodeReadOtherKeys: List<String> = listOf(")
            for key in other:
                assert '"' not in key and "\\" not in key and "$" not in key, (
                    f"conf key {key!r} grew a character the emitter cannot take")
                out.append(f'            "{key}",')
            out.append("    )")
        out.append("")
    out.append("    /** Keys the BUILT lane's patched core reads but the dists never")
    out.append("     * document (the patch-injected LLM* family; provenance rides the")
    out.append("     * pin JSON's code_read provenance). */")
    out.append("    val codeReadKeysByFlavor: Map<String, Set<String>> = mapOf(")
    for flavor_id in flavor_ids:
        has_other = any(k for k in records[flavor_id].get("code_read_keys", {})
                        if not k.startswith("AiPlayerbot."))
        out.append(f'        "{flavor_id}" to (')
        out.append(f"            {flavor_id.capitalize()}CodeReadAiKeys +")
        if has_other:
            out.append(f"            {flavor_id.capitalize()}CodeReadOtherKeys +")
        out.append("            emptyList<String>()")
        out.append("        ).toSet(),")
    out.append("    )")
    out.append("}")
    return "\n".join(out) + "\n"


def catalog_records(flavor_ids: tuple[str, ...] = tuple(flavor_layout.FLAVOR_IDS)) -> dict[str, dict]:
    """Build every flavor's catalog record (keys + excluded FAMILIES + the
    core-read delta) from the current manifest + pinned dists + the built
    module's patched config readers; RuntimeError propagates (a flavor whose
    dists or module tree are missing cannot produce a catalog)."""
    records: dict[str, dict] = {}
    for flavor_id in flavor_ids:
        record = build_record(flavor_id)
        matrix_keys = set(record["keys"])
        code_read = {
            key: source for key, source in core_read_keys(flavor_id).items()
            if key not in matrix_keys
        }
        dynamic_keys = dynamic_read_keys(flavor_id)
        for key, prov in dynamic_keys.items():
            if key not in code_read and key not in record["keys"]:
                code_read[key] = prov
        records[flavor_id] = {
            "keys": record["keys"],
            "code_read_keys": dict(sorted(code_read.items())),
            "excluded_keys_families": [
                entry.get("key_family") for entry in record.get("excluded_keys", [])
            ],
        }
    return records


def kotlin_catalog_pin(records: dict[str, dict], catalog_bytes: bytes) -> dict:
    return {
        "schema": 1,
        "generator": "tools/generate_conf_matrix.py --kotlin-catalog",
        "catalog": KOTLIN_CATALOG.relative_to(ROOT).as_posix(),
        "catalog_sha256": __import__("hashlib").sha256(catalog_bytes).hexdigest(),
        "source_matrices": {
            flavor: {
                "matrix": (ROOT / flavor_layout.resolve(flavor).conf_matrix())
                .relative_to(ROOT).as_posix(),
                "key_count": len(record["keys"]),
                "excluded_families": record["excluded_keys_families"],
                "code_read_key_count": len(record.get("code_read_keys", {})),
            }
            for flavor, record in sorted(records.items())
        },
    }


def cmd_kotlin_catalog(check_only: bool) -> int:
    try:
        records = catalog_records()
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    catalog = render_kotlin_catalog(records).encode("utf-8")
    pin = kotlin_catalog_pin(records, catalog)
    if check_only:
        errors: list[str] = []
        if not KOTLIN_CATALOG.is_file() or KOTLIN_CATALOG.read_bytes() != catalog:
            errors.append(
                f"{KOTLIN_CATALOG.name} is stale - regenerate: python "
                "tools/generate_conf_matrix.py --kotlin-catalog")
        current = (json.loads(KOTLIN_CATALOG_PIN.read_text(encoding="utf-8"))
                   if KOTLIN_CATALOG_PIN.is_file() else None)
        if current != pin:
            errors.append(f"{KOTLIN_CATALOG_PIN.name} is stale - regenerate "
                          "(--kotlin-catalog)")
        for error in errors:
            print(f"ERROR {error}")
        return 1 if errors else 0
    KOTLIN_CATALOG.write_bytes(catalog)
    KOTLIN_CATALOG_PIN.write_text(json.dumps(pin, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {KOTLIN_CATALOG.relative_to(ROOT)} "
          f"({sum(len(r['keys']) for r in records.values())} keys across "
          f"{len(records)} flavors) and the pin")
    return 0


def _catalog_check_errors() -> list[str]:
    """The catalog legs of --check, tolerating missing dists the same loud
    NOTE way the matrix legs do (check_sources owns that posture)."""
    import contextlib
    import io

    captured = io.StringIO()
    code = 0
    with contextlib.redirect_stderr(captured), contextlib.redirect_stdout(captured):
        try:
            code = cmd_kotlin_catalog(check_only=True)
        except Exception as exc:  # a generator crash is an error, not a note
            return [f"kotlin-catalog check crashed: {exc}"]
    if code == 0:
        return []
    text = captured.getvalue()
    if "conf dist files missing" in text:
        print(f"NOTE conf-matrix: kotlin-catalog: {text.strip().splitlines()[0]}",
              file=sys.stderr)
        return []
    return [line for line in text.splitlines() if line.startswith("ERROR")]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flavor", default="vanilla",
                        choices=tuple(flavor_layout.FLAVOR_IDS))
    parser.add_argument("--out", type=Path, default=None,
                        help="write the matrix to PATH instead of the flavor's "
                             "schemas/conf-matrix-{flavor}.json")
    parser.add_argument("--list-surfaces", action="store_true",
                        help="print the dist files the flavor's pins carry and exit")
    parser.add_argument("--kotlin-catalog", action="store_true",
                        help="generate the packaged Kotlin staging-catalog "
                             "(GeneratedBotConfKeyCatalog.kt + its pin) from "
                             "every flavor's conf matrix (W3.4)")
    parser.add_argument("--check", action="store_true",
                        help="verify every committed conf matrix regenerates "
                             "byte-identically from the current manifest + "
                             "pinned dists (R3-1 freshness gate; 0 = in sync, "
                             "1 = stale/missing)")
    args = parser.parse_args()
    if args.check:
        errors = check() + [
            f"kotlin-catalog: {error}" for error in _catalog_check_errors()
        ]
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        print("conf-matrix check: OK (all committed matrices + the Kotlin "
              "staging catalog regenerate identically)" if not errors
              else f"conf-matrix check: FAILED ({len(errors)} stale/missing)")
        return 1 if errors else 0
    if args.kotlin_catalog:
        return cmd_kotlin_catalog(check_only=False)
    return generate(args.flavor, args.out, args.list_surfaces)


if __name__ == "__main__":
    sys.exit(main())
