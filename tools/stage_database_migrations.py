#!/usr/bin/env python3
"""Build the deterministic, hash-verified SQL migration asset set.

The source repos remain the source of truth. This script selects the exact
inputs for one game flavor, records their pinned commits and SQL hashes in
the schema manifest, and emits deterministic gzip assets for Android.

Flavor parameter (port plan S1 item 6; S4 items 4/5; F3-1 ledger naming):
    python tools/stage_database_migrations.py --flavor vanilla|tbc|wotlk
The vanilla lane writes the historical alias ledger
schemas/database-migrations.json byte-identically and maintains its canonical
byte copy schemas/database-migrations-vanilla.json (G3-3/D35); tbc/wotlk
write schemas/database-migrations-{flavor}.json (no alias — a non-vanilla run
can never land bytes on vanilla's seal-critical alias because their layouts
name no alias at all, independent of any guard).

Per-flavor selection rules (S4 items 4/5, executed here — this replaces the
S4SelectionNotImplemented stopgap the P2 data wave left in place):
- EXPECTED_REVISIONS: parsed from the flavor core's src/shared/revision_sql.h
  (the same header the core compiles its CheckRequiredField list from), never
  hand-copied. The vanilla parse is cross-checked against the module-level
  EXPECTED_REVISIONS pin below so a core bump cannot silently re-key the
  golden-sealed vanilla ledger bytes.
- Full_DB archive: the flavor manifest's db_content.seed pin
  (schemas/flavor.json), never a glob.
- world cutoff: the highest `required_*_mangos_*` revision the staged DB-repo
  inputs (Full_DB dump + Updates + Updates/Instances) already install; only
  NEWER core sql/updates/mangos migrations are appended — the vanilla
  z2816+ rule generalized. This lands the tbc s2492 catch-up (the Full_DB
  installs required_s2491_01_mangos_spillover_rename, the core floor is
  s2492: native/cmangos-tbc/sql/updates/mangos/
  s2492_01_mangos_gobject_near_link.sql) and closes the wotlk gap the other
  way (the 14092 dump is completed to the 14104 core floor by the DB repo's
  OWN Updates — e.g. Updates/5864_Core-14102_14104.sql applies 14103/14104 —
  so the honest cutoff appends nothing above the already-integrated 14104;
  replaying 14093+ from the core would fail on db_version columns those
  updates already renamed).
- bots SQL: the shared multi-expansion playerbots pin (C4/D34) —
  sql/characters + sql/world + the flavor's sql/world/{expansion} directory.
- DB names: the flavor manifest's databases block (vanilla classic*, tbc
  tbc*, wotlk wotlk*).

ACID selection (register §0 correction C5, corrected in fact at S4): every
pinned DB repo ships its cumulative ACID release as an in-tree
ACID/acid_<expansion>.sql file applied by InstallFullDB.sh's
apply_acid_data (classic: acid_classic.sql; tbc: ACID/acid_tbc.sql,
3,785,212 bytes at pin 8667236; wotlk: ACID/acid_wotlk.sql, 5,797,373 bytes
at pin a69b555). The C5 premise ("tbc/wotlk have NO acid_*.sql — ACID
arrives as [ACID]-tagged commits in the DB repo's history") does not hold
against the pinned checkouts: both repos are single-commit squashed
imports, tbc's carrying no [ACID]-tagged subject at all — the commit-history
selection rule would refuse tbc outright while recording, for wotlk, the
whole-repo import commit as "ACID provenance". The in-tree file is therefore
the selection for every flavor (the classic shape), and the DB repo HEAD pin
in source_commits is the provenance.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Pattern

try:
    from tools import flavor_layout
except ImportError:
    import flavor_layout


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_OUT = ROOT / "schemas" / "database-migrations.json"
TARGET_ABI = "x86_64"
FLAVOR_ID = "vanilla"


def asset_root(abi: str, flavor_id: str = "vanilla") -> Path:
    if abi not in {"x86_64", "arm64-v8a"}:
        raise ValueError(f"unsupported migration ABI: {abi}")
    root_suffix = "x86_64" if abi == "x86_64" else "arm64"
    # Flavor-resolved staging root (S1 item 7): vanilla keeps the historical
    # native/.build-<suffix>/mariadb-staging literal; tbc/wotlk get a
    # -{flavor} build root so lanes never share staging state. Inside its own
    # root every flavor stages the flat database/migrations/ tree the Gradle
    # completeness gate pins (android/app/build.gradle.kts: the per-flavor
    # staging check requires assets/database/migrations/manifest.json — it
    # also demands the per-flavor MariaDB runtime staging once these trees
    # exist). The in-APK database/migrations/{flavor}/ split
    # (docs/FLAVOR-ARTIFACT-NAMING.md) rides the APK merge wiring that
    # lands with the flavor product build; the per-flavor staging roots
    # already keep the bytes apart.
    build_root = f".build-{root_suffix}" + (
        "" if flavor_id == "vanilla" else f"-{flavor_id}"
    )
    return (
        ROOT / "native" / build_root / "mariadb-staging" /
        "assets" / "database" / "migrations"
    )


# The vanilla floors, retained as the pinned cross-check for the
# revision_sql.h parse: the golden-sealed vanilla ledger bytes carry exactly
# these values, so a core bump that moves them must be a deliberate re-stage,
# never a silent drift. tools/seed_realm_db.py imports this constant for its
# vanilla plan.
EXPECTED_REVISIONS = {
    "realm": "required_z2820_01_realmd_joindate_datetime",
    "characters": "required_z2819_01_characters_item_instance_text_id_fix",
    "logs": "required_z2778_01_logs_anticheat",
    "world": "required_z2830_01_mangos_icon_name",
}
DATABASES = {
    "realm": "classicrealmd",
    "characters": "classiccharacters",
    "logs": "classiclogs",
    "world": "classicmangos",
    "playerbot-characters": "classiccharacters",
    "playerbot-world": "classicmangos",
}

# src/shared/revision_sql.h — the same header the core compiles its
# CheckRequiredField list from (the seed lane's _required_versions_from_core
# precedent, tools/seed_realm_db.py).
_REVISION_HEADER = "src/shared/revision_sql.h"
_REVISION_DEFINE_RE = re.compile(
    r'^\s*#\s*define\s+REVISION_DB_(REALMD|LOGS|CHARACTERS|MANGOS)\s+"([^"]+)"',
    re.MULTILINE,
)
_REVISION_DEFINE_FOR = {
    "realm": "REALMD",
    "characters": "CHARACTERS",
    "logs": "LOGS",
    "world": "MANGOS",
}


@dataclass(frozen=True)
class FlavorRule:
    """The per-flavor selection dialect (S4 item 4): how this cmangos branch
    names its world revisions, which core update files carry them, and which
    expansion segment names the flavor's ACID file and bots SQL directory."""

    revision_marker: Pattern[str]
    core_update_glob: str
    core_update_revision: Callable[[str], int]
    acid_file: str
    bots_expansion: str


