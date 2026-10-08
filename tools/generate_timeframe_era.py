#!/usr/bin/env python3
"""Generate the timeframe artifacts from schemas/timeframes (design §2/§5).

The manifests are the single source of record. This script derives every
generated consumer artifact from them, hash-pins each against its sources,
and `check` verifies the whole chain (used by tools/check_timeframes.py):

  era       → native/patches/playerbots/PlayerbotLlmEraStages.generated.h
              (the per-stage chat era-term classes the truth core selects
              from PocketRealm.TimeframeStage) + the pin JSON
              schemas/timeframes/generated-era-classes-pin.json
  overlays  → schemas/timeframes/<flavor>/overlays/<stage>.sql (the
              reversible marker scaffolding per stage) +
              schemas/timeframes/overlay-pins.json + the shared Kotlin
              catalog android/app/src/main/java/com/pocketrealm/client/
              GeneratedTimeframeOverlayCatalog.kt (the packaged-app form
              of the same bytes)

Every artifact is byte-deterministic; `check` fails loud on any drift
between the manifests, the generated files, and the pins.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TIMEFRAMES = ROOT / "schemas" / "timeframes"
ERA_HEADER = ROOT / "native/patches/playerbots/PlayerbotLlmEraStages.generated.h"
ERA_PIN = TIMEFRAMES / "generated-era-classes-pin.json"
OVERLAY_PINS = TIMEFRAMES / "overlay-pins.json"
OVERLAY_CATALOG = (
    ROOT / "android/app/src/main/java/com/pocketrealm/client/"
    "GeneratedTimeframeOverlayCatalog.kt"
)
FLAVOR_IDS = ("vanilla", "tbc", "wotlk")

HEADER_BANNER = """\
// GENERATED FILE - from schemas/timeframes/<flavor>.json by
// tools/generate_timeframe_era.py. Regenerate with:
//   python tools/generate_timeframe_era.py era
// schemas/timeframes/generated-era-classes-pin.json binds this file's
// sha256 to the source manifests; tools/check_timeframes.py fails loud on
// drift. The per-stage era classes follow design section 5: the world
// resolves its class set from PocketRealm.TimeframeStage; every stage
// whose manifest carries no curated delta resolves to its flavor's
// grounded baseline (today: the vanilla 1.12.1 lists for ALL stages -
// the documented v1 posture). DO NOT EDIT.
"""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def load_manifests() -> dict[str, dict]:
    manifests: dict[str, dict] = {}
    for flavor in FLAVOR_IDS:
        manifests[flavor] = json.loads(
            (TIMEFRAMES / f"{flavor}.json").read_text(encoding="utf-8")
        )
    return manifests


def all_stages(manifests: dict[str, dict]) -> list[dict]:
    stages: list[dict] = []
    for flavor in FLAVOR_IDS:
        stages.extend(manifests[flavor]["stages"])
    return stages


def cpp_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


# --- era --------------------------------------------------------------------


def era_sets(stages: list[dict]) -> tuple[list[str], list[dict]]:
    """Deduplicate the (always-ban, sense) term sets across stages.

    Returns (set names in stable order, per-stage binding). Today every
    stage carries the same grounded baseline, so the generated header holds
    ONE term static and every stage points at it; when the S4 pins curate
    per-stage deltas the generator emits one static per distinct set with
    no further code change.
    """
    names: list[str] = []
    keys: dict[tuple, str] = {}
    bindings: list[dict] = []
    for stage in stages:
        baseline = (
            stage.get("era_terms", {}).get("grounded_baseline")
            if isinstance(stage.get("era_terms"), dict)
            else None
        )
        if baseline is None:
            final_id = f"{stage['id'].rsplit('-', 1)[0]}-"
            flavor = stage["id"][: len(final_id) - 1]
            baseline = manifests_baseline(flavor)
        always = [str(term) for term in baseline["always_ban_terms"]]
        senses = [[str(needle), bool(flag)] for needle, flag in baseline["sense_phrases"]]
        key = (tuple(always), tuple(map(tuple, senses)))
        if key not in keys:
            keys[key] = f"kEraSet{len(keys)}"
            names.append(keys[key])
        bindings.append(
            {"stage": stage["id"], "set": keys[key], "always": always, "senses": senses}
        )
    return names, bindings


_BASELINE_CACHE: dict[str, dict] = {}


def manifests_baseline(flavor: str) -> dict:
    """A stage without its own grounded_baseline inherits its flavor's
    final-stage baseline; the TBC/WotLK lanes carry no grounded baseline of
    their own yet (curation is pending flavor qualification), so they
    inherit the ONLY grounded policy in the repo: the vanilla 1.12.1
    baseline — the documented v1 posture (design §5)."""
    if flavor not in _BASELINE_CACHE:
        manifest = json.loads((TIMEFRAMES / f"{flavor}.json").read_text(encoding="utf-8"))
        baseline = manifest.get("final_stage_era_baseline")
        if baseline is None:
            baseline = manifests_baseline("vanilla")
        _BASELINE_CACHE[flavor] = baseline
    return _BASELINE_CACHE[flavor]


def render_era_header(manifests: dict[str, dict]) -> str:
    stages = all_stages(manifests)
    set_names, bindings = era_sets(stages)
    default = manifests["vanilla"]["default_stage"]
    out: list[str] = [HEADER_BANNER]
    out.append("#ifndef _PlayerbotLlmEraStages_generated_h")
    out.append("#define _PlayerbotLlmEraStages_generated_h")
    out.append("")
    out.append("#include <string>")
    out.append("#include <utility>")
    out.append("#include <vector>")
    out.append("")
    out.append("namespace pocketllm {")
    out.append("namespace erastages {")
    out.append("")
    out.append("// The registered stage ids, timeline order (schemas/timeframes).")
    out.append("inline std::vector<std::string> const& RegisteredStages()")
    out.append("{")
    out.append("    static std::vector<std::string> const stages = {")
    for stage in stages:
        out.append(f"        {cpp_string(stage['id'])},")
    out.append("    };")
    out.append("    return stages;")
    out.append("}")
    out.append("")
    out.append("// The DEFAULT stage: the shipped qualified lane's final patch")
    out.append("// (design section 1). An unset/unknown stage resolves here.")
    out.append("inline std::string const& DefaultStage()")
    out.append("{")
    out.append(f"    static std::string const stage({cpp_string(default)});")
    out.append("    return stage;")
    out.append("}")
    out.append("")
    out.append("inline bool IsDefaultStage(std::string const& stage)")
    out.append("{")
    out.append("    return stage.empty() || stage == DefaultStage();")
    out.append("}")
    out.append("")
    out.append("// The patch label of one stage (\"vanilla-1.5\" -> \"1.5\"), or an")
    out.append("// empty string for an unregistered stage. Drives the stage-era")
    out.append("// instruction line (EraStageLine).")
    out.append("inline std::string PatchLabel(std::string const& stage)")
    out.append("{")
    for stage in stages:
        label = stage["id"].split("-", 1)[1]
        out.append(f"    if (stage == {cpp_string(stage['id'])}) return {cpp_string(label)};")
    out.append("    return std::string();")
    out.append("}")
    out.append("")
    for name in set_names:
        binding = next(b for b in bindings if b["set"] == name)
        out.append(f"inline std::vector<std::string> const& {name}_AlwaysBan()")
        out.append("{")
        out.append(f"    static std::vector<std::string> const terms = {{")
        for term in binding["always"]:
            out.append(f"        {cpp_string(term)},")
        out.append("    };")
        out.append("    return terms;")
        out.append("}")
        out.append("")
        out.append(f"inline std::vector<std::pair<std::string, bool>> const& {name}_Senses()")
        out.append("{")
        out.append("    static std::vector<std::pair<std::string, bool>> const phrases = {")
        for needle, flag in binding["senses"]:
            out.append(f"        {{ {cpp_string(needle)}, {'true' if flag else 'false'} }},")
        out.append("    };")
        out.append("    return phrases;")
        out.append("}")
        out.append("")
    out.append("// The per-stage era class resolution (design section 5). An")
    out.append("// unregistered stage falls open to the grounded default - the")
    out.append("// settings layer never persists one, and the conf consumer")
    out.append("// double-guards.")
    out.append("inline std::vector<std::string> const& AlwaysBanTermsFor(std::string const& stage)")
    out.append("{")
    for binding in bindings:
        out.append(
            f"    if (stage == {cpp_string(binding['stage'])}) return {binding['set']}_AlwaysBan();"
        )
    out.append(f"    return {bindings[0]['set']}_AlwaysBan();")
    out.append("}")
    out.append("")
    out.append(
        "inline std::vector<std::pair<std::string, bool>> const& SensePhrasesFor("
        "std::string const& stage)"
    )
    out.append("{")
    for binding in bindings:
        out.append(
            f"    if (stage == {cpp_string(binding['stage'])}) return {binding['set']}_Senses();"
        )
    out.append(f"    return {bindings[0]['set']}_Senses();")
    out.append("}")
    out.append("")
    out.append("} // namespace erastages")
    out.append("} // namespace pocketllm")
    out.append("")
    out.append("#endif // _PlayerbotLlmEraStages_generated_h")
    return "\n".join(out)


def cmd_era(check_only: bool) -> int:
    manifests = load_manifests()
    header = render_era_header(manifests).encode("utf-8")
    manifest_digests = {
        f"{flavor}.json": sha256_file(TIMEFRAMES / f"{flavor}.json") for flavor in FLAVOR_IDS
    }
    pin = {
        "schema": 1,
        "generator": "tools/generate_timeframe_era.py era",
        "header": ERA_HEADER.relative_to(ROOT).as_posix(),
        "header_sha256": sha256_bytes(header),
        "source_manifests_sha256": manifest_digests,
    }
    if check_only:
        errors: list[str] = []
        if not ERA_HEADER.is_file() or ERA_HEADER.read_bytes() != header:
            errors.append(f"{ERA_HEADER.name} is stale - regenerate (era)")
        current_pin = (
            json.loads(ERA_PIN.read_text(encoding="utf-8")) if ERA_PIN.is_file() else None
        )
        if current_pin != pin:
            errors.append(f"{ERA_PIN.name} is stale - regenerate (era)")
        for error in errors:
            print(f"ERROR {error}")
        return 1 if errors else 0
    ERA_HEADER.write_bytes(header)
    ERA_PIN.write_text(json.dumps(pin, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {ERA_HEADER.relative_to(ROOT)} ({len(header)} bytes)")
    print(f"wrote {ERA_PIN.relative_to(ROOT)}")
    return 0


# --- overlays ----------------------------------------------------------------

GAME_EVENT_DDL_NOTE = """\
-- The marker scaffolding rides the seed's own schema (measured against a
-- REAL seeded datadir - the pinned classicmangos transcripts plus the
-- pinned augment layers, queried read-only on the canonical machine):
--   game_event(entry PK, schedule_type, occurence, length, holiday,
--              linkedTo, description)  - 67 rows, max entry 1024
--   conditions(condition_entry PK AUTOINCREMENT, type, value1..4, flags,
--              comments)                 - 1260 rows, max entry 5310010
-- Markers are schedule_type 0 (GAME_EVENT_SCHEDULE_SERVERSIDE, the core's
-- GameEventMgr.h enum): never scheduled, so INACTIVE until a mechanism
-- starts them - inert scaffolding with zero behavioral effect until the
-- S4 pin curates the real demotion rows that reference their conditions
-- (design section 2: every demoted vendor/loot/quest/entry row points at
-- a CONDITION_ACTIVE_GAME_EVENT (= 12) row naming its stage's marker).
"""


def render_overlay(manifest: dict, stage: dict) -> str:
    flavor = manifest["flavor"]
    # The reserved ranges: 8000001+, verified to hold ZERO rows in a real
    # seeded datadir (maxima 1024 / 5310010) and far below game_event's
    # mediumint ceiling (8388607).
    base_marker = 8000000 + stage["order"] + 1
    base_condition = 8000000 + stage["order"] + 1
    lines: list[str] = []
    lines.append(f"-- GENERATED FILE - from schemas/timeframes/{flavor}.json by")
    lines.append("-- tools/generate_timeframe_era.py overlays. DO NOT EDIT.")
    lines.append(f"-- Stage {stage['id']} overlay: REVERSIBLE, IDEMPOTENT,")
    lines.append("-- flag-set statements only - NEVER DELETE (design section 2;")
    lines.append("-- backward moves re-apply an earlier overlay over the same")
    lines.append("-- datadir, so nothing may be destroyed).")
    lines.append(f"-- {stage['title']}")
    lines.append(GAME_EVENT_DDL_NOTE.rstrip())
    lines.append("--")
    lines.append("-- v1 honesty: this overlay carries the stage-MARKER scaffolding")
    lines.append("-- ONLY. The measured availability sets (event windows, vendor/")
    lines.append("-- loot/quest/entry demotions) are 'pending-s4-pin' in the")
    lines.append("-- manifest; until they land this overlay changes no gameplay")
    lines.append("-- state - the markers are unscheduled and nothing references")
    lines.append("-- their conditions yet.")
    lines.append("")
    lines.append(f"-- Stage marker event: entry {base_marker} (the reserved range")
    lines.append("-- 8000001+, verified to hold zero rows in a real seeded datadir).")
    lines.append(
        "INSERT OR IGNORE INTO game_event (entry, schedule_type, occurence, length, "
        "holiday, linkedTo, description)"
    )
    lines.append(
        f"VALUES ({base_marker}, 0, 0, 0, 0, 0, "
        f"'PocketRealm stage marker: {stage['id']}');"
    )
    lines.append("")
    lines.append(
        f"-- Stage-marker condition: entry {base_condition} (the same reserved"
    )
    lines.append("-- range), type 12 = CONDITION_ACTIVE_GAME_EVENT naming this")
    lines.append("-- stage's marker.")
    lines.append(
        "INSERT OR IGNORE INTO conditions (condition_entry, type, value1, value2, "
        "value3, value4, flags, comments)"
    )
    lines.append(
        f"VALUES ({base_condition}, 12, {base_marker}, 0, 0, 0, 0, "
        f"'PocketRealm stage {stage['id']} active');"
    )
    return "\n".join(lines) + "\n"


def render_catalog(fragments: dict[str, str], digests: dict[str, str]) -> str:
    out: list[str] = []
    out.append("package com.pocketrealm.client")
    out.append("")
    out.append("/**")
    out.append(" * GENERATED FILE - from the schemas/timeframes overlay SQL files")
    out.append(" * (schemas/timeframes/vanilla/overlays/, per stage) by")
    out.append(" * tools/generate_timeframe_era.py overlays. DO NOT EDIT;")
    out.append(" * regenerate with `python tools/generate_timeframe_era.py overlays`.")
    out.append(" * schemas/timeframes/overlay-pins.json binds every overlay's")
    out.append(" * sha256 (and this catalog's) to the source SQL; tools/")
    out.append(" * check_timeframes.py fails loud on drift.")
    out.append(" *")
    out.append(" * The packaged-app form of the per-stage reversible overlay SQL")
    out.append(" * (schemas/ is not packaged): the desktop stage applier executes")
    out.append(" * these statements through the SQLite seam, statement policy")
    out.append(" * enforced (INSERT OR IGNORE only, never DELETE - design §2).")
    out.append(" */")
    out.append("object GeneratedTimeframeOverlayCatalog {")
    out.append("")
    out.append("    /** The pinned sha256 of each overlay's source SQL. */")
    out.append("    val overlaySha256: Map<String, String> = mapOf(")
    for stage in sorted(fragments):
        out.append(f"        \"{stage}\" to \"{digests[stage]}\",")
    out.append("    )")
    out.append("")
    out.append("    /** The overlay SQL for one stage, verbatim from the pinned")
    out.append("     * schemas file; null for an unregistered stage. The when is the")
    out.append("     * generated stage table - its length IS the staged stage count. */")
    out.append('    @Suppress("LongMethod")')
    out.append("    fun overlaySql(stageId: String): String? = when (stageId) {")
    for stage in sorted(fragments):
        body = fragments[stage]
        # A Kotlin RAW string whose content is the SQL text BYTE-EXACTLY:
        # the opening quotes fuse with the first line and the closing
        # quotes sit at column 0 right after the final newline. The SQL
        # carries newlines and single quotes and must never grow a double
        # quote or a dollar sign (the assert keeps regeneration failing
        # loud instead of emitting a broken literal).
        assert '"' not in body and "$" not in body and '"""' not in body, (
            f"overlay {stage} grew a character the raw-string emitter cannot take"
        )
        assert body.endswith("\n"), f"overlay {stage} must end with exactly one newline"
        out.append(f"        \"{stage}\" -> \"\"\"{body}\"\"\"")
    out.append("        else -> null")
    out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def cmd_overlays(check_only: bool) -> int:
    manifests = load_manifests()
    fragments: dict[str, str] = {}
    for flavor in FLAVOR_IDS:
        manifest = manifests[flavor]
        for stage in manifest["stages"]:
            if flavor != "vanilla":
                continue  # v1: the qualified lane only (the machinery's proof lane)
            fragments[stage["id"]] = render_overlay(manifest, stage)
    digests = {stage: sha256_bytes(sql.encode("utf-8")) for stage, sql in fragments.items()}
    catalog = render_catalog(fragments, digests)
    pins = {
        "schema": 1,
        "generator": "tools/generate_timeframe_era.py overlays",
        "overlays": {
            stage: {
                "sql": f"schemas/timeframes/{stage.split('-', 1)[0]}/overlays/{stage}.sql",
                "sha256": digest,
            }
            for stage, digest in sorted(digests.items())
        },
        "generated_catalog": {
            "path": OVERLAY_CATALOG.relative_to(ROOT).as_posix(),
            "sha256": sha256_bytes(catalog.encode("utf-8")),
        },
    }
    if check_only:
        errors: list[str] = []
        for stage, sql in fragments.items():
            path = ROOT / pins["overlays"][stage]["sql"]
            if not path.is_file() or path.read_text(encoding="utf-8") != sql:
                errors.append(f"{path.name} is stale - regenerate (overlays)")
        if not OVERLAY_CATALOG.is_file() or OVERLAY_CATALOG.read_text(encoding="utf-8") != catalog:
            errors.append("GeneratedTimeframeOverlayCatalog.kt is stale - regenerate (overlays)")
        current = (
            json.loads(OVERLAY_PINS.read_text(encoding="utf-8"))
            if OVERLAY_PINS.is_file()
            else None
        )
        if current != pins:
            errors.append(f"{OVERLAY_PINS.name} is stale - regenerate (overlays)")
        for error in errors:
            print(f"ERROR {error}")
        return 1 if errors else 0
    for stage, sql in fragments.items():
        path = ROOT / pins["overlays"][stage]["sql"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(sql, encoding="utf-8", newline="\n")
    OVERLAY_CATALOG.write_text(catalog, encoding="utf-8", newline="\n")
    OVERLAY_PINS.write_text(json.dumps(pins, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {len(fragments)} overlay(s), the catalog, and overlay-pins.json")
    return 0


def cmd_check() -> int:
    failures = cmd_era(check_only=True) + cmd_overlays(check_only=True)
    if failures:
        print(f"generate_timeframe_era check: {failures} error(s)")
        return 1
    print("generate_timeframe_era check: OK (era header + overlays match their pins)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("era", "overlays", "check"),
        help="era: generate the native era header + pin; overlays: generate the "
        "per-stage overlay SQL + pins + Kotlin catalog; check: verify all of it",
    )
    args = parser.parse_args()
    if args.command == "era":
        return cmd_era(check_only=False)
    if args.command == "overlays":
        return cmd_overlays(check_only=False)
    return cmd_check()


if __name__ == "__main__":
    sys.exit(main())
