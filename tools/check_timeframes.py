#!/usr/bin/env python3
"""Validate schemas/timeframes/<flavor>.json (schema 1) and their sync pins.

The per-flavor content-patch timeframe control (design §4 of
docs/DESKTOP-MULTIPLAYER-AND-TIMEFRAME-DESIGN.md) keeps its stage data in
three places that must never drift:

- `schemas/timeframes/<flavor>.json` — the per-flavor stage registry
  (the single source of record for the manifests' stage ids, orders, titles,
  availability sets, and era-term classes);
- `android/app/src/main/java/com/pocketrealm/client/TimeframeStage.kt` —
  the shared android-free registry the app compiles (mirrors
  schemas/flavor.json the same way ClientFlavors does);
- `native/patches/playerbots/PlayerbotLlmTruthCore.h` — the native era-policy
  lists that `vanilla-1.12.1` must equal verbatim (the design's grounded
  baseline / byte-equivalence requirement).

Checks:
- per manifest: schema 1; the flavor slug; unique canonical stage ids
  (`<flavor>-<patch>`); contiguous 0-based orders; `default_stage` is the
  highest-order (FINAL) stage; availability id sets are lists of positive
  ints (empty until the S4 DB pin curates them — the honest v1 posture is
  enforced, an invented id set fails here) with the pending-measurement
  provenance string carried while empty; era blocks well-formed;
- cross-source sync: the manifest stages == the Kotlin registry stages
  (ids AND orders, per flavor, including exactly one `final` stage per
  flavor whose id is the manifest default);
- era baseline three ways: the manifest final-stage baseline == the Kotlin
  registry's baseline == the GENERATED native header's baseline (the lists
  the truth core serves are generated from the manifests by
  tools/generate_timeframe_era.py and hash-pinned; that chain is verified
  byte-identically, and the truth core must consume the generated selector);
- the v1 overlay set: every vanilla stage carries the reversible
  marker-scaffolding overlay; schemas/timeframes/overlay-pins.json binds
  the SQL bytes and the generated Kotlin catalog, overlays carry
  INSERT OR IGNORE statements only and never a destructive one;
- mechanism grounding (skipped with a printed note when the submodule is
  absent): the pinned cmangos schema carries the columns the stage-marker
  mechanism targets — `conditions` DDL, `condition_id` on `npc_vendor`,
  `creature_loot_template`, `areatrigger_teleport`, and
  `quest_template.RequiredCondition` (design §2's zero-core-change claim).

Usage:
    python tools/check_timeframes.py [--repo-root PATH]

Exit codes: 0 = valid, 1 = invalid (errors printed as "ERROR ...").
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TIMEFRAMES = ROOT / "schemas" / "timeframes"
FLAVOR_IDS = ("vanilla", "tbc", "wotlk")

# Loaded eagerly from this checkout: the overlay-pins leg derives the v1
# stage set (the qualified lane) from the vanilla manifest.
manifests_by_flavor: dict[str, dict] = {}
for _flavor in FLAVOR_IDS:
    _manifest_path = TIMEFRAMES / f"{_flavor}.json"
    if _manifest_path.is_file():
        try:
            manifests_by_flavor[_flavor] = json.loads(
                _manifest_path.read_text(encoding="utf-8")
            )
        except json.JSONDecodeError:
            pass  # main() reports the parse error properly


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


STAGE_ID_RE = re.compile(r"^[a-z]+-\d+(\.\d+)*[a-z]?$")
PENDING_PROVENANCE_PREFIX = "pending-s4-pin:"
AVAILABILITY_INT_SETS = (
    "event_open_ids",
    "event_close_ids",
    "quest_required_condition_ids",
    "vendor_demote_entries",
    "loot_demote_entries",
    "zone_entry_gates",
)
# The cmangos source facts the stage-marker mechanism depends on (design §2).
MANGOS_SCHEMA_FACTS = (
    ("CREATE TABLE `conditions`", "conditions DDL (the condition host table)"),
    ("CREATE TABLE `npc_vendor`", "npc_vendor DDL"),
    ("CREATE TABLE `creature_loot_template`", "creature_loot_template DDL"),
    ("CREATE TABLE `areatrigger_teleport`", "areatrigger_teleport DDL"),
    ("CREATE TABLE `quest_template`", "quest_template DDL"),
)


def validate_manifest(manifest: dict, errors: list[str]) -> None:
    """Structural + consistency validation of one per-flavor manifest."""
    flavor = manifest.get("flavor")
    if manifest.get("schema") != 1:
        errors.append(f"{flavor}: schema must be 1, found {manifest.get('schema')!r}")
    if flavor not in FLAVOR_IDS:
        errors.append(f"flavor must be one of {FLAVOR_IDS}, found {flavor!r}")
        return
    stages = manifest.get("stages")
    if not isinstance(stages, list) or not stages:
        errors.append(f"{flavor}: stages must be a non-empty list")
        return
    ids: list[str] = []
    orders: list[int] = []
    for stage in stages:
        stage_id = stage.get("id")
        if not isinstance(stage_id, str) or not STAGE_ID_RE.match(stage_id):
            errors.append(f"{flavor}: bad stage id {stage_id!r} (canonical <flavor>-<patch>)")
            continue
        if not stage_id.startswith(f"{flavor}-"):
            errors.append(f"{flavor}: stage id {stage_id!r} must carry the flavor prefix")
        ids.append(stage_id)
        order = stage.get("order")
        if not isinstance(order, int) or order < 0:
            errors.append(f"{flavor}: stage {stage_id} order must be a 0-based int")
        else:
            orders.append(order)
        title = stage.get("title")
        if not isinstance(title, str) or not title:
            errors.append(f"{flavor}: stage {stage_id} must carry a title string")
        availability = stage.get("availability")
        if not isinstance(availability, dict):
            errors.append(f"{flavor}: stage {stage_id} must carry an availability block")
            continue
        empty_sets = True
        for key in AVAILABILITY_INT_SETS:
            values = availability.get(key)
            if not isinstance(values, list):
                errors.append(f"{flavor}: stage {stage_id} availability.{key} must be a list")
                continue
            for value in values:
                if not isinstance(value, int) or value <= 0:
                    errors.append(
                        f"{flavor}: stage {stage_id} availability.{key} "
                        f"carries a non-positive/non-int id {value!r}"
                    )
            if values:
                empty_sets = False
        if empty_sets:
            provenance = availability.get("provenance", "")
            if not provenance.startswith(PENDING_PROVENANCE_PREFIX):
                errors.append(
                    f"{flavor}: stage {stage_id} has empty availability sets but no "
                    f"provenance starting {PENDING_PROVENANCE_PREFIX!r} — an unmeasured "
                    "set must say so (invented ids fail here)"
                )
        era = stage.get("era_terms")
        if not isinstance(era, dict):
            errors.append(f"{flavor}: stage {stage_id} must carry an era_terms block")
    if len(ids) != len(set(ids)):
        errors.append(f"{flavor}: stage ids must be unique, found {sorted(ids)}")
    if orders and sorted(orders) != list(range(len(stages))):
        errors.append(
            f"{flavor}: orders must be contiguous 0..{len(stages) - 1}, found {sorted(orders)}"
        )
    final_id = max(
        (s for s in stages if isinstance(s.get("order"), int)),
        key=lambda s: s["order"],
        default=None,
    )
    default = manifest.get("default_stage")
    if final_id is None or default != final_id.get("id"):
        errors.append(
            f"{flavor}: default_stage {default!r} must be the FINAL stage "
            f"(highest order: {final_id.get('id') if final_id else None!r})"
        )
    if flavor == "vanilla":
        baseline = manifest.get("final_stage_era_baseline")
        if not isinstance(baseline, dict):
            errors.append("vanilla: must carry the final_stage_era_baseline block")
        elif not isinstance(baseline.get("sense_phrases"), list) or not isinstance(
            baseline.get("always_ban_terms"), list
        ):
            errors.append("vanilla: final_stage_era_baseline must carry the term lists")


def parse_kotlin_registry(text: str) -> dict[str, list[tuple[str, int, bool]]]:
    """Extract (stage_id, order, final) per flavor from the shared registry.

    The registry builds each flavor's timeline from an ordered patch-string
    list: `private val VANILLA_PATCHES = listOf("1.1", ..., "1.12.1")` bound
    by `val VANILLA: List<TimeframeStage> = stages("vanilla", VANILLA_PATCHES)`
    (TimeframeStage.kt). Orders are list positions; the LAST patch of a
    flavor's list is its final/default stage (the registry derives isFinal
    the same way). A registry whose shape this parser cannot read yields no
    entries and the sync leg fails loud — a silent skip would end the
    three-way sync the gate exists for.
    """
    bindings = {
        list_name: flavor
        for flavor, list_name in re.findall(
            r'val \w+: List<TimeframeStage> = stages\("(\w+)", (\w+)\)', text
        )
    }
    found: dict[str, list[tuple[str, int, bool]]] = {}
    for list_name, flavor in bindings.items():
        patches_match = re.search(
            rf"val {list_name} = listO[f]\(([^)]*)\)", text, re.DOTALL
        )
        if not patches_match:
            continue
        patches = re.findall(r'"([^"]+)"', patches_match.group(1))
        found[flavor] = [
            (f"{flavor}-{patch}", order, order == len(patches) - 1)
            for order, patch in enumerate(patches)
        ]
    return found


def validate_kotlin_sync(
    registry: dict[str, list[tuple[str, int, bool]]], manifests: dict[str, dict], errors: list[str]
) -> None:
    for flavor in FLAVOR_IDS:
        stages = {
            s["id"]: s.get("order") for s in manifests.get(flavor, {}).get("stages", [])
        }
        kotlin = registry.get(flavor, [])
        if not kotlin:
            errors.append(
                f"kotlin registry: no stage entries parsed for {flavor} — the registry "
                "shape (stages/patch-list declarations) must stay parseable by this gate"
            )
            continue
        kotlin_ids = {stage_id: order for stage_id, order, _ in kotlin}
        if kotlin_ids != stages:
            errors.append(
                f"{flavor}: kotlin registry stages {sorted(kotlin_ids)} != manifest "
                f"stages {sorted(stages)} (ids AND orders must match)"
            )
        finals = [stage_id for stage_id, _, final in kotlin if final]
        if len(finals) != 1:
            errors.append(f"{flavor}: exactly one registry stage must be final, found {finals}")
        elif manifests.get(flavor, {}).get("default_stage") not in finals:
            errors.append(
                f"{flavor}: the registry's final stage {finals} must be the manifest "
                f"default {manifests.get(flavor, {}).get('default_stage')!r}"
            )


def validate_mangos_schema_facts(text: str, errors: list[str]) -> None:
    for needle, label in MANGOS_SCHEMA_FACTS:
        if needle not in text:
            errors.append(f"mangos schema: missing {label} ({needle!r})")
    for table, column in (
        ("npc_vendor", "condition_id"),
        ("creature_loot_template", "condition_id"),
        ("areatrigger_teleport", "condition_id"),
        ("quest_template", "RequiredCondition"),
    ):
        create = re.search(rf"CREATE TABLE `{table}` \((.*?)\);", text, re.DOTALL)
        if create and column not in create.group(1):
            errors.append(f"mangos schema: {table} lacks {column} (the demotion leg)")


def parse_kotlin_era_baseline(text: str) -> tuple[list[str], list[tuple[str, bool]]] | None:
    """Parse the shared registry's grounded era baseline (the Kotlin copy
    of the manifest's final-stage lists, pinned by TimeframeStageTest)."""
    always = re.search(r"alwaysBanTerms = listOf\(([^)]*)\)", text, re.DOTALL)
    senses = re.search(
        r"sensePhrases = listOf\((.*?)\n        \)", text, re.DOTALL
    )
    if not always or not senses:
        return None
    terms = re.findall(r'"([^"]+)"', always.group(1))
    phrases = [
        (needle, flag == "true")
        for needle, flag in re.findall(
            r'EraSensePhrase\(\s*"([^"]+)",\s*(true|false)\s*\)', senses.group(1)
        )
    ]
    return terms, phrases