_FLAVOR_RULES = {
    "vanilla": FlavorRule(
        revision_marker=re.compile(r"required_z(\d{4})_\d+_mangos_[A-Za-z0-9_]+"),
        core_update_glob="z*.sql",
        core_update_revision=lambda name: int(name[1:5]),
        acid_file="ACID/acid_classic.sql",
        bots_expansion="classic",
    ),
    "tbc": FlavorRule(
        revision_marker=re.compile(r"required_s(\d{4})_\d+_mangos_[A-Za-z0-9_]+"),
        core_update_glob="s*.sql",
        core_update_revision=lambda name: int(name[1:5]),
        acid_file="ACID/acid_tbc.sql",
        bots_expansion="tbc",
    ),
    "wotlk": FlavorRule(
        revision_marker=re.compile(r"required_(\d{5})_\d+_mangos_[A-Za-z0-9_]+"),
        core_update_glob="[0-9]*.sql",
        core_update_revision=lambda name: int(name[:5]),
        acid_file="ACID/acid_wotlk.sql",
        bots_expansion="wotlk",
    ),
}


@dataclass(frozen=True)
class Input:
    component: str
    path: Path


def git_commit(path: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(path), "rev-parse", "HEAD"], text=True
    ).strip()


def sql_bytes(path: Path) -> bytes:
    if path.suffix == ".gz":
        with gzip.open(path, "rb") as stream:
            return stream.read()
    return path.read_bytes()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _manifest_flavor(flavor_id: str) -> dict:
    manifest = flavor_layout.load_flavor_manifest()
    block = next(
        (f for f in manifest.get("flavors", [])
         if isinstance(f, dict) and f.get("id") == flavor_id),
        None,
    )
    if block is None:
        raise RuntimeError(
            f"flavor {flavor_id!r} not found in schemas/flavor.json — the "
            "per-flavor migration selection inputs live in its flavor block"
        )
    return block


