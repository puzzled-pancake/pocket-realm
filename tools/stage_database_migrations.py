#!/usr/bin/env python3
"""Build O08's deterministic, hash-verified SQL migration asset set.

The source repos remain the source of truth. This script selects the exact
Classic 1.12.1 inputs, records their pinned commits and SQL hashes in the
reviewable schema manifest, and emits deterministic gzip assets for Android.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import shutil
import subprocess
import argparse
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_OUT = ROOT / "schemas" / "database-migrations.json"
TARGET_ABI = "x86_64"


def asset_root(abi: str) -> Path:
    if abi not in {"x86_64", "arm64-v8a"}:
        raise ValueError(f"unsupported migration ABI: {abi}")
    root_suffix = "x86_64" if abi == "x86_64" else "arm64"
    return (
        ROOT / "native" / f".build-{root_suffix}" / "mariadb-staging" /
        "assets" / "database" / "migrations"
    )
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


def select_inputs() -> list[Input]:
    cmangos = ROOT / "native" / "cmangos"
    classic = ROOT / "native" / "classic-db"
    bots = ROOT / "native" / "playerbots"
    selected = [
        Input("realm", cmangos / "sql/base/realmd.sql"),
        Input("characters", cmangos / "sql/base/characters.sql"),
        Input("logs", cmangos / "sql/base/logs.sql"),
        Input("world", classic / "Full_DB/ClassicDB_1_12_1_z2815.sql.gz"),
    ]
    classic_updates = sorted((classic / "Updates").glob("[0-9]*.sql"))
    selected.extend(Input("world", p) for p in classic_updates)
    selected.extend(
        Input("world", p)
        for p in sorted((classic / "Updates/Instances").glob("[0-9]*.sql"))
    )
    # Classic-DB's numbered updates carry their own CMaNGOS core-sync steps.
    # Find the highest world revision those pinned files already install and
    # append only newer core migrations; replaying z2816..z2829 would fail on
    # the revision column Classic-DB has already advanced.
    integrated_core_revision = max(
        int(match.group(1))
        for path in classic_updates
        for match in re.finditer(r"required_z(\d{4})_[A-Za-z0-9_]+", path.read_text(errors="replace"))
    )
    selected.extend(
        Input("world", p)
        for p in sorted((cmangos / "sql/updates/mangos").glob("z*.sql"))
        if int(p.name[1:5]) > integrated_core_revision
    )
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
        for p in sorted((bots / "sql/world/classic").glob("*.sql"))
    )
    # ClassicDB's full installer applies these mandatory CMaNGOS/content
    # layers after the numbered content/core updates.  In particular,
    # original_data/Spell.sql recreates spell_template with the coefficient
    # columns required by the pinned core; the z2815 snapshot alone only has
    # the older 153-column table.  Keep these additions at the tail so the
    # migration IDs already shipped by O08 remain append-only and an existing
    # app-private database can repair itself without replaying old entries.
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
    selected.append(Input("world", classic / "ACID/acid_classic.sql"))
    selected.append(Input("world", classic / "utilities/cmangos_custom.sql"))
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
    args = parser.parse_args()
    global TARGET_ABI
    TARGET_ABI = args.abi
    output_root = asset_root(TARGET_ABI)
    inputs = select_inputs()
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
                "database": DATABASES[item.component],
                "source_path": item.path.relative_to(ROOT).as_posix(),
                "asset": f"database/migrations/{asset_name}",
                "compression": "gzip",
                "sql_size": len(data),
                "sql_sha256": sha256(data),
                "asset_sha256": sha256(output_root.joinpath(asset_name).read_bytes()),
            }
        )

    manifest = {
        "schema": 1,
        "app_build_id": "o08-g2-mariadb-v1",
        "ordering": "exact array order; stop realm/world before apply",
        "source_commits": {
            "cmangos": git_commit(ROOT / "native/cmangos"),
            "classic_db": git_commit(ROOT / "native/classic-db"),
            "playerbots": git_commit(ROOT / "native/playerbots"),
        },
        "expected_revisions": EXPECTED_REVISIONS,
        "entries": entries,
    }
    encoded = json.dumps(manifest, indent=2) + "\n"
    SCHEMA_OUT.write_text(encoded, encoding="utf-8")
    (output_root / "manifest.json").write_text(encoded, encoding="utf-8")
    print(f"staged {len(entries)} ordered migrations")
    print(f"manifest: {SCHEMA_OUT}")
    print(f"assets:   {output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