def validate_kotlin_era_sync(
    baseline: dict, kotlin: tuple[list[str], list[tuple[str, bool]]], errors: list[str]
) -> None:
    kotlin_terms, kotlin_phrases = kotlin
    if baseline.get("always_ban_terms") != kotlin_terms:
        errors.append(
            f"era baseline: kotlin registry alwaysBanTerms {kotlin_terms} != manifest "
            f"{baseline.get('always_ban_terms')} (the shared core must mirror the manifests)"
        )
    manifest_phrases = [tuple(p) for p in baseline.get("sense_phrases", [])]
    if manifest_phrases != kotlin_phrases:
        errors.append(
            "era baseline: kotlin registry sensePhrases drifted from the manifest "
            f"({len(kotlin_phrases)} kotlin vs {len(manifest_phrases)} manifest entries)"
        )


def parse_kotlin_named_baselines(
    text: str,
) -> dict[str, tuple[list[str], list[tuple[str, bool]]]]:
    """Parse the per-flavor NAMED era baselines (W3.1): every
    `val <FLAVOR>_FINAL_ERA_BASELINE: TimeframeStage.EraTermClass = ...`
    block in the shared registry, keyed by flavor slug. Returns
    {flavor: (always_ban_terms, [(needle, negatable), ...])}; a registry
    whose shape this parser cannot read yields a missing flavor and the
    per-flavor sync leg fails loud (never a silent skip)."""
    baselines: dict[str, tuple[list[str], list[tuple[str, bool]]]] = {}
    for match in re.finditer(
        r"val ([A-Z]+)_FINAL_ERA_BASELINE: TimeframeStage\.EraTermClass = "
        r"TimeframeStage\.EraTermClass\(",
        text,
    ):
        name = match.group(1).lower()
        # The block ends where the next `val ` declaration or the function
        # section begins — the EraTermClass body is the alwaysBanTerms +
        # sensePhrases lists.
        tail = text[match.end():]
        always = re.search(r"alwaysBanTerms = listOf\(([^)]*)\)", tail)
        senses = re.search(r"sensePhrases = listOf\((.*?)\n        \)", tail, re.DOTALL)
        if not always or not senses:
            continue
        terms = re.findall(r'"([^"]+)"', always.group(1))
        phrases = [
            (needle, flag == "true")
            for needle, flag in re.findall(
                r'EraSensePhrase\(\s*"([^"]+)",\s*(true|false)\s*\)',
                senses.group(1),
            )
        ]
        baselines[name] = (terms, phrases)
    return baselines