def expected_revisions(flavor_id: str = FLAVOR_ID) -> dict[str, str]:
    """Parse the flavor core's revision floors from revision_sql.h.

    Single source of truth (S4 item 4): the ledger's expected_revisions must
    be the revisions the checked-out core will demand at startup, not a
    hand-copied table. For vanilla the parse is cross-checked against the
    pinned EXPECTED_REVISIONS constant so the golden-sealed ledger bytes
    cannot silently re-key when the core pin moves."""
    layout = flavor_layout.resolve(flavor_id)
    header = ROOT / layout.core_submodule / _REVISION_HEADER
    try:
        text = header.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise RuntimeError(
            f"cannot read the {flavor_id} core's revision floors ({header}): "
            f"{exc} — the migration ledger cannot pin expected_revisions "
            "without them"
        ) from exc
    defines = dict(_REVISION_DEFINE_RE.findall(text))
    missing = [
        f"REVISION_DB_{key}" for key in _REVISION_DEFINE_FOR.values()
        if key not in defines
    ]
    if missing:
        raise RuntimeError(
            f"{header} is missing {', '.join(missing)} — the migration "
            "ledger cannot pin expected_revisions without the core's "
            "revision floors"
        )
    parsed = {label: defines[key] for label, key in _REVISION_DEFINE_FOR.items()}
    if flavor_id == "vanilla" and parsed != EXPECTED_REVISIONS:
        raise RuntimeError(
            "the vanilla core's revision_sql.h floors "
            f"({parsed}) no longer match the pinned EXPECTED_REVISIONS "
            f"constant ({EXPECTED_REVISIONS}) — the golden-sealed vanilla "
            "ledger bytes carry the pinned values (G3-3); align the core pin "
            "or deliberately re-pin the constant and re-stage the ledger"
        )
    return parsed


def flavor_databases(flavor_id: str = FLAVOR_ID) -> dict[str, str]:
    """Component -> database name for one flavor, from the flavor manifest's
    databases block (vanilla classic*, tbc tbc*, wotlk wotlk*). For vanilla
    the resolution is cross-checked against the pinned DATABASES table for
    the same reason as EXPECTED_REVISIONS: the emitted ledger bytes must not
    drift with a manifest edit."""
    block = _manifest_flavor(flavor_id)
    databases = block.get("databases")
    if not isinstance(databases, dict):
        raise RuntimeError(
            f"flavor {flavor_id!r} has no databases block in "
            "schemas/flavor.json — cannot name its migration databases"
        )
    resolved = {
        "realm": databases.get("realmd"),
        "characters": databases.get("characters"),
        "logs": databases.get("logs"),
        "world": databases.get("world"),
    }
    resolved["playerbot-characters"] = resolved["characters"]
    resolved["playerbot-world"] = resolved["world"]
    missing = [
        key for key, value in resolved.items()
        if not isinstance(value, str) or not value
    ]
    if missing:
        raise RuntimeError(
            f"flavor {flavor_id!r} databases block is missing/empty for "
            f"{', '.join(sorted(missing))} — cannot name its migration "
            "databases"
        )
    if flavor_id == "vanilla":
        pinned = {
            key: value for key, value in DATABASES.items()
            if not key.startswith("playerbot")
        }
        base = {
            key: value for key, value in resolved.items()
            if not key.startswith("playerbot")
        }
        if base != pinned:
            raise RuntimeError(
                "the vanilla flavor manifest's databases block "
                f"({base}) no longer matches the pinned DATABASES table "
                f"({pinned}) — the golden-sealed vanilla ledger bytes carry "
                "the pinned names (G3-3); a rename is a deliberate re-pin"
            )
    return resolved


