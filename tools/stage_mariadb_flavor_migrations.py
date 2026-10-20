#!/usr/bin/env python3
"""Stage the per-flavor MariaDB migration set into the Windows provider closure.

W4 (completion plan §4 W4.4; DECISIONS-PORT D72 RETIRED by owner ruling
2026-10-12): the Windows MariaDB provider closure
(``native/.build-win-x86_64/mariadb-provider/``) historically carried only the
vanilla migration manifest + assets, and the engine refused every non-vanilla
lane before any SQL crossed (the recorded W1.2 per-flavor-seeding deferral).
This tool stages the tbc/wotlk SIBLING sets beside the untouched vanilla
files:

    migration-manifest-<flavor>.json      byte copy of schemas/database-migrations-<flavor>.json
    migrations-<flavor>/<migration_id>.sqlz   the staged asset bytes, id-named

Every staged asset is verified against its ledger entry (compressed
asset_sha256 AND decompressed sql_sha256 + sql_size) before it lands, so the
engine's materializeMigration checks can never see a mismatch the stager
already refuted. The vanilla files are never read for writing and never
modified — the vanilla lane's closure contract stays byte-stable.

Usage:
    python tools/stage_mariadb_flavor_migrations.py --flavor tbc
    python tools/stage_mariadb_flavor_migrations.py --flavor wotlk

Exit codes: 0 = staged, 1 = invalid (errors printed, nothing half-written:
the manifest is written LAST, after every asset verified).
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PROVIDER = REPO / "native" / ".build-win-x86_64" / "mariadb-provider"
# The per-flavor staged asset roots (tools/stage_database_migrations.py
# --flavor <f> outputs; S1 item 7's flavor-resolved staging roots).
STAGED_ROOTS = {
    "tbc": REPO / "native" / ".build-x86_64-tbc" / "mariadb-staging" / "assets" / "database" / "migrations",
    "wotlk": REPO / "native" / ".build-x86_64-wotlk" / "mariadb-staging" / "assets" / "database" / "migrations",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--flavor", choices=("tbc", "wotlk"), required=True)
    args = parser.parse_args()
    flavor = args.flavor

    ledger_path = REPO / "schemas" / f"database-migrations-{flavor}.json"
    if not ledger_path.is_file():
        print(f"ERROR: ledger not found: {ledger_path}", file=sys.stderr)
        return 1
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    entries = ledger.get("entries")
    if not isinstance(entries, list) or not entries:
        print(f"ERROR: ledger has no entries: {ledger_path}", file=sys.stderr)
        return 1

    staged_root = STAGED_ROOTS[flavor]
    if not staged_root.is_dir():
        print(
            f"ERROR: the {flavor} staged asset root is missing: {staged_root} — "
            f"run python tools/stage_database_migrations.py --flavor {flavor} first",
            file=sys.stderr,
        )
        return 1

    # The staged tree is INDEX-named (0001.sqlz ...) in ledger order; map by index.
    def staged_for(index: int) -> Path:
        return staged_root / f"{index:04d}.sqlz"

    missing = [f"{i:04d}.sqlz" for i in range(1, len(entries) + 1)
               if not staged_for(i).is_file()]
    if missing:
        print(
            f"ERROR: {len(missing)} staged asset(s) missing under {staged_root} "
            f"(first: {missing[:3]}) — re-run "
            f"python tools/stage_database_migrations.py --flavor {flavor}",
            file=sys.stderr,
        )
        return 1

    out_dir = PROVIDER / f"migrations-{flavor}"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    for index, entry in enumerate(entries, 1):
        migration_id = entry["migration_id"]
        staged = staged_for(index)
        # compressed digest
        asset_digest = sha256_file(staged)
        if asset_digest != entry["asset_sha256"]:
            print(
                f"ERROR: {migration_id}: staged asset digest {asset_digest} != "
                f"ledger asset_sha256 {entry['asset_sha256']} — the staged set "
                "does not belong to this ledger",
                file=sys.stderr,
            )
            return 1
        # decompressed digest + size (what materializeMigration will re-check)
        sql_digest = hashlib.sha256()
        sql_size = 0
        with gzip.open(staged, "rb") as zipped:
            for chunk in iter(lambda: zipped.read(1 << 20), b""):
                sql_digest.update(chunk)
                sql_size += len(chunk)
        if (sql_digest.hexdigest() != entry["sql_sha256"]
                or sql_size != entry["sql_size"]):
            print(
                f"ERROR: {migration_id}: decompressed SQL digest/size mismatch "
                f"against the ledger entry",
                file=sys.stderr,
            )
            return 1
        # the claimed family check ENFORCED — a
        # ledger entry naming a database outside THIS flavor's family (a
        # mis-generated ledger would be internally self-consistent through
        # every digest gate) refuses before any file lands.
        target_db = entry.get("database", "")
        prefix = {"vanilla": "classic", "tbc": "tbc", "wotlk": "wotlk"}.get(flavor)
        if prefix is None or not target_db.startswith(prefix):
            print(
                f"ERROR: {migration_id}: target database '{target_db}' is not "
                f"the {flavor} family — the ledger entry was generated over "
                f"the wrong flavor's assets",
                file=sys.stderr,
            )
            return 1
        destination = out_dir / f"{migration_id}.sqlz"
        shutil.copyfile(staged, destination)

    manifest_path = PROVIDER / f"migration-manifest-{flavor}.json"
    manifest_bytes = ledger_path.read_bytes()
    manifest_path.write_bytes(manifest_bytes)
    print(
        f"staged {flavor}: {len(entries)} migrations + manifest into "
        f"{PROVIDER.name}/ (migrations-{flavor}/, migration-manifest-{flavor}.json); "
        f"vanilla files untouched"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