def validate_kotlin_flavor_baseline_sync(
    manifests: dict[str, dict], errors: list[str]
) -> None:
    """W3.1 per-flavor leg: every manifest that carries a
    final_stage_era_baseline must have the SAME-NAMED Kotlin registry
    baseline (`<FLAVOR>_FINAL_ERA_BASELINE`) with identical lists — the
    vanilla-only era sync (validate_kotlin_era_sync) keeps its first-match
    shape; this leg extends the mirror contract to every flavor so the
    TBC/WotLK legitimization moves cannot drift from the shared core."""
    registry_path = (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/TimeframeStage.kt"
    )
    if not registry_path.is_file():
        return  # the missing-registry case is another leg's error
    named = parse_kotlin_named_baselines(registry_path.read_text(encoding="utf-8"))
    for flavor, manifest in manifests.items():
        baseline = manifest.get("final_stage_era_baseline")
        if not isinstance(baseline, dict):
            continue  # the flavor has no grounded baseline yet (earlier shape)
        kotlin = named.get(flavor)
        if kotlin is None:
            errors.append(
                f"{flavor}: manifest carries final_stage_era_baseline but the kotlin "
                f"registry has no {flavor.upper()}_FINAL_ERA_BASELINE — the shared "
                "core must mirror every flavor's grounded baseline (W3.1)"
            )
            continue
        sub_errors: list[str] = []
        validate_kotlin_era_sync(baseline, kotlin, sub_errors)
        for error in sub_errors:
            errors.append(f"{flavor}: {error}")