def _full_db_seed(flavor_id: str, block: dict) -> str:
    seed = (block.get("db_content") or {}).get("seed")
    if not isinstance(seed, str) or not seed:
        raise RuntimeError(
            f"flavor {flavor_id!r} has no db_content.seed registered in "
            "schemas/flavor.json — the world Full_DB archive is a pinned "
            "input, never a glob"
        )
    # the seed is joined onto the flavor's
    # db repo root by every caller; a doctored seed that escapes the repo
    # ("../<other-flavor-db>/...", an absolute path) must refuse here, the
    # same seam rule seed_realm_db's plan enforces (the shared rule lives in
    # flavor_layout.db_content_seed_error).
    seed_error = flavor_layout.db_content_seed_error(flavor_id, seed)
    if seed_error:
        raise RuntimeError(seed_error)
    return seed


def _integrated_world_revision(
    rule: FlavorRule, dump: Path, updates: list[Path], instances: list[Path]
) -> int:
    """The highest world revision the staged DB-repo inputs already install.

    The DB repo's numbered updates (and for tbc/wotlk the Full_DB dump
    itself) carry their own CMaNGOS core-sync steps — e.g. wotlk-db's
    Updates/5864_Core-14102_14104.sql applies the 14103 and 14104 migrations
    — so replaying core sql/updates/mangos files at or below that revision
    would fail on the db_version column those files already renamed. Only
    markers of the flavor's *_mangos_* dialect count: a stray realmd or
    characters revision inside an update file must not move the world
    cutoff."""
    texts = [sql_bytes(dump).decode("utf-8", "replace")]
    texts.extend(path.read_text(errors="replace") for path in updates)
    texts.extend(path.read_text(errors="replace") for path in instances)
    found = [
        int(match.group(1))
        for text in texts
        for match in rule.revision_marker.finditer(text)
    ]
    if not found:
        raise RuntimeError(
            "no integrated world revision found in the DB repo inputs — "
            "cannot compute the core-update cutoff (the Full_DB dump or an "
            "update must carry at least one required_*_mangos_* marker)"
        )
    return max(found)


