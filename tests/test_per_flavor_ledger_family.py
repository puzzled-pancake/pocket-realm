"""R3-6-1/R2-r6-1 (W4 code-red round 6): the per-flavor migration ledger's
family purity — every entry's target database belongs to ITS flavor's own
family from schemas/flavor.json. A mis-generated ledger (generated over
another flavor's assets) would be internally self-consistent through every
digest gate; the family check is the one gate that catches it, enforced in
the stager (tools/stage_mariadb_flavor_migrations.py) and pinned here
against the committed ledgers."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FAMILY_PREFIX = {"vanilla": "classic", "tbc": "tbc", "wotlk": "wotlk"}


def test_committed_per_flavor_ledgers_are_family_pure():
    flavors = json.loads(
        (ROOT / "schemas/flavor.json").read_text(encoding="utf-8"))
    for flavor in flavors["flavors"]:
        fid = flavor["id"]
        prefix = FAMILY_PREFIX[fid]
        ledger = json.loads(
            (ROOT / f"schemas/database-migrations-{fid}.json")
            .read_text(encoding="utf-8"))
        entries = ledger["entries"]
        assert entries, f"{fid}: the committed ledger is empty"
        for entry in entries:
            assert entry["database"].startswith(prefix), (
                f"{fid}: ledger entry {entry['migration_id']} targets "
                f"{entry['database']} — outside the {prefix}* family")


def test_the_stager_refuses_a_wrong_family_entry(tmp_path, monkeypatch):
    """The stager itself refuses before any file lands: a tbc ledger whose
    entry names wotlkmangos exits 1 naming the family mismatch (the
    reviewer's sandbox attack, pinned against the production module)."""
    import gzip
    import hashlib
    import json as json_lib
    import sys

    sys.path.insert(0, str(ROOT / "tools"))
    import stage_mariadb_flavor_migrations as stager

    provider = tmp_path / "provider"
    staged = tmp_path / "staged"
    (provider / "migrations-tbc").mkdir(parents=True)
    (staged / "0001").mkdir(parents=True)

    # a real gzip asset so the digest legs pass and the FAMILY check is the
    # gate that fires
    sql = b"SELECT 1;"
    gz = gzip.compress(sql)
    (staged / "0001" / "0001.sqlz").write_bytes(gz)

    # the stager reads REPO/schemas/... — point REPO at a tmp repo
    repo = tmp_path / "repo"
    (repo / "schemas").mkdir(parents=True)
    ledger = {
        "schema": 1,
        "entries": [{
            "migration_id": "0001-realm-realmd",
            "database": "wotlkmangos",
            "source_path": "x",
            "asset": "database/migrations/0001.sqlz",
            "compression": "gzip",
            "sql_size": len(sql),
            "sql_sha256": hashlib.sha256(sql).hexdigest(),
            "asset_sha256": hashlib.sha256(gz).hexdigest(),
        }],
    }
    ledger_path = repo / "schemas" / "database-migrations-tbc.json"
    ledger_path.write_text(json_lib.dumps(ledger), encoding="utf-8")
    monkeypatch.setattr(stager, "REPO", repo)
    monkeypatch.setattr(stager, "PROVIDER", provider)
    monkeypatch.setattr(stager, "STAGED_ROOTS", {"tbc": staged})

    monkeypatch.setattr(
        sys, "argv",
        [stager.__name__, "--flavor", "tbc"],
    )
    rc = stager.main()
    assert rc == 1, (
        f"the stager accepted a wrong-family ledger entry (rc={rc}) — the "
        "family check is absent (R3-6-1)")