def validate_generated_era_chain(errors: list[str]) -> None:
    """The native consumer chain: the generated era header + its pin must
    regenerate byte-identically from the manifests (tools/
    generate_timeframe_era.py check), and the truth core must consume the
    generated selector - not its own hardcoded lists."""
    generator = ROOT / "tools" / "generate_timeframe_era.py"
    result = subprocess.run(
        ["python", str(generator), "check"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        errors.append(
            "generated era artifacts are stale: python tools/generate_timeframe_era.py "
            f"check failed: {(result.stdout + result.stderr).strip()[:400]}"
        )
    core = ROOT / "native/patches/playerbots/PlayerbotLlmTruthCore.h"
    if core.is_file():
        text = core.read_text(encoding="utf-8")
        if 'include "PlayerbotLlmEraStages.generated.h"' not in text:
            errors.append("truth core: must include the generated era header")
        if "erastages::AlwaysBanTermsFor(ActiveTimeframeStage())" not in text:
            errors.append(
                "truth core: EraAlwaysBanTerms must resolve through the generated "
                "per-stage selector (design §5)"
            )


def validate_overlay_pins(errors: list[str], pins_path: Path | None = None) -> None:
    """The v1 overlay set: every VANILLA stage carries the reversible
    marker-scaffolding overlay; the pins bind the SQL bytes, the generated
    Kotlin catalog, and nothing else may drift."""
    pins_path = pins_path or (TIMEFRAMES / "overlay-pins.json")
    try:
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        errors.append(f"missing overlay pins: {pins_path} (regenerate: python "
                      "tools/generate_timeframe_era.py overlays)")
        return
    except json.JSONDecodeError as failure:
        errors.append(f"{pins_path}: invalid JSON ({failure})")
        return
    vanilla_ids = {
        stage["id"] for stage in manifests_by_flavor.get("vanilla", {}).get("stages", [])
    }
    pinned_ids = set(pins.get("overlays", {}))
    if pinned_ids != vanilla_ids:
        errors.append(
            f"overlay pins: pinned stages {sorted(pinned_ids)} != the vanilla stage "
            f"set {sorted(vanilla_ids)} (v1 = the qualified lane only)"
        )
    for stage, entry in sorted(pins.get("overlays", {}).items()):
        sql = ROOT / entry["sql"]
        if not sql.is_file():
            errors.append(f"overlay pins: missing SQL for {stage}: {entry['sql']}")
            continue
        if sha256(sql.read_bytes()) != entry["sha256"]:
            errors.append(f"overlay pins: {entry['sql']} drifted from its pinned sha256")
        text = sql.read_text(encoding="utf-8")
        # R2-2 (review round 3): the declared contract is the DEVICE
        # applier's own per-statement policy
        # (TimeframeOverlayPlan.policyChecked: every statement starts with
        # INSERT OR IGNORE INTO). The old substring bans
        # (DELETE/DROP/ALTER/ATTACH) accepted UPDATE, INSERT OR REPLACE,
        # and plain INSERT outright — a doctored overlay that passed this
        # gate failed only at world start. The statement-level check is
        # ported here: strip COMMENT LINES FIRST (the honest overlays'
        # prose carries semicolons), then split on ';' and require EVERY
        # non-empty fragment to start with INSERT OR IGNORE INTO.
        code_lines = [line for line in text.splitlines()
                      if not line.strip().startswith("--")]
        statements = []
        for fragment in "\n".join(code_lines).split(";"):
            body = fragment.strip()
            if body:
                statements.append(body)
        if not statements:
            errors.append(
                f"overlay pins: stage {stage}: {entry['sql']} carries no "
                "statements at all (a comment-only overlay ships nothing)")
        for body in statements:
            if not body.upper().startswith("INSERT OR IGNORE INTO"):
                head = body.splitlines()[0][:80]
                errors.append(
                    f"overlay pins: stage {stage}: {entry['sql']} carries a "
                    f"destructive or non-idempotent statement ({head}) - "
                    "every statement must start with INSERT OR IGNORE INTO "
                    "(flag-set only, design §2; the device applier's "
                    "policyChecked rejects the same shape at world start)"
                )
    catalog_entry = pins.get("generated_catalog")
    if not isinstance(catalog_entry, dict):
        errors.append("overlay pins: missing the generated_catalog block")
        return
    catalog = ROOT / catalog_entry["path"]
    if not catalog.is_file():
        errors.append(f"overlay pins: missing generated catalog {catalog_entry['path']}")
    elif sha256(catalog.read_bytes()) != catalog_entry["sha256"]:
        errors.append("overlay pins: the generated Kotlin catalog drifted from its pin")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parent.parent),
        help="repository root (default: the checkout this tool lives in)",
    )
    args = parser.parse_args()
    root = Path(args.repo_root)
    errors: list[str] = []
    run_checks(root, errors)

    for error in errors:
        print(f"ERROR {error}")
    if errors:
        print(f"check_timeframes: {len(errors)} error(s)")
        return 1
    print(
        "check_timeframes: OK — "
        + ", ".join(
            f"{flavor}: {len(manifests_by_flavor.get(flavor, {}).get('stages', []))} stages"
            for flavor in FLAVOR_IDS
            if flavor in manifests_by_flavor
        )
        + " (registry, kotlin/native era sync, and overlay pins verified)"
    )
    return 0