def select_inputs(flavor_id: str = FLAVOR_ID) -> list[Input]:
    layout = flavor_layout.resolve(flavor_id)
    # Fail loud before any glob: every lane verifies the flavor's registered
    # pins (checkout present, HEAD == pin) before selecting inputs. ran
    # this check only for tbc/wotlk as the earlier stopgap; with the selection
    # rules now parameterized, vanilla is verified like the rest (its pins
    # resolve — pinned by test_require_flavor_pins_vanilla_resolves).
    flavor_layout.require_flavor_pins(flavor_id)
    rule = _FLAVOR_RULES[flavor_id]
    block = _manifest_flavor(flavor_id)
    cmangos = ROOT / layout.core_submodule
    classic = ROOT / layout.db_submodule
    bots = ROOT / layout.bots_submodule
    selected = [
        Input("realm", cmangos / "sql/base/realmd.sql"),
        Input("characters", cmangos / "sql/base/characters.sql"),
        Input("logs", cmangos / "sql/base/logs.sql"),
        Input("world", classic / _full_db_seed(flavor_id, block)),
    ]
    classic_updates = sorted((classic / "Updates").glob("[0-9]*.sql"))
    instance_updates = sorted(
        (classic / "Updates/Instances").glob("[0-9]*.sql")
    )
    selected.extend(Input("world", p) for p in classic_updates)
    selected.extend(Input("world", p) for p in instance_updates)
    # The DB repo's numbered updates and full dump carry their own CMaNGOS
    # core-sync steps. Find the highest world revision those pinned files
    # already install and append only newer core migrations; replaying older
    # ones would fail on the revision column the dump/updates have already
    # advanced (vanilla: Classic-DB integrates z2829, the core appends
    # z2830+; tbc: the Full_DB integrates s2491, the core appends the s2492
    # catch-up; wotlk: the Updates integrate 14104 — the core floor — so
    # nothing is appended).
    integrated_core_revision = _integrated_world_revision(
        rule, classic / _full_db_seed(flavor_id, block),
        classic_updates, instance_updates,
    )
    selected.extend(
        Input("world", p)
        for p in sorted(
            (cmangos / "sql/updates/mangos").glob(rule.core_update_glob)
        )
        if rule.core_update_revision(p.name) > integrated_core_revision
    )
    # Bots SQL: the shared multi-expansion playerbots pin — the
    # common characters/world schema pair plus the flavor's own
    # sql/world/{expansion} additions.
    selected.extend(
        Input("playerbot-characters", p)
        for p in sorted((bots / "sql/characters").glob("*.sql"))
    )
    selected.extend(
        Input("playerbot-world", p)
        for p in sorted((bots / "sql/world").glob("*.sql"))
    )
    selected.extend(
        Input("playerbot-world", p)
        for p in sorted(
            (bots / "sql/world" / rule.bots_expansion).glob("*.sql")
        )
    )
    # The full installers apply these mandatory CMaNGOS/content layers after
    # the numbered content/core updates (InstallFullDB.sh:
    # apply_full_dbc_original_data, apply_full_dbc_cmangos_fixes,
    # apply_full_scriptdev2_data).  In particular,
    # original_data/Spell.sql recreates spell_template with the coefficient
    # columns required by the pinned core; the full dump alone only has the
    # older column set.  Keep these additions at the tail so the migration
    # IDs already shipped remain append-only and an existing app-private
    # database can repair itself without replaying old entries.
    selected.extend(
        Input("world", p)
        for p in sorted((cmangos / "sql/base/dbc/original_data").glob("*.sql"))
    )
    selected.extend(
        Input("world", p)
        for p in sorted((cmangos / "sql/base/dbc/cmangos_fixes").glob("*.sql"))
    )
    selected.extend(
        Input("world", p)
        for p in sorted((cmangos / "sql/scriptdev2").glob("*.sql"))
    )
    # ACID: the cumulative in-tree release every flavor's installer applies
    # (InstallFullDB.sh apply_acid_data, ACID/acid_${EXPANSION_LC}.sql).
    # See the module docstring for the correction of register: the
    # pinned tbc/wotlk DB repos DO ship these files; their single-commit
    # squashed histories carry no usable [ACID] selection.
    selected.append(Input("world", classic / rule.acid_file))
    selected.append(Input("world", classic / "utilities/cmangos_custom.sql"))
    # Pocket Realm bot-LLM memory schema. The bot-LLM code that read these
    # tables is retired (D104 native strip); the entries stay because the
    # ledger is append-only - dropping them would renumber the tail, advance
    # the SQLite seed corpus and force every provisioned datadir to
    # re-seed. The tables are inert. These files live in native/llm/sql/,
    # which no glob above covers, and are appended ONLY here at the very tail:
    # a new mid-array discovery would renumber every later migration_id and
    # replay already-applied SQL on upgrade (append-only ledger invariant).
    # Flavor-agnostic by design: the LLM memory tables are Pocket Realm
    # additions riding the shared playerbots characters/world schemas.
    selected.append(Input("playerbot-characters", ROOT / "native/llm/sql/ai_playerbot_llm_memory.sql"))
    selected.append(Input("playerbot-world", ROOT / "native/llm/sql/world_gossip.sql"))
    # LLM memory v2 (voiced-fact persistence + conversation memory):
    # an ALTER-only append over the 0411 seed tables plus one new table. The
    # seed DDL above is NEVER edited to carry these columns - editing a
    # shipped entry's bytes breaks the on-device ledger hash check and
    # diverges fresh provisions from upgraded databases. This file lives in
    # sql/migrations/ (also unglobbed) and stays the LAST entry: anything
    # appended after a release ships must come after it in turn.
    selected.append(Input("playerbot-characters", ROOT / "sql/migrations/ai_playerbot_llm_memory_v2.sql"))
    # The texts.sql register audit -
    # idempotent row-content UPDATEs riding the append-only tail (the
    # 0394 texts.sql entry itself stays byte-identical; the ledger law).
    # Stays the LAST entry: anything appended after a release ships must
    # come after it in turn.
    selected.append(Input("playerbot-world", ROOT / "sql/migrations/playerbot-texts-e2-register.sql"))
    missing = [str(item.path) for item in selected if not item.path.is_file()]
    if missing:
        raise RuntimeError(f"missing pinned SQL inputs: {missing}")
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--abi", choices=("x86_64", "arm64-v8a"), default="x86_64",
        help="ABI-isolated MariaDB staging root (does not build MariaDB)",
    )
    parser.add_argument(
        "--flavor", default="vanilla", choices=tuple(flavor_layout.FLAVOR_IDS),
        help="game flavor whose ledger to build (default vanilla; the "
             "flavor's pins are verified before any selection)",
    )
    args = parser.parse_args()
    global TARGET_ABI, FLAVOR_ID
    TARGET_ABI = args.abi
    FLAVOR_ID = args.flavor
    layout = flavor_layout.resolve(args.flavor)
    canonical_out = ROOT / layout.migration_ledger()[0]
    try:
        inputs = select_inputs(args.flavor)
        databases = flavor_databases(args.flavor)
        expected = expected_revisions(args.flavor)
    except (flavor_layout.FlavorPinError, RuntimeError) as exc:
        # Unregistered/mismatched pin, or a selection-rule refusal (missing
        # manifest seed, unreadable revision floors, vanishing pinned SQL):
        # exit 2 with the remedy named, BEFORE any staging write.
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    output_root = asset_root(TARGET_ABI, args.flavor)
    if output_root.exists():
        shutil.rmtree(output_root)
    output_root.mkdir(parents=True)

    entries = []
    for index, item in enumerate(inputs, 1):
        data = sql_bytes(item.path)
        source_stem = item.path.stem.removesuffix(".sql")
        safe_stem = re.sub(r"[^A-Za-z0-9._-]+", "_", source_stem).strip("_")
        migration_id = f"{index:04d}-{item.component}-{safe_stem}"
        # AAPT treats the conventional .gz suffix as a request to inflate and
        # rename the asset during merge. Use a neutral extension so the exact
        # deterministic gzip byte stream and its pinned hash survive the APK.
        asset_name = f"{index:04d}.sqlz"
        with output_root.joinpath(asset_name).open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as zipped:
                zipped.write(data)
        entries.append(
            {
                "migration_id": migration_id,
                "component": item.component,
                "database": databases[item.component],
                "source_path": item.path.relative_to(ROOT).as_posix(),
                "asset": f"database/migrations/{asset_name}",
                "compression": "gzip",
                "sql_size": len(data),
                "sql_sha256": sha256(data),
                "asset_sha256": sha256(output_root.joinpath(asset_name).read_bytes()),
            }
        )

    source_commits = {
        "cmangos": git_commit(ROOT / layout.core_submodule),
        "classic_db": git_commit(ROOT / layout.db_submodule),
        "playerbots": git_commit(ROOT / layout.bots_submodule),
    }
    manifest = {
        "schema": 1,
        "app_build_id": "o08-g2-mariadb-v1",
        "ordering": "exact array order; stop realm/world before apply",
        "source_commits": source_commits,
        "expected_revisions": expected,
        "entries": entries,
    }
    encoded = json.dumps(manifest, indent=2) + "\n"
    # write_bytes, not write_text: text mode translates \n to os.linesep on
    # Windows, which would silently rewrite the seal-critical vanilla ledger
    # with CRLF (the committed golden blob is LF — sha 3771fc35…). The
    # ledger bytes must be platform-independent.
    #
    # The write path is routed through the flavor layout, not the hard-coded
    # alias constant: canonical first, and the unqualified alias only when
    # THIS flavor's layout names one (vanilla's database-migrations.json —
    # tbc/wotlk layouts return None). A non-vanilla run can therefore never
    # land ledger bytes on vanilla's seal-critical alias.
    canonical_out.write_bytes(encoded.encode("utf-8"))
    ledger_alias = layout.migration_ledger()[1]
    if ledger_alias is not None:
        (ROOT / ledger_alias).write_bytes(encoded.encode("utf-8"))
    (output_root / "manifest.json").write_bytes(encoded.encode("utf-8"))
    print(f"staged {len(entries)} ordered migrations")
    print(f"ledger:   {canonical_out}" + (f" (alias: {ledger_alias})" if ledger_alias else ""))
    print(f"assets:   {output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