def validate() -> list[str]:
    """The aggregate-gate entry (tools/check_repo.py imports this): run the
    full timeframe gate against THIS checkout and return the error list."""
    errors: list[str] = []
    run_checks(ROOT, errors)
    return errors


#: era curation is only PROCEDURALLY sequenced before
# the qualification flip — this leg makes it mechanical. A flavor whose
# manifest is qualified:true must have every stage's era_terms either
# grounded (the vanilla baseline posture / an explicit grounded marker) or
# carrying a curated delta_from_flavor_final. The pending-flavor-qualification
# marker names exactly the state that must NOT ship qualified (it would ban
# core expansion vocabulary in live TBC/WotLK worlds: shattrath, acherus...).
PENDING_FLAVOR_QUALIFICATION_PREFIX = "pending-flavor-qualification"


def _flavor_qualifications(root: Path) -> dict[str, bool]:
    """flavorId -> qualified from schemas/flavor.json ({} when absent — the
    unqualified-by-default posture; a missing manifest is another leg's
    error, never a silent pass here)."""
    path = root / "schemas" / "flavor.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return {
        str(entry.get("id")): bool(entry.get("qualified"))
        for entry in data.get("flavors", [])
    }


def validate_qualified_era_curation(
    qualifications: dict[str, bool], manifest: dict, errors: list[str]
) -> None:
    """Refuse qualified:true for a flavor whose era_terms are uncurated:
    either the pending-flavor-qualification MARKER (W3.1 must curate before
    the W4 flip), or — the data-presence invariant the marker check alone
    missed (round-5 R5-5) — any stage carrying NEITHER a grounded_baseline
    NOR a usable delta_from_flavor_final. A reworded non-pending curation
    string with a null delta would otherwise pass while the generated
    header serves the inherited VANILLA ban list (anachronistic live bans
    of core expansion vocabulary at exactly the W4 flip).

    R5-r1-1 (review round 1): a non-null delta counts as grounding ONLY in
    the exact shape the generator applies (generate_timeframe_era
    .delta_error: always_ban_add / sense_phrases_add, non-empty, no
    unknown keys, no grounded_baseline alongside it) — the gate used to
    bless ANY non-null value while the generator silently dropped it, so
    the W3.1 curation it blessed never reached the generated header."""
    flavor = manifest.get("flavor")
    if not qualifications.get(str(flavor)):
        return  # unqualified flavors may carry pending curation by design
    if not isinstance(manifest.get("final_stage_era_baseline"), dict):
        errors.append(
            f"{flavor}: the flavor is QUALIFIED but its manifest carries no "
            "final_stage_era_baseline — every stage would resolve through the "
            "generator's CROSS-FLAVOR vanilla fallback and live-ban core "
            "expansion vocabulary; curate the flavor's own grounded baseline "
            "(W3.1) before the W4 qualification flip"
        )
    for stage in manifest.get("stages", []):
        era = stage.get("era_terms")
        if not isinstance(era, dict):
            continue  # structural legs already report missing era blocks
        stage_id = stage.get("id")
        curation = str(era.get("curation", ""))
        if curation.startswith(PENDING_FLAVOR_QUALIFICATION_PREFIX):
            errors.append(
                f"{flavor}: stage {stage_id!r} era_terms curation is "
                f"{PENDING_FLAVOR_QUALIFICATION_PREFIX!r} but the flavor is "
                "QUALIFIED — curate the era terms (grounded baseline or "
                "delta_from_flavor_final) before flipping qualification "
                "(W3.1 precedes the W4 flip; shipping pending curation "
                "live-bans core expansion vocabulary)"
            )
            continue
        # the curation STRING is not the contract — the DATA
        # is. A stage is grounded when it embeds its own grounded_baseline,
        # carries a USABLE curated delta_from_flavor_final, or inherits the
        # flavor's OWN final_stage_era_baseline (the generator's
        # intra-flavor inheritance). What must NEVER qualify is a flavor
        # with no final_stage_era_baseline of its own: its stages would
        # resolve through the generator's CROSS-FLAVOR fallback and serve
        # the inherited VANILLA ban list (anachronistic live bans of core
        # expansion vocabulary at exactly the flip).
        grounded = isinstance(era.get("grounded_baseline"), dict)
        delta = era.get("delta_from_flavor_final")
        if grounded and delta is not None:
            errors.append(
                f"{flavor}: stage {stage_id!r} era_terms carries BOTH "
                "grounded_baseline and delta_from_flavor_final — the "
                "grounded baseline REPLACES the flavor-final baseline while "
                "the delta MODIFIES it; register exactly one (W3.1) before "
                "the W4 qualification flip"
            )
            continue
        if delta is not None:
            try:
                from generate_timeframe_era import delta_error
            except ImportError:  # package import (tools.* namespace)
                from tools.generate_timeframe_era import delta_error
            delta_problem = delta_error(str(stage_id), delta)
            if delta_problem:
                errors.append(
                    f"{flavor}: {delta_problem} — the generator would drop "
                    "this curation and serve the UNMODIFIED flavor-final "
                    "baseline; fix the delta shape (W3.1) before the W4 "
                    "qualification flip"
                )
                continue
        delta_present = delta is not None
        inherits_own_baseline = isinstance(
            manifest.get("final_stage_era_baseline"), dict)
        if not grounded and not delta_present and not inherits_own_baseline:
            errors.append(
                f"{flavor}: stage {stage_id!r} era_terms carries neither a "
                f"grounded_baseline nor a curated delta_from_flavor_final "
                f"and the manifest has no final_stage_era_baseline to "
                f"inherit (curation {curation!r}) — the generated header "
                "would serve the CROSS-FLAVOR vanilla fallback and live-ban "
                "core expansion vocabulary; curate the flavor's own baseline "
                "(W3.1) before the W4 qualification flip"
            )


def run_checks(root: Path, errors: list[str]) -> None:
    manifests: dict[str, dict] = {}
    for flavor in FLAVOR_IDS:
        path = root / "schemas" / "timeframes" / f"{flavor}.json"
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            errors.append(f"missing manifest: {path}")
            continue
        except json.JSONDecodeError as failure:
            errors.append(f"{path}: invalid JSON ({failure})")
            continue
        manifests[flavor] = manifest
        validate_manifest(manifest, errors)
        validate_qualified_era_curation(_flavor_qualifications(root), manifest, errors)
    manifests_by_flavor.update(manifests)
    if root != ROOT:
        # The generated-chain + overlay legs run against THIS checkout's
        # tools; a foreign --repo-root only gets the manifest/Kotlin legs.
        print("NOTE: --repo-root override; generated-era and overlay legs skipped")
    else:
        registry_path = (
            root / "android/app/src/main/java/com/pocketrealm/client/TimeframeStage.kt"
        )
        if registry_path.is_file():
            registry_text = registry_path.read_text(encoding="utf-8")
            validate_kotlin_sync(parse_kotlin_registry(registry_text), manifests, errors)
            baseline = (
                manifests.get("vanilla", {}).get("final_stage_era_baseline")
                if manifests.get("vanilla")
                else None
            )
            kotlin_baseline = parse_kotlin_era_baseline(registry_text)
            if isinstance(baseline, dict) and kotlin_baseline is not None:
                validate_kotlin_era_sync(baseline, kotlin_baseline, errors)
            elif kotlin_baseline is None:
                errors.append(
                    "kotlin registry: could not parse the grounded era baseline "
                    "(alwaysBanTerms / sensePhrases)"
                )
            validate_kotlin_flavor_baseline_sync(manifests, errors)
        else:
            errors.append(f"missing shared registry: {registry_path}")
        validate_generated_era_chain(errors)
        validate_overlay_pins(errors)

    mangos_path = root / "native/cmangos/sql/base/mangos.sql"
    if mangos_path.is_file():
        validate_mangos_schema_facts(mangos_path.read_text(encoding="utf-8", errors="ignore"), errors)
    else:
        print(f"NOTE: pinned cmangos schema absent ({mangos_path}); schema-facts leg skipped")


if __name__ == "__main__":
    sys.exit(main())
